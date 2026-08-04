package com.pendulum.phone.export

import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.ui.model.PenteBatterie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Le fichier qui portera la decision de materiel.
 *
 * Ce test ne verifie pas une mise en forme : il verifie que le fichier reste **relisible** sans
 * l'application qui l'a produit, et qu'il ne se corrompt pas selon la langue du telephone.
 */
class PorteP1CsvTest {

    private fun nuit(couverture: Double, batterie: Int, heures: Double): NightSessionEntity {
        val debut = ZonedDateTime
            .of(2026, 3, 12, 23, 14, 0, 0, ZoneId.of("Europe/Paris"))
            .toInstant().toEpochMilli()
        val dureeMs = (heures * 3_600_000L).toLong()
        return NightSessionEntity(
            sessionHex = "a1b2",
            startWallMs = debut,
            plannedStopWallMs = debut + 8 * 3_600_000L,
            endWallMs = debut + dureeMs,
            zoneId = "Europe/Paris",
            tzOffsetStartMin = 60,
            tzOffsetEndMin = 60,
            nominalRateHz = 50,
            modeFlags = 0,
            state = "CLOSED",
            batteryPctLast = batterie,
            // Sans cette date, la couverture est `null` — une nuit non analysee n'a pas de
            // numerateur — et la colonne `coverage` sort vide. C'est le comportement voulu depuis
            // le defaut du 3 aout 2026 ; ce que ce fichier verifie est la mise en forme d'une nuit
            // qui, elle, a bien ete analysee.
            analyzedAtMs = debut + dureeMs + 600_000L,
            fsMeasuredHz = 50.31,
            sampleCount = Math.round(dureeMs * 50 / 1000.0 * couverture),
        )
    }

    @Test
    fun `les nombres sortent avec un point decimal, quelle que soit la langue du telephone`() {
        // Le piege exact : sur un telephone en francais, `"%.5f".format(v)` rend « 0,99400 » —
        // c'est-a-dire un champ contenant le separateur de colonnes. Le fichier reste
        // syntaxiquement valide et decale toutes les colonnes d'un cran : la corruption qui se
        // lit sans erreur.
        val defaut = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val csv = PorteP1Exporter.csv(listOf(nuit(couverture = 0.994, batterie = 34, heures = 8.2)))
            val ligne = csv.lines().last { it.isNotBlank() }
            assertThat(ligne).contains("0.99400")
            assertThat(ligne).doesNotContain("0,99")
        } finally {
            Locale.setDefault(defaut)
        }
    }

    @Test
    fun `le fichier porte ses seuils et sa conclusion avant ses lignes`() {
        val csv = PorteP1Exporter.csv(listOf(nuit(couverture = 0.994, batterie = 34, heures = 8.2)))
        val lignes = csv.lines()
        assertThat(lignes.first()).startsWith("#")
        assertThat(csv).contains("# coverage_min=0.99")
        assertThat(csv).contains("# nights_required_consecutive=3")
        // Une seule nuit ne peut pas franchir une porte qui en demande trois d'affilee.
        assertThat(csv).contains("# longest_consecutive_run=1")
        assertThat(csv).contains("# gate_passed=false")
        // Le trou de mesure qui reste est dans le fichier, pas seulement a l'ecran.
        assertThat(csv).contains("# not transmitted: largest single gap")
        // Et la facon dont la batterie est obtenue, avec le seuil de refus : un pourcentage
        // extrapole qui ne dirait pas qu'il l'est se relirait comme un pourcentage mesure.
        assertThat(csv).contains("least-squares fit on the coulomb counter")
        // Le seuil de refus est lu depuis la constante et non recopie : un fichier qui annoncerait
        // un seuil different de celui qu'applique le code serait pire qu'un fichier muet.
        assertThat(csv).contains("refused below ${PenteBatterie.POINTS_MIN} points")

        val entete = lignes.first { !it.startsWith("#") }
        assertThat(entete.split(",")).startsWith("night_key", "session_hex")
        assertThat(entete.split(",")).endsWith("verdict")

        val ligne = lignes.last { it.isNotBlank() }
        assertThat(ligne.split(",")).hasSameSizeAs(entete.split(","))
        assertThat(ligne).endsWith(",CONFORME")
    }

    @Test
    fun `une nuit trop courte sort avec un critere batterie indetermine, pas conforme`() {
        val csv = PorteP1Exporter.csv(listOf(nuit(couverture = 0.994, batterie = 41, heures = 6.0)))
        val ligne = csv.lines().last { it.isNotBlank() }
        assertThat(ligne).contains("INDETERMINE")
        assertThat(ligne).endsWith(",INDETERMINE")
    }
}
