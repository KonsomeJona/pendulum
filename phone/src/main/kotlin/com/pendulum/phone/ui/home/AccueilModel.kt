package com.pendulum.phone.ui.home

import androidx.compose.runtime.Immutable
import com.pendulum.phone.ui.model.CeSoirUi
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.text.Textes

/**
 * L'accueil : sa machine a etats, et rien d'autre.
 *
 * **Tout ce fichier est pur.** Pas de `Context`, pas de Room, pas de `System.currentTimeMillis` —
 * l'heure locale est un parametre. C'est la meme discipline que [com.pendulum.phone.ui.model.Mapping]
 * et pour la meme raison : la regle qui decide qu'on est « en fin de nuit » plutot qu'« en train de
 * preparer la nuit » merite un test sur ses bornes, pas une verification a l'oeil sur un emulateur
 * a 23 h 58.
 *
 * ### D'ou vient l'etat, et d'ou il ne vient pas
 *
 * De la **machine a etats persistee** : `night_session.state` (`OPEN`, `STALE`, `CLOSED`,
 * `TRUNCATED`), `night_session.analyzedAtMs`, et l'existence du contexte scelle pour la soiree
 * courante. Pas de l'horloge.
 *
 * La v1 faisait l'inverse : la carte du soir apparaissait entre 20 h et 4 h et disparaissait
 * ensuite. Deux consequences, l'une de mise en page et l'autre de justesse. La mise en page
 * d'abord : une carte en tete d'ecran qui apparait et disparait **deplace verticalement tout ce
 * qui la suit, deux fois par jour**, et une cible qui se deplace se cherche a nouveau a chaque
 * fois. La justesse ensuite : quelqu'un qui travaille de nuit se couche a 9 h du matin, et un
 * voyageur change de fuseau sans changer d'habitudes. Dans les deux cas l'horloge affirme le
 * contraire de ce que la base sait.
 *
 * ### Ce que l'heure fait encore
 *
 * Elle **departage**, et seulement quand la base laisse deux lectures egalement plausibles :
 * aucune session en cours et rien a analyser. On est alors soit en soiree — on prepare — soit en
 * journee — il n'y a rien a faire. [CeSoirUi.visibleA] tranche ce cas-la, et lui seul.
 */

/**
 * Ou en est la nuit, du point de vue de l'utilisateur.
 *
 * Quatre phases et pas cinq : « nuit analysee, resultat non devoile » n'en est pas une, c'est une
 * propriete de la derniere nuit. La confondre avec une phase donnerait deux etats indiscernables
 * a l'ecran et un `when` qui se contredit.
 */
enum class PhaseAccueil {
    /** Rien n'est en vol, et l'heure dit que la soiree est en cours. */
    PREPARATION,

    /** La montre enregistre : `night_session.state == OPEN`. */
    ENREGISTREMENT,

    /** Une session attend d'etre fermee, ramenee, ou analysee. */
    FIN_DE_NUIT,

    /** Rien n'est en vol, et l'heure dit que la journee est en cours. */
    JOURNEE,
}

/** Ce que la base sait d'une session, reduit a ce que l'accueil en fait. */
@Immutable
data class SessionAccueil(
    val sessionHex: String,
    /** `night_session.state` tel quel : `OPEN | CLOSED | STALE | TRUNCATED`. */
    val etat: String,
    /** `analyzedAtMs != null`. Une session fermee mais non analysee reste une fin de nuit. */
    val analysee: Boolean,
    val debutLisible: String,
    val dateLisible: String,
)

/**
 * L'etat persiste, lu en une fois, avant toute decision d'affichage.
 *
 * Le repository le produit, la machine le transforme, l'ecran le consomme. Le decoupage tient a
 * ce que la lecture est ce qu'un test JVM ne peut pas verifier, et la decision ce qu'il doit
 * verifier.
 */
@Immutable
data class SourceAccueil(
    /** La session la plus recente, toutes soirees confondues, ou `null` s'il n'y en a jamais eu. */
    val sessionRecente: SessionAccueil?,
    val contexteScelle: Boolean,
    /** Cote portant du contexte scelle. `null` tant qu'il n'est pas scelle : jamais devine. */
    val jambeScellee: String?,
    val repereDeSerrage: String,
    val sourceSommeil: String?,
    val nuitsEnregistrees: Int,
    val nuitsEligibles: Int,
    /**
     * La nuit analysee la plus recente, devoilee ou non.
     *
     * Elle vient de `comparable_night`, qui n'existe que pour les nuits deja scorees : une nuit
     * qui apparait ici a donc forcement un chiffre a devoiler.
     */
    val derniereNuitAnalysee: NuitUi?,
)

/**
 * Ce que l'accueil affiche. **Trois cartes, toujours les trois, toujours dans cet ordre.**
 *
 * Aucune carte n'est retiree quand elle n'a rien a faire : elle est desactivee et porte son
 * motif. Les champs `motif*` sont exactement les arguments de
 * [com.pendulum.phone.ui.common.BoutonMotive] — jamais un bouton actif qui echoue, jamais un
 * bouton grise sans explication.
 */
@Immutable
data class AccueilUi(
    val phase: PhaseAccueil,
    val ceSoir: CeSoirUi,
    /** Motif d'indisponibilite du scellement, ou `null` s'il est possible maintenant. */
    val motifPreparer: String?,
    /** Ligne d'etat de la carte « fin de nuit ». Toujours presente, meme quand il n'y a rien. */
    val ligneFinDeNuit: String,
    val motifFinDeNuit: String?,
    /** La session que le bouton de fin de nuit fermerait, ou `null`. */
    val sessionAFermer: String?,
    /**
     * La nuit dont le resultat attend d'etre devoile, ou `null`.
     *
     * Non nul uniquement quand il n'y a plus rien a fermer : une carte, une action. A 7 h du
     * matin, d'une main, un choix multiple est un choix qu'on ne fait pas.
     */
    val nuitADevoiler: String?,
    val ligneHistorique: String,
    val motifHistorique: String?,
)

/**
 * La machine a etats de l'accueil. Une fonction, deux entrees, aucun effet de bord.
 */
object MachineAccueil {

    /** `night_session.state` : la montre enregistre. */
    const val OUVERTE = "OPEN"

    /** Plus de chunk depuis 45 min. La montre s'est tue ; elle peut revenir. */
    const val SILENCIEUSE = "STALE"

    /**
     * La phase, dans un ordre de priorite qui est le message.
     *
     * L'etat persiste passe **avant** l'heure, toujours. Une session `OPEN` a 15 h est une
     * session `OPEN` : c'est une sieste, ou un travailleur de nuit, ou une montre qu'on a oublie
     * d'arreter — dans les trois cas, l'ecran doit dire qu'elle enregistre. L'heure n'est
     * consultee que lorsque la base ne dit rien.
     */
    fun phase(source: SourceAccueil, heureLocale: Int): PhaseAccueil {
        val s = source.sessionRecente ?: return departage(heureLocale)
        return when {
            s.etat == OUVERTE -> PhaseAccueil.ENREGISTREMENT
            // Silencieuse : c'est precisement le cas ou le balayage sert a quelque chose.
            s.etat == SILENCIEUSE -> PhaseAccueil.FIN_DE_NUIT
            // Fermee mais pas encore scoree : la nuit n'est pas finie tant que rien ne la lit.
            !s.analysee -> PhaseAccueil.FIN_DE_NUIT
            else -> departage(heureLocale)
        }
    }

    /**
     * Le seul endroit ou l'heure entre. Deux lectures egalement plausibles, et rien en base pour
     * choisir : on prepare la soiree, ou la journee suit son cours.
     */
    private fun departage(heureLocale: Int): PhaseAccueil =
        if (CeSoirUi.visibleA(heureLocale)) PhaseAccueil.PREPARATION else PhaseAccueil.JOURNEE

    fun de(source: SourceAccueil, heureLocale: Int): AccueilUi {
        val phase = phase(source, heureLocale)
        val s = source.sessionRecente

        // Fermable : il reste quelque chose a ramener de la montre, a lire, ou a scorer. Une
        // session close ET analysee ne l'est plus — reclamer un balayage n'apporterait rien et
        // un bouton qui ne fait rien est pire qu'un bouton grise.
        val fermable = s != null && (s.etat == OUVERTE || s.etat == SILENCIEUSE || !s.analysee)

        val aDevoiler = source.derniereNuitAnalysee?.takeIf { it.devoileeAtMs == null }
        val devoilementPropose = !fermable && aDevoiler != null

        return AccueilUi(
            phase = phase,
            ceSoir = CeSoirUi(
                // Batterie et espace libre de la montre : rien ne les remonte encore cote
                // telephone. Un tiret dit « pas encore branche » ; un chiffre ecrit en dur
                // dirait « branche », et serait cru.
                batteriePct = null,
                espaceLibre = null,
                bracelet = source.repereDeSerrage,
                jambe = source.jambeScellee,
                sourceSommeil = source.sourceSommeil ?: Textes.Reglages.SOURCE_INCONNUE,
                sourceActive = null,
                contexteScelle = source.contexteScelle,
            ),
            motifPreparer = when {
                phase == PhaseAccueil.ENREGISTREMENT -> Textes.EcranAccueil.Preparer.OCCUPE
                // Le contexte est append-only : le sceller deux fois leve. Le bouton doit donc
                // etre grise, et son motif est la bonne nouvelle — la montre peut partir.
                source.contexteScelle -> Textes.CeSoir.SCELLEMENT_FAIT
                else -> null
            },
            ligneFinDeNuit = when {
                devoilementPropose -> Textes.EcranAccueil.Resultat.enregistree(aDevoiler!!.dateLisible)
                s == null -> Textes.EcranAccueil.Fin.AUCUNE_SESSION
                s.etat == OUVERTE -> Textes.EcranAccueil.Fin.enCoursDepuis(s.debutLisible)
                fermable -> Textes.EcranAccueil.Fin.ouverteDepuis(s.debutLisible)
                else -> Textes.EcranAccueil.Fin.analysee(s.dateLisible)
            },
            motifFinDeNuit = when {
                devoilementPropose -> null
                fermable -> null
                else -> Textes.EcranAccueil.Fin.AUCUNE_SESSION
            },
            sessionAFermer = if (fermable) s!!.sessionHex else null,
            nuitADevoiler = if (devoilementPropose) aDevoiler!!.sessionHex else null,
            ligneHistorique = if (source.nuitsEnregistrees == 0) {
                Textes.EcranAccueil.Historique.VIDE
            } else {
                Textes.EcranAccueil.Historique.compte(source.nuitsEnregistrees, source.nuitsEligibles)
            },
            motifHistorique = if (source.nuitsEnregistrees == 0) {
                Textes.EcranAccueil.Historique.VIDE
            } else {
                null
            },
        )
    }
}
