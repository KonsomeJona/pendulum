package com.pendulum.phone.ui

import com.pendulum.phone.ui.home.HomeMachine
import com.pendulum.phone.ui.home.HomePhase
import com.pendulum.phone.ui.home.HomeSession
import com.pendulum.phone.ui.home.HomeSource
import com.pendulum.phone.ui.home.HomeUi
import com.pendulum.phone.ui.model.Flag
import com.pendulum.phone.ui.model.NightState
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The home state machine, tested on its bounds.
 *
 * ### What these tests protect
 *
 * A single rule, and one that is easy to lose at the first touch-up: **the persisted state decides,
 * the hour tie-breaks**. v1 did the opposite — the evening card appeared between 8 pm and 4 am,
 * full stop. The defect was not visible on a mock-up, because a mock-up is looked at at 3 pm: it
 * showed up for a shift worker, for a traveller, and for anyone who went to bed at 4:30 am.
 *
 * The clock is therefore a **parameter**, not a buried call. That is the condition for these
 * assertions to exist at all: `HomeMachine.phase(source, 3)` can be read, `System.currentTimeMillis()`
 * deep inside a function cannot be tested.
 */
class HomeMachineTest {

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private fun night(revealedAtMs: Long?) = NightUi(
        sessionHex = "a7",
        readableDate = "12 March",
        shortDay = "Fri",
        start = "23:12",
        end = "06:58",
        readableSleep = "6 h 58",
        sleepSource = text("Health Connect"),
        state = NightState.ELIGIBLE,
        reason = null,
        rhythmSec = 18.7,
        plmiCount = 24.0,
        flags = listOf(Flag(text("gap 47 s"))),
        startWallMs = 0L,
        revealedAtMs = revealedAtMs,
    )

    private fun session(state: String, analysed: Boolean) = HomeSession(
        sessionHex = "a7",
        state = state,
        analysed = analysed,
        readableStart = "23:12",
        readableDate = "12 March",
    )

    private fun source(
        session: HomeSession? = null,
        contextSealed: Boolean = false,
        recordedNights: Int = 0,
        eligibleNights: Int = 0,
        lastNight: NightUi? = null,
    ) = HomeSource(
        recentSession = session,
        contextSealed = contextSealed,
        sealedLeg = if (contextSealed) text(R.string.tonight_leg_right) else null,
        strapReference = "4th hole",
        sleepSource = text("Samsung Health"),
        recordedNights = recordedNights,
        eligibleNights = eligibleNights,
        lastAnalysedNight = lastNight,
    )

    private fun ui(source: HomeSource, hour: Int): HomeUi = HomeMachine.of(source, hour)

    // -------------------------------------------------------------------------------------
    // The persisted state comes before the hour
    // -------------------------------------------------------------------------------------

    /**
     * The central test. A session open at 3 pm is an open session: a nap, shift work, or a watch
     * someone forgot to stop. In all three cases the screen must say that it is recording, and the
     * old 8 pm - 4 am window said the opposite.
     */
    @Test
    fun `an open session wins over the hour, at any hour`() {
        val open = source(session(HomeMachine.OPEN, analysed = false))
        for (hour in 0..23) {
            assertThat(HomeMachine.phase(open, hour))
                .describedAs("at %d h", hour)
                .isEqualTo(HomePhase.RECORDING)
        }
    }

    @Test
    fun `a silent session is an end of night, at any hour`() {
        val silent = source(session(HomeMachine.SILENT, analysed = false))
        for (hour in 0..23) {
            assertThat(HomeMachine.phase(silent, hour)).isEqualTo(HomePhase.END_OF_NIGHT)
        }
    }

    /**
     * Closed cleanly but never scored: the night is not over as long as nothing has read it. That
     * is the case of the phone switched off at waking, or of the chain interrupted.
     */
    @Test
    fun `a session closed but not analysed stays an end of night`() {
        val closed = source(session("CLOSED", analysed = false))
        assertThat(HomeMachine.phase(closed, 9)).isEqualTo(HomePhase.END_OF_NIGHT)
        assertThat(HomeMachine.phase(closed, 22)).isEqualTo(HomePhase.END_OF_NIGHT)
    }

    @Test
    fun `a truncated and unanalysed session stays an end of night`() {
        assertThat(HomeMachine.phase(source(session("TRUNCATED", analysed = false)), 11))
            .isEqualTo(HomePhase.END_OF_NIGHT)
    }

    // -------------------------------------------------------------------------------------
    // The hourly tie-break rule — and its exact scope
    // -------------------------------------------------------------------------------------

    /**
     * The hour comes into play there and only there, and the bounds are those of
     * `TonightUi.visibleAt`: 8 pm inclusive, 4 am exclusive.
     */
    @Test
    fun `with nothing in the database, the hour tie-breaks preparation and daytime`() {
        val nothing = source()
        assertThat(HomeMachine.phase(nothing, 19)).isEqualTo(HomePhase.DAYTIME)
        assertThat(HomeMachine.phase(nothing, 20)).isEqualTo(HomePhase.PREPARATION)
        assertThat(HomeMachine.phase(nothing, 23)).isEqualTo(HomePhase.PREPARATION)
        assertThat(HomeMachine.phase(nothing, 0)).isEqualTo(HomePhase.PREPARATION)
        assertThat(HomeMachine.phase(nothing, 3)).isEqualTo(HomePhase.PREPARATION)
        assertThat(HomeMachine.phase(nothing, 4)).isEqualTo(HomePhase.DAYTIME)
        assertThat(HomeMachine.phase(nothing, 12)).isEqualTo(HomePhase.DAYTIME)
    }

    /** A night both closed **and** analysed says nothing more: the hour takes over again. */
    @Test
    fun `a night closed and analysed hands back to the hour`() {
        val finished = source(session("CLOSED", analysed = true), lastNight = night(revealedAtMs = 1L))
        assertThat(HomeMachine.phase(finished, 21)).isEqualTo(HomePhase.PREPARATION)
        assertThat(HomeMachine.phase(finished, 10)).isEqualTo(HomePhase.DAYTIME)
    }

    // -------------------------------------------------------------------------------------
    // The three cards: never removed, disabled with their reason
    // -------------------------------------------------------------------------------------

    @Test
    fun `sealing is impossible during a recording, and the reason says so`() {
        val ui = ui(source(session(HomeMachine.OPEN, analysed = false)), hour = 23)
        assertThat(ui.prepareReason).isEqualTo(text(R.string.home_prepare_busy))
    }

    /** The context is append-only: sealing it twice raises. So the button carries its reason. */
    @Test
    fun `sealing is impossible once the context is sealed, and the reason is the good news`() {
        val ui = ui(source(contextSealed = true), hour = 22)
        assertThat(ui.prepareReason).isEqualTo(text(R.string.tonight_seal_done))
    }

    @Test
    fun `sealing is possible as long as nothing is sealed and nothing is in flight`() {
        assertThat(ui(source(), hour = 21).prepareReason).isNull()
    }

    @Test
    fun `with no session, the end of night card is greyed out with its reason`() {
        val ui = ui(source(), hour = 21)
        assertThat(ui.endOfNightReason).isEqualTo(text(R.string.home_end_no_session))
        assertThat(ui.sessionToClose).isNull()
        assertThat(ui.nightToReveal).isNull()
    }

    @Test
    fun `with a session to close, the button is enabled and knows which one`() {
        val ui = ui(source(session(HomeMachine.SILENT, analysed = false)), hour = 8)
        assertThat(ui.endOfNightReason).isNull()
        assertThat(ui.sessionToClose).isEqualTo("a7")
    }

    /**
     * One card, one action. As long as there is still something to bring back from the watch, that
     * is what the button does — offering a reveal while a night is transferring would have someone
     * read a figure that is going to change.
     */
    @Test
    fun `revealing is offered only when there is nothing left to close`() {
        val inFlight = ui(
            source(session(HomeMachine.OPEN, analysed = false), lastNight = night(null)),
            hour = 2,
        )
        assertThat(inFlight.nightToReveal).isNull()

        val finished = ui(
            source(session("CLOSED", analysed = true), lastNight = night(null)),
            hour = 8,
        )
        assertThat(finished.nightToReveal).isEqualTo("a7")
        assertThat(finished.endOfNightLine).isEqualTo(text(R.string.home_result_recorded, "12 March"))
    }

    /** Guard rail 2: once revealed, the night asks for nothing more. */
    @Test
    fun `a night already revealed is not offered for revealing again`() {
        val ui = ui(
            source(session("CLOSED", analysed = true), lastNight = night(revealedAtMs = 1L)),
            hour = 8,
        )
        assertThat(ui.nightToReveal).isNull()
        assertThat(ui.endOfNightLine).isEqualTo(text(R.string.home_end_analysed, "12 March"))
    }

    @Test
    fun `the history counts the nights and the eligible ones, and greys out at zero`() {
        val empty = ui(source(), hour = 12)
        assertThat(empty.historyLine).isEqualTo(text(R.string.home_history_empty))
        assertThat(empty.historyReason).isEqualTo(text(R.string.home_history_empty))

        val filled = ui(source(recordedNights = 7, eligibleNights = 5), hour = 12)
        assertThat(Resources.resolve(filled.historyLine)).isEqualTo("7 nights · 5 eligible")
        assertThat(filled.historyReason).isNull()
    }

    // -------------------------------------------------------------------------------------
    // The dashes: what is not wired up must be seen
    // -------------------------------------------------------------------------------------

    /**
     * The watch's battery and free space are not reported yet. The machine must therefore invent
     * nothing: a dash says "not wired up yet", a hard-coded "98 %" would say "wired up" and would
     * be believed.
     */
    @Test
    fun `the watch battery and free space stay null as long as nothing reports them`() {
        val ui = ui(source(contextSealed = true), hour = 22)
        assertThat(ui.tonight.batteryPct).isNull()
        assertThat(ui.tonight.freeSpace).isNull()
        assertThat(ui.tonight.batteryInsufficient).isFalse()
        assertThat(ui.tonight.sourceActive).isNull()
    }

    /** The leg is known only once the context is sealed. It is never guessed. */
    @Test
    fun `the leg stays unknown as long as the context is not sealed`() {
        assertThat(ui(source(contextSealed = false), hour = 21).tonight.leg).isNull()
        assertThat(ui(source(contextSealed = true), hour = 21).tonight.leg)
            .isEqualTo(text(R.string.tonight_leg_right))
    }
}
