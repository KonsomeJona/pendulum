package com.pendulum.phone.work

import com.pendulum.phone.time.Durations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two tiers of the phone's watchdog, taken out of the coroutine that read the database and the
 * clock in the same function.
 *
 * What becomes checkable and was not: **the priority of `TRUNCATED` over `STALE`**. A night longer
 * than fourteen hours must be analysed even if chunks keep arriving; the order of the branches of a
 * `when` was the only trace of that decision.
 */
class WatchdogStateTest {

    private val staleMs = Durations.ACTIVE.silenceBeforeStaleMs
    private val maxAgeMs = Durations.ACTIVE.maxNightAgeMs
    private val start = 1_700_000_000_000L

    @Test
    fun `a fresh and talkative night does not change state`() {
        val now = start + staleMs / 2
        assertThat(next("OPEN", lastChunk = now - staleMs / 4, now = now)).isNull()
    }

    @Test
    fun `prolonged silence moves the night to stale`() {
        val now = start + maxAgeMs / 2
        assertThat(next("OPEN", lastChunk = now - staleMs - 1, now = now)).isEqualTo("STALE")
    }

    @Test
    fun `a night without a single chunk never goes stale`() {
        // `lastChunkArrivalMs == 0` means "nothing has arrived yet", not "nothing has arrived
        // since 1970". Without this guard rail, any night announced but not yet transferred would
        // be declared dead on the first run of the watchdog.
        val now = start + maxAgeMs / 2
        assertThat(next("OPEN", lastChunk = 0L, now = now)).isNull()
    }

    @Test
    fun `age truncates the night, even if the chunks are still arriving`() {
        val now = start + maxAgeMs + 1
        assertThat(next("OPEN", lastChunk = now - 1, now = now)).isEqualTo("TRUNCATED")
    }

    @Test
    fun `a night already stale is truncated all the same`() {
        val now = start + maxAgeMs + 1
        assertThat(next("STALE", lastChunk = start, now = now)).isEqualTo("TRUNCATED")
    }

    @Test
    fun `a night already stale is not moved to stale again`() {
        val now = start + maxAgeMs / 2
        assertThat(next("STALE", lastChunk = start, now = now)).isNull()
    }

    private fun next(state: String, lastChunk: Long, now: Long): String? =
        WatchdogWorker.nextState(
            state = state,
            startWallMs = start,
            lastChunkArrivalMs = lastChunk,
            nowMs = now,
            staleMs = staleMs,
            maxAgeMs = maxAgeMs,
        )
}
