package com.pendulum.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pendulum.wear.record.RecordingService
import com.pendulum.wear.record.SessionStore
import com.pendulum.wear.time.Durations
import java.util.concurrent.TimeUnit

/**
 * Safety net against the memory killer.
 *
 * `START_STICKY` is not enough. It promises the service will be recreated "when resources
 * permit", which, in deep standby, can mean in the morning. A recording that dies at 2 a.m. and
 * restarts at 7 a.m. is a lost night with a file that looks normal — the worst kind of failure,
 * the one that does not show.
 *
 * The watchdog re-reads the session marker every fifteen minutes: if a night is declared active
 * and the service is not running, it restarts it. Fifteen minutes is the minimum of a
 * `PeriodicWorkRequest`, and it is also the granularity of loss already accepted elsewhere.
 *
 * **That floor makes this path incompressible.** The bench can divide the requested period
 * (`Durations.watchdogPeriodMs`), WorkManager will bring it back to fifteen real minutes. A bench
 * that wants to exercise the resume must therefore trigger the work itself, and in any case it
 * will never measure the real property at stake: that work scheduled in fifteen minutes sometimes
 * runs in forty-five.
 *
 * **Accepted limitation.** Since Android 12, an application in the background cannot always start
 * a foreground service, and an ordinary worker is not among the exemptions. The sure answer would
 * be an exact alarm, hence `SCHEDULE_EXACT_ALARM` — one more permission, for a purely defensive
 * path, in an application whose absence of permissions is a verifiable argument. The choice is
 * therefore: try, log the failure, and rely on the two other nets (`START_STICKY`, and the resume
 * on `BOOT_COMPLETED`). If measurement shows that this path really does fail in the middle of the
 * night, it is the permission that will have to be added, not the watchdog that will have to be
 * removed.
 */
object Watchdog {

    const val UNIQUE_NAME = "pendulum-watchdog"

    fun start(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            // The intent is to **reset the period** when a night begins, otherwise the first
            // check can land fourteen minutes too late. `UPDATE` does not do that: it replaces
            // the specification while keeping the schedule of the request already pending (the
            // period not having changed, there is nothing to recompute), so here it was very
            // nearly a no-op. `CANCEL_AND_REENQUEUE` is the only policy that cancels the existing
            // instance and starts over — it is the one the comment was describing.
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            PeriodicWorkRequestBuilder<WatchdogWorker>(
                Durations.ACTIVE.watchdogPeriodMs,
                TimeUnit.MILLISECONDS,
            ).build(),
        )
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
    }

    /**
     * Restart of a **fresh** session after an `onTimeout`: the service had to stop, but the night
     * itself is not over. Thirty seconds of delay let the system finish its shutdown before a
     * foreground service is asked for again.
     */
    fun restartAfterTimeout(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            RESTART_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchdogWorker>()
                .setInitialDelay(Durations.ACTIVE.restartAfterTimeoutMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_RESTART to true))
                .build(),
        )
    }

    const val RESTART_NAME = "pendulum-restart"
    const val KEY_RESTART = "restart"
}

class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        if (inputData.getBoolean(Watchdog.KEY_RESTART, false)) {
            return start(ctx, RecordingService.ACTION_START)
        }

        val marker = SessionStore(ctx).readMarker()
            ?: return Result.success() // no active night: nothing to watch

        if (RecordingService.isRunning) return Result.success()

        // The same guard rails as the resume after reboot, and now the same code: we never
        // restart a recording whose time has passed just because a marker is lying around. See
        // `SessionMarker.isStale`.
        if (marker.isStale(System.currentTimeMillis())) return Result.success()

        return start(ctx, RecordingService.ACTION_RESUME)
    }

    private fun start(ctx: Context, action: String): Result = try {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, RecordingService::class.java).setAction(action),
        )
        Result.success()
    } catch (e: Exception) {
        Log.e(TAG, "service restart refused from the background", e)
        Result.retry()
    }

    private companion object {
        const val TAG = "PendulumWatchdog"
    }
}
