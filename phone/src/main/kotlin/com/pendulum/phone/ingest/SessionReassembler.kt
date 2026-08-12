package com.pendulum.phone.ingest

import com.pendulum.algo.model.SampleBlock
import com.pendulum.format.ChunkReader
import com.pendulum.format.ChunkScanResult
import java.io.File

/**
 * The reassembly: from chunk files to the list of blocks that `:algo` knows how to read.
 *
 * ### Why truncation is a front-line output, and not a detail
 *
 * A truncated night is still analysable and **must** be analysed — the watch that died at 3 am
 * still recorded three hours. But its index is biased upwards in a way we do not know how to
 * correct: the series cut off by the edge lose their movements, the denominator loses minutes,
 * and the two biases do not go in the same direction. A truncated night is therefore analysed,
 * displayed, and **excluded from the trend** ([com.pendulum.phone.db.ComparableNight],
 * publication gate `TRUNCATED_NO_TREND`).
 *
 * Hence a rule: `sessionClosedCleanly` must be computed here, not guessed by `:algo`.
 * `TimelineBuilder.build` takes it as a parameter and defaults to `true` precisely because that
 * module cannot know — it only ever sees blocks. The phone, for its part, has seen the files.
 */
object SessionReassembler {

    /**
     * @param blocks all the decoded blocks of the night, in chronological order.
     * @param closedCleanly false as soon as one of the truncation signals is present. This is what
     *   goes into `TimelineBuilder.build(..., sessionClosedCleanly = ...)`.
     * @param missingIndices indices of chunks never received, within `[0, maxIdx]`. A gap in the
     *   middle is not the same thing as a missing end: the first can still arrive (the watch
     *   resends on acknowledgement), the second cannot.
     * @param declaredChunks total number of chunks announced by the watch at closing, `null` if
     *   the session was never closed cleanly.
     */
    data class Night(
        val sessionHex: String,
        val blocks: List<SampleBlock>,
        val nominalRateHz: Int,
        val closedCleanly: Boolean,
        val missingIndices: List<Int>,
        val incompleteChunks: List<Int>,
        val declaredChunks: Int?,
        val receivedChunks: Int,
        val corruptBlocks: Int,
        val resyncSkippedBytes: Long,
        val truncatedTail: Boolean,
        val desynchronised: Boolean,
        /**
         * The wall clock <-> sensor timeline anchoring, taken on the **first** decoded chunk.
         * `null` if no block could be read — in which case the Health Connect hypnogram cannot be
         * placed on the night, and it is better not to place it at all than to place it at random.
         */
        val anchor: TimeAnchor?,
    ) {
        val sampleCount: Long get() = blocks.sumOf { it.x.size.toLong() }
    }

    /**
     * @param files the chunk files, **sorted by index**. [ChunkStore.listChunkFiles] guarantees
     *   this through the zero-padding of the names.
     * @param sessionStateClosed did the watch announce `CLOSED`? A session left `OPEN`, or moved
     *   to `STALE`/`TRUNCATED` by the watchdog, is truncated by definition, even if every file
     *   present is flawless.
     */
    fun reassemble(
        sessionHex: String,
        files: List<File>,
        sessionStateClosed: Boolean,
        declaredChunks: Int?,
    ): Night {
        val blocks = ArrayList<SampleBlock>(files.size * 30)
        val scans = ArrayList<Pair<Int, ChunkScanResult>>(files.size)
        val incomplete = ArrayList<Int>()
        var nominalRateHz = 0
        var anchor: TimeAnchor? = null

        for (f in files) {
            val idx = indexOf(f) ?: continue
            val scan = f.inputStream().buffered().use { input ->
                ChunkReader.forEachBlock(input) { decoded ->
                    // `adoptInPlace`: the block comes out of the reader and is not read anywhere
                    // else, so converting its arrays in place saves ~19 MB of peak memory over a
                    // night without risking anything. See the contract of BlockAdapter.
                    blocks += BlockAdapter.adoptInPlace(decoded)
                }
            }
            if (nominalRateHz == 0) nominalRateHz = scan.header.nominalRateHz
            // The anchoring is taken on the first block **actually decoded**, not on the header
            // alone: if the very first blocks were rejected, the timeline does not start at
            // `firstEventTimestampNs`, and a hypnogram placed on it would be shifted.
            if (anchor == null && blocks.isNotEmpty()) {
                anchor = TimeAnchor(
                    startWallMs = scan.header.startWallMs,
                    firstEventTimestampNs = scan.header.firstEventTimestampNs,
                    timelineT0Ns = blocks.first().tFirstNs,
                )
            }
            if (!scan.complete) incomplete += idx
            scans += idx to scan
        }

        val received = scans.map { it.first }.sorted()
        val missing = missingIndices(received, declaredChunks)

        // Five signals, one single verdict. None of them is redundant:
        //  - the watch has not announced the end       -> the night may still go on, or not;
        //  - indices are missing                       -> a gap in the middle, time lost;
        //  - a chunk has no end marker                 -> it was still being written;
        //  - fewer chunks received than declared       -> the end of the transfer never arrived;
        //  - a desynchronisation elsewhere than in the tail -> corruption, not plain truncation.
        // The fifth signal was missing from the expression. It was computed, copied into `Night`,
        // and never read by anybody: a night corrupted right in the middle, resynchronised
        // afterwards and closed cleanly, came out with `closedCleanly = true` and entered the
        // trend like an intact night. The comment above already said what had to be done — it was
        // the code that did not do it.
        val desynchronised = scans.any { it.second.desynchronised }
        val closedCleanly = sessionStateClosed &&
            missing.isEmpty() &&
            incomplete.isEmpty() &&
            !desynchronised &&
            (declaredChunks == null || received.size >= declaredChunks)

        return Night(
            sessionHex = sessionHex,
            blocks = blocks,
            nominalRateHz = if (nominalRateHz > 0) nominalRateHz else DEFAULT_RATE_HZ,
            closedCleanly = closedCleanly,
            missingIndices = missing,
            incompleteChunks = incomplete,
            declaredChunks = declaredChunks,
            receivedChunks = received.size,
            corruptBlocks = scans.sumOf { it.second.corruptBlocks },
            resyncSkippedBytes = scans.sumOf { it.second.resyncSkippedBytes },
            truncatedTail = scans.any { it.second.truncatedTail },
            desynchronised = desynchronised,
            anchor = anchor,
        )
    }

    /**
     * The indices missing within `[0, upper]`.
     *
     * The bound is the **max of the declared and the received indices**: if the watch announced 96
     * chunks and only 40 are on hand, 56 are missing, not zero. Looking only at what was received
     * would give "no gap" to a night two thirds of which never arrived — the worst of the false
     * negatives, because it is a silent one.
     */
    fun missingIndices(received: List<Int>, declaredChunks: Int?): List<Int> {
        val maxReceived = received.maxOrNull() ?: -1
        val upper = maxOf(maxReceived, (declaredChunks ?: 0) - 1)
        if (upper < 0) return emptyList()
        val present = received.toHashSet()
        return (0..upper).filterNot { it in present }
    }

    /** `00042.pendulum` -> 42. `null` if the name does not follow the convention. */
    fun indexOf(file: File): Int? = file.name.removeSuffix(".pendulum").toIntOrNull()

    /** Fallback rate when no header could be read. `:algo` recomputes `fs` anyway. */
    const val DEFAULT_RATE_HZ = 50
}
