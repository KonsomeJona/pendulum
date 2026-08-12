package com.pendulum.wear.transfer

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
import com.pendulum.wear.R
import com.pendulum.wear.ui.MainActivity

/**
 * The only notification on the watch besides the one of the recording service.
 *
 * ### Why it exists
 *
 * The phone can ask for a start (`/pendulum/start-request`), and the request arrives in a service
 * that Google Play Services starts **from the background**. Android 12 forbids starting a
 * foreground service from there. When the refusal comes, the notification is the only path the
 * system guarantees: the user's tap is an explicit exemption from the block.
 *
 * This is not a makeshift to be hidden. It gives exactly the "one tap" gesture that was sought, on
 * the device already on the wrist — and not on the one that has to be fetched.
 */
object WatchNotifications {

    /**
     * "The context is sealed, press to start."
     *
     * High priority: it answers a gesture the user has just made on their phone, at the very moment
     * they are waiting for something to happen. It is not a solicitation.
     *
     * It opens the screen rather than starting directly, and that is deliberately one gesture
     * slower: the preflight is re-evaluated on opening, so disk space that has become insufficient
     * between the sealing and the tap is seen before the night begins, not after.
     */
    // `notify` is guarded by `canNotify` from the very first line, but lint does not follow a guard
    // across a function call: `PermissionDetector` only analyses the body of the calling method.
    // Inlining the `checkSelfPermission` here would satisfy lint and leave the reason for the guard
    // homeless — it is the KDoc of `canNotify` that carries it, and it is worth more than the
    // warning. Same shape, same rationale in `phone/notify/Notifications.kt`.
    @android.annotation.SuppressLint("MissingPermission")
    fun readyToStart(ctx: Context) {
        if (!canNotify(ctx)) return
        createChannel(ctx)

        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        NotificationManagerCompat.from(ctx).notify(
            ID_READY,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_pendulum)
                .setContentTitle(ctx.getString(R.string.notif_ready_title))
                .setContentText(ctx.getString(R.string.notif_ready_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * `POST_NOTIFICATIONS` is a runtime permission: posting without holding it does not throw, it
     * simply does nothing. Checking it at least avoids believing someone has been told.
     *
     * It is already a preflight blocker, so the case where it is missing here is that of a start
     * requested before the user has opened the application for the first time.
     */
    private fun canNotify(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                ctx.getString(R.string.notif_channel_action),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private const val CHANNEL = "pendulum.action"
    private const val ID_READY = 2
}
