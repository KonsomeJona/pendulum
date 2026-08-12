package com.pendulum.phone.ingest

import com.pendulum.format.wire.ChunkMeta
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The acknowledgement and truncation detection: the two places where an error loses data rather
 * than displaying a false figure.
 *
 * The acknowledgement is what allows the watch to **erase** its file. An over-optimistic
 * acknowledgement is the only way to lose data for good in this protocol.
 */
class AckAndReassemblyTest {

    private val hex = "0123456789abcdef0123456789abcdef"

    @Test
    fun `a night received in order has an empty bitmap`() {
        val ack = AckBuilder.build(hex, (0..9).toList(), emptyList(), 0L)
        assertThat(ack.ackedUpTo).isEqualTo(10)
        // The contiguous prefix is enough: the bitmap only serves out-of-order arrivals, and
        // keeping it empty in the nominal case keeps the item light.
        assertThat(ack.ackedBitmap).isEmpty()
        assertThat(ack.isAcked(0)).isTrue()
        assertThat(ack.isAcked(9)).isTrue()
        assertThat(ack.isAcked(10)).isFalse()
    }

    @Test
    fun `a gap stops the prefix and tips the rest into the bitmap`() {
        val ack = AckBuilder.build(hex, listOf(0, 1, 2, 5, 7), emptyList(), 0L)
        assertThat(ack.ackedUpTo).isEqualTo(3)
        assertThat(ack.isAcked(2)).isTrue()
        assertThat(ack.isAcked(3)).isFalse()
        assertThat(ack.isAcked(4)).isFalse()
        assertThat(ack.isAcked(5)).isTrue()
        assertThat(ack.isAcked(6)).isFalse()
        assertThat(ack.isAcked(7)).isTrue()
        assertThat(ack.isAcked(8)).isFalse()
    }

    @Test
    fun `an acknowledgement survives encoding and decoding`() {
        val ack = AckBuilder.build(hex, listOf(0, 1, 4, 9), listOf(2, 3), 1234L)
        val readBack = com.pendulum.format.wire.Ack.decode(ack.encode())
        assertThat(readBack.ackedUpTo).isEqualTo(ack.ackedUpTo)
        assertThat(readBack.needResend).containsExactly(2, 3)
        for (i in 0..12) assertThat(readBack.isAcked(i)).isEqualTo(ack.isAcked(i))
    }

    @Test
    fun `a duplicate changes nothing in the acknowledgement`() {
        val a = AckBuilder.build(hex, listOf(0, 1, 2), emptyList(), 0L)
        val b = AckBuilder.build(hex, listOf(0, 1, 1, 2, 2, 2), emptyList(), 0L)
        assertThat(b.ackedUpTo).isEqualTo(a.ackedUpTo)
        assertThat(b.ackedBitmap).isEqualTo(a.ackedBitmap)
    }

    // --- Truncation detection -----------------------------------------------------------------

    @Test
    fun `the missing indices take the announced total into account`() {
        // The silent false negative to avoid: the watch announced 96 chunks, we have 40 of them,
        // and looking only at what was received would say "no gap" about a night two thirds of
        // which are missing.
        val missing = SessionReassembler.missingIndices((0..39).toList(), declaredChunks = 96)
        assertThat(missing).hasSize(56)
        assertThat(missing.first()).isEqualTo(40)
        assertThat(missing.last()).isEqualTo(95)
    }

    @Test
    fun `a gap in the middle is detected`() {
        assertThat(SessionReassembler.missingIndices(listOf(0, 1, 3, 4), null))
            .containsExactly(2)
    }

    @Test
    fun `a complete night has nothing missing`() {
        assertThat(SessionReassembler.missingIndices((0..9).toList(), 10)).isEmpty()
    }

    @Test
    fun `a session without a single chunk does not invent missing ones`() {
        assertThat(SessionReassembler.missingIndices(emptyList(), null)).isEmpty()
    }

    // --- Verification of a received chunk ------------------------------------------------------

    private fun meta(size: Int, crc: Long, session: String = hex) =
        ChunkMeta(session, idx = 3, size = size, crc32 = crc, sampleCount = 512,
            tFirstNs = 0, tLastNs = 1, flagsOr = 0)

    @Test
    fun `compliant bytes are accepted`() {
        val bytes = ByteArray(200) { it.toByte() }
        val verdict = ChunkVerifier.verify(hex, meta(bytes.size, ChunkStore.crc32(bytes)), bytes)
        assertThat(verdict).isEqualTo(ChunkVerifier.Verdict.OK)
    }

    @Test
    fun `a wrong CRC is refused, and the size is checked before it`() {
        val bytes = ByteArray(200) { it.toByte() }
        assertThat(ChunkVerifier.verify(hex, meta(bytes.size, 0xDEADBEEF), bytes))
            .isEqualTo(ChunkVerifier.Verdict.CRC_MISMATCH)
        // Announced size differs: we refuse without paying for the CRC sweep.
        assertThat(ChunkVerifier.verify(hex, meta(999, ChunkStore.crc32(bytes)), bytes))
            .isEqualTo(ChunkVerifier.Verdict.SIZE_MISMATCH)
    }

    @Test
    fun `an item routed to another session is refused`() {
        val bytes = ByteArray(200)
        val other = "ffffffffffffffffffffffffffffffff"
        assertThat(ChunkVerifier.verify(hex, meta(bytes.size, ChunkStore.crc32(bytes), other), bytes))
            .isEqualTo(ChunkVerifier.Verdict.SESSION_MISMATCH)
    }

    @Test
    fun `an implausible size is refused`() {
        val tiny = ByteArray(10)
        assertThat(ChunkVerifier.verify(hex, meta(10, ChunkStore.crc32(tiny)), tiny))
            .isEqualTo(ChunkVerifier.Verdict.IMPLAUSIBLE_SIZE)

        val huge = ByteArray(200 * 1024)
        assertThat(ChunkVerifier.verify(hex, meta(huge.size, ChunkStore.crc32(huge)), huge))
            .isEqualTo(ChunkVerifier.Verdict.IMPLAUSIBLE_SIZE)
    }

    @Test
    fun `the chunk envelope makes the round trip without loss`() {
        val chunk = ByteArray(1024) { (it * 7).toByte() }
        val m = meta(chunk.size, ChunkStore.crc32(chunk))
        val decode = ChunkEnvelope.decode(ChunkEnvelope.encode(m, chunk))
        assertThat(decode.meta).isEqualTo(m)
        assertThat(decode.chunkBytes).isEqualTo(chunk)
    }
}
