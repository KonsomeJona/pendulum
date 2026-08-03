package com.pendulum.wear.record

import com.pendulum.wear.temps.Durees
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Le predicat de reprise, desormais unique et pur.
 *
 * Il vivait recopie a trois endroits — `BootReceiver`, le chien de garde, le redemarrage
 * `START_STICKY` du service — chacun lisant l'horloge au fond de sa propre fonction. Aucun des
 * trois n'etait testable, et la seule chose qui garantissait qu'ils disaient la meme chose etait
 * qu'ils avaient ete ecrits le meme jour.
 */
class SessionMarkerTest {

    private val debut = 1_700_000_000_000L

    private fun marqueur(plannedStopWallMs: Long) = SessionMarker(
        sessionHex = "aa",
        startWallMs = debut,
        plannedStopWallMs = plannedStopWallMs,
        lastChunkIndex = 0,
        modeFlags = 0,
        nominalRateHz = 50,
        zoneId = "Europe/Paris",
        stopAtLocalMinutes = 600,
    )

    private val ageMax = Durees.ACTIVES.ageMaxSessionMs

    @Test
    fun `une nuit en cours n'est pas perimee`() {
        val m = marqueur(plannedStopWallMs = debut + 8 * 3_600_000L)
        assertThat(m.estPerimee(debut + 3_600_000L, ageMaxMs = 14 * 3_600_000L)).isFalse()
    }

    @Test
    fun `l'heure d'arret prevue perime la nuit`() {
        val m = marqueur(plannedStopWallMs = debut + 8 * 3_600_000L)
        assertThat(m.estPerimee(debut + 8 * 3_600_000L, ageMaxMs = 14 * 3_600_000L)).isTrue()
    }

    @Test
    fun `l'age maximal perime la nuit meme si l'heure prevue est absurde`() {
        // C'est le cas pour lequel la seconde condition existe : un `plannedStopWallMs` faux —
        // parce qu'ecrit avant un changement d'heure, ou parce que le marqueur traine depuis une
        // semaine — laisserait sinon reprendre un enregistrement en plein apres-midi.
        val m = marqueur(plannedStopWallMs = debut + 1_000L * 3_600_000L)
        assertThat(m.estPerimee(debut + 14 * 3_600_000L, ageMaxMs = 14 * 3_600_000L)).isTrue()
    }

    @Test
    fun `l'age maximal par defaut suit l'echelle de la variante`() {
        val m = marqueur(plannedStopWallMs = Long.MAX_VALUE)
        assertThat(m.estPerimee(debut + ageMax - 1)).isFalse()
        assertThat(m.estPerimee(debut + ageMax)).isTrue()
    }
}
