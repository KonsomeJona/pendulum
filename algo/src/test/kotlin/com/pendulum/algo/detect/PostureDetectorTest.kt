package com.pendulum.algo.detect

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PostureDetectorTest {

    @Test
    @DisplayName("une rotation persistante de 90 degres produit un changement de posture")
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
    @DisplayName("une montre immobile ne produit aucun changement de posture")
    fun immobileWatchProducesNothing() {
        val n = samples(60.0)
        assertThat(PostureDetector.detect(flatGravity(n), wholeNight(n))).isEmpty()
    }

    @Test
    @DisplayName("un mouvement ample qui revient a sa position n'est pas un changement de posture")
    fun transientSwingIsNotAPostureChange() {
        // C'est la raison d'etre de la condition de stabilite des DEUX cotes de la transition :
        // avec la seule condition d'arrivee, il existe toujours un instant ou g_u(t - tau) est pris
        // au sommet du mouvement et g_u(t + tau) apres son retour, ce qui fabriquerait un faux
        // changement de posture a partir d'un simple grand mouvement de jambe.
        val n = samples(60.0)
        val g = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 30.0, andBack = true)

        assertThat(PostureDetector.detect(g, wholeNight(n))).isEmpty()
    }

    @Test
    @DisplayName("une rotation sous le seuil ne produit aucun changement de posture")
    fun smallRotationIsIgnored() {
        val n = samples(60.0)
        val g = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 12.0)

        assertThat(PostureDetector.detect(g, wholeNight(n))).isEmpty()
    }
}
