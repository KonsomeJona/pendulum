package com.pendulum.algo.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared numeric primitives of the `algo` module.
 *
 * Three rules valid for THE WHOLE of this file, and on which the rest of the chain relies:
 *
 *  1. **NaN policy — a `NaN` is an ABSENT sample, never a value.** All the reducers (median,
 *     percentile, MAD, moving means/RMS) ignore it and normalise by the number of genuinely valid
 *     samples. A reducer that sees no valid value returns `NaN` — the caller must test, never
 *     propagate blindly. That is what lets step 0 mark the holes with `NaN` without having to
 *     manufacture zeros (a zero would be a value, and a zero in an envelope would pull the floor
 *     downwards).
 *
 *  2. **No allocation in the hot loops.** The functions that run over 1.44 x 10^6 samples take a
 *     `dst` and a `scratch` supplied by the caller. The convenience overloads that allocate do
 *     exist, but must not be called inside a loop.
 *
 *  3. **Strict determinism.** No random pivot in the quickselect (median of three), no traversal
 *     depending on the order of a hash table, no clock. Same input -> same output down to the bit,
 *     on any JVM (the accumulations are done in `Double`, whose IEEE-754 arithmetic is
 *     reproducible).
 */
object Numeric {

    // ------------------------------------------------------------------
    // Selection / percentiles
    // ------------------------------------------------------------------

    /**
     * Copies into [dst] the non-`NaN` values of `src[from until to]`. Returns the number copied.
     * This is the preliminary step to any percentile: we never sort the signal in place, and we do
     * not want to pay an `isNaN` test at every quickselect comparison.
     */
    fun compactValid(src: FloatArray, from: Int, to: Int, dst: FloatArray): Int {
        var m = 0
        for (i in from until to) {
            val v = src[i]
            if (!v.isNaN()) dst[m++] = v
        }
        return m
    }

    /**
     * In-place quickselect: after the call, `a[k]` holds the k-th smallest value of
     * `a[0 until size]`, everything to its left is <= it and everything to its right >=.
     *
     * Pivot by median of three (first, middle, last): deterministic, and sufficient against the
     * pathological case that really threatens us here — an already sorted or near-constant floor
     * window (very quiet night), where a naive pivot would degenerate into O(n^2).
     */
    fun selectInPlace(a: FloatArray, size: Int, k: Int): Float {
        require(size > 0 && k in 0 until size) { "k=$k outside [0,$size)" }
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            // Median of three, sorted in place: a[lo] <= a[mid] <= a[hi].
            if (a[mid] < a[lo]) swap(a, mid, lo)
            if (a[hi] < a[lo]) swap(a, hi, lo)
            if (a[hi] < a[mid]) swap(a, hi, mid)
            val pivot = a[mid]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    swap(a, i, j)
                    i++
                    j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[lo]
    }

    private fun swap(a: FloatArray, i: Int, j: Int) {
        val t = a[i]; a[i] = a[j]; a[j] = t
    }

    /**
     * Percentile of an **already compacted** buffer (no `NaN`), with linear interpolation between
     * the two bracketing order statistics ("linear" convention of numpy / R type 7).
     *
     * The interpolation is not cosmetic: the noise floor is a p25 then a median over a window of
     * 6 000 samples, and a percentile at integer rank would make the output jump in steps at each
     * sample entering/leaving the sliding window. The threshold, which derives from it by
     * multiplication by 8, would inherit those jumps.
     *
     * @param p percentile in [0, 100].
     */
    fun percentileOfCompact(buf: FloatArray, size: Int, p: Double): Float {
        if (size <= 0) return Float.NaN
        if (size == 1) return buf[0]
        val h = (size - 1) * (p.coerceIn(0.0, 100.0) / 100.0)
        val lo = h.toInt()
        val frac = h - lo
        val vLo = selectInPlace(buf, size, lo)
        if (frac == 0.0 || lo + 1 >= size) return vLo
        // After selectInPlace(lo), everything to the right of `lo` is >= it: the order statistic
        // lo+1 is therefore simply the minimum of the right-hand part. No second sort.
        var vHi = buf[lo + 1]
        for (i in lo + 2 until size) if (buf[i] < vHi) vHi = buf[i]
        return (vLo + frac * (vHi - vLo)).toFloat()
    }

    /** Percentile of `v[from until to]`, `NaN`s ignored. [scratch] must hold `to - from` cells. */
    fun percentile(v: FloatArray, from: Int, to: Int, p: Double, scratch: FloatArray): Float {
        val m = compactValid(v, from, to, scratch)
        return percentileOfCompact(scratch, m, p)
    }

    /** Convenience overload — **allocates**, do not use in a hot loop. */
    fun percentile(v: FloatArray, p: Double): Float =
        percentile(v, 0, v.size, p, FloatArray(v.size))

    /** Median of `v[from until to]`, `NaN`s ignored. */
    fun median(v: FloatArray, from: Int, to: Int, scratch: FloatArray): Float =
        percentile(v, from, to, 50.0, scratch)

    /** Convenience overload — **allocates**. */
    fun median(v: FloatArray): Float = percentile(v, 50.0)

    /** Median of a `DoubleArray` (used outside hot loops: `fs` estimation, calibration). */
    fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val f = FloatArray(v.size)
        // We go through Floats: all the quantities concerned (fs, gains) fit comfortably in
        // 24 bits of mantissa, and this avoids duplicating the quickselect.
        var m = 0
        for (d in v) if (!d.isNaN()) f[m++] = d.toFloat()
        return percentileOfCompact(f, m, 50.0).toDouble()
    }

    /**
     * Weighted median: the smallest value whose cumulated weights reach half the total.
     * Used for `fs_session` (step 0), where each block weighs its number of samples — a block of
     * 512 samples constrains `fs` much better than a block of 30.
     *
     * Convention: lower bound ("lower weighted median"), no interpolation. An `fs` interpolated
     * between two blocks would have no physical meaning.
     */
    fun weightedMedian(values: DoubleArray, weights: DoubleArray): Double {
        require(values.size == weights.size) { "different sizes" }
        if (values.isEmpty()) return Double.NaN
        val idx = values.indices.sortedBy { values[it] }
        var total = 0.0
        for (w in weights) total += w
        if (total <= 0.0) return Double.NaN
        var cum = 0.0
        for (i in idx) {
            cum += weights[i]
            if (cum >= total / 2.0) return values[i]
        }
        return values[idx.last()]
    }

    /**
     * Normalised MAD: `1.4826 x median(|v - median(v)|)`. The factor brings the MAD back onto the
     * standard deviation of a Gaussian, which makes it comparable to a sigma without inheriting
     * its sensitivity to extreme values.
     *
     * @param scratch at least `to - from` cells; reused for both passes.
     */
    fun mad(v: FloatArray, from: Int, to: Int, scratch: FloatArray): Float {
        val m = compactValid(v, from, to, scratch)
        if (m == 0) return Float.NaN
        val med = percentileOfCompact(scratch, m, 50.0)
        // percentileOfCompact has permuted `scratch`, but we only need the values, not the order:
        // we overwrite each cell with its absolute deviation from the median.
        for (i in 0 until m) scratch[i] = abs(scratch[i] - med)
        return 1.4826f * percentileOfCompact(scratch, m, 50.0)
    }

    /** Convenience overload — **allocates**. */
    fun mad(v: FloatArray): Float = mad(v, 0, v.size, FloatArray(v.size))

    // ------------------------------------------------------------------
    // Sliding windows
    // ------------------------------------------------------------------

    /**
     * Cut-out of a centred window of [win] samples around `i`.
     * `win` even: the extra cell goes to the right. Fixed once and for all here so that all the
     * modules (envelope, floor, mask) share exactly the same convention — a half-sample offset
     * between the envelope and the floor would shift the edges.
     */
    fun halfLeft(win: Int): Int = (win - 1) / 2

    fun halfRight(win: Int): Int = win - 1 - halfLeft(win)

    /**
     * Centred moving mean over `[from, to)`, window of [win] samples **truncated at the bounds**
     * and normalised by the number of valid samples (not by [win]).
     *
     * Running sum in `Double`: O(n) whatever [win]. The rounding drift of a running sum over
     * 1.44 x 10^6 additions stays ~1e-10 in relative terms on values of the order of a g, three
     * orders of magnitude below the sensor resolution (0.49 mg) — and it is **deterministic**,
     * which is the only property that counts for non-regression.
     */
    fun movingMean(src: FloatArray, from: Int, to: Int, win: Int, dst: FloatArray) {
        require(win >= 1) { "win must be >= 1" }
        val hl = halfLeft(win)
        val hr = halfRight(win)
        var sum = 0.0
        var count = 0
        var lo = from
        var hi = from - 1 // last inclusive index already added
        for (i in from until to) {
            val wantLo = max(from, i - hl)
            val wantHi = min(to - 1, i + hr)
            while (hi < wantHi) {
                hi++
                val v = src[hi]
                if (!v.isNaN()) { sum += v; count++ }
            }
            while (lo < wantLo) {
                val v = src[lo]
                if (!v.isNaN()) { sum -= v; count-- }
                lo++
            }
            dst[i] = if (count > 0) (sum / count).toFloat() else Float.NaN
        }
    }

    /**
     * Centred moving RMS: `sqrt(moving_mean(src^2))`. This is the envelope of step 2.
     *
     * We sum the squares in `Double` and not the values: on an already positive L2 magnitude, the
     * RMS and the mean differ, and it is indeed the RMS that the specification asks for (energy,
     * not mean amplitude).
     */
    fun movingRms(src: FloatArray, from: Int, to: Int, win: Int, dst: FloatArray) {
        require(win >= 1) { "win must be >= 1" }
        val hl = halfLeft(win)
        val hr = halfRight(win)
        var sum = 0.0
        var count = 0
        var lo = from
        var hi = from - 1
        for (i in from until to) {
            val wantLo = max(from, i - hl)
            val wantHi = min(to - 1, i + hr)
            while (hi < wantHi) {
                hi++
                val v = src[hi]
                if (!v.isNaN()) { sum += v.toDouble() * v.toDouble(); count++ }
            }
            while (lo < wantLo) {
                val v = src[lo]
                if (!v.isNaN()) { sum -= v.toDouble() * v.toDouble(); count-- }
                lo++
            }
            // The running sum can become very slightly negative through catastrophic cancellation
            // when all the squares removed are worth their own sum.
            dst[i] = if (count > 0) sqrt(max(0.0, sum / count)).toFloat() else Float.NaN
        }
    }

    /**
     * Centred moving median. **O(n x win)**: reserved for small windows (typically the WASM
     * 3.2.1-d morphology criterion, `win = 25` at 50 Hz). Do not use it for the noise floor, whose
     * window is 6 000 samples — that is exactly why [NoiseFloor] evaluates on a stepped grid, and
     * not at every sample.
     *
     * @param scratch at least [win] cells.
     */
    fun movingMedian(src: FloatArray, from: Int, to: Int, win: Int, dst: FloatArray, scratch: FloatArray) {
        require(win >= 1 && scratch.size >= win) { "scratch too small" }
        val hl = halfLeft(win)
        val hr = halfRight(win)
        for (i in from until to) {
            val lo = max(from, i - hl)
            val hi = min(to - 1, i + hr)
            val m = compactValid(src, lo, hi + 1, scratch)
            dst[i] = percentileOfCompact(scratch, m, 50.0)
        }
    }

    /**
     * Percentile per **non-overlapping** window of [winSamples] samples.
     * One value per window, the last one possibly truncated. `p = 95` by default: that is the form
     * the immobility mask and the quality report need ("what is the high level of this epoch?"),
     * to be distinguished from the peak, too sensitive to a single artefact.
     */
    fun percentileByWindow(
        src: FloatArray,
        from: Int,
        to: Int,
        winSamples: Int,
        p: Double = 95.0,
        scratch: FloatArray = FloatArray(winSamples),
    ): FloatArray {
        require(winSamples >= 1) { "winSamples must be >= 1" }
        val n = max(0, to - from)
        val nWin = ceil(n.toDouble() / winSamples).toInt()
        val out = FloatArray(nWin)
        for (w in 0 until nWin) {
            val lo = from + w * winSamples
            val hi = min(to, lo + winSamples)
            val m = compactValid(src, lo, hi, scratch)
            out[w] = percentileOfCompact(scratch, m, p)
        }
        return out
    }

    /** Readable shortcut for the dominant case. */
    fun p95ByWindow(src: FloatArray, winSamples: Int): FloatArray =
        percentileByWindow(src, 0, src.size, winSamples, 95.0)

    // ------------------------------------------------------------------
    // Miscellaneous
    // ------------------------------------------------------------------

    /** Number of samples corresponding to [sec] at [fsHz], at least 1. */
    fun samples(sec: Double, fsHz: Double): Int = max(1, Math.round(sec * fsHz).toInt())

    /**
     * Least-squares slope of `y` on `x`, with no imposed intercept.
     * Used for the clock drift (§3.4) and for the autocalibration (§3.3).
     * Returns `NaN` if the variance of `x` is zero (coincident points).
     */
    fun slope(x: DoubleArray, y: DoubleArray): Double {
        require(x.size == y.size)
        val n = x.size
        if (n < 2) return Double.NaN
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[i]; my += y[i] }
        mx /= n; my /= n
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            sxy += dx * (y[i] - my)
            sxx += dx * dx
        }
        return if (sxx <= 0.0) Double.NaN else sxy / sxx
    }
}
