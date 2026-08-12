package com.pendulum.format.wire

/**
 * `/pendulum/session/<sessionHex>` — watch -> phone, `setUrgent()`.
 *
 * Posted on opening ([SessionState.OPEN]), rewritten on clean close. This is what lets the phone
 * know that a night **exists** before it has received its end: without this item, an interrupted
 * night is indistinguishable from a night that never took place.
 *
 * @param zoneId IANA identifier (`Europe/Paris`). It lives here and in the sidecar, not in the
 *   binary chunk header, which carries only the offset in minutes — see the KDoc of
 *   `ChunkFormat` for the justification.
 * @param plannedStopWallMs planned stop time, known from the opening: the phone can thereby
 *   tell "the night is not over" from "the watch is no longer answering".
 */
data class SessionHeader(
    val sessionHex: String,
    val startWallMs: Long,
    val tzOffsetMin: Int,
    val zoneId: String,
    val nominalRateHz: Int,
    val modeFlags: Int,
    val plannedStopWallMs: Long,
    val state: SessionState,
    val endWallMs: Long? = null,
    val totalChunks: Int? = null,
    val stopReason: StopReason? = null,
) {
    fun encode(): ByteArray = WireWriter(96)
        .u8(WireProtocol.VERSION)
        .str(sessionHex)
        .i64(startWallMs)
        .i16(tzOffsetMin)
        .str(zoneId)
        .i16(nominalRateHz)
        .i16(modeFlags)
        .i64(plannedStopWallMs)
        .u8(state.code)
        .optI64(endWallMs)
        .optI32(totalChunks)
        .optI32(stopReason?.code)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): SessionHeader {
            val r = WireReader(bytes)
            r.version("SessionHeader")
            return SessionHeader(
                sessionHex = r.str(),
                startWallMs = r.i64(),
                tzOffsetMin = r.i16().toShort().toInt(),
                zoneId = r.str(),
                nominalRateHz = r.u16(),
                modeFlags = r.u16(),
                plannedStopWallMs = r.i64(),
                state = SessionState.fromCode(r.u8()),
                endWallMs = r.optI64(),
                totalChunks = r.optI32(),
                stopReason = r.optI32()?.let { StopReason.fromCode(it) },
            )
        }
    }
}

/**
 * `/pendulum/chunk/<sessionHex>/<idx:05d>` — watch -> phone. Metadata accompanying the exact
 * bytes of the chunk file.
 *
 * The phone recomputes [crc32] on the received bytes before inserting anything: it is the only
 * check that covers transport, where the per-block CRC16 covers only the content. An index whose
 * CRC32 does not come out right goes into `needResend` of the ack.
 *
 * @param flagsOr `OR` of all the `flags` of the chunk's blocks: makes it possible to spot a chunk
 *   containing saturation or a hole without decoding it.
 */
data class ChunkMeta(
    val sessionHex: String,
    val idx: Int,
    val size: Int,
    val crc32: Long,
    val sampleCount: Int,
    val tFirstNs: Long,
    val tLastNs: Long,
    val flagsOr: Int,
) {
    fun encode(): ByteArray = WireWriter(64)
        .u8(WireProtocol.VERSION)
        .str(sessionHex)
        .i32(idx)
        .i32(size)
        .i64(crc32)
        .i32(sampleCount)
        .i64(tFirstNs)
        .i64(tLastNs)
        .i16(flagsOr)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ChunkMeta {
            val r = WireReader(bytes)
            r.version("ChunkMeta")
            return ChunkMeta(
                sessionHex = r.str(),
                idx = r.i32(),
                size = r.i32(),
                crc32 = r.i64(),
                sampleCount = r.i32(),
                tFirstNs = r.i64(),
                tLastNs = r.i64(),
                flagsOr = r.u16(),
            )
        }
    }
}

/**
 * `/pendulum/live/<sessionHex>` — watch -> phone, `setUrgent()`, **replaced** on every burst and
 * never accumulated: it is a state, not a log.
 *
 * It answers the real need behind "I want to see the movement chart": knowing that the recording
 * is alive, and seeing the shape of the signal, for ~1 KB every 5 min instead of the 300 B/s of
 * the raw signal.
 *
 * @param envU8 1 Hz RMS envelope quantised to logarithmic u8, [PreviewEnvelopeCodec.LENGTH]
 *   bytes = 15 min sliding. See [PreviewEnvelopeCodec].
 * @param syncBacklogged true when the ceiling of in-flight items is reached: the recording
 *   continues without degradation, only the transfer is behind. To display, not to alarm about.
 */
class LivePreview(
    val sessionHex: String,
    val lastUpdateMs: Long,
    val elapsedMs: Long,
    val samplesWritten: Long,
    val bytesWritten: Long,
    val batteryPct: Int,
    val gapCount: Int,
    val gapTotalMs: Long,
    val modeFlags: Int,
    val lastClosedChunkIdx: Int,
    val syncBacklogged: Boolean,
    val envU8: ByteArray,
) {
    init {
        require(envU8.size == PreviewEnvelopeCodec.LENGTH) {
            "envU8 must be ${PreviewEnvelopeCodec.LENGTH} bytes, got ${envU8.size}"
        }
        require(batteryPct in 0..100) { "batteryPct out of bounds: $batteryPct" }
    }

    fun encode(): ByteArray = WireWriter(PreviewEnvelopeCodec.LENGTH + 96)
        .u8(WireProtocol.VERSION)
        .str(sessionHex)
        .i64(lastUpdateMs)
        .i64(elapsedMs)
        .i64(samplesWritten)
        .i64(bytesWritten)
        .u8(batteryPct)
        .i32(gapCount)
        .i64(gapTotalMs)
        .i16(modeFlags)
        .i32(lastClosedChunkIdx)
        .bool(syncBacklogged)
        .blob(envU8)
        .toByteArray()

    // Manual equals/hashCode: ByteArray has reference identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LivePreview) return false
        return sessionHex == other.sessionHex &&
            lastUpdateMs == other.lastUpdateMs &&
            elapsedMs == other.elapsedMs &&
            samplesWritten == other.samplesWritten &&
            bytesWritten == other.bytesWritten &&
            batteryPct == other.batteryPct &&
            gapCount == other.gapCount &&
            gapTotalMs == other.gapTotalMs &&
            modeFlags == other.modeFlags &&
            lastClosedChunkIdx == other.lastClosedChunkIdx &&
            syncBacklogged == other.syncBacklogged &&
            envU8.contentEquals(other.envU8)
    }

    override fun hashCode(): Int {
        var result = sessionHex.hashCode()
        result = 31 * result + lastUpdateMs.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + samplesWritten.hashCode()
        result = 31 * result + bytesWritten.hashCode()
        result = 31 * result + batteryPct
        result = 31 * result + gapCount
        result = 31 * result + gapTotalMs.hashCode()
        result = 31 * result + modeFlags
        result = 31 * result + lastClosedChunkIdx
        result = 31 * result + syncBacklogged.hashCode()
        result = 31 * result + envU8.contentHashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): LivePreview {
            val r = WireReader(bytes)
            r.version("LivePreview")
            return LivePreview(
                sessionHex = r.str(),
                lastUpdateMs = r.i64(),
                elapsedMs = r.i64(),
                samplesWritten = r.i64(),
                bytesWritten = r.i64(),
                batteryPct = r.u8(),
                gapCount = r.i32(),
                gapTotalMs = r.i64(),
                modeFlags = r.u16(),
                lastClosedChunkIdx = r.i32(),
                syncBacklogged = r.bool(),
                envU8 = r.blob(),
            )
        }
    }
}

/**
 * `/pendulum/ack/<sessionHex>` — phone -> watch, `setUrgent()`, rewritten on every ingestion.
 *
 * **The ack is a `DataItem`, not a message**: a message sent while the watch is out of range
 * would be lost and the watch would keep its files forever. Here the ack is a *convergent state*
 * — reading it ten times gives the same result as reading it once, and the idempotence of the
 * protocol is obtained without a counter.
 *
 * @param ackedUpTo every strictly lower index is acknowledged and can be erased.
 * @param ackedBitmap covers `[bitmapBase, bitmapBase + 8 * size)`, least significant bit first.
 * @param needResend indices received but whose **CRC32 is invalid**: to be deleted then re-posted,
 *   an identical `putDataItem` being deduplicated and triggering nothing.
 */
class Ack(
    val sessionHex: String,
    val ackedUpTo: Int,
    val bitmapBase: Int,
    val ackedBitmap: ByteArray,
    val needResend: IntArray,
    val phoneMs: Long,
) {
    /** True if chunk [idx] is acknowledged, hence deletable from the watch's disk. */
    fun isAcked(idx: Int): Boolean {
        if (idx < ackedUpTo) return true
        val bit = idx - bitmapBase
        if (bit < 0) return false
        val byte = bit / 8
        if (byte >= ackedBitmap.size) return false
        return (ackedBitmap[byte].toInt() shr (bit % 8)) and 1 == 1
    }

    fun encode(): ByteArray = WireWriter(64 + ackedBitmap.size)
        .u8(WireProtocol.VERSION)
        .str(sessionHex)
        .i32(ackedUpTo)
        .i32(bitmapBase)
        .blob(ackedBitmap)
        .ints(needResend)
        .i64(phoneMs)
        .toByteArray()

    // Manual equals/hashCode: ByteArray and IntArray have reference identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ack) return false
        return sessionHex == other.sessionHex &&
            ackedUpTo == other.ackedUpTo &&
            bitmapBase == other.bitmapBase &&
            ackedBitmap.contentEquals(other.ackedBitmap) &&
            needResend.contentEquals(other.needResend) &&
            phoneMs == other.phoneMs
    }

    override fun hashCode(): Int {
        var result = sessionHex.hashCode()
        result = 31 * result + ackedUpTo
        result = 31 * result + bitmapBase
        result = 31 * result + ackedBitmap.contentHashCode()
        result = 31 * result + needResend.contentHashCode()
        result = 31 * result + phoneMs.hashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): Ack {
            val r = WireReader(bytes)
            r.version("Ack")
            return Ack(
                sessionHex = r.str(),
                ackedUpTo = r.i32(),
                bitmapBase = r.i32(),
                ackedBitmap = r.blob(),
                needResend = r.ints(),
                phoneMs = r.i64(),
            )
        }
    }
}
