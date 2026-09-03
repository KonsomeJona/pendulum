package com.pendulum.phone.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader
import com.pendulum.format.ChunkWriter
import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.eraseEverything
import com.pendulum.phone.ingest.ChunkStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * What `importBundle` writes into the database — the half of the round trip that Room needs.
 *
 * ### Why this test exists next to `BundleRoundTripTest`
 *
 * The JVM test proves that the same chunk bytes analyse into the same figure. It never calls
 * [NightExporter.importBundle], and the KDoc of that function claimed it did. Between the two
 * lived a loss the figure could not reveal: the import re-read each chunk for its end marker only,
 * dropping the `TLM!` points the reader had already decoded, and wrote the chunk row with
 * `sampleCount = 0, tFirstNs = 0, tLastNs = 0, flagsOr = 0`. A campaign carried to a new phone
 * arrived with `telemetry_point` empty on every night: no metrology band, an empty
 * `Metrology.summary`, and the P1 battery criterion `UNDETERMINED` for the whole campaign — the
 * P1 report from the new phone contradicted the one from the old phone on the same nights.
 *
 * This test builds a real chunk with real telemetry blocks, wraps it in a bundle, imports it, and
 * reads back what ingestion would have written for the same bytes.
 */
@RunWith(AndroidJUnit4::class)
class BundleImportTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun before() = startFromAnEmptyState()

    @After
    fun after() = startFromAnEmptyState()

    @Test
    fun anImportedChunk_keepsItsTelemetry_andItsTimeBase() {
        val chunk = chunkWithTelemetry()
        val bundle = NightBundle.toByteArray(
            NightBundle.Content(
                manifest = mapOf(
                    "sessionHex" to HEX,
                    "startWallMs" to START_WALL_MS.toString(),
                    "plannedStopWallMs" to (START_WALL_MS + 8 * 3_600_000L).toString(),
                    "zoneId" to "Asia/Tokyo",
                    "tzOffsetStartMin" to "540",
                    "tzOffsetEndMin" to "540",
                    "nominalRateHz" to RATE_HZ.toString(),
                    "modeFlags" to "0",
                    "state" to "CLOSED",
                    "totalChunks" to "1",
                ),
                context = emptyMap(),
                baseline = emptyMap(),
                hypnogramCsv = "",
                chunks = mapOf(0 to chunk),
            )
        )

        val imported = runBlocking { NightExporter.importBundle(ctx, bundle.inputStream()) }
        if (imported != HEX) error("the import returned another night: $imported")

        val db = PendulumDatabase.get(ctx)

        // The points are in the chunk bytes; the bundle carried them; the database must hold them.
        val points = runBlocking { db.telemetryDao().ofSession(HEX) }
        if (points.size != TELEMETRY_POINTS) {
            error("telemetry lost on import: ${points.size} point(s) in the database, $TELEMETRY_POINTS in the chunk")
        }
        val batteries = points.map { it.batteryPct }
        if (batteries != (0 until TELEMETRY_POINTS).map { 90 - it }) {
            error("the points came back altered: battery $batteries")
        }

        // The chunk row carries the same summary the watch computes before sending
        // (`DataLayerTransfer.summarize`): sum of the samples, min/max of the block time base,
        // OR of the flags. `tFirstNs` is the origin of the sensor time base for the whole night.
        val row = runBlocking { db.chunkDao().find(HEX, 0) }
            ?: error("no chunk row after import")
        if (!row.complete) error("the chunk was written complete and read back incomplete")
        if (row.sampleCount != BLOCKS * SAMPLES_PER_BLOCK) {
            error("sampleCount ${row.sampleCount}, expected ${BLOCKS * SAMPLES_PER_BLOCK}")
        }
        if (row.tFirstNs != T0_NS) error("tFirstNs ${row.tFirstNs}, expected $T0_NS")
        val expectedLast = T0_NS + (BLOCKS * SAMPLES_PER_BLOCK - 1) * DT_NS
        if (row.tLastNs != expectedLast) error("tLastNs ${row.tLastNs}, expected $expectedLast")
        if (row.flagsOr != ChunkFormat.FLAG_GAP_BEFORE) {
            error("flagsOr ${row.flagsOr}, expected the flag set on one block (${ChunkFormat.FLAG_GAP_BEFORE})")
        }
    }

    /**
     * One complete chunk: a few blocks of still gravity, one telemetry point between every two
     * blocks, and one block flagged so that `flagsOr` has something to carry.
     */
    private fun chunkWithTelemetry(): ByteArray {
        val header = ChunkHeader(
            sessionUuid = ByteArray(16) { it.toByte() },
            chunkIndex = 0,
            nominalRateHz = RATE_HZ,
            startWallMs = START_WALL_MS,
            startElapsedRealtimeNs = T0_NS,
            firstEventTimestampNs = T0_NS,
            sensorResolution = 0.0012f,
            sensorMaxRange = 78.4532f,
            fifoMaxEventCount = 3000,
            modeFlags = ChunkFormat.MODE_WAKEUP_SENSOR or ChunkFormat.MODE_BATCHED,
            tzOffsetMin = 540,
        )
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header)
        val x = FloatArray(SAMPLES_PER_BLOCK)
        val y = FloatArray(SAMPLES_PER_BLOCK)
        val z = FloatArray(SAMPLES_PER_BLOCK) { ChunkFormat.G_IN_MS2.toFloat() }
        for (b in 0 until BLOCKS) {
            val tFirst = T0_NS + b.toLong() * SAMPLES_PER_BLOCK * DT_NS
            val flags = if (b == 1) ChunkFormat.FLAG_GAP_BEFORE else 0
            w.writeBlock(x, y, z, SAMPLES_PER_BLOCK, tFirst, tFirst + (SAMPLES_PER_BLOCK - 1) * DT_NS, flags)
            if (b < TELEMETRY_POINTS) {
                w.writeTelemetry(
                    TelemetryPoint(
                        elapsedRealtimeNs = tFirst,
                        sensorTsNs = tFirst,
                        batteryChargeUah = 200_000 - b * 1_000,
                        maxIntervalUs = 21_000L,
                        fsyncTotalUs = 0L,
                        fsyncMaxUs = 0L,
                        temperatureDeciC = 310,
                        measuredRateCentiHz = 5_000,
                        jitterStdUs = 120,
                        clippedSamples = 0,
                        fsyncCount = 0,
                        batteryPct = 90 - b,
                        offBody = 0,
                        charging = false,
                    )
                )
            }
        }
        w.finish()
        return out.toByteArray()
    }

    /**
     * Files before the database, as `DataEraser` does: a database pointing at files that are gone
     * is detectable, orphaned files are not. `ChunkStore.deleteSession` rather than
     * `DataEraser.eraseEverything`, which would also cancel the application's scheduled work on the
     * device that runs the test.
     */
    private fun startFromAnEmptyState() {
        ChunkStore(ctx).deleteSession(HEX)
        runBlocking { PendulumDatabase.get(ctx).eraseEverything() }
    }

    private companion object {
        const val HEX = "0123456789abcdef0123456789abcdef"
        const val RATE_HZ = 50
        const val SAMPLES_PER_BLOCK = 256
        const val BLOCKS = 4
        const val TELEMETRY_POINTS = 3
        const val DT_NS = 20_000_000L
        const val T0_NS = 5_000_000_000L
        const val START_WALL_MS = 1_754_517_600_000L
    }
}
