package com.pendulum.format

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.abs
import kotlin.random.Random

class ChunkCodecTest {

    /** Nominal period at 50 Hz. Every test block must respect this rate (F-03). */
    private val stepNs = 20_000_000L

    private fun header(
        chunkIndex: Int = 0,
        modeFlags: Int = 0,
        tzOffsetMin: Int = 120,
    ) = ChunkHeader(
        sessionUuid = ByteArray(16) { it.toByte() },
        chunkIndex = chunkIndex,
        nominalRateHz = 50,
        startWallMs = 1_753_600_000_000L,
        startElapsedRealtimeNs = 123_456_789_000L,
        firstEventTimestampNs = 987_654_321_000L,
        sensorResolution = 0.0023956f,
        sensorMaxRange = 78.4532f,
        fifoMaxEventCount = 3000,
        modeFlags = modeFlags,
        tzOffsetMin = tzOffsetMin,
    )

    /**
     * Identifying value of a block: exactly [k] LSB, hence invariant under quantisation. Writing
     * `k.toFloat()` would not work — 2 m/s^2 reads back as 2.0016.
     */
    private fun marker(k: Int): Float = ChunkFormat.toMs2(k.toShort())

    /** Writes a flat block of [n] samples, at an exact 50 Hz rate, marked with [mark]. */
    private fun ChunkWriter.writeFlat(n: Int, t0: Long, mark: Int = 0, flags: Int = 0) {
        writeBlock(
            FloatArray(n) { marker(mark) }, FloatArray(n), FloatArray(n),
            n, t0, t0 + (n - 1) * stepNs, flags,
        )
    }

    private fun blockSize(n: Int) = ChunkFormat.BLOCK_HEADER_SIZE + n * ChunkFormat.BYTES_PER_SAMPLE

    private fun putShortLe(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putLongLe(buf: ByteArray, offset: Int, v: Long) {
        for (i in 0 until 8) buf[offset + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    /** Recomputes a block's CRC after modifying it: simulates a *coherent* corruption. */
    private fun refreshBlockCrc(buf: ByteArray, blockOffset: Int) {
        val count = (buf[blockOffset + 4].toInt() and 0xFF) or ((buf[blockOffset + 5].toInt() and 0xFF) shl 8)
        val payloadLen = count * ChunkFormat.BYTES_PER_SAMPLE
        val crc = ChunkFormat.crc16(
            buf, blockOffset + ChunkFormat.BLOCK_HEADER_SIZE, payloadLen,
            seed = ChunkFormat.crc16(buf, blockOffset, ChunkFormat.BLOCK_CRC_OFFSET),
        )
        putShortLe(buf, blockOffset + ChunkFormat.BLOCK_CRC_OFFSET, crc)
    }

    // --- File header ---

    @Test
    fun `the header is exactly the size it declares`() {
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header())
        assertThat(out.size()).isEqualTo(ChunkFormat.HEADER_SIZE)
    }

    @Test
    fun `the header is written field by field to the documented layout`() {
        val h = header(chunkIndex = 7, modeFlags = 3, tzOffsetMin = -60)
        val out = ByteArrayOutputStream()
        ChunkWriter(out, h)
        val raw = out.toByteArray()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(raw.copyOf(8)).isEqualTo(ChunkFormat.FILE_MAGIC)
        assertThat(bb.getShort(8).toInt()).isEqualTo(ChunkFormat.FORMAT_VERSION)
        assertThat(bb.getShort(10).toInt()).isEqualTo(ChunkFormat.HEADER_SIZE)
        assertThat(bb.getShort(12).toInt()).isEqualTo(50)
        assertThat(bb.getShort(14).toInt()).isEqualTo(3000)
        assertThat(raw.copyOfRange(16, 32)).isEqualTo(h.sessionUuid)
        assertThat(bb.getLong(32)).isEqualTo(h.startWallMs)
        assertThat(bb.getLong(40)).isEqualTo(h.startElapsedRealtimeNs)
        assertThat(bb.getLong(48)).isEqualTo(h.firstEventTimestampNs)
        assertThat(bb.getFloat(56)).isEqualTo(h.sensorResolution)
        assertThat(bb.getFloat(60)).isEqualTo(h.sensorMaxRange)
        assertThat(bb.getInt(64)).isEqualTo(7)
        assertThat(bb.getShort(68).toInt()).isEqualTo(3)
        assertThat(bb.getShort(70).toInt()).isEqualTo(-60)
        assertThat(raw.copyOfRange(72, 78)).containsOnly(0)
        // The CRC is the last field and covers everything that precedes it (F-08).
        assertThat(bb.getShort(78).toInt() and 0xFFFF)
            .isEqualTo(ChunkFormat.crc16(raw, 0, ChunkFormat.HEADER_SIZE - 2))
    }

    @Test
    fun `a corrupt header is rejected by its CRC`() {
        // One flipped byte in startWallMs would date the whole night wrong, silently (F-08).
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header())
        val bytes = out.toByteArray()
        bytes[35] = (bytes[35].toInt() xor 0x01).toByte()
        assertThatThrownBy { ChunkReader.read(ByteArrayInputStream(bytes)) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("header CRC")
    }

    @Test
    fun `a larger header produced by a later version stays readable`() {
        // F-32: headerSize allows fields to be appended at the tail without breaking the archives.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(32, 0L, mark = 1)
        w.finish()
        val raw = out.toByteArray()

        val grownSize = 96
        val grown = ByteArray(grownSize)
        System.arraycopy(raw, 0, grown, 0, ChunkFormat.HEADER_SIZE - 2)
        putShortLe(grown, 10, grownSize)
        grown[80] = 0x42 // field unknown to this version, ignored when read back
        putShortLe(grown, grownSize - 2, ChunkFormat.crc16(grown, 0, grownSize - 2))
        val file = grown + raw.copyOfRange(ChunkFormat.HEADER_SIZE, raw.size)

        val read = ChunkReader.read(ByteArrayInputStream(file))
        assertThat(read.header.headerSize).isEqualTo(grownSize)
        assertThat(read.header.nominalRateHz).isEqualTo(50)
        assertThat(read.blocks).hasSize(1)
        assertThat(read.scan.complete).isTrue()
    }

    @Test
    fun `a v1 format chunk still decodes`() {
        // **Backward compatibility, verified on bytes and not on code.**
        //
        // These bytes are not produced by `ChunkWriter`: they were generated once, outside Kotlin,
        // from the specification written in the KDoc of `ChunkFormat` and from nothing else. A
        // witness regenerated by the code it watches watches nothing — it would follow the
        // regression along. This one is frozen, and the repository never deletes raw data: it is
        // the only thing that will allow a night from 2026 to be rescored the day the algorithm
        // changes.
        val bytes = hex(CHUNK_V1)
        assertThat(bytes).hasSize(248)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))

        assertThat(read.header.formatVersion).isEqualTo(1)
        assertThat(read.header.headerSize).isEqualTo(ChunkFormat.HEADER_SIZE)
        assertThat(read.header.chunkIndex).isEqualTo(3)
        assertThat(read.header.nominalRateHz).isEqualTo(50)
        assertThat(read.header.fifoMaxEventCount).isEqualTo(3000)
        assertThat(read.header.tzOffsetMin).isEqualTo(120)
        assertThat(read.header.modeFlags)
            .isEqualTo(ChunkFormat.MODE_WAKEUP_SENSOR or ChunkFormat.MODE_BATCHED)
        assertThat(read.header.sessionUuid).isEqualTo(ByteArray(16) { it.toByte() })
        assertThat(read.header.startWallMs).isEqualTo(1_753_600_000_000L)
        assertThat(read.header.firstEventTimestampNs).isEqualTo(987_654_321_000L)

        assertThat(read.blocks).hasSize(2)
        assertThat(read.blocks[0].sampleCount).isEqualTo(8)
        assertThat(read.blocks[0].flags).isEqualTo(ChunkFormat.FLAG_FIFO_BOUNDARY)
        assertThat(read.blocks[1].sampleCount).isEqualTo(4)
        assertThat(read.blocks[1].flags).isEqualTo(ChunkFormat.FLAG_GAP_BEFORE)
        // The samples read back identical: the quantisation has not moved.
        for (i in 0 until 8) {
            assertThat(read.blocks[0].x[i]).isEqualTo(ChunkFormat.toMs2((i * 100).toShort()))
            assertThat(read.blocks[0].z[i]).isEqualTo(ChunkFormat.toMs2(2048))
        }

        assertThat(read.scan.complete).isTrue()
        assertThat(read.scan.declaredBlockCount).isEqualTo(2)
        assertThat(read.scan.declaredSampleCount).isEqualTo(12L)
        assertThat(read.corruptBlocks).isZero()
        assertThat(read.scan.desynchronised).isFalse()
        // The two bytes where `telemetryCount` now lives were zero in v1: the chunk therefore
        // declares zero points, which is the truth and not a default value.
        assertThat(read.telemetry).isEmpty()
        assertThat(read.scan.declaredTelemetryPointCount).isZero()
        assertThat(read.scan.lostTelemetryPoints).isZero()
    }

    @Test
    fun `the header refuses a FIFO or a rate outside u16`() {
        // F-26: the silent .toShort() recorded 70000 as 4464.
        assertThatThrownBy {
            header().copy(fifoMaxEventCount = 70_000)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("fifoMaxEventCount")
        assertThatThrownBy {
            header().copy(nominalRateHz = 100_000)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("nominalRateHz")
        assertThatThrownBy {
            header().copy(tzOffsetMin = 2000)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("tzOffsetMin")
    }

    @Test
    fun `round trip, the header is preserved identically, time zone included`() {
        val h = header(
            chunkIndex = 7,
            modeFlags = ChunkFormat.MODE_WAKEUP_SENSOR or ChunkFormat.MODE_BATCHED,
            tzOffsetMin = -330,
        )
        val out = ByteArrayOutputStream()
        ChunkWriter(out, h)
        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.header).isEqualTo(h)
        assertThat(read.header.tzOffsetMin).isEqualTo(-330)
        assertThat(read.blocks).isEmpty()
        assertThat(read.corruptBlocks).isZero()
        assertThat(read.truncatedTail).isFalse()
    }

    // --- Blocks ---

    @Test
    fun `a block is exactly the block header plus 6 bytes per sample`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        val n = 100
        w.writeFlat(n, 0L)
        assertThat(out.size()).isEqualTo(ChunkFormat.HEADER_SIZE + blockSize(n))
    }

    @Test
    fun `the block header is written to the documented layout and its CRC covers the header`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        val n = 8
        w.writeFlat(n, 1_000L, flags = ChunkFormat.FLAG_GAP_BEFORE)
        val raw = out.toByteArray()
        val off = ChunkFormat.HEADER_SIZE
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(raw.copyOfRange(off, off + 4)).isEqualTo(ChunkFormat.BLOCK_MAGIC)
        assertThat(bb.getShort(off + 4).toInt()).isEqualTo(n)
        assertThat(bb.getLong(off + 6)).isEqualTo(1_000L)
        assertThat(bb.getLong(off + 14)).isEqualTo(1_000L + (n - 1) * stepNs)
        assertThat(bb.getShort(off + 22).toInt()).isEqualTo(ChunkFormat.FLAG_GAP_BEFORE)
        // F-02: the CRC covers blockHeader[0, 24) then the payload, not the payload alone.
        val expected = ChunkFormat.crc16(
            raw, off + ChunkFormat.BLOCK_HEADER_SIZE, n * ChunkFormat.BYTES_PER_SAMPLE,
            seed = ChunkFormat.crc16(raw, off, ChunkFormat.BLOCK_CRC_OFFSET),
        )
        assertThat(bb.getShort(off + 24).toInt() and 0xFFFF).isEqualTo(expected)
        assertThat(raw.copyOfRange(off + 26, off + 32)).containsOnly(0)
    }

    @Test
    fun `round trip, samples are preserved to the quantisation resolution`() {
        val rnd = Random(42)
        val n = 512
        // Realistic range for an ankle: gravity (~9.81) plus jolts of a few g.
        val x = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 40f }
        val y = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 40f }
        val z = FloatArray(n) { 9.81f + (rnd.nextFloat() - 0.5f) * 10f }

        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeBlock(x, y, z, n, 1_000L, 1_000L + (n - 1) * stepNs, ChunkFormat.FLAG_FIFO_BOUNDARY)

        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.blocks).hasSize(1)
        val b = read.blocks[0]
        assertThat(b.sampleCount).isEqualTo(n)
        assertThat(b.flags).isEqualTo(ChunkFormat.FLAG_FIFO_BOUNDARY)
        assertThat(b.suspectTimebase).isFalse()

        // 1 LSB = 9.80665/2048 = 0.004789 m/s^2; the rounding error is bounded by half an LSB.
        val maxError = ChunkFormat.G_IN_MS2 / ChunkFormat.LSB_PER_G / 2 + 1e-6
        for (i in 0 until n) {
            assertThat(abs(b.x[i] - x[i])).isLessThan(maxError.toFloat())
            assertThat(abs(b.y[i] - y[i])).isLessThan(maxError.toFloat())
            assertThat(abs(b.z[i] - z[i])).isLessThan(maxError.toFloat())
        }
    }

    @Test
    fun `quantisation saturates at the top of the scale, which is not exactly 16 g`() {
        // A violent shock must clip cleanly, not wrap round to negative through overflow.
        assertThat(ChunkFormat.toRaw(1000f)).isEqualTo(Short.MAX_VALUE)
        assertThat(ChunkFormat.toRaw(-1000f)).isEqualTo(Short.MIN_VALUE)
        assertThat(ChunkFormat.toRaw((16.0 * ChunkFormat.G_IN_MS2).toFloat())).isEqualTo(Short.MAX_VALUE)
        assertThat(ChunkFormat.toRaw((15.9 * ChunkFormat.G_IN_MS2).toFloat())).isLessThan(Short.MAX_VALUE)
        // F-33: the scale is only symmetric to within one LSB, 32767/2048 = 15.9995 g and not 16 g.
        assertThat(ChunkFormat.toMs2(Short.MAX_VALUE) / ChunkFormat.G_IN_MS2)
            .isCloseTo(15.99951, within(1e-4))
        assertThat(ChunkFormat.toMs2(Short.MIN_VALUE) / ChunkFormat.G_IN_MS2)
            .isCloseTo(-16.0, within(1e-6))
    }

    @Test
    fun `saturations and non-finite values are counted and flagged`() {
        // F-11: without a counter or a flag, clipping and NaN are undetectable when read back.
        val n = 10
        val x = FloatArray(n)
        x[0] = 500f            // well beyond the ceiling of the format
        x[1] = Float.NaN       // faulty sensor: becomes 0, indistinguishable from free fall
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeBlock(x, FloatArray(n), FloatArray(n), n, 0L, (n - 1) * stepNs, 0)

        assertThat(w.saturatedSamples).isEqualTo(1)
        assertThat(w.nonFiniteSamples).isEqualTo(1)

        val b = ChunkReader.read(ByteArrayInputStream(out.toByteArray())).blocks[0]
        assertThat(b.flags and ChunkFormat.FLAG_SATURATED).isNotZero()
        assertThat(b.flags and ChunkFormat.FLAG_NON_FINITE).isNotZero()
    }

    @Test
    fun `several blocks are read back in order`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(10) { k -> w.writeFlat(50, k * 1_000_000_000L, mark = k) }
        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.blocks).hasSize(10)
        assertThat(read.sampleCount).isEqualTo(500)
        read.blocks.forEachIndexed { k, b ->
            assertThat(b.x[0]).isEqualTo(marker(k))
            assertThat(b.tFirstNs).isEqualTo(k * 1_000_000_000L)
        }
    }

    // --- Temporal validation at write time (F-03, F-14) ---

    @Test
    fun `writeBlock refuses a reversed timebase`() {
        val w = ChunkWriter(ByteArrayOutputStream(), header())
        assertThatThrownBy {
            w.writeBlock(FloatArray(10), FloatArray(10), FloatArray(10), 10, 1_000L, 500L, 0)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("reversed")
    }

    @Test
    fun `writeBlock refuses an absurd implicit rate`() {
        // The old test suite wrote n=100 over 1 ns and locked that behaviour in (F-14).
        val w = ChunkWriter(ByteArrayOutputStream(), header())
        assertThatThrownBy {
            w.writeBlock(FloatArray(100), FloatArray(100), FloatArray(100), 100, 0L, 1L, 0)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("implicit rate")
    }

    @Test
    fun `writeBlock refuses a block straddling two FIFO flushes`() {
        // F-03: 256 samples at 50 Hz cover 5.1 s; a 3 s hole in the middle brings the mean
        // interval to 31.8 ms, that is +59 % — interpolation would date the whole block wrong.
        val n = 256
        val w = ChunkWriter(ByteArrayOutputStream(), header())
        assertThatThrownBy {
            w.writeBlock(
                FloatArray(n), FloatArray(n), FloatArray(n), n,
                0L, (n - 1) * stepNs + 3_000_000_000L, 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("FIFO flushes")
    }

    @Test
    fun `writeBlock refuses an out-of-bounds count`() {
        val w = ChunkWriter(ByteArrayOutputStream(), header())
        assertThatThrownBy { w.writeBlock(FloatArray(10), FloatArray(10), FloatArray(10), 0, 0, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            w.writeBlock(FloatArray(10), FloatArray(10), FloatArray(10), 11, 0, 0, 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the reader flags a suspect timebase without discarding the block`() {
        // Block written valid, then tLastNs moved by 3 s *with* the CRC recomputed: the corruption
        // is coherent, only physical coherence gives it away.
        val n = 256
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(n, 0L, mark = 1)
        val bytes = out.toByteArray()
        val off = ChunkFormat.HEADER_SIZE
        putLongLe(bytes, off + 14, (n - 1) * stepNs + 3_000_000_000L)
        refreshBlockCrc(bytes, off)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.blocks).hasSize(1)
        assertThat(read.blocks[0].suspectTimebase).isTrue()
        assertThat(read.scan.suspectTimebaseBlocks).isEqualTo(1)
        assertThat(read.corruptBlocks).isZero()
    }

    @Test
    fun `the reader rejects a block whose chronology is impossible`() {
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(n, 10_000_000_000L, mark = 1)
        val bytes = out.toByteArray()
        val off = ChunkFormat.HEADER_SIZE
        putLongLe(bytes, off + 14, 5_000_000_000L) // tLast < tFirst
        refreshBlockCrc(bytes, off)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.blocks).isEmpty()
        assertThat(read.corruptBlocks).isEqualTo(1)
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.INVALID_TIMEBASE)
    }

    // --- Corruption, resynchronisation, localisation (F-02, F-12, F-35) ---

    @Test
    fun `a block with a corrupt CRC is skipped and the following blocks are kept`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        val n = 64
        repeat(3) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        val bytes = out.toByteArray()

        // One byte of the middle block's payload (index 1) is corrupted.
        val victim = ChunkFormat.HEADER_SIZE + blockSize(n) + ChunkFormat.BLOCK_HEADER_SIZE + 3
        bytes[victim] = (bytes[victim].toInt() xor 0xFF).toByte()

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.corruptBlocks).isEqualTo(1)
        assertThat(read.blocks).hasSize(2)
        // Blocks 0 and 2 survive: the loss is bounded to one block, not to the night.
        assertThat(read.blocks.map { it.x[0] }).containsExactly(marker(0), marker(2))
    }

    @Test
    fun `a corrupt count is caught by the CRC and does not desynchronise what follows`() {
        // F-02: this is the case where the old `continue` lost synchronisation, since the payload
        // length being read derived from `count`, precisely the corrupted field.
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(4) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        w.finish()
        val bytes = out.toByteArray()

        val victimBlock = ChunkFormat.HEADER_SIZE + blockSize(n)
        putShortLe(bytes, victimBlock + 4, 32) // count 64 -> 32, without recomputing the CRC

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.corruptBlocks).isEqualTo(1)
        assertThat(read.blocks.map { it.x[0] }).containsExactly(marker(0), marker(2), marker(3))
        assertThat(read.scan.resyncSkippedBytes).isEqualTo(blockSize(n).toLong())
        assertThat(read.scan.complete).isTrue()
        assertThat(read.scan.lostBlocks).isEqualTo(1)
    }

    @Test
    fun `a corrupt tLastNs is caught by the CRC`() {
        // Before F-02, corrupting tLastNs shifted the whole timebase of the night without any
        // check noticing it: the CRC only covered the payload.
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(3) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        val bytes = out.toByteArray()

        val victimBlock = ChunkFormat.HEADER_SIZE + blockSize(n)
        putLongLe(bytes, victimBlock + 14, 999_999_999_999L) // no CRC recomputation

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.corruptBlocks).isEqualTo(1)
        assertThat(read.blocks.map { it.x[0] }).containsExactly(marker(0), marker(2))
    }

    @Test
    fun `a magic destroyed in the middle of the file triggers a resynchronisation`() {
        val n = 32
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(5) { k -> w.writeFlat(n, k * 1_000_000_000L, mark = k) }
        val bytes = out.toByteArray()

        val victimBlock = ChunkFormat.HEADER_SIZE + 2 * blockSize(n)
        bytes[victimBlock] = 'Z'.code.toByte()

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.blocks.map { it.x[0] }).containsExactly(marker(0), marker(1), marker(3), marker(4))
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_MAGIC)
        assertThat(read.scan.desynchronised).isTrue()
        assertThat(read.truncatedTail).isFalse()
    }

    @Test
    fun `a lost area is located both in the file and in time`() {
        // F-35: this is what makes it possible to say "30 s missing at 3:12" rather than
        // "1 block lost".
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(3) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        val bytes = out.toByteArray()

        val victimBlock = ChunkFormat.HEADER_SIZE + blockSize(n)
        bytes[victimBlock + 40] = (bytes[victimBlock + 40].toInt() xor 0xFF).toByte()

        val scan = ChunkReader.forEachBlock(ByteArrayInputStream(bytes)) {}
        val range = scan.damagedRanges.single()
        assertThat(range.reason).isEqualTo(DamageReason.BAD_CRC)
        assertThat(range.fileOffset).isEqualTo(victimBlock.toLong())
        assertThat(range.byteLength).isEqualTo(blockSize(n).toLong())
        assertThat(range.afterTimestampNs).isEqualTo((n - 1) * stepNs)
        assertThat(range.beforeTimestampNs).isEqualTo(4_000_000_000L)
        assertThat(range.missingDurationNs).isEqualTo(4_000_000_000L - (n - 1) * stepNs)
        assertThat(scan.missingDurationNs).isEqualTo(range.missingDurationNs)
    }

    @Test
    fun `a truncated tail and a desynchronisation are two distinct states`() {
        // F-12: one is benign (abrupt kill, one block at most), the other is not.
        val n = 128
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(5) { k -> w.writeFlat(n, k * 3_000_000_000L, mark = k) }
        val cut = out.toByteArray().copyOf(out.size() - 200)

        val read = ChunkReader.read(ByteArrayInputStream(cut))
        assertThat(read.truncatedTail).isTrue()
        assertThat(read.scan.desynchronised).isFalse()
        assertThat(read.scan.complete).isFalse()
        assertThat(read.blocks).hasSize(4)
        assertThat(read.sampleCount).isEqualTo(4 * n)
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.TRUNCATED_TAIL)
    }

    // --- End-of-file marker (F-37) ---

    @Test
    fun `a closed chunk carries a coherent end marker`() {
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(3) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        w.finish()
        assertThat(out.size()).isEqualTo(
            ChunkFormat.HEADER_SIZE + 3 * blockSize(n) + ChunkFormat.FOOTER_SIZE
        )

        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.scan.complete).isTrue()
        assertThat(read.scan.declaredBlockCount).isEqualTo(3)
        assertThat(read.scan.declaredSampleCount).isEqualTo(3L * n)
        assertThat(read.scan.lostBlocks).isEqualTo(0)
    }

    @Test
    fun `a chunk still being written is not declared complete`() {
        // F-13/F-37: this is what forbids the phone from acknowledging a partial file.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(64, 0L)
        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.scan.complete).isFalse()
        assertThat(read.scan.declaredBlockCount).isNull()
        assertThat(read.blocks).hasSize(1)
    }

    @Test
    fun `finish is idempotent and closes the chunk to further writes`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(16, 0L)
        w.finish()
        val size = out.size()
        w.finish()
        assertThat(out.size()).isEqualTo(size)
        assertThatThrownBy { w.writeFlat(16, 1_000_000_000L) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    // --- Streaming read (F-27) and stream robustness (F-31) ---

    @Test
    fun `the streaming API returns the same summary without materialising the blocks`() {
        val n = 128
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(40) { k -> w.writeFlat(n, k * 3_000_000_000L, mark = k) }
        w.finish()

        var seen = 0
        var lastX = -1f
        val scan = ChunkReader.forEachBlock(ByteArrayInputStream(out.toByteArray())) {
            seen++
            lastX = it.x[0]
        }
        assertThat(seen).isEqualTo(40)
        assertThat(lastX).isEqualTo(marker(39))
        assertThat(scan.blockCount).isEqualTo(40)
        assertThat(scan.decodedSampleCount).isEqualTo(40L * n)
        assertThat(scan.complete).isTrue()
    }

    @Test
    fun `a stream returning zero without end of file does not loop forever`() {
        // F-31: real case of a Data Layer Channel.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(32, 0L)
        val bytes = out.toByteArray()
        val stalling = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return 0 // neither data nor EOF
                val k = minOf(len, bytes.size - pos)
                System.arraycopy(bytes, pos, b, off, k)
                pos += k
                return k
            }
        }
        val read = assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            ThrowingSupplier { ChunkReader.read(stalling) },
        )
        assertThat(read.blocks).hasSize(1)
    }

    // --- Miscellaneous ---

    @Test
    fun `an empty file or a truncated header throws`() {
        assertThatThrownBy { ChunkReader.read(ByteArrayInputStream(ByteArray(0))) }
            .isInstanceOf(IOException::class.java)
        assertThatThrownBy { ChunkReader.read(ByteArrayInputStream(ByteArray(40))) }
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `an invalid file magic throws`() {
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header())
        val bytes = out.toByteArray()
        bytes[0] = 'X'.code.toByte()
        assertThatThrownBy { ChunkReader.read(ByteArrayInputStream(bytes)) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("magic")
    }

    @Test
    fun `interpolated timestamps are evenly spaced`() {
        val n = 51
        val tFirst = 1_000_000_000L
        val b = DecodedBlock(tFirst, tFirst + (n - 1) * stepNs, 0, FloatArray(n), FloatArray(n), FloatArray(n))
        assertThat(b.timestampNs(0)).isEqualTo(tFirst)
        assertThat(b.timestampNs(n - 1)).isEqualTo(tFirst + (n - 1) * stepNs)
        assertThat(b.timestampNs(25)).isEqualTo(tFirst + 25 * stepNs)
    }

    @Test
    fun `a single-sample block does not divide by zero`() {
        val b = DecodedBlock(500L, 500L, 0, FloatArray(1), FloatArray(1), FloatArray(1))
        assertThat(b.timestampNs(0)).isEqualTo(500L)
    }

    @Test
    fun `the CRC16 is the CCITT-FALSE one on the reference vector`() {
        // Canonical vector: "123456789" -> 0x29B1 in CRC-16/CCITT-FALSE.
        assertThat(ChunkFormat.crc16("123456789".toByteArray(Charsets.US_ASCII))).isEqualTo(0x29B1)
    }

    @Test
    fun `the CRC chained over two arrays equals the one of their concatenation`() {
        val a = "1234".toByteArray(Charsets.US_ASCII)
        val b = "56789".toByteArray(Charsets.US_ASCII)
        assertThat(ChunkFormat.crc16(b, seed = ChunkFormat.crc16(a))).isEqualTo(0x29B1)
    }

    @Test
    fun `the declared throughput holds, an 8 h night at 50 Hz fits under 9 MB`() {
        // Check of the storage budget the transfer strategy rests on.
        val samples = 50 * 3600 * 8
        val blocks = (samples + ChunkFormat.MAX_SAMPLES_PER_BLOCK - 1) / ChunkFormat.MAX_SAMPLES_PER_BLOCK
        val chunks = 8 * 3600 / 300 // 5 min rotation
        // Telemetry counts in the same budget: one point per minute, each in its own block. This
        // is the measurement of what "the throughput is negligible" means.
        val telemetryPoints = 8 * 60
        val telemetryBytes = telemetryPoints.toLong() *
            (ChunkFormat.TELEMETRY_HEADER_SIZE + ChunkFormat.TELEMETRY_POINT_SIZE)
        val bytes = (ChunkFormat.HEADER_SIZE + ChunkFormat.FOOTER_SIZE).toLong() * chunks +
            blocks.toLong() * ChunkFormat.BLOCK_HEADER_SIZE +
            samples.toLong() * ChunkFormat.BYTES_PER_SAMPLE +
            telemetryBytes
        assertThat(bytes).isLessThan(9L * 1024 * 1024)
        // ~30 KB of telemetry against ~8.3 MB of signal: less than 0.4 % of the night's volume.
        assertThat(telemetryBytes.toDouble() / bytes).isLessThan(0.005)
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {

        /**
         * A complete chunk in the **v1** format, frozen. Two signal blocks (8 then 4 samples at
         * 50 Hz), end marker, no telemetry — since v1 had none.
         *
         * Never regenerate it: the day it has to be changed to make a test pass, backward
         * compatibility has just been broken and that is the news.
         */
        const val CHUNK_V1 =
            "50454e4443484e4b010050003200b80b000102030405060708090a0b0c0d0e0f" +
                "0080b44a98010000081a99be1c00000068f3c8f4e500000080ff1c3b0ae89c42" +
                "0300000003007800000000000000bf59424c4b21080068f3c8f4e5000000682e" +
                "21fde5000000010043a300000000000000000000000864000000000" +
                "8c800000000082c0100000008900100000008f40100000008580200000008bc02" +
                "00000008424c4b210400685b52fee500000068e2e501e60000000200888a00000" +
                "0000000000000000008ceff000000089cff000000086aff00000008454e445045" +
                "4e4421020000000c0000000000000068e2e501e60000000000cf79"
    }
}
