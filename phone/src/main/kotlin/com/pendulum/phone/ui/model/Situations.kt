package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text

/**
 * The wiring of the error messages: two pure functions, a single template.
 *
 * ### The template, invariant
 *
 * **A neutral title, one sentence of cause, one sentence of action, a button when an action exists,
 * a stable code.** [PendulumError] cannot be built any other way — there is no constructor that
 * accepts a message alone. "An error occurred" therefore has nowhere to be written.
 *
 * ### The cross-cutting rule, which is carried by a boolean and not by a convention
 *
 * A night without a hypnogram, a short night, a watch left on the table are **situations**. They
 * are shown in amber. Red is reserved for what is really broken: transfer, permissions, file
 * integrity, storage. That is exactly [PendulumError.technical], read by `ErrorCard` to choose the
 * tint — using red for a short night teaches people to ignore red, and the day the transfer really
 * breaks, nobody is looking any more.
 *
 * ### What is not wired, and why that is written here
 *
 * `E-NIGHT-05` (watch on the table) and `E-NIGHT-06` (loose strap) require the off-body fraction
 * and the night's noise floor; neither is persisted at the night level — the floor only exists per
 * event, in `clm_event.noiseFloorG`. `E-NIGHT-08` (clock drift between the two devices) requires a
 * comparison of the watch and phone timestamps that the ingestion does not keep. All three texts
 * exist and stay in place: wiring them on an approximation would give a false message, which is
 * worse than an absent message. `E-HC-02`, on the other hand, is wired, because a permission can be
 * read.
 */
object Situations {

    // -------------------------------------------------------------------------------------
    // Health Connect: the three E-HC-* codes
    // -------------------------------------------------------------------------------------

    /**
     * The sleep source situation, or `null` when all is well.
     *
     * @param recentSources number of sources having written a session over the last seven days.
     *   `null` when the question has not been asked yet — to be distinguished from zero, which is
     *   an answer.
     * @param lastNightOrigins number of distinct applications having published a session
     *   overlapping last night. Two or more, and the denominator depends on which one is read.
     */
    /**
     * The only permission blocker, without the source diagnosis that goes with it elsewhere.
     *
     * The home screen uses it, and so does the trend via [sleep]: a missing permission must be read
     * in the same place where it is repaired, and the home screen is the first one opened. It was
     * not there — the card lived only on the trend, so someone who never looks at their trend never
     * saw that half the measurement was missing.
     *
     * The other source situations (`E-HC-01`, `E-HC-03`) stay on the trend: they talk about nights
     * already measured and about numbers that do not exist on the home screen.
     *
     * A single factory for both screens, because two copies of the same diagnosis end up
     * contradicting each other — a flaw this project has already paid for.
     */
    fun sleepPermission(availability: SleepReader.Availability?): PendulumError? =
        if (availability != SleepReader.Availability.PERMISSIONS_MISSING) null
        else PendulumError(
            code = "E-HC-02",
            title = text(R.string.error_hc_02_title),
            cause = text(R.string.error_hc_02_cause),
            action = text(R.string.error_hc_02_action),
            button = text(R.string.error_hc_02_button),
            technical = true,
        )

    fun sleep(
        availability: SleepReader.Availability?,
        recentSources: Int?,
        lastNightOrigins: Int,
    ): PendulumError? = when {
        availability == null -> null

        // The permission is **broken**, not absent: red. It is the only one of the three that
        // really prevents something, and the only one that is repaired in one gesture.
        availability == SleepReader.Availability.PERMISSIONS_MISSING ->
            sleepPermission(availability)

        // Health Connect absent or too old: the onboarding already carries those two cases with
        // their install button. Repeating them at the top of the trend would make two places for
        // one repair, and this one is not the right place.
        availability != SleepReader.Availability.READY -> null

        // No source: a **situation**, in amber. The application carries on, on its own mask, and
        // every night concerned carries the flag. This is not a Pendulum failure.
        recentSources == 0 -> PendulumError(
            code = "E-HC-01",
            title = text(R.string.error_hc_01_title),
            cause = text(R.string.error_hc_01_cause),
            action = text(R.string.error_hc_01_action),
            button = text(R.string.error_hc_01_button),
            technical = false,
        )

        // Two contradictory sources: we never merge, we choose and we say so.
        //
        // No button. It used to carry one — "Change the preferred source" — and the only place the
        // preferred source is written is step 4 of the onboarding, which does not reopen. The
        // button therefore led to the Health Connect settings, where nothing changes Pendulum's
        // preference: it looked like it repaired something and repaired nothing. The action
        // sentence now says where the chosen source can be read, which is verifiable.
        lastNightOrigins >= 2 -> PendulumError(
            code = "E-HC-03",
            title = text(R.string.error_hc_03_title),
            cause = text(R.string.error_hc_03_cause),
            action = text(R.string.error_hc_03_action),
            technical = false,
        )

        else -> null
    }

    // -------------------------------------------------------------------------------------
    // One night: the E-NIGHT-* codes
    // -------------------------------------------------------------------------------------

    /**
     * A night's situation, or `null` when it calls for no explanation.
     *
     * Only one is returned, and the order is that of the **consequence** and not of the felt
     * severity: what puts the night out of the trend comes before what leaves it in. A user reading
     * "no sleep stages" on a night that is too short anyway would repair the wrong thing.
     */
    fun night(
        n: ComparableNight,
        session: NightSessionEntity?,
    ): PendulumError? {
        // `E-NIGHT-07` (incomplete transfer) is **not** returned here, and that is not an omission:
        // `night_session.truncated` says the recording stopped without a clean close, not that
        // files are missing. The fact that distinguishes the two is the number of chunks received
        // against `totalChunks`, which the `comparable_night` view does not carry. That code is
        // therefore returned by `WakingMachine`, which has both counters to hand. Deducing it here
        // from `truncated` would show "files are missing" on a fully transferred night whose watch
        // simply stopped early — a false message, with a red button.
        //
        // Consequence: **all night situations are amber**. That is exactly what the cross-cutting
        // rule says — a short night, a night without a hypnogram, a discharged watch are
        // situations, not failures.

        // 1. Night too short: it drops out of the trend, and there is nothing to be done. Amber.
        if (n.exclusionReason == ComparabilityRule.TOO_SHORT) {
            return PendulumError(
                code = "E-NIGHT-02",
                title = text(R.string.error_night_02_title),
                cause = text(R.string.error_night_02_cause),
                action = text(R.string.error_night_02_action),
                technical = false,
            )
        }

        // 2. Watch discharged during the night. The number stays, but it is most likely
        //    under-estimated: movements concentrate in the second half of the night.
        val battery = session?.batteryPctLast
        if (battery != null && battery < Mapping.LOW_BATTERY_THRESHOLD_PCT) {
            return PendulumError(
                code = "E-NIGHT-03",
                title = text(R.string.error_night_03_title),
                cause = text(R.string.error_night_03_cause),
                action = text(R.string.error_night_03_action),
                technical = false,
            )
        }

        // 3. Signal gaps beyond the tolerable total. Amber, and **with no button**.
        //
        //    It used to carry one — "Force continuous mode" — and its action sentence pointed to
        //    "Settings > Measurement". That setting exists nowhere: no preference on the phone
        //    side, no command to the watch, no reading on the watch side. The button was shown by
        //    `ErrorCard` in the night detail, where both action lambdas are `{}` — so it did
        //    nothing, and the sentence sent people looking for a screen that cannot be found. What
        //    remains is what the measurement allows us to say: the movements that fell into the
        //    gaps are not counted. The button will come back with the mode it commands.
        val totalS = (session?.gapTotalMs ?: 0L) / 1000.0
        if (totalS > Checks.MAX_TOTAL_GAPS_S) {
            return PendulumError(
                code = "E-NIGHT-04",
                title = text(R.string.error_night_04_title),
                cause = text(R.string.error_night_04_cause),
                action = text(R.string.error_night_04_action),
                technical = false,
            )
        }

        // 4. No hypnogram. It is the most frequent case and the least serious, hence the last one:
        //    the night counts, it carries its flag, and its sleep time is estimated.
        if (n.maskSource == Mapping.ACCEL_MASK) {
            return PendulumError(
                code = "E-NIGHT-01",
                title = text(R.string.error_night_01_title),
                cause = text(R.string.error_night_01_cause),
                action = text(R.string.error_night_01_action),
                technical = false,
            )
        }

        return null
    }
}
