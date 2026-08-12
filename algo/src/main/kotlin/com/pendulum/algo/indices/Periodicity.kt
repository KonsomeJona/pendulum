package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.SleepMask

/**
 * Ferri's Periodicity Index.
 *
 * # The convention retained, and why one has to be chosen
 *
 * Two contradictory forms of the PI are in circulation — **within Ferri's own publications**. They
 * differ on two independent points, which makes four possible variants:
 *
 *  - is the lower bound of the periodicity window strict (`10 < IMI`) or inclusive (`10 ≤ IMI`)?
 *    Likewise for the upper bound;
 *  - does the numerator count qualifying **intervals**, or **movements** belonging to a periodic
 *    run (`PLMS_alt / LMS_total`, a slightly higher form)?
 *
 * The two forms give different values on the same night. Mixed together, they produce a trend in
 * which part of the observed variation is nothing but a change of definition.
 *
 * **Convention of this module, single and never mixed** ([PiConvention.FERRI_INTERVALS_LOW_EXCLUSIVE]):
 *
 * ```
 * IMI_k = onset_{k+1} − onset_k                        k = 1..N−1   (N = CLM during sleep)
 * qualifying(k)  ⟺  imiLowExclusiveSec < IMI_k ≤ imiHighInclusiveSec (10 s excluded, 90 s included)
 * Split the sequence of IMIs into maximal runs of CONSECUTIVE qualifying intervals.
 * PI = ( Σ length(R) over every run R of length ≥ minRunLength ) / (N − 1)
 * ```
 *
 * — **strict lower bound, inclusive upper bound**;
 * — **numerator in intervals**, not in movements;
 * — minimum run length = **3 intervals**, that is 4 movements (Ferri 2006), which aligns the PI on
 *   the clinical series rule (≥ 4 CLM);
 * — window **10–90 s**, not 10–50 s.
 *
 * The convention is reproduced in [PiDetail.convention] so that an archived result stays
 * interpretable even if this file changes one day. A change of convention **must** bump the
 * `paramsHash` and trigger a rescore of every night (guard rail no. 3 of `SPEC-v2.md` §3).
 *
 * # The rate guard rail
 *
 * Below [PeriodicityConfig.minLmRatePerHour] movements per hour, the PI is **uninterpretable**
 * (Drakatos 2021): its denominator `N − 1` becomes so small that a handful of intervals decides
 * everything. That is exactly what explains the published instability of the control group
 * (0.092 ± 0.152 in Ferri 2022 against 0.220 ± 0.229 in Mogavero 2024). Below this rate,
 * [PiResult.valid] is false and the value must not be displayed.
 *
 * # What the PI does not save
 *
 * It does **not** discriminate movements linked to breathing: RRLMs are periodic too, and the
 * apnoeic cycle (25–45 s) overlaps the PLMS mode (22–26 s). The PI is not an anti-RRLM guard rail
 * and must never be presented as one (§3.5).
 *
 * Reference values: diagnostic threshold ≈ **0.50**; RLS 0.601 ± 0.189; controls 0.092 ± 0.152.
 */

/** A single value today: the type exists to make the convention explicit at archiving time. */
enum class PiConvention {
    /** Strict lower bound, inclusive upper bound, numerator in intervals. */
    FERRI_INTERVALS_LOW_EXCLUSIVE,
}

data class PeriodicityConfig(
    /** 10 s **excluded**: an IMI of exactly 10.0 s does not qualify. */
    val imiLowExclusiveSec: Double = 10.0,
    /** 90 s **included**: an IMI of exactly 90.0 s qualifies. */
    val imiHighInclusiveSec: Double = 90.0,
    /** In **intervals** (3 intervals = 4 movements). */
    val minRunLength: Int = 3,
    /** Below this rate of LM per hour of analysable sleep, the PI is uninterpretable. */
    val minLmRatePerHour: Double = 10.0,
)

/**
 * Detail of the computation, for the export and the tests. [PiResult] remains the contractual
 * output; this type adds what makes it possible to check *how* the figure was obtained.
 */
data class PiDetail(
    val pi: PiResult,
    val convention: PiConvention,
    val sleepClmCount: Int,
    val totalIntervals: Int,
    val qualifyingIntervals: Int,
    val intervalsInCountedRuns: Int,
    val runCount: Int,
    val longestRunLength: Int,
    /** Denominator of the rate guard rail only — the PI itself has **no** temporal denominator. */
    val denominatorMin: Double,
)

object Periodicity {

    /** The convention in plain words, to be copied into the export and the medical report. */
    const val CONVENTION_DOC: String =
        "Ferri PI, Pendulum convention: qualifying intervals 10 s (excluded) < IMI <= 90 s " +
            "(included), maximal runs of at least 3 consecutive intervals, numerator in " +
            "INTERVALS (never in movements), denominator N-1 on the sleep CLMs."

    /**
     * Entry point aligned with `ALGO-v2.md` §4.3.
     *
     * @param clms all the candidate CLMs in chronological order; only the retained ones (`isClm`)
     *   and located within a sleep epoch enter the computation.
     * @param fsHz kept for the stability of the API; the instants come from `Clm.onsetMsRel`.
     */
    fun ferriIndex(
        clms: List<Clm>,
        mask: SleepMask,
        fsHz: Double,
        cfg: PeriodicityConfig = PeriodicityConfig(),
    ): PiResult = detail(clms, mask, fsHz, cfg).pi

    /** Same computation as [ferriIndex], but with the detail of the runs. */
    fun detail(
        clms: List<Clm>,
        mask: SleepMask,
        fsHz: Double,
        cfg: PeriodicityConfig = PeriodicityConfig(),
    ): PiDetail {
        require(fsHz > 0.0) { "fsHz must be > 0" }
        val lookup = SleepLookup(mask.windows)
        val onsets = ArrayList<Long>(clms.size)
        for (c in clms) {
            if (!c.isClm) continue
            if (!lookup.isSleepAt(c.onsetMsRel)) continue
            onsets.add(c.onsetMsRel)
        }
        val imi = DoubleArray(maxOf(0, onsets.size - 1)) {
            (onsets[it + 1] - onsets[it]) / 1000.0
        }
        return fromIntervals(imi, onsets.size, mask.analysableTstMin, cfg)
    }

    /**
     * Core of the computation, exposed for the tests and for the incremental mode.
     *
     * @param imiSec **consecutive** onset-to-onset intervals, in seconds, in order.
     * @param sleepClmCount `N`, number of sleep CLMs that produced these intervals.
     * @param analysableSleepMin denominator of the rate guard rail **only** (the PI has none).
     */
    fun fromIntervals(
        imiSec: DoubleArray,
        sleepClmCount: Int,
        analysableSleepMin: Double,
        cfg: PeriodicityConfig = PeriodicityConfig(),
    ): PiDetail {
        require(cfg.minRunLength >= 1) { "minRunLength must be >= 1" }
        val total = imiSec.size

        var qualifying = 0
        var counted = 0
        var runCount = 0
        var longest = 0
        var run = 0
        // Single sweep: the current run is closed as soon as an interval no longer qualifies.
        // `i == total` is a closing turn, so as not to duplicate the code after the loop.
        for (i in 0..total) {
            val qualifies = i < total &&
                imiSec[i] > cfg.imiLowExclusiveSec && imiSec[i] <= cfg.imiHighInclusiveSec
            if (qualifies) {
                run++
                qualifying++
            } else if (run > 0) {
                if (run > longest) longest = run
                if (run >= cfg.minRunLength) {
                    counted += run
                    runCount++
                }
                run = 0
            }
        }

        val hours = analysableSleepMin / 60.0
        val rate = if (hours > 0.0) sleepClmCount / hours else 0.0
        val piValue = if (total > 0) counted.toDouble() / total else 0.0
        // Three independent conditions, all of them necessary:
        //  - enough intervals for a run of minRunLength to be able to exist at all;
        //  - a known temporal denominator to evaluate the rate;
        //  - an LM rate above the interpretability floor.
        val valid = total >= cfg.minRunLength && hours > 0.0 && rate >= cfg.minLmRatePerHour

        return PiDetail(
            pi = PiResult(
                periodicityIndex = piValue,
                valid = valid,
                totalIntervals = total,
                lmRatePerHour = rate,
            ),
            convention = PiConvention.FERRI_INTERVALS_LOW_EXCLUSIVE,
            sleepClmCount = sleepClmCount,
            totalIntervals = total,
            qualifyingIntervals = qualifying,
            intervalsInCountedRuns = counted,
            runCount = runCount,
            longestRunLength = longest,
            denominatorMin = analysableSleepMin,
        )
    }
}
