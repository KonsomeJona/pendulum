package com.pendulum.format

/**
 * Binary format of the Pendulum accelerometer recording chunks.
 *
 * Design constraints:
 *  - **append-only**: we never write backwards, never patch the header on close. A session
 *    killed abruptly (OOM, reboot, battery) leaves a usable file.
 *  - **self-delimited, CRC-protected blocks**: a corrupt block is skipped on reading, never the
 *    whole night. A single 8 h file without delimiters would be an unacceptable single point of
 *    failure.
 *  - **no per-sample timestamp**: the hardware FIFO samples uniformly, so (tFirstNs, tLastNs)
 *    per block + linear interpolation are enough, and 8 B per sample are saved (that is, ~11 MB
 *    per night). That saving is only legitimate if a block never straddles two FIFO flushes:
 *    this is a contract enforced on writing ([ChunkWriter.writeBlock]) and re-checked on reading.
 *
 * ### File header — 80 bytes, little-endian
 * ```
 * off  size    field                    type
 *   0       8  FILE_MAGIC "PENDCHNK"    ascii
 *   8       2  formatVersion            u16
 *  10       2  headerSize               u16   total header size (80 in v1)
 *  12       2  nominalRateHz            u16
 *  14       2  fifoMaxEventCount        u16
 *  16      16  sessionUuid              bytes
 *  32       8  startWallMs              i64
 *  40       8  startElapsedRealtimeNs   i64
 *  48       8  firstEventTimestampNs    i64
 *  56       4  sensorResolution         f32
 *  60       4  sensorMaxRange           f32
 *  64       4  chunkIndex               i32
 *  68       2  modeFlags                u16
 *  70       2  tzOffsetMin              i16   local UTC offset at opening, in minutes
 *  72       6  reserved, zero
 *  78       2  headerCrc                u16 = crc16(header[0, headerSize - 2))
 * ```
 * **Format evolution rule** (F-32): the CRC *always* occupies the last two bytes of the header,
 * and `headerSize` gives its length. An old reader therefore reads back a file produced by a
 * newer writer that has only *appended* fields at the tail: it reads what it knows at fixed
 * offsets, checks the CRC at `headerSize - 2` and ignores the rest. `formatVersion` is
 * incremented only for an *incompatible* change (block layout, quantisation, semantics of an
 * existing field).
 *
 * **Time zone** (F-09): an IANA identifier (`Europe/Paris`, up to 32 bytes) does not fit in the
 * header without blowing it up, and storing it per block would be absurd. The choice made is
 * therefore to put here the only field the binary read-back needs — the **UTC offset in minutes**
 * (i16, covers -18:00..+18:00) — and to leave the full IANA identifier to the JSON sidecar and to
 * [com.pendulum.format.wire.SessionHeader.zoneId], which are not size-constrained. Consequence to
 * be enforced elsewhere: **no duration is ever computed as a wall-clock difference**, the offset
 * serves only to display a local time.
 *
 * ### Block header — 32 bytes, little-endian
 * ```
 * off  size    field
 *   0       4  BLOCK_MAGIC "BLK!"
 *   4       2  count       u16   number of samples
 *   6       8  tFirstNs    i64
 *  14       8  tLastNs     i64
 *  22       2  flags       u16
 *  24       2  crc         u16 = crc16(header[0, 24) then payload)
 *  26       6  reserved, zero
 * ```
 * The CRC covers the block header **and** the payload (F-02): the six bytes of a sample are
 * locally redundant and a corruption there is benign, whereas `count`, `tFirstNs` and `tLastNs`
 * are unique — corrupting them shifts the whole time base of the night, or makes a wrong payload
 * length be read. The `crc` field is written last, which allows it to be computed in one pass
 * over the first 24 bytes then over the payload.
 *
 * ### Telemetry block — 16-byte header, then `count` points of [TELEMETRY_POINT_SIZE]
 * ```
 * off  size    field
 *   0       4  TELEMETRY_MAGIC "TLM!"
 *   4       2  count       u16   number of points
 *   6       2  pointSize   u16   size of one point, in bytes
 *   8       2  flags       u16   reserved, zero
 *  10       2  crc         u16 = crc16(header[0, 10) then payload)
 *  12       4  reserved, zero
 * ```
 * **Why telemetry travels inside the chunks, and not on a channel of its own.**
 *
 *  1. It inherits all the durability already built **and already verified on real hardware**:
 *     append-only writing, per-block CRC-16, transport CRC-32, push every 15 min, ack, deletion
 *     only after ack. There is nothing new to make reliable.
 *  2. A second transport path would be a second failure mode. The constant lesson of this
 *     repository is that **the Data Layer fails by silence** — the missing `BIND_WEARABLE_LISTENER`
 *     permission, the `start-request` that nobody emitted, the night key that lived on one side
 *     only. Each extra path is one more opportunity to fail without saying anything.
 *  3. The throughput is negligible: one point per minute against fifty samples per second, that
 *     is, ~23 KB against ~9 MB over an eight-hour night. No size trade-off is displaced — and
 *     **least of all** [com.pendulum.format.wire.WireProtocol.CHUNK_ROTATION_BYTES], which
 *     remains the hard guard rail of the 100 KB ceiling of a `DataItem`.
 *
 * `pointSize` carries for the point the same evolution rule as `headerSize` for the file header:
 * a newer writer may **append fields at the tail of the point**, an old reader reads what it
 * knows at fixed offsets and skips the rest. A `pointSize` **smaller** than
 * [TELEMETRY_POINT_SIZE] is on the other hand rejected — there would not be enough to fill the
 * fields.
 *
 * ### End-of-file marker — 32 bytes
 * ```
 * off  size    field
 *   0       8  FILE_FOOTER_MAGIC "ENDPEND!"
 *   8       4  blockCount        u32
 *  12       8  sampleCount       i64
 *  20       8  lastTimestampNs   i64
 *  28       2  telemetryCount    u16   number of telemetry points in the chunk
 *  30       2  crc               u16 = crc16(footer[0, 30))
 * ```
 * Without this marker (F-37), nothing distinguishes a complete chunk from a chunk still being
 * written, and the phone may acknowledge — hence cause the deletion of — a partial file (F-13).
 * The redundant counters make it possible, as a bonus, to quantify what was lost on read-back.
 * `telemetryCount` occupies the two bytes that v1 left at zero: a v1 chunk read back by this
 * version therefore announces zero telemetry points, which is exactly the truth.
 */
object ChunkFormat {

    /** File header magic. 8 ASCII bytes. */
    val FILE_MAGIC = "PENDCHNK".toByteArray(Charsets.US_ASCII)

    /** End-of-file marker magic. 8 ASCII bytes, distinct from [BLOCK_MAGIC]. */
    val FILE_FOOTER_MAGIC = "ENDPEND!".toByteArray(Charsets.US_ASCII)

    /** Block start magic. 4 ASCII bytes. */
    val BLOCK_MAGIC = "BLK!".toByteArray(Charsets.US_ASCII)

    /** Telemetry block start magic. 4 ASCII bytes, distinct from [BLOCK_MAGIC]. */
    val TELEMETRY_MAGIC = "TLM!".toByteArray(Charsets.US_ASCII)

    /**
     * Format version. To be incremented on every incompatible layout change.
     *
     * **v2 — the telemetry block.** The addition is additive on writing, but it is *incompatible
     * on reading*, and that is why the version moves: a v1 reader does not know `TLM!`, counts it
     * as a missing magic, resynchronises on the next signal block, and returns
     * `desynchronised = true`. It would therefore announce a **damaged** file where the file is
     * perfectly sound — exactly the kind of false red that sends people hunting for a failure
     * that does not exist. The version announces it before that happens.
     *
     * The reverse direction, on the other hand, is guaranteed without reservation: a v1 chunk
     * still decodes with this version (`ChunkCodecTest.a v1 format chunk still decodes`), because
     * the repository never deletes raw data — that is the only thing that will make it possible to
     * rescore on the day the algorithm changes.
     */
    const val FORMAT_VERSION = 2

    /** Size of the file header written by this version, in bytes. Multiple of 16. */
    const val HEADER_SIZE = 80

    /**
     * Minimal prefix to read before `headerSize` is known: magic + version + headerSize.
     * Every reader starts there, whatever the version of the file.
     */
    const val HEADER_PREFIX_SIZE = 12

    /** Size of the end-of-file marker, in bytes. */
    const val FOOTER_SIZE = 32

    /** Size of the block header, in bytes. Multiple of 32. */
    const val BLOCK_HEADER_SIZE = 32

    /** Offset of the `crc` field in the block header: everything before it is covered by it. */
    const val BLOCK_CRC_OFFSET = 24

    /** Number of bytes per sample: 3 axes x i16. */
    const val BYTES_PER_SAMPLE = 6

    /** Maximum number of samples per block. */
    const val MAX_SAMPLES_PER_BLOCK = 512

    /** Size of a telemetry block header, in bytes. */
    const val TELEMETRY_HEADER_SIZE = 16

    /** Offset of the `crc` field in the telemetry header: everything before it is covered by it. */
    const val TELEMETRY_CRC_OFFSET = 10

    /** Size of a telemetry point written by this version, in bytes. Multiple of 8. */
    const val TELEMETRY_POINT_SIZE = 48

    /**
     * Maximum number of points in a telemetry block. A corruption guard rail, like
     * [MAX_SAMPLES_PER_BLOCK]: it is what prevents a corrupt `count` from making an aberrant
     * payload length be read. It is chosen so that a full telemetry block stays smaller than a
     * full signal block, and therefore so that the reader's window — sized on the largest block —
     * does not have to grow.
     */
    const val MAX_TELEMETRY_POINTS = 64

    /**
     * Quantisation: 1 LSB = 1/2048 g. An i16 therefore covers -16 g to +15.9995 g
     * (32767/2048, one LSB is missing on the positive side), well beyond what an ankle
     * produces, and the resolution (0.00049 g) stays far below the noise floor of a MEMS
     * accelerometer.
     */
    const val LSB_PER_G = 2048.0

    /** Standard acceleration of gravity, in m/s^2 (exact SI value). */
    const val G_IN_MS2 = 9.80665

    /**
     * Tolerated relative deviation between the implicit rate of a block — `(tLast - tFirst)/(N-1)`
     * — and the nominal period `1e9/fs`. Beyond it, the block most likely straddles two FIFO
     * flushes and the linear interpolation of [DecodedBlock.timestampNs] dates *all* of its
     * samples wrong (F-03). Refused on writing, reported on reading.
     */
    const val TIMEBASE_TOLERANCE = 0.20

    // --- Block flags (u16 `flags` field) ---

    /** This block starts on a hardware FIFO flush boundary. */
    const val FLAG_FIFO_BOUNDARY = 1 shl 0

    /** A hole is suspected just before this block (abnormal timestamp gap, or restart after crash). */
    const val FLAG_GAP_BEFORE = 1 shl 1

    /** The off-body sensor was reporting "not worn" during this block. */
    const val FLAG_OFF_BODY = 1 shl 2

    /**
     * At least one sample of this block saturated at +/-32767 LSB (F-11). Set by the writer:
     * without it, clipping would be undetectable on read-back, and the "clipping" distractor
     * of the verification phase would pass for real movement.
     */
    const val FLAG_SATURATED = 1 shl 3

    /**
     * At least one sample of this block was NaN or infinite and was replaced by 0 (F-11),
     * which is indistinguishable from free fall. This flag is the only trace of the replacement.
     */
    const val FLAG_NON_FINITE = 1 shl 4

    /**
     * At least one sample of this block touched the **sensor range** — `sensorMaxRange` from the
     * header, typically 78.45 m/s2 (8 g) or 39.23 (4 g).
     *
     * Distinct from [FLAG_SATURATED], which marks the ceiling of the **format** at 16 g. The two
     * do not overlap: a sensor at 8 g clips at half of what the format can encode, so a movement
     * can be clipped by the hardware without ever coming near [FLAG_SATURATED] — and the clipping
     * was then completely invisible on read-back.
     *
     * What this flag explains: past the rail, the envelope of the movement is artificially
     * **flat at the top**. The artefact looks like a genuine plateau, it underestimates the
     * amplitude, and it is the amplitude that decides the detection threshold. A movement kept or
     * rejected on a block that carries this flag was not decided on the signal, it was decided on
     * its clipping.
     */
    const val FLAG_SENSOR_CLIPPED = 1 shl 5

    // --- Acquisition mode flags (u16 `modeFlags` field of the header) ---

    /** The sensor in use is the wake-up variant. */
    const val MODE_WAKEUP_SENSOR = 1 shl 0

    /** Hardware batching is requested (maxReportLatencyUs > 0). */
    const val MODE_BATCHED = 1 shl 1

    /** A PARTIAL_WAKE_LOCK is held during acquisition. */
    const val MODE_WAKE_LOCK = 1 shl 2

    /** The wake lock was taken along the way by auto-degradation (holes were detected). */
    const val MODE_DEGRADED = 1 shl 3

    /**
     * Converts an acceleration in m/s^2 to the quantised integer of the format, with saturation.
     *
     * `Math.round(Double)` returns a `Long`: the bounds must be `Long`s, failing which no
     * overload of `coerceIn` applies and the module does not compile.
     * A non-finite value (faulty sensor) is brought back to 0, which is indistinguishable
     * from free fall: the caller must count those cases and set a flag.
     */
    fun toRaw(ms2: Float): Short {
        if (!ms2.isFinite()) return 0
        val lsb = Math.round(ms2 / G_IN_MS2 * LSB_PER_G)
        return lsb.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
    }

    /** Converts a quantised integer of the format to an acceleration in m/s^2. */
    fun toMs2(raw: Short): Float = (raw / LSB_PER_G * G_IN_MS2).toFloat()

    /**
     * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection, no xorout).
     * Chosen for the simplicity of a table-free implementation and its negligible cost:
     * detecting a corrupt block matters more than the strength of the code.
     *
     * @param seed initial state, to chain the computation over several arrays (block header
     *   then payload) without concatenating them in memory.
     */
    fun crc16(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
        seed: Int = 0xFFFF,
    ): Int {
        var crc = seed and 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /**
     * Implicit rate of a block, in nanoseconds per sample. `NaN` if the block has only one
     * sample (no observable interval, hence nothing to check).
     */
    fun meanIntervalNs(count: Int, tFirstNs: Long, tLastNs: Long): Double =
        if (count <= 1) Double.NaN else (tLastNs - tFirstNs).toDouble() / (count - 1)

    /**
     * True if the implicit rate of the block is compatible with `nominalRateHz` to within
     * [TIMEBASE_TOLERANCE]. A block with a single sample is always accepted.
     */
    fun isTimebasePlausible(count: Int, tFirstNs: Long, tLastNs: Long, nominalRateHz: Int): Boolean {
        val mean = meanIntervalNs(count, tFirstNs, tLastNs)
        if (mean.isNaN()) return true
        if (nominalRateHz <= 0) return true
        val expected = 1e9 / nominalRateHz
        return Math.abs(mean - expected) / expected <= TIMEBASE_TOLERANCE
    }
}

/**
 * Metadata of a chunk file. All these values are fixed at the opening of the file and never
 * rewritten.
 *
 * @param sessionUuid identifier of the night (16 bytes), common to every chunk of a session.
 * @param chunkIndex index of the chunk in the session, increasing from 0.
 * @param nominalRateHz frequency *requested* from the sensor. The real frequency is recomputed
 *   at analysis time from the timestamps: it systematically deviates from it (50 -> 50.3 or
 *   52.6 Hz) and a wrong fs shifts the filters and the movement durations.
 * @param startWallMs wall clock (epoch ms) at the opening of the chunk.
 * @param startElapsedRealtimeNs `SystemClock.elapsedRealtimeNanos()` at the opening of the chunk.
 * @param firstEventTimestampNs `SensorEvent.timestamp` of the first sample of the chunk.
 *   The triplet of the three clocks makes drift detectable: `SensorEvent.timestamp`
 *   is not guaranteed equal to `elapsedRealtimeNanos` (some OEMs exclude suspend time),
 *   and without that detection the merge with the hypnogram shifts by several minutes.
 * @param tzOffsetMin local UTC offset in minutes at the opening of the chunk. No default:
 *   forgetting the time zone is precisely the defect being fixed, it must cost a decision.
 *   The IANA identifier lives in the sidecar (see the KDoc of [ChunkFormat]).
 * @param headerSize header size as it was read. Equals [ChunkFormat.HEADER_SIZE] for a file
 *   written by this version; may be larger for a file produced by a more recent version that
 *   appended fields at the tail.
 */
data class ChunkHeader(
    val sessionUuid: ByteArray,
    val chunkIndex: Int,
    val nominalRateHz: Int,
    val startWallMs: Long,
    val startElapsedRealtimeNs: Long,
    val firstEventTimestampNs: Long,
    val sensorResolution: Float,
    val sensorMaxRange: Float,
    val fifoMaxEventCount: Int,
    val modeFlags: Int,
    val tzOffsetMin: Int,
    val formatVersion: Int = ChunkFormat.FORMAT_VERSION,
    val headerSize: Int = ChunkFormat.HEADER_SIZE,
) {
    init {
        require(sessionUuid.size == 16) { "sessionUuid must be 16 bytes, got ${sessionUuid.size}" }
        // The u16 fields were truncating silently: a FIFO of 70000 events was recorded
        // as 4464, and the latency budget computed on it was wrong by a factor of 15 (F-26).
        require(nominalRateHz in 1..0xFFFF) { "nominalRateHz outside u16: $nominalRateHz" }
        require(fifoMaxEventCount in 0..0xFFFF) { "fifoMaxEventCount outside u16: $fifoMaxEventCount" }
        require(modeFlags in 0..0xFFFF) { "modeFlags outside u16: $modeFlags" }
        require(chunkIndex >= 0) { "negative chunkIndex: $chunkIndex" }
        // -18:00..+18:00: the range actually covered by the IANA database, history included.
        require(tzOffsetMin in -1080..1080) { "tzOffsetMin out of range: $tzOffsetMin" }
        require(headerSize >= ChunkFormat.HEADER_SIZE) { "headerSize too small: $headerSize" }
    }

    // Manual equals/hashCode: ByteArray has reference identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkHeader) return false
        return sessionUuid.contentEquals(other.sessionUuid) &&
            chunkIndex == other.chunkIndex &&
            nominalRateHz == other.nominalRateHz &&
            startWallMs == other.startWallMs &&
            startElapsedRealtimeNs == other.startElapsedRealtimeNs &&
            firstEventTimestampNs == other.firstEventTimestampNs &&
            sensorResolution == other.sensorResolution &&
            sensorMaxRange == other.sensorMaxRange &&
            fifoMaxEventCount == other.fifoMaxEventCount &&
            modeFlags == other.modeFlags &&
            tzOffsetMin == other.tzOffsetMin &&
            formatVersion == other.formatVersion &&
            headerSize == other.headerSize
    }

    override fun hashCode(): Int {
        var result = sessionUuid.contentHashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + nominalRateHz
        result = 31 * result + startWallMs.hashCode()
        result = 31 * result + startElapsedRealtimeNs.hashCode()
        result = 31 * result + firstEventTimestampNs.hashCode()
        result = 31 * result + sensorResolution.hashCode()
        result = 31 * result + sensorMaxRange.hashCode()
        result = 31 * result + fifoMaxEventCount
        result = 31 * result + modeFlags
        result = 31 * result + tzOffsetMin
        result = 31 * result + formatVersion
        result = 31 * result + headerSize
        return result
    }
}

/**
 * A decoded block. The samples are in m/s^2, already dequantised.
 *
 * @param tFirstNs timestamp of the first sample (`SensorEvent.timestamp` scale).
 * @param tLastNs timestamp of the last sample. Equal to `tFirstNs` if the block has one sample.
 * @param suspectTimebase set by the reader when the implicit rate of the block deviates by more
 *   than [ChunkFormat.TIMEBASE_TOLERANCE] from the nominal one: the interpolated timestamps are
 *   then wrong by an unknown amount and the block must not be used to date an event.
 */
class DecodedBlock(
    val tFirstNs: Long,
    val tLastNs: Long,
    val flags: Int,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val suspectTimebase: Boolean = false,
) {
    val sampleCount: Int get() = x.size

    /**
     * Interpolated timestamp of the i-th sample. Valid because the hardware FIFO samples at a
     * uniform rate between two flushes — hence the prohibition placed on the writer against
     * letting a block straddle two flushes.
     */
    fun timestampNs(i: Int): Long {
        if (sampleCount <= 1) return tFirstNs
        val step = (tLastNs - tFirstNs).toDouble() / (sampleCount - 1)
        return tFirstNs + Math.round(step * i)
    }
}

/**
 * The state of the device at one instant of the night — 48 bytes, little-endian.
 *
 * Two uses, and every field serves at least one of the two:
 *
 *  1. **Making the P1 gate self-sufficient.** Its second criterion is "battery > 20 % remaining
 *     at eight hours". The watch already held a complete battery series that it did not publish:
 *     the standby measurement of 3 August 2026 (`docs/workings/BENCH-LOG.md` §12.4) was therefore
 *     unable to quantify the battery at all, and the only way considered to quantify it was to
 *     keep an ADB link during the night — that is, to leave the watch on its dock, which falsifies
 *     precisely the quantity being measured.
 *  2. **Explaining why a movement was kept or not.** Each of these quantities decides the
 *     detection threshold or the dating, and none was visible on the phone side.
 *
 * ### Layout — 48 bytes
 * ```
 * off  size    field                 type
 *   0       8  elapsedRealtimeNs     i64
 *   8       8  sensorTsNs            i64
 *  16       4  batteryChargeUah      i32
 *  20       4  maxIntervalUs         u32
 *  24       4  fsyncTotalUs          u32
 *  28       4  fsyncMaxUs            u32
 *  32       2  temperatureDeciC      i16
 *  34       2  measuredRateCentiHz   u16
 *  36       2  jitterStdUs           u16
 *  38       2  clippedSamples        u16
 *  40       2  fsyncCount            u16
 *  42       1  batteryPct            u8
 *  43       1  offBody               u8
 *  44       1  charging              u8
 *  45       3  reserved, zero
 * ```
 *
 * **Counter convention**: `fsyncCount`, `fsyncTotalUs`, `fsyncMaxUs` and `clippedSamples` count
 * **since the previous point**, not since the start of the night. The point carries its own
 * dating, so a lost point costs one minute of attribution and nothing more; cumulative counters
 * would have required the phone to differentiate a series without knowing whether it has holes.
 *
 * @param elapsedRealtimeNs `SystemClock.elapsedRealtimeNanos()` at the moment of the point. It is
 *   the only clock on which a duration is computed — the wall clock jumps at a daylight-saving
 *   change and at NTP resynchronisation —, and the chunk header already carries what is needed to
 *   bring it back to a wall-clock time (`startWallMs` / `startElapsedRealtimeNs`). Hence the
 *   absence of a wall-clock field.
 * @param sensorTsNs last `SensorEvent.timestamp` seen at the moment of the point, or 0 if none.
 *   It is what anchors the telemetry on the **time base of the samples**, the only one that dates
 *   movements: without it, aligning "the temperature dropped" with "this movement was rejected"
 *   would go through a clock conversion that §12.4 shows to drift.
 * @param batteryChargeUah `BATTERY_PROPERTY_CHARGE_COUNTER`, in microampere-hours, or
 *   [CHARGE_UNKNOWN]. This is **the** quantity that makes the battery criterion of P1 measurable
 *   over a short night: the percentage is quantised to the point that half an hour of standby
 *   leaves it at 100 %, whereas the coulomb counter gives a slope, and a slope extrapolates to
 *   eight hours. The instantaneous mean current is **not** collected: it is the derivative of two
 *   consecutive points of this counter, hence redundant.
 * @param maxIntervalUs worst interval between two consecutive samples over the last closed
 *   measurement window. The mean hides it: it is the worst case that bounds the dating error of a
 *   movement, since the format interpolates linearly between `tFirstNs` and `tLastNs`.
 * @param fsyncTotalUs cumulative time spent in `fsync` since the previous point, in
 *   microseconds. An `fsync` briefly freezes the processor; this is the freeze budget of the
 *   period.
 * @param fsyncMaxUs worst `fsync` of the period. It is that one, and not the total, that explains
 *   a missed sensor interrupt at a precise instant.
 * @param temperatureDeciC battery temperature in tenths of a degree Celsius, or
 *   [TEMPERATURE_UNKNOWN]. **Cross-validator of the off-body signal**: a sharp drop is the loss of
 *   thermal coupling with the skin, hence the watch removed. More reliable than the off-body
 *   detector alone, of which the KDoc of `RecordingService` says that at the ankle it very
 *   probably reads "not worn" permanently.
 * @param measuredRateCentiHz `fs` actually delivered over the last closed window, in hundredths
 *   of a hertz (50 Hz -> 5000). Already computed by `GapMonitor` and until now never transmitted
 *   point by point: only its last value went out, in the sidecar.
 * @param jitterStdUs standard deviation of the inter-sample intervals over the last closed
 *   window, in microseconds, saturated at 65 535. **It is the dispersion, not the mean, that
 *   decides the dating**: a perfect mean rate obtained by alternating 10 and 30 ms dates every
 *   sample to within 10 ms, and the hole monitor saw nothing of it.
 * @param clippedSamples samples that touched the sensor range since the previous point. See
 *   [ChunkFormat.FLAG_SENSOR_CLIPPED] for what clipping falsifies; the flag localises the artefact
 *   to the block, this counter gives its volume.
 * @param batteryPct 0..100, or [BATTERY_UNKNOWN].
 * @param offBody [OFF_BODY_WORN], [OFF_BODY_REMOVED] or [OFF_BODY_ABSENT]. Read by
 *   `RecordingService` from `TYPE_LOW_LATENCY_OFFBODY_DETECT` since forever, **logged and never
 *   transmitted**.
 * @param charging true if the charger is connected. A point while charging says nothing about
 *   battery life and must be taken out of any slope regression — this is exactly the case of
 *   §12.4, where the watch stayed on its dock.
 *
 * **Excluded, and why.** `modeFlags` and the degradation tier: they live in the chunk header, and
 * any mode change **forces a rotation** (`RecordingService.applyDegradation`), so the file header
 * already describes exactly all of its blocks and all of its points — repeating them per point
 * would be a second source of truth for nothing. Free disk space: it decides a stop
 * (`StopConditions`), it explains no movement. The system thermal state
 * (`PowerManager.getCurrentThermalStatus`): an accelerometer at 50 Hz does not throttle a watch,
 * and there would be nothing to do with it.
 */
data class TelemetryPoint(
    val elapsedRealtimeNs: Long,
    val sensorTsNs: Long,
    val batteryChargeUah: Int,
    val maxIntervalUs: Long,
    val fsyncTotalUs: Long,
    val fsyncMaxUs: Long,
    val temperatureDeciC: Int,
    val measuredRateCentiHz: Int,
    val jitterStdUs: Int,
    val clippedSamples: Int,
    val fsyncCount: Int,
    val batteryPct: Int,
    val offBody: Int,
    val charging: Boolean,
) {
    init {
        // Same discipline as `ChunkHeader` (F-26): a silent `.toShort()` recorded a FIFO of
        // 70 000 events as 4 464, and everything budgeted on it was wrong.
        // An overflow is refused here; it is up to the caller to saturate explicitly, with
        // [clampU16] and [clampU32], because saturating is a decision.
        require(maxIntervalUs in 0..0xFFFFFFFFL) { "maxIntervalUs outside u32: $maxIntervalUs" }
        require(fsyncTotalUs in 0..0xFFFFFFFFL) { "fsyncTotalUs outside u32: $fsyncTotalUs" }
        require(fsyncMaxUs in 0..0xFFFFFFFFL) { "fsyncMaxUs outside u32: $fsyncMaxUs" }
        require(temperatureDeciC in -32768..32767) { "temperatureDeciC outside i16: $temperatureDeciC" }
        require(measuredRateCentiHz in 0..0xFFFF) { "measuredRateCentiHz outside u16: $measuredRateCentiHz" }
        require(jitterStdUs in 0..0xFFFF) { "jitterStdUs outside u16: $jitterStdUs" }
        require(clippedSamples in 0..0xFFFF) { "clippedSamples outside u16: $clippedSamples" }
        require(fsyncCount in 0..0xFFFF) { "fsyncCount outside u16: $fsyncCount" }
        // **Width** bounds only, and no range of values. Any value decodable from a u8 must be
        // able to build a point: without that, a corrupt byte whose CRC happened to come out
        // right — or a more recent writer having added a sentinel — would make the reader throw,
        // whose contract is to throw only on an invalid file header. A `batteryPct` outside
        // 0..100 and different from [BATTERY_UNKNOWN] is a reading not to be believed, not a
        // reason to lose the rest of the chunk.
        require(batteryPct in 0..0xFF) { "batteryPct outside u8: $batteryPct" }
        require(offBody in 0..0xFF) { "offBody outside u8: $offBody" }
    }

    companion object {

        /** No battery reading succeeded. Distinct from 0 %, which is a real value. */
        const val BATTERY_UNKNOWN = 255

        /** `BATTERY_PROPERTY_CHARGE_COUNTER` not supported by the device. */
        const val CHARGE_UNKNOWN = Int.MIN_VALUE

        /** No readable temperature. Equals -3276.8 degrees, so never confusable with a measurement. */
        const val TEMPERATURE_UNKNOWN = -32768

        const val OFF_BODY_WORN = 0
        const val OFF_BODY_REMOVED = 1

        /** The device has no `TYPE_LOW_LATENCY_OFFBODY_DETECT`. */
        const val OFF_BODY_ABSENT = 255

        /**
         * Explicit saturation to a u16. A value that overflows is **capped**, not truncated: a
         * standard deviation of 80 ms truncated would come out at 14 464 us, that is, at a
         * healthy rate, and the defect would read as its opposite.
         */
        fun clampU16(v: Long): Int = v.coerceIn(0L, 0xFFFFL).toInt()

        /** See [clampU16]. */
        fun clampU32(v: Long): Long = v.coerceIn(0L, 0xFFFFFFFFL)
    }
}
