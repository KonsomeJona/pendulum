package com.pendulum.phone.ui

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.PendulumError
import com.pendulum.phone.ui.model.Situations
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The wiring of the error messages, and above all **the cross-cutting rule**.
 *
 * A night with no hypnogram, a short night, a watch left on the table are *situations*: amber. Red
 * is reserved for what is really broken — transfer, permissions, integrity, storage. That rule is
 * carried by [PendulumError.technical], so it can be tested; written in a document, it would have
 * been lost by the third message added.
 */
class SituationsTest {

    // -------------------------------------------------------------------------------------
    // Health Connect
    // -------------------------------------------------------------------------------------

    @Test
    fun `with no answer from Health Connect, nothing is shown`() {
        // `null` is not "no source": it is "the question has not been asked yet".
        assertThat(Situations.sleep(null, null, 0)).isNull()
    }

    @Test
    fun `permission revoked - E-HC-02, in red, with an action`() {
        val e = Situations.sleep(SleepReader.Availability.PERMISSIONS_MISSING, null, 0)!!
        assertThat(e.code).isEqualTo("E-HC-02")
        assertThat(e.technical).isTrue()
        assertThat(e.button).isEqualTo(text(R.string.error_hc_02_button))
    }

    @Test
    fun `no source - E-HC-01, in amber, because nothing is broken`() {
        val e = Situations.sleep(SleepReader.Availability.READY, 0, 0)!!
        assertThat(e.code).isEqualTo("E-HC-01")
        assertThat(e.technical).isFalse()
    }

    @Test
    fun `two contradictory sources - E-HC-03, in amber`() {
        val e = Situations.sleep(SleepReader.Availability.READY, 2, 2)!!
        assertThat(e.code).isEqualTo("E-HC-03")
        assertThat(e.technical).isFalse()
    }

    @Test
    fun `one source, a single origin - nothing to say`() {
        assertThat(Situations.sleep(SleepReader.Availability.READY, 1, 1)).isNull()
    }

    @Test
    fun `Health Connect missing does not duplicate the onboarding`() {
        // These two cases already have their screen, with their install button. Two places for one
        // and the same repair, and the user tries the one that does not work.
        assertThat(Situations.sleep(SleepReader.Availability.SDK_UNAVAILABLE, null, 0)).isNull()
        assertThat(Situations.sleep(SleepReader.Availability.UPDATE_REQUIRED, null, 0)).isNull()
    }

    // -------------------------------------------------------------------------------------
    // A night
    // -------------------------------------------------------------------------------------

    private fun night(
        maskSource: String = "HEALTH_CONNECT",
        exclusionReason: String = ComparabilityRule.OK,
        truncated: Boolean = false,
    ) = ComparableNight(
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
        periodicityValid = true,
        missRate = 0.21,
        analysableTstMin = 312.0,
        analysableMin = 460.0,
        truncated = truncated,
        revealedAtMs = null,
        comparable = exclusionReason == ComparabilityRule.OK,
        exclusionReason = exclusionReason,
    )

    private fun session(
        batteryPctLast: Int? = 62,
        gapTotalMs: Long = 0L,
        totalChunks: Int? = 17,
    ) = NightSessionEntity(
        sessionHex = "abcd",
        startWallMs = 1_700_000_000_000L,
        plannedStopWallMs = 1_700_000_000_000L,
        zoneId = "Europe/Paris",
        tzOffsetStartMin = 60,
        tzOffsetEndMin = 60,
        nominalRateHz = 50,
        modeFlags = 0,
        state = "CLOSED",
        totalChunks = totalChunks,
        batteryPctLast = batteryPctLast,
        gapTotalMs = gapTotalMs,
    )

    @Test
    fun `a night with nothing to report produces no card`() {
        assertThat(Situations.night(night(), session())).isNull()
    }

    @Test
    fun `a truncated night does not pass itself off as an incomplete transfer`() {
        // `truncated` says the recording stopped without a clean close, not that files are missing.
        // Deducing `E-NIGHT-07` from it would show "files are missing" — with a red button — on a
        // night that was transferred in full. That code is returned by `WakingMachine`, which has
        // both chunk counters to hand.
        assertThat(Situations.night(night(truncated = true), session())).isNull()
    }

    @Test
    fun `night too short - amber, and no button because there is nothing to do`() {
        val e = Situations.night(night(exclusionReason = ComparabilityRule.TOO_SHORT), session())!!
        assertThat(e.code).isEqualTo("E-NIGHT-02")
        assertThat(e.technical).isFalse()
        assertThat(e.button).isNull()
    }

    @Test
    fun `watch discharged - amber, the figure stays but it is underestimated`() {
        val e = Situations.night(night(), session(batteryPctLast = 4))!!
        assertThat(e.code).isEqualTo("E-NIGHT-03")
        assertThat(e.technical).isFalse()
    }

    @Test
    fun `signal gaps - amber, and no button because continuous mode does not exist`() {
        // The message carried "Force continuous mode" and pointed to "Settings > Measurement".
        // That setting exists nowhere — neither as a phone-side preference nor as a command to the
        // watch — and the button was rendered on the night detail, where `ErrorCard` receives empty
        // lambdas. It therefore did nothing, which is worse than a greyed-out button with its
        // reason.
        val e = Situations.night(night(), session(gapTotalMs = 300_000L))!!
        assertThat(e.code).isEqualTo("E-NIGHT-04")
        assertThat(e.technical).isFalse()
        assertThat(e.button).isNull()
    }

    @Test
    fun `two sources - no button, the source is not re-chosen from this card`() {
        // "Change the preferred source" opened the Health Connect settings, where Pendulum's
        // preference cannot be changed: the only screen that writes it is step 4 of the onboarding.
        val e = Situations.sleep(SleepReader.Availability.READY, 2, 2)!!
        assertThat(e.button).isNull()
    }

    @Test
    fun `accelerometric mask - amber, last because it is the most frequent`() {
        val e = Situations.night(night(maskSource = Mapping.ACCEL_MASK), session())!!
        assertThat(e.code).isEqualTo("E-NIGHT-01")
        assertThat(e.technical).isFalse()
    }

    @Test
    fun `the order follows the consequence - what leaves the trend comes first`() {
        // Night both short AND without a hypnogram: it is the duration that must be announced,
        // otherwise the user repairs the sleep source and the night stays out of the trend.
        val e = Situations.night(
            night(maskSource = Mapping.ACCEL_MASK, exclusionReason = ComparabilityRule.TOO_SHORT),
            session(),
        )!!
        assertThat(e.code).isEqualTo("E-NIGHT-02")
    }

    // -------------------------------------------------------------------------------------
    // The template, over every message produced
    // -------------------------------------------------------------------------------------

    @Test
    fun `every message has a title, a cause, an action and a stable code`() {
        val all = listOf(
            Situations.sleep(SleepReader.Availability.PERMISSIONS_MISSING, null, 0),
            Situations.sleep(SleepReader.Availability.READY, 0, 0),
            Situations.sleep(SleepReader.Availability.READY, 2, 2),
            Situations.night(night(exclusionReason = ComparabilityRule.TOO_SHORT), session()),
            Situations.night(night(), session(batteryPctLast = 4)),
            Situations.night(night(), session(gapTotalMs = 300_000L)),
            Situations.night(night(maskSource = Mapping.ACCEL_MASK), session()),
        ).filterNotNull()

        assertThat(all).hasSize(7)
        all.forEach { e ->
            assertThat(e.code).matches("E-[A-Z]+-\\d\\d")
            val title = Resources.resolve(e.title)
            val cause = Resources.resolve(e.cause)
            assertThat(title).isNotBlank()
            assertThat(cause).isNotBlank()
            assertThat(Resources.resolve(e.action)).isNotBlank()
            // "An error occurred" has nowhere to be written, and we check it.
            assertThat(title.lowercase()).doesNotContain("oops")
            assertThat(cause.lowercase()).doesNotContain("an error occurred")
        }
    }

    @Test
    fun `red is reserved for what is really broken`() {
        // A single red situation in this whole file: the revoked permission. Every night situation
        // is amber, which is exactly the cross-cutting rule.
        val reds = listOf(
            Situations.sleep(SleepReader.Availability.PERMISSIONS_MISSING, null, 0),
        ).filterNotNull()
        assertThat(reds).allMatch { it.technical }

        val ambers = listOf(
            Situations.sleep(SleepReader.Availability.READY, 0, 0),
            Situations.sleep(SleepReader.Availability.READY, 2, 2),
            Situations.night(night(exclusionReason = ComparabilityRule.TOO_SHORT), session()),
            Situations.night(night(), session(batteryPctLast = 4)),
            Situations.night(night(), session(gapTotalMs = 300_000L)),
            Situations.night(night(maskSource = Mapping.ACCEL_MASK), session()),
        ).filterNotNull()
        assertThat(ambers).noneMatch { it.technical }
    }
}
