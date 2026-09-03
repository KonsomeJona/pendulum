package com.pendulum.phone.ingest

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
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
            runCatching {
                runBlocking { AckPublisher.publish(this@PendulumListenerService, db, hex, toResend.sorted()) }
            }.onFailure { Log.w(TAG, "acknowledgement not published for $hex", it) }
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

        // The chunks that arrived **before** this header. The Data Layer orders nothing between
        // distinct items, and the morning resynchronisation of a phone that was off all night
        // delivers the chunks and the header in whatever order it likes; `onChunk` keeps such a
        // chunk on disk and inserts nothing, because its row is the child of a cascading foreign
        // key onto the row this method has just created. Now that the row exists, those files get
        // their rows and their telemetry, and the acknowledgement that frees their slots on the
        // watch. Without this, they waited for `IngestWorker` at the close — a night that started
        // this way kept its first 24 chunks in flight, unacknowledged, until morning, and the
        // watch, at its ceiling, sent nothing else: two hours shown out of eight.
        //
        // Done on every header, not only on the one that created the row: it costs one directory
        // listing and one query per file when there is nothing to do, and a state that converges
        // on every arrival is easier to reason about than one that depends on which item came
        // first.
        if (ChunkIngestor.reconcileDisk(db, store, h.sessionHex) > 0) {
            runCatching { AckPublisher.publish(this@PendulumListenerService, db, h.sessionHex) }
                .onFailure { Log.w(TAG, "acknowledgement not published for ${h.sessionHex}", it) }
        }

        if (h.state == SessionState.CLOSED) {
            dao.markClosed(
                hex = h.sessionHex,
                state = h.state.name,
                endWallMs = h.endWallMs,
                totalChunks = h.totalChunks,
                stopReason = h.stopReason?.name,
                tzOffsetEndMin = offsetAt(h),
            )
            // The watch has finished sending — or at least announced the end: the CLOSED item
            // may overtake the last burst, and a phone that was off all night receives it with
            // twenty-four chunks while the rest follow. The chain runs now anyway, so that the
            // waking screen has something to say; the chunk that completes the series relaunches
            // the analysis from `onChunk` (`WorkScheduler.enqueueLateRescore`).
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

        // The chunk row and the telemetry rows are children of a cascading foreign key onto
        // `night_session`, and `INSERT OR IGNORE` does not cover foreign keys — SQLite's conflict
        // clause applies to UNIQUE, NOT NULL, CHECK and PRIMARY KEY, nothing else. Until
        // 4 September 2026 a chunk landing before its session item — the Data Layer orders
        // nothing between distinct items, and the morning resynchronisation of a phone that was
        // off all night delivers chunks and header in whatever order it likes — was written to
        // disk above, then thrown out of this method by `SQLiteConstraintException` at the first
        // insert, swallowed by the `catch` of `onDataChanged` as "item ignored", and acknowledged
        // as nothing. The item stayed in the store, so the watch never re-put it; the file stayed
        // on disk, so nothing re-read it before `IngestWorker` at the close — which never
        // acknowledged either. Each such chunk held one of the watch's 24 in-flight slots until
        // morning: a night that started this way showed its first two hours and nothing after.
        //
        // The file is kept — the bytes were verified, they are the night — and no row is
        // attempted: `onSession` reconciles the directory against the database the moment the
        // header lands, and publishes the acknowledgement that frees the slots. No resend is
        // asked for either: the watch would re-put bytes the phone already holds, and the phone
        // would refuse them again for the same reason.
        val night = db.nightDao().find(meta.sessionHex)
        if (night == null) {
            Log.w(TAG, "chunk ${meta.idx} of ${meta.sessionHex} kept on disk: session not announced yet")
            return@runBlocking null
        }

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

        val inserted = db.chunkDao().insertIfAbsent(
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
        ) != -1L
        db.nightDao().touchChunkArrival(meta.sessionHex, System.currentTimeMillis())

        // A chunk can arrive **after** the night has been closed and scored, and nothing brought
        // the analysis back to it. `finalizeSession` on the watch puts the CLOSED session item
        // (urgent) and then the final burst with only its last item urgent; the Data Layer
        // promises no order between distinct items and may hold a non-urgent one back for up to
        // 30 minutes. And when the phone was off all night, the ceiling of 24 items in flight
        // means the CLOSED item lands with two hours of chunks while six more hours follow at the
        // pace of the acknowledgements. In both cases the chain launched by the CLOSED item scored
        // the night with `received < declared`, hence `closedCleanly = false`, hence a night shown
        // as truncated and kept out of the trend — and the late chunks were inserted and
        // acknowledged into a night nobody re-read. `RescoreWorker` only re-runs when a hypnogram
        // changes, so on a phone without Health Connect the truncation was permanent; the
        // watchdog only looks at OPEN and STALE nights.
        //
        // The analysis is relaunched from here exactly once: at the arrival that completes the
        // announced series. Not before the series is complete, so that a backlog of seventy chunks
        // does not enqueue seventy analyses; not on a duplicate, so that a re-put of a chunk
        // already held does not enqueue one either. And **whether or not the night is scored
        // yet**: until 4 September 2026 the relaunch went through `enqueueNightChain` and its
        // `KEEP`, so it waited for `analyzedAtMs` — and the chunk that landed after `AnalyzeWorker`
        // had listed the files but before it wrote `analyzedAtMs` was neither seen by that
        // analysis nor relaunching one. A few seconds wide, and the night it fell in was
        // truncated for good. `enqueueLateRescore` uses `APPEND_OR_REPLACE`: appended after the
        // chain in flight if there is one, run at once otherwise, never dropped.
        if (completesDeclaredSeries(
                inserted = inserted,
                declaredChunks = night.totalChunks,
                completeChunks = db.chunkDao().completeIndices(meta.sessionHex).size,
            )
        ) {
            Log.i(TAG, "series of ${meta.sessionHex} complete, analysis relaunched")
            WorkScheduler.enqueueLateRescore(applicationContext, meta.sessionHex)
        }
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
    //
    // The acknowledgement is recomputed **entirely from the database**, every single time, by
    // `AckPublisher`. It lived here as a private method until 4 September 2026, which made this
    // service its only caller — and a row that came into the database by any other road
    // (`IngestWorker`, the reconciliation in `onSession`) was never acknowledged.

    private fun sessionHexOfChunkPath(path: String): String? {
        val rest = path.removePrefix(WirePaths.CHUNK_PREFIX)
        val slash = rest.indexOf('/')
        return if (slash <= 0) null else rest.substring(0, slash)
    }

    internal companion object {
        const val TAG = "PendulumIngest"

        /**
         * Whether the chunk that just arrived is the one that completes the series the watch
         * announced — the one arrival after which the analysis must run again.
         *
         * **Pure**, taken out of the `runBlocking` of `onChunk` that reads the database, for the
         * same reason as `WatchdogWorker.nextState`: the three conditions are the contract, and
         * each one left out has a cost that a test can name. Without `inserted`, a re-put of a
         * chunk the phone already holds would relaunch the analysis every time. Without
         * `declaredChunks`, a night the watch never closed would relaunch on every arrival — that
         * night belongs to the watchdog. Without the count reaching the declared total, every
         * chunk of a backlog would enqueue an analysis.
         *
         * There is deliberately **no** `analyzedAtMs` condition any more. It was there because the
         * relaunch went through `enqueueNightChain`, whose `KEEP` drops a request while the chain
         * launched by the CLOSED item is enqueued or running — and it left the chunk that landed
         * while that chain was between listing the files and writing `analyzedAtMs` neither
         * analysed nor relaunching. `WorkScheduler.enqueueLateRescore` appends instead of
         * dropping, so the completing chunk relaunches whatever the state of the night.
         *
         * @param inserted the arrival created a row — `false` for a duplicate.
         * @param declaredChunks `night_session.totalChunks`, null until the watch closes the night.
         * @param completeChunks number of complete chunk rows of the session, this arrival
         *   included.
         */
        fun completesDeclaredSeries(
            inserted: Boolean,
            declaredChunks: Int?,
            completeChunks: Int,
        ): Boolean =
            inserted && declaredChunks != null && completeChunks >= declaredChunks

        /**
         * Three, because a transport failure resolves itself in one or two attempts and beyond
         * that it is the file itself that is at fault. Asking again endlessly would cost the
         * night's battery for a chunk that will never be good.
         */
        const val MAX_RESEND_REQUESTS = 3
    }
}
