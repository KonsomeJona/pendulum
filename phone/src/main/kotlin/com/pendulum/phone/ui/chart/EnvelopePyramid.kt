package com.pendulum.phone.ui.chart

import kotlin.math.max
import kotlin.math.min

/**
 * The min/max pyramid of the RMS envelope.
 *
 * ### The problem
 *
 * Eight hours at 50 Hz make 1.44 million samples for about 1 100 pixel columns. A reduction by a
 * factor of ~1 300 is therefore needed. The obvious reduction — the mean — is **forbidden** here
 * (P5): a 40 ms movement drowned in a mean over 1 300 samples disappears from the screen purely and
 * simply. Yet that is exactly what the user is looking at. A smoothed curve that has lost its peaks
 * is not a simplification, it is a false observation.
 *
 * ### The answer
 *
 * A min/max pyramid built **once only**, off the main thread, when the night is loaded. Level `k`
 * aggregates the pairs of level `k-1` by `min` and `max`: an extremum can never be erased, it only
 * moves up. When drawing, we pick the level giving about one pair per column and trace a vertical
 * `min -> max` segment per column.
 *
 * `EnvelopePyramidTest` guarantees the property that matters: an isolated peak of a single sample
 * survives a decimation factor of 4096.
 *
 * ### Cost
 *
 * `2n` operations to build, and about twice the base in memory — ~11 MB for an 8 h night in
 * `Float`. This is accepted as it stands: if profiling shows memory pressure, the remedy is to
 * quantise into a `ShortArray` (log2 x 512), not to optimise before having measured.
 */
class EnvelopePyramid(private val base: FloatArray) {

    /**
     * `levels[k]` holds the `[min, max]` pairs for a decimation factor of `2^(k+1)`.
     * The conceptual level `-1` is [base] itself.
     */
    val levels: List<FloatArray> = buildList {
        var previous: FloatArray? = null
        var sourceSize = base.size
        while (sourceSize > 2) {
            val pairs = (sourceSize + 1) / 2
            val level = FloatArray(pairs * 2)
            if (previous == null) {
                // First level: aggregates the raw samples two by two.
                for (i in 0 until pairs) {
                    val a = base[i * 2]
                    val b = if (i * 2 + 1 < base.size) base[i * 2 + 1] else a
                    level[i * 2] = min(a, b)
                    level[i * 2 + 1] = max(a, b)
                }
            } else {
                val src = previous
                for (i in 0 until pairs) {
                    val i0 = i * 2
                    val i1 = if (i * 2 + 1 < sourceSize) i * 2 + 1 else i0
                    level[i * 2] = min(src[i0 * 2], src[i1 * 2])
                    level[i * 2 + 1] = max(src[i0 * 2 + 1], src[i1 * 2 + 1])
                }
            }
            add(level)
            previous = level
            sourceSize = pairs
        }
    }

    val size: Int get() = base.size

    /** Decimation factor of level `k`. */
    private fun factor(k: Int): Int = 1 shl (k + 1)

    /**
     * The level one of whose pairs covers at most `samplesPerColumn` samples.
     * Returns `-1` to draw directly from the base (strong zoom).
     */
    fun levelFor(samplesPerColumn: Float): Int {
        if (samplesPerColumn <= 2f) return -1
        var k = 0
        while (k + 1 < levels.size && factor(k + 1) <= samplesPerColumn) k++
        return k
    }

    /**
     * Fills `out` with `2 x columns` `[min, max]` values over the sample interval `[from, to[`.
     *
     * `out` is **pre-allocated by the caller** and reused from one frame to the next: this function
     * allocates nothing. That is the condition for a pinch to stay fluid — allocating 4 x 1 100
     * floats per frame triggers GCs that are visible to the eye.
     */
    fun fillMinMax(from: Int, to: Int, columns: Int, out: FloatArray) {
        require(out.size >= columns * 2) { "out too short" }
        val start = from.coerceIn(0, base.size)
        val end = to.coerceIn(start + 1, base.size)
        val perColumn = (end - start).toFloat() / columns
        val k = levelFor(perColumn)

        if (k < 0) {
            for (c in 0 until columns) {
                val i0 = start + (c * perColumn).toInt()
                val i1 = (start + ((c + 1) * perColumn).toInt()).coerceAtMost(end)
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                var i = i0
                while (i < i1.coerceAtLeast(i0 + 1) && i < base.size) {
                    val v = base[i]
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                    i++
                }
                out[c * 2] = mn
                out[c * 2 + 1] = mx
            }
            return
        }

        val level = levels[k]
        val f = factor(k)
        val pairs = level.size / 2
        for (c in 0 until columns) {
            val s0 = start + (c * perColumn).toInt()
            val s1 = (start + ((c + 1) * perColumn).toInt()).coerceAtMost(end)
            val p0 = (s0 / f).coerceIn(0, pairs - 1)
            val p1 = (s1 / f).coerceIn(p0, pairs - 1)
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (p in p0..p1) {
                val vmin = level[p * 2]
                val vmax = level[p * 2 + 1]
                if (vmin < mn) mn = vmin
                if (vmax > mx) mx = vmax
            }
            out[c * 2] = mn
            out[c * 2 + 1] = mx
        }
    }
}
