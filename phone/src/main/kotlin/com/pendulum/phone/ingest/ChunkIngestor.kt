package com.pendulum.phone.ingest

import android.util.Log
import com.pendulum.format.ChunkReader
import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.ChunkEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.TelemetryPointEntity
import java.io.File

/**
 * A chunk file on the phone's disk, turned back into what the receiving service would have
 * written for it: the `chunk` row **and** the telemetry points.
 *
 * ### Why the row is rebuilt from the file and not stubbed
 *
 * Two paths put a file on the disk without its row: the process dying between
 * `ChunkStore.write` and the row insert, and a chunk landing before the session it belongs to
 * (see `PendulumListenerService.onChunk`). `IngestWorker` reconciled the first by inserting a
 * row with `tFirstNs = 0`, `tLastNs = 0`, `flagsOr = 0` and no telemetry at all — everything the
 * service takes from the watch's `ChunkMeta`, which the worker no longer has. That row was not
 * harmless: `PendulumRepository` takes the origin of the sensor time base from the smallest
 * `tFirstNs` of the night, so one recovered chunk put that origin at zero instead of at the first
 * sample, and every telemetry point was placed hours off the axis — the metrology band of the
 * whole night vanished. And the `TLM!` blocks were never harvested, so the night had no battery,
 * jitter or clipping trace to explain how it reads.
 *
 * Every field the watch announces is a function of the bytes — the watch itself computes them by
 * re-reading the file before it leaves (`DataLayerTransfer.summarize`), and the transport CRC-32
 * guarantees the phone holds the same bytes. So the row can be rebuilt **identically** here:
 * the same sums, the same extrema, the same `OR`, the same CRC. [scan] is that function, pure so
 * that a test can hold a file next to the row it yields.
 */
object ChunkIngestor {

    /** What one chunk file yields: its row, and the points its `TLM!` blocks carried. */
    class Scanned(val row: ChunkEntity, val telemetry: List<TelemetryPointEntity>)

    /**
     * Reads one chunk file back into its row and its telemetry.
     *
     * The summary mirrors the watch's `summarize`: `tFirstNs` is the smallest block start,
     * `tLastNs` the largest block end, `sampleCount` the sum of the decoded blocks, `flagsOr` the
     * `OR` of their flags. A file with no decodable block at all keeps the zeros — there is no
     * instant to name, and `complete` says whether the end marker was there.
     *
     * @param receivedAtMs what to record as the reception instant: the file's modification time
     *   on a recovery, the wall clock on a live arrival.
     */
    fun scan(sessionHex: String, idx: Int, file: File, receivedAtMs: Long): Scanned {
        val bytes = file.readBytes()
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var samples = 0L
        var flags = 0
        val points = ArrayList<TelemetryPoint>()
        val scan = bytes.inputStream().use { input ->
            ChunkReader.forEachBlock(input, onTelemetry = { points += it }) { b ->
                if (b.tFirstNs < first) first = b.tFirstNs
                if (b.tLastNs > last) last = b.tLastNs
                samples += b.sampleCount
                flags = flags or b.flags
            }
        }
        val row = ChunkEntity(
            sessionHex = sessionHex,
            idx = idx,
            path = file.absolutePath,
            size = bytes.size,
            crc32 = ChunkStore.crc32(bytes),
            sampleCount = samples.toInt(),
            tFirstNs = if (samples > 0) first else 0L,
            tLastNs = if (samples > 0) last else 0L,
            flagsOr = flags,
            complete = scan.complete,
            receivedAtMs = receivedAtMs,
        )
        return Scanned(row, TelemetryAdapter.toEntities(sessionHex, points))
    }

    /**
     * Brings the database back into agreement with the disk for one session: every file without
     * a row gets its telemetry, then its row, in that order — the same order as the live path,
     * because the acknowledgement that follows makes the watch delete the file, and the file is
     * what carries the points.
     *
     * The `night_session` row **must exist**: the chunk and telemetry rows are children of a
     * cascading foreign key onto it, and `INSERT OR IGNORE` does not cover foreign keys — the
     * insert would throw. The two callers check; a session without its row has nothing to
     * reconcile against yet.
     *
     * Never deletes anything: an unknown file is data to recover, not waste.
     *
     * @return the number of rows recovered. Zero means disk and database already agreed.
     */
    suspend fun reconcileDisk(db: PendulumDatabase, store: ChunkStore, sessionHex: String): Int {
        var recovered = 0
        for (file in store.listChunkFiles(sessionHex)) {
            val idx = SessionReassembler.indexOf(file) ?: continue
            if (db.chunkDao().find(sessionHex, idx) != null) continue
            val scanned = scan(sessionHex, idx, file, file.lastModified())
            if (scanned.telemetry.isNotEmpty()) {
                db.telemetryDao().insertAllIfAbsent(scanned.telemetry)
            }
            if (db.chunkDao().insertIfAbsent(scanned.row) != -1L) recovered++
        }
        if (recovered > 0) Log.i(TAG, "$sessionHex: $recovered chunk(s) recovered from disk")
        return recovered
    }

    private const val TAG = "PendulumIngest"
}
