package com.pendulum.format

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writing of a chunk file. Pure JVM: the `wear` module provides the [OutputStream]
 * and keeps control of the `FileDescriptor` for the `fsync` calls.
 *
 * No method writes backwards: the file is valid at every instant. [finish] only appends an
 * end marker at the tail, it patches nothing.
 *
 * The byte-by-byte layout is documented in the KDoc of [ChunkFormat].
 */
class ChunkWriter(
    private val out: OutputStream,
    private val header: ChunkHeader,
) {
    private val blockHeader = ByteArray(ChunkFormat.BLOCK_HEADER_SIZE)
    private val payload = ByteArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK * ChunkFormat.BYTES_PER_SAMPLE)
    private val telemetryBuf =
        ByteArray(ChunkFormat.TELEMETRY_HEADER_SIZE + ChunkFormat.TELEMETRY_POINT_SIZE)

    /**
     * Clipping threshold **of the sensor**, in m/s2. One notch of `resolution` below
     * `sensorMaxRange`: the HAL returns the rail value exactly, but the roundings of the
     * floating-point chain can miss it by one LSB, and missing the clipping is far more costly
     * than declaring it one LSB too early. `Float.POSITIVE_INFINITY` when the range is unknown:
     * we do not guess.
     */
    private val clipThreshold: Float =
        if (header.sensorMaxRange > 0f && header.sensorMaxRange.isFinite()) {
            header.sensorMaxRange - Math.max(header.sensorResolution, 0f)
        } else {
            Float.POSITIVE_INFINITY
        }

    var bytesWritten: Long = 0
        private set

    var samplesWritten: Long = 0
        private set

    var blocksWritten: Int = 0
        private set

    /**
     * Number of samples clipped at +/-32767 LSB (F-11). A non-zero count over a night means
     * the signal touched the ceiling of the format: the peaks are underestimated and any
     * amplitude derived from those blocks is wrong.
     */
    var saturatedSamples: Long = 0
        private set

    /**
     * Number of NaN/infinite samples replaced by 0 (F-11). A zero from a faulty sensor is
     * indistinguishable from free fall: without this counter, the fault reads as a movement.
     */
    var nonFiniteSamples: Long = 0
        private set

    /**
     * Number of samples that touched the range of the **sensor** (`sensorMaxRange`), and not
     * the ceiling of the format. See [ChunkFormat.FLAG_SENSOR_CLIPPED]: this is a clipping
     * that remained completely invisible, since a sensor at 8 g clips at half of what the
     * quantisation can encode.
     */
    var clippedSamples: Long = 0
        private set

    /** Telemetry points written in this chunk. Copied into the end marker. */
    var telemetryPointsWritten: Int = 0
        private set

    /** True once the end marker is written: no block is accepted any more. */
    var finished: Boolean = false
        private set

    private var lastTimestampNs: Long = 0

    init {
        require(header.headerSize == ChunkFormat.HEADER_SIZE) {
            "this version only writes headers of ${ChunkFormat.HEADER_SIZE} bytes"
        }
        require(header.formatVersion == ChunkFormat.FORMAT_VERSION) {
            "this version only writes format v${ChunkFormat.FORMAT_VERSION}"
        }
        val h = ByteBuffer.allocate(ChunkFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.put(ChunkFormat.FILE_MAGIC)                       // 8  -> 8
        h.putShort(header.formatVersion.toShort())          // 2  -> 10
        h.putShort(ChunkFormat.HEADER_SIZE.toShort())       // 2  -> 12
        h.putShort(header.nominalRateHz.toShort())          // 2  -> 14
        h.putShort(header.fifoMaxEventCount.toShort())      // 2  -> 16
        h.put(header.sessionUuid)                           // 16 -> 32
        h.putLong(header.startWallMs)                       // 8  -> 40
        h.putLong(header.startElapsedRealtimeNs)            // 8  -> 48
        h.putLong(header.firstEventTimestampNs)             // 8  -> 56
        h.putFloat(header.sensorResolution)                 // 4  -> 60
        h.putFloat(header.sensorMaxRange)                   // 4  -> 64
        h.putInt(header.chunkIndex)                         // 4  -> 68
        h.putShort(header.modeFlags.toShort())              // 2  -> 70
        h.putShort(header.tzOffsetMin.toShort())            // 2  -> 72
        // 6 reserved bytes, left at zero                           -> 78
        val bytes = h.array()
        // The header CRC (F-08) is the last field: a corrupt byte in startWallMs otherwise
        // dated the whole night wrong, without the slightest detection.
        val crc = ChunkFormat.crc16(bytes, 0, ChunkFormat.HEADER_SIZE - 2)
        putShortLe(bytes, ChunkFormat.HEADER_SIZE - 2, crc.toShort())
        out.write(bytes)
        bytesWritten += ChunkFormat.HEADER_SIZE
    }

    /**
     * Writes a block. The arrays are read over `[0, count)`.
     *
     * The block must come from **a single FIFO flush** (F-03): `(tLast - tFirst)/(count - 1)`
     * must stay within [ChunkFormat.TIMEBASE_TOLERANCE] of the nominal period. A block straddling
     * two flushes contains a hole that the linear interpolation spreads over all of its samples,
     * and the producer is the only one able to cut at the right place — hence a `require` and not
     * a flag.
     *
     * @param count number of samples, in `1..MAX_SAMPLES_PER_BLOCK`.
     */
    fun writeBlock(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        count: Int,
        tFirstNs: Long,
        tLastNs: Long,
        flags: Int,
    ) {
        check(!finished) { "the chunk is closed, no block can be added to it any more" }
        require(count in 1..ChunkFormat.MAX_SAMPLES_PER_BLOCK) {
            "count out of bounds: $count"
        }
        require(x.size >= count && y.size >= count && z.size >= count) {
            "arrays too short for count=$count"
        }
        require(flags in 0..0xFFFF) { "flags outside u16: $flags" }
        require(tLastNs >= tFirstNs) {
            "tLastNs < tFirstNs ($tLastNs < $tFirstNs): reversed time base"
        }
        require(count == 1 || tLastNs > tFirstNs) {
            "block of $count samples with zero duration"
        }
        require(ChunkFormat.isTimebasePlausible(count, tFirstNs, tLastNs, header.nominalRateHz)) {
            "implicit rate of ${ChunkFormat.meanIntervalNs(count, tFirstNs, tLastNs)} ns/sample " +
                "incompatible with ${header.nominalRateHz} Hz: the block probably straddles two FIFO flushes"
        }

        var saturated = false
        var nonFinite = false
        var clipped = false
        var p = 0
        for (i in 0 until count) {
            val rx = ChunkFormat.toRaw(x[i])
            val ry = ChunkFormat.toRaw(y[i])
            val rz = ChunkFormat.toRaw(z[i])
            // Counted per sample (the triplet), not per axis: it is the sample that is unusable
            // as soon as one of its axes has been clipped or replaced.
            if (!x[i].isFinite() || !y[i].isFinite() || !z[i].isFinite()) {
                nonFiniteSamples++
                nonFinite = true
            } else {
                if (isSaturated(rx) || isSaturated(ry) || isSaturated(rz)) {
                    saturatedSamples++
                    saturated = true
                }
                // Counted separately and not as an `else if`: the two clippings answer two
                // different questions — "did the format overflow" and "did the sensor touch its
                // rail" — and on a sensor at 8 g the second happens without the first.
                if (isClipped(x[i]) || isClipped(y[i]) || isClipped(z[i])) {
                    clippedSamples++
                    clipped = true
                }
            }
            putShortLe(payload, p, rx); p += 2
            putShortLe(payload, p, ry); p += 2
            putShortLe(payload, p, rz); p += 2
        }
        val payloadLen = count * ChunkFormat.BYTES_PER_SAMPLE

        val effectiveFlags = flags or
            (if (saturated) ChunkFormat.FLAG_SATURATED else 0) or
            (if (nonFinite) ChunkFormat.FLAG_NON_FINITE else 0) or
            (if (clipped) ChunkFormat.FLAG_SENSOR_CLIPPED else 0)

        val b = ByteBuffer.wrap(blockHeader).order(ByteOrder.LITTLE_ENDIAN)
        b.clear()
        b.put(ChunkFormat.BLOCK_MAGIC)          // 4  -> 4
        b.putShort(count.toShort())             // 2  -> 6
        b.putLong(tFirstNs)                     // 8  -> 14
        b.putLong(tLastNs)                      // 8  -> 22
        b.putShort(effectiveFlags.toShort())    // 2  -> 24
        // The CRC covers the block header then the payload (F-02), hence the chaining of the seed.
        val crc = ChunkFormat.crc16(
            payload, 0, payloadLen,
            seed = ChunkFormat.crc16(blockHeader, 0, ChunkFormat.BLOCK_CRC_OFFSET),
        )
        b.putShort(crc.toShort())               // 2  -> 26
        java.util.Arrays.fill(blockHeader, 26, ChunkFormat.BLOCK_HEADER_SIZE, 0)

        out.write(blockHeader)
        out.write(payload, 0, payloadLen)
        // Flush on every block: the cost is negligible (one block every ~10 s at 50 Hz)
        // and it bounds the loss to a single block in case of an abrupt kill.
        out.flush()

        bytesWritten += ChunkFormat.BLOCK_HEADER_SIZE + payloadLen
        samplesWritten += count
        blocksWritten++
        lastTimestampNs = tLastNs
    }

    /**
     * Writes a telemetry block carrying a single point.
     *
     * One point per block, and not an accumulated burst: a block is the unit of loss of the
     * format, and accumulating ten minutes of telemetry to write them at once would lose ten
     * minutes where only one is lost. The overhead is 16 header bytes per point, that is,
     * 80 bytes on a chunk of 92 160 — 0.09 %.
     *
     * The block is written **between** two signal blocks, never inside one: every block of the
     * format is self-delimited and protected by its own CRC, so the interleaving costs nothing
     * on read-back and any block remains skippable on its own.
     */
    fun writeTelemetry(point: TelemetryPoint) {
        check(!finished) { "the chunk is closed, no block can be added to it any more" }
        val b = ByteBuffer.wrap(telemetryBuf).order(ByteOrder.LITTLE_ENDIAN)
        b.clear()
        b.put(ChunkFormat.TELEMETRY_MAGIC)                          // 4  -> 4
        b.putShort(1)                                               // 2  -> 6   count
        b.putShort(ChunkFormat.TELEMETRY_POINT_SIZE.toShort())      // 2  -> 8   pointSize
        b.putShort(0)                                               // 2  -> 10  flags, reserved
        // crc at 10..12, written last; 12..16 reserved, already at zero.
        java.util.Arrays.fill(telemetryBuf, ChunkFormat.TELEMETRY_CRC_OFFSET, ChunkFormat.TELEMETRY_HEADER_SIZE, 0)

        b.position(ChunkFormat.TELEMETRY_HEADER_SIZE)
        b.putLong(point.elapsedRealtimeNs)                          // 8  -> 8
        b.putLong(point.sensorTsNs)                                 // 8  -> 16
        b.putInt(point.batteryChargeUah)                            // 4  -> 20
        b.putInt(point.maxIntervalUs.toInt())                       // 4  -> 24
        b.putInt(point.fsyncTotalUs.toInt())                        // 4  -> 28
        b.putInt(point.fsyncMaxUs.toInt())                          // 4  -> 32
        b.putShort(point.temperatureDeciC.toShort())                // 2  -> 34
        b.putShort(point.measuredRateCentiHz.toShort())             // 2  -> 36
        b.putShort(point.jitterStdUs.toShort())                     // 2  -> 38
        b.putShort(point.clippedSamples.toShort())                  // 2  -> 40
        b.putShort(point.fsyncCount.toShort())                      // 2  -> 42
        b.put(point.batteryPct.toByte())                            // 1  -> 43
        b.put(point.offBody.toByte())                               // 1  -> 44
        b.put(if (point.charging) 1 else 0)                         // 1  -> 45
        java.util.Arrays.fill(telemetryBuf, ChunkFormat.TELEMETRY_HEADER_SIZE + 45, telemetryBuf.size, 0)

        // Same chaining as for a signal block: the header first, the payload next.
        val crc = ChunkFormat.crc16(
            telemetryBuf, ChunkFormat.TELEMETRY_HEADER_SIZE, ChunkFormat.TELEMETRY_POINT_SIZE,
            seed = ChunkFormat.crc16(telemetryBuf, 0, ChunkFormat.TELEMETRY_CRC_OFFSET),
        )
        putShortLe(telemetryBuf, ChunkFormat.TELEMETRY_CRC_OFFSET, crc.toShort())

        out.write(telemetryBuf)
        out.flush()
        bytesWritten += telemetryBuf.size
        telemetryPointsWritten++
    }

    /**
     * Writes the end-of-file marker (F-37) and flushes the stream. Idempotent.
     *
     * Without this marker, a chunk being written is indistinguishable from a complete chunk:
     * the phone acknowledges it and the watch deletes a partial file (F-13). To be called only
     * after rotation, never on the current chunk.
     */
    fun finish() {
        if (finished) return
        val f = ByteBuffer.allocate(ChunkFormat.FOOTER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        f.put(ChunkFormat.FILE_FOOTER_MAGIC)    // 8  -> 8
        f.putInt(blocksWritten)                 // 4  -> 12
        f.putLong(samplesWritten)               // 8  -> 20
        f.putLong(lastTimestampNs)              // 8  -> 28
        // The two bytes that v1 left at zero. A v1 chunk read back here therefore announces zero
        // telemetry points, which is the truth and not a default value.
        f.putShort(telemetryPointsWritten.coerceAtMost(0xFFFF).toShort()) // 2 -> 30
        val bytes = f.array()
        val crc = ChunkFormat.crc16(bytes, 0, ChunkFormat.FOOTER_SIZE - 2)
        putShortLe(bytes, ChunkFormat.FOOTER_SIZE - 2, crc.toShort())
        out.write(bytes)
        out.flush()
        bytesWritten += ChunkFormat.FOOTER_SIZE
        finished = true
    }

    /**
     * A clipped value is indistinguishable from a value that lands exactly on the bound; at
     * 15.9995 g that confusion never happens on an ankle, so we accept it.
     */
    private fun isSaturated(raw: Short): Boolean =
        raw == Short.MAX_VALUE || raw == Short.MIN_VALUE

    /** True if the value touches the sensor range. See [clipThreshold]. */
    private fun isClipped(ms2: Float): Boolean = Math.abs(ms2) >= clipThreshold

    private fun putShortLe(buf: ByteArray, offset: Int, v: Short) {
        buf[offset] = (v.toInt() and 0xFF).toByte()
        buf[offset + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }
}

/** Reason why a byte range was rejected on read-back. */
enum class DamageReason {
    /** Block magic missing where it was expected: outright desynchronisation. */
    BAD_MAGIC,

    /** `count` out of bounds: the payload length would have been read wrong. */
    BAD_COUNT,

    /** Invalid CRC: block header or payload corrupt. */
    BAD_CRC,

    /** Valid CRC but `tLastNs < tFirstNs`: structurally impossible (F-14). */
    INVALID_TIMEBASE,

    /** End of file in the middle of a block: the benign case of an abrupt kill (F-12). */
    TRUNCATED_TAIL,
}

/**
 * Unreadable zone of a chunk file, located in the file **and** in time (F-35).
 * This is what makes it possible to turn "3 blocks lost" into "30 s missing at 3:12".
 *
 * @param fileOffset absolute offset, in the file, of the first rejected byte.
 * @param byteLength number of bytes skipped.
 * @param afterTimestampNs `tLastNs` of the last valid block *before* the zone, `null` if there is
 *   none.
 * @param beforeTimestampNs `tFirstNs` of the first valid block *after* the zone, `null` if the
 *   file ends inside the zone.
 */
data class DamagedRange(
    val reason: DamageReason,
    val fileOffset: Long,
    val byteLength: Long,
    val afterTimestampNs: Long?,
    val beforeTimestampNs: Long?,
) {
    /** Missing signal duration, or `null` if either of the two time bounds is unknown. */
    val missingDurationNs: Long?
        get() = if (afterTimestampNs != null && beforeTimestampNs != null) {
            beforeTimestampNs - afterTimestampNs
        } else {
            null
        }
}

/**
 * Summary of the read-back of a chunk file, without the samples: it is the result of the
 * streaming API [ChunkReader.forEachBlock], and the same object is carried by [ChunkFile].
 *
 * @param complete true if the end-of-file marker was read and its CRC checked. An incomplete
 *   chunk must never be acknowledged by the phone (F-13/F-37).
 * @param truncatedTail true if the file ends in the middle of a block — the **benign** case of an
 *   abrupt kill, to be distinguished from [desynchronised] (F-12).
 * @param desynchronised true if an unreadable zone was met somewhere other than at the tail:
 *   where `truncatedTail` costs at worst one block, this signals a corruption mid-file.
 * @param declaredBlockCount number of blocks announced by the end marker, `null` if absent.
 */
class ChunkScanResult(
    val header: ChunkHeader,
    val blockCount: Int,
    val decodedSampleCount: Long,
    val corruptBlocks: Int,
    val suspectTimebaseBlocks: Int,
    val resyncSkippedBytes: Long,
    val damagedRanges: List<DamagedRange>,
    val complete: Boolean,
    val truncatedTail: Boolean,
    val desynchronised: Boolean,
    val declaredBlockCount: Int?,
    val declaredSampleCount: Long?,
    /** Decoded telemetry points. Zero on a v1 format chunk, which carried none. */
    val telemetryPointCount: Int = 0,
    /** Points announced by the end marker, `null` without a marker. */
    val declaredTelemetryPointCount: Int? = null,
) {
    /** Blocks announced by the end marker but absent on read-back. `null` without a marker. */
    val lostBlocks: Int? get() = declaredBlockCount?.let { it - blockCount }

    /** Telemetry points announced but absent on read-back. `null` without a marker. */
    val lostTelemetryPoints: Int?
        get() = declaredTelemetryPointCount?.let { it - telemetryPointCount }

    /** Total missing signal duration, over only the zones whose two bounds are known. */
    val missingDurationNs: Long get() = damagedRanges.sumOf { it.missingDurationNs ?: 0L }
}

/**
 * Result of the *materialised* reading of a chunk file. Convenient for tests and for short
 * files; for a whole night, prefer [ChunkReader.forEachBlock] (F-27).
 */
class ChunkFile(
    val scan: ChunkScanResult,
    val blocks: List<DecodedBlock>,
    /** The telemetry points of the chunk, in write order. */
    val telemetry: List<TelemetryPoint> = emptyList(),
) {
    val header: ChunkHeader get() = scan.header

    /** Blocks rejected for invalid CRC, missing magic, aberrant `count` or impossible time base. */
    val corruptBlocks: Int get() = scan.corruptBlocks

    /** True if the file ends with a truncated block (the normal case of an abrupt kill). */
    val truncatedTail: Boolean get() = scan.truncatedTail

    val sampleCount: Int get() = blocks.sumOf { it.sampleCount }
}

/**
 * Failure-tolerant reading of a chunk file.
 *
 * A block whose CRC does not match, whose magic is missing, whose `count` is aberrant or whose
 * time base is impossible is **skipped**, and the reader **resynchronises** by looking for the
 * next `BLK!` (F-02): skipping a block "while keeping in sync" makes no sense when it is
 * precisely `count` that may be corrupt, since the payload length read is then wrong. Every
 * skipped zone is located in [ChunkScanResult.damagedRanges].
 *
 * The reading only throws if the file header itself is invalid: the rest of the file is always
 * exploited as best as possible.
 */
object ChunkReader {

    /** Guard rail: beyond it, `headerSize` is manifestly a corrupt value. */
    private const val MAX_HEADER_SIZE = 4096

    private const val MAX_BLOCK_SIZE =
        ChunkFormat.BLOCK_HEADER_SIZE + ChunkFormat.MAX_SAMPLES_PER_BLOCK * ChunkFormat.BYTES_PER_SAMPLE

    /**
     * The buffer must be able to hold the largest block and the largest header: this is what
     * bounds the backtracking of the resynchronisation, and hence the reader's memory.
     */
    private const val BUFFER_SIZE = 2 * MAX_BLOCK_SIZE

    /**
     * Streaming reading: every decoded block is passed to [onBlock] then forgotten. This is the
     * API to use over a whole night — the materialised version keeps ~17 MB of useful data and
     * several thousand arrays alive for an algorithm that is itself streaming (F-27).
     *
     * The [DecodedBlock] passed to [onBlock] is not reused: the caller may keep it if they wish,
     * but it is then their memory consumption, not the reader's.
     */
    fun forEachBlock(
        input: InputStream,
        /** The telemetry points of the chunk. Ignored by default: most callers — the watch's
         *  burst, the export, the reassembly — are only interested in the signal. */
        onTelemetry: (TelemetryPoint) -> Unit = {},
        /** The chunk header, delivered **before** the first block. The header is also returned in
         *  [ChunkScanResult], but that only arrives once the whole chunk has been read: a caller
         *  that must tag each block with something the header carries — the nominal rate, which
         *  changes from one chunk to the next after an auto-degradation — cannot wait for it. */
        onHeader: (ChunkHeader) -> Unit = {},
        onBlock: (DecodedBlock) -> Unit,
    ): ChunkScanResult {
        val sc = ByteScanner(input, BUFFER_SIZE)
        val header = readHeader(sc)
        onHeader(header)

        val blockHeader = ByteArray(ChunkFormat.BLOCK_HEADER_SIZE)
        val payload = ByteArray(ChunkFormat.MAX_SAMPLES_PER_BLOCK * ChunkFormat.BYTES_PER_SAMPLE)
        val telemetryBuf = ByteArray(MAX_BLOCK_SIZE)
        val damaged = ArrayList<DamagedRange>()

        var telemetryCount = 0
        var declaredTelemetry: Int? = null
        var blockCount = 0
        var sampleCount = 0L
        var corrupt = 0
        var suspect = 0
        var skipped = 0L
        var complete = false
        var truncated = false
        var lastValidTLast: Long? = null
        var openDamageIdx = -1
        var declaredBlocks: Int? = null
        var declaredSamples: Long? = null

        /** Records a lost zone and resynchronises on the next magic. */
        fun damageAndResync(reason: DamageReason) {
            val from = sc.offset
            val length = resync(sc)
            skipped += length
            damaged.add(DamagedRange(reason, from, length, lastValidTLast, null))
            openDamageIdx = damaged.size - 1
        }

        /** Records a truncated tail: the rest of the file fits in the lost zone. */
        fun damageTail(reason: DamageReason) {
            val from = sc.offset
            val length = sc.drain()
            truncated = true
            damaged.add(DamagedRange(reason, from, length, lastValidTLast, null))
            openDamageIdx = -1
        }

        while (true) {
            if (!sc.ensure(4)) {
                // Fewer than 4 bytes left: unusable remainder, hence a truncated tail.
                if (sc.available > 0) damageTail(DamageReason.TRUNCATED_TAIL)
                break
            }

            if (sc.startsWith(ChunkFormat.FILE_FOOTER_MAGIC, 4)) {
                if (!sc.ensure(ChunkFormat.FOOTER_SIZE) ||
                    !sc.startsWith(ChunkFormat.FILE_FOOTER_MAGIC, ChunkFormat.FILE_FOOTER_MAGIC.size)
                ) {
                    damageTail(DamageReason.TRUNCATED_TAIL)
                    break
                }
                val footer = ByteArray(ChunkFormat.FOOTER_SIZE)
                sc.copyOut(footer, 0, ChunkFormat.FOOTER_SIZE)
                val f = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
                f.position(8)
                val fBlocks = f.int
                val fSamples = f.long
                f.position(28)
                val fTelemetry = f.short.toInt() and 0xFFFF
                val fCrc = f.short.toInt() and 0xFFFF
                if (ChunkFormat.crc16(footer, 0, ChunkFormat.FOOTER_SIZE - 2) == fCrc) {
                    complete = true
                    declaredBlocks = fBlocks
                    declaredSamples = fSamples
                    declaredTelemetry = fTelemetry
                    sc.skip(ChunkFormat.FOOTER_SIZE)
                } else {
                    damageAndResync(DamageReason.BAD_CRC)
                    corrupt++
                    continue
                }
                break
            }

            if (sc.startsWith(ChunkFormat.TELEMETRY_MAGIC, ChunkFormat.TELEMETRY_MAGIC.size)) {
                if (!sc.ensure(ChunkFormat.TELEMETRY_HEADER_SIZE)) {
                    damageTail(DamageReason.TRUNCATED_TAIL)
                    break
                }
                sc.copyOut(telemetryBuf, 0, ChunkFormat.TELEMETRY_HEADER_SIZE)
                val t = ByteBuffer.wrap(telemetryBuf).order(ByteOrder.LITTLE_ENDIAN)
                t.position(4)
                val tCount = t.short.toInt() and 0xFFFF
                val tPointSize = t.short.toInt() and 0xFFFF
                t.position(ChunkFormat.TELEMETRY_CRC_OFFSET)
                val tCrc = t.short.toInt() and 0xFFFF

                // A `pointSize` larger than this version's is **legal**: a more recent writer
                // appended fields at the tail of the point, we read what we know at fixed offsets
                // and skip the rest. Smaller, no: there would not be enough to fill the fields.
                // Same rule as `headerSize` for the file header (F-32).
                val tBlockSize = ChunkFormat.TELEMETRY_HEADER_SIZE + tCount * tPointSize
                if (tCount < 1 || tCount > ChunkFormat.MAX_TELEMETRY_POINTS ||
                    tPointSize < ChunkFormat.TELEMETRY_POINT_SIZE ||
                    tBlockSize > MAX_BLOCK_SIZE
                ) {
                    corrupt++
                    damageAndResync(DamageReason.BAD_COUNT)
                    continue
                }
                if (!sc.ensure(tBlockSize)) {
                    damageTail(DamageReason.TRUNCATED_TAIL)
                    break
                }
                sc.copyOut(telemetryBuf, 0, tBlockSize)
                val computed = ChunkFormat.crc16(
                    telemetryBuf, ChunkFormat.TELEMETRY_HEADER_SIZE, tCount * tPointSize,
                    seed = ChunkFormat.crc16(telemetryBuf, 0, ChunkFormat.TELEMETRY_CRC_OFFSET),
                )
                if (computed != tCrc) {
                    corrupt++
                    damageAndResync(DamageReason.BAD_CRC)
                    continue
                }
                for (i in 0 until tCount) {
                    onTelemetry(
                        decodeTelemetryPoint(
                            telemetryBuf,
                            ChunkFormat.TELEMETRY_HEADER_SIZE + i * tPointSize,
                        ),
                    )
                    telemetryCount++
                }
                sc.skip(tBlockSize)
                // `openDamageIdx` and `lastValidTLast` do not move: a telemetry point is not
                // signal, so it cannot bound a zone of lost signal.
                continue
            }

            if (!sc.startsWith(ChunkFormat.BLOCK_MAGIC, ChunkFormat.BLOCK_MAGIC.size)) {
                corrupt++
                damageAndResync(DamageReason.BAD_MAGIC)
                continue
            }

            if (!sc.ensure(ChunkFormat.BLOCK_HEADER_SIZE)) {
                damageTail(DamageReason.TRUNCATED_TAIL)
                break
            }
            sc.copyOut(blockHeader, 0, ChunkFormat.BLOCK_HEADER_SIZE)
            val b = ByteBuffer.wrap(blockHeader).order(ByteOrder.LITTLE_ENDIAN)
            b.position(4)
            val count = b.short.toInt() and 0xFFFF
            val tFirst = b.long
            val tLast = b.long
            val flags = b.short.toInt() and 0xFFFF
            val expectedCrc = b.short.toInt() and 0xFFFF

            if (count < 1 || count > ChunkFormat.MAX_SAMPLES_PER_BLOCK) {
                corrupt++
                damageAndResync(DamageReason.BAD_COUNT)
                continue
            }

            val payloadLen = count * ChunkFormat.BYTES_PER_SAMPLE
            if (!sc.ensure(ChunkFormat.BLOCK_HEADER_SIZE + payloadLen)) {
                damageTail(DamageReason.TRUNCATED_TAIL)
                break
            }
            sc.copyOut(payload, ChunkFormat.BLOCK_HEADER_SIZE, payloadLen)
            val computedCrc = ChunkFormat.crc16(
                payload, 0, payloadLen,
                seed = ChunkFormat.crc16(blockHeader, 0, ChunkFormat.BLOCK_CRC_OFFSET),
            )
            if (computedCrc != expectedCrc) {
                corrupt++
                damageAndResync(DamageReason.BAD_CRC)
                continue
            }

            // Valid CRC but impossible chronology: the block is structurally wrong, not merely
            // doubtful, so we reject it (F-14). An aberrant rate, on the other hand, is reported
            // and not rejected: the signal stays usable for everything that does not date.
            if (tLast < tFirst) {
                corrupt++
                sc.skip(ChunkFormat.BLOCK_HEADER_SIZE + payloadLen)
                damaged.add(
                    DamagedRange(
                        DamageReason.INVALID_TIMEBASE,
                        sc.offset - ChunkFormat.BLOCK_HEADER_SIZE - payloadLen,
                        (ChunkFormat.BLOCK_HEADER_SIZE + payloadLen).toLong(),
                        lastValidTLast,
                        null,
                    )
                )
                openDamageIdx = damaged.size - 1
                continue
            }

            val suspectTimebase =
                !ChunkFormat.isTimebasePlausible(count, tFirst, tLast, header.nominalRateHz)
            if (suspectTimebase) suspect++

            val x = FloatArray(count)
            val y = FloatArray(count)
            val z = FloatArray(count)
            var p = 0
            for (i in 0 until count) {
                x[i] = ChunkFormat.toMs2(getShortLe(payload, p)); p += 2
                y[i] = ChunkFormat.toMs2(getShortLe(payload, p)); p += 2
                z[i] = ChunkFormat.toMs2(getShortLe(payload, p)); p += 2
            }
            sc.skip(ChunkFormat.BLOCK_HEADER_SIZE + payloadLen)

            if (openDamageIdx >= 0) {
                damaged[openDamageIdx] = damaged[openDamageIdx].copy(beforeTimestampNs = tFirst)
                openDamageIdx = -1
            }
            blockCount++
            sampleCount += count
            lastValidTLast = tLast
            onBlock(DecodedBlock(tFirst, tLast, flags, x, y, z, suspectTimebase))
        }

        return ChunkScanResult(
            header = header,
            blockCount = blockCount,
            decodedSampleCount = sampleCount,
            corruptBlocks = corrupt,
            suspectTimebaseBlocks = suspect,
            resyncSkippedBytes = skipped,
            damagedRanges = damaged,
            complete = complete,
            truncatedTail = truncated,
            desynchronised = damaged.any { it.reason != DamageReason.TRUNCATED_TAIL },
            declaredBlockCount = declaredBlocks,
            declaredSampleCount = declaredSamples,
            telemetryPointCount = telemetryCount,
            declaredTelemetryPointCount = declaredTelemetry,
        )
    }

    /** Materialised reading. See [forEachBlock] for the API to use over a whole night. */
    fun read(input: InputStream): ChunkFile {
        val blocks = ArrayList<DecodedBlock>()
        val telemetry = ArrayList<TelemetryPoint>()
        val scan = forEachBlock(input, onTelemetry = { telemetry.add(it) }) { blocks.add(it) }
        return ChunkFile(scan, blocks, telemetry)
    }

    /**
     * Decodes a point at `offset`. Reads only the [ChunkFormat.TELEMETRY_POINT_SIZE] bytes known
     * to this version: a longer point produced by a more recent writer leaves its tail intact,
     * and the caller has already computed the offset of the next one from the block's `pointSize`.
     */
    private fun decodeTelemetryPoint(buf: ByteArray, offset: Int): TelemetryPoint {
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        b.position(offset)
        return TelemetryPoint(
            elapsedRealtimeNs = b.long,
            sensorTsNs = b.long,
            batteryChargeUah = b.int,
            maxIntervalUs = b.int.toLong() and 0xFFFFFFFFL,
            fsyncTotalUs = b.int.toLong() and 0xFFFFFFFFL,
            fsyncMaxUs = b.int.toLong() and 0xFFFFFFFFL,
            temperatureDeciC = b.short.toInt(),
            measuredRateCentiHz = b.short.toInt() and 0xFFFF,
            jitterStdUs = b.short.toInt() and 0xFFFF,
            clippedSamples = b.short.toInt() and 0xFFFF,
            fsyncCount = b.short.toInt() and 0xFFFF,
            batteryPct = b.get().toInt() and 0xFF,
            offBody = b.get().toInt() and 0xFF,
            charging = b.get().toInt() != 0,
        )
    }

    private fun readHeader(sc: ByteScanner): ChunkHeader {
        if (!sc.ensure(ChunkFormat.HEADER_PREFIX_SIZE)) throw EOFException("truncated file header")
        val prefix = ByteArray(ChunkFormat.HEADER_PREFIX_SIZE)
        sc.copyOut(prefix, 0, ChunkFormat.HEADER_PREFIX_SIZE)
        val magic = prefix.copyOf(8)
        if (!magic.contentEquals(ChunkFormat.FILE_MAGIC)) {
            throw IOException("invalid file magic: ${magic.toString(Charsets.US_ASCII)}")
        }
        val formatVersion = getShortLe(prefix, 8).toInt() and 0xFFFF
        if (formatVersion < 1 || formatVersion > ChunkFormat.FORMAT_VERSION) {
            throw IOException("unhandled format version: $formatVersion")
        }
        val headerSize = getShortLe(prefix, 10).toInt() and 0xFFFF
        // headerSize may exceed HEADER_SIZE: a later version appended fields at the tail without
        // changing the block layout, we read what we know and ignore the rest.
        if (headerSize < ChunkFormat.HEADER_SIZE || headerSize > MAX_HEADER_SIZE) {
            throw IOException("invalid headerSize: $headerSize")
        }
        if (!sc.ensure(headerSize)) throw EOFException("truncated file header")

        val hb = ByteArray(headerSize)
        sc.copyOut(hb, 0, headerSize)
        val storedCrc = getShortLe(hb, headerSize - 2).toInt() and 0xFFFF
        if (ChunkFormat.crc16(hb, 0, headerSize - 2) != storedCrc) {
            throw IOException("invalid file header CRC")
        }

        val h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        h.position(12)
        val nominalRateHz = h.short.toInt() and 0xFFFF
        val fifoMaxEventCount = h.short.toInt() and 0xFFFF
        val uuid = ByteArray(16).also { h.get(it) }
        val startWallMs = h.long
        val startElapsedRealtimeNs = h.long
        val firstEventTimestampNs = h.long
        val resolution = h.float
        val maxRange = h.float
        val chunkIndex = h.int
        val modeFlags = h.short.toInt() and 0xFFFF
        val tzOffsetMin = h.short.toInt()

        sc.skip(headerSize)
        return ChunkHeader(
            sessionUuid = uuid,
            chunkIndex = chunkIndex,
            nominalRateHz = nominalRateHz,
            startWallMs = startWallMs,
            startElapsedRealtimeNs = startElapsedRealtimeNs,
            firstEventTimestampNs = firstEventTimestampNs,
            sensorResolution = resolution,
            sensorMaxRange = maxRange,
            fifoMaxEventCount = fifoMaxEventCount,
            modeFlags = modeFlags,
            tzOffsetMin = tzOffsetMin,
            formatVersion = formatVersion,
            headerSize = headerSize,
        )
    }

    /**
     * Advances to the next magic — signal block, telemetry block or end of file.
     * Returns the number of bytes skipped, cursor positioned on the magic found (or on the end
     * of the stream). The three magics are searched together: not knowing `TLM!` would skip
     * everything following a corrupt signal block up to the next signal block, telemetry
     * included, when it is intact and may be what explains the corruption.
     */
    private fun resync(sc: ByteScanner): Long {
        val from = sc.offset
        sc.skip(1)
        while (sc.ensure(4)) {
            if (sc.startsWith(ChunkFormat.BLOCK_MAGIC, 4) ||
                sc.startsWith(ChunkFormat.TELEMETRY_MAGIC, 4) ||
                sc.startsWith(ChunkFormat.FILE_FOOTER_MAGIC, 4)
            ) {
                break
            }
            sc.skip(1)
        }
        if (sc.available < 4) sc.drain()
        return sc.offset - from
    }

    private fun getShortLe(buf: ByteArray, offset: Int): Short =
        (((buf[offset + 1].toInt() and 0xFF) shl 8) or (buf[offset].toInt() and 0xFF)).toShort()
}

/**
 * Sliding window over an [InputStream], with bounded backtracking: this is what makes
 * resynchronisation possible without materialising the file. The capacity must exceed the
 * largest block, failing which a valid block could never fit entirely in the window.
 */
private class ByteScanner(private val input: InputStream, capacity: Int) {
    private val buf = ByteArray(capacity)
    private var start = 0
    private var end = 0
    private var eof = false

    /** Absolute offset of the cursor in the file, to locate the lost zones. */
    var offset: Long = 0L
        private set

    val available: Int get() = end - start

    /** Guarantees [n] bytes available from the cursor. False if the stream ends before that. */
    fun ensure(n: Int): Boolean {
        require(n <= buf.size) { "window too small for $n bytes" }
        if (available >= n) return true
        if (eof) return false
        if (start > 0) {
            System.arraycopy(buf, start, buf, 0, available)
            end -= start
            start = 0
        }
        while (available < n) {
            val r = input.read(buf, end, buf.size - end)
            // A read() returning 0 without having reached EOF (a real case with a Data Layer
            // Channel) made the loop spin forever (F-31): we treat it as an end of stream.
            if (r <= 0) {
                eof = true
                break
            }
            end += r
        }
        return available >= n
    }

    /** True if the first [length] bytes from the cursor equal the start of [magic]. */
    fun startsWith(magic: ByteArray, length: Int): Boolean {
        if (available < length) return false
        for (i in 0 until length) if (buf[start + i] != magic[i]) return false
        return true
    }

    fun copyOut(dst: ByteArray, from: Int, length: Int) {
        System.arraycopy(buf, start + from, dst, 0, length)
    }

    fun skip(n: Int) {
        start += n
        offset += n
    }

    /** Consumes everything left (stream exhausted). Returns the number of bytes abandoned. */
    fun drain(): Long {
        var total = 0L
        do {
            total += available
            skip(available)
        } while (ensure(1))
        return total
    }
}
