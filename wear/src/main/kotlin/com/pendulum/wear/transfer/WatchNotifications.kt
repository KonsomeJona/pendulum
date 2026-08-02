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
 * L'unique notification de la montre en dehors de celle du service d'enregistrement.
 *
 * ### Pourquoi elle existe
 *
 * Le telephone peut demander le demarrage (`/pendulum/start-request`), et la demande arrive dans
 * un service que Google Play Services demarre **depuis l'arriere-plan**. Android 12 y interdit de
 * demarrer un service de premier plan. Quand le refus tombe, la notification est le seul chemin
 * que le systeme garantisse : le tap de l'utilisateur est une exemption explicite au blocage.
 *
 * Ce n'est pas un pis-aller a cacher. Elle donne exactement le geste « une tape » recherche, sur
 * l'appareil qui est deja au poignet — et pas sur celui qu'il faut aller chercher.
 */
object WatchNotifications {

    /**
     * « Le contexte est scelle, appuyez pour demarrer. »
     *
     * Priorite haute : elle repond a un geste que l'utilisateur vient de faire sur son telephone,
     * a l'instant ou il attend que quelque chose se passe. Ce n'est pas une sollicitation.
     *
     * Elle ouvre l'ecran plutot que de demarrer directement, et c'est deliberement plus lent d'un
     * geste : le preflight est reevalue a l'ouverture, donc un espace disque devenu insuffisant
     * entre le scellement et le tap est vu avant que la nuit ne commence, pas apres.
     */
    fun pretADemarrer(ctx: Context) {
        if (!peutNotifier(ctx)) return
        creerCanal(ctx)

        val ouvrir = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        NotificationManagerCompat.from(ctx).notify(
            ID_PRET,
            NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_pendulum)
                .setContentTitle(ctx.getString(R.string.notif_ready_title))
                .setContentText(ctx.getString(R.string.notif_ready_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(ouvrir)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * `POST_NOTIFICATIONS` est une permission d'execution : poster sans l'avoir ne leve pas, cela
     * ne fait simplement rien. La verifier evite au moins de croire qu'on a prevenu quelqu'un.
     *
     * C'est deja un bloqueur du preflight, donc le cas ou elle manque ici est celui d'un
     * demarrage demande avant que l'utilisateur n'ait ouvert l'application une premiere fois.
     */
    private fun peutNotifier(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun creerCanal(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CANAL,
                ctx.getString(R.string.notif_channel_action),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private const val CANAL = "pendulum.action"
    private const val ID_PRET = 2
}
