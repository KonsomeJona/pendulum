package com.pendulum.wear.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.pendulum.wear.record.RecordingService

/**
 * The one and only activity. It records nothing, computes nothing and survives nothing: everything
 * that matters lives in the service. Its only role is to display six counters and to carry two
 * buttons — and not to be switched on during the night.
 *
 * No `AmbientModeSupport`, no `keepScreenOn`, no always-on. An always-on activity would leave the
 * screen in ambient mode all night, would light up the ankle under the duvet, would be killed at
 * the first memory pressure, and would cost the screen on top of the sensor — for protection
 * *lower* than that of a `health` foreground service.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The `POST_NOTIFICATIONS` request used to live here, with an empty callback. It has moved
        // down into `RecordRoute`, where its result can re-run the preflight: granting the
        // permission left the blocker displayed, and the application had to be killed for it to
        // disappear. The moment of the request has not changed — when the screen opens, not at
        // START.
        setContent {
            PendulumTheme {
                RecordRoute(
                    onStart = { send(RecordingService.ACTION_START) },
                    onStop = { send(RecordingService.ACTION_STOP) },
                    onOpenSettings = { openAppSettings() },
                )
            }
        }
    }

    private fun send(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java).setAction(action),
        )
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
