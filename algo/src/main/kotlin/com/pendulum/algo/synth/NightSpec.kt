package com.pendulum.algo.synth

/**
 * Parameters of a synthetic night. Transcription of `docs/workings/ALGO-v2.md` §5.2, extended to the
 * twelve distractor families of the table in that same paragraph.
 *
 * Everything is parameterisable and nothing has a hidden value: the generator is what serves as the
 * **reference** for the whole algorithm, and a generator in which a distractor cannot be made harder
 * proves nothing (defect F-21 of `docs/workings/CRITICAL-REVIEW.md`, "circular validation").
 */

/** Scale on which the amplitude drawn for a movement is read. */
enum class AmplitudeScale {
    /**
     * Peak of the **norm** of the movement signal (accelerometer, static gravity excluded): the
     * three contributions of §5.1 summed — tangential, gravity reprojection, centripetal.
     *
     * This is **not** the quantity of the calibration table of §5.1 (30 / 184 / 985 mg), contrary to
     * what this KDoc used to claim. That table quantifies the **tangential** term alone,
     * `r . theta''`, computed by [MovementKinematics.peakTangentialG]; §5.1 notes just afterwards
     * that at large angles it is the gravity term that dominates, which forbids conflating the two.
     * The values of the table are asserted by `MovementModelTest`, which calls `peakTangentialG`
     * directly.
     */
    PEAK,

    /**
     * Peak of the **coarse 0.5 s RMS envelope**, that is to say exactly the quantity the detector
     * compares to `Theta_on`. Indispensable to test T5: "8x the floor = the threshold, by
     * construction" only means something if the abscissa of the sensitivity curve is the one on
     * which the decision is taken.
     */
    COARSE_ENVELOPE,
}

/**
 * Amplitude distribution of the movements (§5.1, last line).
 *
 * @param sigmaLog a **parameter**, not a constant: the specification states that it is an
 *   engineering choice and that it drives the slope of the sensitivity curve directly (T5).
 * @param fixedG if non-null, short-circuits the distribution and imposes this amplitude on every
 *   movement. Used for sweeping the sensitivity curve.
 */
data class AmplitudeSpec(
    val medianG: Double = 0.080,
    val sigmaLog: Double = 0.60,
    val minG: Double = 0.010,
    val maxG: Double = 0.800,
    val fixedG: Double? = null,
    val scale: AmplitudeScale = AmplitudeScale.PEAK,
)

/**
 * Duration distribution and geometry of the movement (§5.1).
 *
 * `meanSec` takes up Sforza 2005: **4.2 s** of mean duration at the ankle in RLS.
 *
 * `sdSec` is **not** a figure from Sforza, contrary to what this KDoc used to claim. The published
 * "+/- 0.14" is a **standard error of the mean** — §2.5 of the article says "results in the text and
 * in the tables are expressed as mean +/- standard error of the mean", and the RLS group counts 11
 * patients. The between-patient standard deviation of the individual means is therefore
 * `0.14 x sqrt(11) ~ 0.46 s`, and the **event-by-event** spread — the only one a generator needs —
 * is published nowhere. The 1.4 s retained here is a **modelling choice**: it gives a CV of 0.33,
 * whose +/-2 sigma quantiles (~1.9 to 8.0 s) stay comfortably inside the 0.5-10 s scoring window of
 * the Coleman criteria. A value of 0.14 s would make the distribution a Dirac mass, which is
 * incompatible with the very existence of that window and with the 3.2 s mean of the PLMD group in
 * the same article. The short mode of ~2-3 s sometimes put forward is confirmed by no published
 * histogram and is not coded.
 */
data class DurationSpec(
    val meanSec: Double = 4.2,
    val sdSec: Double = 1.4,
    val minSec: Double = 0.5,
    val maxSec: Double = 10.0,
    /** Duration of the flexion phase, `T_rise`. Gives `f_peak = 0.8 / T_rise` in [1.6 ; 5.3] Hz. */
    val tRiseMinSec: Double = 0.15,
    val tRiseMaxSec: Double = 0.50,
    /** Lever arm sensor / centre of rotation, in metres. */
    val radiusMinM: Double = 0.15,
    val radiusMaxM: Double = 0.30,
    /**
     * Ratio "acceleration peak during the hold / ballistic peak of the flexion".
     *
     * The hold phase of a CLM is not a motionless plateau: see the KDoc of [MovementKinematics]. The
     * value 0.50 is the **decay threshold / entry threshold** ratio of the PAM-RL (100 mg / 200 mg,
     * Sforza 2005 §2.4), the device that measured the 4.2 s: it is the minimum an event must sustain
     * to have been counted as a single 4.2 s kick rather than split by the 1 s drop-out.
     *
     * `0.0` restores the motionless plateau of the original model — useful to reproduce the defect.
     */
    val holdActivityRatio: Double = 0.50,
)

/** A population of periodic series. `NightSpec.trueSeries` holds as many of them as wanted. */
data class SeriesSpec(
    val nSeries: Int,
    val clmPerSeries: Int,
    val imiMeanSec: Double,
    val imiCvPct: Double,
)

/**
 * Wake / sleep structure of the night. It provides the **true denominator**: it is the perfect sleep
 * diary, entirely independent of the signal, hence non-circular by construction.
 */
data class SleepSpec(
    val sleepLatencyMin: Double = 18.0,
    val finalWakeMin: Double = 8.0,
    val wasoCount: Int = 4,
    val wasoMinMin: Double = 3.0,
    val wasoMaxMin: Double = 12.0,
)

/** Family 5 of the §5.2 table: MEMS noise and quantisation. */
data class NoiseSpec(
    /** Spectral density of the noise, in g/sqrt(Hz). Range from the table: 150-300 ug/sqrt(Hz). */
    val densityMinG: Double = 150e-6,
    val densityMaxG: Double = 300e-6,
    /** Quantisation step of the format, 1/2048 g. `0` disables quantisation. */
    val lsbG: Double = 1.0 / 2048.0,
    /**
     * Slow postural drift of the wearing limb, in RMS degrees (Ornstein-Uhlenbeck process of time
     * constant `wanderTauSec`). **This is not sensor noise**: it is the fact that a living leg never
     * holds exactly the same orientation ten minutes in a row. Without this term, a quiet night
     * shows a raw standard deviation below the `offBodySdG` threshold (5 mg) and step 0 classifies
     * it entirely as **off-body** — test T1 would then run on nothing, for lack of analysable time.
     * The content is below 0.01 Hz: it falls entirely in the gravity channel and does not touch the
     * movement channel.
     */
    val wanderDeg: Double = 0.8,
    val wanderTauSec: Double = 120.0,
)

/**
 * The twelve distractor families of the §5.2 table. The counts are drawn uniformly in
 * `[...Min, ...Max]`, the amplitudes log-uniformly (an amplitude uniform in [3 ; 40] mg would put
 * half the mass above 21 mg, which is not what the source describes).
 */
data class DistractorSpec(
    // --- 1. Posture changes ---------------------------------------------------------------
    val postureCountMin: Int = 15,
    val postureCountMax: Int = 40,
    val postureDegMin: Double = 20.0,
    val postureDegMax: Double = 120.0,
    val postureDurMinSec: Double = 0.5,
    val postureDurMaxSec: Double = 3.0,

    // --- 2. Gross body movements ----------------------------------------------------------
    val grossBodyCountMin: Int = 20,
    val grossBodyCountMax: Int = 60,
    val grossBodyDurMinSec: Double = 2.0,
    val grossBodyDurMaxSec: Double = 20.0,
    val grossBodyAmpMinG: Double = 0.300,
    val grossBodyAmpMaxG: Double = 2.500,

    // --- 3. Respiratory artefact ----------------------------------------------------------
    val respiratory: Boolean = true,
    val respHzMin: Double = 0.20,
    val respHzMax: Double = 0.33,
    val respAmpMinG: Double = 0.001,
    val respAmpMaxG: Double = 0.010,
    val respAmPeriodMinSec: Double = 60.0,
    val respAmPeriodMaxSec: Double = 300.0,

    // --- 4. Mattress vibration ------------------------------------------------------------
    val mattressCountMin: Int = 50,
    val mattressCountMax: Int = 500,
    val mattressDurMinSec: Double = 0.05,
    val mattressDurMaxSec: Double = 0.40,
    val mattressAmpMinG: Double = 0.003,
    val mattressAmpMaxG: Double = 0.040,
    val mattressRingHzMin: Double = 8.0,
    val mattressRingHzMax: Double = 20.0,

    // --- 6. FIFO gaps ---------------------------------------------------------------------
    val gapCountMin: Int = 0,
    val gapCountMax: Int = 0,
    val gapMinSec: Double = 0.1,
    val gapMaxSec: Double = 5.0,
    val longGap: Boolean = false,
    val longGapMinSec: Double = 30.0,
    val longGapMaxSec: Double = 120.0,

    // --- 8. Off-body ----------------------------------------------------------------------
    val offBody: Boolean = false,
    val offBodyMin: Double = 12.0,
    /** The watch raises its own hardware flag. `false` by default: the absolute-immobility detector
     *  must be able to cope on its own, otherwise all that is tested is trust in the flag. */
    val offBodyHardwareFlag: Boolean = false,

    // --- 9. Hypnagogic tremor / ALMA ------------------------------------------------------
    val almaCountMin: Int = 0,
    val almaCountMax: Int = 0,
    val almaDurMinSec: Double = 10.0,
    val almaDurMaxSec: Double = 15.0,
    val almaHzMin: Double = 0.3,
    val almaHzMax: Double = 4.0,
    val almaAmpMinG: Double = 0.020,
    val almaAmpMaxG: Double = 0.080,

    // --- 10. Non-periodic bursts ----------------------------------------------------------
    val clusterCount: Int = 0,
    val clusterSizeMin: Int = 5,
    val clusterSizeMax: Int = 10,
    val clusterImiMinSec: Double = 1.0,
    val clusterImiMaxSec: Double = 8.0,

    // --- 11. RRLM (respiration-related leg movements) -------------------------------------
    val rrlmSeriesCount: Int = 0,
    val rrlmPerSeries: Int = 6,
    val rrlmImiMinSec: Double = 25.0,
    val rrlmImiMaxSec: Double = 45.0,

    // --- 12. Mechanical gain step during the night ----------------------------------------
    /** Factor applied to the mechanical coupling from `gainStepAtFraction` of the night onwards. */
    val gainStep: Double? = null,
    val gainStepAtFraction: Double = 0.5,
) {
    companion object {
        /** Night with no distractor at all: only the useful signal and the MEMS noise remain. */
        val NONE: DistractorSpec = DistractorSpec(
            postureCountMin = 0, postureCountMax = 0,
            grossBodyCountMin = 0, grossBodyCountMax = 0,
            respiratory = false,
            mattressCountMin = 0, mattressCountMax = 0,
        )

        /** The twelve families active, default values of the §5.2 table. The test T6 night. */
        val ALL: DistractorSpec = DistractorSpec(
            gapCountMin = 20, gapCountMax = 60, longGap = true,
            offBody = true,
            almaCountMin = 2, almaCountMax = 8,
            clusterCount = 3,
            rrlmSeriesCount = 2,
        )
    }
}

/**
 * Mechanical calibration ritual of §3.3 part B. The generator **renders it physically**, with the
 * same model as the CLMs, rather than laying down a constant: `gainCal` must vary with the
 * mechanical coupling of the night exactly like the useful signal, otherwise test T11 does no more
 * than check a tautology.
 */
data class RitualSpec(
    val thetaMaxDeg: Double = 25.0,
    val tRiseSec: Double = 0.30,
    val holdSec: Double = 0.40,
    val radiusM: Double = 0.22,
)

/**
 * Complete night. `durationH` is the recording duration; `truncateAtH` cuts it short (dead watch).
 *
 * @param ankleOnlyFraction fraction of the leg movements that are a **pure ankle rotation**: the
 *   case sitting above the talocrural axis, it does not move. This is the physical mechanism behind
 *   Terrill's miss rate (39.0 %), and it is what separates `emgTruth` from `accelTruth`. Published
 *   range 0.25-0.55.
 * @param gainMultiplier mechanical coupling ankle -> strap -> case for the whole night (strap
 *   tightness). `1.0` = reference night.
 * @param visibilityG below this simulated peak, a movement is not mechanically visible and drops out
 *   of `accelTruth` (default 8 mg, ~0.4x the absolute floor).
 */
data class NightSpec(
    val durationH: Double = 8.0,
    val fsRealHz: Double = 50.0,
    /** Linear drift of `fs` over the night, in percent (range from the table: +/- 0.5 %). */
    val fsDriftPct: Double = 0.0,
    val trueSeries: List<SeriesSpec> = listOf(SeriesSpec(24, 7, 22.0, 25.0)),
    val isolatedClmPerHour: Double = 6.0,
    val ankleOnlyFraction: Double = 0.39,
    val gainMultiplier: Double = 1.0,
    val truncateAtH: Double? = null,
    val distractors: DistractorSpec = DistractorSpec.ALL,
    val noise: NoiseSpec = NoiseSpec(),
    val amplitude: AmplitudeSpec = AmplitudeSpec(),
    val duration: DurationSpec = DurationSpec(),
    val sleep: SleepSpec = SleepSpec(),
    val ritual: RitualSpec = RitualSpec(),
    val visibilityG: Double = 0.008,
    /** Initial orientation of the leg segment, in degrees relative to the horizontal. */
    val initialTiltDeg: Double = 15.0,
    /** Nominal size of a block of the format. 250 samples = 5 s at 50 Hz. */
    val blockSamples: Int = 250,
    /** Time of the first sample, in ns. Fixed: no wall clock enters here. */
    val startNs: Long = 1_000_000_000L,
) {
    init {
        require(durationH > 0.0) { "durationH must be > 0" }
        require(fsRealHz > 0.0) { "fsRealHz must be > 0" }
        require(ankleOnlyFraction in 0.0..1.0) { "ankleOnlyFraction outside [0, 1]" }
        require(blockSamples in 2..512) { "blockSamples outside [2, 512] (format limit)" }
    }

    /** Duration actually recorded, truncation included. */
    val recordedH: Double get() = truncateAtH?.coerceAtMost(durationH) ?: durationH
}
