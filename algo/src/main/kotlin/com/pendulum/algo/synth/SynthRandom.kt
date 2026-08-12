package com.pendulum.algo.synth

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pseudo-random generator of the `synth` package. The **only** source of randomness allowed here.
 *
 * Three requirements, in this order:
 *
 *  1. **Total determinism** (`ALGO-v2.md` §5, test T13). The algorithm is SplitMix64, written out
 *     here in full: no dependency on `java.util.Random` (whose `nextGaussian` has hidden state and
 *     a rejection-based polar method, hence a variable number of calls), no clock, no object
 *     `hashCode`. Two runs with the same seed produce the same sequence, bit for bit, on any JVM.
 *  2. **Named independent sub-streams** ([stream]). This is what makes tests T8 to T11 possible:
 *     regenerating a night while changing *a single* distractor (gaps, mechanical gain, `fs`) must
 *     not move the movements. If every draw came out of a single stream, adding a gap would consume
 *     draws and shift the whole night — two different nights would then be compared while believing
 *     the effect of the gap was being measured.
 *  3. **First-class log-normal distribution**: the durations and amplitudes of §5.1 follow one, and
 *     `sigmaLog` must stay a parameter (it drives the slope of the sensitivity curve, T5).
 *
 * The normal distribution is drawn by Box-Muller *without* a cache: each call consumes exactly two
 * uniforms. A cache would make the sequence depend on the parity of the number of preceding calls,
 * that is to say fragile to any modification of the calling code.
 */
class SynthRandom private constructor(private val rootSeed: Long, private var state: Long) {

    constructor(seed: Long) : this(seed, seed)

    /** Named sub-stream, derived from the **root** seed (never from the current state). */
    fun stream(label: String): SynthRandom {
        val h = fnv64(label)
        val s = mix64(rootSeed xor h)
        return SynthRandom(s, s)
    }

    fun nextLong(): Long {
        state += GAMMA
        return mix64(state)
    }

    /** Uniform in `[0, 1)`, 53 mantissa bits. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * TWO_POW_MINUS_53

    /** Uniform in `(0, 1]` — the complement of [nextDouble], for logarithms. */
    fun nextDoublePositive(): Double = 1.0 - nextDouble()

    fun nextInt(boundExclusive: Int): Int {
        require(boundExclusive > 0) { "bound must be > 0" }
        return (nextDouble() * boundExclusive).toInt().coerceAtMost(boundExclusive - 1)
    }

    /** Uniform integer in `[lo, hi]`, bounds included. */
    fun nextIntRange(lo: Int, hi: Int): Int = if (hi <= lo) lo else lo + nextInt(hi - lo + 1)

    fun uniform(lo: Double, hi: Double): Double = lo + (hi - lo) * nextDouble()

    /** Uniform on the logarithmic scale: gives as much weight to `[3, 10[` as to `[10, 30[`. */
    fun logUniform(lo: Double, hi: Double): Double {
        require(lo > 0.0 && hi >= lo) { "invalid log-uniform bounds" }
        return exp(uniform(ln(lo), ln(hi)))
    }

    fun nextBoolean(p: Double): Boolean = nextDouble() < p

    /** Box-Muller, two uniforms per call, no hidden state. */
    fun nextGaussian(): Double {
        val u1 = nextDoublePositive()
        val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(TWO_PI * u2)
    }

    /**
     * Log-normal of median `median` and logarithmic standard deviation `sigmaLog`, truncated to
     * `[lo, hi]`. The truncation is done by **redrawing** (up to 64 attempts then clipping) and not
     * by direct clipping: clipping would pile a Dirac mass onto the bounds, which would distort the
     * low tail — precisely the one that carries the movements below the detection threshold, and so
     * the slope of the T5 sensitivity curve.
     */
    fun logNormal(median: Double, sigmaLog: Double, lo: Double, hi: Double): Double {
        require(median > 0.0) { "median must be > 0" }
        val mu = ln(median)
        repeat(64) {
            val v = exp(mu + sigmaLog * nextGaussian())
            if (v in lo..hi) return v
        }
        return exp(mu).coerceIn(lo, hi)
    }

    /**
     * Log-normal fitted on an arithmetic **mean** and **standard deviation** (that is how the
     * literature publishes CLM durations: 4.2 +/- 1.4 s, Sforza 2005).
     */
    fun logNormalFromMeanSd(mean: Double, sd: Double, lo: Double, hi: Double): Double {
        require(mean > 0.0 && sd >= 0.0) { "invalid mean/standard deviation" }
        val cv2 = (sd / mean) * (sd / mean)
        val sigma = sqrt(ln(1.0 + cv2))
        val median = mean / exp(sigma * sigma / 2.0)
        return logNormal(median, sigma, lo, hi)
    }

    private companion object {
        const val GAMMA: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val TWO_POW_MINUS_53: Double = 1.0 / (1L shl 53)
        const val TWO_PI: Double = 2.0 * Math.PI

        fun mix64(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }

        /** FNV-1a 64-bit on the UTF-8 bytes of the label. Stable across JVMs, unlike `hashCode`. */
        fun fnv64(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xFF)
                h *= 0x100000001B3L
            }
            return h
        }
    }
}

/** Logarithmic standard deviation corresponding to a coefficient of variation in percent. */
internal fun sigmaLogOfCvPct(cvPct: Double): Double = sqrt(ln(1.0 + (cvPct / 100.0) * (cvPct / 100.0)))
