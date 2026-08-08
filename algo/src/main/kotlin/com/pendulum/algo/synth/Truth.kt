package com.pendulum.algo.synth

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.Gap
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.SleepMask

/** Nature d'un evenement injecte. Transcription de `docs/fr/ALGO-v2.md` §5.3. */
enum class TruthKind {
    PLM_IN_SERIES,
    ISOLATED,
    RRLM,
    GROSS_BODY,
    POSTURE,
    ALMA,
    MATTRESS,
}

/**
 * Un evenement injecte, avec toute la physique qui l'a produit.
 *
 * @param peakG crete du **canal mouvement** (hors gravite statique) reellement rendue, en g.
 * @param envPeakG crete de l'enveloppe RMS grossiere de 0,5 s du meme signal. C'est la grandeur que
 *   le detecteur compare a `Theta_on` : c'est donc elle, et non [peakG], qui porte l'axe des
 *   abscisses de la courbe de sensibilite (T5).
 * @param ankleOnly rotation pure de la cheville : `r_eff ~ 0`, le boitier ne se deplace pas.
 *   Mecaniquement invisible, quelle que soit l'activite EMG. C'est le mecanisme de Terrill.
 */
data class TruthEvent(
    val onsetMsRel: Long,
    val durationMs: Int,
    val peakG: Float,
    val envPeakG: Float,
    val thetaMaxDeg: Float,
    val tRiseSec: Float,
    val radiusM: Float,
    val ankleOnly: Boolean,
    val kind: TruthKind,
    val seriesId: Int?,
) {
    val endMsRel: Long get() = onsetMsRel + durationMs

    /** Mouvement de jambe candidat a un CLM. Exclut les artefacts (matelas) et les GBM. */
    val isLegMovement: Boolean
        get() = kind == TruthKind.PLM_IN_SERIES || kind == TruthKind.ISOLATED || kind == TruthKind.RRLM
}

/**
 * Verite terrain a **deux jeux d'etiquettes** (§5.3). C'est le point conceptuel du generateur.
 *
 * [emgTruth] est l'echelle d'un laboratoire : tout ce qui a ete genere. [accelTruth] est le
 * sous-ensemble **mecaniquement visible au capteur** — `emgTruth` prive des evenements `ankleOnly`
 * et de ceux dont la crete simulee tombe sous le seuil de visibilite physique.
 *
 * **Toutes les metriques du detecteur se scorent contre [accelTruth].** [emgTruth] ne sert qu'a une
 * chose, mais elle est essentielle : mesurer et rapporter [emgToAccelRatio], le facteur de
 * conversion entre les deux echelles, c'est-a-dire le biais structurel a la baisse du compte publie.
 * C'est le chiffre qui interdit de comparer directement notre `aPLM-i` au seuil de 15/h de
 * l'ICSD-3 — et sans cette distinction, le critere « F1 >= 0,90 » de T6 serait inatteignable pour
 * une raison qui n'est pas la faute de l'algorithme (contre `emgTruth`, F1 plafonne vers 0,76).
 *
 * @param mask masque de sommeil **vrai**, au sens d'un journal parfait. Ajout au canevas de §5.3 :
 *   sans lui, aucune metrique horaire n'est calculable et le denominateur redeviendrait circulaire.
 * @param floorG plancher d'enveloppe grossiere du bruit injecte seul (sans aucun mouvement). Sert a
 *   normaliser l'axe des abscisses de la courbe de sensibilite.
 * @param gainCalG le gain mecanique de reference de cette nuit-la, couplage
 *   mecanique de la nuit compris.
 */
class GroundTruth(
    val emgTruth: List<TruthEvent>,
    val accelTruth: List<TruthEvent>,
    val postures: List<Long>,
    val gaps: List<Gap>,
    val mask: SleepMask,
    val expectedPlmiAasm: Double,
    val expectedPlmiWasm: Double,
    val expectedPlmw: Double,
    val expectedPi: Double,
    val expectedTstMin: Double,
    val expectedSptMin: Double,
    val floorG: Double,
    val gainCalG: Float,
    val gainMultiplierApplied: Float,
    val fsRealHz: Double,
    val truncatedAtMs: Long?,
) {
    /** Mouvements de jambe seuls (PLM, isoles, RRLM), a l'echelle EMG. */
    val emgLegMovements: List<TruthEvent> = emgTruth.filter { it.isLegMovement }

    /** Mouvements de jambe seuls, a l'echelle accelerometrique. **La reference de tout score.** */
    val accelLegMovements: List<TruthEvent> = accelTruth.filter { it.isLegMovement }

    /**
     * Facteur de conversion EMG -> accelerometre, dans `[0, 1]`. Attendu autour de 0,61 avec la
     * valeur par defaut de `ankleOnlyFraction` (Terrill : 39,0 % des LM EMG sans mouvement
     * detectable), un peu plus bas puisque la queue basse de la loi d'amplitude passe en plus sous
     * le seuil de visibilite physique.
     */
    val emgToAccelRatio: Double
        get() = if (emgLegMovements.isEmpty()) Double.NaN
        else accelLegMovements.size.toDouble() / emgLegMovements.size

    fun eventsOf(kind: TruthKind): List<TruthEvent> = emgTruth.filter { it.kind == kind }
}

/** Une nuit synthetique complete : le signal tel qu'il sortirait du format, et sa verite terrain. */
class SynthNight(
    val blocks: List<SampleBlock>,
    val truth: GroundTruth,
    val seed: Long,
    val spec: NightSpec,
)

/** Grille de reference des instants de verite terrain : celle de l'etape 0 (`targetFsHz`). */
internal const val TARGET_FS_HZ: Double = 50.0

/**
 * Convertit des evenements de verite terrain en [Clm] acceptes, pour repasser la verite dans les
 * **memes** etapes 6 et 7 que la detection.
 *
 * Pourquoi ce detour plutot qu'un calcul d'indice maison : ce qu'on veut mesurer est l'erreur de
 * **detection**, pas un desaccord d'interpretation des regles cliniques. En faisant traverser a la
 * verite terrain le meme `SeriesBuilder` et le meme `Plmi.compute`, toute difference restante est
 * imputable au detecteur, ce qui est exactement ce que T6 pretend mesurer.
 */
fun truthAsClms(events: List<TruthEvent>, floorG: Float = 0.002f): List<Clm> =
    events.sortedBy { it.onsetMsRel }.map { e ->
        val onsetIdx = Math.round(e.onsetMsRel * TARGET_FS_HZ / 1000.0).toInt()
        val offsetIdx = Math.round((e.onsetMsRel + e.durationMs) * TARGET_FS_HZ / 1000.0).toInt()
        Clm(
            onsetIdx = onsetIdx,
            offsetIdx = maxOf(offsetIdx, onsetIdx + 1),
            onsetMsRel = e.onsetMsRel,
            durationMs = e.durationMs,
            peakAmpG = e.peakG,
            medianAmpG = e.envPeakG,
            noiseFloorG = floorG,
            thresholdOnG = floorG * 8f,
            thresholdOffG = floorG * 2.5f,
            tiltChangeDeg = 0f,
            tiltExcursionDeg = e.thetaMaxDeg,
            flags = 0,
            reject = null,
        )
    }
