package com.pendulum.phone.ui

import com.pendulum.phone.ui.onboarding.OnboardingResume
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Resuming the onboarding.
 *
 * The defect these tests stop from coming back is not a crash: it is an onboarding that skips
 * itself. The persisted counter counts the steps **crossed**, and writing it on entering a step
 * would be enough to consider the disclaimer read by someone who has only seen it appear. Since the
 * value is a plain integer, nothing in the type tells the two readings apart — only a test does.
 */
class OnboardingResumeTest {

    @Test
    fun `fresh install, the onboarding is pending and starts at the beginning`() {
        assertThat(OnboardingResume.onboardingPending(0)).isTrue()
        assertThat(OnboardingResume.startPage(0)).isEqualTo(0)
    }

    @Test
    fun `quitting after step 3 brings you back there, and not to the beginning`() {
        // Three steps crossed: pages 0, 1 and 2 are behind, we reopen on page 3 — the fourth, the
        // sleep source one. Redoing three disclaimer screens to reach the one that was being
        // looked for is the surest way to get an application uninstalled.
        assertThat(OnboardingResume.onboardingPending(3)).isTrue()
        assertThat(OnboardingResume.startPage(3)).isEqualTo(3)
    }

    @Test
    fun `onboarding finished, it is no longer pending`() {
        assertThat(OnboardingResume.onboardingPending(OnboardingResume.PAGES)).isFalse()
    }

    @Test
    fun `leaving a page crosses that page and that page only`() {
        assertThat(OnboardingResume.stepAfter(page = 0, stepsCrossed = 0)).isEqualTo(1)
        assertThat(OnboardingResume.stepAfter(page = 3, stepsCrossed = 3)).isEqualTo(4)
    }

    @Test
    fun `leaving the last page finishes the onboarding`() {
        val last = OnboardingResume.PAGES - 1
        val after = OnboardingResume.stepAfter(last, last)
        assertThat(after).isEqualTo(OnboardingResume.PAGES)
        assertThat(OnboardingResume.onboardingPending(after)).isFalse()
    }

    @Test
    fun `the counter never goes back down`() {
        // Leaving a page earlier than what is already acquired must erase nothing: the opposite
        // would ask again for permissions already granted.
        assertThat(OnboardingResume.stepAfter(page = 0, stepsCrossed = 4)).isEqualTo(4)
        assertThat(OnboardingResume.stepAfter(page = 1, stepsCrossed = 3)).isEqualTo(3)
    }

    @Test
    fun `a counter that is too large opens the last page and not a page that does not exist`() {
        // The case of an onboarding brought back to fewer steps in a later version, on a phone
        // where the old counter is already written.
        assertThat(OnboardingResume.startPage(99)).isEqualTo(OnboardingResume.PAGES - 1)
        assertThat(OnboardingResume.stepAfter(99, 0)).isEqualTo(OnboardingResume.PAGES)
    }
}
