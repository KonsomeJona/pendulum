package com.pendulum.phone.ui

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.TelemetryPointEntity
import com.pendulum.phone.ui.model.Controles
import com.pendulum.phone.ui.model.PenteBatterie
import com.pendulum.phone.ui.model.PorteP1
import com.pendulum.phone.ui.model.PorteP1.Conformite
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.texte
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
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
     * @param analysee la nuit a-t-elle ete analysee ? Le defaut est **oui**, parce que c'est
     *   l'etat dans lequel un verdict de couverture a un sens. Le cas contraire a son propre test
     *   et n'a pas a se glisser en silence dans tous les autres.
     */
    private fun nuit(
        debutMs: Long = COUCHER,
        heures: Double? = 8.0,
        couverture: Double = 1.0,
        batterie: Int? = 50,
        fs: Double? = CADENCE.toDouble(),
        zone: String = "Europe/Paris",
        analysee: Boolean = true,
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
            analyzedAtMs = if (analysee) debutMs + 9 * HEURE_MS else null,
            fsMeasuredHz = fs,
            sampleCount = Math.round(attendus * couverture),
        )
    }

    /**
     * Une serie de telemetrie qui se decharge lineairement, sans bruit.
     *
     * Elle est **exacte par construction** : c'est ce qui permet de verifier la droite a la
     * decimale plutot que de constater qu'elle a « l'air correcte ». Le bruit reel de la mesure
     * n'a rien a apprendre a un test de moindres carres — ce qui compte ici est que l'horizon
     * d'extrapolation parte du debut de la nuit, que les points sous charge sortent, et que le
     * refus se declenche ou il est annonce.
     *
     * @param nombre nombre de points.
     * @param pctParHeure consommation, en points de pourcentage par heure.
     * @param pasS intervalle entre deux points, en secondes. Une minute sur l'appareil ; le
     *   parametre existe pour pouvoir separer les deux conditions de refus, qui coincident a la
     *   cadence reelle et ne coincideraient plus a une autre.
     * @param capaciteUah capacite pleine ; le pourcentage entier en decoule, comme sur l'appareil.
     * @param enCharge indices des points a marquer sous charge. Leur compteur **remonte**, ce qui
     *   est le piege : les laisser entrer inverserait le signe de la pente.
     */
    private fun telemetrie(
        nombre: Int,
        pctParHeure: Double,
        pctDepart: Double = 100.0,
        pasS: Long = 60L,
        capaciteUah: Double = 300_000.0,
        enCharge: Set<Int> = emptySet(),
    ): List<TelemetryPointEntity> = (0 until nombre).map { i ->
        val h = i * pasS / 3_600.0
        val pct = pctDepart - pctParHeure * h
        val charge = if (i in enCharge) capaciteUah else capaciteUah * pct / 100.0
        TelemetryPointEntity(
            sessionHex = "t",
            // L'horloge monotone, en nanosecondes : c'est la seule sur laquelle une duree se
            // calcule, et c'est celle que le point porte.
            elapsedRealtimeNs = i * pasS * 1_000_000_000L,
            sensorTsNs = i * pasS * 1_000_000_000L,
            batteryChargeUah = Math.round(charge).toInt(),
            maxIntervalUs = 25_000,
            fsyncTotalUs = 0,
            fsyncMaxUs = 0,
            temperatureDeciC = 320,
            measuredRateCentiHz = 5000,
            jitterStdUs = 800,
            clippedSamples = 0,
            fsyncCount = 0,
            batteryPct = Math.round(pct).toInt().coerceIn(0, 100),
            offBody = 0,
            charging = i in enCharge,
        )
    }

    /**
     * Une nuit comparable quelconque : [Controles.de] en a besoin pour ses autres lignes, et
     * aucune d'elles n'entre dans ce que ce fichier verifie.
     */
    private fun nuitComparable() = ComparableNight(
        sessionHex = "abcd",
        startWallMs = COUCHER,
        zoneId = "Europe/Paris",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = "HEALTH_CONNECT",
        gate = "FULL",
        independence = "INDEPENDENT",
        plmi = 18.4,
        plmiSpt = 9.0,
        fundamentalSec = 21.0,
        rhythmValid = true,
        periodicityIndex = 0.58,
        missRate = 0.11,
        analysableTstMin = 312.0,
        analysableMin = 460.0,
        truncated = false,
        revealedAtMs = null,
        comparable = true,
        exclusionReason = ComparabilityRule.OK,
    )

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

    /**
     * Le defaut du 3 aout 2026, epingle.
     *
     * `night_session.sampleCount` vaut 0 tant qu'`AnalyzeWorker` n'a pas tourne, et l'ancienne
     * version divisait alors 0 par 96 163 : la nuit sortait `NON_CONFORME` avec `coverage=0.0%`.
     * Une nuit **non analysee** etait donc rapportee « hors P1 », ce qui est faux dans les deux
     * sens — elle n'a pas echoue, et elle a peut-etre parfaitement tenu.
     *
     * Le second cas est celui qu'il ne faut pas confondre avec le premier : une nuit **analysee**
     * dont l'analyse n'a retenu aucun echantillon a bel et bien une couverture de zero, et
     * celle-la echoue.
     */
    @Test
    fun `une nuit non analysee n'est pas hors P1, elle n'est pas decidable`() {
        val nonAnalysee = PorteP1.de(nuit(couverture = 0.0, analysee = false))
        assertThat(Controles.couverture(nuit(couverture = 0.0, analysee = false))).isNull()
        assertThat(nonAnalysee.couverture.etat).isEqualTo(Conformite.INDETERMINE)
        assertThat(Ressources.resoudre(nonAnalysee.couverture.valeur)).isEqualTo("—")
        assertThat(nonAnalysee.verdict).isNotEqualTo(Conformite.NON_CONFORME)

        // Analysee, et zero echantillon retenu : la couverture est nulle et le verdict tombe.
        val analyseeVide = PorteP1.de(nuit(couverture = 0.0, analysee = true))
        assertThat(analyseeVide.couverture.etat).isEqualTo(Conformite.NON_CONFORME)
        assertThat(Ressources.resoudre(analyseeVide.couverture.valeur)).isEqualTo("0.0%")
    }

    /**
     * La ligne de controle du detail de nuit lit la meme inconnue. Deux ecrans, un seul calcul :
     * c'est la regle que `Controles` porte deja pour le seuil de batterie.
     */
    @Test
    fun `la ligne de controle rend un tiret sur une nuit non analysee`() {
        val ligne = Controles
            .de(nuit(couverture = 0.0, analysee = false), nuitComparable(), null, texte(R.string.settings_health_connect))
            .first { it.libelle == texte(R.string.night_detail_coverage) }
        assertThat(Ressources.resoudre(ligne.valeur)).isEqualTo("—")
        // `null` et non `false`, sinon les deux ecrans ne lisent plus la meme inconnue : le test
        // voisin exige `INDETERMINE` et un verdict qui n'est pas `NON_CONFORME` pour cette entree
        // exacte. Cette ligne assertait l'inverse — valeur « on ne sait pas », etat « non tenu » —
        // et c'est ce qui rendait `✗` une couverture jamais mesuree.
        assertThat(ligne.ok).isNull()
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
        assertThat(PorteP1.de(nuit(heures = 8.0, batterie = Controles.BATTERIE_MIN_PCT + 1)).batterie.etat)
            .isEqualTo(Conformite.CONFORME)
        assertThat(PorteP1.de(nuit(heures = 8.0, batterie = Controles.BATTERIE_MIN_PCT - 1)).batterie.etat)
            .isEqualTo(Conformite.NON_CONFORME)
    }

    /**
     * La borne exacte, et le seul endroit ou elle etait ambigue.
     *
     * `01-overview.md` §5 ecrit « battery **above** 20 % » et la KDoc de
     * [Controles.BATTERIE_MIN_PCT] disait la meme chose, pendant que les deux comparateurs
     * ecrivaient `>=`. A 20 % pile la documentation disait echec et le code reussite — un
     * desaccord qui ne se declenche qu'une nuit sur cinquante et qui, ce jour-la, fait franchir
     * la porte bloquante du projet a une campagne qui ne l'a pas franchie.
     */
    @Test
    fun `vingt pour cent pile echoue, comme le document le dit`() {
        assertThat(PorteP1.de(nuit(heures = 8.0, batterie = Controles.BATTERIE_MIN_PCT)).batterie.etat)
            .isEqualTo(Conformite.NON_CONFORME)
        // Et la ligne de controle du detail de nuit tranche pareil : un seul seuil, un seul
        // comparateur, sinon la meme nuit porte deux verdicts selon l'ecran qu'on regarde.
        assertThat(ligneBatterie(Controles.BATTERIE_MIN_PCT)?.ok).isFalse()
        assertThat(ligneBatterie(Controles.BATTERIE_MIN_PCT + 1)?.ok).isTrue()
    }

    private fun ligneBatterie(pct: Int) = Controles
        .de(nuit(batterie = pct), nuitComparable(), null, texte(R.string.settings_health_connect))
        .firstOrNull { it.libelle == texte(R.string.night_detail_battery_end) }

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
    // Batterie — la pente, qui rend le critere decidable sur une nuit courte
    // -------------------------------------------------------------------------------------

    /**
     * Le cas que le pourcentage seul ne sait pas trancher, et qui est le cas reel.
     *
     * Une heure de veille a 100 % : le pourcentage n'a pas bouge d'un palier — c'est exactement ce
     * qu'a mesure le §12.4, `level: 100` aux six pas — et l'ancienne lecture rendait `INDETERMINE`.
     * Le compteur coulombmetrique, lui, a une pente, et huit heures a ce regime finissent sous le
     * seuil.
     */
    @Test
    fun `une nuit courte se tranche sur la pente, la ou le pourcentage ne bouge pas`() {
        // 11 % par heure : a 8 h il resterait 12 %, donc l'echec — sur une nuit ou le dernier
        // pourcentage rapporte est 100 et ne dit rien.
        val trop = PorteP1.de(nuit(heures = 1.0, batterie = 100), telemetrie(60, pctParHeure = 11.0))
        assertThat(trop.batterie.etat).isEqualTo(Conformite.NON_CONFORME)

        // 5 % par heure : 60 % a huit heures.
        val large = PorteP1.de(nuit(heures = 1.0, batterie = 100), telemetrie(60, pctParHeure = 5.0))
        assertThat(large.batterie.etat).isEqualTo(Conformite.CONFORME)
        assertThat(Ressources.resoudre(large.batterie.valeur))
            .contains("60%").contains("8 h").contains("60 points")
    }

    /**
     * L'origine des temps est le **debut de la nuit**, pas le premier point utilisable.
     *
     * Une montre restee une heure sur son socle avant de commencer a se decharger n'a que sept
     * heures devant elle, pas huit. Prendre le premier point retenu comme origine offrirait une
     * heure gratuite a chaque nuit ou la montre a ete branchee, et l'ecart ne se verrait nulle part.
     */
    @Test
    fun `les points sous charge sortent de la pente sans decaler l'horizon`() {
        // Deux heures : la premiere sous charge (compteur a fond), la seconde en decharge a 10 %/h.
        val points = telemetrie(120, pctParHeure = 10.0, enCharge = (0 until 60).toSet())
        val p = PenteBatterie.de(points, PorteP1.DUREE_CIBLE_H)!!
        assertThat(p.pointsSousCharge).isEqualTo(60)
        assertThat(p.pointsRetenus).isEqualTo(60)
        assertThat(p.pctParHeure).isCloseTo(10.0, within(0.2))
        // La droite passe par 100 % a t=0 — le debut de la nuit — donc 20 % a huit heures. Si
        // l'origine avait ete le premier point retenu, on lirait 30 %.
        assertThat(p.pctA8h).isCloseTo(20.0, within(0.5))
    }

    @Test
    fun `une pente sur trois points n'est pas une pente`() {
        assertThat(PenteBatterie.de(telemetrie(3, pctParHeure = 10.0), PorteP1.DUREE_CIBLE_H)).isNull()
        // Juste sous le compte minimal, et juste a lui. La borne est celle qui bascule en
        // silence : au-dessus on publie un chiffre, en dessous on rend un tiret.
        assertThat(PenteBatterie.de(telemetrie(PenteBatterie.POINTS_MIN - 1, 10.0), PorteP1.DUREE_CIBLE_H))
            .isNull()
        assertThat(PenteBatterie.de(telemetrie(PenteBatterie.POINTS_MIN, 10.0), PorteP1.DUREE_CIBLE_H))
            .isNotNull()
    }

    /**
     * Assez de points, mais pas assez d'etendue : trente points survivants disperses dans une nuit
     * passee sur son socle ne portent pas une extrapolation a huit heures. Le compte et l'etendue
     * sont deux conditions et non une.
     */
    @Test
    fun `assez de points mais trop peu d'etendue ne conclut pas`() {
        // Quarante points espaces de dix secondes : le compte passe largement, l'etendue fait
        // six minutes et demie. C'est la condition d'etendue, et elle seule, qui refuse — ce qui
        // est exactement ce qu'elle protege le jour ou la cadence des points changerait.
        val serres = telemetrie(40, pctParHeure = 10.0, pasS = 10L)
        assertThat(serres).hasSizeGreaterThan(PenteBatterie.POINTS_MIN)
        assertThat(PenteBatterie.de(serres, PorteP1.DUREE_CIBLE_H)).isNull()
    }

    @Test
    fun `un compteur qui ne descend pas ne rend pas une autonomie infinie`() {
        // Consommation nulle : la pente est plate. On refuse plutot que d'annoncer 100 % a huit
        // heures — c'est le faux vert le plus cher possible sur le critere le plus discriminant.
        assertThat(PenteBatterie.de(telemetrie(120, pctParHeure = 0.0), PorteP1.DUREE_CIBLE_H)).isNull()
    }

    /**
     * Sans telemetrie, rien ne change : les trois lectures exactes du dernier pourcentage restent
     * le chemin, et une nuit enregistree avant que la telemetrie n'existe garde son verdict.
     */
    @Test
    fun `sans telemetrie le critere retombe sur les trois lectures exactes`() {
        assertThat(PorteP1.de(nuit(heures = 6.0, batterie = 45), emptyList()).batterie.etat)
            .isEqualTo(Conformite.INDETERMINE)
        assertThat(PorteP1.de(nuit(heures = 8.0, batterie = 45), emptyList()).batterie.etat)
            .isEqualTo(Conformite.CONFORME)
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
            assertThat(Ressources.resoudre(it.valeur)).isNotBlank()
            assertThat(Ressources.resoudre(it.seuil)).isNotBlank()
        }
        assertThat(Ressources.resoudre(v.couverture.valeur)).isEqualTo("99.4%")
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
