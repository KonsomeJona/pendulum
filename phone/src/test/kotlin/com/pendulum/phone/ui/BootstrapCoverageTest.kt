package com.pendulum.phone.ui

import com.pendulum.phone.ui.model.Aggregate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The real coverage of the displayed interval, measured rather than assumed.
 *
 * ### Why this test exists
 *
 * The interval **is** the argument of this product: it is what turns a figure into a measurement.
 * Labelling it "95 %" without having checked that it covers 95 % of the time would be exactly the
 * defect the rest of the application exists to avoid — a precision asserted, never estimated.
 *
 * ### What is measured, and how
 *
 * Monte Carlo simulation: `n` nights are drawn from a distribution whose median is known, the
 * percentile bootstrap interval is computed exactly as [Aggregate.bootstrapCi] does it on screen,
 * and the proportion of replicates in which the **true** median falls inside is counted. That is
 * the operational definition of coverage, and nothing else replaces it.
 *
 * The reference distribution is log-normal with a coefficient of variation of 0.43: that is the
 * night to night variability of the hourly count measured by Skeba 2016 (43.2 % +/- 37.1), hence
 * the regime in which this application really works. The second set, Gaussian, serves as a control
 * — if the under-coverage were only an artefact of skewness, it would disappear there.
 *
 * ### The ceiling, which is a theorem and not an implementation defect
 *
 * Any median of a resampling with replacement of `n` values is **one of those `n` values**. The
 * bootstrap interval is therefore contained in the `[min, max]` of the sample, whatever the number
 * of draws and whatever the variant — percentile, BCa, or percentile-t. Now
 *
 * ```
 * P(min < theta < max) = 1 - 2 x 2^-n
 * ```
 *
 * for a true median `theta` (each value falls above or below with probability 1/2). That gives
 * 75 % at n = 3, 87.5 % at n = 4, 93.75 % at n = 5, 96.9 % at n = 6.
 *
 * **Direct consequence: BCa repairs nothing here.** Bias correction and acceleration move the
 * percentiles *inside* the bootstrap distribution; they cannot make it leave the `[min, max]`
 * envelope. A 95 % interval on the median of three nights does not exist, by construction. That is
 * why Pendulum does not implement BCa and **labels** the interval as uncalibrated below
 * [Aggregate.MIN_NIGHTS_CALIBRATED_CI] nights.
 */
class BootstrapCoverageTest {

    private companion object {
        const val REPLICATES = 10_000
        const val TRUE_MEDIAN = 20.0

        /** CV 0.43: the night to night variability of the hourly count (Skeba 2016). */
        val SIGMA_LOG = sqrt(ln(1.0 + 0.43 * 0.43))
    }

    private fun coverage(n: Int, draw: (Random) -> Double, replicates: Int = REPLICATES): Double {
        var inside = 0
        val rng = Random(20260802L + n)
        repeat(replicates) { r ->
            val values = DoubleArray(n) { draw(rng) }
            val (low, high) = Aggregate.bootstrapCi(values, seed = r.toLong() * 31 + n)
            if (TRUE_MEDIAN in low..high) inside++
        }
        return inside.toDouble() / replicates
    }

    private fun logNormal(rng: Random): Double =
        exp(ln(TRUE_MEDIAN) + SIGMA_LOG * gaussian(rng))

    private fun gaussian(rng: Random): Double {
        // Box-Muller. Only one of the two outputs is used: the cost is negligible next to the
        // 2,000 resamplings of each replicate.
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private fun normal(rng: Random): Double = TRUE_MEDIAN + 6.0 * gaussian(rng)

    /** `1 - 2 x 2^-n`: the maximum coverage attainable by any bootstrap whatsoever. */
    private fun ceiling(n: Int): Double = 1.0 - 2.0 * 2.0.pow(-n.toDouble())

    @Test
    fun `empirical coverage of the percentile interval, n from 3 to 8`() {
        val lines = StringBuilder("\nempirical coverage of the 95 % percentile bootstrap\n")
        lines.append("n   log-normal   gaussian   theoretical ceiling\n")

        val measurements = HashMap<Int, Double>()
        for (n in 3..8) {
            val cLog = coverage(n, ::logNormal)
            val cNorm = coverage(n, ::normal)
            measurements[n] = cLog
            lines.append(
                "%d   %.3f        %.3f      %.3f%n".format(n, cLog, cNorm, ceiling(n)),
            )
        }
        println(lines)

        // The fact that decides the labelling: below six nights, the interval is markedly below its
        // label. The bounds are set loosely so that this test measures without becoming brittle —
        // it is the orders of magnitude that carry the decision.
        assertThat(measurements[3]!!).isLessThan(0.80)
        assertThat(measurements[4]!!).isLessThan(0.90)
        assertThat(measurements[5]!!).isLessThan(0.95)
    }

    @Test
    fun `sweep up to twenty nights - where coverage catches up with its label`() {
        val lines = StringBuilder("\ncoverage sweep, n from 3 to 20 (log-normal distribution)\n")
        var firstGood = -1
        for (n in 3..20) {
            val c = coverage(n, ::logNormal, replicates = 3_000)
            lines.append("%2d  %.3f   ceiling %.3f%n".format(n, c, ceiling(n)))
            if (firstGood < 0 && c >= 0.93) firstGood = n
        }
        lines.append("first n with coverage >= 0.93: $firstGood\n")
        println(lines)
        assertThat(firstGood).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun `no bootstrap can exceed the ceiling, whatever its variant`() {
        // The theorem checked against the real implementation: the interval returned is always
        // contained in the range of the sample. That is what makes BCa useless here.
        val rng = Random(7)
        repeat(2_000) { r ->
            val n = 3 + r % 6
            val values = DoubleArray(n) { logNormal(rng) }
            val (low, high) = Aggregate.bootstrapCi(values, seed = r.toLong())
            assertThat(low).isGreaterThanOrEqualTo(values.min())
            assertThat(high).isLessThanOrEqualTo(values.max())
        }
    }

    @Test
    fun `the maximum coverage published by Aggregate is the theorem's`() {
        assertThat(Aggregate.maxBootstrapCoverage(3)).isEqualTo(0.75)
        assertThat(Aggregate.maxBootstrapCoverage(4)).isEqualTo(0.875)
        assertThat(Aggregate.maxBootstrapCoverage(5)).isEqualTo(0.9375)
        assertThat(Aggregate.maxBootstrapCoverage(6)).isEqualTo(0.96875)
        // The labelling threshold is the first n whose ceiling exceeds 95 %.
        assertThat(Aggregate.maxBootstrapCoverage(Aggregate.MIN_NIGHTS_CALIBRATED_CI))
            .isGreaterThan(0.95)
        assertThat(Aggregate.maxBootstrapCoverage(Aggregate.MIN_NIGHTS_CALIBRATED_CI - 1))
            .isLessThan(0.95)
    }
}
