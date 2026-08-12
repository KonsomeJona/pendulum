package com.pendulum.wear.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.ChunkReader
import com.pendulum.format.wire.Ack
import com.pendulum.format.wire.ChunkMeta
import com.pendulum.format.wire.LivePreview
import com.pendulum.format.wire.SessionHeader
import com.pendulum.format.wire.SessionState
import com.pendulum.format.wire.StopReason
import com.pendulum.format.wire.WirePaths
import com.pendulum.format.wire.WireProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Incremental push of the chunks to the phone through `DataClient`.
 *
 * **Why `DataClient` and nothing else.** It is a **replicated and persistent** store: an item put
 * at 2 a.m. is already replicated, and a watch that dies at 3 a.m. no longer concerns it. It is
 * buffered offline and synchronised on reconnection — that is documented, so testing whether the
 * phone is reachable before writing would amount to reimplementing by hand a logic the layer
 * provides, and to getting it wrong at the exact moment it counts. We write, full stop.
 * `MessageClient` is fire-and-forget with no queue: never for data that cannot be lost.
 * `ChannelClient` has no persistence at all: the channel dies with the connection.
 *
 * **The protocol is idempotent by construction.** The phone inserts with `INSERT OR IGNORE` on
 * `(sessionId, idx)`, the acknowledgement is a `DataItem` — that is to say a *convergent state*,
 * read ten times for the same result — and **no file is erased before its acknowledgement bit**.
 * The watch's disk stays the source of truth until the phone's database becomes it.
 */
object DataLayerTransfer {

    private const val TAG = "PendulumTransfer"

    /**
     * Ceiling of items in flight: two hours of night, ~2.2 MB in the store. The real quota of the
     * `DataItem` store is documented nowhere, and discovering it through a crash at 4 a.m. is not
     * an acceptable method. Beyond it, the watch stops publishing and **carries on recording to
     * disk without the slightest degradation**.
     */
    const val MAX_INFLIGHT_ITEMS = 24

    /** One burst every three closed chunks, that is a quarter of an hour of night. */
    const val PUSH_EVERY_N_CHUNKS = 3

    private const val TASK_TIMEOUT_S = 60L

    private fun client(ctx: Context): DataClient = Wearable.getDataClient(ctx)

    /** URI without an authority: it designates the path on every node, which is exactly what is
     *  needed, both to read the acknowledgement put by the phone and to erase our own items. */
    private fun uri(path: String): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    private fun <T> await(task: Task<T>): T = Tasks.await(task, TASK_TIMEOUT_S, TimeUnit.SECONDS)

    // --- session ---

    fun putSession(ctx: Context, header: SessionHeader) {
        val req = PutDataRequest.create(WirePaths.session(header.sessionHex))
            .setData(header.encode())
            .setUrgent()
        await(client(ctx).putDataItem(req))
    }

    // --- chunks ---

    /**
     * Publishes the chunks present on disk and absent from the store, in index order, until the
     * ceiling of items in flight is saturated.
     *
     * @return true if the ceiling is reached while files are still left to send.
     */
    fun pushChunks(ctx: Context, sessionHex: String, dir: File, urgentLast: Boolean): Boolean {
        val files = dir.listFiles { f -> f.name.endsWith(".pendulum") }
            ?.sortedBy { it.name }
            ?: return false
        if (files.isEmpty()) return false

        val inflight = inflightIndices(ctx, sessionHex)
        var slots = MAX_INFLIGHT_ITEMS - inflight.size
        var backlogged = false
        var lastReq: PutDataRequest? = null

        for (f in files) {
            val idx = f.nameWithoutExtension.toIntOrNull() ?: continue
            if (idx in inflight) continue
            if (slots <= 0) {
                backlogged = true
                break
            }
            val req = buildChunkRequest(sessionHex, idx, f) ?: continue
            lastReq?.let { await(client(ctx).putDataItem(it)) }
            lastReq = req
            slots--
        }

        // `setUrgent()` is set only on the last item of the burst, betting — medium confidence,
        // undocumented — that the flush it causes also carries away the non-urgent items already
        // queued. If measurement says otherwise, mark every one of them urgent: the energy cost
        // is nil, they leave in the same wake-up.
        lastReq?.let {
            if (urgentLast) it.setUrgent()
            await(client(ctx).putDataItem(it))
        }
        return backlogged
    }

    /**
     * Builds the item of a chunk: `ChunkMeta` then the exact bytes of the file.
     *
     * A chunk **without an end marker is ignored**: either it is being written, or it is the
     * leftover of a brutal kill. Publishing it would expose the phone to acknowledging — hence to
     * having deleted — a partial file.
     */
    private fun buildChunkRequest(sessionHex: String, idx: Int, file: File): PutDataRequest? {
        val bytes = file.readBytes()
        val summary = summarize(bytes) ?: return null
        val meta = ChunkMeta(
            sessionHex = sessionHex,
            idx = idx,
            size = bytes.size,
            crc32 = summary.crc32,
            sampleCount = summary.sampleCount,
            tFirstNs = summary.tFirstNs,
            tLastNs = summary.tLastNs,
            flagsOr = summary.flagsOr,
        ).encode()

        val payload = ByteArrayOutputStream(meta.size + bytes.size + 4)
        // Minimal framing: the meta length on 4 little-endian bytes, then the meta, then the
        // file. No `DataMap`: a dictionary fails silently when a key is renamed, whereas here a
        // change of layout is rejected at the first byte.
        payload.write(meta.size and 0xFF)
        payload.write((meta.size shr 8) and 0xFF)
        payload.write((meta.size shr 16) and 0xFF)
        payload.write((meta.size shr 24) and 0xFF)
        payload.write(meta)
        payload.write(bytes)
        val data = payload.toByteArray()
        if (data.size > WireProtocol.MAX_DATA_ITEM_BYTES) {
            // Should not happen: rotation caps at 92 160 bytes. If it does, the fallback path
            // is `Asset.createFromFd()` on the same item — one line, to be written only the day
            // a measurement calls for it.
            Log.e(TAG, "chunk $idx too big for a DataItem: ${data.size} B")
            return null
        }
        return PutDataRequest.create(WirePaths.chunk(sessionHex, idx)).setData(data)
    }

    private class Summary(
        val crc32: Long,
        val sampleCount: Int,
        val tFirstNs: Long,
        val tLastNs: Long,
        val flagsOr: Int,
    )

    /**
     * Re-reads the file to extract its metadata. What the re-read costs (a few milliseconds over
     * 91 KB) it gives back as a guarantee: the transport CRC-32 and the fact that the end marker is
     * indeed present are computed on **the bytes that leave**, not on counters held in memory that
     * could drift from the file.
     */
    private fun summarize(bytes: ByteArray): Summary? {
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var samples = 0
        var flags = 0
        val scan = try {
            ChunkReader.forEachBlock(bytes.inputStream()) { b ->
                if (b.tFirstNs < first) first = b.tFirstNs
                if (b.tLastNs > last) last = b.tLastNs
                samples += b.sampleCount
                flags = flags or b.flags
            }
        } catch (e: Exception) {
            Log.e(TAG, "unreadable chunk, not published", e)
            return null
        }
        if (!scan.complete || samples == 0) return null
        val crc = CRC32().apply { update(bytes) }.value
        return Summary(crc, samples, first, last, flags)
    }

    /** Indices of the chunks present in the store, therefore in flight and counting towards the
     *  quota. */
    fun inflightIndices(ctx: Context, sessionHex: String): Set<Int> {
        val prefix = WirePaths.CHUNK_PREFIX + sessionHex + "/"
        val buffer = await(client(ctx).getDataItems(uri(prefix), DataClient.FILTER_PREFIX))
        try {
            return buffer.mapNotNull { it.uri.lastPathSegment?.toIntOrNull() }.toSet()
        } finally {
            buffer.release()
        }
    }

    // --- preview ---

    fun putLive(ctx: Context, preview: LivePreview) {
        val req = PutDataRequest.create(WirePaths.live(preview.sessionHex))
            .setData(preview.encode())
            .setUrgent()
        await(client(ctx).putDataItem(req))
    }

    // --- acknowledgement ---

    /**
     * Applies an acknowledgement: deletion of the acknowledged files **then** of their items, and
     * resend of those whose CRC-32 did not come out right. The order matters — an item deleted
     * before its file would leave an orphan file that no acknowledgement would ever claim again.
     */
    fun applyAck(ctx: Context, ack: Ack, dir: File): Int {
        var deleted = 0
        dir.listFiles { f -> f.name.endsWith(".pendulum") }?.forEach { f ->
            val idx = f.nameWithoutExtension.toIntOrNull() ?: return@forEach
            if (ack.isAcked(idx)) {
                f.delete()
                await(client(ctx).deleteDataItems(uri(WirePaths.chunk(ack.sessionHex, idx))))
                deleted++
            }
        }
        for (idx in ack.needResend) {
            val f = File(dir, "%05d.pendulum".format(idx))
            if (!f.exists()) continue
            // An identical `putDataItem` is deduplicated by the Data Layer and would trigger
            // nothing at all: the item must therefore be deleted before being put again.
            await(client(ctx).deleteDataItems(uri(WirePaths.chunk(ack.sessionHex, idx))))
            buildChunkRequest(ack.sessionHex, idx, f)?.let {
                await(client(ctx).putDataItem(it.setUrgent()))
            }
        }
        return deleted
    }

    // --- evening context ---

    /**
     * Lock of the evening context. The phone puts an item under `/pendulum/context/<night key>`
     * when the form is sealed; the watch refuses to start as long as it is not there.
     *
     * **The presence of the item is enough.** Its content is not decoded to decide: if the phone
     * one day writes one more field, a strict decode would turn an evolution of the format into a
     * lost night. The lock is a binary fact, not a structure.
     */
    fun isEveningContextSealed(ctx: Context, nightKey: String): Boolean {
        val buffer = await(
            client(ctx).getDataItems(uri(CONTEXT_PREFIX + nightKey), DataClient.FILTER_LITERAL)
        )
        try {
            return buffer.count > 0
        } finally {
            buffer.release()
        }
    }

    /**
     * The evening context and its night key used to live here, and the phone did not know them: it
     * therefore never wrote the item that `Preflight` requires, and START stayed blocked forever.
     * Both were moved up into `:format`, the module shared by the two applications, because the
     * failure mode of the Data Layer is silence and not error — two copied constants that diverge
     * by one character produce no message at all, they produce a watch that no longer starts.
     *
     * The aliases are kept: this file is the entry point of the Data Layer on the watch side, and
     * reading the name of the path here saves having to know which module declares it.
     */
    const val CONTEXT_PREFIX = WirePaths.CONTEXT_PREFIX

    fun nightKey(nowMs: Long): String = WirePaths.nightKey(nowMs)

    // --- session close ---

    fun closeSession(
        ctx: Context,
        marker: com.pendulum.wear.record.SessionMarker,
        endWallMs: Long,
        totalChunks: Int,
        reason: StopReason,
    ) {
        putSession(
            ctx,
            SessionHeader(
                sessionHex = marker.sessionHex,
                startWallMs = marker.startWallMs,
                tzOffsetMin = java.util.TimeZone.getTimeZone(marker.zoneId)
                    .getOffset(marker.startWallMs) / 60_000,
                zoneId = marker.zoneId,
                nominalRateHz = marker.nominalRateHz,
                modeFlags = marker.modeFlags,
                plannedStopWallMs = marker.plannedStopWallMs,
                state = SessionState.CLOSED,
                endWallMs = endWallMs,
                totalChunks = totalChunks,
                stopReason = reason,
            ),
        )
    }
}
