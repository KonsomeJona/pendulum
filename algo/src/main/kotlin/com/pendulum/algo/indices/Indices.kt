package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.PublicationGate
import com.pendulum.algo.model.RespiratoryConfidence
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Étape 7 de `ALGO-v2.md` : le compte horaire et ses compagnons.
 *
 * Trois règles structurent tout ce fichier, et aucune n'est négociable :
 *
 *  1. **Le dénominateur est le temps de sommeil *analysable*, jamais le TST brut.** Le TST brut
 *     inclut les zones aveugles, les segments rompus et le hors-corps : compter des mouvements
 *     sur une durée pendant laquelle on n'aurait pas pu en voir gonfle le dénominateur et déflate
 *     l'indice, exactement dans le sens qui fait rater un dépistage (§2.4).
 *  2. **`DenominatorIndependence` se propage sans être « amélioré ».** Un résultat dont le
 *     dénominateur est [DenominatorIndependence.CIRCULAR] sort du même signal que son numérateur
 *     (§3.6.3) : il est calculé, stocké, affiché comme second bras, mais il ne peut jamais porter
 *     le résultat principal ni alimenter la tendance. C'est le code qui l'interdit, pas l'UI.
 *  3. **Le biais respiratoire résiduel est *quantifié*, pas ignoré.** Sans canal respiratoire,
 *     aucune des deux règles d'exclusion RRLM publiées n'est calculable (§3.5). On ne prétend donc
 *     pas exclure : on publie un encadrement `[plmiRespWorstCase, plmi]` dont l'écart *est*
 *     l'indicateur d'incertitude. Le biais RRLM est à la hausse ; les biais de Terrill (39 % des
 *     LM EMG mécaniquement invisibles) et de mesure unilatérale sont à la baisse. **Ils ne se
 *     compensent pas** : sujets et mécanismes différents, variances qui s'additionnent.
 *
 * Toutes les fonctions sont pures : aucune horloge, aucun aléa, aucune I/O.
 */

/** Paramètres de l'étape 7. Valeurs par défaut = tableau §6.5 / §6.6 de `ALGO-v2.md`. */
data class PlmiConfig(
    /** Bande apnéique basse : un IMI médian de série dans [low, high] est *suspect*, pas exclu. */
    val respSuspectImiLowSec: Double = 25.0,
    val respSuspectImiHighSec: Double = 45.0,
    val imiHistogramBinSec: Double = 2.0,
    val imiHistogramMaxSec: Double = 100.0,
    /** Porte de publication pleine (§3.7.2). */
    val minTstFullMin: Double = 240.0,
    /** Sous cette valeur, aucun PLMI n'est publié — le PI et le rythme, eux, survivent. */
    val minTstAnyMin: Double = 180.0,
)

// --- Helpers numériques locaux -------------------------------------------------------------
//
// Volontairement minimaux et privés au paquet : `dsp.Numeric` porte les primitives lourdes
// (fenêtres glissantes, MAD sur signaux), mais l'étape 7 ne manipule que quelques dizaines de
// valeurs. Dupliquer trois lignes de médiane coûte moins cher qu'un couplage sur une API qui
// bouge encore, et garde `indices/` compilable isolément.

/** Médiane d'une copie triée. Convention : moyenne des deux centraux si `n` est pair. */
internal fun medianOf(values: DoubleArray): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.copyOf()
    s.sort()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
}

/**
 * Quantile de type « plus proche rang interpolé » sur une copie triée. Déterministe.
 * `q` est écrêté à [0, 1].
 */
internal fun quantileOf(values: DoubleArray, q: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.copyOf()
    s.sort()
    val pos = (q.coerceIn(0.0, 1.0)) * (s.size - 1)
    val lo = kotlin.math.floor(pos).toInt()
    val hi = kotlin.math.ceil(pos).toInt()
    if (lo == hi) return s[lo]
    val f = pos - lo
    return s[lo] * (1.0 - f) + s[hi] * f
}

/** Écart absolu médian, mis à l'échelle d'un écart-type gaussien (facteur 1,4826). */
internal fun madOf(values: DoubleArray): Double {
    if (values.isEmpty()) return Double.NaN
    val med = medianOf(values)
    val dev = DoubleArray(values.size) { kotlin.math.abs(values[it] - med) }
    return 1.4826 * medianOf(dev)
}

/** Un taux n'existe que si son dénominateur existe. `NaN` dit « pas de valeur », pas « zéro ». */
internal fun safeRate(count: Int, hours: Double): Double =
    if (hours > 0.0 && hours.isFinite()) count / hours else Double.NaN

internal fun Stage?.isSleepStage(): Boolean =
    this == Stage.SLEEP || this == Stage.LIGHT || this == Stage.DEEP || this == Stage.REM

/**
 * Éveil *intra-SPT*. [Stage.OUT_OF_BED] en est exclu : le PLMW de la WASM se rapporte au WASO,
 * pas au temps passé debout.
 */
internal fun Stage?.isWakeInBedStage(): Boolean =
    this == Stage.WAKE || this == Stage.AWAKE_IN_BED

/**
 * Index de fenêtres de sommeil, recherche dichotomique. Suppose des fenêtres disjointes ;
 * en cas de recouvrement, la dernière fenêtre commençant avant l'instant gagne.
 */
internal class SleepLookup(windows: List<SleepWindow>) {
    private val starts: LongArray
    private val ends: LongArray
    private val stages: Array<Stage>

    init {
        val sorted = windows.sortedBy { it.startMsRel }
        starts = LongArray(sorted.size) { sorted[it].startMsRel }
        ends = LongArray(sorted.size) { sorted[it].endMsRel }
        stages = Array(sorted.size) { sorted[it].stage }
    }

    /** Bornes du SPT = enveloppe des fenêtres « au lit » (tout sauf [Stage.OUT_OF_BED]). */
    val sptStartMsRel: Long
    val sptEndMsRel: Long

    init {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (i in starts.indices) {
            if (stages[i] == Stage.OUT_OF_BED) continue
            lo = min(lo, starts[i])
            hi = max(hi, ends[i])
        }
        sptStartMsRel = if (lo == Long.MAX_VALUE) 0L else lo
        sptEndMsRel = if (hi == Long.MIN_VALUE) 0L else hi
    }

    fun stageAt(msRel: Long): Stage? {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= msRel) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        return if (msRel < ends[found]) stages[found] else null
    }

    fun isSleepAt(msRel: Long): Boolean = stageAt(msRel).isSleepStage()

    fun isWakeInBedAt(msRel: Long): Boolean = stageAt(msRel).isWakeInBedStage()

    /** Millisecondes de *sommeil* (au sens de [isSleepStage]) recouvrant `[from, to)`. */
    fun sleepMsBetween(from: Long, to: Long): Long {
        if (to <= from) return 0L
        var acc = 0L
        for (i in starts.indices) {
            if (!stages[i].isSleepStage()) continue
            val a = max(starts[i], from)
            val b = min(ends[i], to)
            if (b > a) acc += b - a
        }
        return acc
    }
}

/**
 * Indices horaires, encadrement respiratoire et porte de publication.
 *
 * L'objet s'appelle `Plmi` pour rester aligné sur `ALGO-v2.md` §4.3, mais **le nom publié de la
 * grandeur est `aPLM-i`** (garde-fou nº 6 de `SPEC-v2.md` §3) : appeler « PLMI » un chiffre produit
 * par un bracelet de montre sur une cheville garantit qu'il sera lu comme un PLMI de laboratoire.
 */
object Plmi {

    /** Borne basse d'IMI de chaque jeu de règles (§6.5). Sert au comptage de `shortImiCount`. */
    fun imiMinSecOf(rule: SeriesRule): Double = when (rule) {
        SeriesRule.AASM_V3 -> 5.0
        SeriesRule.WASM_2016 -> 10.0
    }

    /**
     * Un dénominateur circulaire ne peut jamais porter le résultat principal (§2.3, §3.6.3).
     * Prédicat exposé séparément parce qu'il sert aussi au DAO et à la tendance, pas seulement ici.
     */
    fun canCarryPrimaryResult(mask: SleepMask): Boolean =
        mask.independence != DenominatorIndependence.CIRCULAR

    /**
     * Ce que l'analyse **a le droit** de publier. Évalué par le code, jamais par l'utilisateur, et
     * jamais contournable depuis l'interface.
     *
     * Ordre des refus, du plus dur au plus doux :
     *  - masque accélérométrique non convergent → aucun PLMI (§3.6.3, couche 2 : une nuit où
     *    mouvement et immobilité ne se séparent pas ne produit pas de chiffre publiable) ;
     *  - `RespiratoryConfidence.LOW` → aucun PLMI (§3.5 : « non interprétable sans polygraphie
     *    respiratoire ». Un chiffre faux affiché est pire que pas de chiffre) ;
     *  - moins de [PlmiConfig.minTstAnyMin] de sommeil analysable → aucun PLMI (§3.7.2) ;
     *  - nuit tronquée, ou moins de [PlmiConfig.minTstFullMin] → publié mais **hors tendance**
     *    (le PLMI d'une nuit tronquée est biaisé à la hausse de façon non corrigeable) ;
     *  - dénominateur circulaire → publié mais **hors tendance**.
     *
     * Le PI et le rythme fondamental, eux, survivent à `NO_PLMI` : ils n'ont pas de dénominateur
     * temporel. C'est tout l'intérêt du §5 de `SPEC-v2.md`.
     */
    fun publicationGate(
        mask: SleepMask,
        truncated: Boolean,
        respiratory: RespiratoryConfidence,
        cfg: PlmiConfig = PlmiConfig(),
    ): PublicationGate {
        // Le point fixe n'existe que pour un masque dérivé de l'accéléromètre ; un masque HC ou
        // journal n'a rien à faire converger et doit rapporter `fixedPointConverged = true`.
        val hasFixedPoint = mask.source == MaskSource.ACCEL_IMMOBILITY || mask.source == MaskSource.FUSED
        if (hasFixedPoint && !mask.fixedPointConverged) return PublicationGate.NO_PLMI
        if (respiratory == RespiratoryConfidence.LOW) return PublicationGate.NO_PLMI
        if (!(mask.analysableTstMin >= cfg.minTstAnyMin)) return PublicationGate.NO_PLMI
        if (truncated) return PublicationGate.TRUNCATED_NO_TREND
        if (mask.analysableTstMin < cfg.minTstFullMin) return PublicationGate.TRUNCATED_NO_TREND
        if (!canCarryPrimaryResult(mask)) return PublicationGate.TRUNCATED_NO_TREND
        return PublicationGate.FULL
    }

    /**
     * Assemble un [PlmiResult].
     *
     * @param clms tous les CLM candidats, **dans l'ordre chronologique**. Les rejetés
     *   (`reject != null`) et les LM longs sont écartés ici ; `PlmSeries.clmIndices` indexe la liste
     *   des **retenus** (`Clm.isClm`), conformément à `Model.kt`.
     * @param series séries déjà construites pour `rule` (étape 6).
     * @param fsHz conservé pour la stabilité de l'API §4.3 ; les instants viennent de
     *   `Clm.onsetMsRel`, qui est la référence — jamais une différence d'horloge murale (§2.7).
     * @param pi résultat du Periodicity Index (voir `Periodicity.kt`).
     * @param rhythm résultat de la déconvolution des harmoniques (voir `Rhythm.kt`).
     * @param truncatedSeriesDropped séries abandonnées faute de 4 CLM après troncature aux bords,
     *   rapporté par l'étape 6. C'est un biais **à la baisse** mesurable, qui augmente sur une nuit
     *   interrompue et qu'il faut afficher à côté du chiffre (§3.7.2 point 5).
     */
    fun compute(
        clms: List<Clm>,
        series: List<PlmSeries>,
        mask: SleepMask,
        fsHz: Double,
        rule: SeriesRule,
        respiratory: RespiratoryConfidence,
        pi: PiResult,
        rhythm: RhythmResult,
        floorMode: FloorMode,
        truncated: Boolean,
        cfg: PlmiConfig = PlmiConfig(),
        truncatedSeriesDropped: Int = 0,
        paramsHash: String = "",
    ): PlmiResult {
        require(fsHz > 0.0) { "fsHz doit etre > 0" }

        val retained = clms.filter { it.isClm }
        val lookup = SleepLookup(mask.windows)

        // --- Appartenance aux séries -----------------------------------------------------
        val inSeries = BooleanArray(retained.size)
        // Marquage des CLM appartenant a une serie dont l'IMI median tombe dans la bande apneique.
        val respSuspect = BooleanArray(retained.size)
        for (s in series) {
            val medianImi = medianOf(DoubleArray(s.imiSec.size) { s.imiSec[it].toDouble() })
            val suspect = medianImi.isFinite() &&
                medianImi >= cfg.respSuspectImiLowSec && medianImi <= cfg.respSuspectImiHighSec
            for (idx in s.clmIndices) {
                if (idx < 0 || idx >= retained.size) continue
                inSeries[idx] = true
                if (suspect) respSuspect[idx] = true
            }
        }

        // --- Comptes ---------------------------------------------------------------------
        var plmsCount = 0
        var plmwCount = 0
        var isolatedCount = 0
        var plmsCountRespWorst = 0
        var plmsFirstHalf = 0
        var plmsSecondHalf = 0

        val sptStart = lookup.sptStartMsRel
        val sptEnd = lookup.sptEndMsRel
        val midMs = sptStart + (sptEnd - sptStart) / 2

        for (i in retained.indices) {
            val onset = retained[i].onsetMsRel
            val sleeping = lookup.isSleepAt(onset)
            if (!inSeries[i]) {
                isolatedCount++
                continue
            }
            if (sleeping) {
                plmsCount++
                // Pire cas respiratoire : on retire TOUTE serie dont l'IMI median est apneique.
                // Ce n'est pas une exclusion RRLM (impossible sans canal respiratoire) : c'est une
                // borne inferieure garantie. La vraie valeur est entre les deux bornes.
                if (!respSuspect[i]) plmsCountRespWorst++
                if (onset < midMs) plmsFirstHalf++ else plmsSecondHalf++
            } else if (lookup.isWakeInBedAt(onset)) {
                // PLMW : metrique WASM, non definie par l'AASM. Seule la WASM autorise d'ailleurs
                // une serie a traverser une transition sommeil/eveil (2.4.4).
                plmwCount++
            }
        }

        // Intervalles courts : compte sur les CLM retenus consecutifs, borne basse du jeu de regles.
        val imiMinSec = imiMinSecOf(rule)
        var shortImiCount = 0
        for (i in 1 until retained.size) {
            val imiSec = (retained[i].onsetMsRel - retained[i - 1].onsetMsRel) / 1000.0
            if (imiSec < imiMinSec) shortImiCount++
        }

        // --- Denominateurs ---------------------------------------------------------------
        // JAMAIS le TST brut : `analysableTstMin` = TST ∩ segments valides ∩ hors zones aveugles
        // ∩ hors off-body ∩ hors warmup.
        val analysableTstH = mask.analysableTstMin / 60.0
        val analysableSptH = mask.analysableSptMin / 60.0
        // Le masque ne porte pas de « WASO analysable » : on repartit au prorata de la couverture
        // du SPT. Approximation assumee et documentee — elle ne touche que le PLMW, jamais le PLMI.
        val analysableSptRatio = if (mask.sptMin > 0.0) mask.analysableSptMin / mask.sptMin else 0.0
        val analysableWasoH = mask.wasoMin * analysableSptRatio / 60.0

        val plmi = safeRate(plmsCount, analysableTstH)
        val plmiSpt = safeRate(plmsCount, analysableSptH)
        val plmw = safeRate(plmwCount, analysableWasoH)
        val plmiRespWorstCase = safeRate(plmsCountRespWorst, analysableTstH)

        // Split-half : sur une nuit tronquee, ce ratio est le meilleur indicateur de l'ampleur du
        // biais a la hausse (les PLMS se concentrent en premiere moitie de nuit, §3.7.2 point 2).
        val analysableTstRatio = if (mask.tstMin > 0.0) mask.analysableTstMin / mask.tstMin else 0.0
        val firstHalfH = lookup.sleepMsBetween(sptStart, midMs) / 3_600_000.0 * analysableTstRatio
        val secondHalfH = lookup.sleepMsBetween(midMs, sptEnd) / 3_600_000.0 * analysableTstRatio

        // --- Histogramme des IMI ---------------------------------------------------------
        val binCount = max(1, (cfg.imiHistogramMaxSec / cfg.imiHistogramBinSec).roundToInt())
        val histogram = IntArray(binCount)
        val edges = FloatArray(binCount + 1) { (it * cfg.imiHistogramBinSec).toFloat() }
        // Sur TOUS les CLM de sommeil consecutifs, pas seulement ceux en serie : la bimodalite
        // 2-4 s / 22-26 s est l'information diagnostique brute, et le mode court disparait si l'on
        // ne garde que les intervalles deja filtres par la construction de series.
        var prevSleepOnset = Long.MIN_VALUE
        for (c in retained) {
            if (!lookup.isSleepAt(c.onsetMsRel)) continue
            if (prevSleepOnset != Long.MIN_VALUE) {
                val imiSec = (c.onsetMsRel - prevSleepOnset) / 1000.0
                val bin = kotlin.math.floor(imiSec / cfg.imiHistogramBinSec).toInt()
                if (bin in 0 until binCount) histogram[bin]++
            }
            prevSleepOnset = c.onsetMsRel
        }

        return PlmiResult(
            rule = rule,
            maskSource = mask.source,
            plmsCount = plmsCount,
            plmwCount = plmwCount,
            isolatedCount = isolatedCount,
            shortImiCount = shortImiCount,
            tstMin = mask.tstMin,
            analysableTstMin = mask.analysableTstMin,
            sptMin = mask.sptMin,
            wasoMin = mask.wasoMin,
            plmi = plmi,
            plmiSpt = plmiSpt,
            plmw = plmw,
            pi = pi,
            rhythm = rhythm,
            plmiFirstHalf = safeRate(plmsFirstHalf, firstHalfH),
            plmiSecondHalf = safeRate(plmsSecondHalf, secondHalfH),
            imiHistogram = histogram,
            imiBinEdgesSec = edges,
            truncatedSeriesDropped = truncatedSeriesDropped,
            plmiRespWorstCase = plmiRespWorstCase,
            respiratoryConfidence = respiratory,
            independence = mask.independence,
            gate = publicationGate(mask, truncated, respiratory, cfg),
            floorMode = floorMode,
            paramsHash = paramsHash,
        )
    }

    /**
     * Largeur de l'encadrement respiratoire, en événements/h. **C'est cet écart qui est
     * l'indicateur d'incertitude à afficher**, pas la borne basse seule : sans canal respiratoire,
     * la vraie valeur est quelque part dans `[plmiRespWorstCase, plmi]` et rien ne permet de la
     * situer dans l'intervalle. Deux définitions officielles de la fenêtre d'exclusion diffèrent
     * déjà d'un facteur 1,8 entre elles à canal disponible (§3.5).
     */
    fun respiratoryBiasSpread(r: PlmiResult): Double = r.plmi - r.plmiRespWorstCase
}
