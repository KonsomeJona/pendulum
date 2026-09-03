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
    @DisplayName("a comparable night scored on the accelerometer mask is provisional, not excluded")
    fun `provisional night`() {
        val ui = Mapping.nightUi(
            night("a", gate = "TRUNCATED_NO_TREND", maskSource = Mapping.ACCEL_MASK),
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

    /**
     * The defect this test pins down.
     *
     * "Provisional" is the state of a night **waiting for its hypnogram**, and every comparable
     * night whose gate was not `FULL` got it — the row's `maskSource` was never consulted. On a
     * Health Connect row the hypnogram has arrived: the gate is the analysis's final word on that
     * denominator, and nothing will ever recompute it. Such a night — cut short at 3 h 40, say,
     * `TRUNCATED_NO_TREND` — read "◐ provisional" with no reason, on the list, on the chart (as an
     * accelerometer-masked point, which it was not) and in the report, for the rest of the
     * campaign. It is out of the trend for good, and that is what the screen has to say.
     */
    @Test
    @DisplayName("a Health Connect night whose gate is not full is excluded, with the gate as its reason")
    fun `gated night with a hypnogram`() {
        val truncated = Mapping.nightUi(
            night("a", gate = "TRUNCATED_NO_TREND", maskSource = "HEALTH_CONNECT"),
            endWallMs = null,
            sleepSource = text("Oura"),
        )
        assertThat(truncated.state)
            .withFailMessage(
                "A Health Connect night gated `TRUNCATED_NO_TREND` reads %s. Its hypnogram is " +
                    "there and nothing will recompute it: \"provisional\" is a promise the " +
                    "application cannot keep, and it kept it on screen for the whole campaign.",
                truncated.state,
            )
            .isEqualTo(NightState.EXCLUDED)
        assertThat(truncated.reason).isEqualTo(text(R.string.nights_reason_gate_truncated))

        val noPlmi = Mapping.nightUi(
            night("b", gate = "NO_PLMI", maskSource = "HEALTH_CONNECT"),
            endWallMs = null,
            sleepSource = text("Oura"),
        )
        assertThat(noPlmi.state).isEqualTo(NightState.EXCLUDED)
        assertThat(noPlmi.reason).isEqualTo(text(R.string.nights_reason_gate_no_plmi))
    }

    @Test
    @DisplayName("a night out of the comparison keeps that reason, whatever its gate says")
    fun `comparability reason first`() {
        // The most structural reason wins, as in `ComparabilityRule.evaluate`: a night on the
        // wrong leg is not "cut short", even when it also is. Announcing the gate would send the
        // user checking the recording when the setup was the problem.
        val ui = Mapping.nightUi(
            night(
                "a",
                gate = "TRUNCATED_NO_TREND",
                comparable = false,
                exclusionReason = ComparabilityRule.LEG_CHANGED,
            ),
            endWallMs = null,
            sleepSource = text("Oura"),
        )
        assertThat(ui.state).isEqualTo(NightState.EXCLUDED)
        assertThat(ui.reason).isEqualTo(text(R.string.nights_reason_leg_changed))
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
    // The miss rate: read next to its rhythm, never without it
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a refused fit leaves no miss rate to show, whatever the column holds")
    fun `refused fit, no miss rate`() {
        // `MISS_RATE_SATURATED` is the case that exposed it: the fit is refused and the column
        // holds 0.90 — `maxMissRate`, the ceiling of the model, not a measurement. Only
        // `TOO_FEW_INTERVALS` leaves `NULL`; the five other refusals leave a finite `p`.
        assertThat(Mapping.missRate(night("a", rhythmValid = false, missRate = 0.90))).isNull()
        assertThat(Mapping.missRate(night("a", rhythmValid = true, missRate = 0.21))).isEqualTo(0.21)

        // And the flag reads through the same gate: it used to write "missed 90%" next to the
        // rhythm dash of the same night.
        val flags = Mapping.flags(
            night("a", rhythmValid = false, missRate = 0.90),
            gapCount = 0, gapTotalMs = 0, batteryPctLast = 62,
        )
        assertThat(flags).isEmpty()
    }

    @Test
    @DisplayName("the miss rate median obeys the three-night minimum and counts only measured rates")
    fun `miss rate aggregate`() {
        // Three eligible nights, one of them with a refused fit: two measured rates, no median.
        // Rolled by hand, this was a "median" of two values, without interval and without n.
        assertThat(
            Mapping.missRateAggregate(
                listOf(
                    night("a", missRate = 0.10),
                    night("b", missRate = 0.30),
                    night("c", rhythmValid = false, missRate = 0.90),
                )
            )
        ).isNull()

        val r = Mapping.missRateAggregate(
            listOf(night("a", missRate = 0.10), night("b", missRate = 0.30), night("c", missRate = 0.20)),
        )
        assertThat(r).isNotNull
        assertThat(r!!.quantity).isEqualTo(Aggregate.Quantity.MISS_RATE)
        assertThat(r.median).isEqualTo(0.20)
        assertThat(r.nights).isEqualTo(3)
    }

    @Test
    @DisplayName("the periodicity median counts only the nights whose index is valid")
    fun `periodicity aggregate`() {
        assertThat(
            Mapping.periodicityAggregate(
                listOf(night("a"), night("b"), night("c").copy(periodicityValid = false)),
            )
        ).isNull()

        val r = Mapping.periodicityAggregate(listOf(night("a"), night("b"), night("c")))
        assertThat(r).isNotNull
        assertThat(r!!.quantity).isEqualTo(Aggregate.Quantity.PERIODICITY)
        assertThat(r.median).isEqualTo(0.58)
        assertThat(r.nights).isEqualTo(3)
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
