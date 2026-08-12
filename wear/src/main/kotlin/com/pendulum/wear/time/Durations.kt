package com.pendulum.wear.time

import com.pendulum.format.time.TimeScale
import com.pendulum.format.wire.WireProtocol
import java.util.concurrent.TimeUnit

/**
 * Every **wall-clock** duration of `:wear`, and the only place they are allowed to live.
 *
 * Gathering them is not cosmetic: it makes the scale **verifiable**. `DurationsTest` builds two
 * catalogues, one at 1 and the other at 600, and compares every property by reflection. A duration
 * added here without going through [TimeScale.ms] makes that test fail; a duration hard-coded in a
 * file under `src/main/` makes the inventory of `DurationsInventoryTest` fail. Together they close
 * the door that review discipline alone always leaves ajar.
 *
 * ### What is not here, and why
 *
 *  - `WireProtocol.CHUNK_ROTATION_BYTES` — a volume. See [TimeScale]: that is precisely what we do
 *    not compress.
 *  - The windows of `GapMonitor`, the epoch of `WakeDetector`, the bucket of `PreviewEnvelope`,
 *    `SensorPipeline.FLUSH_GAP_NS` and the latencies of `SensorStrategy` — **sensor** or hardware
 *    time, in `Ns`/`Us`. The bench's synthetic source keeps the nominal period in sensor time;
 *    these windows therefore measure the same thing as they do for real, and compressing them
 *    would make them measure something else.
 *  - The `Tasks.await` timeouts — waiting on a real layer, not compressible.
 *  - `stopAtLocalMinutes` — a **local time of day**, not a duration. Ten in the morning stays ten
 *    in the morning whatever the speed at which one gets there.
 *
 * @param divisor 1 in real time. See the `TimeScaling` twins.
 */
class Durations(divisor: Long) {

    /**
     * Closing the current chunk on duration. The other rotation condition — the byte ceiling — is
     * **not** scaled, and that is the consequence to keep in mind: compressed, this bound almost
     * never fires, and it is volume that closes the chunks. The bench therefore exercises rotation
     * by bytes, not rotation by duration. See the KDoc of `ChunkStore.writeBlock`.
     */
    val chunkRotationMs: Long = TimeScale.ms(WireProtocol.CHUNK_ROTATION_MS, divisor)

    /** The service tick: `fsync`, stop conditions, publication of the state. */
    val serviceTickMs: Long = TimeScale.ms(TimeUnit.SECONDS.toMillis(10), divisor)

    /**
     * Beyond this, a session is stale whatever its marker says: neither the watchdog, nor
     * `BOOT_COMPLETED`, nor a `START_STICKY` restart picks it up again. Fourteen hours bound the
     * case of the marker left behind on disk after a kill without a close.
     */
    val sessionMaxAgeMs: Long = TimeScale.ms(TimeUnit.HOURS.toMillis(14), divisor)

    /**
     * Period of the WorkManager watchdog.
     *
     * **Compressing this value does not compress the behaviour.** `PeriodicWorkRequest` imposes a
     * fifteen-minute floor (`MIN_PERIODIC_INTERVAL_MILLIS`) and silently raises any shorter
     * request back to that floor. The scaled value states the intent and is worth having for the
     * reader; the bench, for its part, will never see the watchdog fire any faster, and must
     * therefore fire it itself. This is one of the things this bench does not prove.
     */
    val watchdogPeriodMs: Long = TimeScale.ms(TimeUnit.MINUTES.toMillis(15), divisor)

    /**
     * Delay before asking for a foreground service again after an `onTimeout`. It leaves the
     * system the time to finish the shutdown; a one-time work request has no floor, so this one
     * really does compress.
     */
    val restartAfterTimeoutMs: Long = TimeScale.ms(TimeUnit.SECONDS.toMillis(30), divisor)

    /** Charger debounce: a magnetic contact produces brief false contacts. */
    val chargingDebounceMs: Long = TimeScale.ms(TimeUnit.MINUTES.toMillis(1), divisor)

    /** Maximum duration of a recording, `MAX_DURATION` stop condition. */
    val sessionMaxDurationMs: Long = TimeScale.ms(TimeUnit.HOURS.toMillis(10), divisor)

    /**
     * Minimum duration before the local cut-off time may stop the night: a recording started at
     * 11 am must not stop a millisecond later.
     */
    val minDelayBeforeCutoffMs: Long = TimeScale.ms(TimeUnit.HOURS.toMillis(1), divisor)

    companion object {

        /** The catalogue of the compiled variant. A single point of reading, a single divisor. */
        val ACTIVE = Durations(TimeScaling.DIVISOR)
    }
}
