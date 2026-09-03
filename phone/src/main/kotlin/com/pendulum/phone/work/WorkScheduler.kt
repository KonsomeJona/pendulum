package com.pendulum.phone.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.time.Durations
import java.util.concurrent.TimeUnit

/**
 * The processing chain of a night.
 *
 * ```
 * IngestWorker  ->  AnalyzeWorker  ->  SleepFetchWorker  ->  RescoreWorker
 * (reconcile)       (accel mask)       (ladder T+30min...)    (HC mask)
 * ```
 *
 * ### What the chain guarantees, and what it does not
 *
 * It guarantees the **order**: we do not rescore before having read, we do not read before having
 * analysed, we do not analyse before having reconciled disk and database. It does **not** guarantee
 * the delays: `SleepFetchWorker` reschedules itself along [FetchSchedule], and `RescoreWorker` is
 * re-enqueued by it every time the hypnogram has changed. The `RescoreWorker` link of the initial
 * chain is therefore only a first try, generally with no effect.
 *
 * ### The constraints, and above all the one we do not set
 *
 * No worker requires the charger. The combination "Health Connect read + `requiresCharging`" is an
 * identified trap: on a phone that is not systematically recharged in the morning, it produces a
 * worker that never runs and a night with no denominator, silently.
 *
 * No worker requires the network either, and for good reason: the application does not declare the
 * `INTERNET` permission.
 */
object WorkScheduler {

    private const val CHAIN = "pendulum-night-chain"
    private const val END_OF_NIGHT = "pendulum-end-of-night"
    private const val FETCH = "pendulum-sleep-fetch"
    private const val RESCORE = "pendulum-rescore"
    private const val RESCORE_ALL = "pendulum-rescore-all"
    private const val WATCHDOG = "pendulum-watchdog"
    private const val CONTEXT_PUBLICATION = "pendulum-context-publish"

    /**
     * Common constraints. `setRequiresBatteryNotLow(false)` is explicit: a night that has already
     * been recorded must be analysed even on a phone at 12 %, otherwise the user sees "no result"
     * and believes the night is lost when it is intact on the disk.
     */
    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(false)
        .setRequiresCharging(false)
        .build()

    fun enqueueNightChain(context: Context, sessionHex: String) {
        val data = workDataOf(KEY_SESSION to sessionHex)
        val ingest = OneTimeWorkRequestBuilder<IngestWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val analyze = OneTimeWorkRequestBuilder<AnalyzeWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val fetch = OneTimeWorkRequestBuilder<SleepFetchWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val rescore = OneTimeWorkRequestBuilder<RescoreWorker>()
            .setInputData(data).setConstraints(constraints).build()

        WorkManager.getInstance(context)
            // `KEEP`: if the chain is already running for this night, restarting it would double
            // the work to reach the same result. The name includes the session, so two different
            // nights do not exclude each other.
            .beginUniqueWork("$CHAIN-$sessionHex", ExistingWorkPolicy.KEEP, ingest)
            .then(analyze)
            .then(fetch)
            .then(rescore)
            .enqueue()
    }

    /**
     * The analysis run again for a night whose series of chunks has **just become complete**.
     *
     * ```
     * IngestWorker  ->  AnalyzeWorker
     * (reconcile)       (score with every chunk, and whatever hypnogram is already in the database)
     * ```
     *
     * ### What it repairs
     *
     * The chain of [enqueueNightChain] is launched by the CLOSED session item, and the CLOSED item
     * is not the end of the transfer: `finalizeSession` on the watch puts it **before** the final
     * burst, the Data Layer orders nothing between distinct items, and a phone that was off all
     * night receives it with twenty-four chunks while the rest follow at the pace of the
     * acknowledgements. So the analysis was triggered by the close, not by the completeness of
     * the data. `PendulumListenerService.onChunk` relaunched the chain when the completing chunk
     * landed — but only once the night was *scored*, because the relaunch used [enqueueNightChain]
     * and its `KEEP`, which drops a request while the previous chain is enqueued or running. The
     * chunk that landed **after** `AnalyzeWorker` had listed the files and **before** it wrote
     * `analyzedAtMs` fell in between: not seen by the chain, not relaunching anything. That night
     * was scored with `received < declared`, hence `closedCleanly = false`, shown as truncated and
     * kept out of the trend for good — a gap of a few seconds that the KDoc of `onChunk` used to
     * name as "what only a different work policy could close". This is that policy.
     *
     * `APPEND_OR_REPLACE`, on the **same unique name** as the night chain: if that chain is still
     * enqueued or running, this one is appended after it and runs on the complete series; if it
     * is finished, this one simply runs. Nothing is dropped, nothing running is cancelled — a
     * `REPLACE` would cut `SleepFetchWorker` between its `hc_snapshot` row and the scheduling of
     * the next rung, and the ladder would stop there. The price is one analysis more on the night
     * where the CLOSED item overtook its last chunk, which is exactly the night whose first
     * analysis was wrong.
     *
     * Only the two links that read the chunks: the Health Connect ladder of the first chain is
     * already running under its own unique name, and `AnalysisRunner` reads whatever hypnogram the
     * ladder has stored so far.
     */
    fun enqueueLateRescore(context: Context, sessionHex: String) {
        val data = workDataOf(KEY_SESSION to sessionHex)
        val ingest = OneTimeWorkRequestBuilder<IngestWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val analyze = OneTimeWorkRequestBuilder<AnalyzeWorker>()
            .setInputData(data).setConstraints(constraints).build()

        WorkManager.getInstance(context)
            .beginUniqueWork("$CHAIN-$sessionHex", ExistingWorkPolicy.APPEND_OR_REPLACE, ingest)
            .then(analyze)
            .enqueue()
    }

    /**
     * The chain of the "end of night" button, and its order differs from [enqueueNightChain].
     *
     * ```
     * IngestWorker  ->  SleepFetchWorker    ->  AnalyzeWorker
     * (reconcile)       (read the hypnogram)    (score with it)
     * ```
     *
     * The automatic chain analyses **before** reading Health Connect, and rightly so: on waking,
     * the hypnogram has not arrived yet — the wrist watch synchronisation obeys the manufacturer's
     * battery policy — so waiting would produce an application with nothing to say for hours. It
     * scores with the accelerometer mask, then rescores.
     *
     * Here, the user has just pressed and is waiting. Attempting the read first gives the analysis
     * a chance to use the real denominator on the first go: `AnalysisRunner` reads the latest
     * `hc_snapshot`, so the order alone is enough to change the result. If the read returns
     * nothing, `SleepFetchWorker` returns `success` anyway and the analysis falls back on the
     * accelerometer mask — the worst case is therefore exactly the nominal behaviour, never a
     * deadlock.
     *
     * `REPLACE` and not `KEEP`: the gesture is explicit and repeated when the first one brought
     * nothing back. `KEEP` would make a button that, pressed twice, does nothing the second time.
     */
    fun enqueueEndOfNight(context: Context, sessionHex: String) {
        val data = workDataOf(KEY_SESSION to sessionHex)
        val ingest = OneTimeWorkRequestBuilder<IngestWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val fetch = OneTimeWorkRequestBuilder<SleepFetchWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val analyze = OneTimeWorkRequestBuilder<AnalyzeWorker>()
            .setInputData(data).setConstraints(constraints).build()

        WorkManager.getInstance(context)
            .beginUniqueWork("$END_OF_NIGHT-$sessionHex", ExistingWorkPolicy.REPLACE, ingest)
            .then(fetch)
            .then(analyze)
            .enqueue()
    }

    /** Explicit rescheduling of a read attempt, at the already computed rung. */
    fun scheduleSleepFetch(context: Context, sessionHex: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<SleepFetchWorker>()
            .setInputData(workDataOf(KEY_SESSION to sessionHex))
            .setConstraints(constraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$FETCH-$sessionHex",
            // `REPLACE`: there is only one attempt in flight at a time per night, and it is always
            // the last one computed that stands. `KEEP` would freeze the ladder on its first rung.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Computes the next rung of the ladder and schedules it, or schedules nothing if we give up.
     *
     * @param nowMs the clock as a parameter: it is what decides between rescheduling and giving
     *   up, and reading it from deep inside the function made that branch impossible to exercise.
     */
    fun scheduleNextSleepFetch(
        context: Context,
        sessionHex: String,
        endMs: Long,
        attemptsDone: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        when (val plan = FetchSchedule.plan(attemptsDone, endMs, nowMs)) {
            is FetchSchedule.Plan.Retry -> scheduleSleepFetch(context, sessionHex, plan.delayMs)
            is FetchSchedule.Plan.GiveUp -> Unit
        }
    }

    fun enqueueRescore(context: Context, sessionHex: String) {
        val request = OneTimeWorkRequestBuilder<RescoreWorker>()
            .setInputData(workDataOf(KEY_SESSION to sessionHex))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("$RESCORE-$sessionHex", ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * To be called after **every** parameter change. This is the trigger of guard rail 3, and
     * there is deliberately no "rescore only the recent nights" variant: a trend with three points
     * of which two were computed differently is not a partial trend, it is a false chart.
     */
    fun enqueueRescoreAll(context: Context) {
        val request = OneTimeWorkRequestBuilder<RescoreAllWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RESCORE_ALL, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * The outbox of the evening context: to be enqueued only when the online put has failed.
     *
     * `enqueueUniqueWork` with the night key in the name, and `KEEP`: an evening has only one
     * context, hence only one replay in flight. `REPLACE` would reset the exponential backoff to
     * its first step at every new sealing attempt — except that there cannot be any, the database
     * refusing the duplicate. `KEEP` says the same thing and does not get it wrong if that changes.
     *
     * No network constraint, as everywhere else here: the application does not declare the
     * `INTERNET` permission and the Data Layer goes over Bluetooth. A network constraint would
     * produce work that is never eligible, hence a catch-up that never happens.
     *
     * @param sealedAtMs the instant of the sealing, which is the payload of the item. The replay
     *   must put **exactly** the same one back, failing which the Data Layer deduplication no
     *   longer applies.
     */
    fun enqueueContextPublication(context: Context, nightKey: String, sealedAtMs: Long) {
        val request = OneTimeWorkRequestBuilder<ContextPublicationWorker>()
            .setInputData(workDataOf(KEY_NIGHT_KEY to nightKey, KEY_SEALED_AT to sealedAtMs))
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                Durations.ACTIVE.contextRepublicationDelayMs,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$CONTEXT_PUBLICATION-$nightKey",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * The watchdog runs every 30 minutes. That is the minimum allowed by WorkManager for periodic
     * work (15 min) doubled with a margin: it does nothing as long as no session is open, and its
     * cost is one SQL query.
     *
     * The period goes through `Durations`, but WorkManager brings any value under fifteen minutes
     * back up to fifteen minutes: on the bench, this path does not speed up. See
     * `Durations.watchdogPeriodMs`.
     */
    fun ensureWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(
            Durations.ACTIVE.watchdogPeriodMs,
            TimeUnit.MILLISECONDS,
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * The active parameter profile, or the default one.
     *
     * An important note: only the **hash** is stored in the database, not the values. An active
     * profile whose hash does not match the default values of the code means that the installed
     * version can no longer reproduce that profile — the case of an application update that
     * changed a default value. We then start again from the current profile of the code, which
     * produces a new hash, hence a full rescore. That is the intended behaviour: it is better to
     * recompute than to label old figures with a hash that no longer describes them.
     */
    suspend fun activeParams(context: Context): AnalysisParams {
        val active = PendulumDatabase.get(context).paramDao().active()
        if (active != null && active.paramsHash != AnalysisParams.DEFAULT.paramsHash) {
            // We do not restart the global rescore from here: this function is called *by*
            // `RescoreAllWorker`, and self-enqueuing would give an infinite loop. It is
            // `PendulumApp.onCreate` that detects the hash mismatch at start-up and triggers it.
            android.util.Log.i(
                "PendulumWork",
                "active profile ${active.paramsHash} != ${AnalysisParams.DEFAULT.paramsHash}: rescore expected",
            )
        }
        return AnalysisParams.DEFAULT
    }
}
