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
 * Les deux declenchements gratuits de la lecture Health Connect.
 *
 * [FetchSchedule] fait un repli exponentiel — 30 min, 1 h, 2 h, 4 h, 8 h — parce qu'il ne sait pas
 * quand l'hypnogramme arrivera. Mais la synchronisation n'est pas un evenement aleatoire : elle
 * est **correlee a l'usage**. La montre de poignet pousse quand elle est sur le chargeur, et
 * l'application source ecrit quand on l'ouvre, c'est-a-dire souvent juste avant qu'on ouvre
 * Pendulum. Deux signaux que le systeme donne pour rien, et plusieurs heures de latence percue
 * en moins.
 *
 * La lecture declenchee ici **ne consomme pas l'echelle** : voir
 * [FetchSchedule.INDEX_OPPORTUNISTE] et `HcSnapshotDao.attemptCount`.
 */
object DeclencheurOpportuniste {

    private const val TRAVAIL = "pendulum-sleep-fetch-opportuniste"

    /**
     * Enfile une lecture immediate pour chaque nuit qui attend encore son hypnogramme.
     *
     * Le filtre est en deux temps et l'ordre compte : d'abord les nuits fermees depuis moins de
     * 36 h (une requete indexee), puis, parmi elles, celles dont aucun `hc_snapshot` n'a retenu
     * d'enregistrement. La seconde condition est ce qui evite de relire pour rien une nuit deja
     * complete a chaque branchement du chargeur.
     */
    suspend fun declencher(context: Context, maintenantMs: Long = System.currentTimeMillis()) {
        val db = PendulumDatabase.get(context)
        val nuits = db.nightDao().endedSince(maintenantMs - FetchSchedule.GIVE_UP_MS)
        for (s in nuits) {
            val fin = s.endWallMs ?: continue
            val dernier = db.hcSnapshotDao().latest(s.sessionHex)
            // Un enregistrement deja retenu : la nuit a son denominateur independant. On continue
            // quand meme l'echelle planifiee ailleurs — un fournisseur peut reecrire une session —
            // mais cela ne justifie pas une lecture a chaque branchement.
            if (dernier?.selectedRecordId != null) continue
            if (!FetchSchedule.opportunisteAdmissible(fin, maintenantMs, dernier?.fetchedAtMs)) continue

            val request = OneTimeWorkRequestBuilder<SleepFetchWorker>()
                .setInputData(workDataOf(KEY_SESSION to s.sessionHex, KEY_OPPORTUNISTE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$TRAVAIL-${s.sessionHex}",
                // `KEEP` : si une lecture opportuniste est deja en vol pour cette nuit, en enfiler
                // une seconde ne peut que produire deux lignes `hc_snapshot` identiques.
                ExistingWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "${s.sessionHex} : lecture opportuniste enfilee")
        }
    }
}

/**
 * Le chargeur vient d'etre branche.
 *
 * `ACTION_POWER_CONNECTED` fait partie des rares diffusions **exemptees** de la restriction
 * d'enregistrement au manifeste introduite par Android 8 : elle continue de reveiller une
 * application fermee, ce qui est exactement le cas d'usage — le telephone pose sur son socle a
 * cote de la montre, l'application non ouverte depuis le matin.
 *
 * `goAsync` plutot qu'un travail synchrone : la lecture touche la base, et un `onReceive` qui
 * bloque plus de dix secondes est tue par le systeme. Le `PendingResult` maintient le processus
 * vivant le temps de la requete, qui se compte en millisecondes.
 */
class PowerConnectedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        val app = context.applicationContext
        val fini = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DeclencheurOpportuniste.declencher(app)
            } catch (t: Throwable) {
                Log.e(TAG, "declencheur opportuniste (chargeur) echoue", t)
            } finally {
                fini.finish()
            }
        }
    }
}
