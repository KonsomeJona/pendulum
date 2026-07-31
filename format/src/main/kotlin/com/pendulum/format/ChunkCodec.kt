package com.pendulum.format

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ecriture d'un fichier de chunk. Pur JVM : le module `wear` fournit l'[OutputStream]
 * et garde la main sur le `FileDescriptor` pour les `fsync`.
 *
 * Aucune methode ne reecrit en arriere : le fichier est valide a tout instant. [finish]
 * n'ajoute qu'un marqueur de fin en queue, il ne patche rien.
 *
 * Le layout octet par octet est documente dans la KDoc de [ChunkFormat].
 */
class ChunkWriter(
    private val out: OutputStream,
    private val header: ChunkHeader,
) {
    private val blockHeader = ByteArray(ChunkFormat.BLOCK_HEADER_SIZE)
    private val payload = ByteArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK * ChunkFormat.BYTES_PER_SAMPLE)

    var bytesWritten: Long = 0
        private set

    var samplesWritten: Long = 0
        private set

    var blocksWritten: Int = 0
        private set

    /**
     * Nombre d'echantillons ecretes a +/-32767 LSB (F-11). Un compte non nul sur une nuit
     * signifie que le signal a touche le plafond du format : les pics sont sous-estimes et
     * toute amplitude derivee de ces blocs est fausse.
     */
    var saturatedSamples: Long = 0
        private set

    /**
     * Nombre d'echantillons NaN/infinis remplaces par 0 (F-11). Un zero de capteur en defaut
     * est indiscernable d'une chute libre : sans ce compteur, le defaut se lit comme un mouvement.
     */
    var nonFiniteSamples: Long = 0
        private set

    /** Vrai une fois le marqueur de fin ecrit : plus aucun bloc n'est accepte. */
    var finished: Boolean = false
        private set

    private var lastTimestampNs: Long = 0

    init {
        require(header.headerSize == ChunkFormat.HEADER_SIZE) {
            "cette version n'ecrit que des entetes de ${ChunkFormat.HEADER_SIZE} octets"
        }
        require(header.formatVersion == ChunkFormat.FORMAT_VERSION) {
            "cette version n'ecrit que le format v${ChunkFormat.FORMAT_VERSION}"
        }
        val h = ByteBuffer.allocate(ChunkFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.put(ChunkFormat.FILE_MAGIC)                       // 8  -> 8
        h.putShort(header.formatVersion.toShort())          // 2  -> 10
        h.putShort(ChunkFormat.HEADER_SIZE.toShort())       // 2  -> 12
        h.putShort(header.nominalRateHz.toShort())          // 2  -> 14
        h.putShort(header.fifoMaxEventCount.toShort())      // 2  -> 16
        h.put(header.sessionUuid)                           // 16 -> 32
        h.putLong(header.startWallMs)                       // 8  -> 40
        h.putLong(header.startElapsedRealtimeNs)            // 8  -> 48
        h.putLong(header.firstEventTimestampNs)             // 8  -> 56
        h.putFloat(header.sensorResolution)                 // 4  -> 60
        h.putFloat(header.sensorMaxRange)                   // 4  -> 64
        h.putInt(header.chunkIndex)                         // 4  -> 68
        h.putShort(header.modeFlags.toShort())              // 2  -> 70
        h.putShort(header.tzOffsetMin.toShort())            // 2  -> 72
        // 6 octets reserves, laisses a zero                        -> 78
        val bytes = h.array()
        // Le CRC d'entete (F-08) est le dernier champ : un octet corrompu dans startWallMs
        // datait sinon toute la nuit faux, sans la moindre detection.
        val crc = ChunkFormat.crc16(bytes, 0, ChunkFormat.HEADER_SIZE - 2)
        putShortLe(bytes, ChunkFormat.HEADER_SIZE - 2, crc.toShort())
        out.write(bytes)
        bytesWritten += ChunkFormat.HEADER_SIZE
    }

    /**
     * Ecrit un bloc. Les tableaux sont lus sur `[0, count)`.
     *
     * Le bloc doit provenir d'**un seul vidage du FIFO** (F-03) : `(tLast - tFirst)/(count - 1)`
     * doit rester a [ChunkFormat.TIMEBASE_TOLERANCE] pres de la periode nominale. Un bloc a
     * cheval sur deux vidages contient un trou que l'interpolation lineaire etale sur tous ses
     * echantillons, et le producteur est le seul a pouvoir couper au bon endroit — d'ou un
     * `require` et non un drapeau.
     *
     * @param count nombre d'echantillons, dans `1..MAX_SAMPLES_PER_BLOCK`.
     */
    fun writeBlock(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        count: Int,
        tFirstNs: Long,
        tLastNs: Long,
        flags: Int,
    ) {
        check(!finished) { "le chunk est clos, plus aucun bloc ne peut y etre ajoute" }
        require(count in 1..ChunkFormat.MAX_SAMPLES_PER_BLOCK) {
            "count hors bornes : $count"
        }
        require(x.size >= count && y.size >= count && z.size >= count) {
            "tableaux trop courts pour count=$count"
        }
        require(flags in 0..0xFFFF) { "flags hors u16 : $flags" }
        require(tLastNs >= tFirstNs) {
            "tLastNs < tFirstNs ($tLastNs < $tFirstNs) : base de temps inversee"
        }
        require(count == 1 || tLastNs > tFirstNs) {
            "bloc de $count echantillons de duree nulle"
        }
        require(ChunkFormat.isTimebasePlausible(count, tFirstNs, tLastNs, header.nominalRateHz)) {
            "cadence implicite de ${ChunkFormat.meanIntervalNs(count, tFirstNs, tLastNs)} ns/echantillon " +
                "incompatible avec ${header.nominalRateHz} Hz : le bloc chevauche probablement deux vidages du FIFO"
        }

        var saturated = false
        var nonFinite = false
        var p = 0
        for (i in 0 until count) {
            val rx = ChunkFormat.toRaw(x[i])
            val ry = ChunkFormat.toRaw(y[i])
            val rz = ChunkFormat.toRaw(z[i])
            // Compte par echantillon (le triplet), pas par axe : c'est l'echantillon qui est
            // inutilisable des qu'un de ses axes a ete ecrete ou remplace.
            if (!x[i].isFinite() || !y[i].isFinite() || !z[i].isFinite()) {
                nonFiniteSamples++
                nonFinite = true
            } else if (isSaturated(rx) || isSaturated(ry) || isSaturated(rz)) {
                saturatedSamples++
                saturated = true
            }
            putShortLe(payload, p, rx); p += 2
            putShortLe(payload, p, ry); p += 2
            putShortLe(payload, p, rz); p += 2
        }
        val payloadLen = count * ChunkFormat.BYTES_PER_SAMPLE

        val effectiveFlags = flags or
            (if (saturated) ChunkFormat.FLAG_SATURATED else 0) or
            (if (nonFinite) ChunkFormat.FLAG_NON_FINITE else 0)

        val b = ByteBuffer.wrap(blockHeader).order(ByteOrder.LITTLE_ENDIAN)
        b.clear()
        b.put(ChunkFormat.BLOCK_MAGIC)          // 4  -> 4
        b.putShort(count.toShort())             // 2  -> 6
        b.putLong(tFirstNs)                     // 8  -> 14
        b.putLong(tLastNs)                      // 8  -> 22
        b.putShort(effectiveFlags.toShort())    // 2  -> 24
        // Le CRC couvre l'entete de bloc puis le payload (F-02), d'ou le chainage du seed.
        val crc = ChunkFormat.crc16(
            payload, 0, payloadLen,
            seed = ChunkFormat.crc16(blockHeader, 0, ChunkFormat.BLOCK_CRC_OFFSET),
        )
        b.putShort(crc.toShort())               // 2  -> 26
        java.util.Arrays.fill(blockHeader, 26, ChunkFormat.BLOCK_HEADER_SIZE, 0)

        out.write(blockHeader)
        out.write(payload, 0, payloadLen)
        // Flush a chaque bloc : le cout est negligeable (un bloc toutes les ~10 s a 50 Hz)
        // et cela borne la perte a un seul bloc en cas de kill brutal.
        out.flush()

        bytesWritten += ChunkFormat.BLOCK_HEADER_SIZE + payloadLen
        samplesWritten += count
        blocksWritten++
        lastTimestampNs = tLastNs
    }

    /**
     * Ecrit le marqueur de fin de fichier (F-37) et vide le flux. Idempotent.
     *
     * Sans ce marqueur, un chunk en cours d'ecriture est indiscernable d'un chunk complet :
     * le telephone l'acquitte et la montre supprime un fichier partiel (F-13). N'appeler
     * qu'apres la rotation, jamais sur le chunk courant.
     */
    fun finish() {
        if (finished) return
        val f = ByteBuffer.allocate(ChunkFormat.FOOTER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        f.put(ChunkFormat.FILE_FOOTER_MAGIC)    // 8  -> 8
        f.putInt(blocksWritten)                 // 4  -> 12
        f.putLong(samplesWritten)               // 8  -> 20
        f.putLong(lastTimestampNs)              // 8  -> 28
        // 2 octets reserves                            -> 30
        val bytes = f.array()
        val crc = ChunkFormat.crc16(bytes, 0, ChunkFormat.FOOTER_SIZE - 2)
        putShortLe(bytes, ChunkFormat.FOOTER_SIZE - 2, crc.toShort())
        out.write(bytes)
        out.flush()
        bytesWritten += ChunkFormat.FOOTER_SIZE
        finished = true
    }

    /**
     * Une valeur ecretee est indiscernable d'une valeur qui tombe pile sur la borne ; a
     * 15,9995 g cette confusion n'a jamais lieu sur une cheville, on l'accepte.
     */
    private fun isSaturated(raw: Short): Boolean =
        raw == Short.MAX_VALUE || raw == Short.MIN_VALUE

    private fun putShortLe(buf: ByteArray, offset: Int, v: Short) {
        buf[offset] = (v.toInt() and 0xFF).toByte()
        buf[offset + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }
}

/** Raison pour laquelle une plage d'octets a ete rejetee a la relecture. */
enum class DamageReason {
    /** Magic de bloc absent la ou il etait attendu : desynchronisation franche. */
    BAD_MAGIC,

    /** `count` hors bornes : la longueur de payload aurait ete lue fausse. */
    BAD_COUNT,

    /** CRC invalide : entete de bloc ou payload corrompu. */
    BAD_CRC,

    /** CRC valide mais `tLastNs < tFirstNs` : structurellement impossible (F-14). */
    INVALID_TIMEBASE,

    /** Fin de fichier au milieu d'un bloc : cas benin d'un kill brutal (F-12). */
    TRUNCATED_TAIL,
}

/**
 * Zone illisible d'un fichier de chunk, localisee dans le fichier **et** dans le temps (F-35).
 * C'est ce qui permet de transformer « 3 blocs perdus » en « 30 s manquantes a 3h12 ».
 *
 * @param fileOffset offset absolu, dans le fichier, du premier octet rejete.
 * @param byteLength nombre d'octets sautes.
 * @param afterTimestampNs `tLastNs` du dernier bloc valide *avant* la zone, `null` s'il n'y en a pas.
 * @param beforeTimestampNs `tFirstNs` du premier bloc valide *apres* la zone, `null` si le
 *   fichier se termine dans la zone.
 */
data class DamagedRange(
    val reason: DamageReason,
    val fileOffset: Long,
    val byteLength: Long,
    val afterTimestampNs: Long?,
    val beforeTimestampNs: Long?,
) {
    /** Duree de signal manquante, ou `null` si l'une des deux bornes temporelles est inconnue. */
    val missingDurationNs: Long?
        get() = if (afterTimestampNs != null && beforeTimestampNs != null) {
            beforeTimestampNs - afterTimestampNs
        } else {
            null
        }
}

/**
 * Bilan de la relecture d'un fichier de chunk, sans les echantillons : c'est le resultat de
 * l'API streaming [ChunkReader.forEachBlock], et le meme objet est porte par [ChunkFile].
 *
 * @param complete vrai si le marqueur de fin de fichier a ete lu et son CRC verifie. Un chunk
 *   incomplet ne doit jamais etre acquitte par le telephone (F-13/F-37).
 * @param truncatedTail vrai si le fichier se termine au milieu d'un bloc — cas **benin** d'un
 *   kill brutal, a distinguer de [desynchronised] (F-12).
 * @param desynchronised vrai si une zone illisible a ete rencontree ailleurs qu'en queue :
 *   la ou `truncatedTail` coute au pire un bloc, ceci signale une corruption en plein fichier.
 * @param declaredBlockCount nombre de blocs annonce par le marqueur de fin, `null` si absent.
 */
class ChunkScanResult(
    val header: ChunkHeader,
    val blockCount: Int,
    val decodedSampleCount: Long,
    val corruptBlocks: Int,
    val suspectTimebaseBlocks: Int,
    val resyncSkippedBytes: Long,
    val damagedRanges: List<DamagedRange>,
    val complete: Boolean,
    val truncatedTail: Boolean,
    val desynchronised: Boolean,
    val declaredBlockCount: Int?,
    val declaredSampleCount: Long?,
) {
    /** Blocs annonces par le marqueur de fin mais absents a la relecture. `null` sans marqueur. */
    val lostBlocks: Int? get() = declaredBlockCount?.let { it - blockCount }

    /** Duree totale de signal manquante, sur les seules zones dont les deux bornes sont connues. */
    val missingDurationNs: Long get() = damagedRanges.sumOf { it.missingDurationNs ?: 0L }
}

/**
 * Resultat de la lecture *materialisee* d'un fichier de chunk. Pratique pour les tests et pour
 * les fichiers courts ; pour une nuit entiere, preferer [ChunkReader.forEachBlock] (F-27).
 */
class ChunkFile(
    val scan: ChunkScanResult,
    val blocks: List<DecodedBlock>,
) {
    val header: ChunkHeader get() = scan.header

    /** Blocs rejetes pour CRC invalide, magic absent, `count` aberrant ou base de temps impossible. */
    val corruptBlocks: Int get() = scan.corruptBlocks

    /** Vrai si le fichier se termine par un bloc tronque (cas normal d'un kill brutal). */
    val truncatedTail: Boolean get() = scan.truncatedTail

    val sampleCount: Int get() = blocks.sumOf { it.sampleCount }
}

/**
 * Lecture tolerante aux pannes d'un fichier de chunk.
 *
 * Un bloc dont le CRC ne correspond pas, dont le magic est absent, dont le `count` est
 * aberrant ou dont la base de temps est impossible est **saute**, et le lecteur se
 * **resynchronise** en cherchant le prochain `BLK!` (F-02) : sauter un bloc « en gardant la
 * synchro » n'a aucun sens quand c'est justement `count` qui peut etre corrompu, puisque la
 * longueur de payload lue est alors fausse. Chaque zone sautee est localisee dans
 * [ChunkScanResult.damagedRanges].
 *
 * La lecture ne leve que si l'entete du fichier elle-meme est invalide : le reste du fichier
 * est toujours exploite au mieux.
 */
object ChunkReader {

    /** Garde-fou : au-dela, `headerSize` est manifestement une valeur corrompue. */
    private const val MAX_HEADER_SIZE = 4096

    private const val MAX_BLOCK_SIZE =
        ChunkFormat.BLOCK_HEADER_SIZE + ChunkFormat.MAX_SAMPLES_PER_BLOCK * ChunkFormat.BYTES_PER_SAMPLE

    /**
     * Le tampon doit pouvoir contenir le plus grand bloc et la plus grande entete : c'est ce
     * qui borne le retour arriere de la resynchronisation, et donc la memoire du lecteur.
     */
    private const val BUFFER_SIZE = 2 * MAX_BLOCK_SIZE

    /**
     * Lecture streaming : chaque bloc decode est passe a [onBlock] puis oublie. C'est l'API a
     * utiliser sur une nuit entiere — la version materialisee garde ~17 Mo utiles et plusieurs
     * milliers de tableaux vivants pour un algorithme qui, lui, est streaming (F-27).
     *
     * Le [DecodedBlock] passe a [onBlock] n'est pas reutilise : l'appelant peut le conserver
     * s'il le souhaite, mais c'est alors sa consommation memoire, pas celle du lecteur.
     */
    fun forEachBlock(input: InputStream, onBlock: (DecodedBlock) -> Unit): ChunkScanResult {
        val sc = ByteScanner(input, BUFFER_SIZE)
        val header = readHeader(sc)

        val blockHeader = ByteArray(ChunkFormat.BLOCK_HEADER_SIZE)
        val payload = ByteArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK * ChunkFormat.BYTES_PER_SAMPLE)
        val damaged = ArrayList<DamagedRange>()

        var blockCount = 0
        var sampleCount = 0L
        var corrupt = 0
        var suspect = 0
        var skipped = 0L
        var complete = false
        var truncated = false
        var lastValidTLast: Long? = null
        var openDamageIdx = -1
        var declaredBlocks: Int? = null
        var declaredSamples: Long? = null

        /** Enregistre une zone perdue et resynchronise sur le prochain magic. */
        fun damageAndResync(reason: DamageReason) {
            val from = sc.offset
            val length = resync(sc)
            skipped += length
            damaged.add(DamagedRange(reason, from, length, lastValidTLast, null))
            openDamageIdx = damaged.size - 1
        }

        /** Enregistre une queue tronquee : le reste du fichier tient dans la zone perdue. */
        fun damageTail(reason: DamageReason) {
            val from = sc.offset
            val length = sc.drain()
            truncated = true
            damaged.add(DamagedRange(reason, from, length, lastValidTLast, null))
            openDamageIdx = -1
        }

        while (true) {
            if (!sc.ensure(4)) {
                // Moins de 4 octets restants : reliquat inexploitable, donc queue tronquee.
                if (sc.available > 0) damageTail(DamageReason.TRUNCATED_TAIL)
                break
            }

            if (sc.startsWith(ChunkFormat.FILE_FOOTER_MAGIC, 4)) {
                if (!sc.ensure(ChunkFormat.FOOTER_SIZE) ||
                    !sc.startsWith(ChunkFormat.FILE_FOOTER_MAGIC, ChunkFormat.FILE_FOOTER_MAGIC.size)
                ) {
                    damageTail(DamageReason.TRUNCATED_TAIL)
                    break
                }
                val footer = ByteArray(ChunkFormat.FOOTER_SIZE)
                sc.copyOut(footer, 0, ChunkFormat.FOOTER_SIZE)
                val f = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
                f.position(8)
                val fBlocks = f.int
                val fSamples = f.long
                f.position(ChunkFormat.FOOTER_SIZE - 2)
                val fCrc = f.short.toInt() and 0xFFFF
                if (ChunkFormat.crc16(footer, 0, ChunkFormat.FOOTER_SIZE - 2) == fCrc) {
                    complete = true
                    declaredBlocks = fBlocks
                    declaredSamples = fSamples
                    sc.skip(ChunkFormat.FOOTER_SIZE)
                } else {
                    damageAndResync(DamageReason.BAD_CRC)
                    corrupt++
                    continue
                }
                break
            }

            if (!sc.startsWith(ChunkFormat.BLOCK_MAGIC, ChunkFormat.BLOCK_MAGIC.size)) {
                corrupt++
                damageAndResync(DamageReason.BAD_MAGIC)
                continue
            }

            if (!sc.ensure(ChunkFormat.BLOCK_HEADER_SIZE)) {
                damageTail(DamageReason.TRUNCATED_TAIL)
                break
            }
            sc.copyOut(blockHeader, 0, ChunkFormat.BLOCK_HEADER_SIZE)
            val b = ByteBuffer.wrap(blockHeader).order(ByteOrder.LITTLE_ENDIAN)
            b.position(4)
            val count = b.short.toInt() and 0xFFFF
            val tFirst = b.long
            val tLast = b.long
            val flags = b.short.toInt() and 0xFFFF
            val expectedCrc = b.short.toInt() and 0xFFFF

            if (count < 1 || count > ChunkFormat.MAX_SAMPLES_PER_BLOCK) {
                corrupt++
                damageAndResync(DamageReason.BAD_COUNT)
                continue
            }

            val payloadLen = count * ChunkFormat.BYTES_PER_SAMPLE
            if (!sc.ensure(ChunkFormat.BLOCK_HEADER_SIZE + payloadLen)) {
                damageTail(DamageReason.TRUNCATED_TAIL)
                break
            }
            sc.copyOut(payload, ChunkFormat.BLOCK_HEADER_SIZE, payloadLen)
            val computedCrc = ChunkFormat.crc16(
                payload, 0, payloadLen,
                seed = ChunkFormat.crc16(blockHeader, 0, ChunkFormat.BLOCK_CRC_OFFSET),
            )
            if (computedCrc != expectedCrc) {
                corrupt++
                damageAndResync(DamageReason.BAD_CRC)
                continue
            }

            // CRC valide mais chronologie impossible : le bloc est structurellement faux, pas
            // seulement douteux, on le rejette (F-14). La cadence aberrante, elle, est signalee
            // et non rejetee : le signal reste utilisable pour tout ce qui ne date pas.
            if (tLast < tFirst) {
                corrupt++
                sc.skip(ChunkFormat.BLOCK_HEADER_SIZE + payloadLen)
                damaged.add(
                    DamagedRange(
                        DamageReason.INVALID_TIMEBASE,
                        sc.offset - ChunkFormat.BLOCK_HEADER_SIZE - payloadLen,
                        (ChunkFormat.BLOCK_HEADER_SIZE + payloadLen).toLong(),
                        lastValidTLast,
                        null,
                    )
                )
                openDamageIdx = damaged.size - 1
                continue
            }

            val suspectTimebase =
                !ChunkFormat.isTimebasePlausible(count, tFirst, tLast, header.nominalRateHz)
            if (suspectTimebase) suspect++

            val x = FloatArray(count)
            val y = FloatArray(count)
            val z = FloatArray(count)
            var p = 0
            for (i in 0 until count) {
                x[i] = ChunkFormat.toMs2(getShortLe(payload, p)); p += 2
                y[i] = ChunkFormat.toMs2(getShortLe(payload, p)); p += 2
                z[i] = ChunkFormat.toMs2(getShortLe(payload, p)); p += 2
            }
            sc.skip(ChunkFormat.BLOCK_HEADER_SIZE + payloadLen)

            if (openDamageIdx >= 0) {
                damaged[openDamageIdx] = damaged[openDamageIdx].copy(beforeTimestampNs = tFirst)
                openDamageIdx = -1
            }
            blockCount++
            sampleCount += count
            lastValidTLast = tLast
            onBlock(DecodedBlock(tFirst, tLast, flags, x, y, z, suspectTimebase))
        }

        return ChunkScanResult(
            header = header,
            blockCount = blockCount,
            decodedSampleCount = sampleCount,
            corruptBlocks = corrupt,
            suspectTimebaseBlocks = suspect,
            resyncSkippedBytes = skipped,
            damagedRanges = damaged,
            complete = complete,
            truncatedTail = truncated,
            desynchronised = damaged.any { it.reason != DamageReason.TRUNCATED_TAIL },
            declaredBlockCount = declaredBlocks,
            declaredSampleCount = declaredSamples,
        )
    }

    /** Lecture materialisee. Voir [forEachBlock] pour l'API a utiliser sur une nuit entiere. */
    fun read(input: InputStream): ChunkFile {
        val blocks = ArrayList<DecodedBlock>()
        val scan = forEachBlock(input) { blocks.add(it) }
        return ChunkFile(scan, blocks)
    }

    private fun readHeader(sc: ByteScanner): ChunkHeader {
        if (!sc.ensure(ChunkFormat.HEADER_PREFIX_SIZE)) throw EOFException("entete de fichier tronquee")
        val prefix = ByteArray(ChunkFormat.HEADER_PREFIX_SIZE)
        sc.copyOut(prefix, 0, ChunkFormat.HEADER_PREFIX_SIZE)
        val magic = prefix.copyOf(8)
        if (!magic.contentEquals(ChunkFormat.FILE_MAGIC)) {
            throw IOException("magic de fichier invalide : ${magic.toString(Charsets.US_ASCII)}")
        }
        val formatVersion = getShortLe(prefix, 8).toInt() and 0xFFFF
        if (formatVersion < 1 || formatVersion > ChunkFormat.FORMAT_VERSION) {
            throw IOException("version de format non geree : $formatVersion")
        }
        val headerSize = getShortLe(prefix, 10).toInt() and 0xFFFF
        // headerSize peut depasser HEADER_SIZE : une version ulterieure a ajoute des champs en
        // queue sans changer le layout des blocs, on lit ce qu'on connait et on ignore le reste.
        if (headerSize < ChunkFormat.HEADER_SIZE || headerSize > MAX_HEADER_SIZE) {
            throw IOException("headerSize invalide : $headerSize")
        }
        if (!sc.ensure(headerSize)) throw EOFException("entete de fichier tronquee")

        val hb = ByteArray(headerSize)
        sc.copyOut(hb, 0, headerSize)
        val storedCrc = getShortLe(hb, headerSize - 2).toInt() and 0xFFFF
        if (ChunkFormat.crc16(hb, 0, headerSize - 2) != storedCrc) {
            throw IOException("CRC d'entete de fichier invalide")
        }

        val h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        h.position(12)
        val nominalRateHz = h.short.toInt() and 0xFFFF
        val fifoMaxEventCount = h.short.toInt() and 0xFFFF
        val uuid = ByteArray(16).also { h.get(it) }
        val startWallMs = h.long
        val startElapsedRealtimeNs = h.long
        val firstEventTimestampNs = h.long
        val resolution = h.float
        val maxRange = h.float
        val chunkIndex = h.int
        val modeFlags = h.short.toInt() and 0xFFFF
        val tzOffsetMin = h.short.toInt()

        sc.skip(headerSize)
        return ChunkHeader(
            sessionUuid = uuid,
            chunkIndex = chunkIndex,
            nominalRateHz = nominalRateHz,
            startWallMs = startWallMs,
            startElapsedRealtimeNs = startElapsedRealtimeNs,
            firstEventTimestampNs = firstEventTimestampNs,
            sensorResolution = resolution,
            sensorMaxRange = maxRange,
            fifoMaxEventCount = fifoMaxEventCount,
            modeFlags = modeFlags,
            tzOffsetMin = tzOffsetMin,
            formatVersion = formatVersion,
            headerSize = headerSize,
        )
    }

    /**
     * Avance jusqu'au prochain magic de bloc ou de fin de fichier. Renvoie le nombre d'octets
     * sautes, curseur positionne sur le magic trouve (ou sur la fin du flux).
     */
    private fun resync(sc: ByteScanner): Long {
        val from = sc.offset
        sc.skip(1)
        while (sc.ensure(4)) {
            if (sc.startsWith(ChunkFormat.BLOCK_MAGIC, 4) ||
                sc.startsWith(ChunkFormat.FILE_FOOTER_MAGIC, 4)
            ) {
                break
            }
            sc.skip(1)
        }
        if (sc.available < 4) sc.drain()
        return sc.offset - from
    }

    private fun getShortLe(buf: ByteArray, offset: Int): Short =
        (((buf[offset + 1].toInt() and 0xFF) shl 8) or (buf[offset].toInt() and 0xFF)).toShort()
}

/**
 * Fenetre glissante sur un [InputStream], avec retour arriere borne : c'est ce qui rend la
 * resynchronisation possible sans materialiser le fichier. La capacite doit depasser le plus
 * grand bloc, sans quoi un bloc valide pourrait ne jamais tenir entierement dans la fenetre.
 */
private class ByteScanner(private val input: InputStream, capacity: Int) {
    private val buf = ByteArray(capacity)
    private var start = 0
    private var end = 0
    private var eof = false

    /** Offset absolu du curseur dans le fichier, pour localiser les zones perdues. */
    var offset: Long = 0L
        private set

    val available: Int get() = end - start

    /** Garantit [n] octets disponibles depuis le curseur. Faux si le flux se termine avant. */
    fun ensure(n: Int): Boolean {
        require(n <= buf.size) { "fenetre trop petite pour $n octets" }
        if (available >= n) return true
        if (eof) return false
        if (start > 0) {
            System.arraycopy(buf, start, buf, 0, available)
            end -= start
            start = 0
        }
        while (available < n) {
            val r = input.read(buf, end, buf.size - end)
            // Un read() qui rend 0 sans avoir atteint EOF (cas reel d'un Channel du Data Layer)
            // faisait tourner la boucle indefiniment (F-31) : on le traite comme une fin de flux.
            if (r <= 0) {
                eof = true
                break
            }
            end += r
        }
        return available >= n
    }

    /** Vrai si les [length] premiers octets du curseur valent le debut de [magic]. */
    fun startsWith(magic: ByteArray, length: Int): Boolean {
        if (available < length) return false
        for (i in 0 until length) if (buf[start + i] != magic[i]) return false
        return true
    }

    fun copyOut(dst: ByteArray, from: Int, length: Int) {
        System.arraycopy(buf, start + from, dst, 0, length)
    }

    fun skip(n: Int) {
        start += n
        offset += n
    }

    /** Consomme tout ce qui reste (flux epuise). Renvoie le nombre d'octets abandonnes. */
    fun drain(): Long {
        var total = 0L
        do {
            total += available
            skip(available)
        } while (ensure(1))
        return total
    }
}
