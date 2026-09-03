package com.pendulum.phone.ingest

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which instant of the chunk header the anchor treats as "the first sample".
 *
 * The header carries three clocks. Until 4 September 2026 the watch stamped `startWallMs` and
 * `startElapsedRealtimeNs` when the first **block** reached the disk and `firstEventTimestampNs`
 * at the first **sample** — in `WAKEUP 30 s` mode, one FIFO burst apart, up to thirty seconds —
 * and the anchor read the wall clock and the sample stamp as one and the same instant. Every
 * movement of the night was then projected 10 to 60 s too late against the Health Connect
 * hypnogram and the bedtime journal: a whole 30 s sleep epoch, varying from one night to the next.
 *
 * The watch now pulls both its wall and boot clocks back to the first sample, so a header it
 * produces carries no age. These tests cover the header that still does: a night recorded before
 * that change, or a bench recording, where the age is readable from the boot clock alone.
 */
class TimeAnchorTest {

    private companion object {
        const val WALL_MS = 1_700_000_000_000L
        const val FIRST_SAMPLE_NS = 100_000_000_000L
    }

    private fun header(startWallMs: Long, startElapsedNs: Long, firstEventNs: Long) = ChunkHeader(
        sessionUuid = ByteArray(16) { it.toByte() },
        chunkIndex = 0,
        nominalRateHz = 50,
        startWallMs = startWallMs,
        startElapsedRealtimeNs = startElapsedNs,
        firstEventTimestampNs = firstEventNs,
        sensorResolution = 0.0023956f,
        sensorMaxRange = 78.4532f,
        fifoMaxEventCount = 3000,
        modeFlags = ChunkFormat.MODE_WAKEUP_SENSOR or ChunkFormat.MODE_BATCHED,
        tzOffsetMin = 60,
    )

    @Test
    @DisplayName("an old header: the wall clock is pulled back by the age read from the boot clock")
    fun `the first sample of an old header maps to zero, not to the burst that delivered it`() {
        // WAKEUP 30 s, header written by the old watch: the FIFO flushed at elapsed 130 s a burst
        // whose first sample was taken at elapsed 100 s, and the header stamped both the wall and
        // the boot clock at the flush.
        val anchor = TimeAnchor.of(
            header(startWallMs = WALL_MS, startElapsedNs = 130_000_000_000L, firstEventNs = FIRST_SAMPLE_NS),
            timelineT0Ns = FIRST_SAMPLE_NS,
        )
        // The first sample happened 30 s before the wall clock of the header.
        assertThat(anchor.toMsRel(WALL_MS - 30_000L)).isEqualTo(0L)
        // Read the other way: the wall clock of the header is 30 s into the timeline.
        assertThat(anchor.toMsRel(WALL_MS)).isEqualTo(30_000L)
        assertThat(anchor.toWallMs(0L)).isEqualTo(WALL_MS - 30_000L)
    }

    @Test
    @DisplayName("a header of the current watch carries no age, and nothing is subtracted")
    fun `a header whose three clocks describe the first sample is left as it is`() {
        val anchor = TimeAnchor.of(
            header(startWallMs = WALL_MS, startElapsedNs = FIRST_SAMPLE_NS, firstEventNs = FIRST_SAMPLE_NS),
            timelineT0Ns = FIRST_SAMPLE_NS,
        )
        assertThat(anchor.toMsRel(WALL_MS)).isEqualTo(0L)
        assertThat(anchor.toWallMs(0L)).isEqualTo(WALL_MS)
    }

    @Test
    @DisplayName("the two corrections do not stack: the watch's pull-back leaves a zero age here")
    fun `a header already pulled back by the watch is not corrected a second time`() {
        // The watch subtracted the 30 s age from both clocks. The age this side reads from the
        // boot clock is therefore zero, and the anchor is the watch's — not 30 s earlier again.
        val pulledBack = header(
            startWallMs = WALL_MS - 30_000L,
            startElapsedNs = FIRST_SAMPLE_NS,
            firstEventNs = FIRST_SAMPLE_NS,
        )
        val anchor = TimeAnchor.of(pulledBack, timelineT0Ns = FIRST_SAMPLE_NS)
        assertThat(anchor.toMsRel(WALL_MS - 30_000L)).isEqualTo(0L)
    }

    @Test
    @DisplayName("an implausible age means two clock bases, and it is not subtracted")
    fun `an age beyond the latency ceiling is not subtracted`() {
        // Some OEMs exclude suspend time from SensorEvent.timestamp: two hours into the night the
        // difference no longer measures a burst. Subtracting it would move the hypnogram by hours.
        val anchor = TimeAnchor.of(
            header(startWallMs = WALL_MS, startElapsedNs = 7_300_000_000_000L, firstEventNs = FIRST_SAMPLE_NS),
            timelineT0Ns = FIRST_SAMPLE_NS,
        )
        assertThat(anchor.toMsRel(WALL_MS)).isEqualTo(0L)
    }

    @Test
    @DisplayName("a sensor clock ahead of the boot clock is left alone, as on the bench")
    fun `a negative age is not subtracted`() {
        // The bench replays sensor time 250 times faster than wall time: the sample stamps run
        // ahead of elapsedRealtimeNanos. That is not a burst age.
        val anchor = TimeAnchor.of(
            header(startWallMs = WALL_MS, startElapsedNs = FIRST_SAMPLE_NS, firstEventNs = 160_000_000_000L),
            timelineT0Ns = 160_000_000_000L,
        )
        assertThat(anchor.toMsRel(WALL_MS)).isEqualTo(0L)
    }

    @Test
    @DisplayName("the age and the rejected-first-blocks offset are both applied, in the right direction")
    fun `a timeline that starts after the first sample keeps its own offset on top of the age`() {
        // Old header with a 30 s burst age, and the first two seconds of blocks rejected by the
        // integrity check: the timeline origin is 2 s after the first sample.
        val anchor = TimeAnchor.of(
            header(startWallMs = WALL_MS, startElapsedNs = 130_000_000_000L, firstEventNs = FIRST_SAMPLE_NS),
            timelineT0Ns = FIRST_SAMPLE_NS + 2_000_000_000L,
        )
        // Origin of the timeline = first sample (WALL - 30 s) + 2 s = WALL - 28 s.
        assertThat(anchor.toMsRel(WALL_MS - 28_000L)).isEqualTo(0L)
        assertThat(anchor.toWallMs(0L)).isEqualTo(WALL_MS - 28_000L)
    }

    @Test
    @DisplayName("without the boot clock, the anchor behaves as before: no correction")
    fun `the three-field constructor assumes no age`() {
        // The reassembler still builds the anchor from three fields; the default leaves it exactly
        // where it was, so that a caller which has not been switched to `of` loses nothing.
        val anchor = TimeAnchor(
            startWallMs = WALL_MS,
            firstEventTimestampNs = FIRST_SAMPLE_NS,
            timelineT0Ns = FIRST_SAMPLE_NS,
        )
        assertThat(anchor.toMsRel(WALL_MS)).isEqualTo(0L)
    }
}
