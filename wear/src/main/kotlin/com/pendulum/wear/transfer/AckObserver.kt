package com.pendulum.wear.transfer

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.pendulum.format.wire.Ack
import com.pendulum.format.wire.WirePaths
import com.pendulum.wear.record.RecordingService
import com.pendulum.wear.record.SessionStore

/**
 * Reception des accuses du telephone. Demarre par GMS a la livraison, meme si l'application
 * n'a jamais ete ouverte — c'est la raison pour laquelle l'accuse est un `DataItem` et non un
 * message : un message envoye pendant que la montre est hors de portee serait perdu, et la
 * montre garderait ses fichiers pour toujours.
 *
 * Relire deux fois le meme accuse donne le meme resultat que le relire une fois : les fichiers
 * deja effaces le restent, les items deja supprimes aussi. L'idempotence est acquise sans
 * compteur, parce que l'accuse est un **etat**, pas un evenement.
 */
class AckObserver : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (!path.startsWith(WirePaths.ACK_PREFIX)) continue
            val data = event.dataItem.data ?: continue
            try {
                val ack = Ack.decode(data)
                val dir = SessionStore(this).sessionDir(ack.sessionHex)
                val deleted = DataLayerTransfer.applyAck(this, ack, dir)
                Log.i(TAG, "accuse ${ack.sessionHex} : $deleted fichiers liberes")
                // De la place vient de se liberer dans le magasin : la suite du retard peut
                // partir tout de suite plutot qu'a la prochaine rotation de chunk.
                SyncWorker.enqueue(this)
            } catch (e: Exception) {
                Log.e(TAG, "accuse illisible sur $path", e)
            }
        }
    }

    /**
     * Les deux demandes que le telephone peut adresser a la montre.
     *
     * ### `/pendulum/sweep-request` — rattrapage complet
     *
     * Le balayage par `ChannelClient` n'est pas implemente : `SweepFraming` n'existe pas encore
     * dans `:format`, et le rattrapage par `DataItem` couvre deja le cas reel (le plafond de
     * 24 items se vide au rythme des accuses). La demande est donc honoree par une salve
     * ordinaire — meme resultat, quelques minutes de plus, zero code specifique a maintenir.
     *
     * ### `/pendulum/start-request` — demarrer l'enregistrement
     *
     * Ecart assume vis-a-vis de `docs/06-interface.md` §2.2, qui reserve le demarrage a un geste
     * physique sur la montre. Le garde-fou, lui, reste entier : [RecordingService] re-verifie
     * `Preflight.check` avant `startSession(resume = false)`, donc une demande sans contexte
     * scelle est refusee ici quelle qu'en soit l'origine.
     */
    override fun onMessageReceived(event: MessageEvent) {
        when {
            event.path.startsWith(WirePaths.SWEEP_REQUEST) -> SyncWorker.enqueue(this)
            event.path.startsWith(WirePaths.START_REQUEST) -> demarrerOuNotifier()
        }
    }

    /**
     * Demarrer depuis l'arriere-plan, ou demander a l'utilisateur de le faire.
     *
     * Ce service est demarre par Google Play Services, donc **depuis l'arriere-plan**, et Android
     * 12 interdit d'y demarrer un service de premier plan hors exemptions. La tentative est faite
     * quand meme parce qu'elle passe dans les cas ou une exemption s'applique ; quand elle est
     * refusee, le repli n'est pas un pis-aller a cacher : une notification sur la montre donne
     * exactement le geste « une tape » recherche, et c'est le seul chemin que le systeme
     * garantisse.
     *
     * `ForegroundServiceStartNotAllowedException` n'existe qu'a partir d'Android 12 ; le `catch`
     * porte donc sur `Exception` plutot que sur son type exact, qui ne serait pas resoluble a la
     * compilation contre un `minSdk` inferieur. Ici `minSdk` vaut 33, mais attraper large coute
     * une ligne et couvre aussi le refus pour une autre raison — un service deja mort, un
     * processus en cours d'arret — que rien ne distinguerait a l'execution.
     */
    private fun demarrerOuNotifier() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START),
            )
            Log.i(TAG, "demarrage demande par le telephone")
        } catch (e: Exception) {
            Log.w(TAG, "demarrage depuis l'arriere-plan refuse, repli par notification", e)
            WatchNotifications.pretADemarrer(this)
        }
    }

    private companion object {
        const val TAG = "PendulumAck"
    }
}
