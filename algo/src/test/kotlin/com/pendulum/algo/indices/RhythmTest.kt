package com.pendulum.algo.indices

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Tests of the harmonic deconvolution (`SPEC-v2.md` §5.3).
 *
 * The tolerances are not chosen after the fact: they come from the spec (period to better than
 * 5 %, miss rate to better than 0.1) and the margins actually observed in simulation are about
 * three times better.
 */
class RhythmTest {

    private val fundamentalSec = 22.0

    @Test
    fun `perfectly periodic intervals - no miss and exact period`() {
        val imi = DoubleArray(200) { fundamentalSec }

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isTrue()
        assertThat(fit.result.converged).isTrue()
        assertThat(fit.result.fundamentalSec).isCloseTo(fundamentalSec, within(1e-6))
        assertThat(fit.result.missRate).isLessThan(0.01)
        assertThat(fit.result.alternationSuspect).isFalse()
        assertThat(fit.reject).isNull()
        // All the mass on the fundamental component: no harmonic is invented.
        assertThat(fit.componentShare[0]).isCloseTo(1.0, within(1e-9))
        // The KS statistic is degenerate when the dispersion is; the guard rail must therefore be
        // neutralised, otherwise a perfectly periodic night would be declared uninterpretable.
        assertThat(fit.result.sigmaLog).isLessThan(RhythmConfig().ksMinSigma)
    }

    @Test
    fun `39 percent of misses - period recovered to better than 5 percent, rate to better than 0,1`() {
        for (seed in longArrayOf(11L, 12L, 13L)) {
            val imi = simulateObservedIntervalsSec(fundamentalSec, 0.22, 0.39, 4000, seed)

            val fit = Rhythm.fit(imi)

            assertThat(fit.result.valid).`as`("seed %s valid", seed).isTrue()
            assertThat(fit.result.converged).isTrue()
            assertThat(abs(fit.result.fundamentalSec - fundamentalSec) / fundamentalSec)
                .`as`("seed %s: relative period error", seed)
                .isLessThan(0.05)
            assertThat(abs(fit.result.missRate - 0.39))
                .`as`("seed %s: miss rate error", seed)
                .isLessThan(0.10)
            // The misses being TRULY independent here, the misfit measure must stay low.
            assertThat(fit.geometricMisfit).isLessThan(RhythmConfig().maxGeometricMisfit)
        }
    }

    @Test
    fun `the raw log mean is biased by +0,357 nats, the deconvolution is not`() {
        val cfg = RhythmConfig()
        val imi = simulateObservedIntervalsSec(fundamentalSec, 0.22, 0.39, 4000, 12L)
        val kept = imi.filter { it >= cfg.minIntervalSec && it <= cfg.maxIntervalSec }
        val rawLogMean = kept.sumOf { ln(it) } / kept.size

        val fit = Rhythm.fit(imi, cfg)

        // This is the heart of the §5.3 argument: the raw log mean is shifted by about
        // +0.357 nats (interval x1.43), that is 3.3 times the published night-to-night variability.
        assertThat(rawLogMean - ln(fundamentalSec)).isBetween(0.28, 0.45)
        // The deconvolution, for its part, lands back on the true period.
        assertThat(abs(fit.result.muLog - ln(fundamentalSec))).isLessThan(0.06)
        // And the theoretical bias tabulated in the spec is indeed that one.
        assertThat(Rhythm.rawLogBias(0.39)).isCloseTo(0.357, within(0.002))
    }

    @Test
    fun `the theoretical bias reproduces the SPEC table`() {
        assertThat(Rhythm.rawLogBias(0.20)).isCloseTo(0.158, within(0.002))
        assertThat(Rhythm.rawLogBias(0.30)).isCloseTo(0.255, within(0.002))
        assertThat(Rhythm.rawLogBias(0.50)).isCloseTo(0.508, within(0.002))
        // The SPEC states +0.901 for p = 0.70; the exact value of the series is +0.9146
        // (x2.50 and not x2.46). At p = 0.70 the series converges slowly and 0.901 corresponds to
        // a sum stopped around k = 15. The four other rows of the table are exact.
        assertThat(Rhythm.rawLogBias(0.70)).isCloseTo(0.9146, within(0.001))
        assertThat(exp(Rhythm.rawLogBias(0.39))).isCloseTo(1.43, within(0.01))
        assertThat(Rhythm.rawLogBias(0.0)).isEqualTo(0.0)
    }

    @Test
    fun `left-right alternation - one movement in two seen raises the flag`() {
        // Stochastic lateralisation: each movement of the true series is seen one time in two.
        val imi = simulateObservedIntervalsSec(fundamentalSec, 0.22, 0.50, 4000, 21L)

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isTrue()
        assertThat(fit.result.alternationSuspect).isTrue()
        assertThat(fit.result.missRate).isGreaterThan(0.45)
        // The FUNDAMENTAL period remains that of both legs together: this is the whole point of the
        // deconvolution, a naive reading would have stated 44 s.
        assertThat(abs(fit.result.fundamentalSec - fundamentalSec) / fundamentalSec).isLessThan(0.08)
    }

    @Test
    fun `39 percent of mechanical misses do NOT raise the alternation flag`() {
        val imi = simulateObservedIntervalsSec(fundamentalSec, 0.22, 0.39, 6000, 13L)

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isTrue()
        assertThat(fit.result.alternationSuspect).isFalse()
        assertThat(fit.componentShare[0]).isGreaterThan(0.55)
    }

    @Test
    fun `too few intervals - no result is produced`() {
        val imi = DoubleArray(20) { 22.0 + it * 0.1 }

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isFalse()
        assertThat(fit.result.converged).isFalse()
        assertThat(fit.result.intervalsUsed).isEqualTo(20)
        assertThat(fit.reject).isEqualTo(RhythmReject.TOO_FEW_INTERVALS)
        // NaN and not zero: "no value" must visibly poison every downstream computation.
        assertThat(fit.result.fundamentalSec.isNaN()).isTrue()
        assertThat(fit.result.missRate.isNaN()).isTrue()
        assertThat(fit.result.harmonicWeights).isEmpty()
    }

    @Test
    fun `the selection window excludes intra-burst fragments and inter-series pauses`() {
        val cfg = RhythmConfig()
        val imi = DoubleArray(120) {
            when {
                it % 10 == 0 -> 2.5      // intra-burst fragment, below minIntervalSec
                it % 10 == 5 -> 600.0    // pause between two series, above maxIntervalSec
                else -> 22.0
            }
        }

        val fit = Rhythm.fit(imi, cfg)

        assertThat(fit.result.intervalsUsed).isEqualTo(96)
        assertThat(fit.result.fundamentalSec).isCloseTo(22.0, within(1e-6))
    }

    @Test
    fun `clustered, non-geometric misses invalidate the result`() {
        // The caveat of §5.3 coded, not merely documented: the accelerometer misses low-amplitude
        // movements first, so the misses can cluster and hollow out the 2x peak. The extreme case
        // is built here — one interval in two is worth 4 times the fundamental, NONE is worth
        // 2 times — and a refusal is required rather than a wrong figure that looks sure.
        val rnd = java.util.Random(7L)
        val imi = DoubleArray(1000) {
            val base = 22.0 * exp(0.20 * rnd.nextGaussian())
            if (it % 2 == 0) base else base * 4.0
        }

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isFalse()
        assertThat(fit.reject)
            .isIn(RhythmReject.GEOMETRIC_MISFIT, RhythmReject.DISTRIBUTION_MISFIT)
        assertThat(fit.geometricMisfit).isGreaterThan(RhythmConfig().maxGeometricMisfit)
    }

    @Test
    fun `two runs on the same input are identical to the bit`() {
        val imi = simulateObservedIntervalsSec(fundamentalSec, 0.25, 0.42, 3000, 99L)

        val a = Rhythm.fit(imi)
        val b = Rhythm.fit(imi.copyOf())

        assertThat(a).isEqualTo(b)
        assertThat(a.result.muLog.toRawBits()).isEqualTo(b.result.muLog.toRawBits())
        assertThat(a.result.sigmaLog.toRawBits()).isEqualTo(b.result.sigmaLog.toRawBits())
        assertThat(a.result.missRate.toRawBits()).isEqualTo(b.result.missRate.toRawBits())
        assertThat(a.result.fundamentalSec.toRawBits()).isEqualTo(b.result.fundamentalSec.toRawBits())
        assertThat(a.logLikelihood.toRawBits()).isEqualTo(b.logLikelihood.toRawBits())
        assertThat(a.iterations).isEqualTo(b.iterations)
        for (i in a.result.harmonicWeights.indices) {
            assertThat(a.result.harmonicWeights[i].toRawBits())
                .isEqualTo(b.result.harmonicWeights[i].toRawBits())
        }
    }

    @Test
    fun `the weights are those of a truncated geometric and sum to one`() {
        val k = 5
        val w = Rhythm.geometricWeights(0.4, k)

        assertThat(w.sum()).isCloseTo(1.0, within(1e-12))
        for (i in 1 until k) assertThat(w[i]).isLessThan(w[i - 1])
        assertThat(w[1] / w[0]).isCloseTo(0.4, within(1e-12))
        assertThat(Rhythm.geometricWeights(0.0, k)[0]).isCloseTo(1.0, within(1e-12))
    }

    @Test
    fun `the truncated tail is the one the module quotes to justify its bounds`() {
        // `RhythmConfig` argues approximation no. 2 and `alternationMaxMissRate` from the mass the
        // truncation pushes onto the last component. That mass is `p^K` and it is quoted in prose
        // in three documents, so it is pinned here: prose drifts, an assertion does not. The
        // figures below are read at the module's own default `maxHarmonics`, which is the only
        // value at which the argument applies.
        val k = RhythmConfig().maxHarmonics
        for ((p, expected) in listOf(0.39 to 0.0090, 0.65 to 0.1160)) {
            val untruncated = DoubleArray(k) { Math.pow(p, it.toDouble()) * (1.0 - p) }
            assertThat(1.0 - untruncated.sum())
                .`as`("mass beyond K = %d at p = %s", k, p)
                .isCloseTo(expected, within(5e-5))
        }
    }

    @Test
    fun `the estimation of p exactly inverts the mean rank of the model`() {
        for (p in doubleArrayOf(0.0, 0.05, 0.2, 0.39, 0.5, 0.7, 0.85)) {
            val m = Rhythm.meanRank(p, 5)
            assertThat(Rhythm.solveMissRate(m, 5)).isCloseTo(p, within(1e-9))
        }
        // Outside the domain: a mean rank of 1 means "no miss".
        assertThat(Rhythm.solveMissRate(1.0, 5)).isEqualTo(0.0)
        assertThat(Rhythm.solveMissRate(0.5, 5)).isEqualTo(0.0)
    }
}
