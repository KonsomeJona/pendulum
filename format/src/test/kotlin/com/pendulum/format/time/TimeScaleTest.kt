package com.pendulum.format.time

import com.pendulum.format.wire.WireProtocol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The scaling law, on its own. What it does and above all what it refuses to do. */
class TimeScaleTest {

    @Test
    fun `the real-time divisor is the identity`() {
        val durations = longArrayOf(1L, 10_000L, 300_000L, 36L * 3_600_000L)
        for (d in durations) {
            assertThat(TimeScale.ms(d, TimeScale.REAL_TIME_DIVISOR)).isEqualTo(d)
        }
        assertThat(TimeScale.REAL_TIME_DIVISOR).isEqualTo(1L)
    }

    @Test
    fun `the bench scale compresses exactly`() {
        val d = 250L
        assertThat(TimeScale.ms(300_000L, d)).isEqualTo(1_200L)          // chunk rotation
        assertThat(TimeScale.ms(8L * 3_600_000L, d)).isEqualTo(115_200L) // one night
        assertThat(TimeScale.ms(36L * 3_600_000L, d)).isEqualTo(518_400L) // Health Connect give-up
    }

    @Test
    fun `a non-zero duration never becomes zero`() {
        // A zero delay would not be "faster", it would be a different behaviour:
        // "every five minutes" would become "every block".
        assertThat(TimeScale.ms(10L, 1_000_000L)).isEqualTo(1L)
        assertThat(TimeScale.ms(1L, Long.MAX_VALUE)).isEqualTo(1L)
    }

    @Test
    fun `zero and negative values pass through unchanged`() {
        // "right now" and "already past" have no scale.
        assertThat(TimeScale.ms(0L, 600L)).isEqualTo(0L)
        assertThat(TimeScale.ms(-5L, 600L)).isEqualTo(-5L)
    }

    @Test
    fun `a divisor below one is refused`() {
        // Zero is a division by zero, a negative value would reverse the order of time, and a
        // fractional value does not exist here: the divisor is an integer so that the relative
        // order of durations survives rounding.
        for (bad in longArrayOf(0L, -1L, -600L)) {
            assertThatThrownBy { TimeScale.ms(1_000L, bad) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `the product durations stay strictly ordered at every scale`() {
        // This is the whole point of the integer divisor: the bench tests sequences (the burst
        // leaves before the expiry, the T+1 h rank falls before the T+2 h rank), and two durations
        // that came out equal after rounding would not break a value but a scenario.
        //
        // The property is **not** general — 1 000 ms and 1 001 ms collapse together as soon as the
        // divisor is 600. It holds for the durations the product actually carries, which are
        // spaced by a factor of at least two, and those are the ones we need.
        val nominal = longArrayOf(10_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)
        for (d in longArrayOf(1L, 2L, 250L, 600L, 3_000L)) {
            val compressed = TimeScale.ms(nominal, d)
            for (i in 1 until compressed.size) {
                assertThat(compressed[i])
                    .`as`("divisor %d: %d ms must stay above %d ms", d, nominal[i], nominal[i - 1])
                    .isGreaterThan(compressed[i - 1])
            }
        }
    }

    @Test
    fun `the rotation byte ceiling is not a duration`() {
        // Boundary assertion. `CHUNK_ROTATION_BYTES` is the hard guard rail of both rotation
        // conditions: it ensures that the memory buffers do not overflow and that the payload
        // stays under the 100 KB of a `DataItem`. The day someone runs it through `TimeScale`,
        // the bench stops checking those two things while still looking green.
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES).isEqualTo(92_160L)
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES)
            .isLessThan(WireProtocol.MAX_DATA_ITEM_BYTES.toLong())
    }
}
