package com.pendulum.wear.record

import com.pendulum.wear.time.Durations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The resume predicate, now single and pure.
 *
 * It used to live copied out in three places — `BootReceiver`, the watchdog, the `START_STICKY`
 * restart of the service — each of them reading the clock deep inside its own function. None of
 * the three was testable, and the only thing that guaranteed they said the same thing was that
 * they had been written on the same day.
 */
class SessionMarkerTest {

    private val start = 1_700_000_000_000L

    private fun marker(plannedStopWallMs: Long) = SessionMarker(
        sessionHex = "aa",
        startWallMs = start,
        plannedStopWallMs = plannedStopWallMs,
        lastChunkIndex = 0,
        modeFlags = 0,
        nominalRateHz = 50,
        zoneId = "Europe/Paris",
        stopAtLocalMinutes = 600,
    )

    private val maxAge = Durations.ACTIVE.sessionMaxAgeMs

    @Test
    fun `a night in progress is not stale`() {
        val m = marker(plannedStopWallMs = start + 8 * 3_600_000L)
        assertThat(m.isStale(start + 3_600_000L, ageMaxMs = 14 * 3_600_000L)).isFalse()
    }

    @Test
    fun `the planned stop time makes the night stale`() {
        val m = marker(plannedStopWallMs = start + 8 * 3_600_000L)
        assertThat(m.isStale(start + 8 * 3_600_000L, ageMaxMs = 14 * 3_600_000L)).isTrue()
    }

    @Test
    fun `the maximum age makes the night stale even if the planned time is absurd`() {
        // This is the case the second condition exists for: a wrong `plannedStopWallMs` — because
        // it was written before a time change, or because the marker has been lying around for a
        // week — would otherwise let a recording resume in the middle of the afternoon.
        val m = marker(plannedStopWallMs = start + 1_000L * 3_600_000L)
        assertThat(m.isStale(start + 14 * 3_600_000L, ageMaxMs = 14 * 3_600_000L)).isTrue()
    }

    @Test
    fun `the default maximum age follows the scale of the variant`() {
        val m = marker(plannedStopWallMs = Long.MAX_VALUE)
        assertThat(m.isStale(start + maxAge - 1)).isFalse()
        assertThat(m.isStale(start + maxAge)).isTrue()
    }
}
