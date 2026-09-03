package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import com.pendulum.phone.ui.theme.ChartTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The trend chart.
 *
 * Few points — sixty nights at most — hence no performance problem at all: all the work is in the
 * correctness. Three properties are at stake here, and each has already been wrongly "fixed" in a
 * comparable product:
 *
 * - **the Y axis starts at zero, hard-coded** (see the KDoc of [TrendChartSpec.yMin]);
 * - **the points are not joined up** — this is a design decision, explained below;
 * - **the excluded nights are drawn**, at their value, as a hollow circle, and left out of every
 *   computation. Hiding them would give a cleaner picture and a false reading: one would no longer
 *   see that one week in three was thrown away.
 */
fun DrawScope.drawTrendChart(
    spec: TrendChartSpec,
    t: ChartTokens,
    measurer: TextMeasurer,
    scratch: ChartScratch,
) {
    val m = defaultMargins
    val area = plotArea(m)
    val yAxis = LinearAxis(spec.yMin, spec.yMax, area.top, area.height)
    val spanMs = (spec.lastDayMs - spec.firstDayMs).coerceAtLeast(1L)

    fun xOf(ms: Long): Float =
        area.left + (ms - spec.firstDayMs).toFloat() / spanMs * area.width

    // --- Y ticks
    var v = spec.yMin
    while (v <= spec.yMax) {
        val y = yAxis.y(v)
        drawLine(
            t.structural, Offset(area.left, y), Offset(area.right, y), t.strokeThin,
            alpha = ChartTokens.TICK_ALPHA,
        )
        axisText(measurer, scratch, t, v.roundToInt().toString(), dpPx(4f), y - dpPx(6f))
        v += spec.tickStep
    }

    // --- reference line (clinical threshold). It only exists for the hourly count: no published
    //     threshold applies to the rhythm measured at the ankle, and drawing one would be
    //     manufacturing a boundary.
    spec.reference?.let { ref ->
        val y = yAxis.y(ref.value)
        dashedLine(y, area.left, area.right, t.structural, t.strokeNormal, t.dashReference)
        axisText(measurer, scratch, t, ref.label, area.right - dpPx(28f), y - dpPx(14f))
    }

    // --- 95 % CI bands of the median, then the median itself
    for (b in spec.bands) {
        val x0 = xOf(b.startMs)
        val x1 = xOf(b.endMs)
        val yTop = yAxis.y(b.ciHigh)
        val yBottom = yAxis.y(b.ciLow)
        // The fill is an emphasis, **the bounds are the information**. It is therefore on the two
        // dashed lines that the 3:1 requirement of WCAG 1.4.11 bears, and they are drawn with a
        // normal and not a thin stroke: measured in `ChartContrastTest`. Bringing the fill up to the
        // same contrast would require an opacity of about 0.50, which would drown the night points
        // drawn on top — one compliant element traded for one illegible element.
        drawRect(t.primaryData, Offset(x0, yTop), Size(x1 - x0, yBottom - yTop), alpha = t.ciBandAlpha)
        dashedLine(
            yTop, x0, x1, t.primaryData, t.strokeNormal, t.dashThreshold,
            alpha = ChartTokens.CI_BOUNDS_ALPHA,
        )
        dashedLine(
            yBottom, x0, x1, t.primaryData, t.strokeNormal, t.dashThreshold,
            alpha = ChartTokens.CI_BOUNDS_ALPHA,
        )

        val yMedian = yAxis.y(b.median)
        drawLine(t.primaryData, Offset(x0, yMedian), Offset(x1, yMedian), t.strokeBold)
        b.label?.let { axisText(measurer, scratch, t, it, x1 - dpPx(26f), yMedian - dpPx(14f), color = t.annotationText) }
    }

    // --- comparison separator: a vertical line at the pivot date, never an arrow.
    //     A downward arrow reads as "this is going the right way"; this chart does not say that.
    spec.pivotMs?.let { pivot ->
        val px = xOf(pivot)
        drawLine(t.annotationText, Offset(px, area.top), Offset(px, area.bottom), t.strokeThin)
    }

    // --- one point per night
    //
    // NO POLYLINE BETWEEN THE POINTS. This is not an oversight: a broken line between two nights
    // draws a continuous trajectory between two measurements that have nothing continuous about
    // them, and suggests a causality that does not exist. If a monotonic evolution really is there,
    // the position of the points is enough to show it. Please do not "repair" this.
    val radius = dpPx(2.5f)
    for (p in spec.points) {
        val cx = xOf(p.dateMs)
        val cy = yAxis.y(p.value.coerceIn(spec.yMin, spec.yMax))
        nightPoint(Offset(cx, cy), p.state, t, radius)
    }

    // --- calendar X axis: the nights are at their real date, a gap stays a gap
    drawCalendarXAxis(spec, t, area, m, measurer, scratch, ::xOf)
}

private fun DrawScope.drawCalendarXAxis(
    spec: TrendChartSpec,
    t: ChartTokens,
    area: Rect,
    m: Margins,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    xOf: (Long) -> Float,
) {
    for (g in calendarTicks(spec.firstDayMs, spec.lastDayMs, spec.zoneId)) {
        val px = xOf(g.ms)
        drawLine(t.structural, Offset(px, area.bottom), Offset(px, area.bottom + dpPx(3f)), t.strokeThin)
        axisText(measurer, scratch, t, g.label, px, size.height - m.bottom + dpPx(4f), centred = true)
    }
}

/** A tick of the calendar axis: where it is laid down, and what it says. */
internal data class DayTick(val ms: Long, val label: String)

/**
 * The X axis ticks, computed in the time zone where the nights were lived.
 *
 * ### The defect this function corrects
 *
 * The previous version advanced in steps of exactly 86 400 000 ms from `firstDayMs` — the **start**
 * instant of the first night, so 23:14 and not midnight — and converted the epoch into a date by
 * integer division, that is, in UTC. Two errors, each of them sufficient on its own:
 *
 *  - a night begun at 23:14 in New York falls on the following day in UTC, and its tick was
 *    labelled with the next day;
 *  - a civil day does not last 86 400 000 ms twice a year. From the clock change onwards, the local
 *    time of the ticks drifts by an hour, ends up crossing midnight, and the axis skips a day or
 *    repeats one.
 *
 * The step is therefore a **civil** step (`plusDays` on a `ZonedDateTime`, which stays at the same
 * local time across a clock change), and the label is the local date of that instant.
 *
 * The ticks stay anchored on the instant of the first night and not on midnight: over three nights
 * gone to bed at 23:00, ticks at midnight would all fall between the points, and the first night
 * would have no label at all.
 */
internal fun calendarTicks(
    firstDayMs: Long,
    lastDayMs: Long,
    zoneId: String,
): List<DayTick> {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val from = Instant.ofEpochMilli(firstDayMs).atZone(zone)
    val to = Instant.ofEpochMilli(lastDayMs).atZone(zone)
    val days = ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()).toInt() + 1
    // Beyond two weeks, one tick per day becomes a mush of strokes: we move to the week. This is the
    // only density setting of this axis.
    val step = if (days <= 14) 1 else 7

    val out = ArrayList<DayTick>()
    var i = 0
    while (true) {
        val instant = from.plusDays(i.toLong())
        val ms = instant.toInstant().toEpochMilli()
        if (ms > lastDayMs) break
        if (i % step == 0) out += DayTick(ms, instant.format(DAY_FORMAT))
        i++
    }
    return out
}

/**
 * `DD/MM`. The day first: it is the day that changes between two ticks.
 *
 * Shared with the values table of the trend screen (`formatShortDay`), which is declared to be the
 * accessible alternative to this axis: the two must not be able to print two different dates for
 * one night, and they did — the table kept its own UTC arithmetic after the axis was corrected.
 */
internal val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.UK)

/**
 * Search for the nearest point, acceptance radius 24 dp.
 * Returns the session identifier, or `null` if the finger landed on nothing.
 *
 * ### Why the density is a parameter
 *
 * Because the plot area is not the canvas. The plot occupies `plotArea(defaultMargins)` — 40 dp of
 * left margin for the Y axis labels, 16 dp on the right, 12 and 22 at the top and bottom — and this
 * function computed its coordinates over the **total** width and height. The difference reached
 * 40 dp in X for an acceptance radius of 24: tapping a point could miss it, or select its
 * neighbour. On a chart where each point opens the detail of a night, that means opening the wrong
 * night — and nothing on screen says it is not the one that was aimed at.
 *
 * The computation is therefore the same as the drawing's, margins included, and including the
 * clamping of the value to the bounds of the axis: an off-scale point is drawn on the bound, so
 * that is where it is touched.
 */
fun findNearestPoint(
    spec: TrendChartSpec,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    density: Float,
    acceptanceRadius: Float,
): String? {
    val area = plotArea(defaultMargins(density), width, height)
    if (area.width <= 0f || area.height <= 0f) return null
    val yAxis = LinearAxis(spec.yMin, spec.yMax, area.top, area.height)
    val spanMs = (spec.lastDayMs - spec.firstDayMs).coerceAtLeast(1L)
    var best: String? = null
    var bestDistance = Float.MAX_VALUE
    for (p in spec.points) {
        val px = area.left + (p.dateMs - spec.firstDayMs).toFloat() / spanMs * area.width
        val py = yAxis.y(p.value.coerceIn(spec.yMin, spec.yMax))
        val d = abs(px - x) + abs(py - y)
        if (d < bestDistance) {
            bestDistance = d
            best = p.sessionHex
        }
    }
    return best.takeIf { bestDistance <= acceptanceRadius }
}
