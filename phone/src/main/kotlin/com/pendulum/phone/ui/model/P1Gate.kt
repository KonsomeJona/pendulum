package com.pendulum.phone.ui.model

import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.TelemetryPointEntity
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The P1 gate, made **computable** — that is, verifiable without anyone's memory.
 *
 * `01-overview.md` §5 declares P1 blocking and gives it three criteria: coverage of at least 99 %
 * of the expected samples **measured on the deltas of `SensorEvent.timestamp`**, battery above 20 %
 * at eight hours, and reproducibility over **three consecutive nights**. No line of algorithm is
 * supposed to be written before those three are held, and it is nevertheless the only milestone of
 * the project that no code checked.
 *
 * ### What this file can do, and what it cannot
 *
 * It does not make anyone wear the watch. It makes the verdict **readable and exportable**, so that
 * the decision that follows — carry on with Wear OS, or switch to a dedicated recorder of the
 * Axivity AX3 kind — rests on a file rather than on an impression.
 *
 * ### Everything is pure
 *
 * No `Context`, no clock, no I/O: this gate's bounds are exactly the kind of conditions that break
 * silently, and they are tested one by one. The coverage itself is not recomputed here — it is
 * already in [Checks.coverage], which applies the sensor time rule and carries its own
 * justification. Two implementations of the same percentage would end up diverging, and the
 * divergence would land on the number that decides the rest of the project.
 */
object P1Gate {

    /**
     * Three **consecutive** nights, and not three nights that pass.
     *
     * The distinction is not a strict reading of the document, it is the only one that measures
     * what the gate wants to measure. "Three compliant nights" is satisfied by one successful night
     * in March, one in April and one in May, that is, by three separate strokes of luck separated
     * by failures nobody counts. Reproducibility is precisely the property that distinguishes a
     * setup that holds from a setup that held once.
     *
     * An unrecorded night therefore breaks the run just as a non-compliant night does: the gate
     * asks for three nights **in a row**, and skipping the night that follows a failure is exactly
     * the gesture this project's guard rails exist to make impossible.
     */
    const val CONSECUTIVE_NIGHTS = 3

    /** Eight hours: the duration over which the gate asks for the remaining battery. */
    const val TARGET_DURATION_H = 8.0

    /**
     * Fourteen nights on screen. Two weeks: enough to contain a run of three and the failures
     * around it, which is precisely what has to be seen together. The CSV export, for its part,
     * truncates nothing.
     */
    const val REPORT_NIGHTS = 14

    /**
     * Three states and not two.
     *
     * [UNDETERMINED] is not a convenience: a six-hour night says nothing about the battery at eight
     * hours, and filing it with the compliant nights would make the gate be crossed by a campaign
     * that did not cross it. Filing it with the failures would accuse a setup that nothing proves
     * has failed. A gate that does not know must say so.
     */
    enum class Compliance { COMPLIANT, NON_COMPLIANT, UNDETERMINED }

    /** A criterion: its measured value, its threshold, its state. All three, always — like [Checks]. */
    data class Criterion(
        val label: UiText,
        val value: UiText,
        val threshold: UiText,
        val state: Compliance,
    )

    /**
     * The verdict for one night.
     *
     * @param evening the date of the **evening** the night attaches to, rolling over at noon, in
     *   the time zone where the night was lived. It is what carries consecutiveness: going to bed
     *   at 1:30 am belongs to the previous evening, and counting two evenings where there is only
     *   one would artificially lengthen a streak.
     */
    data class NightVerdict(
        val sessionHex: String,
        val readableDate: String,
        val evening: LocalDate,
        val coverage: Criterion,
        val battery: Criterion,
        val frequency: Criterion,
    ) {
        val criteria: List<Criterion> get() = listOf(coverage, battery, frequency)

        /**
         * The worst of the three, in this order: a failure beats an unknown, and an unknown beats a
         * success. A night whose battery fell below the threshold is not "undetermined" because its
         * frequency is missing.
         */
        val verdict: Compliance
            get() = when {
                criteria.any { it.state == Compliance.NON_COMPLIANT } -> Compliance.NON_COMPLIANT
                criteria.any { it.state == Compliance.UNDETERMINED } -> Compliance.UNDETERMINED
                else -> Compliance.COMPLIANT
            }
    }

    /**
     * The campaign verdict.
     *
     * @param longestStreak the longest run of compliant nights **on consecutive evenings**. It is
     *   the only figure that answers the question the gate asks; [compliantNights] is there for
     *   context and crosses nothing on its own.
     */
    data class Campaign(
        val nightsExamined: Int,
        val compliantNights: Int,
        val longestStreak: Int,
        val streakStart: String?,
        val streakEnd: String?,
    ) {
        val crossed: Boolean get() = longestStreak >= CONSECUTIVE_NIGHTS
    }

    /**
     * @param telemetry this night's points from `telemetry_point`, or the empty list.
     *
     * The default is **the empty list and not a mandatory parameter**, for a reason that is not
     * calling convenience: a night recorded before telemetry existed — or by a watch whose chunks
     * are in v1 of the format — has none and never will. The verdict must remain computable on it,
     * with the three exact readings that `batteryPctLast` allows. What an empty list must **not**
     * produce is an extrapolated figure: it is [BatterySlope] that refuses, not this file that
     * guesses.
     */
    fun of(
        session: NightSessionEntity,
        telemetry: List<TelemetryPointEntity> = emptyList(),
    ): NightVerdict {
        val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.systemDefault())
        return NightVerdict(
            sessionHex = session.sessionHex,
            readableDate = Mapping.readableDate(session.startWallMs, session.zoneId),
            // The night key from `WirePaths`, and not the civil date of the start: it is the
            // convention that already attaches a night to its sealed context, and laying down a
            // second one here would produce two calendars in the same application.
            evening = LocalDate.parse(WirePaths.nightKey(session.startWallMs, zone)),
            coverage = coverage(session),
            battery = battery(session, telemetry),
            frequency = frequency(session),
        )
    }

    fun campaign(verdicts: List<NightVerdict>): Campaign {
        val sorted = verdicts.sortedBy { it.evening }
        var current = ArrayList<NightVerdict>()
        var best = emptyList<NightVerdict>()
        for (v in sorted) {
            val previous = current.lastOrNull()
            current = when {
                v.verdict != Compliance.COMPLIANT -> ArrayList()
                // Exactly the day before. A skipped evening breaks the streak; a repeated evening —
                // two sessions attached to the same night — does not lengthen it.
                previous != null && v.evening == previous.evening.plusDays(1) ->
                    current.apply { add(v) }
                else -> arrayListOf(v)
            }
            if (current.size > best.size) best = current.toList()
        }
        return Campaign(
            nightsExamined = verdicts.size,
            compliantNights = verdicts.count { it.verdict == Compliance.COMPLIANT },
            longestStreak = best.size,
            streakStart = best.firstOrNull()?.readableDate,
            streakEnd = best.lastOrNull()?.readableDate,
        )
    }

    // -------------------------------------------------------------------------------------
    // The three criteria
    // -------------------------------------------------------------------------------------

    /**
     * Sample coverage, as [Checks.coverage] computes it — over sensor time, never over arrival
     * time — and judged by [Checks.coverageMet].
     *
     * Neither the computation nor the comparator is redone here. Two writings of one threshold
     * return two verdicts on the same night, and the disagreement only shows at the moment of
     * deciding.
     */
    private fun coverage(session: NightSessionEntity): Criterion {
        val c = Checks.coverage(session)
        return Criterion(
            label = text(R.string.night_detail_coverage),
            value = text(c?.let(::percent) ?: DASH),
            threshold = text(R.string.p1_at_least, percent(Checks.MIN_COVERAGE)),
            state = verdict(Checks.coverageMet(c)),
        )
    }

    /**
     * A predicate with three outcomes. `null` is **we do not know**, never "not held": a night
     * whose rate was not measured has not failed, it has not been judged.
     */
    private fun verdict(met: Boolean?): Compliance = when (met) {
        null -> Compliance.UNDETERMINED
        true -> Compliance.COMPLIANT
        false -> Compliance.NON_COMPLIANT
    }

    /**
     * The remaining battery **at eight hours**. Two paths, and the first is the one that decides.
     *
     * ### 1. The slope, when the night's telemetry carries it
     *
     * `batteryChargeUah` is regressed on time, points under charge excluded, and the line is
     * evaluated at eight hours: see [BatterySlope], which carries the assumptions of the
     * extrapolation and the number of points below which it refuses to conclude. It is this path
     * that makes the criterion measurable on a short night — the percentage, for its part, does not
     * move by a step in half an hour (`docs/workings/BENCH-LOG.md` §12.4: `level: 100` at all six
     * measurement steps) — and **without keeping the watch on its dock**, which is the only way to
     * measure a runtime.
     *
     * The verdict is then plain: above the threshold or below. The extrapolation does not return
     * `UNDETERMINED` when it succeeds; it is [BatterySlope] that returns `null` when it has nothing
     * to conclude from, and we then fall back on path 2.
     *
     * ### 2. The last percentage, when it is all there is
     *
     * That is the case of a night recorded before telemetry existed, or of a night whose discharge
     * was too short or too often interrupted by a charge. Three readings remain, all exact:
     *  - the night lasted eight hours or more: the reported level **is** the level at eight hours;
     *  - the night was shorter and the level is already at or below the threshold: it will not go
     *    back up, so the night fails, and asserting it assumes nothing;
     *  - the night was shorter and the level still holds: we do not know, and we say so.
     *
     * The comparator is **strict** on both paths — exactly 20 % fails. That is the reading of
     * `01-overview.md` §5 ("battery above 20 %"), and it is the one in [Checks.MIN_BATTERY_PCT],
     * where it carries its justification. Both files must return the same verdict on the same
     * night, and the path taken must not change the bound.
     */
    private fun battery(
        session: NightSessionEntity,
        telemetry: List<TelemetryPointEntity>,
    ): Criterion {
        val threshold = text(
            R.string.p1_battery_threshold,
            Checks.MIN_BATTERY_PCT,
            TARGET_DURATION_H.toInt(),
        )
        val label = text(R.string.night_detail_battery_end)

        BatterySlope.of(telemetry, TARGET_DURATION_H)?.let { s ->
            return Criterion(
                label = label,
                value = text(
                    R.string.p1_battery_extrapolated,
                    "%.0f%%".format(Locale.UK, s.pctAt8h),
                    TARGET_DURATION_H.toInt(),
                    "%.1f%%".format(Locale.UK, s.pctPerHour),
                    s.pointsKept,
                ),
                threshold = threshold,
                state = if (s.pctAt8h > Checks.MIN_BATTERY_PCT) {
                    Compliance.COMPLIANT
                } else {
                    Compliance.NON_COMPLIANT
                },
            )
        }

        val pct = session.batteryPctLast
        val hours = recordedHours(session)
        val reachedEightHours = hours != null && hours >= TARGET_DURATION_H
        return Criterion(
            label = label,
            value = text(
                when {
                    pct == null -> DASH
                    hours == null -> "$pct%"
                    else -> "$pct%  ·  ${Mapping.readableDuration(hours * 60.0)}"
                },
            ),
            threshold = threshold,
            // The only one of the three criteria that does not reduce to [verdict]: a level that
            // holds only concludes if the night actually reached eight hours.
            state = when (Checks.batteryMet(pct)) {
                null -> Compliance.UNDETERMINED
                false -> Compliance.NON_COMPLIANT
                true -> if (reachedEightHours) Compliance.COMPLIANT else Compliance.UNDETERMINED
            },
        )
    }

    /**
     * The delivered rate against the requested rate. It is not a written criterion of P1, and it is
     * here because it conditions the other two: a wrong `fs` shifts the whole timestamping of the
     * movements, and a coverage computed on a nominal the sensor does not hold measures the
     * nominal, not the sensor.
     */
    private fun frequency(session: NightSessionEntity): Criterion {
        val nominal = session.nominalRateHz
        return Criterion(
            label = text(R.string.night_detail_frequency),
            value = text(Checks.readableRate(session.fsMeasuredHz)),
            threshold = text(R.string.p1_frequency_threshold, nominal, percent(Checks.FS_TOLERANCE)),
            state = verdict(Checks.rateMet(session.fsMeasuredHz, nominal)),
        )
    }

    /**
     * The night's duration in hours, or `null` as long as it has no known end.
     *
     * It is a wall clock duration, the same approximation [Checks.coverage] assumes: the gap with
     * sensor duration is of the order of a second over eight hours, that is three orders of
     * magnitude below what would separate a seven hour fifty night from an eight hour one.
     */
    internal fun recordedHours(session: NightSessionEntity): Double? {
        val end = session.endWallMs ?: return null
        val ms = end - session.startWallMs
        return if (ms <= 0) null else ms / 3_600_000.0
    }

    private const val DASH = Mapping.DASH

    private fun percent(v: Double) = Mapping.percent(v)
}
