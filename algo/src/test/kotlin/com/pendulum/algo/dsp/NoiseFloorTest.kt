package com.pendulum.algo.dsp

import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NoiseFloorTest {

    private val fs = 50.0

    /** Synthetic envelope: constant floor + periodic bursts. */
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
    fun `the floor does not self-contaminate despite 13 percent of bursts`() {
        // Realistic duty cycle of PLMS at the ankle: 4.2 s of movement for 31.3 s of interval,
        // that is 13 % (Sforza 2005, cited in §1.3).
        val e = env(1200, floorG = 0.010, burstG = 0.200, burstSec = 4.2, periodSec = 31.3)
        val segs = listOf(Segment(0, e.n))
        val (floor, ext) = NoiseFloor.estimate(e, segs, IntArray(0))

        val mid = e.n / 2
        assertThat(floor.v[mid].toDouble()).isCloseTo(0.010, org.assertj.core.api.Assertions.within(0.0015))
        assertThat(ext[mid]).isFalse()

        // Without exclusion, the raw median of the window would be pulled upwards: we check
        // that the estimator does better than the naive median of the same window.
        val naive = Numeric.percentile(e.v, mid - 3000, mid + 3000, 50.0, FloatArray(6000))
        assertThat(floor.v[mid]).isLessThanOrEqualTo(naive)
    }

    @Test
    fun `a 20 second turn-over does not throw the estimator off`() {
        // This is the real estimator breaker (§1.3): 80 % contamination over a 25 s window, but
        // only 17 % over the 120 s of the specification.
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
    fun `a posture boundary stops the window from mixing two regimes`() {
        // Mechanical floor that jumps by a factor of 4 at the posture change.
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

        // Just AFTER the boundary, the partitioned estimator is already on the new regime at
        // 32 mg. The unpartitioned one stays stuck to the old 8 mg regime for a whole minute:
        // its threshold is four times too low, and that is exactly the shower of false positives
        // that follows every posture change.
        assertThat(withBoundary.v[boundary + 10].toDouble())
            .isCloseTo(0.032, org.assertj.core.api.Assertions.within(0.004))
        assertThat(without.v[boundary + 10].toDouble()).isLessThan(0.015)
    }

    @Test
    fun `the lagged causal mode never looks at the future`() {
        val n = (600 * fs).toInt()
        val v = FloatArray(n) { 0.010f }
        val jump = (300 * fs).toInt()
        for (i in jump until n) v[i] = 0.040f
        val e = Signal1D(fs, 0L, v)

        val cfg = NoiseFloorConfig(mode = FloorMode.CAUSAL_LAGGED)
        val (floor, _) = NoiseFloor.estimate(e, listOf(Segment(0, n)), IntArray(0), cfg)

        // A sample before the jump cannot know anything about it.
        assertThat(floor.v[jump - 1].toDouble()).isCloseTo(0.010, org.assertj.core.api.Assertions.within(1e-4))
        // And the lag of the estimator is quite real: 5 s after the jump, the floor has not
        // moved yet. This is the bias the provisional mode knowingly accepts (§3.7.1).
        assertThat(floor.v[jump + (4 * fs).toInt()].toDouble())
            .isCloseTo(0.010, org.assertj.core.api.Assertions.within(1e-3))
    }

    @Test
    fun `a window too poor in valid data raises FLOOR_EXTRAPOLATED`() {
        val n = (300 * fs).toInt()
        // Night almost entirely absent: a few valid samples drowned in the gaps.
        val v = FloatArray(n) { if (it % 100 == 0) 0.01f else Float.NaN }
        val e = Signal1D(fs, 0L, v)
        val (floor, ext) = NoiseFloor.estimate(e, listOf(Segment(0, n)), IntArray(0))
        assertThat(ext[n / 2]).isTrue()
        assertThat(floor.v[n / 2]).isNotNaN()
    }

    @Test
    fun `the floor is bounded by Theta_abs over k_on`() {
        val n = (300 * fs).toInt()
        val e = Signal1D(fs, 0L, FloatArray(n) { 1e-5f }) // unrealistically calm night
        val (floor, _) = NoiseFloor.estimate(e, listOf(Segment(0, n)), IntArray(0))
        assertThat(floor.v[n / 2]).isEqualTo(0.0025f) // 0.020 g / 8.0
    }
}
