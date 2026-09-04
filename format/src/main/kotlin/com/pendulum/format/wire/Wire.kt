package com.pendulum.format.wire

import java.io.IOException
import java.util.Locale

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

    /**
     * Phone → watch: "everything you sent me before this instant has been erased; forget it."
     *
     * A `DataItem` carrying an [Erasure], and **not** a message like [SWEEP_REQUEST] or
     * [START_REQUEST]. The two requests above are gestures made in front of the watch, and a
     * message that fails when the watch is out of range is reported as such. An erasure is a
     * different thing: it is a **state** — "the phone disowns what it received before T" — and
     * it must reach a watch that is in a drawer, switched off, or out of range at the moment the
     * user types the confirmation, because that is exactly the watch that still holds the most
     * unsent chunks. Only the replicated store gives that guarantee; a message would simply be
     * lost, and the watch would push its files back into a phone that no longer knows the night
     * they belong to.
     *
     * What this repairs. "Erase everything" cancelled the work, deleted the chunk files and wiped
     * the database, and never spoke to the Data Layer. Three things survived it, none of them
     * visible on the screen that had just announced "0 B on disk":
     *  - the watch's chunk items still in flight, which `pushChunks` never re-puts (it skips every
     *    index present in the store) and which no acknowledgement ever names again — the files
     *    behind them stayed on the watch for good, "N chunks pending" every evening;
     *  - a night being recorded: at its close the watch re-put the session item, `insertIfAbsent`
     *    recreated the row, the final burst was ingested, the analysis chain ran, and a night the
     *    user had erased at 3 a.m. was on the screen at 7 a.m., truncated to its last quarter of
     *    an hour;
     *  - every chunk that landed in between: `onChunk` wrote the file to `filesDir` before the
     *    row insertion failed on the missing session, so eight hours of erased accelerometry came
     *    back on the phone's disk, unknown to the database and counted by nothing.
     *
     * The payload is the erasure instant, and the watch compares it with the **start** of each
     * session it holds: a night started after the erasure is not the phone's to disown, and the
     * item may reach the watch hours late, after such a night has begun.
     */
    const val ERASE = "/pendulum/erase"

    fun context(nightKey: String) = CONTEXT_PREFIX + nightKey

    fun session(sessionHex: String) = SESSION_PREFIX + sessionHex

    /**
     * The index is zero-padded to 5 digits: the lexicographic order of the paths must coincide
     * with the order of the chunks, failing which a sorted listing delivers chunk 10 before
     * chunk 2.
     *
     * The locale is pinned to [Locale.ROOT] because `String.format` without one localises the
     * digits of `%d` as soon as the default locale's zero is not '0' — ar-EG, fa-IR, bn-BD,
     * ne-NP, my-MM, on the JDK and on Android's ICU alike. Before the pin, a watch in Arabic
     * published `/pendulum/chunk/<hex>/٠٠٠٠٧` and a phone in English listened for `.../00007`:
     * two different strings, so the chunk was never matched and never acknowledged, and the
     * Data Layer reported nothing, because silence is its failure mode.
     */
    fun chunk(sessionHex: String, idx: Int) =
        "$CHUNK_PREFIX$sessionHex/${"%05d".format(Locale.ROOT, idx)}"

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
        // Locale.ROOT for the same reason as in `chunk`: this key is the one string the two
        // devices must agree on, and the digits of `%d` follow the default locale. A phone in
        // Arabic sealed `/pendulum/context/٢٠٢٦-٠٩-٠٣`, the watch looked for `.../2026-09-03`,
        // and START stayed refused for the night with no error anywhere. Even with both devices
        // in the same locale, `P1Gate` parses this key back with `LocalDate.parse`, which only
        // accepts ASCII digits — so the P1 screen crashed for those users instead.
        return "%04d-%02d-%02d".format(Locale.ROOT, evening.year, evening.monthValue, evening.dayOfMonth)
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

/**
 * `/pendulum/erase` — phone → watch, `setUrgent()`. See [WirePaths.ERASE] for why it is an item.
 *
 * @param erasedBeforeMs the instant of the erasure on the phone's wall clock. Every session
 *   whose `startWallMs` is earlier is disowned: its files and its items go, and if it is still
 *   being recorded it is stopped. A session started at or after this instant is left alone.
 *
 * Versioned and framed like every other structure of the protocol, and not the bare decimal
 * string the context item carries: the watch has to **read this number back** and act on it,
 * where the context item is only ever tested for presence. A payload that cannot be read must be
 * refused at the first byte rather than parsed into zero — zero would disown nothing, silently.
 *
 * Named for what it is and not for what it does. `ErasureOrder` on the phone is the *sequence*
 * of the erasure's steps, after `SealingOrder`; an "erase order" next to it would read as the
 * same thing, and this is not a command at all but a fact the phone states about itself — see
 * [WirePaths.ERASE].
 */
data class Erasure(val erasedBeforeMs: Long) {

    fun encode(): ByteArray = WireWriter(16)
        .u8(WireProtocol.VERSION)
        .i64(erasedBeforeMs)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): Erasure {
            val r = WireReader(bytes)
            r.version("Erasure")
            return Erasure(erasedBeforeMs = r.i64())
        }
    }
}

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
