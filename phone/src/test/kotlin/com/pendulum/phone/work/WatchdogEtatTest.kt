package com.pendulum.phone.work

import com.pendulum.phone.temps.Durees
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Les deux paliers du chien de garde du telephone, sortis de la coroutine qui lisait la base et
 * l'horloge dans la meme fonction.
 *
 * Ce qui devient verifiable et ne l'etait pas : **la priorite de `TRUNCATED` sur `STALE`**. Une
 * nuit de plus de quatorze heures doit etre analysee meme si des chunks continuent d'arriver ;
 * l'ordre des branches d'un `when` etait la seule trace de cette decision.
 */
class WatchdogEtatTest {

    private val staleMs = Durees.ACTIVES.silenceAvantStaleMs
    private val ageMaxMs = Durees.ACTIVES.ageMaxNuitMs
    private val debut = 1_700_000_000_000L

    @Test
    fun `une nuit fraiche et bavarde ne change pas d'etat`() {
        val now = debut + staleMs / 2
        assertThat(etat("OPEN", lastChunk = now - staleMs / 4, now = now)).isNull()
    }

    @Test
    fun `le silence prolonge passe la nuit en stale`() {
        val now = debut + ageMaxMs / 2
        assertThat(etat("OPEN", lastChunk = now - staleMs - 1, now = now)).isEqualTo("STALE")
    }

    @Test
    fun `une nuit sans aucun chunk ne passe jamais en stale`() {
        // `lastChunkArrivalMs == 0` veut dire « rien n'est encore arrive », pas « rien n'arrive
        // depuis 1970 ». Sans ce garde-fou, toute nuit annoncee mais pas encore transferee serait
        // declaree morte a la premiere execution du chien de garde.
        val now = debut + ageMaxMs / 2
        assertThat(etat("OPEN", lastChunk = 0L, now = now)).isNull()
    }

    @Test
    fun `l'age tronque la nuit, meme si les chunks arrivent encore`() {
        val now = debut + ageMaxMs + 1
        assertThat(etat("OPEN", lastChunk = now - 1, now = now)).isEqualTo("TRUNCATED")
    }

    @Test
    fun `une nuit deja stale se tronque quand meme`() {
        val now = debut + ageMaxMs + 1
        assertThat(etat("STALE", lastChunk = debut, now = now)).isEqualTo("TRUNCATED")
    }

    @Test
    fun `une nuit deja stale n'est pas repassee en stale`() {
        val now = debut + ageMaxMs / 2
        assertThat(etat("STALE", lastChunk = debut, now = now)).isNull()
    }

    private fun etat(etat: String, lastChunk: Long, now: Long): String? =
        WatchdogWorker.etatSuivant(
            etat = etat,
            startWallMs = debut,
            lastChunkArrivalMs = lastChunk,
            nowMs = now,
            staleMs = staleMs,
            ageMaxMs = ageMaxMs,
        )
}
