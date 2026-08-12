package com.pendulum.algo.dsp

import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.TriAxial
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class EnvelopeTest {

    private val fs = 50.0

    @Test
    fun `the gravity-motion-magnitude chain is invariant under rotation of the strap`() {
        val n = 3000
        val rnd = java.util.Random(42)
        val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
        for (i in 0 until n) {
            val burst = if (i in 1000..1200) 0.15 * sin(2 * PI * 2.0 * i / fs) else 0.0
            x[i] = (0.0 + burst + 0.002 * rnd.nextGaussian()).toFloat()
            y[i] = (0.3 + 0.002 * rnd.nextGaussian()).toFloat()
            z[i] = (0.954 + 0.002 * rnd.nextGaussian()).toFloat()
        }
        val raw = TriAxial(fs, 0L, x, y, z)

        // Constant rotation of 37 degrees about the z axis: the case is simply placed
        // differently on the ankle from one night to the next.
        val a = Math.toRadians(37.0)
        val rx = FloatArray(n); val ry = FloatArray(n); val rz = FloatArray(n)
        for (i in 0 until n) {
            rx[i] = (cos(a) * x[i] - sin(a) * y[i]).toFloat()
            ry[i] = (sin(a) * x[i] + cos(a) * y[i]).toFloat()
            rz[i] = z[i]
        }
        val rotated = TriAxial(fs, 0L, rx, ry, rz)

        val segs = listOf(Segment(0, n))
        val m1 = Envelope.magnitudeL2(Gravity.split(raw, segs).linear)
        val m2 = Envelope.magnitudeL2(Gravity.split(rotated, segs).linear)

        var maxRel = 0.0
        for (i in 500 until n) {
            val d = abs(m1.v[i] - m2.v[i]).toDouble()
            maxRel = maxOf(maxRel, d / maxOf(1e-6, m1.v[i].toDouble()))
        }
        // The filters being linear and identical on each axis, the rotation commutes with them:
        // only the Float rounding is left.
        assertThat(maxRel).isLessThan(1e-3)
    }

    @Test
    fun `the coarse envelope cancels the 2f ripple that the fine one lets through`() {
        // A synthetic CLM at 2 Hz. Rectified, a sinusoid oscillates at 4 Hz; a 0.15 s window
        // (7.5 samples) does not average out that ripple and fragments the event, whereas a
        // 0.50 s window does. This is the v1 bug (§0-b).
        val n = 2000
        val v = FloatArray(n) { 0.1f * sin(2 * PI * 2.0 * it / fs).toFloat() }
        val sig = com.pendulum.algo.model.Signal1D(fs, 0L, FloatArray(n) { abs(v[it]) })
        val dual = Envelope.dual(sig, listOf(Segment(0, n)))

        fun ripple(a: FloatArray): Double {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (i in 500 until 1500) { lo = minOf(lo, a[i].toDouble()); hi = maxOf(hi, a[i].toDouble()) }
            return (hi - lo) / hi
        }
        assertThat(ripple(dual.coarse.v)).isLessThan(0.05)
        assertThat(ripple(dual.fine.v)).isGreaterThan(0.30)
    }

    @Test
    fun `a window never crosses a segment boundary`() {
        val n = 400
        val v = FloatArray(n) { if (it < 200) 0.01f else 1.0f }
        val sig = com.pendulum.algo.model.Signal1D(fs, 0L, v)
        val out = Envelope.rms(sig, 0.50, listOf(Segment(0, 200), Segment(200, n)))
        // The last sample of the first segment must know nothing of the second.
        assertThat(out.v[199].toDouble()).isCloseTo(0.01, within(1e-6))
        assertThat(out.v[200].toDouble()).isCloseTo(1.0, within(1e-6))
    }
}
