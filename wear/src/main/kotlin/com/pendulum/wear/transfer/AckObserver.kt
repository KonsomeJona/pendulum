package com.pendulum.wear.transfer

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.pendulum.format.wire.Ack
import com.pendulum.format.wire.WirePaths
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
     * `/pendulum/sweep-request` — le telephone demande un rattrapage complet.
     *
     * Le balayage par `ChannelClient` n'est pas implemente : `SweepFraming` n'existe pas encore
     * dans `:format`, et le rattrapage par `DataItem` couvre deja le cas reel (le plafond de
     * 24 items se vide au rythme des accuses). La demande est donc honoree par une salve
     * ordinaire — meme resultat, quelques minutes de plus, zero code specifique a maintenir.
     */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path.startsWith(WirePaths.SWEEP_REQUEST)) {
            SyncWorker.enqueue(this)
        }
    }

    private companion object {
        const val TAG = "PendulumAck"
    }
}
