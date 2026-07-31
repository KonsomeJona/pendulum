package com.pendulum.phone.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pendulum.phone.db.PendulumDatabase
import java.util.concurrent.TimeUnit

/**
 * La chaine de traitement d'une nuit.
 *
 * ```
 * IngestWorker  ->  AnalyzeWorker  ->  SleepFetchWorker  ->  RescoreWorker
 * (reconcilier)     (masque accel)     (echelle T+30min…)     (masque HC)
 * ```
 *
 * ### Ce que la chaine garantit, et ce qu'elle ne garantit pas
 *
 * Elle garantit l'**ordre** : on ne rescore pas avant d'avoir lu, on ne lit pas avant d'avoir
 * analyse, on n'analyse pas avant d'avoir reconcilie disque et base. Elle ne garantit **pas**
 * les delais : `SleepFetchWorker` se replanifie lui-meme le long de [FetchSchedule], et
 * `RescoreWorker` est re-enfile par lui a chaque fois que l'hypnogramme a change. Le maillon
 * `RescoreWorker` de la chaine initiale n'est donc qu'un premier essai, generalement sans effet.
 *
 * ### Les contraintes, et surtout celle qu'on ne met pas
 *
 * Aucun worker n'exige le chargeur. La combinaison « lecture Health Connect + `requiresCharging` »
 * est un piege identifie : sur un telephone qu'on ne recharge pas systematiquement le matin,
 * elle produit un worker qui ne s'execute jamais et une nuit sans denominateur, silencieusement.
 *
 * Aucun worker n'exige le reseau non plus, et pour cause : l'application ne declare pas la
 * permission `INTERNET`.
 */
object WorkScheduler {

    private const val CHAIN = "pendulum-night-chain"
    private const val FETCH = "pendulum-sleep-fetch"
    private const val RESCORE = "pendulum-rescore"
    private const val RESCORE_ALL = "pendulum-rescore-all"
    private const val WATCHDOG = "pendulum-watchdog"

    /**
     * Contraintes communes. `setRequiresBatteryNotLow(false)` est explicite : une nuit deja
     * enregistree doit etre analysee meme sur un telephone a 12 %, sinon l'utilisateur voit
     * « pas de resultat » et croit que la nuit est perdue alors qu'elle est intacte sur le disque.
     */
    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(false)
        .setRequiresCharging(false)
        .build()

    fun enqueueNightChain(context: Context, sessionHex: String) {
        val data = workDataOf(KEY_SESSION to sessionHex)
        val ingest = OneTimeWorkRequestBuilder<IngestWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val analyze = OneTimeWorkRequestBuilder<AnalyzeWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val fetch = OneTimeWorkRequestBuilder<SleepFetchWorker>()
            .setInputData(data).setConstraints(constraints).build()
        val rescore = OneTimeWorkRequestBuilder<RescoreWorker>()
            .setInputData(data).setConstraints(constraints).build()

        WorkManager.getInstance(context)
            // `KEEP` : si la chaine tourne deja pour cette nuit, la relancer en doublerait le
            // travail pour aboutir au meme resultat. Le nom inclut la session, donc deux nuits
            // differentes ne s'excluent pas.
            .beginUniqueWork("$CHAIN-$sessionHex", ExistingWorkPolicy.KEEP, ingest)
            .then(analyze)
            .then(fetch)
            .then(rescore)
            .enqueue()
    }

    /** Replanification explicite d'une tentative de lecture, au rang deja calcule. */
    fun scheduleSleepFetch(context: Context, sessionHex: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<SleepFetchWorker>()
            .setInputData(workDataOf(KEY_SESSION to sessionHex))
            .setConstraints(constraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$FETCH-$sessionHex",
            // `REPLACE` : il n'y a qu'une tentative en vol a la fois par nuit, et c'est toujours
            // la derniere calculee qui fait foi. `KEEP` figerait l'echelle sur son premier rang.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Calcule le rang suivant de l'echelle et le planifie, ou ne planifie rien si on abandonne. */
    fun scheduleNextSleepFetch(context: Context, sessionHex: String, endMs: Long, attemptsDone: Int) {
        when (val plan = FetchSchedule.plan(attemptsDone, endMs, System.currentTimeMillis())) {
            is FetchSchedule.Plan.Retry -> scheduleSleepFetch(context, sessionHex, plan.delayMs)
            is FetchSchedule.Plan.GiveUp -> Unit
        }
    }

    fun enqueueRescore(context: Context, sessionHex: String) {
        val request = OneTimeWorkRequestBuilder<RescoreWorker>()
            .setInputData(workDataOf(KEY_SESSION to sessionHex))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("$RESCORE-$sessionHex", ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * A appeler apres **tout** changement de parametre. C'est le declencheur du garde-fou 3, et
     * il n'y a volontairement pas de variante « ne rescorer que les nuits recentes » : une
     * tendance a trois points dont deux ont ete calcules autrement n'est pas une tendance
     * partielle, c'est un graphe faux.
     */
    fun enqueueRescoreAll(context: Context) {
        val request = OneTimeWorkRequestBuilder<RescoreAllWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RESCORE_ALL, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Le chien de garde tourne toutes les 30 minutes. C'est le minimum autorise par WorkManager
     * pour un travail periodique (15 min) double d'une marge : il ne fait rien tant qu'aucune
     * session n'est ouverte, et son cout est une requete SQL.
     */
    fun ensureWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Le profil de parametres actif, ou celui par defaut.
     *
     * Note importante : seul le **hash** est stocke en base, pas les valeurs. Un profil actif
     * dont le hash ne correspond pas aux valeurs par defaut du code signifie que la version
     * installee ne sait plus reproduire ce profil — cas d'une mise a jour de l'application qui a
     * change une valeur par defaut. On repart alors du profil courant du code, ce qui produit un
     * nouveau hash, donc un rescore complet. C'est le comportement voulu : il vaut mieux
     * recalculer que d'etiqueter d'anciens chiffres avec un hash qui ne les decrit plus.
     */
    suspend fun activeParams(context: Context): AnalysisParams {
        val active = PendulumDatabase.get(context).paramDao().active()
        if (active != null && active.paramsHash != AnalysisParams.DEFAULT.paramsHash) {
            // On ne relance pas le rescore global d'ici : cette fonction est appelee *par*
            // `RescoreAllWorker`, et s'auto-enfiler donnerait une boucle infinie. C'est
            // `PendulumApp.onCreate` qui detecte l'ecart de hash au demarrage et declenche.
            android.util.Log.i(
                "PendulumWork",
                "profil actif ${active.paramsHash} != ${AnalysisParams.DEFAULT.paramsHash} : rescore attendu",
            )
        }
        return AnalysisParams.DEFAULT
    }
}
