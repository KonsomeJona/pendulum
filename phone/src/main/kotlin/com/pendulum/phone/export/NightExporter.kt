package com.pendulum.phone.export

import android.content.Context
import com.pendulum.phone.db.ChunkEntity
import com.pendulum.phone.db.HcSnapshotEntity
import com.pendulum.phone.db.NightContextEntity
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.ingest.TelemetryAdapter
import com.pendulum.phone.work.AnalysisParams
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.work.WorkScheduler
import java.io.InputStream
import java.io.OutputStream

/**
 * Export and reimport of a night.
 *
 * Writing goes into an `OutputStream` supplied by the caller — in practice that of a `Uri`
 * obtained through SAF (`ACTION_CREATE_DOCUMENT`). The application therefore **never** writes
 * into a shared directory on its own initiative: the location is chosen by the user, one gesture
 * at a time. This is the only data output there is, and it is explicit — the application does not
 * declare the `INTERNET` permission and cannot send anything anywhere else.
 */
object NightExporter {

    /**
     * The bundle of a night: raw chunks, sidecar, baselines.
     *
     * What is **not** in it: the results. Including them would invite comparing an exported figure
     * with a figure recomputed by a later version, without going through a rescore — that is,
     * comparing two algorithms while believing one is comparing two nights. The bundle carries
     * what allows a recomputation, not what was computed.
     */
    suspend fun exportBundle(context: Context, sessionHex: String, out: OutputStream) {
        val db = PendulumDatabase.get(context)
        val store = ChunkStore(context)
        val session = db.nightDao().find(sessionHex)
            ?: error("unknown session: $sessionHex")
        val nightContext = db.contextDao().findForSession(sessionHex)
        // The last **reading**, not the last attempt. `hc_snapshot` logs every rung of the fetch
        // ladder, and the ladder carries on after a success; with `latest` here, a rung that read
        // nothing at T+4 h (Health Connect updating, permission revoked) made the bundle leave
        // without the hypnogram the database still held — the backup lost its denominator, and a
        // phone restored from it scored the night without its mask. The same row feeds the manifest
        // and the CSV, so the two stay consistent with each other.
        val snapshot = db.hcSnapshotDao().latestWithSession(sessionHex)
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
     * Reimport. Rebuilds the night **identically**: same chunk bytes, same context, same retained
     * hypnogram.
     *
     * `BundleRoundTripTest` proves the half that runs on the JVM — bundle bytes in, identical
     * analysis out. It does **not** call this function, and this KDoc used to say it did: what
     * happens here, the writing of the database rows, is covered by `BundleImportTest`
     * (androidTest), which needs Room. The distinction cost a real loss — see the chunk loop
     * below — that the round-trip test could not see, because the rows it never wrote were the
     * ones that were wrong.
     *
     * @return the identifier of the imported night.
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

            // The same single pass as ingestion (`PendulumListenerService.onChunk`) and as the
            // watch before it sends (`DataLayerTransfer.summarize`): the `TLM!` points and the
            // block time base are harvested while the reader is already walking the file to find
            // out whether it is complete. A second pass would be an occasion to diverge — a chunk
            // judged complete by the first and unreadable by the second.
            //
            // Until 4 September 2026 this loop walked the file for `complete` alone: the telemetry
            // was decoded, CRC-checked, and dropped, and the chunk row was written with
            // `sampleCount = 0, tFirstNs = 0, tLastNs = 0, flagsOr = 0`. A campaign carried to a
            // new phone therefore arrived with `telemetry_point` empty on every night — no
            // metrology band on the night detail, an empty `Metrology.summary` in the "why" block,
            // and the P1 battery criterion `UNDETERMINED` for the whole campaign, so that the P1
            // report exported from the new phone contradicted the one from the old phone on the
            // same nights. `Entities.kt` says the table cannot be reconstituted from the database;
            // it can from the chunk bytes, which is exactly what the bundle carries.
            val telemetry = ArrayList<com.pendulum.format.TelemetryPoint>()
            var sampleCount = 0
            var tFirstNs = Long.MAX_VALUE
            var tLastNs = Long.MIN_VALUE
            var flagsOr = 0
            val complete = store.fileFor(hex, idx).inputStream().buffered().use {
                com.pendulum.format.ChunkReader.forEachBlock(
                    it,
                    onTelemetry = { point -> telemetry += point },
                ) { block ->
                    sampleCount += block.sampleCount
                    if (block.tFirstNs < tFirstNs) tFirstNs = block.tFirstNs
                    if (block.tLastNs > tLastNs) tLastNs = block.tLastNs
                    flagsOr = flagsOr or block.flags
                }.complete
            }

            // Written **before** the chunk row, in the same order as ingestion. A v1 chunk carries
            // no `TLM!` block: the list is empty and the night has no metrology band, which is the
            // truth of that night and not a failure of the import.
            if (telemetry.isNotEmpty()) {
                db.telemetryDao().insertAllIfAbsent(TelemetryAdapter.toEntities(hex, telemetry))
            }

            db.chunkDao().insertIfAbsent(
                ChunkEntity(
                    sessionHex = hex,
                    idx = idx,
                    path = store.fileFor(hex, idx).absolutePath,
                    size = bytes.size,
                    crc32 = ChunkStore.crc32(bytes),
                    sampleCount = sampleCount,
                    // `tFirstNs` of the first chunk is the origin of the sensor time base
                    // (`PendulumRepository.nightDetail`, `Metrology`): left at zero, the restored
                    // metrology band aligned on an origin that was not the night's. A chunk with
                    // no readable block keeps the zeroes ingestion would never have written for
                    // it, rather than `Long.MAX_VALUE`.
                    tFirstNs = if (sampleCount == 0) 0L else tFirstNs,
                    tLastNs = if (sampleCount == 0) 0L else tLastNs,
                    flagsOr = flagsOr,
                    complete = complete,
                    receivedAtMs = System.currentTimeMillis(),
                )
            )
        }

        // The context is sealed on import as it was originally — `sealedAtMs` is copied across,
        // never regenerated: a sealing date reset to the instant of the import would destroy the
        // only evidence that the context precedes the measurement.
        val c = content.context
        if (c.isNotEmpty() && db.contextDao().findForSession(hex) == null) {
            db.contextDao().seal(
                NightContextEntity(
                    // The night key is **derived from the start of the imported session**, with
                    // the same rollover at noon as the original sealing. Recomputing it rather
                    // than reading it from the bundle guarantees that the reimported night is
                    // attached to its context by the same rule as every other one.
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
                    recordsJson = """{"origin":"imported bundle"}""",
                    outcome = "IMPORTED",
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
     * The "baselines": what the night cannot be recomputed identically without.
     *
     * The gain reference of the reference night is one of them, and it is the field one forgets:
     * it does not belong to the exported night but it enters its analysis (detection of a strap
     * tightened differently) and its comparability. A bundle without it reanalyses into a
     * different `outlierVsBaseline`, hence a night that changes camp.
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
