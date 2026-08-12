package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SleepMask
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fundamental rhythm of the periodic movements, estimated by **harmonic deconvolution** of the
 * inter-movement interval (`SPEC-v2.md` §5.3). This is the product's follow-up metric.
 *
 * # Why the raw mean of the log is unusable
 *
 * The IMI follows a log-normal law (Skeba 2016), and the mean of its log has a night-to-night
 * variability of **3.6 %** against **43.2 %** for the hourly count — twelve times less. That is
 * what justifies changing the primary metric. But a rate of missed movements destroys this
 * quantity:
 *
 * if each movement is missed independently with a probability `p`, an observed interval covers
 * `N` true intervals, `N` geometric, and the bias on the mean of the log is
 * `E[ln N] = Σ_k p^(k−1)(1−p)·ln k`:
 *
 * | `p`  | bias (nats)  | factor on the interval   |
 * |------|--------------|--------------------------|
 * | 0.20 | +0.158       | ×1.17 |
 * | 0.30 | +0.255       | ×1.29 |
 * | 0.39 | **+0.357**   | **×1.43** |
 * | 0.50 | +0.508       | ×1.66 |
 * | 0.70 | +0.915       | ×2.50 |
 *
 * (The last row corrects the table of §5.3, which gives +0.901 / ×2.46: the series
 * `Σ p^(k−1)(1−p)·ln k` converges slowly at `p = 0.70` and 0.901 corresponds to a sum stopped
 * around `k = 15`. The four other rows are exact to 3 decimals. See [rawLogBias].)
 *
 * At 39 % missed — the figure measured by Terrill 2013: 39 % of the movements visible on EMG move
 * **no** ankle sensor at all, a pure dorsiflexion not displacing a case located above the joint
 * axis — the bias is **+0.357 nats**, that is **3.3 times** the night-to-night variability that we
 * are precisely trying to exploit (≈ 0.11 nats for an IMI of 21 s).
 *
 * **Deconvolution is therefore not a refinement: it is the condition of existence of the metric.**
 *
 * # The model
 *
 * ```
 * log I_obs  ~  Σ_{k=1..K} w_k · N(μ + ln k, σ²)        w_k ∝ p^(k−1)(1−p)
 * ```
 *
 * estimated by expectation-maximisation on `(μ, σ, p)`. The centroids are separated by
 * `ln 2 ≈ 0.69` nats for a typical `σ` of 0.2 to 0.4: the separation is worth 1.7 to 3.5 σ, the
 * deconvolution is well posed even at `p = 0.39`, where the fundamental peak still weighs 61 % and
 * the first harmonic 24 %.
 *
 * Three outputs, **of equal importance**:
 *  - `exp(μ)` = fundamental period, rid of the miss rate;
 *  - `p` = **measured** miss rate. A free quality metric, and a comparability criterion: a `p`
 *    that jumps from one night to the next signals two nights that are not comparable;
 *  - `alternationSuspect`: a `p` close to 0.5 with a weak fundamental peak suggests movements
 *    alternating between the legs, which a unilateral sensor sees only one time out of two — a
 *    clinical result in itself.
 *
 * # Three approximations, stated rather than hidden
 *
 * 1. **Components of the same `σ`.** An observed interval of rank `k` is the *sum* of `k`
 *    log-normal intervals, whose log has a lower dispersion (≈ `σ/√k`) and a mean slightly higher
 *    than `μ + ln k`. The literal model of §5.3 ignores these two corrections; at `σ ≤ 0.3` they
 *    are worth less than 1.5 % on the period and the added complexity is not worth it. They are,
 *    on the other hand, the first thing to revisit if the estimated `σ` exceeds 0.4.
 * 2. **Truncation at `K` harmonics.** The tail `k > K` clumps onto the last component and pulls
 *    `p` **upward**. At `K = 5` and `p = 0.39`, the tail weighs 0.9 %: negligible bias. At
 *    `p = 0.65` it weighs 7.5 % and the estimation of `p` is no longer reliable — which the
 *    goodness-of-fit measure below detects.
 * 3. **Independence of the misses — probably false, and it is coded as such.** The accelerometer
 *    misses the low-amplitude movements first; if a burst decreases in amplitude, the misses clump
 *    at the end of the series and the 2× peak is **under-populated** with respect to the geometric
 *    model. The module therefore measures the fit of the model to the data
 *    ([RhythmFit.geometricMisfit] and [RhythmFit.ksStatistic]) and **invalidates** the result when
 *    it is bad, rather than returning a wrong figure with a confident air.
 *
 *    What the goodness-of-fit measure catches and what it does not, verified by simulation:
 *    - **caught** — a distribution whose 2× peak is missing (harmonics populated out of order):
 *      total variation distance of 0.12 to 0.25 against 0.04 at worst for a conforming mixture;
 *    - **not caught, and that is not a defect** — a miss probability that *grows along the burst*
 *      (0 at the start, 0.85 at the end). The resulting mixture of geometrics keeps a quasi
 *      geometric shape (gap 0.012) and the estimation of the period stays accurate to 0.5 %; `p`
 *      then reads as an **average rate over the night**, which is what it is. The dependence
 *      therefore does not derail the estimation as long as it does not hollow out one particular
 *      harmonic.
 *
 *    **A measured reservation, and it is a serious one: these two goodness-of-fit measures are not
 *    confidence measures.** `docs/07-validation.md` §4.3 measures them at an imposed miss rate on
 *    the true train: when `p` rises from 0.00 to 0.70, the error on the fundamental is multiplied
 *    by three (0.067 → 0.201) while the KS **goes down** from 0.116 to 0.071 and the
 *    `geometricMisfit` stays flat. More fits are accepted at `p = 0.70` (4/20) than at `p = 0.00`
 *    (0/20), where the estimation is three times better. The mechanism is understood after the
 *    fact: thinning out a train spreads the distribution of the intervals, and a log-normal
 *    mixture with a free `σ` fits a wide and smooth histogram **better**, whatever happens to the
 *    position of the mode. These two statistics measure the **global fit** of the mixture; what
 *    would need to be bounded is the **identifiability of `μ`**, which is another quantity. It is
 *    therefore `TOO_FEW_INTERVALS` and `MISS_RATE_SATURATED` — capacity guards, not fit guards —
 *    that do all the useful refusing today. §4.3 proposes what would be needed instead; the
 *    decision is not taken here.
 *
 * # An identifiability limit, to be known before reading `alternationSuspect`
 *
 * A **strictly deterministic** left/right alternation (exactly every other movement) is
 * mathematically **indistinguishable** from a rhythm twice as slow with no miss at all: the two
 * produce exactly the same sequence of intervals. No method founded on the intervals alone can
 * separate them. What the model detects is **stochastic** lateralisation (each movement visible
 * with a probability ≈ 1/2), which does leave a clear signature: `p ≈ 0.5` with populated
 * harmonics. Two nights with the sensor on the opposite leg remain the only way to settle the
 * deterministic case (`SPEC-v2.md` §6 question 6).
 */

/** Deconvolution parameters. None introduces randomness; the initialisation is deterministic. */
data class RhythmConfig(
    /**
     * Number of harmonics in the mixture (`k = 1..maxHarmonics`). Bounded: beyond that, the
     * distant components capture nothing but noise and inflate `p`.
     */
    val maxHarmonics: Int = 5,
    /** Below this number of intervals, no result is produced. */
    val minIntervals: Int = 30,
    /** Lower selection bound: excludes the intra-burst fragments (2–4 s bimodality). */
    val minIntervalSec: Double = 5.0,
    /**
     * Upper selection bound. To be tuned together with [maxHarmonics]: it must cover
     * `maxHarmonics × expected fundamental` (≈ 22–26 s), failing which the high harmonics are
     * amputated and `p` underestimated.
     */
    val maxIntervalSec: Double = 150.0,
    val maxIterations: Int = 300,
    /** Stopping criterion: largest variation of a parameter between two iterations. */
    val tolerance: Double = 1e-10,
    val sigmaFloor: Double = 1e-4,
    val sigmaCeiling: Double = 1.5,
    /** Beyond this, the signal no longer contains enough fundamental to speak of a rhythm. */
    val maxMissRate: Double = 0.90,
    /**
     * Distributional goodness-of-fit threshold (Kolmogorov–Smirnov). **Deliberately absolute and
     * not indexed on `n`**: with ~2 000 intervals, a formal test would reject any parametric model
     * whatsoever. We are not testing a hypothesis, we are refusing a gross misfit.
     */
    val maxKs: Double = 0.08,
    /**
     * Threshold of the total variation distance between the fitted geometric weights and the share
     * actually attributed to each harmonic. This is **the** guard rail against the hypothesis of
     * independence of the misses (approximation 3 above).
     *
     * Calibrated by simulation: a mixture conforming to the model gives 0.007 to 0.041 (up to
     * `p = 0.65`, `σ` from 0.10 to 0.40); a distribution whose 2× peak is absent gives 0.118 to
     * 0.250. The threshold is placed in the middle of that gap, on the conservative side.
     */
    val maxGeometricMisfit: Double = 0.10,
    /** Below this dispersion, the KS statistic makes no sense: the KS check is neutralised. */
    val ksMinSigma: Double = 0.02,
    /**
     * **Lower** bound of the alternation flag. Set at 0.48 and not at 0.50 because on a purely
     * periodic train the estimation of `p` is biased **upward** by about +0.03 by the truncation
     * at [maxHarmonics]: a true rate of 0.39 (purely mechanical misses, Terrill) comes out between
     * 0.40 and 0.44, and a true rate of 0.50 (stochastic lateralisation) between 0.53 and 0.55.
     * The threshold separates the two with a margin on both sides, and `RhythmTest` asserts it in
     * both directions.
     *
     * **A measured reservation, of the opposite sign.** That +0.03 holds for a train whose
     * intervals *all* belong to the harmonic mixture. On a complete nominal night — where isolated
     * movements and RRLMs are interleaved between the series — `p` is on the contrary
     * **under**-estimated: `docs/07-validation.md` §4.3 measures it at 0.098 for a true rate of
     * 0.00, 0.213 for 0.30 and 0.333 for 0.50. On such a night, a real lateralisation would
     * therefore come out **below** 0.48 and this flag would not be raised. Correcting it would
     * require calibrating the threshold on mixed trains, which is a clinical calibration decision
     * and not a correction: it is not taken here, it is written down.
     */
    val alternationMinMissRate: Double = 0.48,
    /**
     * **Upper** bound of the alternation flag. Without it, the condition was a half-line, and "one
     * time out of two" was not distinguished from "almost all the time".
     *
     * The flag asserts something clinical — the movements are perhaps alternating between the legs
     * — and it used to deduce that from a high `p`, whatever its cause. Measured on the nominal
     * night (§4.3), where the generator produces **no** alternation and where the miss rate is
     * 0.73 to 0.83 for a purely amplitude-related reason, it was raised **14 times out of 20**.
     * It was the only output of the system to be actively wrong rather than simply absent.
     *
     * The value 0.65 is not chosen to make a measurement pass: it is the one this file already
     * stated two paragraphs above, at approximation no. 2 — beyond `p = 0.65` the truncated tail
     * weighs 7.5 % and "the estimation of `p` is no longer reliable". A flag cannot rest on a
     * quantity that the module itself declares unreliable. It leaves intact the 0.53–0.55 range
     * where a true lateralisation comes out on a pure train, which `RhythmTest` asserts.
     *
     * **What the bound does not repair, and it must be read before believing this flag.** It
     * brings the count down from 14/20 to 1/20 on the nominal night, but the `calFraction` sweep
     * (§4.4) shows that at `f_cal = 0.06`, where the miss rate drops precisely towards 0.5, it
     * climbs back to **11/20** — still without the slightest alternation in the generator. This is
     * expected and it is not tunable: `p` and the share of the fundamental are **the same**
     * whether half of the movements are missing because they are below the threshold or because
     * they are on the other leg. The only quantity that would separate the two is the amplitude of
     * the detected events — a lateralisation is blind to amplitude, a threshold is not — and
     * `Rhythm` only receives intervals. Symmetrically, at the rate it is made for (0.50) the flag
     * is raised only 3 times out of 20 on a realistic train. **A false positive on one side,
     * almost blind on the other:** this flag calls for a design decision, not a tuning.
     */
    val alternationMaxMissRate: Double = 0.65,
    /**
     * "Weak fundamental peak", measured on the **empirical** share of the first component and not
     * on the model weight — failing which the condition would be a mere restatement of `p`.
     */
    val alternationMaxFundamentalShare: Double = 0.55,
)

/** Why a fit was refused. `null` = valid result. */
enum class RhythmReject {
    TOO_FEW_INTERVALS,
    NOT_CONVERGED,
    /** `p` stuck at the ceiling: no exploitable fundamental left. */
    MISS_RATE_SATURATED,
    /** `σ` stuck at the ceiling: the distribution has no identifiable mode. */
    SIGMA_SATURATED,
    /** The misses are not geometric — probably clumped at the end of the burst. */
    GEOMETRIC_MISFIT,
    /** The global shape of the distribution is not that of the fitted mixture. */
    DISTRIBUTION_MISFIT,
}

/**
 * Complete fit. [RhythmResult] is the contractual output; this type adds the diagnostics that make
 * it possible to know **why** one is entitled — or not — to believe the figure.
 *
 * @param componentShare average share of the observations attributed to each harmonic (average
 *   responsibilities). To be compared with `RhythmResult.harmonicWeights`, which are the geometric
 *   weights of the model: their gap is [geometricMisfit].
 */
data class RhythmFit(
    val result: RhythmResult,
    val logLikelihood: Double,
    val iterations: Int,
    val ksStatistic: Double,
    val geometricMisfit: Double,
    val componentShare: DoubleArray,
    val reject: RhythmReject?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RhythmFit) return false
        return result == other.result && logLikelihood == other.logLikelihood &&
            iterations == other.iterations && ksStatistic == other.ksStatistic &&
            geometricMisfit == other.geometricMisfit &&
            componentShare.contentEquals(other.componentShare) && reject == other.reject
    }

    override fun hashCode(): Int {
        var r = result.hashCode()
        r = 31 * r + logLikelihood.hashCode()
        r = 31 * r + iterations
        r = 31 * r + ksStatistic.hashCode()
        r = 31 * r + geometricMisfit.hashCode()
        r = 31 * r + componentShare.contentHashCode()
        r = 31 * r + (reject?.hashCode() ?: 0)
        return r
    }
}

private const val LN_2PI = 1.8378770664093453

object Rhythm {

    // --- Entry points ----------------------------------------------------------------------

    /**
     * **Recommended** entry point: all the consecutive sleep CLMs.
     *
     * Do not start from the already-built series: the series construction has **already** filtered
     * out the intervals outside [10, 90] s, that is to say precisely the high harmonics that we
     * are trying to model. Estimating `p` on pre-filtered intervals mechanically underestimates it.
     */
    fun fromClms(
        clms: List<Clm>,
        mask: SleepMask,
        cfg: RhythmConfig = RhythmConfig(),
    ): RhythmResult = fitFromClms(clms, mask, cfg).result

    fun fitFromClms(
        clms: List<Clm>,
        mask: SleepMask,
        cfg: RhythmConfig = RhythmConfig(),
    ): RhythmFit = fit(intervalsOf(clms, mask, cfg), cfg)

    /**
     * Fallback when only the series are available (incremental mode). See the reservation in
     * [fromClms]: `p` is structurally underestimated there.
     */
    fun fromSeries(series: List<PlmSeries>, cfg: RhythmConfig = RhythmConfig()): RhythmResult {
        val acc = ArrayList<Double>()
        for (s in series) for (v in s.imiSec) acc.add(v.toDouble())
        return fit(DoubleArray(acc.size) { acc[it] }, cfg).result
    }

    /** Onset-to-onset intervals between consecutive sleep CLMs, filtered by the [cfg] window. */
    fun intervalsOf(clms: List<Clm>, mask: SleepMask, cfg: RhythmConfig = RhythmConfig()): DoubleArray {
        val lookup = SleepLookup(mask.windows)
        val acc = ArrayList<Double>(clms.size)
        var prev = Long.MIN_VALUE
        for (c in clms) {
            if (!c.isClm) continue
            if (!lookup.isSleepAt(c.onsetMsRel)) continue
            if (prev != Long.MIN_VALUE) acc.add((c.onsetMsRel - prev) / 1000.0)
            prev = c.onsetMsRel
        }
        return DoubleArray(acc.size) { acc[it] }
    }

    fun estimate(imiSec: DoubleArray, cfg: RhythmConfig = RhythmConfig()): RhythmResult =
        fit(imiSec, cfg).result

    // --- Fit --------------------------------------------------------------------------------

    /**
     * The deconvolution proper. Pure, deterministic, with no randomness: the initialisation is a
     * fixed grid of starting points (data quantiles × three values of `p`), and the best one in
     * likelihood wins. Two runs on the same input are identical to the bit.
     */
    fun fit(imiSec: DoubleArray, cfg: RhythmConfig = RhythmConfig()): RhythmFit {
        require(cfg.maxHarmonics >= 1) { "maxHarmonics must be >= 1" }
        require(cfg.minIntervalSec > 0.0 && cfg.maxIntervalSec > cfg.minIntervalSec) {
            "inconsistent interval selection window"
        }

        // Selection: we keep only what the model can explain. The upper filter also cuts the
        // inter-series intervals (several minutes), which are not harmonics.
        var kept = 0
        for (v in imiSec) {
            if (v >= cfg.minIntervalSec && v <= cfg.maxIntervalSec && v.isFinite()) kept++
        }
        val x = DoubleArray(kept)
        var w = 0
        for (v in imiSec) {
            if (v >= cfg.minIntervalSec && v <= cfg.maxIntervalSec && v.isFinite()) x[w++] = ln(v)
        }

        if (kept < cfg.minIntervals) return emptyFit(kept, RhythmReject.TOO_FEW_INTERVALS)

        val k = cfg.maxHarmonics

        // --- Deterministic initialisation grid ------------------------------------------
        // mu0: two low quantiles. Under a high miss rate, the median can already fall between the
        // fundamental and the first harmonic; the low quartile, for its part, stays within the
        // fundamental as long as p < 0.75.
        val mu0s = doubleArrayOf(quantileOf(x, 0.25), quantileOf(x, 0.50))
        val p0s = doubleArrayOf(0.10, 0.40, 0.65)
        val mad = madOf(x)
        val sigma0 = if (mad.isFinite() && mad > 0.0) mad.coerceIn(0.08, 0.50) else 0.25

        var em = runEm(x, mu0s[0], sigma0, p0s[0], cfg)
        var bestLogLik = em.logLik
        for (mu0 in mu0s) {
            for (p0 in p0s) {
                val s = runEm(x, mu0, sigma0, p0, cfg)
                // Strict comparison: at equal likelihood, the first of the grid wins. That is what
                // makes the choice reproducible to the bit.
                if (s.logLik > bestLogLik) {
                    bestLogLik = s.logLik
                    em = s
                }
            }
        }

        val weights = geometricWeights(em.p, k)
        val share = responsibilityShare(x, em.mu, em.sigma, weights)

        // --- Fit of the model to the data -----------------------------------------------
        // (a) Shape of the weights: are the misses really geometric? If the accelerometer
        //     preferentially misses the ends of bursts, the 2x peak is under-populated and this
        //     total variation distance explodes, even though the mean of the ranks matches.
        var tv = 0.0
        for (i in 0 until k) tv += abs(share[i] - weights[i])
        val geometricMisfit = 0.5 * tv
        // (b) Global shape of the distribution.
        val ks = ksStatistic(x, em.mu, em.sigma, weights)

        var reject: RhythmReject? = null
        if (!em.converged || !em.mu.isFinite() || !em.sigma.isFinite() || !em.p.isFinite()) {
            reject = RhythmReject.NOT_CONVERGED
        } else if (em.p >= cfg.maxMissRate) reject = RhythmReject.MISS_RATE_SATURATED
        else if (em.sigma >= cfg.sigmaCeiling) reject = RhythmReject.SIGMA_SATURATED
        else if (geometricMisfit > cfg.maxGeometricMisfit) reject = RhythmReject.GEOMETRIC_MISFIT
        else if (em.sigma > cfg.ksMinSigma && ks > cfg.maxKs) reject = RhythmReject.DISTRIBUTION_MISFIT

        val valid = reject == null
        // The alternation flag does not depend on `valid`: it is read WITH it. An invalid night
        // whose p is 0.5 remains a night where the alternation hypothesis deserves to be raised.
        //
        // It is a **band** and not a half-line: beyond `alternationMaxMissRate`, `p` no longer
        // says "one time out of two" but "almost all the time", which is a detection defect and
        // not a lateralisation. See the KDoc of that parameter.
        val alternation = em.p >= cfg.alternationMinMissRate &&
            em.p <= cfg.alternationMaxMissRate &&
            share[0] <= cfg.alternationMaxFundamentalShare

        return RhythmFit(
            result = RhythmResult(
                fundamentalSec = exp(em.mu),
                muLog = em.mu,
                sigmaLog = em.sigma,
                missRate = em.p,
                harmonicWeights = weights,
                alternationSuspect = alternation,
                intervalsUsed = kept,
                converged = em.converged,
                valid = valid,
            ),
            logLikelihood = em.logLik,
            iterations = em.iterations,
            ksStatistic = ks,
            geometricMisfit = geometricMisfit,
            componentShare = share,
            reject = reject,
        )
    }

    private fun emptyFit(intervals: Int, reject: RhythmReject): RhythmFit = RhythmFit(
        result = RhythmResult(
            // NaN and not 0.0: "no value" must visibly poison every downstream computation, not
            // pass itself off as a zero period.
            fundamentalSec = Double.NaN,
            muLog = Double.NaN,
            sigmaLog = Double.NaN,
            missRate = Double.NaN,
            harmonicWeights = DoubleArray(0),
            alternationSuspect = false,
            intervalsUsed = intervals,
            converged = false,
            valid = false,
        ),
        logLikelihood = Double.NaN,
        iterations = 0,
        ksStatistic = Double.NaN,
        geometricMisfit = Double.NaN,
        componentShare = DoubleArray(0),
        reject = reject,
    )

    // --- Expectation-maximisation ------------------------------------------------------------

    private class EmState(
        val mu: Double,
        val sigma: Double,
        val p: Double,
        val logLik: Double,
        val iterations: Int,
        val converged: Boolean,
    )

    private fun runEm(
        x: DoubleArray,
        mu0: Double,
        sigma0: Double,
        p0: Double,
        cfg: RhythmConfig,
    ): EmState {
        val n = x.size
        val k = cfg.maxHarmonics
        val lnK = DoubleArray(k) { ln((it + 1).toDouble()) }
        var mu = mu0
        var sigma = sigma0.coerceIn(cfg.sigmaFloor, cfg.sigmaCeiling)
        var p = p0.coerceIn(0.0, cfg.maxMissRate)
        var iterations = 0
        var converged = false
        val lw = DoubleArray(k)
        val lp = DoubleArray(k)

        while (iterations < cfg.maxIterations) {
            iterations++
            val weights = geometricWeights(p, k)
            for (i in 0 until k) lw[i] = if (weights[i] > 0.0) ln(weights[i]) else Double.NEGATIVE_INFINITY
            val lnSigma = ln(sigma)

            var s1 = 0.0   // Σ r·y          with y = x − ln k
            var s2 = 0.0   // Σ r·y²
            var sk = 0.0   // Σ r·k          → mean rank, which is what identifies p

            for (i in 0 until n) {
                var maxLp = Double.NEGATIVE_INFINITY
                for (c in 0 until k) {
                    val z = (x[i] - mu - lnK[c]) / sigma
                    lp[c] = lw[c] - lnSigma - 0.5 * LN_2PI - 0.5 * z * z
                    if (lp[c] > maxLp) maxLp = lp[c]
                }
                var sum = 0.0
                for (c in 0 until k) sum += exp(lp[c] - maxLp)
                val lse = maxLp + ln(sum)
                for (c in 0 until k) {
                    val r = exp(lp[c] - lse)
                    if (r == 0.0) continue
                    val y = x[i] - lnK[c]
                    s1 += r * y
                    s2 += r * y * y
                    sk += r * (c + 1)
                }
            }

            val muNew = s1 / n
            val varNew = s2 / n - muNew * muNew
            val sigmaNew = sqrt(max(varNew, 0.0)).coerceIn(cfg.sigmaFloor, cfg.sigmaCeiling)
            // Exact MLE of p for a geometric TRUNCATED at k components: the mean rank of the model
            // is strictly increasing in p, so the root is unique and the bisection converges with
            // no risk of cycling.
            val pNew = solveMissRate(sk / n, k).coerceIn(0.0, cfg.maxMissRate)

            val delta = max(abs(muNew - mu), max(abs(sigmaNew - sigma), abs(pNew - p)))
            mu = muNew
            sigma = sigmaNew
            p = pNew
            if (delta < cfg.tolerance) {
                converged = true
                break
            }
        }

        return EmState(mu, sigma, p, logLikelihood(x, mu, sigma, geometricWeights(p, k)), iterations, converged)
    }

    // --- Mixture primitives ------------------------------------------------------------------

    /** Weights `w_k ∝ p^(k−1)(1−p)`, truncated at `k` components and renormalised. */
    internal fun geometricWeights(p: Double, k: Int): DoubleArray {
        val w = DoubleArray(k)
        var acc = 0.0
        var pk = 1.0
        for (i in 0 until k) {
            w[i] = pk * (1.0 - p)
            acc += w[i]
            pk *= p
        }
        if (!(acc > 0.0)) {          // p = 1: degenerate, we spread uniformly
            w.fill(1.0 / k)
            return w
        }
        for (i in 0 until k) w[i] /= acc
        return w
    }

    /** Mean rank `E[k]` under truncated weights. Strictly increasing in `p`, from 1 to (k+1)/2. */
    internal fun meanRank(p: Double, k: Int): Double {
        val w = geometricWeights(p, k)
        var m = 0.0
        for (i in 0 until k) m += (i + 1) * w[i]
        return m
    }

    /**
     * Inverts [meanRank] by bisection — 80 turns, hence convergence to ~1e-24: the result depends
     * on no evaluation order and stays identical to the bit from one run to the next.
     */
    internal fun solveMissRate(meanK: Double, k: Int): Double {
        if (k <= 1) return 0.0
        if (!(meanK > 1.0)) return 0.0
        var hi = 1.0 - 1e-12
        if (meanK >= meanRank(hi, k)) return hi
        var lo = 0.0
        repeat(80) {
            val mid = 0.5 * (lo + hi)
            if (meanRank(mid, k) < meanK) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    private fun logLikelihood(x: DoubleArray, mu: Double, sigma: Double, weights: DoubleArray): Double {
        val k = weights.size
        val lnSigma = ln(sigma)
        val lp = DoubleArray(k)
        var acc = 0.0
        for (i in x.indices) {
            var maxLp = Double.NEGATIVE_INFINITY
            for (c in 0 until k) {
                val lw = if (weights[c] > 0.0) ln(weights[c]) else Double.NEGATIVE_INFINITY
                val z = (x[i] - mu - ln((c + 1).toDouble())) / sigma
                lp[c] = lw - lnSigma - 0.5 * LN_2PI - 0.5 * z * z
                if (lp[c] > maxLp) maxLp = lp[c]
            }
            var sum = 0.0
            for (c in 0 until k) sum += exp(lp[c] - maxLp)
            acc += maxLp + ln(sum)
        }
        return acc
    }

    /** Average share of the observations attributed to each harmonic (average responsibilities). */
    private fun responsibilityShare(
        x: DoubleArray,
        mu: Double,
        sigma: Double,
        weights: DoubleArray,
    ): DoubleArray {
        val k = weights.size
        val share = DoubleArray(k)
        val lp = DoubleArray(k)
        val lnSigma = ln(sigma)
        for (i in x.indices) {
            var maxLp = Double.NEGATIVE_INFINITY
            for (c in 0 until k) {
                val lw = if (weights[c] > 0.0) ln(weights[c]) else Double.NEGATIVE_INFINITY
                val z = (x[i] - mu - ln((c + 1).toDouble())) / sigma
                lp[c] = lw - lnSigma - 0.5 * LN_2PI - 0.5 * z * z
                if (lp[c] > maxLp) maxLp = lp[c]
            }
            var sum = 0.0
            for (c in 0 until k) sum += exp(lp[c] - maxLp)
            val lse = maxLp + ln(sum)
            for (c in 0 until k) share[c] += exp(lp[c] - lse)
        }
        if (x.isNotEmpty()) for (c in 0 until k) share[c] /= x.size
        return share
    }

    /** Kolmogorov–Smirnov statistic between the empirical distribution and the fitted mixture. */
    private fun ksStatistic(
        x: DoubleArray,
        mu: Double,
        sigma: Double,
        weights: DoubleArray,
    ): Double {
        val n = x.size
        if (n == 0) return Double.NaN
        val s = x.copyOf()
        s.sort()
        var d = 0.0
        for (i in 0 until n) {
            val f = mixtureCdf(s[i], mu, sigma, weights)
            val above = (i + 1).toDouble() / n - f
            val below = f - i.toDouble() / n
            if (above > d) d = above
            if (below > d) d = below
        }
        return d
    }

    internal fun mixtureCdf(v: Double, mu: Double, sigma: Double, weights: DoubleArray): Double {
        var acc = 0.0
        for (c in weights.indices) {
            if (weights[c] <= 0.0) continue
            acc += weights[c] * normalCdf((v - mu - ln((c + 1).toDouble())) / sigma)
        }
        return acc
    }

    internal fun normalCdf(z: Double): Double = 0.5 * erfc(-z * 0.7071067811865476)

    /**
     * `erfc` by the rational approximation from Numerical Recipes (relative error < 1.2e-7).
     * No external dependency, no draw, identical result on every run.
     */
    internal fun erfc(x: Double): Double {
        val z = abs(x)
        val t = 1.0 / (1.0 + 0.5 * z)
        val ans = t * exp(
            -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
                t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 +
                    t * (-0.82215223 + t * 0.17087277))))))))
        )
        return if (x >= 0.0) ans else 2.0 - ans
    }

    /**
     * Theoretical bias of the mean of the log due to the misses: `E[ln N] = Σ p^(k−1)(1−p)·ln k`.
     * Exposed for the report and to check, on simulated data, that the deconvolution does remove
     * what the raw mean contains (§5.3).
     */
    fun rawLogBias(p: Double, terms: Int = 200): Double {
        if (!(p > 0.0)) return 0.0
        var acc = 0.0
        var pk = 1.0
        for (i in 1..terms) {
            acc += pk * (1.0 - p) * ln(i.toDouble())
            pk *= p
        }
        return acc
    }
}
