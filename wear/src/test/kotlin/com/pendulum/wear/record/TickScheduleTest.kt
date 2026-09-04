package com.pendulum.wear.record

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The service's periodic jobs are owed to the night, not to the processor's awake time.
 *
 * The defect these tests pin: the periods were counted in `Handler` ticks on the uptime clock,
 * which stops while the SoC is suspended. In `WAKEUP 30 s` mode the processor wakes for a few
 * hundred milliseconds every thirty seconds, so the "minute" that evaluates the six stop
 * conditions, writes the telemetry point and samples the battery came round every half hour or so.
 */
class TickScheduleTest {

    private companion object {
        const val TICK = 10_000L
        const val T0 = 5_000_000L
    }

    @Test
    @DisplayName("a night spent suspended still gets its minute job once per minute of elapsed time")
    fun `the minute is a minute of night`() {
        // WAKEUP 30 s: the schedule is only consulted when the SoC is awake, at each FIFO burst —
        // every 30 s of elapsed time, for a few hundred milliseconds of uptime. Twenty bursts are
        // ten minutes of night. The old tick-counting saw twenty times ~300 ms = 6 s of uptime in
        // that stretch and never reached its 60 s minute.
        val s = TickSchedule(TICK, T0)
        var minutes = 0
        var now = T0
        repeat(20) {
            now += 30_000L
            val due = s.due(now)
            if (due.minuteCoveredMs != null) minutes++
        }
        assertThat(minutes).isEqualTo(10)
    }

    @Test
    @DisplayName("a late minute job reports the time it really covers")
    fun `the minute carries its real duration`() {
        // The bursts came every 30 s but the schedule was consulted only at the fourth: the job
        // covers 120 s, and the off-body counter must add 120 s, not 60.
        val s = TickSchedule(TICK, T0)
        val due = s.due(T0 + 120_000L)
        assertThat(due.minuteCoveredMs).isEqualTo(120_000L)
        // Consulted again a second later: nothing is due, the previous call consumed it.
        assertThat(s.due(T0 + 121_000L).any).isFalse()
    }

    @Test
    @DisplayName("the three periods keep their tick multiples: 1, 3 and 6")
    fun `sync, state and minute at their multiples`() {
        val s = TickSchedule(TICK, T0)
        assertThat(s.due(T0 + TICK - 1).any).isFalse()

        val first = s.due(T0 + TICK)
        assertThat(first.sync).isTrue()
        assertThat(first.ui).isFalse()
        assertThat(first.minuteCoveredMs).isNull()

        s.due(T0 + 2 * TICK)
        val third = s.due(T0 + 3 * TICK)
        assertThat(third.sync).isTrue()
        assertThat(third.ui).isTrue()
        assertThat(third.minuteCoveredMs).isNull()

        s.due(T0 + 4 * TICK)
        s.due(T0 + 5 * TICK)
        val sixth = s.due(T0 + 6 * TICK)
        assertThat(sixth.sync).isTrue()
        assertThat(sixth.ui).isTrue()
        assertThat(sixth.minuteCoveredMs).isEqualTo(6 * TICK)
    }

    @Test
    @DisplayName("the first minute is counted from the start of the session, not from the first burst")
    fun `periods are counted from the start`() {
        // The first burst lands 30 s after START; a minute has not passed. At the second, it has.
        val s = TickSchedule(TICK, T0)
        assertThat(s.due(T0 + 30_000L).minuteCoveredMs).isNull()
        assertThat(s.due(T0 + 60_000L).minuteCoveredMs).isEqualTo(60_000L)
    }
}
