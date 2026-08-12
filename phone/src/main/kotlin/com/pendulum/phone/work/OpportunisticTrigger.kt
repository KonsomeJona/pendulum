package com.pendulum.phone.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pendulum.phone.db.PendulumDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "PendulumWork"

/**
 * The two free triggers of the Health Connect read.
 *
 * [FetchSchedule] does an exponential backoff — 30 min, 1 h, 2 h, 4 h, 8 h — because it does not
 * know when the hypnogram will arrive. But the synchronisation is not a random event: it is
 * **correlated with usage**. The wrist watch pushes when it is on the charger, and the source
 * application writes when it is opened, that is to say often just before Pendulum is opened. Two
 * signals the system gives away for free, and several hours of perceived latency saved.
 *
 * The read triggered here **does not consume the ladder**: see [FetchSchedule.OPPORTUNISTIC_INDEX]
 * and `HcSnapshotDao.attemptCount`.
 */
object OpportunisticTrigger {

    private const val WORK = "pendulum-sleep-fetch-opportuniste"

    /**
     * Enqueues an immediate read for every night that is still waiting for its hypnogram.
     *
     * The filter is in two stages and the order matters: first the nights closed less than 36 h
     * ago (an indexed query), then, among those, the ones for which no `hc_snapshot` retained a
     * record. The second condition is what avoids re-reading an already complete night for nothing
     * at every plug-in of the charger.
     */
    suspend fun trigger(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val db = PendulumDatabase.get(context)
        val nights = db.nightDao().endedSince(nowMs - FetchSchedule.GIVE_UP_MS)
        for (s in nights) {
            val endMs = s.endWallMs ?: continue
            val last = db.hcSnapshotDao().latest(s.sessionHex)
            // A record already retained: the night has its independent denominator. We still
            // continue the ladder scheduled elsewhere — a provider can rewrite a session — but that
            // does not justify a read at every plug-in.
            if (last?.selectedRecordId != null) continue
            if (!FetchSchedule.opportunisticAllowed(endMs, nowMs, last?.fetchedAtMs)) continue

            val request = OneTimeWorkRequestBuilder<SleepFetchWorker>()
                .setInputData(workDataOf(KEY_SESSION to s.sessionHex, KEY_OPPORTUNISTIC to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK-${s.sessionHex}",
                // `KEEP`: if an opportunistic read is already in flight for this night, enqueuing a
                // second one can only produce two identical `hc_snapshot` rows.
                ExistingWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "${s.sessionHex}: opportunistic read enqueued")
        }
    }
}

/**
 * The charger has just been plugged in.
 *
 * `ACTION_POWER_CONNECTED` is one of the rare broadcasts **exempt** from the manifest registration
 * restriction introduced by Android 8: it still wakes a closed application, which is exactly the
 * use case — the phone resting on its dock next to the watch, the application not opened since the
 * morning.
 *
 * `goAsync` rather than synchronous work: the read touches the database, and an `onReceive` that
 * blocks for more than ten seconds is killed by the system. The `PendingResult` keeps the process
 * alive for the duration of the query, which is measured in milliseconds.
 */
class PowerConnectedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                OpportunisticTrigger.trigger(app)
            } catch (t: Throwable) {
                Log.e(TAG, "opportunistic trigger (charger) failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
