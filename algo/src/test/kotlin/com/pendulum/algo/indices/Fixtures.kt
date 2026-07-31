package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/** Fabriques partagees par les tests du paquet `indices`. Aucune horloge, aucun aleatoire cache. */

internal const val FS_HZ = 50.0

internal fun clmAt(
    onsetMs: Long,
    durationMs: Int = 800,
    reject: ClmRejectReason? = null,
    flags: Int = 0,
): Clm = Clm(
    onsetIdx = (onsetMs * FS_HZ / 1000.0).toInt(),
    offsetIdx = ((onsetMs + durationMs) * FS_HZ / 1000.0).toInt(),
    onsetMsRel = onsetMs,
    durationMs = durationMs,
    peakAmpG = 0.12f,
    medianAmpG = 0.06f,
    noiseFloorG = 0.010f,
    thresholdOnG = 0.080f,
    thresholdOffG = 0.025f,
    tiltChangeDeg = 1.0f,
    tiltExcursionDeg = 2.0f,
    flags = flags,
    reject = reject,
)

/** Suite de CLM regulierement espaces, le premier a `startSec`. */
internal fun clmsEvery(startSec: Double, stepSec: Double, count: Int): List<Clm> =
    (0 until count).map { clmAt(((startSec + it * stepSec) * 1000.0).roundToLong()) }

internal fun clmsAtSec(vararg onsetsSec: Double): List<Clm> =
    onsetsSec.map { clmAt((it * 1000.0).roundToLong()) }

/**
 * Masque de sommeil simple. Par defaut : une seule fenetre de sommeil, denominateur independant,
 * point fixe convergent — c'est-a-dire le cas ou tout est publiable.
 */
internal fun maskOf(
    windows: List<SleepWindow> = listOf(SleepWindow(0L, 25_200_000L, Stage.SLEEP)),
    sptMin: Double = 480.0,
    tstMin: Double = 420.0,
    wasoMin: Double = 60.0,
    analysableTstMin: Double = 420.0,
    analysableSptMin: Double = 480.0,
    source: MaskSource = MaskSource.HEALTH_CONNECT,
    independence: DenominatorIndependence = DenominatorIndependence.INDEPENDENT_HC,
    fixedPointConverged: Boolean = true,
): SleepMask = SleepMask(
    windows = windows,
    source = source,
    sptMin = sptMin,
    tstMin = tstMin,
    wasoMin = wasoMin,
    analysableTstMin = analysableTstMin,
    analysableSptMin = analysableSptMin,
    corrected = false,
    lagAppliedMs = 0L,
    independence = independence,
    fixedPointConverged = fixedPointConverged,
)

/** Serie couvrant les CLM d'indices `[from, to]` de la liste des CLM retenus. */
internal fun seriesOver(
    clms: List<Clm>,
    from: Int,
    to: Int,
    rule: SeriesRule = SeriesRule.AASM_V3,
): PlmSeries {
    val idx = IntArray(to - from + 1) { from + it }
    val imi = FloatArray(idx.size - 1) {
        ((clms[idx[it + 1]].onsetMsRel - clms[idx[it]].onsetMsRel) / 1000.0).toFloat()
    }
    return PlmSeries(
        rule = rule,
        clmIndices = idx,
        imiSec = imi,
        truncatedAtStart = false,
        truncatedAtEnd = false,
        duringSleepFraction = 1.0f,
    )
}

/**
 * Simule des intervalles OBSERVES : une suite de mouvements vrais log-normaux dont chacun est
 * manque independamment avec la probabilite `missRate`. C'est exactement le processus que la
 * deconvolution pretend inverser — et le seul endroit du module ou un generateur pseudo-aleatoire
 * est autorise, parce qu'il est graine et n'entre jamais dans le code de production.
 */
internal fun simulateObservedIntervalsSec(
    fundamentalSec: Double,
    sigmaLog: Double,
    missRate: Double,
    trueMovements: Int,
    seed: Long,
): DoubleArray {
    val rnd = java.util.Random(seed)
    val times = DoubleArray(trueMovements + 1)
    var t = 0.0
    for (i in 1..trueMovements) {
        t += exp(ln(fundamentalSec) + sigmaLog * rnd.nextGaussian())
        times[i] = t
    }
    val seen = ArrayList<Double>(trueMovements)
    seen.add(times[0])
    for (i in 1..trueMovements) {
        if (rnd.nextDouble() >= missRate) seen.add(times[i])
    }
    return DoubleArray(seen.size - 1) { seen[it + 1] - seen[it] }
}
