package com.pendulum.algo.regression

import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.indices.RhythmReject
import com.pendulum.algo.synth.Scoring
import com.pendulum.algo.synth.SynthRandom
import com.pendulum.algo.synth.TruthKind
import java.util.Locale
import kotlin.math.abs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The fundamental rhythm under a high miss rate — the measurement that decides whether the product
 * holds.
 *
 * **Why this file exists.** T22 publishes that only 6 % of the true `aPLM-i` survives the threshold
 * policy. That would not invalidate Pendulum if `aPLM-i` were a secondary quantity: the follow-up
 * metric announced is the **fundamental rhythm in seconds**, recovered by harmonic deconvolution
 * (`Rhythm`), and its whole interest lies in the fact that it has **no** denominator and varies
 * twelve times less from one night to the next than the hourly count.
 *
 * But the deconvolution has an identifiability limit that its own KDoc states: the components of the
 * mixture are separated by `ln 2` nats, and the weight of the fundamental is `1 - p`. At `p = 0.39`
 * — the case it was designed for — the fundamental still weighs 61 %. At `p = 0.70`, it weighs only
 * 30 %, the first harmonic 21 %, the second 15 %; if `sigma` is wide, the bells merge into a
 * continuous smear and the EM can latch onto the wrong peak. **Nobody had measured what happens at
 * that rate**, and it is exactly the rate at which the detector works.
 *
 * Two measurements, no quality assertion. They print, like the `k_on` sweep: these are figures to
 * decide with, and the decision — change `calFraction`, change the series rule, change the published
 * metric — is clinical and product, not a code fix.
 */
class RhythmMeasurementTest {

    /**
     * Measurement 1 — the deconvolution as it really runs, on the nominal night.
     *
     * Four quantities, in order of importance:
     *
     *  1. **the relative error on `fundamentalSec`** against the rhythm really injected. That is THE
     *     figure: if the rhythm holds under 70 % of misses, the collapse of the index is a problem
     *     of a secondary metric; if it does not hold, it is the product;
     *  2. **the `missRate` returned** against the two true rates. There are two of them because
     *     there are two reference trains: the **EMG** train (all the generated series movements —
     *     the clinical scale, and the one the KDoc of `Rhythm` explicitly targets when it cites
     *     Terrill's 39 %) and the **accelerometric** train (those that move the sensor). Reporting
     *     both avoids declaring `p` right or wrong depending on the reference one picks;
     *  3. **the goodness of fit** — `geometricMisfit`, `ksStatistic` — and the number of seeds the
     *     module **invalidates**. Its KDoc claims it prefers to invalidate rather than return a
     *     wrong figure with a confident air. That is a verifiable claim, and it had not been
     *     verified at this miss rate;
     *  4. `alternationSuspect`. The generator produces **no** alternation, so this flag must never
     *     be raised here. Before the fix it was raised 14 times out of 20: its condition was a
     *     half-line `p >= 0.48`, and at 0.83 of amplitude misses it was satisfied for a reason that
     *     has nothing to do with lateralisation. `RhythmConfig` now carries an upper bound and this
     *     measurement asserts it.
     */
    @Test
    @DisplayName("Rhythm — deconvolution on the nominal night: fundamental, missRate, goodness of fit")
    fun rhythmOnTheNominalNightUnderTheRealMissRate() {
        val fundErr = ArrayList<Double>()
        val fundamental = ArrayList<Double>()
        val injected = ArrayList<Double>()
        val missEstimated = ArrayList<Double>()
        val missTrueEmg = ArrayList<Double>()
        val missTrueAccel = ArrayList<Double>()
        val misfit = ArrayList<Double>()
        val ks = ArrayList<Double>()
        val sigma = ArrayList<Double>()
        val intervals = ArrayList<Double>()
        var valid = 0
        var alternation = 0
        val rejects = HashMap<RhythmReject, Int>()

        for (seed in SEEDS) {
            val night = nominalNight(seed)
            val a = analyse(night)
            val fit = a.rhythmFit()
            val r = fit.result

            val trueFund = injectedFundamentalSec(night.truth)
            injected.add(trueFund)
            fundamental.add(r.fundamentalSec)
            // The error is reported even when the result is invalid: knowing by how much a refused
            // figure would have been wrong is what says whether the refusal was useful.
            fundErr.add(if (trueFund > 0.0) abs(r.fundamentalSec - trueFund) / trueFund else Double.NaN)

            val emgSeries = night.truth.emgTruth.filter { it.kind == TruthKind.PLM_IN_SERIES }
            val accelSeries = night.truth.accelTruth.filter { it.kind == TruthKind.PLM_IN_SERIES }
            missTrueEmg.add(1.0 - Scoring.match(a.retained, emgSeries).sensitivity)
            missTrueAccel.add(1.0 - Scoring.match(a.retained, accelSeries).sensitivity)

            missEstimated.add(r.missRate)
            misfit.add(fit.geometricMisfit)
            ks.add(fit.ksStatistic)
            sigma.add(r.sigmaLog)
            intervals.add(r.intervalsUsed.toDouble())
            if (r.valid) valid++
            if (r.alternationSuspect) alternation++
            fit.reject?.let { rejects[it] = (rejects[it] ?: 0) + 1 }
        }

        val out = StringBuilder()
        out.append("\n=== Rhythm on the nominal night, ").append(SEEDS.size).append(" seeds ===\n")
        out.append(line("injected fundamental, s", injected))
        out.append(line("estimated fundamental, s", fundamental))
        out.append(line("relative error", fundErr))
        out.append(line("estimated missRate", missEstimated))
        out.append(line("true missRate / EMG train", missTrueEmg))
        out.append(line("true missRate / accel train", missTrueAccel))
        out.append(line("estimated sigmaLog", sigma))
        out.append(line("geometricMisfit", misfit))
        out.append(line("ksStatistic", ks))
        out.append(line("intervals used", intervals))
        out.append("valid: ").append(valid).append(" / ").append(SEEDS.size)
        out.append(" ; alternationSuspect: ").append(alternation).append(" / ").append(SEEDS.size).append("\n")
        out.append("rejection reasons: ").append(if (rejects.isEmpty()) "none" else rejects.toString()).append("\n")
        println(out)

        // Scenario guard rail, not a quality one: if the deconvolution no longer received any
        // interval, everything above would be empty and the table would be misleading. The bound is
        // deliberately very low — the fact that the median grazes `RhythmConfig.minIntervals` (30)
        // is precisely one of the results, not a validity condition of the measurement.
        assertThat(medianOf(intervals))
            .`as`("median intervals supplied to the deconvolution")
            .isGreaterThan(10.0)

        // **Inverted** assertion, same spirit as T11: on the nominal night the vast majority of fits
        // is refused. This is not a failure of the module, it is its contract applying. The day this
        // assertion fails, either the chain finally detects enough movements, or the guard rail has
        // been loosened — both require reopening §4.3 of `docs/07-validation.md`.
        assertThat(valid)
            .`as`("rhythm fits declared valid on the nominal night")
            .isLessThanOrEqualTo(SEEDS.size / 4)

        // The generator produces no left/right alternation. A flag raised here is a false clinical
        // claim — the only output of the system that can be one.
        //
        // **A ceiling and not zero, and it is a measurement and not a compromise.** The upper bound
        // of `RhythmConfig.alternationMaxMissRate` brings the count down from 14/20 to 1/20: it
        // removes the absurd case where "almost everything is missing" read as "every other one".
        // The rest is irreducible by tuning: the flag is a function of `p` and of the share of the
        // fundamental, and those two quantities are **identical** whether half the movements are
        // missing because they are below the threshold or because they are on the other leg. The
        // `calFraction` sweep makes it visible: at `f_cal = 0.06`, where the miss rate falls
        // precisely towards 0.5, the flag goes back up to 11/20. Separating the two causes requires
        // the amplitude of the detected events, which `Rhythm` does not receive. See
        // `docs/07-validation.md` §4.4.
        assertThat(alternation)
            .`as`("alternationSuspect raised on a night without any alternation (14/20 before the upper bound)")
            .isLessThanOrEqualTo(SEEDS.size / 10)
    }

    /**
     * Measurement 2 — where the deconvolution gives way, as a function of the imposed miss rate.
     *
     * The detector plays no part here: the **true train** of the nominal night is taken, thinned out
     * with an imposed probability, and fitted. This is the absolute best case of the model — the
     * misses there are exactly independent and geometric, which they never are in reality, since the
     * accelerometer misses the low amplitudes first. **A degradation observed here is therefore a
     * floor on the real degradation, not an estimate of it.**
     *
     * That is also what makes the measurement interpretable: it isolates the identifiability of the
     * mixture from all the rest of the chain, and it is almost free — no detection, no signal.
     */
    @Test
    @DisplayName("Rhythm — breakdown curve: imposed miss rates 0.1 / 0.3 / 0.5 / 0.7 on the true train")
    fun rhythmAgainstImposedMissRateOnTheTrueTrain() {
        val rates = doubleArrayOf(0.0, 0.1, 0.3, 0.5, 0.7)
        val out = StringBuilder()
        out.append("\n=== Deconvolution on the thinned true train, ").append(SEEDS.size)
        out.append(" seeds, medians ===\n")
        out.append(
            String.format(
                Locale.ROOT, "%6s %10s %10s %10s %10s %10s %8s %8s%n",
                "p", "rel.err", "p_est", "sigma", "misfit", "ks", "valid", "altern.",
            ),
        )

        val err = rates.map { ArrayList<Double>() }
        val pEst = rates.map { ArrayList<Double>() }
        val sig = rates.map { ArrayList<Double>() }
        val mis = rates.map { ArrayList<Double>() }
        val kss = rates.map { ArrayList<Double>() }
        val valid = IntArray(rates.size)
        val alternation = IntArray(rates.size)

        // Seed on the outside, rate on the inside: the night does not depend on the rate, and
        // regenerating it five times would cost four 8 h generations for nothing.
        for (seed in SEEDS) {
            val night = nominalNight(seed)
            val trueFund = injectedFundamentalSec(night.truth)
            val onsets = night.truth.emgLegMovements.map { it.onsetMsRel }.sorted()

            for ((k, rate) in rates.withIndex()) {
                // Named stream: thinning at 0.3 must not depend on the draws made for 0.1, otherwise
                // one would be comparing five different trains while believing one compares five
                // rates.
                val rnd = SynthRandom(seed).stream("thin-$rate")
                val kept = onsets.filter { rate <= 0.0 || rnd.nextBoolean(1.0 - rate) }
                val imi = DoubleArray((kept.size - 1).coerceAtLeast(0)) {
                    (kept[it + 1] - kept[it]) / 1000.0
                }
                val fit = Rhythm.fit(imi)
                val r = fit.result
                err[k].add(if (trueFund > 0.0) abs(r.fundamentalSec - trueFund) / trueFund else Double.NaN)
                pEst[k].add(r.missRate)
                sig[k].add(r.sigmaLog)
                mis[k].add(fit.geometricMisfit)
                kss[k].add(fit.ksStatistic)
                if (r.valid) valid[k]++
                if (r.alternationSuspect) alternation[k]++
            }
        }

        for ((k, rate) in rates.withIndex()) {
            out.append(
                String.format(
                    Locale.ROOT, "%6.2f %10.3f %10.3f %10.3f %10.3f %10.3f %8s %8s%n",
                    rate, medianOf(err[k]), medianOf(pEst[k]), medianOf(sig[k]), medianOf(mis[k]),
                    medianOf(kss[k]), "${valid[k]}/${SEEDS.size}", "${alternation[k]}/${SEEDS.size}",
                ),
            )
        }
        out.append("\nImposed misses, independent and geometric: this is the model's best case.\n")
        println(out)

        val last = rates.size - 1
        // Two **inverted** assertions, in the spirit of T11: they state that a measured defect is
        // still there, so that we are warned the day it no longer is.
        //
        // 1. The error on the fundamental gets markedly worse between 0.5 and 0.7 of misses. This is
        //    the identifiability limit that the KDoc of `Rhythm` announces without putting a figure
        //    on it, and the detector works beyond it.
        assertThat(medianOf(err[last]))
            .`as`("error on the fundamental at 70 %% of misses, against %.3f at 30 %%", medianOf(err[2]))
            .isGreaterThan(2.0 * medianOf(err[2]))
        // 2. The KS statistic **goes down** when the miss rate goes up, that is, the goodness-of-fit
        //    measure improves while the estimate degrades. A guard rail anticorrelated with the error
        //    it guards cannot serve as a confidence criterion. **If this assertion ever fails, the
        //    goodness of fit has become informative** — and §4.3 of `docs/07-validation.md`, which is
        //    written on this measurement, must be redone.
        assertThat(medianOf(kss[last]))
            .`as`("KS at 70 %% of misses, against %.3f with no miss", medianOf(kss[0]))
            .isLessThan(medianOf(kss[0]))
        // 3. Not inverted, this one: the thinned train contains no alternation, whatever the rate.
        //    Before the upper bound of `RhythmConfig.alternationMaxMissRate`, the flag was raised 18
        //    times out of 20 at 70 % of misses; 4 remain. As on the nominal night, the residue is not
        //    a tuning defect: see the KDoc of the twin assertion above.
        assertThat(alternation[last])
            .`as`("alternationSuspect at 70 %% of imposed misses, with no alternation (18/20 before the upper bound)")
            .isLessThanOrEqualTo(SEEDS.size / 4)
        // 4. And the counterpart, which is the real bad news: at the miss rate it is **made for** —
        //    0.50, stochastic lateralisation — the flag is raised only 3 times out of 20. On a train
        //    mixing series, isolated movements and RRLM, `p` comes out at 0.333 and falls below the
        //    lower bound of 0.48. The flag is therefore both false-positive when the threshold misses
        //    movements and nearly blind to the very case it exists to signal.
        assertThat(alternation[3])
            .`as`("alternationSuspect at 50 %% of misses, the very case it must detect")
            .isLessThan(SEEDS.size / 2)
    }
}

private fun line(label: String, values: List<Double>): String = String.format(
    Locale.ROOT, "%-30s median %8.3f   [%8.3f ; %8.3f]%n",
    label, medianOf(values), worstMin(values), worstMax(values),
)
