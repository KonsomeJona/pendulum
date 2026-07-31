package com.pendulum.algo.indices

import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Tests du Periodicity Index de Ferri.
 *
 * La moitie de ces tests porte sur la CONVENTION, pas sur l'arithmetique : c'est la ou deux formes
 * contradictoires circulent (borne basse stricte ou inclusive, numerateur en intervalles ou en
 * mouvements) et c'est donc la que la non-regression a de la valeur.
 */
class PeriodicityTest {

    @Test
    fun `une nuit parfaitement periodique donne un index de 1`() {
        val clms = clmsEvery(startSec = 60.0, stepSec = 22.0, count = 100)

        val d = Periodicity.detail(clms, maskOf(), FS_HZ)

        assertThat(d.pi.periodicityIndex).isCloseTo(1.0, within(1e-12))
        assertThat(d.pi.valid).isTrue()
        assertThat(d.pi.totalIntervals).isEqualTo(99)
        assertThat(d.qualifyingIntervals).isEqualTo(99)
        assertThat(d.runCount).isEqualTo(1)
        assertThat(d.longestRunLength).isEqualTo(99)
        // N / TST_analysable_h = 100 / 7 h
        assertThat(d.pi.lmRatePerHour).isCloseTo(100.0 / 7.0, within(1e-9))
    }

    @Test
    fun `la borne basse est STRICTE - un intervalle de 10,0 s exactement ne qualifie pas`() {
        val d = Periodicity.fromIntervals(doubleArrayOf(10.0, 10.0, 10.0, 10.0), 5, 10.0)

        assertThat(d.qualifyingIntervals).isEqualTo(0)
        assertThat(d.pi.periodicityIndex).isEqualTo(0.0)

        val juste = Periodicity.fromIntervals(doubleArrayOf(10.001, 10.001, 10.001), 4, 10.0)
        assertThat(juste.pi.periodicityIndex).isCloseTo(1.0, within(1e-12))
    }

    @Test
    fun `la borne haute est INCLUSIVE - un intervalle de 90,0 s exactement qualifie`() {
        val d = Periodicity.fromIntervals(doubleArrayOf(90.0, 90.0, 90.0), 4, 10.0)

        assertThat(d.qualifyingIntervals).isEqualTo(3)
        assertThat(d.pi.periodicityIndex).isCloseTo(1.0, within(1e-12))

        val trop = Periodicity.fromIntervals(doubleArrayOf(90.001, 90.001, 90.001), 4, 10.0)
        assertThat(trop.pi.periodicityIndex).isEqualTo(0.0)
    }

    @Test
    fun `le numerateur compte des INTERVALLES et seulement ceux des sequences d'au moins 3`() {
        // qualifiants : O O N O O O N  -> une sequence de 2 (ignoree) et une de 3 (comptee)
        val imi = doubleArrayOf(22.0, 22.0, 200.0, 22.0, 22.0, 22.0, 300.0)

        val d = Periodicity.fromIntervals(imi, sleepClmCount = 8, analysableSleepMin = 10.0)

        assertThat(d.qualifyingIntervals).isEqualTo(5)
        assertThat(d.intervalsInCountedRuns).isEqualTo(3)
        assertThat(d.runCount).isEqualTo(1)
        assertThat(d.longestRunLength).isEqualTo(3)
        // 3 intervalles retenus sur 7 — et surtout PAS 4 mouvements sur 8, qui serait l'autre
        // forme publiee par Ferri. Les deux ne doivent jamais etre melangees.
        assertThat(d.pi.periodicityIndex).isCloseTo(3.0 / 7.0, within(1e-12))
        assertThat(d.convention).isEqualTo(PiConvention.FERRI_INTERVALS_LOW_EXCLUSIVE)
        assertThat(Periodicity.CONVENTION_DOC).contains("INTERVALLES")
    }

    @Test
    fun `une sequence de deux intervalles ne compte pas du tout`() {
        val d = Periodicity.fromIntervals(doubleArrayOf(22.0, 22.0, 500.0, 22.0), 5, 10.0)

        assertThat(d.qualifyingIntervals).isEqualTo(3)
        assertThat(d.intervalsInCountedRuns).isEqualTo(0)
        assertThat(d.pi.periodicityIndex).isEqualTo(0.0)
    }

    @Test
    fun `sous 10 mouvements par heure l'index est calcule mais declare ininterpretable`() {
        // 5 CLM sur 1 h : PI parfait, mais le denominateur N-1 est trop petit pour signifier
        // quoi que ce soit (Drakatos 2021). C'est exactement l'instabilite du groupe temoin.
        val d = Periodicity.fromIntervals(doubleArrayOf(22.0, 22.0, 22.0, 22.0), 5, 60.0)

        assertThat(d.pi.periodicityIndex).isCloseTo(1.0, within(1e-12))
        assertThat(d.pi.lmRatePerHour).isCloseTo(5.0, within(1e-9))
        assertThat(d.pi.valid).isFalse()
    }

    @Test
    fun `l'index lui-meme ne depend d'AUCUN denominateur temporel`() {
        // C'est l'argument central du §5 de SPEC-v2 : la circularite du denominateur disparait
        // pour la metrique de suivi. Seul le garde-fou de taux voit le temps de sommeil.
        val clms = clmsEvery(startSec = 60.0, stepSec = 22.0, count = 100)

        val court = Periodicity.detail(clms, maskOf(analysableTstMin = 200.0), FS_HZ)
        val long = Periodicity.detail(clms, maskOf(analysableTstMin = 420.0), FS_HZ)

        assertThat(court.pi.periodicityIndex).isEqualTo(long.pi.periodicityIndex)
        assertThat(court.pi.lmRatePerHour).isNotEqualTo(long.pi.lmRatePerHour)
        assertThat(court.pi.valid).isTrue()
        assertThat(long.pi.valid).isTrue()
    }

    @Test
    fun `seuls les CLM retenus et pendant le sommeil entrent dans le calcul`() {
        val windows = listOf(
            SleepWindow(0L, 3_600_000L, Stage.SLEEP),
            SleepWindow(3_600_000L, 7_200_000L, Stage.AWAKE_IN_BED),
            SleepWindow(7_200_000L, 25_200_000L, Stage.SLEEP),
        )
        val clms = buildList {
            addAll(clmsEvery(startSec = 60.0, stepSec = 22.0, count = 50))          // sommeil
            add(clmAt(4_000_000L))                                                   // eveil : hors calcul
            add(clmAt(4_022_000L))                                                   // eveil : hors calcul
            add(clmAt(8_000_000L, reject = ClmRejectReason.POSTURAL))                // rejete : hors calcul
            addAll(clmsEvery(startSec = 9_000.0, stepSec = 22.0, count = 50))        // sommeil
        }

        val d = Periodicity.detail(clms, maskOf(windows = windows), FS_HZ)

        assertThat(d.sleepClmCount).isEqualTo(100)
        assertThat(d.totalIntervals).isEqualTo(99)
        // 98 intervalles internes aux deux salves + 1 intervalle de raccord tres long, non qualifiant.
        assertThat(d.qualifyingIntervals).isEqualTo(98)
        assertThat(d.pi.periodicityIndex).isCloseTo(98.0 / 99.0, within(1e-12))
    }

    @Test
    fun `aucun intervalle - index nul et non valide, sans exception`() {
        val d = Periodicity.fromIntervals(DoubleArray(0), 1, 420.0)

        assertThat(d.pi.periodicityIndex).isEqualTo(0.0)
        assertThat(d.pi.totalIntervals).isEqualTo(0)
        assertThat(d.pi.valid).isFalse()
    }
}
