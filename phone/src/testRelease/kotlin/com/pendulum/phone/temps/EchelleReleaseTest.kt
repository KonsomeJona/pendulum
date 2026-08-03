package com.pendulum.phone.temps

import com.pendulum.format.temps.Temps
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * La preuve que la release ne sait pas comprimer le temps, cote telephone.
 *
 * Jumeau de `wear/src/testRelease/.../EchelleReleaseTest.kt` : compile et execute par
 * `:phone:testReleaseUnitTest` seulement, contre le jumeau `src/release/` de `EchelleTemps`. Voir
 * la KDoc de l'autre pour la part que ce test ne prouve pas et que le source set prouve a sa
 * place.
 */
class EchelleReleaseTest {

    @Test
    fun `le diviseur vaut un en release`() {
        assertThat(EchelleTemps.DIVISEUR).isEqualTo(1L)
        assertThat(EchelleTemps.DIVISEUR).isEqualTo(Temps.DIVISEUR_REEL)
    }

    @Test
    fun `l'echelle de reprise Health Connect est celle du produit`() {
        val d = Durees.ACTIVES
        assertThat(d.offsetsLectureMs).containsExactly(
            TimeUnit.MINUTES.toMillis(30),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(2),
            TimeUnit.HOURS.toMillis(4),
            TimeUnit.HOURS.toMillis(8),
            TimeUnit.HOURS.toMillis(16),
            TimeUnit.HOURS.toMillis(32),
        )
        assertThat(d.abandonLectureMs).isEqualTo(TimeUnit.HOURS.toMillis(36))
        assertThat(d.minEntreOpportunistesMs).isEqualTo(TimeUnit.MINUTES.toMillis(10))
        assertThat(d.periodeWatchdogMs).isEqualTo(TimeUnit.MINUTES.toMillis(30))
        assertThat(d.silenceAvantStaleMs).isEqualTo(TimeUnit.MINUTES.toMillis(45))
        assertThat(d.ageMaxNuitMs).isEqualTo(TimeUnit.HOURS.toMillis(14))
    }

    @Test
    fun `l'abandon reste au-dela du dernier rang`() {
        // Sans cela l'echelle aurait un rang qui ne se declenche jamais, et l'inversion serait
        // invisible : la nuit abandonnerait simplement un peu plus tot, sans rien dire.
        assertThat(Durees.ACTIVES.offsetsLectureMs.last())
            .isLessThan(Durees.ACTIVES.abandonLectureMs)
    }
}
