package com.pendulum.phone.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pendulum.algo.model.PlmiResult

/**
 * Room schema of `:phone`.
 *
 * ### The principle that governs every table below
 *
 * **The raw data is the only irreplaceable thing.** A result, a mask, a CLM event: all of that is
 * recomputed from the chunks. The chunks themselves are not recomputed. The schema therefore
 * separates strictly what is *received* (`night_session`, `chunk`, `telemetry_point`,
 * `night_context`, `hc_snapshot`, `questionnaire_response`) from what is *derived*
 * (`sleep_window`, `clm_event`, `plm_result`). Everything derived carries a `paramsHash` and can
 * be erased and rebuilt; nothing received can.
 *
 * Direct consequence for migrations: see the KDoc of [PendulumDatabase], which carries the two
 * rules to hold — a migration never loses a column of raw data, and a view is only recreated when
 * it is changed. `fallbackToDestructiveMigration` is forbidden, because it would destroy exactly
 * the half that cannot be rebuilt.
 *
 * The database is at **v1** and carries no migration: the four that existed were removed on
 * 7 August 2026, the application having never been installed anywhere. The detail of what they did
 * is kept in the KDoc of [PendulumDatabase].
 */

/**
 * One night. Primary key = `sessionHex`, the identifier the watch chose and which appears in the
 * `DataItem` paths — no auto-generated key here: that is what makes ingestion idempotent without
 * a mapping table.
 *
 * @param state state as seen from the phone. `OPEN` -> `STALE` -> `TRUNCATED` is driven by
 *   `WatchdogWorker`; `CLOSED` comes from the watch.
 * @param tzOffsetStartMin,tzOffsetEndMin local UTC offsets at the start and at the end. Storing
 *   **both** is what allows a daylight-saving night to be detected: it is exactly
 *   `tzOffsetStartMin <> tzOffsetEndMin`, and such a night must not enter a trend (guard rail 4
 *   of `SPEC-v2.md` section 3). No duration is ever computed from them: durations come from
 *   `SensorEvent.timestamp`.
 * @param analysableMin minutes actually analysable (valid segments, outside blind zones, outside
 *   off-body). This is the "at least 4 h analysable" criterion of the [ComparableNight] view, and
 *   it is written by the analysis, not by ingestion.
 * @param gainCalG gain reference of the night. Used by the comparability criterion: a strap
 *   tightened differently changes the amplitude by a factor of 2 to 3 and makes the night
 *   incomparable without anything else signalling it.
 * @param truncated the night stopped without a clean close. Still analysable, but its index is
 *   biased upwards in a way that cannot be corrected: outside the trend.
 */
@Entity(
    tableName = "night_session",
    indices = [Index("startWallMs"), Index("nightKey")],
)
data class NightSessionEntity(
    @PrimaryKey val sessionHex: String,
    /**
     * The evening this night attaches to, `YYYY-MM-DD`, rolling over at noon
     * (`WirePaths.nightKey`). It is through this that the night finds again the context sealed
     * before it existed — the attachment cannot be made through `sessionHex`, which is only known
     * once the watch announces the session, that is to say after the sealing.
     *
     * Indexed: it is the join of the `comparable_night` view, so it is walked once per night and
     * per trend read.
     */
    val nightKey: String = "",
    val startWallMs: Long,
    val plannedStopWallMs: Long,
    val endWallMs: Long? = null,
    val zoneId: String,
    val tzOffsetStartMin: Int,
    val tzOffsetEndMin: Int,
    val nominalRateHz: Int,
    val modeFlags: Int,
    val state: String,
    val stopReason: String? = null,
    val totalChunks: Int? = null,
    val lastChunkArrivalMs: Long = 0L,
    val batteryPctLast: Int? = null,

    // --- filled in by the analysis ---
    val analyzedAtMs: Long? = null,
    val algoVersion: String? = null,
    val paramsHash: String? = null,
    val fsMeasuredHz: Double? = null,
    val sampleCount: Long = 0L,
    val gapCount: Int = 0,
    val gapTotalMs: Long = 0L,
    val analysableMin: Double = 0.0,
    val gainCalG: Double? = null,
    val gainSource: String? = null,
    val truncated: Boolean = false,
    val integrityRejectedFraction: Double = 0.0,

    /**
     * Has the result been revealed? Guard rail 2: on waking the screen says "night recorded,
     * quality OK" and nothing else; the reveal is **logged**, here, and exported. This field
     * belongs to the database and not to a preference, because it must survive a cache erasure and
     * go out in the export.
     */
    val revealedAtMs: Long? = null,
)

/**
 * One received chunk. `UNIQUE(sessionHex, idx)` is the pivot of the whole protocol: ingestion does
 * an `INSERT OR IGNORE` on it, so receiving the same chunk twice is a silent no-op and **every**
 * re-emission path (lost ack, sweep after a Bluetooth cut, resumption after a phone reboot) is
 * safe without a counter and without state.
 *
 * @param crc32 CRC-32 recomputed over the **received** bytes, and compared to the one announced by
 *   the watch before any insertion. The per-block CRC-16 covers the content; this one covers the
 *   transport, and nothing else covers it.
 * @param complete the end-of-file marker has been read and verified. **An incomplete chunk is
 *   never acknowledged**: acknowledging it would make the watch delete a partial file.
 */
@Entity(
    tableName = "chunk",
    indices = [
        Index(value = ["sessionHex", "idx"], unique = true),
        Index("sessionHex"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val idx: Int,
    val path: String,
    val size: Int,
    val crc32: Long,
    val sampleCount: Int,
    val tFirstNs: Long,
    val tLastNs: Long,
    val flagsOr: Int,
    val complete: Boolean,
    val receivedAtMs: Long,
)

/**
 * The state of the device at one instant of the night — one point per minute, five per complete
 * chunk.
 *
 * ### This is not a derived table
 *
 * It sits here with `chunk` and `night_context`, and not with `sleep_window` or `clm_event`,
 * because it shares with them the property that governs this whole file: **it cannot be
 * reconstituted**. The points live in the `TLM!` blocks of the chunks, and the chunks are erased
 * from the watch as soon as they are acknowledged; a night whose telemetry were lost in the
 * database would keep its signal but no trace at all of the battery, the jitter or the clipping
 * that decided how it reads. Direct consequence: no migration deletes it, and rescoring does not
 * touch it — it carries no `paramsHash`, because no parameter produces it.
 *
 * Technically the raw data could be read again: the chunk files stay on the phone. But re-reading
 * seven 90 kB files on every screen opening to recover forty points is exactly the computation
 * that `nightDetail` already refuses to do for the envelope. The table is the extraction, done
 * once, at ingestion.
 *
 * ### The idempotence key is not `sensorTsNs`, and this is measured and not supposed
 *
 * A chunk can arrive twice — lost ack, sweep after a cut, phone restart — and ingestion must then
 * be a silent no-op, exactly as for `chunk`. A unique key per point is therefore needed.
 *
 * `sensorTsNs` cannot carry it: it is **0 as long as no sample has been seen**
 * (`TelemetryPoint.sensorTsNs`), that is to say for the first points of a session, and two points
 * at zero would be conflated. It is `elapsedRealtimeNs` that is unique: a monotonic clock read
 * once a minute, never twice the same value within a session. Uniqueness is therefore
 * `(sessionHex, elapsedRealtimeNs)`.
 *
 * `(sessionHex, sensorTsNs)` stays **indexed**, because that is the reading order: the metrology
 * band places each point on the sample time base, the only one that timestamps the movements, and
 * it reads the night in that order.
 *
 * @param sessionHex the night. `CASCADE`: erasing a night erases its telemetry, like its chunks.
 * @param charging a point taken while charging must be **taken out** of any battery slope
 *   regression. It is kept — it is a fact of the night — but
 *   [com.pendulum.phone.ui.model.BatterySlope] removes it before computing anything.
 *
 * The other fields are those of [com.pendulum.format.TelemetryPoint], without renaming and without
 * unit conversion: the table is a projection of the `TLM!` block, and any unit converted here
 * would be a second convention to hold. What each one explains is documented once only, in the
 * KDoc of the format.
 */
@Entity(
    tableName = "telemetry_point",
    indices = [
        Index(value = ["sessionHex", "elapsedRealtimeNs"], unique = true),
        Index(value = ["sessionHex", "sensorTsNs"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TelemetryPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
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
)

/**
 * A sleep window, whatever its provenance. Derived: erased and rebuilt at every rescore.
 *
 * @param source `ACCEL_IMMOBILITY | HEALTH_CONNECT | DIARY | FUSED`, values of
 *   `com.pendulum.algo.model.MaskSource`.
 * @param sourcePackage `dataOrigin.packageName` when the source is Health Connect. **Without it,
 *   an abnormal night cannot be debugged**: one does not even know which application wrote the
 *   hypnogram that was used. It is also what the screens name as the sleep source of the night,
 *   through `comparable_night.sourcePackage`: it is the origin of the denominator that was
 *   **actually used** under this `paramsHash`, which neither the preference nor the last
 *   `hc_snapshot` row is. It is written from the same snapshot row the analysis took its windows
 *   from, so the mask and its name cannot come from two different readings.
 * @param startMsRel,endMsRel milliseconds **relative to the start of the session**, never wall
 *   clocks: an NTP resynchronisation in the middle of the night, or a daylight-saving change,
 *   would shift everything else.
 */
@Entity(
    tableName = "sleep_window",
    indices = [Index(value = ["sessionHex", "source"])],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SleepWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val source: String,
    val stage: String,
    val startMsRel: Long,
    val endMsRel: Long,
    val sourcePackage: String? = null,
    val paramsHash: String,
)

/**
 * A candidate movement. Derived.
 *
 * The **rejected ones are stored too** (with their reason): the quality report needs them, and
 * above all, comparing the rejections from one night to the next is the only way to see that a
 * setting changed the behaviour of the detector rather than the sleep of the sleeper.
 */
@Entity(
    tableName = "clm_event",
    indices = [Index(value = ["sessionHex", "paramsHash"])],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ClmEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val paramsHash: String,
    val onsetMsRel: Long,
    val durationMs: Int,
    val peakAmpG: Double,
    val medianAmpG: Double,
    val noiseFloorG: Double,
    val thresholdOnG: Double,
    val tiltChangeDeg: Double,
    val flags: Int,
    val rejectReason: String? = null,
    val inSeriesAasm: Boolean = false,
    val inSeriesWasm: Boolean = false,
    val stageAtOnset: String? = null,
    val duringWake: Boolean = false,
)

/**
 * One result. **Four rows per night and per `paramsHash`**: 2 rule sets x 2 masks. The four exist
 * to make the discrepancy *visible* rather than choosing in silence — it is the discrepancy
 * between the accelerometric mask and the Health Connect mask that says how far the first one can
 * be trusted.
 *
 * **Two rows, not four, when Health Connect returned nothing** — and that is the default case, not
 * the exception: a user without a sleep application, and every night in the hours before its
 * hypnogram arrives. The `ACCEL_IMMOBILITY` rows are the only ones that exist for every scored
 * night; a reader that asks the table (or the `comparable_night` view built on it) for
 * `HEALTH_CONNECT` alone sees nothing of such a night. The screens went through exactly such a
 * read and lost every night without a hypnogram; they now go through `TrendDao.displayNights`,
 * which falls back to the accelerometer row when the external one does not exist.
 *
 * @param paramsHash the hash of the parameters that produced this figure. `UNIQUE(sessionHex,
 *   paramsHash, rule, maskSource)`: two hashes coexist in the table, never in a trend — see
 *   [ComparableNight] and `TrendDao`.
 * @param gate what the analysis **authorises** to publish (`FULL | TRUNCATED_NO_TREND | NO_PLMI`).
 *   Evaluated by the code, never bypassable from the interface.
 * @param fundamentalSec the follow-up metric (`SPEC-v2.md` section 5): fundamental rhythm in
 *   seconds, without a denominator, hence without circularity, and twelve times more stable from
 *   one night to the next than the hourly count.
 * @param missRate estimated miss rate. **To be read next to `fundamentalSec` and never without
 *   it**: a rate that jumps from one night to the next signals two nights that are not comparable,
 *   and a rate close to 0.5 together with a weak fundamental peak suggests a left/right
 *   alternation.
 */
@Entity(
    tableName = "plm_result",
    indices = [
        Index(value = ["sessionHex", "paramsHash", "rule", "maskSource"], unique = true),
        Index(value = ["paramsHash"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlmResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val paramsHash: String,
    val rule: String,
    val maskSource: String,
    val computedAtMs: Long,
    val algoVersion: String,

    val plmsCount: Int,
    val plmwCount: Int,
    val isolatedCount: Int,
    val shortImiCount: Int,

    val tstMin: Double,
    val analysableTstMin: Double,
    val sptMin: Double,
    val wasoMin: Double,

    /**
     * The six rates, **nullable**, and `null` means "this night carries none".
     *
     * `safeRate` returns `NaN` as soon as the denominator does not exist, and that is the right
     * decision — a rate without a denominator is not zero. But SQLite does not know `NaN`: binding
     * converts it to `NULL`, and a `NOT NULL` column then refused the insertion
     * (`SQLiteConstraintException: NOT NULL constraint failed: plm_result.plmi`). Since
     * `AnalyzeWorker` returns `retry()` on an exception, the night was retried endlessly and
     * appeared **nowhere**: this is the default path of every user without a Health Connect
     * hypnogram, not an edge case.
     *
     * Writing `0.0` instead would have been worse than the defect: "0 movements per hour" is a
     * measurement, and announcing it for a night where nothing could be measured is a clinical
     * lie. `null` propagates all the way to the dash on the screen
     * ([com.pendulum.phone.ui.model.Mapping.DASH]), which says exactly what happened.
     *
     * The conversion happens in a single place: [PlmResultEntity.from].
     */
    val plmi: Double?,
    val plmiSpt: Double?,
    val plmw: Double?,
    val plmiFirstHalf: Double?,
    val plmiSecondHalf: Double?,
    val plmiRespWorstCase: Double?,

    /** Never `NaN`: `Periodicity.fromIntervals` returns `0.0` with no interval, and carries its `valid`. */
    val periodicityIndex: Double,
    val periodicityValid: Boolean,

    /**
     * The four outputs of the deconvolution, nullable for the same reason as the rates above —
     * `Rhythm.emptyFit` sets them all to `NaN` when the fit is refused.
     *
     * **Refusal is the frequent case**, not the exception: `RhythmMeasurementTest` measures 2
     * accepted fits out of 20 nominal nights. The `NOT NULL` column therefore made the insertion
     * of most nights fail, including those whose PLMI did exist.
     */
    val fundamentalSec: Double?,
    val muLog: Double?,
    val sigmaLog: Double?,
    val missRate: Double?,
    val alternationSuspect: Boolean,
    val rhythmConverged: Boolean,
    val rhythmValid: Boolean,

    val truncatedSeriesDropped: Int,
    val independence: String,
    val gate: String,
    val floorMode: String,
) {
    companion object {

        /**
         * The one factory for a result row, and **the one place where a `NaN` becomes a `NULL`**.
         *
         * It exists because the conversion must live at the persistence boundary and nowhere else.
         * Two callers build this row — the real analysis and the bench seeding — and a conversion
         * copied into each of them is a conversion of which one of the two copies will end up
         * forgetting a field. The forgotten field would not show: it would make the insertion
         * fail, `AnalyzeWorker` would return `retry()`, and the night would disappear in silence.
         * That is precisely the defect being repaired.
         */
        fun from(
            sessionHex: String,
            paramsHash: String,
            computedAtMs: Long,
            algoVersion: String,
            r: PlmiResult,
        ): PlmResultEntity = PlmResultEntity(
            sessionHex = sessionHex,
            paramsHash = paramsHash,
            rule = r.rule.name,
            maskSource = r.maskSource.name,
            computedAtMs = computedAtMs,
            algoVersion = algoVersion,
            plmsCount = r.plmsCount,
            plmwCount = r.plmwCount,
            isolatedCount = r.isolatedCount,
            shortImiCount = r.shortImiCount,
            tstMin = r.tstMin,
            analysableTstMin = r.analysableTstMin,
            sptMin = r.sptMin,
            wasoMin = r.wasoMin,
            plmi = r.plmi.ifDefined(),
            plmiSpt = r.plmiSpt.ifDefined(),
            plmw = r.plmw.ifDefined(),
            plmiFirstHalf = r.plmiFirstHalf.ifDefined(),
            plmiSecondHalf = r.plmiSecondHalf.ifDefined(),
            plmiRespWorstCase = r.plmiRespWorstCase.ifDefined(),
            periodicityIndex = r.pi.periodicityIndex,
            periodicityValid = r.pi.valid,
            fundamentalSec = r.rhythm.fundamentalSec.ifDefined(),
            muLog = r.rhythm.muLog.ifDefined(),
            sigmaLog = r.rhythm.sigmaLog.ifDefined(),
            missRate = r.rhythm.missRate.ifDefined(),
            alternationSuspect = r.rhythm.alternationSuspect,
            rhythmConverged = r.rhythm.converged,
            rhythmValid = r.rhythm.valid,
            truncatedSeriesDropped = r.truncatedSeriesDropped,
            independence = r.independence.name,
            gate = r.gate.name,
            floorMode = r.floorMode.name,
        )
    }
}

/**
 * The translation from the convention of `:algo` to that of SQLite: `NaN` (and infinity, which
 * `safeRate` can produce if the denominator becomes infinitesimal) means "no value", and "no
 * value" is written `NULL`.
 *
 * `night_session.gainCalG` already applied this rule by hand; it now has a name.
 */
internal fun Double.ifDefined(): Double? = takeIf { it.isFinite() }

/**
 * The evening context: dose, bearing leg, strap, alone in bed, coffee, alcohol.
 *
 * **Append-only table, and the prohibition is structural**: two SQLite triggers
 * (`night_context_no_update`, `night_context_no_delete`, laid down by [PendulumDatabase]) make
 * every `UPDATE` and every `DELETE` fail. The DAO exposes neither `@Update` nor `@Delete`, but a
 * DAO can be modified; a trigger cannot — that is what makes the difference between a convention
 * and a guarantee.
 *
 * **Why this is not paranoia**: the failure mode of this project is not fraud, it is the good-faith
 * touch-up. Remembering the next morning, after seeing a high figure, that "in fact I took the
 * dose later" and correcting it, is enough to manufacture the very correlation being looked for.
 * The sealing must precede the measurement: the watch refuses to start as long as this row is not
 * written.
 *
 * ### Why the key is the night and not the session
 *
 * The sealing **precedes** the night: at the moment the user fills in the form, no session exists,
 * and there is therefore no `sessionHex` to put down. v1 nevertheless required that column as the
 * primary key, which made sealing literally impossible — and since the triggers forbid every
 * `UPDATE`, it could not be filled in afterwards either.
 *
 * The key is therefore the **night key** of `WirePaths.nightKey` — local date of the evening,
 * rolling over at noon — that is to say exactly the string carried by the path of the `DataItem`
 * published to the watch. One single convention, shared by the database and by the protocol: a
 * mismatch between the two would be indistinguishable from an absence of context, and the watch
 * would refuse to start without being able to explain anything.
 *
 * @param nightKey `YYYY-MM-DD` of the evening. A bed time at 1:30 attaches to the previous day.
 * @param sealedAtMs instant of the sealing. Must be **earlier** than `night_session.startWallMs`.
 * @param leg `LEFT | RIGHT`. Comparability criterion: a one-sided sensor sees a doubled interval
 *   when movements alternate, so changing leg changes the measurement.
 * @param strapId identifier of the strap used. Same thing: the SPEC measures amplitudes x2 to x3
 *   depending on the play of the strap.
 * @param aloneInBed a bed partner transmits their own movements through the mattress.
 */
@Entity(tableName = "night_context")
data class NightContextEntity(
    @PrimaryKey val nightKey: String,
    val sealedAtMs: Long,
    val leg: String,
    val strapId: String,
    val aloneInBed: Boolean,
    val bedTimeLocalMs: Long? = null,
    val riseTimeLocalMs: Long? = null,
    val medicationJson: String,
    val caffeineAfter16h: Boolean = false,
    val alcoholUnits: Double = 0.0,
    val unusualExercise: Boolean = false,
    val notes: String? = null,
)

/**
 * Snapshot of what Health Connect **actually returned**, before any interpretation.
 *
 * It exists for a precise and verified reason: some providers do not merely insert, they
 * **rewrite** an already published session ("inserts or *updates*", Samsung developer FAQ). A
 * night read at T+1 h can therefore differ from the same night at T+8 h. Without this snapshot, a
 * figure that changes between two consultations is inexplicable; with it, comparing two rows shows
 * it in one query.
 *
 * Append-only by use: every read adds a row, none modifies one.
 *
 * @param lastModifiedTimeMs `metadata.lastModifiedTime` as returned by Health Connect. This is the
 *   field that betrays the rewrite.
 * @param recordsJson raw serialisation of the sessions kept **and** excluded, with their origin.
 *   Deliberately redundant with `sleep_window`: `sleep_window` is derived and erased at rescore,
 *   this one never is.
 */
@Entity(
    tableName = "hc_snapshot",
    indices = [Index(value = ["sessionHex", "fetchedAtMs"])],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HcSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val fetchedAtMs: Long,
    val attemptIndex: Int,
    val selectedPackage: String? = null,
    val selectedRecordId: String? = null,
    val lastModifiedTimeMs: Long? = null,
    val sessionStartMs: Long? = null,
    val sessionEndMs: Long? = null,
    val stageCount: Int = 0,
    val distinctStageTypes: Int = 0,
    val stageCoverageMin: Double = 0.0,
    val overlapFraction: Double = 0.0,
    /** TST returned by `aggregate(SLEEP_DURATION_TOTAL)`, deduplicated by the system. */
    val aggregateTstMin: Double? = null,
    val originCount: Int = 0,

    /**
     * The hypnogram that was kept, encoded `startMs:endMs:type;...` in UTC wall clock.
     *
     * A CSV and not JSON, for a reason that is not economy: **this is the column that is read
     * again** at every rescore, and a format that can be read back in ten lines without a library
     * cannot start failing differently depending on the version of a parser. `recordsJson`, on the
     * other hand, is a trace meant for the human eye and is never parsed again — if it were, it
     * would have to be versioned.
     */
    val selectedStagesCsv: String = "",

    val recordsJson: String,
    val outcome: String,
)

/** One questionnaire response. Append-only by use: a sitting is added, it is not corrected. */
@Entity(
    tableName = "questionnaire_response",
    indices = [Index(value = ["sessionHex"]), Index(value = ["kind", "answeredAtMs"])],
)
data class QuestionnaireResponseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String? = null,
    val kind: String,
    val answeredAtMs: Long,
    val answersJson: String,
    val score: Double? = null,
)

/**
 * One set of algorithm parameters, identified by its hash.
 *
 * Guard rail 3: **no per-night setting**. A parameter change is global, creates a new row here, and
 * triggers `RescoreAllWorker`, which recomputes *all* the nights from the raw data. The trend then
 * refuses to mix two hashes — this is not a display policy, it is a `WHERE paramsHash = :hash` of
 * which no unfiltered variant exists in `TrendDao`.
 *
 * @param active a single active profile at a time. Uniqueness is held by [ParamDao.activate],
 *   which deactivates everything in the same transaction.
 */
@Entity(tableName = "param_profile")
data class ParamProfileEntity(
    @PrimaryKey val paramsHash: String,
    val createdAtMs: Long,
    val algoVersion: String,
    val paramsJson: String,
    val active: Boolean,
    val label: String? = null,
)
