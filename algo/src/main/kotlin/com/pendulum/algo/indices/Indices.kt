package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.PublicationGate
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Step 7 of `ALGO-v2.md`: the hourly count and its companions.
 *
 * Three rules structure this whole file, and none of them is negotiable:
 *
 *  1. **The denominator is *analysable* sleep time, never raw TST.** Raw TST includes the blind
 *     zones, the broken segments and the off-body time: counting movements over a duration during
 *     which none could have been seen inflates the denominator and deflates the index, exactly in
 *     the direction that makes a screening miss its case (§2.4).
 *  2. **`DenominatorIndependence` propagates without being "improved".** A result whose
 *     denominator is [DenominatorIndependence.CIRCULAR] comes out of the same signal as its
 *     numerator (§3.6.3): it is computed, stored, displayed as a second arm, but it can never
 *     carry the primary result nor feed the trend. It is the code that forbids it, not the UI.
 *  3. **The residual respiratory bias is *quantified*, not ignored.** Without a respiratory
 *     channel, neither of the two published RRLM exclusion rules is computable (§3.5). So we do
 *     not claim to exclude: we publish a bracketing `[plmiRespWorstCase, plmi]` whose spread *is*
 *     the uncertainty indicator. The RRLM bias is upward; the Terrill bias (39 % of the EMG LMs
 *     mechanically invisible) and the unilateral measurement bias are downward. **They do not
 *     cancel out**: different subjects and mechanisms, variances that add up.
 *
 * All the functions are pure: no clock, no randomness, no I/O.
 */

/** Step 7 parameters. Default values = table §6.5 / §6.6 of `ALGO-v2.md`. */
data class PlmiConfig(
    /** Low apnoeic band: a series median IMI within [low, high] is *suspect*, not excluded. */
    val respSuspectImiLowSec: Double = 25.0,
    val respSuspectImiHighSec: Double = 45.0,
    val imiHistogramBinSec: Double = 2.0,
    val imiHistogramMaxSec: Double = 100.0,
    /** Full publication gate (§3.7.2). */
    val minTstFullMin: Double = 240.0,
    /** Below this value, no PLMI is published — the PI and the rhythm, for their part, survive. */
    val minTstAnyMin: Double = 180.0,
)

// --- Local numeric helpers -----------------------------------------------------------------
//
// Deliberately minimal and package-private: `dsp.Numeric` carries the heavy primitives (sliding
// windows, MAD on signals), but step 7 only handles a few dozen values. Duplicating three lines
// of median costs less than a coupling on an API that is still moving, and keeps `indices/`
// compilable on its own.

/** Median of a sorted copy. Convention: mean of the two central values if `n` is even. */
internal fun medianOf(values: DoubleArray): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.copyOf()
    s.sort()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
}

/**
 * Quantile of the "interpolated nearest rank" kind on a sorted copy. Deterministic.
 * `q` is clamped to [0, 1].
 */
internal fun quantileOf(values: DoubleArray, q: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.copyOf()
    s.sort()
    val pos = (q.coerceIn(0.0, 1.0)) * (s.size - 1)
    val lo = kotlin.math.floor(pos).toInt()
    val hi = kotlin.math.ceil(pos).toInt()
    if (lo == hi) return s[lo]
    val f = pos - lo
    return s[lo] * (1.0 - f) + s[hi] * f
}

/** Median absolute deviation, scaled to a Gaussian standard deviation (factor 1.4826). */
internal fun madOf(values: DoubleArray): Double {
    if (values.isEmpty()) return Double.NaN
    val med = medianOf(values)
    val dev = DoubleArray(values.size) { kotlin.math.abs(values[it] - med) }
    return 1.4826 * medianOf(dev)
}

/** A rate exists only if its denominator exists. `NaN` says "no value", not "zero". */
internal fun safeRate(count: Int, hours: Double): Double =
    if (hours > 0.0 && hours.isFinite()) count / hours else Double.NaN

internal fun Stage?.isSleepStage(): Boolean =
    this == Stage.SLEEP || this == Stage.LIGHT || this == Stage.DEEP || this == Stage.REM

/**
 * *Intra-SPT* wake. [Stage.OUT_OF_BED] is excluded from it: the WASM's PLMW refers to the WASO,
 * not to the time spent standing.
 */
internal fun Stage?.isWakeInBedStage(): Boolean =
    this == Stage.WAKE || this == Stage.AWAKE_IN_BED

/**
 * Sleep window index, binary search. Assumes disjoint windows; in case of overlap, the last
 * window starting before the instant wins.
 */
internal class SleepLookup(windows: List<SleepWindow>) {
    private val starts: LongArray
    private val ends: LongArray
    private val stages: Array<Stage>

    init {
        val sorted = windows.sortedBy { it.startMsRel }
        starts = LongArray(sorted.size) { sorted[it].startMsRel }
        ends = LongArray(sorted.size) { sorted[it].endMsRel }
        stages = Array(sorted.size) { sorted[it].stage }
    }

    /** SPT bounds = envelope of the "in bed" windows (everything except [Stage.OUT_OF_BED]). */
    val sptStartMsRel: Long
    val sptEndMsRel: Long

    init {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (i in starts.indices) {
            if (stages[i] == Stage.OUT_OF_BED) continue
            lo = min(lo, starts[i])
            hi = max(hi, ends[i])
        }
        sptStartMsRel = if (lo == Long.MAX_VALUE) 0L else lo
        sptEndMsRel = if (hi == Long.MIN_VALUE) 0L else hi
    }

    fun stageAt(msRel: Long): Stage? {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= msRel) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        return if (msRel < ends[found]) stages[found] else null
    }

    fun isSleepAt(msRel: Long): Boolean = stageAt(msRel).isSleepStage()

    fun isWakeInBedAt(msRel: Long): Boolean = stageAt(msRel).isWakeInBedStage()

    /** Milliseconds of *sleep* (in the sense of [isSleepStage]) overlapping `[from, to)`. */
    fun sleepMsBetween(from: Long, to: Long): Long {
        if (to <= from) return 0L
        var acc = 0L
        for (i in starts.indices) {
            if (!stages[i].isSleepStage()) continue
            val a = max(starts[i], from)
            val b = min(ends[i], to)
            if (b > a) acc += b - a
        }
        return acc
    }
}

/**
 * Hourly indices, respiratory bracketing and publication gate.
 *
 * The object is named `Plmi` to stay aligned with `ALGO-v2.md` §4.3, but **the published name of
 * the quantity is `aPLM-i`** (guard rail no. 6 of `SPEC-v2.md` §3): calling a figure produced by a
 * watch strap on an ankle a "PLMI" guarantees that it will be read as a laboratory PLMI.
 */
object Plmi {

    /** Lower IMI bound of each rule set (§6.5). Used for counting `shortImiCount`. */
    fun imiMinSecOf(rule: SeriesRule): Double = when (rule) {
        SeriesRule.AASM_V3 -> 5.0
        SeriesRule.WASM_2016 -> 10.0
    }

    /**
     * A circular denominator can never carry the primary result (§2.3, §3.6.3).
     * Predicate exposed separately because it also serves the DAO and the trend, not only here.
     */
    fun canCarryPrimaryResult(mask: SleepMask): Boolean =
        mask.independence != DenominatorIndependence.CIRCULAR

    /**
     * What the analysis **is allowed** to publish. Evaluated by the code, never by the user, and
     * never bypassable from the interface.
     *
     * Order of the refusals, from the hardest to the mildest:
     *  - non-convergent accelerometric mask → no PLMI (§3.6.3, layer 2: a night where movement
     *    and immobility do not separate does not produce a publishable figure);
     *  - less than [PlmiConfig.minTstAnyMin] of analysable sleep → no PLMI (§3.7.2);
     *  - truncated night, or less than [PlmiConfig.minTstFullMin] → published but **outside the
     *    trend** (the PLMI of a truncated night is biased upward in a way that cannot be
     *    corrected);
     *  - circular denominator → published but **outside the trend**.
     *
     * The PI and the fundamental rhythm, for their part, survive `NO_PLMI`: they have no temporal
     * denominator. That is the whole point of §5 of `SPEC-v2.md`.
     */
    fun publicationGate(
        mask: SleepMask,
        truncated: Boolean,
        cfg: PlmiConfig = PlmiConfig(),
    ): PublicationGate {
        // The fixed point only exists for a mask derived from the accelerometer; an HC or diary
        // mask has nothing to converge and must report `fixedPointConverged = true`.
        val hasFixedPoint = mask.source == MaskSource.ACCEL_IMMOBILITY || mask.source == MaskSource.FUSED
        if (hasFixedPoint && !mask.fixedPointConverged) return PublicationGate.NO_PLMI
        if (!(mask.analysableTstMin >= cfg.minTstAnyMin)) return PublicationGate.NO_PLMI
        if (truncated) return PublicationGate.TRUNCATED_NO_TREND
        if (mask.analysableTstMin < cfg.minTstFullMin) return PublicationGate.TRUNCATED_NO_TREND
        if (!canCarryPrimaryResult(mask)) return PublicationGate.TRUNCATED_NO_TREND
        return PublicationGate.FULL
    }

    /**
     * Assembles a [PlmiResult].
     *
     * @param clms all the candidate CLMs, **in chronological order**. The rejected ones
     *   (`reject != null`) and the long LMs are excluded here; `PlmSeries.clmIndices` indexes the
     *   list of the **retained** ones (`Clm.isClm`), in accordance with `Model.kt`.
     * @param series series already built for `rule` (step 6).
     * @param fsHz kept for the stability of the §4.3 API; the instants come from
     *   `Clm.onsetMsRel`, which is the reference — never a wall-clock difference (§2.7).
     * @param pi Periodicity Index result (see `Periodicity.kt`).
     * @param rhythm harmonic deconvolution result (see `Rhythm.kt`).
     * @param truncatedSeriesDropped series abandoned for lack of 4 CLMs after truncation at the
     *   edges, reported by step 6. This is a measurable **downward** bias, which grows on an
     *   interrupted night and must be displayed next to the figure (§3.7.2 point 5).
     */
    fun compute(
        clms: List<Clm>,
        series: List<PlmSeries>,
        mask: SleepMask,
        fsHz: Double,
        rule: SeriesRule,
        pi: PiResult,
        rhythm: RhythmResult,
        floorMode: FloorMode,
        truncated: Boolean,
        cfg: PlmiConfig = PlmiConfig(),
        truncatedSeriesDropped: Int = 0,
        paramsHash: String = "",
    ): PlmiResult {
        require(fsHz > 0.0) { "fsHz must be > 0" }

        val retained = clms.filter { it.isClm }
        val lookup = SleepLookup(mask.windows)

        // Translation of the series indices, done **here** and nowhere else.
        //
        // `SeriesBuilder` indexes its series on the list it is given, rejects included — it needs
        // them, a movement that is too long has to break the series at the place where it falls.
        // The counts below, for their part, bear on the retained ones only. The two bases coincide
        // as long as no event is rejected, and diverge as soon as the first one is.
        //
        // That offset produced a costly defect: both bases are `Int`, neither the compiler nor the
        // review saw the difference, and the conversion lived at the caller — written in the test
        // harness, absent from production. The tests were therefore validating a wiring that the
        // application did not have.
        //
        // Fixing it at the caller was not enough: out of three callers, two thought of it and the
        // third did not. This is where it must live, because this function receives **both** of
        // the required inputs — the complete list and the series — and a caller therefore has
        // nothing left to know. There is now only one index base at the input: that of `clms`, as
        // `SeriesBuilder` produces it.
        val toRetainedIndex = IntArray(clms.size) { -1 }
        var rank = 0
        for (i in clms.indices) if (clms[i].isClm) { toRetainedIndex[i] = rank++ }

        // --- Series membership -----------------------------------------------------------
        val inSeries = BooleanArray(retained.size)
        // Marking of the CLMs belonging to a series whose median IMI falls in the apnoeic band.
        val respSuspect = BooleanArray(retained.size)
        for (s in series) {
            val medianImi = medianOf(DoubleArray(s.imiSec.size) { s.imiSec[it].toDouble() })
            val suspect = medianImi.isFinite() &&
                medianImi >= cfg.respSuspectImiLowSec && medianImi <= cfg.respSuspectImiHighSec
            for (idx in s.clmIndices) {
                // `-1` = rejected event. `SeriesBuilder` never puts one in a series, but we do not
                // assume it: we ignore it rather than invent a correspondence.
                val k = toRetainedIndex.getOrElse(idx) { -1 }
                if (k < 0) continue
                inSeries[k] = true
                if (suspect) respSuspect[k] = true
            }
        }

        // --- Counts ----------------------------------------------------------------------
        var plmsCount = 0
        var plmwCount = 0
        var isolatedCount = 0
        var plmsCountRespWorst = 0
        var plmsFirstHalf = 0
        var plmsSecondHalf = 0

        val sptStart = lookup.sptStartMsRel
        val sptEnd = lookup.sptEndMsRel
        val midMs = sptStart + (sptEnd - sptStart) / 2

        for (i in retained.indices) {
            val onset = retained[i].onsetMsRel
            val sleeping = lookup.isSleepAt(onset)
            if (!inSeries[i]) {
                isolatedCount++
                continue
            }
            if (sleeping) {
                plmsCount++
                // Respiratory worst case: we remove EVERY series whose median IMI is apnoeic.
                // This is not an RRLM exclusion (impossible without a respiratory channel): it is
                // a guaranteed lower bound. The true value lies between the two bounds.
                if (!respSuspect[i]) plmsCountRespWorst++
                if (onset < midMs) plmsFirstHalf++ else plmsSecondHalf++
            } else if (lookup.isWakeInBedAt(onset)) {
                // PLMW: a WASM metric, not defined by the AASM. Only the WASM, incidentally,
                // allows a series to cross a sleep/wake transition (2.4.4).
                plmwCount++
            }
        }

        // Short intervals: counted on consecutive retained CLMs, lower bound of the rule set.
        val imiMinSec = imiMinSecOf(rule)
        var shortImiCount = 0
        for (i in 1 until retained.size) {
            val imiSec = (retained[i].onsetMsRel - retained[i - 1].onsetMsRel) / 1000.0
            if (imiSec < imiMinSec) shortImiCount++
        }

        // --- Denominators ----------------------------------------------------------------
        // NEVER raw TST: `analysableTstMin` = TST ∩ valid segments ∩ outside blind zones
        // ∩ outside off-body ∩ outside warmup.
        val analysableTstH = mask.analysableTstMin / 60.0
        val analysableSptH = mask.analysableSptMin / 60.0
        // The mask carries no "analysable WASO": we prorate it by the SPT coverage. An owned and
        // documented approximation — it only touches the PLMW, never the PLMI.
        val analysableSptRatio = if (mask.sptMin > 0.0) mask.analysableSptMin / mask.sptMin else 0.0
        val analysableWasoH = mask.wasoMin * analysableSptRatio / 60.0

        val plmi = safeRate(plmsCount, analysableTstH)
        val plmiSpt = safeRate(plmsCount, analysableSptH)
        val plmw = safeRate(plmwCount, analysableWasoH)
        val plmiRespWorstCase = safeRate(plmsCountRespWorst, analysableTstH)

        // Split-half: on a truncated night, this ratio is the best indicator of the magnitude of
        // the upward bias (PLMS concentrate in the first half of the night, §3.7.2 point 2).
        val analysableTstRatio = if (mask.tstMin > 0.0) mask.analysableTstMin / mask.tstMin else 0.0
        val firstHalfH = lookup.sleepMsBetween(sptStart, midMs) / 3_600_000.0 * analysableTstRatio
        val secondHalfH = lookup.sleepMsBetween(midMs, sptEnd) / 3_600_000.0 * analysableTstRatio

        // --- IMI histogram ---------------------------------------------------------------
        val binCount = max(1, (cfg.imiHistogramMaxSec / cfg.imiHistogramBinSec).roundToInt())
        val histogram = IntArray(binCount)
        val edges = FloatArray(binCount + 1) { (it * cfg.imiHistogramBinSec).toFloat() }
        // On ALL consecutive sleep CLMs, not only those in a series: the 2-4 s / 22-26 s
        // bimodality is the raw diagnostic information, and the short mode disappears if one keeps
        // only the intervals already filtered by the series construction.
        var prevSleepOnset = Long.MIN_VALUE
        for (c in retained) {
            if (!lookup.isSleepAt(c.onsetMsRel)) continue
            if (prevSleepOnset != Long.MIN_VALUE) {
                val imiSec = (c.onsetMsRel - prevSleepOnset) / 1000.0
                val bin = kotlin.math.floor(imiSec / cfg.imiHistogramBinSec).toInt()
                if (bin in 0 until binCount) histogram[bin]++
            }
            prevSleepOnset = c.onsetMsRel
        }

        return PlmiResult(
            rule = rule,
            maskSource = mask.source,
            plmsCount = plmsCount,
            plmwCount = plmwCount,
            isolatedCount = isolatedCount,
            shortImiCount = shortImiCount,
            tstMin = mask.tstMin,
            analysableTstMin = mask.analysableTstMin,
            sptMin = mask.sptMin,
            wasoMin = mask.wasoMin,
            plmi = plmi,
            plmiSpt = plmiSpt,
            plmw = plmw,
            pi = pi,
            rhythm = rhythm,
            plmiFirstHalf = safeRate(plmsFirstHalf, firstHalfH),
            plmiSecondHalf = safeRate(plmsSecondHalf, secondHalfH),
            imiHistogram = histogram,
            imiBinEdgesSec = edges,
            truncatedSeriesDropped = truncatedSeriesDropped,
            plmiRespWorstCase = plmiRespWorstCase,
            independence = mask.independence,
            gate = publicationGate(mask, truncated, cfg),
            floorMode = floorMode,
            paramsHash = paramsHash,
        )
    }

    /**
     * Width of the respiratory bracketing, in events/h. **It is this spread that is the
     * uncertainty indicator to display**, not the lower bound alone: without a respiratory
     * channel, the true value is somewhere within `[plmiRespWorstCase, plmi]` and nothing allows
     * it to be located inside the interval. Two official definitions of the exclusion window
     * already differ from each other by a factor 1.8 when the channel is available (§3.5).
     */
    fun respiratoryBiasSpread(r: PlmiResult): Double = r.plmi - r.plmiRespWorstCase
}
