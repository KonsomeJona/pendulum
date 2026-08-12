package com.pendulum.algo.dsp

import com.pendulum.algo.model.GainSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class CalibrationTest {

    private val fs = 50.0

    /**
     * **Part A has gone, and this file therefore no longer tests it.**
     *
     * Two tests lived here: the sensor's static autocalibration recovered a known offset and a
     * known gain, and refused to conclude on a night spent in a single orientation. They were
     * good. What they did not say is that `autocalibrate` had no production caller and that its
     * output, `NightCalibration.sensor`, had no reader: correcting it would have changed no
     * figure at all. Same criterion as for the guided ritual, same outcome.
     *
     * What remains below is part B, the only gain reference actually used.
     */

    /**
     * This test went through the guided ritual, which was removed for never having been wired up.
     * The guard rail it checks, on the other hand, has not gone away: a strap tightness that
     * changes from one night to the next shifts the mechanical gain, and two nights measured at
     * different gains are not comparable. It is therefore rewired onto the only reference that
     * remains.
     */
    @Test
    fun `a strap tightness that differs from one night to the next is flagged`() {
        val halfTheUsualGain = listOf(
            clm(peak = 0.180f, floor = 0.010f, gross = true),
            clm(peak = 0.190f, floor = 0.010f, gross = true),
            clm(peak = 0.185f, floor = 0.010f, gross = true),
        )
        val cal = Calibration.fromGrossBodyMovements(halfTheUsualGain, baselineGainG = 0.370f)

        assertThat(cal.outlierVsBaseline).isTrue()
        assertThat(cal.gainSource).isEqualTo(GainSource.GROSS_BODY)
    }

    @Test
    fun `an identical strap tightness from one night to the next raises nothing`() {
        val sameGain = listOf(
            clm(peak = 0.360f, floor = 0.010f, gross = true),
            clm(peak = 0.380f, floor = 0.010f, gross = true),
            clm(peak = 0.370f, floor = 0.010f, gross = true),
        )
        val cal = Calibration.fromGrossBodyMovements(sameGain, baselineGainG = 0.370f)

        assertThat(cal.outlierVsBaseline).isFalse()
    }

    @Test
    fun `gross body movements provide the gain reference`() {
        val clms = listOf(
            clm(peak = 0.360f, floor = 0.010f, gross = true),
            clm(peak = 0.380f, floor = 0.010f, gross = true),
            clm(peak = 0.400f, floor = 0.010f, gross = true),
            clm(peak = 0.050f, floor = 0.010f, gross = false),
        )
        val cal = Calibration.fromGrossBodyMovements(clms)
        assertThat(cal.gainSource).isEqualTo(GainSource.GROSS_BODY)
        assertThat(cal.gainCalG).isEqualTo(0.380f) // median of the GBM only
    }

    @Test
    fun `no reference available gives gainSource NONE`() {
        val cal = Calibration.fromGrossBodyMovements(emptyList())
        assertThat(cal.gainSource).isEqualTo(GainSource.NONE)
        assertThat(cal.gainCalG).isNaN()
    }

    private fun clm(peak: Float, floor: Float, gross: Boolean) = com.pendulum.algo.model.Clm(
        onsetIdx = 0, offsetIdx = 100, onsetMsRel = 0L, durationMs = 2000,
        peakAmpG = peak, medianAmpG = peak / 2f, noiseFloorG = floor,
        thresholdOnG = 0.08f, thresholdOffG = 0.025f,
        tiltChangeDeg = 0f, tiltExcursionDeg = 0f,
        flags = if (gross) com.pendulum.algo.model.ClmFlags.GROSS_BODY else 0,
        reject = null,
    )
}
