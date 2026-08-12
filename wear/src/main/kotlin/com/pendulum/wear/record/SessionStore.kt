package com.pendulum.wear.record

import android.content.Context
import com.pendulum.format.wire.StopReason
import com.pendulum.format.wire.sessionHexToBytes
import com.pendulum.format.wire.toSessionHex
import com.pendulum.wear.time.Durations
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone
import java.util.UUID

/**
 * Active-session marker and metrics sidecar.
 *
 * `active_session.json` is the **only** trace that survives a kill of the process. Without it, a
 * watch that reboots at 3 a.m. does not know a night was in progress, and the phone does not know
 * it should wait for the rest. It is written atomically (`tmp` + `fsync` + `rename`): a
 * half-written marker would be worse than no marker at all, since it would make the resume fail
 * after having made it look possible.
 *
 * The sidecar, for its part, is not critical: it carries what explains the night after the fact
 * (gaps, battery minute by minute, off-body, the `fs` actually measured). It is rewritten in full
 * at every update — a few kilobytes every minute, which is negligible next to the 300 B/s of the
 * signal.
 */
class SessionStore(context: Context) {

    private val filesDir: File = context.filesDir
    private val markerFile = File(filesDir, "active_session.json")

    /** Root of the chunks: one subdirectory per session. */
    val chunksRoot: File = File(filesDir, "chunks")

    fun sessionDir(sessionHex: String): File = File(chunksRoot, sessionHex)

    fun sidecarFile(sessionHex: String): File = File(sessionDir(sessionHex), "sidecar.json")

    // --- active-session marker ---

    fun begin(marker: SessionMarker) = writeMarker(marker)

    fun readMarker(): SessionMarker? {
        if (!markerFile.exists()) return null
        return try {
            val o = JSONObject(markerFile.readText())
            SessionMarker(
                sessionHex = o.getString("sessionHex"),
                startWallMs = o.getLong("startWallMs"),
                plannedStopWallMs = o.getLong("plannedStopWallMs"),
                lastChunkIndex = o.getInt("lastChunkIndex"),
                modeFlags = o.getInt("modeFlags"),
                nominalRateHz = o.getInt("nominalRateHz"),
                zoneId = o.getString("zoneId"),
                stopAtLocalMinutes = o.getInt("stopAtLocalMinutes"),
            )
        } catch (e: Exception) {
            // An unreadable marker is an absent marker: a night is not resumed on the strength of
            // a file we do not understand.
            null
        }
    }

    fun updateLastChunkIndex(idx: Int) {
        val m = readMarker() ?: return
        writeMarker(m.copy(lastChunkIndex = idx))
    }

    fun updateMode(modeFlags: Int, rateHz: Int) {
        val m = readMarker() ?: return
        writeMarker(m.copy(modeFlags = modeFlags, nominalRateHz = rateHz))
    }

    fun clearActive() {
        markerFile.delete()
    }

    private fun writeMarker(m: SessionMarker) {
        val o = JSONObject()
            .put("sessionHex", m.sessionHex)
            .put("startWallMs", m.startWallMs)
            .put("plannedStopWallMs", m.plannedStopWallMs)
            .put("lastChunkIndex", m.lastChunkIndex)
            .put("modeFlags", m.modeFlags)
            .put("nominalRateHz", m.nominalRateHz)
            .put("zoneId", m.zoneId)
            .put("stopAtLocalMinutes", m.stopAtLocalMinutes)
        writeAtomically(markerFile, o.toString())
    }

    // --- metrics sidecar ---

    fun writeSidecar(sessionHex: String, s: Sidecar) {
        val dir = sessionDir(sessionHex)
        dir.mkdirs()
        val o = JSONObject()
            .put("sessionHex", sessionHex)
            .put("startWallMs", s.startWallMs)
            .put("endWallMs", s.endWallMs ?: JSONObject.NULL)
            .put("zoneId", s.zoneId)
            .put("nominalRateHz", s.nominalRateHz)
            .put("measuredRateHz", s.measuredRateHz)
            .put("modeFlags", s.modeFlags)
            .put("degradationStep", s.degradationStep)
            .put("gapCount", s.gapCount)
            .put("gapTotalMs", s.gapTotalMs)
            .put("offBodySeconds", s.offBodySeconds)
            .put("samples", s.samples)
            .put("bytes", s.bytes)
            .put("totalChunks", s.totalChunks)
            .put("stopReason", s.stopReason?.name ?: JSONObject.NULL)
            .put("batteryPct", JSONArray().apply { s.batterySeries.forEach { put(it) } })
        writeAtomically(sidecarFile(sessionHex), o.toString())
    }

    /**
     * Atomic write: temporary file, `fsync`, then `rename`. The `rename` is atomic on ext4/f2fs,
     * so the file seen by the next start is either the old one complete or the new one complete,
     * never a mixture of the two.
     */
    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(content.toByteArray(Charsets.UTF_8))
            it.flush()
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Fallback: on a refused rename, an overwritten file is better than no file at all.
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        fun newSessionHex(): String {
            val uuid = UUID.randomUUID()
            val b = ByteArray(16)
            var hi = uuid.mostSignificantBits
            var lo = uuid.leastSignificantBits
            for (i in 7 downTo 0) {
                b[i] = (hi and 0xFF).toByte(); hi = hi shr 8
                b[i + 8] = (lo and 0xFF).toByte(); lo = lo shr 8
            }
            return b.toSessionHex()
        }

        fun uuidBytes(sessionHex: String): ByteArray = sessionHexToBytes(sessionHex)

        fun currentZoneId(): String = TimeZone.getDefault().id
    }
}

/**
 * Contents of `active_session.json`. Everything needed to resume a night without guessing
 * anything — including `lastChunkIndex`, whose continuity is what founds the `(sessionId, idx)`
 * uniqueness on the phone side.
 */
data class SessionMarker(
    val sessionHex: String,
    val startWallMs: Long,
    val plannedStopWallMs: Long,
    val lastChunkIndex: Int,
    val modeFlags: Int,
    val nominalRateHz: Int,
    val zoneId: String,
    val stopAtLocalMinutes: Int,
) {

    /**
     * "This night is over, whatever the marker says."
     *
     * The three resume paths — `BOOT_COMPLETED`, the watchdog, and the `START_STICKY` restart of
     * the service — asked the same question with the same pair of conditions copied out by hand,
     * the second of which was written `14 * 3_600_000L` in all three places. Three copies of a
     * resume predicate are three chances to fix only two of them; and a stale marker resumed by
     * just one of the three paths produces a broad-daylight recording with nothing to say it
     * should never have existed.
     *
     * The clock is a parameter, as in `HomeMachine` and `EveningViewModel`: the function is
     * pure, so the three paths now share a predicate that has tests.
     *
     * @param ageMaxMs safety bound for the case where `plannedStopWallMs` would itself be wrong.
     */
    fun isStale(nowMs: Long, ageMaxMs: Long = Durations.ACTIVE.sessionMaxAgeMs): Boolean =
        nowMs >= plannedStopWallMs || nowMs >= startWallMs + ageMaxMs
}

/** Metrics for the night. None is needed to read the chunks back: they explain. */
data class Sidecar(
    val startWallMs: Long,
    val endWallMs: Long?,
    val zoneId: String,
    val nominalRateHz: Int,
    val measuredRateHz: Double,
    val modeFlags: Int,
    val degradationStep: Int,
    val gapCount: Int,
    val gapTotalMs: Long,
    val offBodySeconds: Long,
    val samples: Long,
    val bytes: Long,
    val totalChunks: Int,
    val stopReason: StopReason?,
    val batterySeries: List<Int>,
)
