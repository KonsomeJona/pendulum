package com.pendulum.phone.ui

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.ComputationPath
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The displayed thresholds, and what they promise.
 *
 * Three things are checked here, and none of them shows to the eye on a screenshot: that the 15/h
 * threshold is **labelled** rather than laid down bare, that the number of nights asked for depends
 * on the regime one is in, and that the "why this figure" block stays generative.
 */
class ThresholdsTest {

    // -------------------------------------------------------------------------------------
    // The 15/h line, anchored
    // -------------------------------------------------------------------------------------

    @Test
    fun `the threshold is presented with its provenance and its actigraphic equivalent`() {
        val legend = Resources.read(R.string.chart_threshold_15_legend)
        // The nuisance reference point: it is this wording that brings the request for urgent
        // contact down from 55.8 % to 34.7 % (Zikmund-Fisher, JMIR 2018).
        assertThat(legend).contains("Many physicians are not concerned below it")
        // And the PSG / ankle actigraphy gap, written down rather than passed over in silence.
        assertThat(legend).contains("16/h")
        assertThat(legend).contains("Aritake-Okada 2014")
    }

    @Test
    fun `the actigraphic equivalent is documented and drives no branch`() {
        assertThat(Aggregate.ANKLE_ACTIGRAPHY_THRESHOLD_PER_HOUR).isEqualTo(16.0)
        // A single cohort: we document, we do not substitute. The five position sentences stay
        // pegged to the ICSD-3 15/h, and the test checks it on the exact bound.
        assertThat(Aggregate.position(15.5, 40.0, 6)).isEqualTo(Aggregate.Position.AboveThreshold)
        assertThat(Aggregate.position(2.0, 15.5, 6)).isEqualTo(Aggregate.Position.SpansThreshold)
    }

    // -------------------------------------------------------------------------------------
    // The number of nights required depends on the regime
    // -------------------------------------------------------------------------------------

    @Test
    fun `high regime - three nights, and it is sourced`() {
        assertThat(Aggregate.requiredNights(ciLow = 18.0, ciHigh = 34.0)).isEqualTo(3)
        assertThat(Aggregate.requiredNightsReason(18.0, 34.0))
            .isEqualTo(text(R.string.trend_nights_required_high))
    }

    @Test
    fun `low regime - far more than three nights, because that is where reliability collapses`() {
        // The defect corrected: `Position.BelowThreshold` was rendered precisely in the regime
        // where three nights give the worst intraclass correlation. The application was more
        // confident when it reassured than when it warned.
        assertThat(Aggregate.requiredNights(ciLow = 4.0, ciHigh = 11.0)).isEqualTo(14)
        assertThat(Aggregate.requiredNights(4.0, 11.0))
            .isGreaterThan(Aggregate.requiredNights(18.0, 34.0))
    }

    @Test
    fun `low regime - the text says 14 is a compromise and 26 is the measured figure`() {
        val reason = Resources.read(R.string.trend_nights_required_low)
        assertThat(reason).contains("26 nights")
        assertThat(reason).contains("compromise")
        assertThat(reason).contains("nothing published supports that number")
    }

    @Test
    fun `straddling the threshold - seven nights, so that the interval tightens`() {
        assertThat(Aggregate.requiredNights(ciLow = 9.0, ciHigh = 22.0)).isEqualTo(7)
        // Exact bounds: at `ciLow == 15` or `ciHigh == 15`, we straddle, never one side.
        assertThat(Aggregate.requiredNights(15.0, 40.0)).isEqualTo(7)
        assertThat(Aggregate.requiredNights(2.0, 15.0)).isEqualTo(7)
    }

    @Test
    fun `the hard refusal at three nights is untouched`() {
        // The rule adds severity to the "provisional" label, not to the aggregation.
        assertThat(Aggregate.MIN_NIGHTS_AGGREGATE).isEqualTo(3)
        assertThat(Aggregate.position(4.0, 11.0, 2)).isEqualTo(Aggregate.Position.Refusal)
    }

    // -------------------------------------------------------------------------------------
    // The uncalibrated interval
    // -------------------------------------------------------------------------------------

    @Test
    fun `below six nights, the label does not promise 95 percent`() {
        val uncalibrated = Resources.resolve(
            text(R.string.trend_interval_uncalibrated, "18", "26", 3),
        )
        // The figure 95 does appear, but **denied**: "not a calibrated 95% interval". What must not
        // appear is the canonical form that asserts it.
        assertThat(uncalibrated).doesNotContain("95% CI")
        assertThat(uncalibrated).contains("not a calibrated")
        assertThat(Resources.resolve(text(R.string.trend_interval_and_n, "18", "26", 6)))
            .contains("95% CI")
    }

    @Test
    fun `the reason for the labelling cites the measurement`() {
        val note = Resources.read(R.string.trend_interval_uncalibrated_note)
        assertThat(note).contains("75%")
        assertThat(note).contains("88%")
        assertThat(note).contains("10,000")
    }

    // -------------------------------------------------------------------------------------
    // "Why this figure": generative, never attributive
    // -------------------------------------------------------------------------------------

    private fun night(maskSource: String = "HEALTH_CONNECT") = ComparableNight(
        sessionHex = "abcd",
        startWallMs = 1_700_000_000_000L,
        zoneId = "Europe/Paris",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = maskSource,
        gate = "FULL",
        independence = "INDEPENDENT",
        plmi = 18.4,
        plmiSpt = 9.0,
        fundamentalSec = 21.0,
        rhythmValid = true,
        periodicityIndex = 0.58,
        missRate = 0.21,
        analysableTstMin = 312.0,
        analysableMin = 460.0,
        truncated = false,
        revealedAtMs = null,
        comparable = true,
        exclusionReason = ComparabilityRule.OK,
    )

    private fun result() = PlmResultEntity(
        sessionHex = "abcd",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = "HEALTH_CONNECT",
        computedAtMs = 0L,
        algoVersion = "1.4.0",
        plmsCount = 96,
        plmwCount = 0,
        isolatedCount = 12,
        shortImiCount = 4,
        tstMin = 320.0,
        analysableTstMin = 312.0,
        sptMin = 461.0,
        wasoMin = 40.0,
        plmi = 18.4,
        plmiSpt = 12.5,
        plmw = 9.0,
        plmiFirstHalf = 15.0,
        plmiSecondHalf = 21.0,
        plmiRespWorstCase = 16.3,
        periodicityIndex = 0.58,
        periodicityValid = true,
        fundamentalSec = 21.0,
        muLog = 3.0,
        sigmaLog = 0.4,
        missRate = 0.21,
        alternationSuspect = false,
        rhythmConverged = true,
        rhythmValid = true,
        truncatedSeriesDropped = 0,
        independence = "INDEPENDENT",
        gate = "FULL",
        floorMode = "ADAPTIVE",
    )

    private fun block(maskSource: String = "HEALTH_CONNECT") = ComputationPath.of(
        n = night(maskSource),
        result = result(),
        recordedDurationMin = 461.0,
        movementsKept = 96,
        rule = text(R.string.settings_rule_aasm),
        sleepSource = text("Samsung Health"),
    )!!

    @Test
    fun `the block shows the computation path, in order`() {
        val b = block()
        // The title carries the value and its unit: we check the rendered text and not only the
        // identifier, because it is the form "18.4 /h" that is at stake.
        assertThat(Resources.resolve(b.title)).isEqualTo("Why 18.4 /h")
        assertThat(b.lines.map { it.label }).containsExactly(
            text(R.string.night_why_analysable_sleep),
            text(R.string.night_why_movements_counted),
            text(R.string.night_why_rule),
            text(R.string.night_why_mask),
            text(R.string.night_why_denominator),
            text(R.string.night_why_missed_rate),
            text(R.string.night_why_resp_bracket),
        )
        assertThat(Resources.resolve(b.lines[0].value)).isEqualTo(Mapping.readableDuration(312.0))
        assertThat(Resources.resolve(b.lines[0].note!!)).contains(Mapping.readableDuration(461.0))
        assertThat(Resources.resolve(b.lines[1].value)).isEqualTo("96")
        // As a percentage, like the quality table on the same screen: "0.21" here and "21.0%" over
        // there were two spellings of the same `missRate`, two cards apart.
        assertThat(Resources.resolve(b.lines[5].value)).isEqualTo("21.0%")
        // The respiratory bracketing: the **lower bound** of the index, hence an index — 16.3 for a
        // `plmi` of 18.4. This row used to assert "-2.1", that is, the gap, under a comment that
        // already said "the lower bound": the screen was therefore showing a negative number under
        // a note promising "the value the index would take".
        assertThat(Resources.resolve(b.lines[6].value)).startsWith("16.3")
    }

    @Test
    fun `the denominator row says whether it is independent of the numerator`() {
        assertThat(block().lines[4].value)
            .isEqualTo(text(R.string.night_why_denominator_independent))
        assertThat(block(Mapping.ACCEL_MASK).lines[4].value)
            .isEqualTo(text(R.string.night_why_denominator_circular))
    }

    @Test
    fun `the non-causality sentence is part of the type, not of the screen`() {
        // A computation path without it must not be able to exist: it is a field with a default
        // value, not a text the screen chooses to add.
        assertThat(block().disclaimer).isEqualTo(text(R.string.night_why_disclaimer))
        assertThat(Resources.resolve(block().disclaimer))
            .contains("does not indicate what caused")
    }

    @Test
    fun `no row ranks factors or attributes a share`() {
        // The guard rail of this piece of work: no "contribution", no percentage of explanation, no
        // ranking. These methods do not tell correlation from causality apart.
        val forbidden = listOf(
            "contribut", "explains", "accounts for", "importance", "ranked", "impact of",
        )
        // The rows carry `UiText`s: it is the **resolved** text that must be scanned. On the
        // objects themselves, `joinToString` would render "Res(id=…)" and the assertion would no
        // longer check anything.
        val rendered = block().lines
            .flatMap { listOfNotNull(it.label, it.value, it.note) }
            .joinToString(" ") { Resources.resolve(it) }
            .lowercase()
        forbidden.forEach { assertThat(rendered).doesNotContain(it) }
    }

    @Test
    fun `with no result, there is nothing to explain`() {
        assertThat(
            ComputationPath.of(night(), null, 461.0, 0, text("AASM v3"), text("Samsung Health")),
        ).isNull()
    }
}
