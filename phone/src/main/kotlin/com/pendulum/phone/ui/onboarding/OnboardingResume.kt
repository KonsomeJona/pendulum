package com.pendulum.phone.ui.onboarding

import com.pendulum.phone.data.PendulumPreferences

/**
 * The onboarding resume rule, isolated from the composable so that it can be verified.
 *
 * ### The counter counts **crossed** steps, not the current step
 *
 * That is the whole subtlety, and departing from it gives an onboarding that skips itself. The
 * persisted number is that of the finished steps: 0 on first launch, 5 when onboarding is over.
 * Writing it on entering a step would mean that opening the application, landing on the disclaimer
 * and closing it again would be enough to consider it read — that is, exactly the opposite of what
 * guard rail 1 asks for.
 *
 * ### Why resume, and not start over
 *
 * Redoing three disclaimer screens to reach the one that was being looked for is the surest way to
 * get an application uninstalled. Onboarding has five steps, two of which ask for a system
 * permission: someone who was interrupted by a phone call during the Health Connect request must
 * come back there, not to the beginning.
 */
object OnboardingResume {

    /** Number of screens. The same as the upper bound of the persisted counter. */
    const val PAGES = PendulumPreferences.ONBOARDING_STEPS

    /** True as long as at least one step remains to be crossed. */
    fun onboardingPending(stepsCrossed: Int): Boolean = stepsCrossed < PAGES

    /**
     * The page to open. Clamped to the last page: a counter greater than the number of screens —
     * which is what an onboarding brought back to fewer steps in a later version would produce, on
     * a phone where it is already installed — must not open a page that does not exist.
     */
    fun startPage(stepsCrossed: Int): Int = stepsCrossed.coerceIn(0, PAGES - 1)

    /**
     * The counter to write when **leaving** [page].
     *
     * Monotonic by construction: it never falls back below what is already acquired. Without this
     * `maxOf`, going back one way or another — and a later version of the onboarding will possibly
     * offer the means to — would erase steps already crossed, and would therefore ask again for
     * permissions already granted.
     */
    fun stepAfter(page: Int, stepsCrossed: Int): Int =
        maxOf(stepsCrossed, page + 1).coerceIn(0, PAGES)
}
