package com.pendulum.phone.ui

import com.pendulum.phone.ui.chart.EnvelopePyramid
import com.pendulum.phone.ui.chart.EtatPoint
import com.pendulum.phone.ui.chart.PointNuit
import com.pendulum.phone.ui.chart.TendanceChartSpec
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.EtatReveil
import com.pendulum.phone.ui.model.MotifRefus
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.texte
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Les regles d'affichage qui sont du code, verifiees comme du code.
 *
 * Chacun de ces tests correspond a une phrase de `06-interface.md` qu'une revue d'ecran ne peut
 * pas garantir a l'oeil : un axe tronque de 5 % ne se voit pas, un intervalle qui bouge d'une
 * recomposition a l'autre ne se voit pas non plus si l'on ne regarde pas deux fois.
 */
class ReglesAffichageTest {

    // -------------------------------------------------------------------------------------
    // P5 — aucune moyenne ne masque un extremum
    // -------------------------------------------------------------------------------------

    @Test
    fun `un pic d'un seul echantillon survit a une decimation de 4096`() {
        val n = 1_440_000
        val base = FloatArray(n) { 1f }
        base[723_456] = 61f // le pic isole : 20 ms sur huit heures

        val pyramide = EnvelopePyramid(base)
        val colonnes = 1100
        val sortie = FloatArray(colonnes * 2)
        pyramide.remplirMinMax(0, n, colonnes, sortie)

        // Le facteur de decimation effectif depasse 1300 ; en moyenne, le pic disparaitrait
        // completement. En min/max il ne peut que remonter.
        val maxAffiche = (0 until colonnes).maxOf { sortie[it * 2 + 1] }
        assertThat(maxAffiche).isEqualTo(61f)
    }

    @Test
    fun `la decimation ne perd pas non plus le minimum`() {
        val base = FloatArray(100_000) { 5f }
        base[42_000] = 0.2f
        val sortie = FloatArray(200)
        EnvelopePyramid(base).remplirMinMax(0, base.size, 100, sortie)
        val minAffiche = (0 until 100).minOf { sortie[it * 2] }
        assertThat(minAffiche).isEqualTo(0.2f)
    }

    // -------------------------------------------------------------------------------------
    // P5 — l'axe de la tendance part de zero, quelles que soient les donnees
    // -------------------------------------------------------------------------------------

    private fun spec(valeurs: List<Float>) = TendanceChartSpec(
        grandeur = Aggregat.Grandeur.RYTHME_SECONDES,
        points = valeurs.mapIndexed { i, v -> PointNuit("s$i", i * 86_400_000L, v, EtatPoint.ELIGIBLE) },
        bandes = emptyList(),
        reference = null,
        premierJourMs = 0L,
        dernierJourMs = valeurs.size * 86_400_000L,
        zoneId = "UTC",
        pivotMs = null,
        descriptionAccessible = "",
    )

    @Test
    fun `yMin vaut toujours zero`() {
        assertThat(spec(listOf(20f, 21f, 22f)).yMin).isEqualTo(0f)
        // Le cas piege : des valeurs serrees et hautes. Un axe « intelligent » demarrerait a 200
        // et transformerait un ecart de 2 s en falaise.
        assertThat(spec(listOf(210f, 211f, 212f)).yMin).isEqualTo(0f)
    }

    @Test
    fun `yMax vaut au moins 20 et arrondit au multiple de 5 superieur`() {
        assertThat(spec(listOf(3f, 4f)).yMax).isEqualTo(20f)
        // 1,15 × 61 = 70,15 → 75
        assertThat(spec(listOf(61f)).yMax).isEqualTo(75f)
    }

    // -------------------------------------------------------------------------------------
    // P1 — rien n'est agrege sous trois nuits, aucune categorie sous cinq
    // -------------------------------------------------------------------------------------

    @Test
    fun `sous trois nuits la position est un refus`() {
        assertThat(Aggregat.position(10.0, 40.0, 0)).isEqualTo(Aggregat.Position.Refus)
        assertThat(Aggregat.position(10.0, 40.0, 2)).isEqualTo(Aggregat.Position.Refus)
    }

    @Test
    fun `entre trois et quatre nuits aucune categorie n'est proposee`() {
        // Meme quand l'intervalle est entierement d'un cote du seuil, on ne categorise pas.
        assertThat(Aggregat.position(2.0, 9.0, 3)).isEqualTo(Aggregat.Position.Provisoire(3))
        assertThat(Aggregat.position(30.0, 50.0, 4)).isEqualTo(Aggregat.Position.Provisoire(4))
    }

    @Test
    fun `les cinq issues de la phrase de position, aux bornes exactes`() {
        assertThat(Aggregat.position(2.0, 14.9, 6)).isEqualTo(Aggregat.Position.SousSeuil)
        assertThat(Aggregat.position(15.1, 40.0, 6)).isEqualTo(Aggregat.Position.AuDessusSeuil)
        // La borne stricte : ciBas == 15 n'est PAS « au-dessus », l'intervalle englobe le seuil.
        assertThat(Aggregat.position(15.0, 40.0, 6)).isEqualTo(Aggregat.Position.EnglobeSeuil)
        assertThat(Aggregat.position(2.0, 15.0, 6)).isEqualTo(Aggregat.Position.EnglobeSeuil)
    }

    // -------------------------------------------------------------------------------------
    // L'intervalle ne bouge pas d'une recomposition a l'autre
    // -------------------------------------------------------------------------------------

    @Test
    fun `la graine fixe donne le meme intervalle a chaque appel`() {
        val valeurs = doubleArrayOf(19.8, 23.4, 21.2, 20.6, 22.9, 18.7)
        val graine = Aggregat.graineDe(listOf("a1", "a2", "a3", "a4", "a5", "a6"))
        val premier = Aggregat.bootstrapCi(valeurs, graine)
        repeat(20) {
            assertThat(Aggregat.bootstrapCi(valeurs, graine)).isEqualTo(premier)
        }
    }

    @Test
    fun `la graine ne depend pas de l'ordre des sessions`() {
        assertThat(Aggregat.graineDe(listOf("b", "a", "c")))
            .isEqualTo(Aggregat.graineDe(listOf("c", "b", "a")))
    }

    // -------------------------------------------------------------------------------------
    // P3 — sous la MDC95, l'ecart est nomme non concluant avant d'etre chiffre
    // -------------------------------------------------------------------------------------

    private fun resultat(mediane: Double, bas: Double, haut: Double, n: Int, disp: Double) =
        Aggregat.Resultat(
            Aggregat.Grandeur.COMPTE_HORAIRE, mediane, bas, haut, n, disp, Aggregat.mdc95(disp, n),
        )

    @Test
    fun `un intervalle de difference contenant zero n'est jamais distinguable`() {
        val a = resultat(31.0, 19.0, 44.0, 6, 12.0)
        val b = resultat(22.0, 14.0, 31.0, 6, 12.0)
        val c = Aggregat.comparer(a, b, -24.0, 5.0, 11)
        assertThat(c.distinguable).isFalse()
        assertThat(c.verdict).isEqualTo(texte(R.string.compare_inconclusive))
    }

    @Test
    fun `un ecart sous la MDC95 reste non concluant meme si l'intervalle exclut zero`() {
        // Dispersion large, peu de nuits : la MDC95 vaut ~13,6/h. Un ecart de 3/h dont
        // l'intervalle exclut zero de justesse ne suffit pas — la methode ne sait pas trancher.
        val a = resultat(25.0, 23.0, 27.0, 6, 12.0)
        val b = resultat(22.0, 20.0, 24.0, 6, 12.0)
        val c = Aggregat.comparer(a, b, -5.0, -1.0, 11)
        assertThat(c.mdc95).isCloseTo(13.58, within(0.1))
        assertThat(c.distinguable).isFalse()
        assertThat(Ressources.resoudre(c.motifNonConcluant!!)).contains("smallest change")
    }

    @Test
    fun `au-dela de trente nuits necessaires, le nombre n'est pas affiche`() {
        val a = resultat(25.0, 23.0, 27.0, 6, 12.0)
        val b = resultat(22.0, 20.0, 24.0, 6, 12.0)
        assertThat(Aggregat.comparer(a, b, -5.0, -1.0, 84).nuitsNecessaires).isNull()
        assertThat(Aggregat.comparer(a, b, -5.0, -1.0, 11).nuitsNecessaires).isEqualTo(11)
    }

    // -------------------------------------------------------------------------------------
    // La periodicite ne s'affiche jamais nue
    // -------------------------------------------------------------------------------------

    @Test
    fun `la periodicite n'est qualifiee qu'a partir de cinq nuits`() {
        assertThat(Aggregat.qualifierPeriodicite(0.71, 4)).isNull()
        assertThat(Aggregat.qualifierPeriodicite(0.71, 6)).isEqualTo(texte(R.string.trend_periodicity_high))
        assertThat(Aggregat.qualifierPeriodicite(0.31, 6)).isEqualTo(texte(R.string.trend_periodicity_low))
    }

    @Test
    fun `aucun libelle de periodicite n'expose l'indice brut`() {
        listOf(0.0, 0.31, 0.5, 0.58, 0.71, 1.0).forEach { indice ->
            val libelle = Aggregat.qualifierPeriodicite(indice, 6)
            assertThat(libelle).isNotNull()
            val rendu = Ressources.resoudre(libelle!!)
            assertThat(rendu).doesNotContain(indice.toString())
            assertThat(rendu).doesNotContain(",")
        }
    }

    // -------------------------------------------------------------------------------------
    // Estimateurs
    // -------------------------------------------------------------------------------------

    @Test
    fun `la mediane et la dispersion resistent a une nuit aberrante`() {
        val sans = doubleArrayOf(20.0, 21.0, 22.0, 23.0, 24.0)
        val avec = doubleArrayOf(20.0, 21.0, 22.0, 23.0, 240.0)
        assertThat(Aggregat.mediane(sans)).isEqualTo(22.0)
        assertThat(Aggregat.mediane(avec)).isEqualTo(22.0)
        // Un ecart-type classique exploserait ; la MAD × 1,4826 bouge a peine.
        assertThat(Aggregat.dispersion(avec)).isCloseTo(Aggregat.dispersion(sans), within(0.5))
    }

    // -------------------------------------------------------------------------------------
    // Le refus : deux motifs sans rapport, et l'ecran doit dire lequel
    // -------------------------------------------------------------------------------------

    private fun refus(eligibles: Int, ajustes: Int) = TendanceUiState.Refus(
        nuitsEligibles = eligibles,
        nuitsRythmeAjuste = ajustes,
        nuitsRequises = Aggregat.MIN_NUITS_AGREGAT,
        nuitsEnregistrees = emptyList(),
        reveil = EtatReveil.Rien,
    )

    /**
     * Le defaut que ce test empeche : neuf nuits eligibles, deux rythmes identifies, et l'ecran
     * qui annonce « 2 nuits sur 3 ». La phrase est fausse et elle envoie chercher un defaut de
     * mesure la ou le modele a simplement refuse de publier une periode qu'il n'identifie pas.
     */
    @Test
    fun `le refus distingue les nuits qui manquent des rythmes qui manquent`() {
        assertThat(refus(eligibles = 2, ajustes = 2).motif).isEqualTo(MotifRefus.NUITS_INSUFFISANTES)
        assertThat(refus(eligibles = 2, ajustes = 2).nuitsAcquises).isEqualTo(2)

        val rythme = refus(eligibles = 9, ajustes = 2)
        assertThat(rythme.motif).isEqualTo(MotifRefus.RYTHME_NON_AJUSTE)
        // Le compteur affiche le compte qui manque, pas celui qui est deja atteint.
        assertThat(rythme.nuitsAcquises).isEqualTo(2)

        // La borne exacte : trois nuits eligibles suffisent a basculer le motif, parce que le
        // compte des nuits n'est alors plus ce qui manque.
        assertThat(refus(eligibles = 3, ajustes = 0).motif).isEqualTo(MotifRefus.RYTHME_NON_AJUSTE)
    }

    /** Le texte du second motif ne doit pas se lire comme une panne, et il cite sa mesure. */
    @Test
    fun `le refus de rythme se presente comme une propriete du produit`() {
        val corps = Ressources.lire(R.string.trend_rhythm_refusal_body)
        assertThat(corps).contains("2 fits out of 20")
        assertThat(corps).contains("refuses")
        // Ni panne, ni erreur, ni echec : ce qui s'est produit est un refus documente.
        listOf("error", "failed", "failure", "broken").forEach {
            assertThat(corps.lowercase()).doesNotContain(it)
        }
    }
}
