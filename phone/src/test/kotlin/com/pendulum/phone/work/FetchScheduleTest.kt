package com.pendulum.phone.work

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * L'echelle de reprise de la lecture Health Connect.
 *
 * Elle existe parce que la session de sommeil **n'apparait pas au reveil** : le transfert
 * montre -> telephone est regi par la politique batterie de la montre, sans delai garanti, et
 * une lecture unique echouerait la plupart du temps — silencieusement, puisque Health Connect
 * renvoie une liste vide et non une erreur.
 */
class FetchScheduleTest {

    private val fin = 1_700_000_000_000L
    private fun h(n: Long) = TimeUnit.HOURS.toMillis(n)
    private fun min(n: Long) = TimeUnit.MINUTES.toMillis(n)

    /**
     * L'echelle **nominale**, passee explicitement, jamais lue dans `FetchSchedule.OFFSETS_MS`.
     *
     * Ce fichier affirme des valeurs — « la premiere tentative est a T+30 minutes », « on
     * abandonne a T+36 h ». Les laisser suivre le diviseur de la variante compilee ferait tomber
     * ces affirmations parce qu'un banc a ete construit avec le temps comprime, c'est-a-dire pour
     * une raison qui n'apprend rien sur la replanification. La compression se verifie dans
     * `DureesTest` ; ici on verifie la regle.
     */
    private val offsets = longArrayOf(min(30), h(1), h(2), h(4), h(8), h(16), h(32))
    private val abandon = h(36)
    private val minEntre = min(10)

    private fun plan(attemptsDone: Int, sessionEndMs: Long = fin, nowMs: Long) =
        FetchSchedule.plan(attemptsDone, sessionEndMs, nowMs, offsets, abandon)

    private fun opportuniste(nowMs: Long, derniereTentativeMs: Long?) =
        FetchSchedule.opportunisteAdmissible(fin, nowMs, derniereTentativeMs, abandon, minEntre)

    @Test
    fun `la premiere tentative est a T+30 minutes`() {
        val plan = plan(attemptsDone = 0, nowMs = fin)
        assertThat(plan).isInstanceOf(FetchSchedule.Plan.Retry::class.java)
        assertThat((plan as FetchSchedule.Plan.Retry).delayMs).isEqualTo(min(30))
    }

    @Test
    fun `l'echelle double a chaque rang`() {
        val attendus = listOf(min(30), h(1), h(2), h(4), h(8), h(16), h(32))
        attendus.forEachIndexed { rang, attendu ->
            val plan = plan(rang, nowMs = fin) as FetchSchedule.Plan.Retry
            assertThat(plan.delayMs).describedAs("rang $rang").isEqualTo(attendu)
            assertThat(plan.attemptIndex).isEqualTo(rang)
        }
    }

    @Test
    fun `un rang deja passe declenche un rattrapage immediat`() {
        // Telephone eteint toute la matinee : on ne saute pas les rangs, on les rattrape un par
        // un. Chacun laisse une ligne `hc_snapshot`, et cette trace est ce qui permettra de
        // recaler l'echelle sur la latence reellement observee.
        val plan = plan(attemptsDone = 0, nowMs = fin + h(5))
        assertThat((plan as FetchSchedule.Plan.Retry).delayMs).isZero()
    }

    @Test
    fun `on abandonne a T+36 heures`() {
        val plan = plan(attemptsDone = 2, nowMs = fin + h(36))
        assertThat(plan).isInstanceOf(FetchSchedule.Plan.GiveUp::class.java)

        val juste = plan(2, nowMs = fin + h(36) - 1)
        assertThat(juste).isInstanceOf(FetchSchedule.Plan.Retry::class.java)
    }

    @Test
    fun `on abandonne aussi quand l'echelle est epuisee`() {
        val plan = plan(attemptsDone = offsets.size, nowMs = fin + h(1))
        assertThat(plan).isInstanceOf(FetchSchedule.Plan.GiveUp::class.java)
    }

    @Test
    fun `aucun rang ne depasse le mur des 36 heures`() {
        assertThat(FetchSchedule.OFFSETS_MS.last()).isLessThan(FetchSchedule.GIVE_UP_MS)
    }

    @Test
    fun `l'echelle est strictement croissante`() {
        // Un rang non croissant produirait deux tentatives au meme instant, donc deux lignes
        // `hc_snapshot` identiques et une echelle consommee deux fois plus vite.
        val o = FetchSchedule.OFFSETS_MS
        for (i in 1 until o.size) assertThat(o[i]).isGreaterThan(o[i - 1])
    }

    // --- Declencheurs opportunistes ---------------------------------------------------------

    @Test
    fun `une lecture opportuniste est admise dans la fenetre des 36 heures`() {
        // Chargeur branche a T+2 h, aucune lecture recente : on lit tout de suite plutot que
        // d'attendre le rang T+4 h. La synchronisation Health Connect est correlee a l'usage —
        // montre sur le chargeur, application source ouverte — pas a une horloge.
        assertThat(opportuniste(fin + h(2), null)).isTrue()
    }

    @Test
    fun `une lecture opportuniste est refusee hors de la fenetre`() {
        assertThat(opportuniste(fin - min(1), null)).isFalse()
        assertThat(opportuniste(fin + h(36), null)).isFalse()
    }

    @Test
    fun `une rafale de branchements ne declenche qu'une lecture`() {
        // Un cable qui fait faux contact emet la diffusion plusieurs fois par minute. Chaque
        // lecture interroge un fournisseur ; sans ce delai minimal, on le martelerait.
        val recente = fin + h(2)
        assertThat(opportuniste(recente + min(1), recente)).isFalse()
        assertThat(opportuniste(recente + minEntre, recente)).isTrue()
    }

    @Test
    fun `l'index opportuniste est negatif, donc invisible au compte des tentatives`() {
        // C'est ce qui empeche une lecture opportuniste de consommer l'echelle : le DAO compte
        // `attemptIndex >= 0`. Trois branchements de cable epuiseraient sinon les sept rangs en
        // une minute, et l'application abandonnerait la nuit avant midi.
        assertThat(FetchSchedule.INDEX_OPPORTUNISTE).isLessThan(0)
    }

    // --- Rescore conditionnel ---------------------------------------------------------------

    @Test
    fun `la premiere lecture reussie declenche un rescore`() {
        assertThat(
            FetchSchedule.shouldRescore(null, null, 0, "rec-1", 100L, 42)
        ).isTrue()
    }

    @Test
    fun `une lecture identique ne declenche rien`() {
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, "rec-1", 100L, 42)
        ).isFalse()
    }

    @Test
    fun `une session reecrite par le fournisseur declenche un rescore`() {
        // Cas documente : « inserts or *updates* ». Une nuit lue a T+1 h peut differer de la
        // meme nuit a T+8 h ; c'est `lastModifiedTime` qui le trahit.
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, "rec-1", 200L, 42)
        ).isTrue()
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, "rec-1", 100L, 55)
        ).isTrue()
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, "rec-2", 100L, 42)
        ).isTrue()
    }

    @Test
    fun `une lecture vide n'efface pas un hypnogramme deja obtenu`() {
        // Si le fournisseur ne renvoie rien a la tentative suivante, on garde ce qu'on avait :
        // rescorer avec un denominateur disparu remplacerait un chiffre valide par un chiffre
        // circulaire, sans que rien ne le signale.
        assertThat(
            FetchSchedule.shouldRescore("rec-1", 100L, 42, null, null, 0)
        ).isFalse()
    }
}
