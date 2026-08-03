package com.pendulum.wear.record

import android.content.Context
import com.pendulum.format.wire.StopReason
import com.pendulum.format.wire.sessionHexToBytes
import com.pendulum.format.wire.toSessionHex
import com.pendulum.wear.temps.Durees
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone
import java.util.UUID

/**
 * Marqueur de session active et sidecar de metriques.
 *
 * `active_session.json` est la **seule** trace qui survit a un kill du processus. Sans lui, une
 * montre qui redemarre a 3 h ne sait pas qu'une nuit etait en cours, et le telephone ne sait pas
 * qu'il faut attendre la suite. Il est ecrit atomiquement (`tmp` + `fsync` + `rename`) : un
 * marqueur a moitie ecrit serait pire que pas de marqueur du tout, puisqu'il ferait echouer la
 * reprise en la faisant croire possible.
 *
 * Le sidecar, lui, n'est pas critique : il porte ce qui explique la nuit apres coup (trous,
 * batterie minute par minute, off-body, `fs` reellement mesure). Il est reecrit en entier a
 * chaque mise a jour — quelques kilo-octets toutes les minutes, ce qui est negligeable devant
 * les 300 o/s du signal.
 */
class SessionStore(context: Context) {

    private val filesDir: File = context.filesDir
    private val markerFile = File(filesDir, "active_session.json")

    /** Racine des chunks : un sous-repertoire par session. */
    val chunksRoot: File = File(filesDir, "chunks")

    fun sessionDir(sessionHex: String): File = File(chunksRoot, sessionHex)

    fun sidecarFile(sessionHex: String): File = File(sessionDir(sessionHex), "sidecar.json")

    // --- marqueur de session active ---

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
            // Un marqueur illisible est un marqueur absent : on ne reprend pas une nuit sur la
            // foi d'un fichier qu'on ne comprend pas.
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

    // --- sidecar de metriques ---

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
     * Ecriture atomique : temporaire, `fsync`, puis `rename`. Le `rename` est atomique sur
     * ext4/f2fs, donc le fichier vu par le prochain demarrage est soit l'ancien complet, soit le
     * nouveau complet, jamais un melange des deux.
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
            // Repli : sur un rename refuse, mieux vaut un fichier ecrase qu'aucun fichier.
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
 * Contenu de `active_session.json`. Tout ce qu'il faut pour reprendre une nuit sans rien
 * deviner — y compris `lastChunkIndex`, dont la continuite fonde l'unicite `(sessionId, idx)`
 * cote telephone.
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
     * « Cette nuit est finie, quoi qu'en dise le marqueur. »
     *
     * Les trois chemins de reprise — `BOOT_COMPLETED`, le chien de garde, et le redemarrage
     * `START_STICKY` du service — posaient la meme question avec le meme couple de conditions
     * recopie a la main, dont la seconde etait ecrite `14 * 3_600_000L` aux trois endroits. Trois
     * copies d'un predicat de reprise, c'est trois occasions d'en corriger deux ; et un marqueur
     * perime repris par un seul des trois chemins produit un enregistrement de plein jour dont
     * rien ne dit qu'il n'aurait pas du exister.
     *
     * L'horloge est un parametre, comme dans `MachineAccueil` et `EveningViewModel` : la fonction
     * est pure, donc les trois chemins partagent desormais un predicat qui a des tests.
     *
     * @param ageMaxMs borne de securite pour le cas ou `plannedStopWallMs` serait lui-meme faux.
     */
    fun estPerimee(nowMs: Long, ageMaxMs: Long = Durees.ACTIVES.ageMaxSessionMs): Boolean =
        nowMs >= plannedStopWallMs || nowMs >= startWallMs + ageMaxMs
}

/** Metriques de la nuit. Aucune n'est necessaire a la relecture des chunks : elles expliquent. */
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
