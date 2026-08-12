package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.PublicationGate
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** Tests of step 7: hourly counts, respiratory bracketing, publication gate. */
class IndicesTest {

    private val pi = PiResult(periodicityIndex = 0.61, valid = true, totalIntervals = 99, lmRatePerHour = 14.3)
    private val rhythm: RhythmResult = Rhythm.estimate(DoubleArray(0))

    private fun compute(
        clms: List<Clm>,
        series: List<PlmSeries>,
        mask: SleepMask,
        rule: SeriesRule = SeriesRule.AASM_V3,
        truncated: Boolean = false,
        truncatedSeriesDropped: Int = 0,
    ) = Plmi.compute(
        clms = clms,
        series = series,
        mask = mask,
        fsHz = FS_HZ,
        rule = rule,
        pi = pi,
        rhythm = rhythm,
        floorMode = FloorMode.BILATERAL,
        truncated = truncated,
        truncatedSeriesDropped = truncatedSeriesDropped,
        paramsHash = "test-hash",
    )

    @Test
    fun `the denominator is ANALYSABLE sleep, never raw TST`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)
        val mask = maskOf(tstMin = 420.0, analysableTstMin = 300.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), mask)

        assertThat(r.plmsCount).isEqualTo(10)
        assertThat(r.plmi).isCloseTo(2.0, within(1e-9))            // 10 / 5 analysable h
        assertThat(r.plmi).isNotEqualTo(10.0 / 7.0)                // and above all NOT 10 / 7 raw h
        assertThat(r.tstMin).isEqualTo(420.0)
        assertThat(r.analysableTstMin).isEqualTo(300.0)
    }

    @Test
    fun `the second index is referred to the time spent in bed`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 12)
        val mask = maskOf(analysableTstMin = 300.0, analysableSptMin = 360.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 11)), mask)

        assertThat(r.plmiSpt).isCloseTo(2.0, within(1e-9))          // 12 / 6 h of analysable SPT
        assertThat(r.plmi).isCloseTo(2.4, within(1e-9))             // 12 / 5 h of analysable TST
    }

    @Test
    fun `without a denominator, no rate is invented`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)
        val mask = maskOf(analysableTstMin = 0.0, tstMin = 0.0, analysableSptMin = 0.0, sptMin = 0.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), mask)

        assertThat(r.plmi.isNaN()).isTrue()
        assertThat(r.plmiSpt.isNaN()).isTrue()
        assertThat(r.gate).isEqualTo(PublicationGate.NO_PLMI)
    }

    @Test
    fun `isolated movements, in-series movements, and short intervals are counted separately`() {
        val inSeries = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)
        val isolated = clmsAtSec(20_000.0, 20_100.0, 20_200.0)
        val clms = inSeries + isolated

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), maskOf(analysableTstMin = 300.0))

        assertThat(r.plmsCount).isEqualTo(10)
        assertThat(r.isolatedCount).isEqualTo(3)
        assertThat(r.plmwCount).isEqualTo(0)
    }

    @Test
    fun `the short-interval lower bound depends on the rule set`() {
        // Intervals of 7 s: above the AASM bound (5 s), below the WASM bound (10 s).
        val clms = clmsAtSec(600.0, 607.0, 629.0, 651.0, 673.0)
        val mask = maskOf(analysableTstMin = 300.0)

        val aasm = compute(clms, emptyList(), mask, rule = SeriesRule.AASM_V3)
        val wasm = compute(clms, emptyList(), mask, rule = SeriesRule.WASM_2016)

        assertThat(aasm.shortImiCount).isEqualTo(0)
        assertThat(wasm.shortImiCount).isEqualTo(1)
        assertThat(Plmi.imiMinSecOf(SeriesRule.AASM_V3)).isEqualTo(5.0)
        assertThat(Plmi.imiMinSecOf(SeriesRule.WASM_2016)).isEqualTo(10.0)
    }

    @Test
    fun `the PLMW is referred to the WASO, not to the TST`() {
        val windows = listOf(
            SleepWindow(0L, 3_600_000L, Stage.SLEEP),
            SleepWindow(3_600_000L, 7_200_000L, Stage.AWAKE_IN_BED),
            SleepWindow(7_200_000L, 25_200_000L, Stage.SLEEP),
        )
        val clms = clmsEvery(startSec = 4_000.0, stepSec = 22.0, count = 5)
        val mask = maskOf(
            windows = windows,
            sptMin = 480.0,
            analysableSptMin = 480.0,
            wasoMin = 60.0,
            tstMin = 420.0,
            analysableTstMin = 420.0,
        )

        val r = compute(clms, listOf(seriesOver(clms, 0, 4)), mask)

        assertThat(r.plmsCount).isEqualTo(0)
        assertThat(r.plmwCount).isEqualTo(5)
        assertThat(r.plmw).isCloseTo(5.0, within(1e-9))     // 5 events / 1 h of analysable WASO
        assertThat(r.plmi).isCloseTo(0.0, within(1e-12))
    }

    @Test
    fun `the split-half reports the two halves of the night separately`() {
        // SPT = [0, 420 min], midpoint at 210 min. 6 events before, 4 after.
        val before = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 6)
        val after = clmsEvery(startSec = 13_000.0, stepSec = 22.0, count = 4)
        val clms = before + after
        val mask = maskOf(tstMin = 420.0, analysableTstMin = 300.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 5), seriesOver(clms, 6, 9)), mask)

        // Each half carries 210 min of sleep, scaled by the 300/420 analysable coverage = 2.5 h.
        assertThat(r.plmiFirstHalf).isCloseTo(6.0 / 2.5, within(1e-9))
        assertThat(r.plmiSecondHalf).isCloseTo(4.0 / 2.5, within(1e-9))
    }

    @Test
    fun `the IMI histogram bears on every sleep CLM, bins of 2 s`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), maskOf(analysableTstMin = 300.0))

        assertThat(r.imiHistogram.size).isEqualTo(50)
        assertThat(r.imiBinEdgesSec.size).isEqualTo(51)
        assertThat(r.imiBinEdgesSec[11]).isEqualTo(22.0f)
        assertThat(r.imiHistogram[11]).isEqualTo(9)
        assertThat(r.imiHistogram.sum()).isEqualTo(9)
    }

    @Test
    fun `the respiratory worst case removes the series whose median IMI is in the apnoeic band`() {
        val plms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)          // PLMS rhythm
        val apnoeic = clmsEvery(startSec = 5_000.0, stepSec = 30.0, count = 10)     // 25-45 s band
        val clms = plms + apnoeic
        val mask = maskOf(analysableTstMin = 300.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9), seriesOver(clms, 10, 19)), mask)

        assertThat(r.plmi).isCloseTo(4.0, within(1e-9))                 // 20 / 5 h
        assertThat(r.plmiRespWorstCase).isCloseTo(2.0, within(1e-9))    // 10 / 5 h
        // The SPREAD between the two bounds is the uncertainty indicator to display: without a
        // respiratory channel, nothing lets the true value be located inside the interval.
        assertThat(Plmi.respiratoryBiasSpread(r)).isCloseTo(2.0, within(1e-9))
    }

    @Test
    fun `the independence of the denominator is propagated as it is`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)
        val series = listOf(seriesOver(clms, 0, 9))

        for (independence in DenominatorIndependence.entries) {
            val mask = maskOf(
                analysableTstMin = 420.0,
                source = if (independence == DenominatorIndependence.CIRCULAR) {
                    MaskSource.ACCEL_IMMOBILITY
                } else {
                    MaskSource.HEALTH_CONNECT
                },
                independence = independence,
            )
            val r = compute(clms, series, mask)
            assertThat(r.independence).isEqualTo(independence)
        }
    }

    @Test
    fun `a circular denominator cannot carry the primary result`() {
        val mask = maskOf(
            analysableTstMin = 420.0,
            source = MaskSource.ACCEL_IMMOBILITY,
            independence = DenominatorIndependence.CIRCULAR,
        )

        assertThat(Plmi.canCarryPrimaryResult(mask)).isFalse()
        assertThat(Plmi.publicationGate(mask, truncated = false))
            .isEqualTo(PublicationGate.TRUNCATED_NO_TREND)
        assertThat(Plmi.canCarryPrimaryResult(maskOf())).isTrue()
    }

    @Test
    fun `the publication gate follows the hard-coded thresholds of paragraph 3-7-2`() {
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 420.0), false))
            .isEqualTo(PublicationGate.FULL)
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 240.0), false))
            .isEqualTo(PublicationGate.FULL)
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 200.0), false))
            .isEqualTo(PublicationGate.TRUNCATED_NO_TREND)
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 420.0), true))
            .isEqualTo(PublicationGate.TRUNCATED_NO_TREND)
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 179.9), false))
            .isEqualTo(PublicationGate.NO_PLMI)
    }

    @Test
    fun `a non-converged accelerometer mask forbids any PLMI`() {
        val mask = maskOf(
            analysableTstMin = 420.0,
            source = MaskSource.ACCEL_IMMOBILITY,
            independence = DenominatorIndependence.CIRCULAR,
            fixedPointConverged = false,
        )

        assertThat(Plmi.publicationGate(mask, false))
            .isEqualTo(PublicationGate.NO_PLMI)
    }

    @Test
    fun `a night truncated at 3 h publishes no PLMI but keeps the PI and the count`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 40)
        val mask = maskOf(tstMin = 170.0, analysableTstMin = 170.0, sptMin = 180.0, analysableSptMin = 180.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 39)), mask, truncated = true, truncatedSeriesDropped = 2)

        assertThat(r.gate).isEqualTo(PublicationGate.NO_PLMI)
        assertThat(r.plmsCount).isEqualTo(40)
        assertThat(r.pi.valid).isTrue()
        assertThat(r.truncatedSeriesDropped).isEqualTo(2)
    }

    @Test
    fun `rejected CLM count nowhere`() {
        val clms = listOf(
            clmAt(600_000L),
            clmAt(622_000L, reject = com.pendulum.algo.model.ClmRejectReason.POSTURAL),
            clmAt(644_000L),
        )

        val r = compute(clms, emptyList(), maskOf(analysableTstMin = 300.0))

        assertThat(r.isolatedCount).isEqualTo(2)
        // The two retained CLM are 44 s apart: a single interval, in the [44, 46) bin.
        assertThat(r.imiHistogram[22]).isEqualTo(1)
        assertThat(r.imiHistogram.sum()).isEqualTo(1)
    }

    @Test
    fun `the result metadata are those of the mask and of the parameters`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), maskOf(), rule = SeriesRule.WASM_2016)

        assertThat(r.rule).isEqualTo(SeriesRule.WASM_2016)
        assertThat(r.maskSource).isEqualTo(MaskSource.HEALTH_CONNECT)
        assertThat(r.floorMode).isEqualTo(FloorMode.BILATERAL)
        assertThat(r.paramsHash).isEqualTo("test-hash")
        assertThat(r.pi).isEqualTo(pi)
        assertThat(r.rhythm.valid).isFalse()
    }
}
