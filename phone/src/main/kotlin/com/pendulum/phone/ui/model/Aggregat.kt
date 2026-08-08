package com.pendulum.phone.ui.model

import androidx.annotation.StringRes
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.texte
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Les regles qui sont du code, et pas des recommandations.
 *
 * Tout ce fichier est du Kotlin pur, sans dependance Android : c'est ce qui permet de le tester
 * sur JVM, exhaustivement, y compris sur les bornes exactes. Les ecrans n'en sont que des
 * lecteurs — aucun composable ne recalcule une mediane ni ne decide d'une eligibilite.
 */
object Aggregat {

    /**
     * Sous ce nombre de nuits eligibles, **rien** n'est agrege.
     *
     * Ce n'est pas un avertissement : il n'existe aucune branche de ce code qui produise une
     * valeur en dessous. Le motif est chiffre et il vient de Skeba 2016 — chez des patients
     * confirmes, le seuil de 15/h n'est franchi que sur ~34 % des nuits individuelles, et la
     * variabilite nuit a nuit du compte horaire est de 43 % ± 37. Un chiffre affiche sur une ou
     * deux nuits est domine par le hasard, dans un sens comme dans l'autre.
     */
    const val MIN_NUITS_AGREGAT = 3

    /**
     * En dessous, aucune categorie textuelle n'est proposee, quel que soit l'intervalle.
     * Entre 3 et 4 nuits on agrege — l'intervalle dit alors lui-meme qu'il est large — mais on
     * ne range pas quelqu'un dans une case sur quatre nuits.
     */
    const val MIN_NUITS_CATEGORIE = 5

    /** Minimum par periode pour qu'une comparaison ait un sens. */
    const val MIN_NUITS_COMPARAISON = 5

    /** Reechantillonnages du bootstrap. Assez pour un percentile a 95 %, assez peu pour tenir. */
    const val BOOTSTRAP_N = 2000

    /**
     * Sous ce nombre de nuits, l'intervalle **n'est pas** un intervalle a 95 % et l'ecran le dit.
     *
     * ### Ce qui a ete mesure
     *
     * `CouvertureBootstrapTest` simule 10 000 replicats et compte la proportion ou la mediane
     * vraie tombe dans l'intervalle rendu par [bootstrapCi]. Sous six nuits la couverture reelle
     * est tres au-dessous de l'etiquette — l'intervalle affiche est plus etroit que ce qu'il
     * annonce, ce qui est le defaut le plus embarrassant possible sur un produit dont
     * l'intervalle est l'argument.
     *
     * ### Pourquoi ce n'est pas un defaut d'implementation, et pourquoi BCa ne le repare pas
     *
     * La mediane d'un reechantillonnage avec remise de `n` valeurs est **l'une de ces `n`
     * valeurs**. Tout intervalle construit a partir de la distribution bootstrap est donc
     * enferme dans l'etendue de l'echantillon, et
     * `P(min < mediane vraie < max) = 1 - 2 x 2^-n` : voir [couvertureMaxBootstrap]. A trois
     * nuits, le plafond est 75 % ; aucun percentile, corrige du biais et de l'acceleration ou
     * non, ne peut le franchir. Implementer BCa aurait donne quarante lignes de plus et la meme
     * couverture.
     *
     * La seule reponse honnete est donc de nommer ce que l'intervalle est reellement en dessous
     * de six nuits — `trend_interval_uncalibrated_note` — et de ne pas ecrire « 95 % ».
     */
    const val MIN_NUITS_IC_CALIBRE = 6

    /**
     * Couverture maximale atteignable par **n'importe quel** intervalle bootstrap sur la mediane
     * de `n` observations : `1 - 2 x 2^-n`.
     *
     * 75 % a 3 nuits, 87,5 % a 4, 93,75 % a 5, 96,9 % a 6. C'est la borne qui fixe
     * [MIN_NUITS_IC_CALIBRE] : six est le premier `n` dont le plafond depasse 95 %.
     */
    fun couvertureMaxBootstrap(n: Int): Double = 1.0 - 2.0 * 2.0.pow(-n.toDouble())

    /**
     * Ce que la tendance trace.
     *
     * `SPEC-v2.md` §5 a change la grandeur suivie apres coup : ce n'est plus le compte horaire
     * mais le **rythme fondamental en secondes**. Trois raisons, toutes mesurees :
     * douze fois moins de variabilite nuit a nuit (log IMI : 3,6 % ± 3,7 contre 43,2 % ± 37,1) ;
     * aucun denominateur, donc la circularite du masque accelero disparait pour le suivi ; et le
     * seuil de 15/h n'etait de toute facon pas transposable a une mesure de cheville, puisque
     * 39 % des mouvements vus a l'EMG sont mecaniquement invisibles en accelerometrie.
     *
     * Le compte horaire **reste** — c'est la langue des somnologues — mais au second rang a
     * l'ecran et en premier rang dans le rapport pour le medecin.
     */
    enum class Grandeur(@StringRes val unite: Int, val decimales: Int) {
        RYTHME_SECONDES(R.string.trend_unit_seconds, 0),
        COMPTE_HORAIRE(R.string.trend_unit_per_hour, 0),
    }

    /**
     * Un agregat complet. Il n'existe pas d'agregat sans son intervalle ni son `n` : les trois
     * champs sont non nuls par construction, ce qui rend P2 (« tout chiffre agrege porte son
     * incertitude dans la meme ligne ») impossible a violer par oubli — il n'y a pas de
     * constructeur qui accepte une mediane seule.
     */
    data class Resultat(
        val grandeur: Grandeur,
        val mediane: Double,
        val ciBas: Double,
        val ciHaut: Double,
        val nuits: Int,
        /** MAD × 1,4826 : la dispersion propre de l'utilisateur, etalon du bruit. */
        val dispersion: Double,
        /** Plus petite variation detectable a 95 %, dans l'unite de [grandeur]. */
        val mdc95: Double,
    ) {
        /**
         * L'intervalle merite-t-il son etiquette « 95 % » ?
         *
         * Faux sous [MIN_NUITS_IC_CALIBRE] nuits, ou la couverture reelle mesuree tombe a 75 %.
         * L'ecran change alors de libelle (`trend_interval_uncalibrated`) au lieu de
         * promettre une precision qui n'existe pas.
         */
        val icCalibre: Boolean get() = nuits >= MIN_NUITS_IC_CALIBRE
    }

    // -------------------------------------------------------------------------------------
    // Estimateurs
    // -------------------------------------------------------------------------------------

    fun mediane(valeurs: DoubleArray): Double {
        require(valeurs.isNotEmpty()) { "mediane sur un echantillon vide" }
        val tri = valeurs.sortedArray()
        val m = tri.size / 2
        return if (tri.size % 2 == 1) tri[m] else (tri[m - 1] + tri[m]) / 2.0
    }

    /** MAD × 1,4826 : ecart-type robuste, insensible a une nuit aberrante. */
    fun dispersion(valeurs: DoubleArray): Double {
        if (valeurs.size < 2) return 0.0
        val med = mediane(valeurs)
        val ecarts = DoubleArray(valeurs.size) { abs(valeurs[it] - med) }
        return mediane(ecarts) * 1.4826
    }

    /**
     * Intervalle de confiance percentile a 95 % par bootstrap sur les nuits.
     *
     * **La graine est fixe et derivee de l'ensemble des identifiants de session.** C'est une
     * exigence d'interface, pas de statistique : un intervalle qui change a chaque recomposition
     * detruit la confiance dans tout l'ecran, et l'utilisateur qui rouvre l'app dix minutes plus
     * tard doit relire le meme nombre. Meme jeu de nuits = meme intervalle, pour toujours.
     *
     * Ce que cet intervalle capture : la variabilite **d'une nuit a l'autre**, qui est la source
     * d'erreur dominante. Ce qu'il ne capture pas : l'incertitude *a l'interieur* d'une nuit, pour
     * laquelle il n'existe aucun modele defendable — les evenements sont periodiques par
     * definition, donc non independants, donc non reechantillonnables. On ne fabrique pas ce
     * qu'on ne sait pas estimer.
     */
    fun bootstrapCi(
        valeurs: DoubleArray,
        graine: Long,
        tirages: Int = BOOTSTRAP_N,
    ): Pair<Double, Double> {
        require(valeurs.isNotEmpty()) { "bootstrap sur un echantillon vide" }
        if (valeurs.size == 1) return valeurs[0] to valeurs[0]
        val rng = Random(graine)
        val medianes = DoubleArray(tirages)
        val tirage = DoubleArray(valeurs.size)
        for (b in 0 until tirages) {
            for (i in valeurs.indices) tirage[i] = valeurs[rng.nextInt(valeurs.size)]
            medianes[b] = mediane(tirage)
        }
        medianes.sort()
        val bas = medianes[(0.025 * (tirages - 1)).toInt()]
        val haut = medianes[ceil(0.975 * (tirages - 1)).toInt()]
        return bas to haut
    }

    /** Graine deterministe : meme ensemble de sessions, meme graine, quel que soit l'ordre. */
    fun graineDe(sessionHex: List<String>): Long =
        sessionHex.sorted().fold(0L) { acc, s -> acc * 31 + s.hashCode() }

    /**
     * Plus petite variation detectable a 95 % (MDC95).
     *
     * Formule retenue : `1,96 × √2 × dispersion / √n`. C'est la MDC classique appliquee a
     * l'erreur type de la mediane estimee sur `n` nuits, en prenant la dispersion nuit a nuit
     * comme erreur de mesure.
     *
     * **Ce que cette formule suppose, honnetement** : que la variabilite nuit a nuit est du bruit
     * de mesure et non un signal. C'est faux en toute rigueur — une nuit d'insomnie n'est pas une
     * erreur de capteur. Le choix est conservateur dans la bonne direction : il rend la bande
     * plus large, donc l'interface plus prudente. Une bande trop etroite ferait annoncer des
     * ecarts qui n'en sont pas, ce qui est exactement le mode de defaillance a eviter.
     */
    fun mdc95(dispersion: Double, n: Int): Double {
        if (n <= 0) return Double.POSITIVE_INFINITY
        return 1.96 * sqrt(2.0) * dispersion / sqrt(n.toDouble())
    }

    // -------------------------------------------------------------------------------------
    // Phrase de position : cinq issues, et aucune autre formulation n'existe dans l'app
    // -------------------------------------------------------------------------------------

    sealed interface Position {
        /** Sous trois nuits : ecran de refus, pas de phrase du tout. */
        data object Refus : Position
        data class Provisoire(val nuits: Int) : Position
        data object SousSeuil : Position
        data object AuDessusSeuil : Position
        data object EnglobeSeuil : Position

        /** `null` sous trois nuits : l'ecran de refus ne pose aucune phrase de position. */
        fun phrase(): UiText? = when (this) {
            Refus -> null
            is Provisoire -> texte(R.string.trend_position_provisional, nuits)
            SousSeuil -> texte(R.string.trend_below_threshold)
            AuDessusSeuil -> texte(R.string.trend_above_threshold)
            EnglobeSeuil -> texte(R.string.trend_spans_threshold)
        }
    }

    /**
     * Le seuil de 15/h, et le seul endroit ou ce nombre est ecrit.
     *
     * **Il vient de la polysomnographie et il est applique ici a un accelerometre de cheville.**
     * Ce n'est pas la meme mesure, et le dire fait partie du produit : voir
     * [SEUIL_ACTIMETRIQUE_CHEVILLE_PAR_HEURE] et `chart_threshold_15_legend`.
     */
    const val SEUIL_CLINIQUE_PAR_HEURE = 15.0

    /**
     * L'equivalent **actimetrique** du seuil precedent : 16,0/h.
     *
     * Il vient de la validation du PAM-RL — actimetre de cheville, le meme montage que celui de
     * Pendulum — contre la polysomnographie : 16,0/h a l'actimetre correspond a 15/h en PSG
     * (Aritake-Okada et al., *Sleep Medicine* 2014, n = 41).
     *
     * **Cette constante ne pilote aucune branche, et c'est deliberé.** Un seul article, une seule
     * cohorte : la substituer a [SEUIL_CLINIQUE_PAR_HEURE] deplacerait toutes les phrases de
     * position sur la foi d'une source unique, alors que le 15/h a derriere lui l'ICSD-3 et
     * l'usage clinique. Elle existe pour que l'ecart soit **ecrit** plutot que tu, et
     * `SeuilsTest` verifie que la legende du graphe le mentionne.
     */
    const val SEUIL_ACTIMETRIQUE_CHEVILLE_PAR_HEURE = 16.0

    /**
     * Le nombre de nuits au-dela duquel le resultat cesse d'etre annonce comme provisoire.
     *
     * ### Le defaut que cette fonction corrige
     *
     * Le minimum dur de trois nuits vient de la variabilite nuit a nuit du compte horaire. Mais
     * cette variabilite **n'est pas la meme des deux cotes du seuil**. La validation du PAM-RL
     * mesure une correlation intraclasse >= 0,90 des **3 nuits quand le PLMI depasse 15/h**, et
     * il en faut **26 quand il est en dessous** (Aritake-Okada 2014).
     *
     * Or [Position.SousSeuil] — donc la phrase qui rassure — etait rendue exactement dans le
     * regime ou trois nuits donnent la pire fiabilite. L'application etait **plus assuree quand
     * elle rassurait que quand elle alertait**, ce qui est l'inverse de son propos.
     *
     * ### Ce que la fonction fait, et ce qu'elle ne fait pas
     *
     * Elle ne touche pas au refus dur a trois nuits : sous trois nuits, rien n'est agrege, et
     * cela ne change pas. Elle deplace la frontiere entre *provisoire* et *definitif*, qui est un
     * libelle, pas une porte — on ne cache jamais un chiffre deja calcule.
     *
     * ### La valeur 14 n'est pas sourcee, et le texte le dit
     *
     * 3 et 26 sont mesures. 14 est un compromis : demander 26 nuits rendrait l'ecran inutilisable
     * en pratique, en demander 3 le rendrait confiant precisement la ou il a le moins de raisons
     * de l'etre. `trend_nights_required_low` enonce les deux chiffres et nomme
     * le compromis comme tel.
     */
    fun nuitsRequises(ciBas: Double, ciHaut: Double): Int = when {
        ciBas > SEUIL_CLINIQUE_PAR_HEURE -> 3
        ciHaut < SEUIL_CLINIQUE_PAR_HEURE -> 14
        else -> 7
    }

    /** Le motif, en toutes lettres, du nombre rendu par [nuitsRequises]. Jamais un chiffre nu. */
    fun motifNuitsRequises(ciBas: Double, ciHaut: Double): UiText = when {
        ciBas > SEUIL_CLINIQUE_PAR_HEURE -> texte(R.string.trend_nights_required_high)
        ciHaut < SEUIL_CLINIQUE_PAR_HEURE -> texte(R.string.trend_nights_required_low)
        else -> texte(R.string.trend_nights_required_straddling)
    }

    /**
     * Fonction pure, cinq issues, testee sur les bornes exactes (`ciBas == 15`).
     *
     * Elle ne s'applique **qu'au compte horaire**. Le rythme fondamental n'a pas d'equivalent :
     * le seuil publie sur la periodicite (≈ 0,5, Ferri 2016) est sur une autre echelle avec
     * d'autres preuves derriere lui, et le transposer mecaniquement serait fabriquer une
     * frontiere clinique. Voir `trend_rhythm_no_threshold`.
     */
    fun position(ciBas: Double, ciHaut: Double, n: Int): Position = when {
        n < MIN_NUITS_AGREGAT -> Position.Refus
        n < MIN_NUITS_CATEGORIE -> Position.Provisoire(n)
        ciHaut < SEUIL_CLINIQUE_PAR_HEURE -> Position.SousSeuil
        ciBas > SEUIL_CLINIQUE_PAR_HEURE -> Position.AuDessusSeuil
        else -> Position.EnglobeSeuil
    }

    // -------------------------------------------------------------------------------------
    // Comparaison de deux periodes
    // -------------------------------------------------------------------------------------

    /**
     * L'ordre des champs est l'ordre d'affichage, et il n'est pas negociable (P3) :
     * verdict, puis les deux estimations, puis la difference, puis la dispersion propre de
     * l'utilisateur comme etalon, puis le nombre de nuits qu'il faudrait.
     *
     * Un lecteur presse lit la premiere ligne et s'arrete. C'est donc la premiere ligne qui doit
     * porter la reserve — pas une note sous le chiffre.
     */
    data class Comparaison(
        val distinguable: Boolean,
        val a: Resultat,
        val b: Resultat,
        val difference: Double,
        val diffCiBas: Double,
        val diffCiHaut: Double,
        val dispersion: Double,
        val mdc95: Double,
        /** `null` quand le nombre requis depasse 30 : dire « hors de portee » est plus honnete. */
        val nuitsNecessaires: Int?,
    ) {
        val verdict: UiText
            get() = if (distinguable) {
                texte(R.string.compare_distinguishable)
            } else {
                texte(R.string.compare_inconclusive)
            }

        /** Pourquoi ce n'est pas concluant : zero dans l'intervalle, ou ecart sous la MDC95. */
        val motifNonConcluant: UiText?
            get() = when {
                distinguable -> null
                abs(difference) <= mdc95 -> texte(R.string.compare_below_mdc)
                else -> texte(R.string.compare_zero_in_interval)
            }
    }

    /**
     * Deux conditions cumulatives pour declarer une difference distinguable :
     * l'intervalle de la difference exclut zero **et** l'ecart depasse la MDC95.
     *
     * La seconde n'est pas redondante. Un intervalle bootstrap peut exclure zero de justesse sur
     * peu de nuits ; la MDC95 rappelle qu'en dessous d'un certain ecart, cette methode ne sait
     * simplement pas trancher, quelle que soit la chance du tirage.
     */
    fun comparer(
        a: Resultat,
        b: Resultat,
        diffCiBas: Double,
        diffCiHaut: Double,
        nuitsNecessaires: Int?,
    ): Comparaison {
        val diff = b.mediane - a.mediane
        val dispersion = maxOf(a.dispersion, b.dispersion)
        val mdc = mdc95(dispersion, minOf(a.nuits, b.nuits))
        val excluZero = (diffCiBas > 0.0 && diffCiHaut > 0.0) || (diffCiBas < 0.0 && diffCiHaut < 0.0)
        return Comparaison(
            distinguable = excluZero && abs(diff) > mdc,
            a = a,
            b = b,
            difference = diff,
            diffCiBas = diffCiBas,
            diffCiHaut = diffCiHaut,
            dispersion = dispersion,
            mdc95 = mdc,
            nuitsNecessaires = nuitsNecessaires?.takeIf { it <= 30 },
        )
    }

    // -------------------------------------------------------------------------------------
    // Periodicite : jamais nue
    // -------------------------------------------------------------------------------------

    /**
     * L'indice de periodicite ne s'affiche jamais tel quel entre 0 et 1.
     *
     * « 0,58 » ne veut rien dire pour personne — ni pour l'utilisateur, ni pour le medecin qui
     * recoit la feuille. Le rythme se presente comme un **intervalle en secondes**, qu'on peut
     * se representer, et la periodicite comme un qualificatif, disponible seulement quand il y a
     * assez de nuits pour qu'une categorie ait un sens.
     */
    fun qualifierPeriodicite(indice: Double, nuits: Int): UiText? = when {
        nuits < MIN_NUITS_CATEGORIE -> null
        indice >= 0.5 -> texte(R.string.trend_periodicity_high)
        else -> texte(R.string.trend_periodicity_low)
    }
}
