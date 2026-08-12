package com.pendulum.wear.time

import com.pendulum.format.wire.WireProtocol
import com.pendulum.wear.record.SensorStrategy
import com.pendulum.wear.record.SyntheticSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The guard rail that forbids a scale inconsistent with the replay.
 *
 * Two accelerations live in this bench, and **they must be the same number**:
 *
 *  - the `SyntheticSource` replay advances N seconds of sensor time per second of wall-clock time.
 *    That factor is not free: it falls out of the burst size (`maxReportLatencyUs x rateHz`) and
 *    of the pause between bursts, itself constrained by `SensorPipeline.FLUSH_GAP_NS`;
 *  - `TimeScaling.DIVISOR` divides the wall-clock durations.
 *
 * If they differ, the race between the two chunk rotation conditions changes winner — and with it
 * what the bench actually tests. The demonstration in figures is in the KDoc of
 * `ChunkStore.writeBlock`; this test is its execution.
 *
 * The `DIVISOR == 1` case is let through: it is an ordinary debug build, which is not a bench and
 * has no replay running.
 */
class ScaleConsistencyTest {

    /**
     * The nominal mode of the product, the one the bench exercises: wake-up accelerometer, 3 000
     * guaranteed FIFO events, 50 Hz — that is, `BATCHED_WAKEUP` at 30 s latency.
     */
    private val nominalMode = SensorStrategy.decide(
        isWakeUp = SyntheticSource.SIMULATED_SENSOR.wakeUp,
        fifoReserved = SyntheticSource.SIMULATED_SENSOR.fifoReserved,
        rateHz = 50,
    )

    /** Seconds of sensor time delivered per second of wall-clock time. */
    private val replayAcceleration: Long
        get() = nominalMode.maxReportLatencyUs.toLong() / 1_000L / SyntheticSource.BURST_PAUSE_MS

    @Test
    fun `the replay really does advance 250 s of sensor time per second`() {
        // 30 s of burst for 120 ms of pause. If this number moves — because `FLUSH_GAP_NS`
        // changes, or because the simulated FIFO changes — it is the bench divisor that must
        // change with it, and not the other way round.
        assertThat(replayAcceleration).isEqualTo(250L)
    }

    @Test
    fun `the bench divisor equals the replay acceleration`() {
        if (TimeScaling.DIVISOR == 1L) return // ordinary debug build, not a bench

        assertThat(TimeScaling.DIVISOR)
            .`as`(
                "time divisor: compress wall-clock time exactly as much as the replay compresses " +
                    "sensor time, otherwise chunk rotation changes cause",
            )
            .isEqualTo(replayAcceleration)
    }

    /**
     * Why the `Preflight.scaleMismatch` guard rail exists, in figures.
     *
     * At bench scale, the guard delay of the cut-off time drops below the FIFO burst latency. On
     * the replay this is not a problem: the burst is compressed too, since sensor time advances
     * 250 times faster. On the real sensor it is not — it is hardware — and the recording
     * therefore stops before its first sample. That is the measurement of §11.5.3: 14.636 s of
     * night, zero chunks, no message.
     *
     * An **inverted** assertion, like the one on chunk fill: the day it fails, it is not this test
     * that must be adjusted, it is that the guard rail's reason for existing has changed.
     */
    @Test
    fun `at bench scale, the cut-off time would cut before the first FIFO burst`() {
        val cutoffGuardMs = com.pendulum.wear.time.Durations(replayAcceleration).minDelayBeforeCutoffMs
        val fifoBurstMs = nominalMode.maxReportLatencyUs / 1_000L

        assertThat(cutoffGuardMs)
            .`as`("guard delay of the cut-off time, compressed at the bench divisor")
            .isLessThan(fifoBurstMs)
        assertThat(fifoBurstMs).isEqualTo(30_000L)
        assertThat(cutoffGuardMs).isEqualTo(14_400L)
    }

    @Test
    fun `at replay scale, duration closes the chunk just before the byte ceiling`() {
        // This is the property the bench must preserve, and it is true in real running: 50 Hz x 6
        // bytes plus the block headers make about 303 B/s, so 92 160 bytes are reached ~4 s
        // **after** the 300 s of the duration bound. Chunks come out filled to ~99 % of the
        // ceiling, and it is that fill which exercises how the buffers hold up and the passage
        // under the 100 KB of a `DataItem`.
        //
        // An **inverted** assertion: the day it fails, it is not this test that must be adjusted,
        // it is the fact that the bench has stopped circulating full chunks.
        val bytesPerSensorSecond = 50.0 * 6.0 * (1.0 + 32.0 / (512.0 * 6.0))
        val secondsToFill = WireProtocol.CHUNK_ROTATION_BYTES / bytesPerSensorSecond
        val secondsOfTheBound = WireProtocol.CHUNK_ROTATION_MS / 1_000.0

        assertThat(secondsOfTheBound)
            .`as`("the duration bound must close the chunk before the byte ceiling")
            .isLessThan(secondsToFill)
        assertThat(secondsOfTheBound / secondsToFill)
            .`as`("chunk fill at closing time")
            .isGreaterThan(0.95)
    }
}
