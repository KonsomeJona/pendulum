package com.pendulum.phone.ingest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The one arrival after which the analysis must run again.
 *
 * Until this rule existed, the chain ran once, from the CLOSED session item, and a chunk arriving
 * after it was inserted, acknowledged, and never read: `finalizeSession` on the watch puts the
 * CLOSED item and then a burst of which only the last item is urgent, and the phone that was off
 * all night receives the CLOSED item with twenty-four chunks while the other seventy-two follow at
 * the pace of the acknowledgements. Scored with `received < declared`, the night was shown as
 * truncated and kept out of the trend — permanently on a phone without Health Connect, since only
 * a changed hypnogram brought the analysis back.
 *
 * The first version of the rule waited for the night to be **scored** before relaunching, because
 * the relaunch went through `enqueueNightChain` and its `KEEP` would have dropped it otherwise.
 * That left a gap: the chunk that landed after `AnalyzeWorker` had listed the files and before it
 * wrote `analyzedAtMs` was neither analysed nor relaunching. The relaunch now goes through
 * `enqueueLateRescore`, which appends after a chain in flight instead of being dropped, and the
 * rule no longer looks at the scoring state at all.
 *
 * The rule is pure, like `WatchdogWorker.nextState`: the three conditions each prevent a distinct
 * failure, and this file names them.
 */
class LateChunkRelaunchTest {

    @Test
    @DisplayName("the completing chunk relaunches, whatever the scoring state of the night")
    fun `the chunk that completes the announced series relaunches the analysis`() {
        // Session closed with three chunks announced, two of them held, the third lands. Whether
        // the chain launched by the CLOSED item has already scored the night, is still running,
        // or has not started, is deliberately not an input: the first rule refused this arrival
        // while the night was unscored ("the chain still ahead will see this chunk itself") and
        // got it wrong when the chunk landed after that chain had already listed the files. The
        // rule has no way to tell the two apart, and it no longer needs to. What makes that safe
        // is the work policy of `WorkScheduler.enqueueLateRescore`, argued in its KDoc; nothing
        // here exercises WorkManager, this test only pins the rule.
        assertThat(
            PendulumListenerService.completesDeclaredSeries(
                inserted = true,
                declaredChunks = 3,
                completeChunks = 3,
            )
        ).isTrue()
    }

    @Test
    fun `a chunk that leaves the series incomplete relaunches nothing`() {
        // Second of three: enqueuing here would enqueue one analysis per chunk of a backlog,
        // seventy times for a phone that was off all night.
        assertThat(
            PendulumListenerService.completesDeclaredSeries(
                inserted = true,
                declaredChunks = 3,
                completeChunks = 2,
            )
        ).isFalse()
    }

    @Test
    fun `a duplicate of a chunk already held relaunches nothing`() {
        // The watch re-puts an item after a lost acknowledgement; `INSERT OR IGNORE` makes the row
        // a no-op, and the series was complete before this arrival. Relaunching on it would
        // append one analysis per re-put.
        assertThat(
            PendulumListenerService.completesDeclaredSeries(
                inserted = false,
                declaredChunks = 3,
                completeChunks = 3,
            )
        ).isFalse()
    }

    @Test
    fun `a night the watch never closed is left to the watchdog`() {
        // `totalChunks` is null until the CLOSED item: nothing says how many chunks make the
        // series complete, and the TRUNCATED path of the watchdog already owns that night.
        assertThat(
            PendulumListenerService.completesDeclaredSeries(
                inserted = true,
                declaredChunks = null,
                completeChunks = 40,
            )
        ).isFalse()
    }
}
