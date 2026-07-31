package com.pendulum.algo.model

/**
 * Types partages du module `algo`. Transcription de `docs/ALGO-v2.md` §4.1.
 *
 * Regles du module :
 *  - fonctions pures, aucune I/O, aucune horloge murale, `fs` toujours explicite ;
 *  - aucun `import android.*` ;
 *  - **aucune dependance vers `:format`**. L'entree est [SampleBlock], une interface minimale ;
 *    l'adaptateur au-dessus de `com.pendulum.format.DecodedBlock` vit dans `:phone`. Cela garde le
 *    module testable sans le codec, permet d'injecter du synthetique, et — surtout — permet a
 *    `algo` de **revalider** les timestamps sans heriter des garanties (absentes) du CRC de bloc.
 */

/** Entree minimale. Amplitudes en g. */
interface SampleBlock {
    val tFirstNs: Long
    val tLastNs: Long
    val flags: Int
    val x: FloatArray
    val y: FloatArray
    val z: FloatArray
}

/** Implementation triviale, utilisee par le generateur synthetique et les adaptateurs. */
data class SimpleBlock(
    override val tFirstNs: Long,
    override val tLastNs: Long,
    override val flags: Int,
    override val x: FloatArray,
    override val y: FloatArray,
    override val z: FloatArray,
) : SampleBlock {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimpleBlock) return false
        return tFirstNs == other.tFirstNs && tLastNs == other.tLastNs && flags == other.flags &&
            x.contentEquals(other.x) && y.contentEquals(other.y) && z.contentEquals(other.z)
    }

    override fun hashCode(): Int {
        var r = tFirstNs.hashCode()
        r = 31 * r + tLastNs.hashCode()
        r = 31 * r + flags
        r = 31 * r + x.contentHashCode()
        r = 31 * r + y.contentHashCode()
        r = 31 * r + z.contentHashCode()
        return r
    }
}

/** Signal tri-axial sur grille uniforme. `NaN` = echantillon absent. */
class TriAxial(
    val fsHz: Double,
    val t0Ns: Long,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
) {
    init {
        require(x.size == y.size && y.size == z.size) { "axes de tailles differentes" }
        require(fsHz > 0.0) { "fsHz doit etre > 0" }
    }

    val n: Int get() = x.size

    fun tNs(i: Int): Long = t0Ns + Math.round(i * 1e9 / fsHz)

    fun tMsRel(i: Int): Long = Math.round(i * 1000.0 / fsHz)

    /** Index de la grille correspondant a un instant relatif, borne a `[0, n]`. */
    fun indexOfMsRel(msRel: Long): Int =
        Math.round(msRel * fsHz / 1000.0).toInt().coerceIn(0, n)
}

/** Signal scalaire sur la meme grille uniforme. */
class Signal1D(val fsHz: Double, val t0Ns: Long, val v: FloatArray) {
    val n: Int get() = v.size

    fun tMsRel(i: Int): Long = Math.round(i * 1000.0 / fsHz)
}

/**
 * Enveloppe a deux echelles. La grossiere porte la detection (elle annule l'ondulation a 2f) ;
 * la fine ne sert qu'au recalage des fronts d'un evenement deja detecte.
 */
data class DualEnvelope(
    val coarse: Signal1D,
    val fine: Signal1D,
    val coarseWinSec: Double,
    val fineWinSec: Double,
)

enum class GapKind {
    /** Assez court pour etre interpole sans artefact. */
    MICRO,

    /** Trop long pour interpoler, trop court pour rompre le segment : zone aveugle. */
    BLIND,

    /** Rupture de segment : l'etat des filtres est perdu, la serie est cassee. */
    SEGMENT_BREAK,
}

data class Gap(val fromIdx: Int, val toIdx: Int, val kind: GapKind, val durationSec: Double)

/** Intervalle continu et analysable. Bornes en index de la grille uniforme, `toIdx` exclu. */
data class Segment(val fromIdx: Int, val toIdx: Int) {
    val length: Int get() = toIdx - fromIdx
}

// --- Integrite (etape -1) ---

enum class IntegrityViolation {
    BAD_COUNT,
    BAD_TIMESTAMP_ORDER,
    IMPLAUSIBLE_RATE,
    NON_MONOTONIC,
    OVERLAP,
    IMPLAUSIBLE_GAP,
    BAD_CHUNK_INDEX,
    IMPOSSIBLE_JERK,
    SATURATED,
    GRAVITY_IMPLAUSIBLE,
    FLAG_INCONSISTENT,
}

data class IntegrityReport(
    val blocksTotal: Int,
    val blocksRejected: Int,
    val byViolation: Map<IntegrityViolation, Int>,
    val rejectedFraction: Double,
    /** Vrai si le motif de rejets evoque une desynchronisation du decodeur, pas du bruit. */
    val decodeSuspect: Boolean,
) {
    val acceptable: Boolean get() = rejectedFraction <= 0.01 && !decodeSuspect
}

data class IntegrityConfig(
    val maxRateDeviation: Double = 0.20,
    val maxGapNs: Long = 14L * 3600 * 1_000_000_000,
    val maxJerkG: Float = 8.0f,
    val saturationFraction: Double = 0.05,
    val gravityRangeG: ClosedFloatingPointRange<Float> = 0.80f..1.20f,
)

data class FsEstimate(
    val fsSessionHz: Double,
    val fsNominalHz: Double,
    val blocksRejected: Int,
    /** Derive de `SensorEvent.timestamp` par rapport a l'horloge murale, en ppm. */
    val clockDriftPpm: Double,
    val clockDriftSuspect: Boolean,
)

data class Timeline(
    /** Grille uniforme a `targetFsHz`, `NaN` dans les trous. */
    val signal: TriAxial,
    val gaps: List<Gap>,
    val segments: List<Segment>,
    val blindZones: List<Segment>,
    val fs: FsEstimate,
    val offBody: List<Segment>,
    val integrity: IntegrityReport,
    val analysableSec: Double,
    /** La session s'arrete sans marqueur de fin propre (montre morte, kill). */
    val truncated: Boolean,
)

// --- Evenements ---

object ClmFlags {
    const val POSTURAL = 1 shl 0
    const val GROSS_BODY = 1 shl 1

    /** Depasse `clmMaxSec` : casse la serie, n'est jamais un CLM. */
    const val LM_LONG = 1 shl 2
    const val TRUNCATED = 1 shl 3
    const val IN_BLIND_ZONE = 1 shl 4
    const val FLOOR_EXTRAPOLATED = 1 shl 5
    const val TRANSMITTED_SUSPECT = 1 shl 6

    /** Le seuil etait domine par le plancher absolu. */
    const val ABS_FLOOR_LIMITED = 1 shl 7

    /** Le seuil etait domine par le terme de calibration. */
    const val CAL_FLOOR_LIMITED = 1 shl 8
    const val DURING_WAKE = 1 shl 9
    const val OFF_BODY = 1 shl 10

    /** Serie dont l'IMI median tombe dans la bande apneique. */
    const val RESP_SUSPECT = 1 shl 11
}

enum class ClmRejectReason { TOO_SHORT, MORPHOLOGY, POSTURAL, GROSS_BODY, BLIND_ZONE, OFF_BODY, TRUNCATED }

data class Clm(
    val onsetIdx: Int,
    val offsetIdx: Int,
    val onsetMsRel: Long,
    val durationMs: Int,
    val peakAmpG: Float,
    val medianAmpG: Float,
    val noiseFloorG: Float,
    val thresholdOnG: Float,
    val thresholdOffG: Float,
    val tiltChangeDeg: Float,
    val tiltExcursionDeg: Float,
    val flags: Int,
    val reject: ClmRejectReason?,
) {
    val isClm: Boolean get() = reject == null && (flags and ClmFlags.LM_LONG) == 0
}

data class PostureChange(val atIdx: Int, val atMsRel: Long, val deltaDeg: Float, val settleMs: Int)

enum class SeriesRule { AASM_V3, WASM_2016 }

/**
 * Que faire d'un CLM precede d'un intervalle trop court.
 * C'est **le** parametre qui separe reellement les deux jeux de regles (Ferri 2015) :
 * WASM rompt la serie, AASM est muette et l'on saute l'evenement.
 */
enum class ShortImiPolicy { BREAK_SERIES, SKIP_LATER }

data class PlmSeries(
    val rule: SeriesRule,
    /** Index dans la liste des [Clm] retenus. */
    val clmIndices: IntArray,
    /** Taille = `clmIndices.size - 1`. */
    val imiSec: FloatArray,
    val truncatedAtStart: Boolean,
    val truncatedAtEnd: Boolean,
    val duringSleepFraction: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlmSeries) return false
        return rule == other.rule && clmIndices.contentEquals(other.clmIndices) &&
            imiSec.contentEquals(other.imiSec) && truncatedAtStart == other.truncatedAtStart &&
            truncatedAtEnd == other.truncatedAtEnd && duringSleepFraction == other.duringSleepFraction
    }

    override fun hashCode(): Int {
        var r = rule.hashCode()
        r = 31 * r + clmIndices.contentHashCode()
        r = 31 * r + imiSec.contentHashCode()
        r = 31 * r + truncatedAtStart.hashCode()
        r = 31 * r + truncatedAtEnd.hashCode()
        r = 31 * r + duringSleepFraction.hashCode()
        return r
    }
}

// --- Masque ---

enum class Stage { WAKE, SLEEP, LIGHT, DEEP, REM, AWAKE_IN_BED, OUT_OF_BED, UNKNOWN }

enum class MaskSource { ACCEL_IMMOBILITY, HEALTH_CONNECT, DIARY, FUSED }

/**
 * D'ou vient le denominateur, et est-il circulaire ? Pilote ce que l'interface a le droit
 * d'afficher : un resultat principal ne peut jamais reposer sur [CIRCULAR].
 */
enum class DenominatorIndependence { INDEPENDENT_HC, INDEPENDENT_DIARY, SPT_QUASI_INDEPENDENT, CIRCULAR }

data class SleepWindow(val startMsRel: Long, val endMsRel: Long, val stage: Stage) {
    val durationMin: Double get() = (endMsRel - startMsRel) / 60_000.0
}

/** Journal de sommeil manuel : denominateur totalement independant du signal. */
data class DiaryWindow(val bedTimeMsRel: Long, val riseTimeMsRel: Long)

data class SleepMask(
    val windows: List<SleepWindow>,
    val source: MaskSource,
    val sptMin: Double,
    val tstMin: Double,
    val wasoMin: Double,
    /** TST ∩ segments valides ∩ hors zones aveugles ∩ hors off-body. C'est le vrai denominateur. */
    val analysableTstMin: Double,
    val analysableSptMin: Double,
    val corrected: Boolean,
    val lagAppliedMs: Long,
    val independence: DenominatorIndependence,
    val fixedPointConverged: Boolean,
)

data class MaskAgreement(val kappa: Double, val tstDeltaMin: Double, val overlapPct: Double, val bestLagMs: Long)

// --- Calibration ---

data class SensorCalibration(
    val offsetG: FloatArray,
    val scale: FloatArray,
    val residualG: Float,
    val valid: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensorCalibration) return false
        return offsetG.contentEquals(other.offsetG) && scale.contentEquals(other.scale) &&
            residualG == other.residualG && valid == other.valid
    }

    override fun hashCode(): Int {
        var r = offsetG.contentHashCode()
        r = 31 * r + scale.contentHashCode()
        r = 31 * r + residualG.hashCode()
        r = 31 * r + valid.hashCode()
        return r
    }
}

enum class GainSource { RITUAL, GROSS_BODY, NONE }

data class NightCalibration(
    val sensor: SensorCalibration?,
    val gainCalG: Float,
    val floorCalG: Float,
    val snrCal: Float,
    val gainSource: GainSource,
    /** Le gain s'ecarte de la nuit de reference au-dela de la tolerance : nuit non comparable. */
    val outlierVsBaseline: Boolean,
)

// --- Resultats ---

enum class RespiratoryConfidence { HIGH, MEDIUM, LOW }

enum class FloorMode { BILATERAL, CAUSAL_LAGGED }

/** Ce que l'analyse autorise a publier. Evalue par le code, jamais par l'utilisateur. */
enum class PublicationGate { FULL, TRUNCATED_NO_TREND, NO_PLMI }

data class PiResult(
    val periodicityIndex: Double,
    val valid: Boolean,
    val totalIntervals: Int,
    val lmRatePerHour: Double,
)

/**
 * Rythme fondamental estime par deconvolution des harmoniques de l'intervalle inter-mouvements
 * (`SPEC-v2.md` §5). C'est la **metrique de suivi** du produit : elle n'a pas de denominateur,
 * donc pas de circularite, et sa variabilite nuit a nuit publiee est douze fois moindre que
 * celle du compte horaire.
 *
 * @param fundamentalSec periode fondamentale `exp(mu)`, en secondes.
 * @param muLog moyenne du log de l'intervalle fondamental, en nats.
 * @param sigmaLog ecart-type du log.
 * @param missRate taux de manques estime. Sortie **aussi importante que la periode** : un taux
 *   qui saute d'une nuit a l'autre signale que les deux nuits ne sont pas comparables, et un taux
 *   proche de 0,5 avec un pic fondamental faible evoque une alternance gauche/droite.
 * @param harmonicWeights poids des composantes 1x, 2x, 3x… du melange.
 * @param alternationSuspect vrai si le profil des poids evoque une alternance entre les jambes.
 */
data class RhythmResult(
    val fundamentalSec: Double,
    val muLog: Double,
    val sigmaLog: Double,
    val missRate: Double,
    val harmonicWeights: DoubleArray,
    val alternationSuspect: Boolean,
    val intervalsUsed: Int,
    val converged: Boolean,
    val valid: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RhythmResult) return false
        return fundamentalSec == other.fundamentalSec && muLog == other.muLog &&
            sigmaLog == other.sigmaLog && missRate == other.missRate &&
            harmonicWeights.contentEquals(other.harmonicWeights) &&
            alternationSuspect == other.alternationSuspect && intervalsUsed == other.intervalsUsed &&
            converged == other.converged && valid == other.valid
    }

    override fun hashCode(): Int {
        var r = fundamentalSec.hashCode()
        r = 31 * r + muLog.hashCode()
        r = 31 * r + sigmaLog.hashCode()
        r = 31 * r + missRate.hashCode()
        r = 31 * r + harmonicWeights.contentHashCode()
        r = 31 * r + alternationSuspect.hashCode()
        r = 31 * r + intervalsUsed
        r = 31 * r + converged.hashCode()
        r = 31 * r + valid.hashCode()
        return r
    }
}

data class PlmiResult(
    val rule: SeriesRule,
    val maskSource: MaskSource,
    val plmsCount: Int,
    val plmwCount: Int,
    val isolatedCount: Int,
    val shortImiCount: Int,
    val tstMin: Double,
    val analysableTstMin: Double,
    val sptMin: Double,
    val wasoMin: Double,
    val plmi: Double,
    val plmiSpt: Double,
    val plmw: Double,
    val pi: PiResult,
    val rhythm: RhythmResult,
    val plmiFirstHalf: Double,
    val plmiSecondHalf: Double,
    val imiHistogram: IntArray,
    val imiBinEdgesSec: FloatArray,
    val truncatedSeriesDropped: Int,
    val plmiRespWorstCase: Double,
    val respiratoryConfidence: RespiratoryConfidence,
    val independence: DenominatorIndependence,
    val gate: PublicationGate,
    val floorMode: FloorMode,
    val paramsHash: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlmiResult) return false
        return rule == other.rule && maskSource == other.maskSource && plmsCount == other.plmsCount &&
            plmi == other.plmi && paramsHash == other.paramsHash &&
            imiHistogram.contentEquals(other.imiHistogram)
    }

    override fun hashCode(): Int {
        var r = rule.hashCode()
        r = 31 * r + maskSource.hashCode()
        r = 31 * r + plmsCount
        r = 31 * r + plmi.hashCode()
        r = 31 * r + paramsHash.hashCode()
        r = 31 * r + imiHistogram.contentHashCode()
        return r
    }
}

data class QualityReport(
    val analysableFraction: Double,
    val gapCount: Int,
    val gapTotalSec: Double,
    val longestGapSec: Double,
    val postureChanges: Int,
    val grossBodyMovements: Int,
    val medianFloorG: Float,
    val floorVsBaselineRatio: Double,
    val offBodyFraction: Double,
    val clockDriftSuspect: Boolean,
    val integrity: IntegrityReport,
    val truncatedNight: Boolean,
    val maskNonConvergent: Boolean,
    val warnings: List<String>,
)

data class NightAnalysis(
    val timeline: Timeline,
    val calibration: NightCalibration,
    val clms: List<Clm>,
    val postures: List<PostureChange>,
    val masks: Map<MaskSource, SleepMask>,
    val agreement: MaskAgreement?,
    /** Quatre lignes : 2 jeux de regles x 2 masques. */
    val results: List<PlmiResult>,
    val plmiLowerBound: Double,
    val plmiUpperBound: Double,
    val quality: QualityReport,
    val algoVersion: String,
)
