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
 * Les notifications du telephone. Il y en a deux, et pas une de plus.
 *
 * ### Pourquoi une notification plutot qu'une ouverture directe
 *
 * Parce qu'Android ne laisse pas le choix. Le service qui recoit un message de la montre tourne en
 * arriere-plan — c'est Google Play Services qui le demarre — et le lancement d'activite depuis
 * l'arriere-plan est bloque depuis Android 10, en silence. Le tap de l'utilisateur sur une
 * notification est, lui, une exemption explicite et fiable.
 *
 * ### Ce qu'elles ne disent jamais
 *
 * **Aucun chiffre.** La notification du matin annonce qu'une nuit a ete analysee et combien de
 * nuits sont disponibles, jamais un indice. Le garde-fou 2 masque le resultat au reveil et
 * journalise son devoilement : une notification qui porterait la valeur serait un devoilement sans
 * trace, c'est-a-dire le contournement complet du garde-fou par un canal lateral.
 */
object Notifications {

    /**
     * « Le formulaire du soir vous attend » — postee quand la montre le demande.
     *
     * Priorite haute : elle est declenchee par un geste explicite de l'utilisateur sur sa montre,
     * a l'instant ou il attend qu'un ecran s'allume. Ce n'est pas une sollicitation, c'est une
     * reponse.
     */
    // `notify` est garde par `peutNotifier` des la premiere ligne, mais lint ne suit pas une garde
    // a travers un appel de fonction : `PermissionDetector` n'analyse que le corps de la methode
    // appelante. Inliner le `checkSelfPermission` ici satisferait lint et laisserait la raison de
    // la garde sans domicile — c'est la KDoc de `peutNotifier` qui la porte, et elle vaut plus que
    // le warning. Meme forme, meme motif dans `wear/transfer/WatchNotifications.kt`.
    @android.annotation.SuppressLint("MissingPermission")
    fun demandeDeContexteDuSoir(ctx: Context) {
        if (!peutNotifier(ctx)) return
        creerCanaux(ctx)

        val intent = Intent(
            Intent.ACTION_VIEW,
            LIEN_SOIR.toUri(),
            ctx,
            MainActivity::class.java,
        )
        val enAttente = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        NotificationManagerCompat.from(ctx).notify(
            ID_CONTEXTE_DU_SOIR,
            NotificationCompat.Builder(ctx, CANAL_ACTION)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(TITRE_CONTEXTE)
                .setContentText(CORPS_CONTEXTE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(enAttente)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * `POST_NOTIFICATIONS` est une permission d'execution depuis Android 13, et `minSdk` vaut 30 :
     * poster sans l'avoir ne leve pas, cela ne fait simplement rien. Verifier permet au moins de
     * ne pas croire qu'on a prevenu quelqu'un.
     */
    private fun peutNotifier(ctx: Context): Boolean =
        // La garde de version n'est pas une precaution de style : sous Android 13 la permission
        // **n'existe pas**, et `checkSelfPermission` d'une permission qu'aucun paquet ne definit
        // rend `DENIED`, pas « accordee ». `minSdk` valant 30, ce test a rendu l'application
        // entierement muette sur Android 11 et 12 — le rappel du soir n'y partait jamais, et le
        // bouton « autoriser » de l'assistant demandait une permission inconnue, donc refusee sans
        // meme afficher de dialogue. Le commentaire ci-dessus enoncait le fait ; le code n'en
        // tirait pas la consequence.
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun creerCanaux(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CANAL_ACTION,
                NOM_CANAL_ACTION,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = DESC_CANAL_ACTION },
        )
    }

    const val LIEN_SOIR = "pendulum://tonight"

    private const val CANAL_ACTION = "pendulum.action"
    private const val NOM_CANAL_ACTION = "Actions needed tonight"
    private const val DESC_CANAL_ACTION =
        "Sent when your watch asks for something that can only be done on the phone. " +
            "At most one per evening."

    private const val ID_CONTEXTE_DU_SOIR = 1

    private const val TITRE_CONTEXTE = "Pendulum — the evening record is not sealed"
    private const val CORPS_CONTEXTE =
        "Your watch will not start recording until it is. Tap to fill it in."
}
