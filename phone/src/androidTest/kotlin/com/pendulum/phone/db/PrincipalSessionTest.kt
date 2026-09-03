package com.pendulum.phone.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two sessions under one evening: which one is "the night"?
 *
 * ### Why this test exists
 *
 * Nothing forbids two sessions with the same night key — a false start at bedtime, stopped after
 * a minute and started again, is the ordinary way to get one — and nothing defined which of the
 * two the readers of "the night of that evening" should take. `NightDao.findByNightKey` was a
 * bare `LIMIT 1`, and so was the `ref` subquery of the `comparable_night` view: the row SQLite
 * visited first won. On the reference evening, with the one-minute session chosen, its `NULL`
 * gain became the campaign's reference gain and **every** night came out `CAL_GAIN_UNKNOWN`;
 * with the other one chosen, everything was fine. Same database, two trends.
 *
 * The definition is now `PrincipalSessionSql.ORDER_BY` — most analysable minutes, then earliest
 * start, then identifier — shared by the DAO and the view. `ComparableNightPredicateTest` pins the
 * text; this test runs it on the real database, with the false start inserted **first** so that
 * rowid order, the planner's usual default, points at the wrong row.
 *
 * Same set-up as `HcSnapshotDaoTest`: the real database, cleaned by the only route the
 * append-only triggers allow.
 */
@RunWith(AndroidJUnit4::class)
class PrincipalSessionTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun before() = startFromAnEmptyDatabase()

    @After
    fun after() = startFromAnEmptyDatabase()

    @Test
    fun theSessionWithTheMostAnalysableSleep_isTheNightOfTheEvening() {
        val db = PendulumDatabase.get(ctx)
        runBlocking {
            db.nightDao().insertIfAbsent(FALSE_START)
            db.nightDao().insertIfAbsent(REAL_NIGHT)
        }

        val chosen = runBlocking { db.nightDao().findByNightKey(EVENING) }
            ?: error("no session at all for $EVENING: the set-up did not happen")
        if (chosen.sessionHex != REAL_NIGHT.sessionHex) {
            error("the false start was taken for the night: ${chosen.sessionHex}")
        }
    }

    @Test
    fun theReferenceGainComesFromThePrincipalSession_soLaterNightsStayComparable() {
        val db = PendulumDatabase.get(ctx)
        runBlocking {
            db.contextDao().seal(context(EVENING, sealedAtMs = FALSE_START.startWallMs - 3_600_000L))
            db.contextDao().seal(context(NEXT_EVENING, sealedAtMs = NEXT_NIGHT.startWallMs - 3_600_000L))
            db.nightDao().insertIfAbsent(FALSE_START)
            db.nightDao().insertIfAbsent(REAL_NIGHT)
            db.nightDao().insertIfAbsent(NEXT_NIGHT)
            db.derivedDao().insertResults(
                listOf(result(FALSE_START), result(REAL_NIGHT), result(NEXT_NIGHT))
            )
        }

        // The night after the reference evening: its gain is within tolerance of the real
        // night's, and out of reach of the false start's, which is NULL.
        val next = runBlocking { db.trendDao().forNight(NEXT_NIGHT.sessionHex, HASH) }
            .singleOrNull() ?: error("the view returned no row for the next night")
        if (next.exclusionReason != ComparabilityRule.OK) {
            error(
                "the next night is excluded for ${next.exclusionReason}: the reference gain was " +
                    "taken from the false start, not from the night of the evening"
            )
        }
        val real = runBlocking { db.trendDao().forNight(REAL_NIGHT.sessionHex, HASH) }
            .singleOrNull() ?: error("the view returned no row for the real night")
        if (real.exclusionReason != ComparabilityRule.OK) {
            error("the reference night itself is excluded for ${real.exclusionReason}")
        }
    }

    private fun startFromAnEmptyDatabase() {
        runBlocking { PendulumDatabase.get(ctx).eraseEverything() }
    }

    private companion object {
        const val EVENING = "2026-08-07"
        const val NEXT_EVENING = "2026-08-08"
        const val HASH = "hash-under-test"

        private fun session(
            hex: String,
            nightKey: String,
            startWallMs: Long,
            analysableMin: Double,
            gainCalG: Double?,
        ) = NightSessionEntity(
            sessionHex = hex,
            nightKey = nightKey,
            startWallMs = startWallMs,
            plannedStopWallMs = startWallMs + 8 * 3_600_000L,
            endWallMs = startWallMs + (analysableMin * 60_000L).toLong(),
            zoneId = "Asia/Tokyo",
            tzOffsetStartMin = 540,
            tzOffsetEndMin = 540,
            nominalRateHz = 50,
            modeFlags = 0,
            state = "CLOSED",
            analysableMin = analysableMin,
            gainCalG = gainCalG,
            paramsHash = HASH,
        )

        /** Stopped after a minute — wrong strap — and started again. Inserted first. */
        val FALSE_START = session(
            hex = "0000000000000000000000000000aaaa",
            nightKey = EVENING,
            startWallMs = 1_754_575_200_000L, // 22:00 local
            analysableMin = 1.2,
            gainCalG = null,
        )

        val REAL_NIGHT = session(
            hex = "0000000000000000000000000000bbbb",
            nightKey = EVENING,
            startWallMs = 1_754_575_500_000L, // 22:05 local
            analysableMin = 480.0,
            gainCalG = 1.0,
        )

        val NEXT_NIGHT = session(
            hex = "0000000000000000000000000000cccc",
            nightKey = NEXT_EVENING,
            startWallMs = 1_754_661_600_000L,
            analysableMin = 470.0,
            gainCalG = 1.1,
        )

        fun context(nightKey: String, sealedAtMs: Long) = NightContextEntity(
            nightKey = nightKey,
            sealedAtMs = sealedAtMs,
            leg = "RIGHT",
            strapId = "strap-a",
            aloneInBed = true,
            medicationJson = "[]",
        )

        /** One result row, so that the view — which starts from `plm_result` — has a row to annotate. */
        fun result(s: NightSessionEntity) = PlmResultEntity(
            sessionHex = s.sessionHex,
            paramsHash = HASH,
            rule = "AASM",
            maskSource = "ACCEL_IMMOBILITY",
            computedAtMs = s.startWallMs + 10 * 3_600_000L,
            algoVersion = "test",
            plmsCount = 40,
            plmwCount = 2,
            isolatedCount = 5,
            shortImiCount = 1,
            tstMin = s.analysableMin,
            analysableTstMin = s.analysableMin,
            sptMin = s.analysableMin,
            wasoMin = 0.0,
            plmi = if (s.analysableMin > 0) 40.0 / (s.analysableMin / 60.0) else null,
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
            independence = "NONE",
            gate = "FULL",
            floorMode = "ADAPTIVE",
        )
    }
}
