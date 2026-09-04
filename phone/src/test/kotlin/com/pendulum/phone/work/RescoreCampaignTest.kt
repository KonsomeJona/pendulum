package com.pendulum.phone.work

import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The global rescore as a campaign: a cursor that survives a stop, and a switch of the active
 * profile that happens once, at the end, and nowhere else.
 *
 * ### The two defects this file pins down
 *
 * **The switch never happened.** `ParamDao.activate()` had no caller outside the bench seeding.
 * `AnalysisRunner` records the profile with `active = active() == null`, so as soon as an older
 * profile was active the new hash was inserted **inactive** and stayed so. After an update that
 * changed a default of `:algo`, every night was recomputed under the new hash — and every screen
 * kept reading the old one (`observeActive()`), while the physician report, which goes through
 * `WorkScheduler.activeParams()`, read the new one: two figures for the same night. And since the
 * mismatch never resolved, `PendulumApp.onCreate` re-enqueued the full recompute (`REPLACE`) at
 * every cold start.
 *
 * **The campaign restarted from the oldest night.** WorkManager stops a worker after ten minutes.
 * The loop counted the resulting `JobCancellationException` as one failed night, kept going into
 * nights that then failed instantly at their first Room call, returned `retry`, and the retry
 * began again at the oldest night — the tail of a long campaign was never reached, and the trend
 * carried two hashes for good.
 *
 * ### Why the side effects are parameters
 *
 * Same reason as `ContextPublicationWorker.outcomeOf`: the decision — skip, analyse, count as a
 * failure, rethrow, activate — is what these tests establish, and it was unreachable while it sat
 * inside a `doWork()` that opens the real database.
 */
class RescoreCampaignTest {

    private val target = "cafe0000cafe0000"

    private class Recorder {
        val analysed = mutableListOf<String>()
        var activations = 0
    }

    private fun runCampaign(
        nights: List<String>,
        stamped: Map<String, String?>,
        recorder: Recorder,
        analyse: suspend (String) -> Unit = { recorder.analysed += it },
    ): ListenableWorker.Result = runBlocking {
        RescoreAllWorker.runCampaign(
            nights = nights,
            targetHash = target,
            stampedHash = { stamped[it] },
            analyse = analyse,
            activate = { recorder.activations++ },
        )
    }

    @Test
    fun `a night already stamped with the target hash is not recomputed`() {
        // The cursor needs no extra state: `writeAnalysisSummary` stamps `paramsHash` at the end
        // of every successful night, so "not yet under the target hash" is the resume point.
        val r = Recorder()
        val result = runCampaign(
            nights = listOf("a", "b", "c"),
            stamped = mapOf("a" to target, "b" to "0ld0000000000000", "c" to null),
            recorder = r,
        )

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(r.analysed).containsExactly("b", "c")
    }

    @Test
    fun `the profile becomes active once the last night carries the hash`() {
        val r = Recorder()
        runCampaign(nights = listOf("a", "b"), stamped = emptyMap(), recorder = r)

        assertThat(r.activations).`as`("activations of the target profile").isEqualTo(1)
    }

    @Test
    fun `a campaign with nothing left to recompute still activates the profile`() {
        // The run that follows a stop after the very last night: every night is stamped, the
        // activation is the only thing left to do. Skipping it here would reproduce the defect
        // one level up — recomputed everywhere, active nowhere.
        val r = Recorder()
        val result = runCampaign(
            nights = listOf("a", "b"),
            stamped = mapOf("a" to target, "b" to target),
            recorder = r,
        )

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(r.analysed).isEmpty()
        assertThat(r.activations).isEqualTo(1)
    }

    @Test
    fun `a failed night keeps the old profile active and asks for another attempt`() {
        // As long as one night is still under the old hash, the screens must keep reading the old
        // hash rather than plot a campaign half recomputed. The other nights are still processed:
        // one corrupt night must not hold the whole campaign at its position.
        val r = Recorder()
        val result = runCampaign(
            nights = listOf("a", "b", "c"),
            stamped = emptyMap(),
            recorder = r,
            analyse = { hex ->
                if (hex == "b") throw IllegalStateException("corrupt chunk")
                r.analysed += hex
            },
        )

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(r.analysed).containsExactly("a", "c")
        assertThat(r.activations).`as`("the old profile stays active").isZero()
    }

    @Test
    fun `a cancellation raised inside a night is the system's stop, not a failed night`() {
        // Room raises `JobCancellationException` — a `CancellationException` — at the first query
        // after WorkManager has taken the worker back. Counting it as a failure and carrying on
        // made every following night fail instantly, and turned a ten-minute limit into a
        // `retry` from the oldest night.
        val r = Recorder()

        assertThatThrownBy {
            runCampaign(
                nights = listOf("a", "b", "c"),
                stamped = emptyMap(),
                recorder = r,
                analyse = { hex ->
                    if (hex == "b") throw CancellationException("worker stopped")
                    r.analysed += hex
                },
            )
        }.isInstanceOf(CancellationException::class.java)

        assertThat(r.analysed).containsExactly("a")
        assertThat(r.activations).isZero()
    }

    @Test
    fun `a stopped worker does not start one more night`() {
        // `NightAnalyzer.analyze` is one non-suspending block of several seconds: without a check
        // between two nights, a stop landing during night "a" would still pay night "b" in full.
        val r = Recorder()

        assertThatThrownBy {
            runBlocking {
                RescoreAllWorker.runCampaign(
                    nights = listOf("a", "b", "c"),
                    targetHash = target,
                    stampedHash = { null },
                    analyse = { hex ->
                        r.analysed += hex
                        if (hex == "a") cancel()
                    },
                    activate = { r.activations++ },
                )
            }
        }.isInstanceOf(CancellationException::class.java)

        assertThat(r.analysed).containsExactly("a")
        assertThat(r.activations).isZero()
    }
}
