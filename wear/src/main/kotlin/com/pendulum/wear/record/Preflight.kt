package com.pendulum.wear.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import java.util.Locale
import android.util.Log
import androidx.core.content.ContextCompat
import com.pendulum.wear.transfer.DataLayerTransfer
import java.io.File

/**
 * Verification d'avant-nuit. C'est le seul moment ou l'utilisateur regarde la montre, donc le
 * seul moment ou un message a une chance d'etre lu : chaque bloqueur est formule en action, et
 * la separation bloqueurs / avertissements est une decision, pas une nuance de couleur.
 *
 * **Le telephone injoignable n'est jamais bloquant.** Toute l'architecture de transfert existe
 * pour que ce cas soit sans consequence : le signaler comme une erreur serait mentir a
 * l'utilisateur et l'inciter a ne pas enregistrer sa nuit.
 */
object Preflight {

    /** En dessous, on refuse de commencer : une nuit pese ~9 Mo, mais la marge protege des
     *  reliquats non acquittes qu'on ne supprimera jamais de force. */
    const val MIN_FREE_BYTES = 300L * 1024 * 1024

    /** Plafond du repertoire de chunks : ~22 nuits. */
    const val CHUNK_DIR_CAP_BYTES = 200L * 1024 * 1024

    const val PREFS = "pendulum"

    /** Pose par le service quand `startForeground` a ete refuse : le seul moyen de faire
     *  remonter a l'ecran du coucher un echec qui, sinon, ne vit que dans logcat. */
    const val PREF_FGS_REFUSED = "fgs_refused"

    fun check(ctx: Context): PreflightResult {
        val blockers = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()

        val sm = ctx.getSystemService(SensorManager::class.java)
        val wakeUp = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val sensor = wakeUp ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) blockers += Issue(IssueId.NO_ACCELEROMETER)

        // Sans notification, le systeme ne peut pas afficher le service de premier plan, et il
        // finit par l'arreter : la permission n'est pas cosmetique.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            blockers += Issue(IssueId.NOTIFICATIONS_DENIED)
        }

        val store = SessionStore(ctx)
        val pending = pendingChunkCount(store.chunksRoot)
        val free = ctx.filesDir.usableSpace
        val occupied = dirSize(store.chunksRoot)
        if (free < MIN_FREE_BYTES || occupied > CHUNK_DIR_CAP_BYTES * 95 / 100) {
            // Refuser de commencer une nuit vaut mieux que d'en ecraser une silencieusement.
            blockers += Issue(IssueId.STORAGE_FULL, listOf(formatBytes(free), pending.toString()))
        }

        val nightKey = DataLayerTransfer.nightKey(System.currentTimeMillis())
        val sealed = try {
            DataLayerTransfer.isEveningContextSealed(ctx, nightKey)
        } catch (e: Exception) {
            Log.w(TAG, "lecture du verrou de contexte impossible", e)
            false
        }
        if (!sealed) blockers += Issue(IssueId.CONTEXT_NOT_SEALED)

        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_FGS_REFUSED, false)) {
            blockers += Issue(IssueId.FGS_REFUSED)
        }

        val bm = ctx.getSystemService(BatteryManager::class.java)
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (battery in 0..39) warnings += Issue(IssueId.LOW_BATTERY, listOf(battery.toString()))
        if (sensor != null && wakeUp == null) warnings += Issue(IssueId.NO_WAKEUP_SENSOR)
        if (pending > 0) warnings += Issue(IssueId.PENDING_SYNC, listOf(pending.toString()))
        if (!phoneReachable(ctx)) warnings += Issue(IssueId.PHONE_UNREACHABLE)

        return PreflightResult(blockers, warnings, battery, free, pending)
    }

    private fun phoneReachable(ctx: Context): Boolean = try {
        val nodes = com.google.android.gms.tasks.Tasks.await(
            com.google.android.gms.wearable.Wearable.getNodeClient(ctx).connectedNodes,
            10,
            java.util.concurrent.TimeUnit.SECONDS,
        )
        nodes.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    fun pendingChunkCount(chunksRoot: File): Int =
        chunksRoot.listFiles()?.sumOf { dir ->
            dir.listFiles { f: File -> f.name.endsWith(".pendulum") }?.size ?: 0
        } ?: 0

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(Locale.UK, bytes / 1024.0 / 1024 / 1024)
        else -> "%d MB".format(Locale.UK, bytes / 1024 / 1024)
    }

    private const val TAG = "PendulumPreflight"
}

enum class IssueId {
    // bloqueurs
    CONTEXT_NOT_SEALED,
    NOTIFICATIONS_DENIED,
    NO_ACCELEROMETER,
    STORAGE_FULL,
    FGS_REFUSED,

    // avertissements
    LOW_BATTERY,
    PHONE_UNREACHABLE,
    NO_WAKEUP_SENSOR,
    PENDING_SYNC,
}

/** Un probleme et ses arguments deja formates. Les libelles vivent dans `strings.xml`. */
data class Issue(val id: IssueId, val args: List<String> = emptyList())

data class PreflightResult(
    val blockers: List<Issue>,
    val warnings: List<Issue>,
    val batteryPct: Int,
    val freeBytes: Long,
    val pendingChunks: Int,
) {
    val canStart: Boolean get() = blockers.isEmpty()
}
