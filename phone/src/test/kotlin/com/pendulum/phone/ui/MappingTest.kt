package com.pendulum.phone.ui

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.Mapping
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La traduction base → ecran.
 *
 * Elle merite des tests pour une raison precise : c'est elle qui a remplace le jeu de donnees
 * d'apercu sur lequel toute la navigation etait cablee. Le defaut qu'on veut rendre impossible
 * n'est pas un plantage, c'est **un chiffre qui s'affiche alors qu'il ne devrait pas exister** —
 * et ce genre de defaut passe une revue visuelle sans se faire remarquer.
 */
class MappingTest {

    // -------------------------------------------------------------------------------------
    // La regle qui compte : aucun agregat sous trois nuits
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("aucun agregat n'existe sous trois nuits eligibles")
    fun `pas d agregat sous trois nuits`() {
        for (n in 0..2) {
            val resultat = Mapping.agregat(
                Aggregat.Grandeur.RYTHME_SECONDES,
                List(n) { nuit(hex = "n$it", fundamentalSec = 21.0) },
            ) { it.fundamentalSec }

            assertThat(resultat)
                .withFailMessage(
                    "Sur %d nuit(s), `agregat` a renvoye une valeur. C'est le garde-fou central du " +
                        "produit : sous %d nuits eligibles il ne doit exister aucun chemin de code " +
                        "produisant une mediane, parce que l'ecran choisit sa branche sur cette " +
                        "nullite et non sur un booleen qu'on peut oublier de tester.",
                    n, Aggregat.MIN_NUITS_AGREGAT,
                )
                .isNull()
        }
    }

    @Test
    @DisplayName("a exactement trois nuits, l'agregat existe et porte son intervalle et son n")
    fun `agregat a trois nuits`() {
        val nuits = listOf(
            nuit("a", fundamentalSec = 19.0),
            nuit("b", fundamentalSec = 21.0),
            nuit("c", fundamentalSec = 24.0),
        )

        val r = Mapping.agregat(Aggregat.Grandeur.RYTHME_SECONDES, nuits) { it.fundamentalSec }

        assertThat(r).isNotNull
        assertThat(r!!.mediane).isEqualTo(21.0)
        assertThat(r.nuits).isEqualTo(3)
        assertThat(r.ciBas).isLessThanOrEqualTo(r.mediane)
        assertThat(r.ciHaut).isGreaterThanOrEqualTo(r.mediane)
    }

    @Test
    @DisplayName("meme ensemble de nuits, meme intervalle — la graine ne depend pas de l'ordre")
    fun `l intervalle est stable`() {
        val nuits = listOf(nuit("a", 19.0), nuit("b", 21.0), nuit("c", 24.0), nuit("d", 27.0))

        val direct = Mapping.agregat(Aggregat.Grandeur.RYTHME_SECONDES, nuits) { it.fundamentalSec }
        val inverse = Mapping.agregat(
            Aggregat.Grandeur.RYTHME_SECONDES,
            nuits.reversed(),
        ) { it.fundamentalSec }

        // Un intervalle qui bouge entre deux affichages du meme jeu de nuits detruit la confiance
        // dans tout l'ecran : l'utilisateur qui rouvre l'application dix minutes plus tard doit
        // relire exactement le meme nombre.
        assertThat(inverse!!.ciBas).isEqualTo(direct!!.ciBas)
        assertThat(inverse.ciHaut).isEqualTo(direct.ciHaut)
    }

    // -------------------------------------------------------------------------------------
    // Les trois etats d'une nuit
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("une nuit comparable et publiable est eligible, sans motif")
    fun `nuit eligible`() {
        val ui = Mapping.nuitUi(nuit("a"), finWallMs = null, sourceSommeil = "Oura")

        assertThat(ui.etat).isEqualTo(EtatNuit.ELIGIBLE)
        assertThat(ui.motif).isNull()
    }

    @Test
    @DisplayName("une nuit comparable dont le gate n'est pas complet est provisoire, pas ecartee")
    fun `nuit provisoire`() {
        val ui = Mapping.nuitUi(
            nuit("a", gate = "NO_PLMI"),
            finWallMs = null,
            sourceSommeil = "Oura",
        )

        // La difference n'est pas cosmetique. Une nuit provisoire a ete mesuree correctement : ce
        // qui manque est son denominateur, parce que l'hypnogramme n'est pas encore arrive. Elle
        // sera recalculee toute seule. La ranger avec les nuits ecartees ferait lire comme une
        // panne le cas le plus frequent au reveil.
        assertThat(ui.etat).isEqualTo(EtatNuit.PROVISOIRE)
        assertThat(ui.motif).isNull()
    }

    @Test
    @DisplayName("une nuit ecartee garde sa valeur et porte son motif traduit")
    fun `nuit ecartee`() {
        val ui = Mapping.nuitUi(
            nuit("a", comparable = false, exclusionReason = ComparabilityRule.TOO_SHORT, plmi = 22.0),
            finWallMs = null,
            sourceSommeil = "Oura",
        )

        assertThat(ui.etat).isEqualTo(EtatNuit.ECARTEE)
        assertThat(ui.motif).isNotNull()
        // La valeur reste visible : une nuit invisible est une nuit qu'on oublie d'expliquer.
        assertThat(ui.comptePlmi).isEqualTo(22.0)
    }

    // -------------------------------------------------------------------------------------
    // Le rythme : absent est le cas normal
    // -------------------------------------------------------------------------------------

    /**
     * Le defaut que ce test fixe.
     *
     * `:algo` refuse la plupart des ajustements de rythme — 2 acceptes sur 20 nuits nominales — et
     * deux de ses six motifs de refus (`MISS_RATE_SATURATED`, `SIGMA_SATURATED`) laissent un
     * `fundamentalSec` **fini** dans la colonne. L'interface lisait cette colonne sans consulter
     * `rhythmValid` : elle affichait donc, avec la meme mise en forme qu'un rythme mesure, une
     * periode que le modele avait refuse de publier.
     */
    @Test
    @DisplayName("un ajustement refuse n'a pas de rythme, meme quand la colonne porte un nombre fini")
    fun `rythme refuse`() {
        assertThat(Mapping.rythmeSec(nuit("a", fundamentalSec = 42.0, rhythmValid = false))).isNull()
        assertThat(Mapping.rythmeSec(nuit("a", fundamentalSec = 21.0, rhythmValid = true))).isEqualTo(21.0)
    }

    /**
     * `NaN` est ce que `:algo` ecrit quand il n'a meme pas pu tenter l'ajustement — « pas de
     * valeur » doit empoisonner visiblement tout calcul aval. `Math.round(NaN)` vaut 0, donc
     * l'ecran affichait « 0 s » : un rythme nul, qui est la seule valeur physiquement impossible.
     */
    @Test
    @DisplayName("un rythme absent ne s'arrondit jamais en 0 s")
    fun `rythme absent`() {
        assertThat(Mapping.rythmeSec(nuit("a", fundamentalSec = Double.NaN))).isNull()
        assertThat(Mapping.rythmeLisible(null)).doesNotContain("0 s")
        assertThat(Mapping.rythmeLisible(null))
            .isEqualTo(com.pendulum.phone.ui.text.Textes.Nuits.Detail.RYTHME_NON_AJUSTE)
        assertThat(Mapping.rythmeLisible(21.4)).isEqualTo("21 s")
    }

    // -------------------------------------------------------------------------------------
    // Mise en forme
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("une duree se lit en heures et minutes, jamais en decimal d'heure")
    fun `duree lisible`() {
        assertThat(Mapping.dureeLisible(312.0)).isEqualTo("5 h 12")
        assertThat(Mapping.dureeLisible(60.0)).isEqualTo("1 h 00")
        assertThat(Mapping.dureeLisible(0.0)).isEqualTo("—")
    }

    @Test
    @DisplayName("la source dit explicitement quand le denominateur vient du meme capteur")
    fun `libelle de source`() {
        // C'est la seule information qui permette de savoir si le chiffre repose sur un
        // denominateur independant du numerateur : elle ne doit jamais etre remplacee par un
        // nom d'application qui laisserait croire a une source tierce.
        assertThat(Mapping.libelleSource(Mapping.MASQUE_ACCELERO, "com.oura.app"))
            .isEqualTo(com.pendulum.phone.ui.text.Textes.Reglages.MASQUE_ACCELERO_SEUL)
        assertThat(Mapping.libelleSource("HEALTH_CONNECT", "com.oura.app")).isEqualTo("App")
        assertThat(Mapping.libelleSource("HEALTH_CONNECT", null))
            .isEqualTo(com.pendulum.phone.ui.text.Textes.Reglages.SOURCE_INCONNUE)
    }

    @Test
    @DisplayName("le masque accelerometrique est le premier drapeau : il change la nature du chiffre")
    fun `ordre des drapeaux`() {
        val drapeaux = Mapping.drapeaux(
            nuit("a", maskSource = Mapping.MASQUE_ACCELERO, truncated = true),
            gapCount = 2,
            gapTotalMs = 47_000,
            batteryPctLast = 8,
        )

        assertThat(drapeaux).hasSizeGreaterThanOrEqualTo(4)
        assertThat(drapeaux.first().libelle)
            .isEqualTo(com.pendulum.phone.ui.text.Textes.Nuits.Drapeaux.MASQUE_ACCELERO)
    }

    @Test
    @DisplayName("une nuit sans anomalie ne porte aucun drapeau")
    fun `pas de drapeau sans motif`() {
        val drapeaux = Mapping.drapeaux(nuit("a"), gapCount = 0, gapTotalMs = 0, batteryPctLast = 62)
        assertThat(drapeaux).isEmpty()
    }

    // -------------------------------------------------------------------------------------

    private fun nuit(
        hex: String,
        fundamentalSec: Double = 21.0,
        rhythmValid: Boolean = true,
        plmi: Double = 18.0,
        gate: String = Mapping.GATE_COMPLET,
        comparable: Boolean = true,
        exclusionReason: String = ComparabilityRule.OK,
        maskSource: String = "HEALTH_CONNECT",
        truncated: Boolean = false,
        missRate: Double = 0.05,
    ) = ComparableNight(
        sessionHex = hex,
        startWallMs = 1_741_737_600_000L,
        zoneId = "Europe/Paris",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = maskSource,
        gate = gate,
        independence = "INDEPENDENT_HC",
        plmi = plmi,
        plmiSpt = plmi,
        fundamentalSec = fundamentalSec,
        rhythmValid = rhythmValid,
        periodicityIndex = 0.58,
        missRate = missRate,
        analysableTstMin = 312.0,
        analysableMin = 420.0,
        truncated = truncated,
        revealedAtMs = null,
        comparable = comparable,
        exclusionReason = exclusionReason,
    )
}
