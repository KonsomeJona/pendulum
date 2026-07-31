package com.pendulum.phone.ui.chart

import androidx.compose.runtime.Immutable
import com.pendulum.phone.ui.model.Aggregat
import kotlin.math.ceil
import kotlin.math.max

/**
 * Les descriptions de graphe.
 *
 * Elles sont construites dans le ViewModel, jamais dans un composable. Consequences directes :
 * elles se testent sans ecran, et l'export PDF passe exactement les memes objets aux memes
 * fonctions de dessin — un seul chemin de rendu, donc un seul endroit ou une divergence entre
 * l'ecran et le papier pourrait naitre.
 */

@Immutable
data class Intervalle(val debutMs: Long, val finMs: Long)

/** Un marqueur d'evenement, sur la bande dediee sous la courbe. Jamais superpose au signal. */
@Immutable
data class Marqueur(
    val onsetMs: Long,
    val genre: GenreMarqueur,
    val numeroSerie: Int?,
)

/**
 * Trois genres, trois formes distinctes — la couleur ne suffit jamais (P6) : trait plein, croix
 * fine, trait creux.
 */
enum class GenreMarqueur { COMPTE, EXCLU_POSTURE, EN_EVEIL }

@Immutable
data class PicAnnote(val ratio: Float, val heure: String)

/**
 * Le graphe de nuit.
 *
 * L'axe Y est une amplitude **relative au plancher de bruit**, sans dimension, en echelle log₂.
 * Justification : les amplitudes utiles s'etalent de ×1,5 a ×30 ; en lineaire les petits
 * evenements sont invisibles, en log ils restent lisibles **et le seuil a ×8 devient une droite
 * horizontale**, ce qui rend la logique du detecteur immediatement comprehensible a l'oeil.
 */
@Immutable
data class NuitChartSpec(
    val debutMs: Long,
    val finMs: Long,
    val pyramide: EnvelopePyramid,
    /** Periode d'echantillonnage de l'enveloppe, en ms. */
    val pasEnveloppeMs: Long,
    /** Plancher de bruit et seuil d'onset, echantillonnes a ~1 Hz, en ratio du plancher. */
    val plancher: FloatArray,
    val seuilOnset: FloatArray,
    val pasSerieMs: Long,
    val horsSommeil: List<Intervalle>,
    val trous: List<Intervalle>,
    val marqueurs: List<Marqueur>,
    val series: List<Intervalle>,
    val pic: PicAnnote?,
    val logarithmique: Boolean = true,
    val descriptionAccessible: String,
) {
    /**
     * Borne haute de l'axe : la puissance de deux immediatement superieure au maximum observe.
     *
     * **Aucun ecretage silencieux.** Si un seul echantillon oblige a doubler l'echelle, on la
     * double et on annote le pic ([pic]). Couper un pic pour garder une echelle ronde, c'est
     * effacer l'evenement le plus informatif de la nuit.
     */
    val ratioMax: Float
        get() {
            val m = max(pic?.ratio ?: 0f, 32f)
            var p = 1f
            while (p < m) p *= 2f
            return p
        }

    val ratioMin: Float get() = if (logarithmique) 0.5f else 0f
}

/** Etat d'un point de tendance. Forme distincte pour chacun, jamais la seule couleur. */
enum class EtatPoint { ELIGIBLE, MASQUE_ACCELERO, ECARTEE }

@Immutable
data class PointNuit(
    val sessionHex: String,
    val dateMs: Long,
    val valeur: Float,
    val etat: EtatPoint,
)

@Immutable
data class LigneReference(val valeur: Float, val libelle: String, val legende: String)

@Immutable
data class BandeMediane(
    val debutMs: Long,
    val finMs: Long,
    val mediane: Float,
    val ciBas: Float,
    val ciHaut: Float,
    val etiquette: String?,
)

/**
 * Le graphe de tendance — le graphe le plus important du produit.
 *
 * ### L'axe X est calendaire, pas ordinal
 *
 * Les nuits sont posees a leur date reelle. Une semaine sans mesure laisse un trou visible : ce
 * trou est une information, pas un defaut de rendu. Un axe ordinal ferait passer trois nuits
 * espacees d'un mois pour une serie reguliere.
 *
 * ### Les points ne sont pas relies
 *
 * **C'est une decision, pas un oubli, et elle est commentee ici pour qu'un contributeur futur ne
 * la « corrige » pas.** Une polyligne entre deux nuits dessine une trajectoire continue entre
 * deux mesures qui n'ont rien de continu, et suggere une causalite qui n'existe pas. Si une
 * evolution monotone est reellement presente, la position des points la montrera sans qu'on ait
 * besoin de la souligner d'un trait. Une droite de tendance proprement dite est refusee sous dix
 * nuits comparables.
 */
@Immutable
data class TendanceChartSpec(
    val grandeur: Aggregat.Grandeur,
    val points: List<PointNuit>,
    val bandes: List<BandeMediane>,
    val reference: LigneReference?,
    val premierJourMs: Long,
    val dernierJourMs: Long,
    /** Date pivot en mode comparaison ; `null` sinon. */
    val pivotMs: Long?,
    val descriptionAccessible: String,
) {
    /**
     * `yMin = 0`, **en dur, non parametrable**.
     *
     * Pour le compte horaire c'est l'invariante de produit classique : une echelle tronquee
     * transforme une variation de 2/h en falaise. Pour le rythme en secondes, la question a ete
     * posee autrement — un rythme n'a pas de zero « naturel » au sens ou une duree nulle n'existe
     * pas physiologiquement. Elle est tranchee dans le meme sens et pour la meme raison : ancrer
     * a zero preserve les **rapports** (21 s contre 42 s se lit comme le double), alors qu'un axe
     * demarrant a 18 s ferait d'un ecart de trois secondes un effondrement visuel. Le prix est un
     * peu de hauteur perdue ; le benefice est qu'aucune capture d'ecran ne peut mentir sur
     * l'ampleur d'un ecart.
     */
    val yMin: Float get() = 0f

    /** `max(20, ceil(1,15 × plus haute valeur / 5) × 5)` : arrondi au multiple de 5 superieur. */
    val yMax: Float
        get() {
            val plusHaut = (points.maxOfOrNull { it.valeur } ?: 0f)
                .coerceAtLeast(bandes.maxOfOrNull { it.ciHaut } ?: 0f)
                .coerceAtLeast(reference?.valeur ?: 0f)
            return max(20f, ceil(1.15f * plusHaut / 5f) * 5f)
        }

    val pasGraduation: Float get() = if (yMax <= 40f) 5f else 10f
}

/** Un segment de stade dans l'hypnogramme. */
@Immutable
data class SegmentStade(val debutMs: Long, val finMs: Long, val stade: StadeUi)

/**
 * Ordre conventionnel des laboratoires de sommeil : eveil en haut, sommeil profond en bas.
 * La position verticale est le porteur principal de l'information ; la couleur ne fait que
 * confirmer.
 */
enum class StadeUi(val rang: Int, val libelle: String) {
    EVEIL(0, "Awake"),
    REM(1, "REM"),
    N1(2, "N1"),
    N2(3, "N2"),
    N3(4, "N3"),
    ;

    companion object {
        const val NIVEAUX = 5

        fun depuisCode(code: String): StadeUi = when (code.uppercase()) {
            "WAKE", "AWAKE_IN_BED", "OUT_OF_BED", "EVEIL" -> EVEIL
            "REM" -> REM
            "LIGHT", "N1" -> N1
            "N2", "SLEEP" -> N2
            "DEEP", "N3" -> N3
            else -> N2
        }
    }
}

/**
 * L'hypnogramme, sous le graphe de nuit, meme largeur, meme transformation X.
 *
 * Quand [stades] est `null`, la voie centrale n'est **pas dessinee vide** : elle est remplacee
 * par une bande sourde portant un texte centre. Une voie vide avec ses graduations invite l'oeil
 * a chercher une courbe qui n'existe pas, et laisse croire a un bug plutot qu'a une donnee
 * absente.
 */
@Immutable
data class HypnogrammeSpec(
    val debutMs: Long,
    val finMs: Long,
    val stades: List<SegmentStade>?,
    val immobiliteAccelero: List<Intervalle>,
    val desaccord: List<Intervalle>,
    val texteIndisponible: String,
    val statistiques: String,
)
