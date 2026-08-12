package com.pendulum.phone.ui.chart

import androidx.compose.runtime.Immutable
import com.pendulum.phone.ui.model.Aggregate
import kotlin.math.ceil
import kotlin.math.max

/**
 * The chart descriptions.
 *
 * They are built in the ViewModel, never in a composable. Two direct consequences: they can be
 * tested without a screen, and the PDF export passes exactly the same objects to the same drawing
 * functions — a single rendering path, hence a single place where a divergence between the screen
 * and the paper could be born.
 */

@Immutable
data class Interval(val startMs: Long, val endMs: Long)

/** An event marker, on the dedicated band under the curve. Never overlaid on the signal. */
@Immutable
data class Marker(
    val onsetMs: Long,
    val kind: MarkerKind,
    val seriesNumber: Int?,
    /**
     * Duration of the movement, in milliseconds, and amplitude of its peak in multiples of the
     * noise floor.
     *
     * They do not feed the trace — a marker is a tick on a dedicated band, of fixed width — but the
     * **value table**, which is at once the accessible alternative to the chart and the "I want the
     * exact figure" path. Until now it displayed "2.4 s" and "x9.2" as literals for every row,
     * whatever the movement: a table that promises the exact value and returns a constant is worse
     * than no table at all.
     *
     * Nullable because a marker may come from a source that does not carry them; the table then
     * shows a dash rather than an invented figure.
     */
    val durationMs: Long? = null,
    val amplitudeRatio: Float? = null,
)

/**
 * Three kinds, three distinct shapes — colour is never enough (P6): solid stroke, thin cross,
 * hollow stroke.
 */
enum class MarkerKind { COUNTED, POSTURE_EXCLUDED, DURING_WAKE }

@Immutable
data class AnnotatedPeak(val ratio: Float, val time: String)

/**
 * The night chart.
 *
 * The Y axis is an amplitude **relative to the noise floor**, dimensionless, on a log2 scale.
 * Rationale: the useful amplitudes spread from x1.5 to x30; on a linear scale the small events are
 * invisible, on a log scale they stay readable **and the threshold at x8 becomes a horizontal
 * line**, which makes the detector's logic immediately understandable to the eye.
 */
@Immutable
data class NightChartSpec(
    val startMs: Long,
    val endMs: Long,
    val pyramid: EnvelopePyramid,
    /** Sampling period of the envelope, in ms. */
    val envelopeStepMs: Long,
    /** Noise floor and onset threshold, sampled at ~1 Hz, as a ratio of the floor. */
    val noiseFloor: FloatArray,
    val onsetThreshold: FloatArray,
    val fineSeriesStepMs: Long,
    val outsideSleep: List<Interval>,
    val gaps: List<Interval>,
    val markers: List<Marker>,
    val series: List<Interval>,
    val peak: AnnotatedPeak?,
    val logarithmic: Boolean = true,
    val accessibleDescription: String,
) {
    /**
     * Upper bound of the axis: the power of two immediately above the observed maximum.
     *
     * **No silent clipping.** If a single sample forces the scale to double, we double it and
     * annotate the peak ([peak]). Cutting a peak to keep a round scale means erasing the most
     * informative event of the night.
     */
    val ratioMax: Float
        get() {
            val m = max(peak?.ratio ?: 0f, 32f)
            var p = 1f
            while (p < m) p *= 2f
            return p
        }

    val ratioMin: Float get() = if (logarithmic) 0.5f else 0f
}

/** State of a trend point. A distinct shape for each, never colour alone. */
enum class PointState { ELIGIBLE, ACCEL_MASKED, EXCLUDED }

@Immutable
data class NightPoint(
    val sessionHex: String,
    val dateMs: Long,
    val value: Float,
    val state: PointState,
)

@Immutable
data class ReferenceLine(val value: Float, val label: String, val caption: String)

@Immutable
data class MedianBand(
    val startMs: Long,
    val endMs: Long,
    val median: Float,
    val ciLow: Float,
    val ciHigh: Float,
    val label: String?,
)

/**
 * The trend chart — the most important chart in the product.
 *
 * ### The X axis is calendar-based, not ordinal
 *
 * Nights are laid down at their real date. A week without a measurement leaves a visible gap: that
 * gap is information, not a rendering defect. An ordinal axis would make three nights a month apart
 * look like a regular run.
 *
 * ### The points are not joined up
 *
 * **This is a decision, not an oversight, and it is commented here so that a future contributor
 * does not "fix" it.** A polyline between two nights draws a continuous trajectory between two
 * measurements that have nothing continuous about them, and suggests a causality that does not
 * exist. If a monotonic evolution really is present, the position of the points will show it
 * without needing to be underlined by a stroke. A trend line proper is refused below ten comparable
 * nights.
 */
@Immutable
data class TrendChartSpec(
    val quantity: Aggregate.Quantity,
    val points: List<NightPoint>,
    val bands: List<MedianBand>,
    val reference: ReferenceLine?,
    val firstDayMs: Long,
    val lastDayMs: Long,
    /**
     * The time zone in which the nights were lived.
     *
     * It is not decorative: the X axis is **calendar-based**, so its ticks are dates, and a date
     * does not exist without a calendar. Without it, the only way to label a tick is to divide an
     * epoch by 86 400 000 — that is, to label it in UTC — and a night begun at 23:14 in Paris then
     * shows up on the following day. A one-day step has the same weakness: a civil day lasts 23 or
     * 25 hours twice a year, and a fixed step ends up crossing midnight and repeating a date.
     */
    val zoneId: String,
    /** Pivot date in comparison mode; `null` otherwise. */
    val pivotMs: Long?,
    val accessibleDescription: String,
) {
    /**
     * `yMin = 0`, **hard-coded, not configurable**.
     *
     * For the hourly count this is the classic product invariant: a truncated scale turns a
     * variation of 2/h into a cliff. For the rhythm in seconds the question was put differently — a
     * rhythm has no "natural" zero in the sense that a null duration does not exist
     * physiologically. It is settled the same way and for the same reason: anchoring at zero
     * preserves the **ratios** (21 s against 42 s reads as double), whereas an axis starting at
     * 18 s would turn a three-second difference into a visual collapse. The price is a little lost
     * height; the benefit is that no screenshot can lie about the size of a difference.
     */
    val yMin: Float get() = 0f

    /** `max(20, ceil(1.15 x highest value / 5) x 5)`: rounded up to the next multiple of 5. */
    val yMax: Float
        get() {
            val highest = (points.maxOfOrNull { it.value } ?: 0f)
                .coerceAtLeast(bands.maxOfOrNull { it.ciHigh } ?: 0f)
                .coerceAtLeast(reference?.value ?: 0f)
            return max(20f, ceil(1.15f * highest / 5f) * 5f)
        }

    val tickStep: Float get() = if (yMax <= 40f) 5f else 10f
}

/** A stage segment in the hypnogram. */
@Immutable
data class StageSegment(val startMs: Long, val endMs: Long, val stage: StageUi)

/**
 * The conventional order of sleep laboratories: wake at the top, deep sleep at the bottom.
 * The vertical position is the primary carrier of the information; colour only confirms it.
 *
 * The rank is the only thing this enum carries: the five words written in the margin live in
 * `strings.xml` and travel down to the drawing through [HypnogramLabels]. A label in an enum
 * constructor is an English string that no `values-fr/` can reach.
 */
enum class StageUi(val rank: Int) {
    WAKE(0),
    REM(1),
    N1(2),
    N2(3),
    N3(4),
    ;

    companion object {
        const val LEVELS = 5

        fun fromCode(code: String): StageUi = when (code.uppercase()) {
            "WAKE", "AWAKE_IN_BED", "OUT_OF_BED", "EVEIL" -> WAKE
            "REM" -> REM
            "LIGHT", "N1" -> N1
            "N2", "SLEEP" -> N2
            "DEEP", "N3" -> N3
            else -> N2
        }
    }
}

// =========================================================================================
// The device state band — third band, same axis, deliberately foreign shape
// =========================================================================================

/**
 * Wearing, in three states and not two.
 *
 * [NO_SENSOR] is not a convenience: the Pixel Watch 3 does carry a
 * `TYPE_LOW_LATENCY_OFFBODY_DETECT`, but a device that has none would render an empty band
 * indistinguishable from a "worn all night" band. And the KDoc of `RecordingService` notes that
 * **at the ankle** this detector very probably reads "not worn" permanently: the lane is therefore
 * an indication to cross-check against temperature, not a verdict.
 */
enum class WearState { WORN, REMOVED, NO_SENSOR }

/**
 * The timestamping level, in named classes and **with no graduated axis**.
 *
 * What the class designates is the uncertainty that jitter places on the instant of a sample: the
 * format interpolates linearly between `tFirstNs` and `tLastNs`, so a perfect mean rate obtained by
 * alternating 10 and 30 ms dates each sample only to within 10 ms. It is the **dispersion** that
 * decides the timestamping class, never the mean — and that is why the lane shows `jitterStdUs`
 * and not `measuredRateCentiHz`.
 *
 * Three classes and not a continuous scale, because a continuous scale invites reading a trend into
 * a quantity that has none: what matters is which side of a sampling period one is on, not whether
 * the jitter rose from 1.2 to 1.4 ms.
 */
enum class TimestampLevel(val label: String) {
    FINE("≤ 2 ms"),
    MEDIUM("≤ 10 ms"),
    COARSE("> 10 ms"),
}

/** A timestamping tier. Horizontal step, vertical step: no interpolation. */
@Immutable
data class TimestampTier(val startMs: Long, val endMs: Long, val level: TimestampLevel)

/**
 * The battery gauge — **a gauge, not a curve**.
 *
 * A curve that goes down suggests a dynamic and invites the reader to look for a correlation
 * between the battery and the movements, between which there is no causality at all. The question
 * put to this lane is "did the watch last the night", not "what was the charge at 03:12" — and a
 * gauge answers the first without making the second askable.
 *
 * @param fraction fill in `[0, 1]`, the charge left at the end of the night.
 * @param thresholdMet is the battery criterion of the P1 gate met? It is **computed elsewhere** —
 *   [com.pendulum.phone.ui.model.BatterySlope] when the slope succeeds, the last percentage
 *   otherwise — and never recomputed here: two readings of the same threshold end up diverging.
 * @param label the figure in plain text, next to the gauge. The gauge situates, the text measures.
 */
@Immutable
data class BatteryGauge(val fraction: Float, val thresholdMet: Boolean, val label: String)

/**
 * The state of the device during the night — the third band, under the hypnogram.
 *
 * ### The shared axis is a necessity, not a convenience
 *
 * Without it, a measurement artefact is read as a physiological event: a flat signal taken for
 * calm when the watch was off the wrist, a missing movement taken for a quiet night when the
 * sensor's clipping had flattened its peak. The three bands therefore share `startMs`, `endMs` and
 * the same [XTransform] — a single owner of the gesture, the night chart, exactly as for the
 * hypnogram.
 *
 * ### And the separation must be total in the shape
 *
 * The eye must **not** correlate the battery with the movements: there is no causality between the
 * two, and a reader who found one would be right to believe what he sees and wrong on the
 * substance. Three means, cumulated:
 *
 *  1. **A clear gutter**, abnormally wide, with a hard separating rule. It says "what follows is
 *     not signal" before anything at all has been read.
 *  2. **A foreign graphical grammar**: nothing continuous, nothing curved. Blocks, hard staircases,
 *     *rug plots*, a gauge. This is the *housekeeping* convention of scientific telemetry, isolated
 *     precisely so as not to be read as measurement.
 *  3. **No graduated Y axis.** The lanes carry names, not values: `worn`, `charger`, `timing`. A
 *     numbered axis facing a numbered axis invites comparison.
 *
 * ### The intervals cover the minute that *precedes* their point
 *
 * `fsyncCount`, `fsyncTotalUs`, `fsyncMaxUs` and `clippedSamples` count **from the previous point**
 * ([com.pendulum.format.TelemetryPoint]). A state block therefore extends from the previous point
 * to the current one, and not the other way round — laying the interval down backwards would shift
 * the whole band by a minute, which is exactly the order of magnitude of a movement.
 *
 * @param clipping instants where at least one sample touched the sensor's dynamic range. *Rug
 *   plot*: ticks of the same height, because height would be a scale.
 * @param freezes instants where the worst `fsync` of the period exceeded one sampling period — that
 *   is, where the processor may have frozen long enough to miss an interrupt.
 * @param unavailableText what is written when the night carries no telemetry. An empty band with
 *   its lanes would make one look for a fault where there is a night recorded before telemetry
 *   existed.
 */
@Immutable
data class MetrologySpec(
    val startMs: Long,
    val endMs: Long,
    val wearState: WearState,
    val offWrist: List<Interval>,
    val charging: List<Interval>,
    val timestamping: List<TimestampTier>,
    val clipping: List<Long>,
    val freezes: List<Long>,
    val battery: BatteryGauge?,
    val points: Int,
    val unavailableText: String,
    val accessibleDescription: String,
)

/**
 * The hypnogram, under the night chart, same width, same X transform.
 *
 * When [stages] is `null`, the central lane is **not drawn empty**: it is replaced by a muted band
 * carrying a centred text. An empty lane with its ticks invites the eye to look for a curve that
 * does not exist, and suggests a bug rather than absent data.
 */
@Immutable
data class HypnogramSpec(
    val startMs: Long,
    val endMs: Long,
    val stages: List<StageSegment>?,
    val accelStillness: List<Interval>,
    val disagreement: List<Interval>,
    val unavailableText: String,
    val statistics: String,
)
