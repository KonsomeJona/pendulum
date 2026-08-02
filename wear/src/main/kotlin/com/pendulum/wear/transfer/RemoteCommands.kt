package com.pendulum.wear.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Ouvrir l'application telephone depuis la montre.
 *
 * ### Le probleme que ca resout
 *
 * Le bloqueur le plus frequent du preflight est « contexte du soir non scelle », et il se leve
 * **sur l'autre appareil**. Jusqu'ici la montre disait « remplissez le formulaire du soir sur le
 * telephone » et s'arretait la : a l'utilisateur de reposer la montre, de trouver son telephone,
 * de deverrouiller, de retrouver l'application. Au coucher, ecran a la cheville.
 *
 * ### Pourquoi ce chemin et pas l'evident
 *
 * L'evident serait : la montre envoie un message, le telephone le recoit dans son
 * `WearableListenerService` et appelle `startActivity`. **Ce chemin est casse depuis Android 10** —
 * le service tourne en arriere-plan, le lancement est bloque, et le seul temoin est une ligne
 * `Background activity launch blocked!` dans les journaux du systeme. Android 14 puis 15 ont
 * encore durci les regles. C'est un echec silencieux, donc le pire genre.
 *
 * [RemoteActivityHelper] passe, lui, parce que le lancement est execute cote telephone par les
 * services Google Play et non par notre processus : il beneficie de l'exemption accordee aux
 * composants du systeme. Sa contrainte est de n'accepter qu'un `ACTION_VIEW` avec une URI
 * navigable, d'ou le lien profond declare sur `MainActivity` cote telephone.
 */
object RemoteCommands {

    /**
     * Ouvre le formulaire du soir sur le telephone.
     *
     * @return `false` si aucun appareil n'a pu etre atteint — telephone eteint, hors de portee, ou
     *   application compagnon absente. L'appelant doit le dire : annoncer un succes alors que rien
     *   ne s'est ouvert envoie quelqu'un chercher un ecran qui n'est pas apparu.
     */
    suspend fun ouvrirLeTelephone(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // `get` bloquant plutot que `await` : ce dernier viendrait de
            // `kotlinx-coroutines-guava`, une dependance de plus pour convertir un
            // `ListenableFuture` alors qu'on est deja sur `Dispatchers.IO` et qu'un delai
            // explicite vaut mieux qu'une attente sans borne.
            RemoteActivityHelper(ctx).startRemoteActivity(
                Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse(LIEN_SOIR)),
            ).get(DELAI_S, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            // Repli : un message, que le telephone transforme en notification. Une notification
            // est le seul chemin garanti pour reveiller une application depuis l'arriere-plan,
            // parce que le tap de l'utilisateur est une exemption explicite au blocage.
            Log.w(TAG, "ouverture distante refusee, repli par notification", e)
            envoyerATousLesNoeuds(ctx, WirePaths.OPEN_PHONE)
        }
    }

    /**
     * Envoie un message a tous les noeuds connectes.
     *
     * A tous, et non au premier : il peut y avoir plusieurs telephones appaires, et deviner lequel
     * est le bon a partir d'un identifiant de noeud n'est pas quelque chose qu'on peut faire
     * correctement. Les autres ignorent un message qu'ils ne savent pas traiter.
     */
    private fun envoyerATousLesNoeuds(ctx: Context, chemin: String): Boolean = try {
        val noeuds = Tasks.await(
            Wearable.getNodeClient(ctx).connectedNodes,
            DELAI_S,
            TimeUnit.SECONDS,
        )
        noeuds.forEach { noeud ->
            Tasks.await(
                Wearable.getMessageClient(ctx).sendMessage(noeud.id, chemin, ByteArray(0)),
                DELAI_S,
                TimeUnit.SECONDS,
            )
        }
        noeuds.isNotEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "aucun noeud joignable pour $chemin", e)
        false
    }

    /**
     * Lien profond vers le formulaire du soir, cote telephone.
     *
     * Schema personnalise et non `https` verifie : un App Link demanderait d'heberger un
     * `assetlinks.json`, ce qui est faisable — le site de documentation existe — mais ajoute une
     * dependance a un domaine pour une application qui ne declare meme pas la permission Internet.
     * Le prix du schema personnalise est qu'un constructeur peut afficher une boite « ouvrir
     * avec » au premier usage.
     */
    const val LIEN_SOIR = "pendulum://tonight"

    private const val TAG = "PendulumRemote"
    private const val DELAI_S = 15L
}
