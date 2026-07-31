package com.pendulum.algo.regression

import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.synth.Scoring
import kotlin.math.abs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * T6 et T7 du tableau `docs/ALGO-v2.md` §5.5 — la nuit nominale et la nuit negative.
 *
 * **Le point conceptuel de T6 est le denominateur du score.** Le `F1 >= 0,90` est celui de la v1,
 * mais il est desormais mesure contre `accelTruth`, pas contre l'ensemble des mouvements generes.
 * Contre `emgTruth`, la sensibilite plafonne mecaniquement a 0,61 (Terrill : 39,0 % des LM EMG ne
 * deplacent aucun capteur de cheville) et le F1 ne peut pas depasser ~0,76 : un seuil de 0,90 y
 * serait inatteignable **pour une raison qui n'est pas la faute de l'algorithme**. La distinction
 * des deux jeux d'etiquettes est ce qui rend le critere honnete.
 *
 * Le facteur de conversion entre les deux echelles est rapporte a chaque execution : c'est lui qui
 * interdit de comparer le compte publie au seuil de 15/h de l'ICSD-3.
 */
class NominalNightRegressionTest {

    /**
     * **T6 — nuit nominale, tous distracteurs : F1 >= 0,90 vs `accelTruth`, |dPLMI|/PLMI <= 0,10,
     * |dPI| <= 0,05, biais d'onset <= 300 ms, ecart-type <= 400 ms.**
     *
     * Intention : c'est le test d'ensemble. Il est compare a l'attendu calcule sur `accelTruth` en
     * traversant **les memes** etapes 6 et 7 et avec le **meme** objet masque que la mesure : toute
     * difference restante est donc imputable a la detection, ce qui est ce que le seuil pretend
     * borner. Si l'attendu et la mesure ne partageaient pas leur denominateur, ce test mesurerait
     * surtout l'arithmetique du masque.
     */
    @Test
    @DisplayName("T6 — nuit nominale : F1 >= 0,90, dPLMI <= 10 %, dPI <= 0,05, datation <= 300/400 ms")
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
            val expected = a.truthResult(SeriesRule.AASM_V3)

            // Garde-fou de scenario : si la nuit generee ne ressemble pas a la nuit decrite par
            // l'enonce, le seuil ne veut rien dire. On le verifie plutot que de le supposer.
            assertThat(expected.plmi)
                .`as`("graine %d : aPLM-i vrai (echelle accelerometrique)", seed)
                .isBetween(15.0, 40.0)

            val m = Scoring.match(a.retained, night.truth.accelLegMovements)
            f1.add(m.f1)
            bias.add(abs(m.onsetBiasMs))
            sd.add(m.onsetSdMs)
            plmiErr.add(relDiff(expected.plmi, measured.plmi))
            piErr.add(abs(expected.pi.periodicityIndex - measured.pi.periodicityIndex))
            conversion.add(night.truth.emgToAccelRatio)
        }

        assertThat(medianOf(f1)).`as`("F1 median vs accelTruth").isGreaterThanOrEqualTo(0.90)
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
}
