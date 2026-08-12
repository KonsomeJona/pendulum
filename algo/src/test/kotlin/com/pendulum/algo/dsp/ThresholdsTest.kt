package com.pendulum.algo.dsp

import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.Signal1D
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ThresholdsTest {

    private val p = ThresholdParams()

    @Test
    fun `the dominant term is traced sample by sample`() {
        // Noisy night: 8 x 5 mg = 40 mg, above the 20 mg absolute and the 12 % of 200 mg.
        assertThat(Thresholds.dominanceAt(0.005f, 0.200f, p)).isZero()
        // Very calm night: 8 x 1 mg = 8 mg, it is the absolute floor that commands.
        assertThat(Thresholds.dominanceAt(0.001f, 0.100f, p)).isEqualTo(ClmFlags.ABS_FLOOR_LIMITED)
        // Very tight strap: a dorsiflexion at 400 mg forces a 48 mg threshold.
        assertThat(Thresholds.dominanceAt(0.002f, 0.400f, p)).isEqualTo(ClmFlags.CAL_FLOOR_LIMITED)
    }

    @Test
    fun `the hysteresis is 3 2 whatever the dominant term`() {
        for (floor in listOf(0.0005f, 0.002f, 0.005f, 0.02f)) {
            for (gain in listOf(0f, 0.1f, 0.4f)) {
                val on = Thresholds.onAt(floor, gain, p)
                val off = Thresholds.offAt(floor, gain, p)
                assertThat(on / off).isCloseTo(3.2f, within(0.01f))
            }
        }
    }

    @Test
    fun `an absent calibration gain disables the third term without breaking the threshold`() {
        val on = Thresholds.onAt(0.005f, Float.NaN, p)
        assertThat(on).isEqualTo(0.040f)
    }

    @Test
    fun `the curves report the fraction of time capped`() {
        val n = 100
        val v = FloatArray(n) { if (it < 40) 0.0005f else 0.005f } // 40 % of a very calm night
        val curves = Thresholds.compute(Signal1D(50.0, 0L, v), 0.100f, p)
        assertThat(curves.limitedFraction()).isCloseTo(0.40, org.assertj.core.api.Assertions.within(1e-9))
        assertThat(curves.on.v[0]).isEqualTo(0.020f)
        assertThat(curves.on.v[99]).isEqualTo(0.040f)
    }
}
