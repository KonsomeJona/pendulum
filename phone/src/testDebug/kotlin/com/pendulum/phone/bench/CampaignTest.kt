package com.pendulum.phone.bench

import com.pendulum.algo.model.PublicationGate
import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.ui.model.Aggregate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * What the bench campaign has to be worth, verified on the JVM rather than observed on a phone.
 *
 * ### The defect this test exists so as never to see again
 *
 * The first version of the seeding wrote nine perfectly comparable and publishable nights, and the
 * Trend screen stayed closed. Reason: `TrendUiState` requires at least three nights **whose rhythm
 * is identified**, and `Rhythm` was refusing the fit on all nine. Nothing announced it — no
 * exception, no log; one had to install, seed, open the screen and read the refusal sentence.
 *
 * This file is in `src/testDebug/` and not `src/test/`, because what it tests exists only in the
 * debug variant. `src/test/` is shared by both variants: putting this test there would make the
 * compilation of `testReleaseUnitTest` fail on a missing class — which is the good news in a bad
 * shape.
 */
class CampaignTest {

    private val campaign: List<Campaign.Night> by lazy {
        val kinds = Campaign.kinds(Seeding.DEFAULT_NIGHTS)
        kinds.mapIndexed { index, kind -> Campaign.night(kind, index) }
    }

    @Test
    fun `the default campaign holds seven eligible nights, one provisional and one excluded`() {
        val kinds = Campaign.kinds(Seeding.DEFAULT_NIGHTS)
        assertThat(kinds).hasSize(Seeding.DEFAULT_NIGHTS + 2)
        assertThat(kinds.count { it == Campaign.Kind.ELIGIBLE })
            .isEqualTo(Seeding.DEFAULT_NIGHTS)
        assertThat(kinds.count { it == Campaign.Kind.PROVISIONAL }).isEqualTo(1)
        assertThat(kinds.count { it == Campaign.Kind.EXCLUDED }).isEqualTo(1)

        // The first night is the reference for the whole comparability criterion, the last is the
        // one the waking status band speaks about. Neither of them can be atypical.
        assertThat(kinds.first()).isEqualTo(Campaign.Kind.ELIGIBLE)
        assertThat(kinds.last()).isEqualTo(Campaign.Kind.ELIGIBLE)
    }

    @Test
    fun `enough nights carry an identified rhythm to open the Trend screen`() {
        val fitted = campaign
            .filter { it.kind == Campaign.Kind.ELIGIBLE }
            .count { it.primary.rhythm.valid && it.primary.rhythm.fundamentalSec > 0.0 }

        // This is **the** gate the first version missed: below that count, `TrendScreen` stays in
        // its refusal branch and the five screens being aimed at — list, detail, questionnaire,
        // comparison, export — still have no link leading to them.
        assertThat(fitted)
            .describedAs("eligible nights whose rhythm fit is accepted")
            .isGreaterThanOrEqualTo(Aggregate.MIN_NIGHTS_AGGREGATE)
    }

    @Test
    fun `eligible nights are publishable and the others carry the state they claim`() {
        for (night in campaign) {
            val gate = night.primary.gate
            when (night.kind) {
                // Publishable and inside the trend: that is what "eligible" means.
                Campaign.Kind.ELIGIBLE, Campaign.Kind.EXCLUDED ->
                    assertThat(gate)
                        .describedAs("publication gate of a ${night.kind} night")
                        .isEqualTo(PublicationGate.FULL)

                // Measured correctly, but out of the trend: the index of a truncated night is
                // biased upwards with no correction possible. That is the "provisional" state.
                Campaign.Kind.PROVISIONAL ->
                    assertThat(gate).isEqualTo(PublicationGate.TRUNCATED_NO_TREND)
            }
        }
    }

    @Test
    fun `every night passes the analysable duration criterion`() {
        for (night in campaign) {
            assertThat(night.analysableMin)
                .describedAs("analysable minutes of a ${night.kind} night")
                .isGreaterThanOrEqualTo(ComparabilityRule.MIN_ANALYSABLE_MIN)
        }
    }

    @Test
    fun `the gain reference stays within the tolerance of the reference night`() {
        val reference = campaign.first().synth.truth.gainCalG.toDouble()
        for (night in campaign) {
            val deviation = abs(night.synth.truth.gainCalG - reference) / reference
            assertThat(deviation)
                .describedAs("gain deviation from the reference night")
                .isLessThan(ComparabilityRule.GAIN_TOLERANCE)
        }
    }

    @Test
    fun `no two nights look alike`() {
        val eligible = campaign.filter { it.kind == Campaign.Kind.ELIGIBLE }
        val counts = eligible.map { it.primary.plmi }
        val rhythms = eligible.map { it.primary.rhythm.fundamentalSec }

        // A trend chart where every point sits at the same height gives no way to judge the axis
        // scale, the dispersion band, or the median line. Identical nights would hide exactly the
        // display defects one is trying to see.
        assertThat(counts.distinct()).hasSameSizeAs(counts)
        assertThat(rhythms.max() - rhythms.min())
            .describedAs("spread of the fundamental rhythm over the eligible nights, in seconds")
            .isGreaterThan(0.5)
    }
}
