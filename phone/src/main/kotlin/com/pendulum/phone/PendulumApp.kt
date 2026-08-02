package com.pendulum.phone

import android.app.Application
import android.util.Log
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.work.AnalysisParams
import com.pendulum.phone.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Point d'entree du processus.
 *
 * Deux gestes au demarrage, et un seul est evident.
 *
 * 1. **Le chien de garde** est (re)planifie. Un travail periodique WorkManager survit aux
 *    redemarrages, mais pas a une desinstallation de mise a jour ni a un effacement de donnees :
 *    le reposer a chaque lancement coute une requete et evite qu'une session ouverte reste
 *    ouverte pour toujours.
 *
 * 2. **La detection d'un changement de parametres.** Si le profil actif en base ne correspond
 *    plus au hash que le code sait produire — cas d'une mise a jour de l'application qui a
 *    change une valeur par defaut de `:algo` — alors les nuits deja en base ont ete calculees
 *    avec un algorithme que cette version ne reproduit pas. Le garde-fou 3 impose le rescore de
 *    **toutes** les nuits depuis le brut ; c'est ici qu'il est declenche, parce que c'est le
 *    seul endroit qui s'execute une fois par lancement et pas une fois par nuit.
 *
 * C'est aussi la raison pour laquelle les chunks bruts ne sont jamais supprimes automatiquement.
 * Le jour ou l'algorithme change — et il changera — la campagne passee ne vaut que ce que le
 * rescore peut en tirer.
 */
class PendulumApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        WorkScheduler.ensureWatchdog(this)

        scope.launch {
            val db = PendulumDatabase.get(this@PendulumApp)
            val active = db.paramDao().active()
            if (active != null && active.paramsHash != AnalysisParams.DEFAULT.paramsHash) {
                Log.i(
                    TAG,
                    "parametres modifies (${active.paramsHash} -> ${AnalysisParams.DEFAULT.paramsHash}) : " +
                        "rescore de toutes les nuits depuis le brut",
                )
                WorkScheduler.enqueueRescoreAll(this@PendulumApp)
            }
        }
    }

    private companion object {
        const val TAG = "PendulumApp"
    }
}
