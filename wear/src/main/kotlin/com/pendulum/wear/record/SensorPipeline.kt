package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat

/**
 * Turning the stream of sensor events into chunk blocks.
 *
 * Two clocks arrive here and they **never** serve the same purpose:
 *
 *  - `SensorEvent.timestamp` — the time base of the measurement. It is the only one that dates
 *    the samples, the only one on which [GapMonitor] measures gaps, the only one that decides
 *    whether a block is valid.
 *  - `SystemClock.elapsedRealtimeNanos()` at reception — the *delivery* time. It says nothing
 *    about the measurement, and everything about the hardware: a thirty-second delivery interval
 *    in batched mode is a FIFO flush, not a gap. That is precisely what makes it useful for the
 *    only thing it knows: **spotting the boundary between two FIFO flushes**.
 *
 * Hence the central rule of this file: **a block never straddles two FIFO flushes**. The format
 * has no per-sample timestamp — it interpolates linearly between `tFirstNs` and `tLastNs` — and
 * that saving of 8 bytes per sample (11 MB per night) is only legitimate as long as the
 * interpolation is. A block straddling two flushes contains a gap that the interpolation spreads
 * over *all* of its samples, and therefore dates them all wrong. The producer is the only one
 * that knows where the boundary is: [ChunkWriter][com.pendulum.format.ChunkWriter] checks it with
 * a `require`, and cutting in the right place is our job, not its.
 */
class SensorPipeline(
    private val store: ChunkStore,
    private val gaps: GapMonitor,
    private val envelope: PreviewEnvelope,
    private var rateHz: Int,
    /** Called with the index of the chunk that has just been closed. */
    private val onChunkClosed: (Int) -> Unit,
    /** Current off-body state, logged and never acted upon. */
    private val offBody: () -> Boolean,
) {

    companion object {
        /**
         * Beyond this interval between two *arrivals*, a new FIFO flush is deemed to begin. In
         * continuous mode arrivals are one period apart (20 ms at 50 Hz); inside a burst they are
         * a few microseconds apart. 100 ms separates the two cases unambiguously, across the
         * whole range of rates under consideration.
         */
        const val FLUSH_GAP_NS = 100_000_000L
    }

    private val bx = FloatArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK)
    private val by = FloatArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK)
    private val bz = FloatArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK)
    private var count = 0
    private var tFirstNs = 0L
    private var tLastNs = 0L
    private var pendingFlags = 0
    private var lastArrivalNs = 0L

    /** Flags to set on the next block opened (gap detected, resume after reboot). */
    fun markNextBlock(flag: Int) {
        pendingFlags = pendingFlags or flag
    }

    /** @param nowMs `SystemClock.elapsedRealtime()` — a duration is never computed on the wall
     *   clock, which jumps at a time change and at NTP resynchronisation. */
    fun onRateChanged(rateHz: Int, nowMs: Long) {
        // The block in progress was sampled at the old rate: it must leave before the new one
        // makes its time base invalid with respect to the header of the next chunk.
        flushBlock(nowMs)
        this.rateHz = rateHz
        gaps.onRateChanged(rateHz)
        envelope.onRateChanged(rateHz)
    }

    fun onEvent(x: Float, y: Float, z: Float, tsNs: Long, arrivalNs: Long, nowMs: Long) {
        val flushBoundary = lastArrivalNs != 0L && arrivalNs - lastArrivalNs > FLUSH_GAP_NS
        lastArrivalNs = arrivalNs

        val gapBefore = gaps.onSample(tsNs)
        envelope.onSample(x, y, z, tsNs)

        if (count > 0) {
            val mustCut = flushBoundary ||
                gapBefore ||
                count >= ChunkFormat.MAX_SAMPLES_PER_BLOCK ||
                tsNs <= tLastNs ||
                // Same predicate as the writer's: we cut *before* the block's implicit rate
                // leaves the tolerance, which guarantees that `writeBlock` never fails and that
                // the interpolation stays honest.
                !ChunkFormat.isTimebasePlausible(count + 1, tFirstNs, tsNs, rateHz)
            if (mustCut) {
                flushBlock(nowMs)
                if (flushBoundary) pendingFlags = pendingFlags or ChunkFormat.FLAG_FIFO_BOUNDARY
                if (gapBefore) pendingFlags = pendingFlags or ChunkFormat.FLAG_GAP_BEFORE
            }
        } else if (flushBoundary) {
            pendingFlags = pendingFlags or ChunkFormat.FLAG_FIFO_BOUNDARY
        }

        if (count == 0) {
            tFirstNs = tsNs
            if (offBody()) pendingFlags = pendingFlags or ChunkFormat.FLAG_OFF_BODY
        }
        bx[count] = x
        by[count] = y
        bz[count] = z
        tLastNs = tsNs
        count++
    }

    /** Writes the block in progress, if there is one. To be called before any session close. */
    fun flushBlock(nowMs: Long) {
        if (count == 0) return
        val closed = store.writeBlock(bx, by, bz, count, tFirstNs, tLastNs, pendingFlags, nowMs)
        count = 0
        pendingFlags = 0
        closed?.let(onChunkClosed)
    }
}
