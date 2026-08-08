package com.pendulum.algo.synth

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.SeriesRule
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Resultat d'un appariement detection / verite terrain (`docs/fr/ALGO-v2.md` §5.4).
 *
 * @param onsetBiasMs biais moyen de datation, **signe** : positif si le detecteur date trop tard.
 *   L'enveloppe grossiere de 0,5 s introduit par construction un biais borne a ~0,25 s.
 * @param onsetSdMs ecart-type de l'erreur de datation.
 */
data class MatchResult(
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val sensitivity: Double,
    val precision: Double,
    val f1: Double,
    val onsetBiasMs: Double,
    val onsetSdMs: Double,
) {
    val truthCount: Int get() = tp + fn
    val detectedCount: Int get() = tp + fp
}

/** Une nuit analysee : ce qu'on a injecte, ce qu'on a detecte, et le plancher effectif du soir. */
class DetectionRun(
    val truth: List<TruthEvent>,
    val detected: List<Clm>,
    /** `Theta_on / k_on` median de la nuit. C'est la reference d'amplitude du detecteur. */
    val effectiveFloorG: Double,
)

/**
 * Appariement et metriques. `docs/fr/ALGO-v2.md` §5.4.
 *
 * L'appariement est **glouton et chronologique**, tolerance d'onset 1,0 s. Justification de la
 * tolerance, reprise telle quelle : la granularite clinique la plus fine est la borne basse de
 * l'IMI (5 s) et l'enveloppe grossiere de 0,5 s introduit un biais d'onset borne a ~0,25 s ; 1,0 s
 * est donc large devant le biais et etroit devant la regle.
 */
object Scoring {

    /** Bornes de bin par defaut de la courbe de sensibilite, en multiples du plancher effectif. */
    val DEFAULT_RATIO_BINS: DoubleArray =
        doubleArrayOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 24.0, 48.0, Double.MAX_VALUE)

    /**
     * @param detected evenements produits par le detecteur. Les rejetes et les LM longs sont
     *   ecartes ici : un evenement que la chaine ne compte pas ne peut etre ni un vrai positif ni un
     *   faux positif du **compte**.
     * @param truth **toujours** `accelTruth` (§5.3). Scorer contre `emgTruth` plafonnerait le F1
     *   vers 0,76 pour une raison qui n'est pas la faute de l'algorithme.
     */
    fun match(
        detected: List<Clm>,
        truth: List<TruthEvent>,
        toleranceSec: Double = 1.0,
    ): MatchResult {
        val det = detected.filter { it.isClm }.sortedBy { it.onsetMsRel }
        val tru = truth.sortedBy { it.onsetMsRel }
        val tolMs = Math.round(toleranceSec * 1000.0)
        val used = BooleanArray(det.size)

        var tp = 0
        var sum = 0.0
        var sumSq = 0.0
        var lo = 0
        for (t in tru) {
            // Fenetre glissante : les detections sont triees, donc `lo` n'avance que vers l'avant.
            while (lo < det.size && det[lo].onsetMsRel < t.onsetMsRel - tolMs) lo++
            var best = -1
            var bestDelta = Long.MAX_VALUE
            var j = lo
            while (j < det.size && det[j].onsetMsRel <= t.onsetMsRel + tolMs) {
                if (!used[j]) {
                    val d = abs(det[j].onsetMsRel - t.onsetMsRel)
                    if (d < bestDelta) { bestDelta = d; best = j }
                }
                j++
            }
            if (best >= 0) {
                used[best] = true
                tp++
                val delta = (det[best].onsetMsRel - t.onsetMsRel).toDouble()
                sum += delta
                sumSq += delta * delta
            }
        }

        val fn = tru.size - tp
        val fp = det.size - tp
        val se = if (tru.isEmpty()) Double.NaN else tp.toDouble() / tru.size
        val pr = if (det.isEmpty()) Double.NaN else tp.toDouble() / det.size
        val f1 = if (tp == 0) 0.0 else 2.0 * tp / (2.0 * tp + fp + fn)
        val bias = if (tp == 0) Double.NaN else sum / tp
        val sd = if (tp < 2) Double.NaN else {
            val v = (sumSq - sum * sum / tp) / (tp - 1)
            sqrt(v.coerceAtLeast(0.0))
        }
        return MatchResult(tp, fp, fn, se, pr, f1, bias, sd)
    }

    /**
     * Erreur **relative** de compte horaire, contre l'attendu du meme jeu de regles.
     *
     * Rappel de §5.3 : l'attendu est calcule sur `accelTruth`. Le rapport `emgToAccelRatio` de la
     * verite terrain reste a rapporter a cote — c'est lui qui dit de combien le compte publie est
     * structurellement sous l'echelle EMG a laquelle appartient le seuil de 15/h de l'ICSD-3.
     */
    fun plmiError(actual: PlmiResult, truth: GroundTruth): Double {
        val expected = when (actual.rule) {
            SeriesRule.AASM_V3 -> truth.expectedPlmiAasm
            SeriesRule.WASM_2016 -> truth.expectedPlmiWasm
        }
        if (!(expected > 0.0)) return Double.NaN
        return abs(actual.plmi - expected) / expected
    }

    /** Erreur **absolue** de Periodicity Index. Le PI est deja une fraction : pas de relatif. */
    fun piError(actual: PlmiResult, truth: GroundTruth): Double =
        abs(actual.pi.periodicityIndex - truth.expectedPi)

    /**
     * Courbe de sensibilite : `(rapport amplitude / plancher, Se)`.
     *
     * L'abscisse est le rapport entre la **crete d'enveloppe grossiere** de l'evenement injecte et
     * le **plancher effectif** du detecteur, `Theta_on / k_on`. C'est la seule definition sous
     * laquelle l'enonce de T5 (« 8x le plancher = le seuil, par construction ») est vrai : le seuil
     * de declenchement vaut `max(k_on.plancher, Theta_abs, f_cal.gainCal)`, et sur une nuit calme
     * c'est le plancher absolu qui l'emporte, pas le terme relatif. Rapporter l'amplitude au
     * plancher **brut** ferait dependre l'abscisse du terme dominant, donc de la nuit.
     *
     * [minCount] est le nombre minimal d'evenements pour qu'un bin soit **rapporte**. Il n'est pas
     * cosmetique : le balayage de T5 place tous ses evenements a exactement 4x, 8x et 16x, si bien
     * que les bins intermediaires ne sont peuples que par le **residu de calage** de l'amplitude —
     * mesure a 3 evenements sur 3 240 dans le bin [6 ; 8). Un taux estime sur 3 tirages n'est pas une
     * sensibilite, et le laisser sortir faisait echouer l'assertion de monotonie de T5 sur du bruit
     * d'echantillonnage. Le defaut de 1 conserve le comportement historique pour tout autre appelant.
     */
    fun sensitivityCurve(
        runs: List<DetectionRun>,
        binEdges: DoubleArray = DEFAULT_RATIO_BINS,
        toleranceSec: Double = 1.0,
        minCount: Int = 1,
    ): List<Pair<Double, Double>> {
        require(binEdges.size >= 2) { "il faut au moins un bin" }
        val matched = IntArray(binEdges.size - 1)
        val total = IntArray(binEdges.size - 1)
        val tolMs = Math.round(toleranceSec * 1000.0)

        for (run in runs) {
            val det = run.detected.filter { it.isClm }.sortedBy { it.onsetMsRel }
            val tru = run.truth.sortedBy { it.onsetMsRel }
            val used = BooleanArray(det.size)
            var lo = 0
            for (t in tru) {
                while (lo < det.size && det[lo].onsetMsRel < t.onsetMsRel - tolMs) lo++
                var best = -1
                var bestDelta = Long.MAX_VALUE
                var j = lo
                while (j < det.size && det[j].onsetMsRel <= t.onsetMsRel + tolMs) {
                    if (!used[j]) {
                        val d = abs(det[j].onsetMsRel - t.onsetMsRel)
                        if (d < bestDelta) { bestDelta = d; best = j }
                    }
                    j++
                }
                val ratio = if (run.effectiveFloorG > 0.0) t.envPeakG / run.effectiveFloorG else 0.0
                val bin = binOf(ratio, binEdges)
                total[bin]++
                if (best >= 0) { used[best] = true; matched[bin]++ }
            }
        }

        val out = ArrayList<Pair<Double, Double>>(matched.size)
        for (b in matched.indices) {
            if (total[b] < minCount.coerceAtLeast(1)) continue
            val center = if (binEdges[b + 1] == Double.MAX_VALUE) binEdges[b] else
                0.5 * (binEdges[b] + binEdges[b + 1])
            out.add(center to matched[b].toDouble() / total[b])
        }
        return out
    }

    private fun binOf(ratio: Double, edges: DoubleArray): Int {
        for (b in 0 until edges.size - 1) {
            if (ratio >= edges[b] && ratio < edges[b + 1]) return b
        }
        return edges.size - 2
    }
}
