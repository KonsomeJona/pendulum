package com.pendulum.phone.db

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Do the immutability triggers really hold?
 *
 * ### Why this test exists, and why nothing replaced it
 *
 * `PendulumDatabase` lays down by hand, in a `RoomDatabase.Callback`, three SQLite triggers:
 * `night_context_no_update`, `night_context_no_delete` and `hc_snapshot_no_update`. They are what
 * makes the evening context non-modifiable after the fact, that is, **guard rail 1** of the
 * product: the dose, the leg, the strap and the alcohol are sealed before the watch agrees to
 * start, and the failure mode to prevent is not fraud — it is the good-faith touch-up at waking,
 * after having seen the figure.
 *
 * Now **nothing checked them**. Room does not model triggers: its schema validation compares
 * tables, columns, indexes and the text of views, and entirely ignores the content of
 * `sqlite_master` of type `trigger`. A `Callback` wired up wrongly, an `onOpen` that stops calling
 * `createTriggers`, or a future migration that recreates `night_context`: in all three cases the
 * database goes on opening, every other test stays green, and the guard rail is no more than a
 * paragraph of documentation. The only symptom would be an `UPDATE` that succeeds — that is, no
 * symptom.
 *
 * This hole was more serious than the one `MigrationTest` covered, removed together with the four
 * migrations on 7 August 2026 (see the KDoc of [PendulumDatabase]): a failed migration prevents the
 * database from opening, and that shows; a missing trigger does not show.
 *
 * ### What the test opens, and why it is the real database
 *
 * Everything goes through [PendulumDatabase.get], hence through the real `Callback` and the real
 * `pendulum.db` file. That is deliberate and it is the only honest way to put the question: the
 * triggers are not in the schema, they are in the construction path, and a
 * `Room.inMemoryDatabaseBuilder` set up by hand in the test would check the test's fidelity to
 * itself. `GuardRailsTest` already takes that same database, for the same reason.
 *
 * The counterpart is that one has to clean up, and that the cleaning cannot be a `DELETE`: that is
 * precisely what the trigger forbids. [eraseEverything] is the only way out — it removes the
 * triggers, erases, then lays them down again — and it is also one more check, in the negative: if
 * it stopped working, this test could not even set itself up.
 */
@RunWith(AndroidJUnit4::class)
class ImmutabilityTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun before() = startFromAnEmptyDatabase()

    @After
    fun after() = startFromAnEmptyDatabase()

    /**
     * A sealed context refuses every `UPDATE`.
     *
     * Both halves count, and the second more than the first: an exception can be raised **after**
     * the write has taken place, or by something other than the trigger. The test therefore reads
     * the row back and checks that the alcohol and the note are those of the sealing. That is the
     * question as put from the user's point of view: is last night's value still last night's
     * value?
     */
    @Test
    fun sealedContext_refusesAnUpdate_andTheStoredValueDoesNotMove() {
        val db = PendulumDatabase.get(ctx)
        runBlocking { db.contextDao().seal(CONTEXT) }

        refuses(
            db,
            "UPDATE night_context SET alcoholUnits = 9.0, notes = 'touched up at waking' " +
                "WHERE nightKey = '${CONTEXT.nightKey}'",
            expectedReason = "sealed",
        )

        val reread = runBlocking { db.contextDao().find(CONTEXT.nightKey) }
            ?: error("the context has vanished: this is no longer a refusal, it is a loss")
        if (reread.alcoholUnits != CONTEXT.alcoholUnits) {
            error("the alcohol was rewritten: ${reread.alcoholUnits} instead of ${CONTEXT.alcoholUnits}")
        }
        if (reread.notes != CONTEXT.notes) error("the note was rewritten: \"${reread.notes}\"")
    }

    /**
     * A sealed context refuses every `DELETE`.
     *
     * Deleting then re-inserting is the obvious way round the ban on modifying, and it takes no ill
     * intent: a `@Delete` added to the DAO "to correct an entry" would be enough. The only
     * legitimate erasure is global and goes through [eraseEverything].
     */
    @Test
    fun sealedContext_refusesADelete_andTheRowIsStillThere() {
        val db = PendulumDatabase.get(ctx)
        runBlocking { db.contextDao().seal(CONTEXT) }

        refuses(
            db,
            "DELETE FROM night_context WHERE nightKey = '${CONTEXT.nightKey}'",
            expectedReason = "sealed",
        )

        runBlocking { db.contextDao().find(CONTEXT.nightKey) }
            ?: error("the row was deleted despite the trigger")
    }

    /**
     * A Health Connect snapshot refuses every `UPDATE`.
     *
     * `hc_snapshot` is a log, and it exists because some providers **rewrite** an already published
     * session: a night read at T+1 h can differ from the same night at T+8 h. If the application
     * could, in its turn, correct a past reading, the comparison of two rows would no longer prove
     * anything — it is the only trace that explains a figure that moves between two consultations.
     */
    @Test
    fun healthConnectSnapshot_refusesAnUpdate_andTheReadingStaysTheOneOfThatDay() {
        val db = PendulumDatabase.get(ctx)
        runBlocking {
            db.nightDao().insertIfAbsent(SESSION)
            db.hcSnapshotDao().append(SNAPSHOT)
        }

        refuses(
            db,
            "UPDATE hc_snapshot SET outcome = 'RETOUCHED', stageCount = 0 " +
                "WHERE sessionHex = '${SESSION.sessionHex}'",
            expectedReason = "log",
        )

        val reread = runBlocking { db.hcSnapshotDao().latest(SESSION.sessionHex) }
            ?: error("the snapshot has vanished")
        if (reread.outcome != SNAPSHOT.outcome) error("outcome rewritten: ${reread.outcome}")
        if (reread.stageCount != SNAPSHOT.stageCount) error("stageCount rewritten: ${reread.stageCount}")
    }

    /**
     * The three triggers are **laid down again at every opening**, not only at creation.
     *
     * Observing that they are in `sqlite_master` after a `get()` would prove nothing on its own:
     * they would be there anyway, laid down when the file was created during an earlier run. So the
     * test **drops** them first, closes the database, resets the memoised instance, then reopens by
     * the normal path — which is exactly what a migration that recreates `night_context` and takes
     * its triggers away with it would do. If they come back, the `onCreate` / `onOpen` duplication
     * of [PendulumDatabase] is doing its job; if they do not, guard rail 1 disappears in silence at
     * the next change of schema.
     */
    @Test
    fun afterANormalOpening_theThreeTriggersAreInTheDatabase() {
        val first = PendulumDatabase.get(ctx)
        val raw = first.openHelper.writableDatabase
        for (name in TRIGGERS) raw.execSQL("DROP TRIGGER IF EXISTS $name")
        if (triggers(first).isNotEmpty()) error("the test's own cleaning did not happen")
        first.close()
        PendulumDatabase.resetInstanceForTests()

        val found = triggers(PendulumDatabase.get(ctx))
        val missing = TRIGGERS - found
        if (missing.isNotEmpty()) {
            error("triggers absent after opening: $missing (present: $found)")
        }
    }

    /** The names of the triggers present in `sqlite_master`, among those that concern us. */
    private fun triggers(db: PendulumDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'trigger'")
            .use { c -> while (c.moveToNext()) names += c.getString(0) }
        return names intersect TRIGGERS
    }

    /**
     * Runs a write that must fail, and checks **why** it fails.
     *
     * The expected reason is a fragment of the message passed to `RAISE(ABORT, …)`: without it, a
     * foreign key error or a missing table would make the test pass for the wrong reason. We catch
     * [SQLiteException] and not `SQLiteConstraintException` — it is indeed the latter that is
     * raised, but the message is a stronger proof than the class, which depends on an extended
     * error code falling back to a base code.
     */
    private fun refuses(db: PendulumDatabase, sql: String, expectedReason: String) {
        val thrown = try {
            db.openHelper.writableDatabase.execSQL(sql)
            null
        } catch (e: SQLiteException) {
            e
        }
        if (thrown == null) error("write accepted, the trigger did not fire: $sql")
        val message = thrown.message.orEmpty()
        if (!message.contains(expectedReason)) {
            error("refusal obtained, but not from the expected trigger (\"$expectedReason\"): $message")
        }
    }

    /**
     * Brings the database back to empty by the only authorised route.
     *
     * `PendulumDatabase.get` and not a test file: it is the application's database that carries the
     * triggers, so it is the one that is needed. On an everyday device this erases the recorded
     * nights — that is already the case for `GuardRailsTest`, and it is the price of a test that
     * checks the assembled application rather than a mock-up.
     */
    private fun startFromAnEmptyDatabase() {
        runBlocking { PendulumDatabase.get(ctx).eraseEverything() }
    }

    private companion object {

        val TRIGGERS = setOf(
            "night_context_no_update",
            "night_context_no_delete",
            "hc_snapshot_no_update",
        )

        /**
         * A plausible sealed context, and above all **not null everywhere**: a context that was all
         * zeroes would pass a refusal test while hiding that a column had indeed been rewritten.
         */
        val CONTEXT = NightContextEntity(
            nightKey = "2026-08-07",
            sealedAtMs = 1_754_500_000_000L,
            leg = "LEFT",
            strapId = "strap-a",
            aloneInBed = true,
            bedTimeLocalMs = 1_754_517_600_000L,
            riseTimeLocalMs = 1_754_546_400_000L,
            medicationJson = """[{"name":"pramipexole","doseMg":0.18}]""",
            caffeineAfter16h = false,
            alcoholUnits = 1.5,
            unusualExercise = false,
            notes = "sealed before the night",
        )

        /** The night the snapshot attaches to: `hc_snapshot` has a foreign key. */
        val SESSION = NightSessionEntity(
            sessionHex = "deadbeef",
            nightKey = CONTEXT.nightKey,
            startWallMs = 1_754_517_600_000L,
            plannedStopWallMs = 1_754_546_400_000L,
            zoneId = "Asia/Tokyo",
            tzOffsetStartMin = 540,
            tzOffsetEndMin = 540,
            nominalRateHz = 50,
            modeFlags = 0,
            state = "CLOSED",
        )

        val SNAPSHOT = HcSnapshotEntity(
            sessionHex = SESSION.sessionHex,
            fetchedAtMs = 1_754_550_000_000L,
            attemptIndex = 0,
            selectedPackage = "com.sec.android.app.shealth",
            selectedRecordId = "abc-123",
            lastModifiedTimeMs = 1_754_549_000_000L,
            sessionStartMs = SESSION.startWallMs,
            sessionEndMs = SESSION.plannedStopWallMs,
            stageCount = 42,
            distinctStageTypes = 4,
            stageCoverageMin = 411.0,
            overlapFraction = 0.97,
            aggregateTstMin = 398.0,
            originCount = 1,
            selectedStagesCsv = "1754517600000:1754519400000:4",
            recordsJson = """{"kept":1,"excluded":0}""",
            outcome = "OK",
        )
    }
}
