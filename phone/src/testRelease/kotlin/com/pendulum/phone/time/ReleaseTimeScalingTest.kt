package com.pendulum.phone.time

import com.pendulum.format.time.TimeScale
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The proof that the release does not know how to compress time, on the phone side.
 *
 * Twin of `wear/src/testRelease/.../ReleaseScaleTest.kt`: compiled and run by
 * `:phone:testReleaseUnitTest` only, against the `src/release/` twin of `TimeScaling`. See the
 * KDoc of the other one for the part this test does not prove and that the source set proves in
 * its place.
 */
class ReleaseTimeScalingTest {

    @Test
    fun `the divisor is one in release`() {
        assertThat(TimeScaling.DIVISOR).isEqualTo(1L)
        assertThat(TimeScaling.DIVISOR).isEqualTo(TimeScale.REAL_TIME_DIVISOR)
    }

    @Test
    fun `the Health Connect retry ladder is the product's`() {
        val d = Durations.ACTIVE
        assertThat(d.readOffsetsMs).containsExactly(
            TimeUnit.MINUTES.toMillis(30),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(2),
            TimeUnit.HOURS.toMillis(4),
            TimeUnit.HOURS.toMillis(8),
            TimeUnit.HOURS.toMillis(16),
            TimeUnit.HOURS.toMillis(32),
        )
        assertThat(d.readGiveUpMs).isEqualTo(TimeUnit.HOURS.toMillis(36))
        assertThat(d.minBetweenOpportunisticMs).isEqualTo(TimeUnit.MINUTES.toMillis(10))
        assertThat(d.watchdogPeriodMs).isEqualTo(TimeUnit.MINUTES.toMillis(30))
        assertThat(d.silenceBeforeStaleMs).isEqualTo(TimeUnit.MINUTES.toMillis(45))
        assertThat(d.maxNightAgeMs).isEqualTo(TimeUnit.HOURS.toMillis(14))
    }

    @Test
    fun `giving up stays beyond the last rung`() {
        // Without this the ladder would have a rung that never fires, and the inversion would be
        // invisible: the night would simply give up a little earlier, without saying anything.
        assertThat(Durations.ACTIVE.readOffsetsMs.last())
            .isLessThan(Durations.ACTIVE.readGiveUpMs)
    }
}
