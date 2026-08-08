package com.pendulum.algo.dsp

import com.pendulum.algo.model.GainSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class CalibrationTest {

    private val fs = 50.0

    /**
     * **Le volet A a disparu, et ce fichier ne le teste donc plus.**
     *
     * Deux tests vivaient ici : l'autocalibration statique du capteur retrouvait un offset et un
     * gain connus, et refusait de conclure sur une nuit passee dans une seule orientation. Ils
     * etaient bons. Ce qu'ils ne disaient pas, c'est que `autocalibrate` n'avait aucun appelant de
     * production et que sa sortie, `NightCalibration.sensor`, n'avait aucun lecteur : la corriger
     * n'aurait rien change a aucun chiffre. Meme critere que pour le rituel guide, meme issue.
     *
     * Ce qui subsiste ci-dessous est le volet B, le seul etalon de gain reellement utilise.
     */

    /**
     * Ce test passait par le rituel guide, qui a ete retire faute d'avoir jamais ete branche. Le
     * garde-fou qu'il verifie, lui, n'a pas disparu : un serrage de bracelet qui change d'une nuit
     * sur l'autre deplace le gain mecanique, et deux nuits mesurees a des gains differents ne sont
     * pas comparables. Il est donc rebranche sur le seul etalon qui subsiste.
     */
    @Test
    fun `un serrage de bracelet different d une nuit sur l autre est signale`() {
        val moitieDuGainHabituel = listOf(
            clm(peak = 0.180f, floor = 0.010f, gross = true),
            clm(peak = 0.190f, floor = 0.010f, gross = true),
            clm(peak = 0.185f, floor = 0.010f, gross = true),
        )
        val cal = Calibration.fromGrossBodyMovements(moitieDuGainHabituel, baselineGainG = 0.370f)

        assertThat(cal.outlierVsBaseline).isTrue()
        assertThat(cal.gainSource).isEqualTo(GainSource.GROSS_BODY)
    }

    @Test
    fun `un serrage identique d une nuit sur l autre ne leve rien`() {
        val memeGain = listOf(
            clm(peak = 0.360f, floor = 0.010f, gross = true),
            clm(peak = 0.380f, floor = 0.010f, gross = true),
            clm(peak = 0.370f, floor = 0.010f, gross = true),
        )
        val cal = Calibration.fromGrossBodyMovements(memeGain, baselineGainG = 0.370f)

        assertThat(cal.outlierVsBaseline).isFalse()
    }

    @Test
    fun `les mouvements corporels grossiers fournissent l etalon de gain`() {
        val clms = listOf(
            clm(peak = 0.360f, floor = 0.010f, gross = true),
            clm(peak = 0.380f, floor = 0.010f, gross = true),
            clm(peak = 0.400f, floor = 0.010f, gross = true),
            clm(peak = 0.050f, floor = 0.010f, gross = false),
        )
        val cal = Calibration.fromGrossBodyMovements(clms)
        assertThat(cal.gainSource).isEqualTo(GainSource.GROSS_BODY)
        assertThat(cal.gainCalG).isEqualTo(0.380f) // mediane des seuls GBM
    }

    @Test
    fun `aucun etalon disponible donne gainSource NONE`() {
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
