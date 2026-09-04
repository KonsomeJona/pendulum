package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The shared burst-age bound stays tied to the latency it is derived from.
 *
 * `ChunkFormat.MAX_BURST_AGE_NS` lives in `:format` because the watch and the phone both need it
 * and neither module sees the other: the watch subtracts the age of the first sample when it writes
 * a header, the phone subtracts whatever age an older header still carries. But the figure is not
 * arbitrary — it is twice the report latency the strategy may ask of the sensor, which is the
 * longest a sample can plausibly have waited in the FIFO.
 *
 * Nothing in the type system holds those two numbers together. Raising `MAX_LATENCY_US` without
 * raising the bound would leave the watch refusing to correct precisely the bursts that waited
 * longest — the ones whose correction matters most — and the symptom would be a night placed
 * against its hypnogram one AASM epoch off, on some devices only.
 */
class BurstAgeBoundTest {

    @Test
    fun `the burst age ceiling is twice the longest report latency the strategy may ask for`() {
        val expectedNs = 2L * SensorStrategy.MAX_LATENCY_US * 1_000L

        assertThat(ChunkFormat.MAX_BURST_AGE_NS).isEqualTo(expectedNs)
    }
}
