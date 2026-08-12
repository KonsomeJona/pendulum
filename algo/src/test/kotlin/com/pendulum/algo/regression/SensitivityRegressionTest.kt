package com.pendulum.algo.regression

import com.pendulum.algo.synth.DetectionRun
import com.pendulum.algo.synth.Scoring
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * T5 of the `docs/workings/ALGO-v2.md` §5.5 table — amplitude sensitivity curve.
 *
 * **What this test really checks, and why its abscissa is what it is.** The statement says "Se in
 * [0.35 ; 0.65] at 8x the floor (the threshold, by construction)". But the trigger threshold is
 * `max(k_on . floor, Theta_abs, f_cal . gainCal)` and, on a quiet night, it is the **absolute
 * floor** that wins, not the relative term: referring the amplitude to the raw noise floor would
 * give an "8x" that would correspond to nothing. The abscissa retained is therefore the ratio to
 * `Theta_on / k_on`, the **effective** floor of the detector, the only quantity under which
 * "8x = the threshold" is true by construction.
 *
 * The ordinate is measured against `accelTruth`, like every metric (§5.3). The night is generated
 * here without pure ankle rotations so that the measured sensitivity is not confused with the
 * mechanical miss rate — these are two different things and T5 measures only the first.
 */
class SensitivityRegressionTest {

    /**
     * **T5 — Se <= 0.05 at 4x ; Se in [0.35 ; 0.65] at 8x ; Se >= 0.95 at 16x.**
     *
     * Intent: bound the slope of the detection curve. Too steep, and the detector is a comparator
     * where the slightest gain drift changes the count; too soft, and the threshold no longer means
     * anything and half the noise gets through. The slope is driven by the generator's `sigmaLog`,
     * which is for that reason a parameter and not a constant.
     */
    @Test
    @DisplayName("T5 — sensitivity curve: 0.05 at 4x, ~0.5 at 8x, 0.95 at 16x the effective floor")
    fun t5_sensitivityCurveCrossesFiftyPercentAtTheThreshold() {
        val ratios = doubleArrayOf(4.0, 8.0, 16.0)
        val sensitivities = ratios.map { ArrayList<Double>() }
        val runs = ArrayList<DetectionRun>()

        for (seed in SEEDS) {
            // Effective floor measured on a noise-only night of the same specification: it does not
            // depend on the movements, so measuring it separately introduces no circularity.
            val floor = probeEffectiveFloorG(seed)
            assertThat(floor).`as`("effective floor of seed %d", seed).isGreaterThan(0.0)

            for ((k, ratio) in ratios.withIndex()) {
                val night = fixedAmplitudeNight(seed, minutes = 20.0, envelopeAmplitudeG = ratio * floor)
                // Calibration disabled: the `f_cal . gainCal` term would shift the threshold and the
                // abscissa would no longer be the one the statement describes.
                val a = analyse(night, calibrated = false)
                val m = Scoring.match(a.retained, night.truth.accelLegMovements)
                sensitivities[k].add(m.sensitivity)
                runs.add(DetectionRun(night.truth.accelLegMovements, a.retained, a.effectiveFloorG))
            }
        }

        val se4 = medianOf(sensitivities[0])
        val se8 = medianOf(sensitivities[1])
        val se16 = medianOf(sensitivities[2])

        assertThat(se4).`as`("Se at 4x the effective floor").isLessThanOrEqualTo(0.05)
        assertThat(se8).`as`("Se at the threshold (8x)").isBetween(0.35, 0.65)
        assertThat(se16).`as`("Se at 16x the effective floor").isGreaterThanOrEqualTo(0.95)

        // The aggregated curve must be monotonic: a sensitivity that goes back down when the
        // amplitude goes up signals a state machine that merges or truncates the large events.
        //
        // `minCount = 20` does not weaken the assertion, it makes it measurable. The sweep places
        // all its events at exactly 4x, 8x and 16x: the three real bins hold ~1 080 events each, and
        // the intermediate bins receive only the residue of the amplitude calibration — 3 events out
        // of 3 240 in [6 ; 8), 1 in [3 ; 4). The bin with 3 events came out at 2/3 and made
        // monotonicity fail against a bin with 1 077 events. Comparing a rate estimated on 3 draws
        // to a rate estimated on a thousand does not test the detector, it tests the draw.
        val curve = Scoring.sensitivityCurve(runs, minCount = 20)
        assertThat(curve).isNotEmpty
        for (i in 1 until curve.size) {
            assertThat(curve[i].second)
                .`as`("sensitivity curve not monotonic between %s and %s", curve[i - 1], curve[i])
                .isGreaterThanOrEqualTo(curve[i - 1].second - 1e-9)
        }
    }
}
