package com.pendulum.phone.work

import com.pendulum.algo.detect.ClmDetector
import com.pendulum.algo.detect.PostureDetector
import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.dsp.Calibration
import com.pendulum.algo.dsp.Preprocess
import com.pendulum.algo.indices.Periodicity
import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.mask.ImmobilityMask
import com.pendulum.algo.mask.MaskFusion
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.MaskAgreement
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow

/**
 * The orchestration of `:algo`. **No Android import**: this class runs as it stands on the JVM,
 * which is what makes the most important property of the export testable — that a database rebuilt
 * from a bundle gives back the same result down to the bit.
 *
 * `:algo` does not expose an "analyse this night" function and that is deliberate: it exposes pure
 * steps that somebody has to chain together. That somebody is this file, and the order below is
 * not negotiable.
 *
 * ### The three preprocessing passes, and why three are needed
 *
 * 1. **Pass 0, with nothing.** Its only purpose is to obtain the gravity channel, which the
 *    posture detector needs. Everything else is thrown away.
 * 2. **Pass 1, with the posture boundaries.** A posture change cuts the noise floor estimation
 *    windows: without that cut, the rotation contaminates the floor of the minutes that follow and
 *    the threshold rises for the wrong reasons.
 * 3. **Pass 2, with the calibration.** The third term of the threshold depends on the gain
 *    reference of the night, which is measured on gross body movements, which are produced by a
 *    first detection. The loop is closed once and only once: re-detecting after pass 2 would bring
 *    nothing, the gain reference being already stable.
 *
 * ### The fixed point of the mask
 *
 * `ImmobilityMask.fixedPoint` receives the movement intervals as a lambda. The **already
 * detected** CLMs are wired into it, which is correct here: the detection does not consult the
 * mask, the dependency only goes one way. Neutralising the CLMs as evidence of wake is layer 1 of
 * the answer to circularity — a PLMS is by definition a movement *during* sleep, and using it as
 * evidence of wake makes the index of the most affected subject explode.
 */
object NightAnalyzer {

    /**
     * @param results **four rows** in the nominal case: 2 rule sets x 2 masks. Only two if Health
     *   Connect returned nothing. Both masks are always computed and reported: the gap between
     *   them is information in itself, and hiding it would amount to choosing in silence.
     */
    data class Result(
        val fsHz: Double,
        val analysableMin: Double,
        val analysableTstMin: Double,
        val sampleCount: Long,
        val gapCount: Int,
        val gapTotalMs: Long,
        val truncated: Boolean,
        val integrityRejectedFraction: Double,
        val calibration: NightCalibration,
        val clms: List<Clm>,
        val postures: List<PostureChange>,
        val masks: Map<MaskSource, SleepMask>,
        val agreement: MaskAgreement?,
        val results: List<PlmiResult>,
        val paramsHash: String,
        val algoVersion: String,
    )

    /**
     * @param hcWindows Health Connect hypnogram **already converted** into milliseconds relative
     *   to the start of the timeline (see `TimeAnchor`). The conversion is not done here because
     *   it needs the three clocks of the chunk header, which `:algo` does not see.
     * @param diary manual diary. It does not make the accelerometer mask independent: it bounds
     *   the **search** for the SPT, which stops a stretch of sofa stillness from taking the place
     *   of the start of the night.
     * @param baselineGainG gain reference of the reference night, to detect a strap tightened
     *   differently. `null` on the first night of a campaign.
     */
    fun analyze(
        blocks: List<SampleBlock>,
        nominalRateHz: Int,
        sessionClosedCleanly: Boolean,
        hcWindows: List<SleepWindow>?,
        diary: DiaryWindow?,
        baselineGainG: Float?,
        params: AnalysisParams = AnalysisParams.DEFAULT,
    ): Result {
        // --- Pass 0: only to obtain gravity ------------------------------------------------
        val pass0 = Preprocess.run(
            blocks, nominalRateHz.toDouble(), params.preprocess,
            sessionClosedCleanly = sessionClosedCleanly,
        )
        val postures = PostureDetector.detect(pass0.gravity, pass0.timeline.segments, params.posture)
        val postureBoundaries = postures.map { it.atIdx }.toIntArray()

        // --- Pass 1: noise floor cut at the posture boundaries -----------------------------
        val pass1 = Preprocess.run(
            blocks, nominalRateHz.toDouble(), params.preprocess, postureBoundaries,
            calibration = null, sessionClosedCleanly = sessionClosedCleanly,
        )

        // Provisional detection, only to measure the gain reference. `gainCalG = NaN` disables
        // the third term of the threshold: the detector then works on the adaptive floor alone,
        // which is amply enough to spot a gross body movement.
        val noCalibration = NightCalibration(
            sensor = null,
            gainCalG = Float.NaN,
            floorCalG = Float.NaN,
            snrCal = Float.NaN,
            gainSource = GainSource.NONE,
            outlierVsBaseline = false,
        )
        val provisional = ClmDetector.detect(
            pass1.envelope, pass1.floor, pass1.floorExtrapolated, pass1.gravity,
            pass1.timeline.segments, pass1.timeline.blindZones, postures,
            noCalibration, params.clm, params.posture,
        )
        val calibration = Calibration.fromGrossBodyMovements(provisional, baselineGainG)

        // --- Pass 2: calibrated thresholds --------------------------------------------------
        val pre = Preprocess.run(
            blocks, nominalRateHz.toDouble(), params.preprocess, postureBoundaries,
            calibration = calibration, sessionClosedCleanly = sessionClosedCleanly,
        )
        val clms = ClmDetector.detect(
            pre.envelope, pre.floor, pre.floorExtrapolated, pre.gravity,
            pre.timeline.segments, pre.timeline.blindZones, postures,
            calibration, params.clm, params.posture,
        )

        val timeline = pre.timeline
        val fsHz = timeline.signal.fsHz
        val analysableMin = timeline.analysableSec / 60.0
        val coverage = analysableCoverage(timeline.analysableSec, timeline.signal.n, fsHz)

        // --- Accelerometer mask, fixed point bounded to two iterations ----------------------
        val movementIntervals: (SleepMask) -> List<Segment> = {
            clms.filter { c -> c.isClm }.map { c -> Segment(c.onsetIdx, c.offsetIdx) }
        }
        val fixedPoint = ImmobilityMask.fixedPoint(
            gravity = pre.gravity,
            env = pre.envelope.coarse,
            floor = pre.floor,
            segments = timeline.segments,
            offBody = timeline.offBody,
            diary = diary,
            cfg = params.immobility,
            blindZones = timeline.blindZones,
            movementIntervalsOf = movementIntervals,
        )
        val accelMask = fixedPoint.mask

        val masks = LinkedHashMap<MaskSource, SleepMask>()
        masks[MaskSource.ACCEL_IMMOBILITY] = accelMask

        var agreement: MaskAgreement? = null
        if (!hcWindows.isNullOrEmpty()) {
            masks[MaskSource.HEALTH_CONNECT] = MaskFusion.fromHealthConnect(hcWindows, coverage)
            agreement = MaskFusion.align(accelMask, hcWindows, params.fusion)
        }

        // --- The four results ---------------------------------------------------------------
        val rules = listOf(SeriesConfig.aasmV3(), SeriesConfig.wasm2016())
        val out = ArrayList<PlmiResult>(rules.size * masks.size)
        for ((source, mask) in masks) {
            // These two measurements are **outside the rule loop by design**. Neither
            // `ferriIndex` nor `fromClms` takes the rule as a parameter: at a fixed mask, AASM and
            // WASM give them the same result. Leaving them in the inner loop had them computed
            // four times for two useful results, and `Rhythm.fromClms` is not free —
            // `Rhythm.fit` chains seven EM starts of up to 300 iterations full of `ln` and `exp`,
            // replayed on battery at every re-scoring of a whole campaign.
            val pi = Periodicity.ferriIndex(clms, mask, fsHz, params.periodicity)
            // `fromClms` and not `fromSeries`: series construction has already filtered out the
            // intervals outside [10, 90] s, that is to say precisely the high harmonics the
            // deconvolution is trying to model. Starting from the series would mechanically
            // underestimate the miss rate.
            val rhythm = Rhythm.fromClms(clms, mask, params.rhythm)
            for (cfg in rules) {
                out += computeOne(
                    clms, mask, source, cfg, fsHz, timeline.truncated, params, pi, rhythm,
                    timeline.segments,
                )
            }
        }

        return Result(
            fsHz = fsHz,
            analysableMin = analysableMin,
            analysableTstMin = accelMask.analysableTstMin,
            sampleCount = blocks.sumOf { it.x.size.toLong() },
            gapCount = timeline.gaps.size,
            gapTotalMs = timeline.gaps.sumOf { Math.round(it.durationSec * 1000.0) },
            truncated = timeline.truncated,
            integrityRejectedFraction = timeline.integrity.rejectedFraction,
            calibration = calibration,
            clms = clms,
            postures = postures,
            masks = masks,
            agreement = agreement,
            results = out,
            paramsHash = params.paramsHash,
            algoVersion = params.algoVersion,
        )
    }

    /**
     * @param pi and [rhythm] **measured by the caller, once per mask**. They do not depend on
     *   [cfg]: passing them rather than recomputing them here is what avoids paying twice for the
     *   same deconvolution for the two rule sets of a single mask.
     * @param segments the segments of the timeline. Without them `SeriesBuilder` would let a series
     *   run across a recording gap and publish an interval nobody could have observed — WASM 3.3.3.
     */
    private fun computeOne(
        clms: List<Clm>,
        mask: SleepMask,
        source: MaskSource,
        cfg: SeriesConfig,
        fsHz: Double,
        truncated: Boolean,
        params: AnalysisParams,
        pi: PiResult,
        rhythm: RhythmResult,
        segments: List<Segment>,
    ): PlmiResult {
        val built = SeriesBuilder.buildDetailed(clms, mask, fsHz, cfg, segments)

        return Plmi.compute(
            clms = clms,
            series = built.series,
            mask = mask,
            fsHz = fsHz,
            rule = cfg.rule,
            pi = pi,
            rhythm = rhythm,
            floorMode = FloorMode.BILATERAL,
            truncated = truncated,
            cfg = params.plmi,
            truncatedSeriesDropped = built.truncatedSeriesDropped,
            paramsHash = params.paramsHash,
        ).let { r ->
            // `Plmi.compute` does not know the source of the mask; it is carried here so that the
            // four rows are distinguishable in the database.
            if (r.maskSource == source) r else r.copy(maskSource = source)
        }
    }

    /**
     * Fraction of the time that is actually analysable, passed to the mask builders.
     *
     * It serves to compute `analysableTstMin`, which is **the real denominator**: sleep epochs
     * intersected with the epochs actually covered by valid blocks. The raw TST will not do — an
     * 8 h night of which 3 h are gappy does not have 8 h of analysable sleep, and dividing by 8 h
     * would underestimate the index by a third.
     */
    private fun analysableCoverage(analysableSec: Double, gridPoints: Int, fsHz: Double): Double {
        if (gridPoints <= 0 || fsHz <= 0.0) return 0.0
        val totalSec = gridPoints / fsHz
        return if (totalSec <= 0.0) 0.0 else (analysableSec / totalSec).coerceIn(0.0, 1.0)
    }

    /** The two rule sets, exposed for the tests and for the display. */
    val RULES: List<SeriesRule> = listOf(SeriesRule.AASM_V3, SeriesRule.WASM_2016)
}
