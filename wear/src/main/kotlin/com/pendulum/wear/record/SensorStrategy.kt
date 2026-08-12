package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat

/**
 * Acquisition strategy decision. **Pure**: no Android dependency at all, so the four branches
 * are covered by JVM tests.
 *
 * The HAL contract is normative and decides almost everything:
 *  - **non-wake-up** sensor while suspended: events keep entering the hardware FIFO, but
 *    "if the FIFO is too small to store all events, the older events are lost";
 *  - **wake-up** sensor while suspended: the HAL **must wake the SoC** before exceeding the
 *    report latency *or* filling the FIFO.
 *
 * So: if a wake-up variant of `TYPE_ACCELEROMETER` exists, it is the only defensible choice, and
 * the question of the wake lock only arises in its absence.
 *
 * **We budget on `fifoReservedEventCount`, never on `fifoMaxEventCount`.** The latter is the
 * sensor's total capacity, *shared* between all of its clients (Health Services, the
 * manufacturer's app, the system): sizing on it means betting that nobody else is listening, and
 * that bet is lost on a Pixel Watch. The former is the only share guaranteed to this application
 * — and it is also the one written into the chunk header.
 */
object SensorStrategy {

    /** ~10 s of guaranteed buffer at 50 Hz. **An engineering choice, not a sourced value.** */
    const val RESERVED_FOR_WAKEUP_BATCH = 500

    /** ~60 s of guaranteed buffer at 50 Hz: an acceptable bet for a non-wake-up sensor. */
    const val RESERVED_FOR_NONWAKEUP_BATCH = 3000

    private const val MIN_LATENCY_US = 10_000_000
    private const val MAX_LATENCY_US = 60_000_000

    /**
     * @param isWakeUp `Sensor.isWakeUpSensor` of the sensor that was retained.
     * @param fifoReserved `Sensor.getFifoReservedEventCount()` — the guaranteed share, the only
     *   one a latency budget can be computed on.
     * @param rateHz requested rate, in Hz.
     */
    fun decide(isWakeUp: Boolean, fifoReserved: Int, rateHz: Int): AcquisitionMode {
        require(rateHz > 0) { "rateHz must be positive: $rateHz" }
        return when {
            isWakeUp && fifoReserved >= RESERVED_FOR_WAKEUP_BATCH -> {
                // We fill only half of the guaranteed share: the margin absorbs an fs drift and
                // a batch that starts late without ever touching the ceiling.
                val latencyUs = (0.5 * fifoReserved / rateHz * 1_000_000).toInt()
                    .coerceIn(MIN_LATENCY_US, MAX_LATENCY_US)
                AcquisitionMode(AcquisitionKind.BATCHED_WAKEUP, latencyUs, false, rateHz, true)
            }

            isWakeUp ->
                // Wake-up but a thin FIFO: the HAL will wake often. We keep batching for what it
                // is worth, without a wake lock — the wake-up contract is enough to guarantee
                // that no event is lost.
                AcquisitionMode(AcquisitionKind.BATCHED_WAKEUP, MIN_LATENCY_US, false, rateHz, true)

            fifoReserved >= RESERVED_FOR_NONWAKEUP_BATCH ->
                AcquisitionMode(AcquisitionKind.BATCHED_WAKELOCK, 20_000_000, true, rateHz, false)

            else ->
                // Non-wake-up and a short FIFO: loss while suspended is documented, not
                // hypothetical.
                AcquisitionMode(AcquisitionKind.CONTINUOUS_WAKELOCK, 0, true, rateHz, false)
        }
    }
}

enum class AcquisitionKind { BATCHED_WAKEUP, BATCHED_WAKELOCK, CONTINUOUS_WAKELOCK }

/**
 * Effective acquisition mode. [modeFlags] goes as-is into the chunk header, where it is frozen:
 * the format is append-only, so any change of mode forces a chunk rotation.
 */
data class AcquisitionMode(
    val kind: AcquisitionKind,
    val maxReportLatencyUs: Int,
    val needsWakeLock: Boolean,
    val rateHz: Int,
    /** The sensor that was retained is the wake-up variant. Preserved across degradations: the
     *  flag describes the sensor, not the mode, and it must not disappear at step 2. */
    val wakeUpSensor: Boolean,
    val degraded: Boolean = false,
) {
    val samplingPeriodUs: Int get() = 1_000_000 / rateHz

    val modeFlags: Int
        get() = (if (wakeUpSensor) ChunkFormat.MODE_WAKEUP_SENSOR else 0) or
            (if (maxReportLatencyUs > 0) ChunkFormat.MODE_BATCHED else 0) or
            (if (needsWakeLock) ChunkFormat.MODE_WAKE_LOCK else 0) or
            (if (degraded) ChunkFormat.MODE_DEGRADED else 0)

    /** Short label shown on the watch: `WAKEUP 30 s`, `CONTINUOUS`, `WAKELOCK 20 s`. */
    val label: String
        get() = when (kind) {
            AcquisitionKind.BATCHED_WAKEUP -> "WAKEUP ${maxReportLatencyUs / 1_000_000} s"
            AcquisitionKind.BATCHED_WAKELOCK -> "WAKELOCK ${maxReportLatencyUs / 1_000_000} s"
            AcquisitionKind.CONTINUOUS_WAKELOCK -> "CONTINUOUS"
        } + if (degraded) " (degraded)" else ""

    /**
     * Application of an auto-degradation step. **Monotonic**: a step never goes back down within
     * the same session, otherwise the system oscillates between two modes all night long.
     *
     * 1. take the wake lock — 2. zero latency (continuous) — 3. re-register at 25 Hz.
     */
    fun degradedTo(step: Int): AcquisitionMode = when (step) {
        1 -> copy(needsWakeLock = true, degraded = true)
        2 -> copy(
            kind = AcquisitionKind.CONTINUOUS_WAKELOCK,
            maxReportLatencyUs = 0,
            needsWakeLock = true,
            degraded = true,
        )
        // 25 Hz is still ample: CLMs last 0.5 to 10 s and the RMS envelope is computed over
        // 0.15 s. Nyquist is not the limiting factor, onset resolution is, and 40 ms is
        // enough for that.
        else -> copy(
            kind = AcquisitionKind.CONTINUOUS_WAKELOCK,
            maxReportLatencyUs = 0,
            needsWakeLock = true,
            rateHz = 25,
            degraded = true,
        )
    }
}
