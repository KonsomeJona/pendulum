package com.pendulum.phone

import android.content.Context
import androidx.work.WorkManager
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
 *    have just deleted reappear;
 * 2. **the chunk files** — this is the bulk of it, ~8.7 MB of raw signal per night. Forgetting
 *    them is the classic mistake: the database is empty, the screen is empty, and eight hours of
 *    accelerometry per night are still asleep in `filesDir`;
 * 3. **the database**, with the append-only triggers temporarily removed, then `VACUUM` —
 *    without it the freed pages stay readable inside the database file and "erase everything"
 *    leaves the content recoverable.
 *
 * Files before database, and not the reverse: an interruption between the two leaves a database
 * pointing at files that are gone, a state that is detectable and repairable. The reverse order
 * would leave orphaned files whose existence nothing knows about any more — that is, health data
 * the user believes they have erased.
 */
object DataEraser {

    suspend fun eraseEverything(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
        ChunkStore(context).deleteAll()
        PendulumDatabase.get(context).eraseEverything()
    }

    /** The space occupied, so that the deletion screen can announce what it is about to destroy. */
    fun bytesOnDisk(context: Context): Long {
        val chunks = ChunkStore(context).totalBytes()
        val db = context.getDatabasePath(PendulumDatabase.NAME)
        val dbBytes = if (db.exists()) db.length() else 0L
        return chunks + dbBytes
    }
}
