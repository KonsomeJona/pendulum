package com.pendulum.algo.detect

import com.pendulum.algo.dsp.Gravity
import com.pendulum.algo.dsp.Numeric
import com.pendulum.algo.dsp.ThresholdParams
import com.pendulum.algo.dsp.Thresholds
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.DualEnvelope
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.TriAxial
import kotlin.math.max

/**
 * Detection thresholds as seen by the detector (`docs/workings/ALGO-v2.md` §2 step 4, parameters §6.3).
 *
 * Twin of `com.pendulum.algo.dsp.ThresholdParams`, which carries the same four values: `dsp`
 * computes the curves, `detect` decides. The detector converts its configuration into
 * [ThresholdParams] rather than the reverse, so that `dsp` never depends on `detect`.
 *
 * **On `kOn = 8.0`.** This KDoc long described 8.0 as an anti-artefact budget inherited from the
 * first specification "for want of anything better". The sweep in `ThresholdPolicySweepTest` —
 * `k_on` from 4 to 12, step 1.0, 20 seeds — contradicts both halves of that sentence, and both
 * halves are needed:
 *
 *  - **`k_on` is not free.** The three criteria of T5 only hold at 8.0: the sensitivity at the
 *    "8x" point is 1.000 for `k_on <= 7` and 0.000 for `k_on >= 9`. The reason must be read in
 *    full, because it does no credit to T5: the abscissa of T5 is the **effective** floor
 *    `Theta_on / k_on`, and on its quiet, uncalibrated night it is `Theta_abs` that governs the
 *    threshold. "8x the effective floor" is therefore `8 x Theta_abs / k_on`, which equals the
 *    threshold only if `k_on = 8`. **T5 does not validate 8.0, it writes it into its own
 *    statement.** That is a reason not to move `k_on` without rewriting T5; it is not proof that
 *    8.0 is the right value.
 *  - **`k_on` is not the dominant parameter on a calibrated night.** `Theta_on` stays pinned there
 *    at 53.7 mg from `k_on = 4` to `k_on = 12`, because the third term — `f_cal x gainCal` — wins
 *    over the whole range. The recall of T6 (0.265 to 0.276) and the undercount of T22 (0.697) do
 *    not move across the sweep. What governs the undercount is `calFraction`, not `k_on`.
 *
 * What `k_on` really changes on a calibrated night goes through [ClmConfig.grossBodyFactor], whose
 * reference amplitude is `Theta_on / k_on`: precision above the threshold rises from 0.768 to
 * 0.956 across the sweep while sensitivity stays flat at ~0.90. `k_on` therefore acts there as a
 * gross body movement rejection setting, which is the opposite of the usual reading.
 *
 * What remains true of the original sentence: 8.0 is not dictated by thermal noise, 4.8 would be
 * enough for that. The full table is in `docs/07-validation.md` §4.1.
 *
 * @param kOn floor multiplier on attack (range 5-12). Fixed at 8.0: see above.
 * @param kOff floor multiplier on release (range 2.0-4.0). Hysteresis = kOn/kOff.
 * @param absFloorG absolute floor, in g (range 0.010-0.050).
 * @param calFraction fraction of the calibration gain (range 0.08-0.20).
 */
data class ThresholdConfig(
    val kOn: Double = 8.0,
    val kOff: Double = 2.5,
    val absFloorG: Float = 0.020f,
    val calFraction: Double = 0.12,
) {
    fun toParams(): ThresholdParams = ThresholdParams(kOn, kOff, absFloorG, calFraction)
}

/**
 * Parameters of LM detection and of their classification into CLM (§2 step 5, §6.3).
 *
 * `offHoldSec`, `minDurSec` and `maxDurSec` are marked **fixed** in table §6.3 (literal AASM/WASM
 * rule): they are readable but **not constructible**, so that no caller can make them vary. The
 * variation of the clinical rules is test T14 — which changes rule set in [SeriesConfig] — not
 * T12, which sweeps the processing parameters.
 *
 * @param morphologyWinSec window of the WASM 3.2.1-d morphology criterion (range 0.3-0.8).
 * @param grossBodyFactor gross body movement threshold, in x effective floor (range 25-60).
 * @param refractorySec refractory period on either side of a GBM (range 1-4).
 * @param minExcursionDeg below this `tilt` excursion, `TRANSMITTED_SUSPECT` marking (range 0.5-4).
 */
data class ClmConfig(
    val thresholds: ThresholdConfig = ThresholdConfig(),
    val morphologyWinSec: Double = 0.50,
    val grossBodyFactor: Double = 40.0,
    val refractorySec: Double = 2.0,
    val minExcursionDeg: Double = 1.5,
) {
    /** Hold duration below the off threshold that dates the offset. Fixed, clinical. */
    val offHoldSec: Double get() = OFF_HOLD_SEC

    /** Minimum duration of an LM. Fixed (AASM VII / WASM 3.3.1). */
    val minDurSec: Double get() = MIN_DUR_SEC

    /** Maximum duration of a CLM. Fixed. Beyond it: long LM, which **breaks** the series. */
    val maxDurSec: Double get() = MAX_DUR_SEC

    companion object {
        const val OFF_HOLD_SEC: Double = 0.50
        const val MIN_DUR_SEC: Double = 0.50
        const val MAX_DUR_SEC: Double = 10.0

        /** §3.2: duration bound of the `TRANSMITTED_SUSPECT` marker. Engineering constant. */
        const val TRANSMITTED_MAX_DUR_SEC: Double = 1.50
    }
}

/**
 * LM detection state machine, then classification into CLM. `docs/workings/ALGO-v2.md` §2 step 5.
 *
 * Sequence, literal:
 *  1. provisional onset when `Theta_on` is crossed on the **coarse** envelope;
 *  2. provisional offset at the **start** of a period of at least `offHoldSec` below `Theta_off` —
 *     this is the letter of the AASM/WASM rule ("the START of a period lasting at least 0.5 s
 *     during which the EMG does not exceed..."), and it is also what **merges** two bursts
 *     separated by less than 0.5 s without a dedicated merging step. §1.5-iv: the v1 rule
 *     "merging of CLM spaced < 0.5 s apart" is a confusion with the **bilateral** combination
 *     rule, which does not apply to a single leg; correctly implemented, the offset does it on
 *     its own;
 *  3. realignment of both edges on the **fine** envelope;
 *  4. WASM 3.2.1-d morphology criterion: a `morphologyWinSec` window whose **median** of the fine
 *     envelope exceeds `Theta_off`. It is the best anti-mattress filter available in the corpus of
 *     rules (§3.2): a transmitted vibration is a 0.05-0.4 s ringing, high peak and low median, and
 *     it is the only filter in this catalogue that is a published clinical rule rather than an
 *     invention;
 *  5. classification by duration, then GBM with refractory period, posture, blind zone.
 *
 * Priority order of the single reject reason carried by [Clm.reject]:
 * `MORPHOLOGY` > `TRUNCATED` > `TOO_SHORT` > `BLIND_ZONE` > `POSTURAL` > `GROSS_BODY`.
 * Morphology comes before duration because the specification evaluates it at step 5.4, before
 * classification 5.5; consequence worth knowing: with `morphologyWinSec == minDurSec` (default),
 * `TOO_SHORT` is only reachable by lowering `morphologyWinSec`. The **flags**, by contrast, are
 * cumulative: one event can carry both `POSTURAL` and `GROSS_BODY`.
 *
 * All the events of the state machine are returned, including the rejected ones: the quality
 * report needs them, and [SeriesBuilder] needs the `LM_LONG` and the `TRUNCATED` ones to break the
 * series at the right place. Consumers filter with [Clm.isClm].
 */
object ClmDetector {

    fun detect(
        env: DualEnvelope,
        floor: Signal1D,
        floorExtrapolated: BooleanArray,
        gravity: TriAxial,
        segments: List<Segment>,
        blindZones: List<Segment>,
        postures: List<PostureChange>,
        calibration: NightCalibration,
        cfg: ClmConfig = ClmConfig(),
        postureCfg: PostureConfig = PostureConfig(),
    ): List<Clm> {
        val n = env.coarse.n
        require(env.coarse.fsHz > 0.0) { "fsHz must be > 0" }
        require(env.fine.n == n && floor.n == n && floorExtrapolated.size == n) {
            "envelopes, floor and flags must share the grid"
        }
        require(gravity.n == n) { "gravity must share the envelope grid" }
        return Detection(
            env, floor, floorExtrapolated, gravity, blindZones, postures, calibration, cfg, postureCfg,
        ).detectAll(segments)
    }
}

/** Working state of one call to [ClmDetector.detect]. Nothing gets out, nothing gets in: pure. */
private class Detection(
    env: DualEnvelope,
    floor: Signal1D,
    private val floorExtrapolated: BooleanArray,
    private val gravity: TriAxial,
    private val blindZones: List<Segment>,
    private val postures: List<PostureChange>,
    calibration: NightCalibration,
    private val cfg: ClmConfig,
    private val postureCfg: PostureConfig,
) {
    private val coarse = env.coarse.v
    private val fine = env.fine.v
    private val fl = floor.v
    private val fs = env.coarse.fsHz
    private val params = cfg.thresholds.toParams()
    private val gain = calibration.gainCalG

    private val offHold = samplesOf(cfg.offHoldSec, fs).coerceAtLeast(1)
    private val morphoWin = samplesOf(cfg.morphologyWinSec, fs).coerceAtLeast(1)
    private val minDur = samplesOf(cfg.minDurSec, fs)
    private val maxDur = samplesOf(cfg.maxDurSec, fs)
    private val tau = samplesOf(postureCfg.tauSec, fs)
    private val guard = samplesOf(postureCfg.guardSec, fs)
    private val refractory = samplesOf(cfg.refractorySec, fs)
    private val transmittedMaxDur = samplesOf(ClmConfig.TRANSMITTED_MAX_DUR_SEC, fs)

    /** Working buffer for the sliding medians: avoids one allocation per window. */
    private var scratch = FloatArray(morphoWin.coerceAtLeast(64))

    private fun on(i: Int): Float = Thresholds.onAt(fl[i], gain, params)
    private fun off(i: Int): Float = Thresholds.offAt(fl[i], gain, params)

    /**
     * **Effective** floor: `Theta_on / kOn`. It is this one, and not the raw `floor`, that serves
     * as the amplitude reference for the GBM criterion — otherwise a null floor would make the
     * criterion degenerate (every event would satisfy `peak >= 40 x 0`).
     */
    private fun effectiveFloor(i: Int): Float = (on(i) / params.kOn).toFloat()

    private fun scratchOf(len: Int): FloatArray {
        if (scratch.size < len) scratch = FloatArray(len)
        return scratch
    }

    fun detectAll(segments: List<Segment>): List<Clm> {
        val raw = ArrayList<Clm>()
        for (seg in segments) {
            var i = seg.fromIdx
            while (i < seg.toIdx) {
                if (!(coarse[i] >= on(i))) {
                    i++
                    continue
                }
                val onsetProv = i

                // --- 2. provisional offset: START of first `offHoldSec` period below Theta_off.
                var quietStart = -1
                var offsetProv = -1
                var resume = seg.toIdx
                var j = onsetProv
                while (j < seg.toIdx) {
                    if (coarse[j] < off(j)) {
                        if (quietStart < 0) quietStart = j
                        if (j - quietStart + 1 >= offHold) {
                            offsetProv = quietStart
                            resume = j + 1
                            break
                        }
                    } else {
                        quietStart = -1
                    }
                    j++
                }
                val truncatedEnd = offsetProv < 0
                if (truncatedEnd) offsetProv = seg.toIdx

                // --- 3. realignment of the edges on the fine envelope.
                var k = onsetProv
                while (k >= seg.fromIdx && !(fine[k] < off(k))) k--
                val truncatedStart = k < seg.fromIdx
                val onsetIdx = if (truncatedStart) seg.fromIdx else minOf(onsetProv, k + 1)

                val offsetIdx: Int
                if (truncatedEnd) {
                    offsetIdx = seg.toIdx
                } else {
                    var m = offsetProv - 1
                    while (m > onsetIdx && !(fine[m] >= off(m))) m--
                    offsetIdx = (m + 1).coerceIn(onsetIdx + 1, seg.toIdx)
                }

                raw += classify(onsetIdx, offsetIdx, truncatedStart || truncatedEnd, seg)
                i = max(resume, onsetIdx + 1)
            }
        }
        return applyRefractory(raw)
    }

    /**
     * §2 step 5.6: refractory period on either side of a gross body movement. It only applies to
     * events that are otherwise accepted — an already rejected event keeps its original reason,
     * which is more informative for the quality report.
     */
    private fun applyRefractory(raw: List<Clm>): List<Clm> {
        val zones = raw.asSequence()
            .filter { (it.flags and ClmFlags.GROSS_BODY) != 0 }
            .map { Segment(it.onsetIdx - refractory, it.offsetIdx + refractory) }
            .toList()
        if (zones.isEmpty()) return raw
        return raw.map { e ->
            if (e.reject == null && (e.flags and ClmFlags.GROSS_BODY) == 0 &&
                zones.any { overlaps(e.onsetIdx, e.offsetIdx, it) }
            ) {
                e.copy(reject = ClmRejectReason.GROSS_BODY)
            } else {
                e
            }
        }
    }

    private fun classify(onsetIdx: Int, offsetIdx: Int, truncated: Boolean, seg: Segment): Clm {
        val durSamples = offsetIdx - onsetIdx
        var flags = 0

        // Amplitudes. The peak is taken on the decision envelope (coarse); the median on the fine
        // envelope, which is the quantity used by the morphology criterion.
        var peak = 0f
        var peakIdx = onsetIdx
        for (t in onsetIdx until offsetIdx) {
            val v = coarse[t]
            if (v.isFinite() && v > peak) {
                peak = v
                peakIdx = t
            }
        }
        val medianAmp = Numeric.median(fine, onsetIdx, offsetIdx, scratchOf(durSamples))

        // Features extracted from g: they carry the information the high-pass throws away (§1.2).
        // tiltChange = net, persistent change; tiltExcursion = transient excursion.
        val refIdx = (onsetIdx - tau).coerceAtLeast(seg.fromIdx)
        val postIdx = (offsetIdx - 1 + tau).coerceIn(seg.fromIdx, seg.toIdx - 1)
        val tiltChange = Gravity.angleDeg(gravity, refIdx, postIdx).let { if (it.isNaN()) 0f else it }
        var excursion = 0f
        for (t in onsetIdx until offsetIdx) {
            val a = Gravity.angleDeg(gravity, refIdx, t)
            if (!a.isNaN() && a > excursion) excursion = a
        }

        for (t in onsetIdx until offsetIdx) {
            if (floorExtrapolated[t]) {
                flags = flags or ClmFlags.FLOOR_EXTRAPOLATED
                break
            }
        }
        flags = flags or Thresholds.dominanceAt(fl[onsetIdx], gain, params)

        val morphoOk = morphologyOk(onsetIdx, offsetIdx)
        val inBlind = blindZones.any { overlaps(onsetIdx, offsetIdx, it) }
        if (inBlind) flags = flags or ClmFlags.IN_BLIND_ZONE
        if (truncated) flags = flags or ClmFlags.TRUNCATED
        if (durSamples > maxDur) flags = flags or ClmFlags.LM_LONG

        val postural = postures.any { onsetIdx >= it.atIdx - guard && onsetIdx <= it.atIdx + guard }
        if (postural) flags = flags or ClmFlags.POSTURAL

        // §3.2, defence no. 2: reported, never excluded. An isolated dorsiflexion also produces a
        // near-zero excursion at the sensor (Terrill's mechanism); excluding these events would
        // worsen the downward bias already present — two errors in the same direction.
        if (excursion < cfg.minExcursionDeg && durSamples < transmittedMaxDur) {
            flags = flags or ClmFlags.TRANSMITTED_SUSPECT
        }

        val gbm = peak >= cfg.grossBodyFactor * effectiveFloor(peakIdx) ||
            tiltChange > postureCfg.postureDeg ||
            durSamples > maxDur
        if (gbm) flags = flags or ClmFlags.GROSS_BODY

        val reject = when {
            !morphoOk -> ClmRejectReason.MORPHOLOGY
            truncated -> ClmRejectReason.TRUNCATED
            durSamples < minDur -> ClmRejectReason.TOO_SHORT
            inBlind -> ClmRejectReason.BLIND_ZONE
            postural -> ClmRejectReason.POSTURAL
            gbm -> ClmRejectReason.GROSS_BODY
            else -> null
        }

        return Clm(
            onsetIdx = onsetIdx,
            offsetIdx = offsetIdx,
            onsetMsRel = Math.round(onsetIdx * 1000.0 / fs),
            durationMs = Math.round(durSamples * 1000.0 / fs).toInt(),
            peakAmpG = peak,
            medianAmpG = medianAmp,
            noiseFloorG = effectiveFloor(onsetIdx),
            thresholdOnG = on(onsetIdx),
            thresholdOffG = off(onsetIdx),
            tiltChangeDeg = tiltChange,
            tiltExcursionDeg = excursion,
            flags = flags,
            reject = reject,
        )
    }

    /** WASM 3.2.1-d criterion: at least one `morphoWin` window whose median exceeds Theta_off. */
    private fun morphologyOk(from: Int, to: Int): Boolean {
        if (to - from < morphoWin) return false
        val buf = scratchOf(morphoWin)
        var s = from
        while (s + morphoWin <= to) {
            if (Numeric.median(fine, s, s + morphoWin, buf) >= off(s + morphoWin / 2)) return true
            s++
        }
        return false
    }

    private fun overlaps(from: Int, to: Int, seg: Segment): Boolean =
        from < seg.toIdx && to > seg.fromIdx
}
