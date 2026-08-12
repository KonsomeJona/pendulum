package com.pendulum.algo.dsp

import com.pendulum.algo.model.DualEnvelope
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.Timeline

/**
 * **All** the preprocessing parameter values, gathered together, with the **exact** defaults of
 * tables §6.1 (integrity and preprocessing) and §6.2 (envelope and floor).
 *
 * A single object for a single reason: these values enter the parameter hash that accompanies
 * every `PlmiResult`. Two nights computed with different settings are not comparable, and the only
 * reliable way to notice it is that the hash changes. Scattering the constants across the calls
 * would make that hash incomplete, therefore a lie.
 *
 * The fields marked INTERPRETATION have no line in the tables of the specification; their
 * justification is given where they are consumed.
 */
data class PreprocessConfig(
    // --- §6.1: integrity and timeline ---
    val timeline: TimelineConfig = TimelineConfig(),
    // --- §6.1: gravity / movement separation ---
    val fcGravityHz: Double = 0.15,
    val fcHpHz: Double = 0.50,
    val fcLpHz: Double = 8.0,
    val hpOrder: Int = 2,
    // --- §6.2: envelopes ---
    val coarseEnvSec: Double = 0.50,
    val fineEnvSec: Double = 0.15,
    // --- §6.2: noise floor ---
    val noiseFloor: NoiseFloorConfig = NoiseFloorConfig(),
    // --- §6.3: thresholds (the four values used by step 4) ---
    val thresholds: ThresholdParams = ThresholdParams(),
) {
    /** `Theta_abs / k_on` — lower bound of the floor, cf. §1.3 last line. */
    val floorMinG: Float get() = (thresholds.absFloorG / thresholds.kOn).toFloat()
}

/** Everything the preprocessing produces, in the order the following steps consume it. */
data class Preprocessed(
    val timeline: Timeline,
    val gravity: com.pendulum.algo.model.TriAxial,
    val linear: com.pendulum.algo.model.TriAxial,
    val magnitude: Signal1D,
    val envelope: DualEnvelope,
    val floor: Signal1D,
    val floorExtrapolated: BooleanArray,
    val thresholds: ThresholdCurves,
)

/**
 * Chaining of steps −1 to 4. Pure function: same input -> same output down to the bit.
 *
 * The order is not negotiable, each step consuming strictly the output of the previous one:
 * integrity -> timeline -> gravity/movement -> magnitude -> envelopes -> floor -> thresholds.
 */
object Preprocess {

    /**
     * @param postureBoundaries posture change boundaries, as grid indices. They **cut the floor
     *   windows** just as much as the segment boundaries do (§3.1, side effect (a)). They are not
     *   known on the first pass — the posture detector works on `g_hat`, therefore after step 1 —
     *   hence the possible two-stage call: a first `run` without boundaries to obtain `gravity`,
     *   then a second one with them.
     * @param calibration supplies `gainCalG` to the third term of the threshold. `null` = term
     *   disabled.
     */
    fun run(
        blocks: List<SampleBlock>,
        nominalHz: Double,
        cfg: PreprocessConfig = PreprocessConfig(),
        postureBoundaries: IntArray = IntArray(0),
        calibration: NightCalibration? = null,
        sessionClosedCleanly: Boolean = true,
    ): Preprocessed {
        val timeline = TimelineBuilder.build(blocks, nominalHz, cfg.timeline, sessionClosedCleanly)
        val split = Gravity.split(
            timeline.signal, timeline.segments,
            cfg.fcGravityHz, cfg.fcHpHz, cfg.fcLpHz, cfg.hpOrder, cfg.timeline.settleSec,
        )
        val magnitude = Envelope.magnitudeL2(split.linear)
        val env = Envelope.dual(magnitude, timeline.segments, cfg.coarseEnvSec, cfg.fineEnvSec)
        val (floor, extrapolated) = NoiseFloor.estimate(
            env.coarse, timeline.segments, postureBoundaries, cfg.noiseFloor, cfg.floorMinG,
        )
        val curves = Thresholds.compute(floor, calibration?.gainCalG ?: Float.NaN, cfg.thresholds)
        return Preprocessed(
            timeline = timeline,
            gravity = split.gravity,
            linear = split.linear,
            magnitude = magnitude,
            envelope = env,
            floor = floor,
            floorExtrapolated = extrapolated,
            thresholds = curves,
        )
    }
}
