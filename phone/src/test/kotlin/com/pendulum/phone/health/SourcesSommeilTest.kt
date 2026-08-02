package com.pendulum.phone.health

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Le resume des sources, tel que l'etape 4 de l'assistant l'affiche.
 *
 * Ce que ces tests tiennent : le nombre annonce est un nombre de **nuits**, pas de sessions. Une
 * application qui republie trois fois la meme nuit n'est pas une source qui couvre trois nuits, et
 * l'inverse ferait choisir une source bavarde plutot qu'une source reguliere — donc changerait le
 * denominateur de l'index sans que l'utilisateur ait le moyen de s'en apercevoir.
 */
class SourcesSommeilTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun instant(jour: Int, heure: Int): Long =
        ZonedDateTime.of(2026, 3, jour, heure, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun session(
        paquet: String,
        jour: Int,
        heure: Int = 23,
        stades: List<Int> = emptyList(),
        id: String = "$paquet-$jour-$heure",
    ) = SleepSourceSelector.Candidate(
        recordId = id,
        packageName = paquet,
        startMs = instant(jour, heure),
        endMs = instant(jour, heure) + 7 * 3_600_000L,
        lastModifiedMs = instant(jour, heure),
        stages = stades.mapIndexed { i, type ->
            SleepSourceSelector.StageSpan(
                startMs = instant(jour, heure) + i * 3_600_000L,
                endMs = instant(jour, heure) + (i + 1) * 3_600_000L,
                stageType = type,
            )
        },
    )

    @Test
    fun `aucune session, aucune source`() {
        assertThat(SourcesSommeil.resumer(emptyList(), zone)).isEmpty()
    }

    @Test
    fun `deux sources, chacune avec ses nuits`() {
        val resume = SourcesSommeil.resumer(
            listOf(
                session("com.sec.android.app.shealth", 10, stades = listOf(4, 5, 6)),
                session("com.sec.android.app.shealth", 11, stades = listOf(4, 5, 6)),
                session("com.urbandroid.sleep", 11),
            ),
            zone,
        )

        assertThat(resume.map { it.paquet })
            .containsExactly("com.sec.android.app.shealth", "com.urbandroid.sleep")
        assertThat(resume[0].nuits).isEqualTo(2)
        assertThat(resume[0].stades).isTrue()
        assertThat(resume[1].nuits).isEqualTo(1)
        assertThat(resume[1].stades).isFalse()
    }

    @Test
    fun `trois republications de la meme nuit comptent pour une nuit`() {
        // Le point du test. Trois enregistrements, un seul soir : 22 h et 23 h 30 sont le meme
        // soir, et 6 h du matin appartient encore a la nuit de la veille — c'est la bascule a
        // midi de la cle de nuit, la meme que celle du contexte du soir.
        val resume = SourcesSommeil.resumer(
            listOf(
                session("app", 10, heure = 22, id = "a"),
                session("app", 10, heure = 23, id = "b"),
                session("app", 11, heure = 6, id = "c"),
            ),
            zone,
        )

        assertThat(resume).hasSize(1)
        assertThat(resume.single().nuits).isEqualTo(1)
    }

    @Test
    fun `une seule nuit avec des stades suffit a annoncer des stades`() {
        val resume = SourcesSommeil.resumer(
            listOf(
                session("app", 10),
                session("app", 11, stades = listOf(4, 5)),
            ),
            zone,
        )
        assertThat(resume.single().stades).isTrue()
    }

    @Test
    fun `un stade inconnu ne fait pas un hypnogramme`() {
        // `STAGE_TYPE_UNKNOWN` ne renseigne rien : une source qui n'ecrit que celui-la rend une
        // duree deguisee en hypnogramme, et l'ecran doit dire « duree seule ».
        val resume = SourcesSommeil.resumer(
            listOf(
                session(
                    "app", 10,
                    stades = listOf(
                        SleepSourceSelector.STAGE_TYPE_UNKNOWN,
                        SleepSourceSelector.STAGE_TYPE_UNKNOWN,
                    ),
                ),
            ),
            zone,
        )
        assertThat(resume.single().stades).isFalse()
    }

    @Test
    fun `l'ordre est total, donc stable d'un affichage a l'autre`() {
        // Deux sources a egalite de nuits et de stades : c'est le nom de paquet qui tranche.
        // Sans ce dernier critere, deux ouvertures de l'assistant pourraient proposer les memes
        // lignes dans un ordre different, et on tape sur la ligne d'a cote.
        val resume = SourcesSommeil.resumer(
            listOf(session("b.app", 10), session("a.app", 10)),
            zone,
        )
        assertThat(resume.map { it.paquet }).containsExactly("a.app", "b.app")
    }
}
