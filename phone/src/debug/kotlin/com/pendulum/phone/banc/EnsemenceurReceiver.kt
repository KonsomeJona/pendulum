package com.pendulum.phone.banc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Le declencheur d'ensemencement du banc, invocable depuis `adb`.
 *
 * ```
 * # ensemencer 7 nuits eligibles (+ 1 provisoire, + 1 ecartee)
 * adb shell am start -n com.pendulum/com.pendulum.phone.ui.MainActivity
 * adb shell am broadcast -f 0x00000020 \
 *   -n com.pendulum/com.pendulum.phone.banc.EnsemenceurReceiver \
 *   -a com.pendulum.banc.ENSEMENCER --ei nuits 7
 *
 * # rendre l'appareil a son etat neuf
 * adb shell am broadcast -f 0x00000020 \
 *   -n com.pendulum/com.pendulum.phone.banc.EnsemenceurReceiver \
 *   -a com.pendulum.banc.EFFACER
 * ```
 *
 * `-f 0x00000020` est `FLAG_INCLUDE_STOPPED_PACKAGES`, et il n'est pas decoratif : une
 * application fraichement installee, ou qui vient de subir un `pm clear`, est dans l'etat
 * *stopped*, et le systeme **filtre silencieusement** toute diffusion vers elle. Sans ce drapeau,
 * la commande rend « Broadcast completed: result=0 » et il ne se passe rien.
 *
 * Suivre l'avancement : `adb logcat -s PendulumBanc`. La derniere ligne dit « termine en N ms ».
 *
 * ### Lancer l'application d'abord, et ce n'est pas un detail de confort
 *
 * L'ensemencement dure plusieurs dizaines de secondes — la synthese d'une nuit de huit heures a
 * 50 Hz coute quelques secondes sur telephone — et **rien dans un receveur ne permet de tenir un
 * processus aussi longtemps**. Les deux voies ont ete essayees et mesurees sur le Pixel Fold :
 *
 *  - **WorkManager** : le travail demarre, ecrit deux nuits, et `onStopJob` l'interrompt au bout
 *    de quatre secondes. La cause n'est pas WorkManager mais la priorite du processus — la
 *    diffusion finie, l'application n'a aucun composant au premier plan, elle repasse en cache. Le
 *    rendre *expedited* imposerait un `getForegroundInfo`, donc un canal de notification et une
 *    notification visible, dans un outil dont tout l'objet est de ne rien afficher.
 *  - **`goAsync` tenu jusqu'au bout** : le processus garde bien sa priorite, mais le systeme borne
 *    un receveur de manifeste a 60 s. Mesure : `ANR in com.pendulum / Reason: Broadcast of
 *    Intent { act=com.pendulum.banc.ENSEMENCER }`, a la cinquieme nuit sur neuf.
 *
 * Le receveur **termine donc sa diffusion immediatement** et poursuit dans une portee a lui : plus
 * aucun delai ne pese sur lui. Ce qui garde le processus vivant est alors l'activite, d'ou la
 * premiere ligne de la commande. C'est aussi l'ordre naturel du banc, qui ouvre l'application pour
 * la photographier.
 */
class EnsemenceurReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action
        if (action != ACTION_ENSEMENCER && action != ACTION_EFFACER) {
            Log.w(TAG, "action inconnue : $action")
            return
        }

        val nuits = intent.getIntExtra(EXTRA_NUITS, Ensemencement.NUITS_PAR_DEFAUT)
        portee.launch {
            val depart = System.currentTimeMillis()
            try {
                if (action == ACTION_ENSEMENCER) {
                    Ensemencement.ensemencer(app, nuits)
                } else {
                    Ensemencement.effacer(app)
                }
                Log.i(TAG, "$action : termine en ${System.currentTimeMillis() - depart} ms")
            } catch (t: Throwable) {
                // Un ensemencement interrompu laisse une base a moitie remplie. Ce n'est pas un
                // etat a reparer : `ensemencer` commence par tout effacer, donc rejouer la
                // commande repart proprement.
                Log.e(TAG, "$action : echec", t)
            }
        }
    }

    companion object {
        const val TAG = "PendulumBanc"

        const val ACTION_ENSEMENCER = "com.pendulum.banc.ENSEMENCER"
        const val ACTION_EFFACER = "com.pendulum.banc.EFFACER"

        /** Nombre de nuits **eligibles**. La provisoire et l'ecartee s'ajoutent en plus. */
        const val EXTRA_NUITS = "nuits"

        /**
         * La portee qui survit au receveur, et qui est volontairement un singleton.
         *
         * Un `BroadcastReceiver` est jete des la fin de `onReceive` ; une portee construite dans
         * la methode serait donc detruite avec lui, et le travail avec. Celle-ci vit dans le
         * chargeur de classes, donc aussi longtemps que le processus. Elle n'est jamais annulee :
         * il n'y a rien a annuler, l'ensemencement etant un geste unique dont on veut la fin.
         */
        private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
