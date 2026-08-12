package com.pendulum.phone.work

import androidx.work.ListenableWorker
import com.pendulum.format.wire.WirePaths
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The outbox of the evening context: put the item down again as long as the evening lasts, give up
 * afterwards.
 *
 * The worker is the second half of the fix — `SealingOrderTest` covers the first, the one that
 * enqueues. Here we check what the queue does with that work: it puts the item down again, it asks
 * for another attempt as long as the put fails, and it **stops** once the targeted evening has
 * passed.
 *
 * ### Why the stopping guard rail is tested on the noon rollover
 *
 * `WirePaths.nightKey` attaches an evening to the local date, the rollover happening **at noon**: a
 * sealing at 10 pm and a start at 1.30 am share the same key. A naive guard rail written on today's
 * date would therefore stop the replay at midnight, that is, at the very moment the watch is still
 * waiting for the item. The two rollover tests below exist for that, and the time zone in them is
 * explicit: the rollover is the heart of the guard rail, it must not depend on the machine running
 * the test.
 */
class ContextRepublicationTest {

    private val zone = ZoneId.of("Europe/Paris")

    /** The evening of 2 March 2026, as a sealing done that evening at 10 pm would name it. */
    private val evening = WirePaths.nightKey(instant(day = 2, hour = 22), zone)

    private fun instant(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun outcome(nowMs: Long, put: () -> Boolean): ListenableWorker.Result =
        ContextPublicationWorker.outcomeOf(evening, nowMs, zone, put)

    @Test
    fun `an item put down again is a success`() {
        var puts = 0
        val result = outcome(instant(day = 2, hour = 22, minute = 30)) { puts++; true }

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(puts).`as`("puts of the item").isEqualTo(1)
    }

    @Test
    fun `a put still failing asks for another attempt`() {
        // `retry` and not `failure`: the exponential backoff of the queue is the whole point of
        // this outbox. `failure` would remove the work on the first failure, hence would reproduce
        // exactly the defect being repaired.
        val result = outcome(instant(day = 2, hour = 23)) { false }

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `past midnight the evening is still running and the replay carries on`() {
        // 1.30 am, so still the evening of the 2nd: the rollover is at noon. A guard rail written
        // on today's date would give up here — at the very hour the watch is waiting for the item.
        var puts = 0
        val result = outcome(instant(day = 3, hour = 1, minute = 30)) { puts++; false }

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(puts).`as`("puts of the item").isEqualTo(1)
    }

    @Test
    fun `past noon the evening is over, the work gives up without putting the item down`() {
        var puts = 0
        val result = outcome(instant(day = 3, hour = 12, minute = 1)) { puts++; true }

        // `success` and not `failure`: the work stops because its object has disappeared, not
        // because it failed. This is the same convention as `FetchSchedule.GiveUp`.
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(puts)
            .`as`("the Data Layer is not called on for an evening that has passed")
            .isZero()
    }

    @Test
    fun `the next evening does not inherit the previous one's replay`() {
        // The following day at 10 pm: the key has rolled over, the targeted item would no longer
        // unblock anything since the watch is asking for the one of the current evening. Without
        // this guard rail, the work would retry indefinitely, promising a catch-up that will not
        // happen.
        var puts = 0
        val result = outcome(instant(day = 3, hour = 22)) { puts++; false }

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(puts).isZero()
    }

    @Test
    fun `the replay targets the evening of the sealing and not the day of the sealing`() {
        // Guard rail of the guard rail: if `nightKey` stopped rolling over at noon, every test
        // above would stay green while measuring something else.
        assertThat(evening).isEqualTo("2026-03-02")
        assertThat(WirePaths.nightKey(instant(day = 3, hour = 1, minute = 30), zone))
            .isEqualTo("2026-03-02")
        assertThat(WirePaths.nightKey(instant(day = 3, hour = 12, minute = 1), zone))
            .isEqualTo("2026-03-03")
    }
}
