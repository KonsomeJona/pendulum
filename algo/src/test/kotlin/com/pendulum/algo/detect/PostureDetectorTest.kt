package com.pendulum.algo.detect

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PostureDetectorTest {

    @Test
    @DisplayName("a persistent 90 degree rotation produces a posture change")
    fun persistentRotationIsDetected() {
        val n = samples(60.0)
        val g = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 90.0)

        val changes = PostureDetector.detect(g, wholeNight(n))

        assertThat(changes).hasSize(1)
        val c = changes.single()
        assertThat(c.deltaDeg).isCloseTo(90f, Offset.offset(2f))
        assertThat(c.atMsRel).isBetween(28_000L, 32_000L)
        assertThat(c.settleMs).isGreaterThan(0)
    }

    @Test
    @DisplayName("an immobile watch produces no posture change")
    fun immobileWatchProducesNothing() {
        val n = samples(60.0)
        assertThat(PostureDetector.detect(flatGravity(n), wholeNight(n))).isEmpty()
    }

    @Test
    @DisplayName("a large movement that comes back to its position is not a posture change")
    fun transientSwingIsNotAPostureChange() {
        // This is the whole reason for requiring stability on BOTH sides of the transition:
        // with the arrival condition alone, there is always an instant where g_u(t - tau) is
        // taken at the peak of the movement and g_u(t + tau) after its return, which would
        // manufacture a false posture change out of a plain large leg movement.
        val n = samples(60.0)
        val g = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 30.0, andBack = true)

        assertThat(PostureDetector.detect(g, wholeNight(n))).isEmpty()
    }

    @Test
    @DisplayName("a rotation below the threshold produces no posture change")
    fun smallRotationIsIgnored() {
        val n = samples(60.0)
        val g = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 12.0)

        assertThat(PostureDetector.detect(g, wholeNight(n))).isEmpty()
    }
}
