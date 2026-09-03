package com.pendulum.phone.ingest

import com.pendulum.algo.model.SampleBlock
import com.pendulum.format.ChunkHeader
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
        /**
         * How many times the watch rebooted in the middle of this session, each one bridged by
         * [bridgeEpoch]. Zero for a normal night.
         *
         * Reported rather than silent: a bridged night is intact — that is the whole point of the
         * repair — but the bridge rests on a wall-clock difference, so a night that needed one is
         * not measured under quite the same conditions as one that did not. A reader who has to
         * explain an odd figure should be able to see that a reboot happened.
         */
        val epochResets: Int,
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
        var prevHeader: ChunkHeader? = null
        val epoch = EpochBridge()

        for (f in files) {
            val idx = indexOf(f) ?: continue
            // Read from the header, which arrives before the first block:
            //  - the nominal rate **of this chunk**, because it changes in flight;
            //  - the boot epoch, because it changes when the watch restarts.
            var chunkNominalHz = 0.0
            val scan = f.inputStream().buffered().use { input ->
                ChunkReader.forEachBlock(
                    input,
                    onHeader = { h ->
                        chunkNominalHz = h.nominalRateHz.toDouble()
                        bridgeEpoch(h, prevHeader, blocks, epoch)
                        prevHeader = h
                    },
                ) { decoded ->
                    // `adoptInPlace`: the block comes out of the reader and is not read anywhere
                    // else, so converting its arrays in place saves ~19 MB of peak memory over a
                    // night without risking anything. See the contract of BlockAdapter.
                    blocks += BlockAdapter.adoptInPlace(decoded, chunkNominalHz, epoch.offsetNs)
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
            epochResets = epoch.resets,
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

    /**
     * The running shift applied to the sensor time base, and how many reboots it has bridged.
     *
     * Mutable and carried across the whole reassembly, because the shifts **accumulate**: a night
     * that survives two reboots must place the third epoch after the second, not after the first.
     */
    private class EpochBridge {
        var offsetNs: Long = 0L
        var resets: Int = 0
    }

    /**
     * Places a new boot epoch on the time scale of the previous one, when the watch has restarted
     * in the middle of a session.
     *
     * ### Why this is needed at all
     *
     * A reboot mid-night is a case the watch handles on purpose: `BootReceiver` resumes the **same**
     * session — same `sessionHex`, chunk numbering carried on, `FLAG_GAP_BEFORE` set on the first
     * block. What nothing handled is that `SensorEvent.timestamp` is `elapsedRealtimeNanos`, which
     * restarts from zero at boot. Every post-reboot block therefore dates *before* the last
     * pre-reboot one, and step −1 rejected them one after another as `NON_MONOTONIC` — because it
     * compares against the last **accepted** block, which never advances again. The result was a
     * night cut in half at the reboot, reported as an integrity failure although every file was
     * intact, with the second half sitting unused on the phone's disk.
     *
     * Step −1 only ever knew how to handle a jump **forward** (> 14 h = clock reset, cut the
     * session). A reboot is a jump backwards.
     *
     * ### Why the wall clock is legitimate here, and only here
     *
     * `SPEC-v2` §2 forbids computing durations by differencing wall clocks, and rightly so. This is
     * the one place where there is no alternative: two boot epochs share **no** monotonic clock, so
     * the wall clock is the only bridge between them. Its error is seconds; the hole it measures is
     * minutes. And that error lands *inside* the hole, which is longer than `gapSegmentSec`: step 0
     * turns it into a `SEGMENT_BREAK`, the filters restart on the new segment, and `SeriesBuilder`
     * forbids any series from spanning it. So no inter-movement interval — the quantity this whole
     * application measures — is ever computed across the bridge.
     *
     * The bridging is **counted**, never silent: see [Night.epochResets].
     */
    private fun bridgeEpoch(
        h: ChunkHeader,
        prev: ChunkHeader?,
        blocks: List<SampleBlock>,
        epoch: EpochBridge,
    ) {
        // `startElapsedRealtimeNs` is monotonic within one boot and can only go backwards across
        // one: it is the only field that tells the epochs apart. The chunk header is also the only
        // place it is visible — the block headers carry the raw sensor stamps alone.
        if (prev == null || h.startElapsedRealtimeNs >= prev.startElapsedRealtimeNs) return

        epoch.resets++
        val wallNs = (h.startWallMs - prev.startWallMs) * 1_000_000L
        epoch.offsetNs += prev.firstEventTimestampNs + wallNs - h.firstEventTimestampNs

        // The wall clock may itself have stepped backwards — a watch resynchronises over NTP right
        // after booting, and that correction can exceed the reboot's own duration. Without this
        // floor the bridge would land on, or before, the last sample already stacked, and step −1
        // would reject the new epoch as `NON_MONOTONIC` or `OVERLAP`: exactly the defect being
        // fixed, reintroduced by its own fix. Two sample periods of clearance, so the gap stays a
        // gap and never becomes an overlap.
        val last = blocks.lastOrNull() ?: return
        val hz = if (h.nominalRateHz > 0) h.nominalRateHz else DEFAULT_RATE_HZ
        val floorNs = last.tLastNs + 2 * (1_000_000_000L / hz)
        if (h.firstEventTimestampNs + epoch.offsetNs < floorNs) {
            epoch.offsetNs = floorNs - h.firstEventTimestampNs
        }
    }
}
