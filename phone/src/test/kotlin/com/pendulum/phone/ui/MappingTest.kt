package com.pendulum.phone.ui

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NightState
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The database to screen translation.
 *
 * It deserves tests for a precise reason: it is what replaced the preview data set on which the
 * whole navigation used to be wired. The defect we want to make impossible is not a crash, it is
 * **a figure that gets displayed when it should not exist** — and that kind of defect goes through
 * a visual review unnoticed.
 */
class MappingTest {

    // -------------------------------------------------------------------------------------
    // The rule that matters: no aggregate below three nights
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("no aggregate exists below three eligible nights")
    fun `no aggregate below three nights`() {
        for (n in 0..2) {
            val result = Mapping.aggregate(
                Aggregate.Quantity.RHYTHM_SECONDS,
                List(n) { night(hex = "n$it", fundamentalSec = 21.0) },
            ) { it.fundamentalSec }

            assertThat(result)
                .withFailMessage(
                    "On %d night(s), `aggregate` returned a value. This is the central guard rail " +
                        "of the product: below %d eligible nights there must exist no code path " +
                        "producing a median, because the screen chooses its branch on that " +
                        "nullity and not on a boolean one can forget to test.",
                    n, Aggregate.MIN_NIGHTS_AGGREGATE,
                )
                .isNull()
        }
    }

    @Test
    @DisplayName("at exactly three nights, the aggregate exists and carries its interval and its n")
    fun `aggregate at three nights`() {
        val nights = listOf(
            night("a", fundamentalSec = 19.0),
            night("b", fundamentalSec = 21.0),
            night("c", fundamentalSec = 24.0),
        )

        val r = Mapping.aggregate(Aggregate.Quantity.RHYTHM_SECONDS, nights) { it.fundamentalSec }

        assertThat(r).isNotNull
        assertThat(r!!.median).isEqualTo(21.0)
        assertThat(r.nights).isEqualTo(3)
        assertThat(r.ciLow).isLessThanOrEqualTo(r.median)
        assertThat(r.ciHigh).isGreaterThanOrEqualTo(r.median)
    }

    @Test
    @DisplayName("same set of nights, same interval — the seed does not depend on the order")
    fun `the interval is stable`() {
        val nights = listOf(night("a", 19.0), night("b", 21.0), night("c", 24.0), night("d", 27.0))

        val direct = Mapping.aggregate(Aggregate.Quantity.RHYTHM_SECONDS, nights) { it.fundamentalSec }
        val reversed = Mapping.aggregate(
            Aggregate.Quantity.RHYTHM_SECONDS,
            nights.reversed(),
        ) { it.fundamentalSec }

        // An interval that moves between two displays of the same set of nights destroys trust in
        // the whole screen: a user who reopens the application ten minutes later must read exactly
        // the same number again.
        assertThat(reversed!!.ciLow).isEqualTo(direct!!.ciLow)
        assertThat(reversed.ciHigh).isEqualTo(direct.ciHigh)
    }

    // -------------------------------------------------------------------------------------
    // The three states of a night
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a comparable and publishable night is eligible, with no reason")
    fun `eligible night`() {
        val ui = Mapping.nightUi(night("a"), endWallMs = null, sleepSource = text("Oura"))

        assertThat(ui.state).isEqualTo(NightState.ELIGIBLE)
        assertThat(ui.reason).isNull()
    }

    @Test
    @DisplayName("a comparable night whose gate is not full is provisional, not excluded")
    fun `provisional night`() {
        val ui = Mapping.nightUi(
            night("a", gate = "NO_PLMI"),
            endWallMs = null,
            sleepSource = text("Oura"),
        )

        // The difference is not cosmetic. A provisional night was measured correctly: what is
        // missing is its denominator, because the hypnogram has not arrived yet. It will be
        // recomputed on its own. Filing it with the excluded nights would make the most frequent
        // case at waking read as a breakage.
        assertThat(ui.state).isEqualTo(NightState.PROVISIONAL)
        assertThat(ui.reason).isNull()
    }

    @Test
    @DisplayName("an excluded night keeps its value and carries its translated reason")
    fun `excluded night`() {
        val ui = Mapping.nightUi(
            night("a", comparable = false, exclusionReason = ComparabilityRule.TOO_SHORT, plmi = 22.0),
            endWallMs = null,
            sleepSource = text("Oura"),
        )

        assertThat(ui.state).isEqualTo(NightState.EXCLUDED)
        assertThat(ui.reason).isNotNull()
        // The value stays visible: an invisible night is a night one forgets to explain.
        assertThat(ui.plmiCount).isEqualTo(22.0)
    }

    // -------------------------------------------------------------------------------------
    // The rhythm: absent is the normal case
    // -------------------------------------------------------------------------------------

    /**
     * The defect this test pins down.
     *
     * `:algo` refuses most rhythm fits — 2 accepted out of 20 nominal nights — and two of its six
     * refusal reasons (`MISS_RATE_SATURATED`, `SIGMA_SATURATED`) leave a **finite** `fundamentalSec`
     * in the column. The interface was reading that column without consulting `rhythmValid`: it
     * therefore displayed, with the same formatting as a measured rhythm, a period the model had
     * refused to publish.
     */
    @Test
    @DisplayName("a refused fit has no rhythm, even when the column carries a finite number")
    fun `refused rhythm`() {
        assertThat(Mapping.rhythmSec(night("a", fundamentalSec = 42.0, rhythmValid = false))).isNull()
        assertThat(Mapping.rhythmSec(night("a", fundamentalSec = 21.0, rhythmValid = true))).isEqualTo(21.0)
    }

    /**
     * `NaN` is what `:algo` writes when it could not even attempt the fit — "no value" must poison
     * every downstream computation visibly. `Math.round(NaN)` is 0, so the screen displayed "0 s":
     * a null rhythm, which is the one physically impossible value.
     */
    @Test
    @DisplayName("an absent rhythm never rounds to 0 s")
    fun `absent rhythm`() {
        assertThat(Mapping.rhythmSec(night("a", fundamentalSec = Double.NaN))).isNull()
        // The content **and** the identifier: "0 s" is the one physically impossible value, so it
        // is the rendered text that must be checked, not only the branch taken.
        assertThat(Resources.resolve(Mapping.readableRhythm(null))).doesNotContain("0 s")
        assertThat(Mapping.readableRhythm(null))
            .isEqualTo(text(R.string.night_detail_rhythm_not_fitted))
        assertThat(Resources.resolve(Mapping.readableRhythm(21.4))).isEqualTo("21 s")
    }

    // -------------------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a duration reads in hours and minutes, never in decimal hours")
    fun `readable duration`() {
        assertThat(Mapping.readableDuration(312.0)).isEqualTo("5 h 12")
        assertThat(Mapping.readableDuration(60.0)).isEqualTo("1 h 00")
        assertThat(Mapping.readableDuration(0.0)).isEqualTo("—")
    }

    @Test
    @DisplayName("the source says explicitly when the denominator comes from the same sensor")
    fun `source label`() {
        // This is the only piece of information that tells whether the figure rests on a
        // denominator independent of the numerator: it must never be replaced by an application
        // name, which would suggest a third-party source.
        assertThat(Mapping.sourceLabel(Mapping.ACCEL_MASK, "com.oura.app"))
            .isEqualTo(text(R.string.settings_accel_mask_only))
        // The application name comes from Health Connect: it is not translatable, so it goes out as
        // `UiText.Raw` and not as a resource.
        assertThat(Mapping.sourceLabel("HEALTH_CONNECT", "com.oura.app")).isEqualTo(text("App"))
        // Unknown package: what is missing is the **name of the application**, not the origin of
        // the denominator — `maskSource` carries that. Rendering "unidentified source" here put the
        // night detail screen in contradiction with its own quality check row, which wrote
        // "Health Connect" for the same night.
        assertThat(Mapping.sourceLabel("HEALTH_CONNECT", null))
            .isEqualTo(text(R.string.settings_health_connect))
    }

    @Test
    @DisplayName("the accelerometric mask is the first flag: it changes the nature of the figure")
    fun `flag order`() {
        val flags = Mapping.flags(
            night("a", maskSource = Mapping.ACCEL_MASK, truncated = true),
            gapCount = 2,
            gapTotalMs = 47_000,
            batteryPctLast = 8,
        )

        assertThat(flags).hasSizeGreaterThanOrEqualTo(4)
        assertThat(flags.first().label)
            .isEqualTo(text(R.string.nights_flag_accel_mask))
    }

    @Test
    @DisplayName("a night with no anomaly carries no flag")
    fun `no flag without a reason`() {
        val flags = Mapping.flags(night("a"), gapCount = 0, gapTotalMs = 0, batteryPctLast = 62)
        assertThat(flags).isEmpty()
    }

    // -------------------------------------------------------------------------------------

    private fun night(
        hex: String,
        fundamentalSec: Double = 21.0,
        rhythmValid: Boolean = true,
        plmi: Double = 18.0,
        gate: String = Mapping.GATE_FULL,
        comparable: Boolean = true,
        exclusionReason: String = ComparabilityRule.OK,
        maskSource: String = "HEALTH_CONNECT",
        truncated: Boolean = false,
        missRate: Double = 0.05,
    ) = ComparableNight(
        sessionHex = hex,
        startWallMs = 1_741_737_600_000L,
        zoneId = "Europe/Paris",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = maskSource,
        gate = gate,
        independence = "INDEPENDENT_HC",
        plmi = plmi,
        plmiSpt = plmi,
        fundamentalSec = fundamentalSec,
        rhythmValid = rhythmValid,
        periodicityIndex = 0.58,
        periodicityValid = true,
        missRate = missRate,
        analysableTstMin = 312.0,
        analysableMin = 420.0,
        truncated = truncated,
        revealedAtMs = null,
        comparable = comparable,
        exclusionReason = exclusionReason,
    )
}
