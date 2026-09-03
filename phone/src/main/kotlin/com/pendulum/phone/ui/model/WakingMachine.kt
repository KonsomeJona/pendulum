package com.pendulum.phone.ui.model

import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.work.FetchSchedule

/**
 * The five-state waking machine. **A pure function, two inputs, no side effects.**
 *
 * ### Why it is not buried in the ViewModel
 *
 * It is the most read logic in the application: it decides what the user sees every morning, in the
 * state where they are least able to sort things out. The bounds that matter — "all the chunks have
 * arrived", "the hypnogram is here", "we have passed T+36 h" — are exactly the kind of conditions
 * that break silently when re-reading a thirty-line `when`. Here they are tested one by one,
 * without Android, without a database.
 *
 * ### The order of the branches is the message
 *
 * We read **the persisted state before the clock**, and the most advanced before the least
 * advanced: an analysed night is never "awaiting transfer", even if a late chunk arrives
 * afterwards. The reverse would produce a screen that goes backwards, and a screen that goes
 * backwards makes people believe something was lost.
 *
 * ### State 4 is the normal case, and nothing in its shape must say otherwise
 *
 * The synchronisation from the wrist watch to Health Connect obeys the manufacturer's battery
 * policy, with no guaranteed delay — often several hours. Three consequences held here:
 *
 * 1. The word is "provisional", not "failed".
 * 2. Nothing red: [WakingState.Provisional] is not a [WakingState.Failure], so it does not go
 *    through `ErrorCard` and cannot inherit its tint.
 * 3. **No indeterminate progress indicator.** Material 3 calibrates its *loading indicator* for
 *    waits of under five seconds; a circle spinning for six hours **is** a failure message,
 *    whatever the text next to it. What this state has to show, it shows in plain terms: last
 *    attempt, next attempt, and the dated deadline for giving up.
 */
object WakingMachine {

    /** `night_session.state`: the watch is still recording. */
    const val OPEN = "OPEN"

    /** No chunk for 45 min. The watch has gone quiet; it can come back. */
    const val SILENT = "STALE"

    /** The night stopped without a clean close. Analysable, but out of the trend. */
    const val TRUNCATED = "TRUNCATED"

    /**
     * Transfer throughput used for the estimate in minutes of state 1.
     *
     * **This is an estimate, not a measurement**, and that is why it is here and named. Wear OS's
     * Data Layer publishes no throughput; 200 kB/s is the order of magnitude observed on a classic
     * Bluetooth link with the watch on its dock. The screen rounds up to the minute and never shows
     * seconds: a precision to the second on a guessed figure would be exactly the kind of value one
     * then quotes in a defect report.
     */
    const val ESTIMATED_THROUGHPUT_BYTES_PER_S = 200_000L

    /** Average size of a chunk, used when no byte has arrived yet. */
    const val ESTIMATED_CHUNK_SIZE_BYTES = 1_200_000L

    /**
     * What the database knows about the last night, reduced to what the machine needs.
     *
     * @param sessionState `night_session.state`, as is.
     * @param totalChunks announced by the watch on closing. `null` as long as it has not closed:
     *   neither a fraction nor a remaining count can then be computed.
     * @param sleepMaskApplied there is a `sleep_window` from a Health Connect source for the
     *   current `paramsHash`. It is the only fact that says the number shown rests on an
     *   independent denominator — a successful `hc_snapshot` is not enough, the rescore may not
     *   have taken place.
     * @param hypnogramReceived the last `hc_snapshot` kept a record. Used to distinguish "nothing
     *   arrived" from "it arrived, the rescore follows".
     * @param integrityRejected fraction of the bytes rejected by the integrity check.
     */
    data class Facts(
        val sessionHex: String,
        val readableDate: String,
        /** The night's time zone, as the session carries it. A night is read at the time lived. */
        val zoneId: String,
        val sessionState: String,
        val chunksReceived: Int,
        val totalChunks: Int?,
        val bytesReceived: Long,
        val analysedAtMs: Long?,
        val nightEndMs: Long?,
        val sleepMaskApplied: Boolean,
        val hypnogramReceived: Boolean,
        val hcAttempts: Int,
        val lastAttemptMs: Long?,
        val integrityRejected: Double,
    )

    /** Beyond this, the analysis did not assemble a usable signal: it is a failure, in red. */
    const val MAX_INTEGRITY_REJECTED = 0.05

    /**
     * @param formatTime the formatting of an instant, injected rather than called: the machine
     *   computes instants (next attempt, give-up deadline) and must stay testable without a time
     *   zone or a locale. It is the same pattern as the parameterised clock of `HomeMachine`.
     */
    fun of(facts: Facts?, nowMs: Long, formatTime: (Long) -> String): WakingState {
        if (facts == null) return WakingState.None

        // The watch is recording: there is no "waking" to announce. The home card already carries
        // the recording in progress, and repeating it here would steal the single line P4 grants to
        // the state of last night.
        if (facts.sessionState == OPEN) return WakingState.None

        val failure = possibleFailure(facts)
        if (failure != null) return WakingState.Failure(facts.readableDate, failure)

        val total = facts.totalChunks
        val transferDone = total != null && facts.chunksReceived >= total

        if (facts.analysedAtMs == null) {
            return when {
                // State 1: the watch has closed and nothing has arrived yet — or it has not closed
                // at all (`STALE`, no chunk for 45 min), in which case `totalChunks` is unknown,
                // whatever number of chunks is already here.
                //
                // The estimate exists only in the first case. With no total, `estimatedTotalBytes`
                // has nothing to extrapolate from and falls back on the bytes received, so the
                // remainder was zero and the strip read "0.0 MB to transfer … Allow 1 minutes"
                // for a night the watch had not even closed. The two figures now stay `null`
                // together: one estimate, present or absent as a whole.
                total == null || facts.chunksReceived == 0 -> {
                    val remaining = total?.let { remainingBytes(facts) }
                    WakingState.AwaitingTransfer(
                        date = facts.readableDate,
                        mb = remaining?.let { megabytes(it) },
                        minutes = remaining?.let { estimatedMinutes(it) },
                    )
                }

                // State 2: it is arriving. The percentage never goes backwards — it is computed on
                // the number of chunks present in the database, and a chunk already received does
                // not disappear.
                !transferDone ->
                    WakingState.Transfer(
                        receivedMb = megabytes(facts.bytesReceived),
                        totalMb = megabytes(estimatedTotalBytes(facts)),
                        chunk = facts.chunksReceived,
                        chunks = total,
                    )

                // State 3: everything is here, the analysis is running. The step shown is deduced
                // from what is already in the database and not from a progress counter that nothing
                // would feed: the chunks are assembled, so we are detecting; the hypnogram is here,
                // so we are cross-referencing.
                else -> WakingState.Analysis(
                    date = facts.readableDate,
                    step = if (facts.hypnogramReceived) AnalysisStep.CROSSREF else AnalysisStep.DETECTION,
                    secondsRemaining = ESTIMATED_ANALYSIS_SECONDS,
                )
            }
        }

        // State 5 -> complete: the independent sleep mask is applied, the night is finished.
        if (facts.sleepMaskApplied) return WakingState.None

        // State 4: the normal case at waking.
        val end = facts.nightEndMs ?: return WakingState.None
        val plan = FetchSchedule.plan(facts.hcAttempts, end, nowMs)
        val giveUpMs = end + FetchSchedule.GIVE_UP_MS

        return WakingState.Provisional(
            date = facts.readableDate,
            lastAttempt = facts.lastAttemptMs?.let(formatTime),
            nextAttempt = (plan as? FetchSchedule.Plan.Retry)
                ?.let { formatTime(nowMs + it.delayMs) },
            givingUpAt = formatTime(giveUpMs),
            gaveUp = plan is FetchSchedule.Plan.GiveUp,
        )
    }

    /**
     * The two failures the database actually allows us to observe.
     *
     * We forbid ourselves from guessing any others. "The analysis failed" can be read nowhere: an
     * `AnalyzeWorker` that returns `retry` leaves no trace in the database, so inferring it from an
     * elapsed delay would produce a red card on a perfectly healthy night whose phone was simply
     * busy. A failure shown wrongly costs more than a failure kept quiet: it teaches people to
     * ignore red.
     */
    private fun possibleFailure(facts: Facts): PendulumError? {
        if (facts.integrityRejected > MAX_INTEGRITY_REJECTED) {
            return PendulumError(
                code = "E-ANA-01",
                title = text(R.string.error_ana_01_title),
                cause = text(R.string.error_ana_01_cause),
                action = text(R.string.error_ana_01_action),
                button = text(R.string.error_ana_01_button),
                technical = true,
            )
        }
        val total = facts.totalChunks
        if (facts.sessionState == TRUNCATED && total != null && facts.chunksReceived < total) {
            return PendulumError(
                code = "E-NIGHT-07",
                title = text(R.string.error_night_07_title),
                cause = text(R.string.error_night_07_cause),
                action = text(R.string.error_night_07_action),
                button = text(R.string.error_night_07_button),
                technical = true,
            )
        }
        return null
    }

    /** Estimate in seconds of the remaining analysis. Fixed: nothing measures its duration yet. */
    const val ESTIMATED_ANALYSIS_SECONDS = 40

    private fun estimatedTotalBytes(facts: Facts): Long {
        val total = facts.totalChunks ?: return facts.bytesReceived
        if (facts.chunksReceived <= 0) return total * ESTIMATED_CHUNK_SIZE_BYTES
        // Extrapolation on the average size **observed** for that particular night, and not on a
        // constant: the number of samples per chunk varies with the FIFO mode.
        return facts.bytesReceived / facts.chunksReceived * total
    }

    private fun remainingBytes(facts: Facts): Long =
        (estimatedTotalBytes(facts) - facts.bytesReceived).coerceAtLeast(0L)

    /** One digit after the point: `8.8`. Beyond that, we would be showing counting noise. */
    internal fun megabytes(bytes: Long): String =
        "%.1f".format(java.util.Locale.UK, bytes / 1_000_000.0)

    /** Rounded up to the minute, never zero: "0 minutes" reads as "it is over". */
    internal fun estimatedMinutes(bytes: Long): Int {
        val seconds = bytes.toDouble() / ESTIMATED_THROUGHPUT_BYTES_PER_S
        return kotlin.math.ceil(seconds / 60.0).toInt().coerceAtLeast(1)
    }
}
