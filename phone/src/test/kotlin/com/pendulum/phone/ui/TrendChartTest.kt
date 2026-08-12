package com.pendulum.phone.ui

import com.pendulum.phone.ui.chart.LinearAxis
import com.pendulum.phone.ui.chart.NightPoint
import com.pendulum.phone.ui.chart.PointState
import com.pendulum.phone.ui.chart.TrendChartSpec
import com.pendulum.phone.ui.chart.calendarTicks
import com.pendulum.phone.ui.chart.defaultMargins
import com.pendulum.phone.ui.chart.findNearestPoint
import com.pendulum.phone.ui.chart.plotArea
import com.pendulum.phone.ui.model.Aggregate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The trend chart, where the eye catches nothing up.
 *
 * Two defects are covered here, and both had the same signature: the chart **looked right**. A
 * point tapped beside its target reads as a slip of the finger; a tick shifted by one day is only
 * noticed if one already knows the date of the night being looked at.
 */
class TrendChartTest {

    private companion object {
        const val DAY = 86_400_000L
        /** Density 2: an mdpi screen x2, hence a 40 dp left margin = 80 px. */
        const val DENSITY = 2f
        const val WIDTH = 1000f
        const val HEIGHT = 400f
        /** The real gesture radius: 24 dp, converted the way `TrendChart` converts it. */
        const val RADIUS = 24f * DENSITY
    }

    private fun spec(points: Int, value: Float = 20f) = TrendChartSpec(
        quantity = Aggregate.Quantity.RHYTHM_SECONDS,
        points = List(points) { NightPoint("s$it", it * DAY, value, PointState.ELIGIBLE) },
        bands = emptyList(),
        reference = null,
        firstDayMs = 0L,
        lastDayMs = (points - 1) * DAY,
        zoneId = "Europe/Paris",
        pivotMs = null,
        accessibleDescription = "",
    )

    // -------------------------------------------------------------------------------------
    // Tapped-point detection reads the plot area, not the canvas
    // -------------------------------------------------------------------------------------

    @Test
    fun `the first point is tapped at the x where it is drawn, margin included`() {
        val s = spec(3)
        // The plot area starts 40 dp from the edge, that is 80 px here: the first point is drawn
        // there, and not at x = 0. The search was computing its x coordinates over the full canvas
        // width, hence aiming at 0 — 80 px off for an acceptance radius of 48 px.
        val area = plotArea(defaultMargins(DENSITY), WIDTH, HEIGHT)
        assertThat(area.left).isEqualTo(80f)

        val y = LinearAxis(s.yMin, s.yMax, area.top, area.height).y(20f)
        assertThat(findNearestPoint(s, area.left, y, WIDTH, HEIGHT, DENSITY, RADIUS))
            .isEqualTo("s0")
    }

    @Test
    fun `every point is tapped where it is drawn`() {
        val s = spec(10)
        val area = plotArea(defaultMargins(DENSITY), WIDTH, HEIGHT)
        val yAxis = LinearAxis(s.yMin, s.yMax, area.top, area.height)
        val span = (s.lastDayMs - s.firstDayMs).toFloat()

        for (p in s.points) {
            val x = area.left + (p.dateMs - s.firstDayMs) / span * area.width
            val y = yAxis.y(p.value)
            assertThat(findNearestPoint(s, x, y, WIDTH, HEIGHT, DENSITY, RADIUS))
                .describedAs("point %s at x=%.1f", p.sessionHex, x)
                .isEqualTo(p.sessionHex)
        }
    }

    @Test
    fun `tapping the first point does not select the neighbouring night`() {
        // The defect in its costliest form. Ten nights over ten days: the drawn spacing is
        // 98.7 px, the old computation placed the first point 80 px too far left, so the night
        // nearest the aimed-at x became the second one. Every point of this chart opens the detail
        // of a night: this is not a missed gesture, it is the wrong night opened, with nothing on
        // screen to say so.
        val s = spec(10)
        val area = plotArea(defaultMargins(DENSITY), WIDTH, HEIGHT)
        val yAxis = LinearAxis(s.yMin, s.yMax, area.top, area.height)
        val tapped = findNearestPoint(s, area.left, yAxis.y(20f), WIDTH, HEIGHT, DENSITY, RADIUS)
        assertThat(tapped).isEqualTo("s0")
        assertThat(tapped).isNotEqualTo("s1")
    }

    @Test
    fun `the y coordinate follows the plot area too, top and bottom margins included`() {
        val s = spec(3)
        val area = plotArea(defaultMargins(DENSITY), WIDTH, HEIGHT)
        val drawnY = LinearAxis(s.yMin, s.yMax, area.top, area.height).y(20f)
        // The y coordinate computed over the full height lands elsewhere. The gap is small — about
        // ten pixels here — but it adds to the one on x inside a Manhattan distance, and it grows
        // with the height of the chart.
        val oldY = HEIGHT * (1f - 20f / s.yMax)
        assertThat(drawnY).isNotEqualTo(oldY)
        assertThat(drawnY).isBetween(area.top, area.bottom)
        assertThat(findNearestPoint(s, area.left, drawnY, WIDTH, HEIGHT, DENSITY, RADIUS))
            .isEqualTo("s0")
    }

    @Test
    fun `a finger landing in empty space selects nothing`() {
        val s = spec(3)
        assertThat(findNearestPoint(s, WIDTH / 2f, 10f, WIDTH, HEIGHT, DENSITY, RADIUS))
            .isNull()
    }

    // -------------------------------------------------------------------------------------
    // The calendar axis dates the nights in the zone where they were lived
    // -------------------------------------------------------------------------------------

    private fun bedTime(zone: String, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, ZoneId.of(zone))
            .toInstant().toEpochMilli()

    @Test
    fun `a night begun at 23h14 is labelled on the day one went to bed`() {
        // The case that was failing. In New York, 23:14 on 12 March is 03:14 UTC on 13 March: the
        // old conversion — an epoch divided by 86 400 000 — dated the tick to the following day.
        // The night of the 12th showed up under the label of the 13th.
        val start = bedTime("America/New_York", 12, 23, 14)
        val g = calendarTicks(start, start, "America/New_York")

        assertThat(Instant.ofEpochMilli(start).atZone(ZoneId.of("UTC")).dayOfMonth).isEqualTo(13)
        assertThat(g).hasSize(1)
        assertThat(g.first().label).isEqualTo("12/03")
    }

    @Test
    fun `the step is a civil day and not 86 400 000 ms`() {
        // Four nights at 23:14, from 27 to 30 March 2026, straddling the switch to summer time on
        // the 29th. A fixed step of 86 400 000 ms shifts the ticks by one hour from the transition
        // onwards: the third falls on the 30th at 00:14 local, the axis skips the 29th and leaves
        // the domain before the fourth. Here, every tick stays at 23:14 local.
        val first = bedTime("Europe/Paris", 27, 23, 14)
        val last = bedTime("Europe/Paris", 30, 23, 14)
        val g = calendarTicks(first, last, "Europe/Paris")

        assertThat(g.map { it.label })
            .containsExactly("27/03", "28/03", "29/03", "30/03")
        assertThat(g).allSatisfy {
            val local = Instant.ofEpochMilli(it.ms).atZone(ZoneId.of("Europe/Paris"))
            assertThat(local.hour).isEqualTo(23)
            assertThat(local.minute).isEqualTo(14)
        }
        // The proof that the step is not constant: the day of the clock change is one hour shorter.
        val steps = g.zipWithNext { a, b -> b.ms - a.ms }
        assertThat(steps).containsExactly(DAY, DAY - 3_600_000L, DAY)
    }

    @Test
    fun `beyond two weeks the ticks switch to weekly`() {
        val first = bedTime("Europe/Paris", 1, 23, 14)
        val last = first + 20 * DAY
        val g = calendarTicks(first, last, "Europe/Paris")
        assertThat(g).hasSize(3)
        assertThat(g.map { it.label }).containsExactly("01/03", "08/03", "15/03")
    }

    @Test
    fun `no tick falls outside the plotted domain`() {
        val first = bedTime("Europe/Paris", 10, 23, 14)
        val last = bedTime("Europe/Paris", 13, 7, 30)
        val g = calendarTicks(first, last, "Europe/Paris")
        assertThat(g).isNotEmpty()
        assertThat(g).allSatisfy { assertThat(it.ms).isBetween(first, last) }
    }

    @Test
    fun `an unknown time zone does not bring the drawing down`() {
        val start = bedTime("Europe/Paris", 12, 23, 14)
        assertThat(calendarTicks(start, start, "Not/A/Zone")).hasSize(1)
    }

    @Test
    fun `a single night gives a single tick`() {
        val start = bedTime("Europe/Paris", 12, 23, 14)
        val g = calendarTicks(start, start, "Europe/Paris")
        assertThat(g).hasSize(1)
        assertThat(g.first().ms).isEqualTo(start)
        assertThat(g.first().label).isEqualTo("12/03")
    }

    @Test
    fun `the plot area leaves its margins on both sides`() {
        val area = plotArea(defaultMargins(DENSITY), WIDTH, HEIGHT)
        assertThat(area.left).isEqualTo(80f)
        assertThat(area.right).isEqualTo(WIDTH - 32f)
        assertThat(area.top).isEqualTo(24f)
        assertThat(area.bottom).isCloseTo(HEIGHT - 44f, within(0.01f))
    }
}
