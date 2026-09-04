package com.pendulum.phone.ingest

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader
import com.pendulum.format.ChunkWriter
import com.pendulum.format.TelemetryPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The row rebuilt from a chunk file on the phone's disk.
 *
 * `IngestWorker` used to rebuild it with `tFirstNs = 0`, `tLastNs = 0`, `flagsOr = 0` and
 * harvested no telemetry: one recovered chunk put the origin of the sensor time base at zero and
 * the metrology band of the whole night off the axis. These tests hold a file next to the row it
 * yields, and require the row to be the one the live path would have written from the watch's
 * `ChunkMeta` — the same sums, extrema, `OR` and CRC.
 */
class ChunkIngestorTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        const val HEX = "0123456789abcdef0123456789abcdef"
        const val STEP_NS = 20_000_000L // 50 Hz
        const val T0_NS = 5_000_000_000L
        const val RECEIVED_AT = 1_700_000_000_000L
    }

    private fun header() = ChunkHeader(
        sessionUuid = ByteArray(16) { it.toByte() },
        chunkIndex = 7,
        nominalRateHz = 50,
        startWallMs = RECEIVED_AT - 60_000L,
        startElapsedRealtimeNs = T0_NS,
        firstEventTimestampNs = T0_NS,
        sensorResolution = 0.0023956f,
        sensorMaxRange = 78.4532f,
        fifoMaxEventCount = 3000,
        modeFlags = ChunkFormat.MODE_WAKEUP_SENSOR or ChunkFormat.MODE_BATCHED,
        tzOffsetMin = 60,
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
        clippedSamples = 4,
        fsyncCount = 3,
        batteryPct = 77,
        offBody = TelemetryPoint.OFF_BODY_WORN,
        charging = false,
    )

    /**
     * Three blocks of 512 samples, a telemetry point between the second and the third, distinct
     * flags on two of the blocks, and the end marker unless [finish] is false.
     */
    private fun writeChunk(finish: Boolean = true): File {
        val file = File(dir, "00007.pendulum")
        file.outputStream().buffered().use { os ->
            val w = ChunkWriter(os, header())
            var t = T0_NS
            val n = 512
            val flags = intArrayOf(0, ChunkFormat.FLAG_GAP_BEFORE, ChunkFormat.FLAG_SENSOR_CLIPPED)
            for (i in 0 until 3) {
                if (i == 2) w.writeTelemetry(point(t - 1_000L))
                w.writeBlock(
                    FloatArray(n), FloatArray(n), FloatArray(n) { ChunkFormat.G_IN_MS2.toFloat() },
                    n, t, t + (n - 1) * STEP_NS, flags[i],
                )
                t += n * STEP_NS
            }
            if (finish) w.finish()
        }
        return file
    }

    @Test
    @DisplayName("the row carries the instants of the blocks, not zeros")
    fun `the recovered row names the first and the last sample of the file`() {
        val file = writeChunk()
        val row = ChunkIngestor.scan(HEX, 7, file, RECEIVED_AT).row

        assertThat(row.tFirstNs).isEqualTo(T0_NS)
        assertThat(row.tLastNs).isEqualTo(T0_NS + (3 * 512 - 1) * STEP_NS)
        assertThat(row.sampleCount).isEqualTo(3 * 512)
        assertThat(row.flagsOr).isEqualTo(ChunkFormat.FLAG_GAP_BEFORE or ChunkFormat.FLAG_SENSOR_CLIPPED)
        assertThat(row.complete).isTrue()
    }

    @Test
    @DisplayName("the row is the one the live path would have written from the watch's metadata")
    fun `size, CRC, index, session and path match the file`() {
        val file = writeChunk()
        val bytes = file.readBytes()
        val row = ChunkIngestor.scan(HEX, 7, file, RECEIVED_AT).row

        assertThat(row.sessionHex).isEqualTo(HEX)
        assertThat(row.idx).isEqualTo(7)
        assertThat(row.path).isEqualTo(file.absolutePath)
        assertThat(row.size).isEqualTo(bytes.size)
        // The same CRC the verifier compared with the watch's `ChunkMeta.crc32` on arrival.
        assertThat(row.crc32).isEqualTo(ChunkStore.crc32(bytes))
        assertThat(row.receivedAtMs).isEqualTo(RECEIVED_AT)
    }

    @Test
    @DisplayName("the telemetry of the file is harvested, as the live path harvests it")
    fun `the TLM blocks come out as rows of the same session`() {
        val file = writeChunk()
        val telemetry = ChunkIngestor.scan(HEX, 7, file, RECEIVED_AT).telemetry

        assertThat(telemetry).hasSize(1)
        val p = telemetry.single()
        assertThat(p.sessionHex).isEqualTo(HEX)
        assertThat(p.elapsedRealtimeNs).isEqualTo(T0_NS + 2 * 512 * STEP_NS - 1_000L)
        assertThat(p.batteryPct).isEqualTo(77)
        assertThat(p.clippedSamples).isEqualTo(4)
    }

    @Test
    @DisplayName("a file without its end marker is recovered but never marked complete")
    fun `a chunk still being written is not acknowledgeable`() {
        val file = writeChunk(finish = false)
        val row = ChunkIngestor.scan(HEX, 7, file, RECEIVED_AT).row

        // The blocks are there, and the instants with them; only the acknowledgement is withheld.
        assertThat(row.complete).isFalse()
        assertThat(row.sampleCount).isEqualTo(3 * 512)
        assertThat(row.tFirstNs).isEqualTo(T0_NS)
    }
}
