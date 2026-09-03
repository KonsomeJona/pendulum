package com.pendulum.phone.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.pendulum.phone.R
import com.pendulum.phone.ui.MainActivity

/**
 * The phone's only notification.
 *
 * ### Why a notification rather than opening the app directly
 *
 * Because Android leaves no choice. The service that receives a message from the watch runs in
 * the background — it is Google Play Services that starts it — and launching an activity from the
 * background has been blocked since Android 10, silently. The user's tap on a notification is, by
 * contrast, an explicit and reliable exemption.
 *
 * ### What it never says
 *
 * **No figure.** Nothing posted here carries a result, and nothing is posted at waking at all.
 * Guard rail 2 masks the result at waking and journals its unveiling: a notification that carried
 * the value would be an unveiling with no trace, that is, the complete circumvention of the guard
 * rail through a side channel. A waking notification announcing only "a night has been analysed"
 * would be defensible on that count — but it does not exist, and until 4 September 2026 this
 * KDoc said there were two notifications and described it as if it did. The onboarding step, the
 * README and `docs/06-interface.md` repeated the promise, so a first-night user granted
 * `POST_NOTIFICATIONS` for a morning notification that never came, and did not open the app —
 * which is also the gesture that fires the opportunistic Health Connect read. Do not describe a
 * notification here before something posts it: `AnalyzeWorker` and `RescoreWorker` post nothing,
 * and the single call site of this object is `PendulumListenerService`, in the evening.
 */
object Notifications {

    /**
     * "The evening form is waiting for you" — posted when the watch asks for it.
     *
     * High priority: it is triggered by an explicit gesture of the user on their watch, at the
     * very moment they are waiting for a screen to light up. It is not a solicitation, it is an
     * answer.
     */
    // `notify` is guarded by `canNotify` from the very first line, but lint does not follow a
    // guard across a function call: `PermissionDetector` only analyses the body of the calling
    // method. Inlining the `checkSelfPermission` here would satisfy lint and leave the reason for
    // the guard homeless — it is the KDoc of `canNotify` that carries it, and it is worth more
    // than the warning. Same shape, same reason in `wear/transfer/WatchNotifications.kt`.
    @android.annotation.SuppressLint("MissingPermission")
    fun eveningContextRequest(ctx: Context) {
        if (!canNotify(ctx)) return
        createChannels(ctx)

        val intent = Intent(
            Intent.ACTION_VIEW,
            EVENING_LINK.toUri(),
            ctx,
            MainActivity::class.java,
        )
        val pending = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        NotificationManagerCompat.from(ctx).notify(
            EVENING_CONTEXT_ID,
            NotificationCompat.Builder(ctx, ACTION_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(CONTEXT_TITLE)
                .setContentText(CONTEXT_BODY)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * `POST_NOTIFICATIONS` has been a runtime permission since Android 13, and `minSdk` is 30:
     * posting without holding it does not throw, it simply does nothing. Checking at least keeps
     * us from believing we have warned somebody.
     */
    private fun canNotify(ctx: Context): Boolean =
        // The version guard is not a precaution of style: below Android 13 the permission
        // **does not exist**, and `checkSelfPermission` of a permission that no package defines
        // returns `DENIED`, not "granted". `minSdk` being 30, this test made the application
        // entirely mute on Android 11 and 12 — the evening reminder never went out there, and the
        // "allow" button of the onboarding asked for an unknown permission, hence refused without
        // even showing a dialog. The comment above stated the fact; the code did not draw the
        // consequence from it.
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun createChannels(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ACTION_CHANNEL,
                ACTION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = ACTION_CHANNEL_DESC },
        )
    }

    const val EVENING_LINK = "pendulum://tonight"

    private const val ACTION_CHANNEL = "pendulum.action"
    private const val ACTION_CHANNEL_NAME = "Actions needed tonight"
    private const val ACTION_CHANNEL_DESC =
        "Sent when your watch asks for something that can only be done on the phone. " +
            "At most one per evening."

    private const val EVENING_CONTEXT_ID = 1

    private const val CONTEXT_TITLE = "Pendulum — the evening record is not sealed"
    private const val CONTEXT_BODY =
        "Your watch will not start recording until it is. Tap to fill it in."
}
