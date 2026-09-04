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
import com.pendulum.format.wire.Erasure
import com.pendulum.format.wire.LivePreview
import com.pendulum.format.wire.SessionHeader
import com.pendulum.format.wire.SessionState
import com.pendulum.format.wire.StopReason
import com.pendulum.format.wire.WirePaths
import com.pendulum.format.wire.WireProtocol
import com.pendulum.wear.record.RecordPhase
import com.pendulum.wear.record.RecordingState
import com.pendulum.wear.record.SessionMarker
import com.pendulum.wear.record.SessionStore
import org.json.JSONObject
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
        // A disowned session announces nothing — see `disown`. This is the check that keeps an
        // erased night from coming back at its close: `finalizeSession` re-puts the header in
        // state CLOSED, and on the phone `insertIfAbsent` would recreate the row from it.
        if (isDisowned(SessionStore(ctx).sessionDir(header.sessionHex))) {
            Log.i(TAG, "session ${header.sessionHex} disowned by the phone: not announced")
            return
        }
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
        if (discardIfDisowned(dir)) return false

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
        // Same rule as `putSession`: a disowned session leaves nothing new in the store.
        if (isDisowned(SessionStore(ctx).sessionDir(preview.sessionHex))) return
        val req = PutDataRequest.create(WirePaths.live(preview.sessionHex))
            .setData(preview.encode())
            .setUrgent()
        await(client(ctx).putDataItem(req))
    }

    // --- acknowledgement ---

    /**
     * Re-reads the acknowledgement item of a session, outside of any event.
     *
     * The acknowledgement is a convergent state, and the KDoc above sells that as "read ten times
     * for the same result" — but nobody read it a second time. `AckObserver` sees it only when it
     * *changes*, and the phone only re-puts it after a chunk event of its own. Every file whose bit
     * was already set when something went wrong stayed on the watch: an `applyAck` that died in
     * the middle of its loop, an acknowledgement applied while the store was unreachable. The
     * files counted in `Preflight.pendingChunkCount` for ever, "Sync now" was a no-op because
     * `pushChunks` skips every index still in the store, and the session directory was never
     * released. This read is what makes the "state, not event" claim true: `SyncWorker` applies
     * it before pushing anything.
     *
     * @return null when the phone has not acknowledged anything yet for this session, or when the
     *   item cannot be decoded — both mean "nothing to apply", and the push goes on as before.
     */
    fun readAck(ctx: Context, sessionHex: String): Ack? {
        val buffer = await(
            client(ctx).getDataItems(uri(WirePaths.ack(sessionHex)), DataClient.FILTER_LITERAL)
        )
        try {
            val data = buffer.firstOrNull()?.data ?: return null
            return runCatching { Ack.decode(data) }
                .onFailure { Log.w(TAG, "unreadable ack of $sessionHex", it) }
                .getOrNull()
        } finally {
            buffer.release()
        }
    }

    /**
     * Applies an acknowledgement: deletion of the acknowledged files **then** of their items, and
     * resend of those whose CRC-32 did not come out right. The order matters — an item deleted
     * before its file would leave an orphan file that no acknowledgement would ever claim again.
     *
     * The store operations are handed to [reconcileAck] as functions so that the reconciliation
     * itself — which files go, which items go, what one failure is allowed to stop — can be
     * exercised on a temporary directory without Google Play Services.
     */
    fun applyAck(ctx: Context, ack: Ack, dir: File): Int = reconcileAck(
        ack = ack,
        dir = dir,
        inflight = { inflightIndices(ctx, ack.sessionHex) },
        deleteItem = { idx ->
            await(client(ctx).deleteDataItems(uri(WirePaths.chunk(ack.sessionHex, idx))))
        },
        resendItem = { idx, f ->
            // An identical `putDataItem` is deduplicated by the Data Layer and would trigger
            // nothing at all: the item must therefore be deleted before being put again.
            await(client(ctx).deleteDataItems(uri(WirePaths.chunk(ack.sessionHex, idx))))
            buildChunkRequest(ack.sessionHex, idx, f)?.let {
                await(client(ctx).putDataItem(it.setUrgent()))
            }
        },
    )

    /**
     * The reconciliation of the disk and the store against one acknowledgement.
     *
     * **Every acknowledged file is erased, whatever the store does.** It was not so: each
     * `deleteDataItems` was awaited inline with a 60 s timeout, and the first `TimeoutException`
     * — a Bluetooth link that falters at 7 a.m. while the last acknowledgement of the night covers
     * twenty-four files — left the remaining files on disk with their items in the store. They
     * were acknowledged just as much, and no later pass ever came back for them: not `pushChunks`,
     * which skips every index in flight, and not `AckObserver`, which needs the acknowledgement
     * to change. The watch then showed "15 chunks pending" for good, and every such night added
     * ~1.4 MB of files and as much in the store.
     *
     * **The store is given up on after its first failure**, not retried file by file: this runs
     * inside a GMS callback, and twenty-four timeouts of 60 s would hold that callback for
     * twenty-four minutes where the old code held it for one. The files are still erased — the
     * acknowledgement covers them and the phone holds them — and their items become exactly the
     * orphans the sweep below is for, at the next pass.
     *
     * **Items with no file left are deleted.** They are what an interruption between `f.delete()`
     * and its `deleteDataItems` leaves behind: the file is gone, so no acknowledgement will ever
     * name that index again, and the item holds one of the [MAX_INFLIGHT_ITEMS] slots for the rest
     * of the session. The in-flight set is read **before** the disk is listed: a file only appears
     * before its item is put (`buildChunkRequest` reads it), so an item in flight whose file is
     * missing afterwards can only be one whose file this function, or a previous run of it, has
     * erased. The other order would let a chunk closed and pushed between the two reads be taken
     * for an orphan — recoverable, since its file stays and the next push re-puts it, but
     * pointless.
     *
     * @return the number of files erased.
     */
    internal fun reconcileAck(
        ack: Ack,
        dir: File,
        inflight: () -> Set<Int>,
        deleteItem: (Int) -> Unit,
        resendItem: (Int, File) -> Unit,
    ): Int {
        var deleted = 0
        var storeReachable = true
        // Sorted by index, and that is not cosmetic. The loop stops calling `deleteItem` at the
        // first failure — the link has dropped, the rest would only pile up timeouts — so *which*
        // items survive to the next pass depends on the order this loop walks in. Left to
        // `listFiles`, that order is whatever the filesystem hands back: stable enough to look
        // deterministic on one device, different on another. The oldest chunks are also the ones
        // that have been holding a slot the longest, so walking in index order is the right order
        // in its own right, not merely a reproducible one.
        dir.listFiles { f -> f.name.endsWith(".pendulum") }
            ?.mapNotNull { f -> f.nameWithoutExtension.toIntOrNull()?.let { it to f } }
            ?.sortedBy { it.first }
            ?.forEach { (idx, f) ->
                if (ack.isAcked(idx)) {
                    f.delete()
                    deleted++
                    if (storeReachable) {
                        runCatching { deleteItem(idx) }.onFailure {
                            storeReachable = false
                            Log.w(TAG, "item $idx of ${ack.sessionHex} not deleted, next pass", it)
                        }
                    }
                }
            }
        // The resends below go through the same store: with it unreachable they would only add
        // their own timeouts, and the acknowledgement that names them is re-read at the next pass.
        if (!storeReachable) return deleted

        val swept = runCatching {
            val stillInFlight = inflight()
            val onDisk = dir.listFiles { f -> f.name.endsWith(".pendulum") }
                ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
                ?.toSet()
                .orEmpty()
            for (idx in stillInFlight) {
                if (idx !in onDisk) deleteItem(idx)
            }
        }
        swept.exceptionOrNull()?.let {
            Log.w(TAG, "orphan items of ${ack.sessionHex} not swept, the next pass will", it)
            return deleted
        }

        for (idx in ack.needResend) {
            val f = File(dir, "%05d.pendulum".format(idx))
            if (!f.exists()) continue
            resendItem(idx, f)
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

    // --- erasure ---

    /**
     * The tombstone of a session the phone has disowned while it was still being recorded.
     *
     * It sits in the session directory, next to the chunks, because the directory is the one
     * thing the recording service and this object share without a word: the service keeps
     * writing there until its close, and every path that would carry a byte of it to the phone —
     * [putSession], [putLive], [pushChunks] — looks for this file first. An erased session must
     * not be *announced*, and not merely not sent: the phone recreates the night from the CLOSED
     * header alone.
     */
    const val DISOWNED_MARKER = "disowned"

    /** What [disown] did, for `AckObserver` to finish: the recording is stopped from there. */
    data class Disowned(
        /** The sessions given up, oldest directory first. */
        val sessions: List<String>,
        /** The session being recorded is among them: it has been tombstoned and must be stopped. */
        val stopRecording: Boolean,
    )

    /**
     * Applies an [Erasure] from the phone: every session started before the erasure loses its
     * files and its items, and the one being recorded, if it is one of them, is tombstoned so that
     * the service closes it in silence.
     *
     * ### What this repairs
     *
     * "Erase everything" on the phone erased the phone. The watch heard nothing, so it kept its
     * unacknowledged files for good — `pushChunks` skips every index still in the store and no
     * acknowledgement names an erased night again — counted as pending every evening; and a night
     * being recorded came back at its close, since `finalizeSession` re-put the CLOSED header and
     * the phone recreated the row from it. Both are described on `WirePaths.ERASE`.
     *
     * ### Why the start of each session is compared, and not merely "everything"
     *
     * The erasure is an item: it reaches a watch that was out of range, hours later, and by then a
     * new night may have started. That night is not the phone's to disown. The comparison is with
     * the session's `startWallMs` — from the marker for the active one, from the sidecar for the
     * others — against the instant on the phone's clock; the two clocks are a few seconds apart at
     * worst, and a session begun *during* the erasure on either reading is nothing anyone wants.
     *
     * A directory that cannot be dated — no sidecar, so a session killed inside its first five
     * minutes and never finalised — is disowned: the alternative keeps, for ever, a file nothing
     * will ever date. That is at most one chunk of a night that did not happen.
     *
     * ### The active session is not deleted, it is tombstoned
     *
     * The service holds an open stream in that directory and rotates new chunks into it until
     * its close; deleting the directory under it would only have it recreated at the next
     * rotation, without the tombstone. So the chunk files go now, the [DISOWNED_MARKER] is
     * written, and [discardIfDisowned] deletes whatever the service writes afterwards at each
     * burst — the recording ends up in nothing, and the phone never hears its name. `AckObserver`
     * then stops the service; `finalizeSession` clears the marker and enqueues `SyncWorker`,
     * which finds an empty directory and releases it, tombstone included. A session with a
     * marker but no service behind it — a crash, a kill — is simply deleted, marker included, so
     * that no resume path brings it back.
     *
     * The store is given up on after its first failure, for the same reason as in
     * [reconcileAck]: this runs in a GMS callback. The phone deleted the items on its side at the
     * instant of the erasure; what this deletes is what the watch put afterwards, and a failure
     * here leaves at worst an item nothing will ever read, not a file.
     */
    fun disown(ctx: Context, erasure: Erasure): Disowned {
        val store = SessionStore(ctx)
        return disownSessions(
            chunksRoot = store.chunksRoot,
            active = store.readMarker(),
            recording = RecordingState.state.value.phase != RecordPhase.IDLE,
            erasedBeforeMs = erasure.erasedBeforeMs,
            startOf = { dir -> sidecarStart(File(dir, "sidecar.json")) },
            deleteItems = { hex -> deleteItemsOf(ctx, hex) },
            clearActive = { store.clearActive() },
        )
    }

    /**
     * The disowning itself, with the marker, the clock, the sidecar reader and the store handed in
     * as values and functions, so that it runs on a temporary directory without Google Play
     * Services — the way [reconcileAck] is exercised.
     *
     * @param active the marker of the session being, or last, recorded — `null` when none.
     * @param recording whether the recording service is alive behind that marker. A marker with
     *   no service is a crashed session: deleted outright, marker included.
     * @param startOf reads a session's start from its directory; `null` when it cannot be dated.
     * @param deleteItems deletes every item of one session from the store. May throw; the first
     *   throw ends the store calls, never the file deletions.
     */
    internal fun disownSessions(
        chunksRoot: File,
        active: SessionMarker?,
        recording: Boolean,
        erasedBeforeMs: Long,
        startOf: (File) -> Long?,
        deleteItems: (String) -> Unit,
        clearActive: () -> Unit,
    ): Disowned {
        val disowned = ArrayList<String>()
        var stopRecording = false
        var storeReachable = true
        chunksRoot.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                val hex = dir.name
                val isActive = active != null && active.sessionHex == hex
                val started = if (isActive && active != null) active.startWallMs else startOf(dir)
                if (started != null && started >= erasedBeforeMs) return@forEach

                if (isActive && recording) {
                    dir.listFiles { f -> f.name.endsWith(".pendulum") }?.forEach { it.delete() }
                    File(dir, DISOWNED_MARKER).writeBytes(ByteArray(0))
                    stopRecording = true
                } else {
                    dir.deleteRecursively()
                    if (isActive) clearActive()
                }
                disowned += hex

                if (storeReachable) {
                    runCatching { deleteItems(hex) }.onFailure {
                        storeReachable = false
                        Log.w(TAG, "items of $hex not deleted, the phone's deletion covers them", it)
                    }
                }
            }
        return Disowned(disowned, stopRecording)
    }

    /** The session directory carries the tombstone written by [disownSessions]. */
    internal fun isDisowned(dir: File): Boolean = File(dir, DISOWNED_MARKER).exists()

    /**
     * For a tombstoned directory: erase the chunk files the service wrote since the last pass,
     * and report that there is nothing to push. This is the one place where a file is deleted
     * without its acknowledgement bit — the phone asked for exactly that.
     */
    internal fun discardIfDisowned(dir: File): Boolean {
        if (!isDisowned(dir)) return false
        dir.listFiles { f -> f.name.endsWith(".pendulum") }?.forEach { it.delete() }
        return true
    }

    /** `startWallMs` of the sidecar `SessionStore` writes at every chunk close, or `null`. */
    private fun sidecarStart(sidecar: File): Long? = runCatching {
        JSONObject(sidecar.readText()).getLong("startWallMs")
    }.getOrNull()

    private fun deleteItemsOf(ctx: Context, sessionHex: String) {
        await(client(ctx).deleteDataItems(uri(WirePaths.session(sessionHex))))
        await(client(ctx).deleteDataItems(uri(WirePaths.live(sessionHex))))
        await(
            client(ctx).deleteDataItems(
                uri(WirePaths.CHUNK_PREFIX + sessionHex + "/"),
                DataClient.FILTER_PREFIX,
            ),
        )
    }

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
