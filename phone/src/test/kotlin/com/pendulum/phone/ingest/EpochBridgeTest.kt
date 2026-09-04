package com.pendulum.phone.ingest

import com.pendulum.algo.dsp.Integrity
import com.pendulum.algo.model.IntegrityViolation
import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader
import com.pendulum.format.ChunkWriter
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The night survives a reboot of the watch.
 *
 * `SensorEvent.timestamp` is `elapsedRealtimeNanos`, which restarts from zero when the watch
 * reboots — and a reboot mid-night is a case the watch handles on purpose: `BootReceiver` resumes
 * the same session, with the chunk numbering carried on. Every post-reboot block therefore dated
 * *before* the last pre-reboot one, and step −1 rejected them one after another as
 * `NON_MONOTONIC`, because it compares against the last **accepted** block, which never advances
 * again. Half a night lost, reported as an integrity failure although every file was intact.
 *
 * These tests exist because that repair was the largest of its commit and the only one asserted
 * without proof. It rests on a difference of wall clocks — which this code base forbids everywhere
 * else — and on a floor that guards against the watch resynchronising its clock *backwards* just
 * after booting. A sign flipped in the offset, or a floor placed one period too early, restores the
 * exact defect being fixed, and nothing else in the suite would go red.
 */
class EpochBridgeTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        const val HEX = "0123456789abcdef0123456789abcdef"
        const val RATE_HZ = 50
        const val STEP_NS = 20_000_000L
        const val SAMPLES = 128

        /** Where the first boot epoch starts on the sensor clock: two hours of uptime. */
        const val FIRST_BOOT_NS = 7_200_000_000_000L

        /** Wall clock at the first chunk. */
        const val FIRST_WALL_MS = 1_700_000_000_000L
    }

    private fun header(
        idx: Int,
        elapsedNs: Long,
        wallMs: Long,
        firstEventNs: Long = elapsedNs,
    ) = ChunkHeader(
        sessionUuid = ByteArray(16) { it.toByte() },
        chunkIndex = idx,
        nominalRateHz = RATE_HZ,
        startWallMs = wallMs,
        startElapsedRealtimeNs = elapsedNs,
        firstEventTimestampNs = firstEventNs,
        sensorResolution = 0.0023956f,
        sensorMaxRange = 78.4532f,
        fifoMaxEventCount = 3000,
        modeFlags = ChunkFormat.MODE_WAKEUP_SENSOR,
        tzOffsetMin = 0,
    )

    /** One chunk of [SAMPLES] samples starting at [firstEventNs] on the sensor clock. */
    private fun writeChunk(idx: Int, elapsedNs: Long, wallMs: Long, firstEventNs: Long = elapsedNs): File {
        val file = File(dir, "%05d.pendulum".format(idx))
        file.outputStream().buffered().use { os ->
            val w = ChunkWriter(os, header(idx, elapsedNs, wallMs, firstEventNs))
            w.writeBlock(
                FloatArray(SAMPLES), FloatArray(SAMPLES),
                FloatArray(SAMPLES) { ChunkFormat.G_IN_MS2.toFloat() },
                SAMPLES, firstEventNs, firstEventNs + (SAMPLES - 1) * STEP_NS, 0,
            )
            w.finish()
        }
        return file
    }

    private fun reassemble(files: List<File>) =
        SessionReassembler.reassemble(HEX, files.sortedBy { it.name }, true, files.size)

    @Test
    fun `a night with no reboot is left exactly where it was`() {
        val a = writeChunk(0, FIRST_BOOT_NS, FIRST_WALL_MS)
        val bStart = FIRST_BOOT_NS + 900_000_000_000L
        val b = writeChunk(1, bStart, FIRST_WALL_MS + 900_000L)

        val night = reassemble(listOf(a, b))

        assertThat(night.epochResets).isZero()
        assertThat(night.blocks.first().tFirstNs).isEqualTo(FIRST_BOOT_NS)
        assertThat(night.blocks.last().tFirstNs).isEqualTo(bStart)
    }

    @Test
    fun `blocks recorded after a reboot are placed after the ones before it`() {
        // 15 min of night, then the watch reboots and comes back 2 min later on a sensor clock
        // that restarted near zero.
        val a = writeChunk(0, FIRST_BOOT_NS, FIRST_WALL_MS)
        val elapsedSinceFirstChunkMs = 15 * 60_000L + 2 * 60_000L
        val b = writeChunk(1, 3_000_000_000L, FIRST_WALL_MS + elapsedSinceFirstChunkMs)

        val night = reassemble(listOf(a, b))

        assertThat(night.epochResets).isEqualTo(1)
        val lastBefore = night.blocks.first().tLastNs
        val firstAfter = night.blocks.last().tFirstNs
        // Monotonic, which is the whole point: step −1 accepts them instead of rejecting the lot.
        assertThat(firstAfter).isGreaterThan(lastBefore)
        // And placed at the real distance the wall clock measured, not at an arbitrary one: the
        // hole is what the reboot really cost, minus the chunk already played out before it.
        val holeNs = firstAfter - lastBefore
        val expectedNs = elapsedSinceFirstChunkMs * 1_000_000L - (SAMPLES - 1) * STEP_NS
        assertThat(holeNs).isCloseTo(expectedNs, Offset.offset(STEP_NS * 2))
    }

    @Test
    fun `a wall clock that steps backwards at boot still lands after the last sample`() {
        // The watch reboots and resynchronises over NTP, which moves its wall clock *back* by more
        // than the reboot lasted. Taken at face value the bridge would place the new epoch on top
        // of — or before — the samples already stacked, and step −1 would reject them as
        // NON_MONOTONIC or OVERLAP: the very defect this repair exists to close, reintroduced by
        // its own fix. The floor is what forbids that.
        val a = writeChunk(0, FIRST_BOOT_NS, FIRST_WALL_MS)
        val b = writeChunk(1, 3_000_000_000L, FIRST_WALL_MS - 30 * 60_000L)

        val night = reassemble(listOf(a, b))

        assertThat(night.epochResets).isEqualTo(1)
        val lastBefore = night.blocks.first().tLastNs
        val firstAfter = night.blocks.last().tFirstNs
        assertThat(firstAfter).isGreaterThan(lastBefore)
        // Two sample periods of clearance, so what follows reads as a gap and never as an overlap.
        assertThat(firstAfter - lastBefore).isGreaterThanOrEqualTo(2 * STEP_NS)
    }

    @Test
    fun `two reboots in one night accumulate, they do not cancel out`() {
        val a = writeChunk(0, FIRST_BOOT_NS, FIRST_WALL_MS)
        val b = writeChunk(1, 3_000_000_000L, FIRST_WALL_MS + 20 * 60_000L)
        val c = writeChunk(2, 2_000_000_000L, FIRST_WALL_MS + 40 * 60_000L)

        val night = reassemble(listOf(a, b, c))

        assertThat(night.epochResets).isEqualTo(2)
        val t = night.blocks.map { it.tFirstNs }
        assertThat(t).isSorted
        // The third epoch sits after the second, not folded back onto the first — which is what a
        // non-cumulative offset would do.
        assertThat(t[2] - t[1]).isGreaterThan(0L)
    }

    @Test
    fun `the blocks recorded after a reboot survive the integrity check`() {
        val a = writeChunk(0, FIRST_BOOT_NS, FIRST_WALL_MS)
        val b = writeChunk(1, 3_000_000_000L, FIRST_WALL_MS + 17 * 60_000L)

        val night = reassemble(listOf(a, b))

        // Through step −1, and not merely through the reassembler. This is the whole point: the
        // second chunk was always read from disk and adopted — `sampleCount` counted it before the
        // bridge existed — and then thrown away one block at a time by `Integrity.check`, which
        // compares against the last *accepted* block and so never advanced again. Asserting on the
        // reassembler alone would be an assertion that passed while the defect was present.
        val (accepted, report) = Integrity.check(night.blocks, night.nominalRateHz.toDouble())

        assertThat(accepted).hasSize(night.blocks.size)
        assertThat(report.byViolation[IntegrityViolation.NON_MONOTONIC]).isNull()
        assertThat(report.blocksRejected).isZero()
    }
}
