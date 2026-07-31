package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat

/**
 * Transformation du flot d'evenements capteur en blocs de chunk.
 *
 * Deux horloges arrivent ici et elles ne servent **jamais** a la meme chose :
 *
 *  - `SensorEvent.timestamp` — la base de temps de la mesure. C'est la seule qui date les
 *    echantillons, la seule sur laquelle [GapMonitor] mesure les trous, la seule qui decide de
 *    la validite d'un bloc.
 *  - `SystemClock.elapsedRealtimeNanos()` a la reception — l'heure de *livraison*. Elle ne dit
 *    rien de la mesure, et tout du materiel : un ecart de livraison de trente secondes en mode
 *    batche, c'est un vidage de FIFO, pas un trou. C'est precisement ce qui la rend utile pour
 *    la seule chose qu'elle sache : **reperer la frontiere entre deux vidages du FIFO**.
 *
 * D'ou la regle centrale de ce fichier : **un bloc ne chevauche jamais deux vidages du FIFO**.
 * Le format n'a pas de timestamp par echantillon — il interpole lineairement entre `tFirstNs` et
 * `tLastNs` — et cette economie de 8 octets par echantillon (11 Mo par nuit) n'est licite que
 * tant que l'interpolation l'est. Un bloc a cheval sur deux vidages contient un trou que
 * l'interpolation etale sur *tous* ses echantillons, et les date donc tous faux. Le producteur
 * est le seul a savoir ou est la frontiere : [ChunkWriter][com.pendulum.format.ChunkWriter] le
 * verifie par un `require`, et couper au bon endroit est notre travail, pas le sien.
 */
class SensorPipeline(
    private val store: ChunkStore,
    private val gaps: GapMonitor,
    private val envelope: PreviewEnvelope,
    private var rateHz: Int,
    /** Appele avec l'index du chunk qui vient d'etre ferme. */
    private val onChunkClosed: (Int) -> Unit,
    /** Etat off-body courant, journalise et jamais actionne. */
    private val offBody: () -> Boolean,
) {

    companion object {
        /**
         * Au-dela de cet ecart entre deux *arrivees*, on considere qu'un nouveau vidage du FIFO
         * commence. En continu les arrivees sont espacees d'une periode (20 ms a 50 Hz) ; a
         * l'interieur d'une salve elles sont espacees de quelques microsecondes. 100 ms separe
         * les deux cas sans ambiguite, sur toute la plage de cadences envisagee.
         */
        const val FLUSH_GAP_NS = 100_000_000L
    }

    private val bx = FloatArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK)
    private val by = FloatArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK)
    private val bz = FloatArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK)
    private var count = 0
    private var tFirstNs = 0L
    private var tLastNs = 0L
    private var pendingFlags = 0
    private var lastArrivalNs = 0L

    /** Drapeaux a poser sur le prochain bloc ouvert (trou detecte, reprise apres reboot). */
    fun markNextBlock(flag: Int) {
        pendingFlags = pendingFlags or flag
    }

    /** @param nowMs `SystemClock.elapsedRealtime()` — une duree ne se calcule jamais sur
     *   l'horloge murale, qui saute au changement d'heure et a la resynchronisation NTP. */
    fun onRateChanged(rateHz: Int, nowMs: Long) {
        // Le bloc en cours a ete echantillonne a l'ancienne cadence : il doit partir avant que
        // la nouvelle ne rende sa base de temps invalide au regard de l'entete du chunk suivant.
        flushBlock(nowMs)
        this.rateHz = rateHz
        gaps.onRateChanged(rateHz)
        envelope.onRateChanged(rateHz)
    }

    fun onEvent(x: Float, y: Float, z: Float, tsNs: Long, arrivalNs: Long, nowMs: Long) {
        val flushBoundary = lastArrivalNs != 0L && arrivalNs - lastArrivalNs > FLUSH_GAP_NS
        lastArrivalNs = arrivalNs

        val gapBefore = gaps.onSample(tsNs)
        envelope.onSample(x, y, z, tsNs)

        if (count > 0) {
            val mustCut = flushBoundary ||
                gapBefore ||
                count >= ChunkFormat.MAX_SAMPLES_PER_BLOCK ||
                tsNs <= tLastNs ||
                // Meme predicat que celui du writer : on coupe *avant* que la cadence implicite
                // du bloc ne sorte de la tolerance, ce qui garantit que `writeBlock` n'echoue
                // jamais et que l'interpolation reste honnete.
                !ChunkFormat.isTimebasePlausible(count + 1, tFirstNs, tsNs, rateHz)
            if (mustCut) {
                flushBlock(nowMs)
                if (flushBoundary) pendingFlags = pendingFlags or ChunkFormat.FLAG_FIFO_BOUNDARY
                if (gapBefore) pendingFlags = pendingFlags or ChunkFormat.FLAG_GAP_BEFORE
            }
        } else if (flushBoundary) {
            pendingFlags = pendingFlags or ChunkFormat.FLAG_FIFO_BOUNDARY
        }

        if (count == 0) {
            tFirstNs = tsNs
            if (offBody()) pendingFlags = pendingFlags or ChunkFormat.FLAG_OFF_BODY
        }
        bx[count] = x
        by[count] = y
        bz[count] = z
        tLastNs = tsNs
        count++
    }

    /** Ecrit le bloc en cours, s'il y en a un. A appeler avant toute fermeture de session. */
    fun flushBlock(nowMs: Long) {
        if (count == 0) return
        val closed = store.writeBlock(bx, by, bz, count, tFirstNs, tLastNs, pendingFlags, nowMs)
        count = 0
        pendingFlags = 0
        closed?.let(onChunkClosed)
    }
}
