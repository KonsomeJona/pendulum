package com.pendulum.wear.record

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Le garde-fou qui interdit une compilation de banc sur le vrai capteur.
 *
 * `CoherenceEchelleTest` verifie que le diviseur du banc vaut l'acceleration du rejeu. Il ne
 * repond pas a la question qui a coute la mesure du §11.5.3 : que se passe-t-il quand il n'y a
 * **pas** de rejeu. Reponse mesuree : l'heure butoir, dont le delai de garde se comprime, coupe
 * l'enregistrement a 14,6 s, c'est-a-dire avant que la latence de salve du FIFO — 30 s, materielle
 * et non comprimable — n'ait livre son premier octet. Zero chunk, zero message, et des chiffres
 * qu'on croit lire.
 *
 * Les quatre combinaisons sont ecrites une par une plutot que parametrees : celle qui compte est
 * la troisieme, et la nommer vaut mieux que la deduire d'une table.
 */
class GardeEchelleTest {

    @Test
    fun `une compilation ordinaire sur le vrai capteur demarre`() {
        assertThat(Preflight.echelleDesaccordee(diviseur = 1L, sourceSynthetique = false)).isFalse()
    }

    @Test
    fun `une compilation ordinaire avec le rejeu demarre`() {
        assertThat(Preflight.echelleDesaccordee(diviseur = 1L, sourceSynthetique = true)).isFalse()
    }

    @Test
    fun `une compilation de banc sur le vrai capteur est refusee`() {
        assertThat(Preflight.echelleDesaccordee(diviseur = 250L, sourceSynthetique = false))
            .`as`(
                "le diviseur comprime le temps mural et rien d'autre : sur le capteur reel il ne " +
                    "comprime pas le banc, il le desaccorde",
            )
            .isTrue()
    }

    @Test
    fun `une compilation de banc avec le rejeu demarre`() {
        assertThat(Preflight.echelleDesaccordee(diviseur = 250L, sourceSynthetique = true)).isFalse()
    }

    /**
     * La regle ne nomme pas 250 : n'importe quelle compression sans rejeu est un desaccord, et un
     * garde-fou qui ne connaitrait qu'une valeur laisserait passer la suivante.
     */
    @Test
    fun `le refus ne depend pas de la valeur du diviseur`() {
        for (d in listOf(2L, 60L, 250L, 600L, 3_600L)) {
            assertThat(Preflight.echelleDesaccordee(d, sourceSynthetique = false))
                .`as`("diviseur $d")
                .isTrue()
        }
    }
}
