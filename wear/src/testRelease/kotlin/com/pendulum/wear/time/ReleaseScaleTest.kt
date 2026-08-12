package com.pendulum.wear.time

import com.pendulum.format.time.TimeScale
import com.pendulum.format.wire.WireProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The proof that the release does not know how to compress time.
 *
 * This test lives in `src/testRelease/`: it is compiled and run only by
 * `:wear:testReleaseUnitTest`, against the `src/release/` twin of `TimeScaling`. Putting it in
 * `src/test/` — shared by both variants — would have made it tautological in one and false in the
 * other.
 *
 * **What it cannot prove, and what is proved elsewhere.** That no path *can* change the divisor in
 * release is not demonstrated by an assertion: the compiler is what guarantees it, because
 * `BuildConfig.TEMPS_DIVISEUR` is declared only in the `debug` block of `build.gradle.kts` and the
 * release twin has no other input. If someone added such an input, this test would stay green —
 * what would catch it is the review of the twenty-line file where the addition would be visible.
 * This test checks the value; the source set checks the impossibility.
 */
class ReleaseScaleTest {

    @Test
    fun `the divisor is one in release`() {
        assertThat(TimeScaling.DIVISOR).isEqualTo(1L)
        assertThat(TimeScaling.DIVISOR).isEqualTo(TimeScale.REAL_TIME_DIVISOR)
    }

    @Test
    fun `the published durations are the nominal durations`() {
        val d = Durations.ACTIVE
        assertThat(d.chunkRotationMs).isEqualTo(WireProtocol.CHUNK_ROTATION_MS)
        assertThat(d.chunkRotationMs).isEqualTo(300_000L)
        assertThat(d.serviceTickMs).isEqualTo(10_000L)
        assertThat(d.sessionMaxAgeMs).isEqualTo(14L * 3_600_000L)
        assertThat(d.watchdogPeriodMs).isEqualTo(15L * 60_000L)
        assertThat(d.restartAfterTimeoutMs).isEqualTo(30_000L)
        assertThat(d.chargingDebounceMs).isEqualTo(60_000L)
        assertThat(d.sessionMaxDurationMs).isEqualTo(10L * 3_600_000L)
        assertThat(d.minDelayBeforeCutoffMs).isEqualTo(3_600_000L)
    }

    @Test
    fun `the byte ceiling is the same in both variants`() {
        // It has never depended on the scale, and this is written here so that the question gets
        // asked every time this file is read again.
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES).isEqualTo(92_160L)
    }
}
