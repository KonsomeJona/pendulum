package com.pendulum.phone.export

import android.content.Context
import com.pendulum.phone.db.ChunkEntity
import com.pendulum.phone.db.HcSnapshotEntity
import com.pendulum.phone.db.NightContextEntity
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.work.AnalysisParams
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.work.WorkScheduler
import java.io.InputStream
import java.io.OutputStream

/**
 * Export et reimport d'une nuit.
 *
 * L'ecriture se fait dans un `OutputStream` fourni par l'appelant — en pratique celui d'un
 * `Uri` obtenu par SAF (`ACTION_CREATE_DOCUMENT`). L'application n'ecrit donc **jamais** dans un
 * repertoire partage de sa propre initiative : l'emplacement est choisi par l'utilisateur, geste
 * par geste. C'est la seule sortie de donnees prevue, et elle est explicite — l'application ne
 * declare pas la permission `INTERNET` et ne peut rien envoyer ailleurs.
 */
object NightExporter {

    /**
     * Le bundle d'une nuit : chunks bruts, sidecar, lignes de base.
     *
     * Ce qui **n'y est pas** : les resultats. Les inclure inviterait a comparer un chiffre
     * exporte a un chiffre recalcule par une version ulterieure, sans passer par un rescore —
     * c'est-a-dire a comparer deux algorithmes en croyant comparer deux nuits. Le bundle porte
     * ce qui permet de recalculer, pas ce qui a ete calcule.
     */
    suspend fun exportBundle(context: Context, sessionHex: String, out: OutputStream) {
        val db = PendulumDatabase.get(context)
        val store = ChunkStore(context)
        val session = db.nightDao().find(sessionHex)
            ?: error("session inconnue : $sessionHex")
        val nightContext = db.contextDao().findForSession(sessionHex)
        val snapshot = db.hcSnapshotDao().latest(sessionHex)
        val reference = db.contextDao().reference()
        val params = WorkScheduler.activeParams(context)

        val chunks = LinkedHashMap<Int, ByteArray>()
        for (row in db.chunkDao().ofSession(sessionHex)) {
            val f = store.fileFor(sessionHex, row.idx)
            if (f.exists()) chunks[row.idx] = f.readBytes()
        }

        NightBundle.write(
            out,
            NightBundle.Content(
                manifest = manifestOf(session, snapshot),
                context = contextOf(nightContext),
                baseline = baselineOf(
                    db,
                    reference?.nightKey?.let { db.nightDao().findByNightKey(it)?.sessionHex },
                    params,
                ),
                hypnogramCsv = snapshot?.selectedStagesCsv.orEmpty(),
                chunks = chunks,
            ),
        )
    }

    /**
     * Reimport. Reconstruit la nuit **a l'identique** : memes octets de chunks, meme contexte,
     * meme hypnogramme retenu.
     *
     * C'est ce chemin que `BundleRoundTripTest` exerce : une base reconstruite depuis un bundle
     * doit produire un resultat identique au bit pres. Si un champ manquait ici, le test
     * echouerait sur le chiffre plutot que sur le champ — ce qui est exactement le bon endroit
     * pour echouer, parce que c'est le chiffre qui compte.
     *
     * @return l'identifiant de la nuit importee.
     */
    suspend fun importBundle(context: Context, input: InputStream): String {
        val content = NightBundle.read(input)
        val db = PendulumDatabase.get(context)
        val store = ChunkStore(context)
        val hex = content.sessionHex
        val m = content.manifest

        db.nightDao().insertIfAbsent(
            NightSessionEntity(
                sessionHex = hex,
                nightKey = WirePaths.nightKey(m.long("startWallMs")),
                startWallMs = m.long("startWallMs"),
                plannedStopWallMs = m.long("plannedStopWallMs"),
                endWallMs = m["endWallMs"]?.toLongOrNull(),
                zoneId = m["zoneId"].orEmpty(),
                tzOffsetStartMin = m.int("tzOffsetStartMin"),
                tzOffsetEndMin = m.int("tzOffsetEndMin"),
                nominalRateHz = m.int("nominalRateHz"),
                modeFlags = m.int("modeFlags"),
                state = m["state"] ?: "CLOSED",
                stopReason = m["stopReason"],
                totalChunks = m["totalChunks"]?.toIntOrNull(),
                lastChunkArrivalMs = m.long("lastChunkArrivalMs"),
            )
        )

        for ((idx, bytes) in content.chunks) {
            store.write(hex, idx, bytes)
            val complete = store.fileFor(hex, idx).inputStream().buffered().use {
                com.pendulum.format.ChunkReader.forEachBlock(it) { }.complete
            }
            db.chunkDao().insertIfAbsent(
                ChunkEntity(
                    sessionHex = hex,
                    idx = idx,
                    path = store.fileFor(hex, idx).absolutePath,
                    size = bytes.size,
                    crc32 = ChunkStore.crc32(bytes),
                    sampleCount = 0,
                    tFirstNs = 0L,
                    tLastNs = 0L,
                    flagsOr = 0,
                    complete = complete,
                    receivedAtMs = System.currentTimeMillis(),
                )
            )
        }

        // Le contexte est scelle a l'import comme il l'etait a l'origine — `sealedAtMs` est
        // recopie, jamais regenere : une date de scellement remise a l'instant de l'import
        // detruirait la seule preuve que le contexte precede la mesure.
        val c = content.context
        if (c.isNotEmpty() && db.contextDao().findForSession(hex) == null) {
            db.contextDao().seal(
                NightContextEntity(
                    // La cle de nuit est **derivee du debut de la session importee**, avec la
                    // meme bascule a midi que le scellement d'origine. La recalculer plutot que
                    // de la lire dans le bundle garantit que la nuit reimportee se rattache a son
                    // contexte par la meme regle que toutes les autres.
                    nightKey = WirePaths.nightKey(m.long("startWallMs")),
                    sealedAtMs = c.long("sealedAtMs"),
                    leg = c["leg"].orEmpty(),
                    strapId = c["strapId"].orEmpty(),
                    aloneInBed = c["aloneInBed"] == "true",
                    bedTimeLocalMs = c["bedTimeLocalMs"]?.toLongOrNull(),
                    riseTimeLocalMs = c["riseTimeLocalMs"]?.toLongOrNull(),
                    medicationJson = c["medicationJson"].orEmpty(),
                    caffeineAfter16h = c["caffeineAfter16h"] == "true",
                    alcoholUnits = c["alcoholUnits"]?.toDoubleOrNull() ?: 0.0,
                    unusualExercise = c["unusualExercise"] == "true",
                    notes = c["notes"],
                )
            )
        }

        if (content.hypnogramCsv.isNotBlank()) {
            db.hcSnapshotDao().append(
                HcSnapshotEntity(
                    sessionHex = hex,
                    fetchedAtMs = m.long("hcFetchedAtMs"),
                    attemptIndex = 0,
                    selectedPackage = m["hcPackage"],
                    selectedRecordId = m["hcRecordId"],
                    lastModifiedTimeMs = m["hcLastModifiedMs"]?.toLongOrNull(),
                    sessionStartMs = m["hcSessionStartMs"]?.toLongOrNull(),
                    sessionEndMs = m["hcSessionEndMs"]?.toLongOrNull(),
                    stageCount = m.int("hcStageCount"),
                    distinctStageTypes = m.int("hcDistinctStageTypes"),
                    selectedStagesCsv = content.hypnogramCsv,
                    recordsJson = """{"origine":"bundle importe"}""",
                    outcome = "IMPORTE",
                )
            )
        }
        return hex
    }

    // ------------------------------------------------------------------

    private fun manifestOf(s: NightSessionEntity, hc: HcSnapshotEntity?): Map<String, String> =
        buildMap {
            put("sessionHex", s.sessionHex)
            put("startWallMs", s.startWallMs.toString())
            put("plannedStopWallMs", s.plannedStopWallMs.toString())
            s.endWallMs?.let { put("endWallMs", it.toString()) }
            put("zoneId", s.zoneId)
            put("tzOffsetStartMin", s.tzOffsetStartMin.toString())
            put("tzOffsetEndMin", s.tzOffsetEndMin.toString())
            put("nominalRateHz", s.nominalRateHz.toString())
            put("modeFlags", s.modeFlags.toString())
            put("state", s.state)
            s.stopReason?.let { put("stopReason", it) }
            s.totalChunks?.let { put("totalChunks", it.toString()) }
            put("lastChunkArrivalMs", s.lastChunkArrivalMs.toString())
            hc?.let {
                put("hcFetchedAtMs", it.fetchedAtMs.toString())
                it.selectedPackage?.let { v -> put("hcPackage", v) }
                it.selectedRecordId?.let { v -> put("hcRecordId", v) }
                it.lastModifiedTimeMs?.let { v -> put("hcLastModifiedMs", v.toString()) }
                it.sessionStartMs?.let { v -> put("hcSessionStartMs", v.toString()) }
                it.sessionEndMs?.let { v -> put("hcSessionEndMs", v.toString()) }
                put("hcStageCount", it.stageCount.toString())
                put("hcDistinctStageTypes", it.distinctStageTypes.toString())
            }
        }

    private fun contextOf(c: NightContextEntity?): Map<String, String> = when (c) {
        null -> emptyMap()
        else -> buildMap {
            put("sealedAtMs", c.sealedAtMs.toString())
            put("leg", c.leg)
            put("strapId", c.strapId)
            put("aloneInBed", c.aloneInBed.toString())
            c.bedTimeLocalMs?.let { put("bedTimeLocalMs", it.toString()) }
            c.riseTimeLocalMs?.let { put("riseTimeLocalMs", it.toString()) }
            put("medicationJson", c.medicationJson)
            put("caffeineAfter16h", c.caffeineAfter16h.toString())
            put("alcoholUnits", c.alcoholUnits.toString())
            put("unusualExercise", c.unusualExercise.toString())
            c.notes?.let { put("notes", it) }
        }
    }

    /**
     * Les « lignes de base » : ce sans quoi la nuit ne se recalcule pas a l'identique.
     *
     * L'etalon de gain de la nuit de reference en fait partie, et c'est le champ qu'on oublie :
     * il n'appartient pas a la nuit exportee mais il entre dans son analyse (detection d'un
     * bracelet resserre differemment) et dans sa comparabilite. Un bundle sans lui se reanalyse
     * en donnant un `outlierVsBaseline` different, donc une nuit qui change de camp.
     */
    private suspend fun baselineOf(
        db: PendulumDatabase,
        referenceHex: String?,
        params: AnalysisParams,
    ): Map<String, String> = buildMap {
        put("paramsHash", params.paramsHash)
        put("algoVersion", params.algoVersion)
        put("paramsJson", params.toJson())
        referenceHex?.let { ref ->
            put("referenceSessionHex", ref)
            db.nightDao().find(ref)?.gainCalG?.let { put("referenceGainCalG", it.toString()) }
        }
    }

    private fun Map<String, String>.long(key: String): Long = this[key]?.toLongOrNull() ?: 0L
    private fun Map<String, String>.int(key: String): Int = this[key]?.toIntOrNull() ?: 0
}
