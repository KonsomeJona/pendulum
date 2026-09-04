package com.pendulum.phone.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.data.ContextPublication
import com.pendulum.phone.data.PendulumPreferences
import com.pendulum.phone.db.HcSnapshotEntity
import com.pendulum.phone.db.ParamProfileEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.health.Hypnogram
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.ingest.AckPublisher
import com.pendulum.phone.ingest.ChunkIngestor
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.time.Durations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

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
 *
 * ### Two things it did not do until 4 September 2026
 *
 * **It rebuilt a degraded row.** The row was inserted with `tFirstNs = 0`, `tLastNs = 0`,
 * `flagsOr = 0` and none of the file's telemetry — the fields the service takes from the watch's
 * `ChunkMeta`, which the worker no longer has. `PendulumRepository` takes the origin of the sensor
 * time base from the smallest `tFirstNs` of the night, so one recovered chunk put that origin at
 * zero and the metrology band of the whole night off the axis. The row is now rebuilt by
 * `ChunkIngestor.scan` from the bytes, which is where the watch computed those fields in the first
 * place: same sums, same extrema, same CRC.
 *
 * **It never acknowledged.** The rows it recovered were exactly the ones the service had not
 * acknowledged, and the acknowledgement had a single caller — the service, at the end of a burst
 * of chunk events. So a file recovered here stayed on the watch: counted as pending every
 * evening, its item holding one of the 24 in-flight slots for the rest of the session, and the
 * phone had no way to ask for the acknowledgement to be re-read. The acknowledgement is now put
 * from here, **always**, not only when something was recovered: it is recomputed from the
 * `complete` rows, so it costs one query and one deduplicated put, and it also covers the row the
 * service committed just before dying between its insert and its own put.
 */
class IngestWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val db = PendulumDatabase.get(applicationContext)
        val store = ChunkStore(applicationContext)

        // The chunk row is the child of a cascading foreign key onto `night_session`, and
        // `INSERT OR IGNORE` does not cover foreign keys: without the parent row the insert
        // throws, and a worker that throws fails the whole chain. Nothing enqueues this chain
        // without a session row today; the guard is for the erasure that removes it while the
        // chain is already queued.
        if (db.nightDao().find(hex) == null) {
            Log.w(TAG, "$hex: no session row, nothing to reconcile against")
            return Result.success(workDataOf(KEY_SESSION to hex))
        }

        ChunkIngestor.reconcileDisk(db, store, hex)

        // A failure here is logged, not retried: the analysis behind this worker must run on what
        // the disk holds whatever the state of the link, and the next event of the session — or
        // the next run of this worker — puts the acknowledgement again.
        try {
            AckPublisher.publish(applicationContext, db, hex)
        } catch (e: Exception) {
            Log.w(TAG, "$hex: acknowledgement not published from the reconciliation", e)
        }
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

        val previous = db.hcSnapshotDao().latest(hex)
        val reader = SleepReader(applicationContext)
        // The source the user chose in the settings, handed to the read. This was `null` under a
        // TODO saying the DataStore did not exist; `PendulumPreferences` has existed since, the
        // settings screen writes into it, and the read still ignored it — so the selection fell
        // back on the coverage heuristic every time, and a user with two sleep applications got
        // whichever one covered the night better, night by night: a denominator that could change
        // source between two nights, which is precisely what the preference exists to prevent. And
        // the screens labelled the night with the source the user had ticked, whichever one had
        // actually been read; that label now comes from `sleep_window.sourcePackage`, written by
        // the analysis from the snapshot this read appends. `SleepSourceSelector` still falls back
        // on the heuristic when the preferred source published nothing usable for the night.
        val preferredPackage = PendulumPreferences(applicationContext).preferredSleepSourceNow()
        val attempt = attempt({ reader.availability() }) {
            reader.read(
                windowStartMs = session.startWallMs,
                windowEndMs = endMs,
                preferredPackage = preferredPackage,
            )
        }
        if (attempt !is Attempt.Read) {
            if (attempt is Attempt.Threw) {
                Log.w(TAG, "$hex: the Health Connect read threw", attempt.error)
            } else {
                Log.i(TAG, "$hex: Health Connect unavailable (${attempt.outcome})")
            }
            // We log the attempt anyway: without a row, the ladder does not advance and we would
            // retry indefinitely at the same rung.
            appendSnapshot(db, hex, loggedRung, null, attempt.outcome)
            if (!opportunistic) {
                WorkScheduler.scheduleNextSleepFetch(applicationContext, hex, endMs, attempts + 1)
            }
            return Result.success()
        }
        val reading = attempt.reading
        appendSnapshot(db, hex, loggedRung, reading, attempt.outcome)

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

    /**
     * What one rung of the ladder got out of Health Connect. Three shapes, and the row written in
     * `hc_snapshot` — hence the advance of the ladder — is the same for all three.
     */
    sealed interface Attempt {

        /** What `hc_snapshot.outcome` records for this attempt. */
        val outcome: String

        /** The provider said it could not be read; the reason is its own name. */
        data class Unavailable(val availability: SleepReader.Availability) : Attempt {
            override val outcome: String get() = availability.name
        }

        /** The read went through — possibly with nothing in it, which is `READ_FAILED`. */
        data class Read(val reading: SleepReader.Reading?) : Attempt {
            override val outcome: String get() = reading?.verdict ?: "READ_FAILED"
        }

        /** The provider threw. The class name is enough to tell a dead binder from a bug of ours. */
        data class Threw(val error: Throwable) : Attempt {
            override val outcome: String get() = "EXCEPTION:${error.javaClass.simpleName}"
        }
    }

    companion object {

        /**
         * One guarded attempt at Health Connect, the reader as two lambdas.
         *
         * ### Why an exception is an attempt and not a crash
         *
         * `availability()` calls `getGrantedPermissions()` and `read()` calls `readRecords()`, both
         * over the Health Connect binder, and the binder throws — `RemoteException`,
         * `IOException`, `IllegalStateException` — while Play is updating the provider, on the
         * first bind after a boot, or when the provider's process has just been killed. Until
         * 4 September 2026 neither call was guarded. A `CoroutineWorker` that throws is marked
         * `FAILED`; the `hc_snapshot` row and the scheduling of the next rung both came *after*
         * the read, so the exception left no row and no next rung: the unique work of the night
         * was simply gone. A night closed at 07:00 whose T+30 min rung hit a provider update
         * never saw the 1 h / 2 h / 4 h … rungs, kept the circular accelerometer mask for good,
         * and reported zero attempts on the waking strip. Here the exception becomes a logged
         * attempt, exactly like `SDK_UNAVAILABLE`: a row, so the ladder advances; a normal
         * return, so the next rung is scheduled.
         *
         * `CancellationException` is the one thing rethrown: it is the system taking the worker
         * back, not the provider answering, and a row for it would consume a rung on a read that
         * never took place.
         *
         * The two lambdas exist for the same reason as `ContextPublicationWorker.outcomeOf`'s
         * `put`: `SleepReader` is built on a `Context` and talks to a binder, so the branch this
         * function exists for could not be exercised while it was written inline in `doWork()`.
         */
        suspend fun attempt(
            availability: suspend () -> SleepReader.Availability,
            read: suspend () -> SleepReader.Reading?,
        ): Attempt = try {
            val state = availability()
            if (state != SleepReader.Availability.READY) Attempt.Unavailable(state) else Attempt.Read(read())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Attempt.Threw(t)
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
 *
 * ### It is a campaign, and a campaign gets stopped
 *
 * WorkManager stops a worker after ten minutes, and sixty nights of `NightAnalyzer` do not fit in
 * ten minutes. Until 4 September 2026 the loop had no cursor and no notion of being stopped: the
 * `JobCancellationException` that Room raises once the worker is cancelled was caught by the
 * `catch (Throwable)` and counted as a failed night, every following night failed the same way at
 * its first query, the worker returned `retry`, and the retry began again at the oldest night —
 * paying the same ten minutes to reach the same place. On a long campaign the tail was never
 * rescored: `TrendDao.distinctHashes()` returned two hashes for good, the trend refused to plot,
 * and the phone burnt a ten-minute CPU burst at every backoff period with no end. The campaign
 * now lives in [runCampaign], which skips the nights already stamped with the target hash and lets
 * a cancellation through.
 */
class RescoreAllWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val db = PendulumDatabase.get(applicationContext)
        val params = WorkScheduler.activeParams(applicationContext)
        return runCampaign(
            nights = db.nightDao().allHexOldestFirst(),
            targetHash = params.paramsHash,
            stampedHash = { hex -> db.nightDao().find(hex)?.paramsHash },
            analyse = { hex -> AnalysisRunner.analyse(applicationContext, hex, params) },
            activate = {
                db.paramDao().activate(
                    ParamProfileEntity(
                        paramsHash = params.paramsHash,
                        createdAtMs = System.currentTimeMillis(),
                        algoVersion = params.algoVersion,
                        paramsJson = params.toJson(),
                        active = true,
                    )
                )
            },
        )
    }

    companion object {

        /**
         * The campaign itself, database and analysis as parameters — same shape, same reason as
         * `ContextPublicationWorker.outcomeOf`: what is decided here (skip, analyse, count as a
         * failure, let the stop through, activate) could not be demonstrated while it sat in a
         * `doWork()` that opens the real database.
         *
         * ### The cursor
         *
         * `night_session.paramsHash` **is** the cursor, and it costs no new state:
         * `AnalysisRunner.persist` stamps it through `writeAnalysisSummary` at the end of every
         * successful night, so "not yet under [targetHash]" is exactly the set left to do. The run
         * that follows a stop resumes at the first night still bearing the old hash instead of
         * paying the whole head of the campaign again. Oldest-first is preserved: the reference
         * night is stamped first, and its gain reference is what the later nights read.
         *
         * A night stamped with [targetHash] is not recomputed. That holds for the "Apply to every
         * night" button too: under an unchanged hash the inputs are unchanged, and a changed
         * hypnogram or a late chunk has its own trigger (`RescoreWorker`, the night chain).
         *
         * ### The switch of the active profile happens here and nowhere else
         *
         * `ParamDao.activate()` had **no caller** outside the bench seeding. `AnalysisRunner`
         * records the profile with `active = active() == null`, which is the first-install
         * bootstrap and nothing more: as soon as an older profile was active, the new hash was
         * inserted inactive and stayed so. After an update that changed a default of `:algo`,
         * every night was recomputed under the new hash while every screen kept reading the old
         * one through `observeActive()` — the trend, the night detail and the waking strip showed
         * the very figures the update was meant to correct, the "custom profile" label named the
         * old profile, `mixedHashes` fired, and `ReportExporter`, which goes through
         * `WorkScheduler.activeParams()`, wrote the new figure: two figures for the same night,
         * one on the screen and one in the physician's report. And because the mismatch never
         * resolved, `PendulumApp.onCreate` re-enqueued the full recompute (`REPLACE`) at every
         * cold start, cancelling the one in flight.
         *
         * The switch comes **after** the last night, never before: as long as one night is still
         * under the old hash the screens must keep reading the old hash rather than plot a
         * campaign half recomputed. It still runs when nothing was left to recompute — the run
         * that follows a stop after the very last night has only the activation left to do.
         *
         * ### A stop is not a failed night
         *
         * `CancellationException` is rethrown, and it must be caught before `Throwable`:
         * `kotlinx.coroutines.CancellationException` is `java.util.concurrent.CancellationException`,
         * the type of the `JobCancellationException` Room raises once the worker is cancelled.
         * Rethrowing loses nothing — WorkManager re-enqueues the stopped work, and the cursor makes
         * the next run start where this one stopped. The `ensureActive()` between two nights is
         * for the case where the stop lands in the middle of `NightAnalyzer.analyze`, one
         * non-suspending block of several seconds: without it, a stopped worker would still pay
         * one more night in full.
         *
         * @param nights every night, oldest first.
         * @param stampedHash the `paramsHash` a night currently carries, `null` when it was never
         *   analysed.
         * @param analyse the analysis of one night under the target profile.
         * @param activate the switch of the active profile to the target one.
         * @return `retry` if one night failed for a reason of its own: the old profile stays
         *   active, and `TrendDao.distinctHashes()` makes the two hashes visible so that the
         *   interface says so rather than draw a truncated trend.
         */
        suspend fun runCampaign(
            nights: List<String>,
            targetHash: String,
            stampedHash: suspend (String) -> String?,
            analyse: suspend (String) -> Unit,
            activate: suspend () -> Unit,
        ): ListenableWorker.Result {
            var failures = 0
            for (hex in nights) {
                coroutineContext.ensureActive()
                if (stampedHash(hex) == targetHash) continue
                try {
                    analyse(hex)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    failures++
                    Log.e(TAG, "global rescore: $hex failed", t)
                }
            }
            if (failures != 0) return ListenableWorker.Result.retry()
            activate()
            return ListenableWorker.Result.success()
        }
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
