package com.pendulum.phone.health

import com.pendulum.phone.health.SleepSourceSelector.Candidate
import com.pendulum.phone.health.SleepSourceSelector.StageSpan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * La deduplication est le point le plus contre-intuitif de l'integration Health Connect :
 * `readRecords()` ne dedoublonne rien. Si deux applications ont ecrit la nuit, on recoit deux
 * sessions qui se chevauchent, et les concatener double a peu pres le temps de sommeil — donc
 * divise l'index par deux, sans le moindre avertissement.
 */
class SleepSourceSelectorTest {

    private val nuitDebut = 1_000_000_000L
    private val nuitFin = nuitDebut + 8 * 3_600_000L

    private fun stages(from: Long, count: Int, types: List<Int>): List<StageSpan> =
        (0 until count).map {
            StageSpan(from + it * 600_000L, from + (it + 1) * 600_000L, types[it % types.size])
        }

    private fun candidat(
        pkg: String,
        start: Long = nuitDebut,
        end: Long = nuitFin,
        stageTypes: List<Int> = listOf(4, 5, 6, 1),
        stageCount: Int = 40,
        lastModified: Long = 0L,
    ) = Candidate(
        recordId = "$pkg-rec",
        packageName = pkg,
        startMs = start,
        endMs = end,
        lastModifiedMs = lastModified,
        stages = if (stageCount == 0) emptyList() else stages(start, stageCount, stageTypes),
    )

    @Test
    fun `une seule source est retenue quand deux applications ecrivent la meme nuit`() {
        val samsung = candidat("com.sec.android.app.shealth")
        val sleepAsAndroid = candidat("com.urbandroid.sleep")

        val s = SleepSourceSelector.select(
            listOf(samsung, sleepAsAndroid), nuitDebut, nuitFin, preferredPackage = null,
        )

        assertThat(s.chosen).isNotNull()
        assertThat(s.rejected).hasSize(1)
        // On ne fusionne jamais : l'ensemble retenu + ecarte doit couvrir exactement l'entree.
        assertThat(listOfNotNull(s.chosen) + s.rejected)
            .containsExactlyInAnyOrder(samsung, sleepAsAndroid)
    }

    @Test
    fun `la source preferee l'emporte des qu'elle couvre la moitie de la fenetre`() {
        val prefere = candidat("com.pref", start = nuitDebut, end = nuitDebut + 5 * 3_600_000L)
        val autre = candidat("com.autre")

        val s = SleepSourceSelector.select(
            listOf(autre, prefere), nuitDebut, nuitFin, preferredPackage = "com.pref",
        )

        assertThat(s.chosen?.packageName).isEqualTo("com.pref")
        assertThat(s.reason).isEqualTo("SOURCE_PREFEREE")
    }

    @Test
    fun `la source preferee ne l'emporte pas si elle ne couvre presque rien`() {
        // Une session de 30 min sur une nuit de 8 h : le fournisseur prefere n'a manifestement
        // pas synchronise. Lui donner la priorite ferait un denominateur de 30 min et un index
        // seize fois trop grand.
        val prefereTropCourt = candidat("com.pref", start = nuitDebut, end = nuitDebut + 1_800_000L)
        val complet = candidat("com.autre")

        val s = SleepSourceSelector.select(
            listOf(prefereTropCourt, complet), nuitDebut, nuitFin, preferredPackage = "com.pref",
        )

        assertThat(s.chosen?.packageName).isEqualTo("com.autre")
    }

    @Test
    fun `un vrai hypnogramme bat une duree deguisee en hypnogramme`() {
        val dureeSeule = candidat("com.duree", stageTypes = listOf(2), stageCount = 40)
        val vraiHypnogramme = candidat("com.stades", stageTypes = listOf(4, 5, 6, 1), stageCount = 40)

        val s = SleepSourceSelector.select(
            listOf(dureeSeule, vraiHypnogramme), nuitDebut, nuitFin, preferredPackage = null,
        )

        assertThat(s.chosen?.packageName).isEqualTo("com.stades")
        assertThat(s.reason).isEqualTo("PLUS_DE_STADES")
    }

    @Test
    fun `STAGE_TYPE_UNKNOWN ne compte pas comme un stade`() {
        // Une source qui remplit le champ sans le renseigner ne doit pas passer pour un
        // hypnogramme : c'est le piege « typesDistincts = [0] ».
        val inconnu = candidat("com.inconnu", stageTypes = listOf(0), stageCount = 40)
        assertThat(inconnu.distinctStageTypes).isEqualTo(0)
    }

    @Test
    fun `a egalite de stades, la plus longue couverture gagne`() {
        val court = candidat("com.court", stageTypes = listOf(4, 5), stageCount = 10)
        val long = candidat("com.long", stageTypes = listOf(4, 5), stageCount = 40)

        val s = SleepSourceSelector.select(
            listOf(court, long), nuitDebut, nuitFin, preferredPackage = null,
        )
        assertThat(s.chosen?.packageName).isEqualTo("com.long")
    }

    @Test
    fun `la selection est deterministe a egalite parfaite`() {
        // Deux lectures des memes donnees doivent choisir la meme source, quel que soit l'ordre
        // de retour de l'API — sinon deux analyses de la meme nuit donneraient deux chiffres.
        val a = candidat("com.aaa")
        val b = candidat("com.bbb")

        val s1 = SleepSourceSelector.select(listOf(a, b), nuitDebut, nuitFin, null)
        val s2 = SleepSourceSelector.select(listOf(b, a), nuitDebut, nuitFin, null)

        assertThat(s1.chosen?.packageName).isEqualTo(s2.chosen?.packageName)
        assertThat(s1.chosen?.packageName).isEqualTo("com.aaa")
    }

    @Test
    fun `une sieste d'apres-midi ne concerne pas la nuit`() {
        val sieste = candidat(
            "com.sieste",
            start = nuitFin + 6 * 3_600_000L,
            end = nuitFin + 7 * 3_600_000L,
        )
        val s = SleepSourceSelector.select(listOf(sieste), nuitDebut, nuitFin, null)
        assertThat(s.chosen).isNull()
        assertThat(s.reason).isEqualTo("AUCUNE_SESSION_RECOUVRANTE")
    }

    @Test
    fun `le verdict distingue un probleme de latence d'un probleme de stades`() {
        val tropCourt = candidat("com.x", start = nuitDebut, end = nuitDebut + 1_800_000L)
        assertThat(SleepSourceSelector.verdictOf(tropCourt, nuitDebut, nuitFin)).isEqualTo("LATENCE")

        val sansStades = candidat("com.y", stageCount = 0)
        assertThat(SleepSourceSelector.verdictOf(sansStades, nuitDebut, nuitFin))
            .isEqualTo("STADES_ABSENTS")

        // Stades presents mais couvrant moins de 80 % de la session : hypnogramme troue.
        val troue = candidat("com.z", stageTypes = listOf(4, 5, 6), stageCount = 10)
        assertThat(SleepSourceSelector.verdictOf(troue, nuitDebut, nuitFin))
            .isEqualTo("HYPNOGRAMME_TROUE")

        val bon = candidat("com.ok", stageTypes = listOf(4, 5, 6, 1), stageCount = 48)
        assertThat(SleepSourceSelector.verdictOf(bon, nuitDebut, nuitFin)).isEqualTo("OK")
    }
}
