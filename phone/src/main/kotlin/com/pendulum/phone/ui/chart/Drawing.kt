package com.pendulum.phone.ui.chart

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.pendulum.phone.ui.theme.ChartTokens
import kotlin.math.log2
import kotlin.math.max

/**
 * What is common to the three charts.
 *
 * A reminder of the principle from `UX.md` section 8.3, which governs this whole package: **drawing
 * is an extension function of `DrawScope`, never a composable**. The same code is called from a
 * Compose `Canvas` (screen, dark) and from a `DrawScope` mounted on the canvas of a
 * `PdfDocument.Page` (export, light). A single rendering path, hence a single place where a
 * divergence between the screen and the paper could appear — and that divergence is one nobody
 * would see before a doctor had the sheet in hand.
 */

/**
 * The buffers reused from one frame to the next.
 *
 * A pinch redraws sixty times a second. Allocating 4 400 floats and a `Paint` on every pass
 * triggers collections that are visible to the eye: the chart stutters exactly at the moment the
 * user is exploring it. This bag is created once per chart (`remember`) and passed to the drawing
 * functions.
 */
class ChartScratch {
    /** `[min0, max0, min1, max1, …]` filled in by the pyramid. */
    var minMax: FloatArray = FloatArray(0)
        private set

    /** `[x0, y0, x1, y1, …]` in the format `Canvas.drawLines` expects. */
    var segments: FloatArray = FloatArray(0)
        private set

    val paint: Paint = Paint().apply { isAntiAlias = true }

    private val textCache = HashMap<String, TextLayoutResult>()

    fun prepare(columns: Int) {
        if (minMax.size < columns * 2) minMax = FloatArray(columns * 2)
        if (segments.size < columns * 4) segments = FloatArray(columns * 4)
    }

    /**
     * Measuring text is the first bottleneck of a Compose `Canvas`: it is a round trip to the
     * rendering engine, per label and per frame. Axis labels rarely change, so we memoise them by
     * string.
     */
    fun measure(measurer: TextMeasurer, s: String, style: TextStyle): TextLayoutResult =
        textCache.getOrPut(s + style.fontSize.value) { measurer.measure(s, style) }
}

/** Internal margins of a chart, in drawing pixels. */
data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** dp -> drawing units conversion. On export, `density` carries the points/dp factor. */
fun DrawScope.dpPx(v: Float): Float = v * density

/**
 * The default margins for a given density, **outside any `DrawScope`**.
 *
 * This form exists for a precise reason: tap detection must read exactly the same plot area as the
 * drawing, and the gesture handler is not a `DrawScope`. As long as the 40 dp of left margin only
 * existed in the version below, the search for the nearest point computed its abscissas over the
 * full width of the canvas: up to 40 dp between the point seen and the point touched, against an
 * acceptance radius of 24 dp. On a chart where each point opens the detail of a night, that means
 * opening the wrong night.
 */
fun defaultMargins(density: Float): Margins = Margins(
    left = 40f * density,
    top = 12f * density,
    right = 16f * density,
    bottom = 22f * density,
)

val DrawScope.defaultMargins: Margins get() = defaultMargins(density)

/** Effective plot area, for a given canvas size. */
fun plotArea(m: Margins, width: Float, height: Float): Rect = Rect(
    left = m.left,
    top = m.top,
    right = width - m.right,
    bottom = height - m.bottom,
)

/** Effective plot area. */
fun DrawScope.plotArea(m: Margins): Rect = plotArea(m, size.width, size.height)

/**
 * Base-2 logarithmic Y axis, for the night chart.
 *
 * The detection threshold being a fixed multiple of the noise floor, in log2 it becomes a
 * **horizontal line**: the detector's logic can then be read at a glance, which is exactly what the
 * technically minded user comes to check.
 */
class Log2Axis(private val min: Float, private val max: Float, private val top: Float, private val height: Float) {
    private val l0 = log2(min.coerceAtLeast(1e-3f))
    private val l1 = log2(max.coerceAtLeast(min * 2f))
    fun y(ratio: Float): Float {
        val l = log2(ratio.coerceAtLeast(min * 0.5f))
        return top + height * (1f - (l - l0) / (l1 - l0))
    }
}

/** Linear Y axis anchored at zero. Used by the trend, and never truncated. */
class LinearAxis(private val min: Float, private val max: Float, private val top: Float, private val height: Float) {
    fun y(v: Float): Float = top + height * (1f - (v - min) / (max - min).coerceAtLeast(1e-6f))
}

/** Hatching at 45 degrees, drawn by clip + loop. A stroke `PathEffect` does not fill an area. */
fun DrawScope.hatch(rect: Rect, color: Color, strokeWidth: Float, spacing: Float) {
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        val h = rect.height
        var i = rect.left - h
        while (i < rect.right) {
            drawLine(
                color = color,
                start = Offset(i, rect.bottom),
                end = Offset(i + h, rect.top),
                strokeWidth = strokeWidth,
            )
            i += spacing
        }
    }
}

/** Dashed line: the second carrier of information alongside colour (P6). */
fun DrawScope.dashedLine(
    y: Float,
    fromX: Float,
    toX: Float,
    color: Color,
    strokeWidth: Float,
    dash: FloatArray,
    alpha: Float = 1f,
) = drawLine(
    color = color,
    start = Offset(fromX, y),
    end = Offset(toX, y),
    strokeWidth = strokeWidth,
    alpha = alpha,
    pathEffect = PathEffect.dashPathEffect(dash, 0f),
)

/**
 * The envelope trace: one vertical `min -> max` segment per pixel column, pushed in a single call
 * to the native canvas.
 *
 * We deliberately drop below `DrawScope` for this layer, and for it alone.
 * `DrawScope.drawPoints` expects a `List<Offset>`: building that list allocates 1 100 objects per
 * frame, which cancels out the benefit of the pyramid. `Canvas.drawLines(FloatArray, Paint)` takes
 * the pre-allocated array as it is. The native canvas is available both under a Compose `Canvas`
 * and under a PDF page, so this optimisation does not break the uniqueness of the rendering path.
 */
fun DrawScope.drawEnvelope(
    scratch: ChartScratch,
    columns: Int,
    xOrigin: Float,
    columnWidth: Float,
    yOf: (Float) -> Float,
    color: Color,
    strokeWidth: Float,
) {
    val seg = scratch.segments
    var n = 0
    for (c in 0 until columns) {
        val mn = scratch.minMax[c * 2]
        val mx = scratch.minMax[c * 2 + 1]
        if (mn > mx) continue
        val x = xOrigin + c * columnWidth
        seg[n++] = x
        seg[n++] = yOf(mx)
        seg[n++] = x
        seg[n++] = yOf(mn)
    }
    scratch.paint.color = color.toArgb()
    scratch.paint.strokeWidth = strokeWidth
    scratch.paint.strokeCap = Paint.Cap.BUTT
    drawContext.canvas.nativeCanvas.drawLines(seg, 0, n, scratch.paint)
}

/** Small utility: axis text, already measured and memoised. */
fun DrawScope.axisText(
    measurer: TextMeasurer,
    scratch: ChartScratch,
    t: ChartTokens,
    s: String,
    x: Float,
    y: Float,
    color: Color = t.axisText,
    textSize: TextUnit = t.axisTextSize.sp,
    centred: Boolean = false,
) {
    val style = TextStyle(color = color, fontSize = textSize, fontFeatureSettings = "tnum")
    val measured = scratch.measure(measurer, s, style)
    val dx = if (centred) -measured.size.width / 2f else 0f
    drawText(measured, topLeft = Offset(x + dx, y))
}

/** Filled circle / filled circle plus ring / hollow circle: the three night states, as shapes. */
fun DrawScope.nightPoint(centre: Offset, state: PointState, t: ChartTokens, radius: Float) {
    when (state) {
        PointState.ELIGIBLE -> drawCircle(t.primaryData, radius, centre)
        PointState.ACCEL_MASKED -> {
            drawCircle(t.primaryData, radius, centre)
            drawCircle(t.attention, radius + t.strokeNormal, centre, style = Stroke(t.strokeNormal))
        }
        PointState.EXCLUDED -> drawCircle(t.structural, radius, centre, style = Stroke(t.strokeNormal))
    }
}

internal fun clampColumns(width: Float): Int = max(1, width.toInt())

/**
 * The hour ticks of the X axis, **written once for all the bands**.
 *
 * The time axis is shared by the night chart, the hypnogram and the metrology band; if there were
 * two versions of its ticks, they would end up falling on different hours and the alignment, which
 * is the whole point of the stack, would become wrong by a pixel. A movement read in the wrong
 * stage, or attributed to a minute when the watch was on its charger, raises no exception.
 *
 * @param textY ordinate of the text, which differs from one band to the next according to its
 *   bottom margin.
 */
internal fun DrawScope.drawHourTicks(
    startMs: Long,
    endMs: Long,
    area: Rect,
    textY: Float,
    t: ChartTokens,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    xOf: (Long) -> Float,
) {
    val hourMs = 3_600_000L
    var ms = (startMs / hourMs) * hourMs
    if (ms < startMs) ms += hourMs
    while (ms <= endMs) {
        val px = xOf(ms)
        if (px in area.left..area.right) {
            drawLine(t.structural, Offset(px, area.bottom), Offset(px, area.bottom + dpPx(3f)), t.strokeThin)
            axisText(measurer, scratch, t, formatHourMinute(ms), px, textY, centred = true)
        }
        ms += hourMs
    }
}

/**
 * Minimal time-of-day format, with no dependency on a time zone: the timestamps passed to the
 * drawing functions have already been shifted by the ViewModel to the local wall clock time
 * recorded with the night. Doing the conversion here would force the drawing function to know a
 * `ZoneId`, hence to differ between the screen and the export.
 */
internal fun formatHourMinute(ms: Long): String {
    val minutesOfDay = ((ms / 60000L) % 1440L).toInt()
    val h = minutesOfDay / 60
    val mn = minutesOfDay % 60
    return "%02d:%02d".format(h, mn)
}
