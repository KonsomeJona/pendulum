package com.pendulum.algo.regression

import com.pendulum.algo.detect.ClmConfig
import com.pendulum.algo.detect.ThresholdConfig
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.synth.MatchResult
import com.pendulum.algo.synth.Scoring
import com.pendulum.algo.synth.SynthNight
import com.pendulum.algo.synth.TruthEvent
import com.pendulum.algo.synth.TruthKind
import java.util.Locale
import kotlin.math.abs
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
     * Balayage de `calFraction` sur [0,03 ; 0,12], 20 graines par valeur — **la mesure qui decide.**
     *
     * Le balayage de `k_on` a montre que ce n'est pas `k_on` qui commande le seuil sur une nuit
     * calibree, mais le troisieme terme `f_cal x gainCal`, dominant 99 % du temps. Toute la chaine de
     * consequences mesuree depuis part de la : `f_cal` -> `Theta_on` -> taux de manques 0,73-0,83 ->
     * 30 % de detections brutes -> 6 % d'indice par la regle des quatre consecutifs -> 11 % d'erreur
     * sur le rythme, et 2 ajustements valides sur 20. **Un seul parametre commande les deux metriques
     * publiees**, et il est le moins etaye du tableau §8.3 : « 12 % d'une dorsiflexion volontaire
     * confortable, choix d'ingenierie, aucun equivalent publie ».
     *
     * La plage encadre la mediane des evenements de `accelTruth`, 38,7 mg : a `gainCal ~ 447 mg`,
     * `f_cal = 0,09` place le seuil dessus et `f_cal = 0,08` dessous.
     *
     * **La question a laquelle ce tableau doit repondre**, et elle est chiffree : existe-t-il une
     * valeur ou le taux de manques passe sous **0,50** — la zone ou la courbe de rupture de
     * `RhythmMeasurementTest` montre que la deconvolution tient encore — **sans que la precision
     * s'effondre** ? Les douze familles de distracteurs sont toutes actives ; c'est leur raison
     * d'etre. Si la precision tombe, ce n'est pas une correction.
     *
     * Les trois criteres de T5 sont rapportes a chaque valeur. On s'attend a ce qu'ils ne bougent
     * pas — T5 tourne calibration desactivee, donc `f_cal` y est sans effet par construction — mais
     * l'attendre et le verifier sont deux choses differentes, et la verification est presque gratuite
     * dans un test deja `@Disabled`.
     */
    @Test
    @Disabled(
        "Mesure de decision, pas assertion. ~25 min. A relancer a la main avant toute discussion " +
            "sur la valeur de calFraction, qui est la seule qui deplace les deux metriques publiees.",
    )
    @DisplayName("Balayage de calFraction sur [0,03 ; 0,12] : taux de manques, rythme, et le prix en faux positifs")
    fun calFractionSweepAsksWhetherTheMissRateCanBeBroughtUnderFifty() {
        val rows = listOf(0.03, 0.04, 0.06, 0.08, 0.10, 0.12).map { CalRow(it) }

        for (seed in SEEDS) {
            val night = nominalNight(seed)
            for (row in rows) row.recordNominalNight(night, analyse(night, clmCfg = row.cfg))
        }

        val ratios = doubleArrayOf(4.0, 8.0, 16.0)
        for (row in rows) {
            for (seed in SEEDS) {
                val floor = probeEffectiveFloorG(seed, row.cfg)
                for ((k, ratio) in ratios.withIndex()) {
                    val night = fixedAmplitudeNight(seed, minutes = 20.0, envelopeAmplitudeG = ratio * floor)
                    val a = analyse(night, clmCfg = row.cfg, calibrated = false)
                    row.t5[k].add(Scoring.match(a.retained, night.truth.accelLegMovements).sensitivity)
                }
            }
        }

        val out = StringBuilder()
        out.append("\n=== Balayage de calFraction, ").append(SEEDS.size).append(" graines, medianes ===\n")
        out.append("sous = fraction de accelTruth sous Theta_on ; brut = CLM retenus / accelTruth ; ")
        out.append("p_vrai = manques sur le train EMG de serie ; p_est = manques rendus par la ")
        out.append("deconvolution ; idx_id = indice d'un detecteur parfait sous ce seuil / indice vrai ; ")
        out.append("idx_ms = indice mesure / indice vrai ; Pr et FP contre accelTruth entier ; ")
        out.append("err_ryt = erreur relative sur fundamentalSec ; valid = ajustements de rythme valides.\n\n")
        out.append(
            String.format(
                Locale.ROOT,
                "%6s %8s | %6s %6s | %6s %6s | %6s %6s | %6s %5s | %7s %6s | %6s %6s %6s %-4s%n",
                "f_cal", "Th_on", "sous", "brut", "p_vrai", "p_est", "idx_id", "idx_ms",
                "Pr", "FP", "err_ryt", "valid", "Se_4x", "Se_8x", "Se_16x", "T5",
            ),
        )
        for (row in rows) out.append(row.line())
        out.append("\nalternationSuspect leve : ")
        out.append(rows.joinToString(" ") { "${fmt(it.calFraction, 2)}:${it.alternations}" })
        out.append("\n")
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

/**
 * Une valeur de `calFraction` et tout ce qu'elle change. Colonnes differentes de [SweepRow] parce que
 * la question posee est differente : le balayage de `k_on` cherchait ou le F1 bouge, celui-ci cherche
 * ou le **taux de manques** passe sous le seuil d'identifiabilite de la deconvolution, et a quel prix
 * en faux positifs.
 */
private class CalRow(val calFraction: Double) {
    val cfg: ClmConfig = ClmConfig(thresholds = ThresholdConfig(calFraction = calFraction))

    val thOn = ArrayList<Double>()
    val subThreshold = ArrayList<Double>()
    val rawRatio = ArrayList<Double>()
    val missTrue = ArrayList<Double>()
    val missEstimated = ArrayList<Double>()
    val idxIdeal = ArrayList<Double>()
    val idxMeasured = ArrayList<Double>()
    val precision = ArrayList<Double>()
    val falsePositives = ArrayList<Double>()
    val rhythmErr = ArrayList<Double>()
    var validFits = 0
    var alternations = 0

    /** Les trois points de T5, dans l'ordre 4x, 8x, 16x le plancher effectif. */
    val t5 = List(3) { ArrayList<Double>() }

    fun recordNominalNight(night: SynthNight, a: Analysis) {
        val truth = night.truth.accelLegMovements
        val thresholdOn = a.thresholdOnG
        thOn.add(thresholdOn)
        subThreshold.add(truth.count { it.envPeakG < thresholdOn }.toDouble() / truth.size)
        rawRatio.add(a.retained.size.toDouble() / truth.size)

        // La precision et le compte de faux positifs se lisent contre `accelTruth` **entier** : c'est
        // la contrepartie attendue d'un seuil plus bas, et la restreindre au-dessus du seuil la
        // rendrait aveugle a ce qu'on cherche justement a surveiller.
        val m = Scoring.match(a.retained, truth)
        precision.add(m.precision)
        falsePositives.add(m.fp.toDouble())

        // Le taux de manques qui compte est celui du **train EMG**, parce que c'est l'abscisse de la
        // courbe de rupture de la deconvolution (`RhythmMeasurementTest`, docs §4.3).
        val emgSeries = night.truth.emgTruth.filter { it.kind == TruthKind.PLM_IN_SERIES }
        missTrue.add(1.0 - Scoring.match(a.retained, emgSeries).sensitivity)

        val full = a.truthResult(SeriesRule.AASM_V3).plmi
        idxIdeal.add(
            if (full > 0.0) a.truthResult(SeriesRule.AASM_V3, aboveEnvelope(truth, thresholdOn)).plmi / full
            else Double.NaN,
        )
        idxMeasured.add(if (full > 0.0) a.result(SeriesRule.AASM_V3).plmi / full else Double.NaN)

        val fit = a.rhythmFit()
        val trueFund = injectedFundamentalSec(night.truth)
        rhythmErr.add(
            if (trueFund > 0.0) abs(fit.result.fundamentalSec - trueFund) / trueFund else Double.NaN,
        )
        missEstimated.add(fit.result.missRate)
        if (fit.result.valid) validFits++
        if (fit.result.alternationSuspect) alternations++
    }

    fun t5Holds(): Boolean {
        val se8 = medianOf(t5[1])
        return medianOf(t5[0]) <= 0.05 && se8 >= 0.35 && se8 <= 0.65 && medianOf(t5[2]) >= 0.95
    }

    fun line(): String = String.format(
        Locale.ROOT,
        "%6s %8s | %6s %6s | %6s %6s | %6s %6s | %6s %5s | %7s %6s | %6s %6s %6s %-4s%n",
        fmt(calFraction, 2), mg(medianOf(thOn)),
        fmt(medianOf(subThreshold), 3), fmt(medianOf(rawRatio), 3),
        fmt(medianOf(missTrue), 3), fmt(medianOf(missEstimated), 3),
        fmt(medianOf(idxIdeal), 3), fmt(medianOf(idxMeasured), 3),
        fmt(medianOf(precision), 3), fmt(medianOf(falsePositives), 0),
        fmt(medianOf(rhythmErr), 3), "$validFits/${SEEDS.size}",
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
