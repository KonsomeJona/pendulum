package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader
import com.pendulum.format.ChunkWriter
import com.pendulum.format.TelemetryPoint
import com.pendulum.format.wire.WireProtocol
import com.pendulum.wear.time.Durations
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * Naming, rotation and durability of a session's chunk files.
 *
 * A chunk is closed as soon as `elapsed >= 300 s` **or** writing the next block would take it
 * past 92 160 bytes. Both conditions are necessary: the duration bounds what is lost if the
 * watch dies, the byte ceiling guarantees the file fits in the 100 KB payload of a `DataItem`
 * even if the real `fs` drifts or a degraded mode changes the rate.
 *
 * **On the bench, the two conditions no longer run at the same speed.** Duration compresses,
 * volume does not — that is the choice explained in `TimeScale`. The consequence is quantified in
 * the KDoc of [writeBlock], because that is where the race is decided.
 *
 * **Night telemetry travels in the same files**, as one more block type (see the KDoc of
 * `ChunkFormat`). It therefore has no ceiling of its own: its bytes count towards the writer's
 * `bytesWritten`, that is, towards the rotation-by-volume condition. The 92 160-byte guard rail
 * covers telemetry without anything having to be added for it — and it would have had to be
 * thought about had telemetry been given a path of its own.
 *
 * The file bears its final name from the moment it is opened: it is the **end marker** that
 * distinguishes a complete chunk from one still in progress, not its extension. A `.part`
 * renamed at close would be a second source of truth, which would diverge the day the process
 * dies between the `finish()` and the `rename()`.
 */
class ChunkStore(
    private val sessionDir: File,
    private val sessionUuid: ByteArray,
    private val sensorResolution: Float,
    private val sensorMaxRange: Float,
    private val fifoReserved: Int,
    startIndex: Int,
    private var rateHz: Int,
    private var modeFlags: Int,
    /** Rotation duration bound. A parameter rather than a constant read deep inside [writeBlock]:
     *  this is what makes rotation testable at a chosen scale, with no clock to tamper with. */
    private val rotationMs: Long = Durations.ACTIVE.chunkRotationMs,
    /** The wall clock and the boot clock, injected for the same reason as [rotationMs]: the anchor
     *  written by [open] is only testable if both can be set by hand. */
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedNs: () -> Long = android.os.SystemClock::elapsedRealtimeNanos,
) {

    /** Index of the next chunk to open. Carries the numbering on after a resume: never reset, the
     *  `(sessionId, idx)` uniqueness on the phone side depends on it. */
    var nextIndex: Int = startIndex
        private set

    private var writer: ChunkWriter? = null
    private var out: FileOutputStream? = null
    private var buffered: BufferedOutputStream? = null
    private var openedAtMs = 0L
    private var openIndex = -1

    /** Bytes and samples of the current chunk, for the display and the sidecar. */
    var totalSamples: Long = 0
        private set
    var totalBytes: Long = 0
        private set

    /** Processor freezes caused by `fsync`, since the last [consumeFlashWrites]. */
    private var fsyncCount = 0
    private var fsyncTotalUs = 0L
    private var fsyncMaxUs = 0L

    /** Samples clipped by the sensor since the last [consumeClippedSamples]. */
    private var clippedSinceLastPoint = 0L

    /** Value of the current writer's counter already accounted for: the writer restarts from zero
     *  at every chunk, whereas the telemetry runs across the whole night. */
    private var clippedInChunk = 0L

    init {
        sessionDir.mkdirs()
    }

    fun chunkFile(idx: Int): File = File(sessionDir, "%05d.pendulum".format(idx))

    /** True if a chunk is open, and therefore if a telemetry point has somewhere to go. */
    val chunkOpen: Boolean get() = writer != null

    /**
     * Writes a block, opening or rotating the chunk if necessary.
     *
     * ### Which of the two conditions closes the chunk, and what the bench changes about it
     *
     * In real operation the two are level, to within one per cent: 50 Hz x 6 bytes make about
     * 303 B/s once the block headers are counted, so the 92 160 bytes are reached after roughly
     * 304 s — just **after** the 300 s of the duration bound. It is the duration that closes, by
     * a hair, and chunks come out filled to ~99 % of the ceiling. The byte ceiling only wins in
     * the degraded cases, which are exactly the ones it exists for.
     *
     * On the bench, two independent accelerations are superimposed:
     *
     *  - the **replay** advances 250 s of sensor time per second of wall time, and that factor is
     *    not free — `SyntheticSource` derives it from the burst size and from
     *    `SensorPipeline.FLUSH_GAP_NS`. Bytes therefore accumulate 250 times faster;
     *  - the **duration bound**, for its part, is divided by `TimeScaling.DIVISOR`.
     *
     * The original equality is preserved only if those two factors are **the same number**. At
     * 250, the bound falls to 1 200 ms of wall time and the byte ceiling is reached at around
     * 1 216 ms: same hair, same chunks, same bytes — the real behaviour, played faster. At 600,
     * the bound falls to 500 ms while it still takes 1 216 ms to fill the chunk: duration wins by
     * a wide margin, chunks come out at ~40 % of the ceiling, and the bench **stops exercising**
     * what the ceiling exists for — the memory buffers holding up and the chunk staying under the
     * 100 KB of a `DataItem`.
     *
     * That is why `ScaleConsistencyTest` refuses any divisor that is not the acceleration of the
     * replay. The rule in one sentence: **compress wall time exactly as much as the replay
     * compresses sensor time, otherwise the rotation being tested is no longer the same one.**
     *
     * @return the index of the chunk closed by this write, or `null` if no rotation took place.
     */
    fun writeBlock(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        count: Int,
        tFirstNs: Long,
        tLastNs: Long,
        flags: Int,
        nowMs: Long,
    ): Int? {
        var closed: Int? = null
        val w = writer
        if (w != null) {
            val blockBytes = blockBytes(count)
            if (nowMs - openedAtMs >= rotationMs ||
                // Never scaled. See the KDoc above, and that of `TimeScale`.
                w.bytesWritten + blockBytes > WireProtocol.CHUNK_ROTATION_BYTES
            ) {
                closed = close()
            }
        }
        if (writer == null) open(tFirstNs, nowMs)
        val w2 = writer!!
        w2.writeBlock(x, y, z, count, tFirstNs, tLastNs, flags)
        // The writer's clipping counter is cumulative *per chunk*; the telemetry point, for its
        // part, counts from the previous point. The difference is taken here, where the two
        // horizons cross.
        val chunkTotal = w2.clippedSamples
        clippedSinceLastPoint += chunkTotal - clippedInChunk
        clippedInChunk = chunkTotal
        totalSamples += count
        totalBytes += blockBytes(count)
        return closed
    }

    /**
     * Writes a telemetry point into the current chunk.
     *
     * **Never opens a chunk on its own**, and that is deliberate: the file header carries
     * `firstEventTimestampNs`, which does not exist until a sample has arrived. A chunk opened by
     * telemetry would therefore carry an invented time base. The case only arises before the
     * first block of the night and just after a rotation forced by [rotate] — a few seconds out
     * of eight hours.
     *
     * @return true if the point was written, false if no chunk was open.
     */
    fun writeTelemetry(point: TelemetryPoint): Boolean {
        val w = writer ?: return false
        w.writeTelemetry(point)
        totalBytes += ChunkFormat.TELEMETRY_HEADER_SIZE + ChunkFormat.TELEMETRY_POINT_SIZE
        return true
    }

    /** The freezes caused by `fsync` since the last call, then a reset to zero. */
    fun consumeFlashWrites(): FlashWrites {
        val e = FlashWrites(fsyncCount, fsyncTotalUs, fsyncMaxUs)
        fsyncCount = 0
        fsyncTotalUs = 0
        fsyncMaxUs = 0
        return e
    }

    /** The samples clipped by the sensor since the last call, then a reset to zero. */
    fun consumeClippedSamples(): Long {
        val n = clippedSinceLastPoint
        clippedSinceLastPoint = 0
        return n
    }

    private fun blockBytes(count: Int): Long =
        ChunkFormat.BLOCK_HEADER_SIZE + count.toLong() * ChunkFormat.BYTES_PER_SAMPLE

    /**
     * Forces a rotation, without writing a block. Used at every mode change: `modeFlags` and
     * `nominalRateHz` live in the file header and are never rewritten.
     */
    fun rotate(rateHz: Int = this.rateHz, modeFlags: Int = this.modeFlags): Int? {
        val closed = close()
        this.rateHz = rateHz
        this.modeFlags = modeFlags
        return closed
    }

    /**
     * `fsync` of the current chunk. No effect if no chunk is open.
     *
     * Called every ten seconds of `elapsedRealtime`, decided by `TickSchedule` in the wake of a
     * FIFO flush or of the service tick — whichever comes first — so it causes **no wake-up of its
     * own**. It used to be counted in ticks of the uptime clock, which does not advance while the
     * SoC is suspended: in batched mode that made "ten seconds" a matter of minutes.
     */
    fun sync() {
        val fos = out ?: return
        buffered?.flush()
        measure(fos)
    }

    /**
     * Timed `fsync`. The duration goes into the telemetry because it is the only moment when the
     * processor **freezes** of its own doing: during that freeze a sensor interrupt can be missed,
     * and a gap that falls there does not have the same cause as a gap that falls elsewhere. The
     * timing itself costs only two `System.nanoTime()`, around a call that already takes
     * milliseconds.
     */
    private fun measure(fos: FileOutputStream) {
        val t0 = System.nanoTime()
        fos.fd.sync()
        val dtUs = (System.nanoTime() - t0) / 1_000
        fsyncCount++
        fsyncTotalUs += dtUs
        if (dtUs > fsyncMaxUs) fsyncMaxUs = dtUs
    }

    /** Closes the current chunk by writing its end marker, then `fsync`s it. */
    fun close(): Int? {
        val w = writer ?: return null
        w.finish()
        buffered?.flush()
        out?.let {
            measure(it)
            it.close()
        }
        totalBytes += ChunkFormat.FOOTER_SIZE
        writer = null
        out = null
        buffered = null
        val idx = openIndex
        openIndex = -1
        return idx
    }

    private fun open(firstEventTsNs: Long, nowMs: Long) {
        val idx = nextIndex
        val file = chunkFile(idx)
        val fos = FileOutputStream(file)
        // The three clocks of the header describe **one instant: the first sample**, never the
        // moment the first block reaches the disk. A chunk opens from `writeBlock`, that is, at
        // the first FIFO flush — in `WAKEUP 30 s`, the mode measured on the Pixel Watch 3
        // (BENCH-LOG §12.4, reserved=3000), that burst is already thirty seconds old when it
        // arrives; in continuous mode the 512-sample cut still puts 10.24 s between the first
        // sample and the write. The header used to stamp `startWallMs` and
        // `startElapsedRealtimeNs` at the write and `firstEventTimestampNs` at the sample, and
        // `TimeAnchor` on the phone reads `(startWallMs, firstEventTimestampNs)` as simultaneous:
        // every movement of the night was projected 10 to 60 s too late against the Health
        // Connect hypnogram and the bedtime journal — a whole 30 s sleep epoch, varying from one
        // night to the next with the phase between `registerListener` and the first burst. A
        // movement at a wake/sleep boundary changed epoch, hence AASM status, silently.
        //
        // Both wall and boot clocks are pulled back by the same age, so that a reader pairing
        // *any* two of the three fields is right — the format's own KDoc reads
        // `startWallMs / startElapsedRealtimeNs` as a pair for the telemetry, and `TimeAnchor`
        // reads `startWallMs / firstEventTimestampNs`; pulling back only the wall clock would
        // have made the two pairs contradict each other, and a phone-side correction computing
        // the age from the boot clock would then subtract it a second time.
        //
        // The age is only subtracted when it is plausible: `SensorEvent.timestamp` is not
        // guaranteed to share the `elapsedRealtimeNanos` base (some OEMs exclude suspend time),
        // and on the bench `SyntheticSource` replays sensor time 250 times faster than wall time,
        // so the sample stamps run ahead of the boot clock. Outside the window the header stays
        // raw — the anchor merely late, as before, and the raw triplet still visible to the phone
        // for what the format calls drift detection — rather than moved by hours.
        val openedElapsedNs = elapsedNs()
        val ageNs = openedElapsedNs - firstEventTsNs
        val burstAgeNs = if (ageNs in 0..MAX_BURST_AGE_NS) ageNs else 0L
        val header = ChunkHeader(
            sessionUuid = sessionUuid,
            chunkIndex = idx,
            nominalRateHz = rateHz,
            startWallMs = wallClockMs() - burstAgeNs / 1_000_000L,
            startElapsedRealtimeNs = openedElapsedNs - burstAgeNs,
            firstEventTimestampNs = firstEventTsNs,
            sensorResolution = sensorResolution,
            sensorMaxRange = sensorMaxRange,
            // What goes in here is `fifoReservedEventCount`, not `fifoMaxEventCount`: it is the
            // guaranteed share, the only one that explains the behaviour observed on read-back.
            fifoMaxEventCount = fifoReserved.coerceIn(0, 0xFFFF),
            modeFlags = modeFlags,
            tzOffsetMin = TimeZone.getDefault().getOffset(wallClockMs()) / 60_000,
        )
        // The header is written in the writer's constructor: the file is valid from its very
        // first millisecond.
        val bos = BufferedOutputStream(fos, 8 * 1024)
        writer = ChunkWriter(bos, header)
        buffered = bos
        out = fos
        openedAtMs = nowMs
        openIndex = idx
        nextIndex = idx + 1
        clippedInChunk = 0
        totalBytes += ChunkFormat.HEADER_SIZE
        // The header is still in the buffer at this point: flush it before the `fsync`, otherwise
        // the file exists on disk but empty, and a cut here leaves a file with no magic.
        bos.flush()
        measure(fos)
    }

    private companion object {
        /**
         * Ceiling of a plausible age for the first sample of a chunk: twice the report latency
         * the strategy may ask of the sensor. Hardware time, not a wall-clock duration — the bench
         * compresses neither the FIFO nor this bound. Beyond it the two clocks do not share a base
         * and the difference measures nothing that should be subtracted.
         */
        const val MAX_BURST_AGE_NS: Long = 2L * SensorStrategy.MAX_LATENCY_US * 1_000L
    }
}

/**
 * What the writes to flash memory have cost since the previous telemetry point.
 *
 * @param count number of `fsync`s. It normalises the other two: ten freezes of 2 ms and one
 *   freeze of 20 ms are not explained the same way.
 * @param totalUs cumulative time spent frozen. It is the budget for the period.
 * @param maxUs worst freeze of the period. That is the one that explains an interrupt missed at a
 *   precise instant, where the total only tells the trend.
 */
data class FlashWrites(val count: Int, val totalUs: Long, val maxUs: Long)
