package com.pendulum.phone.ui

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.ui.model.ErreurPendulum
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.Situations
import com.pendulum.phone.ui.text.Textes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Le cablage des messages d'erreur, et surtout **la regle transversale**.
 *
 * Une nuit sans hypnogramme, une nuit courte, une montre restee sur la table sont des
 * *situations* : ambre. Le rouge est reserve a ce qui est reellement casse — transfert,
 * permissions, integrite, stockage. Cette regle est portee par [ErreurPendulum.technique], donc
 * elle se teste ; ecrite dans un document, elle se serait perdue au troisieme message ajoute.
 */
class SituationsTest {

    // -------------------------------------------------------------------------------------
    // Health Connect
    // -------------------------------------------------------------------------------------

    @Test
    fun `sans reponse de Health Connect, on n'affiche rien`() {
        // `null` n'est pas « pas de source » : c'est « la question n'a pas encore ete posee ».
        assertThat(Situations.sommeil(null, null, 0)).isNull()
    }

    @Test
    fun `permission retiree - E-HC-02, en rouge, avec une action`() {
        val e = Situations.sommeil(SleepReader.Availability.PERMISSIONS_MISSING, null, 0)!!
        assertThat(e.code).isEqualTo("E-HC-02")
        assertThat(e.technique).isTrue()
        assertThat(e.bouton).isEqualTo(Textes.Erreurs.HC_02_BOUTON)
    }

    @Test
    fun `aucune source - E-HC-01, en ambre, parce que rien n'est casse`() {
        val e = Situations.sommeil(SleepReader.Availability.READY, 0, 0)!!
        assertThat(e.code).isEqualTo("E-HC-01")
        assertThat(e.technique).isFalse()
    }

    @Test
    fun `deux sources contradictoires - E-HC-03, en ambre`() {
        val e = Situations.sommeil(SleepReader.Availability.READY, 2, 2)!!
        assertThat(e.code).isEqualTo("E-HC-03")
        assertThat(e.technique).isFalse()
    }

    @Test
    fun `une source, une seule origine - rien a dire`() {
        assertThat(Situations.sommeil(SleepReader.Availability.READY, 1, 1)).isNull()
    }

    @Test
    fun `Health Connect absent ne double pas l'assistant de premier lancement`() {
        // Ces deux cas ont deja leur ecran, avec leur bouton d'installation. Deux endroits pour
        // une meme reparation, et l'utilisateur essaie celui qui ne marche pas.
        assertThat(Situations.sommeil(SleepReader.Availability.SDK_UNAVAILABLE, null, 0)).isNull()
        assertThat(Situations.sommeil(SleepReader.Availability.UPDATE_REQUIRED, null, 0)).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Une nuit
    // -------------------------------------------------------------------------------------

    private fun nuit(
        maskSource: String = "HEALTH_CONNECT",
        exclusionReason: String = ComparabilityRule.OK,
        truncated: Boolean = false,
    ) = ComparableNight(
        sessionHex = "abcd",
        startWallMs = 1_700_000_000_000L,
        zoneId = "Europe/Paris",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = maskSource,
        gate = "FULL",
        independence = "INDEPENDENT",
        plmi = 18.4,
        plmiSpt = 9.0,
        fundamentalSec = 21.0,
        rhythmValid = true,
        periodicityIndex = 0.58,
        missRate = 0.21,
        analysableTstMin = 312.0,
        analysableMin = 460.0,
        truncated = truncated,
        revealedAtMs = null,
        comparable = exclusionReason == ComparabilityRule.OK,
        exclusionReason = exclusionReason,
    )

    private fun session(
        batteryPctLast: Int? = 62,
        gapTotalMs: Long = 0L,
        totalChunks: Int? = 17,
    ) = NightSessionEntity(
        sessionHex = "abcd",
        startWallMs = 1_700_000_000_000L,
        plannedStopWallMs = 1_700_000_000_000L,
        zoneId = "Europe/Paris",
        tzOffsetStartMin = 60,
        tzOffsetEndMin = 60,
        nominalRateHz = 50,
        modeFlags = 0,
        state = "CLOSED",
        totalChunks = totalChunks,
        batteryPctLast = batteryPctLast,
        gapTotalMs = gapTotalMs,
    )

    @Test
    fun `une nuit sans rien a signaler ne produit aucune carte`() {
        assertThat(Situations.nuit(nuit(), session())).isNull()
    }

    @Test
    fun `une nuit tronquee ne se fait pas passer pour un transfert incomplet`() {
        // `truncated` dit que l'enregistrement s'est arrete sans fermeture propre, pas qu'il
        // manque des fichiers. En deduire `E-NIGHT-07` afficherait « des fichiers manquent » —
        // avec un bouton rouge — sur une nuit integralement transferee. Ce code est rendu par
        // `MachineReveil`, qui a les deux compteurs de chunks sous la main.
        assertThat(Situations.nuit(nuit(truncated = true), session())).isNull()
    }

    @Test
    fun `nuit trop courte - ambre, et aucun bouton parce qu'il n'y a rien a faire`() {
        val e = Situations.nuit(nuit(exclusionReason = ComparabilityRule.TOO_SHORT), session())!!
        assertThat(e.code).isEqualTo("E-NIGHT-02")
        assertThat(e.technique).isFalse()
        assertThat(e.bouton).isNull()
    }

    @Test
    fun `montre dechargee - ambre, le chiffre reste mais il est sous-estime`() {
        val e = Situations.nuit(nuit(), session(batteryPctLast = 4))!!
        assertThat(e.code).isEqualTo("E-NIGHT-03")
        assertThat(e.technique).isFalse()
    }

    @Test
    fun `trous de signal - ambre, avec une action qui a un cout`() {
        val e = Situations.nuit(nuit(), session(gapTotalMs = 300_000L))!!
        assertThat(e.code).isEqualTo("E-NIGHT-04")
        assertThat(e.technique).isFalse()
        assertThat(e.bouton).isEqualTo(Textes.Erreurs.NIGHT_04_BOUTON)
    }

    @Test
    fun `masque accelerometrique - ambre, en dernier parce que c'est le plus frequent`() {
        val e = Situations.nuit(nuit(maskSource = Mapping.MASQUE_ACCELERO), session())!!
        assertThat(e.code).isEqualTo("E-NIGHT-01")
        assertThat(e.technique).isFalse()
    }

    @Test
    fun `l'ordre suit la consequence - ce qui sort de la tendance passe devant`() {
        // Nuit courte ET sans hypnogramme : c'est la duree qu'il faut annoncer, sinon
        // l'utilisateur repare la source de sommeil et la nuit reste hors de la tendance.
        val e = Situations.nuit(
            nuit(maskSource = Mapping.MASQUE_ACCELERO, exclusionReason = ComparabilityRule.TOO_SHORT),
            session(),
        )!!
        assertThat(e.code).isEqualTo("E-NIGHT-02")
    }

    // -------------------------------------------------------------------------------------
    // Le gabarit, sur tous les messages produits
    // -------------------------------------------------------------------------------------

    @Test
    fun `tout message a un titre, une cause, une action et un code stable`() {
        val tous = listOf(
            Situations.sommeil(SleepReader.Availability.PERMISSIONS_MISSING, null, 0),
            Situations.sommeil(SleepReader.Availability.READY, 0, 0),
            Situations.sommeil(SleepReader.Availability.READY, 2, 2),
            Situations.nuit(nuit(exclusionReason = ComparabilityRule.TOO_SHORT), session()),
            Situations.nuit(nuit(), session(batteryPctLast = 4)),
            Situations.nuit(nuit(), session(gapTotalMs = 300_000L)),
            Situations.nuit(nuit(maskSource = Mapping.MASQUE_ACCELERO), session()),
        ).filterNotNull()

        assertThat(tous).hasSize(7)
        tous.forEach { e ->
            assertThat(e.code).matches("E-[A-Z]+-\\d\\d")
            assertThat(e.titre).isNotBlank()
            assertThat(e.cause).isNotBlank()
            assertThat(e.action).isNotBlank()
            // « Une erreur est survenue » n'a nulle part ou s'ecrire, et on le verifie.
            assertThat(e.titre.lowercase()).doesNotContain("oops")
            assertThat(e.cause.lowercase()).doesNotContain("an error occurred")
        }
    }

    @Test
    fun `le rouge est reserve a ce qui est reellement casse`() {
        // Une seule situation rouge dans tout ce fichier : la permission retiree. Toutes les
        // situations de nuit sont ambre, ce qui est exactement la regle transversale.
        val rouges = listOf(
            Situations.sommeil(SleepReader.Availability.PERMISSIONS_MISSING, null, 0),
        ).filterNotNull()
        assertThat(rouges).allMatch { it.technique }

        val ambres = listOf(
            Situations.sommeil(SleepReader.Availability.READY, 0, 0),
            Situations.sommeil(SleepReader.Availability.READY, 2, 2),
            Situations.nuit(nuit(exclusionReason = ComparabilityRule.TOO_SHORT), session()),
            Situations.nuit(nuit(), session(batteryPctLast = 4)),
            Situations.nuit(nuit(), session(gapTotalMs = 300_000L)),
            Situations.nuit(nuit(maskSource = Mapping.MASQUE_ACCELERO), session()),
        ).filterNotNull()
        assertThat(ambres).noneMatch { it.technique }
    }
}
