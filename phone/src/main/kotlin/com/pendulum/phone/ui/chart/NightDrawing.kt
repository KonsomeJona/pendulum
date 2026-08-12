package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import com.pendulum.phone.ui.theme.ChartTokens
import kotlin.math.roundToInt

/**
 * The night chart — 1.44 million points, six layers, no clipping.
 *
 * The layers are drawn from back to front, and the order carries meaning:
 *
 * 1. **outside-sleep bands** — they say visually "nothing is counted here";
 * 2. **signal gaps** — hatched, with their duration written above when it exceeds 5 s;
 * 3. **noise floor** — fine dotted line;
 * 4. **adaptive onset threshold** — dashed; on a log2 scale it is a horizontal line;
 * 5. **RMS envelope** — solid stroke, decimated min/max, never averaged;
 * 6. **event markers** — on a dedicated band **under** the signal area, never overlaid on the
 *    curve: overlaid, they hide precisely the part of the signal they claim to designate.
 *
 * The three lines are told apart by their stroke — solid / dashed / dotted — and not by their hue
 * alone (P6).
 */
fun DrawScope.drawNightChart(
    spec: NightChartSpec,
    t: ChartTokens,
    x: XTransform,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    /**
     * The annotation of the off-scale peak, **already formatted**, or `null` when the night carries
     * none. Same reason as the lane labels: the drawing has neither composition nor `Context`, hence
     * neither resources nor `Locale` — it is the calling composable that resolves it.
     */
    peakLabel: String? = null,
    cursorMs: Long? = null,
) {
    val m = defaultMargins
    val markerBandHeight = dpPx(14f)
    val area = plotArea(m)
    val signalArea = Rect(area.left, area.top, area.right, area.bottom - markerBandHeight)
    x.widthPx = signalArea.width

    val axis = Log2Axis(spec.ratioMin, spec.ratioMax, signalArea.top, signalArea.height)
    fun yOf(ratio: Float) = axis.y(ratio)
    fun xOf(ms: Long) = signalArea.left + x.xOf(ms)

    // --- 1. outside-sleep bands
    for (iv in spec.outsideSleep) {
        val x0 = xOf(iv.startMs).coerceIn(signalArea.left, signalArea.right)
        val x1 = xOf(iv.endMs).coerceIn(signalArea.left, signalArea.right)
        if (x1 <= x0) continue
        drawRect(t.mutedBand, Offset(x0, signalArea.top), Size(x1 - x0, signalArea.height))
    }

    // --- 2. signal gaps, hatched, duration annotated beyond 5 s
    for (iv in spec.gaps) {
        val x0 = xOf(iv.startMs).coerceIn(signalArea.left, signalArea.right)
        val x1 = xOf(iv.endMs).coerceIn(signalArea.left, signalArea.right)
        if (x1 <= x0) continue
        val r = Rect(x0, signalArea.top, x1, signalArea.bottom)
        hatch(r, t.structural, t.strokeThin, dpPx(2f))
        val seconds = (iv.endMs - iv.startMs) / 1000
        if (seconds > 5) {
            axisText(measurer, scratch, t, "$seconds s", (x0 + x1) / 2f, signalArea.top, centred = true)
        }
    }

    // --- 3 and 4. noise floor and threshold: a few thousand points, very few columns
    drawFineSeries(spec.noiseFloor, spec, signalArea, x, t.structural, t.strokeThin, t.dashNoiseFloor, ::yOf)
    drawFineSeries(
        spec.onsetThreshold, spec, signalArea, x, t.primaryData, t.strokeNormal, t.dashThreshold, ::yOf,
        alpha = t.thresholdAlpha,
    )

    // --- 5. envelope, decimated min/max per pixel column
    val columns = clampColumns(signalArea.width)
    scratch.prepare(columns)
    val total = spec.pyramid.size
    val from = (x.visibleWindow.start * total).roundToInt().coerceIn(0, total)
    val to = (x.visibleWindow.endInclusive * total).roundToInt().coerceIn(from + 1, total)
    spec.pyramid.fillMinMax(from, to, columns, scratch.minMax)
    drawEnvelope(
        scratch = scratch,
        columns = columns,
        xOrigin = signalArea.left,
        columnWidth = signalArea.width / columns,
        yOf = ::yOf,
        color = t.primaryData,
        strokeWidth = t.strokeNormal,
    )

    // --- off-scale peak: annotated, never cut
    spec.peak?.let { peak ->
        if (peak.ratio > 32f && peakLabel != null) {
            axisText(
                measurer, scratch, t,
                peakLabel,
                signalArea.left + dpPx(4f), signalArea.top + dpPx(2f),
                color = t.annotationText,
            )
        }
    }

    // --- 6. markers, on their dedicated band
    drawMarkers(spec, t, signalArea, markerBandHeight, ::xOf)

    // --- axes
    drawLogYAxis(spec, t, signalArea, measurer, scratch, ::yOf)
    drawHourXAxis(spec, t, signalArea, m, measurer, scratch, ::xOf)

    // --- cursor, shared with the hypnogram
    cursorMs?.let {
        val cx = xOf(it)
        if (cx in signalArea.left..signalArea.right) {
            drawLine(t.annotationText, Offset(cx, area.top), Offset(cx, area.bottom), t.strokeThin)
        }
    }
}

/**
 * Binary search over the visible window, then a loop over the visible events only.
 * Looping over the night's 412 events on every pinch frame can be felt under the finger.
 */
private fun DrawScope.drawMarkers(
    spec: NightChartSpec,
    t: ChartTokens,
    area: Rect,
    bandHeight: Float,
    xOf: (Long) -> Float,
) {
    val bandTop = area.bottom + dpPx(2f)
    val bandBottom = bandTop + bandHeight - dpPx(6f)

    // Series: a continuous horizontal bar under the markers.
    for (s in spec.series) {
        val x0 = xOf(s.startMs).coerceIn(area.left, area.right)
        val x1 = xOf(s.endMs).coerceIn(area.left, area.right)
        if (x1 <= x0) continue
        drawRect(
            t.primaryData,
            Offset(x0, bandBottom + dpPx(1f)),
            Size(x1 - x0, dpPx(4f)),
            alpha = t.seriesBarAlpha,
        )
    }

    val list = spec.markers
    var i = firstVisible(list, area.left, xOf)
    while (i < list.size) {
        val mk = list[i]
        val mx = xOf(mk.onsetMs)
        if (mx > area.right) break
        if (mx >= area.left) {
            when (mk.kind) {
                // Solid: counted.
                MarkerKind.COUNTED -> drawLine(
                    t.primaryData, Offset(mx, bandTop), Offset(mx, bandBottom), t.strokeBold,
                )
                // Thin cross: excluded because of a posture change.
                MarkerKind.POSTURE_EXCLUDED -> {
                    val r = dpPx(3f)
                    val cy = (bandTop + bandBottom) / 2f
                    drawLine(t.structural, Offset(mx - r, cy - r), Offset(mx + r, cy + r), t.strokeThin)
                    drawLine(t.structural, Offset(mx - r, cy + r), Offset(mx + r, cy - r), t.strokeThin)
                }
                // Hollow: movement that occurred during wake, counted separately (PLMW).
                MarkerKind.DURING_WAKE -> {
                    drawLine(t.primaryData, Offset(mx, bandTop), Offset(mx, bandTop + dpPx(3f)), t.strokeNormal)
                    drawLine(t.primaryData, Offset(mx, bandBottom - dpPx(3f)), Offset(mx, bandBottom), t.strokeNormal)
                }
            }
        }
        i++
    }
}

private fun firstVisible(list: List<Marker>, xMin: Float, xOf: (Long) -> Float): Int {
    var low = 0
    var high = list.size
    while (low < high) {
        val mid = (low + high) / 2
        if (xOf(list[mid].onsetMs) < xMin) low = mid + 1 else high = mid
    }
    return low
}

private fun DrawScope.drawFineSeries(
    series: FloatArray,
    spec: NightChartSpec,
    area: Rect,
    x: XTransform,
    color: androidx.compose.ui.graphics.Color,
    strokeWidth: Float,
    dash: FloatArray,
    yOf: (Float) -> Float,
    alpha: Float = 1f,
) {
    if (series.isEmpty()) return
    val effect = PathEffect.dashPathEffect(dash, 0f)
    var previous: Offset? = null
    val step = spec.fineSeriesStepMs
    for (i in series.indices) {
        val ms = spec.startMs + i * step
        val px = area.left + x.xOf(ms)
        if (px < area.left - 8f || px > area.right + 8f) {
            previous = null
            continue
        }
        val p = Offset(px, yOf(series[i]))
        previous?.let { drawLine(color, it, p, strokeWidth, alpha = alpha, pathEffect = effect) }
        previous = p
    }
}

/** Ticks `x1 x2 x4 x8 x16 x32`: the powers of two, written out in plain text. */
private fun DrawScope.drawLogYAxis(
    spec: NightChartSpec,
    t: ChartTokens,
    area: Rect,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    yOf: (Float) -> Float,
) {
    var ratio = 1f
    while (ratio <= spec.ratioMax) {
        val y = yOf(ratio)
        drawLine(t.structural, Offset(area.left, y), Offset(area.right, y), t.strokeThin, alpha = 0.25f)
        axisText(measurer, scratch, t, "×${ratio.roundToInt()}", dpPx(4f), y - dpPx(6f))
        ratio *= 2f
    }
}

/**
 * Local wall clock time. A night containing a clock change carries its mark on the axis.
 *
 * The ticks themselves live in `Drawing.kt`: the three stacked bands share the same axis, so they
 * must share the function that ticks it.
 */
private fun DrawScope.drawHourXAxis(
    spec: NightChartSpec,
    t: ChartTokens,
    area: Rect,
    m: Margins,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    xOf: (Long) -> Float,
) = drawHourTicks(
    startMs = spec.startMs,
    endMs = spec.endMs,
    area = area,
    textY = size.height - m.bottom + dpPx(4f),
    t = t,
    measurer = measurer,
    scratch = scratch,
    xOf = xOf,
)
