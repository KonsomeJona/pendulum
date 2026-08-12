package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The four branches of [SensorStrategy.decide] and their exact bounds.
 *
 * These tests exist because the KDoc of `SensorStrategy` promises that they exist. But the real
 * reason lies elsewhere: each branch commits a different battery cost for a whole night (wake lock
 * or not, frequent SoC wake-ups or not), and the boundary between two branches is a plain
 * comparison on `fifoReservedEventCount`. A `>` that becomes a `>=` — or the other way round —
 * crashes nothing: it silently changes the acquisition mode of a given device, and the failure
 * shows up weeks later in the form of "the battery no longer lasts the night".
 */
class SensorStrategyTest {

    // -------------------------------------------------------------------------------------
    // Branch 1: wake-up with a sufficient guaranteed FIFO
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("wake-up with exactly 500 guaranteed events: budgeted batching is chosen")
    fun `exact bound of the batched wake-up branch`() {
        // rateHz = 20 so that the computed latency (12.5 s) differs from the floor (10 s):
        // at 50 Hz branches 1 and 2 produce the same result and the bound would be invisible.
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 500, rateHz = 20)

        assertThat(mode.kind).isEqualTo(AcquisitionKind.BATCHED_WAKEUP)
        assertThat(mode.maxReportLatencyUs)
            .withFailMessage(
                "At the bound fifoReserved = RESERVED_FOR_WAKEUP_BATCH the latency must be " +
                    "computed on half the guaranteed share (0.5 x 500 / 20 Hz = 12.5 s), not " +
                    "fall back to the floor: otherwise the bound has slipped and sensors just " +
                    "at the threshold lose their batching budget. Got: %d us.",
                mode.maxReportLatencyUs,
            )
            .isEqualTo(12_500_000)
        assertThat(mode.needsWakeLock).isFalse()
        assertThat(mode.wakeUpSensor).isTrue()
    }

    @Test
    @DisplayName("the latency never drops below 10 s: we do not wake the SoC more often")
    fun `latency floor`() {
        // 0.5 x 500 / 50 Hz = 5 s, below the floor: the value must be raised back to 10 s.
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 500, rateHz = 50)
        assertThat(mode.maxReportLatencyUs).isEqualTo(10_000_000)
    }

    @Test
    @DisplayName("the latency never exceeds 60 s, even with a huge FIFO")
    fun `latency ceiling`() {
        // 0.5 x 12000 / 50 Hz = 120 s: without a ceiling, the first burst would arrive two
        // minutes after going to bed and the user would judge the real-time preview dead.
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 12_000, rateHz = 50)
        assertThat(mode.maxReportLatencyUs).isEqualTo(60_000_000)
    }

    @Test
    @DisplayName("between floor and ceiling, the latency is half the guaranteed share")
    fun `budgeted latency without clipping`() {
        // 0.5 x 3000 / 50 Hz = 30 s: the 50 % margin absorbs an fs drift without ever touching
        // the FIFO ceiling, so without ever losing an event.
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 3_000, rateHz = 50)
        assertThat(mode.maxReportLatencyUs).isEqualTo(30_000_000)
        assertThat(mode.needsWakeLock).isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Branch 2: wake-up with a thin FIFO
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("wake-up at 499 guaranteed events: batching at the floor, still no wake lock")
    fun `wake-up below the bound`() {
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 499, rateHz = 20)

        assertThat(mode.kind).isEqualTo(AcquisitionKind.BATCHED_WAKEUP)
        assertThat(mode.maxReportLatencyUs).isEqualTo(10_000_000)
        // The wake-up HAL contract already guarantees that no event is lost: taking a wake lock
        // here would be paying twice for the same insurance, all night long.
        assertThat(mode.needsWakeLock)
            .withFailMessage(
                "A wake-up sensor must never require a wake lock: the HAL contract wakes the " +
                    "SoC before any loss. A wake lock here doubles the battery cost for nothing.",
            )
            .isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Branch 3: non-wake-up with a large FIFO
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("non-wake-up with exactly 3000 guaranteed events: batching under a wake lock")
    fun `exact bound of the batched non-wake-up branch`() {
        val mode = SensorStrategy.decide(isWakeUp = false, fifoReserved = 3_000, rateHz = 50)

        assertThat(mode.kind).isEqualTo(AcquisitionKind.BATCHED_WAKELOCK)
        assertThat(mode.maxReportLatencyUs).isEqualTo(20_000_000)
        assertThat(mode.needsWakeLock).isTrue()
        assertThat(mode.wakeUpSensor).isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Branch 4: non-wake-up with a short FIFO
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("non-wake-up at 2999: continuous under a wake lock is the only lossless mode")
    fun `non-wake-up below the bound`() {
        val mode = SensorStrategy.decide(isWakeUp = false, fifoReserved = 2_999, rateHz = 50)

        // The suspend-time loss of a non-wake-up sensor with a short FIFO is documented by the
        // HAL, not hypothetical: batching here means accepting gaps in every night.
        assertThat(mode.kind).isEqualTo(AcquisitionKind.CONTINUOUS_WAKELOCK)
        assertThat(mode.maxReportLatencyUs).isEqualTo(0)
        assertThat(mode.needsWakeLock).isTrue()
    }

    @Test
    @DisplayName("a zero or negative rate is a caller defect, never a default mode")
    fun `invalid rate rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            SensorStrategy.decide(isWakeUp = true, fifoReserved = 500, rateHz = 0)
        }
    }

    // -------------------------------------------------------------------------------------
    // Degradation: monotonic, and the wake-up flag describes the sensor, not the mode
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the three steps degrade in order: wake lock, continuous, 25 Hz")
    fun `degradation steps`() {
        val nominal = SensorStrategy.decide(isWakeUp = true, fifoReserved = 3_000, rateHz = 50)

        val step1 = nominal.degradedTo(1)
        assertThat(step1.needsWakeLock).isTrue()
        assertThat(step1.degraded).isTrue()
        // Step 1 does not touch batching: it only answers losses while suspended.
        assertThat(step1.kind).isEqualTo(AcquisitionKind.BATCHED_WAKEUP)
        assertThat(step1.maxReportLatencyUs).isEqualTo(nominal.maxReportLatencyUs)

        val step2 = nominal.degradedTo(2)
        assertThat(step2.kind).isEqualTo(AcquisitionKind.CONTINUOUS_WAKELOCK)
        assertThat(step2.maxReportLatencyUs).isEqualTo(0)
        assertThat(step2.rateHz).isEqualTo(50)

        val step3 = nominal.degradedTo(3)
        assertThat(step3.kind).isEqualTo(AcquisitionKind.CONTINUOUS_WAKELOCK)
        assertThat(step3.rateHz).isEqualTo(25)
    }

    @Test
    @DisplayName("the wake-up flag survives step 2: it describes the sensor, not the mode")
    fun `wake-up flag preserved through degradation`() {
        val degraded = SensorStrategy.decide(isWakeUp = true, fifoReserved = 3_000, rateHz = 50)
            .degradedTo(2)

        // The chunk header is the only trace that makes it possible, weeks later, to know which
        // physical sensor a night was recorded on. Losing this bit during degradation would make
        // two nights from the same device incomparable for no reason.
        assertThat(degraded.modeFlags and ChunkFormat.MODE_WAKEUP_SENSOR)
            .withFailMessage(
                "MODE_WAKEUP_SENSOR disappeared at step 2. The flag describes the sensor that " +
                    "was retained, not the acquisition mode: degradation does not change sensor.",
            )
            .isNotZero()
        assertThat(degraded.modeFlags and ChunkFormat.MODE_BATCHED).isZero()
        assertThat(degraded.modeFlags and ChunkFormat.MODE_WAKE_LOCK).isNotZero()
        assertThat(degraded.modeFlags and ChunkFormat.MODE_DEGRADED).isNotZero()
    }

    @Test
    @DisplayName("the flags of the nominal wake-up mode: batched, no wake lock, not degraded")
    fun `flags of the nominal mode`() {
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 3_000, rateHz = 50)

        assertThat(mode.modeFlags and ChunkFormat.MODE_WAKEUP_SENSOR).isNotZero()
        assertThat(mode.modeFlags and ChunkFormat.MODE_BATCHED).isNotZero()
        assertThat(mode.modeFlags and ChunkFormat.MODE_WAKE_LOCK).isZero()
        assertThat(mode.modeFlags and ChunkFormat.MODE_DEGRADED).isZero()
        assertThat(mode.samplingPeriodUs).isEqualTo(20_000)
    }
}
