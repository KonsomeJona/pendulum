package com.pendulum.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pendulum.wear.record.RecordingService
import com.pendulum.wear.record.SessionStore
import com.pendulum.wear.temps.Durees
import java.util.concurrent.TimeUnit

/**
 * Filet de securite contre le tueur de memoire.
 *
 * `START_STICKY` ne suffit pas. Il promet une recreation du service « quand les ressources le
 * permettent », ce qui, en veille profonde, peut vouloir dire au matin. Un enregistrement mort a
 * 2 h et redemarre a 7 h, c'est une nuit perdue avec un fichier qui a l'air normal — la pire des
 * pannes, celle qui ne se voit pas.
 *
 * Le watchdog relit le marqueur de session toutes les quinze minutes : si une nuit est declaree
 * active et que le service ne tourne pas, il le relance. Quinze minutes est le minimum d'un
 * `PeriodicWorkRequest`, et c'est aussi la granularite de perte deja acceptee par ailleurs.
 *
 * **Ce plancher rend ce chemin incompressible.** Le banc peut diviser la periode demandee
 * (`Durees.periodeWatchdogMs`), WorkManager la ramenera a quinze minutes reelles. Un banc qui
 * veut exercer la reprise doit donc declencher le travail lui-meme, et il ne mesurera de toute
 * facon jamais la vraie propriete en jeu : qu'un travail prevu dans quinze minutes s'execute
 * parfois dans quarante-cinq.
 *
 * **Limite assumee.** Depuis Android 12, une application en arriere-plan ne peut pas toujours
 * demarrer un service de premier plan, et un worker ordinaire ne fait pas partie des exemptions.
 * La parade sure serait une alarme exacte, donc `SCHEDULE_EXACT_ALARM` — une permission de plus,
 * pour un chemin purement defensif, dans une application dont l'absence de permissions est un
 * argument verifiable. Le choix est donc : tenter, journaliser l'echec, et compter sur les deux
 * autres filets (`START_STICKY`, et la reprise sur `BOOT_COMPLETED`). Si la mesure montre que ce
 * chemin echoue reellement en pleine nuit, c'est la permission qu'il faudra ajouter, pas le
 * watchdog qu'il faudra supprimer.
 */
object Watchdog {

    const val UNIQUE_NAME = "pendulum-watchdog"

    fun start(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            // L'intention est de **reinitialiser la periode** quand une nuit commence, sinon le
            // premier controle peut tomber quatorze minutes trop tard. `UPDATE` ne fait pas
            // cela : il remplace la specification en conservant l'echeancier de la demande deja
            // en attente (la periode n'ayant pas change, il n'y a rien a recalculer), donc il
            // etait ici un quasi no-op. `CANCEL_AND_REENQUEUE` est la seule politique qui annule
            // l'instance existante et repart de zero — c'est celle que decrivait le commentaire.
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            PeriodicWorkRequestBuilder<WatchdogWorker>(
                Durees.ACTIVES.periodeWatchdogMs,
                TimeUnit.MILLISECONDS,
            ).build(),
        )
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
    }

    /**
     * Relance d'une session **neuve** apres un `onTimeout` : le service a du s'arreter, mais la
     * nuit, elle, n'est pas finie. Trente secondes de delai laissent le systeme achever son
     * arret avant qu'on redemande un service de premier plan.
     */
    fun restartAfterTimeout(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            RESTART_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchdogWorker>()
                .setInitialDelay(Durees.ACTIVES.relanceApresTimeoutMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_RESTART to true))
                .build(),
        )
    }

    const val RESTART_NAME = "pendulum-restart"
    const val KEY_RESTART = "restart"
}

class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        if (inputData.getBoolean(Watchdog.KEY_RESTART, false)) {
            return start(ctx, RecordingService.ACTION_START)
        }

        val marker = SessionStore(ctx).readMarker()
            ?: return Result.success() // aucune nuit active : rien a surveiller

        if (RecordingService.isRunning) return Result.success()

        // Les memes garde-fous que la reprise apres reboot, et desormais le meme code : on ne
        // relance jamais un enregistrement dont l'heure est passee, sous pretexte qu'un marqueur
        // traine. Voir `SessionMarker.estPerimee`.
        if (marker.estPerimee(System.currentTimeMillis())) return Result.success()

        return start(ctx, RecordingService.ACTION_RESUME)
    }

    private fun start(ctx: Context, action: String): Result = try {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, RecordingService::class.java).setAction(action),
        )
        Result.success()
    } catch (e: Exception) {
        Log.e(TAG, "relance du service refusee depuis l'arriere-plan", e)
        Result.retry()
    }

    private companion object {
        const val TAG = "PendulumWatchdog"
    }
}
