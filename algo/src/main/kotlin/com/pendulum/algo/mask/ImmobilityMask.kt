package com.pendulum.algo.mask

import com.pendulum.algo.dsp.Gravity
import com.pendulum.algo.dsp.Numeric
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import com.pendulum.algo.model.TriAxial
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Immobility mask parameters (`docs/workings/ALGO-v2.md` §3.6.2, table §6.6).
 *
 * @param epochSec duration of an epoch. **Fixed** in table §6.6 (van Hees 2015), but left
 *   constructible because the tests must be able to lower the grid without rewriting the module.
 * @param angleDeg orientation change threshold, in degrees (range 3–8). **Second most sensitive
 *   parameter of the whole chain: ±20 % move the index by 8 to 15 %.**
 * @param moveFactor additional amplitude criterion, in multiples of the noise floor (range 4–10).
 *   This is the addition with respect to van Hees: it catches the *vibratory* movements, which
 *   reorient nothing and are therefore strictly invisible to a purely angular criterion.
 * @param sustainedMin minimum duration of a bout of inactivity to count as sleep (range 3–10).
 * @param sptMinMin minimum duration of a bout to be able to **bound** the SPT (range 10–30).
 * @param maxFixedPointIterations §3.6.3 layer 2. **Fixed at 2**: a fixed point iterated without a
 *   bound on a non-monotonic criterion can oscillate. Verified by a `require`, not merely
 *   documented.
 * @param convergenceTstFraction relative TST gap beyond which the mask is declared non-convergent
 *   (range 0.15–0.40).
 * @param neutralizeMaxAngleDeg guard rail of layer 1: an epoch whose Δφ reaches the magnitude of a
 *   **posture change** (§6.4: `postureDeg` = 20°) is **never** neutralised, even if a CLM was
 *   detected in it. Without this guard rail, it would be enough for a leg movement to coincide
 *   with a roll-over to erase the only really reliable proof of wake that we have.
 * @param minEpochCoverage minimum fraction of usable samples for an epoch to be scorable; below
 *   that, the epoch is `UNKNOWN` — neither proof of sleep, nor proof of wake.
 */
data class ImmobilityConfig(
    val epochSec: Double = 5.0,
    val angleDeg: Double = 5.0,
    val moveFactor: Double = 6.0,
    val sustainedMin: Double = 5.0,
    val sptMinMin: Double = 15.0,
    val maxFixedPointIterations: Int = 2,
    val convergenceTstFraction: Double = 0.25,
    val neutralizeMaxAngleDeg: Double = 20.0,
    val minEpochCoverage: Double = 0.50,
)

/**
 * Output of the bounded fixed point (§3.6.3, layer 2). The intermediate quantities are exposed
 * because **non-convergence is a clinical signal**, not an implementation detail: it is what comes
 * up in `QualityReport.maskNonConvergent` and what closes the publication gate.
 *
 * @param provisionalTstMin TST of the M₀ mask, the one built **without** neutralisation. On a
 *   severely affected subject it is zero: that is exactly the failure mode described in §3.6.3,
 *   and keeping it makes it possible to measure it instead of merely suffering it.
 * @param tstDeltaFraction `|TST(M₁) − TST(M₀)| / TST(M₀)`. `NaN` if `TST(M₀)` is zero.
 */
data class FixedPointResult(
    val mask: SleepMask,
    val provisionalTstMin: Double,
    val tstDeltaFraction: Double,
    val iterations: Int,
)

/**
 * Step 6 — accelerometric sleep mask, **van Hees-type sustained inactivity** rule
 * (`docs/workings/ALGO-v2.md` §3.6.2), adapted to the ankle and made invariant by orientation.
 *
 * **What this file does not implement, and why.** Cole-Kripke is rejected (§3.6.1), with no
 * variant and no "adaptation": it consumes ActiGraph *activity counts* — a proprietary
 * transformation for which no published conversion from g exists — it is validated at the wrist,
 * and at the ankle it **overestimates the TST by +43 min**. Now an overestimated TST **deflates**
 * the index (+43 min over 420 → ×0.907: a true aPLM-i of 15.0 is displayed at 13.6, below the
 * screening threshold). The most accurate algorithm at the wrist is the one that makes the
 * diagnosis be missed at the ankle. The GGIR/van Hees algorithms are on the contrary the **only**
 * ones in the literature to show no significant difference between wrist and ankle — exactly the
 * property required here, since the position of the case varies from one night to the next.
 *
 * **Two deviations owned with respect to van Hees 2015.**
 *  1. The **angular Δ of the unit gravity vector** replaces the angle on a named axis. The original
 *     computes `atan(a_z / √(a_x²+a_y²))`, which presupposes a known anatomical orientation of the
 *     case. We do not know it, and it changes from one night to the next. `Δφ = angle(ĝ_k,
 *     ĝ_{k−1})` measures the same thing — the reorientation of the segment — without ever naming
 *     an axis: it is **invariant under a constant rotation** of the case, and therefore
 *     reproducible from one placement to the next.
 *  2. The **amplitude criterion** `amp_k < moveFactor · floor_k` is added to the angular criterion.
 *     A jolt that returns to its starting position (the typical case of a CLM, and of any
 *     vibratory movement) leaves **no angular trace at all**: without this second criterion, the
 *     mask would be blind to a whole category of mobility.
 *
 * **A known bias, owned, and corrected elsewhere**: van Hees at the ankle underestimates the TST
 * by −89 min in the only study available, which **inflates** the index by about 27 %. We trade a
 * bias of −9 % (Cole-Kripke) for a bias of +27 %, gaining site invariance. Neither of the two is
 * acceptable uncorrected: the correction lives in [MaskFusion].
 *
 * All the functions are pure. `fs` always comes from the signal, never from a constant; no wall
 * clock is read; the accumulations are done in `Double` — same input, same output.
 */
object ImmobilityMask {

    /**
     * Builds a mask in **one pass**.
     *
     * @param gravity `ĝ` estimated at step 1. The gravity channel holds its last value across the
     *   micro-gaps: we therefore do not expect `NaN` inside a segment.
     * @param env **coarse** envelope (0.50 s) from step 2. That is the one that carries the
     *   decision: the fine one keeps the ripple at `2f` and would manufacture mobile epochs at
     *   random.
     * @param floor adaptive noise floor from step 3, on the same grid.
     * @param segments continuous intervals. An epoch straddling a boundary is never compared with
     *   its neighbour: on either side, the state of the filters has nothing in common and the Δφ
     *   would only measure a reset transient.
     * @param offBody off-body periods. Neither sleep nor wake: they prove nothing.
     * @param ignoreIntervals intervals (typically the detected CLMs) **neutralised** as proof of
     *   mobility. Layer 1 of the answer to circularity (§3.6.3): a PLMS is by definition a
     *   movement **during** sleep; using it as proof of wake is a category error, and it is the
     *   one that makes the index of the most severely affected subject explode.
     * @param diary manual diary. It does **not** manufacture independence here (see the returned
     *   `independence` value): it bounds the **search** for the SPT, as van Hees 2015 required,
     *   which prevents a nap or a sofa immobility from pre-empting the start of the night.
     * @param blindZones gaps too long to be interpolated. **A parameter added at the end of the
     *   list, and deliberately not inserted after [offBody]**: `blindZones` and `ignoreIntervals`
     *   have the same type, and putting them side by side would mean that a positional call
     *   written against the §4.4 signature would compile silently with the two arguments swapped.
     *   A silent error on layer 1 is precisely what must not be made possible.
     *
     * `fixedPointConverged` is `true`: a single pass has **nothing** to converge. The flag only
     * makes sense when filled in by [fixedPoint], which alone has the two TSTs to compare.
     */
    fun build(
        gravity: TriAxial,
        env: Signal1D,
        floor: Signal1D,
        segments: List<Segment>,
        offBody: List<Segment>,
        ignoreIntervals: List<Segment> = emptyList(),
        diary: DiaryWindow? = null,
        cfg: ImmobilityConfig = ImmobilityConfig(),
        blindZones: List<Segment> = emptyList(),
    ): SleepMask =
        Scorer(gravity, env, floor, segments, offBody, ignoreIntervals, diary, cfg, blindZones)
            .run()
            .toMask(converged = true)

    /**
     * Layer 2 of the answer to circularity — **fixed point bounded to two iterations** (§3.6.3).
     *
     * ```
     * 1. M₀: immobility WITHOUT ignoreIntervals          (degraded, but it bounds the SPT)
     * 2. C₀: movement intervals obtained with M₀
     * 3. M₁: immobility WITH ignoreIntervals = C₀
     * 4. |TST(M₁) − TST(M₀)| < convergenceTstFraction · TST(M₀) ?  otherwise MASK_NON_CONVERGENT
     * ```
     *
     * **Two iterations, never more.** This is not a saving of computation: layer 1 *removes* the
     * feedback loop instead of attenuating it, so an extra pass would bring nothing, whereas an
     * unbounded fixed point on a non-monotonic criterion can oscillate indefinitely between two
     * equally defensible scorings.
     *
     * **A consequence to be known and owned.** Layer 1 can only *give back* sleep (it never turns
     * an immobile epoch into a mobile one), hence `TST(M₁) ≥ TST(M₀)`. On a severely affected
     * subject, `TST(M₀)` collapses to zero and the relative gap explodes: the night comes out
     * **non-convergent**, and the publication gate refuses the aPLM-i. This is intended — on such
     * a night, the accelerometric TST is simply not determinable — and it has no consequence for
     * the follow-up metric: the Periodicity Index and the fundamental rhythm have no temporal
     * denominator and survive `NO_PLMI`. The right answer to this case is not to loosen the
     * criterion, it is to supply an independent denominator (layer 3).
     *
     * @param movementIntervalsOf injected detection: `M → movement intervals to neutralise`.
     *   Passed as a lambda so that `mask` does not depend on `detect` — the natural dependency goes
     *   the other way (the series consume the mask), and closing it here would create a cycle.
     *   The caller plugs in `clms.filter { it.isClm }.map { Segment(it.onsetIdx, it.offsetIdx) }`.
     */
    fun fixedPoint(
        gravity: TriAxial,
        env: Signal1D,
        floor: Signal1D,
        segments: List<Segment>,
        offBody: List<Segment>,
        diary: DiaryWindow? = null,
        cfg: ImmobilityConfig = ImmobilityConfig(),
        blindZones: List<Segment> = emptyList(),
        movementIntervalsOf: (SleepMask) -> List<Segment>,
    ): FixedPointResult {
        require(cfg.maxFixedPointIterations in 1..2) {
            "maxFixedPointIterations is fixed at 1 or 2 (§6.6): beyond, the fixed point can oscillate"
        }
        val m0 = build(gravity, env, floor, segments, offBody, emptyList(), diary, cfg, blindZones)
        if (cfg.maxFixedPointIterations == 1) {
            return FixedPointResult(m0, m0.tstMin, 0.0, 1)
        }
        val ignore = movementIntervalsOf(m0)
        val m1 = build(gravity, env, floor, segments, offBody, ignore, diary, cfg, blindZones)

        val delta = abs(m1.tstMin - m0.tstMin)
        val fraction = if (m0.tstMin > 0.0) delta / m0.tstMin else Double.NaN
        // `TST(M₀) == 0` is not a perfect convergence, it is the collapse described above: nothing
        // can be concluded from a ratio whose denominator is zero, hence non-convergent.
        val converged = fraction.isFinite() && fraction < cfg.convergenceTstFraction
        return FixedPointResult(
            mask = m1.copy(fixedPointConverged = converged),
            provisionalTstMin = m0.tstMin,
            tstDeltaFraction = fraction,
            iterations = 2,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Epoch scoring
// ---------------------------------------------------------------------------------------------

private const val ST_UNKNOWN: Byte = 0
private const val ST_IMMOBILE: Byte = 1
private const val ST_MOBILE: Byte = 2

/** Intermediate result of the scoring: everything needed to build the [SleepMask]. */
private class Scored(
    val fsHz: Double,
    val epochLen: Int,
    val epochSec: Double,
    /** `ST_*` after neutralisation (layer 1). */
    val state: ByteArray,
    /** Immobile epoch belonging to a bout of at least `sustainedMin`. */
    val sleepEpoch: BooleanArray,
    val sptFromEpoch: Int,
    val sptToEpoch: Int,
    val analysable: IntervalSet,
) {
    private fun msOf(idx: Int): Long = Math.round(idx * 1000.0 / fsHz)

    private fun stageOf(k: Int): Stage = when {
        sleepEpoch[k] -> Stage.SLEEP
        state[k] == ST_UNKNOWN -> Stage.UNKNOWN
        // Immobile but within a bout that is too short: this is quiet wake in bed, not sleep.
        // Grouping it with movement is the letter of van Hees (sleep is *sustained* inactivity,
        // never instantaneous inactivity).
        else -> Stage.AWAKE_IN_BED
    }

    fun toMask(converged: Boolean): SleepMask {
        if (sptToEpoch <= sptFromEpoch) {
            return SleepMask(
                windows = emptyList(),
                source = MaskSource.ACCEL_IMMOBILITY,
                sptMin = 0.0, tstMin = 0.0, wasoMin = 0.0,
                analysableTstMin = 0.0, analysableSptMin = 0.0,
                corrected = false, lagAppliedMs = 0L,
                independence = DenominatorIndependence.CIRCULAR,
                fixedPointConverged = converged,
            )
        }

        val windows = ArrayList<SleepWindow>()
        var runStart = sptFromEpoch
        var runStage = stageOf(sptFromEpoch)
        for (k in sptFromEpoch + 1..sptToEpoch) {
            val s = if (k < sptToEpoch) stageOf(k) else null
            if (s != runStage) {
                windows.add(
                    SleepWindow(msOf(runStart * epochLen), msOf(k * epochLen), runStage),
                )
                if (s == null) break
                runStart = k
                runStage = s
            }
        }

        // The SPT is aligned on the epoch grid, so `waso = spt − tst` is exact: not a millisecond
        // is lost between the two counts.
        var sleepEpochs = 0
        for (k in sptFromEpoch until sptToEpoch) if (sleepEpoch[k]) sleepEpochs++
        val sptEpochs = sptToEpoch - sptFromEpoch
        val sptMin = sptEpochs * epochSec / 60.0
        val tstMin = sleepEpochs * epochSec / 60.0

        // Exposed denominator = **analysable** sleep time (§2.4 and `SPEC-v2.md` §2.3):
        // TST ∩ valid segments ∩ outside blind zones ∩ outside off-body. Counting movements over a
        // duration during which none could have been seen inflates the denominator and deflates
        // the index — in the exact direction that makes a screening miss its case. Never raw TST.
        var analysableTstSamples = 0L
        for (w in windows) {
            if (w.stage != Stage.SLEEP) continue
            val a = Math.round(w.startMsRel * fsHz / 1000.0).toInt()
            val b = Math.round(w.endMsRel * fsHz / 1000.0).toInt()
            analysableTstSamples += analysable.intersectLength(a, b).toLong()
        }
        val analysableSptSamples =
            analysable.intersectLength(sptFromEpoch * epochLen, sptToEpoch * epochLen).toLong()
        val toMin = 1.0 / (fsHz * 60.0)

        return SleepMask(
            windows = windows,
            source = MaskSource.ACCEL_IMMOBILITY,
            sptMin = sptMin,
            tstMin = tstMin,
            wasoMin = sptMin - tstMin,
            analysableTstMin = analysableTstSamples * toMin,
            analysableSptMin = analysableSptSamples * toMin,
            corrected = false,
            lagAppliedMs = 0L,
            // **Non-negotiable rule** (`SPEC-v2.md` §2.3): a mask derived from the accelerometer is
            // circular — numerator and denominator come out of the same signal and are
            // anti-correlated by construction — and can therefore NEVER carry the primary result.
            // The manual diary passed to `build` changes nothing to that: it bounds the search for
            // the SPT, it does not supply the denominator. An independent denominator is produced
            // in [MaskFusion].
            independence = DenominatorIndependence.CIRCULAR,
            fixedPointConverged = converged,
        )
    }
}

private class Scorer(
    private val gravity: TriAxial,
    private val env: Signal1D,
    private val floor: Signal1D,
    segments: List<Segment>,
    offBody: List<Segment>,
    ignoreIntervals: List<Segment>,
    private val diary: DiaryWindow?,
    private val cfg: ImmobilityConfig,
    blindZones: List<Segment>,
) {
    private val fs = env.fsHz
    private val n = env.n

    /** Scorable = inside a continuous segment and outside the excluded off-body. */
    private val scorable = IntervalSet.of(segments).minus(IntervalSet.of(offBody))

    /** Analysable = scorable, minus the blind zones. Serves the denominator only. */
    private val analysable = scorable.minus(IntervalSet.of(blindZones))
    private val ignore = IntervalSet.of(ignoreIntervals)

    private val epochLen = Numeric.samples(cfg.epochSec, fs)
    private val nEpochs = n / epochLen

    private val state = ByteArray(nEpochs)
    private val dphiDeg = FloatArray(nEpochs)
    private val ux = DoubleArray(nEpochs)
    private val uy = DoubleArray(nEpochs)
    private val uz = DoubleArray(nEpochs)
    private val hasDir = BooleanArray(nEpochs)
    private val scratch = FloatArray(epochLen)

    init {
        require(fs > 0.0) { "fsHz must be > 0" }
        require(gravity.n == n && floor.n == n) { "gravity, envelope and floor must share the grid" }
        require(cfg.epochSec > 0.0 && cfg.sustainedMin > 0.0) { "strictly positive durations expected" }
    }

    fun run(): Scored {
        scoreRaw()
        neutralize()
        val sleepEpoch = sustainedBouts()
        val (from, to) = sptBounds()
        return Scored(fs, epochLen, cfg.epochSec, state, sleepEpoch, from, to, analysable)
    }

    // --- 1. raw state ------------------------------------------------------------------------

    private fun scoreRaw() {
        val minValid = (cfg.minEpochCoverage * epochLen).toInt().coerceAtLeast(1)
        val bedIdx = diary?.let { Math.round(it.bedTimeMsRel * fs / 1000.0).toInt() } ?: Int.MIN_VALUE
        val riseIdx = diary?.let { Math.round(it.riseTimeMsRel * fs / 1000.0).toInt() } ?: Int.MAX_VALUE

        for (k in 0 until nEpochs) {
            val a = k * epochLen
            val b = a + epochLen

            // An epoch is scorable only if the recording really covers it. Below that, it is
            // neither proof of sleep nor proof of wake: UNKNOWN, and nothing else.
            if (scorable.intersectLength(a, b) < minValid) continue

            // The diary bounds the **search**: outside the declared window, no bout can open or
            // close the SPT. Without this, a sofa immobility before bedtime pre-empts the start of
            // the night and lengthens the SPT by several tens of minutes.
            if (a < bedIdx || b > riseIdx) continue

            meanUnitGravity(a, b)
            if (!hasDir[k]) continue

            // Δφ against the previous epoch, and only if the two are **contiguous within the same
            // segment**: across a boundary, the filters have been reset and the angle would
            // measure a warm-up transient, not a movement of the subject.
            val contiguous = k > 0 && hasDir[k - 1] && scorable.containsRange(a - 1, a + 1)
            val d = if (contiguous) {
                Gravity.angleDeg(
                    ux[k - 1].toFloat(), uy[k - 1].toFloat(), uz[k - 1].toFloat(),
                    ux[k].toFloat(), uy[k].toFloat(), uz[k].toFloat(),
                )
            } else {
                0f // no comparison possible = absence of proof, never proof of wake
            }
            dphiDeg[k] = if (d.isNaN()) 0f else d

            val amp = Numeric.percentile(env.v, a, b, 95.0, scratch)
            val fl = Numeric.median(floor.v, a, b, scratch)
            val amplitudeMobile = !amp.isNaN() && !fl.isNaN() && amp >= cfg.moveFactor * fl
            val angularMobile = dphiDeg[k] > cfg.angleDeg

            state[k] = if (angularMobile || amplitudeMobile) ST_MOBILE else ST_IMMOBILE
        }
    }

    /**
     * `ĝ_k` = mean of the **unit** gravity vectors of the epoch, renormalised.
     *
     * Normalise each sample *before* averaging, and not the other way round: the mean of the raw
     * vectors is weighted by the norm, so that a second where `‖ĝ‖` drifts to 1.1 g weighs 10 %
     * more in the mean direction. We are measuring an orientation; the magnitude has no business
     * in it.
     */
    private fun meanUnitGravity(a: Int, b: Int) {
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var cnt = 0
        for (i in a until b) {
            val x = gravity.x[i].toDouble()
            val y = gravity.y[i].toDouble()
            val z = gravity.z[i].toDouble()
            val norm = sqrt(x * x + y * y + z * z)
            if (norm.isNaN() || norm <= 1e-9) continue
            sx += x / norm; sy += y / norm; sz += z / norm; cnt++
        }
        val k = a / epochLen
        if (cnt == 0) return
        val norm = sqrt(sx * sx + sy * sy + sz * sz)
        if (norm <= 1e-9) return // epoch whose directions cancel out: no mean direction at all
        ux[k] = sx / norm; uy[k] = sy / norm; uz[k] = sz / norm
        hasDir[k] = true
    }

    // --- 2. layer 1: neutralisation of the periodic movements ---------------------------------

    /**
     * Layer 1 of §3.6.3 — **make the mask blind to the periodic movements**.
     *
     * The statement of the problem, with figures: the rule requires ≥ 5 consecutive min without
     * movement, yet a series at IMI 22 s places ~13 movements in *any* 5 min window. The
     * probability that a 5 min window is free of any movement during a series is **nil**. Applied
     * naively, the mask therefore scores the whole crisis period as wake: the TST collapses, the
     * index explodes, and at the same time the AASM rule "at least part of the movement within a
     * sleep epoch" suppresses the movements themselves. The most severely affected subject is the
     * one for whom the algorithm behaves worst — a disqualifying failure mode.
     *
     * The tenable clinical position, and the only one: a PLMS is *by definition* a movement
     * **during** sleep. The AASM scores PLMS *within* sleep epochs; a leg movement does not make
     * the epoch a wake one, barring a cortical arousal criterion, which we cannot evaluate without
     * EEG. What remains as proof of wake: **gross** body movements, **posture changes**, and
     * sustained mobility not attributable to a periodic movement — that is to say everything the
     * caller has not placed in `ignoreIntervals`.
     *
     * Two invariants hold the implementation together:
     *  - the rescoring **always** reads the raw states, never states that have already been
     *    rescored: without this the traversal order would change the result, and bit-level
     *    determinism would fall;
     *  - it can only turn MOBILE → IMMOBILE. It never manufactures wake, which is what makes
     *    `TST(M₁) ≥ TST(M₀)` and gives its meaning to the layer 2 convergence check.
     */
    private fun neutralize() {
        if (ignore.isEmpty()) return
        val raw = state.copyOf()
        val neutral = BooleanArray(nEpochs)
        for (k in 0 until nEpochs) {
            val a = k * epochLen
            neutral[k] = ignore.intersectLength(a, a + epochLen) > 0
        }
        for (k in 0 until nEpochs) {
            if (raw[k] != ST_MOBILE || !neutral[k]) continue
            // Posture guard rail: a persistent reorientation of the magnitude of a roll-over is
            // not neutralisable. It is the only really reliable proof of wake the device has, and
            // a coinciding CLM must not be enough to erase it.
            if (dphiDeg[k] > cfg.neutralizeMaxAngleDeg) continue
            if (nearestNonNeutralState(raw, neutral, k) == ST_IMMOBILE) state[k] = ST_IMMOBILE
        }
    }

    /**
     * Nearest non-neutralised neighbour interpolation. Distance tie → **the left one wins**,
     * arbitrarily but in a fixed way: determinism matters more than the choice. No usable
     * neighbour (a series covering the whole night) → `ST_IMMOBILE`: in that case the only
     * mobility observed is precisely the one we decided not to count.
     */
    private fun nearestNonNeutralState(raw: ByteArray, neutral: BooleanArray, k: Int): Byte {
        var d = 1
        while (d < nEpochs) {
            val l = k - d
            if (l >= 0 && !neutral[l] && raw[l] != ST_UNKNOWN) return raw[l]
            val r = k + d
            if (r < nEpochs && !neutral[r] && raw[r] != ST_UNKNOWN) return raw[r]
            if (l < 0 && r >= nEpochs) break
            d++
        }
        return ST_IMMOBILE
    }

    // --- 3. sustained inactivity bouts and SPT bounds ------------------------------------------

    private fun sustainedBouts(): BooleanArray {
        val sleep = BooleanArray(nEpochs)
        val minEpochs = boutEpochs(cfg.sustainedMin)
        forEachBout { from, to -> if (to - from >= minEpochs) for (k in from until to) sleep[k] = true }
        return sleep
    }

    /**
     * `SPT = from the start of the first bout ≥ sptMinMin to the end of the last one`.
     *
     * The SPT thus depends only on the **two extreme transitions** of the night, which are far
     * from the movement-dense core: it is almost insensitive to circularity, where the TST is
     * directly exposed to it through the WASO. That is what makes `plmiSpt` a defensible fallback
     * in the absence of an independent denominator (§3.6.5-b).
     */
    private fun sptBounds(): Pair<Int, Int> {
        val minEpochs = boutEpochs(cfg.sptMinMin)
        var first = -1
        var last = -1
        forEachBout { from, to ->
            if (to - from >= minEpochs) {
                if (first < 0) first = from
                last = to
            }
        }
        return if (first < 0) 0 to 0 else first to last
    }

    private fun boutEpochs(minutes: Double): Int =
        Math.ceil(minutes * 60.0 / cfg.epochSec).toInt().coerceAtLeast(1)

    /**
     * Walks the maximal ranges of consecutive `ST_IMMOBILE` epochs. An `UNKNOWN` epoch **breaks**
     * the range: the continuity of an immobility cannot be certified across a period during which
     * nothing was being measured, and the opposite would pass a watch left on the bedside table
     * off as sleep.
     */
    private inline fun forEachBout(action: (Int, Int) -> Unit) {
        var k = 0
        while (k < nEpochs) {
            if (state[k] != ST_IMMOBILE) { k++; continue }
            var e = k
            while (e < nEpochs && state[e] == ST_IMMOBILE) e++
            action(k, e)
            k = e
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Interval set
// ---------------------------------------------------------------------------------------------

/**
 * Normalised union of sample-index intervals `[from, to)` — sorted, disjoint, merged. Used
 * exclusively for the arithmetic of the analysable denominator, where the slightest double
 * counting translates directly into an error on the index.
 */
internal class IntervalSet private constructor(private val from: IntArray, private val to: IntArray) {

    fun isEmpty(): Boolean = from.isEmpty()

    /** Number of samples of `[a, b)` covered by the set. */
    fun intersectLength(a: Int, b: Int): Int {
        if (b <= a) return 0
        var acc = 0
        var i = lowerBound(a)
        while (i < from.size && from[i] < b) {
            val lo = if (from[i] > a) from[i] else a
            val hi = if (to[i] < b) to[i] else b
            if (hi > lo) acc += hi - lo
            i++
        }
        return acc
    }

    fun containsRange(a: Int, b: Int): Boolean = b <= a || intersectLength(a, b) == b - a

    fun minus(other: IntervalSet): IntervalSet {
        if (other.isEmpty() || isEmpty()) return this
        val outFrom = ArrayList<Int>(from.size)
        val outTo = ArrayList<Int>(from.size)
        for (i in from.indices) {
            var cur = from[i]
            var j = other.lowerBound(cur)
            while (j < other.from.size && other.from[j] < to[i]) {
                if (other.from[j] > cur) { outFrom.add(cur); outTo.add(other.from[j]) }
                if (other.to[j] > cur) cur = other.to[j]
                if (cur >= to[i]) break
                j++
            }
            if (cur < to[i]) { outFrom.add(cur); outTo.add(to[i]) }
        }
        return IntervalSet(outFrom.toIntArray(), outTo.toIntArray())
    }

    /** First interval whose upper bound exceeds `x`. Binary search. */
    private fun lowerBound(x: Int): Int {
        var lo = 0
        var hi = from.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (to[mid] <= x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        fun of(segments: List<Segment>): IntervalSet {
            if (segments.isEmpty()) return IntervalSet(IntArray(0), IntArray(0))
            val sorted = segments.filter { it.toIdx > it.fromIdx }
                .sortedWith(compareBy<Segment>({ it.fromIdx }, { it.toIdx }))
            if (sorted.isEmpty()) return IntervalSet(IntArray(0), IntArray(0))
            val f = ArrayList<Int>(sorted.size)
            val t = ArrayList<Int>(sorted.size)
            var curFrom = sorted[0].fromIdx
            var curTo = sorted[0].toIdx
            for (i in 1 until sorted.size) {
                val s = sorted[i]
                if (s.fromIdx <= curTo) {
                    if (s.toIdx > curTo) curTo = s.toIdx
                } else {
                    f.add(curFrom); t.add(curTo)
                    curFrom = s.fromIdx; curTo = s.toIdx
                }
            }
            f.add(curFrom); t.add(curTo)
            return IntervalSet(f.toIntArray(), t.toIntArray())
        }
    }
}
