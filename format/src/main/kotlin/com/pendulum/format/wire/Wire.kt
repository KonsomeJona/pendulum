package com.pendulum.format.wire

import java.io.IOException

/**
 * Wire structures of the watch -> phone transfer (CAPTURE-ARCHITECTURE.md §2.3).
 *
 * They live in `:format` — pure JVM, zero Android dependency — for three reasons: both ends of
 * the protocol share the same encoding code, the encode/decode symmetry is testable without a
 * device, and the `phone` module can read back a `DataItem` without pulling in the `wear` module.
 *
 * The encoding is a version byte followed by fixed-size little-endian fields, strings and arrays
 * being prefixed with their length as u16. It is more verbose than a `DataMap`, and that is the
 * point: a `DataMap` is a dictionary whose reading fails silently when a key is renamed, whereas
 * here a layout change is rejected at the first byte.
 */
object WireProtocol {

    /** Version of the wire structures. Independent of `ChunkFormat.FORMAT_VERSION`. */
    const val VERSION = 1

    /**
     * Chunk rotation: the current chunk is closed as soon as `elapsed >= 300 s`
     * **or** `bytesWritten >= 92 160`. Both conditions exist because neither is sufficient:
     * the duration bounds the loss should the watch die, the byte ceiling guarantees that
     * the chunk stays under the 100 KB of a `DataItem` even if the real `fs` drifts to 52.6 Hz
     * or a degraded mode changes the rate.
     */
    const val CHUNK_ROTATION_MS = 300_000L

    /** 90 KiB. See [CHUNK_ROTATION_MS]: this is the hard guard rail of the two. */
    const val CHUNK_ROTATION_BYTES = 92_160L

    /**
     * Nominal rate of the night telemetry
     * ([com.pendulum.format.TelemetryPoint]): one point per minute.
     *
     * **It divides [CHUNK_ROTATION_MS], and that is not a setting.** A chunk is the unit of loss
     * of the protocol; if the telemetry rate were an independent clock, a lost chunk would take
     * with it a telemetry hole that no other chunk would fill, and the hole would not even be
     * countable. By dividing the rotation, every complete chunk carries at least one point — five,
     * in real operation — and the phone can state "a point is missing" rather than observe a
     * silence. `WireCodecTest` locks the divisibility down.
     *
     * On the watch side this rate is **not** a new timer: it is that of
     * `RecordingService.minuteTick`, the branch that already reads the battery once a minute. See
     * the KDoc of that tick for why it wakes nothing up.
     */
    const val TELEMETRY_PERIOD_MS = 60_000L

    /** Documented ceiling of a `DataItem` payload. */
    const val MAX_DATA_ITEM_BYTES = 100 * 1024
}

/** Namespace of the `DataItem`s and channels. Paths are derived from the session UUID. */
object WirePaths {

    const val SESSION_PREFIX = "/pendulum/session/"
    const val CHUNK_PREFIX = "/pendulum/chunk/"
    const val LIVE_PREFIX = "/pendulum/live/"
    const val ACK_PREFIX = "/pendulum/ack/"
    const val SWEEP_PREFIX = "/pendulum/sweep/"
    const val SWEEP_REQUEST = "/pendulum/sweep-request"

    /**
     * The sealed evening context, published by the **phone** and read by the watch.
     *
     * It is the only gate of the product: `Preflight` refuses to start as long as the item does
     * not exist. It lived on the watch side only, in `DataLayerTransfer`, and the phone never
     * wrote it — so START was permanently blocked, with as its only symptom the message
     * "fill in the evening form on the phone" in front of a form that did not exist.
     *
     * It is here, in the shared module, for the reason that holds for every other path:
     * **the failure mode of the Data Layer is silence, not error.** Two copied constants that
     * diverge by one character produce no message; they produce a watch that never starts again.
     */
    const val CONTEXT_PREFIX = "/pendulum/context/"

    /**
     * Watch → phone: "open the evening form".
     *
     * Fallback for the `RemoteActivityHelper` path. The phone is **not** allowed to launch an
     * activity on receiving this message — Android blocks launches from the background — it posts
     * a notification whose tap, in turn, is an explicit exemption.
     */
    const val OPEN_PHONE = "/pendulum/open-phone"

    /**
     * Phone → watch: "start recording".
     *
     * A deliberate departure from `docs/06-interface.md` §2.2, which reserves starting for a
     * physical gesture on the watch. The guard rail remains whole: `RecordingService` re-checks
     * the preflight before `startSession`, so a request without a sealed context is refused on
     * the watch side whatever its origin.
     */
    const val START_REQUEST = "/pendulum/start-request"

    fun context(nightKey: String) = CONTEXT_PREFIX + nightKey

    fun session(sessionHex: String) = SESSION_PREFIX + sessionHex

    /**
     * The index is zero-padded to 5 digits: the lexicographic order of the paths must coincide
     * with the order of the chunks, failing which a sorted listing delivers chunk 10 before
     * chunk 2.
     */
    fun chunk(sessionHex: String, idx: Int) = "$CHUNK_PREFIX$sessionHex/${"%05d".format(idx)}"

    fun live(sessionHex: String) = LIVE_PREFIX + sessionHex

    fun ack(sessionHex: String) = ACK_PREFIX + sessionHex

    fun sweep(sessionHex: String) = SWEEP_PREFIX + sessionHex

    /**
     * Night key: the local date of the **evening**, the rollover happening at noon.
     *
     * A START at 1:30 therefore attaches to the form filled in the previous evening, and not to
     * an evening that has not happened yet. Without this rollover, anyone going to bed after
     * midnight would see their sealed context attached to the wrong night — and the watch would
     * refuse to start, claiming they had filled in nothing.
     *
     * The function is here rather than in each of the two modules because both sides must produce
     * **exactly** the same string: the phone writes `context(nightKey(...))` and the watch reads
     * `context(nightKey(...))`, and a one-day shift is indistinguishable from an absent context.
     *
     * @param zone explicit time zone. Both devices are normally in the same one, but the implicit
     *   default would make the shift invisible when reading the code on the day they no longer
     *   are.
     */
    fun nightKey(nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val local = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        val evening = if (local.hour < EVENING_ROLLOVER_HOUR) local.minusDays(1) else local
        return "%04d-%02d-%02d".format(evening.year, evening.monthValue, evening.dayOfMonth)
    }

    /** Noon. Before it we are still in the previous night; after it, in the current evening. */
    const val EVENING_ROLLOVER_HOUR = 12
}

/** State of a session as seen from the phone (CAPTURE-ARCHITECTURE.md §2.7). */
enum class SessionState(val code: Int) {
    /** The watch has announced the night and has not yet announced its end. */
    OPEN(0),

    /** Clean close announced by the watch. */
    CLOSED(1),

    /** No news for more than 45 min: the watch may yet come back. */
    STALE(2),

    /** Deadline passed: we analyse what we have, without waiting for the rest. */
    TRUNCATED(3),
    ;

    companion object {
        fun fromCode(code: Int): SessionState = entries.firstOrNull { it.code == code }
            ?: throw WireFormatException("unknown SessionState: $code")
    }
}

/** Stop cause of a session (CAPTURE-ARCHITECTURE.md §3.4). Never guessed: `UNKNOWN` is an answer. */
enum class StopReason(val code: Int) {
    USER(0),
    CHARGING(1),
    LOW_BATTERY(2),
    MAX_DURATION(3),
    TIME_LIMIT(4),
    WAKE_DETECTED(5),
    DISK_FULL(6),

    /** Recovery after reboot/kill: the session was not closed by a stop condition. */
    CRASH(7),
    UNKNOWN(255),
    ;

    companion object {
        fun fromCode(code: Int): StopReason = entries.firstOrNull { it.code == code }
            ?: throw WireFormatException("unknown StopReason: $code")
    }
}

/** Malformed payload, or one produced by an incompatible version. */
class WireFormatException(message: String) : IOException(message)

/** Lowercase hexadecimal form of a session UUID, as it appears in the paths. */
fun ByteArray.toSessionHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

/** Inverse of [toSessionHex]. */
fun sessionHexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hexadecimal string of odd length: ${hex.length}" }
    return ByteArray(hex.length / 2) {
        ((digit(hex[it * 2]) shl 4) or digit(hex[it * 2 + 1])).toByte()
    }
}

private const val HEX = "0123456789abcdef"

private fun digit(c: Char): Int {
    val d = Character.digit(c, 16)
    require(d >= 0) { "invalid hexadecimal character: $c" }
    return d
}

/** Little-endian writing onto a growing array. */
internal class WireWriter(capacity: Int = 64) {
    private var buf = ByteArray(capacity)
    private var pos = 0

    private fun need(n: Int) {
        if (pos + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, pos + n))
    }

    fun u8(v: Int) = apply {
        need(1)
        buf[pos++] = v.toByte()
    }

    fun i16(v: Int) = apply {
        need(2)
        buf[pos++] = (v and 0xFF).toByte()
        buf[pos++] = ((v shr 8) and 0xFF).toByte()
    }

    fun i32(v: Int) = apply {
        need(4)
        for (i in 0 until 4) buf[pos++] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    fun i64(v: Long) = apply {
        need(8)
        for (i in 0 until 8) buf[pos++] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    fun bool(v: Boolean) = u8(if (v) 1 else 0)

    fun optI32(v: Int?) = apply { if (v == null) bool(false) else bool(true).i32(v) }

    fun optI64(v: Long?) = apply { if (v == null) bool(false) else bool(true).i64(v) }

    fun blob(b: ByteArray) = apply {
        i16(b.size)
        need(b.size)
        System.arraycopy(b, 0, buf, pos, b.size)
        pos += b.size
    }

    fun str(s: String) = blob(s.toByteArray(Charsets.UTF_8))

    fun ints(v: IntArray) = apply {
        i16(v.size)
        for (x in v) i32(x)
    }

    fun toByteArray(): ByteArray = buf.copyOf(pos)
}

/** Little-endian reading. Any read past the end throws [WireFormatException]. */
internal class WireReader(private val buf: ByteArray) {
    private var pos = 0

    private fun need(n: Int) {
        if (pos + n > buf.size) throw WireFormatException("payload truncated at offset $pos")
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun i16(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or (buf[pos + 1].toInt() shl 8)
        pos += 2
        return v
    }

    fun u16(): Int = i16() and 0xFFFF

    fun i32(): Int {
        need(4)
        var v = 0
        for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun bool(): Boolean = u8() != 0

    fun optI32(): Int? = if (bool()) i32() else null

    fun optI64(): Long? = if (bool()) i64() else null

    fun blob(): ByteArray {
        val n = u16()
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun str(): String = String(blob(), Charsets.UTF_8)

    fun ints(): IntArray {
        val n = u16()
        return IntArray(n) { i32() }
    }

    /** Checks the version byte at the head of the payload. */
    fun version(what: String): Int {
        val v = u8()
        if (v != WireProtocol.VERSION) {
            throw WireFormatException("$what: wire version $v, expected ${WireProtocol.VERSION}")
        }
        return v
    }
}
