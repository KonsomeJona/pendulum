package com.pendulum.algo.indices

import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.SeriesRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **La sortie de `SeriesBuilder` entre telle quelle dans `Plmi.compute`.**
 *
 * ### Le defaut que ces tests verrouillent
 *
 * `SeriesBuilder` recoit **tous** les evenements, rejets compris — il en a besoin, un `LM_LONG`
 * doit casser la serie a l'endroit ou il tombe — et indexe donc ses series sur la liste telle
 * qu'on la lui a donnee. Les comptes de `Plmi.compute`, eux, portent sur les seuls retenus. Les
 * deux bases coincident tant qu'aucun evenement n'est rejete, et divergent des le premier.
 *
 * La divergence a vecu longtemps parce qu'elle ne se voyait ni a la compilation — les deux bases
 * sont des `Int` — ni dans la suite de non-regression : le harnais convertissait a la main, la
 * production non. Les tests validaient donc un cablage que l'application n'avait pas, ce qui est
 * la forme la plus couteuse d'un filet.
 *
 * ### Pourquoi la conversion a fini dans `Plmi.compute`
 *
 * Une premiere correction avait pose la conversion chez le producteur, a charge pour chaque
 * appelant de l'invoquer. Elle n'a pas tenu une journee : sur trois appelants, deux y pensaient et
 * le troisieme non. `Plmi.compute` recoit **les deux** entrees necessaires — la liste complete et
 * les series — donc c'est chez lui que la traduction est structurellement impossible a oublier.
 * Ces tests appellent volontairement `Plmi.compute` comme un appelant naif le ferait : la sortie
 * de `buildDetailed`, sans rien entre les deux.
 */
class IndexationDesSeriesTest {

    private val pi = PiResult(periodicityIndex = 0.61, valid = true, totalIntervals = 9, lmRatePerHour = 14.3)
    private val rhythm = Rhythm.estimate(DoubleArray(0))

    /**
     * Deux rejets en tete, puis quatre mouvements retenus a 22 s d'intervalle. Les index bruts de
     * la serie valent donc 2, 3, 4, 5 pour une liste de retenus qui n'en compte que quatre : sans
     * traduction, les deux derniers sortent du tableau et les deux premiers designent le mauvais
     * mouvement.
     */
    private fun evenements() = buildList {
        add(clmAt(600_000L, reject = ClmRejectReason.TOO_SHORT))
        add(clmAt(602_000L, reject = ClmRejectReason.MORPHOLOGY))
        addAll(clmsEvery(startSec = 700.0, stepSec = 22.0, count = 4))
    }

    private fun compute(events: List<com.pendulum.algo.model.Clm>) =
        Plmi.compute(
            clms = events,
            series = SeriesBuilder.buildDetailed(events, maskOf(), FS_HZ, SeriesConfig.aasmV3()).series,
            mask = maskOf(),
            fsHz = FS_HZ,
            rule = SeriesRule.AASM_V3,
            pi = pi,
            rhythm = rhythm,
            floorMode = FloorMode.BILATERAL,
            truncated = false,
        )

    @Test
    fun `les quatre mouvements de la serie sont comptes malgre deux rejets en tete`() {
        val events = evenements()

        // La premisse : la base brute est bien decalee. On la verifie plutot que de la supposer,
        // sans quoi le test pourrait passer pour la mauvaise raison.
        val brut = SeriesBuilder.buildDetailed(events, maskOf(), FS_HZ, SeriesConfig.aasmV3())
        assertThat(brut.series).hasSize(1)
        assertThat(brut.series[0].clmIndices.toList()).containsExactly(2, 3, 4, 5)

        // Le chiffre, en valeur absolue. Avant correction il valait 2 : les index 4 et 5 tombaient
        // hors du tableau des retenus et disparaissaient en silence.
        val r = compute(events)
        assertThat(r.plmsCount).isEqualTo(4)
        assertThat(r.isolatedCount).isEqualTo(0)
    }

    @Test
    fun `sans aucun rejet le resultat est le meme`() {
        val r = compute(clmsEvery(startSec = 700.0, stepSec = 22.0, count = 4))

        assertThat(r.plmsCount).isEqualTo(4)
        assertThat(r.isolatedCount).isEqualTo(0)
    }

    /**
     * Le cas qui distingue une traduction d'un simple decalage : des rejets **entre** les
     * mouvements retenus, pas seulement en tete. La correspondance n'est alors plus une constante.
     */
    @Test
    fun `des rejets intercales ne decalent aucun compte`() {
        val events = buildList {
            add(clmAt(700_000L))
            add(clmAt(710_000L, reject = ClmRejectReason.POSTURAL))
            add(clmAt(722_000L))
            add(clmAt(730_000L, reject = ClmRejectReason.BLIND_ZONE))
            add(clmAt(744_000L))
            add(clmAt(766_000L))
        }

        val r = compute(events)

        assertThat(r.plmsCount).isEqualTo(4)
        assertThat(r.isolatedCount).isEqualTo(0)
    }
}
