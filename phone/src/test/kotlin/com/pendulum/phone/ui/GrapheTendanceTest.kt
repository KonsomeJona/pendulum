package com.pendulum.phone.ui

import com.pendulum.phone.ui.chart.AxeLineaire
import com.pendulum.phone.ui.chart.EtatPoint
import com.pendulum.phone.ui.chart.PointNuit
import com.pendulum.phone.ui.chart.TendanceChartSpec
import com.pendulum.phone.ui.chart.graduationsCalendaires
import com.pendulum.phone.ui.chart.margesDefaut
import com.pendulum.phone.ui.chart.trouverPointProche
import com.pendulum.phone.ui.chart.zoneTrace
import com.pendulum.phone.ui.model.Aggregat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Le graphe de tendance, la ou l'oeil ne rattrape rien.
 *
 * Deux defauts sont couverts ici, et tous deux avaient la meme signature : le graphe **avait
 * l'air juste**. Un point tape a cote se lit comme une erreur de doigt ; une graduation decalee
 * d'un jour ne se remarque que si l'on connait deja la date de la nuit qu'on regarde.
 */
class GrapheTendanceTest {

    private companion object {
        const val JOUR = 86_400_000L
        /** Densite 2 : un ecran mdpi x2, donc 40 dp de marge gauche = 80 px. */
        const val DENSITE = 2f
        const val LARGEUR = 1000f
        const val HAUTEUR = 400f
        /** Le rayon reel du geste : 24 dp, converti comme `GrapheTendance` le convertit. */
        const val RAYON = 24f * DENSITE
    }

    private fun spec(points: Int, valeur: Float = 20f) = TendanceChartSpec(
        grandeur = Aggregat.Grandeur.RYTHME_SECONDES,
        points = List(points) { PointNuit("s$it", it * JOUR, valeur, EtatPoint.ELIGIBLE) },
        bandes = emptyList(),
        reference = null,
        premierJourMs = 0L,
        dernierJourMs = (points - 1) * JOUR,
        zoneId = "Europe/Paris",
        pivotMs = null,
        descriptionAccessible = "",
    )

    // -------------------------------------------------------------------------------------
    // La detection du point tape lit la zone de trace, pas le canevas
    // -------------------------------------------------------------------------------------

    @Test
    fun `le premier point se touche a l'abscisse ou il est dessine, marge comprise`() {
        val s = spec(3)
        // La zone de trace commence a 40 dp du bord, soit 80 px ici : le premier point est
        // dessine la, et non a x = 0. La recherche calculait ses abscisses sur la largeur totale
        // du canevas, donc visait 0 — 80 px d'ecart pour un rayon d'acceptation de 48 px.
        val zone = zoneTrace(margesDefaut(DENSITE), LARGEUR, HAUTEUR)
        assertThat(zone.left).isEqualTo(80f)

        val y = AxeLineaire(s.yMin, s.yMax, zone.top, zone.height).y(20f)
        assertThat(trouverPointProche(s, zone.left, y, LARGEUR, HAUTEUR, DENSITE, RAYON))
            .isEqualTo("s0")
    }

    @Test
    fun `chaque point se touche a l'endroit ou il est dessine`() {
        val s = spec(10)
        val zone = zoneTrace(margesDefaut(DENSITE), LARGEUR, HAUTEUR)
        val axeY = AxeLineaire(s.yMin, s.yMax, zone.top, zone.height)
        val span = (s.dernierJourMs - s.premierJourMs).toFloat()

        for (p in s.points) {
            val x = zone.left + (p.dateMs - s.premierJourMs) / span * zone.width
            val y = axeY.y(p.valeur)
            assertThat(trouverPointProche(s, x, y, LARGEUR, HAUTEUR, DENSITE, RAYON))
                .describedAs("point %s a x=%.1f", p.sessionHex, x)
                .isEqualTo(p.sessionHex)
        }
    }

    @Test
    fun `taper le premier point ne selectionne pas la nuit voisine`() {
        // Le defaut sous sa forme la plus couteuse. Dix nuits sur dix jours : l'espacement dessine
        // est de 98,7 px, l'ancien calcul placait le premier point 80 px trop a gauche, donc la
        // nuit la plus proche de l'abscisse visee devenait la deuxieme. Chaque point de ce graphe
        // ouvre le detail d'une nuit : ce n'est pas un geste rate, c'est la mauvaise nuit ouverte,
        // sans que rien a l'ecran ne le signale.
        val s = spec(10)
        val zone = zoneTrace(margesDefaut(DENSITE), LARGEUR, HAUTEUR)
        val axeY = AxeLineaire(s.yMin, s.yMax, zone.top, zone.height)
        val touche = trouverPointProche(s, zone.left, axeY.y(20f), LARGEUR, HAUTEUR, DENSITE, RAYON)
        assertThat(touche).isEqualTo("s0")
        assertThat(touche).isNotEqualTo("s1")
    }

    @Test
    fun `l'ordonnee aussi suit la zone de trace, marges haute et basse comprises`() {
        val s = spec(3)
        val zone = zoneTrace(margesDefaut(DENSITE), LARGEUR, HAUTEUR)
        val yDessine = AxeLineaire(s.yMin, s.yMax, zone.top, zone.height).y(20f)
        // L'ordonnee calculee sur la hauteur totale tombe ailleurs. L'ecart est petit — une
        // dizaine de pixels ici — mais il s'ajoute a celui de l'abscisse dans une distance de
        // Manhattan, et il grandit avec la hauteur du graphe.
        val yAncien = HAUTEUR * (1f - 20f / s.yMax)
        assertThat(yDessine).isNotEqualTo(yAncien)
        assertThat(yDessine).isBetween(zone.top, zone.bottom)
        assertThat(trouverPointProche(s, zone.left, yDessine, LARGEUR, HAUTEUR, DENSITE, RAYON))
            .isEqualTo("s0")
    }

    @Test
    fun `un doigt tombe dans le vide ne selectionne rien`() {
        val s = spec(3)
        assertThat(trouverPointProche(s, LARGEUR / 2f, 10f, LARGEUR, HAUTEUR, DENSITE, RAYON))
            .isNull()
    }

    // -------------------------------------------------------------------------------------
    // L'axe calendaire date les nuits dans le fuseau ou elles ont ete vecues
    // -------------------------------------------------------------------------------------

    private fun couche(zone: String, jour: Int, heure: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 3, jour, heure, minute, 0, 0, ZoneId.of(zone))
            .toInstant().toEpochMilli()

    @Test
    fun `une nuit commencee a 23h14 s'etiquette au jour ou l'on s'est couche`() {
        // Le cas qui echouait. A New York, 23 h 14 le 12 mars, c'est le 13 mars a 03 h 14 UTC :
        // l'ancienne conversion — un epoch divise par 86 400 000 — datait la graduation au
        // lendemain. La nuit du 12 apparaissait sous l'etiquette du 13.
        val debut = couche("America/New_York", 12, 23, 14)
        val g = graduationsCalendaires(debut, debut, "America/New_York")

        assertThat(Instant.ofEpochMilli(debut).atZone(ZoneId.of("UTC")).dayOfMonth).isEqualTo(13)
        assertThat(g).hasSize(1)
        assertThat(g.first().etiquette).isEqualTo("12/03")
    }

    @Test
    fun `le pas est un jour civil et non 86 400 000 ms`() {
        // Quatre nuits a 23 h 14, du 27 au 30 mars 2026, a cheval sur le passage a l'heure d'ete
        // du 29. Un pas fixe de 86 400 000 ms decale les graduations d'une heure a partir de la
        // transition : la troisieme tombe le 30 a 00 h 14 locales, l'axe saute le 29 et sort du
        // domaine avant la quatrieme. Ici, chaque graduation reste a 23 h 14 locales.
        val premier = couche("Europe/Paris", 27, 23, 14)
        val dernier = couche("Europe/Paris", 30, 23, 14)
        val g = graduationsCalendaires(premier, dernier, "Europe/Paris")

        assertThat(g.map { it.etiquette })
            .containsExactly("27/03", "28/03", "29/03", "30/03")
        assertThat(g).allSatisfy {
            val locale = Instant.ofEpochMilli(it.ms).atZone(ZoneId.of("Europe/Paris"))
            assertThat(locale.hour).isEqualTo(23)
            assertThat(locale.minute).isEqualTo(14)
        }
        // La preuve que le pas n'est pas constant : la journee du changement d'heure fait une
        // heure de moins.
        val ecarts = g.zipWithNext { a, b -> b.ms - a.ms }
        assertThat(ecarts).containsExactly(JOUR, JOUR - 3_600_000L, JOUR)
    }

    @Test
    fun `au-dela de deux semaines les graduations passent a la semaine`() {
        val premier = couche("Europe/Paris", 1, 23, 14)
        val dernier = premier + 20 * JOUR
        val g = graduationsCalendaires(premier, dernier, "Europe/Paris")
        assertThat(g).hasSize(3)
        assertThat(g.map { it.etiquette }).containsExactly("01/03", "08/03", "15/03")
    }

    @Test
    fun `aucune graduation ne sort du domaine trace`() {
        val premier = couche("Europe/Paris", 10, 23, 14)
        val dernier = couche("Europe/Paris", 13, 7, 30)
        val g = graduationsCalendaires(premier, dernier, "Europe/Paris")
        assertThat(g).isNotEmpty()
        assertThat(g).allSatisfy { assertThat(it.ms).isBetween(premier, dernier) }
    }

    @Test
    fun `un fuseau inconnu ne fait pas tomber le dessin`() {
        val debut = couche("Europe/Paris", 12, 23, 14)
        assertThat(graduationsCalendaires(debut, debut, "Pas/Un/Fuseau")).hasSize(1)
    }

    @Test
    fun `une seule nuit donne une seule graduation`() {
        val debut = couche("Europe/Paris", 12, 23, 14)
        val g = graduationsCalendaires(debut, debut, "Europe/Paris")
        assertThat(g).hasSize(1)
        assertThat(g.first().ms).isEqualTo(debut)
        assertThat(g.first().etiquette).isEqualTo("12/03")
    }

    @Test
    fun `la zone de trace laisse ses marges des deux cotes`() {
        val zone = zoneTrace(margesDefaut(DENSITE), LARGEUR, HAUTEUR)
        assertThat(zone.left).isEqualTo(80f)
        assertThat(zone.right).isEqualTo(LARGEUR - 32f)
        assertThat(zone.top).isEqualTo(24f)
        assertThat(zone.bottom).isCloseTo(HAUTEUR - 44f, within(0.01f))
    }
}
