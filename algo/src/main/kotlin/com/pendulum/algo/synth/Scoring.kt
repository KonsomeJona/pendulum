package com.pendulum.algo.synth

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.SeriesRule
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Result of a detection / ground-truth matching (`docs/workings/ALGO-v2.md` §5.4).
 *
 * @param onsetBiasMs mean timing bias, **signed**: positive if the detector dates too late. The
 *   coarse 0.5 s envelope introduces by construction a bias bounded by ~0.25 s.
 * @param onsetSdMs standard deviation of the timing error.
 */
data class MatchResult(
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val sensitivity: Double,
    val precision: Double,
    val f1: Double,
    val onsetBiasMs: Double,
    val onsetSdMs: Double,
) {
    val truthCount: Int get() = tp + fn
    val detectedCount: Int get() = tp + fp
}

/** An analysed night: what was injected, what was detected, and the evening's effective floor. */
class DetectionRun(
    val truth: List<TruthEvent>,
    val detected: List<Clm>,
    /** Median `Theta_on / k_on` of the night. It is the detector's amplitude reference. */
    val effectiveFloorG: Double,
)

/**
 * Matching and metrics. `docs/workings/ALGO-v2.md` §5.4.
 *
 * The matching is **greedy and chronological**, onset tolerance 1.0 s. Justification of the
 * tolerance, taken over as it stands: the finest clinical granularity is the lower bound of the IMI
 * (5 s) and the coarse 0.5 s envelope introduces an onset bias bounded by ~0.25 s; 1.0 s is
 * therefore wide compared to the bias and narrow compared to the rule.
 */
object Scoring {

    /** Default bin edges of the sensitivity curve, in multiples of the effective floor. */
    val DEFAULT_RATIO_BINS: DoubleArray =
        doubleArrayOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 24.0, 48.0, Double.MAX_VALUE)

    /**
     * @param detected events produced by the detector. The rejected ones and the long LMs are
     *   excluded here: an event the chain does not count can be neither a true positive nor a false
     *   positive of the **count**.
     * @param truth **always** `accelTruth` (§5.3). Scoring against `emgTruth` would cap F1 towards
     *   0.76 for a reason that is not the algorithm's fault.
     */
    fun match(
        detected: List<Clm>,
        truth: List<TruthEvent>,
        toleranceSec: Double = 1.0,
    ): MatchResult {
        val det = detected.filter { it.isClm }.sortedBy { it.onsetMsRel }
        val tru = truth.sortedBy { it.onsetMsRel }
        val tolMs = Math.round(toleranceSec * 1000.0)
        val used = BooleanArray(det.size)

        var tp = 0
        var sum = 0.0
        var sumSq = 0.0
        var lo = 0
        for (t in tru) {
            // Sliding window: the detections are sorted, so `lo` only ever advances forward.
            while (lo < det.size && det[lo].onsetMsRel < t.onsetMsRel - tolMs) lo++
            var best = -1
            var bestDelta = Long.MAX_VALUE
            var j = lo
            while (j < det.size && det[j].onsetMsRel <= t.onsetMsRel + tolMs) {
                if (!used[j]) {
                    val d = abs(det[j].onsetMsRel - t.onsetMsRel)
                    if (d < bestDelta) { bestDelta = d; best = j }
                }
                j++
            }
            if (best >= 0) {
                used[best] = true
                tp++
                val delta = (det[best].onsetMsRel - t.onsetMsRel).toDouble()
                sum += delta
                sumSq += delta * delta
            }
        }

        val fn = tru.size - tp
        val fp = det.size - tp
        val se = if (tru.isEmpty()) Double.NaN else tp.toDouble() / tru.size
        val pr = if (det.isEmpty()) Double.NaN else tp.toDouble() / det.size
        val f1 = if (tp == 0) 0.0 else 2.0 * tp / (2.0 * tp + fp + fn)
        val bias = if (tp == 0) Double.NaN else sum / tp
        val sd = if (tp < 2) Double.NaN else {
            val v = (sumSq - sum * sum / tp) / (tp - 1)
            sqrt(v.coerceAtLeast(0.0))
        }
        return MatchResult(tp, fp, fn, se, pr, f1, bias, sd)
    }

    /**
     * **Relative** error of the hourly count, against the expectation of the same rule set.
     *
     * Reminder from §5.3: the expectation is computed on `accelTruth`. The `emgToAccelRatio` of the
     * ground truth still has to be reported alongside — it is what says by how much the published
     * count sits structurally below the EMG scale to which the ICSD-3 threshold of 15/h belongs.
     */
    fun plmiError(actual: PlmiResult, truth: GroundTruth): Double {
        val expected = when (actual.rule) {
            SeriesRule.AASM_V3 -> truth.expectedPlmiAasm
            SeriesRule.WASM_2016 -> truth.expectedPlmiWasm
        }
        if (!(expected > 0.0)) return Double.NaN
        return abs(actual.plmi - expected) / expected
    }

    /** **Absolute** Periodicity Index error. The PI is already a fraction: nothing relative. */
    fun piError(actual: PlmiResult, truth: GroundTruth): Double =
        abs(actual.pi.periodicityIndex - truth.expectedPi)

    /**
     * Sensitivity curve: `(amplitude / floor ratio, Se)`.
     *
     * The abscissa is the ratio between the **coarse envelope peak** of the injected event and the
     * **effective floor** of the detector, `Theta_on / k_on`. It is the only definition under which
     * the statement of T5 ("8x the floor = the threshold, by construction") is true: the trigger
     * threshold is `max(k_on.floor, Theta_abs, f_cal.gainCal)`, and on a quiet night it is the
     * absolute floor that wins, not the relative term. Relating the amplitude to the **raw** floor
     * would make the abscissa depend on the dominant term, hence on the night.
     *
     * [minCount] is the minimum number of events for a bin to be **reported**. It is not cosmetic:
     * the T5 sweep places all its events at exactly 4x, 8x and 16x, so the intermediate bins are
     * populated only by the amplitude **tuning residue** — measured at 3 events out of 3 240 in the
     * [6 ; 8) bin. A rate estimated on 3 draws is not a sensitivity, and letting it out made the
     * monotonicity assertion of T5 fail on sampling noise. The default of 1 preserves the historical
     * behaviour for every other caller.
     */
    fun sensitivityCurve(
        runs: List<DetectionRun>,
        binEdges: DoubleArray = DEFAULT_RATIO_BINS,
        toleranceSec: Double = 1.0,
        minCount: Int = 1,
    ): List<Pair<Double, Double>> {
        require(binEdges.size >= 2) { "at least one bin is required" }
        val matched = IntArray(binEdges.size - 1)
        val total = IntArray(binEdges.size - 1)
        val tolMs = Math.round(toleranceSec * 1000.0)

        for (run in runs) {
            val det = run.detected.filter { it.isClm }.sortedBy { it.onsetMsRel }
            val tru = run.truth.sortedBy { it.onsetMsRel }
            val used = BooleanArray(det.size)
            var lo = 0
            for (t in tru) {
                while (lo < det.size && det[lo].onsetMsRel < t.onsetMsRel - tolMs) lo++
                var best = -1
                var bestDelta = Long.MAX_VALUE
                var j = lo
                while (j < det.size && det[j].onsetMsRel <= t.onsetMsRel + tolMs) {
                    if (!used[j]) {
                        val d = abs(det[j].onsetMsRel - t.onsetMsRel)
                        if (d < bestDelta) { bestDelta = d; best = j }
                    }
                    j++
                }
                val ratio = if (run.effectiveFloorG > 0.0) t.envPeakG / run.effectiveFloorG else 0.0
                val bin = binOf(ratio, binEdges)
                total[bin]++
                if (best >= 0) { used[best] = true; matched[bin]++ }
            }
        }

        val out = ArrayList<Pair<Double, Double>>(matched.size)
        for (b in matched.indices) {
            if (total[b] < minCount.coerceAtLeast(1)) continue
            val center = if (binEdges[b + 1] == Double.MAX_VALUE) binEdges[b] else
                0.5 * (binEdges[b] + binEdges[b + 1])
            out.add(center to matched[b].toDouble() / total[b])
        }
        return out
    }

    private fun binOf(ratio: Double, edges: DoubleArray): Int {
        for (b in 0 until edges.size - 1) {
            if (ratio >= edges[b] && ratio < edges[b + 1]) return b
        }
        return edges.size - 2
    }
}
