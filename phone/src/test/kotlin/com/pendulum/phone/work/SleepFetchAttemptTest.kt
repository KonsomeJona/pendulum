package com.pendulum.phone.work

import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.health.SleepSourceSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * One rung of the Health Connect ladder, when the provider does not answer politely.
 *
 * ### The defect this file pins down
 *
 * `SleepFetchWorker` called `reader.availability()` then `reader.read()` with no guard. Both go
 * through the Health Connect binder, and the binder throws — `RemoteException`, `IOException`,
 * `IllegalStateException` — while Play is updating the provider, on the first bind after a boot,
 * or when the provider's process has just been killed. A `CoroutineWorker` that throws is marked
 * `FAILED`. The `hc_snapshot` row and the scheduling of the next rung both came **after** the
 * read, so on an exception nothing was written and nothing was rescheduled: the unique work of
 * that night was simply gone, the 1 h / 2 h / 4 h … rungs never happened, and the night kept the
 * circular accelerometer mask for good — with the waking strip reporting zero attempts, as if
 * nothing had been tried.
 *
 * The rule these tests establish: an exception is a **logged attempt**, exactly like a provider
 * that reports itself unavailable. It leaves a row, so the ladder advances, and the worker
 * returns normally, so the next rung is scheduled.
 *
 * ### Why the reader is two lambdas
 *
 * `SleepReader` is built on a `Context` and talks to a binder; nothing of it runs on the JVM. The
 * decision — unavailable, read, threw — is what matters here, and it is the same shape as
 * `ContextPublicationWorker.outcomeOf`.
 */
class SleepFetchAttemptTest {

    private val ready: suspend () -> SleepReader.Availability = { SleepReader.Availability.READY }

    private fun attempt(
        availability: suspend () -> SleepReader.Availability = ready,
        read: suspend () -> SleepReader.Reading?,
    ): SleepFetchWorker.Attempt = runBlocking { SleepFetchWorker.attempt(availability, read) }

    @Test
    fun `a provider that throws during the read is a logged attempt, named after the exception`() {
        val attempt = attempt { throw IOException("binder died") }

        assertThat(attempt).isInstanceOf(SleepFetchWorker.Attempt.Threw::class.java)
        assertThat(attempt.outcome).isEqualTo("EXCEPTION:IOException")
    }

    @Test
    fun `a provider that throws while reporting its availability is a logged attempt too`() {
        // `availability()` calls `getGrantedPermissions()` over the same binder: it is not safer
        // than the read, and it runs first.
        var reads = 0
        val attempt = attempt(
            availability = { throw IllegalStateException("provider updating") },
            read = { reads++; null },
        )

        assertThat(attempt).isInstanceOf(SleepFetchWorker.Attempt.Threw::class.java)
        assertThat(attempt.outcome).isEqualTo("EXCEPTION:IllegalStateException")
        assertThat(reads).`as`("no read after a failed availability check").isZero()
    }

    @Test
    fun `an unavailable provider is recorded under its own name and never read`() {
        var reads = 0
        val attempt = attempt(
            availability = { SleepReader.Availability.PERMISSIONS_MISSING },
            read = { reads++; null },
        )

        assertThat(attempt).isInstanceOf(SleepFetchWorker.Attempt.Unavailable::class.java)
        assertThat(attempt.outcome).isEqualTo("PERMISSIONS_MISSING")
        assertThat(reads).isZero()
    }

    @Test
    fun `a read that returns nothing is READ_FAILED, a read that returns a session carries its verdict`() {
        val nothing = attempt { null }
        assertThat(nothing.outcome).isEqualTo("READ_FAILED")

        val empty = attempt {
            SleepReader.Reading(
                selection = SleepSourceSelector.Selection(chosen = null, rejected = emptyList(), reason = "none"),
                allCandidates = emptyList(),
                aggregateTstMin = null,
                verdict = "NO_SESSION",
                readAtMs = 0L,
            )
        }
        assertThat(empty).isInstanceOf(SleepFetchWorker.Attempt.Read::class.java)
        assertThat(empty.outcome).isEqualTo("NO_SESSION")
    }

    @Test
    fun `a cancellation is the system's stop, not an attempt`() {
        // Writing an `EXCEPTION:JobCancellationException` row would advance the ladder for a
        // read that never took place: the rung would be consumed by the system taking the worker
        // back, not by Health Connect.
        assertThatThrownBy {
            attempt { throw CancellationException("worker stopped") }
        }.isInstanceOf(CancellationException::class.java)
    }
}
