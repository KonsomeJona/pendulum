package com.pendulum.phone.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.NightContextEntity
import com.pendulum.phone.db.PendulumDatabase
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
 * (`Textes.CeSoir.SCELLEMENT_TITRE` et `SCELLEMENT_CONFIRMATION`) n'etaient referencees par aucun
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
     * plutot que d'ecraser en silence. Le Data Layer ensuite : il n'est qu'un signal, il se
     * republie, et le perdre ne perd rien.
     *
     * L'inverse aurait une consequence desagreable et silencieuse : une montre debloquee pour une
     * soiree dont le contexte n'est pas en base, donc une nuit qui s'enregistre et qui sortira
     * ecartee pour `NO_CONTEXT` au matin.
     *
     * @return `true` si l'item a bien ete publie. `false` signifie que la base a le contexte mais
     *   que la montre ne le sait pas encore — l'appelant doit le dire, pas le taire : le
     *   scellement a reussi, le deblocage non.
     */
    suspend fun sceller(entree: SaisieDuSoir, maintenantMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val cleDeNuit = WirePaths.nightKey(maintenantMs)

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

            publier(cleDeNuit, maintenantMs)
        }

    /**
     * Publie l'item que `Preflight` attend.
     *
     * La charge utile est **volontairement minimale** : la soiree et l'instant du scellement, rien
     * du contenu du formulaire. La montre n'a besoin de savoir qu'une chose — que le contexte
     * existe — et le contenu, lui, est une donnee de sante qui n'a aucune raison de traverser le
     * Data Layer ni de se repliquer sur un second appareil.
     *
     * Cadrage manuel plutot qu'un `DataMap`, comme partout ailleurs dans ce protocole : un
     * dictionnaire echoue en silence quand une cle change de nom.
     */
    private fun publier(cleDeNuit: String, sealedAtMs: Long): Boolean = try {
        val requete = PutDataRequest.create(WirePaths.context(cleDeNuit))
            .setData(sealedAtMs.toString().toByteArray())
            // La montre est peut-etre en train d'attendre cet item, ecran allume, au pied du lit.
            .setUrgent()
        Tasks.await(Wearable.getDataClient(app).putDataItem(requete), DELAI_S, TimeUnit.SECONDS)
        true
    } catch (e: Exception) {
        // Pas de renvoi d'exception : le contexte **est** scelle, ce qui est l'essentiel et ce
        // qui est irreversible. Seule la notification de la montre a echoue, et le Data Layer la
        // rattrapera de lui-meme a la prochaine reconnexion — c'est toute sa raison d'etre.
        Log.w(TAG, "contexte scelle en base mais non publie vers la montre", e)
        false
    }

    private companion object {
        const val TAG = "PendulumContext"
        const val DELAI_S = 20L
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
