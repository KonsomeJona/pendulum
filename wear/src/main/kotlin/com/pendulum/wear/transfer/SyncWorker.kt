package com.pendulum.wear.transfer

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pendulum.format.wire.StopReason
import com.pendulum.wear.record.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transfer catch-up **outside the service**: in the morning, after a reboot, after coming back
 * into range, or when the ceiling of items in flight has freed up.
 *
 * It does not duplicate the service: during recording it is the service that pushes, on every
 * third closed chunk. This worker exists for the moments when nobody is recording any more and
 * files are still on disk — that is to say exactly the "the phone was switched off all night"
 * scenario.
 *
 * **No network constraint**: the Data Layer is not the network, and a constraint that corresponds
 * to nothing simply keeps the worker from running. The only constraint is an energy one, and it is
 * checked here rather than declared, because `WorkManager` can only express "battery not low"
 * (15 %) where the rule is "charger **or** battery above 30 %".
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charging = bm?.isCharging ?: false
        if (!charging && pct < 30) {
            // Retry rather than fail: the catch-up is never urgent, and it will take place on the
            // morning charger in any case.
            return@withContext Result.retry()
        }

        val store = SessionStore(ctx)
        val activeHex = store.readMarker()?.sessionHex
        val finalizeHex = inputData.getString(KEY_FINALIZE_SESSION)

        if (finalizeHex != null) {
            finalizeCrashedSession(ctx, store, finalizeHex)
        }

        val dirs = store.chunksRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in dirs) {
            val hex = dir.name
            val files = dir.listFiles { f: File -> f.name.endsWith(".pendulum") } ?: emptyArray()
            if (files.isEmpty()) {
                // Fully acknowledged session: the directory holds nothing but its sidecar any
                // more, so it is released. This is the quota purge, done as we go.
                if (hex != activeHex) dir.deleteRecursively()
                continue
            }
            try {
                DataLayerTransfer.pushChunks(ctx, hex, dir, urgentLast = true)
            } catch (e: Exception) {
                Log.w(TAG, "could not push $hex, will retry", e)
                return@withContext Result.retry()
            }
        }
        Result.success()
    }

    /**
     * A session whose marker still exists while nothing is recording any more was not closed by a
     * stop condition: it was killed. It is therefore closed explicitly with `stopReason = CRASH` —
     * the phone must be able to tell "the night is not over" from "the watch is not answering any
     * more", and guessing is not an acceptable answer.
     */
    private fun finalizeCrashedSession(ctx: Context, store: SessionStore, hex: String) {
        val marker = store.readMarker() ?: return
        if (marker.sessionHex != hex) return
        try {
            DataLayerTransfer.closeSession(
                ctx = ctx,
                marker = marker,
                endWallMs = System.currentTimeMillis(),
                totalChunks = marker.lastChunkIndex + 1,
                reason = StopReason.CRASH,
            )
            store.clearActive()
        } catch (e: Exception) {
            Log.w(TAG, "could not close session $hex", e)
        }
    }

    companion object {
        private const val TAG = "PendulumSync"
        const val UNIQUE_NAME = "pendulum-transfer"
        const val KEY_FINALIZE_SESSION = "finalize_session"

        fun enqueue(ctx: Context, finalizeSessionHex: String? = null) {
            val data = Data.Builder().apply {
                finalizeSessionHex?.let { putString(KEY_FINALIZE_SESSION, it) }
            }.build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_NAME,
                // REPLACE: a more recent request necessarily carries more information than the one
                // that was waiting, and two simultaneous catch-ups make no sense.
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data).build(),
            )
        }
    }
}
