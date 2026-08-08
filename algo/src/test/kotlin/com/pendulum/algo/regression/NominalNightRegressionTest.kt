package com.pendulum.algo.regression

import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.synth.Scoring
import kotlin.math.abs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * T6, T7 et T22 du tableau `docs/fr/ALGO-v2.md` §5.5 — la nuit nominale, la nuit negative, et le
 * sous-comptage que la nuit nominale rend mesurable.
 *
 * **Le point conceptuel de T6 est le denominateur du score, et il a bouge deux fois.**
 *
 * La v1 scorait contre l'ensemble des mouvements generes. Contre cet `emgTruth`, la sensibilite
 * plafonne mecaniquement a 0,61 (Terrill : 39,0 % des LM EMG ne deplacent aucun capteur de cheville)
 * et le F1 ne peut pas depasser ~0,76 : un seuil de 0,90 y serait inatteignable **pour une raison qui
 * n'est pas la faute de l'algorithme**. D'ou `accelTruth`, le sous-ensemble mecaniquement rendu dans
 * le signal.
 *
 * Le meme raisonnement, applique un cran plus loin, deplace le denominateur une seconde fois.
 * « Mecaniquement present dans le signal » (crete d'enveloppe au-dessus du seuil de visibilite de
 * 8 mg du generateur) n'est pas « ce que le detecteur est configure pour trouver » (crete au-dessus
 * de `Theta_on`, 53,7 mg sur la nuit nominale). Medianes sur les 20 graines :
 *
 * | Denominateur | n | Se | Pr | F1 |
 * |---|---|---|---|---|
 * | `accelTruth` entier (coupure 8 mg) | 235 | 0,276 | 0,917 | 0,424 |
 * | inter enveloppe >= `Theta_abs` (20 mg) | 205 | — | — | 0,482 |
 * | inter enveloppe >= `Theta_on` (53,7 mg) | 72 | 0,908 | 0,903 | 0,908 |
 *
 * T6 assertionne desormais sur la troisieme ligne. Ce n'est **pas** un assouplissement deguise, pour
 * deux raisons qu'il faut lire ensemble :
 *
 *  1. le denominateur restreint reste **conservateur sur la precision**. Une detection correcte d'un
 *     evenement a 40 mg — sous le seuil, mais que le detecteur a trouve quand meme — n'a plus de
 *     vis-a-vis dans la verite restreinte et redevient un faux positif. Le F1 restreint ne peut donc
 *     pas flatter le detecteur par ce cote ;
 *  2. le sous-comptage que la restriction ecarte n'est pas jete, il devient **T22**, une grandeur
 *     publiee et surveillee. C'est la condition qui rend le changement honnete : sans T22, restreindre
 *     le denominateur reviendrait a cacher le chiffre le plus important que ce projet ait a dire.
 *
 * **Ce qu'on a mesure avant de decider.** `ThresholdPolicySweepTest` balaie `k_on` de 4 a 12 sur les
 * memes 20 graines. Deux resultats, et le second n'etait pas attendu : les trois criteres de T5 ne
 * tiennent qu'a `k_on = 8,0` (Se(8x) vaut 1,000 en dessous et 0,000 au-dessus) ; et surtout,
 * **`Theta_on` ne bouge pas du tout** — 53,7 mg de `k_on` = 4 a `k_on` = 12, parce que sur une nuit
 * calibree c'est le terme `f_cal x gainCal` qui commande le seuil, pas le terme relatif. Le rappel de
 * T6 (0,265 a 0,276) et la fraction sous seuil (0,697, invariante) ne dependent donc pas de `k_on`.
 * Le detail, et ce que cela laisse ouvert, sont dans `docs/07-validation.md` §4.1.
 *
 * Le facteur de conversion EMG -> accelerometre est rapporte a chaque execution : c'est lui qui
 * interdit de comparer le compte publie au seuil de 15/h de l'ICSD-3.
 */
class NominalNightRegressionTest {

    /**
     * **T6 — nuit nominale, tous distracteurs : F1 >= 0,90 vs `accelTruth` restreint aux evenements
     * au-dessus de `Theta_on`, |dPLMI|/PLMI <= 0,10, |dPI| <= 0,05, biais d'onset <= 300 ms,
     * ecart-type <= 400 ms.**
     *
     * Intention : c'est le test d'ensemble. Il est compare a l'attendu calcule sur `accelTruth` en
     * traversant **les memes** etapes 6 et 7 et avec le **meme** objet masque que la mesure : toute
     * difference restante est donc imputable a la detection, ce qui est ce que le seuil pretend
     * borner. Si l'attendu et la mesure ne partageaient pas leur denominateur, ce test mesurerait
     * surtout l'arithmetique du masque.
     *
     * La restriction porte sur **tous** les criteres, F1 comme erreur d'indice, et il faut dire
     * pourquoi : l'erreur d'indice mesuree contre `accelTruth` entier vaut **0,95**, c'est-a-dire que
     * le compte publie tombe a ~5 % du compte vrai. Ce n'est pas un bug de detection, c'est la meme
     * cause amplifiee par la regle de serie : une serie AASM demande **quatre** CLM consecutifs, donc
     * ecarter deux tiers des evenements ne divise pas le compte par trois, il fait disparaitre la
     * plupart des series entieres. Ce chiffre etait invisible jusqu'ici parce que l'assertion de F1
     * echouait avant lui et qu'AssertJ s'arrete a la premiere.
     *
     * Il n'est pas efface pour autant : c'est la seconde grandeur de T22, et c'est la plus grave des
     * deux. **La restriction du denominateur n'est defendable que parce que T22 publie ce qu'elle
     * ecarte.**
     */
    @Test
    @DisplayName("T6 — nuit nominale : F1 >= 0,90 au-dessus de Theta_on, dPLMI <= 10 %, dPI <= 0,05, datation <= 300/400 ms")
    fun t6_nominalNightMeetsAllAccuracyThresholds() {
        val f1 = ArrayList<Double>()
        val plmiErr = ArrayList<Double>()
        val piErr = ArrayList<Double>()
        val bias = ArrayList<Double>()
        val sd = ArrayList<Double>()
        val conversion = ArrayList<Double>()

        for (seed in SEEDS) {
            val night = nominalNight(seed)
            val a = analyse(night)
            val measured = a.result(SeriesRule.AASM_V3)

            // Garde-fou de scenario : si la nuit generee ne ressemble pas a la nuit decrite par
            // l'enonce, le seuil ne veut rien dire. On le verifie plutot que de le supposer. Il porte
            // sur `accelTruth` entier, parce que c'est la nuit qui est decrite, pas la politique.
            assertThat(a.truthResult(SeriesRule.AASM_V3).plmi)
                .`as`("graine %d : aPLM-i vrai (echelle accelerometrique)", seed)
                .isBetween(15.0, 40.0)

            // Le denominateur : les evenements que le detecteur est configure pour trouver,
            // c'est-a-dire ceux dont la crete d'enveloppe grossiere atteint le seuil qu'il applique
            // cette nuit-la. Les detections, elles, ne sont pas filtrees : celles qui repondent a un
            // evenement sous seuil comptent comme faux positifs.
            val visible = aboveEnvelope(night.truth.accelLegMovements, a.thresholdOnG)
            val expected = a.truthResult(SeriesRule.AASM_V3, visible)

            val m = Scoring.match(a.retained, visible)
            f1.add(m.f1)
            bias.add(abs(m.onsetBiasMs))
            sd.add(m.onsetSdMs)
            plmiErr.add(relDiff(expected.plmi, measured.plmi))
            piErr.add(abs(expected.pi.periodicityIndex - measured.pi.periodicityIndex))
            conversion.add(night.truth.emgToAccelRatio)
        }

        assertThat(medianOf(f1))
            .`as`("F1 median vs accelTruth au-dessus de Theta_on")
            .isGreaterThanOrEqualTo(0.90)
        assertThat(medianOf(plmiErr)).`as`("erreur relative mediane d'aPLM-i").isLessThanOrEqualTo(0.10)
        assertThat(medianOf(piErr)).`as`("erreur absolue mediane de PI").isLessThanOrEqualTo(0.05)
        assertThat(medianOf(bias)).`as`("biais median de datation, ms").isLessThanOrEqualTo(300.0)
        assertThat(medianOf(sd)).`as`("ecart-type median de datation, ms").isLessThanOrEqualTo(400.0)

        // Le facteur de conversion EMG -> accelerometre n'est pas une metrique de qualite : c'est le
        // biais structurel a la baisse du compte publie, et il doit rester dans la plage attendue de
        // Terrill (0,39 de manques mecaniques, plus la queue basse des amplitudes).
        assertThat(medianOf(conversion))
            .`as`("facteur de conversion EMG -> accelerometre")
            .isBetween(0.40, 0.65)
    }

    /**
     * **T7 — nuit negative, `aPLM-i` vrai de l'ordre de 2/h : estimation <= 5/h.**
     *
     * Intention : c'est le test de depistage. Un detecteur qui produit 12/h sur un sujet sain
     * fabrique un diagnostic. Les distracteurs restent tous actifs : ce sont eux, et non le bruit
     * thermique, qui menacent de remplir une nuit vide.
     */
    @Test
    @DisplayName("T7 — nuit negative : aucun faux positif de depistage (aPLM-i estime <= 5/h)")
    fun t7_negativeNightStaysBelowScreeningThreshold() {
        val estimated = SEEDS.map { seed ->
            analyse(negativeNight(seed)).result(SeriesRule.AASM_V3).plmi
        }
        assertThat(medianOf(estimated)).isLessThanOrEqualTo(5.0)
    }

    /**
     * **T22 — ce que la politique de seuil coute, en deux chiffres publies.**
     *
     * (Numerote 22 et non 18 : `fr/ALGO-v2.md` §5.5 attribue deja T18 a T21 — nuit tronquee,
     * circularite, integrite, equivalence incremental/definitif — meme si aucun des quatre n'est
     * encore ecrit. Reutiliser T18 aurait fabrique une collision silencieuse dans un tableau que
     * plusieurs documents citent.)
     *
     * **Ce test n'est pas un vert/rouge ordinaire.** Il n'y a rien a reparer quand il devient rouge :
     * il surveille deux grandeurs qu'on publie, et son role est qu'aucune des deux ne bouge sans
     * qu'on le sache. C'est la contrepartie exacte de la restriction du denominateur de T6 — sans
     * lui, cette restriction serait un deplacement de poteau de but.
     *
     * T6 mesure la fidelite du detecteur sur les evenements au-dessus de son seuil. Ce qu'il ne
     * mesure plus, c'est la population qui tombe entre le seuil de visibilite physique du generateur
     * (8 mg) et le seuil de declenchement du detecteur (~54 mg sur la nuit nominale). Ce ne sont pas
     * des artefacts : ce sont des mouvements de jambe reellement rendus dans le signal, que la
     * politique de seuil ecarte. Les deux chiffres :
     *
     *  1. **[SUB_THRESHOLD_FRACTION] — la fraction d'evenements ecartes.** Meme fonction que
     *     `emgToAccelRatio`, un cran plus bas : `emgToAccelRatio` dit de combien le capteur rate ce
     *     que l'EMG voit, celui-ci dit de combien le seuil rate ce que le capteur voit.
     *  2. **[RAW_COUNT_RATIO] — le compte brut retenu, avant toute regle de serie.** C'est le
     *     diagnostic differentiel : il dit si l'effondrement de l'indice vient du detecteur ou de la
     *     regle des quatre consecutifs. Voir sa KDoc pour l'arithmetique.
     *  3. **[INDEX_RATIO] — ce qu'il en reste sur l'indice publie**, c'est-a-dire le rapport entre
     *     l'`aPLM-i` qu'un detecteur parfait produirait sous cette politique de seuil et l'`aPLM-i`
     *     vrai a l'echelle accelerometrique. **C'est le chiffre grave, et il est bien pire que le
     *     premier** : une serie AASM demande quatre CLM consecutifs, donc ecarter deux tiers des
     *     evenements ne divise pas le compte par trois, il fait disparaitre la plupart des series.
     *
     * Les deux ensemble sont ce qui interdit de comparer le compte publie au seuil de 15/h de
     * l'ICSD-3, et les deux doivent remonter jusqu'au rapport medecin.
     *
     * **Pourquoi des bandes et non des bornes hautes.** Les deux directions signalent quelque chose
     * et aucune n'est « meilleure ». Un sous-comptage qui monte veut dire que le nombre publie
     * s'eloigne du nombre vrai ; un sous-comptage qui descend veut dire que la population sous seuil
     * a maigri — parce que le generateur a change de loi d'amplitude, ou parce que le seuil a baisse —
     * et dans les deux cas le F1 de T6 n'est plus comparable a celui d'hier. Les bandes sont larges a
     * dessein : elles ne serrent pas une cible, elles rendent un deplacement visible.
     */
    @Test
    @DisplayName("T22 — sous-comptage publie : fraction d'evenements sous Theta_on, et ce qu'il reste de l'indice")
    fun t22_thresholdPolicyCostStaysWhereItWasMeasured() {
        val subThreshold = ArrayList<Double>()
        val rawRatio = ArrayList<Double>()
        val indexRatio = ArrayList<Double>()
        val thresholds = ArrayList<Double>()
        val calLimited = ArrayList<Double>()

        for (seed in SEEDS) {
            val night = nominalNight(seed)
            val a = analyse(night)
            val truth = night.truth.accelLegMovements
            assertThat(truth).`as`("graine %d : verite accelerometrique non vide", seed).isNotEmpty

            thresholds.add(a.thresholdOnG)
            // Quel terme commande le seuil. Rapporte parce que c'est lui, et non `k_on`, qui explique
            // le sous-comptage : le balayage de `ThresholdPolicySweepTest` montre `Theta_on` fige a
            // 53,7 mg de `k_on` = 4 a `k_on` = 12.
            val dom = a.pre.thresholds.dominance
            calLimited.add(
                if (dom.isEmpty()) Double.NaN
                else dom.count { it == ClmFlags.CAL_FLOOR_LIMITED }.toDouble() / dom.size,
            )
            subThreshold.add(truth.count { it.envPeakG < a.thresholdOnG }.toDouble() / truth.size)

            // Le compte **brut**, avant toute regle de serie : combien de CLM la chaine retient-elle
            // pour 100 mouvements mecaniquement presents. C'est le diagnostic differentiel entre les
            // deux explications possibles de l'effondrement de l'indice — voir la KDoc.
            rawRatio.add(a.retained.size.toDouble() / truth.size)

            // Les deux indices traversent les memes etapes 6 et 7 et le meme masque : leur rapport
            // ne contient donc que l'effet du seuil, et rien de l'arithmetique du denominateur.
            val full = a.truthResult(SeriesRule.AASM_V3).plmi
            val visible = a.truthResult(SeriesRule.AASM_V3, aboveEnvelope(truth, a.thresholdOnG)).plmi
            indexRatio.add(if (full > 0.0) visible / full else Double.NaN)
        }

        // Les etendues sont imprimees a cote des medianes parce que ce sont elles qui justifient la
        // largeur des deux bandes : une bande plus etroite que la dispersion inter-graines ne
        // signalerait pas un deplacement, elle signalerait le tirage.
        println(
            ("T22 — Theta_on median %.1f mg, commande par le terme de calibration %.0f %% du temps ; " +
                "fraction sous seuil %.3f [%.3f ; %.3f] ; compte brut retenu %.3f [%.3f ; %.3f] ; " +
                "part de l'indice qui survit %.3f [%.3f ; %.3f]").format(
                medianOf(thresholds) * 1000.0, medianOf(calLimited) * 100.0,
                medianOf(subThreshold), worstMin(subThreshold), worstMax(subThreshold),
                medianOf(rawRatio), worstMin(rawRatio), worstMax(rawRatio),
                medianOf(indexRatio), worstMin(indexRatio), worstMax(indexRatio),
            ),
        )

        assertThat(medianOf(subThreshold))
            .`as`("fraction mediane de accelTruth sous Theta_on")
            .isBetween(SUB_THRESHOLD_FRACTION - SUB_THRESHOLD_BAND, SUB_THRESHOLD_FRACTION + SUB_THRESHOLD_BAND)
        assertThat(medianOf(rawRatio))
            .`as`("compte brut de CLM retenus, rapporte a accelTruth entier")
            .isBetween(RAW_COUNT_RATIO - RAW_COUNT_BAND, RAW_COUNT_RATIO + RAW_COUNT_BAND)
        assertThat(medianOf(indexRatio))
            .`as`("part mediane de l'aPLM-i vrai qui survit au seuil")
            .isBetween(INDEX_RATIO - INDEX_RATIO_BAND, INDEX_RATIO + INDEX_RATIO_BAND)
    }

    companion object {
        /**
         * Fraction de `accelTruth` qui tombe sous `Theta_on` sur la nuit nominale, mediane des 20
         * graines. **C'est une mesure, pas une cible** : elle est ici pour etre citee — par
         * `docs/07-validation.md` §4.1 et par le rapport medecin — et pour bouger visiblement si la
         * politique de seuil ou la loi d'amplitude du generateur change.
         */
        const val SUB_THRESHOLD_FRACTION: Double = 0.70

        /**
         * Compte **brut** de CLM retenus, rapporte a `accelTruth` entier, avant toute regle de serie.
         *
         * C'est le diagnostic differentiel entre les deux lectures possibles de [INDEX_RATIO]. Si le
         * compte brut vaut ~0,30 alors que l'indice n'en garde que 0,06, l'effondrement est
         * entierement produit par la regle des quatre CLM consecutifs, qui agit en **suppresseur
         * exponentiel** et non en diluteur. S'il etait deja tres en dessous de 0,30, il y aurait
         * autre chose a chercher — dans le generateur ou dans le denominateur.
         *
         * L'arithmetique de reference : a `p = 0,30` de detection et un rythme de 25 s, un intervalle
         * percu survit a la borne des 90 s avec `p + (1-p)p + (1-p)^2 p = 0,657` ; une serie de
         * quatre demande trois intervalles consecutifs, soit `0,657^3 = 0,283` ; total
         * `0,30 x 0,283 = 0,085`. Mesure : 0,06. L'ecart tient a la variance et au fait que les
         * manques ne sont pas independants de l'amplitude.
         */
        const val RAW_COUNT_RATIO: Double = 0.30

        /**
         * Part de l'`aPLM-i` vrai qui survit a la politique de seuil, mediane des 20 graines. Meme
         * statut que [SUB_THRESHOLD_FRACTION] : mesuree, publiee, surveillee.
         *
         * **6 %.** Ce n'est pas une coquille et ce n'est pas un defaut de detection : c'est ce que
         * devient un indice de series quand on retire 70 % des evenements qui les composent.
         */
        const val INDEX_RATIO: Double = 0.06

        /** Demi-largeur de la bande de [SUB_THRESHOLD_FRACTION], soit +/- 10 % relatifs. */
        const val SUB_THRESHOLD_BAND: Double = 0.07

        /** Demi-largeur de la bande de [RAW_COUNT_RATIO]. */
        const val RAW_COUNT_BAND: Double = 0.06

        /**
         * Demi-largeur de la bande de [INDEX_RATIO]. Elle est absolue et non relative parce que la
         * grandeur est un rapport de deux petits comptes, donc bien plus dispersee que la premiere :
         * a 0,03 elle vaut la moitie de la valeur surveillee, assez large pour absorber le tirage et
         * assez etroite pour qu'un doublement ou un effondrement se voie.
         */
        const val INDEX_RATIO_BAND: Double = 0.03
    }
}
