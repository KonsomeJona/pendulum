package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader
import com.pendulum.format.ChunkWriter
import com.pendulum.format.wire.WireProtocol
import com.pendulum.wear.temps.Durees
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * Nommage, rotation et durabilite des fichiers de chunk d'une session.
 *
 * Un chunk est ferme des que `elapsed >= 300 s` **ou** que l'ecriture du bloc suivant ferait
 * depasser 92 160 octets. Les deux conditions sont necessaires : la duree borne ce qu'on perd
 * si la montre meurt, le plafond d'octets garantit que le fichier tient dans la charge utile de
 * 100 Ko d'un `DataItem` meme si `fs` reel derive ou si un mode degrade change la cadence.
 *
 * **Sur le banc, les deux conditions ne courent plus a la meme vitesse.** La duree se comprime,
 * le volume non — c'est le choix explique dans `Temps`. La consequence est chiffree dans la KDoc
 * de [writeBlock], parce que c'est la que la course se decide.
 *
 * Le fichier porte son nom definitif des l'ouverture : c'est le **marqueur de fin** qui
 * distingue un chunk complet d'un chunk en cours, pas son extension. Un `.part` renomme a la
 * fermeture serait une seconde source de verite, qui divergerait le jour ou le processus meurt
 * entre le `finish()` et le `rename()`.
 */
class ChunkStore(
    private val sessionDir: File,
    private val sessionUuid: ByteArray,
    private val sensorResolution: Float,
    private val sensorMaxRange: Float,
    private val fifoReserved: Int,
    startIndex: Int,
    private var rateHz: Int,
    private var modeFlags: Int,
    /** Borne de duree de la rotation. Parametre plutot que constante lue au fond de [writeBlock] :
     *  c'est ce qui rend la rotation testable a une echelle choisie, sans horloge a bousculer. */
    private val rotationMs: Long = Durees.ACTIVES.rotationChunkMs,
) {

    /** Index du prochain chunk a ouvrir. Continue la numerotation apres une reprise : jamais
     *  reinitialise, l'unicite `(sessionId, idx)` cote telephone en depend. */
    var nextIndex: Int = startIndex
        private set

    private var writer: ChunkWriter? = null
    private var out: FileOutputStream? = null
    private var buffered: BufferedOutputStream? = null
    private var openedAtMs = 0L
    private var openIndex = -1

    /** Octets et echantillons du chunk courant, pour l'affichage et le sidecar. */
    var totalSamples: Long = 0
        private set
    var totalBytes: Long = 0
        private set

    init {
        sessionDir.mkdirs()
    }

    fun chunkFile(idx: Int): File = File(sessionDir, "%05d.pendulum".format(idx))

    /**
     * Ecrit un bloc, en ouvrant ou en faisant tourner le chunk si necessaire.
     *
     * ### Laquelle des deux conditions ferme le chunk, et ce que le banc en change
     *
     * En marche reelle les deux sont a egalite, a un pour cent pres : 50 Hz x 6 octets font
     * environ 303 o/s une fois les entetes de bloc comptes, donc les 92 160 octets sont atteints
     * apres a peu pres 304 s — juste **apres** les 300 s de la borne de duree. C'est la duree qui
     * ferme, d'un cheveu, et les chunks sortent remplis a ~99 % du plafond. Le plafond d'octets ne
     * gagne que dans les cas degrades, qui sont exactement ceux pour lesquels il existe.
     *
     * Sur le banc, deux accelerations independantes se superposent :
     *
     *  - le **rejeu** avance de 250 s de temps capteur par seconde de temps mural, et ce facteur
     *    n'est pas libre — `SourceSynthetique` le derive de la taille de salve et de
     *    `SensorPipeline.FLUSH_GAP_NS`. Les octets s'accumulent donc 250 fois plus vite ;
     *  - la **borne de duree**, elle, est divisee par `EchelleTemps.DIVISEUR`.
     *
     * L'egalite d'origine n'est preservee que si ces deux facteurs sont **le meme nombre**. A 250,
     * la borne tombe a 1 200 ms de temps mural et le plafond d'octets est atteint vers 1 216 ms :
     * meme cheveu, memes chunks, memes octets — c'est le comportement reel, joue plus vite. A 600,
     * la borne tombe a 500 ms alors qu'il faut toujours 1 216 ms pour remplir le chunk : la duree
     * gagne largement, les chunks sortent a ~40 % du plafond, et le banc **cesse d'exercer** ce
     * pour quoi le plafond existe — la tenue des tampons memoire et le passage sous les 100 Ko
     * d'un `DataItem`.
     *
     * C'est pourquoi `CoherenceEchelleTest` refuse tout diviseur qui ne soit pas l'acceleration du
     * rejeu. La regle en une phrase : **comprimer le temps mural exactement autant que le rejeu
     * comprime le temps capteur, sinon on ne teste plus la meme rotation.**
     *
     * @return l'index du chunk ferme par cette ecriture, ou `null` si aucune rotation n'a eu lieu.
     */
    fun writeBlock(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        count: Int,
        tFirstNs: Long,
        tLastNs: Long,
        flags: Int,
        nowMs: Long,
    ): Int? {
        var closed: Int? = null
        val w = writer
        if (w != null) {
            val blockBytes = blockBytes(count)
            if (nowMs - openedAtMs >= rotationMs ||
                // Jamais mis a l'echelle. Voir la KDoc ci-dessus, et celle de `Temps`.
                w.bytesWritten + blockBytes > WireProtocol.CHUNK_ROTATION_BYTES
            ) {
                closed = close()
            }
        }
        if (writer == null) open(tFirstNs, nowMs)
        writer!!.writeBlock(x, y, z, count, tFirstNs, tLastNs, flags)
        totalSamples += count
        totalBytes += blockBytes(count)
        return closed
    }

    private fun blockBytes(count: Int): Long =
        ChunkFormat.BLOCK_HEADER_SIZE + count.toLong() * ChunkFormat.BYTES_PER_SAMPLE

    /**
     * Force une rotation, sans ecrire de bloc. Utilise a chaque changement de mode : `modeFlags`
     * et `nominalRateHz` vivent dans l'entete de fichier et ne sont jamais reecrits.
     */
    fun rotate(rateHz: Int = this.rateHz, modeFlags: Int = this.modeFlags): Int? {
        val closed = close()
        this.rateHz = rateHz
        this.modeFlags = modeFlags
        return closed
    }

    /**
     * `fsync` du chunk courant. Sans effet si aucun chunk n'est ouvert.
     *
     * Appele toutes les dix secondes *de temps eveille* : le tick est un `Handler` sur l'horloge
     * d'uptime, qui ne s'ecoule pas pendant la suspension du SoC. Le `fsync` tombe donc
     * naturellement juste apres chaque vidage du FIFO, et ne provoque **aucun reveil a lui seul**.
     */
    fun sync() {
        val fos = out ?: return
        buffered?.flush()
        fos.fd.sync()
    }

    /** Ferme le chunk courant en ecrivant son marqueur de fin, puis le `fsync`. */
    fun close(): Int? {
        val w = writer ?: return null
        w.finish()
        buffered?.flush()
        out?.let {
            it.fd.sync()
            it.close()
        }
        totalBytes += ChunkFormat.FOOTER_SIZE
        writer = null
        out = null
        buffered = null
        val idx = openIndex
        openIndex = -1
        return idx
    }

    private fun open(firstEventTsNs: Long, nowMs: Long) {
        val idx = nextIndex
        val file = chunkFile(idx)
        val fos = FileOutputStream(file)
        val header = ChunkHeader(
            sessionUuid = sessionUuid,
            chunkIndex = idx,
            nominalRateHz = rateHz,
            startWallMs = System.currentTimeMillis(),
            startElapsedRealtimeNs = android.os.SystemClock.elapsedRealtimeNanos(),
            firstEventTimestampNs = firstEventTsNs,
            sensorResolution = sensorResolution,
            sensorMaxRange = sensorMaxRange,
            // On y ecrit `fifoReservedEventCount`, pas `fifoMaxEventCount` : c'est la part
            // garantie, la seule qui explique le comportement observe a la relecture.
            fifoMaxEventCount = fifoReserved.coerceIn(0, 0xFFFF),
            modeFlags = modeFlags,
            tzOffsetMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000,
        )
        // L'ecriture de l'entete a lieu dans le constructeur du writer : le fichier est valide
        // des la premiere milliseconde.
        val bos = BufferedOutputStream(fos, 8 * 1024)
        writer = ChunkWriter(bos, header)
        buffered = bos
        out = fos
        openedAtMs = nowMs
        openIndex = idx
        nextIndex = idx + 1
        totalBytes += ChunkFormat.HEADER_SIZE
        // L'entete est encore dans le tampon a ce stade : la vider avant le `fsync`, sinon le
        // fichier existe sur le disque mais vide, et une coupure ici laisse un fichier sans magic.
        bos.flush()
        fos.fd.sync()
    }
}
