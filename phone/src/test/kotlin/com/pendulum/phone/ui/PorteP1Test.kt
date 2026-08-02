package com.pendulum.phone.ui

import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.ui.model.Controles
import com.pendulum.phone.ui.model.PorteP1
import com.pendulum.phone.ui.model.PorteP1.Conformite
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * La porte P1 sur ses bornes.
 *
 * ### Pourquoi ces tests plutot qu'une relecture
 *
 * P1 est le seul jalon declare **bloquant** du projet : tant qu'il n'est pas franchi, aucune ligne
 * d'algorithme n'est censee etre ecrite, et s'il ne peut pas l'etre, le materiel change. Un
 * verdict faux ne se voit pas — il ressemble a un verdict. Les trois endroits ou il peut basculer
 * en silence sont l'egalite exacte au seuil, l'inconnue traitee comme une reussite, et la
 * consecutivite comprise comme un simple compte.
 */
class PorteP1Test {

    private companion object {
        const val HEURE_MS = 3_600_000L
        const val CADENCE = 50

        /** 12 mars 2026, 23 h 14, heure de Paris — un coucher plausible, et pas minuit. */
        val COUCHER: Long = ZonedDateTime
            .of(2026, 3, 12, 23, 14, 0, 0, ZoneId.of("Europe/Paris"))
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Une nuit, decrite par ce qui decide de son verdict.
     *
     * @param couverture fraction des echantillons attendus. Le nombre d'echantillons est derive
     *   d'elle plutot que donne : c'est le chemin que suit la vraie donnee, et ecrire directement
     *   un `sampleCount` laisserait passer une erreur de denominateur.
     */
    private fun nuit(
        debutMs: Long = COUCHER,
        heures: Double? = 8.0,
        couverture: Double = 1.0,
        batterie: Int? = 50,
        fs: Double? = CADENCE.toDouble(),
        zone: String = "Europe/Paris",
    ): NightSessionEntity {
        val dureeMs = heures?.let { (it * HEURE_MS).toLong() }
        val attendus = (dureeMs ?: 0L) * CADENCE / 1000.0
        return NightSessionEntity(
            sessionHex = "n%d".format(debutMs % 1000),
            startWallMs = debutMs,
            plannedStopWallMs = debutMs + 8 * HEURE_MS,
            endWallMs = dureeMs?.let { debutMs + it },
            zoneId = zone,
            tzOffsetStartMin = 60,
            tzOffsetEndMin = 60,
            nominalRateHz = CADENCE,
            modeFlags = 0,
            state = if (dureeMs == null) "OPEN" else "CLOSED",
            batteryPctLast = batterie,
            fsMeasuredHz = fs,
            sampleCount = Math.round(attendus * couverture),
        )
    }

    // -------------------------------------------------------------------------------------
    // Couverture
    // -------------------------------------------------------------------------------------

    @Test
    fun `la couverture au seuil exact passe, un dixieme de point en dessous non`() {
        assertThat(PorteP1.de(nuit(couverture = Controles.COUVERTURE_MIN)).couverture.etat)
            .isEqualTo(Conformite.CONFORME)
        assertThat(PorteP1.de(nuit(couverture = 0.989)).couverture.etat)
            .isEqualTo(Conformite.NON_CONFORME)
    }

    @Test
    fun `une nuit sans fin connue n'a pas de couverture, elle en a une pour l'instant`() {
        // Le piege : `sampleCount` est non nul et la tentation est de diviser par la duree ecoulee.
        // Une session ouverte n'a pas de duree, donc pas de denominateur, donc pas de verdict.
        val ouverte = PorteP1.de(nuit(heures = null))
        assertThat(ouverte.couverture.etat).isEqualTo(Conformite.INDETERMINE)
        assertThat(ouverte.verdict).isEqualTo(Conformite.INDETERMINE)
    }

    // -------------------------------------------------------------------------------------
    // Batterie — et les trois lectures qu'on a le droit d'en faire
    // -------------------------------------------------------------------------------------

    @Test
    fun `a huit heures, le niveau rapporte est le niveau a huit heures`() {
        assertThat(PorteP1.de(nuit(heures = 8.0, batterie = Controles.BATTERIE_MIN_PCT)).batterie.etat)
            .isEqualTo(Conformite.CONFORME)
        assertThat(PorteP1.de(nuit(heures = 8.0, batterie = Controles.BATTERIE_MIN_PCT - 1)).batterie.etat)
            .isEqualTo(Conformite.NON_CONFORME)
    }

    @Test
    fun `une nuit courte ne dit rien sur huit heures, sauf si elle est deja sous le seuil`() {
        // Six heures a 45 % : on ne sait pas ou en serait la batterie a huit heures, et le
        // niveau de depart n'est stocke nulle part. On ne devine pas.
        assertThat(PorteP1.de(nuit(heures = 6.0, batterie = 45)).batterie.etat)
            .isEqualTo(Conformite.INDETERMINE)
        // Six heures a 15 % : la batterie ne remontera pas, donc la nuit echoue. L'affirmer ne
        // suppose rien — c'est la seule inference que cette donnee autorise.
        assertThat(PorteP1.de(nuit(heures = 6.0, batterie = 15)).batterie.etat)
            .isEqualTo(Conformite.NON_CONFORME)
    }

    @Test
    fun `sans niveau rapporte, le critere est indetermine et non satisfait`() {
        assertThat(PorteP1.de(nuit(batterie = null)).batterie.etat).isEqualTo(Conformite.INDETERMINE)
    }

    // -------------------------------------------------------------------------------------
    // Cadence
    // -------------------------------------------------------------------------------------

    @Test
    fun `la cadence delivree est toleree a cinq pour cent, bornes comprises`() {
        val limite = CADENCE * (1.0 + Controles.TOLERANCE_FS)
        assertThat(PorteP1.de(nuit(fs = limite)).frequence.etat).isEqualTo(Conformite.CONFORME)
        assertThat(PorteP1.de(nuit(fs = limite + 0.1)).frequence.etat).isEqualTo(Conformite.NON_CONFORME)
        assertThat(PorteP1.de(nuit(fs = null)).frequence.etat).isEqualTo(Conformite.INDETERMINE)
    }

    // -------------------------------------------------------------------------------------
    // Le verdict d'une nuit
    // -------------------------------------------------------------------------------------

    @Test
    fun `un echec l'emporte sur une inconnue`() {
        // Couverture insuffisante et batterie inconnue : la nuit a echoue, elle n'est pas en
        // suspens. L'inverse ferait esperer une nuit qui est deja perdue.
        val v = PorteP1.de(nuit(couverture = 0.90, batterie = null))
        assertThat(v.verdict).isEqualTo(Conformite.NON_CONFORME)
    }

    @Test
    fun `chaque critere porte sa valeur et son seuil, jamais l'un sans l'autre`() {
        val v = PorteP1.de(nuit(couverture = 0.994))
        assertThat(v.criteres).hasSize(3)
        assertThat(v.criteres).allSatisfy {
            assertThat(it.valeur).isNotBlank()
            assertThat(it.seuil).isNotBlank()
        }
        assertThat(v.couverture.valeur).isEqualTo("99.4%")
    }

    // -------------------------------------------------------------------------------------
    // La soiree, et la bascule a midi
    // -------------------------------------------------------------------------------------

    @Test
    fun `un coucher apres minuit se rattache a la soiree de la veille`() {
        val uneHeureTrente = ZonedDateTime
            .of(2026, 3, 13, 1, 30, 0, 0, ZoneId.of("Europe/Paris"))
            .toInstant().toEpochMilli()
        assertThat(PorteP1.de(nuit(debutMs = uneHeureTrente)).soiree)
            .isEqualTo(LocalDate.of(2026, 3, 12))
    }

    // -------------------------------------------------------------------------------------
    // La campagne — « trois nuits » et « trois nuits consecutives » ne sont pas la meme chose
    // -------------------------------------------------------------------------------------

    private fun campagneDe(vararg jours: Pair<Int, Conformite>): PorteP1.Campagne {
        val verdicts = jours.map { (jour, attendu) ->
            val debut = ZonedDateTime
                .of(2026, 3, jour, 23, 14, 0, 0, ZoneId.of("Europe/Paris"))
                .toInstant().toEpochMilli()
            // Une nuit non conforme l'est par sa couverture ; c'est le critere le plus direct.
            PorteP1.de(
                nuit(
                    debutMs = debut,
                    couverture = if (attendu == Conformite.CONFORME) 1.0 else 0.5,
                )
            )
        }
        return PorteP1.campagne(verdicts)
    }

    @Test
    fun `trois nuits conformes de suite franchissent la porte`() {
        val c = campagneDe(
            10 to Conformite.CONFORME,
            11 to Conformite.CONFORME,
            12 to Conformite.CONFORME,
        )
        assertThat(c.serieMax).isEqualTo(3)
        assertThat(c.franchie).isTrue()
        assertThat(c.debutSerie).isEqualTo("10 March")
        assertThat(c.finSerie).isEqualTo("12 March")
    }

    @Test
    fun `trois nuits conformes separees par des soirees sautees ne franchissent rien`() {
        // Le coeur du critere. Trois reussites eparpillees sont trois coups de chance ; la porte
        // demande la reproductibilite, c'est-a-dire trois nuits de suite. Une soiree non
        // enregistree rompt la serie au meme titre qu'un echec — sauter la nuit qui suit un echec
        // est precisement ce qui rendrait le critere satisfaisable a volonte.
        val c = campagneDe(
            10 to Conformite.CONFORME,
            12 to Conformite.CONFORME,
            14 to Conformite.CONFORME,
        )
        assertThat(c.nuitsConformes).isEqualTo(3)
        assertThat(c.serieMax).isEqualTo(1)
        assertThat(c.franchie).isFalse()
    }

    @Test
    fun `une nuit hors criteres au milieu casse la serie`() {
        val c = campagneDe(
            10 to Conformite.CONFORME,
            11 to Conformite.CONFORME,
            12 to Conformite.NON_CONFORME,
            13 to Conformite.CONFORME,
            14 to Conformite.CONFORME,
        )
        assertThat(c.nuitsConformes).isEqualTo(4)
        assertThat(c.serieMax).isEqualTo(2)
        assertThat(c.franchie).isFalse()
    }

    @Test
    fun `l'ordre d'entree ne change pas le verdict`() {
        // Le repository lit la nuit la plus recente en premier ; la serie se lit dans l'autre
        // sens. Un tri implicite serait exactement le genre de dependance qui casse le jour ou
        // l'appelant change.
        val c = campagneDe(
            12 to Conformite.CONFORME,
            10 to Conformite.CONFORME,
            11 to Conformite.CONFORME,
        )
        assertThat(c.serieMax).isEqualTo(3)
        assertThat(c.debutSerie).isEqualTo("10 March")
    }

    @Test
    fun `deux sessions sur la meme soiree ne comptent pas pour deux nuits`() {
        val premiere = ZonedDateTime
            .of(2026, 3, 12, 23, 14, 0, 0, ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        val seconde = ZonedDateTime
            .of(2026, 3, 13, 2, 40, 0, 0, ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        val c = PorteP1.campagne(
            listOf(PorteP1.de(nuit(debutMs = premiere)), PorteP1.de(nuit(debutMs = seconde)))
        )
        assertThat(c.nuitsConformes).isEqualTo(2)
        assertThat(c.serieMax).isEqualTo(1)
    }

    @Test
    fun `une campagne vide ne franchit pas la porte`() {
        val c = PorteP1.campagne(emptyList())
        assertThat(c.serieMax).isZero()
        assertThat(c.franchie).isFalse()
        assertThat(c.debutSerie).isNull()
    }
}
