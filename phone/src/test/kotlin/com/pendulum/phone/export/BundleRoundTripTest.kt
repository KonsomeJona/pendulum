package com.pendulum.phone.export

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader
import com.pendulum.format.ChunkWriter
import com.pendulum.phone.ingest.SessionReassembler
import com.pendulum.phone.work.AnalysisParams
import com.pendulum.phone.work.NightAnalyzer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * **The test that justifies the existence of the bundle.**
 *
 * The claim to prove: a database rebuilt from a bundle gives a bit-for-bit identical result. Without
 * that property, the export is a memory, not a backup — and the promise "we will be able to rescore
 * when the algorithm changes" no longer holds, since we would not even be able to reproduce today's
 * figure.
 *
 * The path exercised is the complete one: generation of a synthetic night -> writing of real chunk
 * files -> analysis -> bundling -> reading the bundle back -> rewriting the files -> second
 * analysis -> field-by-field comparison.
 *
 * This test runs on the JVM because neither [NightBundle], nor `SessionReassembler`, nor
 * [NightAnalyzer] depend on Android. That is the reason why they do not.
 */
class BundleRoundTripTest {

    @Test
    fun `a night rebuilt from a bundle gives exactly the same result`(
        @TempDir tmp: File,
    ) {
        val origin = File(tmp, "origin").apply { mkdirs() }
        val restored = File(tmp, "restored").apply { mkdirs() }

        val chunks = SyntheticNight.write(origin)
        val before = analyse(origin)

        // --- Export then reimport ---------------------------------------------------------
        val bundle = NightBundle.toByteArray(
            NightBundle.Content(
                manifest = mapOf(
                    "sessionHex" to SESSION_HEX,
                    "startWallMs" to "1700000000000",
                    "plannedStopWallMs" to "1700028800000",
                    "zoneId" to "Europe/Paris",
                    "tzOffsetStartMin" to "60",
                    "tzOffsetEndMin" to "60",
                    "nominalRateHz" to "50",
                    "modeFlags" to "0",
                    "state" to "CLOSED",
                    "totalChunks" to "${chunks.size}",
                ),
                context = mapOf("leg" to "RIGHT", "strapId" to "strap-a", "aloneInBed" to "true"),
                baseline = mapOf("paramsHash" to AnalysisParams.DEFAULT.paramsHash),
                hypnogramCsv = "",
                chunks = chunks.mapValues { it.value.readBytes() },
            )
        )

        val readBack = NightBundle.read(bundle.inputStream())
        assertThat(readBack.sessionHex).isEqualTo(SESSION_HEX)
        for ((idx, bytes) in readBack.chunks) {
            // The bytes must be identical: the bundle carries the raw data, it does not transcode
            // it. A re-encoding, even one with no apparent loss, would make the quantised values
            // diverge and therefore the result too.
            assertThat(bytes).isEqualTo(chunks.getValue(idx).readBytes())
            File(restored, "%05d.pendulum".format(idx)).writeBytes(bytes)
        }

        val after = analyse(restored)

        // --- The comparison that counts ------------------------------------------------------
        assertThat(canonical(after)).isEqualTo(canonical(before))
    }

    @Test
    fun `two exports of the same content produce the same file`(@TempDir tmp: File) {
        // Determinism: without a frozen timestamp and without sorting the keys, two exports of the
        // same material would give two different files, and it would become impossible to check by
        // simple comparison that an export has not altered its content.
        val dir = File(tmp, "night").apply { mkdirs() }
        val chunks = SyntheticNight.write(dir).mapValues { it.value.readBytes() }
        val content = NightBundle.Content(
            manifest = mapOf("sessionHex" to SESSION_HEX, "zoneId" to "Europe/Paris"),
            context = mapOf("leg" to "RIGHT"),
            baseline = emptyMap(),
            hypnogramCsv = "1:2:4;2:3:5",
            chunks = chunks,
        )
        assertThat(NightBundle.toByteArray(content)).isEqualTo(NightBundle.toByteArray(content))
    }

    @Test
    fun `the sidecar survives the round trip, line breaks included`() {
        val map = mapOf(
            "notes" to "difficult waking\nsecond paragraph",
            "leg" to "LEFT",
            "path" to """C:\data""",
        )
        assertThat(NightBundle.decodeMap(NightBundle.encodeMap(map))).isEqualTo(map)
    }

    // ------------------------------------------------------------------

    private fun analyse(dir: File): NightAnalyzer.Result {
        val night = SessionReassembler.reassemble(
            sessionHex = SESSION_HEX,
            files = dir.listFiles()!!.filter { it.name.endsWith(".pendulum") }.sortedBy { it.name },
            sessionStateClosed = true,
            declaredChunks = null,
        )
        assertThat(night.blocks).isNotEmpty()
        assertThat(night.closedCleanly).isTrue()
        return NightAnalyzer.analyze(
            blocks = night.blocks,
            nominalRateHz = night.nominalRateHz,
            sessionClosedCleanly = night.closedCleanly,
            hcWindows = null,
            diary = null,
            baselineGainG = null,
            params = AnalysisParams.DEFAULT,
        )
    }

    /**
     * Exhaustive serialisation of the result. We do not compare with `equals`: `PlmiResult.equals`
     * only looks at a few fields, and a test that passes thanks to a partial `equals` proves
     * nothing of what we want to prove here.
     */
    private fun canonical(r: NightAnalyzer.Result): String = buildString {
        appendLine("fs=${r.fsHz}")
        appendLine("analysableMin=${r.analysableMin}")
        appendLine("analysableTstMin=${r.analysableTstMin}")
        appendLine("sampleCount=${r.sampleCount}")
        appendLine("gapCount=${r.gapCount} gapTotalMs=${r.gapTotalMs}")
        appendLine("truncated=${r.truncated} rejected=${r.integrityRejectedFraction}")
        appendLine("gain=${r.calibration.gainCalG} source=${r.calibration.gainSource}")
        appendLine("postures=${r.postures.size}")
        appendLine("clms=${r.clms.size}")
        for (c in r.clms) {
            appendLine(
                "  clm ${c.onsetIdx} ${c.offsetIdx} ${c.onsetMsRel} ${c.durationMs} " +
                    "${c.peakAmpG} ${c.medianAmpG} ${c.noiseFloorG} ${c.thresholdOnG} " +
                    "${c.thresholdOffG} ${c.tiltChangeDeg} ${c.flags} ${c.reject}"
            )
        }
        for ((source, mask) in r.masks) {
            appendLine(
                "mask $source tst=${mask.tstMin} spt=${mask.sptMin} waso=${mask.wasoMin} " +
                    "analysable=${mask.analysableTstMin} conv=${mask.fixedPointConverged} " +
                    "windows=${mask.windows.size}"
            )
        }
        for (p in r.results) {
            appendLine(
                "result ${p.rule}/${p.maskSource} plms=${p.plmsCount} plmw=${p.plmwCount} " +
                    "iso=${p.isolatedCount} short=${p.shortImiCount} tst=${p.tstMin} " +
                    "atst=${p.analysableTstMin} plmi=${p.plmi} plmiSpt=${p.plmiSpt} " +
                    "pi=${p.pi.periodicityIndex}/${p.pi.valid} " +
                    "rhythm=${p.rhythm.fundamentalSec}/${p.rhythm.muLog}/${p.rhythm.sigmaLog}/" +
                    "${p.rhythm.missRate}/${p.rhythm.converged}/${p.rhythm.valid} " +
                    "h1=${p.plmiFirstHalf} h2=${p.plmiSecondHalf} resp=${p.plmiRespWorstCase} " +
                    "gate=${p.gate} indep=${p.independence} hash=${p.paramsHash} " +
                    "hist=${p.imiHistogram.joinToString(",")}"
            )
        }
    }

    private companion object {
        const val SESSION_HEX = "0123456789abcdef0123456789abcdef"
    }
}

/**
 * A minimal synthetic night: constant gravity, deterministic noise, and a burst of movements every
 * 22 seconds.
 *
 * It is not meant to be realistic — `:algo` has its own generator for that, with ground truth. Here
 * what is needed is a **reproducible** signal that crosses the whole chain and produces events:
 * what is being tested is the invariance of the result across the round trip, not the correctness
 * of the detection.
 */
private object SyntheticNight {

    const val RATE_HZ = 50
    const val BLOCKS_PER_CHUNK = 30
    const val CHUNKS = 4
    const val SAMPLES_PER_BLOCK = 512
    const val DT_NS = 20_000_000L
    const val T0_NS = 1_000_000_000L
    const val START_WALL_MS = 1_700_000_000_000L

    fun write(dir: File): Map<Int, File> {
        val uuid = ByteArray(16) { it.toByte() }
        val out = LinkedHashMap<Int, File>()
        var sampleIndex = 0L

        for (chunkIdx in 0 until CHUNKS) {
            val firstEventNs = T0_NS + sampleIndex * DT_NS
            val header = ChunkHeader(
                sessionUuid = uuid,
                chunkIndex = chunkIdx,
                nominalRateHz = RATE_HZ,
                startWallMs = START_WALL_MS + sampleIndex * 20,
                startElapsedRealtimeNs = firstEventNs,
                firstEventTimestampNs = firstEventNs,
                sensorResolution = 0.0012f,
                sensorMaxRange = 78.4532f,
                fifoMaxEventCount = 3000,
                modeFlags = ChunkFormat.MODE_WAKEUP_SENSOR or ChunkFormat.MODE_BATCHED,
                tzOffsetMin = 60,
            )
            val file = File(dir, "%05d.pendulum".format(chunkIdx))
            file.outputStream().buffered().use { os ->
                val w = ChunkWriter(os, header)
                repeat(BLOCKS_PER_CHUNK) {
                    val x = FloatArray(SAMPLES_PER_BLOCK)
                    val y = FloatArray(SAMPLES_PER_BLOCK)
                    val z = FloatArray(SAMPLES_PER_BLOCK)
                    val tFirst = T0_NS + sampleIndex * DT_NS
                    for (i in 0 until SAMPLES_PER_BLOCK) {
                        val n = sampleIndex + i
                        val tSec = n / RATE_HZ.toDouble()
                        val burst = burstAt(tSec)
                        x[i] = (burst + noise(n)).toFloat()
                        y[i] = noise(n + 7_919).toFloat()
                        z[i] = (ChunkFormat.G_IN_MS2 + noise(n + 104_729)).toFloat()
                    }
                    w.writeBlock(
                        x, y, z, SAMPLES_PER_BLOCK,
                        tFirst, tFirst + (SAMPLES_PER_BLOCK - 1) * DT_NS, 0,
                    )
                    sampleIndex += SAMPLES_PER_BLOCK
                }
                w.finish()
            }
            out[chunkIdx] = file
        }
        return out
    }

    /** A 1 s burst at 3 Hz every 22 s, windowed so as not to create a discontinuity. */
    private fun burstAt(tSec: Double): Double {
        val phase = tSec % 22.0
        if (phase >= 1.0) return 0.0
        val window = 0.5 * (1.0 - kotlin.math.cos(2 * PI * phase))
        return 3.0 * window * sin(2 * PI * 3.0 * phase)
    }

    /** Deterministic noise: same input, same output, without which the test would prove nothing. */
    private fun noise(n: Long): Double {
        var h = n * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        h = h xor (h ushr 33)
        h *= -49_064_778_989_728_563L
        h = h xor (h ushr 29)
        return ((h ushr 11).toDouble() / (1L shl 53).toDouble() - 0.5) * 0.02
    }
}
