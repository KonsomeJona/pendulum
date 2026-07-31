package com.pendulum.algo.dsp

import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.Signal1D
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ThresholdsTest {

    private val p = ThresholdParams()

    @Test
    fun `le terme dominant est trace echantillon par echantillon`() {
        // Nuit bruyante : 8 x 5 mg = 40 mg, au-dessus des 20 mg absolus et des 12 % de 200 mg.
        assertThat(Thresholds.dominanceAt(0.005f, 0.200f, p)).isZero()
        // Nuit tres calme : 8 x 1 mg = 8 mg, c'est le plancher absolu qui commande.
        assertThat(Thresholds.dominanceAt(0.001f, 0.100f, p)).isEqualTo(ClmFlags.ABS_FLOOR_LIMITED)
        // Bracelet tres serre : une dorsiflexion a 400 mg impose 48 mg de seuil.
        assertThat(Thresholds.dominanceAt(0.002f, 0.400f, p)).isEqualTo(ClmFlags.CAL_FLOOR_LIMITED)
    }

    @Test
    fun `l hysteresis vaut 3 2 quel que soit le terme dominant`() {
        for (floor in listOf(0.0005f, 0.002f, 0.005f, 0.02f)) {
            for (gain in listOf(0f, 0.1f, 0.4f)) {
                val on = Thresholds.onAt(floor, gain, p)
                val off = Thresholds.offAt(floor, gain, p)
                assertThat(on / off).isCloseTo(3.2f, within(0.01f))
            }
        }
    }

    @Test
    fun `un gain de calibration absent desactive le troisieme terme sans casser le seuil`() {
        val on = Thresholds.onAt(0.005f, Float.NaN, p)
        assertThat(on).isEqualTo(0.040f)
    }

    @Test
    fun `les courbes rapportent la fraction de temps plafonnee`() {
        val n = 100
        val v = FloatArray(n) { if (it < 40) 0.0005f else 0.005f } // 40 % de nuit tres calme
        val curves = Thresholds.compute(Signal1D(50.0, 0L, v), 0.100f, p)
        assertThat(curves.limitedFraction()).isCloseTo(0.40, org.assertj.core.api.Assertions.within(1e-9))
        assertThat(curves.on.v[0]).isEqualTo(0.020f)
        assertThat(curves.on.v[99]).isEqualTo(0.040f)
    }
}
