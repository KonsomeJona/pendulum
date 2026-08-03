package com.pendulum.sleepwriter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Ce que ces tests protegent : la **plausibilite** de l'hypnogramme, pas sa forme.
 *
 * Un generateur d'hypnogramme qui produirait une suite de stades incoherente passerait la chaine
 * de Pendulum sans rien exercer, et le banc dirait « ca marche ». Les invariants ci-dessous sont
 * ceux dont depend ce que la superposition avec les mouvements va reellement tester ; ils sont
 * verifies ici parce qu'aucun test d'emulateur ne les verifiera jamais.
 */
class HypnogrammeTest {

    private val debut = 1_754_000_000_000L
    private val huitHeures = debut + 8 * 3_600_000L

    @Test
    fun `les stades couvrent la fenetre sans trou ni chevauchement`() {
        val stades = Hypnogramme.nuitComplete(debut, huitHeures)

        assertThat(stades.first().debutMs).isEqualTo(debut)
        assertThat(stades.last().finMs).isEqualTo(huitHeures)
        stades.zipWithNext().forEach { (a, b) ->
            assertThat(b.debutMs).isEqualTo(a.finMs)
        }
        assertThat(stades).allSatisfy { assertThat(it.finMs).isGreaterThan(it.debutMs) }
    }

    @Test
    fun `on ne passe jamais d'un eveil directement au sommeil profond`() {
        val stades = Hypnogramme.nuitComplete(debut, huitHeures)
        val eveils = setOf(Hypnogramme.EVEIL, Hypnogramme.EVEIL_AU_LIT)

        assertThat(stades.zipWithNext()).noneMatch { (a, b) ->
            a.type in eveils && b.type == Hypnogramme.PROFOND
        }
    }

    @Test
    fun `le paradoxal ne suit jamais immediatement le profond`() {
        val stades = Hypnogramme.nuitComplete(debut, huitHeures)

        assertThat(stades.zipWithNext()).noneMatch { (a, b) ->
            a.type == Hypnogramme.PROFOND && b.type == Hypnogramme.PARADOXAL
        }
    }

    @Test
    fun `le profond est en premiere moitie de nuit et le paradoxal en seconde`() {
        val stades = Hypnogramme.nuitComplete(debut, huitHeures)
        val milieu = debut + (huitHeures - debut) / 2

        fun duree(type: Int, avantLeMilieu: Boolean) = stades
            .filter { it.type == type }
            .sumOf {
                val a = if (avantLeMilieu) it.debutMs else maxOf(it.debutMs, milieu)
                val b = if (avantLeMilieu) minOf(it.finMs, milieu) else it.finMs
                (b - a).coerceAtLeast(0L)
            }

        assertThat(duree(Hypnogramme.PROFOND, true))
            .isGreaterThan(4 * duree(Hypnogramme.PROFOND, false))
        assertThat(duree(Hypnogramme.PARADOXAL, false))
            .isGreaterThan(duree(Hypnogramme.PARADOXAL, true))
    }

    /**
     * `SleepSourceSelector` choisit la source qui a le plus de **types** de stade distincts. Une
     * nuit qui n'en porterait que deux serait techniquement valide et ne departagerait rien.
     */
    @Test
    fun `une nuit complete porte au moins quatre types de stade distincts`() {
        val types = Hypnogramme.nuitComplete(debut, huitHeures).map { it.type }.distinct()

        assertThat(types).contains(
            Hypnogramme.LEGER,
            Hypnogramme.PROFOND,
            Hypnogramme.PARADOXAL,
            Hypnogramme.EVEIL_AU_LIT,
        )
    }

    /**
     * `verdictOf` exige que les stades couvrent au moins 80 % de la session, faute de quoi il rend
     * `HYPNOGRAMME_TROUE`. La couverture etant totale ici, le scenario nominal ne doit jamais
     * declencher ce verdict — s'il le declenche un jour, c'est la chaine qui a perdu des stades,
     * pas le generateur.
     */
    @Test
    fun `la couverture par les stades est totale`() {
        val stades = Hypnogramme.nuitComplete(debut, huitHeures)
        val couverture = stades.sumOf { it.finMs - it.debutMs }

        assertThat(couverture).isEqualTo(huitHeures - debut)
    }

    @Test
    fun `une fenetre trop courte ne produit aucun stade plutot qu'une nuit fausse`() {
        assertThat(Hypnogramme.nuitComplete(debut, debut + 5 * 60_000L)).isEmpty()
    }

    /** Une nuit longue ne doit pas recommencer a produire du sommeil profond. */
    @Test
    fun `une nuit de dix heures ne fabrique pas de profond en fin de nuit`() {
        val dixHeures = debut + 10 * 3_600_000L
        val stades = Hypnogramme.nuitComplete(debut, dixHeures)
        val derniereHeure = dixHeures - 3_600_000L

        assertThat(stades.filter { it.debutMs >= derniereHeure })
            .noneMatch { it.type == Hypnogramme.PROFOND }
    }
}
