package com.pendulum.algo.synth

import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.SimpleBlock

/**
 * Decimation of an already generated night (test T9, "decimation 50 -> 25 Hz").
 *
 * The **existing signal** is decimated, without any anti-aliasing filter, rather than regenerating
 * the night at 25 Hz. The two are not equivalent and the difference is the very subject of the
 * test: the 8-20 Hz mattress ringing aliases onto the useful band when every other sample is
 * thrown away, whereas it would simply not exist in a night generated directly at 25 Hz.
 * Regenerating would be the docile version of this test.
 *
 * The ground truth is unchanged: the same movements took place.
 */
object Resample {

    fun decimate(blocks: List<SampleBlock>, factor: Int): List<SampleBlock> {
        require(factor >= 1) { "decimation factor must be >= 1" }
        if (factor == 1) return blocks
        val out = ArrayList<SampleBlock>(blocks.size)
        // Global phase: it carries across blocks, otherwise the resulting rate would be irregular
        // at block boundaries and step −1 would reject healthy blocks.
        var phase = 0
        for (b in blocks) {
            val n = b.x.size
            val keep = ArrayList<Int>((n + factor - 1) / factor)
            var i = phase
            while (i < n) { keep.add(i); i += factor }
            phase = (i - n).coerceAtLeast(0)
            if (keep.size < 2) continue
            val m = keep.size
            val x = FloatArray(m); val y = FloatArray(m); val z = FloatArray(m)
            for (k in 0 until m) {
                x[k] = b.x[keep[k]]; y[k] = b.y[keep[k]]; z[k] = b.z[keep[k]]
            }
            out.add(
                SimpleBlock(
                    tFirstNs = sampleTimeNs(b, keep.first()),
                    tLastNs = sampleTimeNs(b, keep.last()),
                    flags = b.flags,
                    x = x, y = y, z = z,
                ),
            )
        }
        return out
    }

    /** Same formula as step 0: exact interpolation of the header, never `tFirst + i/fs`. */
    private fun sampleTimeNs(b: SampleBlock, i: Int): Long {
        val n = b.x.size
        if (n <= 1) return b.tFirstNs
        return b.tFirstNs + Math.round(i.toDouble() * (b.tLastNs - b.tFirstNs).toDouble() / (n - 1))
    }
}
