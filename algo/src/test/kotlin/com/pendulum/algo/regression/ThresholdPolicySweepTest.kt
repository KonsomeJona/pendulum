package com.pendulum.algo.regression

import com.pendulum.algo.detect.ClmConfig
import com.pendulum.algo.detect.ThresholdConfig
import com.pendulum.algo.synth.MatchResult
import com.pendulum.algo.synth.Scoring
import com.pendulum.algo.synth.TruthEvent
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La « courbe de sensibilite parametrique » prevue par la phase P7 (`docs/01-overview.md` §5), et qui
 * manquait. Ce n'est **pas** une non-regression : rien n'y est assertionne, tout y est imprime.
 *
 * **Pourquoi une mesure plutot qu'une assertion.** §4.1 du document de validation laissait ouverte une
 * decision — restreindre ou non le denominateur de T6 aux evenements que le detecteur est configure
 * pour trouver — en notant qu'elle ne devait pas etre prise en silence. Une decision de ce genre se
 * prend avec le tableau sous les yeux, et le tableau n'existait pas : on savait ce que valait `k_on`
 * a 8,0 et rien d'autre. Ce fichier produit ce que la decision exigeait, a savoir, pour chaque valeur
 * de `k_on` entre 4 et 12 :
 *
 *  - le F1 de la nuit nominale sous les **trois** denominateurs de §4.1 (`accelTruth` entier,
 *    `accelTruth` au-dessus de `Theta_abs`, `accelTruth` au-dessus de `Theta_on`) ;
 *  - la fraction de `accelTruth` qui tombe sous `Theta_on`, c'est-a-dire le sous-comptage ;
 *  - **les trois criteres de T5**, refaits a chaque valeur de `k_on`. C'est le point non evident : T5
 *    exprime ses trois points de mesure en multiples du plancher **effectif** `Theta_on / k_on`, donc
 *    deplacer `k_on` deplace mecaniquement les trois abscisses. Un balayage qui ne mesurerait que le
 *    rappel de T6 conclurait qu'il suffit de baisser `k_on`, sans voir ce que cette baisse casse.
 *
 * Les deux tests sont `@Disabled` pour leur duree, pas parce qu'ils seraient fragiles. Ils sont a
 * relancer a la main des que `k_on`, `Theta_abs` ou le modele de mouvement du generateur changent, et
 * le tableau produit se recopie dans `docs/07-validation.md` §4.1.
 */
class ThresholdPolicySweepTest {

    /**
     * Balayage de `k_on` sur [4 ; 12], pas de 1,0, 20 graines par valeur.
     *
     * Lecture attendue du tableau : la colonne `F1_on` bouge peu — le detecteur fait bien son travail
     * sur les evenements au-dessus de son seuil, quel que soit ce seuil — tandis que `F1_all` et
     * `sous` bougent beaucoup. C'est la demonstration que ces deux dernieres colonnes mesurent la
     * politique de seuil et non la fidelite du detecteur.
     */
    @Test
    @Disabled(
        "Mesure de decision, pas assertion. ~25 min : 180 nuits de 8 h plus 540 nuits de balayage " +
            "T5. A relancer a la main quand k_on, Theta_abs ou le modele de mouvement changent.",
    )
    @DisplayName("Balayage de kOn sur [4 ; 12] : F1 sous trois denominateurs, et les trois criteres de T5")
    fun kOnSweepReportsBothTheRecallAndWhatItCosts() {
        val rows = (4..12).map { SweepRow(it.toDouble()) }
        val absFloorG = ClmConfig().thresholds.absFloorG.toDouble()

        // --- Volet T6 : la nuit nominale, tous distracteurs actifs.
        //
        // La nuit ne depend que de la graine, jamais de `k_on` : on la genere une fois et on
        // l'analyse neuf fois. A 8 h et 50 Hz la generation coute plus cher que la detection, et la
        // regenerer a chaque valeur ferait passer ce balayage de 25 minutes a plus d'une heure.
        for (seed in SEEDS) {
            val night = nominalNight(seed)
            val truth = night.truth.accelLegMovements
            for (row in rows) {
                val a = analyse(night, clmCfg = row.cfg)
                row.recordNominalNight(truth, a, absFloorG)
            }
        }

        // --- Volet T5 : les trois points de la courbe de sensibilite, refaits pour chaque `k_on`.
        val ratios = doubleArrayOf(4.0, 8.0, 16.0)
        for (row in rows) {
            for (seed in SEEDS) {
                val floor = probeEffectiveFloorG(seed, row.cfg)
                for ((k, ratio) in ratios.withIndex()) {
                    val night = fixedAmplitudeNight(seed, minutes = 20.0, envelopeAmplitudeG = ratio * floor)
                    // Calibration desactivee, comme dans T5 : le terme `f_cal x gainCal` deplacerait
                    // le seuil et l'abscisse ne serait plus celle que l'enonce decrit.
                    val a = analyse(night, clmCfg = row.cfg, calibrated = false)
                    val m = Scoring.match(a.retained, night.truth.accelLegMovements)
                    row.t5[k].add(m.sensitivity)
                }
            }
        }

        val out = StringBuilder()
        out.append("\n=== Balayage de k_on, ").append(SEEDS.size).append(" graines, medianes ===\n")
        out.append("Theta_abs = ").append(fmt(absFloorG * 1000.0, 1)).append(" mg fixe. ")
        out.append("Denominateurs : all = accelTruth entier ; abs = au-dessus de Theta_abs ; ")
        out.append("on = au-dessus de Theta_on. `sous` = fraction de accelTruth sous Theta_on.\n\n")
        out.append(
            String.format(
                Locale.ROOT,
                "%5s %8s | %6s %6s %6s %6s | %6s %6s | %6s %6s %6s %6s | %6s | %6s %6s %6s %-4s%n",
                "k_on", "Th_on", "n_all", "Se", "Pr", "F1", "n_abs", "F1", "n_on", "Se", "Pr", "F1",
                "sous", "Se_4x", "Se_8x", "Se_16x", "T5",
            ),
        )
        for (row in rows) out.append(row.line())
        out.append("\nT5 = les trois criteres tiennent ensemble : Se(4x) <= 0,05, ")
        out.append("Se(8x) dans [0,35 ; 0,65], Se(16x) >= 0,95.\n")
        println(out)
    }

    /**
     * Distribution complete des cretes d'enveloppe grossiere de `accelTruth` sur la nuit nominale.
     *
     * §4.1 n'en publiait que cinq quantiles mesures sur deux graines. Les memes cinq, sur les 20
     * graines et sur la population entiere, disent de combien la conclusion « la mediane des
     * evenements est a 0,70 x le seuil » depend du tirage.
     *
     * Aucune detection ici : `envPeakG` est porte par la verite terrain, donc seule la generation est
     * necessaire. C'est aussi ce qui rend cette mesure independante de `k_on` — elle decrit le signal,
     * pas la decision.
     */
    @Test
    @Disabled("Mesure de decision, pas assertion. ~2 min : 20 nuits de 8 h a generer.")
    @DisplayName("Distribution des cretes d'enveloppe de accelTruth sur la nuit nominale")
    fun accelTruthPeakAmplitudeDistribution() {
        val all = ArrayList<Double>()
        val perSeedMedian = ArrayList<Double>()
        for (seed in SEEDS) {
            val peaks = nominalNight(seed).truth.accelLegMovements.map { it.envPeakG.toDouble() }
            all += peaks
            perSeedMedian += medianOf(peaks)
        }

        val out = StringBuilder()
        out.append("\n=== Cretes d'enveloppe grossiere de accelTruth, nuit nominale ===\n")
        out.append(all.size).append(" evenements sur ").append(SEEDS.size).append(" graines, ")
        out.append("soit ").append(fmt(all.size.toDouble() / SEEDS.size, 1)).append(" par nuit.\n\n")
        out.append(String.format(Locale.ROOT, "%8s %8s %8s %8s %8s%n", "p10", "p25", "p50", "p75", "p90"))
        out.append(
            String.format(
                Locale.ROOT,
                "%7s %7s %7s %7s %7s%n",
                mg(percentileOf(all, 0.10)), mg(percentileOf(all, 0.25)), mg(percentileOf(all, 0.50)),
                mg(percentileOf(all, 0.75)), mg(percentileOf(all, 0.90)),
            ),
        )
        out.append("\nMediane par graine : de ").append(mg(worstMin(perSeedMedian)))
        out.append(" a ").append(mg(worstMax(perSeedMedian))).append(".\n")
        out.append("Fraction sous Theta_abs (20 mg) : ")
        out.append(fmt(all.count { it < 0.020 }.toDouble() / all.size, 3)).append("\n")
        println(out)
    }
}

// -------------------------------------------------------------------------------------------------
// Accumulateur d'une ligne du balayage
// -------------------------------------------------------------------------------------------------

/**
 * Une valeur de `k_on` et tout ce qu'on mesure sous cette valeur. Un objet par ligne du tableau
 * plutot que douze listes paralleles : c'est la seule structure ou l'on ne peut pas desaligner deux
 * colonnes en ajoutant une metrique.
 */
private class SweepRow(val kOn: Double) {
    val cfg: ClmConfig = ClmConfig(thresholds = ThresholdConfig(kOn = kOn))

    val thOn = ArrayList<Double>()
    val subThreshold = ArrayList<Double>()
    val all = Denominator()
    val abs = Denominator()
    val on = Denominator()

    /** Les trois points de T5, dans l'ordre 4x, 8x, 16x le plancher effectif. */
    val t5 = List(3) { ArrayList<Double>() }

    fun recordNominalNight(truth: List<TruthEvent>, a: Analysis, absFloorG: Double) {
        val thresholdOn = a.thresholdOnG
        thOn.add(thresholdOn)
        // Les trois denominateurs partagent la **meme** liste de detections : seule la verite change.
        // Un evenement detecte qui n'a plus de vis-a-vis dans la verite restreinte redevient un faux
        // positif, ce qui est bien ce qu'on veut mesurer — sinon on comparerait des precisions
        // calculees sur des populations de detections differentes.
        all.add(Scoring.match(a.retained, truth))
        abs.add(Scoring.match(a.retained, aboveEnvelope(truth, absFloorG)))
        on.add(Scoring.match(a.retained, aboveEnvelope(truth, thresholdOn)))
        subThreshold.add(
            if (truth.isEmpty()) Double.NaN
            else truth.count { it.envPeakG < thresholdOn }.toDouble() / truth.size,
        )
    }

    /** Les trois criteres de T5 tiennent-ils simultanement a cette valeur de `k_on` ? */
    fun t5Holds(): Boolean {
        val se4 = medianOf(t5[0])
        val se8 = medianOf(t5[1])
        val se16 = medianOf(t5[2])
        return se4 <= 0.05 && se8 >= 0.35 && se8 <= 0.65 && se16 >= 0.95
    }

    fun line(): String = String.format(
        Locale.ROOT,
        "%5s %8s | %6s %6s %6s %6s | %6s %6s | %6s %6s %6s %6s | %6s | %6s %6s %6s %-4s%n",
        fmt(kOn, 1), mg(medianOf(thOn)),
        fmt(medianOf(all.n), 0), fmt(medianOf(all.se), 3), fmt(medianOf(all.pr), 3), fmt(medianOf(all.f1), 3),
        fmt(medianOf(abs.n), 0), fmt(medianOf(abs.f1), 3),
        fmt(medianOf(on.n), 0), fmt(medianOf(on.se), 3), fmt(medianOf(on.pr), 3), fmt(medianOf(on.f1), 3),
        fmt(medianOf(subThreshold), 3),
        fmt(medianOf(t5[0]), 3), fmt(medianOf(t5[1]), 3), fmt(medianOf(t5[2]), 3),
        if (t5Holds()) "oui" else "NON",
    )
}

/** Les quatre grandeurs d'un appariement, accumulees sur les graines. */
private class Denominator {
    val n = ArrayList<Double>()
    val se = ArrayList<Double>()
    val pr = ArrayList<Double>()
    val f1 = ArrayList<Double>()

    fun add(m: MatchResult) {
        n.add(m.truthCount.toDouble())
        se.add(m.sensitivity)
        pr.add(m.precision)
        f1.add(m.f1)
    }
}

private fun fmt(v: Double, decimals: Int): String =
    if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.${decimals}f", v)

private fun mg(v: Double): String = if (v.isNaN()) "-" else fmt(v * 1000.0, 1) + "mg"

/**
 * Quantile par interpolation lineaire entre rangs (convention « type 7 », celle de R et de numpy).
 * Choisie parce que c'est la seule qui rende `percentileOf(x, 0.5)` identique a [medianOf], deja
 * utilise partout ailleurs dans la suite : deux definitions de la mediane dans le meme rapport
 * seraient une source d'ecart inexplicable.
 */
private fun percentileOf(values: List<Double>, p: Double): Double {
    val s = values.filter { !it.isNaN() }.sorted()
    if (s.isEmpty()) return Double.NaN
    val h = (s.size - 1) * p
    val lo = floor(h).toInt()
    val hi = ceil(h).toInt()
    return s[lo] + (h - lo) * (s[hi] - s[lo])
}
