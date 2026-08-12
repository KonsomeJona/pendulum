package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkReader
import com.pendulum.format.TelemetryPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Telemetry as seen from the watch: where it lands, and what it must not cost.
 *
 * The central invariant is the one stated by the KDoc of
 * [WireProtocol.TELEMETRY_PERIOD_MS][com.pendulum.format.wire.WireProtocol.TELEMETRY_PERIOD_MS]:
 * **a complete chunk carries at least one point**. Without it, a lost chunk would take with it a
 * telemetry hole that no other chunk would fill — and that nobody would even be able to count.
 */
class ChunkStoreTelemetryTest {

    @TempDir
    lateinit var dir: File

    private val stepNs = 20_000_000L

    private fun store(rotationMs: Long = 300L, maxRange: Float = 78.4532f) = ChunkStore(
        sessionDir = File(dir, "session"),
        sessionUuid = ByteArray(16) { it.toByte() },
        sensorResolution = 0.0023956f,
        sensorMaxRange = maxRange,
        fifoReserved = 3000,
        startIndex = 0,
        rateHz = 50,
        modeFlags = 0,
        rotationMs = rotationMs,
    )

    private fun point(elapsedNs: Long) = TelemetryPoint(
        elapsedRealtimeNs = elapsedNs,
        sensorTsNs = elapsedNs,
        batteryChargeUah = 210_000,
        maxIntervalUs = 20_100,
        fsyncTotalUs = 1_234,
        fsyncMaxUs = 900,
        temperatureDeciC = 312,
        measuredRateCentiHz = 5_003,
        jitterStdUs = 180,
        clippedSamples = 0,
        fsyncCount = 3,
        batteryPct = 77,
        offBody = TelemetryPoint.OFF_BODY_WORN,
        charging = false,
    )

    /** A flat block of [n] samples at exactly 50 Hz, value [value] on the x axis. */
    private fun ChunkStore.block(n: Int, tFirstNs: Long, nowMs: Long, value: Float = 0f): Int? =
        writeBlock(
            FloatArray(n) { value }, FloatArray(n), FloatArray(n),
            n, tFirstNs, tFirstNs + (n - 1) * stepNs, 0, nowMs,
        )

    private fun files(): List<File> =
        File(dir, "session").listFiles { f -> f.name.endsWith(".pendulum") }!!.sortedBy { it.name }

    // --- Where the point lands ---

    @Test
    @DisplayName("a point written during a chunk is read back in that chunk")
    fun `the point lands in the current chunk`() {
        val cs = store()
        cs.block(50, 0L, 0L)
        val p = point(42_000_000L)
        assertThat(cs.writeTelemetry(p)).isTrue()
        cs.block(50, 50 * stepNs, 20L)
        cs.close()

        val chunk = ChunkReader.read(files().single().inputStream())
        assertThat(chunk.telemetry).containsExactly(p)
        assertThat(chunk.blocks).hasSize(2)
        assertThat(chunk.scan.complete).isTrue()
        assertThat(chunk.scan.declaredTelemetryPointCount).isEqualTo(1)
    }

    @Test
    @DisplayName("with no chunk open, the point is refused rather than invented")
    fun `no chunk open, no point`() {
        // Telemetry never opens a chunk on its own: the file header carries
        // `firstEventTimestampNs`, which does not exist until a sample has arrived. A chunk
        // opened by telemetry would carry an invented time base.
        val cs = store()
        assertThat(cs.chunkOpen).isFalse()
        assertThat(cs.writeTelemetry(point(0L))).isFalse()
        assertThat(File(dir, "session").listFiles()).isEmpty()
    }

    @Test
    @DisplayName("every chunk of a rotation carries its points")
    fun `every chunk carries at least one point`() {
        // The 5-to-1 ratio of real operation — 300 s of rotation, one point per minute — replayed
        // at the scale of the test: 300 ms of rotation, one point every 60 ms.
        val cs = store(rotationMs = 300L)
        var now = 0L
        var ts = 0L
        var nextPoint = 60L
        var written = 0
        repeat(60) {
            cs.block(50, ts, now)
            ts += 50 * stepNs
            now += 20L
            if (now >= nextPoint) {
                if (cs.writeTelemetry(point(now * 1_000_000L))) written++
                nextPoint += 60L
            }
        }
        cs.close()

        val chunks = files()
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(4)
        val perChunk = chunks.map { ChunkReader.read(it.inputStream()).telemetry.size }
        assertThat(perChunk)
            .`as`("telemetry points per chunk")
            .allSatisfy { assertThat(it).isGreaterThanOrEqualTo(1) }
        assertThat(perChunk.sum()).isEqualTo(written)
    }

    @Test
    @DisplayName("telemetry bytes count towards rotation by volume")
    fun `telemetry does not bypass the byte ceiling`() {
        // It has no ceiling of its own: its bytes go into the writer's `bytesWritten`, that is,
        // into the rotation-by-volume condition. That is what makes the 92 160-byte guard rail
        // cover it without anything having had to be added for it.
        val cs = store()
        cs.block(50, 0L, 0L)
        val before = cs.totalBytes
        cs.writeTelemetry(point(0L))
        assertThat(cs.totalBytes - before)
            .isEqualTo(
                (ChunkFormat.TELEMETRY_HEADER_SIZE + ChunkFormat.TELEMETRY_POINT_SIZE).toLong(),
            )
    }

    // --- The counters the point consumes ---

    @Test
    @DisplayName("the freezes caused by fsync are consumed then reset to zero")
    fun `fsync counters are consumed`() {
        val cs = store()
        cs.block(50, 0L, 0L) // opening the chunk fsyncs its header
        cs.sync()
        cs.sync()
        cs.close()

        val e = cs.consumeFlashWrites()
        assertThat(e.count).isEqualTo(4)
        assertThat(e.totalUs).isGreaterThanOrEqualTo(0L)
        assertThat(e.maxUs).isLessThanOrEqualTo(e.totalUs)

        // Consuming means consuming: otherwise two consecutive points would count the same freeze
        // twice, and the night's freeze budget would come out doubled.
        assertThat(cs.consumeFlashWrites()).isEqualTo(FlashWrites(0, 0L, 0L))
    }

    @Test
    @DisplayName("sensor clipping is counted across chunk rotations")
    fun `clipping accumulates across rotations`() {
        // The writer's counter restarts from zero at every chunk; the telemetry, for its part,
        // runs across the whole night. Without the difference taken by `ChunkStore`, every
        // rotation would reset the counter and the clipping at the end of a chunk would vanish
        // outright.
        val fourG = 4f * ChunkFormat.G_IN_MS2.toFloat()
        val cs = store(rotationMs = 10L, maxRange = fourG)
        cs.block(10, 0L, 0L, value = fourG + 1f)
        cs.block(10, 10 * stepNs, 100L, value = fourG + 1f) // closes chunk 0, opens chunk 1
        cs.close()

        assertThat(files()).hasSize(2)
        assertThat(cs.consumeClippedSamples()).isEqualTo(20L)
        assertThat(cs.consumeClippedSamples()).isZero()
    }
}
