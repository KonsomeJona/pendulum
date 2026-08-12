package com.pendulum.wear.record

import android.os.Handler
import android.os.SystemClock
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth

/**
 * Replay of a synthetic night in place of the sensor. **Debug only**: this file is not compiled
 * into the release variant, and that is the only guarantee that holds — see the KDoc of
 * [SensorSource] for why a run-time flag would not be one.
 *
 * ### The rule that decides what the bench is worth
 *
 * **The `SensorEvent.timestamp` values stay consistent with one another** — spaced by the nominal
 * period in sensor time — while the progression in real time is much faster. That is the rule
 * [GapMonitor] states as non-negotiable: a gap is measured on the differences between
 * `SensorEvent.timestamp` values, never on the arrival time. A source that aligned its timestamps
 * on the accelerated real clock would manufacture a gap at every burst; the escalation would take
 * a `PARTIAL_WAKE_LOCK`, and the bench would no longer measure anything but its own injector.
 *
 * ### Delivery by bursts is not an implementation detail
 *
 * The hardware delivers in bursts — 1 500 events at once after thirty seconds of silence — and it
 * is that shape [SensorPipeline] observes in order to place `FLAG_FIFO_BOUNDARY`. The replay
 * reproduces it, and the two constants that make it possible are not free:
 *
 *  - the **burst size** equals `maxReportLatencyUs x rateHz`, that is, exactly what one FIFO flush
 *    contains. With the default description below, 30 s at 50 Hz = 1 500 samples — the very figure
 *    the KDoc of [GapMonitor] cites;
 *  - the **pause between bursts** must exceed [SensorPipeline.FLUSH_GAP_NS] (100 ms), otherwise
 *    the pipeline no longer sees the boundary and the replay stops resembling batched mode.
 *
 * Time acceleration is therefore **not a free parameter**: it falls out of these two constraints.
 * 30 s of sensor time per 120 ms of real time, that is x250, that is an eight-hour night in under
 * two minutes.
 *
 * In continuous mode ([AcquisitionKind.CONTINUOUS_WAKELOCK], zero latency) the pause drops to
 * zero: real arrivals there are spaced by 20 ms, hence **already** below the 100 ms threshold, and
 * delivering them faster still changes nothing to what the pipeline observes — no boundary is
 * manufactured, none is lost.
 *
 * ### What this replay does not simulate, and must not be allowed to suggest
 *
 *  - **Hardware FIFO batching and SoC wake-ups.** That is the price of injection, stated in full
 *    in the KDoc of [SensorSource]: this bench does not bring phase P1 one step closer.
 *  - **Self-degradation.** A re-registration at 25 Hz keeps replaying the timestamps generated at
 *    50 Hz: the replay does not re-render the signal at another rate. The degradation path is
 *    checked elsewhere, on [GapMonitor] in the JVM and on the hardware.
 *  - **Process resilience.** See [SensorSource]: it goes through the real path.
 */
class SyntheticSource(
    private val spec: NightSpec = NightSpec(),
    private val seed: Long = 1L,
    private val description: SensorDescription = SIMULATED_SENSOR,
    private val clockNs: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) : SensorSource {

    companion object {
        /**
         * Just above [SensorPipeline.FLUSH_GAP_NS] (100 ms). Below it, the pipeline no longer
         * tells two bursts from a continuous stream and `FLAG_FIFO_BOUNDARY` disappears from the
         * chunks: the replay would then test a delivery shape the hardware does not produce.
         */
        const val BURST_PAUSE_MS = 120L

        /**
         * Size of the work packet when the report latency is zero. Without latency there is no
         * burst to imitate; this number therefore has no physical meaning, it only bounds the
         * duration of one loop iteration so that [stop] stays responsive.
         */
        const val CONTINUOUS_PACKET = 250

        /**
         * A plausible watch accelerometer, BMI270 class: wake-up, +/-8 g, 16 bits, and a
         * guaranteed FIFO share of 3 000 events. **Plausible values, not measured** — they serve
         * to make [SensorStrategy.decide] land on `BATCHED_WAKEUP` at 30 s of latency, which is
         * the nominal mode of the product and therefore the one the bench must exercise.
         */
        val SIMULATED_SENSOR = SensorDescription(
            name = "synthetic (algo/synth replay)",
            wakeUp = true,
            fifoReserved = 3000,
            fifoMax = 3000,
            resolution = 0.0023942f,
            maxRange = 78.4532f,
        )
    }

    /**
     * Created once, kept across re-registrations: a degradation must not restart the night from
     * its beginning, which would rewrite the same samples under new chunks and make the
     * end-to-end result incomparable with the JVM reference.
     */
    private var replay: SyntheticReplay? = null
    private var loop: Runnable? = null
    private var handler: Handler? = null

    override fun describe(): SensorDescription = description

    override fun start(mode: AcquisitionMode, handler: Handler, sink: SampleSink) {
        stop()
        val size = burstSize(mode)
        val pause = if (mode.maxReportLatencyUs == 0) 0L else BURST_PAUSE_MS
        val r = replay ?: SyntheticReplay(
            blocks = NightSynth.generate(spec, seed).blocks,
            // The replay is rebased on `elapsedRealtimeNanos`: real `SensorEvent.timestamp` values
            // share that base, and the reconstruction on the phone side depends on it to produce a
            // wall-clock time. The generator itself starts from a fixed `startNs` — no clock
            // enters a synthetic night, and that is what makes it deterministic.
            originNs = clockNs(),
        ).also { replay = it }

        this.handler = handler
        val runnable = object : Runnable {
            override fun run() {
                val burst = r.nextBurst(size) ?: return
                // A single read of the wall clock per burst: a FIFO flush does arrive within the
                // same millisecond, and that is what the pipeline expects.
                val nowMs = clockMs()
                for (i in 0 until burst.n) {
                    sink.onSample(
                        burst.x[i],
                        burst.y[i],
                        burst.z[i],
                        burst.tsNs[i],
                        // Read per sample, as in the real listener: inside a burst the arrivals
                        // are spaced by a few hundred nanoseconds, far below the boundary
                        // threshold, and that is precisely what keeps a burst from being taken
                        // for a resumption after silence.
                        clockNs(),
                        nowMs,
                    )
                }
                handler.postDelayed(this, pause)
            }
        }
        loop = runnable
        handler.post(runnable)
    }

    override fun stop() {
        loop?.let { handler?.removeCallbacks(it) }
        loop = null
    }

    /** What one FIFO flush contains: the report latency multiplied by the rate. */
    private fun burstSize(mode: AcquisitionMode): Int {
        val n = mode.maxReportLatencyUs.toLong() * mode.rateHz / 1_000_000L
        return if (n >= 2) n.toInt() else CONTINUOUS_PACKET
    }
}

/** A burst ready to deliver. [n] useful samples; the arrays may be longer. */
class Burst(
    val n: Int,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val tsNs: LongArray,
)

/**
 * The slicing of a generated night into bursts, **pure**: no Android, no clock, no thread. It is
 * the same discipline as [GapMonitor] — the part that decides something must be testable in the
 * JVM, because it is the part that can lie.
 *
 * Two choices have a reason behind them:
 *
 *  1. **Bursts ignore the generator's block boundaries.** A FIFO flush knows nothing of the
 *     slicing a generator has chosen; cutting bursts on block boundaries would produce a
 *     correlation that does not exist in the hardware, and the bench would validate a coincidence.
 *  2. **Timestamps are linearly interpolated between `tFirstNs` and `tLastNs` of each block**,
 *     exactly as the format does on read-back — the format has no per-sample timestamp. Replaying
 *     anything else would amount to injecting a signal the chain could not restore anyway.
 *
 * The gaps the generator has scheduled translate naturally into `tsNs` jumps between two
 * consecutive samples, and rightly so: they must be seen by [GapMonitor]. A night without gaps, on
 * the other hand, must produce none.
 */
class SyntheticReplay(
    private val blocks: List<SampleBlock>,
    originNs: Long,
) {

    private val offsetNs: Long = if (blocks.isEmpty()) 0L else originNs - blocks.first().tFirstNs

    private var iBlock = 0
    private var iSample = 0

    val finished: Boolean get() = iBlock >= blocks.size

    /**
     * @param size number of samples in the burst.
     * @return the next burst, or `null` when the night is exhausted.
     */
    fun nextBurst(size: Int): Burst? {
        require(size >= 1) { "burst size must be >= 1: $size" }
        if (finished) return null
        val x = FloatArray(size)
        val y = FloatArray(size)
        val z = FloatArray(size)
        val ts = LongArray(size)
        var n = 0
        while (n < size && iBlock < blocks.size) {
            val b = blocks[iBlock]
            if (iSample >= b.x.size) {
                iBlock++
                iSample = 0
                continue
            }
            x[n] = b.x[iSample]
            y[n] = b.y[iSample]
            z[n] = b.z[iSample]
            ts[n] = offsetNs + sampleTs(b, iSample)
            iSample++
            n++
        }
        return if (n == 0) null else Burst(n, x, y, z, ts)
    }

    private fun sampleTs(b: SampleBlock, i: Int): Long {
        val len = b.x.size
        if (len <= 1) return b.tFirstNs
        return b.tFirstNs + i.toLong() * (b.tLastNs - b.tFirstNs) / (len - 1)
    }
}
