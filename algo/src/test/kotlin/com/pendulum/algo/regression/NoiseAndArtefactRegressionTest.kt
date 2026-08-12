package com.pendulum.algo.regression

import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth
import kotlin.math.abs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * T1 to T4 of the `docs/workings/ALGO-v2.md` §5.5 table — the four **negative** scenarios.
 *
 * These are the most important tests of the suite and the least spectacular: a detector that fails
 * here is not measuring periodic movements, it is measuring ambient noise. Each one runs on the 20
 * seeds; the assertion bears on the median, and on the worst case where the specification demands it
 * (T1).
 */
class NoiseAndArtefactRegressionTest {

    /**
     * **T1 — MEMS noise alone, 30 min: 0 CLM. Non-negotiable, worst case included.**
     *
     * Intent: this is the guard rail of the absolute floor `Theta_abs` (§1.1). The relative
     * threshold alone would drop towards 7 mg on a night this quiet, and the detector would count
     * micro-vibrations.
     *
     * The analysable-fraction assertion is not decorative: pure MEMS noise has a standard deviation
     * well below the `offBodySdG` threshold of step 0, and without the slow postural drift of the
     * wearing limb ([com.pendulum.algo.synth.NoiseSpec.wanderDeg]) the whole night would be
     * classified off-body. The test would then pass **on nothing**, which is worse than a failure.
     */
    @Test
    @DisplayName("T1 — MEMS noise alone: no CLM, over the 20 seeds, worst case included")
    fun t1_memsNoiseOnlyProducesNoClm() {
        val counts = SEEDS.map { seed ->
            val a = analyse(distractorOnlyNight(seed, minutes = 30.0, distractors = DistractorSpec.NONE))
            assertThat(a.analysableFraction)
                .`as`("seed %d: the night must stay analysable, otherwise the test is empty", seed)
                .isGreaterThan(0.80)
            a.retained.size.toDouble()
        }
        assertThat(medianOf(counts)).isEqualTo(0.0)
        assertThat(worstMax(counts)).`as`("T1 is non-negotiable: worst case included").isEqualTo(0.0)
    }

    /**
     * **T2 — respiration alone (8 mg at 0.25 Hz), 30 min: 0 CLM.**
     *
     * Intent: check the low corner of the high-pass (§1.2). At 0.5 Hz and order 2, a signal at
     * 0.25 Hz is attenuated by about 12 dB; 8 mg become 2 mg, far below the absolute floor. It is
     * this test that justifies keeping `fcHpHz = 0.50` rather than 0.30.
     */
    @Test
    @DisplayName("T2 — respiratory artefact alone: no CLM")
    fun t2_respiratoryArtefactProducesNoClm() {
        val onlyRespiration = DistractorSpec.NONE.copy(
            respiratory = true,
            respHzMin = 0.25, respHzMax = 0.25,
            respAmpMinG = 0.008, respAmpMaxG = 0.008,
        )
        val counts = SEEDS.map { seed ->
            analyse(distractorOnlyNight(seed, minutes = 30.0, distractors = onlyRespiration))
                .retained.size.toDouble()
        }
        assertThat(medianOf(counts)).isEqualTo(0.0)
        assertThat(worstMax(counts)).isLessThanOrEqualTo(0.0)
    }

    /**
     * **T3 — mattress vibrations alone, 300 transients in 30 min: at most 2 CLM.**
     *
     * Intent: measure the WASM 3.2.1-d morphology criterion (§3.2). A transmitted vibration is a
     * ringing of 0.05 to 0.4 s: high peak, **low median**, and above all unchanged tilt. The 0.5 s
     * morphology window is the only anti-mattress filter in the corpus of published rules; anything
     * else would be an invention.
     *
     * 2 out of 300 is 0.7 % of false positives. That is the specification's figure, and it is tight:
     * the upper bound of the amplitude range (40 mg) is at twice the absolute floor.
     */
    @Test
    @DisplayName("T3 — 300 mattress vibrations: at most 2 CLM (0.7 % of FP)")
    fun t3_mattressVibrationsProduceAtMostTwoClm() {
        val onlyMattress = DistractorSpec.NONE.copy(mattressCountMin = 300, mattressCountMax = 300)
        val counts = SEEDS.map { seed ->
            analyse(distractorOnlyNight(seed, minutes = 30.0, distractors = onlyMattress))
                .retained.size.toDouble()
        }
        assertThat(medianOf(counts)).isLessThanOrEqualTo(2.0)
    }

    /**
     * **T4 — 40 posture changes alone: 0 CLM not tagged `POSTURAL`, recall of the posture detector
     * >= 0.95.**
     *
     * Intent: posture is **the first source of false positives**. A turn changes the projection of
     * gravity on an axis by up to 1 g in 0.5 to 3 s; passed through the high-pass at 0.5 Hz, that
     * step produces a transient of 5 to 30 times the amplitude of a true CLM and of the right
     * duration to be counted. The only information that separates them is carried by gravity.
     *
     * The first part reads literally: the `POSTURAL` flag entails rejection, so "no CLM without the
     * flag" is equivalent to "no CLM at all" on a night where there is nothing else. Both
     * formulations are written down, so that the failure points at the right cause.
     */
    @Test
    @DisplayName("T4 — 40 posture changes: no CLM accepted, posture recall >= 0.95")
    fun t4_postureChangesAreNeitherCountedNorMissed() {
        val onlyPosture = DistractorSpec.NONE.copy(postureCountMin = 40, postureCountMax = 40)
        val counts = ArrayList<Double>()
        val recalls = ArrayList<Double>()

        for (seed in SEEDS) {
            val night = NightSynth.generate(
                NightSpec(
                    durationH = 8.0,
                    trueSeries = emptyList(),
                    isolatedClmPerHour = 0.0,
                    distractors = onlyPosture,
                ),
                seed,
            )
            val a = analyse(night)
            counts.add(a.clms.count { it.isClm && (it.flags and ClmFlags.POSTURAL) == 0 }.toDouble())

            // Recall: an injected change is found again if a transition is dated within 5 s. The
            // tolerance is wide compared to `settleMs` and narrow compared to the injected spacing
            // (12 min): it cannot match two different transitions.
            val detected = a.postures.map { it.atMsRel }
            val matched = night.truth.postures.count { t -> detected.any { abs(it - t) <= 5_000L } }
            recalls.add(if (night.truth.postures.isEmpty()) Double.NaN else matched.toDouble() / night.truth.postures.size)
        }

        assertThat(medianOf(counts)).isEqualTo(0.0)
        assertThat(medianOf(recalls)).isGreaterThanOrEqualTo(0.95)
    }
}
