package com.pendulum.phone.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The sequence of the sealing, and the reliability defect it repairs.
 *
 * ### The defect
 *
 * `seal()` wrote the context into the database — irreversibly, `OnConflictStrategy.ABORT` plus two
 * SQLite immutability triggers — then attempted a `putDataItem`. **Nothing replayed that put.** A
 * passing unavailability of Google Play services was therefore enough to produce a state with no
 * way out: context sealed and unmodifiable on the phone side, item never entered into the store,
 * and a watch that refuses to record that night for good — `Preflight` makes `CONTEXT_NOT_SEALED`
 * a hard block. No recourse for the user: sealing again raises.
 *
 * The one gesture that changes this is this one — a failed put enqueues a replay — and it came
 * down to a single missing line, at the bottom of a coroutine that opened SQLite and called Google
 * Play services. That is why it now lives in [SealingOrder], where it can be exercised.
 */
class SealingOrderTest {

    @Test
    fun `a failed put enqueues the replay`() {
        var replaysEnqueued = 0

        val published = SealingOrder.execute(
            writeToDatabase = {},
            publish = { false },
            enqueueReplay = { replaysEnqueued++ },
        )

        assertThat(published).`as`("the caller must know the watch received nothing").isFalse()
        assertThat(replaysEnqueued).`as`("replays enqueued after a failed put").isEqualTo(1)
    }

    @Test
    fun `a successful put enqueues nothing`() {
        var replaysEnqueued = 0

        val published = SealingOrder.execute(
            writeToDatabase = {},
            publish = { true },
            enqueueReplay = { replaysEnqueued++ },
        )

        assertThat(published).isTrue()
        // Enqueuing anyway would not be dangerous — an identical item is deduplicated — but it
        // would be a background job scheduled every evening to do nothing, and above all a false
        // signal in the WorkManager inspector on the day we go looking for why a night went missing.
        assertThat(replaysEnqueued).`as`("replays enqueued after a successful put").isZero()
    }

    @Test
    fun `the database is written before any publication attempt`() {
        // The reverse would unblock the watch for an evening whose context is not in the database:
        // a night that records and that will come out excluded for `NO_CONTEXT` in the morning.
        val steps = mutableListOf<String>()

        SealingOrder.execute(
            writeToDatabase = { steps += "database" },
            publish = { steps += "put"; false },
            enqueueReplay = { steps += "replay" },
        )

        assertThat(steps).containsExactly("database", "put", "replay")
    }

    @Test
    fun `a database that refuses the duplicate neither publishes nor enqueues`() {
        // `writeToDatabase` raises: this is `OnConflictStrategy.ABORT` on an evening already
        // sealed. Publishing at that moment would announce to the watch a context that the current
        // entry did not write, and enqueuing a replay would repeat it all evening long.
        val steps = mutableListOf<String>()

        val raised = runCatching {
            SealingOrder.execute(
                writeToDatabase = { throw IllegalStateException("already sealed") },
                publish = { steps += "put"; true },
                enqueueReplay = { steps += "replay" },
            )
        }

        assertThat(raised.isFailure).isTrue()
        assertThat(steps).isEmpty()
    }
}
