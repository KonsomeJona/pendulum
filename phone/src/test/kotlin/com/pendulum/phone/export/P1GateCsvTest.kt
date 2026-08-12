package com.pendulum.phone.export

import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.ui.model.BatterySlope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The file that will carry the hardware decision.
 *
 * This test does not check a layout: it checks that the file stays **readable back** without the
 * application that produced it, and that it does not corrupt itself according to the phone's
 * language.
 */
class P1GateCsvTest {

    private fun night(coverage: Double, battery: Int, hours: Double): NightSessionEntity {
        val start = ZonedDateTime
            .of(2026, 3, 12, 23, 14, 0, 0, ZoneId.of("Europe/Paris"))
            .toInstant().toEpochMilli()
        val durationMs = (hours * 3_600_000L).toLong()
        return NightSessionEntity(
            sessionHex = "a1b2",
            startWallMs = start,
            plannedStopWallMs = start + 8 * 3_600_000L,
            endWallMs = start + durationMs,
            zoneId = "Europe/Paris",
            tzOffsetStartMin = 60,
            tzOffsetEndMin = 60,
            nominalRateHz = 50,
            modeFlags = 0,
            state = "CLOSED",
            batteryPctLast = battery,
            // Without this date the coverage is `null` — an unanalysed night has no numerator —
            // and the `coverage` column comes out empty. That is the behaviour intended since the
            // defect of 3 August 2026; what this file checks is the layout of a night that has, for
            // its part, indeed been analysed.
            analyzedAtMs = start + durationMs + 600_000L,
            fsMeasuredHz = 50.31,
            sampleCount = Math.round(durationMs * 50 / 1000.0 * coverage),
        )
    }

    @Test
    fun `the numbers come out with a decimal point, whatever the phone's language`() {
        // The exact trap: on a phone in French, `"%.5f".format(v)` returns "0,99400" — that is, a
        // field containing the column separator. The file stays syntactically valid and shifts
        // every column by one: the corruption that reads back without an error.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val csv = P1GateExporter.csv(listOf(night(coverage = 0.994, battery = 34, hours = 8.2)))
            val row = csv.lines().last { it.isNotBlank() }
            assertThat(row).contains("0.99400")
            assertThat(row).doesNotContain("0,99")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the file carries its thresholds and its conclusion ahead of its rows`() {
        val csv = P1GateExporter.csv(listOf(night(coverage = 0.994, battery = 34, hours = 8.2)))
        val lines = csv.lines()
        assertThat(lines.first()).startsWith("#")
        assertThat(csv).contains("# coverage_min=0.99")
        assertThat(csv).contains("# nights_required_consecutive=3")
        // A single night cannot cross a gate that asks for three in a row.
        assertThat(csv).contains("# longest_consecutive_run=1")
        assertThat(csv).contains("# gate_passed=false")
        // The measurement gap that remains is in the file, not only on the screen.
        assertThat(csv).contains("# not transmitted: largest single gap")
        // And the way the battery figure is obtained, with the refusal threshold: an extrapolated
        // percentage that did not say it was one would read back as a measured percentage.
        assertThat(csv).contains("least-squares fit on the coulomb counter")
        // The refusal threshold is read from the constant and not copied out: a file that
        // announced a threshold different from the one the code applies would be worse than a file
        // that said nothing.
        assertThat(csv).contains("refused below ${BatterySlope.MIN_POINTS} points")

        val header = lines.first { !it.startsWith("#") }
        assertThat(header.split(",")).startsWith("night_key", "session_hex")
        assertThat(header.split(",")).endsWith("verdict")

        val row = lines.last { it.isNotBlank() }
        assertThat(row.split(",")).hasSameSizeAs(header.split(","))
        assertThat(row).endsWith(",COMPLIANT")
    }

    @Test
    fun `a night that is too short comes out with an undetermined battery criterion, not a compliant one`() {
        val csv = P1GateExporter.csv(listOf(night(coverage = 0.994, battery = 41, hours = 6.0)))
        val row = csv.lines().last { it.isNotBlank() }
        assertThat(row).contains("UNDETERMINED")
        assertThat(row).endsWith(",UNDETERMINED")
    }
}
