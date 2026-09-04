package com.pendulum.phone.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NightDao {

    /**
     * `IGNORE` and not `REPLACE`: the `/pendulum/session` item is **put down again** by the watch
     * at every state change, and a `REPLACE` would overwrite along the way everything the analysis
     * has written in the row (gain, analysable minutes, hash). Updates go through the targeted
     * methods below, which touch only their own columns.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(session: NightSessionEntity): Long

    @Update
    suspend fun update(session: NightSessionEntity)

    @Query("SELECT * FROM night_session WHERE sessionHex = :hex")
    suspend fun find(hex: String): NightSessionEntity?

    /**
     * The night recorded under a given evening, if there is one.
     *
     * An evening can have a sealed context and no night — that is the case of every form filled in
     * before the watch starts, hence of **all** the evenings between 8 pm and bed time. `null` is
     * a normal state here and not an anomaly.
     *
     * And an evening can have **two**: a false start stopped after a minute and started again, a
     * nap after the noon rollover. This query used to be a bare `LIMIT 1`, so which of the two
     * came back was the query planner's choice, not ours — and its callers read the reference
     * session from it: `AnalysisRunner.baselineGainOf` took the campaign's reference gain from the
     * row, `NightExporter` named it in the bundle. A one-minute false start chosen on the
     * reference evening gave a `NULL` reference gain, hence a campaign with no comparable night.
     * The order is now [PrincipalSessionSql.ORDER_BY], the same one the `comparable_night` view
     * applies, so the two readers name the same session.
     */
    @Query(PrincipalSessionSql.OF_EVENING)
    suspend fun findByNightKey(nightKey: String): NightSessionEntity?

    @Query("SELECT * FROM night_session ORDER BY startWallMs DESC")
    fun observeAll(): Flow<List<NightSessionEntity>>

    /**
     * The nights in one one-off read, the most recent first.
     *
     * The P1 gate report and its CSV export need them without a flow: they are snapshots, produced
     * once when the screen opens or when the file is written, and a flow would recompose a report
     * while it is being read.
     */
    @Query("SELECT * FROM night_session ORDER BY startWallMs DESC")
    suspend fun all(): List<NightSessionEntity>

    @Query("SELECT * FROM night_session WHERE state = 'OPEN' OR state = 'STALE'")
    suspend fun openOrStale(): List<NightSessionEntity>

    @Query("SELECT sessionHex FROM night_session ORDER BY startWallMs ASC")
    suspend fun allHexOldestFirst(): List<String>

    /**
     * The nights closed less than 36 h ago: exactly those whose hypnogram can still arrive. This
     * is the set that the opportunistic trigger sweeps when the phone is plugged in or when the
     * application comes back to the foreground.
     */
    @Query(
        """
        SELECT * FROM night_session
        WHERE endWallMs IS NOT NULL AND endWallMs >= :sinceMs
        ORDER BY startWallMs DESC
        """
    )
    suspend fun endedSince(sinceMs: Long): List<NightSessionEntity>

    /**
     * Closure announced by the watch. `endWallMs` and `stopReason` are written only here: a guessed
     * end is a bug, `UNKNOWN` is an acceptable answer.
     */
    @Query(
        """
        UPDATE night_session
        SET state = :state, endWallMs = :endWallMs, totalChunks = :totalChunks,
            stopReason = :stopReason, tzOffsetEndMin = :tzOffsetEndMin
        WHERE sessionHex = :hex
        """
    )
    suspend fun markClosed(
        hex: String,
        state: String,
        endWallMs: Long?,
        totalChunks: Int?,
        stopReason: String?,
        tzOffsetEndMin: Int,
    )

    @Query("UPDATE night_session SET state = :state WHERE sessionHex = :hex")
    suspend fun setState(hex: String, state: String)

    @Query("UPDATE night_session SET lastChunkArrivalMs = :atMs WHERE sessionHex = :hex")
    suspend fun touchChunkArrival(hex: String, atMs: Long)

    @Query("UPDATE night_session SET batteryPctLast = :pct WHERE sessionHex = :hex")
    suspend fun setBattery(hex: String, pct: Int)

    /**
     * The reveal of the result is **logged** (guard rail 2). `revealedAtMs` is written only once:
     * `WHERE revealedAtMs IS NULL` means that replaying the gesture does not rewrite the date,
     * without which the trace would say "revealed this morning" for a night opened three times.
     */
    @Query("UPDATE night_session SET revealedAtMs = :atMs WHERE sessionHex = :hex AND revealedAtMs IS NULL")
    suspend fun markRevealed(hex: String, atMs: Long)

    /** Written by the analysis, and by it alone. */
    @Query(
        """
        UPDATE night_session
        SET analyzedAtMs = :atMs, algoVersion = :algoVersion, paramsHash = :paramsHash,
            fsMeasuredHz = :fsHz, sampleCount = :sampleCount, gapCount = :gapCount,
            gapTotalMs = :gapTotalMs, analysableMin = :analysableMin, gainCalG = :gainCalG,
            gainSource = :gainSource, truncated = :truncated,
            integrityRejectedFraction = :rejectedFraction
        WHERE sessionHex = :hex
        """
    )
    suspend fun writeAnalysisSummary(
        hex: String,
        atMs: Long,
        algoVersion: String,
        paramsHash: String,
        fsHz: Double,
        sampleCount: Long,
        gapCount: Int,
        gapTotalMs: Long,
        analysableMin: Double,
        gainCalG: Double?,
        gainSource: String?,
        truncated: Boolean,
        rejectedFraction: Double,
    )
}

@Dao
interface ChunkDao {

    /**
     * **The keystone of the whole transfer protocol.** `IGNORE` on `UNIQUE(sessionHex, idx)`:
     * receiving the same chunk twice is a silent no-op, so every re-emission path (lost ack, sweep
     * after a cut, phone restart) is safe without a counter and without a state machine.
     *
     * @return the inserted identifier, or -1 if the row already existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(chunk: ChunkEntity): Long

    @Query("SELECT * FROM chunk WHERE sessionHex = :hex ORDER BY idx ASC")
    suspend fun ofSession(hex: String): List<ChunkEntity>

    /**
     * The **complete** indices of a session, sorted. The acknowledgement is recomputed from this
     * query every time, never from an in-memory counter: a counter survives a service restart
     * badly, and an over-optimistic acknowledgement makes the watch delete a file the phone does
     * not have.
     */
    @Query("SELECT idx FROM chunk WHERE sessionHex = :hex AND complete = 1 ORDER BY idx ASC")
    suspend fun completeIndices(hex: String): List<Int>

    @Query("SELECT COUNT(*) FROM chunk WHERE sessionHex = :hex")
    suspend fun count(hex: String): Int

    @Query("SELECT COALESCE(SUM(size), 0) FROM chunk WHERE sessionHex = :hex")
    suspend fun totalBytes(hex: String): Long

    @Query("SELECT path FROM chunk")
    suspend fun allPaths(): List<String>

    @Query("SELECT * FROM chunk WHERE sessionHex = :hex AND idx = :idx")
    suspend fun find(hex: String, idx: Int): ChunkEntity?
}

/**
 * The night telemetry. **Written by ingestion, never by the analysis**, and never erased by a
 * rescore: it is received, not derived.
 *
 * There is deliberately neither `@Update` nor `@Delete`. A telemetry point describes the state of
 * a device at one instant: there is nothing in it to correct, and the only legitimate erasure is
 * the one that takes the whole night with it, carried by the foreign key in `CASCADE`.
 */
@Dao
interface TelemetryDao {

    /**
     * `IGNORE` on `UNIQUE(sessionHex, elapsedRealtimeNs)`, for exactly the same reason as
     * `ChunkDao.insertIfAbsent`: a re-emitted chunk passes its points through here again, and
     * re-inserting must be a silent no-op rather than a duplicate. A duplicate would raise nothing
     * and would falsify every average of the metrology band.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(points: List<TelemetryPointEntity>)

    /**
     * The points of a night, in the order of the **sample** time base. That is the one that
     * timestamps the movements; sorting on the monotonic clock would give the same order in the
     * nominal case and a different order exactly when the two clocks diverge, that is to say in
     * the only case where the question arises.
     */
    @Query("SELECT * FROM telemetry_point WHERE sessionHex = :hex ORDER BY sensorTsNs ASC, elapsedRealtimeNs ASC")
    suspend fun ofSession(hex: String): List<TelemetryPointEntity>

    @Query("SELECT COUNT(*) FROM telemetry_point WHERE sessionHex = :hex")
    suspend fun count(hex: String): Int
}

/**
 * Everything derived. One single rule: **erase before rewriting, by `(night, paramsHash)`**. A
 * rescore that piled up instead of overwriting would double the events at every pass, and the
 * symptom (an index that doubles) would look like a clinical worsening.
 */
@Dao
abstract class DerivedDao {

    @Query("DELETE FROM sleep_window WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun clearWindows(hex: String, paramsHash: String)

    @Query("DELETE FROM clm_event WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun clearEvents(hex: String, paramsHash: String)

    @Query("DELETE FROM plm_result WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun clearResults(hex: String, paramsHash: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertWindows(rows: List<SleepWindowEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEvents(rows: List<ClmEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertResults(rows: List<PlmResultEntity>)

    /**
     * Atomic replacement. If it is interrupted in the middle, the transaction is rolled back and
     * the old analysis stays: better an out-of-date result than a half-rescored night, which would
     * be indistinguishable from a night without movements.
     */
    @Transaction
    open suspend fun replaceAnalysis(
        hex: String,
        paramsHash: String,
        windows: List<SleepWindowEntity>,
        events: List<ClmEventEntity>,
        results: List<PlmResultEntity>,
    ) {
        clearWindows(hex, paramsHash)
        clearEvents(hex, paramsHash)
        clearResults(hex, paramsHash)
        insertWindows(windows)
        insertEvents(events)
        insertResults(results)
    }

    @Query("SELECT * FROM plm_result WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun resultsOf(hex: String, paramsHash: String): List<PlmResultEntity>

    @Query("SELECT * FROM plm_result WHERE sessionHex = :hex")
    abstract suspend fun allResultsOf(hex: String): List<PlmResultEntity>

    @Query("SELECT * FROM sleep_window WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun windowsOf(hex: String, paramsHash: String): List<SleepWindowEntity>

    @Query("SELECT * FROM clm_event WHERE sessionHex = :hex AND paramsHash = :paramsHash ORDER BY onsetMsRel")
    abstract suspend fun eventsOf(hex: String, paramsHash: String): List<ClmEventEntity>
}

/**
 * The evening context. **This DAO deliberately has neither `@Update` nor `@Delete`** — and if
 * someone added one, the SQLite triggers laid down by [PendulumDatabase] would make it fail at
 * run time. The convention and the guarantee are both there, in that order.
 */
@Dao
interface ContextDao {

    /** `ABORT`: rewriting an already sealed context must raise, not overwrite in silence. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun seal(context: NightContextEntity)

    @Query("SELECT * FROM night_context WHERE nightKey = :nightKey")
    suspend fun find(nightKey: String): NightContextEntity?

    /**
     * The context of an already recorded night, found again through its session.
     *
     * The attachment goes through `night_session.nightKey` and not through a key equality: the
     * session knows which evening it belongs to, the context was sealed under that same evening,
     * and it is the only possible join since the sealing precedes the session by several hours.
     */
    @Query(
        """
        SELECT c.* FROM night_context c
        JOIN night_session s ON s.nightKey = c.nightKey
        WHERE s.sessionHex = :sessionHex
        """
    )
    suspend fun findForSession(sessionHex: String): NightContextEntity?

    /**
     * Is the sealed context the one of the current evening? That is the question the "Tonight"
     * card asks, and it is distinct from [find]: here a flow is wanted, because the sealing must
     * make the button disappear without having to come back to the screen.
     */
    @Query("SELECT * FROM night_context WHERE nightKey = :nightKey")
    fun observe(nightKey: String): Flow<NightContextEntity?>

    @Query("SELECT * FROM night_context ORDER BY sealedAtMs ASC LIMIT 1")
    suspend fun reference(): NightContextEntity?

    @Query("SELECT * FROM night_context ORDER BY sealedAtMs DESC")
    suspend fun all(): List<NightContextEntity>
}

@Dao
interface HcSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(snapshot: HcSnapshotEntity): Long

    @Query("SELECT * FROM hc_snapshot WHERE sessionHex = :hex ORDER BY fetchedAtMs ASC")
    suspend fun ofSession(hex: String): List<HcSnapshotEntity>

    /**
     * The number of **scheduled** attempts already made: it is this that drives the backoff, not a
     * preference.
     *
     * `attemptIndex >= 0` excludes the opportunistic reads (charger plugged in, return to the
     * foreground), which are logged with `FetchSchedule.INDEX_OPPORTUNISTIC`. Counting them would
     * advance the ladder at every plug-in: three cable round trips would exhaust the seven rungs
     * in a minute, and the application would give the night up before noon.
     */
    @Query("SELECT COUNT(*) FROM hc_snapshot WHERE sessionHex = :hex AND attemptIndex >= 0")
    suspend fun attemptCount(hex: String): Int

    @Query("SELECT * FROM hc_snapshot WHERE sessionHex = :hex ORDER BY fetchedAtMs DESC LIMIT 1")
    suspend fun latest(hex: String): HcSnapshotEntity?

    /**
     * The most recent row that actually **carries a session** — what the analysis, the report and
     * the bundle must read.
     *
     * [latest] cannot serve them, and it did. Every attempt appends a row,
     * including the ones that read nothing — Health Connect updating, permission revoked, the
     * provider mid-rewrite, a read that timed out — and those rows carry `selectedStagesCsv = ''`
     * and a null session. They exist so that the ladder advances (`attemptCount`) and so that the
     * burst guard knows when the last *attempt* was; they were never meant to replace a hypnogram
     * already obtained. And the ladder carries on after a success, deliberately, because a
     * provider can rewrite a published session: a night read at T+2 h is read again at T+4 h,
     * T+8 h, up to T+32 h.
     *
     * So a failed rung at T+4 h became "the hypnogram of the night" for three readers. The bundle
     * of that night was exported **without** its hypnogram while the database still held it two
     * rows up; the report for the physician wrote "no external hypnogram for this night … circular"
     * under a table that still showed `HEALTH_CONNECT` rows; and the next global rescore, at the
     * first parameter change, scored the night without its mask — `plmi` NULL under the new hash,
     * the night dropped out of the trend with no reason displayed. Nothing flagged the regression
     * when it happened, because `FetchSchedule.shouldRescore` sees a null record and keeps the
     * existing results: the loss only surfaced later, in a document or after a rescore, where it
     * could no longer be traced to the rung that caused it.
     *
     * The predicate mirrors what `AnalysisRunner.loadHypnogram` accepts: a chosen session with no
     * stage span (`selectedStagesCsv` empty, `sessionStartMs` set) counts as a read, an attempt
     * that chose nothing does not. Among the rows that pass, the newest wins, as before — a
     * provider rewrite read at T+8 h still replaces the T+2 h reading; only the empty rows stop
     * counting.
     */
    @Query(
        """
        SELECT * FROM hc_snapshot
        WHERE sessionHex = :hex AND (selectedStagesCsv <> '' OR sessionStartMs IS NOT NULL)
        ORDER BY fetchedAtMs DESC LIMIT 1
        """
    )
    suspend fun latestWithSession(hex: String): HcSnapshotEntity?
}

@Dao
interface QuestionnaireDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(response: QuestionnaireResponseEntity): Long

    @Query("SELECT * FROM questionnaire_response WHERE sessionHex = :hex ORDER BY answeredAtMs")
    suspend fun ofSession(hex: String): List<QuestionnaireResponseEntity>

    @Query("SELECT * FROM questionnaire_response ORDER BY answeredAtMs DESC")
    suspend fun all(): List<QuestionnaireResponseEntity>
}

@Dao
abstract class ParamDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(profile: ParamProfileEntity)

    @Query("SELECT * FROM param_profile WHERE active = 1 LIMIT 1")
    abstract suspend fun active(): ParamProfileEntity?

    @Query("SELECT * FROM param_profile WHERE active = 1 LIMIT 1")
    abstract fun observeActive(): Flow<ParamProfileEntity?>

    @Query("SELECT * FROM param_profile ORDER BY createdAtMs DESC")
    abstract suspend fun all(): List<ParamProfileEntity>

    @Query("UPDATE param_profile SET active = 0")
    abstract suspend fun deactivateAll()

    @Query("UPDATE param_profile SET active = 1 WHERE paramsHash = :hash")
    abstract suspend fun setActive(hash: String)

    /**
     * A single active profile, guaranteed by the transaction. This is what makes the
     * `WHERE paramsHash = :hash` of [TrendDao] sufficient: there are never two "current" hashes
     * between which a query could pick at random.
     */
    @Transaction
    open suspend fun activate(profile: ParamProfileEntity) {
        insertIfAbsent(profile)
        deactivateAll()
        setActive(profile.paramsHash)
    }
}

/**
 * The trends. **All** of them go through `comparable_night`, and all of them require a
 * `paramsHash`.
 *
 * There is deliberately no overload without the `paramsHash` parameter: this is guard rail 3 ("the
 * trend refuses to mix two hashes") applied at the level of the type, where a rule written down in
 * a document gets bypassed out of distraction. Mixing two hashes would mean plotting on one chart
 * figures produced by two different algorithms, and reading the jump between the two as a clinical
 * change.
 *
 * `gate` is filtered separately: a night can be perfectly comparable and have no right at all to
 * carry a figure (non-convergent mask, insufficient analysable sleep, low respiratory confidence).
 * Comparability and publishability are two distinct questions.
 */
@Dao
interface TrendDao {

    /**
     * One row per scored night, for the screens: the [preferredMask] row when the night has one,
     * the [fallbackMask] row when it has not, never both.
     *
     * A read of the view for one `maskSource` cannot serve the screens, and it did: asked for
     * `HEALTH_CONNECT`, it returned nothing for a night scored without a hypnogram, and that
     * night — analysed, stamped, its accelerometer rows in `plm_result` — appeared on no screen.
     * The why is in the KDoc of [DisplayedNightSql], the proof on SQLite in `DisplayedNightTest`.
     * That single-mask query is gone: [trendPoints] keeps its own, because the trend must never
     * see the fallback row.
     */
    @Query(DisplayedNightSql.SQL)
    suspend fun displayNights(
        paramsHash: String,
        rule: String,
        preferredMask: String,
        fallbackMask: String,
    ): List<ComparableNight>

    /**
     * The points that have the right to enter a curve. `comparable = 1`, `gate = 'FULL'` **and**
     * `plmi IS NOT NULL`.
     *
     * The third condition is not redundant with the second, even if the two coincide today. `gate`
     * bears on the *publication* — enough analysable sleep, night not truncated; `plmi IS NOT
     * NULL` bears on the *existence* of the figure. Making one depend on the other would amount to
     * letting a loosening of the gate admit a night without an index into a median, where it would
     * count as one night more while bringing no measurement at all.
     */
    @Query(
        """
        SELECT * FROM comparable_night
        WHERE paramsHash = :paramsHash AND rule = :rule AND maskSource = :maskSource
          AND comparable = 1 AND gate = 'FULL' AND plmi IS NOT NULL
        ORDER BY startWallMs ASC
        """
    )
    suspend fun trendPoints(paramsHash: String, rule: String, maskSource: String): List<ComparableNight>

    @Query(
        """
        SELECT * FROM comparable_night
        WHERE paramsHash = :paramsHash AND rule = :rule AND maskSource = :maskSource
          AND comparable = 1 AND gate = 'FULL' AND plmi IS NOT NULL
        ORDER BY startWallMs ASC
        """
    )
    fun observeTrendPoints(
        paramsHash: String,
        rule: String,
        maskSource: String,
    ): Flow<List<ComparableNight>>

    @Query("SELECT * FROM comparable_night WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    suspend fun forNight(hex: String, paramsHash: String): List<ComparableNight>

    /**
     * The hashes present in the database. If this list has more than one element after a parameter
     * change, it means `RescoreAllWorker` did not go all the way: the interface must say so rather
     * than display a truncated trend.
     */
    @Query("SELECT DISTINCT paramsHash FROM plm_result")
    suspend fun distinctHashes(): List<String>
}

/**
 * Total erasure. It erases **the raw chunks too** — that is the whole point of the button.
 *
 * Room only deletes what it knows about; the chunk files live on the disk and are erased by
 * `ChunkStore.deleteAll()`. The order matters: files first, database second. If the operation is
 * interrupted between the two, what is left is a database pointing at files that have disappeared
 * — a detectable and repairable state. The reverse order would leave orphan files whose existence
 * nothing knows about any more, that is to say health data the user believes erased.
 */
@Dao
interface MaintenanceDao {

    @Query("DELETE FROM night_session")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM questionnaire_response")
    suspend fun deleteAllQuestionnaires()

    @Query("DELETE FROM param_profile")
    suspend fun deleteAllProfiles()

    @Query("DELETE FROM night_context WHERE 1 = 1")
    suspend fun deleteAllContexts()
}
