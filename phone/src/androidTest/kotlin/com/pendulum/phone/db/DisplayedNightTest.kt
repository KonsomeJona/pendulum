package com.pendulum.phone.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A night scored without a hypnogram: which row do the screens get, and what is it named after?
 *
 * ### Why this test exists
 *
 * `NightAnalyzer` writes the `HEALTH_CONNECT` rows of `plm_result` only when Health Connect
 * returned a hypnogram; the `ACCEL_IMMOBILITY` rows exist for every scored night. The night list,
 * the home card and the detail screen all read the `comparable_night` view for
 * `maskSource = 'HEALTH_CONNECT'` and nothing else, so a night scored without a hypnogram —
 * analysed, stamped, its accelerometer rows in the table — came back as **no row**: absent from
 * the list, "0 nights recorded" the morning after a night the user had just watched being
 * analysed, a blank when tapped. That is the default path of every user without a sleep
 * application, and of every night in the hours before its hypnogram arrives.
 *
 * And the sleep source shown next to a night was the **preference**, read at display time: a
 * night scored from Samsung Health read "Fitness" the morning after the setting changed.
 *
 * `DisplayedNightSqlTest` pins the text of `TrendDao.displayNights` and of the view's
 * `sourcePackage` column on the JVM; this test runs both on the real database, through the real
 * construction path, in the order the defect happened: accelerometer rows first, the hypnogram
 * later. Same set-up as `PrincipalSessionTest`, cleaned by the only route the append-only
 * triggers allow.
 */
@RunWith(AndroidJUnit4::class)
class DisplayedNightTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun before() = startFromAnEmptyDatabase()

    @After
    fun after() = startFromAnEmptyDatabase()

    @Test
    fun aNightScoredWithoutAHypnogram_isShownThroughItsAccelerometerRow() {
        val db = PendulumDatabase.get(ctx)
        runBlocking {
            db.nightDao().insertIfAbsent(NIGHT)
            db.derivedDao().insertResults(listOf(result(ACCEL, gate = "TRUNCATED_NO_TREND")))
            db.derivedDao().insertWindows(listOf(window(ACCEL, sourcePackage = null)))
        }

        val shown = runBlocking { db.trendDao().displayNights(HASH, RULE, HC, ACCEL) }
        val row = shown.singleOrNull()
            ?: error("expected exactly one row for the night, got ${shown.size}: the night is invisible")
        if (row.maskSource != ACCEL) {
            error("the night is shown through ${row.maskSource}, expected its $ACCEL row")
        }
        if (row.sourcePackage != null) {
            error("the accelerometer mask has no origin to name, got `${row.sourcePackage}`")
        }
    }

    @Test
    fun onceTheHypnogramArrives_theHealthConnectRowReplacesIt_andNamesItsOrigin() {
        val db = PendulumDatabase.get(ctx)
        runBlocking {
            db.nightDao().insertIfAbsent(NIGHT)
            // Waking: accelerometer only.
            db.derivedDao().insertResults(listOf(result(ACCEL, gate = "TRUNCATED_NO_TREND")))
            db.derivedDao().insertWindows(listOf(window(ACCEL, sourcePackage = null)))
            // T+2 h: the rescore adds the external mask, named after the application that
            // published the hypnogram — `sleep_window.sourcePackage`, written by the analysis.
            db.derivedDao().insertResults(listOf(result(HC, gate = "FULL")))
            db.derivedDao().insertWindows(
                listOf(
                    window(HC, sourcePackage = SAMSUNG_HEALTH),
                    window(HC, sourcePackage = SAMSUNG_HEALTH, startMsRel = 1_800_000L),
                )
            )
        }

        val shown = runBlocking { db.trendDao().displayNights(HASH, RULE, HC, ACCEL) }
        val row = shown.singleOrNull()
            ?: error("expected exactly one row for the night, got ${shown.size}: the night appears twice")
        if (row.maskSource != HC) {
            error("the hypnogram is there and the night is still shown through ${row.maskSource}")
        }
        if (row.sourcePackage != SAMSUNG_HEALTH) {
            error("the source is named `${row.sourcePackage}`, expected `$SAMSUNG_HEALTH` from the windows used")
        }
    }

    @Test
    fun theTrend_stillAdmitsTheIndependentDenominatorOnly() {
        val db = PendulumDatabase.get(ctx)
        runBlocking {
            db.nightDao().insertIfAbsent(NIGHT)
            // A circular mask can be `FULL` only by a bug of `:algo`; the row is forged here so
            // that the trend filter, and not the gate, is what this test exercises.
            db.derivedDao().insertResults(listOf(result(ACCEL, gate = "FULL")))
        }

        // Shown to the user, with its flag…
        val shown = runBlocking { db.trendDao().displayNights(HASH, RULE, HC, ACCEL) }
        if (shown.size != 1) error("the night is not shown: ${shown.size} rows")
        // …but never plotted: the fallback reaches the screens through `displayNights` alone.
        val plotted = runBlocking { db.trendDao().trendPoints(HASH, RULE, HC) }
        if (plotted.isNotEmpty()) {
            error("a night without an independent denominator entered the trend: ${plotted.size} points")
        }
    }

    private fun startFromAnEmptyDatabase() {
        runBlocking { PendulumDatabase.get(ctx).eraseEverything() }
    }

    private companion object {
        const val HASH = "hash-under-test"
        const val RULE = "AASM_V3"
        const val HC = "HEALTH_CONNECT"
        const val ACCEL = "ACCEL_IMMOBILITY"
        const val SAMSUNG_HEALTH = "com.sec.android.app.shealth"

        val NIGHT = NightSessionEntity(
            sessionHex = "0000000000000000000000000000dddd",
            nightKey = "2026-09-03",
            startWallMs = 1_756_904_400_000L,
            plannedStopWallMs = 1_756_904_400_000L + 8 * 3_600_000L,
            endWallMs = 1_756_904_400_000L + 8 * 3_600_000L,
            zoneId = "Asia/Tokyo",
            tzOffsetStartMin = 540,
            tzOffsetEndMin = 540,
            nominalRateHz = 50,
            modeFlags = 0,
            state = "CLOSED",
            analysableMin = 470.0,
            gainCalG = 1.0,
            paramsHash = HASH,
        )

        fun result(maskSource: String, gate: String) = PlmResultEntity(
            sessionHex = NIGHT.sessionHex,
            paramsHash = HASH,
            rule = RULE,
            maskSource = maskSource,
            computedAtMs = NIGHT.startWallMs + 10 * 3_600_000L,
            algoVersion = "test",
            plmsCount = 40,
            plmwCount = 2,
            isolatedCount = 5,
            shortImiCount = 1,
            tstMin = 400.0,
            analysableTstMin = 400.0,
            sptMin = 460.0,
            wasoMin = 60.0,
            plmi = 6.0,
            plmiSpt = null,
            plmw = null,
            plmiFirstHalf = null,
            plmiSecondHalf = null,
            plmiRespWorstCase = null,
            periodicityIndex = 0.0,
            periodicityValid = false,
            fundamentalSec = null,
            muLog = null,
            sigmaLog = null,
            missRate = null,
            alternationSuspect = false,
            rhythmConverged = false,
            rhythmValid = false,
            truncatedSeriesDropped = 0,
            independence = if (maskSource == ACCEL) "CIRCULAR" else "INDEPENDENT",
            gate = gate,
            floorMode = "BILATERAL",
        )

        fun window(source: String, sourcePackage: String?, startMsRel: Long = 0L) = SleepWindowEntity(
            sessionHex = NIGHT.sessionHex,
            source = source,
            stage = "SLEEP",
            startMsRel = startMsRel,
            endMsRel = startMsRel + 1_800_000L,
            sourcePackage = sourcePackage,
            paramsHash = HASH,
        )
    }
}
