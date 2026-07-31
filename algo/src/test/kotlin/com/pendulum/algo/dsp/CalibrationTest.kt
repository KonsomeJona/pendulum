package com.pendulum.algo.dsp

import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.TriAxial
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class CalibrationTest {

    private val fs = 50.0

    @Test
    fun `l autocalibration retrouve un offset et un gain connus`() {
        val trueOffset = doubleArrayOf(0.020, -0.010, 0.030)
        val trueScale = doubleArrayOf(1.010, 0.990, 1.020)
        val rnd = java.util.Random(1)
        val winSamples = 500 // 10 s
        val nWin = 40
        val n = winSamples * nWin
        val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
        for (w in 0 until nWin) {
            // Orientation aleatoire mais reproductible : la couverture de la sphere est la
            // condition d'existence du probleme (§3.3, volet A, point 2).
            var ux = rnd.nextGaussian(); var uy = rnd.nextGaussian(); var uz = rnd.nextGaussian()
            val nrm = sqrt(ux * ux + uy * uy + uz * uz)
            ux /= nrm; uy /= nrm; uz /= nrm
            for (i in 0 until winSamples) {
                val k = w * winSamples + i
                x[k] = (ux / trueScale[0] + trueOffset[0] + rnd.nextGaussian() * 0.002).toFloat()
                y[k] = (uy / trueScale[1] + trueOffset[1] + rnd.nextGaussian() * 0.002).toFloat()
                z[k] = (uz / trueScale[2] + trueOffset[2] + rnd.nextGaussian() * 0.002).toFloat()
            }
        }
        val raw = TriAxial(fs, 0L, x, y, z)
        val cal = Calibration.autocalibrate(raw, listOf(Segment(0, n)))

        assertThat(cal.valid).isTrue()
        for (a in 0..2) {
            assertThat(cal.scale[a].toDouble()).isCloseTo(trueScale[a], within(0.01))
            assertThat(cal.offsetG[a].toDouble()).isCloseTo(trueOffset[a], within(0.01))
        }
        assertThat(cal.residualG.toDouble()).isLessThan(0.01)

        // Apres correction, la norme vaut 1 g.
        val fixed = Calibration.apply(raw, cal)
        val nrm = sqrt(
            (fixed.x[10] * fixed.x[10] + fixed.y[10] * fixed.y[10] + fixed.z[10] * fixed.z[10]).toDouble(),
        )
        assertThat(nrm).isCloseTo(1.0, within(0.01))
    }

    @Test
    fun `une nuit passee dans une seule orientation fait echouer l autocalibration`() {
        val n = 20000
        val rnd = java.util.Random(2)
        val x = FloatArray(n) { (rnd.nextGaussian() * 0.002).toFloat() }
        val y = FloatArray(n) { (rnd.nextGaussian() * 0.002).toFloat() }
        val z = FloatArray(n) { (1.0 + rnd.nextGaussian() * 0.002).toFloat() }
        val cal = Calibration.autocalibrate(TriAxial(fs, 0L, x, y, z), listOf(Segment(0, n)))
        // Le probleme est mal pose : mieux vaut ne rien corriger que corriger n'importe quoi.
        assertThat(cal.valid).isFalse()
        assertThat(cal.scale).containsExactly(1f, 1f, 1f)
    }

    @Test
    fun `le rituel mesure le gain aux instants du metronome et non a ceux qu il detecte`() {
        // 30 s d'immobilite a 5 mg, puis 10 dorsiflexions a 3 s d'intervalle culminant a 300 mg.
        val n = (70 * fs).toInt()
        val v = FloatArray(n) { 0.005f }
        val kicks = LongArray(10) { 30_000L + it * 3_000L }
        for (t in kicks) {
            val from = (t * fs / 1000.0).toInt()
            for (i in from until from + 25) v[i] = 0.300f
        }
        val cal = Calibration.fromRitual(Signal1D(fs, 0L, v), 0L, 30_000L, kicks)

        assertThat(cal.gainSource).isEqualTo(GainSource.RITUAL)
        assertThat(cal.gainCalG).isEqualTo(0.300f)
        assertThat(cal.floorCalG).isEqualTo(0.005f)
        assertThat(cal.snrCal.toDouble()).isCloseTo(60.0, within(0.1))
        assertThat(cal.outlierVsBaseline).isFalse()
    }

    @Test
    fun `un serrage de bracelet different d une nuit sur l autre est signale`() {
        val n = (70 * fs).toInt()
        val v = FloatArray(n) { 0.005f }
        val kicks = LongArray(10) { 30_000L + it * 3_000L }
        for (t in kicks) {
            val from = (t * fs / 1000.0).toInt()
            for (i in from until from + 25) v[i] = 0.150f // moitie du gain habituel
        }
        val cal = Calibration.fromRitual(Signal1D(fs, 0L, v), 0L, 30_000L, kicks, baselineGainG = 0.300f)
        assertThat(cal.outlierVsBaseline).isTrue()
    }

    @Test
    fun `sans rituel le repli sur les mouvements corporels grossiers fournit un etalon`() {
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
