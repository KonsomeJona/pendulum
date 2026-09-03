package com.pendulum.phone

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The sequence of "erase everything", and the step it was missing.
 *
 * ### The defect
 *
 * `DataEraser` cancelled the work, deleted the chunk files and wiped the database — and never
 * spoke to the Data Layer. Everything the replicated store held survived: the phone's
 * acknowledgements and sealed contexts, the watch's session and chunk items. Three consequences,
 * each proven from the code and none visible on the screen that had just said "0 B on disk":
 *
 *  - chunks in flight at that instant stayed in the store and on the watch for good —
 *    `pushChunks` skips every index present in the store, and no acknowledgement names an erased
 *    night again — counted as "N chunks pending" by `Preflight` every evening;
 *  - a night being recorded came back at its close: the watch re-put the session item,
 *    `insertIfAbsent` recreated the row, the final burst was ingested and the analysis chain
 *    scored a night the user had erased at 3 a.m., shown at 7 a.m. as truncated;
 *  - the evening's context item outlived its database row, so the watch let a night start under
 *    an evening the phone had no context for, and that night came out `NO_CONTEXT`.
 *
 * The step now exists, and these tests pin where it sits and what it is allowed to do.
 */
class ErasureOrderTest {

    @Test
    fun `the watch is told before anything local is erased`() {
        // Told while the phone still holds the data: a sequence cut after this step leaves
        // something to erase again. The other order leaves a watch pushing erased nights back
        // into a database with no row to attach them to — files written to `filesDir` by the
        // ingestion, unknown to the database, counted by nothing.
        val steps = mutableListOf<String>()

        ErasureOrder.execute(
            cancelWork = { steps += "work" },
            disownWatch = { steps += "watch"; true },
            deleteFiles = { steps += "files" },
            eraseDatabase = { steps += "database" },
        )

        assertThat(steps).containsExactly("work", "watch", "files", "database")
    }

    @Test
    fun `a watch that cannot be told does not stop the local erasure`() {
        // The disown step has a remote party in it and it is the only one that has. It reports;
        // it does not veto. Keeping the user's health data because their watch is in a drawer
        // would be the wrong reading of "erase everything".
        val steps = mutableListOf<String>()

        val told = ErasureOrder.execute(
            cancelWork = { steps += "work" },
            disownWatch = { steps += "watch"; false },
            deleteFiles = { steps += "files" },
            eraseDatabase = { steps += "database" },
        )

        assertThat(told).`as`("the caller must know the watch was not told").isFalse()
        assertThat(steps).containsExactly("work", "watch", "files", "database")
    }

    @Test
    fun `a disown step that raises is a watch not told, not an erasure not done`() {
        val steps = mutableListOf<String>()

        val told = ErasureOrder.execute(
            cancelWork = { steps += "work" },
            disownWatch = { throw IllegalStateException("Google Play services unavailable") },
            deleteFiles = { steps += "files" },
            eraseDatabase = { steps += "database" },
        )

        assertThat(told).isFalse()
        assertThat(steps).containsExactly("work", "files", "database")
    }

    @Test
    fun `the scheduled work is cancelled first`() {
        // A `ContextPublicationWorker` still waiting would put the evening's context item back
        // into the store right after the disown step removed it; a `SleepFetchWorker` would
        // recreate an `hc_snapshot` row minutes after the database was wiped.
        val steps = mutableListOf<String>()

        ErasureOrder.execute(
            cancelWork = { steps += "work" },
            disownWatch = { steps += "watch"; true },
            deleteFiles = {},
            eraseDatabase = {},
        )

        assertThat(steps.first()).isEqualTo("work")
    }
}
