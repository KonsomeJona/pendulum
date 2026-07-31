package com.pendulum.algo.dsp

import com.pendulum.algo.model.DualEnvelope
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.Timeline

/**
 * **Toutes** les valeurs de parametres du pretraitement, regroupees, avec les defauts **exacts**
 * des tableaux §6.1 (integrite et pretraitement) et §6.2 (enveloppe et plancher).
 *
 * Un seul objet pour une seule raison : ces valeurs entrent dans le hash de parametres qui
 * accompagne chaque `PlmiResult`. Deux nuits calculees avec des reglages differents ne sont pas
 * comparables, et le seul moyen fiable de s'en apercevoir est que le hash change. Eparpiller les
 * constantes dans les appels rendrait ce hash incomplet, donc mensonger.
 *
 * Les champs marques INTERPRETATION n'ont pas de ligne dans les tableaux de la specification ;
 * leur justification est donnee a l'endroit ou ils sont consommes.
 */
data class PreprocessConfig(
    // --- §6.1 : integrite et ligne de temps ---
    val timeline: TimelineConfig = TimelineConfig(),
    // --- §6.1 : separation gravite / mouvement ---
    val fcGravityHz: Double = 0.15,
    val fcHpHz: Double = 0.50,
    val fcLpHz: Double = 8.0,
    val hpOrder: Int = 2,
    // --- §6.2 : enveloppes ---
    val coarseEnvSec: Double = 0.50,
    val fineEnvSec: Double = 0.15,
    // --- §6.2 : plancher de bruit ---
    val noiseFloor: NoiseFloorConfig = NoiseFloorConfig(),
    // --- §6.3 : seuils (les quatre valeurs utilisees par l'etape 4) ---
    val thresholds: ThresholdParams = ThresholdParams(),
) {
    /** `Theta_abs / k_on` — borne inferieure du plancher, cf. §1.3 derniere ligne. */
    val floorMinG: Float get() = (thresholds.absFloorG / thresholds.kOn).toFloat()
}

/** Tout ce que le pretraitement produit, dans l'ordre ou les etapes suivantes le consomment. */
data class Preprocessed(
    val timeline: Timeline,
    val gravity: com.pendulum.algo.model.TriAxial,
    val linear: com.pendulum.algo.model.TriAxial,
    val magnitude: Signal1D,
    val envelope: DualEnvelope,
    val floor: Signal1D,
    val floorExtrapolated: BooleanArray,
    val thresholds: ThresholdCurves,
)

/**
 * Enchainement des etapes −1 a 4. Fonction pure : meme entree -> meme sortie au bit pres.
 *
 * L'ordre n'est pas negociable, chaque etape consommant strictement la sortie de la precedente :
 * integrite -> ligne de temps -> gravite/mouvement -> magnitude -> enveloppes -> plancher ->
 * seuils.
 */
object Preprocess {

    /**
     * @param postureBoundaries frontieres de changement de posture, en index de grille. Elles
     *   **coupent les fenetres du plancher** au meme titre que les frontieres de segment (§3.1,
     *   effet de bord (a)). Elles ne sont pas connues au premier passage — le detecteur de
     *   posture travaille sur `g_chapeau`, donc apres l'etape 1 — d'ou l'appel en deux temps
     *   possible : un premier `run` sans frontieres pour obtenir `gravity`, puis un second avec.
     * @param calibration fournit `gainCalG` au troisieme terme du seuil. `null` = terme desactive.
     */
    fun run(
        blocks: List<SampleBlock>,
        nominalHz: Double,
        cfg: PreprocessConfig = PreprocessConfig(),
        postureBoundaries: IntArray = IntArray(0),
        calibration: NightCalibration? = null,
        sessionClosedCleanly: Boolean = true,
    ): Preprocessed {
        val timeline = TimelineBuilder.build(blocks, nominalHz, cfg.timeline, sessionClosedCleanly)
        val split = Gravity.split(
            timeline.signal, timeline.segments,
            cfg.fcGravityHz, cfg.fcHpHz, cfg.fcLpHz, cfg.hpOrder, cfg.timeline.settleSec,
        )
        val magnitude = Envelope.magnitudeL2(split.linear)
        val env = Envelope.dual(magnitude, timeline.segments, cfg.coarseEnvSec, cfg.fineEnvSec)
        val (floor, extrapolated) = NoiseFloor.estimate(
            env.coarse, timeline.segments, postureBoundaries, cfg.noiseFloor, cfg.floorMinG,
        )
        val curves = Thresholds.compute(floor, calibration?.gainCalG ?: Float.NaN, cfg.thresholds)
        return Preprocessed(
            timeline = timeline,
            gravity = split.gravity,
            linear = split.linear,
            magnitude = magnitude,
            envelope = env,
            floor = floor,
            floorExtrapolated = extrapolated,
            thresholds = curves,
        )
    }
}
