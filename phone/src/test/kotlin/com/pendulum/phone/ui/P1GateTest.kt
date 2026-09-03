package com.pendulum.phone.ui

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.TelemetryPointEntity
import com.pendulum.phone.ui.model.BatterySlope
import com.pendulum.phone.ui.model.Checks
import com.pendulum.phone.ui.model.P1Gate
import com.pendulum.phone.ui.model.P1Gate.Compliance
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The P1 gate on its bounds.
 *
 * ### Why these tests rather than a re-read
 *
 * P1 is the only milestone declared **blocking** for the project: until it is crossed, no line of
 * algorithm is supposed to be written, and if it cannot be crossed, the hardware changes. A false
 * verdict does not show — it looks like a verdict. The three places where it can swing silently are
 * exact equality at the threshold, the unknown treated as a success, and consecutiveness understood
 * as a plain count.
 */
class P1GateTest {

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val RATE_HZ = 50

        /** 12 March 2026, 23:14, Paris time — a plausible bed time, and not midnight. */
        val BED_TIME: Long = ZonedDateTime
            .of(2026, 3, 12, 23, 14, 0, 0, ZoneId.of("Europe/Paris"))
            .toInstant()
            .toEpochMilli()
    }

    /**
     * A night, described by what decides its verdict.
     *
     * @param coverage fraction of the expected samples. The sample count is derived from it rather
     *   than given: that is the path the real data takes, and writing a `sampleCount` directly
     *   would let a denominator error through.
     * @param analysed has the night been analysed? The default is **yes**, because that is the
     *   state in which a coverage verdict means something. The opposite case has its own test and
     *   has no business slipping silently into all the others.
     */
    private fun night(
        startMs: Long = BED_TIME,
        hours: Double? = 8.0,
        coverage: Double = 1.0,
        battery: Int? = 50,
        fs: Double? = RATE_HZ.toDouble(),
        zone: String = "Europe/Paris",
        analysed: Boolean = true,
    ): NightSessionEntity {
        val durationMs = hours?.let { (it * HOUR_MS).toLong() }
        val expected = (durationMs ?: 0L) * RATE_HZ / 1000.0
        return NightSessionEntity(
            sessionHex = "n%d".format(startMs % 1000),
            startWallMs = startMs,
            plannedStopWallMs = startMs + 8 * HOUR_MS,
            endWallMs = durationMs?.let { startMs + it },
            zoneId = zone,
            tzOffsetStartMin = 60,
            tzOffsetEndMin = 60,
            nominalRateHz = RATE_HZ,
            modeFlags = 0,
            state = if (durationMs == null) "OPEN" else "CLOSED",
            batteryPctLast = battery,
            analyzedAtMs = if (analysed) startMs + 9 * HOUR_MS else null,
            fsMeasuredHz = fs,
            sampleCount = Math.round(expected * coverage),
        )
    }

    /**
     * A telemetry series discharging linearly, with no noise.
     *
     * It is **exact by construction**: that is what makes it possible to check the straight line to
     * the decimal rather than to observe that it "looks about right". The real measurement noise
     * has nothing to teach a least-squares test — what matters here is that the extrapolation
     * horizon starts at the beginning of the night, that the charging points drop out, and that the
     * refusal fires where it is announced.
     *
     * @param count number of points.
     * @param pctPerHour consumption, in percentage points per hour.
     * @param stepS interval between two points, in seconds. One minute on the device; the parameter
     *   exists so that the two refusal conditions can be separated, since they coincide at the real
     *   rate and would no longer coincide at another.
     * @param capacityUah full capacity; the whole percentage follows from it, as on the device.
     * @param chargingAt indices of the points to mark as charging. Their counter **goes back up**,
     *   which is the trap: letting them in would invert the sign of the slope.
     */
    private fun telemetry(
        count: Int,
        pctPerHour: Double,
        startPct: Double = 100.0,
        stepS: Long = 60L,
        capacityUah: Double = 300_000.0,
        chargingAt: Set<Int> = emptySet(),
    ): List<TelemetryPointEntity> = (0 until count).map { i ->
        val h = i * stepS / 3_600.0
        val pct = startPct - pctPerHour * h
        val chargeUah = if (i in chargingAt) capacityUah else capacityUah * pct / 100.0
        TelemetryPointEntity(
            sessionHex = "t",
            // The monotonic clock, in nanoseconds: it is the only one on which a duration can be
            // computed, and it is the one the point carries.
            elapsedRealtimeNs = i * stepS * 1_000_000_000L,
            sensorTsNs = i * stepS * 1_000_000_000L,
            batteryChargeUah = Math.round(chargeUah).toInt(),
            maxIntervalUs = 25_000,
            fsyncTotalUs = 0,
            fsyncMaxUs = 0,
            temperatureDeciC = 320,
            measuredRateCentiHz = 5000,
            jitterStdUs = 800,
            clippedSamples = 0,
            fsyncCount = 0,
            batteryPct = Math.round(pct).toInt().coerceIn(0, 100),
            offBody = 0,
            charging = i in chargingAt,
        )
    }

    /**
     * Any comparable night: [Checks.of] needs one for its other rows, and none of them enters into
     * what this file checks.
     */
    private fun comparableNight() = ComparableNight(
        sessionHex = "abcd",
        startWallMs = BED_TIME,
        zoneId = "Europe/Paris",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = "HEALTH_CONNECT",
        gate = "FULL",
        independence = "INDEPENDENT",
        plmi = 18.4,
        plmiSpt = 9.0,
        fundamentalSec = 21.0,
        rhythmValid = true,
        periodicityIndex = 0.58,
        periodicityValid = true,
        missRate = 0.11,
        analysableTstMin = 312.0,
        analysableMin = 460.0,
        truncated = false,
        revealedAtMs = null,
        comparable = true,
        exclusionReason = ComparabilityRule.OK,
    )

    // -------------------------------------------------------------------------------------
    // Coverage
    // -------------------------------------------------------------------------------------

    @Test
    fun `coverage at the exact threshold passes, a tenth of a point below does not`() {
        assertThat(P1Gate.of(night(coverage = Checks.MIN_COVERAGE)).coverage.state)
            .isEqualTo(Compliance.COMPLIANT)
        assertThat(P1Gate.of(night(coverage = 0.989)).coverage.state)
            .isEqualTo(Compliance.NON_COMPLIANT)
    }

    /**
     * The defect of 3 August 2026, pinned down.
     *
     * `night_session.sampleCount` is 0 for as long as `AnalyzeWorker` has not run, and the old
     * version then divided 0 by 96,163: the night came out `NON_COMPLIANT` with `coverage=0.0%`. An
     * **unanalysed** night was therefore reported as "outside P1", which is false in both
     * directions — it did not fail, and it may well have held out perfectly.
     *
     * The second case is the one not to confuse with the first: an **analysed** night whose
     * analysis kept no sample really does have a coverage of zero, and that one fails.
     */
    @Test
    fun `an unanalysed night is not outside P1, it is not decidable`() {
        val notAnalysed = P1Gate.of(night(coverage = 0.0, analysed = false))
        assertThat(Checks.coverage(night(coverage = 0.0, analysed = false))).isNull()
        assertThat(notAnalysed.coverage.state).isEqualTo(Compliance.UNDETERMINED)
        assertThat(Resources.resolve(notAnalysed.coverage.value)).isEqualTo("—")
        assertThat(notAnalysed.verdict).isNotEqualTo(Compliance.NON_COMPLIANT)

        // Analysed, and zero sample kept: the coverage is null and the verdict falls.
        val analysedEmpty = P1Gate.of(night(coverage = 0.0, analysed = true))
        assertThat(analysedEmpty.coverage.state).isEqualTo(Compliance.NON_COMPLIANT)
        assertThat(Resources.resolve(analysedEmpty.coverage.value)).isEqualTo("0.0%")
    }

    /**
     * The check row of the night detail reads the same unknown. Two screens, a single computation:
     * that is the rule `Checks` already carries for the battery threshold.
     */
    @Test
    fun `the check row renders a dash on an unanalysed night`() {
        val row = Checks
            .of(night(coverage = 0.0, analysed = false), comparableNight(), null, text(R.string.settings_health_connect))
            .first { it.label == text(R.string.night_detail_coverage) }
        assertThat(Resources.resolve(row.value)).isEqualTo("—")
        // `null` and not `false`, otherwise the two screens no longer read the same unknown: the
        // neighbouring test requires `UNDETERMINED` and a verdict that is not `NON_COMPLIANT` for
        // this exact input. This row used to assert the opposite — value "we do not know", state
        // "not met" — and that is what turned a coverage never measured into a `✗`.
        assertThat(row.ok).isNull()
    }

    @Test
    fun `a night with no known end has no coverage, and a coverage so far is not one`() {
        // The trap: `sampleCount` is non-zero and the temptation is to divide by the elapsed
        // duration. An open session has no duration, hence no denominator, hence no verdict.
        val open = P1Gate.of(night(hours = null))
        assertThat(open.coverage.state).isEqualTo(Compliance.UNDETERMINED)
        assertThat(open.verdict).isEqualTo(Compliance.UNDETERMINED)
    }

    // -------------------------------------------------------------------------------------
    // Battery — and the three readings we are entitled to make of it
    // -------------------------------------------------------------------------------------

    @Test
    fun `at eight hours, the reported level is the level at eight hours`() {
        assertThat(P1Gate.of(night(hours = 8.0, battery = Checks.MIN_BATTERY_PCT + 1)).battery.state)
            .isEqualTo(Compliance.COMPLIANT)
        assertThat(P1Gate.of(night(hours = 8.0, battery = Checks.MIN_BATTERY_PCT - 1)).battery.state)
            .isEqualTo(Compliance.NON_COMPLIANT)
    }

    /**
     * The exact bound, and the only place where it was ambiguous.
     *
     * `01-overview.md` §5 writes "battery **above** 20 %" and the KDoc of [Checks.MIN_BATTERY_PCT]
     * said the same thing, while both comparators wrote `>=`. At exactly 20 % the documentation
     * said failure and the code success — a disagreement that fires on one night in fifty and
     * which, on that day, makes a campaign cross the project's blocking gate when it has not
     * crossed it.
     */
    @Test
    fun `exactly twenty percent fails, as the document says`() {
        assertThat(P1Gate.of(night(hours = 8.0, battery = Checks.MIN_BATTERY_PCT)).battery.state)
            .isEqualTo(Compliance.NON_COMPLIANT)
        // And the check row of the night detail decides the same way: one threshold, one
        // comparator, otherwise the same night carries two verdicts depending on the screen looked
        // at.
        assertThat(batteryRow(Checks.MIN_BATTERY_PCT)?.ok).isFalse()
        assertThat(batteryRow(Checks.MIN_BATTERY_PCT + 1)?.ok).isTrue()
    }

    private fun batteryRow(pct: Int) = Checks
        .of(night(battery = pct), comparableNight(), null, text(R.string.settings_health_connect))
        .firstOrNull { it.label == text(R.string.night_detail_battery_end) }

    @Test
    fun `a short night says nothing about eight hours, unless it is already below the threshold`() {
        // Six hours at 45 %: we do not know where the battery would be at eight hours, and the
        // starting level is stored nowhere. We do not guess.
        assertThat(P1Gate.of(night(hours = 6.0, battery = 45)).battery.state)
            .isEqualTo(Compliance.UNDETERMINED)
        // Six hours at 15 %: the battery will not come back up, so the night fails. Asserting that
        // supposes nothing — it is the only inference this data allows.
        assertThat(P1Gate.of(night(hours = 6.0, battery = 15)).battery.state)
            .isEqualTo(Compliance.NON_COMPLIANT)
    }

    @Test
    fun `with no reported level, the criterion is undetermined and not met`() {
        assertThat(P1Gate.of(night(battery = null)).battery.state).isEqualTo(Compliance.UNDETERMINED)
    }

    // -------------------------------------------------------------------------------------
    // Battery — the slope, which makes the criterion decidable on a short night
    // -------------------------------------------------------------------------------------

    /**
     * The case the percentage alone cannot decide, and it is the real case.
     *
     * One hour awake at 100 %: the percentage has not moved by a single step — which is exactly
     * what §12.4 measured, `level: 100` at all six steps — and the old reading returned
     * `UNDETERMINED`. The coulomb counter, for its part, does have a slope, and eight hours at that
     * rate end up below the threshold.
     */
    @Test
    fun `a short night is decided on the slope, where the percentage does not move`() {
        // 11 % per hour: at 8 h there would be 12 % left, hence the failure — on a night whose last
        // reported percentage is 100 and says nothing.
        val tooFast = P1Gate.of(night(hours = 1.0, battery = 100), telemetry(60, pctPerHour = 11.0))
        assertThat(tooFast.battery.state).isEqualTo(Compliance.NON_COMPLIANT)

        // 5 % per hour: 60 % at eight hours.
        val ample = P1Gate.of(night(hours = 1.0, battery = 100), telemetry(60, pctPerHour = 5.0))
        assertThat(ample.battery.state).isEqualTo(Compliance.COMPLIANT)
        assertThat(Resources.resolve(ample.battery.value))
            .contains("60%").contains("8 h").contains("60 points")
    }

    /**
     * The time origin is the **start of the night**, not the first usable point.
     *
     * A watch left on its dock for an hour before starting to discharge has only seven hours ahead
     * of it, not eight. Taking the first kept point as the origin would hand a free hour to every
     * night where the watch was plugged in, and the discrepancy would show up nowhere.
     */
    @Test
    fun `the charging points leave the slope without shifting the horizon`() {
        // Two hours: the first charging (counter at full), the second discharging at 10 %/h.
        val points = telemetry(120, pctPerHour = 10.0, chargingAt = (0 until 60).toSet())
        val p = BatterySlope.of(points, P1Gate.TARGET_DURATION_H)!!
        assertThat(p.pointsCharging).isEqualTo(60)
        assertThat(p.pointsKept).isEqualTo(60)
        assertThat(p.pctPerHour).isCloseTo(10.0, within(0.2))
        // The line passes through 100 % at t=0 — the start of the night — hence 20 % at eight
        // hours. Had the origin been the first kept point, we would read 30 %.
        assertThat(p.pctAt8h).isCloseTo(20.0, within(0.5))
    }

    @Test
    fun `a slope over three points is not a slope`() {
        assertThat(BatterySlope.of(telemetry(3, pctPerHour = 10.0), P1Gate.TARGET_DURATION_H)).isNull()
        // Just below the minimum count, and exactly at it. The bound is the one that swings
        // silently: above it a figure is published, below it a dash is returned.
        assertThat(BatterySlope.of(telemetry(BatterySlope.MIN_POINTS - 1, 10.0), P1Gate.TARGET_DURATION_H))
            .isNull()
        assertThat(BatterySlope.of(telemetry(BatterySlope.MIN_POINTS, 10.0), P1Gate.TARGET_DURATION_H))
            .isNotNull()
    }

    /**
     * Enough points, but not enough span: thirty surviving points scattered through a night spent
     * on its dock do not carry an extrapolation to eight hours. The count and the span are two
     * conditions and not one.
     */
    @Test
    fun `enough points but too little span concludes nothing`() {
        // Forty points ten seconds apart: the count passes easily, the span is six and a half
        // minutes. It is the span condition, and it alone, that refuses — which is exactly what it
        // protects the day the rate of the points changes.
        val tight = telemetry(40, pctPerHour = 10.0, stepS = 10L)
        assertThat(tight).hasSizeGreaterThan(BatterySlope.MIN_POINTS)
        assertThat(BatterySlope.of(tight, P1Gate.TARGET_DURATION_H)).isNull()
    }

    @Test
    fun `a counter that does not go down does not yield infinite battery life`() {
        // Zero consumption: the slope is flat. We refuse rather than announce 100 % at eight hours
        // — that is the costliest possible false green on the most discriminating criterion.
        assertThat(BatterySlope.of(telemetry(120, pctPerHour = 0.0), P1Gate.TARGET_DURATION_H)).isNull()
    }

    /**
     * With no telemetry, nothing changes: the three exact readings of the last percentage remain
     * the path, and a night recorded before telemetry existed keeps its verdict.
     */
    @Test
    fun `with no telemetry the criterion falls back on the three exact readings`() {
        assertThat(P1Gate.of(night(hours = 6.0, battery = 45), emptyList()).battery.state)
            .isEqualTo(Compliance.UNDETERMINED)
        assertThat(P1Gate.of(night(hours = 8.0, battery = 45), emptyList()).battery.state)
            .isEqualTo(Compliance.COMPLIANT)
    }

    // -------------------------------------------------------------------------------------
    // Rate
    // -------------------------------------------------------------------------------------

    @Test
    fun `the delivered rate is tolerated to five percent, bounds included`() {
        val limit = RATE_HZ * (1.0 + Checks.FS_TOLERANCE)
        assertThat(P1Gate.of(night(fs = limit)).frequency.state).isEqualTo(Compliance.COMPLIANT)
        assertThat(P1Gate.of(night(fs = limit + 0.1)).frequency.state).isEqualTo(Compliance.NON_COMPLIANT)
        assertThat(P1Gate.of(night(fs = null)).frequency.state).isEqualTo(Compliance.UNDETERMINED)
    }

    // -------------------------------------------------------------------------------------
    // The verdict of one night
    // -------------------------------------------------------------------------------------

    @Test
    fun `a failure wins over an unknown`() {
        // Insufficient coverage and unknown battery: the night has failed, it is not in suspense.
        // The opposite would raise hope for a night that is already lost.
        val v = P1Gate.of(night(coverage = 0.90, battery = null))
        assertThat(v.verdict).isEqualTo(Compliance.NON_COMPLIANT)
    }

    @Test
    fun `every criterion carries its value and its threshold, never one without the other`() {
        val v = P1Gate.of(night(coverage = 0.994))
        assertThat(v.criteria).hasSize(3)
        assertThat(v.criteria).allSatisfy {
            assertThat(Resources.resolve(it.value)).isNotBlank()
            assertThat(Resources.resolve(it.threshold)).isNotBlank()
        }
        assertThat(Resources.resolve(v.coverage.value)).isEqualTo("99.4%")
    }

    // -------------------------------------------------------------------------------------
    // The evening, and the switch at noon
    // -------------------------------------------------------------------------------------

    @Test
    fun `a bed time after midnight attaches to the previous evening`() {
        val halfPastOne = ZonedDateTime
            .of(2026, 3, 13, 1, 30, 0, 0, ZoneId.of("Europe/Paris"))
            .toInstant().toEpochMilli()
        assertThat(P1Gate.of(night(startMs = halfPastOne)).evening)
            .isEqualTo(LocalDate.of(2026, 3, 12))
    }

    // -------------------------------------------------------------------------------------
    // The campaign — "three nights" and "three consecutive nights" are not the same thing
    // -------------------------------------------------------------------------------------

    private fun campaignOf(vararg days: Pair<Int, Compliance>): P1Gate.Campaign {
        val verdicts = days.map { (day, expected) ->
            val start = ZonedDateTime
                .of(2026, 3, day, 23, 14, 0, 0, ZoneId.of("Europe/Paris"))
                .toInstant().toEpochMilli()
            // A non-compliant night is made so by its coverage; that is the most direct criterion.
            P1Gate.of(
                night(
                    startMs = start,
                    coverage = if (expected == Compliance.COMPLIANT) 1.0 else 0.5,
                )
            )
        }
        return P1Gate.campaign(verdicts)
    }

    @Test
    fun `three compliant nights in a row cross the gate`() {
        val c = campaignOf(
            10 to Compliance.COMPLIANT,
            11 to Compliance.COMPLIANT,
            12 to Compliance.COMPLIANT,
        )
        assertThat(c.longestStreak).isEqualTo(3)
        assertThat(c.crossed).isTrue()
        assertThat(c.streakStart).isEqualTo("10 March")
        assertThat(c.streakEnd).isEqualTo("12 March")
    }

    @Test
    fun `three compliant nights separated by skipped evenings cross nothing`() {
        // The heart of the criterion. Three successes scattered about are three strokes of luck;
        // the gate asks for reproducibility, that is, three nights in a row. An evening that was
        // not recorded breaks the streak just as a failure does — skipping the night that follows a
        // failure is precisely what would make the criterion satisfiable at will.
        val c = campaignOf(
            10 to Compliance.COMPLIANT,
            12 to Compliance.COMPLIANT,
            14 to Compliance.COMPLIANT,
        )
        assertThat(c.compliantNights).isEqualTo(3)
        assertThat(c.longestStreak).isEqualTo(1)
        assertThat(c.crossed).isFalse()
    }

    @Test
    fun `a night outside the criteria in the middle breaks the streak`() {
        val c = campaignOf(
            10 to Compliance.COMPLIANT,
            11 to Compliance.COMPLIANT,
            12 to Compliance.NON_COMPLIANT,
            13 to Compliance.COMPLIANT,
            14 to Compliance.COMPLIANT,
        )
        assertThat(c.compliantNights).isEqualTo(4)
        assertThat(c.longestStreak).isEqualTo(2)
        assertThat(c.crossed).isFalse()
    }

    @Test
    fun `the input order does not change the verdict`() {
        // The repository reads the most recent night first; the streak is read the other way round.
        // An implicit sort would be exactly the kind of dependency that breaks the day the caller
        // changes.
        val c = campaignOf(
            12 to Compliance.COMPLIANT,
            10 to Compliance.COMPLIANT,
            11 to Compliance.COMPLIANT,
        )
        assertThat(c.longestStreak).isEqualTo(3)
        assertThat(c.streakStart).isEqualTo("10 March")
    }

    @Test
    fun `two sessions on the same evening do not count as two nights`() {
        val first = ZonedDateTime
            .of(2026, 3, 12, 23, 14, 0, 0, ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        val second = ZonedDateTime
            .of(2026, 3, 13, 2, 40, 0, 0, ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        val c = P1Gate.campaign(
            listOf(P1Gate.of(night(startMs = first)), P1Gate.of(night(startMs = second)))
        )
        assertThat(c.compliantNights).isEqualTo(2)
        assertThat(c.longestStreak).isEqualTo(1)
    }

    @Test
    fun `a second session on an evening does not break the run`() {
        val zone = ZoneId.of("Europe/Paris")
        fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime
            .of(2026, 3, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        // The 11th carries two compliant recordings; it is still one evening. The loop used to
        // treat the second as a break: not "day + 1", so it fell to the branch that restarts the
        // run from that session, and three good evenings read "longest streak 2".
        val c = P1Gate.campaign(
            listOf(
                P1Gate.of(night(startMs = at(12, 23, 14), coverage = 1.0)),
                P1Gate.of(night(startMs = at(11, 23, 20), coverage = 1.0)),
                P1Gate.of(night(startMs = at(11, 23, 14), coverage = 1.0)),
                P1Gate.of(night(startMs = at(10, 23, 14), coverage = 1.0)),
            )
        )
        assertThat(c.longestStreak).isEqualTo(3)
        assertThat(c.crossed).isTrue()
        assertThat(c.streakStart).isEqualTo("10 March")
        assertThat(c.streakEnd).isEqualTo("12 March")
    }

    @Test
    fun `a false start does not erase the night that followed it`() {
        val zone = ZoneId.of("Europe/Paris")
        fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime
            .of(2026, 3, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        // Bedtime on the 12th: start, stop after a minute, start again. The aborted session is
        // never analysed, so its coverage is unknown and its verdict undetermined — and an
        // undetermined verdict used to empty the run. Four consecutive compliant evenings then
        // read "longest streak 2" and the blocking milestone "not crossed", because of a minute
        // nobody counts as a night — or "3" with the two sessions of the 12th the other way round,
        // since the sort is by evening alone and the input order decided what a duplicate did.
        val c = P1Gate.campaign(
            listOf(
                P1Gate.of(night(startMs = at(13, 23, 14), coverage = 1.0)),
                P1Gate.of(night(startMs = at(12, 23, 5), analysed = false)),
                P1Gate.of(night(startMs = at(12, 23, 14), coverage = 1.0)),
                P1Gate.of(night(startMs = at(11, 23, 14), coverage = 1.0)),
                P1Gate.of(night(startMs = at(10, 23, 14), coverage = 1.0)),
            )
        )
        // The sessions are still counted as examined; only the run is counted over evenings.
        assertThat(c.nightsExamined).isEqualTo(5)
        assertThat(c.compliantNights).isEqualTo(4)
        assertThat(c.longestStreak).isEqualTo(4)
        assertThat(c.crossed).isTrue()
    }

    @Test
    fun `an empty campaign does not cross the gate`() {
        val c = P1Gate.campaign(emptyList())
        assertThat(c.longestStreak).isZero()
        assertThat(c.crossed).isFalse()
        assertThat(c.streakStart).isNull()
    }
}
