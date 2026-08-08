package com.pendulum.algo.regression

import com.pendulum.algo.synth.DetectionRun
import com.pendulum.algo.synth.Scoring
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * T5 du tableau `docs/fr/ALGO-v2.md` §5.5 — courbe de sensibilite en amplitude.
 *
 * **Ce que ce test verifie vraiment, et pourquoi son abscisse est ce qu'elle est.** L'enonce dit
 * « Se dans [0,35 ; 0,65] a 8x le plancher (le seuil, par construction) ». Or le seuil de
 * declenchement vaut `max(k_on . plancher, Theta_abs, f_cal . gainCal)` et, sur une nuit calme,
 * c'est le **plancher absolu** qui l'emporte, pas le terme relatif : rapporter l'amplitude au
 * plancher de bruit brut donnerait un « 8x » qui ne correspondrait a rien. L'abscisse retenue est
 * donc le rapport a `Theta_on / k_on`, le plancher **effectif** du detecteur, seule grandeur sous
 * laquelle « 8x = le seuil » est vrai par construction.
 *
 * L'ordonnee est mesuree contre `accelTruth`, comme toutes les metriques (§5.3). La nuit est ici
 * generee sans rotations pures de cheville pour que la sensibilite mesuree ne soit pas confondue
 * avec le taux de manques mecanique — c'est deux choses differentes et T5 ne mesure que la premiere.
 */
class SensitivityRegressionTest {

    /**
     * **T5 — Se <= 0,05 a 4x ; Se dans [0,35 ; 0,65] a 8x ; Se >= 0,95 a 16x.**
     *
     * Intention : borner la pente de la courbe de detection. Trop raide, le detecteur est un
     * comparateur et la moindre derive de gain change le compte ; trop molle, le seuil ne veut plus
     * rien dire et la moitie du bruit passe. La pente est pilotee par `sigmaLog` du generateur, qui
     * est pour cette raison un parametre et non une constante.
     */
    @Test
    @DisplayName("T5 — courbe de sensibilite : 0,05 a 4x, ~0,5 a 8x, 0,95 a 16x le plancher effectif")
    fun t5_sensitivityCurveCrossesFiftyPercentAtTheThreshold() {
        val ratios = doubleArrayOf(4.0, 8.0, 16.0)
        val sensitivities = ratios.map { ArrayList<Double>() }
        val runs = ArrayList<DetectionRun>()

        for (seed in SEEDS) {
            // Plancher effectif mesure sur une nuit de bruit seul de meme specification : il ne
            // depend pas des mouvements, donc le mesurer a part n'introduit aucune circularite.
            val floor = probeEffectiveFloorG(seed)
            assertThat(floor).`as`("plancher effectif de la graine %d", seed).isGreaterThan(0.0)

            for ((k, ratio) in ratios.withIndex()) {
                val night = fixedAmplitudeNight(seed, minutes = 20.0, envelopeAmplitudeG = ratio * floor)
                // Calibration desactivee : le terme `f_cal . gainCal` deplacerait le seuil et
                // l'abscisse ne serait plus celle que l'enonce decrit.
                val a = analyse(night, calibrated = false)
                val m = Scoring.match(a.retained, night.truth.accelLegMovements)
                sensitivities[k].add(m.sensitivity)
                runs.add(DetectionRun(night.truth.accelLegMovements, a.retained, a.effectiveFloorG))
            }
        }

        val se4 = medianOf(sensitivities[0])
        val se8 = medianOf(sensitivities[1])
        val se16 = medianOf(sensitivities[2])

        assertThat(se4).`as`("Se a 4x le plancher effectif").isLessThanOrEqualTo(0.05)
        assertThat(se8).`as`("Se au seuil (8x)").isBetween(0.35, 0.65)
        assertThat(se16).`as`("Se a 16x le plancher effectif").isGreaterThanOrEqualTo(0.95)

        // La courbe agregee doit etre monotone : une sensibilite qui redescend quand l'amplitude
        // monte signale une machine d'etats qui fusionne ou tronque les gros evenements.
        //
        // `minCount = 20` n'affaiblit pas l'assertion, il la rend mesurable. Le balayage place tous
        // ses evenements a exactement 4x, 8x et 16x : les trois bins reels comptent ~1 080 evenements
        // chacun, et les bins intermediaires ne recoivent que le residu de calage de l'amplitude —
        // 3 evenements sur 3 240 dans [6 ; 8), 1 dans [3 ; 4). Le bin a 3 evenements sortait a 2/3 et
        // faisait echouer la monotonie contre un bin a 1 077 evenements. Comparer un taux estime sur
        // 3 tirages a un taux estime sur mille ne teste pas le detecteur, il teste le tirage.
        val curve = Scoring.sensitivityCurve(runs, minCount = 20)
        assertThat(curve).isNotEmpty
        for (i in 1 until curve.size) {
            assertThat(curve[i].second)
                .`as`("courbe de sensibilite non monotone entre %s et %s", curve[i - 1], curve[i])
                .isGreaterThanOrEqualTo(curve[i - 1].second - 1e-9)
        }
    }
}
