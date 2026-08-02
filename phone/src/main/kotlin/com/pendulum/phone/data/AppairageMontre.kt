package com.pendulum.phone.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Les trois etats de l'appairage, et la raison pour laquelle ce n'est pas un booleen.
 *
 * Chacun se repare a un endroit different, et l'erreur classique de cet ecran est de confondre
 * les deux premiers : proposer d'installer l'application sur une montre qui n'est appairee a
 * rien produit une installation que personne ne verra jamais, et laisse l'utilisateur convaincu
 * d'avoir fait ce qu'on lui demandait.
 */
enum class EtatAppairage {
    /**
     * Aucun noeud connecte : **aucune montre n'est appairee a ce telephone**. La reparation est
     * dans l'application compagnon du constructeur, pas dans un magasin d'applications.
     */
    AUCUNE_MONTRE,

    /**
     * Une montre est appairee, mais rien ne repond a la capacite Pendulum. Deux causes
     * indiscernables de l'exterieur — application absente, ou montre hors de portee Bluetooth —
     * et une seule action utile dans les deux cas : proposer l'installation, et attendre.
     */
    APP_ABSENTE_OU_HORS_PORTEE,

    /** Une montre joignable annonce la capacite. C'est le seul etat qui valide l'etape. */
    PRETE,
}

/** Ce que l'ecran d'appairage sait de la montre. Le nom est `null` tant qu'aucun noeud n'existe. */
data class EtatMontre(val etat: EtatAppairage, val nom: String? = null)

/**
 * Detection de la montre depuis le telephone.
 *
 * ### Pourquoi la capacite et pas la liste des noeuds
 *
 * `NodeClient.connectedNodes` repond a « une montre est-elle appairee », et c'est tout. Il ne dit
 * rien de l'application qui tourne dessus : une Galaxy Watch appairee mais sans Pendulum y figure
 * exactement comme une montre prete. Seul `CapabilityClient` repond a la question qui compte, et
 * seulement si les deux cotes declarent leur capacite dans `res/values/wear.xml`.
 *
 * ### Ce qui manque encore, et qu'il faut savoir en lisant ce fichier
 *
 * Le module `:wear` ne declare **pas** encore [CAPACITE_MONTRE]. Tant que ce fichier de ressources
 * n'existe pas de l'autre cote, [lire] rendra [EtatAppairage.APP_ABSENTE_OU_HORS_PORTEE] meme sur
 * une montre ou l'application tourne. C'est le seul defaut assume ici, et il est reparable en
 * trois lignes de XML cote montre — pas en changeant ce code.
 */
object AppairageMontre {

    /** Ce que le telephone annonce. Doit correspondre a `res/values/wear.xml`. */
    const val CAPACITE_TELEPHONE = "pendulum_phone_app"

    /** Ce que le telephone cherche. Doit correspondre au `wear.xml` du module `:wear`. */
    const val CAPACITE_MONTRE = "pendulum_watch_app"

    /**
     * Page du magasin ouverte **sur la montre**. `market://` et non `https://play.google.com/…` :
     * l'URI est resolue par le Play Store de la montre, qui est le seul a pouvoir y installer
     * quoi que ce soit.
     */
    private const val LIEN_MAGASIN_MONTRE = "market://details?id=com.pendulum"

    /**
     * La classification, isolee de tout appel Android pour etre testable.
     *
     * @param noeudsConnectes identifiants rendus par `NodeClient.connectedNodes`.
     * @param noeudsCapables identifiants rendus par `CapabilityClient.getCapability(…,
     *   FILTER_REACHABLE)`.
     *
     * Un noeud capable l'emporte meme si la liste des noeuds connectes est vide : les deux
     * lectures ne sont pas atomiques, et un noeud qui annonce la capacite *et* est declare
     * joignable est une preuve plus forte qu'une liste vide lue une milliseconde plus tot.
     */
    fun classer(noeudsConnectes: Set<String>, noeudsCapables: Set<String>): EtatAppairage = when {
        noeudsCapables.isNotEmpty() -> EtatAppairage.PRETE
        noeudsConnectes.isEmpty() -> EtatAppairage.AUCUNE_MONTRE
        else -> EtatAppairage.APP_ABSENTE_OU_HORS_PORTEE
    }

    /**
     * Une lecture ponctuelle des deux clients.
     *
     * L'echec est classe [EtatAppairage.AUCUNE_MONTRE] et non « inconnu » : quand les services
     * Wearable levent, c'est presque toujours que Google Play Services ou l'application Wear OS
     * manquent, et l'action a proposer est alors exactement celle du premier etat — passer par
     * l'application compagnon. Un quatrieme etat « on ne sait pas » n'ouvrirait aucune action de
     * plus et ferait un ecran de moins comprehensible.
     */
    suspend fun lire(context: Context): EtatMontre = withContext(Dispatchers.IO) {
        try {
            val noeuds = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes, DELAI_S, TimeUnit.SECONDS,
            )
            val capables = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(CAPACITE_MONTRE, CapabilityClient.FILTER_REACHABLE),
                DELAI_S,
                TimeUnit.SECONDS,
            ).nodes

            EtatMontre(
                etat = classer(
                    noeudsConnectes = noeuds.map { it.id }.toSet(),
                    noeudsCapables = capables.map { it.id }.toSet(),
                ),
                nom = capables.firstOrNull()?.displayName ?: noeuds.firstOrNull()?.displayName,
            )
        } catch (e: Exception) {
            Log.w(TAG, "lecture de l'appairage impossible", e)
            EtatMontre(EtatAppairage.AUCUNE_MONTRE)
        }
    }

    /**
     * L'etat, relu a chaque changement de capacite.
     *
     * C'est ce qui permet de **ne pas bloquer pendant l'installation** : l'utilisateur part
     * installer l'application sur la montre, l'ecran reste ouvert, et l'etape se coche d'elle-meme
     * quand la capacite apparait. Sans cet ecouteur il faudrait un bouton « j'ai fini » — donc un
     * bouton qu'on appuie trop tot, donc un ecran qui dit non a quelqu'un qui a fait ce qu'on lui
     * demandait.
     */
    fun observer(context: Context): Flow<EtatMontre> = callbackFlow {
        val client = Wearable.getCapabilityClient(context)

        val ecouteur = CapabilityClient.OnCapabilityChangedListener { _: CapabilityInfo ->
            // On relit tout plutot que de se fier a l'evenement : `CapabilityInfo` ne porte que
            // les noeuds capables, et distinguer « aucune montre » de « montre sans
            // l'application » demande aussi la liste des noeuds connectes.
            launch { trySend(lire(context)) }
        }

        client.addListener(ecouteur, CAPACITE_MONTRE)
        trySend(lire(context))

        awaitClose { client.removeListener(ecouteur, CAPACITE_MONTRE) }
    }

    /**
     * Ouvre la fiche Play Store de Pendulum **sur la montre**.
     *
     * `RemoteActivityHelper` et non un `startActivity` local : le lancement doit avoir lieu de
     * l'autre cote, et il est execute la-bas par les services Google Play, ce qui est le seul
     * chemin qui ne se fasse pas bloquer. Symetrique de `wear/transfer/RemoteCommands.kt`.
     *
     * @return `false` si rien n'a pu etre ouvert. L'appelant doit le dire : annoncer une page
     *   ouverte qui ne l'est pas envoie quelqu'un chercher un ecran sur sa montre.
     */
    suspend fun ouvrirLeMagasinSurLaMontre(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val noeuds = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes, DELAI_S, TimeUnit.SECONDS,
            )
            if (noeuds.isEmpty()) return@withContext false

            val intention = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse(LIEN_MAGASIN_MONTRE))

            // Toutes les montres appairees, et non la premiere : deviner laquelle l'utilisateur
            // portera cette nuit n'est pas quelque chose qu'on peut faire correctement, et une
            // fiche de magasin ouverte en trop ne coute rien.
            //
            // `get` bloquant plutot que `await` : ce dernier viendrait de `kotlinx-coroutines-guava`,
            // une dependance de plus pour convertir un `ListenableFuture` alors qu'on est deja sur
            // `Dispatchers.IO`.
            var ouverte = false
            noeuds.forEach { noeud ->
                runCatching {
                    RemoteActivityHelper(context)
                        .startRemoteActivity(intention, noeud.id)
                        .get(DELAI_S, TimeUnit.SECONDS)
                    ouverte = true
                }.onFailure { Log.w(TAG, "magasin non ouvert sur ${noeud.displayName}", it) }
            }
            ouverte
        } catch (e: Exception) {
            Log.w(TAG, "magasin non ouvert sur la montre", e)
            false
        }
    }

    /**
     * Ouvre l'application compagnon de la montre, sur **ce** telephone.
     *
     * C'est la reparation du premier etat, et elle n'est pas le Play Store : une montre qui n'est
     * appairee a rien ne recevra jamais d'installation, quel que soit le nombre de fiches
     * ouvertes. L'appairage se fait dans l'application du constructeur, et nulle part ailleurs.
     *
     * Les deux paquets couvrent l'essentiel du parc Wear OS ; ils sont declares dans `<queries>`
     * du manifeste, sans quoi `getLaunchIntentForPackage` rend `null` depuis Android 11 meme
     * lorsque l'application est installee. Sur un telephone qui n'a ni l'un ni l'autre, on rend
     * `false` et l'ecran le dit, plutot que d'ouvrir une fiche de magasin au hasard.
     */
    fun ouvrirLApplicationCompagnon(context: Context): Boolean {
        for (paquet in COMPAGNONS) {
            val intention = context.packageManager.getLaunchIntentForPackage(paquet) ?: continue
            runCatching {
                context.startActivity(intention.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }.onFailure { Log.w(TAG, "compagnon $paquet non lance", it) }
        }
        return false
    }

    private val COMPAGNONS = listOf(
        "com.google.android.wearable.app", // Wear OS by Google / Pixel Watch
        "com.samsung.android.app.watchmanager", // Galaxy Wearable
    )

    private const val TAG = "PendulumAppairage"
    private const val DELAI_S = 15L
}
