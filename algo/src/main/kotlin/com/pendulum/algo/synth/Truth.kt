package com.pendulum.algo.synth

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.Gap
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.SleepMask

/** Nature of an injected event. Transcription of `docs/workings/ALGO-v2.md` §5.3. */
enum class TruthKind {
    PLM_IN_SERIES,
    ISOLATED,
    RRLM,
    GROSS_BODY,
    POSTURE,
    ALMA,
    MATTRESS,
}

/**
 * An injected event, with all the physics that produced it.
 *
 * @param peakG peak of the **movement channel** (static gravity excluded) actually rendered, in g.
 * @param envPeakG peak of the coarse 0.5 s RMS envelope of the same signal. It is the quantity the
 *   detector compares to `Theta_on`: it is therefore this one, and not [peakG], that carries the
 *   abscissa of the sensitivity curve (T5).
 * @param ankleOnly pure ankle rotation: `r_eff ~ 0`, the case does not move. Mechanically
 *   invisible, whatever the EMG activity. This is Terrill's mechanism.
 */
data class TruthEvent(
    val onsetMsRel: Long,
    val durationMs: Int,
    val peakG: Float,
    val envPeakG: Float,
    val thetaMaxDeg: Float,
    val tRiseSec: Float,
    val radiusM: Float,
    val ankleOnly: Boolean,
    val kind: TruthKind,
    val seriesId: Int?,
) {
    val endMsRel: Long get() = onsetMsRel + durationMs

    /** Leg movement that is a candidate CLM. Excludes the artefacts (mattress) and the GBMs. */
    val isLegMovement: Boolean
        get() = kind == TruthKind.PLM_IN_SERIES || kind == TruthKind.ISOLATED || kind == TruthKind.RRLM
}

/**
 * Ground truth with **two label sets** (§5.3). This is the conceptual point of the generator.
 *
 * [emgTruth] is a laboratory's scale: everything that was generated. [accelTruth] is the subset
 * **mechanically visible to the sensor** — `emgTruth` minus the `ankleOnly` events and those whose
 * simulated peak falls below the physical visibility threshold.
 *
 * **Every detector metric is scored against [accelTruth].** [emgTruth] serves one purpose only, but
 * an essential one: measuring and reporting [emgToAccelRatio], the conversion factor between the
 * two scales, that is to say the structural downward bias of the published count. It is the figure
 * that forbids comparing our `aPLM-i` directly with the ICSD-3 threshold of 15/h — and without that
 * distinction, the "F1 >= 0.90" criterion of T6 would be unreachable for a reason that is not the
 * algorithm's fault (against `emgTruth`, F1 caps towards 0.76).
 *
 * @param mask **true** sleep mask, in the sense of a perfect diary. An addition to the canvas of
 *   §5.3: without it no hourly metric is computable and the denominator would become circular
 *   again.
 * @param floorG coarse envelope floor of the injected noise alone (without any movement). Used to
 *   normalise the abscissa of the sensitivity curve.
 * @param gainCalG the reference mechanical gain of that particular night, mechanical coupling of
 *   the night included.
 */
class GroundTruth(
    val emgTruth: List<TruthEvent>,
    val accelTruth: List<TruthEvent>,
    val postures: List<Long>,
    val gaps: List<Gap>,
    val mask: SleepMask,
    val expectedPlmiAasm: Double,
    val expectedPlmiWasm: Double,
    val expectedPlmw: Double,
    val expectedPi: Double,
    val expectedTstMin: Double,
    val expectedSptMin: Double,
    val floorG: Double,
    val gainCalG: Float,
    val gainMultiplierApplied: Float,
    val fsRealHz: Double,
    val truncatedAtMs: Long?,
) {
    /** Leg movements only (PLM, isolated, RRLM), on the EMG scale. */
    val emgLegMovements: List<TruthEvent> = emgTruth.filter { it.isLegMovement }

    /** Leg movements only, on the accelerometric scale. **The reference for every score.** */
    val accelLegMovements: List<TruthEvent> = accelTruth.filter { it.isLegMovement }

    /**
     * EMG -> accelerometer conversion factor, in `[0, 1]`. Expected around 0.61 with the default
     * value of `ankleOnlyFraction` (Terrill: 39.0 % of the EMG LMs with no detectable movement), a
     * little lower since the low tail of the amplitude distribution additionally falls below the
     * physical visibility threshold.
     */
    val emgToAccelRatio: Double
        get() = if (emgLegMovements.isEmpty()) Double.NaN
        else accelLegMovements.size.toDouble() / emgLegMovements.size

    fun eventsOf(kind: TruthKind): List<TruthEvent> = emgTruth.filter { it.kind == kind }
}

/** A complete synthetic night: the signal as the format would yield it, and its ground truth. */
class SynthNight(
    val blocks: List<SampleBlock>,
    val truth: GroundTruth,
    val seed: Long,
    val spec: NightSpec,
)

/** Reference grid for the ground-truth timestamps: that of step 0 (`targetFsHz`). */
internal const val TARGET_FS_HZ: Double = 50.0

/**
 * Converts ground-truth events into accepted [Clm]s, so as to run the truth back through the
 * **same** steps 6 and 7 as the detection.
 *
 * Why this detour rather than a home-made index computation: what is to be measured is the
 * **detection** error, not a disagreement over the interpretation of the clinical rules. By making
 * the ground truth cross the same `SeriesBuilder` and the same `Plmi.compute`, any remaining
 * difference is imputable to the detector, which is exactly what T6 claims to measure.
 */
fun truthAsClms(events: List<TruthEvent>, floorG: Float = 0.002f): List<Clm> =
    events.sortedBy { it.onsetMsRel }.map { e ->
        val onsetIdx = Math.round(e.onsetMsRel * TARGET_FS_HZ / 1000.0).toInt()
        val offsetIdx = Math.round((e.onsetMsRel + e.durationMs) * TARGET_FS_HZ / 1000.0).toInt()
        Clm(
            onsetIdx = onsetIdx,
            offsetIdx = maxOf(offsetIdx, onsetIdx + 1),
            onsetMsRel = e.onsetMsRel,
            durationMs = e.durationMs,
            peakAmpG = e.peakG,
            medianAmpG = e.envPeakG,
            noiseFloorG = floorG,
            thresholdOnG = floorG * 8f,
            thresholdOffG = floorG * 2.5f,
            tiltChangeDeg = 0f,
            tiltExcursionDeg = e.thetaMaxDeg,
            flags = 0,
            reject = null,
        )
    }
