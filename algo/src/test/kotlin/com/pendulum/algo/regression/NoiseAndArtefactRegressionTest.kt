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
 * T1 a T4 du tableau `docs/fr/ALGO-v2.md` §5.5 — les quatre scenarios **negatifs**.
 *
 * Ce sont les tests les plus importants de la suite et les moins spectaculaires : un detecteur qui
 * echoue ici ne mesure pas des mouvements periodiques, il mesure du bruit ambiant. Chacun tourne sur
 * les 20 graines ; l'assertion porte sur la mediane, et sur le pire cas la ou la specification
 * l'exige (T1).
 */
class NoiseAndArtefactRegressionTest {

    /**
     * **T1 — bruit MEMS seul, 30 min : 0 CLM. Non negociable, pire cas inclus.**
     *
     * Intention : c'est le garde-fou du plancher absolu `Theta_abs` (§1.1). Le seuil relatif seul
     * tomberait vers 7 mg sur une nuit aussi calme, et le detecteur compterait des micro-vibrations.
     *
     * L'assertion de fraction analysable n'est pas decorative : le bruit MEMS pur a un ecart-type
     * bien inferieur au seuil `offBodySdG` de l'etape 0, et sans la derive posturale lente du membre
     * porteur ([com.pendulum.algo.synth.NoiseSpec.wanderDeg]) la nuit entiere serait classee off-body.
     * Le test passerait alors **a vide**, ce qui est pire qu'un echec.
     */
    @Test
    @DisplayName("T1 — bruit MEMS seul : aucun CLM, sur les 20 graines, pire cas inclus")
    fun t1_memsNoiseOnlyProducesNoClm() {
        val counts = SEEDS.map { seed ->
            val a = analyse(distractorOnlyNight(seed, minutes = 30.0, distractors = DistractorSpec.NONE))
            assertThat(a.analysableFraction)
                .`as`("graine %d : la nuit doit rester analysable, sinon le test est vide", seed)
                .isGreaterThan(0.80)
            a.retained.size.toDouble()
        }
        assertThat(medianOf(counts)).isEqualTo(0.0)
        assertThat(worstMax(counts)).`as`("T1 est non negociable : pire cas inclus").isEqualTo(0.0)
    }

    /**
     * **T2 — respiration seule (8 mg a 0,25 Hz), 30 min : 0 CLM.**
     *
     * Intention : verifier le coude bas du passe-haut (§1.2). A 0,5 Hz et a l'ordre 2, un signal a
     * 0,25 Hz est attenue d'environ 12 dB ; 8 mg deviennent 2 mg, tres au-dessous du plancher
     * absolu. C'est ce test qui justifie de garder `fcHpHz = 0,50` plutot que 0,30.
     */
    @Test
    @DisplayName("T2 — artefact respiratoire seul : aucun CLM")
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
     * **T3 — vibrations de matelas seules, 300 transitoires en 30 min : au plus 2 CLM.**
     *
     * Intention : mesurer le critere de morphologie WASM 3.2.1-d (§3.2). Une vibration transmise est
     * une sonnerie de 0,05 a 0,4 s : crete elevee, **mediane faible**, et surtout tilt inchange. La
     * fenetre de morphologie de 0,5 s est le seul filtre anti-matelas du corpus des regles publiees ;
     * tout le reste serait une invention.
     *
     * 2 sur 300 vaut 0,7 % de faux positifs. C'est le chiffre de la specification, et il est serre :
     * la borne haute de la plage d'amplitude (40 mg) est au double du plancher absolu.
     */
    @Test
    @DisplayName("T3 — 300 vibrations de matelas : au plus 2 CLM (0,7 % de FP)")
    fun t3_mattressVibrationsProduceAtMostTwoClm() {
        val onlyMattress = DistractorSpec.NONE.copy(mattressCountMin = 300, mattressCountMax = 300)
        val counts = SEEDS.map { seed ->
            analyse(distractorOnlyNight(seed, minutes = 30.0, distractors = onlyMattress))
                .retained.size.toDouble()
        }
        assertThat(medianOf(counts)).isLessThanOrEqualTo(2.0)
    }

    /**
     * **T4 — 40 changements de posture seuls : 0 CLM non tague `POSTURAL`, rappel du detecteur de
     * posture >= 0,95.**
     *
     * Intention : la posture est **la premiere source de faux positifs**. Un retournement change la
     * projection de la gravite d'un axe de jusqu'a 1 g en 0,5 a 3 s ; passe dans le passe-haut a
     * 0,5 Hz, cet echelon produit un transitoire de 5 a 30 fois l'amplitude d'un vrai CLM et de la
     * bonne duree pour etre compte. La seule information qui les separe est portee par la gravite.
     *
     * Le premier volet se lit litteralement : le drapeau `POSTURAL` entraine le rejet, donc « aucun
     * CLM sans le drapeau » equivaut a « aucun CLM du tout » sur une nuit ou il n'y a rien d'autre.
     * Les deux formulations sont ecrites, pour que l'echec designe la bonne cause.
     */
    @Test
    @DisplayName("T4 — 40 changements de posture : aucun CLM accepte, rappel de posture >= 0,95")
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

            // Rappel : un changement injecte est retrouve si une transition est datee a moins de 5 s.
            // La tolerance est large devant `settleMs` et etroite devant l'espacement injecte
            // (12 min) : elle ne peut pas apparier deux transitions differentes.
            val detected = a.postures.map { it.atMsRel }
            val matched = night.truth.postures.count { t -> detected.any { abs(it - t) <= 5_000L } }
            recalls.add(if (night.truth.postures.isEmpty()) Double.NaN else matched.toDouble() / night.truth.postures.size)
        }

        assertThat(medianOf(counts)).isEqualTo(0.0)
        assertThat(medianOf(recalls)).isGreaterThanOrEqualTo(0.95)
    }
}
