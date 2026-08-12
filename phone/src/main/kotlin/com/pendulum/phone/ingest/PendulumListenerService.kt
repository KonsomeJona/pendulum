package com.pendulum.phone.ingest

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.pendulum.format.wire.LivePreview
import com.pendulum.format.wire.SessionHeader
import com.pendulum.format.wire.SessionState
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.ChunkEntity
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.work.WorkScheduler
import kotlinx.coroutines.runBlocking

/**
 * Data Layer reception.
 *
 * ### Why this service and not a worker that polls
 *
 * Google Play Services starts this service in order to deliver a `DataItem`, **even if the
 * application has never been opened**, and it does so as soon as the item is synchronised. This is
 * the only way to take in a whole night without the user touching the phone. A periodic worker
 * would miss the window: the items arrive in bursts of three every fifteen minutes, and making
 * them wait for WorkManager's next wake-up would only keep items in flight for longer — hence
 * bring us closer to the ceiling of 24 beyond which the watch stops publishing.
 *
 * ### The order of the operations, which is the protocol itself
 *
 * 1. check the size, then the CRC-32 over the received bytes;
 * 2. write the file atomically;
 * 3. read the file back: the end marker, and the telemetry points of the `TLM!` block;
 * 4. `INSERT OR IGNORE` the telemetry, then `INSERT OR IGNORE` on `(sessionHex, idx)`;
 * 5. read the state back **from the database** and publish the acknowledgement.
 *
 * None of these steps commutes. Acknowledging before writing would make the watch delete the only
 * correct copy; inserting before verifying would record wrong bytes as valid; recomputing the
 * acknowledgement from anything other than the database would make it wrong at the first restart
 * of the service.
 *
 * `runBlocking` is deliberate: the callbacks of `WearableListenerService` already arrive on a
 * background thread, and GMS considers the event handled when the method returns. Launching a
 * coroutine and returning straight away would lose the event if the process is killed in the
 * meantime.
 */
class PendulumListenerService : WearableListenerService() {

    private val db by lazy { PendulumDatabase.get(this) }
    private val store by lazy { ChunkStore(this) }

    /** Number of resend requests already made, by `sessionHex#idx`. See [canStillAsk]. */
    private val resendRequests = HashMap<String, Int>()

    /**
     * Fallback for the remote opening: the watch asks for the phone to open.
     *
     * **This service does not launch an activity**, and that is not an oversight. It is started by
     * Google Play Services, hence from the background, and Android has blocked activity launches
     * from the background since version 10 — with no readable exception, with no error, and with a
     * single line in the system logs as its only trace. The nominal path goes through
     * `RemoteActivityHelper` on the watch side; when it fails, a notification is posted, and the
     * user tapping it is the only reliable exemption.
     */
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path != WirePaths.OPEN_PHONE) return
        com.pendulum.phone.notify.Notifications.eveningContextRequest(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // The sessions touched during this burst: the acknowledgement is published only once per
        // session and per burst, after everything has been written. One acknowledgement per chunk
        // would triple the `putDataItem` calls for the same final information.
        val touched = LinkedHashMap<String, MutableSet<Int>>()

        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val payload = event.dataItem.data ?: continue
            try {
                when {
                    path.startsWith(WirePaths.SESSION_PREFIX) -> onSession(payload)
                    path.startsWith(WirePaths.CHUNK_PREFIX) -> {
                        val hex = sessionHexOfChunkPath(path) ?: continue
                        val toResend = touched.getOrPut(hex) { linkedSetOf() }
                        onChunk(hex, payload)?.let { idx ->
                            if (canStillAsk(hex, idx)) toResend += idx
                        }
                    }
                    path.startsWith(WirePaths.LIVE_PREFIX) -> onLive(payload)
                }
            } catch (t: Throwable) {
                // A malformed item must not prevent the following ones from being processed: the
                // burst holds three chunks, losing three of them for one is losing 15 min of the
                // night.
                Log.w(TAG, "item ignored: $path", t)
            }
        }

        for ((hex, toResend) in touched) {
            runCatching { publishAck(hex, toResend.sorted()) }
                .onFailure { Log.w(TAG, "acknowledgement not published for $hex", it) }
        }
    }

    /**
     * **How many times a chunk is asked for again before giving up.**
     *
     * Resending is indispensable — without it a refused chunk is lost forever **and** keeps one of
     * the 24 in-flight slots until morning — but it cannot be unconditional: a chunk corrupted
     * **on the watch's own disk** would be resent identically at every acknowledgement, and the
     * night would be spent sending it back. A transport failure deserves to be retried, a storage
     * failure deserves to be given up on; nothing tells them apart seen from here, so we put a
     * bound on it.
     *
     * The counter lives in memory, and that is deliberate: if it starts again from zero because
     * the service was recreated, at worst we get a few more attempts, which is exactly the
     * behaviour we would want after a restart of the phone. Persisting it would add a third state
     * to reconcile for no benefit at all.
     */
    private fun canStillAsk(sessionHex: String, idx: Int): Boolean {
        val key = "$sessionHex#$idx"
        val n = (resendRequests[key] ?: 0) + 1
        resendRequests[key] = n
        if (n > MAX_RESEND_REQUESTS) {
            Log.w(TAG, "chunk $idx of $sessionHex given up after $MAX_RESEND_REQUESTS requests")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // /pendulum/session
    // ------------------------------------------------------------------

    private fun onSession(payload: ByteArray) = runBlocking {
        val h = SessionHeader.decode(payload)
        val dao = db.nightDao()

        // `insertIfAbsent` and not `REPLACE`: this item is re-put at every change of state, and a
        // REPLACE would wipe out along the way everything the analysis has written into the row.
        dao.insertIfAbsent(
            NightSessionEntity(
                sessionHex = h.sessionHex,
                // The evening this night attaches to, derived from its start time by the same
                // noon rollover as the path of the sealed context. It is through it that the
                // night finds again the form filled in several hours before it existed: the
                // `sessionHex` was not known at the moment of sealing, and it therefore cannot
                // serve as the attachment.
                //
                // The time zone is the one **announced by the watch**, not the phone's. The two
                // are normally identical; when they are not — a flight during the day — it is the
                // zone in which the night was lived that defines the evening.
                // The time zone comes from the watch and it is resolved **here**, on the phone:
                // the two devices do not necessarily have the same tzdb, and an identifier that
                // the phone's does not know throws. Without this fallback, the exception went up
                // to the generic `catch` of `onDataChanged`, the `night_session` row was never
                // created, and since the chunks carry a `CASCADE` foreign key onto it, each of
                // them violated the constraint in turn: the whole night disappeared without a
                // word. `offsetAt`, fifteen lines further down, already protected itself — this
                // one did not.
                nightKey = WirePaths.nightKey(
                    h.startWallMs,
                    runCatching { java.time.ZoneId.of(h.zoneId) }
                        .getOrElse {
                            Log.w(TAG, "time zone unknown to the phone: ${h.zoneId}", it)
                            java.time.ZoneId.systemDefault()
                        },
                ),
                startWallMs = h.startWallMs,
                plannedStopWallMs = h.plannedStopWallMs,
                zoneId = h.zoneId,
                tzOffsetStartMin = h.tzOffsetMin,
                // At opening, the end offset is the one of the start. It is corrected only at
                // closing: it is their difference that detects a daylight-saving night.
                tzOffsetEndMin = h.tzOffsetMin,
                nominalRateHz = h.nominalRateHz,
                modeFlags = h.modeFlags,
                state = h.state.name,
            )
        )

        if (h.state == SessionState.CLOSED) {
            dao.markClosed(
                hex = h.sessionHex,
                state = h.state.name,
                endWallMs = h.endWallMs,
                totalChunks = h.totalChunks,
                stopReason = h.stopReason?.name,
                tzOffsetEndMin = offsetAt(h),
            )
            // The watch has finished sending: this is the moment to launch the full chain.
            WorkScheduler.enqueueNightChain(applicationContext, h.sessionHex)
        }
    }

    /**
     * Local UTC offset **at the end** of the night.
     *
     * It is recomputed here from `zoneId` and `endWallMs` rather than taken back from the item:
     * the watch publishes the offset it had at opening, and a daylight-saving night is exactly the
     * one where the two differ. Taking the same one on both sides would make the criterion
     * `tzOffsetStartMin <> tzOffsetEndMin` structurally always false — a guard rail that never
     * fires is worse than no guard rail, because it reassures.
     */
    private fun offsetAt(h: SessionHeader): Int {
        val end = h.endWallMs ?: return h.tzOffsetMin
        return runCatching {
            java.time.ZoneId.of(h.zoneId)
                .rules
                .getOffset(java.time.Instant.ofEpochMilli(end))
                .totalSeconds / 60
        }.getOrDefault(h.tzOffsetMin)
    }

    // ------------------------------------------------------------------
    // /pendulum/chunk
    // ------------------------------------------------------------------

    /** @return the index of the chunk to resend if the verification failed, `null` otherwise. */
    private fun onChunk(sessionHexFromPath: String, payload: ByteArray): Int? = runBlocking {
        val (meta, bytes) = ChunkEnvelope.decode(payload)
        when (val verdict = ChunkVerifier.verify(sessionHexFromPath, meta, bytes)) {
            ChunkVerifier.Verdict.OK -> Unit
            else -> {
                Log.w(TAG, "chunk ${meta.idx} refused: $verdict")
                // The resend request is not recorded in the database: it is re-derived from the
                // absence of the row. A persistent "to be resent" state would be a third state to
                // reconcile, whereas "present or absent" is enough.
                return@runBlocking meta.idx
            }
        }

        store.write(meta.sessionHex, meta.idx, bytes)

        // The file is read back to find out whether it is *complete*: the end marker is the only
        // thing that tells a closed chunk from a chunk still being written, and a chunk that is
        // not complete must never be acknowledged.
        //
        // The telemetry is harvested **in the same pass**. The reader was already walking through
        // the `TLM!` blocks to check their CRC and was discarding the points; collecting them here
        // does not cost a second read of 90 KB, and a second pass would in any case be an occasion
        // to diverge — a chunk judged complete by the first and unreadable by the second.
        val telemetry = ArrayList<com.pendulum.format.TelemetryPoint>()
        val complete = store.fileFor(meta.sessionHex, meta.idx).inputStream().buffered().use {
            com.pendulum.format.ChunkReader
                .forEachBlock(it, onTelemetry = { point -> telemetry += point }) { }
                .complete
        }

        // Written **before** the chunk row, and hence before any acknowledgement: the
        // acknowledgement makes the file be deleted on the watch, and it is that file which
        // carries the points. The order of the protocol is the same as for the signal — nothing is
        // acknowledged before it is in the database.
        //
        // A v1 chunk of the format carries no `TLM!` block: the list is empty, the insertion is a
        // no-op, and the night will have no metrology band. That is the truth, not a failure.
        if (telemetry.isNotEmpty()) {
            db.telemetryDao().insertAllIfAbsent(
                TelemetryAdapter.toEntities(meta.sessionHex, telemetry)
            )
        }

        db.chunkDao().insertIfAbsent(
            ChunkEntity(
                sessionHex = meta.sessionHex,
                idx = meta.idx,
                path = store.fileFor(meta.sessionHex, meta.idx).absolutePath,
                size = meta.size,
                crc32 = meta.crc32,
                sampleCount = meta.sampleCount,
                tFirstNs = meta.tFirstNs,
                tLastNs = meta.tLastNs,
                flagsOr = meta.flagsOr,
                complete = complete,
                receivedAtMs = System.currentTimeMillis(),
            )
        )
        db.nightDao().touchChunkArrival(meta.sessionHex, System.currentTimeMillis())
        null
    }

    // ------------------------------------------------------------------
    // /pendulum/live
    // ------------------------------------------------------------------

    /**
     * The preview is **never** stored nor used for a computation: it is a display state, replaced
     * at every burst. The only thing kept from it is the battery percentage, because it makes it
     * possible to report a probable cause of interruption ("last reading at 6 %" -> battery)
     * instead of guessing it.
     */
    private fun onLive(payload: ByteArray) = runBlocking {
        val live = LivePreview.decode(payload)
        db.nightDao().setBattery(live.sessionHex, live.batteryPct)
    }

    // ------------------------------------------------------------------
    // /pendulum/ack
    // ------------------------------------------------------------------

    /**
     * The acknowledgement is recomputed **entirely from the database**, every single time. That is
     * more expensive than an incremental counter and that is the point: the cost is one query over
     * a few dozen rows, the benefit is that no in-memory state can diverge from the truth.
     */
    private fun publishAck(sessionHex: String, needResend: List<Int>) = runBlocking {
        val complete = db.chunkDao().completeIndices(sessionHex)
        val ack = AckBuilder.build(sessionHex, complete, needResend, System.currentTimeMillis())
        val request = PutDataRequest.create(WirePaths.ack(sessionHex))
            .setData(ack.encode())
            // Without `setUrgent()`, the system may delay the synchronisation by 30 minutes.
            // The watch keeps its files until the acknowledgement: delaying it means saturating
            // its disk and its ceiling of items in flight for nothing.
            .setUrgent()
        Tasks.await(Wearable.getDataClient(this@PendulumListenerService).putDataItem(request))
    }

    private fun sessionHexOfChunkPath(path: String): String? {
        val rest = path.removePrefix(WirePaths.CHUNK_PREFIX)
        val slash = rest.indexOf('/')
        return if (slash <= 0) null else rest.substring(0, slash)
    }

    private companion object {
        const val TAG = "PendulumIngest"

        /**
         * Three, because a transport failure resolves itself in one or two attempts and beyond
         * that it is the file itself that is at fault. Asking again endlessly would cost the
         * night's battery for a chunk that will never be good.
         */
        const val MAX_RESEND_REQUESTS = 3
    }
}
