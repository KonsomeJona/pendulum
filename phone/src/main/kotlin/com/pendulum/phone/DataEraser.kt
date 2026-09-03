package com.pendulum.phone

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.pendulum.phone.data.WatchCommands
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.eraseEverything
import com.pendulum.phone.ingest.ChunkStore

/**
 * The total deletion.
 *
 * What is erased, and the order, which is not a matter of indifference:
 *
 * 1. **the scheduled work** — otherwise a `SleepFetchWorker` already queued would recreate an
 *    `hc_snapshot` row a few minutes after the erasure, and the user would watch a night they
 *    have just deleted reappear; and a `ContextPublicationWorker` still waiting would put the
 *    evening's context item back into the store right after step 2 removed it;
 * 2. **the Data Layer** — the erase order for the watch, then every item either device wrote:
 *    acknowledgements, sealed contexts, sessions, chunks, previews. This step used not to exist,
 *    and what it leaves out comes back — see `WirePaths.ERASE` for the three ways it did: chunks
 *    stuck in flight on the watch for good, a night being recorded that reappeared at its close,
 *    and erased chunks written back to `filesDir` by an ingestion that no longer had a row to
 *    attach them to. It runs **before** the local erasure: if the sequence is cut between the
 *    two, the phone still holds data the user can erase again, whereas the other order would
 *    leave a watch pushing erased nights back into an empty database. And it never blocks the
 *    local erasure: a watch that cannot be told is a watch that cannot be told, not a reason to
 *    keep the user's data;
 * 3. **the chunk files** — this is the bulk of it, ~8.7 MB of raw signal per night. Forgetting
 *    them is the classic mistake: the database is empty, the screen is empty, and eight hours of
 *    accelerometry per night are still asleep in `filesDir`;
 * 4. **the database**, with the append-only triggers temporarily removed, then `VACUUM` —
 *    without it the freed pages stay readable inside the database file and "erase everything"
 *    leaves the content recoverable.
 *
 * Files before database, and not the reverse: an interruption between the two leaves a database
 * pointing at files that are gone, a state that is detectable and repairable. The reverse order
 * would leave orphaned files whose existence nothing knows about any more — that is, health data
 * the user believes they have erased.
 */
object DataEraser {

    /**
     * @return `false` when the watch side could not be reached — the order was not put or a
     *   deletion failed. The phone's own data is gone either way; what may remain is on the
     *   watch, and it may come back. The erasure screen does not show this yet: the value is
     *   there for it, and the log line says it in the meantime.
     */
    suspend fun eraseEverything(context: Context): Boolean = ErasureOrder.execute(
        cancelWork = { WorkManager.getInstance(context).cancelAllWork() },
        disownWatch = { WatchCommands.disown(context, System.currentTimeMillis()) },
        deleteFiles = { ChunkStore(context).deleteAll() },
        eraseDatabase = { PendulumDatabase.get(context).eraseEverything() },
    ).also { if (!it) Log.w(TAG, "erased locally; the watch was not told") }

    /** The space occupied, so that the deletion screen can announce what it is about to destroy. */
    fun bytesOnDisk(context: Context): Long {
        val chunks = ChunkStore(context).totalBytes()
        val db = context.getDatabasePath(PendulumDatabase.NAME)
        val dbBytes = if (db.exists()) db.length() else 0L
        return chunks + dbBytes
    }

    private const val TAG = "PendulumEraser"
}

/**
 * The sequence of the erasure, isolated from WorkManager, from the Data Layer, from the disk and
 * from the database, so that its two decisions can be exercised on the JVM — the way
 * `SealingOrder` isolates the sealing:
 *
 *  1. **the watch is told before anything local is erased**, and it is told while the phone still
 *     holds the data, so an interrupted sequence leaves something to erase again rather than a
 *     watch pushing back what is gone;
 *  2. **a watch that cannot be told does not stop the local erasure.** The disown step is the one
 *     with a remote party in it; it reports, it does not veto.
 *
 * `inline` for the same reason as `SealingOrder.execute`: the four steps stay suspending lambdas
 * called from a `suspend` function without this one having to be one.
 */
object ErasureOrder {

    /** @return what [disownWatch] returned: whether the watch side was reached. */
    inline fun execute(
        cancelWork: () -> Unit,
        disownWatch: () -> Boolean,
        deleteFiles: () -> Unit,
        eraseDatabase: () -> Unit,
    ): Boolean {
        cancelWork()
        // A remote party that raises is a remote party that could not be told — not a reason to
        // leave the user's data in place. `WatchCommands.disown` already catches; this is the
        // guarantee at the level of the sequence, where a test can see it.
        val watchTold = try {
            disownWatch()
        } catch (e: Exception) {
            false
        }
        deleteFiles()
        eraseDatabase()
        return watchTold
    }
}
