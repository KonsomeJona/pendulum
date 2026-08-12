package com.pendulum.algo.mask

import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskAgreement
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fusion parameters (`docs/workings/ALGO-v2.md` §3.6.4, table §6.6).
 *
 * @param maxLagMs half-range of the lag search, ±10 min by default (range 300–900 s).
 * @param lagStepMs step of the search. Equal to the duration of an epoch: searching finer than the
 *   grid on which the comparison is made adds no information.
 * @param minCoverage minimum overlap required for a candidate lag to be merely *eligible*. Without
 *   it, the maximal lag always wins: by leaving only a handful of epochs facing each other, it is
 *   trivial to reach 100 % agreement on almost nothing.
 */
data class FusionConfig(
    val maxLagMs: Long = 600_000,
    val lagStepMs: Long = 5_000,
    val minCoverage: Double = 0.50,
)

/**
 * Learned correction of the accelerometric TST towards the reference TST (§3.6.4, point 3).
 * `TST_HC ≈ alpha · TST_accel + beta`.
 */
data class TstCorrection(val alpha: Double, val beta: Double, val nNights: Int, val valid: Boolean)

/** Epoch not filled in by a mask. Never to be confused with "awake": see `binarize`. */
private const val UNDEFINED: Byte = -1

/**
 * Fusion of the accelerometric mask with an external hypnogram and/or a manual diary
 * (`docs/workings/ALGO-v2.md` §3.6.4 and §3.6.5).
 *
 * **The reason this file exists is denominator independence, not accuracy.**
 * `aPLM-i = numerator(movement) / denominator(sleep deduced from the absence of movement)`: the two
 * terms come out of the same signal and are anti-correlated by construction, so the metric
 * self-amplifies. Health Connect breaks the loop because it is *independent* — other wrist, other
 * sensor, other algorithm, other device — and not because it brings in the stages. The manual
 * diary breaks it even more completely: two fields entered by hand depend on no signal at all.
 *
 * **Non-negotiable rule, applied here and not in the interface** (`SPEC-v2.md` §2.3): a
 * [MaskSource.ACCEL_IMMOBILITY] mask is [DenominatorIndependence.CIRCULAR] and can never carry the
 * primary result nor feed the trend. No function in this file "promotes" its independence: neither
 * the realignment nor the learned correction turns a circular denominator into an independent
 * denominator — they only make it less biased, which is an altogether different property.
 *
 * Pure functions: no wall clock, no randomness, no I/O.
 */
object MaskFusion {

    /** Comparison grid, in ms. Identical to `ImmobilityConfig.epochSec`: §3.6.4, point 1. */
    const val ALIGN_EPOCH_MS: Long = 5_000

    /** Beyond this, `HC_LAG_SUSPECT` flag (§3.6.4, point 1). */
    const val LAG_SUSPECT_MS: Long = 300_000

    fun lagSuspect(agreement: MaskAgreement): Boolean = abs(agreement.bestLagMs) > LAG_SUSPECT_MS

    /**
     * Times 1 and 2 of §3.6.4: **temporal realignment** then **agreement**.
     *
     * Why the realignment is not an affectation: the two devices have two clocks, and the sleep
     * onset latency really is different at the wrist and at the ankle. Two minutes of lag are
     * enough to tip movements from one side of sleep onset to the other, and therefore to impute
     * them to the wrong stage — and the AASM v3 requires precisely that part of each movement fall
     * within a sleep epoch. An uncorrected lag does not degrade the result "a little": it deletes
     * or manufactures events.
     *
     * @return Cohen's κ, ΔTST = `TST_HC − TST_accel` (positive = HC scores more sleep), the
     *   Jaccard overlap of the sleep periods in %, and the lag retained. All the quantities are
     *   evaluated **after** realignment. `NaN` if one of the masks is empty: an absence of
     *   measurement is not a disagreement of zero.
     */
    fun align(accel: SleepMask, hc: List<SleepWindow>, cfg: FusionConfig = FusionConfig()): MaskAgreement {
        val nothing = MaskAgreement(Double.NaN, Double.NaN, Double.NaN, 0L)
        if (accel.windows.isEmpty() || hc.isEmpty()) return nothing

        val endMs = max(lastEnd(accel.windows), lastEnd(hc))
        val nEpochs = (endMs / ALIGN_EPOCH_MS).toInt() + 1
        val a = binarize(accel.windows, nEpochs)
        val h = binarize(hc, nEpochs)

        val definedA = a.count { it >= 0 }
        val definedH = h.count { it >= 0 }
        if (definedA == 0 || definedH == 0) return nothing
        val reference = min(definedA, definedH)

        val step = max(1L, cfg.lagStepMs / ALIGN_EPOCH_MS)
        val maxShift = (cfg.maxLagMs / ALIGN_EPOCH_MS)
        var bestShift = 0L
        var bestScore = -1.0
        var bestFound = false
        // Sweep ordered by increasing |λ|, with **strict** improvement: at equal agreement, the
        // smallest lag wins. A realignment is a clock correction, not a fitting degree of freedom;
        // at a tie, correcting nothing is the honest conclusion.
        var d = 0L
        while (d <= maxShift) {
            for (sign in intArrayOf(1, -1)) {
                if (d == 0L && sign == -1) continue
                val shift = sign * d
                val score = agreementAt(a, h, shift, reference, cfg.minCoverage) ?: continue
                if (!bestFound || score > bestScore) {
                    bestFound = true
                    bestScore = score
                    bestShift = shift
                }
            }
            d += step
        }
        if (!bestFound) return nothing

        return MaskAgreement(
            kappa = kappaAt(a, h, bestShift),
            tstDeltaMin = sleepMinutes(hc) - accel.tstMin,
            overlapPct = jaccardAt(a, h, bestShift),
            bestLagMs = bestShift * ALIGN_EPOCH_MS,
        )
    }

    /**
     * Produces the mask that will carry the result, in strict order of preference: Health Connect,
     * then the manual diary, then — for want of anything better — the accelerometric mask
     * unchanged.
     *
     * The order is not an order of measurement quality, it is an order of **independence**. A watch
     * hypnogram is less accurate than a PSG and probably less accurate, on some nights, than our
     * own mask; it remains preferable because it does not share its error source with the
     * numerator. This is layer 3 of §3.6.3.
     *
     * @param hc Health Connect windows, already expressed in the same `msRel` reference as the
     *   accelerometric mask. `null` or empty = HC did not answer.
     * @param diary manual diary. Used as the **denominator** when HC is missing, and as a plain
     *   "in bed" bounding when HC answers.
     */
    fun fuse(
        accel: SleepMask,
        hc: List<SleepWindow>?,
        diary: DiaryWindow?,
        cfg: FusionConfig = FusionConfig(),
    ): SleepMask {
        val coverage = analysableCoverageOf(accel)
        if (hc != null && hc.isNotEmpty()) {
            val lag = align(accel, hc, cfg).bestLagMs
            val shifted = hc.map { SleepWindow(it.startMsRel + lag, it.endMsRel + lag, it.stage) }
            val windows = if (diary == null) shifted else clipTo(shifted, diary)
            return maskOf(
                windows = windows,
                source = MaskSource.FUSED,
                independence = DenominatorIndependence.INDEPENDENT_HC,
                coverage = coverage,
                lagAppliedMs = lag,
                // The denominator is HC's: no learned correction applies to it, even if the
                // accelerometric mask was carrying one.
                corrected = false,
            )
        }
        if (diary != null) return fromDiary(diary, coverage, MaskSource.FUSED)
        // Nothing independent to offer: we return the accelerometric mask **as it is**, with its
        // source and its circularity. Renaming it `FUSED` would suggest that a fusion took place.
        return accel
    }

    /**
     * The Health Connect mask alone, as it is stored in `NightAnalysis.masks`. No realignment: a
     * realignment only makes sense relative to another mask, and it is [align] that carries it.
     */
    fun fromHealthConnect(
        hc: List<SleepWindow>,
        analysableCoverage: Double = 1.0,
    ): SleepMask = maskOf(
        windows = hc,
        source = MaskSource.HEALTH_CONNECT,
        independence = DenominatorIndependence.INDEPENDENT_HC,
        coverage = analysableCoverage,
        lagAppliedMs = 0L,
        corrected = false,
    )

    /**
     * Manual diary mask — §3.6.5-a, "the cheapest and the best solution".
     *
     * The denominator produced is **time in bed**, strictly independent of the signal. It
     * overestimates the TST since it includes the WASO: the index comes out **deflated**, that is
     * to say biased on the cautious side (we under-diagnose rather than over-diagnose), and above
     * all biased by a quantity that **does not depend on the number of movements**. That is
     * exactly what we are after: a constant bias compares from one night to the next, a feedback
     * loop does not.
     *
     * The stage is undifferentiated [Stage.SLEEP]: a diary does not know the stages, and claiming
     * otherwise would manufacture an N1/N2/N3/REM breakdown out of nowhere.
     */
    fun fromDiary(
        diary: DiaryWindow,
        analysableCoverage: Double = 1.0,
        source: MaskSource = MaskSource.DIARY,
    ): SleepMask = maskOf(
        windows = listOf(SleepWindow(diary.bedTimeMsRel, diary.riseTimeMsRel, Stage.SLEEP)),
        source = source,
        independence = DenominatorIndependence.INDEPENDENT_DIARY,
        coverage = analysableCoverage,
        lagAppliedMs = 0L,
        corrected = false,
    )

    /**
     * Time 3 of §3.6.4 — **learned correction** `TST_HC ≈ alpha · TST_accel + beta`.
     *
     * @param pairs `(TST_accel, TST_HC)` of the nights that have both masks, in minutes.
     *
     * Three regimes, imposed by the sample size and not by taste:
     *  - `n < 3`: **no correction**. Two nights are enough to draw a perfect line and to export any
     *    aberration whatsoever onto all the following nights. All that remains then is the
     *    bracketing of time 4;
     *  - `3 ≤ n < 5`: **median of the ratio**, `beta = 0`. A single parameter, bounded, insensitive
     *    to an outlying point;
     *  - `n ≥ 5`: **Theil-Sen** (median of the pairwise slopes, then median of the intercepts).
     *    Breakdown point of 29 %, deterministic, and without the slightest external dependency —
     *    where least squares let themselves be carried away by a single badly segmented night.
     */
    fun fitCorrection(pairs: List<Pair<Double, Double>>): TstCorrection {
        val usable = pairs.filter { it.first.isFinite() && it.second.isFinite() && it.first > 0.0 }
        val n = usable.size
        if (n < 3) return TstCorrection(Double.NaN, Double.NaN, n, false)

        val alpha: Double
        val beta: Double
        if (n < 5) {
            alpha = medianD(DoubleArray(n) { usable[it].second / usable[it].first })
            beta = 0.0
        } else {
            val slopes = ArrayList<Double>(n * (n - 1) / 2)
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = usable[j].first - usable[i].first
                    if (abs(dx) < 1e-9) continue
                    slopes.add((usable[j].second - usable[i].second) / dx)
                }
            }
            if (slopes.isEmpty()) return TstCorrection(Double.NaN, Double.NaN, n, false)
            alpha = medianD(slopes.toDoubleArray())
            beta = medianD(DoubleArray(n) { usable[it].second - alpha * usable[it].first })
        }
        val valid = alpha.isFinite() && beta.isFinite() && alpha > 0.0
        return TstCorrection(alpha, beta, n, valid)
    }

    /**
     * Applies the correction to the **scalar quantities** of the mask, never to its windows.
     *
     * Redistributing 40 min of recovered sleep over specific windows would require knowing *where*
     * they were missing — precisely the information we do not have. The windows therefore remain
     * the raw scoring (they serve the attribution of the events) while the denominator carries the
     * correction; `corrected = true` signals this dissociation to the caller.
     *
     * `independence` stays [DenominatorIndependence.CIRCULAR]: recentring the mean of an estimator
     * does not make it independent of what it measures. The night-to-night feedback remains whole.
     */
    fun applyCorrection(accel: SleepMask, c: TstCorrection): SleepMask {
        if (!c.valid || accel.tstMin <= 0.0) return accel
        val corrected = (c.alpha * accel.tstMin + c.beta).coerceIn(0.0, accel.sptMin)
        val ratio = corrected / accel.tstMin
        return accel.copy(
            tstMin = corrected,
            wasoMin = accel.sptMin - corrected,
            analysableTstMin = accel.analysableTstMin * ratio,
            corrected = true,
        )
    }

    // --- Internal -----------------------------------------------------------------------------

    /**
     * Fraction of the night that is really analysable, measured on the SPT of the accelerometric
     * mask.
     *
     * This rate is a property of the **recording** — gaps, broken segments, off-body — and not of
     * the scoring: it therefore transfers legitimately to a mask built on another source, which by
     * definition has no idea of our own gaps. It is an estimate, and the only one available
     * without carrying the whole timeline all the way up to here.
     */
    private fun analysableCoverageOf(accel: SleepMask): Double =
        if (accel.sptMin > 0.0) (accel.analysableSptMin / accel.sptMin).coerceIn(0.0, 1.0) else 1.0

    private fun maskOf(
        windows: List<SleepWindow>,
        source: MaskSource,
        independence: DenominatorIndependence,
        coverage: Double,
        lagAppliedMs: Long,
        corrected: Boolean,
    ): SleepMask {
        val inBed = windows.filter { it.stage != Stage.OUT_OF_BED }
        val sptMin = if (inBed.isEmpty()) 0.0 else {
            (inBed.maxOf { it.endMsRel } - inBed.minOf { it.startMsRel }) / 60_000.0
        }
        val tstMin = sleepMinutes(windows)
        return SleepMask(
            windows = windows,
            source = source,
            sptMin = sptMin,
            tstMin = tstMin,
            wasoMin = max(0.0, sptMin - tstMin),
            analysableTstMin = tstMin * coverage,
            analysableSptMin = sptMin * coverage,
            corrected = corrected,
            lagAppliedMs = lagAppliedMs,
            independence = independence,
            // No fixed point is at stake: the denominator does not come out of the signal being
            // counted, so there is nothing that could oscillate. Reporting `false` would close the
            // publication gate for a reason that does not exist.
            fixedPointConverged = true,
        )
    }

    private fun clipTo(windows: List<SleepWindow>, diary: DiaryWindow): List<SleepWindow> =
        windows.mapNotNull {
            val a = max(it.startMsRel, diary.bedTimeMsRel)
            val b = min(it.endMsRel, diary.riseTimeMsRel)
            if (b > a) SleepWindow(a, b, it.stage) else null
        }

    private fun isSleep(s: Stage): Boolean =
        s == Stage.SLEEP || s == Stage.LIGHT || s == Stage.DEEP || s == Stage.REM

    private fun sleepMinutes(windows: List<SleepWindow>): Double {
        var acc = 0L
        for (w in windows) if (isSleep(w.stage)) acc += w.endMsRel - w.startMsRel
        return acc / 60_000.0
    }

    private fun lastEnd(windows: List<SleepWindow>): Long = windows.maxOf { it.endMsRel }

    /**
     * `1` = sleep, `0` = in bed but not asleep, `-1` = **not filled in**.
     *
     * The distinction between `0` and `-1` is what makes the agreement interpretable: outside its
     * window, a mask does not say "awake", it says nothing. Counting those as concordant wakes
     * would mechanically inflate κ with the length of the recording alone.
     */
    private fun binarize(windows: List<SleepWindow>, nEpochs: Int): ByteArray {
        val out = ByteArray(nEpochs) { UNDEFINED }
        for (w in windows) {
            if (w.stage == Stage.OUT_OF_BED) continue
            val v: Byte = if (isSleep(w.stage)) 1 else 0
            val from = (w.startMsRel / ALIGN_EPOCH_MS).toInt().coerceIn(0, nEpochs)
            val to = (w.endMsRel / ALIGN_EPOCH_MS).toInt().coerceIn(0, nEpochs)
            for (k in from until to) out[k] = v
        }
        return out
    }

    /** Raw agreement at a given lag, or `null` if the overlap is insufficient. */
    private fun agreementAt(
        a: ByteArray,
        h: ByteArray,
        shift: Long,
        reference: Int,
        minCoverage: Double,
    ): Double? {
        var both = 0
        var agree = 0
        for (k in a.indices) {
            val j = k - shift.toInt()
            if (j < 0 || j >= h.size) continue
            if (a[k] < 0 || h[j] < 0) continue
            both++
            if (a[k] == h[j]) agree++
        }
        if (both == 0 || both < minCoverage * reference) return null
        return agree.toDouble() / both
    }

    /**
     * Cohen's κ on the epochs where both masks are filled in.
     *
     * Degenerate case handled explicitly: if both masks see nothing but sleep, `pe = 1` and κ takes
     * the form 0/0. A perfect agreement on a single category brings no information beyond chance —
     * the convention retained is therefore `κ = 0` in case of disagreement and `κ = 1` when the
     * agreement is perfect, rather than a `NaN` that would propagate into the report.
     */
    private fun kappaAt(a: ByteArray, h: ByteArray, shift: Long): Double {
        var both = 0
        var agree = 0
        var a1 = 0
        var h1 = 0
        for (k in a.indices) {
            val j = k - shift.toInt()
            if (j < 0 || j >= h.size) continue
            if (a[k] < 0 || h[j] < 0) continue
            both++
            if (a[k] == h[j]) agree++
            if (a[k].toInt() == 1) a1++
            if (h[j].toInt() == 1) h1++
        }
        if (both == 0) return Double.NaN
        val p0 = agree.toDouble() / both
        val pa = a1.toDouble() / both
        val ph = h1.toDouble() / both
        val pe = pa * ph + (1 - pa) * (1 - ph)
        if (abs(1.0 - pe) < 1e-12) return if (p0 >= 1.0) 1.0 else 0.0
        return (p0 - pe) / (1.0 - pe)
    }

    /** Jaccard overlap of the sleep periods, in %. */
    private fun jaccardAt(a: ByteArray, h: ByteArray, shift: Long): Double {
        var inter = 0
        var union = 0
        for (k in a.indices) {
            val j = k - shift.toInt()
            val sa = if (a[k].toInt() == 1) 1 else 0
            val sh = if (j in h.indices && h[j].toInt() == 1) 1 else 0
            if (sa == 1 && sh == 1) inter++
            if (sa == 1 || sh == 1) union++
        }
        return if (union == 0) Double.NaN else 100.0 * inter / union
    }

    /** Exact median in `Double`: the fit bears on a few nights, so the precision is free here. */
    private fun medianD(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.copyOf()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }
}
