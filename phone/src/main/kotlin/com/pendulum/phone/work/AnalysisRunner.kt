package com.pendulum.phone.work

import android.content.Context
import android.util.Log
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.SleepWindow
import com.pendulum.phone.db.ClmEventEntity
import com.pendulum.phone.db.HcSnapshotEntity
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.ParamProfileEntity
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.SleepWindowEntity
import com.pendulum.phone.health.Hypnogram
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.ingest.SessionReassembler
import com.pendulum.phone.ingest.TimeAnchor

/**
 * Analysis of a night, from the database to the database. This is the only place that writes into
 * the derived tables.
 *
 * ### Idempotence is a requirement, not a happy property
 *
 * The same night is analysed several times by construction: a first time on waking with the
 * accelerometer mask alone, a second when the hypnogram arrives, a third if the provider rewrites
 * its session, and an n-th at every parameter change. Each pass **replaces** what existed for
 * `(night, paramsHash)` instead of adding to it. A rescore that piled up would double the number
 * of events at every round, and the symptom — an index that doubles — would look exactly like a
 * clinical worsening.
 */
object AnalysisRunner {

    private const val TAG = "PendulumAnalysis"

    /**
     * @return `true` if the analysis produced results, `false` if the night was not analysable
     *   (no chunk, or no valid block).
     */
    suspend fun analyse(
        context: Context,
        sessionHex: String,
        params: AnalysisParams = AnalysisParams.DEFAULT,
    ): Boolean {
        val db = PendulumDatabase.get(context)
        val store = ChunkStore(context)
        val session = db.nightDao().find(sessionHex) ?: return false

        // The parameter profile is recorded as active if it is not already: the trend filters on
        // `paramsHash`, and a result whose hash does not exist in `param_profile` would be an
        // orphan figure, impossible to explain later.
        db.paramDao().insertIfAbsent(
            ParamProfileEntity(
                paramsHash = params.paramsHash,
                createdAtMs = System.currentTimeMillis(),
                algoVersion = params.algoVersion,
                paramsJson = params.toJson(),
                active = db.paramDao().active() == null,
            )
        )

        val files = store.listChunkFiles(sessionHex)
        if (files.isEmpty()) return false

        val night = SessionReassembler.reassemble(
            sessionHex = sessionHex,
            files = files,
            sessionStateClosed = session.state == "CLOSED",
            declaredChunks = session.totalChunks,
        )
        if (night.epochResets > 0) {
            // The watch restarted during the night and the reassembler bridged the boot epochs, on
            // a wall-clock difference this code base forbids everywhere else. The night is intact —
            // that is the point of the bridge — but it was not measured under quite the same
            // conditions as one that needed none, and a figure that later looks odd deserves to be
            // explainable. Nothing else records this, so the log is where it is recorded.
            Log.i(TAG, "$sessionHex: ${night.epochResets} watch reboot(s) bridged")
        }
        if (night.blocks.isEmpty() || night.anchor == null) {
            Log.w(TAG, "$sessionHex: no usable block")
            return false
        }

        // One snapshot row, read once, feeding both the windows and the name of their origin: the
        // mask and its label cannot then come from two different readings. `latestWithSession`
        // and not `latest` — see its KDoc: `hc_snapshot` logs every rung of the fetch ladder, and
        // the ladder carries on after a success, so the newest row is quite often a rung that read
        // nothing. With `latest` here, that rung dropped the hypnogram from the rescore, and wrote
        // `sourcePackage = NULL` into the windows of the runs where the hypnogram did survive —
        // hence a night whose source read "Health Connect" with no application name, although the
        // database knew it two rows up.
        val hcSnapshot = db.hcSnapshotDao().latestWithSession(sessionHex)
        val hcWindows = loadHypnogram(hcSnapshot, night.anchor!!, params)
        val diary = loadDiary(db, sessionHex, night.anchor!!)
        val baselineGain = baselineGainOf(db, sessionHex)

        val result = NightAnalyzer.analyze(
            blocks = night.blocks,
            nominalRateHz = night.nominalRateHz,
            sessionClosedCleanly = night.closedCleanly,
            hcWindows = hcWindows,
            diary = diary,
            baselineGainG = baselineGain,
            params = params,
        )

        persist(db, session, night, result, params, hcPackage = hcSnapshot?.selectedPackage)
        return true
    }

    // ------------------------------------------------------------------

    /**
     * The hypnogram retained by the last Health Connect **reading**, re-read from
     * `hc_snapshot.selectedStagesCsv` of the row [analyse] chose.
     *
     * We do **not** re-read Health Connect here. Two reasons: a rescore must be able to run on a
     * database restored from a bundle, on a phone that has never seen this night; and re-reading
     * would give a potentially different answer (the provider rewrites its sessions), which would
     * make the rescore non-reproducible — two successive runs with no parameter change could
     * produce two figures.
     */
    private fun loadHypnogram(
        snap: HcSnapshotEntity?,
        anchor: TimeAnchor,
        params: AnalysisParams,
    ): List<SleepWindow>? {
        if (snap == null) return null
        if (snap.selectedStagesCsv.isBlank() && snap.sessionStartMs == null) return null
        val stages = Hypnogram.decodeCsv(snap.selectedStagesCsv)
        val start = snap.sessionStartMs ?: return null
        val end = snap.sessionEndMs ?: return null
        return Hypnogram.toWindows(stages, start, end, anchor, params.hypnogramHolePolicy)
    }

    /**
     * The manual diary, from the sealed evening context. It does not make the accelerometer mask
     * independent of the signal — it bounds the search for the SPT, which stops a nap or a stretch
     * of sofa stillness from pre-empting the start of the night.
     */
    private suspend fun loadDiary(
        db: PendulumDatabase,
        sessionHex: String,
        anchor: TimeAnchor,
    ): DiaryWindow? {
        val ctx = db.contextDao().findForSession(sessionHex) ?: return null
        val bed = ctx.bedTimeLocalMs ?: return null
        val rise = ctx.riseTimeLocalMs ?: return null
        return DiaryWindow(anchor.toMsRel(bed), anchor.toMsRel(rise))
    }

    /**
     * The gain reference of the **reference night** — the first one whose context was sealed, the
     * same one that serves as the reference for the `comparable_night` view. Using the campaign
     * average would move the reference at every night added, and a night comparable yesterday
     * could stop being comparable today without anything having changed in the sleeper.
     */
    private suspend fun baselineGainOf(db: PendulumDatabase, sessionHex: String): Float? {
        val ref = db.contextDao().reference() ?: return null
        // The reference context is keyed by the evening, not by the session: it is the night
        // recorded under that evening that carries the gain reference. It may not exist — a form
        // sealed on an evening where the watch did not, in the end, start.
        val refSession = db.nightDao().findByNightKey(ref.nightKey) ?: return null
        if (refSession.sessionHex == sessionHex) return null
        return refSession.gainCalG?.toFloat()
    }

    // ------------------------------------------------------------------

    /**
     * @param hcPackage the application that published the hypnogram the Health Connect windows
     *   were built from — `selectedPackage` of the very snapshot row [loadHypnogram] read, so the
     *   windows and their origin are one reading. It is what `comparable_night.sourcePackage`
     *   aggregates, hence what the screens name as the sleep source of the night.
     */
    private suspend fun persist(
        db: PendulumDatabase,
        session: NightSessionEntity,
        night: SessionReassembler.Night,
        r: NightAnalyzer.Result,
        params: AnalysisParams,
        hcPackage: String?,
    ) {
        val hex = session.sessionHex

        val windows = r.masks.flatMap { (source, mask) ->
            mask.windows.map { w ->
                SleepWindowEntity(
                    sessionHex = hex,
                    source = source.name,
                    stage = w.stage.name,
                    startMsRel = w.startMsRel,
                    endMsRel = w.endMsRel,
                    sourcePackage = if (source == MaskSource.HEALTH_CONNECT) hcPackage else null,
                    paramsHash = r.paramsHash,
                )
            }
        }

        // Series membership, per rule set. It is rebuilt from the results rather than guessed:
        // `SeriesBuilder` decides, and it alone.
        val events = r.clms.map { c ->
            ClmEventEntity(
                sessionHex = hex,
                paramsHash = r.paramsHash,
                onsetMsRel = c.onsetMsRel,
                durationMs = c.durationMs,
                peakAmpG = c.peakAmpG.toDouble(),
                medianAmpG = c.medianAmpG.toDouble(),
                noiseFloorG = c.noiseFloorG.toDouble(),
                thresholdOnG = c.thresholdOnG.toDouble(),
                tiltChangeDeg = c.tiltChangeDeg.toDouble(),
                flags = c.flags,
                rejectReason = c.reject?.name,
                duringWake = (c.flags and ClmFlags.DURING_WAKE) != 0,
            )
        }

        // `from` and not the constructor: it is the one that translates the `NaN` of `:algo`
        // into `NULL`. A night with no analysable sleep — the default case without a Health
        // Connect hypnogram — has no PLMI at all, and hard-coding one here would make it fail on
        // insertion.
        val computedAtMs = System.currentTimeMillis()
        val results = r.results.map { p ->
            PlmResultEntity.from(
                sessionHex = hex,
                paramsHash = r.paramsHash,
                computedAtMs = computedAtMs,
                algoVersion = r.algoVersion,
                r = p,
            )
        }

        db.derivedDao().replaceAnalysis(hex, r.paramsHash, windows, events, results)

        db.nightDao().writeAnalysisSummary(
            hex = hex,
            atMs = System.currentTimeMillis(),
            algoVersion = r.algoVersion,
            paramsHash = r.paramsHash,
            fsHz = r.fsHz,
            sampleCount = r.sampleCount,
            gapCount = r.gapCount,
            gapTotalMs = r.gapTotalMs,
            analysableMin = r.analysableMin,
            gainCalG = r.calibration.gainCalG.takeIf { it.isFinite() }?.toDouble(),
            gainSource = r.calibration.gainSource.name,
            truncated = r.truncated || !night.closedCleanly,
            rejectedFraction = r.integrityRejectedFraction,
        )
    }
}
