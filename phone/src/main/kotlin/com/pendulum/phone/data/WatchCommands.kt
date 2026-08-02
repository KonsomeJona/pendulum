package com.pendulum.phone.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Les ordres que le telephone envoie a la montre.
 *
 * ### Ce qui manquait
 *
 * `WirePaths.SWEEP_REQUEST` est **ecoute par la montre depuis le debut** — `AckObserver` y
 * enfile `SyncWorker`, qui republie tout ce qui n'a pas ete acquitte — et **aucun code du
 * telephone ne l'emettait**. Le chemin de rattrapage du protocole existait donc entierement,
 * cable des deux cotes sauf du cote qui declenche. La consequence n'etait pas une erreur mais
 * une attente : apres une coupure Bluetooth, les chunks restaient sur la montre jusqu'a ce
 * qu'elle decide d'elle-meme de reessayer.
 *
 * ### Un message, pas un `DataItem`
 *
 * Un `DataItem` est un etat replique : il persiste, et republier la meme charge utile ne
 * declenche rien du tout, puisque rien n'a change. Un ordre n'est pas un etat — « balaie
 * maintenant » demande a etre entendu deux fois de suite si on le dit deux fois. `MessageClient`
 * a exactement cette semantique, et sa contrepartie est qu'il echoue quand la montre est hors de
 * portee. C'est acceptable ici : le geste est explicite, l'utilisateur est devant l'ecran, et
 * l'echec se dit.
 */
object WatchCommands {

    /**
     * Demande a la montre de pousser tout ce qu'elle detient encore.
     *
     * @return `false` si aucun noeud n'a pu etre atteint. L'appelant doit le dire : annoncer un
     *   balayage qui n'a jamais ete demande fait attendre des donnees qui ne viendront pas.
     */
    suspend fun demanderLeBalayage(context: Context): Boolean =
        envoyerATousLesNoeuds(context, WirePaths.SWEEP_REQUEST)

    /**
     * Envoie un message a **tous** les noeuds connectes, et non au premier.
     *
     * Plusieurs montres peuvent etre appairees, et deviner laquelle porte l'enregistrement a
     * partir d'un identifiant de noeud n'est pas quelque chose qu'on peut faire correctement.
     * Celles qui ne savent pas traiter le chemin l'ignorent — c'est le comportement du Data
     * Layer, pas une tolerance de notre part. Symetrique de `wear/transfer/RemoteCommands.kt`.
     */
    private suspend fun envoyerATousLesNoeuds(context: Context, chemin: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val noeuds = Tasks.await(
                    Wearable.getNodeClient(context).connectedNodes,
                    DELAI_S,
                    TimeUnit.SECONDS,
                )
                noeuds.forEach { noeud ->
                    Tasks.await(
                        Wearable.getMessageClient(context).sendMessage(noeud.id, chemin, ByteArray(0)),
                        DELAI_S,
                        TimeUnit.SECONDS,
                    )
                }
                noeuds.isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "aucun noeud joignable pour $chemin", e)
                false
            }
        }

    private const val TAG = "PendulumCommands"
    private const val DELAI_S = 15L
}
