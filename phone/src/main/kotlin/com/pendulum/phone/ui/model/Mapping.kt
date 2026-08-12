package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The translation from what the database knows into what the screens show.
 *
 * **This whole file is pure**: no `Context`, no Room, no `System.currentTimeMillis`. It is the same
 * discipline as `Aggregate` and `ComparabilityRule`, and for the same reason — the rule that
 * decides a night is "provisional" rather than "eligible" deserves a test on its exact bounds, not
 * a visual check on an emulator.
 *
 * What these functions **never** do: decide eligibility. It is already decided, in SQL, in the
 * `comparable_night` view, before any display code runs. Here we only make it readable.
 */
object Mapping {

    /**
     * A night, as the list and the trend show it.
     *
     * ### The three states, and why "provisional" is not "excluded"
     *
     * A comparable night whose `gate` is not `FULL` was measured correctly: what is missing is its
     * denominator, because the hypnogram has not arrived from Health Connect yet. It will be
     * recomputed on its own. Filing it with the excluded nights would make the most frequent case
     * at waking read as a failure.
     *
     * @param zone the night's time zone, read from the session — and not the current zone. A night
     *   spent abroad must be shown at the time it was lived, otherwise the list says the user went
     *   to bed at 4 in the morning.
     */
    fun nightUi(
        n: ComparableNight,
        endWallMs: Long?,
        sleepSource: UiText,
        flags: List<Flag> = emptyList(),
    ): NightUi {
        val zone = runCatching { ZoneId.of(n.zoneId) }.getOrDefault(ZoneId.of("UTC"))
        val start = Instant.ofEpochMilli(n.startWallMs).atZone(zone)
        val end = endWallMs?.let { Instant.ofEpochMilli(it).atZone(zone) }

        val state = when {
            !n.comparable -> NightState.EXCLUDED
            n.gate != GATE_FULL -> NightState.PROVISIONAL
            else -> NightState.ELIGIBLE
        }

        return NightUi(
            sessionHex = n.sessionHex,
            readableDate = start.format(DATE_FORMAT),
            shortDay = start.format(DAY_FORMAT),
            start = start.format(TIME_FORMAT),
            end = end?.format(TIME_FORMAT) ?: DASH,
            readableSleep = readableDuration(n.analysableTstMin),
            sleepSource = sleepSource,
            state = state,
            // Non-null if and only if the state is EXCLUDED: the model demands it, and showing a
            // reason next to a night that was kept would be incomprehensible.
            reason = if (state == NightState.EXCLUDED) reason(n.exclusionReason) else null,
            rhythmSec = rhythmSec(n),
            plmiCount = n.plmi,
            flags = flags,
            startWallMs = n.startWallMs,
            revealedAtMs = n.revealedAtMs,
        )
    }

    /**
     * A night's rhythm, or `null` when there is nothing to show.
     *
     * **`null` is the frequent case and not the exception**: `RhythmMeasurementTest` measures 2
     * accepted fits out of 20 nominal nights. The deconvolution refuses to return a period when the
     * interval train does not identify it, which is the quality we ask of it — but the interface
     * read `fundamentalSec` without consulting that refusal, and two of the six refusal reasons
     * leave a **finite** number in the column. A refused rhythm was therefore shown as a measured
     * rhythm, and an absent rhythm (`NaN`) was rounded to "0 s".
     *
     * The type now carries the rule: there is no value to show when the fit was not accepted, so no
     * screen can show one by inattention.
     */
    fun rhythmSec(n: ComparableNight): Double? =
        n.fundamentalSec?.takeIf { n.rhythmValid && it.isFinite() && it > 0.0 }

    /**
     * `21 s`, or the mention of the refusal. **The only place where a rhythm is formatted**: the
     * list, the detail and the export each wrote it their own way, and two of them rounded a `NaN`
     * to "0 s" — that is, they announced a rhythm of zero where there was none at all.
     */
    fun readableRhythm(sec: Double?): UiText =
        sec?.let { text(R.string.night_detail_rhythm_seconds, kotlin.math.round(it).toInt()) }
            ?: text(R.string.night_detail_rhythm_not_fitted)

    /**
     * `18/h`, or the dash — the counterpart of [readableRhythm] for the hourly count.
     *
     * It exists for the same reason: two screens rounded on their own, and a rounding applied to an
     * absence returns "0/h", that is, a night without a single movement. That is not what a night
     * without a denominator means.
     */
    fun readableCount(perHour: Double?): String =
        perHour?.let { "${Math.round(it)}/h" } ?: DASH

    /** `23:12`, in the time zone where the night was lived. */
    fun readableTime(ms: Long, zoneId: String): String =
        Instant.ofEpochMilli(ms).atZone(zoneOf(zoneId)).format(TIME_FORMAT)

    /** `12 March`, in the time zone where the night was lived. */
    fun readableDate(ms: Long, zoneId: String): String =
        Instant.ofEpochMilli(ms).atZone(zoneOf(zoneId)).format(DATE_FORMAT)

    /** `12 March, 07:04` — the instant of a reveal, as it will go out in the export. */
    fun readableInstant(ms: Long, zoneId: String): String =
        "${readableDate(ms, zoneId)}, ${readableTime(ms, zoneId)}"

    /**
     * The wearing side, translated from `night_context.leg`.
     *
     * There is no default value: a one-sided sensor sees a doubled interval when movements
     * alternate, so a guessed leg would falsify the comparability criterion without anything
     * flagging it. An unknown value stays unknown.
     */
    fun legLabel(leg: String?): UiText? = when (leg) {
        "LEFT" -> text(R.string.tonight_leg_left)
        "RIGHT" -> text(R.string.tonight_leg_right)
        else -> null
    }

    /**
     * Exclusion reasons: the translation of the `ComparabilityRule` identifiers, done here and
     * nowhere else. It used to live in the texts file; it now sits next to the rule it translates,
     * which makes it visible at a glance that no code is left without a sentence.
     */
    fun reason(code: String): UiText = when (code) {
        "NO_CONTEXT" -> text(R.string.nights_reason_no_context)
        "LEG_CHANGED" -> text(R.string.nights_reason_leg_changed)
        "STRAP_CHANGED" -> text(R.string.nights_reason_strap_changed)
        "NOT_ALONE" -> text(R.string.nights_reason_not_alone)
        "CAL_GAIN_UNKNOWN" -> text(R.string.nights_reason_cal_gain_unknown)
        "CAL_GAIN_OUT_OF_TOLERANCE" -> text(R.string.nights_reason_cal_gain_out_of_tolerance)
        "TOO_SHORT" -> text(R.string.nights_reason_too_short)
        "DST_NIGHT" -> text(R.string.nights_reason_dst_night)
        else -> text(R.string.nights_reason_default)
    }

    private fun zoneOf(zoneId: String): ZoneId =
        runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())

    /**
     * A night's quality flags. These are **not** errors: they are measured properties, shown so
     * that the user knows under which conditions their number was obtained. At most three are
     * visible on screen; the list comes out in order of severity.
     */
    fun flags(
        n: ComparableNight,
        gapCount: Int,
        gapTotalMs: Long,
        batteryPctLast: Int?,
    ): List<Flag> = buildList {
        // The accelerometric mask first: it is the one that changes the nature of the number, since
        // the denominator then stops being independent of the numerator.
        if (n.maskSource == ACCEL_MASK) add(Flag(text(R.string.nights_flag_accel_mask)))
        if (n.truncated) add(Flag(text(R.string.nights_flag_truncated)))
        if (gapTotalMs > 0) {
            add(Flag(text(R.string.nights_flag_gap, (gapTotalMs / 1000).toInt(), gapCount)))
        }
        if (batteryPctLast != null && batteryPctLast < LOW_BATTERY_THRESHOLD_PCT) {
            add(Flag(text(R.string.nights_flag_battery, batteryPctLast)))
        }
        // No flag when the miss rate is unknown: an absent flag already says "nothing to report",
        // and raising one on a value that does not exist would signal a measurement.
        n.missRate?.let { rate ->
            if (rate > NOTABLE_MISS_RATE_THRESHOLD) {
                add(Flag(text(R.string.nights_flag_missed, Math.round(rate * 100).toInt())))
            }
        }
    }

    /**
     * The aggregate of a quantity over a set of nights, or `null` below the minimum.
     *
     * Returning `null` and not a degraded `Result` is the point: there is no aggregated value below
     * three nights, so no code path can show one by inattention. The type carries the rule.
     *
     * ### Nights without a value drop out before the count, not after
     *
     * [valueOf] returns `null` when the night does not carry the quantity — no analysable sleep, or
     * a refused rhythm fit. Those nights are **removed**, and the minimum of three applies to what
     * is left. Counting them would have been the worst of both worlds: `Aggregate.median` sorts
     * `NaN` to the end, so a single night without a value could become the median itself and turn
     * the whole interval into `NaN` — a whole screen of dashes with nothing to say why. Replacing
     * them with zero would have pulled the median down and announced an improvement that never
     * happened.
     */
    fun aggregate(
        quantity: Aggregate.Quantity,
        nights: List<ComparableNight>,
        valueOf: (ComparableNight) -> Double?,
    ): Aggregate.Result? {
        val measured = nights.filter { valueOf(it) != null }
        if (measured.size < Aggregate.MIN_NIGHTS_AGGREGATE) return null
        val values = DoubleArray(measured.size) { valueOf(measured[it])!! }
        val seed = Aggregate.seedOf(measured.map { it.sessionHex })
        val (low, high) = Aggregate.bootstrapCi(values, seed)
        val dispersion = Aggregate.dispersion(values)
        return Aggregate.Result(
            quantity = quantity,
            median = Aggregate.median(values),
            ciLow = low,
            ciHigh = high,
            // `measured` and not `nights`: the `n` shown next to the interval is the number of
            // values that produced it. Counting the nights without a value would inflate it, and
            // the MDC95 — which divides by the square root of that `n` — would announce a precision
            // it does not have.
            nights = measured.size,
            dispersion = dispersion,
            mdc95 = Aggregate.mdc95(dispersion, measured.size),
        )
    }

    /**
     * `5 h 12` — never `5.2 h` nor `312 min`.
     *
     * A sleep duration is read in hours and minutes; a decimal of an hour forces a mental
     * conversion, and a duration in minutes alone cannot be compared at a glance.
     */
    fun readableDuration(minutes: Double): String {
        if (minutes <= 0.0) return DASH
        val total = kotlin.math.round(minutes).toInt()
        return "%d h %02d".format(Locale.UK, total / 60, total % 60)
    }

    /**
     * The label of the sleep source, **produced here and nowhere else**.
     *
     * There is no third case: either an external sleep period supplied the denominator, or it is
     * Pendulum's own accelerometric mask, and that has to be said — it is the only piece of
     * information that tells whether the number rests on an independent denominator.
     *
     * ### Why the fallback is no longer "unidentified source"
     *
     * It used to be, and a night's detail then showed **two different labels for the same field**:
     * the "why this number" block went through this function and returned `SOURCE_INCONNUE`, while
     * the quality check row, three cards below, wrote `HEALTH_CONNECT` hard-coded for the same
     * night. Two values on one screen are two values one of which is wrong, and nothing said which.
     *
     * The right one is `HEALTH_CONNECT`. What the missing package makes unknown is **which
     * application** published the session, not where the denominator comes from: `maskSource` says
     * so, it comes from the database, and it is the only fact that carries numerator/denominator
     * independence. Writing "unidentified source" next to a check that goes green would cast doubt
     * on the check. The package name is still shown as soon as it is known.
     */
    fun sourceLabel(maskSource: String, packageName: String?): UiText = when {
        maskSource == ACCEL_MASK -> text(R.string.settings_accel_mask_only)
        packageName.isNullOrBlank() -> text(R.string.settings_health_connect)
        else -> text(appName(packageName))
    }

    /**
     * The readable name of an Android package: `com.google.android.apps.fitness` gives `Fitness`.
     *
     * Extracted from [sourceLabel] so that the settings can use it too. They showed the **raw**
     * package while the night list showed "Fitness": the same source carried two names depending on
     * the screen, and the whole package did not fit the width — it ran into its own heading and was
     * cut mid-word.
     *
     * The name comes from Health Connect: it is not translated.
     */
    fun appName(packageName: String): String =
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    /**
     * `2026-03-12`: the day of a night, for a **file name**.
     *
     * This is not [readableDate], and the two must not be confused: "12 March" reads, does not
     * sort, and depends on the locale. A folder of exports must order itself.
     */
    fun isoDay(ms: Long, zoneId: String): String {
        val zone = runCatching { java.time.ZoneId.of(zoneId) }
            .getOrDefault(java.time.ZoneId.systemDefault())
        return java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
    }

    /**
     * A size on disk, in decimal units (MB = 10^6 bytes) and not binary ones.
     *
     * It is the unit Android shows in its own storage settings, and the erasure screen will be read
     * next to that one: two different numbers for the same thing would cast doubt on the more
     * alarming of the two, which is precisely ours.
     */
    fun readableBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(Locale.UK, bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.0f MB".format(Locale.UK, bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.0f kB".format(Locale.UK, bytes / 1_000.0)
        else -> "$bytes B"
    }

    /** `FULL`: the night is allowed to carry a number. See `PublicationGate` in `:algo`. */
    const val GATE_FULL = "FULL"

    /** `MaskSource.ACCEL_IMMOBILITY`: the denominator comes from the same sensor as the numerator. */
    const val ACCEL_MASK = "ACCEL_IMMOBILITY"

    /**
     * Beyond this, the miss rate estimated by the deconvolution deserves to be flagged: the night
     * is still usable, but one movement in five was not seen, and two nights whose miss rates
     * differ widely are not quite the same measurement.
     */
    const val NOTABLE_MISS_RATE_THRESHOLD = 0.20

    /**
     * Below this level at the end of the night, the watch probably stopped measuring before waking.
     * This is information about the night, not an alert: `StopConditions` has already decided the
     * stop, and movements concentrate in the second half of the night, so the number is most likely
     * under-estimated.
     */
    const val LOW_BATTERY_THRESHOLD_PCT = 10

    /**
     * The em dash, once and only once.
     *
     * It does not mean zero, it means "no value", and that is why it deserves a constant: three
     * files each declared it on their own side, and an ASCII `-` slipping into one of the three
     * would pass review unnoticed.
     */
    const val DASH = "—"

    /** `99.0%`, with the decimal punctuation pinned. See [readableDuration] for the reason. */
    fun percent(v: Double): String = "%.1f%%".format(Locale.UK, v * 100)

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
}

/** Reading shortcut: the reason identifiers stay the rule's own, never copied out. */
val ComparableNight.excluded: Boolean get() = exclusionReason != ComparabilityRule.OK
