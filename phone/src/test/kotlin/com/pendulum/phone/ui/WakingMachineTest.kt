package com.pendulum.phone.ui

import com.pendulum.phone.ui.model.AnalysisStep
import com.pendulum.phone.ui.model.WakingMachine
import com.pendulum.phone.ui.model.WakingState
import com.pendulum.phone.work.FetchSchedule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The five waking states, one by one, on their bounds.
 *
 * This is the most read logic in the application — it decides what is shown every morning — and it
 * is the one no screen review checks: an emulator cannot be put into the state "transfer at 8
 * chunks out of 17, hypnogram not arrived yet, T+7 h" on demand.
 */
class WakingMachineTest {

    private val end = 1_700_000_000_000L
    private fun h(n: Long) = TimeUnit.HOURS.toMillis(n)

    /** Deterministic formatting: the machine computes instants, not labels. */
    private val formatTime: (Long) -> String = { ms -> "T${(ms - end) / 60_000}" }

    private fun facts(
        sessionState: String = "CLOSED",
        chunksReceived: Int = 17,
        totalChunks: Int? = 17,
        bytesReceived: Long = 8_800_000,
        analysedAtMs: Long? = end + h(1),
        nightEndMs: Long? = end,
        sleepMaskApplied: Boolean = true,
        hypnogramReceived: Boolean = true,
        hcAttempts: Int = 1,
        lastAttemptMs: Long? = end + FetchSchedule.OFFSETS_MS[0],
        integrityRejected: Double = 0.0,
    ) = WakingMachine.Facts(
        sessionHex = "abcd",
        readableDate = "12 March",
        zoneId = "Europe/Paris",
        sessionState = sessionState,
        chunksReceived = chunksReceived,
        totalChunks = totalChunks,
        bytesReceived = bytesReceived,
        analysedAtMs = analysedAtMs,
        nightEndMs = nightEndMs,
        sleepMaskApplied = sleepMaskApplied,
        hypnogramReceived = hypnogramReceived,
        hcAttempts = hcAttempts,
        lastAttemptMs = lastAttemptMs,
        integrityRejected = integrityRejected,
    )

    /**
     * The default instant is the **T+2 h rank of the time scale**, and not two hours written out in
     * plain sight. The two coincide in real time; they diverge as soon as a bench variant compresses
     * the scale, and it would then be the reading of `WakingMachine` that failed — for a reason
     * having nothing to do with what this file checks. The machine is read relative to the time
     * scale, so its tests are too.
     */
    private fun state(f: WakingMachine.Facts?, now: Long = end + FetchSchedule.OFFSETS_MS[2]) =
        WakingMachine.of(f, now, formatTime)

    // -------------------------------------------------------------------------------------
    // The bounds of the machine
    // -------------------------------------------------------------------------------------

    @Test
    fun `with no night, the strip does not exist`() {
        assertThat(state(null)).isEqualTo(WakingState.None)
    }

    @Test
    fun `during the recording, the waking strip stays silent`() {
        // The home card already carries the recording under way. Repeating it here would steal the
        // only line P4 grants to the state of last night.
        assertThat(state(facts(sessionState = WakingMachine.OPEN, analysedAtMs = null)))
            .isEqualTo(WakingState.None)
    }

    @Test
    fun `state 1 - night closed, nothing has arrived yet`() {
        val s = state(facts(chunksReceived = 0, bytesReceived = 0, analysedAtMs = null))
        assertThat(s).isInstanceOf(WakingState.AwaitingTransfer::class.java)
        s as WakingState.AwaitingTransfer
        assertThat(s.date).isEqualTo("12 March")
        // 17 chunks x 1.2 MB estimated at 200 kB/s: about 102 s, rounded up to 2 minutes. The
        // watch announced its chunk count, so both figures exist — this is the case the other
        // state-1 test is the counterpart of.
        assertThat(s.mb).isNotNull()
        assertThat(s.minutes).isNotNull()
        assertThat(s.minutes!!).isGreaterThan(0)
    }

    /**
     * The defect this test pins down.
     *
     * A night the watch has gone quiet on — `STALE`, no chunk for 45 min — has no `totalChunks`:
     * the count is announced on closing, and the session has not closed. State 1 still fired,
     * which is right, but its two figures were computed anyway: with no total, the estimated
     * total fell back on the bytes already received, so the remainder was zero, and the strip
     * read "**0.0 MB to transfer** … Allow **1 minutes**" — a size that says "nothing left" and a
     * delay that says "almost done", on the one morning where the phone knows neither. Both are
     * now `null`, and it is the type that says so: no screen can format an estimate that does
     * not exist.
     */
    @Test
    fun `state 1 - a night the watch has not closed announces neither a size nor a delay`() {
        val s = state(
            facts(
                sessionState = WakingMachine.SILENT,
                totalChunks = null,
                chunksReceived = 10,
                bytesReceived = 5_000_000,
                analysedAtMs = null,
            ),
        )
        assertThat(s).isInstanceOf(WakingState.AwaitingTransfer::class.java)
        s as WakingState.AwaitingTransfer
        assertThat(s.mb)
            .withFailMessage(
                "With no `totalChunks`, state 1 formatted a size: `%s`. The estimate falls back on " +
                    "the bytes received, so this is 0.0 MB — \"nothing left to transfer\" for a night " +
                    "the watch has not even closed.",
                s.mb,
            )
            .isNull()
        assertThat(s.minutes).isNull()
    }

    @Test
    fun `state 2 - the transfer is under way and the percentage does not go backwards`() {
        val s = state(facts(chunksReceived = 8, bytesReceived = 4_100_000, analysedAtMs = null))
        assertThat(s).isInstanceOf(WakingState.Transfer::class.java)
        s as WakingState.Transfer
        assertThat(s.chunk).isEqualTo(8)
        assertThat(s.chunks).isEqualTo(17)
        assertThat(s.fraction).isBetween(0f, 1f)
    }

    @Test
    fun `state 3 - every chunk is there, the analysis is running`() {
        val withoutHypno = state(facts(analysedAtMs = null, hypnogramReceived = false))
        assertThat(withoutHypno).isInstanceOf(WakingState.Analysis::class.java)
        assertThat((withoutHypno as WakingState.Analysis).step).isEqualTo(AnalysisStep.DETECTION)

        // The step shown is deduced from what is in the database, not from a counter nothing would
        // feed: the hypnogram is there, so we are at the cross-referencing.
        val withHypno = state(facts(analysedAtMs = null, hypnogramReceived = true))
        assertThat((withHypno as WakingState.Analysis).step).isEqualTo(AnalysisStep.CROSSREF)
    }

    @Test
    fun `state 4 - the NORMAL case at waking, and it is not a failure`() {
        val s = state(facts(sleepMaskApplied = false, hypnogramReceived = false))
        assertThat(s).isInstanceOf(WakingState.Provisional::class.java)
        s as WakingState.Provisional

        // What the state shows in place of an indeterminate progress indicator.
        assertThat(s.lastAttempt).isNotNull()
        assertThat(s.nextAttempt).isNotNull()
        assertThat(s.givingUpAt).isNotBlank()
        assertThat(s.gaveUp).isFalse()

        // And above all: it is not a `Failure`, so it cannot go through `ErrorCard` nor inherit its
        // red hue. The constraint is carried by the type, not by a convention.
        assertThat(s).isNotInstanceOf(WakingState.Failure::class.java)
    }

    @Test
    fun `state 4 - before any attempt, no dash is written`() {
        val s = state(
            facts(sleepMaskApplied = false, hcAttempts = 0, lastAttemptMs = null),
            now = end + TimeUnit.MINUTES.toMillis(5),
        ) as WakingState.Provisional
        assertThat(s.lastAttempt).isNull()
        assertThat(s.nextAttempt).isNotNull()
    }

    @Test
    fun `state 4 - past T+36 h, giving up is stated and nothing more is scheduled`() {
        val s = state(
            facts(sleepMaskApplied = false),
            now = end + FetchSchedule.GIVE_UP_MS,
        ) as WakingState.Provisional
        assertThat(s.gaveUp).isTrue()
        assertThat(s.nextAttempt).isNull()
    }

    @Test
    fun `the give-up deadline really is T+36 h after the end of the night`() {
        val s = state(facts(sleepMaskApplied = false)) as WakingState.Provisional
        assertThat(s.givingUpAt).isEqualTo(formatTime(end + FetchSchedule.GIVE_UP_MS))
    }

    @Test
    fun `the applied sleep mask ends the night`() {
        // Complete state: the strip disappears. It is `sleep_window` that is authoritative, not the
        // snapshot — a hypnogram received but not yet rescored leaves the night provisional.
        assertThat(state(facts(sleepMaskApplied = true))).isEqualTo(WakingState.None)
        assertThat(state(facts(sleepMaskApplied = false, hypnogramReceived = true)))
            .isInstanceOf(WakingState.Provisional::class.java)
    }

    // -------------------------------------------------------------------------------------
    // The only two breakages the database allows us to observe
    // -------------------------------------------------------------------------------------

    @Test
    fun `state 5 - integrity at fault, in red and with its code`() {
        val s = state(facts(integrityRejected = 0.4, analysedAtMs = null)) as WakingState.Failure
        assertThat(s.error.code).isEqualTo("E-ANA-01")
        assertThat(s.error.technical).isTrue()
        assertThat(s.error.button).isNotNull()
    }

    @Test
    fun `state 5 - truncated night with missing chunks`() {
        val s = state(
            facts(sessionState = WakingMachine.TRUNCATED, chunksReceived = 12, analysedAtMs = null),
        ) as WakingState.Failure
        assertThat(s.error.code).isEqualTo("E-NIGHT-07")
        assertThat(s.error.technical).isTrue()
    }

    @Test
    fun `a truncated but complete night is not a breakage`() {
        // The watchdog marks `TRUNCATED` as early as 2 pm; if every chunk is there, nothing is
        // broken. Showing a red card here would teach the user to ignore red.
        assertThat(state(facts(sessionState = WakingMachine.TRUNCATED, chunksReceived = 17)))
            .isEqualTo(WakingState.None)
    }

    @Test
    fun `the state never goes backwards - an analysed night does not return to waiting`() {
        // A late chunk arrives after the analysis: the chunk count becomes incomplete again. The
        // screen must stay downstream, not go back to "awaiting transfer".
        val s = state(facts(chunksReceived = 3, totalChunks = 40, analysedAtMs = end + h(1)))
        assertThat(s).isNotInstanceOf(WakingState.AwaitingTransfer::class.java)
        assertThat(s).isNotInstanceOf(WakingState.Transfer::class.java)
    }
}
