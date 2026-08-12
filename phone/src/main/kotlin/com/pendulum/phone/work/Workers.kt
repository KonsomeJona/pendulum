package com.pendulum.phone.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.data.ContextPublication
import com.pendulum.phone.db.ChunkEntity
import com.pendulum.phone.db.HcSnapshotEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.health.Hypnogram
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.ingest.SessionReassembler
import com.pendulum.format.ChunkReader
import com.pendulum.phone.time.Durations
import java.time.ZoneId

internal const val KEY_SESSION = "sessionHex"

/**
 * Marks a Health Connect read triggered **outside the ladder** — charger plugged in, return to the
 * foreground. It reads at once, reschedules nothing, and does not consume the ladder.
 */
internal const val KEY_OPPORTUNISTIC = "opportuniste"

/** The evening targeted by a context republication, in the format of `WirePaths.nightKey`. */
internal const val KEY_NIGHT_KEY = "cleDeNuit"

/**
 * The instant of the sealing, which **is** the payload of the context item.
 *
 * It travels in the input data of the worker and is never re-read from the database: that is what
 * makes the replay strictly identical to the online attempt, hence deduplicable by the Data Layer.
 */
internal const val KEY_SEALED_AT = "scelleAMs"

private const val TAG = "PendulumWork"

/**
 * Disk <-> database reconciliation, at the head of the chain.
 *
 * The receiving service writes the file **then** the row. Between the two, the process can die:
 * Google Play Services starts and kills this service freely, and a phone under memory pressure
 * during the night is nothing exotic. What remains is a valid file whose existence the database
 * knows nothing about — hence a chunk never acknowledged, which the watch keeps indefinitely, and
 * which is missing from the reassembly.
 *
 * This worker re-reads the directory, re-inserts what is missing (`INSERT OR IGNORE`, so without
 * risk), and brings the database back into agreement with the disk. It never deletes anything: an
 * unknown file is data to recover, not waste.
 */
class IngestWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val db = PendulumDatabase.get(applicationContext)
        val store = ChunkStore(applicationContext)

        var recovered = 0
        for (file in store.listChunkFiles(hex)) {
            val idx = SessionReassembler.indexOf(file) ?: continue
            if (db.chunkDao().find(hex, idx) != null) continue

            val bytes = file.readBytes()
            val scan = file.inputStream().buffered().use { ChunkReader.forEachBlock(it) { } }
            db.chunkDao().insertIfAbsent(
                ChunkEntity(
                    sessionHex = hex,
                    idx = idx,
                    path = file.absolutePath,
                    size = bytes.size,
                    crc32 = ChunkStore.crc32(bytes),
                    sampleCount = scan.decodedSampleCount.toInt(),
                    tFirstNs = 0L,
                    tLastNs = 0L,
                    flagsOr = 0,
                    complete = scan.complete,
                    receivedAtMs = file.lastModified(),
                )
            )
            recovered++
        }
        if (recovered > 0) Log.i(TAG, "$hex: $recovered chunk(s) recovered from disk")
        return Result.success(workDataOf(KEY_SESSION to hex))
    }
}

/**
 * The analysis on waking. **It does not depend on Health Connect**: it runs with the accelerometer
 * mask, immediately.
 *
 * This is the decision that makes the rest bearable. The hypnogram arrives when the provider
 * decides — sometimes eight hours later — and making the analysis wait would produce an
 * application that, on waking, has nothing to say. The accelerometer mask is allowed to exist and
 * to be displayed; it is not allowed to carry the main result, because the denominator there is
 * derived from the same signal as the numerator. `RescoreWorker` adds the second, non-circular
 * arm, as soon as the hypnogram is there.
 */
class AnalyzeWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val params = WorkScheduler.activeParams(applicationContext)
        return try {
            val ok = AnalysisRunner.analyse(applicationContext, hex, params)
            if (ok) Result.success(workDataOf(KEY_SESSION to hex)) else Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "analysis of $hex failed", t)
            // `retry` and not `failure`: an analysis that fails on a lack of memory may well
            // succeed when the phone is quiet. The chunks, for their part, are there.
            Result.retry()
        }
    }
}

/**
 * The Health Connect read, with its retry ladder ([FetchSchedule]).
 *
 * **No `requiresCharging` constraint.** It is an identified trap: combined with a phone that is not
 * systematically recharged in the morning, it produces a worker that never runs and a night that
 * never gets its denominator — without the slightest error message. The read costs one query to a
 * local provider; it deserves no constraint.
 */
class SleepFetchWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val db = PendulumDatabase.get(applicationContext)
        val session = db.nightDao().find(hex) ?: return Result.success()

        val endMs = session.endWallMs ?: session.plannedStopWallMs
        val attempts = db.hcSnapshotDao().attemptCount(hex)
        val now = System.currentTimeMillis()
        val opportunistic = inputData.getBoolean(KEY_OPPORTUNISTIC, false)

        if (opportunistic) {
            // Outside the ladder: we read now if the window is open and if the last read is not too
            // close, and we reschedule nothing. The ladder carries on separately, driven by its
            // own rungs — the two paths do not tread on each other because the attempt count
            // ignores the opportunistic rows.
            if (!FetchSchedule.opportunisticAllowed(endMs, now, db.hcSnapshotDao().latest(hex)?.fetchedAtMs)) {
                return Result.success()
            }
        } else {
            when (val plan = FetchSchedule.plan(attempts, endMs, now)) {
                is FetchSchedule.Plan.GiveUp -> {
                    Log.i(TAG, "$hex: giving up on the sleep read (${plan.reason})")
                    return Result.success()
                }
                is FetchSchedule.Plan.Retry -> {
                    if (plan.delayMs > 0) {
                        // Not time yet: we reschedule ourselves and hand back. Waiting inside the
                        // worker would hold a `wakelock` for hours doing nothing.
                        WorkScheduler.scheduleSleepFetch(applicationContext, hex, plan.delayMs)
                        return Result.success()
                    }
                }
            }
        }

        val loggedRung = if (opportunistic) FetchSchedule.OPPORTUNISTIC_INDEX else attempts

        val reader = SleepReader(applicationContext)
        val availability = reader.availability()
        if (availability != SleepReader.Availability.READY) {
            Log.i(TAG, "$hex: Health Connect unavailable ($availability)")
            // We log the attempt anyway: without a row, the ladder does not advance and we would
            // retry indefinitely at the same rung.
            appendSnapshot(db, hex, loggedRung, null, availability.name)
            if (!opportunistic) {
                WorkScheduler.scheduleNextSleepFetch(applicationContext, hex, endMs, attempts + 1)
            }
            return Result.success()
        }

        val previous = db.hcSnapshotDao().latest(hex)
        val reading = reader.read(
            windowStartMs = session.startWallMs,
            windowEndMs = endMs,
            // TODO(preferred-source): this `null` makes the selection fall back on the heuristic of
            // `SleepSourceSelector` (coverage, then number of stages). The settings screen displays
            // a preferred source, but nothing persists it yet — there is no DataStore in the
            // module. As long as that path does not exist, the setting is decorative, and saying so
            // here is better than letting it be believed that it is honoured.
            preferredPackage = null,
        )
        appendSnapshot(db, hex, loggedRung, reading, reading?.verdict ?: "READ_FAILED")

        val chosen = reading?.selection?.chosen
        val rescore = FetchSchedule.shouldRescore(
            previousRecordId = previous?.selectedRecordId,
            previousLastModifiedMs = previous?.lastModifiedTimeMs,
            previousStageCount = previous?.stageCount ?: 0,
            currentRecordId = chosen?.recordId,
            currentLastModifiedMs = chosen?.lastModifiedMs,
            currentStageCount = chosen?.stages?.size ?: 0,
        )

        // We carry on with the ladder **even after a success**: a provider can rewrite an already
        // published session, and the night read at T+1 h differ from the same night at T+8 h. An
        // opportunistic read, for its part, reschedules nothing: it adds itself to the ladder
        // without moving it, otherwise plugging in a charger would push back the next rung.
        if (!opportunistic) {
            WorkScheduler.scheduleNextSleepFetch(applicationContext, hex, endMs, attempts + 1)
        }

        if (rescore) WorkScheduler.enqueueRescore(applicationContext, hex)
        return Result.success(workDataOf(KEY_SESSION to hex))
    }

    private suspend fun appendSnapshot(
        db: PendulumDatabase,
        hex: String,
        attemptIndex: Int,
        reading: SleepReader.Reading?,
        outcome: String,
    ) {
        val chosen = reading?.selection?.chosen
        db.hcSnapshotDao().append(
            HcSnapshotEntity(
                sessionHex = hex,
                fetchedAtMs = System.currentTimeMillis(),
                attemptIndex = attemptIndex,
                selectedPackage = chosen?.packageName,
                selectedRecordId = chosen?.recordId,
                lastModifiedTimeMs = chosen?.lastModifiedMs,
                sessionStartMs = chosen?.startMs,
                sessionEndMs = chosen?.endMs,
                stageCount = chosen?.stages?.size ?: 0,
                distinctStageTypes = chosen?.distinctStageTypes ?: 0,
                stageCoverageMin = (chosen?.stageCoverageMs ?: 0L) / 60_000.0,
                overlapFraction = 0.0,
                aggregateTstMin = reading?.aggregateTstMin,
                originCount = reading?.allCandidates?.map { it.packageName }?.distinct()?.size ?: 0,
                selectedStagesCsv = chosen?.let { Hypnogram.encodeCsv(it.stages) }.orEmpty(),
                recordsJson = traceOf(reading),
                outcome = outcome,
            )
        )
    }

    /**
     * Readable trace of **everything** Health Connect returned, including the excluded sessions.
     * Never reparsed (see the KDoc of `hc_snapshot.selectedStagesCsv`): it exists so that an
     * abnormal night is explainable six months later.
     */
    private fun traceOf(reading: SleepReader.Reading?): String {
        if (reading == null) return "{}"
        return buildString {
            append("""{"candidates":[""")
            reading.allCandidates.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append(
                    """{"package":"${c.packageName}","id":"${c.recordId}","start":${c.startMs},""" +
                        """"end":${c.endMs},"modified":${c.lastModifiedMs},"stages":${c.stages.size},""" +
                        """"distinctTypes":${c.distinctStageTypes}}"""
                )
            }
            append("""],"chosen":"${reading.selection.chosen?.recordId ?: ""}",""")
            append(""""reason":"${reading.selection.reason}","verdict":"${reading.verdict}"}""")
        }
    }
}

/**
 * The rescore of a night: exactly the same analysis, with the hypnogram now available.
 *
 * It shares all its code with [AnalyzeWorker] and that is intended — two different computation
 * paths for the same night would end up diverging, and the gap would be attributed to sleep.
 */
class RescoreWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val params = WorkScheduler.activeParams(applicationContext)
        return try {
            AnalysisRunner.analyse(applicationContext, hex, params)
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "rescore of $hex failed", t)
            Result.retry()
        }
    }
}

/**
 * The rescore of **every** night, from the raw data.
 *
 * Triggered by any parameter change. It is the executive half of guard rail 3: the trend refuses
 * to mix two `paramsHash`, so changing a parameter without recomputing everything would empty the
 * trend of all its earlier points. Recomputing is the only answer that preserves the campaign.
 *
 * The nights are processed from the oldest to the most recent, because the gain reference used as
 * baseline is that of the first night: recomputing it last would have all the others analysed with
 * an out-of-date reference.
 */
class RescoreAllWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val db = PendulumDatabase.get(applicationContext)
        val params = WorkScheduler.activeParams(applicationContext)
        var failures = 0
        for (hex in db.nightDao().allHexOldestFirst()) {
            try {
                AnalysisRunner.analyse(applicationContext, hex, params)
            } catch (t: Throwable) {
                failures++
                Log.e(TAG, "global rescore: $hex failed", t)
            }
        }
        // A partial failure leaves two hashes in the database. `TrendDao.distinctHashes()` makes it
        // visible, and the interface must say so rather than draw a truncated trend.
        return if (failures == 0) Result.success() else Result.retry()
    }
}

/**
 * The watchdog for sessions left open.
 *
 * The watch can die without ever publishing a close: undetected flat battery, system kill, strap
 * torn off. The `/pendulum/session` item then stays `OPEN` for ever, and without this worker the
 * night would never be analysed — not because it is unusable, but because nobody says it is over.
 *
 * Two tiers, and the second triggers the analysis:
 *  - more than 45 min with no new chunk -> `STALE`. The watch has fallen silent; it may come back.
 *  - more than 14 h since the start -> `TRUNCATED`, **and we analyse what we have**.
 *
 * It is never a final state: if chunks arrive after the fact (watch recharged), the chain runs
 * again and rescoring gives the same result as if everything had arrived on time.
 */
class WatchdogWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val db = PendulumDatabase.get(applicationContext)
        val now = System.currentTimeMillis()

        for (s in db.nightDao().openOrStale()) {
            when (nextState(s.state, s.startWallMs, s.lastChunkArrivalMs, now)) {
                "TRUNCATED" -> {
                    db.nightDao().setState(s.sessionHex, "TRUNCATED")
                    WorkScheduler.enqueueNightChain(applicationContext, s.sessionHex)
                }
                "STALE" -> db.nightDao().setState(s.sessionHex, "STALE")
                else -> Unit
            }
        }
        return Result.success()
    }

    companion object {
        val STALE_MS = Durations.ACTIVE.silenceBeforeStaleMs
        val MAX_NIGHT_MS = Durations.ACTIVE.maxNightAgeMs

        /**
         * The two tiers, isolated from the database and from the clock. **Pure**, therefore
         * testable.
         *
         * The order of the two branches is the contract: `TRUNCATED` wins over `STALE`, because a
         * night of more than fourteen hours must be analysed even if chunks keep arriving. Writing
         * it in a `when` deep inside a coroutine that reads `System.currentTimeMillis()` made that
         * priority impossible to demonstrate other than by re-reading the code.
         *
         * @return the new state, or `null` if nothing changes.
         */
        fun nextState(
            state: String,
            startWallMs: Long,
            lastChunkArrivalMs: Long,
            nowMs: Long,
            staleMs: Long = STALE_MS,
            maxAgeMs: Long = MAX_NIGHT_MS,
        ): String? = when {
            nowMs - startWallMs > maxAgeMs -> "TRUNCATED"
            state == "OPEN" && lastChunkArrivalMs > 0 && nowMs - lastChunkArrivalMs > staleMs -> "STALE"
            else -> null
        }
    }
}

/**
 * The republication of the evening context — the outbox of the only gate of the product.
 *
 * ### What it repairs
 *
 * `seal()` writes the context into the database, **irreversibly**, then puts the item that
 * `Preflight` waits for. The put fails when Google Play services are unavailable, and it does not
 * fail when the watch is switched off or out of range: `putDataItem` writes into the local
 * replicated store, the synchronisation comes afterwards and on its own. So there is nothing to
 * hook onto the reconnection of the watch — that is not the failure mode.
 *
 * The real failure mode is this one: one second of unavailability, a context sealed and immutable
 * in the database, an item that has entered nowhere, and a watch that refuses to record that night
 * for ever (`IssueId.CONTEXT_NOT_SEALED` is a hard blocker). No user recourse: the database
 * refuses to seal twice.
 *
 * ### Why WorkManager rather than a table
 *
 * The queue **is** the outbox: it survives a phone restart and already carries the exponential
 * backoff. A Room outbox table would be impossible here anyway — the context table is made
 * immutable by two SQLite triggers, so a publication state cannot be marked in it.
 *
 * Putting again is riskless and costless: a `putDataItem` with an identical payload is
 * deduplicated by the Data Layer. It is the same property `AckBuilder` relies on.
 */
class ContextPublicationWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val nightKey = inputData.getString(KEY_NIGHT_KEY) ?: return Result.failure()
        val sealedAt = inputData.getLong(KEY_SEALED_AT, 0L)
        return outcomeOf(nightKey, System.currentTimeMillis()) {
            ContextPublication.put(applicationContext, nightKey, sealedAt)
        }
    }

    companion object {

        /**
         * The outcome of a republication attempt, clock and Data Layer as parameters.
         *
         * ### The stopping guard rail
         *
         * A past night cannot be caught up. As long as the targeted evening **is** the current
         * evening, putting the item again makes sense: the watch is waiting, and it actively
         * queries the store. As soon as the night key has rolled over, the item would no longer
         * unblock anything — the watch asks for the one of the current evening — and a worker that
         * keeps retrying does nothing but consume battery while promising a catch-up that will not
         * happen.
         *
         * `success` and not `failure` for this giving up, like `FetchSchedule.GiveUp`: the work has
         * done what it had to do, it stops because its object has disappeared, and marking it as a
         * failure would raise an alarm where there is nothing to be alarmed about.
         *
         * @param targetKey the evening for which the context was sealed.
         * @param nowMs the clock as a parameter: it is what decides between putting again and
         *   giving up, and reading it from deep inside the function made that branch untestable.
         * @param zone explicit time zone, for the same reason as in `WirePaths.nightKey`: the noon
         *   rollover is the heart of this guard rail, an implicit zone would make it depend on the
         *   machine running the test.
         * @param put the put of the item, `true` if it succeeded.
         */
        fun outcomeOf(
            targetKey: String,
            nowMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            put: () -> Boolean,
        ): ListenableWorker.Result {
            if (targetKey != WirePaths.nightKey(nowMs, zone)) {
                Log.i(TAG, "republication of $targetKey abandoned: the evening has passed")
                return ListenableWorker.Result.success()
            }
            // `retry` and not `failure`: the exponential backoff is the whole point of the queue,
            // and `failure` would remove the work on the first failure — that is to say exactly the
            // defect we are repairing.
            return if (put()) ListenableWorker.Result.success() else ListenableWorker.Result.retry()
        }
    }
}
