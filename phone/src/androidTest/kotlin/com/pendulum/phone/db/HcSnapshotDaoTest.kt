package com.pendulum.phone.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which row of `hc_snapshot` is "the hypnogram of the night"?
 *
 * ### Why this test exists
 *
 * `hc_snapshot` is a log of **attempts**, not of readings: `SleepFetchWorker` appends a row at
 * every rung of its ladder, including the rungs that read nothing (Health Connect updating,
 * permission revoked, read timed out), and the ladder carries on after a success because a
 * provider can rewrite a published session. Until 4 September 2026 the bundle export, the report
 * for the physician and the rescore all read [HcSnapshotDao.latest] — the newest row by date,
 * whatever its outcome — so a failed rung at T+4 h silently replaced the hypnogram read at T+2 h.
 * The night was then exported without its denominator, the report called its figure circular under
 * a table that still showed `HEALTH_CONNECT` rows, and the next global rescore dropped the night
 * from the trend with no reason displayed.
 *
 * The two questions are distinct and both are legitimate: the ladder and the burst guard want the
 * last **attempt**, the analysis wants the last **reading**. This test pins the difference between
 * [HcSnapshotDao.latest] and [HcSnapshotDao.latestWithSession] on the exact sequence that caused
 * the loss.
 *
 * Same set-up as `ImmutabilityTest`, for the same reason: the real database, through the real
 * construction path, cleaned by the only route the append-only triggers allow.
 */
@RunWith(AndroidJUnit4::class)
class HcSnapshotDaoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun before() = startFromAnEmptyDatabase()

    @After
    fun after() = startFromAnEmptyDatabase()

    @Test
    fun aFailedAttemptAfterASuccess_doesNotEraseTheHypnogramAlreadyRead() {
        val db = PendulumDatabase.get(ctx)
        val dao = db.hcSnapshotDao()
        runBlocking {
            db.nightDao().insertIfAbsent(SESSION)
            dao.append(SUCCESS)
            // The rung that follows: Health Connect is updating. The row exists only so that the
            // ladder advances — `attemptCount` counts it — and it carries no session at all.
            dao.append(
                HcSnapshotEntity(
                    sessionHex = SESSION.sessionHex,
                    fetchedAtMs = SUCCESS.fetchedAtMs + 2 * 3_600_000L,
                    attemptIndex = SUCCESS.attemptIndex + 1,
                    recordsJson = "{}",
                    outcome = "UPDATE_REQUIRED",
                )
            )
        }

        // The ladder must still see the failure: it is the last attempt, and the burst guard and
        // the rewrite detection are keyed on attempts.
        val lastAttempt = runBlocking { dao.latest(SESSION.sessionHex) }
            ?: error("no row at all: the set-up did not happen")
        if (lastAttempt.outcome != "UPDATE_REQUIRED") {
            error("`latest` no longer returns the last attempt: ${lastAttempt.outcome}")
        }

        // The analysis, the report and the bundle must see the reading.
        val reading = runBlocking { dao.latestWithSession(SESSION.sessionHex) }
            ?: error("the failed rung hid the hypnogram: `latestWithSession` returned nothing")
        if (reading.selectedRecordId != SUCCESS.selectedRecordId) {
            error("wrong row: record ${reading.selectedRecordId}, expected ${SUCCESS.selectedRecordId}")
        }
        if (reading.selectedStagesCsv != SUCCESS.selectedStagesCsv) {
            error("the stages came back different: \"${reading.selectedStagesCsv}\"")
        }
    }

    @Test
    fun aLaterSuccessfulRead_stillReplacesTheEarlierOne() {
        // The provider rewrote the session between two rungs: the newer reading is the one to use.
        // Filtering out the empty rows must not freeze the first success — that would reintroduce,
        // the other way round, the defect this file pins.
        val db = PendulumDatabase.get(ctx)
        val dao = db.hcSnapshotDao()
        val rewritten = SUCCESS.copy(
            fetchedAtMs = SUCCESS.fetchedAtMs + 6 * 3_600_000L,
            attemptIndex = SUCCESS.attemptIndex + 2,
            selectedRecordId = "rec-2",
            lastModifiedTimeMs = SUCCESS.fetchedAtMs + 5 * 3_600_000L,
            stageCount = 5,
            selectedStagesCsv = SUCCESS.selectedStagesCsv + ";1754521200000:1754523000000:6",
        )
        runBlocking {
            db.nightDao().insertIfAbsent(SESSION)
            dao.append(SUCCESS)
            dao.append(rewritten)
        }

        val reading = runBlocking { dao.latestWithSession(SESSION.sessionHex) }
            ?: error("two readings, none returned")
        if (reading.selectedRecordId != "rec-2") {
            error("the older reading won over the newer one: ${reading.selectedRecordId}")
        }
    }

    @Test
    fun withoutAnySuccessfulRead_thereIsNoHypnogramToOffer() {
        // Every rung failed: `latest` has an answer for the ladder, the analysis must get none —
        // a night without a hypnogram is scored without the `HEALTH_CONNECT` mask, and the report
        // says so. Returning an empty row here would put the report back where it was.
        val db = PendulumDatabase.get(ctx)
        val dao = db.hcSnapshotDao()
        runBlocking {
            db.nightDao().insertIfAbsent(SESSION)
            dao.append(
                HcSnapshotEntity(
                    sessionHex = SESSION.sessionHex,
                    fetchedAtMs = SUCCESS.fetchedAtMs,
                    attemptIndex = 0,
                    recordsJson = "{}",
                    outcome = "PERMISSIONS_MISSING",
                )
            )
        }
        if (runBlocking { dao.latest(SESSION.sessionHex) } == null) {
            error("the ladder lost its last attempt")
        }
        val reading = runBlocking { dao.latestWithSession(SESSION.sessionHex) }
        if (reading != null) error("an attempt that chose nothing was offered as a reading: $reading")
    }

    private fun startFromAnEmptyDatabase() {
        runBlocking { PendulumDatabase.get(ctx).eraseEverything() }
    }

    private companion object {

        /** The night the snapshots attach to: `hc_snapshot` has a foreign key. */
        val SESSION = NightSessionEntity(
            sessionHex = "cafebabe",
            nightKey = "2026-08-07",
            startWallMs = 1_754_517_600_000L,
            plannedStopWallMs = 1_754_546_400_000L,
            zoneId = "Asia/Tokyo",
            tzOffsetStartMin = 540,
            tzOffsetEndMin = 540,
            nominalRateHz = 50,
            modeFlags = 0,
            state = "CLOSED",
        )

        /** A reading that retained a session — the rung at T+2 h. */
        val SUCCESS = HcSnapshotEntity(
            sessionHex = SESSION.sessionHex,
            fetchedAtMs = 1_754_553_600_000L,
            attemptIndex = 2,
            selectedPackage = "com.sec.android.app.shealth",
            selectedRecordId = "rec-1",
            lastModifiedTimeMs = 1_754_549_000_000L,
            sessionStartMs = SESSION.startWallMs,
            sessionEndMs = SESSION.plannedStopWallMs,
            stageCount = 4,
            distinctStageTypes = 3,
            stageCoverageMin = 411.0,
            overlapFraction = 0.97,
            aggregateTstMin = 398.0,
            originCount = 1,
            selectedStagesCsv = "1754517600000:1754519400000:4;1754519400000:1754521200000:5",
            recordsJson = """{"kept":1,"excluded":0}""",
            outcome = "OK",
        )
    }
}
