package com.pendulum.algo.model

/**
 * Shared types of the `algo` module. Transcription of `docs/workings/ALGO-v2.md` §4.1.
 *
 * Module rules:
 *  - pure functions, no I/O, no wall clock, `fs` always explicit;
 *  - no `import android.*`;
 *  - **no dependency on `:format`**. The input is [SampleBlock], a minimal interface; the adapter
 *    on top of `com.pendulum.format.DecodedBlock` lives in `:phone`. This keeps the module
 *    testable without the codec, allows synthetic data to be injected, and — above all — allows
 *    `algo` to **revalidate** the timestamps without inheriting the (absent) guarantees of the
 *    block CRC.
 */

/** Minimal input. Amplitudes in g. */
interface SampleBlock {
    val tFirstNs: Long
    val tLastNs: Long
    val flags: Int
    val x: FloatArray
    val y: FloatArray
    val z: FloatArray

    /**
     * Nominal rate declared by the chunk header that carried this block, in Hz; `0.0` when the
     * producer does not know it, in which case the caller's session nominal is used instead.
     *
     * A session is **not** at a single rate, and treating it as one destroyed data. Auto-degradation
     * step 3 re-registers the sensor at 25 Hz in the middle of the night
     * (`SensorStrategy.degradedTo(3)`) and rotates the chunk, so the new rate lands in the *next*
     * header — the watch records the change correctly. But the phone kept only the first chunk's
     * nominal, and integrity check no. 3 then measured every post-degradation block against 50 Hz:
     * a 50 % deviation against a 20 % tolerance, so every one of them was rejected as
     * `IMPLAUSIBLE_RATE`. The whole point of degrading is to keep recording; the night was thrown
     * away precisely when the watch had managed to save it.
     */
    val nominalHz: Double get() = 0.0
}

/** Trivial implementation, used by the synthetic generator and the adapters. */
data class SimpleBlock(
    override val tFirstNs: Long,
    override val tLastNs: Long,
    override val flags: Int,
    override val x: FloatArray,
    override val y: FloatArray,
    override val z: FloatArray,
    override val nominalHz: Double = 0.0,
) : SampleBlock {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimpleBlock) return false
        // `nominalHz` is part of the identity: two blocks holding the same samples but recorded at
        // different rates are not the same block, and saying otherwise would let a test that
        // straddles an auto-degradation pass while comparing the wrong thing.
        return tFirstNs == other.tFirstNs && tLastNs == other.tLastNs && flags == other.flags &&
            nominalHz == other.nominalHz &&
            x.contentEquals(other.x) && y.contentEquals(other.y) && z.contentEquals(other.z)
    }

    override fun hashCode(): Int {
        var r = tFirstNs.hashCode()
        r = 31 * r + tLastNs.hashCode()
        r = 31 * r + flags
        r = 31 * r + nominalHz.hashCode()
        r = 31 * r + x.contentHashCode()
        r = 31 * r + y.contentHashCode()
        r = 31 * r + z.contentHashCode()
        return r
    }
}

/** Tri-axial signal on a uniform grid. `NaN` = missing sample. */
class TriAxial(
    val fsHz: Double,
    val t0Ns: Long,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
) {
    init {
        require(x.size == y.size && y.size == z.size) { "axes of different sizes" }
        require(fsHz > 0.0) { "fsHz must be > 0" }
    }

    val n: Int get() = x.size

    fun tNs(i: Int): Long = t0Ns + Math.round(i * 1e9 / fsHz)

    fun tMsRel(i: Int): Long = Math.round(i * 1000.0 / fsHz)

    /** Grid index matching a relative instant, clamped to `[0, n]`. */
    fun indexOfMsRel(msRel: Long): Int =
        Math.round(msRel * fsHz / 1000.0).toInt().coerceIn(0, n)
}

/** Scalar signal on the same uniform grid. */
class Signal1D(val fsHz: Double, val t0Ns: Long, val v: FloatArray) {
    val n: Int get() = v.size

    fun tMsRel(i: Int): Long = Math.round(i * 1000.0 / fsHz)
}

/**
 * Two-scale envelope. The coarse one carries the detection (it cancels the ripple at 2f); the fine
 * one only serves to realign the edges of an already detected event.
 */
data class DualEnvelope(
    val coarse: Signal1D,
    val fine: Signal1D,
    val coarseWinSec: Double,
    val fineWinSec: Double,
)

enum class GapKind {
    /** Short enough to be interpolated without artefact. */
    MICRO,

    /** Too long to interpolate, too short to break the segment: blind zone. */
    BLIND,

    /** Segment break: the filter state is lost, the series is broken. */
    SEGMENT_BREAK,
}

data class Gap(val fromIdx: Int, val toIdx: Int, val kind: GapKind, val durationSec: Double)

/** Continuous, analysable interval. Bounds as uniform-grid indices, `toIdx` excluded. */
data class Segment(val fromIdx: Int, val toIdx: Int) {
    val length: Int get() = toIdx - fromIdx

    companion object {
        /**
         * The intervals left by the [GapKind.SEGMENT_BREAK] holes of [gaps] over a grid of [n]
         * points.
         *
         * Shared rather than rewritten at each site because the same segmentation now feeds the
         * timeline, the ground-truth expectation and the bench, and `SeriesBuilder` breaks a series
         * on its boundaries: two sites disagreeing on where a recording restarts would publish two
         * different indices for the same night, and only one of them would be tested.
         */
        fun between(gaps: List<Gap>, n: Int): List<Segment> {
            val out = ArrayList<Segment>()
            var start = 0
            for (g in gaps.sortedBy { it.fromIdx }) {
                if (g.kind != GapKind.SEGMENT_BREAK) continue
                if (g.fromIdx > start) out.add(Segment(start, g.fromIdx))
                start = maxOf(start, g.toIdx)
            }
            if (start < n) out.add(Segment(start, n))
            return out
        }
    }
}

// --- Integrity (step -1) ---

enum class IntegrityViolation {
    BAD_COUNT,
    BAD_TIMESTAMP_ORDER,
    IMPLAUSIBLE_RATE,
    NON_MONOTONIC,
    OVERLAP,
    IMPLAUSIBLE_GAP,
    BAD_CHUNK_INDEX,
    IMPOSSIBLE_JERK,
    SATURATED,
    GRAVITY_IMPLAUSIBLE,
    FLAG_INCONSISTENT,
}

data class IntegrityReport(
    val blocksTotal: Int,
    val blocksRejected: Int,
    val byViolation: Map<IntegrityViolation, Int>,
    val rejectedFraction: Double,
    /** True if the pattern of rejections suggests a decoder desynchronisation, not noise. */
    val decodeSuspect: Boolean,
) {
    val acceptable: Boolean get() = rejectedFraction <= 0.01 && !decodeSuspect
}

data class IntegrityConfig(
    val maxRateDeviation: Double = 0.20,
    val maxGapNs: Long = 14L * 3600 * 1_000_000_000,
    val maxJerkG: Float = 8.0f,
    val saturationFraction: Double = 0.05,
    val gravityRangeG: ClosedFloatingPointRange<Float> = 0.80f..1.20f,
)

data class FsEstimate(
    val fsSessionHz: Double,
    val fsNominalHz: Double,
    val blocksRejected: Int,
    /** Drift of `SensorEvent.timestamp` relative to the wall clock, in ppm. */
    val clockDriftPpm: Double,
    val clockDriftSuspect: Boolean,
)

data class Timeline(
    /** Uniform grid at `targetFsHz`, `NaN` in the gaps. */
    val signal: TriAxial,
    val gaps: List<Gap>,
    val segments: List<Segment>,
    val blindZones: List<Segment>,
    val fs: FsEstimate,
    val offBody: List<Segment>,
    val integrity: IntegrityReport,
    val analysableSec: Double,
    /** The session stops without a clean end marker (dead watch, kill). */
    val truncated: Boolean,
)

// --- Events ---

object ClmFlags {
    const val POSTURAL = 1 shl 0
    const val GROSS_BODY = 1 shl 1

    /** Exceeds `clmMaxSec`: breaks the series, is never a CLM. */
    const val LM_LONG = 1 shl 2
    const val TRUNCATED = 1 shl 3
    const val IN_BLIND_ZONE = 1 shl 4
    const val FLOOR_EXTRAPOLATED = 1 shl 5
    const val TRANSMITTED_SUSPECT = 1 shl 6

    /** The threshold was dominated by the absolute floor. */
    const val ABS_FLOOR_LIMITED = 1 shl 7

    /** The threshold was dominated by the calibration term. */
    const val CAL_FLOOR_LIMITED = 1 shl 8
    const val DURING_WAKE = 1 shl 9
    const val OFF_BODY = 1 shl 10

    /** Series whose median IMI falls in the apnoeic band. */
    const val RESP_SUSPECT = 1 shl 11
}

enum class ClmRejectReason { TOO_SHORT, MORPHOLOGY, POSTURAL, GROSS_BODY, BLIND_ZONE, OFF_BODY, TRUNCATED }

data class Clm(
    val onsetIdx: Int,
    val offsetIdx: Int,
    val onsetMsRel: Long,
    val durationMs: Int,
    val peakAmpG: Float,
    val medianAmpG: Float,
    val noiseFloorG: Float,
    val thresholdOnG: Float,
    val thresholdOffG: Float,
    val tiltChangeDeg: Float,
    val tiltExcursionDeg: Float,
    val flags: Int,
    val reject: ClmRejectReason?,
) {
    val isClm: Boolean get() = reject == null && (flags and ClmFlags.LM_LONG) == 0
}

data class PostureChange(val atIdx: Int, val atMsRel: Long, val deltaDeg: Float, val settleMs: Int)

enum class SeriesRule { AASM_V3, WASM_2016 }

/**
 * What to do with a CLM preceded by too short an interval.
 * This is **the** parameter that really separates the two rule sets (Ferri 2015):
 * WASM breaks the series, AASM is silent and the event is skipped.
 */
enum class ShortImiPolicy { BREAK_SERIES, SKIP_LATER }

data class PlmSeries(
    val rule: SeriesRule,
    /** Indices into the list of retained [Clm]. */
    val clmIndices: IntArray,
    /** Size = `clmIndices.size - 1`. */
    val imiSec: FloatArray,
    val truncatedAtStart: Boolean,
    val truncatedAtEnd: Boolean,
    val duringSleepFraction: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlmSeries) return false
        return rule == other.rule && clmIndices.contentEquals(other.clmIndices) &&
            imiSec.contentEquals(other.imiSec) && truncatedAtStart == other.truncatedAtStart &&
            truncatedAtEnd == other.truncatedAtEnd && duringSleepFraction == other.duringSleepFraction
    }

    override fun hashCode(): Int {
        var r = rule.hashCode()
        r = 31 * r + clmIndices.contentHashCode()
        r = 31 * r + imiSec.contentHashCode()
        r = 31 * r + truncatedAtStart.hashCode()
        r = 31 * r + truncatedAtEnd.hashCode()
        r = 31 * r + duringSleepFraction.hashCode()
        return r
    }
}

// --- Mask ---

enum class Stage { WAKE, SLEEP, LIGHT, DEEP, REM, AWAKE_IN_BED, OUT_OF_BED, UNKNOWN }

enum class MaskSource { ACCEL_IMMOBILITY, HEALTH_CONNECT, DIARY, FUSED }

/**
 * Where the denominator comes from, and is it circular? Drives what the interface is allowed to
 * display: a primary result can never rest on [CIRCULAR].
 */
enum class DenominatorIndependence { INDEPENDENT_HC, INDEPENDENT_DIARY, SPT_QUASI_INDEPENDENT, CIRCULAR }

data class SleepWindow(val startMsRel: Long, val endMsRel: Long, val stage: Stage) {
    val durationMin: Double get() = (endMsRel - startMsRel) / 60_000.0
}

/** Manual sleep diary: denominator completely independent of the signal. */
data class DiaryWindow(val bedTimeMsRel: Long, val riseTimeMsRel: Long)

data class SleepMask(
    val windows: List<SleepWindow>,
    val source: MaskSource,
    val sptMin: Double,
    val tstMin: Double,
    val wasoMin: Double,
    /**
     * TST ∩ valid segments ∩ outside blind zones ∩ outside off-body. This is the real denominator.
     */
    val analysableTstMin: Double,
    val analysableSptMin: Double,
    val corrected: Boolean,
    val lagAppliedMs: Long,
    val independence: DenominatorIndependence,
    val fixedPointConverged: Boolean,
)

data class MaskAgreement(val kappa: Double, val tstDeltaMin: Double, val overlapPct: Double, val bestLagMs: Long)

// --- Calibration ---

data class SensorCalibration(
    val offsetG: FloatArray,
    val scale: FloatArray,
    val residualG: Float,
    val valid: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensorCalibration) return false
        return offsetG.contentEquals(other.offsetG) && scale.contentEquals(other.scale) &&
            residualG == other.residualG && valid == other.valid
    }

    override fun hashCode(): Int {
        var r = offsetG.contentHashCode()
        r = 31 * r + scale.contentHashCode()
        r = 31 * r + residualG.hashCode()
        r = 31 * r + valid.hashCode()
        return r
    }
}

enum class GainSource { GROSS_BODY, NONE }

data class NightCalibration(
    val sensor: SensorCalibration?,
    val gainCalG: Float,
    val floorCalG: Float,
    val snrCal: Float,
    val gainSource: GainSource,
    /** The gain deviates from the reference night beyond tolerance: night not comparable. */
    val outlierVsBaseline: Boolean,
)

// --- Results ---

enum class FloorMode { BILATERAL, CAUSAL_LAGGED }

/** What the analysis allows to be published. Evaluated by the code, never by the user. */
enum class PublicationGate { FULL, TRUNCATED_NO_TREND, NO_PLMI }

data class PiResult(
    val periodicityIndex: Double,
    val valid: Boolean,
    val totalIntervals: Int,
    val lmRatePerHour: Double,
)

/**
 * Fundamental rhythm estimated by deconvolution of the harmonics of the inter-movement interval
 * (`SPEC-v2.md` §5). This is the product's **tracking metric**: it has no denominator, hence no
 * circularity, and its published night-to-night variability is twelve times lower than that of
 * the hourly count.
 *
 * @param fundamentalSec fundamental period `exp(mu)`, in seconds.
 * @param muLog mean of the log of the fundamental interval, in nats.
 * @param sigmaLog standard deviation of the log.
 * @param missRate estimated miss rate. An output **as important as the period**: a rate that
 *   jumps from one night to the next signals that the two nights are not comparable, and a rate
 *   close to 0.5 with a weak fundamental peak suggests a left/right alternation.
 * @param harmonicWeights weights of the 1x, 2x, 3x… components of the mixture.
 * @param alternationSuspect true if the weight profile suggests an alternation between the legs.
 */
data class RhythmResult(
    val fundamentalSec: Double,
    val muLog: Double,
    val sigmaLog: Double,
    val missRate: Double,
    val harmonicWeights: DoubleArray,
    val alternationSuspect: Boolean,
    val intervalsUsed: Int,
    val converged: Boolean,
    val valid: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RhythmResult) return false
        return fundamentalSec == other.fundamentalSec && muLog == other.muLog &&
            sigmaLog == other.sigmaLog && missRate == other.missRate &&
            harmonicWeights.contentEquals(other.harmonicWeights) &&
            alternationSuspect == other.alternationSuspect && intervalsUsed == other.intervalsUsed &&
            converged == other.converged && valid == other.valid
    }

    override fun hashCode(): Int {
        var r = fundamentalSec.hashCode()
        r = 31 * r + muLog.hashCode()
        r = 31 * r + sigmaLog.hashCode()
        r = 31 * r + missRate.hashCode()
        r = 31 * r + harmonicWeights.contentHashCode()
        r = 31 * r + alternationSuspect.hashCode()
        r = 31 * r + intervalsUsed
        r = 31 * r + converged.hashCode()
        r = 31 * r + valid.hashCode()
        return r
    }
}

data class PlmiResult(
    val rule: SeriesRule,
    val maskSource: MaskSource,
    val plmsCount: Int,
    val plmwCount: Int,
    val isolatedCount: Int,
    val shortImiCount: Int,
    val tstMin: Double,
    val analysableTstMin: Double,
    val sptMin: Double,
    val wasoMin: Double,
    val plmi: Double,
    val plmiSpt: Double,
    val plmw: Double,
    val pi: PiResult,
    val rhythm: RhythmResult,
    val plmiFirstHalf: Double,
    val plmiSecondHalf: Double,
    val imiHistogram: IntArray,
    val imiBinEdgesSec: FloatArray,
    val truncatedSeriesDropped: Int,
    val plmiRespWorstCase: Double,
    val independence: DenominatorIndependence,
    val gate: PublicationGate,
    val floorMode: FloorMode,
    val paramsHash: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlmiResult) return false
        return rule == other.rule && maskSource == other.maskSource && plmsCount == other.plmsCount &&
            plmi == other.plmi && paramsHash == other.paramsHash &&
            imiHistogram.contentEquals(other.imiHistogram)
    }

    override fun hashCode(): Int {
        var r = rule.hashCode()
        r = 31 * r + maskSource.hashCode()
        r = 31 * r + plmsCount
        r = 31 * r + plmi.hashCode()
        r = 31 * r + paramsHash.hashCode()
        r = 31 * r + imiHistogram.contentHashCode()
        return r
    }
}

data class QualityReport(
    val analysableFraction: Double,
    val gapCount: Int,
    val gapTotalSec: Double,
    val longestGapSec: Double,
    val postureChanges: Int,
    val grossBodyMovements: Int,
    val medianFloorG: Float,
    val floorVsBaselineRatio: Double,
    val offBodyFraction: Double,
    val clockDriftSuspect: Boolean,
    val integrity: IntegrityReport,
    val truncatedNight: Boolean,
    val maskNonConvergent: Boolean,
    val warnings: List<String>,
)

data class NightAnalysis(
    val timeline: Timeline,
    val calibration: NightCalibration,
    val clms: List<Clm>,
    val postures: List<PostureChange>,
    val masks: Map<MaskSource, SleepMask>,
    val agreement: MaskAgreement?,
    /** Four rows: 2 rule sets x 2 masks. */
    val results: List<PlmiResult>,
    val plmiLowerBound: Double,
    val plmiUpperBound: Double,
    val quality: QualityReport,
    val algoVersion: String,
)
