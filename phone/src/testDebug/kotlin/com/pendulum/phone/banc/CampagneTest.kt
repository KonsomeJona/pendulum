package com.pendulum.phone.banc

import com.pendulum.algo.model.PublicationGate
import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.ui.model.Aggregat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Ce que la campagne du banc doit valoir, verifie sur JVM plutot que constate sur un telephone.
 *
 * ### Le defaut que ce test existe pour ne plus revoir
 *
 * La premiere version de l'ensemencement ecrivait neuf nuits parfaitement comparables et
 * publiables, et l'ecran Tendance restait ferme. Motif : `TendanceUiState` exige au moins trois
 * nuits **dont le rythme est identifie**, et `Rhythm` refusait l'ajustement sur les neuf. Rien ne
 * l'annoncait — ni une exception, ni un journal ; il fallait installer, ensemencer, ouvrir
 * l'ecran et lire la phrase de refus.
 *
 * Ce fichier est dans `src/testDebug/` et non `src/test/`, parce que ce qu'il teste n'existe que
 * dans la variante debug. `src/test/` est partage par les deux variantes : y placer ce test ferait
 * echouer la compilation de `testReleaseUnitTest` sur une classe absente — ce qui est la bonne
 * nouvelle sous une mauvaise forme.
 */
class CampagneTest {

    private val campagne: List<Campagne.Nuit> by lazy {
        val genres = Campagne.genres(Ensemencement.NUITS_PAR_DEFAUT)
        genres.mapIndexed { rang, genre -> Campagne.nuit(genre, rang) }
    }

    @Test
    fun `la campagne par defaut compte sept nuits eligibles, une provisoire et une ecartee`() {
        val genres = Campagne.genres(Ensemencement.NUITS_PAR_DEFAUT)
        assertThat(genres).hasSize(Ensemencement.NUITS_PAR_DEFAUT + 2)
        assertThat(genres.count { it == Campagne.Genre.ELIGIBLE })
            .isEqualTo(Ensemencement.NUITS_PAR_DEFAUT)
        assertThat(genres.count { it == Campagne.Genre.PROVISOIRE }).isEqualTo(1)
        assertThat(genres.count { it == Campagne.Genre.ECARTEE }).isEqualTo(1)

        // La premiere nuit sert de reference a tout le critere de comparabilite, la derniere est
        // celle dont parle la bande d'etat du reveil. Ni l'une ni l'autre ne peut etre atypique.
        assertThat(genres.first()).isEqualTo(Campagne.Genre.ELIGIBLE)
        assertThat(genres.last()).isEqualTo(Campagne.Genre.ELIGIBLE)
    }

    @Test
    fun `assez de nuits portent un rythme identifie pour ouvrir l'ecran Tendance`() {
        val ajustees = campagne
            .filter { it.genre == Campagne.Genre.ELIGIBLE }
            .count { it.principal.rhythm.valid && it.principal.rhythm.fundamentalSec > 0.0 }

        // C'est **la** porte que la premiere version manquait : sous ce compte, `TrendScreen`
        // reste dans sa branche de refus et les cinq ecrans qu'on cherche a atteindre — liste,
        // detail, questionnaire, comparaison, export — n'ont toujours aucun lien qui y mene.
        assertThat(ajustees)
            .describedAs("nuits eligibles dont l'ajustement du rythme est accepte")
            .isGreaterThanOrEqualTo(Aggregat.MIN_NUITS_AGREGAT)
    }

    @Test
    fun `les nuits eligibles sont publiables et les autres portent bien leur etat`() {
        for (nuit in campagne) {
            val porte = nuit.principal.gate
            when (nuit.genre) {
                // Publiable et dans la tendance : c'est ce que veut dire « eligible ».
                Campagne.Genre.ELIGIBLE, Campagne.Genre.ECARTEE ->
                    assertThat(porte)
                        .describedAs("porte de publication d'une nuit ${nuit.genre}")
                        .isEqualTo(PublicationGate.FULL)

                // Mesuree correctement, mais hors tendance : l'index d'une nuit tronquee est
                // biaise a la hausse sans correction possible. C'est l'etat « provisoire ».
                Campagne.Genre.PROVISOIRE ->
                    assertThat(porte).isEqualTo(PublicationGate.TRUNCATED_NO_TREND)
            }
        }
    }

    @Test
    fun `toutes les nuits passent le critere de duree analysable`() {
        for (nuit in campagne) {
            assertThat(nuit.analysableMin)
                .describedAs("minutes analysables d'une nuit ${nuit.genre}")
                .isGreaterThanOrEqualTo(ComparabilityRule.MIN_ANALYSABLE_MIN)
        }
    }

    @Test
    fun `l'etalon de gain reste dans la tolerance de la nuit de reference`() {
        val reference = campagne.first().synth.truth.gainCalG.toDouble()
        for (nuit in campagne) {
            val ecart = abs(nuit.synth.truth.gainCalG - reference) / reference
            assertThat(ecart)
                .describedAs("ecart de gain a la nuit de reference")
                .isLessThan(ComparabilityRule.GAIN_TOLERANCE)
        }
    }

    @Test
    fun `les nuits ne se ressemblent pas`() {
        val eligibles = campagne.filter { it.genre == Campagne.Genre.ELIGIBLE }
        val comptes = eligibles.map { it.principal.plmi }
        val rythmes = eligibles.map { it.principal.rhythm.fundamentalSec }

        // Un graphe de tendance ou tous les points sont a la meme hauteur ne permet de juger ni
        // l'echelle de l'axe, ni la bande de dispersion, ni le trace de la mediane. Des nuits
        // identiques cacheraient exactement les defauts d'affichage qu'on cherche a voir.
        assertThat(comptes.distinct()).hasSameSizeAs(comptes)
        assertThat(rythmes.max() - rythmes.min())
            .describedAs("etendue du rythme fondamental sur les nuits eligibles, en secondes")
            .isGreaterThan(0.5)
    }
}
