package com.pendulum.phone.work

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The retry ladder of the Health Connect read.
 *
 * It exists because the sleep session **does not appear at waking**: the watch -> phone transfer is
 * governed by the watch's battery policy, with no guaranteed delay, and a single read would fail
 * most of the time — silently, since Health Connect returns an empty list and not an error.
 */
class FetchScheduleTest {

    private val end = 1_700_000_000_000L
    private fun h(n: Long) = TimeUnit.HOURS.toMillis(n)
    private fun min(n: Long) = TimeUnit.MINUTES.toMillis(n)

    /**
     * The **nominal** ladder, passed explicitly, never read from `FetchSchedule.OFFSETS_MS`.
     *
     * This file asserts values — "the first attempt is at T+30 minutes", "we give up at T+36 h".
     * Letting them follow the divisor of the compiled variant would make those assertions fall
     * because a bench was built with compressed time, that is, for a reason that teaches nothing
     * about rescheduling. Compression is checked in `DurationsTest`; here we check the rule.
     */
    private val offsets = longArrayOf(min(30), h(1), h(2), h(4), h(8), h(16), h(32))
    private val giveUp = h(36)
    private val minBetween = min(10)

    private fun plan(attemptsDone: Int, sessionEndMs: Long = end, nowMs: Long) =
        FetchSchedule.plan(attemptsDone, sessionEndMs, nowMs, offsets, giveUp)

    private fun opportunistic(nowMs: Long, lastAttemptMs: Long?) =
        FetchSchedule.opportunisticAllowed(end, nowMs, lastAttemptMs, giveUp, minBetween)

    @Test
    fun `the first attempt is at T+30 minutes`() {
        val plan = plan(attemptsDone = 0, nowMs = end)
        assertThat(plan).isInstanceOf(FetchSchedule.Plan.Retry::class.java)
        assertThat((plan as FetchSchedule.Plan.Retry).delayMs).isEqualTo(min(30))
    }

    @Test
    fun `the ladder doubles at every rung`() {
        val expected = listOf(min(30), h(1), h(2), h(4), h(8), h(16), h(32))
        expected.forEachIndexed { rung, value ->
            val plan = plan(rung, nowMs = end) as FetchSchedule.Plan.Retry
            assertThat(plan.delayMs).describedAs("rung $rung").isEqualTo(value)
            assertThat(plan.attemptIndex).isEqualTo(rung)
        }
    }

    @Test
    fun `a rung already past triggers an immediate catch-up`() {
        // Phone switched off all morning: we do not skip the rungs, we catch them up one by one.
        // Each of them leaves an `hc_snapshot` row, and that trace is what will make it possible to
        // re-tune the ladder on the latency actually observed.
        val plan = plan(attemptsDone = 0, nowMs = end + h(5))
        assertThat((plan as FetchSchedule.Plan.Retry).delayMs).isZero()
    }

    @Test
    fun `we give up at T+36 hours`() {
        val plan = plan(attemptsDone = 2, nowMs = end + h(36))
        assertThat(plan).isInstanceOf(FetchSchedule.Plan.GiveUp::class.java)

        val justBefore = plan(2, nowMs = end + h(36) - 1)
        assertThat(justBefore).isInstanceOf(FetchSchedule.Plan.Retry::class.java)
    }

    @Test
    fun `we also give up when the ladder is exhausted`() {
        val plan = plan(attemptsDone = offsets.size, nowMs = end + h(1))
        assertThat(plan).isInstanceOf(FetchSchedule.Plan.GiveUp::class.java)
    }

    @Test
    fun `no rung goes past the 36-hour wall`() {
        assertThat(FetchSchedule.OFFSETS_MS.last()).isLessThan(FetchSchedule.GIVE_UP_MS)
    }

    @Test
    fun `the ladder is strictly increasing`() {
        // A non-increasing rung would produce two attempts at the same instant, hence two identical
        // `hc_snapshot` rows and a ladder consumed twice as fast.
        val o = FetchSchedule.OFFSETS_MS
        for (i in 1 until o.size) assertThat(o[i]).isGreaterThan(o[i - 1])
    }

    // --- Opportunistic triggers -------------------------------------------------------------

    @Test
    fun `an opportunistic read is allowed inside the 36-hour window`() {
        // Charger plugged in at T+2 h, no recent read: we read straight away rather than waiting
        // for the T+4 h rung. Health Connect synchronisation is correlated with usage — watch on
        // the charger, source application opened — not with a clock.
        assertThat(opportunistic(end + h(2), null)).isTrue()
    }

    @Test
    fun `an opportunistic read is refused outside the window`() {
        assertThat(opportunistic(end - min(1), null)).isFalse()
        assertThat(opportunistic(end + h(36), null)).isFalse()
    }

    @Test
    fun `a burst of plug-ins triggers only one read`() {
        // A cable making a bad contact emits the broadcast several times a minute. Every read
        // queries a provider; without this minimum delay, we would hammer it.
        val recent = end + h(2)
        assertThat(opportunistic(recent + min(1), recent)).isFalse()
        assertThat(opportunistic(recent + minBetween, recent)).isTrue()
    }

    @Test
    fun `the opportunistic index is negative, hence invisible to the attempt count`() {
        // This is what stops an opportunistic read from consuming the ladder: the DAO counts
        // `attemptIndex >= 0`. Three plug-ins of the cable would otherwise exhaust the seven rungs
        // in a minute, and the application would give up on the night before noon.
        assertThat(FetchSchedule.OPPORTUNISTIC_INDEX).isLessThan(0)
    }

    // --- Conditional rescore -----------------------------------------------------------------

    @Test
    fun `the first successful read triggers a rescore`() {
        assertThat(
            FetchSchedule.shouldRescore(null, null, 0, "rec-1", 100L, 42)
        ).isTrue()
    }

    @Test
    fun `an identical read triggers nothing`() {
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, "rec-1", 100L, 42)
        ).isFalse()
    }

    @Test
    fun `a session rewritten by the provider triggers a rescore`() {
        // Documented case: "inserts or *updates*". A night read at T+1 h can differ from the same
        // night at T+8 h; `lastModifiedTime` is what gives it away.
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, "rec-1", 200L, 42)
        ).isTrue()
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, "rec-1", 100L, 55)
        ).isTrue()
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, "rec-2", 100L, 42)
        ).isTrue()
    }

    @Test
    fun `an empty read does not erase a hypnogram already obtained`() {
        // If the provider returns nothing on the next attempt, we keep what we had: rescoring with
        // a vanished denominator would replace a valid figure with a circular one, with nothing
        // flagging it.
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, null, null, 0)
        ).isFalse()
    }
}
