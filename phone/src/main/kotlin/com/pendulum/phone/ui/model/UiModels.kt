package com.pendulum.phone.ui.model

import androidx.compose.runtime.Immutable
import com.pendulum.phone.ui.chart.TendanceChartSpec
import com.pendulum.phone.ui.text.Textes

/**
 * Les modeles que les ecrans consomment.
 *
 * Regle d'architecture tenue partout ici : **un composable d'ecran ne prend que son etat et des
 * lambdas**. Aucun acces Room, aucun acces Health Connect, aucun calcul d'agregat depuis un
 * `@Composable`. Les `Spec` de graphe sont eux aussi construits en amont, ce qui les rend
 * testables sans ecran et permet a l'export PDF de reutiliser exactement les memes objets.
 */

/** Etat d'une nuit vis-a-vis de la tendance. Toujours double d'une forme, jamais d'une couleur. */
enum class EtatNuit { ELIGIBLE, PROVISOIRE, ECARTEE }

/**
 * Un drapeau de qualite. Ce n'est **pas** une erreur : c'est une propriete mesuree de la nuit,
 * affichee pour que l'utilisateur sache dans quelles conditions son chiffre a ete obtenu.
 */
@Immutable
data class Drapeau(val libelle: String)

@Immutable
data class NuitUi(
    val sessionHex: String,
    val dateLisible: String,
    val jourAbrege: String,
    val debut: String,
    val fin: String,
    val sommeilLisible: String,
    val sourceSommeil: String,
    val etat: EtatNuit,
    /** Motif d'exclusion deja traduit. Non nul si et seulement si [etat] vaut ECARTEE. */
    val motif: String?,
    /** Rythme fondamental de cette nuit, en secondes. */
    val rythmeSec: Double,
    /** Compte horaire de cette nuit. Present, jamais mis en avant (P7). */
    val comptePlmi: Double,
    val drapeaux: List<Drapeau>,
    val startWallMs: Long,
    /**
     * Instant du devoilement du resultat, ou `null` s'il n'a jamais ete demande — garde-fou 2.
     *
     * Tant qu'il est nul, [rythmeSec] et [comptePlmi] ne sont **pas affiches**, ni dans la liste
     * ni dans le detail. Ils restent dans le modele parce que le masquage est une regle
     * d'affichage et non un chiffrement : le but n'est pas de rendre la valeur inaccessible, il
     * est qu'on la demande explicitement et que la demande laisse une trace.
     */
    val devoileeAtMs: Long? = null,
)

/**
 * Les cinq etats du reveil, un seul a la fois, au plus une action.
 *
 * L'etat [Provisoire] est le **cas normal** au reveil et non une panne : les stades de sommeil de
 * la montre de poignet arrivent dans Health Connect quand la politique batterie du fabricant le
 * decide, souvent plusieurs heures apres. Toute la mise en forme de cet etat en decoule — ambre
 * et non rouge, mot « provisoire » et non « echec », explication du delai avant toute action.
 */
sealed interface EtatReveil {
    data object Rien : EtatReveil

    data class EnAttenteTransfert(val date: String, val mo: String, val minutes: Int) : EtatReveil

    data class Transfert(val recuMo: String, val totalMo: String, val chunk: Int, val chunks: Int) : EtatReveil {
        /** Le pourcentage ne recule jamais : le transfert reprend ou il s'est arrete. */
        val fraction: Float get() = if (chunks == 0) 0f else (chunk.toFloat() / chunks).coerceIn(0f, 1f)
    }

    data class Analyse(val date: String, val etape: EtapeAnalyse, val secondesRestantes: Int) : EtatReveil

    data class Provisoire(
        val date: String,
        val derniereTentative: String,
        val prochaineTentative: String,
    ) : EtatReveil

    data class Echec(val date: String, val erreur: ErreurPendulum) : EtatReveil
}

/** Trois libelles d'etape, et pas un de plus. Le log technique vit dans Reglages › Journal. */
enum class EtapeAnalyse(val libelle: String, val fraction: Float) {
    ASSEMBLAGE(Textes.Reveil.Analyse.ETAPE_ASSEMBLAGE, 0.25f),
    DETECTION(Textes.Reveil.Analyse.ETAPE_DETECTION, 0.6f),
    CROISEMENT(Textes.Reveil.Analyse.ETAPE_CROISEMENT, 0.9f),
}

/**
 * Un message d'erreur a toujours la meme forme : titre neutre, une phrase de cause, une phrase
 * d'action, un bouton quand une action existe, et un code stable.
 *
 * [technique] separe ce qui est reellement casse (transfert, permission, integrite, stockage —
 * affiche en rouge) de ce qui est une **situation** (nuit courte, hypnogramme absent, montre non
 * portee — affiche en ambre). Employer le mot « erreur » quand rien n'est casse apprend a
 * l'utilisateur a ignorer les vraies pannes.
 */
@Immutable
data class ErreurPendulum(
    val code: String,
    val titre: String,
    val cause: String,
    val action: String,
    val bouton: String? = null,
    val boutonSecondaire: String? = null,
    val technique: Boolean = false,
)

/**
 * Ce que la carte « Preparer la nuit » affiche.
 *
 * ### Elle ne disparait plus entre 4 h et 20 h
 *
 * La v1 la faisait apparaitre entre 20 h et 4 h et disparaitre le reste du temps. Le cout de ce
 * choix n'etait pas visible depuis la maquette : la carte etant en tete d'ecran, son apparition
 * et sa disparition **deplacaient verticalement tout le contenu situe en dessous, deux fois par
 * jour**. Et l'horloge n'est pas une source d'etat : quelqu'un qui travaille de nuit, ou qui vient
 * de changer de fuseau, se voyait refuser l'ecran dont il avait besoin.
 *
 * La carte est donc permanente et c'est l'etat persiste qui la remplit. [visibleA] survit comme
 * **regle de departage** — quand la base ne permet pas de trancher entre « on prepare la nuit » et
 * « la journee est en cours », l'heure locale departage, et elle ne fait que cela.
 *
 * ### Les tirets sont voulus
 *
 * [batteriePct] et [espaceLibre] sont nuls tant que la montre ne les remonte pas : rien, cote
 * telephone, ne lit encore ces deux valeurs. Un tiret dit « pas encore branche ». Un « 98 % »
 * ecrit en dur dirait « branche », ce qui serait faux, et serait cru — c'est exactement le genre
 * de chiffre qu'on cite ensuite dans un rapport de defaut.
 */
@Immutable
data class CeSoirUi(
    val batteriePct: Int?,
    val espaceLibre: String?,
    val bracelet: String,
    /** Cote portant, connu seulement une fois le contexte scelle. */
    val jambe: String?,
    val sourceSommeil: String,
    /** `null` tant que l'activite de la source n'est pas verifiable. */
    val sourceActive: Boolean?,
    val contexteScelle: Boolean,
    val enregistrement: EnregistrementUi? = null,
) {
    /** Avis, pas porte : sous 85 %, la ligne passe en ambre et rien n'est bloque. */
    val batterieInsuffisante: Boolean get() = batteriePct != null && batteriePct < 85

    companion object {
        const val HEURE_DEBUT = 20
        const val HEURE_FIN = 4

        /**
         * `heureLocale` en 0..23. Vrai entre 20 h et 4 h.
         *
         * Ce n'est plus une condition d'affichage : c'est le departage de la machine a etats de
         * l'accueil quand la base laisse deux lectures egalement plausibles. Voir
         * `ui/home/AccueilModel.kt`.
         */
        fun visibleA(heureLocale: Int): Boolean = heureLocale >= HEURE_DEBUT || heureLocale < HEURE_FIN
    }
}

@Immutable
data class EnregistrementUi(
    val depuis: String,
    val duree: String,
    val echantillons: Long,
    val hzMesures: Double,
    val batteriePct: Int,
    val trous: Int,
)

/**
 * L'etat de l'ecran Tendance.
 *
 * [Refus] n'est pas un etat d'erreur : c'est le comportement normal du produit sous trois nuits
 * eligibles. Aucun graphe n'est construit, aucune mediane n'existe, l'export est desactive avec
 * son motif. La difference avec « un graphe vide » est essentielle — un axe vide invite l'oeil a
 * imaginer une courbe.
 */
sealed interface TendanceUiState {
    data object Chargement : TendanceUiState

    data class Refus(
        val nuitsEligibles: Int,
        val nuitsRequises: Int,
        val nuitsEnregistrees: List<NuitUi>,
        val reveil: EtatReveil,
    ) : TendanceUiState

    data class Pret(
        /** Le rythme fondamental : la grandeur suivie. */
        val rythme: Aggregat.Resultat,
        /** Le compte horaire, au second rang. Present pour le medecin, pas pour le suivi. */
        val compte: Aggregat.Resultat,
        val position: Aggregat.Position,
        val periodiciteQualifiee: String?,
        val tauxManques: Double,
        val graphe: TendanceChartSpec,
        val nuitsEnregistrees: Int,
        val nuitsEligibles: Int,
        val nuitsEcartees: Int,
        val regle: String,
        val masque: String,
        val plmw: Double,
        val reveil: EtatReveil,
        val profilPersonnalise: String?,
        val hashsMelanges: Boolean,
        val questionnaireEtat: String,
        val exportPossible: Boolean,
    ) : TendanceUiState {
        /** Bandeau « resultat provisoire » entre 3 et 4 nuits. */
        val bandeauProvisoire: String?
            get() = if (rythme.nuits < Aggregat.MIN_NUITS_CATEGORIE) {
                Textes.Tendance.BANDEAU_PROVISOIRE.format(rythme.nuits)
            } else {
                null
            }
    }
}
