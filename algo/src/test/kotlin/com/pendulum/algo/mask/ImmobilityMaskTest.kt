package com.pendulum.algo.mask

import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

private const val NIGHT_SEC = 8.0 * 3600.0
private const val NIGHT_MIN = 480.0

/** Bouffee franchement au-dessus de `moveFactor x plancher` (6 x 0,004 = 0,024 g). */
private const val MOVE_G = 0.10f

class ImmobilityMaskTest {

    private fun build(
        night: Night,
        ignoreIntervals: List<Segment> = emptyList(),
        offBody: List<Segment> = emptyList(),
        blindZones: List<Segment> = emptyList(),
        diary: DiaryWindow? = null,
        cfg: ImmobilityConfig = ImmobilityConfig(),
    ): SleepMask = ImmobilityMask.build(
        gravity = night.gravity(),
        env = night.env(),
        floor = night.floor(),
        segments = night.segments(),
        offBody = offBody,
        ignoreIntervals = ignoreIntervals,
        diary = diary,
        cfg = cfg,
        blindZones = blindZones,
    )

    @Test
    fun `nuit sans aucun mouvement - SPT plausible et TST egal au SPT`() {
        val mask = build(Night(NIGHT_SEC))

        assertThat(mask.sptMin).isCloseTo(NIGHT_MIN, within(0.1))
        assertThat(mask.tstMin).isCloseTo(NIGHT_MIN, within(0.1))
        assertThat(mask.wasoMin).isCloseTo(0.0, within(1e-9))
        assertThat(mask.windows).hasSize(1)
        assertThat(mask.windows[0].stage).isEqualTo(Stage.SLEEP)
        assertThat(mask.source).isEqualTo(MaskSource.ACCEL_IMMOBILITY)
    }

    /**
     * Le mode de defaillance disqualifiant de §3.6.3, rendu explicite. Ce test **documente** le
     * comportement naif ; il n'est pas la pour approuver ce comportement mais pour que sa
     * disparition (test suivant) soit lisible comme un resultat et non comme une coincidence.
     *
     * Une serie a IMI 22 s place ~13 mouvements dans n'importe quelle fenetre de 5 min : aucune
     * bouffee d'inactivite soutenue ne peut exister, donc **toute la nuit est scoree en eveil**.
     */
    @Test
    fun `serie periodique dense - sans neutralisation le masque s'effondre`() {
        val night = Night(NIGHT_SEC)
        night.periodic(startSec = 10.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 1300)

        val mask = build(night)

        assertThat(mask.tstMin).isEqualTo(0.0)
        assertThat(mask.sptMin).isEqualTo(0.0)
    }

    /**
     * **Le test qui protege du mode de defaillance disqualifiant.** Memes donnees, memes
     * parametres ; seule change la couche 1 : les intervalles de mouvement periodique cessent
     * d'etre des preuves d'eveil. Le sujet le plus atteint doit redevenir celui dont on mesure le
     * sommeil, pas celui a qui l'on en refuse.
     */
    @Test
    fun `serie periodique dense - neutralisee elle n'est PAS scoree comme de l'eveil`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 10.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 1300)

        val mask = build(night, ignoreIntervals = clms)

        assertThat(mask.sptMin).isCloseTo(NIGHT_MIN, within(0.1))
        assertThat(mask.tstMin).isGreaterThan(0.98 * NIGHT_MIN)
        assertThat(mask.windows.filter { it.stage != Stage.SLEEP }).isEmpty()
    }

    @Test
    fun `mouvement corporel ample - reste une preuve d'eveil`() {
        val night = Night(NIGHT_SEC)
        night.tilt(atSec = 4.0 * 3600.0, deg = 40.0)

        val mask = build(night)

        assertThat(mask.wasoMin).isGreaterThan(0.0)
        assertThat(mask.windows.map { it.stage }).contains(Stage.AWAKE_IN_BED)
        // Une reorientation isolee ne doit pas pour autant amputer le SPT : ses bornes ne dependent
        // que des deux transitions extremes de la nuit.
        assertThat(mask.sptMin).isCloseTo(NIGHT_MIN, within(0.1))
    }

    /**
     * Garde-fou de la couche 1. Meme si un mouvement periodique est detecte au moment exact d'un
     * retournement, la reorientation persistante reste une preuve d'eveil : c'est la seule dont on
     * dispose vraiment, et l'effacer par coincidence de calendrier serait le pire echange possible.
     */
    @Test
    fun `garde-fou posture - une reorientation ample n'est jamais neutralisee`() {
        val night = Night(NIGHT_SEC)
        val at = 4.0 * 3600.0
        night.tilt(atSec = at, deg = 40.0)
        val covering = listOf(Segment(samples(at - 10.0), samples(at + 20.0)))

        val mask = build(night, ignoreIntervals = covering)

        assertThat(mask.wasoMin).isGreaterThan(0.0)
        assertThat(mask.windows.map { it.stage }).contains(Stage.AWAKE_IN_BED)
    }

    /**
     * Invariance par rotation du boitier : le Delta angulaire porte sur le vecteur gravite
     * unitaire, donc sur une grandeur qui ne nomme aucun axe. Une nuit rejouee avec le bracelet
     * tourne doit produire le **meme** masque — c'est la propriete pour laquelle van Hees a ete
     * prefere, et la seule qui rende deux nuits comparables quand la pose change.
     */
    @Test
    fun `invariance par rotation du boitier`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 10.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 600)
        night.tilt(atSec = 5.0 * 3600.0, deg = 40.0)

        val direct = ImmobilityMask.build(
            night.gravity(), night.env(), night.floor(), night.segments(), emptyList(), clms,
        )
        val rotated = ImmobilityMask.build(
            rotate(night.gravity(), 1.0, 2.0, 3.0, 37.0),
            night.env(), night.floor(), night.segments(), emptyList(), clms,
        )

        assertThat(rotated.windows).isEqualTo(direct.windows)
        assertThat(rotated.sptMin).isEqualTo(direct.sptMin)
        assertThat(rotated.tstMin).isEqualTo(direct.tstMin)
        assertThat(rotated.wasoMin).isEqualTo(direct.wasoMin)
    }

    @Test
    fun `un masque accelerometrique est toujours CIRCULAR, journal compris`() {
        val night = Night(NIGHT_SEC)

        val plain = build(night)
        val withDiary = build(night, diary = DiaryWindow(3_600_000L, 25_200_000L))

        assertThat(plain.independence).isEqualTo(DenominatorIndependence.CIRCULAR)
        assertThat(withDiary.independence).isEqualTo(DenominatorIndependence.CIRCULAR)
    }

    @Test
    fun `le journal borne la recherche du SPT sans fournir le denominateur`() {
        val night = Night(NIGHT_SEC)

        val mask = build(night, diary = DiaryWindow(3_600_000L, 25_200_000L))

        assertThat(mask.sptMin).isCloseTo(360.0, within(0.2))
        assertThat(mask.tstMin).isCloseTo(360.0, within(0.2))
    }

    @Test
    fun `le denominateur analysable retire les zones aveugles du TST`() {
        val night = Night(NIGHT_SEC)
        val blind = listOf(Segment(samples(3600.0), samples(7200.0)))

        val mask = build(night, blindZones = blind)

        assertThat(mask.tstMin).isCloseTo(NIGHT_MIN, within(0.1))
        assertThat(mask.analysableTstMin).isCloseTo(NIGHT_MIN - 60.0, within(0.2))
        assertThat(mask.analysableSptMin).isCloseTo(NIGHT_MIN - 60.0, within(0.2))
    }

    @Test
    fun `le hors-corps ne prouve rien et ferme le SPT`() {
        val night = Night(NIGHT_SEC)
        val off = listOf(Segment(samples(7.0 * 3600.0), night.n))

        val mask = build(night, offBody = off)

        assertThat(mask.sptMin).isCloseTo(420.0, within(0.2))
        assertThat(mask.tstMin).isCloseTo(420.0, within(0.2))
    }

    @Test
    fun `point fixe - une serie localisee converge`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 7200.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 122)

        val fp = ImmobilityMask.fixedPoint(
            night.gravity(), night.env(), night.floor(), night.segments(), emptyList(),
        ) { clms }

        assertThat(fp.iterations).isEqualTo(2)
        assertThat(fp.provisionalTstMin).isCloseTo(NIGHT_MIN - 45.0, within(2.0))
        assertThat(fp.tstDeltaFraction).isLessThan(0.25)
        assertThat(fp.mask.fixedPointConverged).isTrue()
        assertThat(fp.mask.tstMin).isCloseTo(NIGHT_MIN, within(0.2))
    }

    /**
     * Le cas ou le TST provisoire s'effondre. Deux choses doivent se produire ensemble, et c'est le
     * coeur de la reponse a la circularite : le masque **final** conserve la nuit (couche 1), et la
     * non-convergence est rapportee honnetement (couche 2) pour que la porte de publication refuse
     * un aPLM-i sur une nuit ou le TST accelerometrique n'est pas determinable. Le Periodicity
     * Index, lui, n'a pas de denominateur temporel et survit a ce refus.
     */
    @Test
    fun `point fixe - non convergence quand le TST provisoire s'effondre`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 10.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 1300)

        val fp = ImmobilityMask.fixedPoint(
            night.gravity(), night.env(), night.floor(), night.segments(), emptyList(),
        ) { clms }

        assertThat(fp.provisionalTstMin).isEqualTo(0.0)
        assertThat(fp.tstDeltaFraction).isNaN()
        assertThat(fp.mask.fixedPointConverged).isFalse()
        assertThat(fp.mask.tstMin).isGreaterThan(0.98 * NIGHT_MIN)
    }

    @Test
    fun `plus de deux iterations est refuse par construction`() {
        val night = Night(60.0)

        assertThatThrownBy {
            ImmobilityMask.fixedPoint(
                night.gravity(), night.env(), night.floor(), night.segments(), emptyList(),
                cfg = ImmobilityConfig(maxFixedPointIterations = 3),
            ) { emptyList() }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `le masque est deterministe - deux constructions identiques donnent le meme resultat`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 30.0, imiSec = 25.0, durSec = 1.0, ampG = MOVE_G, count = 500)

        val a = build(night, ignoreIntervals = clms)
        val b = build(night, ignoreIntervals = clms)

        assertThat(b.windows).isEqualTo(a.windows)
        assertThat(b.analysableTstMin).isEqualTo(a.analysableTstMin)
    }
}
