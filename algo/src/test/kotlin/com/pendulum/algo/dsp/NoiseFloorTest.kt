package com.pendulum.algo.dsp

import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NoiseFloorTest {

    private val fs = 50.0

    /** Enveloppe synthetique : plancher constant + salves periodiques. */
    private fun env(
        durationSec: Int,
        floorG: Double,
        burstG: Double,
        burstSec: Double,
        periodSec: Double,
        seed: Long = 3,
    ): Signal1D {
        val n = (durationSec * fs).toInt()
        val rnd = java.util.Random(seed)
        val v = FloatArray(n)
        val burstLen = (burstSec * fs).toInt()
        val period = (periodSec * fs).toInt()
        for (i in 0 until n) {
            val inBurst = (i % period) < burstLen
            val base = floorG * (1.0 + 0.10 * rnd.nextGaussian())
            v[i] = (if (inBurst) burstG else base).toFloat()
        }
        return Signal1D(fs, 0L, v)
    }

    @Test
    fun `le plancher ne s auto-contamine pas malgre 13 pourcent de salves`() {
        // Rapport cyclique realiste des PLMS a la cheville : 4,2 s de mouvement pour 31,3 s
        // d'intervalle, soit 13 % (Sforza 2005, cite en §1.3).
        val e = env(1200, floorG = 0.010, burstG = 0.200, burstSec = 4.2, periodSec = 31.3)
        val segs = listOf(Segment(0, e.n))
        val (floor, ext) = NoiseFloor.estimate(e, segs, IntArray(0))

        val mid = e.n / 2
        assertThat(floor.v[mid].toDouble()).isCloseTo(0.010, org.assertj.core.api.Assertions.within(0.0015))
        assertThat(ext[mid]).isFalse()

        // Sans exclusion, la mediane brute de la fenetre serait tiree vers le haut : on verifie
        // que l'estimateur fait mieux que la mediane naive de la meme fenetre.
        val naive = Numeric.percentile(e.v, mid - 3000, mid + 3000, 50.0, FloatArray(6000))
        assertThat(floor.v[mid]).isLessThanOrEqualTo(naive)
    }

    @Test
    fun `un retournement de 20 secondes ne fait pas decrocher l estimateur`() {
        // C'est le vrai casseur d'estimateur (§1.3) : 80 % de contamination sur une fenetre de
        // 25 s, mais 17 % seulement sur les 120 s de la specification.
        val n = (600 * fs).toInt()
        val rnd = java.util.Random(11)
        val v = FloatArray(n) { (0.010 * (1.0 + 0.10 * rnd.nextGaussian())).toFloat() }
        val from = (300 * fs).toInt()
        for (i in from until from + (20 * fs).toInt()) v[i] = 0.5f
        val e = Signal1D(fs, 0L, v)

        val (floor, _) = NoiseFloor.estimate(e, listOf(Segment(0, n)), IntArray(0))
        assertThat(floor.v[from - 10].toDouble()).isCloseTo(0.010, org.assertj.core.api.Assertions.within(0.002))
        assertThat(floor.v[from + (25 * fs).toInt()].toDouble())
            .isCloseTo(0.010, org.assertj.core.api.Assertions.within(0.002))
    }

    @Test
    fun `une frontiere de posture empeche la fenetre de melanger deux regimes`() {
        // Plancher mecanique qui saute d'un facteur 4 au changement de posture.
        val n = (600 * fs).toInt()
        val boundary = n / 2
        val rnd = java.util.Random(5)
        val v = FloatArray(n) {
            val base = if (it < boundary) 0.008 else 0.032
            (base * (1.0 + 0.10 * rnd.nextGaussian())).toFloat()
        }
        val e = Signal1D(fs, 0L, v)

        val (withBoundary, _) = NoiseFloor.estimate(e, listOf(Segment(0, n)), intArrayOf(boundary))
        val (without, _) = NoiseFloor.estimate(e, listOf(Segment(0, n)), IntArray(0))

        // Juste APRES la frontiere, l'estimateur cloisonne est deja sur le nouveau regime a
        // 32 mg. Le non cloisonne reste accroche a l'ancien regime a 8 mg pendant une minute :
        // son seuil est quatre fois trop bas, et c'est exactement la pluie de faux positifs qui
        // suit chaque changement de posture.
        assertThat(withBoundary.v[boundary + 10].toDouble())
            .isCloseTo(0.032, org.assertj.core.api.Assertions.within(0.004))
        assertThat(without.v[boundary + 10].toDouble()).isLessThan(0.015)
    }

    @Test
    fun `le mode causal decale ne regarde jamais le futur`() {
        val n = (600 * fs).toInt()
        val v = FloatArray(n) { 0.010f }
        val jump = (300 * fs).toInt()
        for (i in jump until n) v[i] = 0.040f
        val e = Signal1D(fs, 0L, v)

        val cfg = NoiseFloorConfig(mode = FloorMode.CAUSAL_LAGGED)
        val (floor, _) = NoiseFloor.estimate(e, listOf(Segment(0, n)), IntArray(0), cfg)

        // Un echantillon avant le saut ne peut rien en savoir.
        assertThat(floor.v[jump - 1].toDouble()).isCloseTo(0.010, org.assertj.core.api.Assertions.within(1e-4))
        // Et le retard de l'estimateur est bien reel : 5 s apres le saut, le plancher n'a pas
        // encore bouge. C'est le biais assume du mode provisoire (§3.7.1).
        assertThat(floor.v[jump + (4 * fs).toInt()].toDouble())
            .isCloseTo(0.010, org.assertj.core.api.Assertions.within(1e-3))
    }

    @Test
    fun `une fenetre trop pauvre en donnees valides leve FLOOR_EXTRAPOLATED`() {
        val n = (300 * fs).toInt()
        // Nuit presque entierement absente : quelques echantillons valides noyes dans les trous.
        val v = FloatArray(n) { if (it % 100 == 0) 0.01f else Float.NaN }
        val e = Signal1D(fs, 0L, v)
        val (floor, ext) = NoiseFloor.estimate(e, listOf(Segment(0, n)), IntArray(0))
        assertThat(ext[n / 2]).isTrue()
        assertThat(floor.v[n / 2]).isNotNaN()
    }

    @Test
    fun `le plancher est borne par Theta_abs sur k_on`() {
        val n = (300 * fs).toInt()
        val e = Signal1D(fs, 0L, FloatArray(n) { 1e-5f }) // nuit irrealiste de calme
        val (floor, _) = NoiseFloor.estimate(e, listOf(Segment(0, n)), IntArray(0))
        assertThat(floor.v[n / 2]).isEqualTo(0.0025f) // 0,020 g / 8,0
    }
}
