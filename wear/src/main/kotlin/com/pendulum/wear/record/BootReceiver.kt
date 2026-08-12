package com.pendulum.wear.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.pendulum.wear.Watchdog
import com.pendulum.wear.transfer.SyncWorker
import java.util.Calendar

/**
 * Resume after a reboot of the watch or a replacement of the application.
 *
 * Starting a foreground service from `BOOT_COMPLETED` is forbidden to the `dataSync`, `camera`,
 * `mediaPlayback`, `phoneCall`, `mediaProjection` and `microphone` types for an application
 * targeting Android 15 or later. `health` is not on that list: that is what makes this path
 * legal, and it is also the reason the service is of that type.
 *
 * **Five conditions, not one.** Testing only the fourteen-hour window restarts a recording at
 * eight in the morning, on the charger, after a night-time reboot — and pollutes the night
 * exactly as we are trying to avoid. When the conditions are not met, we do not resume, but we do
 * **finalise**: the session moves to `CLOSED` with `stopReason = CRASH` and the remainder leaves
 * for the phone. Doing nothing would leave a session `OPEN` for ever on the phone side.
 *
 * **Known blind spot.** If the watch has a lock code, `BOOT_COMPLETED` is only broadcast after
 * unlocking and credential-encrypted storage is inaccessible before that: a watch that reboots at
 * 3 a.m. **on the wrist** stays locked until morning, so no resume takes place. `directBootAware`
 * is deliberately not used — it would force moving the chunks to device-encrypted storage, more
 * exposed and more complex, for an uncertain gain. Until that delay is measured, it is the clean
 * shutdown on low battery that remains the main protection against losing a night.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val marker = SessionStore(context).readMarker()
        if (marker == null) {
            // Nothing in progress: the catch-up is kicked off anyway, an unacknowledged remainder
            // may have been sleeping on the disk since the day before.
            SyncWorker.enqueue(context)
            return
        }

        if (shouldResume(context, marker, System.currentTimeMillis())) {
            Log.i(TAG, "resuming session ${marker.sessionHex}")
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_RESUME),
            )
            Watchdog.start(context)
        } else {
            Log.i(TAG, "no resume: finalising ${marker.sessionHex}")
            SyncWorker.enqueue(context, marker.sessionHex)
        }
    }

    private fun shouldResume(context: Context, m: SessionMarker, nowMs: Long): Boolean {
        if (m.isStale(nowMs)) return false

        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val localMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (localMinutes >= m.stopAtLocalMinutes) return false

        // On the charger the night is over by definition: resuming it would amount to recording
        // a worktop.
        val bm = context.getSystemService(BatteryManager::class.java)
        if (bm?.isCharging == true) return false

        return true
    }

    private companion object {
        const val TAG = "PendulumBoot"
    }
}
