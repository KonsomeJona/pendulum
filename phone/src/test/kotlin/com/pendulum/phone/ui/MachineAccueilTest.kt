package com.pendulum.phone.ui

import com.pendulum.phone.ui.home.AccueilUi
import com.pendulum.phone.ui.home.MachineAccueil
import com.pendulum.phone.ui.home.PhaseAccueil
import com.pendulum.phone.ui.home.SessionAccueil
import com.pendulum.phone.ui.home.SourceAccueil
import com.pendulum.phone.ui.model.Drapeau
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.text.Textes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * La machine a etats de l'accueil, testee sur ses bornes.
 *
 * ### Ce que ces tests protegent
 *
 * Une seule regle, et elle est facile a perdre a la premiere retouche : **l'etat persiste decide,
 * l'heure departage**. La v1 faisait l'inverse — la carte du soir apparaissait entre 20 h et 4 h,
 * point. Le defaut n'etait pas visible sur une maquette, parce qu'une maquette se regarde a
 * 15 h : il apparaissait chez un travailleur poste, chez un voyageur, et chez quiconque se
 * couchait a 4 h 30.
 *
 * L'horloge est donc un **parametre**, pas un appel enfoui. C'est la condition pour que ces
 * assertions existent : `MachineAccueil.phase(source, 3)` se lit, `System.currentTimeMillis()`
 * au fond d'une fonction ne se teste pas.
 */
class MachineAccueilTest {

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private fun nuit(devoileeAtMs: Long?) = NuitUi(
        sessionHex = "a7",
        dateLisible = "12 March",
        jourAbrege = "Fri",
        debut = "23:12",
        fin = "06:58",
        sommeilLisible = "6 h 58",
        sourceSommeil = "Health Connect",
        etat = EtatNuit.ELIGIBLE,
        motif = null,
        rythmeSec = 18.7,
        comptePlmi = 24.0,
        drapeaux = listOf(Drapeau("gap 47 s")),
        startWallMs = 0L,
        devoileeAtMs = devoileeAtMs,
    )

    private fun session(etat: String, analysee: Boolean) = SessionAccueil(
        sessionHex = "a7",
        etat = etat,
        analysee = analysee,
        debutLisible = "23:12",
        dateLisible = "12 March",
    )

    private fun source(
        session: SessionAccueil? = null,
        contexteScelle: Boolean = false,
        nuitsEnregistrees: Int = 0,
        nuitsEligibles: Int = 0,
        derniereNuit: NuitUi? = null,
    ) = SourceAccueil(
        sessionRecente = session,
        contexteScelle = contexteScelle,
        jambeScellee = if (contexteScelle) Textes.CeSoir.JAMBE_DROITE else null,
        repereDeSerrage = "4th hole",
        sourceSommeil = "Samsung Health",
        nuitsEnregistrees = nuitsEnregistrees,
        nuitsEligibles = nuitsEligibles,
        derniereNuitAnalysee = derniereNuit,
    )

    private fun ui(source: SourceAccueil, heure: Int): AccueilUi = MachineAccueil.de(source, heure)

    // -------------------------------------------------------------------------------------
    // L'etat persiste passe avant l'heure
    // -------------------------------------------------------------------------------------

    /**
     * Le test central. Une session ouverte a 15 h est une session ouverte : sieste, travail
     * poste, ou montre qu'on a oublie d'arreter. Dans les trois cas l'ecran doit dire qu'elle
     * enregistre, et l'ancienne fenetre 20 h – 4 h disait le contraire.
     */
    @Test
    fun `une session ouverte l'emporte sur l'heure, a toute heure`() {
        val ouverte = source(session(MachineAccueil.OUVERTE, analysee = false))
        for (heure in 0..23) {
            assertThat(MachineAccueil.phase(ouverte, heure))
                .describedAs("a %d h", heure)
                .isEqualTo(PhaseAccueil.ENREGISTREMENT)
        }
    }

    @Test
    fun `une session silencieuse est une fin de nuit, a toute heure`() {
        val silencieuse = source(session(MachineAccueil.SILENCIEUSE, analysee = false))
        for (heure in 0..23) {
            assertThat(MachineAccueil.phase(silencieuse, heure)).isEqualTo(PhaseAccueil.FIN_DE_NUIT)
        }
    }

    /**
     * Fermee proprement mais jamais scoree : la nuit n'est pas finie tant que rien ne l'a lue.
     * C'est le cas du telephone eteint au reveil, ou de la chaine interrompue.
     */
    @Test
    fun `une session close mais non analysee reste une fin de nuit`() {
        val close = source(session("CLOSED", analysee = false))
        assertThat(MachineAccueil.phase(close, 9)).isEqualTo(PhaseAccueil.FIN_DE_NUIT)
        assertThat(MachineAccueil.phase(close, 22)).isEqualTo(PhaseAccueil.FIN_DE_NUIT)
    }

    @Test
    fun `une session tronquee et non analysee reste une fin de nuit`() {
        assertThat(MachineAccueil.phase(source(session("TRUNCATED", analysee = false)), 11))
            .isEqualTo(PhaseAccueil.FIN_DE_NUIT)
    }

    // -------------------------------------------------------------------------------------
    // La regle de departage horaire — et son perimetre exact
    // -------------------------------------------------------------------------------------

    /**
     * L'heure n'entre en jeu que la, et les bornes sont celles de `CeSoirUi.visibleA` : 20 h
     * incluse, 4 h exclue.
     */
    @Test
    fun `sans rien en base, l'heure departe preparation et journee`() {
        val rien = source()
        assertThat(MachineAccueil.phase(rien, 19)).isEqualTo(PhaseAccueil.JOURNEE)
        assertThat(MachineAccueil.phase(rien, 20)).isEqualTo(PhaseAccueil.PREPARATION)
        assertThat(MachineAccueil.phase(rien, 23)).isEqualTo(PhaseAccueil.PREPARATION)
        assertThat(MachineAccueil.phase(rien, 0)).isEqualTo(PhaseAccueil.PREPARATION)
        assertThat(MachineAccueil.phase(rien, 3)).isEqualTo(PhaseAccueil.PREPARATION)
        assertThat(MachineAccueil.phase(rien, 4)).isEqualTo(PhaseAccueil.JOURNEE)
        assertThat(MachineAccueil.phase(rien, 12)).isEqualTo(PhaseAccueil.JOURNEE)
    }

    /** Une nuit close **et** analysee ne dit plus rien : l'heure reprend la main. */
    @Test
    fun `une nuit close et analysee rend la main a l'heure`() {
        val finie = source(session("CLOSED", analysee = true), derniereNuit = nuit(devoileeAtMs = 1L))
        assertThat(MachineAccueil.phase(finie, 21)).isEqualTo(PhaseAccueil.PREPARATION)
        assertThat(MachineAccueil.phase(finie, 10)).isEqualTo(PhaseAccueil.JOURNEE)
    }

    // -------------------------------------------------------------------------------------
    // Les trois cartes : jamais retirees, desactivees avec leur motif
    // -------------------------------------------------------------------------------------

    @Test
    fun `sceller est impossible pendant un enregistrement, et le motif le dit`() {
        val ui = ui(source(session(MachineAccueil.OUVERTE, analysee = false)), heure = 23)
        assertThat(ui.motifPreparer).isEqualTo(Textes.EcranAccueil.Preparer.OCCUPE)
    }

    /** Le contexte est append-only : le sceller deux fois leve. Le bouton porte donc son motif. */
    @Test
    fun `sceller est impossible une fois le contexte scelle, et le motif est la bonne nouvelle`() {
        val ui = ui(source(contexteScelle = true), heure = 22)
        assertThat(ui.motifPreparer).isEqualTo(Textes.CeSoir.SCELLEMENT_FAIT)
    }

    @Test
    fun `sceller est possible tant que rien n'est scelle ni en cours`() {
        assertThat(ui(source(), heure = 21).motifPreparer).isNull()
    }

    @Test
    fun `sans session, la carte de fin de nuit est grisee avec son motif`() {
        val ui = ui(source(), heure = 21)
        assertThat(ui.motifFinDeNuit).isEqualTo(Textes.EcranAccueil.Fin.AUCUNE_SESSION)
        assertThat(ui.sessionAFermer).isNull()
        assertThat(ui.nuitADevoiler).isNull()
    }

    @Test
    fun `avec une session a fermer, le bouton est actif et sait laquelle`() {
        val ui = ui(source(session(MachineAccueil.SILENCIEUSE, analysee = false)), heure = 8)
        assertThat(ui.motifFinDeNuit).isNull()
        assertThat(ui.sessionAFermer).isEqualTo("a7")
    }

    /**
     * Une carte, une action. Tant qu'il reste quelque chose a ramener de la montre, c'est cela
     * que le bouton fait — proposer un devoilement pendant qu'une nuit se transfere ferait lire
     * un chiffre qui va changer.
     */
    @Test
    fun `le devoilement n'est propose que lorsqu'il n'y a plus rien a fermer`() {
        val enCours = ui(
            source(session(MachineAccueil.OUVERTE, analysee = false), derniereNuit = nuit(null)),
            heure = 2,
        )
        assertThat(enCours.nuitADevoiler).isNull()

        val finie = ui(
            source(session("CLOSED", analysee = true), derniereNuit = nuit(null)),
            heure = 8,
        )
        assertThat(finie.nuitADevoiler).isEqualTo("a7")
        assertThat(finie.ligneFinDeNuit).isEqualTo(Textes.EcranAccueil.Resultat.enregistree("12 March"))
    }

    /** Garde-fou 2 : une fois devoilee, la nuit ne redemande plus rien. */
    @Test
    fun `une nuit deja devoilee ne propose plus de l'etre`() {
        val ui = ui(
            source(session("CLOSED", analysee = true), derniereNuit = nuit(devoileeAtMs = 1L)),
            heure = 8,
        )
        assertThat(ui.nuitADevoiler).isNull()
        assertThat(ui.ligneFinDeNuit).isEqualTo(Textes.EcranAccueil.Fin.analysee("12 March"))
    }

    @Test
    fun `l'historique compte les nuits et les eligibles, et se grise a zero`() {
        val vide = ui(source(), heure = 12)
        assertThat(vide.ligneHistorique).isEqualTo(Textes.EcranAccueil.Historique.VIDE)
        assertThat(vide.motifHistorique).isEqualTo(Textes.EcranAccueil.Historique.VIDE)

        val garni = ui(source(nuitsEnregistrees = 7, nuitsEligibles = 5), heure = 12)
        assertThat(garni.ligneHistorique).isEqualTo("7 nights · 5 eligible")
        assertThat(garni.motifHistorique).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Les tirets : ce qui n'est pas branche doit se voir
    // -------------------------------------------------------------------------------------

    /**
     * La batterie et l'espace libre de la montre ne remontent pas encore. La machine ne doit
     * donc rien inventer : un tiret dit « pas encore branche », un « 98 % » ecrit en dur dirait
     * « branche » et serait cru.
     */
    @Test
    fun `la batterie et l'espace de la montre restent nuls tant que rien ne les remonte`() {
        val ui = ui(source(contexteScelle = true), heure = 22)
        assertThat(ui.ceSoir.batteriePct).isNull()
        assertThat(ui.ceSoir.espaceLibre).isNull()
        assertThat(ui.ceSoir.batterieInsuffisante).isFalse()
        assertThat(ui.ceSoir.sourceActive).isNull()
    }

    /** La jambe n'est connue qu'une fois le contexte scelle. Elle n'est jamais devinee. */
    @Test
    fun `la jambe reste inconnue tant que le contexte n'est pas scelle`() {
        assertThat(ui(source(contexteScelle = false), heure = 21).ceSoir.jambe).isNull()
        assertThat(ui(source(contexteScelle = true), heure = 21).ceSoir.jambe)
            .isEqualTo(Textes.CeSoir.JAMBE_DROITE)
    }
}
