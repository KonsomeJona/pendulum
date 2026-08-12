package com.pendulum.phone.bench

import android.content.Context
import android.util.Log
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.Stage
import com.pendulum.algo.synth.GroundTruth
import com.pendulum.algo.synth.TruthKind
import com.pendulum.algo.synth.truthAsClms
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.DataEraser
import com.pendulum.phone.bench.Campaign.Kind
import com.pendulum.phone.data.PendulumPreferences
import com.pendulum.phone.db.ClmEventEntity
import com.pendulum.phone.db.HcSnapshotEntity
import com.pendulum.phone.db.NightContextEntity
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.ParamProfileEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.db.SleepWindowEntity
import com.pendulum.phone.db.eraseEverything
import com.pendulum.phone.health.Hypnogram
import com.pendulum.phone.health.SleepSourceSelector
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.work.AnalysisParams
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The bench seeding: filling the database with synthetic nights so as to make reachable the
 * screens that a fresh install leaves behind a closed gate.
 *
 * ### Why this file lives in `src/debug/` and has no right to leave it
 *
 * It manufactures measurements. An application that knows how to manufacture its own measurements
 * and that is distributed is an application in which **no displayed figure proves anything any
 * more**: a forgotten code path, a badly tested flag, a broadcast received by mistake is enough
 * for a user's campaign to hold nights that never took place — and nothing, neither on screen nor
 * in the export, tells them apart from the real ones. That is exactly the defect that moving
 * `PreviewData` to `src/debug` had corrected, and the reason why the bench
 * (`docs/workings/BENCH-LOG.md` §14.6) had **refused** to manufacture nights in order to
 * photograph the five screens with no gate.
 *
 * The guarantee is therefore not an `if (BuildConfig.DEBUG)` guard, which would leave the class
 * inside the release APK: it is the source set. The release variant does not compile this
 * directory, does not know these classes, and its manifest does not carry the receiver that calls
 * them.
 *
 * ### What is manufactured, and what is not
 *
 * What is manufactured: the **derived** — events, sleep windows, results — and the little of the
 * received that it takes for the derived to stand up: the session row, the sealed evening context,
 * the Health Connect snapshot, the telemetry.
 *
 * What is **not**: the raw chunks. Neither the files, nor the rows of the `chunk` table. Two
 * reasons, and the second is the more important one:
 *
 *  1. A `chunk` row pointing at a missing file is a lie that the export and the analysis both
 *     trust — `NightExporter` would produce a bundle that calls itself complete and is not.
 *  2. **With no chunk, a rescore destroys nothing.** `AnalysisRunner.analyse` returns on
 *     `files.isEmpty()` before touching the derived tables: seeded nights therefore survive a
 *     `RescoreAllWorker` triggered by a parameter change. The opposite was measured and it is
 *     fatal — analysed for real, a synthetic night yields `gate = NO_PLMI` (the accelerometric
 *     mask on its own carries no independent denominator), and the seeding would erase itself a
 *     few seconds later.
 *
 * A visible and accepted consequence: the metrology band of a night detail shows its battery gauge
 * but no time band, because those are placed on the sensor time base, whose origin
 * (`chunk.tFirstNs`) does not exist here.
 *
 * ### Why `NightSynth` rather than numbers written by hand
 *
 * `:algo` already carries a generator of realistic nights, the one the whole non-regression suite
 * takes as its reference. Its `SeriesSpec`, its log-normal amplitude law, its distractor families
 * and its ground truth produce intervals, durations and counts that have the shape of real nights
 * — and it is that shape which brings out the display defects a run of round numbers hides (an
 * axis that does not move, a confidence interval that overlaps nothing, a median exactly on a
 * threshold). The exact path is described by [Campaign], which also carries the one thing this
 * file cannot do: verify itself on the JVM.
 *
 * What is short-circuited is only the 1 to 5 segment — signal to CLM. It is also the only
 * expensive one: measured on 7 August 2026 on Apple Silicon, an 8 h night **generates** in 0.4 s
 * and **analyses** in 11 s. On the Pixel Fold, a seeded night costs 1.3 s in the foreground.
 */
object Seeding {

    private const val TAG = "PendulumBench"

    /** Eligible nights seeded by default. Three would do; seven give a chart. */
    const val DEFAULT_NIGHTS = 7

    /**
     * Ceiling on the `nights` extra. It is not a limit of the database: it is the reminder that a
     * night costs a second and a half of synthesis on a phone, and that a campaign of a hundred
     * nights would take several minutes during which the application has to stay in the
     * foreground.
     */
    const val MAX_NIGHTS = 30

    /**
     * The package that declares itself the source of the hypnogram.
     *
     * It does not have to exist on the device: it is a string recorded in `hc_snapshot`, which the
     * interface merely renders readable. Choosing a plausible one rather than `com.example` is
     * what makes it possible to judge the "sleep source" row on screen at its true length.
     */
    private const val SLEEP_PACKAGE = "com.google.android.apps.fitness"

    /** Strap reference, identical for the whole campaign: otherwise everything comes out `STRAP_CHANGED`. */
    private const val STRAP = "3"

    /** Wearing side, identical for the whole campaign: otherwise everything comes out `LEG_CHANGED`. */
    private const val LEG = "LEFT"

    // -------------------------------------------------------------------------------------
    // Entry points
    // -------------------------------------------------------------------------------------

    /**
     * Fills the database with [eligibleNights] eligible nights, plus one provisional night and one
     * excluded night. The chronological order is the one of [Campaign.kinds], which explains why
     * it is not immaterial.
     */
    suspend fun seed(context: Context, eligibleNights: Int) {
        val eligibleCount = eligibleNights.coerceIn(1, MAX_NIGHTS)
        val db = PendulumDatabase.get(context)

        // Start again from an empty database. This is not a comfort precaution: `night_context` is
        // append-only and protected by a SQLite trigger, so re-seeding without erasing would make
        // the first `seal` fail on a night key already taken, in the middle of the campaign.
        clearDatabase(context, db)

        val params = AnalysisParams.DEFAULT
        db.paramDao().activate(
            ParamProfileEntity(
                paramsHash = params.paramsHash,
                createdAtMs = System.currentTimeMillis(),
                algoVersion = params.algoVersion,
                paramsJson = params.toJson(),
                active = true,
            )
        )

        val kinds = Campaign.kinds(eligibleCount)
        val zone = ZoneId.systemDefault()
        val nightStarts = starts(kinds.size, zone)

        for ((index, kind) in kinds.withIndex()) {
            val startedAt = System.currentTimeMillis()
            val night = Campaign.night(kind, index, params)
            write(db, params, night, index, nightStarts[index], zone)
            val elapsed = System.currentTimeMillis() - startedAt
            Log.i(
                TAG,
                "night ${index + 1}/${kinds.size} ($kind) in $elapsed ms: " +
                    "gate=${night.primary.gate} rhythm=${night.primary.rhythm.valid}",
            )
        }

        // The onboarding, without which the application reopens on "Step 1 of 6" and nothing that
        // has just been written is reachable. The strap reference and the sleep source are set
        // along with it, because that same onboarding asks for them and the home screen reads them.
        val prefs = PendulumPreferences(context)
        prefs.setOnboardingStep(PendulumPreferences.ONBOARDING_STEPS)
        prefs.setStrapReference(STRAP)
        prefs.setPreferredSleepSource(SLEEP_PACKAGE)

        Log.i(TAG, "seeding finished: ${kinds.size} nights, hash ${params.paramsHash}")
    }

    /**
     * Returns the device to its fresh-install state.
     *
     * `DataEraser` covers the database, the chunk files and the scheduled work; it does not touch
     * the `DataStore`, which carries the onboarding state. Without the second half, "erase
     * everything" would leave an application with no nights that nevertheless believes itself
     * configured — which is the fresh state of nothing.
     */
    suspend fun erase(context: Context) {
        DataEraser.eraseEverything(context)
        val prefs = PendulumPreferences(context)
        prefs.setOnboardingStep(0)
        prefs.setStrapReference("")
        prefs.setPreferredSleepSource(null)
        prefs.setTheme(PendulumPreferences.THEME_DARK)
        Log.i(TAG, "database and preferences erased: the device is back to its fresh state")
    }

    // -------------------------------------------------------------------------------------
    // Writing one night
    // -------------------------------------------------------------------------------------

    private suspend fun write(
        db: PendulumDatabase,
        params: AnalysisParams,
        night: Campaign.Night,
        index: Int,
        startWallMs: Long,
        zone: ZoneId,
    ) {
        val truth = night.synth.truth
        val hex = "%016x".format(Campaign.seed(index) * 31 + index)
        val endWallMs = startWallMs + Math.round(night.recordedDurationMin * 60_000.0)
        val nightKey = WirePaths.nightKey(startWallMs, zone)
        val offsetMin = zone.rules.getOffset(Instant.ofEpochMilli(startWallMs)).totalSeconds / 60

        // --- the received --------------------------------------------------------------
        db.nightDao().insertIfAbsent(
            NightSessionEntity(
                sessionHex = hex,
                nightKey = nightKey,
                startWallMs = startWallMs,
                plannedStopWallMs = startWallMs + 9 * 3_600_000L,
                endWallMs = endWallMs,
                zoneId = zone.id,
                tzOffsetStartMin = offsetMin,
                tzOffsetEndMin = offsetMin,
                nominalRateHz = 50,
                modeFlags = 0,
                state = "CLOSED",
                stopReason = if (night.truncated) "BATTERY" else "PLANNED",
                // Deliberately `null`: no watch announced a chunk count, and writing a number
                // against an empty `chunk` table would make the waking status band say that a
                // transfer is under way forever.
                totalChunks = null,
                lastChunkArrivalMs = endWallMs,
                batteryPctLast = 100 - (night.recordedDurationMin / 12.0).toInt(),
            )
        )

        // The evening context is sealed **before** the night: that is guard rail 1, and the table
        // makes it structural (two SQLite triggers forbid every UPDATE and every DELETE). There is
        // nothing to work around here — we insert once, in the right order.
        db.contextDao().seal(
            NightContextEntity(
                nightKey = nightKey,
                sealedAtMs = startWallMs - 40 * 60_000L,
                leg = LEG,
                strapId = STRAP,
                // The only exclusion reason used, and it is a plausible one: a night with two
                // people in the bed is not comparable, a partner passing their movements on
                // through the mattress. The other reasons (leg or strap changed) would presuppose
                // a badly kept campaign rather than an ordinary night.
                aloneInBed = night.kind != Kind.EXCLUDED,
                bedTimeLocalMs = startWallMs,
                riseTimeLocalMs = endWallMs,
                medicationJson = "[]",
                caffeineAfter16h = index % 3 == 1,
                alcoholUnits = if (index % 4 == 2) 1.0 else 0.0,
                unusualExercise = index % 5 == 3,
            )
        )

        val stages = truth.mask.windows.map {
            SleepSourceSelector.StageSpan(
                startMs = startWallMs + it.startMsRel,
                endMs = startWallMs + it.endMsRel,
                stageType = hcType(it.stage),
            )
        }
        db.hcSnapshotDao().append(
            HcSnapshotEntity(
                sessionHex = hex,
                fetchedAtMs = endWallMs + 2 * 3_600_000L,
                attemptIndex = 1,
                selectedPackage = SLEEP_PACKAGE,
                selectedRecordId = "bench-$hex",
                lastModifiedTimeMs = endWallMs + 90 * 60_000L,
                sessionStartMs = startWallMs,
                sessionEndMs = endWallMs,
                stageCount = stages.size,
                distinctStageTypes = stages.map { it.stageType }.distinct().size,
                stageCoverageMin = truth.mask.sptMin,
                overlapFraction = 1.0,
                aggregateTstMin = truth.mask.tstMin,
                originCount = 1,
                selectedStagesCsv = Hypnogram.encodeCsv(stages),
                recordsJson = """{"bench":true,"source":"$SLEEP_PACKAGE"}""",
                outcome = "SELECTED",
            )
        )

        db.telemetryDao().insertAllIfAbsent(
            Telemetry.points(hex, night.spec, truth, night.recordedDurationMin),
        )

        // --- the derived ---------------------------------------------------------------
        // Same factory as the real analysis. The bench has to go through the `NaN` -> `NULL`
        // conversion exactly as production does: a synthetic night that inserted itself where a
        // real one fails would make the bench miss the one defect it is there to catch.
        val results = night.measurements.map { p ->
            PlmResultEntity.from(
                sessionHex = hex,
                paramsHash = params.paramsHash,
                computedAtMs = endWallMs + 3 * 3_600_000L,
                algoVersion = params.algoVersion,
                r = p,
            )
        }

        val windows = night.mask.windows.map {
            SleepWindowEntity(
                sessionHex = hex,
                source = MaskSource.HEALTH_CONNECT.name,
                stage = it.stage.name,
                startMsRel = it.startMsRel,
                endMsRel = it.endMsRel,
                sourcePackage = SLEEP_PACKAGE,
                paramsHash = params.paramsHash,
            )
        }

        db.derivedDao().replaceAnalysis(
            hex = hex,
            paramsHash = params.paramsHash,
            windows = windows,
            events = eventsOf(hex, params.paramsHash, truth),
            results = results,
        )

        db.nightDao().writeAnalysisSummary(
            hex = hex,
            atMs = endWallMs + 3 * 3_600_000L,
            algoVersion = params.algoVersion,
            paramsHash = params.paramsHash,
            fsHz = truth.fsRealHz,
            sampleCount = night.synth.blocks.sumOf { it.x.size.toLong() },
            gapCount = truth.gaps.size,
            gapTotalMs = truth.gaps.sumOf { Math.round(it.durationSec * 1000.0) },
            analysableMin = night.analysableMin,
            gainCalG = truth.gainCalG.toDouble(),
            gainSource = "RITUAL",
            truncated = night.truncated,
            rejectedFraction = 0.0,
        )
    }

    /**
     * The start instants, from the oldest to the most recent.
     *
     * ### The bed time is a datum of the screen, not a detail
     *
     * The first version anchored the last night "six hours before now", which was convenient and
     * wrong: seeded at 18:00, the campaign displayed nights running from **04:29 to 12:14**. Not
     * one figure was incorrect, and yet the night list was unusable as a capture — nobody goes to
     * bed at half past four in the morning seven days in a row.
     *
     * The anchor is therefore a bed time, [BED_TIME_HOUR], on the previous day. The extra one-day
     * shift covers the only case where that would not be enough: seeding in the morning, before
     * last night is over — the list would then show a night that ends in the future.
     *
     * Stepping back one day per night gives the night key — which rolls over at noon — a distinct
     * value per night, as the primary key of `night_context` requires.
     */
    private fun starts(count: Int, zone: ZoneId): List<Long> {
        val now = ZonedDateTime.now(zone)
        var last = now.toLocalDate().minusDays(1)
            .atTime(BED_TIME_HOUR, 10)
            .atZone(zone)
        if (last.plusHours(MAX_DURATION_H) > now) last = last.minusDays(1)

        return (0 until count).map { index ->
            last
                .minusDays((count - 1 - index).toLong())
                // Nobody goes to bed at the same minute two evenings in a row, and a column of
                // identical times in the night list is noticed immediately.
                .minusMinutes((index * 13L) % 47)
                .toInstant()
                .toEpochMilli()
        }
    }

    /** 23:00: the bed time of the campaign, to within a few tens of minutes. */
    private const val BED_TIME_HOUR = 23

    /** Longest duration of a campaign night, rounded up. See `Campaign.recipe`. */
    private const val MAX_DURATION_H = 9L

    /**
     * The persisted events: the leg movements kept, **and the rejected artefacts**.
     *
     * The rejected ones are written with their reason, as the real analysis does. This is not
     * padding: the detail of a night counts separately the movements excluded for posture and
     * those excluded for duration, and a night where both of those counters are zero gives no way
     * to judge the row that displays them.
     */
    private fun eventsOf(hex: String, hash: String, truth: GroundTruth): List<ClmEventEntity> {
        val kept = truthAsClms(truth.accelLegMovements, truth.floorG.toFloat()).map {
            row(hex, hash, it.onsetMsRel, it.durationMs, it.peakAmpG, it.medianAmpG, truth, null)
        }
        val rejected = truth.accelTruth.filter { !it.isLegMovement }.map { e ->
            row(
                hex, hash, e.onsetMsRel, e.durationMs, e.peakG, e.envPeakG, truth,
                when (e.kind) {
                    TruthKind.POSTURE -> "POSTURAL"
                    TruthKind.GROSS_BODY -> "GROSS_BODY"
                    // A mattress vibration lasts between 50 and 400 ms: that is literally the
                    // reason the detector would hold against it.
                    TruthKind.MATTRESS -> "TOO_SHORT"
                    else -> "MORPHOLOGY"
                },
            )
        }
        return (kept + rejected).sortedBy { it.onsetMsRel }
    }

    private fun row(
        hex: String,
        hash: String,
        onsetMsRel: Long,
        durationMs: Int,
        peakG: Float,
        medianG: Float,
        truth: GroundTruth,
        reason: String?,
    ): ClmEventEntity = ClmEventEntity(
        sessionHex = hex,
        paramsHash = hash,
        onsetMsRel = onsetMsRel,
        durationMs = durationMs,
        peakAmpG = peakG.toDouble(),
        medianAmpG = medianG.toDouble(),
        noiseFloorG = truth.floorG,
        thresholdOnG = truth.floorG * 8.0,
        tiltChangeDeg = 0.0,
        flags = 0,
        rejectReason = reason,
        duringWake = false,
    )

    /** `:algo` stage -> Health Connect type, the inverse of `Hypnogram.stageOf`. */
    private fun hcType(stage: Stage): Int = when (stage) {
        Stage.SLEEP -> Hypnogram.STAGE_SLEEPING
        Stage.LIGHT -> Hypnogram.STAGE_LIGHT
        Stage.DEEP -> Hypnogram.STAGE_DEEP
        Stage.REM -> Hypnogram.STAGE_REM
        Stage.AWAKE_IN_BED -> Hypnogram.STAGE_AWAKE_IN_BED
        Stage.OUT_OF_BED -> Hypnogram.STAGE_OUT_OF_BED
        else -> Hypnogram.STAGE_UNKNOWN
    }

    /**
     * Erasure of the database alone, without going through [DataEraser].
     *
     * `DataEraser.eraseEverything` begins with `WorkManager.cancelAllWork()`. That is right for the
     * "delete everything" button — a `SleepFetchWorker` already queued would recreate an
     * `hc_snapshot` row just afterwards — but not once moved here: the seeding writes nights that
     * are already closed and already analysed, and cutting the watchdog on the way would be a side
     * effect the command does not promise. The difference comes down to that line and to nothing
     * else.
     */
    private suspend fun clearDatabase(context: Context, db: PendulumDatabase) {
        ChunkStore(context).deleteAll()
        db.eraseEverything()
    }
}
