package com.pendulum.algo.regression

import com.pendulum.algo.detect.ClmConfig
import com.pendulum.algo.detect.ClmDetector
import com.pendulum.algo.detect.PostureConfig
import com.pendulum.algo.detect.PostureDetector
import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.detect.ThresholdConfig
import com.pendulum.algo.dsp.Preprocess
import com.pendulum.algo.dsp.PreprocessConfig
import com.pendulum.algo.dsp.Preprocessed
import com.pendulum.algo.indices.Periodicity
import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.Stage
import com.pendulum.algo.model.Timeline
import com.pendulum.algo.synth.AmplitudeScale
import com.pendulum.algo.synth.AmplitudeSpec
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.GroundTruth
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth
import com.pendulum.algo.synth.NoiseSpec
import com.pendulum.algo.synth.SeriesSpec
import com.pendulum.algo.synth.SleepSpec
import com.pendulum.algo.synth.SynthNight
import com.pendulum.algo.synth.TruthEvent
import com.pendulum.algo.synth.TruthKind
import com.pendulum.algo.synth.truthAsClms
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Shared tooling for the non-regression suite of the `docs/workings/ALGO-v2.md` §5.5 table.
 *
 * Three things are settled here once and for all, because they condition how every threshold is
 * read:
 *
 *  1. **The 20 seeds.** Every test runs on at least 20 nights; the assertion bears on the median,
 *     with a secondary assertion on the worst case where the specification says so.
 *  2. **The sleep mask is the ground-truth one**, refined by the time actually analysable on the
 *     timeline. The accelerometric mask layer of §3.6 is not written yet, and above all: making it
 *     carry the denominator here would mix the detector's error with the mask's, whereas §5.5
 *     measures only the first. The denominator therefore stays `INDEPENDENT_DIARY`, non-circular by
 *     construction.
 *  3. **The expected value goes through the same steps 6 and 7 as the measurement**, with the
 *     **same** mask object ([Analysis.truthResult]). Any remaining difference is imputable to
 *     detection — which is exactly what T6 claims to measure.
 */
internal val SEEDS: List<Long> = (0 until 20).map { 20_260_729L + it * 7_919L }

internal const val FS: Double = 50.0

/**
 * Detection configuration under which **the whole** non-regression suite runs.
 *
 * This is the product's published value, unless `-Palgo.calFraction=<x>` is passed to Gradle. That
 * hook exists for one precise and bounded reason: the `calFraction` sweep
 * (`ThresholdPolicySweepTest`, `docs/07-validation.md` §4.4) recommends `f_cal` close to 0.06, and a
 * recommendation of that kind is not put forward without **the list of what it breaks**. Replaying
 * T1 to T22 under another value is the only way to establish that list, and doing it by editing the
 * default — even temporarily — would amount to measuring a working tree nobody will read again.
 *
 * Without the flag, nothing changes: the product default stays the one from [ThresholdConfig].
 */
internal val REGRESSION_CAL_FRACTION: Double =
    System.getProperty("algo.calFraction")?.toDoubleOrNull() ?: ThresholdConfig().calFraction

/** @see REGRESSION_CAL_FRACTION */
internal val REGRESSION_CLM_CFG: ClmConfig =
    ClmConfig(thresholds = ThresholdConfig(calFraction = REGRESSION_CAL_FRACTION))

// ---------------------------------------------------------------------------------------------
// Aggregation statistics over the seeds
// ---------------------------------------------------------------------------------------------

internal fun medianOf(values: List<Double>): Double {
    require(values.isNotEmpty()) { "median of an empty list" }
    val s = values.filter { !it.isNaN() }.sorted()
    if (s.isEmpty()) return Double.NaN
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else 0.5 * (s[m - 1] + s[m])
}

internal fun worstMax(values: List<Double>): Double = values.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN

internal fun worstMin(values: List<Double>): Double = values.filter { !it.isNaN() }.minOrNull() ?: Double.NaN

/** Relative difference between two values, referred to the first. `NaN` if the reference is zero. */
internal fun relDiff(a: Double, b: Double): Double = if (a == 0.0) Double.NaN else abs(a - b) / abs(a)

// ---------------------------------------------------------------------------------------------
// Full chain
// ---------------------------------------------------------------------------------------------

internal class Analysis(
    val night: SynthNight,
    val pre: Preprocessed,
    val postures: List<PostureChange>,
    val clms: List<Clm>,
    val mask: SleepMask,
    val calibration: NightCalibration,
    val kOn: Double,
) {
    val timeline: Timeline get() = pre.timeline
    val truth: GroundTruth get() = night.truth

    /** Retained CLM, in chronological order. */
    val retained: List<Clm> get() = clms.filter { it.isClm }

    /** Fraction of the recorded time actually analysable. Guard rail against an empty test. */
    val analysableFraction: Double
        get() = if (timeline.signal.n == 0) 0.0
        else timeline.analysableSec / (timeline.signal.n / timeline.signal.fsHz)

    /**
     * Trigger threshold `Theta_on`, median over the night. This is the amplitude above which an
     * event belongs to those the detector is **configured** to find — which is not the same thing as
     * those mechanically present in the signal. T6's denominator and T22's sub-threshold fraction
     * both refer to this value.
     */
    val thresholdOnG: Double by lazy {
        val v = pre.thresholds.on.v
        val acc = ArrayList<Double>(v.size / 50 + 1)
        var i = 0
        while (i < v.size) {
            val x = v[i]
            if (x.isFinite()) acc.add(x.toDouble())
            i += 50
        }
        medianOf(acc)
    }

    /**
     * **Effective** floor of the detector, `Theta_on / k_on`, median over the night. This is the
     * amplitude reference actually used by the decision, and therefore the abscissa of the T5 curve.
     *
     * Derived from [thresholdOnG] rather than recomputed: the median commutes with division by a
     * positive constant, and the two quantities must stay exactly consistent — it is their ratio,
     * `k_on`, that is the whole subject of the `ThresholdPolicySweepTest` sweep.
     */
    val effectiveFloorG: Double get() = thresholdOnG / kOn

    fun result(rule: SeriesRule): PlmiResult = indexOf(clms, rule)

    /**
     * The same computation, applied to a ground truth and to the **same** mask.
     *
     * @param events by default the whole `accelTruth`, that is, the true index on the accelerometric
     *   scale. Passing it restricted to the events above `Theta_on` gives the index that a
     *   **perfect detector applying this threshold policy** would produce: that is T6's expected
     *   value, while the ratio between the two is what T22 publishes.
     */
    fun truthResult(rule: SeriesRule, events: List<TruthEvent> = truth.accelLegMovements): PlmiResult =
        indexOf(truthAsClms(events, truth.floorG.toFloat()), rule)

    /** Estimated fundamental rhythm (`SPEC-v2.md` §5), over the consecutive sleep CLM. */
    fun rhythm() = Rhythm.fromClms(retained, mask)

    /** The same fit, with its goodness-of-fit diagnostics. Output of `RhythmMeasurementTest`. */
    fun rhythmFit() = Rhythm.fitFromClms(retained, mask)

    private fun indexOf(events: List<Clm>, rule: SeriesRule): PlmiResult {
        val cfg = if (rule == SeriesRule.AASM_V3) SeriesConfig.aasmV3() else SeriesConfig.wasm2016()
        // The segments of the timeline, and the same object for the measurement and for the
        // expected value: `SeriesBuilder` breaks a series at a recording restart (WASM 3.3.3), so a
        // harness keeping its own idea of where the recording stopped would compare an index built
        // on one segmentation with an index built on another, and would validate a chain the
        // application does not have. That is the failure this project has already paid for.
        val built = SeriesBuilder.buildDetailed(events, mask, FS, cfg, timeline.segments)

        // `events` and not the filtered list. `clms` is **the complete list**, the one just handed
        // to `SeriesBuilder`, and `Plmi.compute` does the translation to the retained ones itself.
        //
        // This harness used to pass the filtered list while handing over series indexed on the
        // complete list: the two bases did not coincide, and the offset was silent. T6 caught it on
        // the very first run after the translation moved down into `compute` — which is precisely
        // what the new contract makes impossible to write without seeing it.
        //
        // `ferriIndex` and `fromClms` filter internally: both forms are equivalent to them. They are
        // given the same list as `compute` so that there is only one answer to the question "what do
        // we pass here?".
        val pi = Periodicity.ferriIndex(events, mask, FS)
        val rhythm = Rhythm.fromClms(events, mask)
        return Plmi.compute(
            clms = events,
            series = built.series,
            mask = mask,
            fsHz = FS,
            rule = rule,
            pi = pi,
            rhythm = rhythm,
            floorMode = FloorMode.BILATERAL,
            truncated = timeline.truncated,
            truncatedSeriesDropped = built.truncatedSeriesDropped,
            paramsHash = "regression",
        )
    }
}

/**
 * Full v2 chain, steps −1 to 7, on a synthetic night.
 *
 * Two preprocessing passes, as §3.1 provides: the posture detector works on `g_chapeau`, hence after
 * step 1, and its boundaries then cut the floor windows.
 *
 * @param calibrated `false` disables the third term of the threshold (`f_cal x gainCal`). This is
 *   the "calibration inactive" arm of test T11, whose assertion is **inverted**.
 */
internal fun analyse(
    night: SynthNight,
    nominalHz: Double = FS,
    cfg: PreprocessConfig = PreprocessConfig(),
    clmCfg: ClmConfig = REGRESSION_CLM_CFG,
    postureCfg: PostureConfig = PostureConfig(),
    calibrated: Boolean = true,
): Analysis = analyseBlocks(night, night.blocks, nominalHz, cfg, clmCfg, postureCfg, calibrated)

/** Variant that analyses a modified block stream (T9 decimation) while keeping the ground truth. */
internal fun analyseBlocks(
    night: SynthNight,
    blocks: List<SampleBlock>,
    nominalHz: Double = FS,
    cfg: PreprocessConfig = PreprocessConfig(),
    clmCfg: ClmConfig = REGRESSION_CLM_CFG,
    postureCfg: PostureConfig = PostureConfig(),
    calibrated: Boolean = true,
): Analysis {
    // The four threshold values live in duplicate in `dsp` and `detect`: they are synchronised here,
    // otherwise a T12 sweep on `kOn` would touch only half the chain.
    val effCfg = cfg.copy(thresholds = clmCfg.thresholds.toParams())
    val cal = NightCalibration(
        sensor = null,
        gainCalG = if (calibrated) night.truth.gainCalG else Float.NaN,
        floorCalG = night.truth.floorG.toFloat(),
        snrCal = Float.NaN,
        gainSource = if (calibrated) GainSource.GROSS_BODY else GainSource.NONE,
        outlierVsBaseline = false,
    )

    val pass1 = Preprocess.run(blocks, nominalHz, effCfg)
    val postures = PostureDetector.detect(pass1.gravity, pass1.timeline.segments, postureCfg)
    val bounds = postures.map { it.atIdx }.toIntArray()

    val pre = Preprocess.run(blocks, nominalHz, effCfg, bounds, cal)
    val clms = ClmDetector.detect(
        env = pre.envelope,
        floor = pre.floor,
        floorExtrapolated = pre.floorExtrapolated,
        gravity = pre.gravity,
        segments = pre.timeline.segments,
        blindZones = pre.timeline.blindZones,
        postures = postures,
        calibration = cal,
        cfg = clmCfg,
        postureCfg = postureCfg,
    )
    val mask = refinedMask(night.truth, pre.timeline)
    return Analysis(night, pre, postures, clms, mask, cal, clmCfg.thresholds.kOn)
}

/**
 * Ground-truth mask, whose analysable time is recomputed on the timeline actually obtained: valid
 * segments, minus the blind zones and the off-body ones.
 *
 * This is the subtraction the §3.6 mask layer will perform; doing it here keeps the denominator of
 * the measurement and that of the expected value **identical**, which is the condition for T10 to
 * measure the effect of the holes on detection and not on the arithmetic of the denominator.
 */
internal fun refinedMask(truth: GroundTruth, timeline: Timeline): SleepMask {
    val n = timeline.signal.n
    val fs = timeline.signal.fsHz
    if (n == 0) return truth.mask
    val ok = BooleanArray(n)
    for (s in timeline.segments) for (i in s.fromIdx until minOf(s.toIdx, n)) ok[i] = true
    for (z in timeline.blindZones) for (i in maxOf(0, z.fromIdx) until minOf(z.toIdx, n)) ok[i] = false
    for (z in timeline.offBody) for (i in maxOf(0, z.fromIdx) until minOf(z.toIdx, n)) ok[i] = false

    var tstSamples = 0
    var sptSamples = 0
    for (w in truth.mask.windows) {
        val from = Math.round(w.startMsRel * fs / 1000.0).toInt().coerceIn(0, n)
        val to = Math.round(w.endMsRel * fs / 1000.0).toInt().coerceIn(0, n)
        var c = 0
        for (i in from until to) if (ok[i]) c++
        if (w.stage == Stage.SLEEP) tstSamples += c
        if (w.stage != Stage.OUT_OF_BED) sptSamples += c
    }
    val toMin = 1.0 / (fs * 60.0)
    return truth.mask.copy(
        analysableTstMin = tstSamples * toMin,
        analysableSptMin = sptSamples * toMin,
    )
}

// ---------------------------------------------------------------------------------------------
// Night factories
// ---------------------------------------------------------------------------------------------

/** Compact wake/sleep structure, for the short scenarios (T1 to T5). */
internal fun shortSleep(): SleepSpec = SleepSpec(sleepLatencyMin = 1.0, finalWakeMin = 1.0, wasoCount = 0)

/**
 * T6's nominal night: every distractor, a true `aPLM-i` of the order of 25/h **on the
 * accelerometric scale**.
 *
 * The number of injected movements is deliberately larger than the targeted count: 39 % of them are
 * pure ankle rotations and do not move the sensor (Terrill), and the low tail of the amplitude law
 * falls below the physical visibility threshold. Aiming at 25/h on the EMG scale would give about
 * 15/h on the accelerometric scale — that is, exactly at the ICSD-3 threshold, which would tip half
 * the nights to one side or the other for nothing.
 */
internal fun nominalNight(
    seed: Long,
    durationH: Double = 8.0,
    fsRealHz: Double = FS,
    distractors: DistractorSpec = DistractorSpec.ALL,
    gainMultiplier: Double = 1.0,
): SynthNight = NightSynth.generate(
    NightSpec(
        durationH = durationH,
        fsRealHz = fsRealHz,
        // The number of series follows the duration: it is the RATE that must stay constant from
        // one scenario to another, otherwise a short night would become a very severe night.
        trueSeries = listOf(
            SeriesSpec(
                nSeries = Math.round(4.25 * durationH).toInt().coerceAtLeast(1),
                clmPerSeries = 9,
                imiMeanSec = 22.0,
                imiCvPct = 22.0,
            ),
        ),
        isolatedClmPerHour = 6.0,
        gainMultiplier = gainMultiplier,
        distractors = distractors,
    ),
    seed,
)

/** T7's negative night: true `aPLM-i` of the order of 2/h, every distractor active. */
internal fun negativeNight(seed: Long): SynthNight = NightSynth.generate(
    NightSpec(
        durationH = 8.0,
        trueSeries = listOf(SeriesSpec(nSeries = 4, clmPerSeries = 5, imiMeanSec = 24.0, imiCvPct = 25.0)),
        isolatedClmPerHour = 1.0,
        distractors = DistractorSpec.ALL,
    ),
    seed,
)

/** Night without any movement: the named distractor is the only content of the signal. */
internal fun distractorOnlyNight(
    seed: Long,
    minutes: Double,
    distractors: DistractorSpec,
    noise: NoiseSpec = NoiseSpec(),
): SynthNight = NightSynth.generate(
    NightSpec(
        durationH = minutes / 60.0,
        trueSeries = emptyList(),
        isolatedClmPerHour = 0.0,
        distractors = distractors,
        noise = noise,
        sleep = shortSleep(),
    ),
    seed,
)

/**
 * Night of isolated CLM at an amplitude **imposed on the coarse-envelope scale**, for the sweep of
 * the sensitivity curve (T5).
 */
internal fun fixedAmplitudeNight(
    seed: Long,
    minutes: Double,
    envelopeAmplitudeG: Double,
    spacingSec: Double = 20.0,
): SynthNight {
    // Near-regular spacing rather than exponential arrivals: two overlapping events merge into a
    // single detection and would drop the measured sensitivity for a reason that has nothing to do
    // with amplitude.
    val count = ((minutes * 60.0 - 120.0) / spacingSec).toInt().coerceAtLeast(4)
    return NightSynth.generate(
        NightSpec(
            durationH = minutes / 60.0,
            trueSeries = listOf(SeriesSpec(1, count, spacingSec, 5.0)),
            isolatedClmPerHour = 0.0,
            ankleOnlyFraction = 0.0,
            visibilityG = 0.0,
            amplitude = AmplitudeSpec(fixedG = envelopeAmplitudeG, scale = AmplitudeScale.COARSE_ENVELOPE),
            distractors = DistractorSpec.NONE,
            sleep = shortSleep(),
        ),
        seed,
    )
}

/**
 * Effective floor of the detector on a noise-only night, same noise specification.
 * Used to normalise the abscissa of the T5 sweep without running the detector on the test night.
 *
 * @param clmCfg the configuration whose floor is being probed. It is a parameter and not a constant
 *   because the effective floor is `Theta_on / k_on`: on a quiet night it is `Theta_abs` that wins,
 *   so T5's abscissa **depends** on `k_on`. Probing with the default configuration while detecting
 *   with another one would place the events somewhere other than where T5's statement wants them.
 */
internal fun probeEffectiveFloorG(seed: Long, clmCfg: ClmConfig = REGRESSION_CLM_CFG): Double {
    val probe = distractorOnlyNight(seed, minutes = 12.0, distractors = DistractorSpec.NONE)
    return analyse(probe, clmCfg = clmCfg, calibrated = false).effectiveFloorG
}

/**
 * Subset of a ground truth above a given amplitude, on the coarse-envelope scale — the one the
 * detector compares to `Theta_on`.
 *
 * This is the operation that separates the three denominators of §4.1 of the validation document:
 * "mechanically present in the signal" (the whole `accelTruth`), "above the absolute guard rail"
 * and "above the threshold the detector actually applies that night".
 */
internal fun aboveEnvelope(events: List<TruthEvent>, amplitudeG: Double): List<TruthEvent> =
    events.filter { it.envPeakG >= amplitudeG }

/**
 * Fundamental rhythm **actually injected** that night: geometric mean of the onset-to-onset
 * intervals between consecutive movements of the same series, on the EMG scale.
 *
 * Measured rather than read from `NightSpec.imiMeanSec`: the law is truncated to [2 ; 120] s and the
 * series are placed in slots, so that the realised value is not exactly the requested one. Comparing
 * the estimate to a setpoint rather than to the realisation would charge the deconvolution with an
 * error that is not its own.
 *
 * The geometric mean, and not the arithmetic one, because `fundamentalSec = exp(mu)` is the
 * **median** of the fitted log-normal: it is the same quantity on both sides of the comparison.
 */
internal fun injectedFundamentalSec(truth: GroundTruth): Double {
    val logs = ArrayList<Double>()
    truth.emgTruth
        .filter { it.kind == TruthKind.PLM_IN_SERIES && it.seriesId != null }
        .groupBy { it.seriesId }
        .forEach { (_, events) ->
            val ordered = events.sortedBy { it.onsetMsRel }
            for (i in 1 until ordered.size) {
                val d = (ordered[i].onsetMsRel - ordered[i - 1].onsetMsRel) / 1000.0
                if (d > 0.0) logs.add(ln(d))
            }
        }
    return if (logs.isEmpty()) Double.NaN else exp(logs.sum() / logs.size)
}
