package com.pendulum.algo.dsp

import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import kotlin.math.max
import kotlin.math.min

/**
 * Noise floor parameters. Default values = table §6.2.
 *
 * [hopSec] does not appear in the specification: it is an implementation parameter, documented in
 * [NoiseFloor.estimate].
 */
data class NoiseFloorConfig(
    val winSec: Double = 120.0,
    val pass1Percentile: Int = 25,
    val excludeFactor: Double = 4.0,
    val minValidFraction: Double = 0.25,
    val mode: FloorMode = FloorMode.BILATERAL,
    /** Used only if `mode == CAUSAL_LAGGED`. */
    val causalLagSec: Double = 5.0,
    /** IMPLEMENTATION — step of the evaluation grid. See [NoiseFloor.estimate]. */
    val hopSec: Double = 1.0,
)

/**
 * Step 3 — adaptive noise floor, **three-pass, segmented** estimator.
 *
 * ```
 * Pass 1 : floor0(t) = p25 of env over the window of W = 120 s
 * Pass 2 : mask M = { i : env[i] > k_excl x floor0[i] }           (k_excl = 4)
 * Pass 3 : floor(t)  = median of env over the same window, restricted to { i outside M }
 *          if too few samples survive -> nearest valid value, FLOOR_EXTRAPOLATED
 * Then   : floor(t) <- max(floor(t), Theta_abs / k_on)
 * ```
 *
 * **Why iterative exclusion rather than a simple low percentile** (§1.3, quantified
 * counter-analysis). A PLMS series barely self-contaminates a median: mean duration of a PLM at
 * the ankle 4.2 s, mean IMI 31.3 s, i.e. a duty cycle of 13 to 18 %, and a median only breaks down
 * beyond 50 % contamination. At 18 %, the median rises by 16.6 % and a p10 by 11.1 %: the low
 * percentile alone only gains 5.5 points, that is not an order of magnitude. **What really breaks
 * the estimator is gross body movements**: a 20 s turn inside a 25 s window is 80 % contamination
 * and a complete breakdown. Hence the two real protections: `W = 120 s` (the same turn then weighs
 * no more than 17 % in it) and the explicit masking of the high samples before the final pass.
 *
 * **Why the window never crosses a boundary.** The floor is not thermal noise, it is a
 * **mechanical** floor: it changes in steps at every posture change, because the
 * strap-ankle-bedding coupling changes. A window straddling a step averages two regimes and gives
 * a wrong threshold on both sides. That is exactly the flaw the lagged causal window cannot deal
 * with (§1.3, reason no. 2) and the reason why the definitive mode stays bilateral.
 */
object NoiseFloor {

    /**
     * @param boundaries boundaries never to be crossed: **segment boundaries AND posture change
     *   boundaries** (§3.1, side effect (a)). Grid indices, any order, duplicates tolerated.
     * @param floorMinG floor of the floor, `Theta_abs / k_on` (0.020 / 8.0 = 2.5 mg with the
     *   default values of §6.3). It is passed as an argument rather than housed in
     *   [NoiseFloorConfig] so as not to duplicate here the threshold parameters, which belong to
     *   step 4; the caller must derive it from its own threshold configuration.
     * @return the floor per sample, and the `FLOOR_EXTRAPOLATED` flag per sample. Outside a
     *   segment, the floor is `NaN` and the flag is `true`.
     *
     * **IMPLEMENTATION — evaluation grid with step `hopSec`.** Recomputing two percentiles over
     * 6 000 samples at each of the night's 1.44 x 10^6 points would cost ~10^10 operations. The
     * floor is therefore evaluated every `hopSec` (1 s by default) then **linearly interpolated**.
     * This is licit because the window is 120 s long: between two points 1 s apart, 99.2 % of the
     * window content is shared, and the variation of the floor between the two is necessarily
     * minute. The cost is brought down to ~6 x 10^8 operations. The output stays bit-identical
     * from one run to the next for a given `hopSec`; changing `hopSec` changes the result
     * marginally but really, so it is part of the parameter hash.
     */
    fun estimate(
        env: Signal1D,
        segments: List<Segment>,
        boundaries: IntArray,
        cfg: NoiseFloorConfig = NoiseFloorConfig(),
        floorMinG: Float = 0.0025f,
    ): Pair<Signal1D, BooleanArray> {
        val n = env.n
        val fs = env.fsHz
        val floor = FloatArray(n) { Float.NaN }
        val extrapolated = BooleanArray(n) { true }

        val win = Numeric.samples(cfg.winSec, fs)
        val hop = Numeric.samples(cfg.hopSec, fs)
        val lag = Numeric.samples(cfg.causalLagSec, fs)
        val minValid = (cfg.minValidFraction * win).toInt().coerceAtLeast(1)
        val scratch = FloatArray(win + 1)

        for (interval in splitAtBoundaries(segments, boundaries, n)) {
            estimateInterval(env, interval, win, hop, lag, minValid, cfg, floor, extrapolated, scratch)
        }

        // Consistency with the absolute floor (§1.3, last line): a floor measured below
        // `Theta_abs / k_on` cannot produce a threshold below `Theta_abs` anyway.
        // Bounding it here prevents an abnormally quiet night from blowing up the signal/floor
        // ratio and making all the quality statistics unreadable.
        for (i in 0 until n) {
            val f = floor[i]
            if (!f.isNaN() && f < floorMinG) floor[i] = floorMinG
        }
        return Signal1D(fs, env.t0Ns, floor) to extrapolated
    }

    /**
     * Cuts the segments at the supplied boundaries. A boundary interior to a segment cuts it into
     * two homogeneous intervals; boundaries outside a segment are ignored.
     */
    internal fun splitAtBoundaries(segments: List<Segment>, boundaries: IntArray, n: Int): List<Segment> {
        val sorted = boundaries.filter { it in 0..n }.distinct().sorted()
        val out = ArrayList<Segment>()
        for (seg in segments) {
            var start = seg.fromIdx
            for (b in sorted) {
                if (b > start && b < seg.toIdx) {
                    out.add(Segment(start, b))
                    start = b
                }
            }
            if (seg.toIdx > start) out.add(Segment(start, seg.toIdx))
        }
        return out
    }

    private fun estimateInterval(
        env: Signal1D,
        iv: Segment,
        win: Int,
        hop: Int,
        lag: Int,
        minValid: Int,
        cfg: NoiseFloorConfig,
        floor: FloatArray,
        extrapolated: BooleanArray,
        scratch: FloatArray,
    ) {
        val len = iv.length
        if (len <= 0) return

        // Evaluation points: every `hop`, plus the last sample so that the interpolation covers
        // the whole interval without extrapolating.
        val evalCount = ((len - 1) / hop) + 1
        val evalIdx = IntArray(evalCount + 1)
        for (j in 0 until evalCount) evalIdx[j] = iv.fromIdx + j * hop
        evalIdx[evalCount] = iv.toIdx - 1
        val nEval = if (evalIdx[evalCount] > evalIdx[evalCount - 1]) evalCount + 1 else evalCount

        val f0 = FloatArray(nEval)
        val f3 = FloatArray(nEval) { Float.NaN }
        val ext = BooleanArray(nEval)

        // --- Pass 1: raw p25 --------------------------------------------------------------
        for (j in 0 until nEval) {
            val e = evalIdx[j]
            val lo: Int
            val hi: Int
            if (cfg.mode == FloorMode.BILATERAL) {
                lo = max(iv.fromIdx, e - win / 2)
                hi = min(iv.toIdx, e + win - win / 2)
            } else {
                // CAUSAL_LAGGED: window [t - lag - W, t - lag]. The 5 s shift prevents the current
                // event from contaminating its own floor; the resulting 35 s lag bias is
                // acceptable in provisional mode, never in the definitive one.
                hi = min(iv.toIdx, max(iv.fromIdx, e - lag))
                lo = max(iv.fromIdx, hi - win)
            }
            f0[j] = if (hi > lo) {
                Numeric.percentile(env.v, lo, hi, cfg.pass1Percentile.toDouble(), scratch)
            } else Float.NaN
        }
        // `f0` may contain NaNs (window entirely inside a hole): they are filled in before being
        // used as the exclusion reference, otherwise the pass 2 mask would let anything through at
        // that spot.
        fillNearest(f0)

        // --- Pass 1bis: floor0 per sample (support for the pass 2 mask) -------------------
        val floor0Sample = FloatArray(len)
        interpolate(evalIdx, f0, nEval, iv, floor0Sample)

        // --- Passes 2 and 3: median over the unmasked samples -----------------------------
        for (j in 0 until nEval) {
            val e = evalIdx[j]
            val lo: Int
            val hi: Int
            if (cfg.mode == FloorMode.BILATERAL) {
                lo = max(iv.fromIdx, e - win / 2)
                hi = min(iv.toIdx, e + win - win / 2)
            } else {
                hi = min(iv.toIdx, max(iv.fromIdx, e - lag))
                lo = max(iv.fromIdx, hi - win)
            }
            var m = 0
            for (i in lo until hi) {
                val v = env.v[i]
                if (v.isNaN()) continue
                val ref = floor0Sample[i - iv.fromIdx]
                // Masking: anything exceeding `k_excl x floor0` is presumed to be event, not
                // background noise. `k_excl = 4` is deliberately BELOW `k_on = 8`: we also want to
                // exclude the sub-threshold CLMs, which are signal even though they will not be
                // counted, failing which they would raise the floor and eliminate themselves.
                if (!ref.isNaN() && v > cfg.excludeFactor * ref) continue
                scratch[m++] = v
            }
            if (m >= minValid) {
                f3[j] = Numeric.percentileOfCompact(scratch, m, 50.0)
                ext[j] = false
            } else {
                // Fewer than `minValidFraction x W` surviving samples: the window is dominated by
                // event or by hole. We do not publish a median over 3 samples — we take back the
                // nearest valid value and we say so.
                // This case is SYSTEMATIC on the last seconds of a truncated night (§3.7.2).
                f3[j] = Float.NaN
                ext[j] = true
            }
        }
        // Fallback: nearest valid value; failing any valid value at all in the interval, pass 1
        // (§1.3 explicitly provides for it this way).
        val anyValid = (0 until nEval).any { !f3[it].isNaN() }
        if (anyValid) {
            fillNearest(f3)
        } else {
            for (j in 0 until nEval) f3[j] = f0[j]
        }

        // --- Per-sample restitution --------------------------------------------------------
        val out = FloatArray(len)
        interpolate(evalIdx, f3, nEval, iv, out)
        for (i in 0 until len) floor[iv.fromIdx + i] = out[i]

        // A sample is extrapolated as soon as **one of the two** evaluation points bracketing it
        // is: a floor interpolated from an extrapolated value stays extrapolated.
        var j = 0
        for (i in iv.fromIdx until iv.toIdx) {
            while (j + 1 < nEval && evalIdx[j + 1] < i) j++
            val right = if (j + 1 < nEval) ext[j + 1] else ext[j]
            extrapolated[i] = ext[j] || right
        }
    }

    /** Fills the `NaN`s of an array with the nearest valid value (backward then forward). */
    private fun fillNearest(a: FloatArray) {
        val n = a.size
        var last = Float.NaN
        for (i in 0 until n) {
            if (!a[i].isNaN()) last = a[i] else if (!last.isNaN()) a[i] = last
        }
        last = Float.NaN
        for (i in n - 1 downTo 0) {
            if (!a[i].isNaN()) last = a[i] else if (!last.isNaN()) a[i] = last
        }
    }

    /** Linear interpolation from the values at the evaluation points to every sample. */
    private fun interpolate(evalIdx: IntArray, values: FloatArray, nEval: Int, iv: Segment, out: FloatArray) {
        if (nEval == 1) {
            java.util.Arrays.fill(out, values[0])
            return
        }
        var j = 0
        for (i in iv.fromIdx until iv.toIdx) {
            while (j + 1 < nEval && evalIdx[j + 1] < i) j++
            val a = evalIdx[j]
            val b = if (j + 1 < nEval) evalIdx[j + 1] else a
            out[i - iv.fromIdx] = if (b == a) values[j] else {
                val u = (i - a).toDouble() / (b - a).toDouble()
                (values[j] + u * (values[j + 1] - values[j])).toFloat()
            }
        }
    }
}
