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
    /**
     * Duree du mouvement, en millisecondes, et amplitude de son pic en multiples du plancher de
     * bruit.
     *
     * Elles n'alimentent pas le trace — un marqueur est un tick sur une bande dediee, de largeur
     * fixe — mais le **tableau de valeurs**, qui est a la fois l'alternative accessible du graphe
     * et le chemin « je veux le chiffre exact ». Il affichait jusqu'ici « 2.4 s » et « x9.2 » en
     * litteraux pour chaque ligne, quel que soit le mouvement : un tableau qui promet la valeur
     * exacte et rend une constante est pire qu'un tableau absent.
     *
     * Nullables parce qu'un marqueur peut venir d'une source qui ne les porte pas ; le tableau
     * affiche alors un tiret plutot qu'un chiffre invente.
     */
    val dureeMs: Long? = null,
    val amplitudeRatio: Float? = null,
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
    /**
     * Le fuseau dans lequel les nuits ont ete vecues.
     *
     * Il n'est pas decoratif : l'axe des X est **calendaire**, donc ses graduations sont des
     * dates, et une date n'existe pas sans calendrier. Sans lui, la seule maniere d'etiqueter une
     * graduation est de diviser un epoch par 86 400 000 — c'est-a-dire de l'etiqueter en UTC — et
     * une nuit commencee a 23 h 14 a Paris s'affiche alors au lendemain. Le pas d'un jour a la
     * meme faiblesse : un jour civil fait 23 ou 25 heures deux fois par an, et un pas fixe finit
     * par traverser minuit et repeter une date.
     */
    val zoneId: String,
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

// =========================================================================================
// La bande d'etat de l'appareil — troisieme bande, meme axe, forme volontairement etrangere
// =========================================================================================

/**
 * Le port, en trois etats et non deux.
 *
 * [SANS_CAPTEUR] n'est pas une commodite : le Pixel Watch 3 porte bien un
 * `TYPE_LOW_LATENCY_OFFBODY_DETECT`, mais un appareil qui n'en a pas rendrait une bande vide
 * indiscernable d'une bande « porte toute la nuit ». Et la KDoc de `RecordingService` note que
 * **a la cheville**, ce detecteur lit tres probablement « non porte » en permanence : la voie est
 * donc une indication a croiser avec la temperature, pas un verdict.
 */
enum class EtatPort { PORTE, RETIRE, SANS_CAPTEUR }

/**
 * Le niveau de datation, en classes nommees et **sans axe gradue**.
 *
 * Ce que la classe designe est l'incertitude que la gigue fait peser sur l'instant d'un
 * echantillon : le format interpole lineairement entre `tFirstNs` et `tLastNs`, donc une cadence
 * moyenne parfaite obtenue en alternant 10 et 30 ms date chaque echantillon a 10 ms pres. C'est la
 * **dispersion** qui decide de la datation, jamais la moyenne — et c'est pour cela que la voie
 * montre `jitterStdUs` et non `measuredRateCentiHz`.
 *
 * Trois classes et pas une echelle continue, parce qu'une echelle continue invite a lire une
 * tendance dans une grandeur qui n'en a pas : ce qui compte est de quel cote d'une periode
 * d'echantillonnage on se trouve, pas si la gigue a monte de 1,2 a 1,4 ms.
 */
enum class NiveauDatation(val libelle: String) {
    FINE("≤ 2 ms"),
    MOYENNE("≤ 10 ms"),
    GROSSIERE("> 10 ms"),
}

/** Un palier de datation. Marche horizontale, marche verticale : aucune interpolation. */
@Immutable
data class PalierDatation(val debutMs: Long, val finMs: Long, val niveau: NiveauDatation)

/**
 * La jauge de batterie — **une jauge, pas une courbe**.
 *
 * Une courbe qui descend suggere une dynamique et invite a chercher une correlation entre la
 * batterie et les mouvements, entre lesquels il n'y a aucune causalite. La question posee a cette
 * voie est « la montre a-t-elle tenu la nuit », pas « quelle charge a 3 h 12 » — et une jauge
 * repond a la premiere sans permettre de poser la seconde.
 *
 * @param fraction remplissage dans `[0, 1]`, la charge restante a la fin de la nuit.
 * @param tenue le critere batterie de la porte P1 est-il tenu ? Il est **calcule ailleurs** —
 *   [com.pendulum.phone.ui.model.PenteBatterie] quand la pente aboutit, le dernier pourcentage
 *   sinon — et jamais recalcule ici : deux lectures du meme seuil finissent par diverger.
 * @param libelle le chiffre en clair, a cote de la jauge. La jauge situe, le texte mesure.
 */
@Immutable
data class JaugeBatterie(val fraction: Float, val tenue: Boolean, val libelle: String)

/**
 * L'etat de l'appareil pendant la nuit — la troisieme bande, sous l'hypnogramme.
 *
 * ### L'axe partage est une necessite, pas un confort
 *
 * Sans lui, on lit un artefact de mesure comme un evenement physiologique : un signal plat pris
 * pour du calme alors que la montre etait hors du poignet, un mouvement absent pris pour une nuit
 * tranquille alors que l'ecretage du capteur avait aplati son sommet. Les trois bandes partagent
 * donc `debutMs`, `finMs` et la meme [XTransform] — un seul proprietaire du geste, le graphe de
 * nuit, exactement comme pour l'hypnogramme.
 *
 * ### Et la separation doit etre totale dans la forme
 *
 * L'oeil ne doit **pas** correler la batterie aux mouvements : il n'y a aucune causalite entre les
 * deux, et un lecteur qui en trouverait une aurait raison de croire ce qu'il voit et tort sur le
 * fond. Trois moyens, cumules :
 *
 *  1. **Une gouttiere franche**, anormalement large, avec un filet dur de separation. Elle dit
 *     « ce qui suit n'est pas du signal » avant qu'on ait lu quoi que ce soit.
 *  2. **Une grammaire graphique etrangere** : rien de continu, rien de courbe. Des blocs, des
 *     escaliers durs, des *rug plots*, une jauge. C'est la convention du *housekeeping* en
 *     telemetrie scientifique, isolee precisement pour ne pas etre lue comme de la mesure.
 *  3. **Aucune echelle Y graduee.** Les voies portent des noms, pas des valeurs : `worn`,
 *     `charger`, `timing`. Un axe chiffre en face d'un axe chiffre invite a la comparaison.
 *
 * ### Les intervalles couvrent la minute qui *precede* leur point
 *
 * `fsyncCount`, `fsyncTotalUs`, `fsyncMaxUs` et `clippedSamples` comptent **depuis le point
 * precedent** ([com.pendulum.format.TelemetryPoint]). Un bloc d'etat s'etend donc du point
 * precedent au point courant, et non l'inverse — poser l'intervalle a l'envers decalerait toute la
 * bande d'une minute, ce qui est exactement l'ordre de grandeur d'un mouvement.
 *
 * @param ecretage instants ou au moins un echantillon a touche la dynamique du capteur. *Rug
 *   plot* : des tics de meme hauteur, parce que la hauteur serait une echelle.
 * @param gels instants ou le pire `fsync` de la periode a depasse une periode d'echantillonnage —
 *   c'est-a-dire ou le processeur a pu geler assez longtemps pour manquer une interruption.
 * @param texteIndisponible ce qui s'ecrit quand la nuit ne porte aucune telemetrie. Une bande
 *   vide avec ses voies ferait chercher une panne la ou il y a une nuit enregistree avant que la
 *   telemetrie n'existe.
 */
@Immutable
data class MetrologieSpec(
    val debutMs: Long,
    val finMs: Long,
    val etatPort: EtatPort,
    val horsPoignet: List<Intervalle>,
    val charge: List<Intervalle>,
    val datation: List<PalierDatation>,
    val ecretage: List<Long>,
    val gels: List<Long>,
    val batterie: JaugeBatterie?,
    val points: Int,
    val texteIndisponible: String,
    val descriptionAccessible: String,
)

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
