package com.pendulum.wear.record

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock

/**
 * The sensor, as [RecordingService] sees it: enough to decide the strategy, open the stream and
 * close it.
 *
 * **Why this seam exists.** A night lasts eight hours and does not replay. As long as
 * [SensorPipeline] can only be fed by `SensorManager`, the only way to exercise the chain —
 * cutting, chunks, transfer, analysis — is to wait for a real night. The seam lets a bench
 * substitute a signal with known ground truth, and compare what comes out end to end with what
 * the same signal produces in a pure JVM test: any divergence is then a defect of the chain, and
 * this is the only way to find out.
 *
 * **What the substitution gives up proving — and this is not a regrettable compromise to be
 * played down.** Replacing the source means no longer testing what happens **beneath** it:
 *
 *  - the **hardware FIFO batching** — that the HAL really does accumulate in
 *    `fifoReservedEventCount` and loses nothing, the contract the whole of [SensorStrategy]
 *    rests on;
 *  - the **SoC wake-ups** — that a wake-up sensor does raise the processor before exceeding its
 *    report latency, and therefore that the processor **sleeps** between two such raisings.
 *
 * That is exactly what phase P1 exists to measure, and P1 remains the only path to 99 % sample
 * coverage and eight-hour battery life. **A bench that passes brings P1 not one step closer.**
 *
 * **What this seam must not be used to test.** Process resilience — service killed, resume after
 * `am kill`, Doze — is verified on the **real** path, hardware source included. Running both
 * concerns through the same injection point would give a test that passes while guaranteeing
 * nothing: it would check that the injector restarts, not that the capture resumes.
 *
 * **The separation is made by source set, not by a flag.** `SourceFactory` exists in two copies —
 * `src/debug/` and `src/release/` — and only the debug one knows how to build a synthetic source.
 * An `if (BuildConfig.DEBUG)` can be worked around by inattention during a reshuffle and nothing
 * signals it; a source set cannot be worked around, because the code is not compiled into the
 * release variant and the oversight becomes a compilation error rather than a credible recording
 * produced out of nothing. It is the discipline already applied to the preview fixtures of
 * `:phone` (`phone/src/debug/.../preview/`).
 */
interface SensorSource {

    /**
     * What has to be known about the sensor **before** opening a session: the strategy
     * ([SensorStrategy.decide]) and the chunk header are derived from it.
     *
     * @return `null` when the device has no accelerometer — the only case in which a night cannot
     *   begin.
     */
    fun describe(): SensorDescription?

    /**
     * Opens the stream. Samples arrive on [handler], never on the calling thread: that is the
     * contract of `registerListener(..., handler)` and the whole of [SensorPipeline] depends on
     * it, since it is not synchronised.
     */
    fun start(mode: AcquisitionMode, handler: Handler, sink: SampleSink)

    /** Closes the stream. Idempotent: called at session close and at every degradation. */
    fun stop()
}

/**
 * The sensor properties that decide something, and nothing else.
 *
 * [fifoReserved] is the share **guaranteed** to this application; [fifoMax] is the total
 * capacity, shared between all the clients of the sensor. Both are exposed because the first one
 * decides ([SensorStrategy] budgets on it) and the second one is logged — see the KDoc of
 * [SensorStrategy] for why budgeting on the second is a lost bet on a Pixel Watch.
 */
data class SensorDescription(
    val name: String,
    val wakeUp: Boolean,
    val fifoReserved: Int,
    val fifoMax: Int,
    /** `Sensor.getResolution()`, in m/s2. Goes into the chunk header as it is. */
    val resolution: Float,
    /** `Sensor.getMaximumRange()`, in m/s2. Goes into the chunk header as it is. */
    val maxRange: Float,
)

/**
 * Where the samples go. The signature is **exactly** that of [SensorPipeline.onEvent], and that
 * is no accident: the two clocks it carries never serve the same purpose, and a source that
 * confused them would break everything else.
 *
 *  - [tsNs]: `SensorEvent.timestamp`, the time base of the **measurement**. It alone dates the
 *    samples, it alone is what [GapMonitor] looks at, it alone decides whether a block is valid.
 *  - [arrivalNs]: `SystemClock.elapsedRealtimeNanos()` at **reception**. It says nothing about
 *    the measurement and everything about the hardware: it is this one, and this one only, that
 *    spots the boundary between two FIFO flushes.
 */
fun interface SampleSink {
    fun onSample(x: Float, y: Float, z: Float, tsNs: Long, arrivalNs: Long, nowMs: Long)
}

/**
 * The real source: `SensorManager`, unchanged in its behaviour.
 *
 * The wake-up accelerometer is preferred when it exists, and the fallback to the ordinary variant
 * is kept as it was: the HAL contract makes the first the only defensible one, and
 * [SensorStrategy] only knows how to decide starting from that choice.
 *
 * **The off-body detector is not here.** It is logged and never acted upon, it does not feed
 * [SensorPipeline], and there is no reason to simulate it: the service keeps it. The seam
 * replaces the accelerometer, and only that.
 */
class HardwareSensorSource(private val sm: SensorManager) : SensorSource {

    private var sensor: Sensor? = null
    private var listener: SensorEventListener? = null

    private fun sensor(): Sensor? = sensor ?: (
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        )?.also { sensor = it }

    override fun describe(): SensorDescription? = sensor()?.let {
        SensorDescription(
            name = it.name,
            wakeUp = it.isWakeUpSensor,
            fifoReserved = it.fifoReservedEventCount,
            fifoMax = it.fifoMaxEventCount,
            resolution = it.resolution,
            maxRange = it.maximumRange,
        )
    }

    override fun start(mode: AcquisitionMode, handler: Handler, sink: SampleSink) {
        val c = sensor() ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sink.onSample(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    event.timestamp,
                    SystemClock.elapsedRealtimeNanos(),
                    SystemClock.elapsedRealtime(),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = l
        sm.registerListener(l, c, mode.samplingPeriodUs, mode.maxReportLatencyUs, handler)
    }

    override fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
    }
}
