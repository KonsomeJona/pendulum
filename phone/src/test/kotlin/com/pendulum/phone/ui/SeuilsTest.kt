package com.pendulum.phone.ui

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.CheminDeCalcul
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.text.Textes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Les seuils affiches, et ce qu'ils promettent.
 *
 * Trois choses y sont verifiees, et aucune ne se voit a l'oeil sur une capture d'ecran : que le
 * seuil de 15/h est **etiquete** plutot que pose nu, que le nombre de nuits demande depend du
 * regime dans lequel on se trouve, et que le bloc « pourquoi ce chiffre » reste generatif.
 */
class SeuilsTest {

    // -------------------------------------------------------------------------------------
    // La ligne des 15/h, ancree
    // -------------------------------------------------------------------------------------

    @Test
    fun `le seuil est presente avec sa provenance et son equivalent actimetrique`() {
        val legende = Textes.Graphes.LEGENDE_SEUIL_15
        // Le repere de nuisance : c'est cette formulation qui fait tomber la demande de contact
        // urgent de 55,8 % a 34,7 % (Zikmund-Fisher, JMIR 2018).
        assertThat(legende).contains("Many physicians are not concerned below it")
        // Et l'ecart PSG / actimetrie de cheville, ecrit plutot que tu.
        assertThat(legende).contains("16/h")
        assertThat(legende).contains("Aritake-Okada 2014")
    }

    @Test
    fun `l'equivalent actimetrique est documente et ne pilote aucune branche`() {
        assertThat(Aggregat.SEUIL_ACTIMETRIQUE_CHEVILLE_PAR_HEURE).isEqualTo(16.0)
        // Une seule cohorte : on documente, on ne substitue pas. Les cinq phrases de position
        // restent calees sur le 15/h de l'ICSD-3, et le test le verifie a la borne exacte.
        assertThat(Aggregat.position(15.5, 40.0, 6)).isEqualTo(Aggregat.Position.AuDessusSeuil)
        assertThat(Aggregat.position(2.0, 15.5, 6)).isEqualTo(Aggregat.Position.EnglobeSeuil)
    }

    // -------------------------------------------------------------------------------------
    // Le nombre de nuits requis depend du regime
    // -------------------------------------------------------------------------------------

    @Test
    fun `regime haut - trois nuits, et c'est sourcé`() {
        assertThat(Aggregat.nuitsRequises(ciBas = 18.0, ciHaut = 34.0)).isEqualTo(3)
        assertThat(Aggregat.motifNuitsRequises(18.0, 34.0))
            .isEqualTo(Textes.Tendance.NUITS_REQUISES_REGIME_HAUT)
    }

    @Test
    fun `regime bas - bien plus de trois nuits, parce que c'est la que la fiabilite s'effondre`() {
        // Le defaut corrige : `Position.SousSeuil` etait rendue precisement dans le regime ou
        // trois nuits donnent la pire correlation intraclasse. L'application etait plus assuree
        // quand elle rassurait que quand elle alertait.
        assertThat(Aggregat.nuitsRequises(ciBas = 4.0, ciHaut = 11.0)).isEqualTo(14)
        assertThat(Aggregat.nuitsRequises(4.0, 11.0))
            .isGreaterThan(Aggregat.nuitsRequises(18.0, 34.0))
    }

    @Test
    fun `regime bas - le texte dit que 14 est un compromis et que 26 est le chiffre mesure`() {
        val motif = Textes.Tendance.NUITS_REQUISES_REGIME_BAS
        assertThat(motif).contains("26 nights")
        assertThat(motif).contains("compromise")
        assertThat(motif).contains("nothing published supports that number")
    }

    @Test
    fun `a cheval sur le seuil - sept nuits, pour que l'intervalle se resserre`() {
        assertThat(Aggregat.nuitsRequises(ciBas = 9.0, ciHaut = 22.0)).isEqualTo(7)
        // Bornes exactes : a `ciBas == 15` ou `ciHaut == 15`, on est a cheval, jamais d'un cote.
        assertThat(Aggregat.nuitsRequises(15.0, 40.0)).isEqualTo(7)
        assertThat(Aggregat.nuitsRequises(2.0, 15.0)).isEqualTo(7)
    }

    @Test
    fun `le refus dur a trois nuits n'est pas touche`() {
        // La regle ajoute de la severite sur le libelle « provisoire », pas sur l'agregation.
        assertThat(Aggregat.MIN_NUITS_AGREGAT).isEqualTo(3)
        assertThat(Aggregat.position(4.0, 11.0, 2)).isEqualTo(Aggregat.Position.Refus)
    }

    // -------------------------------------------------------------------------------------
    // L'intervalle non calibre
    // -------------------------------------------------------------------------------------

    @Test
    fun `sous six nuits, le libelle ne promet pas 95 pourcent`() {
        val nonCalibre = Textes.Tendance.intervalleNonCalibre("18", "26", 3)
        // Le chiffre 95 apparait, mais **nie** : « not a calibrated 95% interval ». Ce qui ne
        // doit pas apparaitre est la forme canonique qui l'affirme.
        assertThat(nonCalibre).doesNotContain("95% CI")
        assertThat(nonCalibre).contains("not a calibrated")
        assertThat(Textes.Tendance.intervalleEtN("18", "26", 6)).contains("95% CI")
    }

    @Test
    fun `le motif de l'etiquetage cite la mesure`() {
        val note = Textes.Tendance.INTERVALLE_NON_CALIBRE
        assertThat(note).contains("75%")
        assertThat(note).contains("88%")
        assertThat(note).contains("10,000")
    }

    // -------------------------------------------------------------------------------------
    // « Pourquoi ce chiffre » : generatif, jamais attributif
    // -------------------------------------------------------------------------------------

    private fun nuit(maskSource: String = "HEALTH_CONNECT") = ComparableNight(
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
        periodicityIndex = 0.58,
        missRate = 0.21,
        analysableTstMin = 312.0,
        analysableMin = 460.0,
        truncated = false,
        revealedAtMs = null,
        comparable = true,
        exclusionReason = ComparabilityRule.OK,
    )

    private fun resultat() = PlmResultEntity(
        sessionHex = "abcd",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = "HEALTH_CONNECT",
        computedAtMs = 0L,
        algoVersion = "1.4.0",
        plmsCount = 96,
        plmwCount = 0,
        isolatedCount = 12,
        shortImiCount = 4,
        tstMin = 320.0,
        analysableTstMin = 312.0,
        sptMin = 461.0,
        wasoMin = 40.0,
        plmi = 18.4,
        plmiSpt = 12.5,
        plmw = 9.0,
        plmiFirstHalf = 15.0,
        plmiSecondHalf = 21.0,
        plmiRespWorstCase = 16.3,
        periodicityIndex = 0.58,
        periodicityValid = true,
        fundamentalSec = 21.0,
        muLog = 3.0,
        sigmaLog = 0.4,
        missRate = 0.21,
        alternationSuspect = false,
        rhythmConverged = true,
        rhythmValid = true,
        truncatedSeriesDropped = 0,
        respiratoryConfidence = "MEDIUM",
        independence = "INDEPENDENT",
        gate = "FULL",
        floorMode = "ADAPTIVE",
    )

    private fun bloc(maskSource: String = "HEALTH_CONNECT") = CheminDeCalcul.de(
        n = nuit(maskSource),
        resultat = resultat(),
        dureeEnregistreeMin = 461.0,
        mouvementsRetenus = 96,
        regle = Textes.Reglages.REGLE_AASM,
        sourceSommeil = "Samsung Health",
    )!!

    @Test
    fun `le bloc montre le chemin de calcul, dans l'ordre`() {
        val b = bloc()
        val p = Textes.Nuits.Detail.Pourquoi
        assertThat(b.titre).isEqualTo(p.titre("18.4 /h"))
        assertThat(b.lignes.map { it.libelle }).containsExactly(
            p.SOMMEIL_ANALYSABLE,
            p.MOUVEMENTS_RETENUS,
            p.REGLE,
            p.MASQUE,
            p.DENOMINATEUR,
            p.TAUX_MANQUES,
            p.ENCADREMENT_RESPI,
        )
        assertThat(b.lignes[0].valeur).isEqualTo(Mapping.dureeLisible(312.0))
        assertThat(b.lignes[0].note).contains(Mapping.dureeLisible(461.0))
        assertThat(b.lignes[1].valeur).isEqualTo("96")
        assertThat(b.lignes[5].valeur).isEqualTo("0.21")
        // L'encadrement respiratoire : la borne basse si tout le suspect sortait, donc negative.
        assertThat(b.lignes[6].valeur).startsWith("-2.1")
    }

    @Test
    fun `la ligne du denominateur dit s'il est independant du numerateur`() {
        val p = Textes.Nuits.Detail.Pourquoi
        assertThat(bloc().lignes[4].valeur).isEqualTo(p.DENOMINATEUR_INDEPENDANT)
        assertThat(bloc(Mapping.MASQUE_ACCELERO).lignes[4].valeur)
            .isEqualTo(p.DENOMINATEUR_CIRCULAIRE)
    }

    @Test
    fun `la phrase de non-causalite fait partie du type, pas de l'ecran`() {
        // Un chemin de calcul sans elle ne doit pas pouvoir exister : c'est un champ avec une
        // valeur par defaut, pas un texte que l'ecran choisit d'ajouter.
        assertThat(bloc().avertissement)
            .isEqualTo(Textes.Nuits.Detail.Pourquoi.AVERTISSEMENT)
        assertThat(bloc().avertissement).contains("does not indicate what caused")
    }

    @Test
    fun `aucune ligne ne classe des facteurs ni n'attribue une part`() {
        // Le garde-fou du chantier : pas de « contribution », pas de pourcentage d'explication,
        // pas de classement. Ces methodes ne distinguent pas correlation et causalite.
        val interdits = listOf(
            "contribut", "explains", "accounts for", "importance", "ranked", "impact of",
        )
        val texte = (bloc().lignes.flatMap { listOfNotNull(it.libelle, it.valeur, it.note) })
            .joinToString(" ")
            .lowercase()
        interdits.forEach { assertThat(texte).doesNotContain(it) }
    }

    @Test
    fun `sans resultat, il n'y a rien a expliquer`() {
        assertThat(
            CheminDeCalcul.de(nuit(), null, 461.0, 0, "AASM v3", "Samsung Health"),
        ).isNull()
    }
}
