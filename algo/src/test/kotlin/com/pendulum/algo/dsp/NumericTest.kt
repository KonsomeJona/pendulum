package com.pendulum.algo.dsp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class NumericTest {

    @Test
    fun `percentile ignore les NaN et interpole entre statistiques d ordre`() {
        val v = floatArrayOf(1f, Float.NaN, 2f, 3f, Float.NaN, 4f)
        val scratch = FloatArray(v.size)
        // Valeurs valides : 1 2 3 4. Mediane = 2,5 par interpolation.
        assertThat(Numeric.percentile(v, 0, v.size, 50.0, scratch)).isEqualTo(2.5f)
        assertThat(Numeric.percentile(v, 0, v.size, 0.0, scratch)).isEqualTo(1f)
        assertThat(Numeric.percentile(v, 0, v.size, 100.0, scratch)).isEqualTo(4f)
        // p25 sur 4 points : h = 3*0,25 = 0,75 -> 1 + 0,75*(2-1) = 1,75.
        assertThat(Numeric.percentile(v, 0, v.size, 25.0, scratch)).isEqualTo(1.75f)
    }

    @Test
    fun `une fenetre entierement absente renvoie NaN et non zero`() {
        val v = floatArrayOf(Float.NaN, Float.NaN)
        assertThat(Numeric.percentile(v, 0, 2, 50.0, FloatArray(2))).isNaN()
        val dst = FloatArray(2)
        Numeric.movingRms(v, 0, 2, 3, dst)
        assertThat(dst[0]).isNaN()
    }

    @Test
    fun `le RMS glissant est tronque aux bornes et normalise par les echantillons valides`() {
        val v = floatArrayOf(3f, 4f, 0f, 0f)
        val dst = FloatArray(4)
        Numeric.movingRms(v, 0, 4, 3, dst)
        // i=0 : fenetre tronquee {3,4} -> sqrt((9+16)/2) = 3,5355
        assertThat(dst[0].toDouble()).isCloseTo(3.5355, within(1e-3))
        // i=1 : {3,4,0} -> sqrt(25/3) = 2,8868
        assertThat(dst[1].toDouble()).isCloseTo(2.8868, within(1e-3))
    }

    @Test
    fun `la mediane ponderee suit les poids et non le nombre de valeurs`() {
        // Trois blocs a 49 Hz de 10 echantillons, un bloc a 50 Hz de 500 echantillons :
        // c'est le gros bloc qui doit l'emporter.
        val fs = doubleArrayOf(49.0, 49.0, 49.0, 50.0)
        val w = doubleArrayOf(10.0, 10.0, 10.0, 500.0)
        assertThat(Numeric.weightedMedian(fs, w)).isEqualTo(50.0)
    }

    @Test
    fun `la MAD resiste a une contamination haute massive`() {
        val v = FloatArray(100) { if (it < 70) 1f else 100f }
        assertThat(Numeric.median(v)).isEqualTo(1f)
        assertThat(Numeric.mad(v)).isEqualTo(0f)
    }

    @Test
    fun `le quickselect reste correct sur une entree deja triee`() {
        // Cas pathologique redoute : une fenetre de plancher quasi triee.
        val a = FloatArray(1000) { it.toFloat() }
        assertThat(Numeric.selectInPlace(a.copyOf(), 1000, 0)).isEqualTo(0f)
        assertThat(Numeric.selectInPlace(a.copyOf(), 1000, 999)).isEqualTo(999f)
        assertThat(Numeric.selectInPlace(a.copyOf(), 1000, 500)).isEqualTo(500f)
    }
}
