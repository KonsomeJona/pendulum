package com.pendulum.phone.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.NightContextEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.work.PublicationContexteWorker
import com.pendulum.phone.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Le scellement du contexte du soir — la seule vraie porte du produit.
 *
 * ### Ce qui n'existait pas
 *
 * `Preflight` refuse le demarrage tant que `/pendulum/context/<soiree>` n'a pas ete publie, et
 * **aucun code du telephone ne publiait jamais cet item**. Le bouton de scellement etait un
 * `onSceller = {}`, le formulaire de saisie n'existait pas, et les deux chaines qui le decrivent
 * (`tonight_seal_title` et `tonight_seal_confirmation`) n'etaient referencees par aucun
 * composable. Consequence : la montre affichait « remplissez le formulaire du soir sur le
 * telephone » et refusait de demarrer, indefiniment, sans qu'aucun chemin ne permette d'y
 * repondre.
 *
 * ### Pourquoi le scellement est irreversible
 *
 * Le mode de defaillance vise n'est pas la fraude, c'est la retouche de bonne foi. Voir un
 * chiffre eleve au reveil, se souvenir qu'« en fait la dose etait plus tardive », et corriger,
 * suffit a fabriquer la correlation qu'on cherchait. La parade n'est pas la discipline : c'est
 * que la base refuse la modification (`ContextDao` sans `@Update`, plus deux declencheurs
 * SQLite) et que le formulaire le dise avant, pas apres.
 */
class EveningContextSealer(context: Context) {

    private val app = context.applicationContext
    private val db = PendulumDatabase.get(app)

    /** Le contexte de la soiree en cours, ou `null` tant qu'il n'est pas scelle. */
    fun observerSoireeCourante(maintenantMs: Long): Flow<NightContextEntity?> =
        db.contextDao().observe(WirePaths.nightKey(maintenantMs))

    suspend fun estScelle(maintenantMs: Long): Boolean =
        db.contextDao().find(WirePaths.nightKey(maintenantMs)) != null

    /**
     * Scelle le contexte, puis le publie vers la montre. **Dans cet ordre, et il compte.**
     *
     * La base d'abord : c'est elle qui porte la preuve que le contexte precede la mesure, et elle
     * refuse le doublon (`OnConflictStrategy.ABORT`) — sceller deux fois la meme soiree leve
     * plutot que d'ecraser en silence.
     *
     * L'inverse aurait une consequence desagreable et silencieuse : une montre debloquee pour une
     * soiree dont le contexte n'est pas en base, donc une nuit qui s'enregistre et qui sortira
     * ecartee pour `NO_CONTEXT` au matin.
     *
     * ### Ce que le Data Layer rattrape, et ce qu'il ne rattrape pas
     *
     * Il rattrape ce qui **est entre dans le magasin** : un item pose alors que la montre est
     * eteinte ou hors de portee part tout seul des qu'elle revient, et c'est bien toute sa raison
     * d'etre. Il ne rattrape rien du tout quand le `putDataItem` lui-meme a echoue — services
     * Google Play indisponibles, exception d'API — parce que l'item n'a alors jamais existe nulle
     * part. Et comme la base est immuable et que `Preflight` fait de l'absence de cet item un
     * blocage dur, cette seule seconde d'indisponibilite perdait la nuit entiere, sans recours.
     *
     * C'est [PublicationContexteWorker] qui rattrape ce cas-la, et lui seul : sa file WorkManager
     * survit au redemarrage du telephone, et un `putDataItem` de charge utile identique est
     * dedoublonne, donc reposer l'item ne coute rien et ne risque rien.
     *
     * @return `true` si l'item a bien ete publie **tout de suite**. `false` signifie que la base a
     *   le contexte, que le rejeu est enfile, et que la montre ne le sait pas encore — l'appelant
     *   doit le dire, pas le taire : le scellement a reussi, le deblocage non.
     */
    suspend fun sceller(entree: SaisieDuSoir, maintenantMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val cleDeNuit = WirePaths.nightKey(maintenantMs)

            OrdreDuScellement.executer(
                ecrireEnBase = {
                    db.contextDao().seal(
                        NightContextEntity(
                            nightKey = cleDeNuit,
                            sealedAtMs = maintenantMs,
                            leg = entree.jambe,
                            strapId = entree.bracelet,
                            aloneInBed = entree.seulDansLeLit,
                            medicationJson = entree.medicationJson,
                            caffeineAfter16h = entree.cafeApres16h,
                            alcoholUnits = entree.unitesAlcool,
                            unusualExercise = entree.exerciceInhabituel,
                            notes = entree.notes?.takeIf { it.isNotBlank() },
                        )
                    )
                },
                publier = { PublicationContexte.poser(app, cleDeNuit, maintenantMs) },
                enfilerRejeu = {
                    WorkScheduler.enqueuePublicationContexte(app, cleDeNuit, maintenantMs)
                },
            )
        }
}

/**
 * L'enchainement du scellement, isole de la base, du Data Layer et de WorkManager.
 *
 * Trois lignes qui portent deux decisions, et aucune des deux n'etait verifiable tant qu'elles
 * vivaient au fond d'une coroutine qui ouvrait SQLite et appelait les services Google Play :
 *
 *  1. **La base avant le Data Layer.** Debloquer la montre pour une soiree dont le contexte n'est
 *     pas en base donne une nuit qui s'enregistre et qui sortira ecartee pour `NO_CONTEXT`.
 *  2. **Un put echoue enfile le rejeu.** C'est la seule chose qui separe une indisponibilite
 *     passagere des services Google Play d'une nuit definitivement perdue : la base est immuable,
 *     donc il n'y a pas de seconde chance a la main.
 *
 * `inline` pour que [ecrireEnBase] puisse rester une lambda suspendante appelee depuis
 * `sceller()` sans que cette fonction-ci ait a l'etre — le test l'exerce alors sans coroutine.
 */
object OrdreDuScellement {

    /** @return `true` si l'item est parti du premier coup, `false` si le rejeu a ete enfile. */
    inline fun executer(
        ecrireEnBase: () -> Unit,
        publier: () -> Boolean,
        enfilerRejeu: () -> Unit,
    ): Boolean {
        ecrireEnBase()
        if (publier()) return true
        enfilerRejeu()
        return false
    }
}

/**
 * La pose de l'item que `Preflight` attend — le seul endroit du telephone qui l'ecrive.
 *
 * Un seul endroit, parce que deux chemins d'ecriture donneraient deux charges utiles, et deux
 * charges utiles differentes ne se dedoublonnent pas : le rejeu reposerait un item distinct de
 * celui de la tentative en ligne, et la montre verrait deux fois le meme contexte changer sous
 * elle. Ici, [PublicationContexteWorker] et [EveningContextSealer] appellent la meme fonction avec
 * le meme `sealedAtMs`, donc le Data Layer reconnait l'item et ne fait rien — ce qui est
 * exactement le comportement voulu quand la premiere tentative avait en realite abouti.
 *
 * La charge utile est **volontairement minimale** : la soiree et l'instant du scellement, rien du
 * contenu du formulaire. La montre n'a besoin de savoir qu'une chose — que le contexte existe — et
 * le contenu, lui, est une donnee de sante qui n'a aucune raison de traverser le Data Layer ni de
 * se repliquer sur un second appareil.
 *
 * Cadrage manuel plutot qu'un `DataMap`, comme partout ailleurs dans ce protocole : un
 * dictionnaire echoue en silence quand une cle change de nom.
 */
object PublicationContexte {

    private const val TAG = "PendulumContext"

    /**
     * Le delai d'attente de l'appel distant, et il ne passe pas par `Durees`.
     *
     * `Temps` l'ecrit noir sur blanc : les attentes d'appels distants ne se comprimant pas, les
     * diviser produirait des expirations franches sur un banc par ailleurs sain.
     */
    private const val DELAI_S = 20L

    /** @return `true` si l'item est entre dans le magasin repliquee local. */
    fun poser(context: Context, cleDeNuit: String, scelleAMs: Long): Boolean = try {
        val requete = PutDataRequest.create(WirePaths.context(cleDeNuit))
            .setData(scelleAMs.toString().toByteArray())
            // La montre est peut-etre en train d'attendre cet item, ecran allume, au pied du lit.
            .setUrgent()
        Tasks.await(
            Wearable.getDataClient(context.applicationContext).putDataItem(requete),
            DELAI_S,
            TimeUnit.SECONDS,
        )
        true
    } catch (e: Exception) {
        // Pas de renvoi d'exception : le contexte **est** scelle, ce qui est l'essentiel et ce qui
        // est irreversible. Ce qui a echoue est l'entree dans le magasin, et c'est le worker de
        // republication qui la reprend — le Data Layer, lui, ne peut rattraper que ce qui y est
        // deja entre.
        Log.w(TAG, "contexte scelle en base mais non publie vers la montre", e)
        false
    }
}

/**
 * Ce que le formulaire du soir demande.
 *
 * Les champs sont exactement ceux de `NightContextEntity`, et ce n'est pas une coincidence : un
 * formulaire qui collecte plus que ce que la base scelle collecte des donnees que rien ne protege,
 * et un formulaire qui en collecte moins laisse des colonnes vides dont personne ne saura dire si
 * elles sont fausses ou absentes.
 */
data class SaisieDuSoir(
    /** `LEFT` ou `RIGHT`. Critere de comparabilite : un capteur unilateral voit un intervalle
     *  double quand les mouvements alternent, donc changer de jambe change la mesure. */
    val jambe: String,
    /** Bracelet et cran de serrage. Le jeu du bracelet fait varier l'amplitude d'un facteur 2 a 3. */
    val bracelet: String,
    /** Un partenaire de lit transmet ses propres mouvements par le matelas. */
    val seulDansLeLit: Boolean,
    val medicationJson: String,
    val cafeApres16h: Boolean,
    val unitesAlcool: Double,
    val exerciceInhabituel: Boolean,
    val notes: String? = null,
) {
    companion object {
        const val JAMBE_GAUCHE = "LEFT"
        const val JAMBE_DROITE = "RIGHT"
    }
}
