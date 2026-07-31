package com.pendulum.wear.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.pendulum.wear.Watchdog
import com.pendulum.wear.transfer.SyncWorker
import java.util.Calendar

/**
 * Reprise apres redemarrage de la montre ou remplacement de l'application.
 *
 * Le demarrage d'un service de premier plan depuis `BOOT_COMPLETED` est interdit aux types
 * `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` et `microphone` pour une
 * application ciblant Android 15 ou plus. `health` n'est pas sur cette liste : c'est ce qui rend
 * ce chemin legal, et c'est aussi la raison pour laquelle le service est de ce type.
 *
 * **Cinq conditions, pas une.** Ne tester que la fenetre de quatorze heures redemarre un
 * enregistrement a huit heures du matin, sur le chargeur, apres un reboot nocturne — et pollue
 * la nuit exactement comme on cherche a l'eviter. Quand les conditions ne sont pas reunies, on
 * ne reprend pas, mais on **finalise** : la session passe a `CLOSED` avec `stopReason = CRASH`
 * et le reliquat part vers le telephone. Ne rien faire laisserait une session `OPEN` pour
 * toujours du cote du telephone.
 *
 * **Angle mort connu.** Si la montre a un code de verrouillage, `BOOT_COMPLETED` n'est diffuse
 * qu'apres deverrouillage et le stockage credential-encrypted est inaccessible avant : une
 * montre qui redemarre a 3 h **au poignet** reste verrouillee jusqu'au matin, donc aucune
 * reprise n'a lieu. `directBootAware` n'est deliberement pas utilise — il imposerait de deplacer
 * les chunks vers un stockage device-encrypted, plus expose et plus complexe, pour un gain
 * incertain. Tant que ce delai n'est pas mesure, c'est l'arret propre sur batterie faible qui
 * reste la principale protection contre la perte d'une nuit.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val marker = SessionStore(context).readMarker()
        if (marker == null) {
            // Rien en cours : on relance quand meme le rattrapage, un reliquat non acquitte peut
            // dormir sur le disque depuis la veille.
            SyncWorker.enqueue(context)
            return
        }

        if (shouldResume(context, marker, System.currentTimeMillis())) {
            Log.i(TAG, "reprise de la session ${marker.sessionHex}")
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_RESUME),
            )
            Watchdog.start(context)
        } else {
            Log.i(TAG, "pas de reprise : finalisation de ${marker.sessionHex}")
            SyncWorker.enqueue(context, marker.sessionHex)
        }
    }

    private fun shouldResume(context: Context, m: SessionMarker, nowMs: Long): Boolean {
        if (nowMs >= m.startWallMs + 14 * 3_600_000L) return false
        if (nowMs >= m.plannedStopWallMs) return false

        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val localMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (localMinutes >= m.stopAtLocalMinutes) return false

        // Sur le chargeur, la nuit est finie par definition : la reprendre reviendrait a
        // enregistrer un plan de travail.
        val bm = context.getSystemService(BatteryManager::class.java)
        if (bm?.isCharging == true) return false

        return true
    }

    private companion object {
        const val TAG = "PendulumBoot"
    }
}
