package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.ui.nights.Check
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text
import java.util.Locale

/**
 * The list of quality checks for a night — **this is where the "why this number" lives**.
 *
 * Every row carries the three things together: the measured value, the threshold it must hold, and
 * its state. All three, always. A value without its threshold cannot be judged, and a state without
 * its value cannot be verified.
 *
 * The explanation is **generative and not attributive**: it shows the computation path, not
 * contributions. We do not rank factors by importance, we do not say that a signal gap "explains" a
 * low index. We say what was measured, what was kept, and on what denominator — the eye makes the
 * link, the text does not.
 *
 * Everything is pure: no `Context`, no clock, no I/O. Sample coverage in particular deserves to be
 * tested on its bounds, because it is what decides whether the P1 gate is crossed.
 */
object Checks {

    /**
     * Sample coverage, over **sensor time** and never over arrival time.
     *
     * This is the non-negotiable rule of the P1 gate, and it is the same one `GapMonitor` applies
     * on the watch side: in batched mode the samples arrive in bursts — thirty seconds of silence
     * then 1,500 events at once — and a rule based on delivery time fires every night while
     * measuring nothing.
     *
     * The denominator is therefore the **nominal** duration of the session multiplied by the
     * requested rate. It remains an approximation: wall clock duration is not exactly sensor
     * duration when the system clock is adjusted during the night. The gap is of the order of a
     * second over eight hours, that is three orders of magnitude below the 99 % criterion, and
     * correcting it would require persisting the bounds of `SensorEvent.timestamp` which the format
     * already carries but the database does not extract.
     *
     * ### The numerator only exists after the analysis, and keeping quiet about it cost a false verdict
     *
     * `night_session.sampleCount` is written by `AnalyzeWorker` and by it alone. As long as it has
     * not run, the column is 0 — not because no sample arrived, but because nobody has counted them
     * yet. This file then divided 0 by the denominator and returned `0.0`, which [coverageMet]
     * judges `false`: an **unanalysed** night was reported as "outside P1".
     *
     * The defect is not theoretical. On 3 August 2026, at the gate's first real use, a perfectly
     * transferred 32-minute night — seven chunks, seven acknowledgements, 94,502 samples on the
     * phone's disk — came out `NON_COMPLIANT` with `coverage=0.00000`
     * (`docs/workings/BENCH-LOG.md` §12.5). The analysis had not run: `analyzedAtMs` was null fifty
     * minutes after closing, the application being in the `RESTRICTED` standby bucket where it
     * lands because nobody ever opens it — which is **exactly** the use case the product describes.
     *
     * The test is therefore `analyzedAtMs`, and not `sampleCount > 0`: an analysed night whose
     * analysis effectively kept no sample has a coverage of 0 %, and that one is true.
     * Distinguishing the two is the whole point of the three states of [P1Gate.Compliance] — "we do
     * not know" is not a convenience.
     *
     * @return the coverage in `[0, 1]`; `null` if the night has no known end — an open session has
     *   no coverage, it has a coverage *so far* — or if the analysis has not run yet, in which case
     *   the numerator has not been counted.
     */
    fun coverage(session: NightSessionEntity): Double? {
        if (session.analyzedAtMs == null) return null
        val end = session.endWallMs ?: return null
        val durationMs = end - session.startWallMs
        if (durationMs <= 0 || session.nominalRateHz <= 0) return null
        val expected = durationMs * session.nominalRateHz / 1000.0
        if (expected <= 0.0) return null
        return (session.sampleCount / expected).coerceIn(0.0, 1.0)
    }

    /** P1 gate threshold: at least 99 % of the expected samples. */
    const val MIN_COVERAGE = 0.99

    /**
     * P1 gate threshold: **strictly more** than 20 % battery left at eight hours.
     *
     * The comparison is strict because `01-overview.md` §5 writes "battery **above** 20 %" where
     * coverage is written "**at or above** 99 %" — the difference between the two wordings is
     * carried by the document and is not a drafting slip. At exactly 20 %, the code returned
     * "compliant" and the documentation "failure": the exact bound is precisely the one that flips
     * silently, since it only happens one night in fifty and never looks like a defect. The
     * disagreement is settled in favour of the document, which is what P1 means.
     */
    const val MIN_BATTERY_PCT = 20

    /** Tolerated gap between the requested rate and the delivered rate. */
    const val FS_TOLERANCE = 0.05

    // -------------------------------------------------------------------------------------
    // The three predicates of the P1 gate, written once
    // -------------------------------------------------------------------------------------
    //
    // They are here and not in [P1Gate] for the reason that file already gives for not recomputing
    // coverage: two implementations of one threshold end up diverging, and the divergence lands on
    // the number that decides the rest of the project. The battery threshold did it — two `>=`
    // where the document writes "above" — and it only surfaced on reading them side by side.
    //
    // `null` means **we do not know**, and not "not held": the check row then returns a dash, and
    // the gate returns `UNDETERMINED`. Both screens read the same unknown.

    /** True if the coverage reaches the threshold, bounds included. `null` if it is unknown. */
    fun coverageMet(coverage: Double?): Boolean? = coverage?.let { it >= MIN_COVERAGE }

    /** True if the battery is **strictly** above the threshold. See [MIN_BATTERY_PCT]. */
    fun batteryMet(pct: Int?): Boolean? = pct?.let { it > MIN_BATTERY_PCT }

    /**
     * True if the delivered rate holds the tolerance. `null` when it was not measured, or when the
     * nominal rate is absurd — dividing by it would give a verdict, not a measurement.
     *
     * The requested rate is not the delivered rate: 50 Hz routinely comes out at 50.3 or 52.6 Hz,
     * and a wrong `fs` shifts the whole timestamping of the movements.
     */
    fun rateMet(fs: Double?, nominalHz: Int): Boolean? =
        if (fs == null || nominalHz <= 0) null
        else kotlin.math.abs(fs - nominalHz) / nominalHz <= FS_TOLERANCE

    /** The largest tolerable gap before the signal stops being usable. */
    const val MAX_LARGEST_GAP_S = 5.0

    /** Tolerable total of gaps over one night. */
    const val MAX_TOTAL_GAPS_S = 120.0

    /**
     * @param sleepSource the label **already resolved** by [Mapping.sourceLabel], and resolved once
     *   by the caller. This row used to hard-code it (`HEALTH_CONNECT` as soon as the mask was not
     *   the accelerometer) while the "why this number" block, on the same screen, went through
     *   `Mapping`: the same night therefore carried two different source labels, and nothing said
     *   which was the right one. The label has a single origin.
     */
    fun of(
        session: NightSessionEntity,
        night: ComparableNight,
        result: PlmResultEntity?,
        sleepSource: UiText,
    ): List<Check> = buildList {
        val cov = coverage(session)
        add(
            Check(
                label = text(R.string.night_detail_coverage),
                value = text(cov?.let { percent(it) } ?: DASH),
                threshold = text(percent(MIN_COVERAGE)),
                ok = coverageMet(cov),
            )
        )

        // The total of the gaps is known; the largest individual gap is not — `GapMonitor` measures
        // it on the watch but only its total comes back in the session. The row is kept with a dash
        // rather than removed: its disappearance would suggest the check does not exist, when in
        // fact it is simply not transmitted yet.
        add(
            Check(
                label = text(R.string.night_detail_largest_gap),
                value = text(DASH),
                threshold = text("%.0f s".format(Locale.UK, MAX_LARGEST_GAP_S)),
                // `null` and not `true`: nothing was measured here, so nothing is held. A `✓` on an
                // absent value asserts a check that never took place.
                ok = null,
            )
        )
        val totalS = session.gapTotalMs / 1000.0
        add(
            Check(
                label = text(R.string.night_detail_total_gaps),
                value = text("%.0f s".format(Locale.UK, totalS)),
                threshold = text("%.0f s".format(Locale.UK, MAX_TOTAL_GAPS_S)),
                ok = totalS <= MAX_TOTAL_GAPS_S,
            )
        )

        val fs = session.fsMeasuredHz
        add(
            Check(
                label = text(R.string.night_detail_frequency),
                value = text(readableRate(fs)),
                threshold = text("${session.nominalRateHz} Hz"),
                ok = rateMet(fs, session.nominalRateHz),
            )
        )

        val battery = session.batteryPctLast
        add(
            Check(
                label = text(R.string.night_detail_battery_end),
                value = text(battery?.let { "$it%" } ?: DASH),
                threshold = text("$MIN_BATTERY_PCT%"),
                ok = batteryMet(battery),
            )
        )

        // Analysable sleep, not recorded sleep: an 8 h night of which 5 h are gappy is not worth 8,
        // and it is that number which serves as the denominator.
        add(
            Check(
                label = text(R.string.night_detail_total_sleep),
                value = text(Mapping.readableDuration(night.analysableTstMin)),
                threshold = text("4 h"),
                ok = night.analysableTstMin >= MIN_TST_MIN,
            )
        )

        add(
            Check(
                label = text(R.string.night_detail_sleep_source),
                value = sleepSource,
                threshold = text(R.string.settings_health_connect),
                // The denominator must come from a **different** sensor than the numerator. When it
                // comes from the same one, the number is circular: a treatment that removes
                // movements lowers the numerator and, by the same gesture, raises the denominator.
                ok = night.maskSource != Mapping.ACCEL_MASK,
            )
        )

        // The miss rate is **measured** by the harmonic deconvolution, not assumed. It is both a
        // quality indicator and the criterion that says whether two nights measure the same thing:
        // two nights whose rates differ widely cannot be compared.
        //
        // When the rhythm fit was refused — the frequent case — there is no rate to show. The row
        // stays, with a dash and an unknown state: it is the same convention as the largest gap row
        // above, and for the same reason — a `✓` or a `✗` on an absent value asserts a check that
        // never took place.
        //
        // The rate is dropped because the fit was **refused**, not because `:algo` returned
        // nothing: on five of the six refusals the column holds a finite estimate, and reading it
        // raw put "90.0% / 20.0% ✗" here — the model's own ceiling, judged as a measurement — on a
        // night whose rhythm row, one card up, says "not fitted". `Mapping.missRate` is the gate.
        result?.let {
            val rate = Mapping.missRate(night)
            add(
                Check(
                    label = text(R.string.night_detail_missed_rate),
                    value = text(rate?.let { r -> percent(r) } ?: DASH),
                    threshold = text(percent(Mapping.NOTABLE_MISS_RATE_THRESHOLD)),
                    ok = rate?.let { r -> r <= Mapping.NOTABLE_MISS_RATE_THRESHOLD },
                )
            )
        }
    }

    /** Four hours. Below this threshold the index explodes on a handful of grouped movements. */
    const val MIN_TST_MIN = 240.0

    /** `50.31 Hz`, or the dash. Shared with [P1Gate], which shows the same value. */
    internal fun readableRate(fs: Double?): String =
        fs?.let { "%.2f Hz".format(Locale.UK, it) } ?: DASH

    private const val DASH = Mapping.DASH

    private fun percent(v: Double) = Mapping.percent(v)
}
