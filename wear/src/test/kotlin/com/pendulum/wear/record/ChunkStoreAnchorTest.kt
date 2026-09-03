package com.pendulum.wear.record

import com.pendulum.format.ChunkReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The time anchor written into the chunk header: which instant the three clocks describe.
 *
 * The phone reads `(startWallMs, firstEventTimestampNs)` as one and the same instant, and
 * projects the Health Connect hypnogram and the bedtime journal onto the sensor timeline through
 * that pair. The defect these tests pin: the chunk opens when the first **block** reaches the
 * disk, and in `WAKEUP 30 s` mode that is one FIFO burst — up to thirty seconds — after the sample
 * the header names as its first. Every movement of the night then lands one 30 s sleep epoch too
 * late against the hypnogram, by an amount that changes from one night to the next.
 */
class ChunkStoreAnchorTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        const val WALL_MS = 1_700_000_000_000L
        const val STEP_NS = 20_000_000L // 50 Hz
    }

    private fun store(wallMs: Long, elapsedNs: Long) = ChunkStore(
        sessionDir = File(dir, "session"),
        sessionUuid = ByteArray(16) { it.toByte() },
        sensorResolution = 0.0023956f,
        sensorMaxRange = 78.4532f,
        fifoReserved = 3000,
        startIndex = 0,
        rateHz = 50,
        modeFlags = 0,
        wallClockMs = { wallMs },
        elapsedNs = { elapsedNs },
    )

    /** Writes one 512-sample block whose first sample is stamped [tFirstNs], then closes. */
    private fun ChunkStore.oneBlock(tFirstNs: Long) {
        val n = 512
        writeBlock(
            FloatArray(n), FloatArray(n), FloatArray(n),
            n, tFirstNs, tFirstNs + (n - 1) * STEP_NS, 0, 0L,
        )
        close()
    }

    private fun header() = ChunkReader
        .read(File(dir, "session").listFiles()!!.single().inputStream())
        .header

    @Test
    @DisplayName("startWallMs dates the first sample, not the burst that delivered it")
    fun `the wall clock is pulled back by the age of the first sample`() {
        // WAKEUP 30 s: the FIFO flushes at elapsed 130 s a burst whose first sample was taken at
        // elapsed 100 s. The chunk opens during that flush.
        val cs = store(wallMs = WALL_MS, elapsedNs = 130_000_000_000L)
        cs.oneBlock(tFirstNs = 100_000_000_000L)

        val h = header()
        assertThat(h.firstEventTimestampNs).isEqualTo(100_000_000_000L)
        assertThat(h.startWallMs).isEqualTo(WALL_MS - 30_000L)
        // All three clocks describe the same instant: a reader pairing any two of them is right.
        assertThat(h.startElapsedRealtimeNs).isEqualTo(100_000_000_000L)
    }

    @Test
    @DisplayName("continuous mode: the ten seconds of the first 512-sample block are pulled back too")
    fun `the correction is not specific to batching`() {
        val cs = store(wallMs = WALL_MS, elapsedNs = 110_240_000_000L)
        cs.oneBlock(tFirstNs = 100_000_000_000L)
        assertThat(header().startWallMs).isEqualTo(WALL_MS - 10_240L)
    }

    @Test
    @DisplayName("an implausible age means two clock bases, and the header is left raw")
    fun `an age beyond the latency ceiling is not subtracted`() {
        // Some OEMs exclude suspend time from SensorEvent.timestamp: two hours into the night the
        // difference no longer measures a burst. Subtracting it would move the anchor by hours;
        // leaving it makes the anchor merely late, as it was — and keeps the raw triplet for the
        // phone to see the drift.
        val cs = store(wallMs = WALL_MS, elapsedNs = 7_300_000_000_000L)
        cs.oneBlock(tFirstNs = 100_000_000_000L)

        val h = header()
        assertThat(h.startWallMs).isEqualTo(WALL_MS)
        assertThat(h.startElapsedRealtimeNs).isEqualTo(7_300_000_000_000L)
    }

    @Test
    @DisplayName("a sensor clock ahead of the boot clock is left alone, as on the bench")
    fun `a negative age is not subtracted`() {
        // The bench replays sensor time 250 times faster than wall time: after the first burst
        // the sample stamps run ahead of elapsedRealtimeNanos. That is not a burst age.
        val cs = store(wallMs = WALL_MS, elapsedNs = 100_000_000_000L)
        cs.oneBlock(tFirstNs = 160_000_000_000L)

        val h = header()
        assertThat(h.startWallMs).isEqualTo(WALL_MS)
        assertThat(h.startElapsedRealtimeNs).isEqualTo(100_000_000_000L)
    }
}
