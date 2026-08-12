package com.pendulum.format

import com.pendulum.format.wire.WireProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The telemetry block: its symmetry, its layout, and above all **what it does not break**.
 *
 * The central requirement is not that it works — it is that it costs nothing to what already
 * worked. Hence the shape of this file: every property of the signal that could have regressed has
 * its own test, and the inverted assertion ("telemetry survives a corrupt signal block, and vice
 * versa") counts as much as the round trip.
 */
class TelemetryBlockTest {

    private val stepNs = 20_000_000L

    private fun header(maxRange: Float = 78.4532f, resolution: Float = 0.0023956f) = ChunkHeader(
        sessionUuid = ByteArray(16) { it.toByte() },
        chunkIndex = 0,
        nominalRateHz = 50,
        startWallMs = 1_753_600_000_000L,
        startElapsedRealtimeNs = 123_456_789_000L,
        firstEventTimestampNs = 987_654_321_000L,
        sensorResolution = resolution,
        sensorMaxRange = maxRange,
        fifoMaxEventCount = 3000,
        modeFlags = 0,
        tzOffsetMin = 120,
    )

    /** A point where **no** field holds its default value: a byte forgotten at encoding time or
     *  two swapped fields then show up, which a zeroed point would not reveal. */
    private fun point() = TelemetryPoint(
        elapsedRealtimeNs = 0x0102030405060708L,
        sensorTsNs = 987_654_321_000L,
        batteryChargeUah = -123_456,
        // Beyond Int.MAX_VALUE: this is the u32 field exercising the trip through a signed Int.
        maxIntervalUs = 4_000_000_000L,
        fsyncTotalUs = 123_456L,
        fsyncMaxUs = 9_999L,
        temperatureDeciC = -157,
        measuredRateCentiHz = 5026,
        jitterStdUs = 65_535,
        clippedSamples = 12,
        fsyncCount = 7,
        batteryPct = 83,
        offBody = TelemetryPoint.OFF_BODY_REMOVED,
        charging = true,
    )

    private fun ChunkWriter.writeFlat(n: Int, t0: Long, mark: Int = 0) {
        writeBlock(
            FloatArray(n) { ChunkFormat.toMs2(mark.toShort()) }, FloatArray(n), FloatArray(n),
            n, t0, t0 + (n - 1) * stepNs, 0,
        )
    }

    private fun putShortLe(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun blockSize(n: Int) = ChunkFormat.BLOCK_HEADER_SIZE + n * ChunkFormat.BYTES_PER_SAMPLE

    private val telemetryBlockSize =
        ChunkFormat.TELEMETRY_HEADER_SIZE + ChunkFormat.TELEMETRY_POINT_SIZE

    // --- Symmetry and layout ---

    @Test
    fun `round trip, a telemetry point is preserved field by field`() {
        val p = point()
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(8, 0L)
        w.writeTelemetry(p)
        w.finish()

        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.telemetry).containsExactly(p)
        assertThat(read.blocks).hasSize(1)
        assertThat(read.scan.telemetryPointCount).isEqualTo(1)
    }

    @Test
    fun `a telemetry block is exactly its header plus the size of one point`() {
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header()).writeTelemetry(point())
        assertThat(out.size()).isEqualTo(ChunkFormat.HEADER_SIZE + telemetryBlockSize)
    }

    @Test
    fun `the telemetry block is written to the documented layout`() {
        val p = point()
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header()).writeTelemetry(p)
        val raw = out.toByteArray()
        val off = ChunkFormat.HEADER_SIZE
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(raw.copyOfRange(off, off + 4)).isEqualTo(ChunkFormat.TELEMETRY_MAGIC)
        assertThat(bb.getShort(off + 4).toInt()).isEqualTo(1)
        assertThat(bb.getShort(off + 6).toInt()).isEqualTo(ChunkFormat.TELEMETRY_POINT_SIZE)
        assertThat(bb.getShort(off + 8).toInt()).isZero()
        // The CRC covers header[0, 10) then the payload, exactly as for a signal block.
        val expected = ChunkFormat.crc16(
            raw, off + ChunkFormat.TELEMETRY_HEADER_SIZE, ChunkFormat.TELEMETRY_POINT_SIZE,
            seed = ChunkFormat.crc16(raw, off, ChunkFormat.TELEMETRY_CRC_OFFSET),
        )
        assertThat(bb.getShort(off + ChunkFormat.TELEMETRY_CRC_OFFSET).toInt() and 0xFFFF)
            .isEqualTo(expected)
        assertThat(raw.copyOfRange(off + 12, off + 16)).containsOnly(0)

        val q = off + ChunkFormat.TELEMETRY_HEADER_SIZE
        assertThat(bb.getLong(q)).isEqualTo(p.elapsedRealtimeNs)
        assertThat(bb.getLong(q + 8)).isEqualTo(p.sensorTsNs)
        assertThat(bb.getInt(q + 16)).isEqualTo(p.batteryChargeUah)
        assertThat(bb.getInt(q + 20).toLong() and 0xFFFFFFFFL).isEqualTo(p.maxIntervalUs)
        assertThat(bb.getInt(q + 24).toLong() and 0xFFFFFFFFL).isEqualTo(p.fsyncTotalUs)
        assertThat(bb.getInt(q + 28).toLong() and 0xFFFFFFFFL).isEqualTo(p.fsyncMaxUs)
        assertThat(bb.getShort(q + 32).toInt()).isEqualTo(p.temperatureDeciC)
        assertThat(bb.getShort(q + 34).toInt() and 0xFFFF).isEqualTo(p.measuredRateCentiHz)
        assertThat(bb.getShort(q + 36).toInt() and 0xFFFF).isEqualTo(p.jitterStdUs)
        assertThat(bb.getShort(q + 38).toInt() and 0xFFFF).isEqualTo(p.clippedSamples)
        assertThat(bb.getShort(q + 40).toInt() and 0xFFFF).isEqualTo(p.fsyncCount)
        assertThat(raw[q + 42].toInt() and 0xFF).isEqualTo(p.batteryPct)
        assertThat(raw[q + 43].toInt() and 0xFF).isEqualTo(TelemetryPoint.OFF_BODY_REMOVED)
        assertThat(raw[q + 44].toInt()).isEqualTo(1)
        assertThat(raw.copyOfRange(q + 45, q + ChunkFormat.TELEMETRY_POINT_SIZE)).containsOnly(0)
    }

    @Test
    fun `the absence sentinels survive the round trip`() {
        // A missing reading must never come back out as a measurement: 0 % battery and 0 degrees
        // are perfectly plausible values, so absence has codes of its own.
        val p = point().copy(
            batteryPct = TelemetryPoint.BATTERY_UNKNOWN,
            batteryChargeUah = TelemetryPoint.CHARGE_UNKNOWN,
            temperatureDeciC = TelemetryPoint.TEMPERATURE_UNKNOWN,
            offBody = TelemetryPoint.OFF_BODY_ABSENT,
            charging = false,
        )
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header()).writeTelemetry(p)
        assertThat(ChunkReader.read(ByteArrayInputStream(out.toByteArray())).telemetry)
            .containsExactly(p)
    }

    @Test
    fun `a point grown by a later version stays readable`() {
        // Same rule as `headerSize` for the file header (F-32): `pointSize` allows fields to be
        // appended at the tail of the point without breaking the archives or changing version.
        val p = point()
        val native = ByteArrayOutputStream()
        val w = ChunkWriter(native, header())
        w.writeFlat(8, 0L)
        val before = native.size()
        w.writeTelemetry(p)
        val nativeBlock = native.toByteArray().copyOfRange(before, native.size())

        val grown = 56
        val block = ByteArray(ChunkFormat.TELEMETRY_HEADER_SIZE + grown)
        System.arraycopy(nativeBlock, 0, block, 0, nativeBlock.size)
        putShortLe(block, 6, grown)
        // The bytes unknown to this version: they must be skipped, not read.
        for (i in nativeBlock.size until block.size) block[i] = 0x5A
        putShortLe(
            block, ChunkFormat.TELEMETRY_CRC_OFFSET,
            ChunkFormat.crc16(
                block, ChunkFormat.TELEMETRY_HEADER_SIZE, grown,
                seed = ChunkFormat.crc16(block, 0, ChunkFormat.TELEMETRY_CRC_OFFSET),
            ),
        )

        val file = native.toByteArray().copyOf(before) + block
        val read = ChunkReader.read(ByteArrayInputStream(file))
        assertThat(read.telemetry).containsExactly(p)
        assertThat(read.blocks).hasSize(1)
        assertThat(read.corruptBlocks).isZero()
    }

    @Test
    fun `a point smaller than this version's is rejected`() {
        // The other direction is not symmetric: a truncated point would not leave enough to fill
        // the fields, and filling them with defaults would invent measurements.
        val native = ByteArrayOutputStream()
        val w = ChunkWriter(native, header())
        w.writeTelemetry(point())
        w.writeFlat(8, 0L)
        val bytes = native.toByteArray()
        putShortLe(bytes, ChunkFormat.HEADER_SIZE + 6, ChunkFormat.TELEMETRY_POINT_SIZE - 8)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.telemetry).isEmpty()
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_COUNT)
        // The signal that follows is intact: resynchronisation found its magic again.
        assertThat(read.blocks).hasSize(1)
    }

    // --- Cohabitation with the signal ---

    @Test
    fun `signal and telemetry interleave in the same chunk without disturbing each other`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(5) { k ->
            w.writeFlat(64, k * 2_000_000_000L, mark = k)
            w.writeTelemetry(point().copy(fsyncCount = k))
        }
        w.finish()

        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.blocks).hasSize(5)
        assertThat(read.blocks.map { it.x[0] })
            .containsExactlyElementsOf((0..4).map { ChunkFormat.toMs2(it.toShort()) })
        assertThat(read.telemetry.map { it.fsyncCount }).containsExactly(0, 1, 2, 3, 4)
        assertThat(read.scan.complete).isTrue()
        assertThat(read.corruptBlocks).isZero()
        assertThat(read.scan.desynchronised).isFalse()
    }

    @Test
    fun `the end marker declares the telemetry points`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(32, 0L)
        repeat(3) { w.writeTelemetry(point()) }
        w.finish()

        val scan = ChunkReader.forEachBlock(ByteArrayInputStream(out.toByteArray())) {}
        assertThat(scan.declaredTelemetryPointCount).isEqualTo(3)
        assertThat(scan.telemetryPointCount).isEqualTo(3)
        assertThat(scan.lostTelemetryPoints).isZero()
    }

    @Test
    fun `a corrupt telemetry block is skipped and the signal survives`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(64, 0L, mark = 1)
        w.writeTelemetry(point())
        w.writeFlat(64, 2_000_000_000L, mark = 2)
        w.finish()
        val bytes = out.toByteArray()

        val victim = ChunkFormat.HEADER_SIZE + blockSize(64) + ChunkFormat.TELEMETRY_HEADER_SIZE + 3
        bytes[victim] = (bytes[victim].toInt() xor 0xFF).toByte()

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.telemetry).isEmpty()
        assertThat(read.blocks.map { it.x[0] })
            .containsExactly(ChunkFormat.toMs2(1), ChunkFormat.toMs2(2))
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_CRC)
        assertThat(read.scan.resyncSkippedBytes).isEqualTo(telemetryBlockSize.toLong())
        // The end marker declares the point that the re-read did not find: the loss is quantified,
        // not merely suffered.
        assertThat(read.scan.lostTelemetryPoints).isEqualTo(1)
    }

    @Test
    fun `an absurd telemetry count is rejected without desynchronising what follows`() {
        // The exact case `MAX_TELEMETRY_POINTS` exists to bound: without it, a corrupt `count`
        // would make an absurd payload length be read, and so lose synchronisation.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeTelemetry(point())
        w.writeFlat(64, 0L, mark = 7)
        w.finish()
        val bytes = out.toByteArray()
        putShortLe(bytes, ChunkFormat.HEADER_SIZE + 4, 0xFFFF)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.telemetry).isEmpty()
        assertThat(read.blocks.map { it.x[0] }).containsExactly(ChunkFormat.toMs2(7))
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_COUNT)
        assertThat(read.scan.complete).isTrue()
    }

    @Test
    fun `a corrupt signal block does not lose the telemetry that follows it`() {
        // This is the assertion that locks in the resynchronisation's knowledge of `TLM!`: without
        // it, the reader would jump from signal block to signal block and throw away an intact
        // telemetry on the way — the very one that may explain the corruption.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(64, 0L, mark = 1)
        w.writeTelemetry(point())
        w.writeFlat(64, 2_000_000_000L, mark = 2)
        w.finish()
        val bytes = out.toByteArray()

        bytes[ChunkFormat.HEADER_SIZE] = 'Z'.code.toByte() // magic of the first signal block

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.telemetry).containsExactly(point())
        assertThat(read.blocks.map { it.x[0] }).containsExactly(ChunkFormat.toMs2(2))
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_MAGIC)
    }

    // --- Sensor clipping (FLAG_SENSOR_CLIPPED) ---

    @Test
    fun `sensor clipping is distinct from format saturation`() {
        // A 4 g sensor clips at 39.23 m/s2, a quarter of what the format can encode: the clipping
        // artefact was therefore completely invisible, since FLAG_SATURATED is only set at 16 g.
        // That is the gap which skewed the amplitude, and so the detection threshold.
        val fourG = 4f * ChunkFormat.G_IN_MS2.toFloat()
        val n = 10
        val x = FloatArray(n)
        x[0] = fourG + 1f
        x[1] = -(fourG + 1f)

        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header(maxRange = fourG))
        w.writeBlock(x, FloatArray(n), FloatArray(n), n, 0L, (n - 1) * stepNs, 0)

        assertThat(w.clippedSamples).isEqualTo(2)
        assertThat(w.saturatedSamples).isZero()

        val b = ChunkReader.read(ByteArrayInputStream(out.toByteArray())).blocks[0]
        assertThat(b.flags and ChunkFormat.FLAG_SENSOR_CLIPPED).isNotZero()
        assertThat(b.flags and ChunkFormat.FLAG_SATURATED).isZero()
    }

    @Test
    fun `a movement within the sensor range sets no flag`() {
        // Inverted assertion: a flag that was set all the time would no longer say anything.
        // Gravity plus a few g of jolts stays well below an 8 g rail.
        val n = 64
        val z = FloatArray(n) { 9.81f + (it % 7) }
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeBlock(FloatArray(n), FloatArray(n), z, n, 0L, (n - 1) * stepNs, 0)

        assertThat(w.clippedSamples).isZero()
        val b = ChunkReader.read(ByteArrayInputStream(out.toByteArray())).blocks[0]
        assertThat(b.flags and ChunkFormat.FLAG_SENSOR_CLIPPED).isZero()
    }

    @Test
    fun `an unknown range does not declare any clipping`() {
        // `sensorMaxRange` at zero means "we do not know", and we do not guess: declaring the
        // whole night clipped would be worse than declaring nothing.
        val n = 8
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header(maxRange = 0f))
        w.writeBlock(FloatArray(n) { 100f }, FloatArray(n), FloatArray(n), n, 0L, (n - 1) * stepNs, 0)
        assertThat(w.clippedSamples).isZero()
    }

    // --- Size non-regression ---

    @Test
    fun `a full telemetry block stays smaller than a full signal block`() {
        // This is what guarantees that the reader window — sized on the largest block — did not
        // have to grow. A larger window means more memory on the watch *and* on the phone, for a
        // piece of data that weighs a few kilobytes per night.
        val fullTelemetry = ChunkFormat.TELEMETRY_HEADER_SIZE +
            ChunkFormat.MAX_TELEMETRY_POINTS * ChunkFormat.TELEMETRY_POINT_SIZE
        val fullSignal = ChunkFormat.BLOCK_HEADER_SIZE +
            ChunkFormat.MAX_SAMPLES_PER_BLOCK * ChunkFormat.BYTES_PER_SAMPLE
        assertThat(fullTelemetry).isLessThanOrEqualTo(fullSignal)
    }

    @Test
    fun `telemetry overflows neither the rotation nor a DataItem`() {
        // The two ceilings that the KDoc of `WireProtocol` declares untouchable. Telemetry is
        // written without consulting the rotation condition — it can therefore only add itself
        // after the fact, and it is that overshoot which is bounded here.
        val pointsPerRotation =
            (WireProtocol.CHUNK_ROTATION_MS / WireProtocol.TELEMETRY_PERIOD_MS).toInt()
        val worst = WireProtocol.CHUNK_ROTATION_BYTES +
            pointsPerRotation * telemetryBlockSize +
            ChunkFormat.FOOTER_SIZE
        // 256 bytes of margin for the `ChunkMeta` and its length frame, which `DataLayerTransfer`
        // places in front of the file in the same payload.
        assertThat(worst + 256).isLessThan(WireProtocol.MAX_DATA_ITEM_BYTES.toLong())
        assertThat(worst - WireProtocol.CHUNK_ROTATION_BYTES).isLessThan(1024L)
    }

    @Test
    fun `a chunk full of signal still accepts its five points`() {
        // The check on the real bytes, and not on the arithmetic above: fill a chunk up to the
        // rotation ceiling, add the points of one rotation, and check the file still fits in a
        // `DataItem`.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        var t = 0L
        val n = ChunkFormat.MAX_SAMPLES_PER_BLOCK
        while (w.bytesWritten + blockSize(n) <= WireProtocol.CHUNK_ROTATION_BYTES) {
            w.writeFlat(n, t)
            t += n * stepNs
        }
        repeat((WireProtocol.CHUNK_ROTATION_MS / WireProtocol.TELEMETRY_PERIOD_MS).toInt()) {
            w.writeTelemetry(point())
        }
        w.finish()

        assertThat(out.size()).isLessThan(WireProtocol.MAX_DATA_ITEM_BYTES)
        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.scan.complete).isTrue()
        assertThat(read.telemetry).hasSize(5)
        assertThat(read.corruptBlocks).isZero()
    }
}
