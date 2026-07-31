package com.pendulum.algo.synth

import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.SimpleBlock

/**
 * Decimation d'une nuit deja generee (test T9, « decimation 50 -> 25 Hz »).
 *
 * On decime le **signal existant**, sans filtre anti-repliement, plutot que de regenerer la nuit a
 * 25 Hz. Les deux ne sont pas equivalents et la difference est le sujet meme du test : la sonnerie
 * de matelas a 8-20 Hz se replie sur la bande utile quand on jette un echantillon sur deux, alors
 * qu'elle n'existerait tout simplement pas dans une nuit generee directement a 25 Hz. Regenerer
 * serait la version docile de ce test.
 *
 * La verite terrain est inchangee : les memes mouvements ont eu lieu.
 */
object Resample {

    fun decimate(blocks: List<SampleBlock>, factor: Int): List<SampleBlock> {
        require(factor >= 1) { "facteur de decimation doit etre >= 1" }
        if (factor == 1) return blocks
        val out = ArrayList<SampleBlock>(blocks.size)
        // Phase globale : elle traverse les blocs, sinon la cadence resultante serait irreguliere
        // aux frontieres de bloc et l'etape −1 rejetterait des blocs sains.
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

    /** Meme formule que l'etape 0 : interpolation exacte de l'en-tete, jamais `tFirst + i/fs`. */
    private fun sampleTimeNs(b: SampleBlock, i: Int): Long {
        val n = b.x.size
        if (n <= 1) return b.tFirstNs
        return b.tFirstNs + Math.round(i.toDouble() * (b.tLastNs - b.tFirstNs).toDouble() / (n - 1))
    }
}
