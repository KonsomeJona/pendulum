package com.pendulum.wear.record

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The colour of a blocker encodes **what it is**, not what it prevents.
 *
 * This test exists because the defect it pins down could not be seen by reading the code: the two
 * screens had to be put side by side. For the same fact — the evening context not yet sealed — the
 * phone displayed amber and the watch red. No test could fail, no preview showed it, and each of
 * the two screens was defensible on its own.
 *
 * The exhaustiveness of the `when` in [isFailure] does the rest of the work: adding an [IssueId]
 * without giving it a severity does not compile. What is written here is the intent, so that a
 * change of severity is a deliberate act and not a side effect.
 */
@DisplayName("The severity of a blocker describes its nature, not what it prevents")
class BlockerSeverityTest {

    @Test
    fun `a step the user can clear is not a failure`() {
        // Filling in a form you have not filled in yet is not a failure: it is the normal course
        // of the product, and it is even the way in.
        assertThat(IssueId.CONTEXT_NOT_SEALED.isFailure).isFalse()
        // The notification permission is granted in two gestures, from the screen itself.
        assertThat(IssueId.NOTIFICATIONS_DENIED.isFailure).isFalse()
    }

    @Test
    fun `what the user cannot repair from this screen stays red`() {
        assertThat(IssueId.NO_ACCELEROMETER.isFailure).isTrue()
        assertThat(IssueId.STORAGE_FULL.isFailure).isTrue()
        assertThat(IssueId.FGS_REFUSED.isFailure).isTrue()
        // A bench build wired to the real sensor would measure wrong without saying so.
        // It is the worst silent case in the project; it cannot be amber.
        assertThat(IssueId.BENCH_SCALE_MISMATCH.isFailure).isTrue()
    }

    @Test
    fun `no warning is a failure, otherwise the distinction means nothing any more`() {
        listOf(
            IssueId.LOW_BATTERY,
            IssueId.PHONE_UNREACHABLE,
            IssueId.NO_WAKEUP_SENSOR,
            IssueId.PENDING_SYNC,
        ).forEach { assertThat(it.isFailure).describedAs(it.name).isFalse() }
    }
}
