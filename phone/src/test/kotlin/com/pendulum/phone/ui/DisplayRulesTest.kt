package com.pendulum.phone.ui

import com.pendulum.phone.ui.chart.EnvelopePyramid
import com.pendulum.phone.ui.chart.NightPoint
import com.pendulum.phone.ui.chart.PointState
import com.pendulum.phone.ui.chart.TrendChartSpec
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.RefusalReason
import com.pendulum.phone.ui.model.TrendUiState
import com.pendulum.phone.ui.model.WakingState
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The display rules that are code, checked as code.
 *
 * Each of these tests matches a sentence of `06-interface.md` that a screen review cannot guarantee
 * by eye: an axis truncated by 5 % does not show, nor does an interval that moves from one
 * recomposition to the next if one does not look twice.
 */
class DisplayRulesTest {

    // -------------------------------------------------------------------------------------
    // P5 — no average hides an extremum
    // -------------------------------------------------------------------------------------

    @Test
    fun `a single-sample peak survives a decimation of 4096`() {
        val n = 1_440_000
        val base = FloatArray(n) { 1f }
        base[723_456] = 61f // the isolated peak: 20 ms out of eight hours

        val pyramid = EnvelopePyramid(base)
        val columns = 1100
        val out = FloatArray(columns * 2)
        pyramid.fillMinMax(0, n, columns, out)

        // The effective decimation factor exceeds 1300; on an average, the peak would disappear
        // entirely. In min/max it can only come back up.
        val shownMax = (0 until columns).maxOf { out[it * 2 + 1] }
        assertThat(shownMax).isEqualTo(61f)
    }

    @Test
    fun `the decimation does not lose the minimum either`() {
        val base = FloatArray(100_000) { 5f }
        base[42_000] = 0.2f
        val out = FloatArray(200)
        EnvelopePyramid(base).fillMinMax(0, base.size, 100, out)
        val shownMin = (0 until 100).minOf { out[it * 2] }
        assertThat(shownMin).isEqualTo(0.2f)
    }

    // -------------------------------------------------------------------------------------
    // P5 — the trend axis starts at zero, whatever the data
    // -------------------------------------------------------------------------------------

    private fun spec(values: List<Float>) = TrendChartSpec(
        quantity = Aggregate.Quantity.RHYTHM_SECONDS,
        points = values.mapIndexed { i, v -> NightPoint("s$i", i * 86_400_000L, v, PointState.ELIGIBLE) },
        bands = emptyList(),
        reference = null,
        firstDayMs = 0L,
        lastDayMs = values.size * 86_400_000L,
        zoneId = "UTC",
        pivotMs = null,
        accessibleDescription = "",
    )

    @Test
    fun `yMin is always zero`() {
        assertThat(spec(listOf(20f, 21f, 22f)).yMin).isEqualTo(0f)
        // The trap case: tight, high values. A "smart" axis would start at 200 and turn a gap of
        // 2 s into a cliff.
        assertThat(spec(listOf(210f, 211f, 212f)).yMin).isEqualTo(0f)
    }

    @Test
    fun `yMax is at least 20 and rounds up to the next multiple of 5`() {
        assertThat(spec(listOf(3f, 4f)).yMax).isEqualTo(20f)
        // 1.15 x 61 = 70.15 -> 75
        assertThat(spec(listOf(61f)).yMax).isEqualTo(75f)
    }

    // -------------------------------------------------------------------------------------
    // P1 — nothing is aggregated below three nights, no category below five
    // -------------------------------------------------------------------------------------

    @Test
    fun `below three nights the position is a refusal`() {
        assertThat(Aggregate.position(10.0, 40.0, 0)).isEqualTo(Aggregate.Position.Refusal)
        assertThat(Aggregate.position(10.0, 40.0, 2)).isEqualTo(Aggregate.Position.Refusal)
    }

    @Test
    fun `between three and four nights no category is offered`() {
        // Even when the interval lies entirely on one side of the threshold, we do not categorise.
        assertThat(Aggregate.position(2.0, 9.0, 3)).isEqualTo(Aggregate.Position.Provisional(3))
        assertThat(Aggregate.position(30.0, 50.0, 4)).isEqualTo(Aggregate.Position.Provisional(4))
    }

    @Test
    fun `the five outcomes of the position sentence, on the exact bounds`() {
        assertThat(Aggregate.position(2.0, 14.9, 6)).isEqualTo(Aggregate.Position.BelowThreshold)
        assertThat(Aggregate.position(15.1, 40.0, 6)).isEqualTo(Aggregate.Position.AboveThreshold)
        // The strict bound: ciLow == 15 is NOT "above", the interval spans the threshold.
        assertThat(Aggregate.position(15.0, 40.0, 6)).isEqualTo(Aggregate.Position.SpansThreshold)
        assertThat(Aggregate.position(2.0, 15.0, 6)).isEqualTo(Aggregate.Position.SpansThreshold)
    }

    // -------------------------------------------------------------------------------------
    // The interval does not move from one recomposition to the next
    // -------------------------------------------------------------------------------------

    @Test
    fun `the fixed seed gives the same interval on every call`() {
        val values = doubleArrayOf(19.8, 23.4, 21.2, 20.6, 22.9, 18.7)
        val seed = Aggregate.seedOf(listOf("a1", "a2", "a3", "a4", "a5", "a6"))
        val first = Aggregate.bootstrapCi(values, seed)
        repeat(20) {
            assertThat(Aggregate.bootstrapCi(values, seed)).isEqualTo(first)
        }
    }

    @Test
    fun `the seed does not depend on the order of the sessions`() {
        assertThat(Aggregate.seedOf(listOf("b", "a", "c")))
            .isEqualTo(Aggregate.seedOf(listOf("c", "b", "a")))
    }

    // -------------------------------------------------------------------------------------
    // P3 — below the MDC95, the gap is named inconclusive before it is quantified
    // -------------------------------------------------------------------------------------

    private fun result(median: Double, low: Double, high: Double, n: Int, disp: Double) =
        Aggregate.Result(
            Aggregate.Quantity.HOURLY_COUNT, median, low, high, n, disp, Aggregate.mdc95(disp, n),
        )

    @Test
    fun `a difference interval containing zero is never distinguishable`() {
        val a = result(31.0, 19.0, 44.0, 6, 12.0)
        val b = result(22.0, 14.0, 31.0, 6, 12.0)
        val c = Aggregate.compare(a, b, -24.0, 5.0, 11)
        assertThat(c.distinguishable).isFalse()
        assertThat(c.verdict).isEqualTo(text(R.string.compare_inconclusive))
    }

    @Test
    fun `a gap below the MDC95 stays inconclusive even if the interval excludes zero`() {
        // Wide dispersion, few nights: the MDC95 is about 13.6/h. A gap of 3/h whose interval
        // excludes zero by a hair is not enough — the method cannot decide.
        val a = result(25.0, 23.0, 27.0, 6, 12.0)
        val b = result(22.0, 20.0, 24.0, 6, 12.0)
        val c = Aggregate.compare(a, b, -5.0, -1.0, 11)
        assertThat(c.mdc95).isCloseTo(13.58, within(0.1))
        assertThat(c.distinguishable).isFalse()
        assertThat(Resources.resolve(c.inconclusiveReason!!)).contains("smallest change")
    }

    @Test
    fun `beyond thirty nights needed, the number is not displayed`() {
        val a = result(25.0, 23.0, 27.0, 6, 12.0)
        val b = result(22.0, 20.0, 24.0, 6, 12.0)
        assertThat(Aggregate.compare(a, b, -5.0, -1.0, 84).nightsNeeded).isNull()
        assertThat(Aggregate.compare(a, b, -5.0, -1.0, 11).nightsNeeded).isEqualTo(11)
    }

    // -------------------------------------------------------------------------------------
    // Periodicity is never displayed bare
    // -------------------------------------------------------------------------------------

    @Test
    fun `periodicity is qualified only from five nights on`() {
        assertThat(Aggregate.qualifyPeriodicity(0.71, 4)).isNull()
        assertThat(Aggregate.qualifyPeriodicity(0.71, 6)).isEqualTo(text(R.string.trend_periodicity_high))
        assertThat(Aggregate.qualifyPeriodicity(0.31, 6)).isEqualTo(text(R.string.trend_periodicity_low))
    }

    @Test
    fun `no periodicity label exposes the raw index`() {
        listOf(0.0, 0.31, 0.5, 0.58, 0.71, 1.0).forEach { index ->
            val label = Aggregate.qualifyPeriodicity(index, 6)
            assertThat(label).isNotNull()
            val rendered = Resources.resolve(label!!)
            assertThat(rendered).doesNotContain(index.toString())
            assertThat(rendered).doesNotContain(",")
        }
    }

    // -------------------------------------------------------------------------------------
    // Estimators
    // -------------------------------------------------------------------------------------

    @Test
    fun `the median and the dispersion resist one aberrant night`() {
        val withoutOutlier = doubleArrayOf(20.0, 21.0, 22.0, 23.0, 24.0)
        val withOutlier = doubleArrayOf(20.0, 21.0, 22.0, 23.0, 240.0)
        assertThat(Aggregate.median(withoutOutlier)).isEqualTo(22.0)
        assertThat(Aggregate.median(withOutlier)).isEqualTo(22.0)
        // A classic standard deviation would explode; the MAD x 1.4826 barely moves.
        assertThat(Aggregate.dispersion(withOutlier))
            .isCloseTo(Aggregate.dispersion(withoutOutlier), within(0.5))
    }

    // -------------------------------------------------------------------------------------
    // The refusal: two unrelated reasons, and the screen must say which one
    // -------------------------------------------------------------------------------------

    private fun refusal(eligible: Int, fitted: Int) = TrendUiState.Refusal(
        eligibleNights = eligible,
        fittedRhythmNights = fitted,
        requiredNights = Aggregate.MIN_NIGHTS_AGGREGATE,
        recordedNights = emptyList(),
        waking = WakingState.None,
    )

    /**
     * The defect this test prevents: nine eligible nights, two identified rhythms, and a screen
     * announcing "2 nights out of 3". The sentence is false and it sends the user hunting for a
     * measurement defect where the model has simply refused to publish a period it does not
     * identify.
     */
    @Test
    fun `the refusal distinguishes the missing nights from the missing rhythms`() {
        assertThat(refusal(eligible = 2, fitted = 2).reason).isEqualTo(RefusalReason.NOT_ENOUGH_NIGHTS)
        assertThat(refusal(eligible = 2, fitted = 2).acquiredNights).isEqualTo(2)

        val rhythm = refusal(eligible = 9, fitted = 2)
        assertThat(rhythm.reason).isEqualTo(RefusalReason.RHYTHM_NOT_FITTED)
        // The counter shows the count that is missing, not the one already reached.
        assertThat(rhythm.acquiredNights).isEqualTo(2)

        // The exact bound: three eligible nights are enough to swing the reason, because the count
        // of nights is then no longer what is missing.
        assertThat(refusal(eligible = 3, fitted = 0).reason).isEqualTo(RefusalReason.RHYTHM_NOT_FITTED)
    }

    /** The text of the second reason must not read as a breakage, and it cites its measurement. */
    @Test
    fun `the rhythm refusal presents itself as a property of the product`() {
        val body = Resources.read(R.string.trend_rhythm_refusal_body)
        assertThat(body).contains("2 fits out of 20")
        assertThat(body).contains("refuses")
        // Neither breakage, nor error, nor failure: what happened is a documented refusal.
        listOf("error", "failed", "failure", "broken").forEach {
            assertThat(body.lowercase()).doesNotContain(it)
        }
    }
}
