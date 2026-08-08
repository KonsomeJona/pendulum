package com.pendulum.algo.dsp

import com.pendulum.algo.model.GapKind
import com.pendulum.algo.model.SimpleBlock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class TimelineTest {

    private val fs = 50.0
    private val stepNs = (1e9 / fs).toLong()

    /** Bloc de [n] echantillons a [fsBlock], commencant a [tStartNs]. */
    private fun block(tStartNs: Long, n: Int, fsBlock: Double = fs, value: (Long) -> Float = { 0f }): SimpleBlock {
        val spanNs = Math.round((n - 1) * 1e9 / fsBlock)
        val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
        for (i in 0 until n) {
            val t = tStartNs + Math.round(i * spanNs.toDouble() / (n - 1))
            x[i] = value(t)
            y[i] = 0f
            z[i] = 1f
        }
        return SimpleBlock(tStartNs, tStartNs + spanNs, 0, x, y, z)
    }

    @Test
    fun `les trous sont classes en MICRO BLIND et SEGMENT_BREAK et decoupent les segments`() {
        val blocks = ArrayList<SimpleBlock>()
        var t = 1_000_000_000L
        fun add(count: Int) {
            repeat(count) {
                val b = block(t, 100)
                blocks.add(b)
                t = b.tLastNs + stepNs
            }
        }
        add(5)
        t += 60_000_000L // micro : 0,06 s d'exces
        add(5)
        t += 500_000_000L // aveugle : 0,5 s
        add(5)
        t += 3_000_000_000L // rupture : 3 s
        add(5)

        val tl = TimelineBuilder.build(blocks, fs)

        assertThat(tl.gaps.map { it.kind }).containsExactly(
            GapKind.MICRO, GapKind.BLIND, GapKind.SEGMENT_BREAK,
        )
        assertThat(tl.segments).hasSize(2)

        val micro = tl.gaps.first { it.kind == GapKind.MICRO }
        assertThat(micro.durationSec).isCloseTo(0.06, within(1e-3))
        val blind = tl.gaps.first { it.kind == GapKind.BLIND }
        assertThat(blind.durationSec).isCloseTo(0.50, within(1e-3))
        // 0,5 s a 50 Hz : 25 echantillons manquants, a un pres selon l'alignement de la grille.
        assertThat(blind.toIdx - blind.fromIdx).isBetween(24, 26)

        // Le trou micro est interpole silencieusement, le trou aveugle ne l'est pas.
        for (i in micro.fromIdx until micro.toIdx) assertThat(tl.signal.z[i]).isNotNaN()
        for (i in blind.fromIdx until blind.toIdx) assertThat(tl.signal.z[i]).isNaN()

        // Zone aveugle elargie de settleSec = 2 s de chaque cote (100 echantillons).
        val zone = tl.blindZones.first { it.fromIdx <= blind.fromIdx && it.toIdx >= blind.toIdx }
        assertThat(blind.fromIdx - zone.fromIdx).isGreaterThanOrEqualTo(100)
        assertThat(zone.toIdx - blind.toIdx).isGreaterThanOrEqualTo(100)
    }

    @Test
    fun `les warmupSec de chaque segment sont ranges dans les zones aveugles`() {
        val blocks = ArrayList<SimpleBlock>()
        var t = 1_000_000_000L
        repeat(10) {
            val b = block(t, 500)
            blocks.add(b)
            t = b.tLastNs + stepNs
        }
        val tl = TimelineBuilder.build(blocks, fs)
        assertThat(tl.segments).hasSize(1)
        val head = tl.blindZones.first()
        assertThat(head.fromIdx).isEqualTo(0)
        assertThat(head.toIdx).isEqualTo(250) // warmupSec = 5,0 s x 50 Hz
        // 100 s enregistrees, 5 s de warmup exclues.
        assertThat(tl.analysableSec).isCloseTo(95.0, within(0.1))
    }

    @Test
    fun `le reechantillonnage d une source a 52 Hz reste fidele a 3 Hz`() {
        // §2 etape 0 borne l'attenuation point a point d'une interpolation lineaire a 3 Hz par
        // (pi f T)^2 / 2, soit au plus 1,8 %. On asserte la borne verifiable : erreur instantanee
        // sous 3 %.
        //
        // Ce commentaire citait « moins de 0,2 % » comme une annonce de la specification, et
        // proposait de la sauver en la lisant comme une attenuation *moyenne d'enveloppe*. Les
        // deux sont caduques : la specification a retire ce chiffre le 2026-07-31, et l'encadre
        // de correction refute nommement l'echappatoire de la moyenne, qui vaut ~1,1 %.
        val fsSrc = 52.0
        val t0 = 1_000_000_000L
        val f = 3.0
        val blocks = ArrayList<SimpleBlock>()
        var t = t0
        repeat(10) {
            val b = block(t, 208, fsSrc) { ts ->
                sin(2 * PI * f * (ts - t0) / 1e9).toFloat()
            }
            blocks.add(b)
            t = b.tLastNs + Math.round(1e9 / fsSrc)
        }
        val tl = TimelineBuilder.build(blocks, fsSrc)
        assertThat(tl.fs.fsSessionHz).isCloseTo(52.0, within(0.05))
        assertThat(tl.signal.fsHz).isEqualTo(50.0)

        var maxErr = 0.0
        for (i in 200 until tl.signal.n - 200) {
            val expected = sin(2 * PI * f * (tl.signal.tNs(i) - t0) / 1e9)
            val got = tl.signal.x[i].toDouble()
            if (!got.isNaN()) maxErr = maxOf(maxErr, kotlin.math.abs(got - expected))
        }
        assertThat(maxErr).isLessThan(0.03)
    }

    @Test
    fun `une montre posee sur la table est marquee off-body`() {
        val blocks = ArrayList<SimpleBlock>()
        var t = 1_000_000_000L
        val rnd = java.util.Random(7)
        // 5 min de porte (micro-mouvements), puis 12 min de table (bruit MEMS seul).
        repeat(700) { b ->
            val worn = b < 150
            val n = 500
            val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
            val spanNs = Math.round((n - 1) * 1e9 / fs)
            for (i in 0 until n) {
                val noise = if (worn) 0.03 else 0.001
                x[i] = (rnd.nextGaussian() * noise).toFloat()
                y[i] = (rnd.nextGaussian() * noise).toFloat()
                z[i] = (1.0 + rnd.nextGaussian() * noise).toFloat()
            }
            blocks.add(SimpleBlock(t, t + spanNs, 0, x, y, z))
            t += spanNs + (1e9 / fs).toLong()
        }
        val tl = TimelineBuilder.build(blocks, fs)
        assertThat(tl.offBody).isNotEmpty()
        val offSec = tl.offBody.sumOf { it.length } / fs
        assertThat(offSec).isGreaterThan(600.0)
        // Le temps off-body est retire du denominateur.
        assertThat(tl.analysableSec).isLessThan(tl.signal.n / fs - 600.0)
    }
}
