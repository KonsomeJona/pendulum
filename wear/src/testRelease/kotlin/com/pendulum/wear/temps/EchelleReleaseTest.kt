package com.pendulum.wear.temps

import com.pendulum.format.temps.Temps
import com.pendulum.format.wire.WireProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * La preuve que la release ne sait pas comprimer le temps.
 *
 * Ce test vit dans `src/testRelease/` : il n'est compile et execute que par
 * `:wear:testReleaseUnitTest`, contre le jumeau `src/release/` de `EchelleTemps`. Le mettre dans
 * `src/test/` — partage par les deux variantes — l'aurait rendu tautologique dans l'une et faux
 * dans l'autre.
 *
 * **Ce qu'il ne peut pas prouver, et qui est prouve ailleurs.** Qu'aucun chemin ne *puisse*
 * changer le diviseur en release ne se demontre pas par une assertion : c'est le compilateur qui
 * le garantit, parce que `BuildConfig.TEMPS_DIVISEUR` n'est declare que dans le bloc `debug` de
 * `build.gradle.kts` et que le jumeau release n'a aucune autre entree. Si quelqu'un ajoutait une
 * telle entree, ce test resterait vert — ce qui tomberait, c'est la revue du fichier de vingt
 * lignes ou l'ajout serait visible. Ce test-ci verifie la valeur ; le source set verifie
 * l'impossibilite.
 */
class EchelleReleaseTest {

    @Test
    fun `le diviseur vaut un en release`() {
        assertThat(EchelleTemps.DIVISEUR).isEqualTo(1L)
        assertThat(EchelleTemps.DIVISEUR).isEqualTo(Temps.DIVISEUR_REEL)
    }

    @Test
    fun `les durees publiees sont les durees nominales`() {
        val d = Durees.ACTIVES
        assertThat(d.rotationChunkMs).isEqualTo(WireProtocol.CHUNK_ROTATION_MS)
        assertThat(d.rotationChunkMs).isEqualTo(300_000L)
        assertThat(d.tickServiceMs).isEqualTo(10_000L)
        assertThat(d.ageMaxSessionMs).isEqualTo(14L * 3_600_000L)
        assertThat(d.periodeWatchdogMs).isEqualTo(15L * 60_000L)
        assertThat(d.relanceApresTimeoutMs).isEqualTo(30_000L)
        assertThat(d.antiRebondChargeMs).isEqualTo(60_000L)
        assertThat(d.dureeMaxSessionMs).isEqualTo(10L * 3_600_000L)
        assertThat(d.delaiMinAvantHeureButoirMs).isEqualTo(3_600_000L)
    }

    @Test
    fun `le plafond d'octets est le meme dans les deux variantes`() {
        // Il n'a jamais dependu de l'echelle, et c'est ecrit ici pour que la question soit posee
        // a chaque fois qu'on relit ce fichier.
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES).isEqualTo(92_160L)
    }
}
