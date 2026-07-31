package com.pendulum.wear.transfer

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pendulum.format.wire.StopReason
import com.pendulum.wear.record.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rattrapage du transfert **hors service** : au matin, apres un reboot, apres un retour de
 * portee, ou quand le plafond d'items en vol s'est libere.
 *
 * Il ne double pas le service : pendant l'enregistrement c'est le service qui pousse, a chaque
 * troisieme chunk ferme. Ce worker existe pour les moments ou plus personne n'enregistre et ou
 * il reste des fichiers sur le disque — c'est-a-dire exactement le scenario « le telephone etait
 * eteint toute la nuit ».
 *
 * **Aucune contrainte reseau** : le Data Layer n'est pas le reseau, et une contrainte qui ne
 * correspond a rien empeche simplement le worker de tourner. La seule contrainte est
 * energetique, et elle est verifiee ici plutot que declaree, parce que `WorkManager` ne sait
 * exprimer que « batterie pas faible » (15 %) la ou la regle est « chargeur **ou** batterie
 * au-dessus de 30 % ».
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charging = bm?.isCharging ?: false
        if (!charging && pct < 30) {
            // Reessayer plutot qu'echouer : le rattrapage n'est jamais urgent, et il aura lieu
            // sur le chargeur du matin de toute facon.
            return@withContext Result.retry()
        }

        val store = SessionStore(ctx)
        val activeHex = store.readMarker()?.sessionHex
        val finalizeHex = inputData.getString(KEY_FINALIZE_SESSION)

        if (finalizeHex != null) {
            finalizeCrashedSession(ctx, store, finalizeHex)
        }

        val dirs = store.chunksRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in dirs) {
            val hex = dir.name
            val files = dir.listFiles { f: File -> f.name.endsWith(".pendulum") } ?: emptyArray()
            if (files.isEmpty()) {
                // Session entierement acquittee : le repertoire ne contient plus que son
                // sidecar, on le libere. C'est la purge de quota, faite au fil de l'eau.
                if (hex != activeHex) dir.deleteRecursively()
                continue
            }
            try {
                DataLayerTransfer.pushChunks(ctx, hex, dir, urgentLast = true)
            } catch (e: Exception) {
                Log.w(TAG, "poussee de $hex impossible, on reessaiera", e)
                return@withContext Result.retry()
            }
        }
        Result.success()
    }

    /**
     * Une session dont le marqueur existe encore alors que plus rien n'enregistre n'a pas ete
     * fermee par une condition d'arret : elle a ete tuee. On la ferme donc explicitement avec
     * `stopReason = CRASH` — le telephone doit pouvoir distinguer « la nuit n'est pas finie » de
     * « la montre ne repond plus », et deviner n'est pas une reponse acceptable.
     */
    private fun finalizeCrashedSession(ctx: Context, store: SessionStore, hex: String) {
        val marker = store.readMarker() ?: return
        if (marker.sessionHex != hex) return
        try {
            DataLayerTransfer.closeSession(
                ctx = ctx,
                marker = marker,
                endWallMs = System.currentTimeMillis(),
                totalChunks = marker.lastChunkIndex + 1,
                reason = StopReason.CRASH,
            )
            store.clearActive()
        } catch (e: Exception) {
            Log.w(TAG, "fermeture de la session $hex impossible", e)
        }
    }

    companion object {
        private const val TAG = "PendulumSync"
        const val UNIQUE_NAME = "pendulum-transfer"
        const val KEY_FINALIZE_SESSION = "finalize_session"

        fun enqueue(ctx: Context, finalizeSessionHex: String? = null) {
            val data = Data.Builder().apply {
                finalizeSessionHex?.let { putString(KEY_FINALIZE_SESSION, it) }
            }.build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_NAME,
                // REPLACE : une demande plus recente porte forcement plus d'information que
                // celle qui attendait, et deux rattrapages simultanes n'ont aucun sens.
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data).build(),
            )
        }
    }
}
