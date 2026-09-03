package com.pendulum.format.wire

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class WireCodecTest {

    private val sessionHex = "000102030405060708090a0b0c0d0e0f"

    // --- Paths and identifiers ---

    @Test
    fun `the session UUID survives a hexadecimal round trip`() {
        val uuid = ByteArray(16) { it.toByte() }
        assertThat(uuid.toSessionHex()).isEqualTo(sessionHex)
        assertThat(sessionHexToBytes(sessionHex)).isEqualTo(uuid)
    }

    @Test
    fun `chunk paths are zero-padded so they stay sorted`() {
        // A lexicographic listing must return the chunks in order, otherwise 10 comes before 2
        // and the night is reconstructed wrong.
        val paths = listOf(2, 10, 100).map { WirePaths.chunk(sessionHex, it) }
        assertThat(paths).isSorted()
        assertThat(paths.first()).isEqualTo("/pendulum/chunk/$sessionHex/00002")
    }

    @Test
    fun `paths and night keys stay ASCII whatever the default locale`() {
        // `String.format` without a locale hands `%d` to `java.util.Formatter`, which localises
        // the digits as soon as the default locale's zero is not '0'. In ar-EG, 7 is written ٧:
        // the phone sealed `/pendulum/context/٢٠٢٦-٠٩-٠٣` while a watch left in English looked
        // for `/pendulum/context/2026-09-03`, never found it, and refused START without a single
        // error message — the exact silent failure the KDoc of `CONTEXT_PREFIX` warns about.
        // With the same locale on both sides the keys matched, but `P1Gate` then did
        // `LocalDate.parse(nightKey)` on the Arabic-Indic string and crashed the P1 screen.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertThat(WirePaths.chunk(sessionHex, 7)).isEqualTo("/pendulum/chunk/$sessionHex/00007")
            val evening = ZonedDateTime.of(2026, 9, 3, 20, 0, 0, 0, ZoneOffset.UTC)
            val key = WirePaths.nightKey(evening.toInstant().toEpochMilli(), ZoneOffset.UTC)
            assertThat(key).isEqualTo("2026-09-03")
            // The consumer that crashed: `P1Gate` parses the key back into a LocalDate.
            assertThat(LocalDate.parse(key)).isEqualTo(LocalDate.of(2026, 9, 3))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `chunk rotation is 5 min or 90 KiB`() {
        assertThat(WireProtocol.CHUNK_ROTATION_MS).isEqualTo(300_000L)
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES).isEqualTo(92_160L)
        // The byte ceiling must leave margin under the payload of a DataItem.
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES).isLessThan(WireProtocol.MAX_DATA_ITEM_BYTES.toLong())
    }

    @Test
    fun `the telemetry period divides the chunk rotation`() {
        // **The property, not the value.** A chunk is the protocol's unit of loss: if telemetry
        // had a clock of its own, a lost chunk would carry away a telemetry hole that no other
        // chunk would fill, and that nobody would even be able to count. By dividing the rotation,
        // every complete chunk carries a known number of points, and "three are missing" becomes
        // a verifiable sentence.
        assertThat(WireProtocol.CHUNK_ROTATION_MS % WireProtocol.TELEMETRY_PERIOD_MS).isZero()
        assertThat(WireProtocol.CHUNK_ROTATION_MS / WireProtocol.TELEMETRY_PERIOD_MS)
            .`as`("telemetry points per complete chunk")
            .isGreaterThanOrEqualTo(1L)
            .isEqualTo(5L)
    }

    // --- Round trip of the four structures ---

    @Test
    fun `an open session header survives a round trip`() {
        val h = SessionHeader(
            sessionHex = sessionHex,
            startWallMs = 1_753_600_000_000L,
            tzOffsetMin = -330,
            zoneId = "Asia/Kolkata",
            nominalRateHz = 50,
            modeFlags = 0b1011,
            plannedStopWallMs = 1_753_636_000_000L,
            state = SessionState.OPEN,
        )
        assertThat(SessionHeader.decode(h.encode())).isEqualTo(h)
    }

    @Test
    fun `a closed session header survives a round trip with its stop reason`() {
        val h = SessionHeader(
            sessionHex = sessionHex,
            startWallMs = 1_753_600_000_000L,
            tzOffsetMin = 120,
            zoneId = "Europe/Paris",
            nominalRateHz = 50,
            modeFlags = 0,
            plannedStopWallMs = 1_753_636_000_000L,
            state = SessionState.CLOSED,
            endWallMs = 1_753_630_000_000L,
            totalChunks = 96,
            stopReason = StopReason.LOW_BATTERY,
        )
        val decoded = SessionHeader.decode(h.encode())
        assertThat(decoded).isEqualTo(h)
        assertThat(decoded.stopReason).isEqualTo(StopReason.LOW_BATTERY)
        assertThat(decoded.tzOffsetMin).isEqualTo(120)
    }

    @Test
    fun `a chunk entry survives a round trip`() {
        val m = ChunkMeta(
            sessionHex = sessionHex,
            idx = 41,
            size = 91_010,
            crc32 = 0xDEADBEEFL, // u32: the high bit must not turn negative
            sampleCount = 15_000,
            tFirstNs = 987_654_321_000L,
            tLastNs = 1_287_654_321_000L,
            flagsOr = 0xFFFF,
        )
        val decoded = ChunkMeta.decode(m.encode())
        assertThat(decoded).isEqualTo(m)
        assertThat(decoded.crc32).isEqualTo(0xDEADBEEFL)
    }

    @Test
    fun `a live preview survives a round trip`() {
        val rnd = Random(7)
        val env = ByteArray(PreviewEnvelopeCodec.LENGTH) { rnd.nextInt(256).toByte() }
        val live = LivePreview(
            sessionHex = sessionHex,
            lastUpdateMs = 1_753_612_345_678L,
            elapsedMs = 12_345_678L,
            samplesWritten = 617_283L,
            bytesWritten = 3_703_698L,
            batteryPct = 63,
            gapCount = 4,
            gapTotalMs = 8_200L,
            modeFlags = 0b0110,
            lastClosedChunkIdx = 41,
            syncBacklogged = true,
            envU8 = env,
        )
        val decoded = LivePreview.decode(live.encode())
        assertThat(decoded).isEqualTo(live)
        assertThat(decoded.envU8).isEqualTo(env)
        // ~1 KB per burst: that is the price of the "movement chart" in near real time.
        assertThat(live.encode().size).isLessThan(1200)
    }

    @Test
    fun `an acknowledgement survives a round trip`() {
        val ack = Ack(
            sessionHex = sessionHex,
            ackedUpTo = 12,
            bitmapBase = 12,
            ackedBitmap = byteArrayOf(0b0000_1101, 0b0000_0010),
            needResend = intArrayOf(19, 23),
            phoneMs = 1_753_612_000_000L,
        )
        assertThat(Ack.decode(ack.encode())).isEqualTo(ack)
    }

    @Test
    fun `the acknowledgement names the chunks that can be deleted`() {
        val ack = Ack(
            sessionHex = sessionHex,
            ackedUpTo = 12,
            bitmapBase = 12,
            ackedBitmap = byteArrayOf(0b0000_1101),
            needResend = intArrayOf(),
            phoneMs = 0L,
        )
        assertThat(ack.isAcked(11)).isTrue()   // below ackedUpTo
        assertThat(ack.isAcked(12)).isTrue()   // bit 0
        assertThat(ack.isAcked(13)).isFalse()  // bit 1
        assertThat(ack.isAcked(14)).isTrue()   // bit 2
        assertThat(ack.isAcked(15)).isTrue()   // bit 3
        assertThat(ack.isAcked(20)).isFalse()  // outside the bitmap
    }

    @Test
    fun `an erase order survives a round trip`() {
        // The instant is what the watch compares each session's start against: a session begun
        // after it is not the phone's to disown. Off by one bit, and a night recorded after the
        // erasure would be thrown away — or a night recorded before it kept.
        val order = EraseOrder(erasedBeforeMs = 1_757_000_000_123L)
        assertThat(EraseOrder.decode(order.encode())).isEqualTo(order)
        assertThat(WirePaths.ERASE).isEqualTo("/pendulum/erase")
    }

    @Test
    fun `a bare erase order is refused rather than read as instant zero`() {
        // The context item carries a bare decimal string, and that is fine for an item only ever
        // tested for presence. The erase order is read back and acted on: a payload the watch
        // cannot understand must raise, because "instant 0" would disown nothing at all and the
        // erasure would silently not reach the watch.
        assertThatThrownBy { EraseOrder.decode("1757000000123".toByteArray()) }
            .isInstanceOf(WireFormatException::class.java)
            .hasMessageContaining("wire version")
        val bytes = EraseOrder(1_757_000_000_123L).encode()
        assertThatThrownBy { EraseOrder.decode(bytes.copyOf(bytes.size - 1)) }
            .isInstanceOf(WireFormatException::class.java)
            .hasMessageContaining("truncated")
    }

    // --- Decoding robustness ---

    @Test
    fun `a truncated payload is rejected explicitly`() {
        val m = ChunkMeta(sessionHex, 1, 100, 0L, 10, 0L, 1L, 0)
        val bytes = m.encode()
        assertThatThrownBy { ChunkMeta.decode(bytes.copyOf(bytes.size - 4)) }
            .isInstanceOf(WireFormatException::class.java)
            .hasMessageContaining("truncated")
    }

    @Test
    fun `an unknown wire version is rejected on the first byte`() {
        val bytes = ChunkMeta(sessionHex, 1, 100, 0L, 10, 0L, 1L, 0).encode()
        bytes[0] = 99
        assertThatThrownBy { ChunkMeta.decode(bytes) }
            .isInstanceOf(WireFormatException::class.java)
            .hasMessageContaining("wire version")
    }

    @Test
    fun `an unknown session state code is rejected`() {
        assertThatThrownBy { SessionState.fromCode(42) }
            .isInstanceOf(WireFormatException::class.java)
    }

    // --- Preview envelope ---

    @Test
    fun `the quantised envelope fits in 900 bytes`() {
        val rms = DoubleArray(PreviewEnvelopeCodec.LENGTH) { 0.05 }
        assertThat(PreviewEnvelopeCodec.encode(rms)).hasSize(900)
    }

    @Test
    fun `logarithmic quantisation keeps the relative error bounded over four decades`() {
        // The point of the logarithm: the same relative precision at 2e-3 as at 30 m/s^2, where a
        // linear quantisation would crush the whole of sleep onto two levels.
        var v = PreviewEnvelopeCodec.MIN_MS2 * 1.05
        while (v < PreviewEnvelopeCodec.MAX_MS2) {
            val back = PreviewEnvelopeCodec.dequantize(PreviewEnvelopeCodec.quantize(v))
            assertThat(abs(back - v) / v).isLessThan(0.025)
            v *= 1.37
        }
    }

    @Test
    fun `the envelope bounds are respected`() {
        assertThat(PreviewEnvelopeCodec.quantize(0.0)).isZero()
        assertThat(PreviewEnvelopeCodec.quantize(Double.NaN)).isZero()
        assertThat(PreviewEnvelopeCodec.quantize(1e-6)).isZero()
        assertThat(PreviewEnvelopeCodec.quantize(1_000.0)).isEqualTo(255)
        assertThat(PreviewEnvelopeCodec.dequantize(255))
            .isCloseTo(PreviewEnvelopeCodec.MAX_MS2, within(1e-6))
    }

    @Test
    fun `a partial window is right-aligned`() {
        // The last byte is always the most recent one: the phone does not need to know how long
        // ago the night started in order to draw the curve.
        val encoded = PreviewEnvelopeCodec.encode(doubleArrayOf(1.0, 2.0, 3.0))
        assertThat(encoded.copyOf(PreviewEnvelopeCodec.LENGTH - 3)).containsOnly(0)
        assertThat(encoded[PreviewEnvelopeCodec.LENGTH - 1].toInt() and 0xFF)
            .isEqualTo(PreviewEnvelopeCodec.quantize(3.0))
    }

    @Test
    fun `a full window survives a round trip`() {
        val rnd = Random(3)
        val rms = DoubleArray(PreviewEnvelopeCodec.LENGTH) { rnd.nextDouble(0.01, 20.0) }
        val back = PreviewEnvelopeCodec.decode(PreviewEnvelopeCodec.encode(rms))
        for (i in rms.indices) {
            assertThat(abs(back[i] - rms[i]) / rms[i]).isLessThan(0.025)
        }
    }
}
