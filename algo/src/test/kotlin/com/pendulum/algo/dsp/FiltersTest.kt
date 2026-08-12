package com.pendulum.algo.dsp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FiltersTest {

    private val fs = 50.0

    @Test
    fun `the corner of a low-pass Butterworth is at minus 3 dB`() {
        val lp = Filters.butterLowpass(fs, 8.0, 2)
        assertThat(Filters.magnitudeAt(lp, 8.0)).isCloseTo(0.7071, within(1e-3))
        assertThat(Filters.magnitudeAt(lp, 0.5)).isCloseTo(1.0, within(2e-3))
    }

    @Test
    fun `the band-pass lets 2 Hz through and rejects posture and the top of the spectrum`() {
        val bp = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        assertThat(Filters.magnitudeAt(bp, 2.0)).isGreaterThan(0.95)
        // 0.25 Hz: the breathing band. The table in `docs/workings/ALGO-v2.md` §1.2 puts figures on both
        // options and **keeps order 2**: |H| = 0.243 (-12.3 dB) at order 2, 0.062 (-24.1 dB) at
        // order 4. Order 4 is explicitly rejected (posture ringing 2 s -> 4 s), and `hpOrder = 2`
        // is the value published in §6.1. So it is 0.243 that must be asserted here; requiring
        // < 0.07 amounted to asserting the "order 4" row on an order 2 filter.
        assertThat(Filters.magnitudeAt(bp, 0.25)).isCloseTo(0.243, within(2e-3))
        // The gain at 0.7 Hz (component of a slow CLM) must stay nearly intact. Exact value at
        // order 2: (f/fc)^2 / sqrt(1 + (f/fc)^4) = 1.96 / 2.2004 = 0.891. The §1.2 table carried
        // 0.915 in that cell; it was wrong, and the table has been corrected (07-validation.md
        // §5.4), which allows the exact value to be asserted rather than a loose bound.
        assertThat(Filters.magnitudeAt(bp, 0.7)).isCloseTo(0.891, within(2e-3))
        // 20 Hz: top of the spectrum. No cell of the §1.2 table puts a figure on this point; the
        // 0.03 bound was a round number, and the published filter does not hold it. Exact value
        // of the order 2 digital low-pass obtained by prewarped bilinear transform:
        //   |H| = 1 / sqrt(1 + (tan(pi.20/50) / tan(pi.8/50))^4) = 1 / sqrt(1 + 5.598^4) = 0.0319.
        // This is not the analogue value (0.158): it is the frequency warping of the bilinear
        // transform near Nyquist, and it works **in favour** of the rejection. The bound is
        // therefore relaxed to 0.035, which still exposes a design regression without
        // over-constraining.
        assertThat(Filters.magnitudeAt(bp, 20.0)).isLessThan(0.035)
    }

    @Test
    fun `resetToDc removes the 1 g transient of the gravity low-pass`() {
        val lp = Filters.butterLowpass(fs, 0.15, 2)
        lp.resetToDc(1.0f)
        // Constant input at 1 g: the output must read 1 g from the very first sample.
        for (i in 0 until 100) {
            assertThat(lp.step(1.0f).toDouble()).isCloseTo(1.0, within(1e-6))
        }

        val naive = Filters.butterLowpass(fs, 0.15, 2)
        naive.reset()
        // Without priming, the first sample is nearly zero: a transient the size of gravity
        // itself, that is 5 to 30 times the amplitude of a real CLM.
        assertThat(naive.step(1.0f)).isLessThan(0.01f)
    }

    @Test
    fun `streaming filtering is identical whatever the split into blocks`() {
        val n = 3000
        val x = FloatArray(n) { 1.0f + 0.05f * sin(2 * PI * 2.0 * it / fs).toFloat() }

        val whole = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        whole.resetToDc(1.0f)
        val ref = whole.process(x)

        // Same filter, same state, but fed in blocks of 512 samples.
        val streamed = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        streamed.resetToDc(1.0f)
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val end = minOf(n, i + 512)
            for (k in i until end) out[k] = streamed.step(x[k])
            i = end
        }
        assertThat(out).isEqualTo(ref) // bit-identical: the state is never lost
    }

    @Test
    fun `resetting the filter on every block manufactures a periodic artefact`() {
        // This is pitfall no. 3 of v1: the transient repeats at the block rate and looks like a
        // perfect PLM series. The test checks that we know how to measure it, hence that the
        // stateful implementation is not a decorative precaution.
        val n = 3000
        val blockLen = 512
        val x = FloatArray(n) { 1.0f + 0.05f * sin(2 * PI * 2.0 * it / fs).toFloat() }

        val whole = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        whole.resetToDc(1.0f)
        val ref = whole.process(x)

        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val end = minOf(n, i + blockLen)
            val perBlock = Filters.butterBandpass(fs, 0.50, 8.0, 2)
            perBlock.reset() // zero state on every block: the mistake not to make
            for (k in i until end) out[k] = perBlock.step(x[k])
            i = end
        }
        // The maximum error is of the same order as gravity itself, that is tens of times the
        // amplitude of the useful signal (50 mg here).
        var maxErr = 0.0
        for (k in 0 until n) maxErr = maxOf(maxErr, abs(out[k] - ref[k]).toDouble())
        assertThat(maxErr).isGreaterThan(0.5)
    }

    @Test
    fun `snapshot and restore make the filter resumable identically`() {
        val a = Filters.butterHighpass(fs, 0.5, 2)
        a.resetToDc(1f)
        repeat(200) { a.step(1f + 0.01f * it) }
        val state = a.snapshot()

        val b = Filters.butterHighpass(fs, 0.5, 2)
        b.restore(state)
        for (k in 0 until 100) {
            val v = 2f + 0.01f * k
            assertThat(b.step(v)).isEqualTo(a.step(v))
        }
    }

    @Test
    fun `the announced settling time stays under the warmup of the specification`() {
        val bp = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        assertThat(bp.settlingTimeSec).isLessThan(5.0) // warmupSec = 5.0 s (§6.1)
    }
}
