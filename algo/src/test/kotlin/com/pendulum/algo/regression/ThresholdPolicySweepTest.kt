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
 * The "parametric sensitivity curve" planned by phase P7 (`docs/01-overview.md` §5), and which was
 * missing. This is **not** a non-regression test: nothing is asserted here, everything is printed.
 *
 * **Why a measurement rather than an assertion.** §4.1 of the validation document left a decision
 * open — whether or not to restrict T6's denominator to the events the detector is configured to
 * find — while noting that it must not be taken in silence. A decision of that kind is taken with
 * the table in front of you, and the table did not exist: what `k_on` was worth at 8.0 was known and
 * nothing else. This file produces what the decision required, namely, for every value of `k_on`
 * between 4 and 12:
 *
 *  - the F1 of the nominal night under the **three** denominators of §4.1 (whole `accelTruth`,
 *    `accelTruth` above `Theta_abs`, `accelTruth` above `Theta_on`);
 *  - the fraction of `accelTruth` that falls below `Theta_on`, that is, the under-counting;
 *  - **the three criteria of T5**, redone at every value of `k_on`. This is the non-obvious point:
 *    T5 expresses its three measurement points in multiples of the **effective** floor
 *    `Theta_on / k_on`, so moving `k_on` mechanically moves the three abscissas. A sweep that
 *    measured only T6's recall would conclude that it is enough to lower `k_on`, without seeing what
 *    that lowering breaks.
 *
 * Both tests are `@Disabled` for their duration, not because they would be fragile. They are to be
 * rerun by hand as soon as `k_on`, `Theta_abs` or the generator's movement model change, and the
 * table produced is copied into `docs/07-validation.md` §4.1.
 */
class ThresholdPolicySweepTest {

    /**
     * Sweep of `k_on` over [4 ; 12], step 1.0, 20 seeds per value.
     *
     * Expected reading of the table: the `F1_on` column moves little — the detector does its job
     * well on the events above its threshold, whatever that threshold is — while `F1_all` and
     * `under` move a lot. That is the demonstration that these last two columns measure the
     * threshold policy and not the detector's fidelity.
     */
    @Test
    @Disabled(
        "Decision measurement, not an assertion. ~25 min: 180 nights of 8 h plus 540 nights of T5 " +
            "sweep. To be rerun by hand when k_on, Theta_abs or the movement model change.",
    )
    @DisplayName("Sweep of kOn over [4 ; 12]: F1 under three denominators, and the three criteria of T5")
    fun kOnSweepReportsBothTheRecallAndWhatItCosts() {
        val rows = (4..12).map { SweepRow(it.toDouble()) }
        val absFloorG = ClmConfig().thresholds.absFloorG.toDouble()

        // --- T6 part: the nominal night, every distractor active.
        //
        // The night depends only on the seed, never on `k_on`: it is generated once and analysed
        // nine times. At 8 h and 50 Hz the generation costs more than the detection, and
        // regenerating it at every value would take this sweep from 25 minutes to over an hour.
        for (seed in SEEDS) {
            val night = nominalNight(seed)
            val truth = night.truth.accelLegMovements
            for (row in rows) {
                val a = analyse(night, clmCfg = row.cfg)
                row.recordNominalNight(truth, a, absFloorG)
            }
        }

        // --- T5 part: the three points of the sensitivity curve, redone for each `k_on`.
        val ratios = doubleArrayOf(4.0, 8.0, 16.0)
        for (row in rows) {
            for (seed in SEEDS) {
                val floor = probeEffectiveFloorG(seed, row.cfg)
                for ((k, ratio) in ratios.withIndex()) {
                    val night = fixedAmplitudeNight(seed, minutes = 20.0, envelopeAmplitudeG = ratio * floor)
                    // Calibration disabled, as in T5: the `f_cal x gainCal` term would shift the
                    // threshold and the abscissa would no longer be the one the statement describes.
                    val a = analyse(night, clmCfg = row.cfg, calibrated = false)
                    val m = Scoring.match(a.retained, night.truth.accelLegMovements)
                    row.t5[k].add(m.sensitivity)
                }
            }
        }

        val out = StringBuilder()
        out.append("\n=== Sweep of k_on, ").append(SEEDS.size).append(" seeds, medians ===\n")
        out.append("Theta_abs = ").append(fmt(absFloorG * 1000.0, 1)).append(" mg fixed. ")
        out.append("Denominators: all = whole accelTruth ; abs = above Theta_abs ; ")
        out.append("on = above Theta_on. `under` = fraction of accelTruth below Theta_on.\n\n")
        out.append(
            String.format(
                Locale.ROOT,
                "%5s %8s | %6s %6s %6s %6s | %6s %6s | %6s %6s %6s %6s | %6s | %6s %6s %6s %-4s%n",
                "k_on", "Th_on", "n_all", "Se", "Pr", "F1", "n_abs", "F1", "n_on", "Se", "Pr", "F1",
                "under", "Se_4x", "Se_8x", "Se_16x", "T5",
            ),
        )
        for (row in rows) out.append(row.line())
        out.append("\nT5 = the three criteria hold together: Se(4x) <= 0.05, ")
        out.append("Se(8x) in [0.35 ; 0.65], Se(16x) >= 0.95.\n")
        println(out)
    }

    /**
     * Sweep of `calFraction` over [0.03 ; 0.12], 20 seeds per value — **the measurement that
     * decides.**
     *
     * The `k_on` sweep showed that it is not `k_on` that commands the threshold on a calibrated
     * night, but the third term `f_cal x gainCal`, dominant 99 % of the time. The whole chain of
     * consequences measured since starts there: `f_cal` -> `Theta_on` -> miss rate 0.73-0.83 ->
     * 30 % of raw detections -> 6 % of index through the rule of four consecutive ones -> 11 % of
     * error on the rhythm, and 2 valid fits out of 20. **A single parameter commands both published
     * metrics**, and it is the least supported of the §8.3 table: "12 % of a comfortable voluntary
     * dorsiflexion, an engineering choice, no published equivalent".
     *
     * The range brackets the median of the `accelTruth` events, 38.7 mg: at `gainCal ~ 447 mg`,
     * `f_cal = 0.09` places the threshold above it and `f_cal = 0.08` below it.
     *
     * **The question this table must answer**, and it carries a figure: is there a value where the
     * miss rate goes below **0.50** — the zone where the breakdown curve of `RhythmMeasurementTest`
     * shows that the deconvolution still holds — **without precision collapsing**? The twelve
     * families of distractors are all active; that is their reason for being. If precision falls,
     * this is not a fix.
     *
     * The three criteria of T5 are reported at every value. They are expected not to move — T5 runs
     * with calibration disabled, so `f_cal` has no effect there by construction — but expecting it
     * and checking it are two different things, and the check is almost free in a test that is
     * already `@Disabled`.
     */
    @Test
    @Disabled(
        "Decision measurement, not an assertion. ~25 min. To be rerun by hand before any discussion " +
            "of the value of calFraction, the only one that moves both published metrics.",
    )
    @DisplayName("Sweep of calFraction over [0.03 ; 0.12]: miss rate, rhythm, and the price in false positives")
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
        out.append("\n=== Sweep of calFraction, ").append(SEEDS.size).append(" seeds, medians ===\n")
        out.append("under = fraction of accelTruth below Theta_on ; raw = retained CLM / accelTruth ; ")
        out.append("p_true = misses on the EMG series train ; p_est = misses returned by the ")
        out.append("deconvolution ; idx_id = index of a perfect detector under this threshold / true index ; ")
        out.append("idx_ms = measured index / true index ; Pr and FP against the whole accelTruth ; ")
        out.append("err_rhy = relative error on fundamentalSec ; valid = valid rhythm fits.\n\n")
        out.append(
            String.format(
                Locale.ROOT,
                "%6s %8s | %6s %6s | %6s %6s | %6s %6s | %6s %5s | %7s %6s | %6s %6s %6s %-4s%n",
                "f_cal", "Th_on", "under", "raw", "p_true", "p_est", "idx_id", "idx_ms",
                "Pr", "FP", "err_rhy", "valid", "Se_4x", "Se_8x", "Se_16x", "T5",
            ),
        )
        for (row in rows) out.append(row.line())
        out.append("\nalternationSuspect raised: ")
        out.append(rows.joinToString(" ") { "${fmt(it.calFraction, 2)}:${it.alternations}" })
        out.append("\n")
        println(out)
    }

    /**
     * Full distribution of the coarse-envelope peaks of `accelTruth` on the nominal night.
     *
     * §4.1 published only five quantiles of it, measured on two seeds. The same five, over the 20
     * seeds and over the whole population, say how much the conclusion "the median of the events is
     * at 0.70 x the threshold" depends on the draw.
     *
     * No detection here: `envPeakG` is carried by the ground truth, so only the generation is
     * needed. That is also what makes this measurement independent of `k_on` — it describes the
     * signal, not the decision.
     */
    @Test
    @Disabled("Decision measurement, not an assertion. ~2 min: 20 nights of 8 h to generate.")
    @DisplayName("Distribution of the envelope peaks of accelTruth on the nominal night")
    fun accelTruthPeakAmplitudeDistribution() {
        val all = ArrayList<Double>()
        val perSeedMedian = ArrayList<Double>()
        for (seed in SEEDS) {
            val peaks = nominalNight(seed).truth.accelLegMovements.map { it.envPeakG.toDouble() }
            all += peaks
            perSeedMedian += medianOf(peaks)
        }

        val out = StringBuilder()
        out.append("\n=== Coarse-envelope peaks of accelTruth, nominal night ===\n")
        out.append(all.size).append(" events over ").append(SEEDS.size).append(" seeds, ")
        out.append("that is ").append(fmt(all.size.toDouble() / SEEDS.size, 1)).append(" per night.\n\n")
        out.append(String.format(Locale.ROOT, "%8s %8s %8s %8s %8s%n", "p10", "p25", "p50", "p75", "p90"))
        out.append(
            String.format(
                Locale.ROOT,
                "%7s %7s %7s %7s %7s%n",
                mg(percentileOf(all, 0.10)), mg(percentileOf(all, 0.25)), mg(percentileOf(all, 0.50)),
                mg(percentileOf(all, 0.75)), mg(percentileOf(all, 0.90)),
            ),
        )
        out.append("\nMedian per seed: from ").append(mg(worstMin(perSeedMedian)))
        out.append(" to ").append(mg(worstMax(perSeedMedian))).append(".\n")
        out.append("Fraction below Theta_abs (20 mg): ")
        out.append(fmt(all.count { it < 0.020 }.toDouble() / all.size, 3)).append("\n")
        println(out)
    }
}

// -------------------------------------------------------------------------------------------------
// Accumulator for one row of the sweep
// -------------------------------------------------------------------------------------------------

/**
 * One value of `k_on` and everything measured under that value. One object per table row rather than
 * twelve parallel lists: it is the only structure in which two columns cannot be misaligned by
 * adding a metric.
 */
private class SweepRow(val kOn: Double) {
    val cfg: ClmConfig = ClmConfig(thresholds = ThresholdConfig(kOn = kOn))

    val thOn = ArrayList<Double>()
    val subThreshold = ArrayList<Double>()
    val all = Denominator()
    val abs = Denominator()
    val on = Denominator()

    /** The three points of T5, in the order 4x, 8x, 16x the effective floor. */
    val t5 = List(3) { ArrayList<Double>() }

    fun recordNominalNight(truth: List<TruthEvent>, a: Analysis, absFloorG: Double) {
        val thresholdOn = a.thresholdOnG
        thOn.add(thresholdOn)
        // The three denominators share the **same** list of detections: only the truth changes. A
        // detected event that no longer has a counterpart in the restricted truth goes back to being
        // a false positive, which is exactly what is meant to be measured — otherwise precisions
        // computed on different populations of detections would be compared.
        all.add(Scoring.match(a.retained, truth))
        abs.add(Scoring.match(a.retained, aboveEnvelope(truth, absFloorG)))
        on.add(Scoring.match(a.retained, aboveEnvelope(truth, thresholdOn)))
        subThreshold.add(
            if (truth.isEmpty()) Double.NaN
            else truth.count { it.envPeakG < thresholdOn }.toDouble() / truth.size,
        )
    }

    /** Do the three criteria of T5 hold simultaneously at this value of `k_on`? */
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
        if (t5Holds()) "yes" else "NO",
    )
}

/**
 * One value of `calFraction` and everything it changes. Different columns from [SweepRow] because
 * the question asked is different: the `k_on` sweep looked for where F1 moves, this one looks for
 * where the **miss rate** goes below the identifiability threshold of the deconvolution, and at what
 * price in false positives.
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

    /** The three points of T5, in the order 4x, 8x, 16x the effective floor. */
    val t5 = List(3) { ArrayList<Double>() }

    fun recordNominalNight(night: SynthNight, a: Analysis) {
        val truth = night.truth.accelLegMovements
        val thresholdOn = a.thresholdOnG
        thOn.add(thresholdOn)
        subThreshold.add(truth.count { it.envPeakG < thresholdOn }.toDouble() / truth.size)
        rawRatio.add(a.retained.size.toDouble() / truth.size)

        // Precision and the false-positive count are read against the **whole** `accelTruth`: that
        // is the expected counterpart of a lower threshold, and restricting it to above the
        // threshold would make it blind to precisely what is being watched.
        val m = Scoring.match(a.retained, truth)
        precision.add(m.precision)
        falsePositives.add(m.fp.toDouble())

        // The miss rate that matters is that of the **EMG train**, because it is the abscissa of the
        // breakdown curve of the deconvolution (`RhythmMeasurementTest`, docs §4.3).
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
        if (t5Holds()) "yes" else "NO",
    )
}

/** The four quantities of a match, accumulated over the seeds. */
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
 * Quantile by linear interpolation between ranks ("type 7" convention, the one used by R and numpy).
 * Chosen because it is the only one that makes `percentileOf(x, 0.5)` identical to [medianOf],
 * already used everywhere else in the suite: two definitions of the median in the same report would
 * be a source of inexplicable discrepancy.
 */
private fun percentileOf(values: List<Double>, p: Double): Double {
    val s = values.filter { !it.isNaN() }.sorted()
    if (s.isEmpty()) return Double.NaN
    val h = (s.size - 1) * p
    val lo = floor(h).toInt()
    val hi = ceil(h).toInt()
    return s[lo] + (h - lo) * (s[hi] - s[lo])
}
