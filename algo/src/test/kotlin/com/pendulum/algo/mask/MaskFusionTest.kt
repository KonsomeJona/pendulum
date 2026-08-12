package com.pendulum.algo.mask

import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

private const val MIN = 60_000L
private const val HOUR = 3_600_000L

/**
 * Reference accelerometer mask: 8 h in bed, a 20 min wake at 3 in the morning.
 * Built by hand rather than by [ImmobilityMask]: this file tests the fusion, not the scoring.
 */
private fun accelMask(
    independence: DenominatorIndependence = DenominatorIndependence.CIRCULAR,
): SleepMask = SleepMask(
    windows = listOf(
        SleepWindow(0L, 3 * HOUR, Stage.SLEEP),
        SleepWindow(3 * HOUR, 3 * HOUR + 20 * MIN, Stage.AWAKE_IN_BED),
        SleepWindow(3 * HOUR + 20 * MIN, 8 * HOUR, Stage.SLEEP),
    ),
    source = MaskSource.ACCEL_IMMOBILITY,
    sptMin = 480.0,
    tstMin = 460.0,
    wasoMin = 20.0,
    analysableTstMin = 437.0,
    analysableSptMin = 456.0,
    corrected = false,
    lagAppliedMs = 0L,
    independence = independence,
    fixedPointConverged = true,
)

private fun shifted(windows: List<SleepWindow>, byMs: Long): List<SleepWindow> =
    windows.map { SleepWindow(it.startMsRel + byMs, it.endMsRel + byMs, it.stage) }

class MaskFusionTest {

    /**
     * The realignment must recover an injected lag. Two watches, two clocks: without this
     * correction, two minutes of difference are enough to tip movements to either side of sleep
     * onset, and so to charge them to the wrong stage.
     */
    @Test
    fun `lag search - an injected lag is recovered`() {
        val accel = accelMask()
        val hc = shifted(accel.windows, -2 * MIN)

        val agreement = MaskFusion.align(accel, hc)

        assertThat(agreement.bestLagMs).isEqualTo(2 * MIN)
        assertThat(agreement.kappa).isGreaterThan(0.95)
        assertThat(agreement.overlapPct).isGreaterThan(95.0)
        assertThat(agreement.tstDeltaMin).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `lag search - at equal agreement the smallest lag wins`() {
        val accel = accelMask()

        val agreement = MaskFusion.align(accel, accel.windows)

        assertThat(agreement.bestLagMs).isEqualTo(0L)
        assertThat(agreement.kappa).isCloseTo(1.0, within(1e-9))
        assertThat(agreement.overlapPct).isCloseTo(100.0, within(1e-9))
    }

    @Test
    fun `a lag greater than five minutes is suspect`() {
        val accel = accelMask()
        val hc = shifted(accel.windows, -7 * MIN)

        val agreement = MaskFusion.align(accel, hc)

        assertThat(agreement.bestLagMs).isEqualTo(7 * MIN)
        assertThat(MaskFusion.lagSuspect(agreement)).isTrue()
    }

    @Test
    fun `no agreement when one of the masks is empty`() {
        val agreement = MaskFusion.align(accelMask(), emptyList())

        assertThat(agreement.kappa).isNaN()
        assertThat(agreement.bestLagMs).isEqualTo(0L)
    }

    // --- Denominator independence -------------------------------------------------------------

    @Test
    fun `independence - Health Connect breaks the circularity`() {
        val hc = MaskFusion.fromHealthConnect(accelMask().windows)

        assertThat(hc.source).isEqualTo(MaskSource.HEALTH_CONNECT)
        assertThat(hc.independence).isEqualTo(DenominatorIndependence.INDEPENDENT_HC)
        assertThat(hc.tstMin).isCloseTo(460.0, within(1e-9))
        assertThat(hc.sptMin).isCloseTo(480.0, within(1e-9))
    }

    @Test
    fun `independence - the diary gives a denominator wholly independent of the signal`() {
        val diary = MaskFusion.fromDiary(DiaryWindow(0L, 8 * HOUR))

        assertThat(diary.source).isEqualTo(MaskSource.DIARY)
        assertThat(diary.independence).isEqualTo(DenominatorIndependence.INDEPENDENT_DIARY)
        // Time in bed overestimates the TST: the index comes out deflated, a cautious bias AND
        // independent of the number of movements — that is precisely what is being bought.
        assertThat(diary.tstMin).isCloseTo(480.0, within(1e-9))
        assertThat(diary.wasoMin).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `fusion - HC takes priority, realigned, and independent`() {
        val accel = accelMask()
        val hc = shifted(accel.windows, -2 * MIN)

        val fused = MaskFusion.fuse(accel, hc, diary = null)

        assertThat(fused.source).isEqualTo(MaskSource.FUSED)
        assertThat(fused.independence).isEqualTo(DenominatorIndependence.INDEPENDENT_HC)
        assertThat(fused.lagAppliedMs).isEqualTo(2 * MIN)
        assertThat(fused.windows.first().startMsRel).isEqualTo(0L)
        // The analysable coverage rate is a property of the RECORDING: it carries over.
        assertThat(fused.analysableSptMin / fused.sptMin).isCloseTo(456.0 / 480.0, within(1e-9))
    }

    @Test
    fun `fusion - without HC the diary takes over`() {
        val fused = MaskFusion.fuse(accelMask(), hc = null, diary = DiaryWindow(0L, 8 * HOUR))

        assertThat(fused.source).isEqualTo(MaskSource.FUSED)
        assertThat(fused.independence).isEqualTo(DenominatorIndependence.INDEPENDENT_DIARY)
        assertThat(fused.tstMin).isCloseTo(480.0, within(1e-9))
    }

    /**
     * Non-negotiable rule of `SPEC-v2.md` §2.3: without an independent source, there is nothing to
     * fuse and the accelerometer mask is returned **as it is**. Renaming it `FUSED` would let a
     * fusion be believed in, and a disguised `CIRCULAR` would end up carrying a primary result.
     */
    @Test
    fun `fusion - without HC or diary the mask stays accelerometer and circular`() {
        val accel = accelMask()

        val fused = MaskFusion.fuse(accel, hc = null, diary = null)

        assertThat(fused).isSameAs(accel)
        assertThat(fused.source).isEqualTo(MaskSource.ACCEL_IMMOBILITY)
        assertThat(fused.independence).isEqualTo(DenominatorIndependence.CIRCULAR)
    }

    @Test
    fun `fusion - the diary bounds the HC windows to the time spent in bed`() {
        val accel = accelMask()
        val hc = accel.windows

        val fused = MaskFusion.fuse(accel, hc, diary = DiaryWindow(HOUR, 7 * HOUR))

        assertThat(fused.windows.minOf { it.startMsRel }).isEqualTo(HOUR)
        assertThat(fused.windows.maxOf { it.endMsRel }).isEqualTo(7 * HOUR)
        assertThat(fused.sptMin).isCloseTo(360.0, within(1e-9))
    }

    // --- Learned correction -------------------------------------------------------------------

    @Test
    fun `correction - fewer than three nights, no correction`() {
        val fit = MaskFusion.fitCorrection(listOf(400.0 to 460.0, 380.0 to 440.0))

        assertThat(fit.valid).isFalse()
        assertThat(fit.nNights).isEqualTo(2)
    }

    @Test
    fun `correction - between three and five nights, median of the ratio`() {
        val fit = MaskFusion.fitCorrection(listOf(400.0 to 440.0, 300.0 to 360.0, 200.0 to 220.0))

        assertThat(fit.valid).isTrue()
        assertThat(fit.beta).isEqualTo(0.0)
        assertThat(fit.alpha).isCloseTo(1.1, within(1e-9)) // median of {1.10 ; 1.20 ; 1.10}
    }

    @Test
    fun `correction - at least five nights, Theil-Sen recovers the line`() {
        val pairs = (0 until 6).map { val x = 300.0 + 20.0 * it; x to (1.2 * x + 15.0) }

        val fit = MaskFusion.fitCorrection(pairs)

        assertThat(fit.alpha).isCloseTo(1.2, within(1e-9))
        assertThat(fit.beta).isCloseTo(15.0, within(1e-6))
        assertThat(fit.valid).isTrue()
    }

    @Test
    fun `correction - an outlier night does not tip the slope`() {
        val clean = (0 until 6).map { val x = 300.0 + 20.0 * it; x to (1.2 * x + 15.0) }
        val fit = MaskFusion.fitCorrection(clean + listOf(340.0 to 900.0))

        assertThat(fit.alpha).isCloseTo(1.2, within(0.15))
    }

    /**
     * Realigning the mean of an estimator does not make it independent of what it measures: the
     * night-to-night feedback stays whole, so `independence` does not move one iota.
     */
    @Test
    fun `applied correction - the denominator stays CIRCULAR`() {
        val accel = accelMask()
        val fit = MaskFusion.fitCorrection((0 until 6).map { 460.0 - it to 470.0 - it })

        val corrected = MaskFusion.applyCorrection(accel, fit)

        assertThat(corrected.corrected).isTrue()
        assertThat(corrected.independence).isEqualTo(DenominatorIndependence.CIRCULAR)
        assertThat(corrected.tstMin).isGreaterThan(accel.tstMin)
        assertThat(corrected.wasoMin).isCloseTo(corrected.sptMin - corrected.tstMin, within(1e-9))
        // The published denominator follows the correction in the same ratio as the raw TST.
        assertThat(corrected.analysableTstMin / accel.analysableTstMin)
            .isCloseTo(corrected.tstMin / accel.tstMin, within(1e-9))
    }

    @Test
    fun `invalid correction - the mask is returned intact`() {
        val accel = accelMask()

        val out = MaskFusion.applyCorrection(accel, MaskFusion.fitCorrection(listOf(400.0 to 440.0)))

        assertThat(out).isSameAs(accel)
        assertThat(out.corrected).isFalse()
    }

    @Test
    fun `correction - the corrected TST never exceeds the SPT`() {
        val accel = accelMask()
        val huge = TstCorrection(alpha = 2.0, beta = 0.0, nNights = 6, valid = true)

        val corrected = MaskFusion.applyCorrection(accel, huge)

        assertThat(corrected.tstMin).isEqualTo(accel.sptMin)
        assertThat(corrected.wasoMin).isEqualTo(0.0)
    }
}
