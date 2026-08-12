package com.pendulum.phone.ui.home

import androidx.compose.runtime.Immutable
import com.pendulum.phone.ui.model.TonightUi
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text

/**
 * The home screen: its state machine, and nothing else.
 *
 * **This whole file is pure.** No `Context`, no Room, no `System.currentTimeMillis` — the local
 * hour is a parameter. It is the same discipline as [com.pendulum.phone.ui.model.Mapping] and for
 * the same reason: the rule that decides we are "at the end of the night" rather than "getting
 * ready for the night" deserves a test on its bounds, not a visual check on an emulator at 23:58.
 *
 * ### Where the state comes from, and where it does not
 *
 * From the **persisted state machine**: `night_session.state` (`OPEN`, `STALE`, `CLOSED`,
 * `TRUNCATED`), `night_session.analyzedAtMs`, and the existence of the sealed context for the
 * current evening. Not from the clock.
 *
 * v1 did the opposite: the evening card appeared between 8 pm and 4 am and disappeared afterwards.
 * Two consequences, one of layout and one of correctness. Layout first: a card at the head of the
 * screen that appears and disappears **moves everything below it vertically, twice a day**, and a
 * target that moves has to be searched for again every time. Correctness next: someone working
 * nights goes to bed at 9 am, and a traveller changes time zone without changing habits. In both
 * cases the clock asserts the opposite of what the database knows.
 *
 * ### What the hour still does
 *
 * It **breaks a tie**, and only when the database leaves two equally plausible readings: no session
 * under way and nothing to analyse. We are then either in the evening — getting ready — or in the
 * daytime — there is nothing to do. [TonightUi.visibleAt] settles that case, and only that one.
 */

/**
 * Where the night stands, from the user's point of view.
 *
 * Four phases and not five: "night analysed, result not revealed" is not one of them, it is a
 * property of the last night. Confusing it with a phase would give two states indistinguishable on
 * screen and a `when` that contradicts itself.
 */
enum class HomePhase {
    /** Nothing is in flight, and the hour says the evening is under way. */
    PREPARATION,

    /** The watch is recording: `night_session.state == OPEN`. */
    RECORDING,

    /** A session is waiting to be closed, brought back, or analysed. */
    END_OF_NIGHT,

    /** Nothing is in flight, and the hour says the day is under way. */
    DAYTIME,
}

/** What the database knows of a session, reduced to what the home screen does with it. */
@Immutable
data class HomeSession(
    val sessionHex: String,
    /** `night_session.state` as it stands: `OPEN | CLOSED | STALE | TRUNCATED`. */
    val state: String,
    /** `analyzedAtMs != null`. A session closed but not analysed is still an end of night. */
    val analysed: Boolean,
    val readableStart: String,
    val readableDate: String,
)

/**
 * The persisted state, read in one go, before any display decision.
 *
 * The repository produces it, the machine transforms it, the screen consumes it. The split holds
 * because the reading is what a JVM test cannot check, and the decision is what it must check.
 */
@Immutable
data class HomeSource(
    /** The most recent session, all evenings taken together, or `null` if there has never been one. */
    val recentSession: HomeSession?,
    val contextSealed: Boolean,
    /** Wearing side of the sealed context. `null` as long as it is not sealed: never guessed. */
    val sealedLeg: UiText?,
    val strapReference: String,
    val sleepSource: UiText?,
    val recordedNights: Int,
    val eligibleNights: Int,
    /**
     * The most recent analysed night, revealed or not.
     *
     * It comes from `comparable_night`, which exists only for nights that have already been scored:
     * a night appearing here therefore necessarily has a figure to reveal.
     */
    val lastAnalysedNight: NightUi?,
)

/**
 * What the home screen displays. **Three cards, always the three, always in this order.**
 *
 * No card is withdrawn when it has nothing to do: it is disabled and carries its reason. The
 * `*Reason` fields are exactly the arguments of
 * [com.pendulum.phone.ui.common.ReasonedButton] — never an enabled button that fails, never a
 * greyed-out button without an explanation.
 */
@Immutable
data class HomeUi(
    val phase: HomePhase,
    /**
     * The sleep permission blocker, or `null`.
     *
     * It is shown here and not only on the trend: without a hypnogram, the denominator comes from
     * the same signal as the numerator, so no night can carry a result — and the user who never
     * consults their trend was learning that nowhere.
     */
    val sleepSituation: com.pendulum.phone.ui.model.PendulumError? = null,
    val tonight: TonightUi,
    /** Reason the sealing is unavailable, or `null` if it is possible now. */
    val prepareReason: UiText?,
    /** Status line of the "end of night" card. Always present, even when there is nothing. */
    val endOfNightLine: UiText,
    val endOfNightReason: UiText?,
    /** The session the end-of-night button would close, or `null`. */
    val sessionToClose: String?,
    /**
     * The night whose result is waiting to be revealed, or `null`.
     *
     * Non-null only when there is nothing left to close: one card, one action. At 7 am, one-handed,
     * a multiple choice is a choice one does not make.
     */
    val nightToReveal: String?,
    val historyLine: UiText,
    val historyReason: UiText?,
)

/**
 * The home screen's state machine. One function, two inputs, no side effects.
 */
object HomeMachine {

    /** `night_session.state`: the watch is recording. */
    const val OPEN = "OPEN"

    /** No chunk for 45 min. The watch has fallen silent; it may come back. */
    const val SILENT = "STALE"

    /**
     * The phase, in an order of priority that is itself the message.
     *
     * The persisted state comes **before** the hour, always. An `OPEN` session at 3 pm is an `OPEN`
     * session: it is a nap, or a night worker, or a watch someone forgot to stop — in all three
     * cases, the screen must say that it is recording. The hour is consulted only when the database
     * says nothing.
     */
    fun phase(source: HomeSource, localHour: Int): HomePhase {
        val s = source.recentSession ?: return tieBreak(localHour)
        return when {
            s.state == OPEN -> HomePhase.RECORDING
            // Silent: this is precisely the case where the sweep is of some use.
            s.state == SILENT -> HomePhase.END_OF_NIGHT
            // Closed but not yet scored: the night is not over as long as nothing has read it.
            !s.analysed -> HomePhase.END_OF_NIGHT
            else -> tieBreak(localHour)
        }
    }

    /**
     * The only place where the hour comes in. Two equally plausible readings, and nothing in the
     * database to choose between them: either we are getting the evening ready, or the day is
     * running its course.
     */
    private fun tieBreak(localHour: Int): HomePhase =
        if (TonightUi.visibleAt(localHour)) HomePhase.PREPARATION else HomePhase.DAYTIME

    fun of(source: HomeSource, localHour: Int): HomeUi {
        val phase = phase(source, localHour)
        val s = source.recentSession

        // Closable: there is still something to bring back from the watch, to read, or to score. A
        // session that is both closed AND analysed no longer is — asking for a sweep would bring
        // nothing and a button that does nothing is worse than a greyed-out button.
        val closable = s != null && (s.state == OPEN || s.state == SILENT || !s.analysed)

        val toReveal = source.lastAnalysedNight?.takeIf { it.revealedAtMs == null }
        val revealOffered = !closable && toReveal != null

        return HomeUi(
            phase = phase,
            tonight = TonightUi(
                // The watch's battery and free space: nothing reports them on the phone side yet. A
                // dash says "not wired up yet"; a hard-coded figure would say "wired up", and would
                // be believed.
                batteryPct = null,
                freeSpace = null,
                strap = source.strapReference,
                leg = source.sealedLeg,
                sleepSource = source.sleepSource ?: text(R.string.settings_source_unknown),
                sourceActive = null,
                contextSealed = source.contextSealed,
            ),
            prepareReason = when {
                phase == HomePhase.RECORDING -> text(R.string.home_prepare_busy)
                // The context is append-only: sealing it twice raises. The button must therefore be
                // greyed out, and its reason state the fact — the context is sealed **here**.
                //
                // It used to say "the watch can start", and the phone knows nothing of the sort:
                // the publication of the `DataItem` to the watch may have failed, and its outcome
                // is persisted nowhere. This card therefore announced the departure while the watch
                // was still asking for the evening form — two screens contradicting each other,
                // neither of them at fault. What the database proves is the sealing, and nothing
                // more.
                source.contextSealed -> text(R.string.tonight_seal_done)
                else -> null
            },
            endOfNightLine = when {
                revealOffered -> text(R.string.home_result_recorded, toReveal!!.readableDate)
                s == null -> text(R.string.home_end_no_session)
                s.state == OPEN -> text(R.string.home_end_recording_since, s.readableStart)
                closable -> text(R.string.home_end_open_since, s.readableStart)
                else -> text(R.string.home_end_analysed, s.readableDate)
            },
            endOfNightReason = when {
                revealOffered -> null
                closable -> null
                else -> text(R.string.home_end_no_session)
            },
            sessionToClose = if (closable) s!!.sessionHex else null,
            nightToReveal = if (revealOffered) toReveal!!.sessionHex else null,
            historyLine = if (source.recordedNights == 0) {
                text(R.string.home_history_empty)
            } else {
                text(
                    R.string.home_history_count,
                    source.recordedNights,
                    source.eligibleNights,
                )
            },
            historyReason = if (source.recordedNights == 0) {
                text(R.string.home_history_empty)
            } else {
                null
            },
        )
    }
}
