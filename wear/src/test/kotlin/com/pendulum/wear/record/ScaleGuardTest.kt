package com.pendulum.wear.record

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The guard rail that forbids a bench build on the real sensor.
 *
 * `ScaleConsistencyTest` checks that the bench divisor equals the replay acceleration. It does not
 * answer the question that cost the measurement of §11.5.3: what happens when there is **no**
 * replay. Measured answer: the cut-off time, whose guard delay is compressed, cuts the recording
 * at 14.6 s, that is, before the FIFO burst latency — 30 s, hardware and not compressible — has
 * delivered its first byte. Zero chunks, zero messages, and figures one believes one is reading.
 *
 * The four combinations are written out one by one rather than parameterised: the one that counts
 * is the third, and naming it is worth more than deducing it from a table.
 */
class ScaleGuardTest {

    @Test
    fun `an ordinary build on the real sensor starts`() {
        assertThat(Preflight.scaleMismatch(divisor = 1L, syntheticSource = false)).isFalse()
    }

    @Test
    fun `an ordinary build with the replay starts`() {
        assertThat(Preflight.scaleMismatch(divisor = 1L, syntheticSource = true)).isFalse()
    }

    @Test
    fun `a bench build on the real sensor is refused`() {
        assertThat(Preflight.scaleMismatch(divisor = 250L, syntheticSource = false))
            .`as`(
                "the divisor compresses wall-clock time and nothing else: on the real sensor it " +
                    "does not compress the bench, it detunes it",
            )
            .isTrue()
    }

    @Test
    fun `a bench build with the replay starts`() {
        assertThat(Preflight.scaleMismatch(divisor = 250L, syntheticSource = true)).isFalse()
    }

    /**
     * The rule does not name 250: any compression without a replay is a mismatch, and a guard rail
     * that knew only one value would let the next one through.
     */
    @Test
    fun `the refusal does not depend on the value of the divisor`() {
        for (d in listOf(2L, 60L, 250L, 600L, 3_600L)) {
            assertThat(Preflight.scaleMismatch(d, syntheticSource = false))
                .`as`("divisor $d")
                .isTrue()
        }
    }
}
