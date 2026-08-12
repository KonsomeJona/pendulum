package com.pendulum.phone.ui.model

import androidx.compose.runtime.Immutable
import com.pendulum.phone.ui.chart.TrendChartSpec
import androidx.annotation.StringRes
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text

/**
 * The models the screens consume.
 *
 * Architectural rule held everywhere here: **a screen composable takes only its state and
 * lambdas**. No Room access, no Health Connect access, no aggregate computation from a
 * `@Composable`. The chart `Spec`s are built upstream too, which makes them testable without a
 * screen and lets the PDF export reuse exactly the same objects.
 */

/** A night's state with respect to the trend. Always doubled by a shape, never by a colour alone. */
enum class NightState { ELIGIBLE, PROVISIONAL, EXCLUDED }

/**
 * A quality flag. This is **not** an error: it is a measured property of the night, shown so that
 * the user knows under which conditions their number was obtained.
 */
@Immutable
data class Flag(val label: UiText)

@Immutable
data class NightUi(
    val sessionHex: String,
    val readableDate: String,
    val shortDay: String,
    val start: String,
    val end: String,
    val readableSleep: String,
    val sleepSource: UiText,
    val state: NightState,
    /** Exclusion reason, resolved at display time. Non-null if and only if [state] is EXCLUDED. */
    val reason: UiText?,
    /**
     * This night's fundamental rhythm, in seconds, or `null` when `:algo` refused the fit — see
     * [Mapping.rhythmSec], which explains why that is the frequent case.
     */
    val rhythmSec: Double?,
    /**
     * This night's hourly count. Present, never put forward (P7).
     *
     * `null` when the night has no analysable sleep: there is then no denominator, hence no rate.
     * The type carries the rule — no screen can round that absence into "0/h", which would read as
     * a night without the slightest movement.
     */
    val plmiCount: Double?,
    val flags: List<Flag>,
    val startWallMs: Long,
    /**
     * The instant the result was revealed, or `null` if it was never asked for — guard rail 2.
     *
     * As long as it is null, [rhythmSec] and [plmiCount] are **not shown**, neither in the list nor
     * in the detail. They stay in the model because the masking is a display rule and not
     * encryption: the point is not to make the value inaccessible, it is that it be asked for
     * explicitly and that the request leave a trace.
     */
    val revealedAtMs: Long? = null,
)

/**
 * The five waking states, one at a time, at most one action.
 *
 * The [Provisional] state is the **normal case** at waking and not a failure: the wrist watch's
 * sleep stages arrive in Health Connect when the manufacturer's battery policy decides, often
 * several hours later. All the styling of this state follows from that — amber and not red, the
 * word "provisional" and not "failure", an explanation of the delay before any action.
 */
sealed interface WakingState {
    data object None : WakingState

    data class AwaitingTransfer(val date: String, val mb: String, val minutes: Int) : WakingState

    data class Transfer(val receivedMb: String, val totalMb: String, val chunk: Int, val chunks: Int) : WakingState {
        /** The percentage never goes backwards: the transfer resumes where it stopped. */
        val fraction: Float get() = if (chunks == 0) 0f else (chunk.toFloat() / chunks).coerceIn(0f, 1f)
    }

    data class Analysis(val date: String, val step: AnalysisStep, val secondsRemaining: Int) : WakingState

    /**
     * State 4, and the only one whose **shape** is as constrained as its text.
     *
     * @param lastAttempt `null` before the first Health Connect read. Writing a dash would make it
     *   read as a failure where there is only a wait that is beginning.
     * @param nextAttempt `null` when the schedule is over — nothing more is planned.
     * @param givingUpAt the T+36 h deadline, **dated**. It replaces the indeterminate progress
     *   indicator: a circle spinning for six hours says "broken", a deadline says "this is still
     *   going, and here is until when".
     */
    data class Provisional(
        val date: String,
        val lastAttempt: String?,
        val nextAttempt: String?,
        val givingUpAt: String,
        val gaveUp: Boolean,
    ) : WakingState

    data class Failure(val date: String, val error: PendulumError) : WakingState
}

/**
 * Three step labels, and not one more. The technical log lives in Settings > Log.
 *
 * The label is a **resource identifier**: an `enum` constructor has no `Context`, and resolving the
 * string here would freeze it to the language in force when the class was loaded.
 */
enum class AnalysisStep(@StringRes val label: Int, val fraction: Float) {
    ASSEMBLY(R.string.waking_analysis_step_assembly, 0.25f),
    DETECTION(R.string.waking_analysis_step_detection, 0.6f),
    CROSSREF(R.string.waking_analysis_step_crossref, 0.9f),
}

/**
 * An error message always has the same shape: a neutral title, one sentence of cause, one sentence
 * of action, a button when an action exists, and a stable code.
 *
 * [technical] separates what is really broken (transfer, permission, integrity, storage — shown in
 * red) from what is a **situation** (short night, missing hypnogram, watch not worn — shown in
 * amber). Using the word "error" when nothing is broken teaches the user to ignore real failures.
 */
@Immutable
data class PendulumError(
    val code: String,
    val title: UiText,
    val cause: UiText,
    val action: UiText,
    val button: UiText? = null,
    val technical: Boolean = false,
)

/**
 * The feedback from a one-off action: what has to be said about it, and whether it failed.
 *
 * It exists because three of this product's actions **go somewhere else and may not arrive** — the
 * start request to the watch, writing a report into a SAF `Uri`, writing a night bundle. None of
 * the three changes the state the screen shows: on return, the card has exactly the same shape
 * whether it worked or not. Without explicit feedback, "the watch is recording" and "the watch
 * received nothing" are visually identical, and whoever goes to bed believing the first loses
 * their night.
 *
 * [failed] carries the **tint**, never the text: the message already says what happened, and
 * deriving the colour from the content of the sentence would mean comparing strings. Amber and not
 * red — a watch out of range or a SAF provider refusing a stream are not Pendulum failures, they
 * are situations, and red stays reserved for what is broken.
 */
@Immutable
data class Feedback(val message: UiText, val failed: Boolean)

/**
 * What the "Prepare the night" card shows.
 *
 * ### It no longer disappears between 4 am and 8 pm
 *
 * v1 made it appear between 8 pm and 4 am and disappear the rest of the time. The cost of that
 * choice was not visible from the mockup: the card being at the top of the screen, its appearance
 * and disappearance **moved everything below it vertically, twice a day**. And the clock is not a
 * source of state: someone working nights, or who has just changed time zone, was refused the
 * screen they needed.
 *
 * The card is therefore permanent and it is the persisted state that fills it. [visibleAt] survives
 * as a **tie-breaker** — when the database cannot settle between "we are preparing the night" and
 * "the day is under way", the local hour settles it, and that is all it does.
 *
 * ### The dashes are deliberate
 *
 * [batteryPct] and [freeSpace] are null as long as the watch does not report them: nothing on the
 * phone side reads those two values yet. A dash says "not wired up yet". A hard-coded "98 %" would
 * say "wired up", which would be false, and would be believed — it is exactly the kind of number
 * one then quotes in a defect report.
 */
@Immutable
data class TonightUi(
    val batteryPct: Int?,
    val freeSpace: String?,
    val strap: String,
    /** The wearing side, known only once the context is sealed. */
    val leg: UiText?,
    val sleepSource: UiText,
    /** `null` as long as the source's activity cannot be verified. */
    val sourceActive: Boolean?,
    val contextSealed: Boolean,
    val recording: RecordingUi? = null,
) {
    /** Advice, not a gate: below 85 %, the row turns amber and nothing is blocked. */
    val batteryInsufficient: Boolean get() = batteryPct != null && batteryPct < 85

    companion object {
        const val START_HOUR = 20
        const val END_HOUR = 4

        /**
         * `localHour` in 0..23. True between 8 pm and 4 am.
         *
         * This is no longer a display condition: it is the tie-breaker of the home state machine
         * when the database leaves two equally plausible readings. See `ui/home/HomeModel.kt`.
         */
        fun visibleAt(localHour: Int): Boolean = localHour >= START_HOUR || localHour < END_HOUR
    }
}

@Immutable
data class RecordingUi(
    val since: String,
    val duration: String,
    val samples: Long,
    val measuredHz: Double,
    val batteryPct: Int,
    val gaps: Int,
)

/**
 * What is missing in order to aggregate. Two causes unrelated to each other, and confusing them
 * makes the screen say something false: "2 nights out of 3" to someone who has nine.
 */
enum class RefusalReason {
    /** Fewer than three eligible nights. The historical refusal, the one that fills by sleeping. */
    NOT_ENOUGH_NIGHTS,

    /**
     * The nights are there, but too few carry an accepted rhythm fit.
     *
     * **This is the normal case, not the failure**: `RhythmMeasurementTest` measures 2 accepted
     * fits out of 20 nominal nights. The refusal comes from the product itself, which prefers to
     * report nothing rather than report a period that the intervals do not identify.
     */
    RHYTHM_NOT_FITTED,
}

/**
 * The state of the Trend screen.
 *
 * [Refusal] is not an error state: it is the product's normal behaviour as long as it has nothing
 * to aggregate. No chart is built, no median exists, the export is disabled with its reason. The
 * difference with "an empty chart" is essential — an empty axis invites the eye to imagine a curve.
 */
sealed interface TrendUiState {
    data object Loading : TrendUiState

    data class Refusal(
        val eligibleNights: Int,
        /** How many of those nights carry an accepted rhythm fit. See [RefusalReason]. */
        val fittedRhythmNights: Int,
        val requiredNights: Int,
        val recordedNights: List<NightUi>,
        val waking: WakingState,
        /**
         * The sleep source situation is shown **as well** below three nights, and that is the
         * moment when it is most useful: a Health Connect permission that was never granted gets
         * repaired before six nights have been accumulated scored on the accelerometric mask alone.
         */
        val sleepSituation: PendulumError? = null,
        /**
         * The night the status strip is talking about, when there is one.
         *
         * It is here because the strip's action — "Transfer now", "Try again now", "Resume the
         * transfer", "Run the analysis again" — applies to **one specific night**, and none of the
         * five [WakingState]s carries its identifier: they carry a readable date, made for the eye
         * and not for a query. Without this field, the strip's button had nothing to call, which is
         * exactly what it was doing.
         */
        val wakingSession: String? = null,
    ) : TrendUiState {

        /**
         * The reason is **derived** from the two counts rather than carried by one more field:
         * enough eligible nights and not enough fits leaves only one possible reading, and a field
         * the caller would fill could be filled wrongly.
         */
        val reason: RefusalReason
            get() = if (eligibleNights >= requiredNights) {
                RefusalReason.RHYTHM_NOT_FITTED
            } else {
                RefusalReason.NOT_ENOUGH_NIGHTS
            }

        /** The count that is missing — the one the counter and the bar must show. */
        val acquiredNights: Int
            get() = if (reason == RefusalReason.RHYTHM_NOT_FITTED) fittedRhythmNights else eligibleNights
    }

    data class Ready(
        /** The fundamental rhythm: the quantity being tracked. */
        val rhythm: Aggregate.Result,
        /** The hourly count, in second place. Present for the doctor, not for the tracking. */
        val count: Aggregate.Result,
        val position: Aggregate.Position,
        val qualifiedPeriodicity: UiText?,
        /** `null` when it could not be measured. See [plmw]. */
        val missRate: Double?,
        val chart: TrendChartSpec,
        val recordedNights: Int,
        val eligibleNights: Int,
        val excludedNights: Int,
        val rule: UiText,
        val mask: UiText,
        /** `null` if none of the nights kept carries this rate: a dash, never a zero. */
        val plmw: Double?,
        val waking: WakingState,
        val customProfile: String?,
        val mixedHashes: Boolean,
        val questionnaireState: UiText,
        val exportPossible: Boolean,
        val sleepSituation: PendulumError? = null,
        /** The night the status strip is talking about. See [Refusal.wakingSession]. */
        val wakingSession: String? = null,
    ) : TrendUiState {

        /**
         * The number of nights beyond which the result stops being announced as provisional.
         *
         * It **depends on the position of the interval** and not on a constant: see
         * [Aggregate.requiredNights]. Three nights are enough above the threshold, where night to
         * night reliability is measured at 0.90; many more are needed below, where it collapses —
         * and that is precisely the regime in which the screen reassures.
         */
        val requiredNights: Int get() = Aggregate.requiredNights(count.ciLow, count.ciHigh)

        /** The reason for the number above, spelled out. A bare figure cannot be argued with. */
        val requiredNightsReason: UiText
            get() = Aggregate.requiredNightsReason(count.ciLow, count.ciHigh)

        /** The "provisional result" banner, as long as [requiredNights] is not reached. */
        val provisionalBanner: UiText?
            get() = if (rhythm.nights < requiredNights) {
                text(R.string.trend_provisional_banner, rhythm.nights, requiredNights)
            } else {
                null
            }
    }
}
