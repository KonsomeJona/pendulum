package com.pendulum.algo.mask

import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

private const val MIN = 60_000L
private const val HOUR = 3_600_000L

/**
 * Masque accelerometrique de reference : 8 h au lit, un eveil de 20 min a 3 h du matin.
 * Construit a la main plutot que par [ImmobilityMask] : ce fichier teste la fusion, pas le scorage.
 */
private fun accelMask(
    independence: DenominatorIndependence = DenominatorIndependence.CIRCULAR,
): SleepMask = SleepMask(
    windows = listOf(
        SleepWindow(0L, 3 * HOUR, Stage.SLEEP),
        SleepWindow(3 * HOUR, 3 * HOUR + 20 * MIN, Stage.AWAKE_IN_BED),
        SleepWindow(3 * HOUR + 20 * MIN, 8 * HOUR, Stage.SLEEP),
    ),
    source = MaskSource.ACCEL_IMMOBILITY,
    sptMin = 480.0,
    tstMin = 460.0,
    wasoMin = 20.0,
    analysableTstMin = 437.0,
    analysableSptMin = 456.0,
    corrected = false,
    lagAppliedMs = 0L,
    independence = independence,
    fixedPointConverged = true,
)

private fun shifted(windows: List<SleepWindow>, byMs: Long): List<SleepWindow> =
    windows.map { SleepWindow(it.startMsRel + byMs, it.endMsRel + byMs, it.stage) }

class MaskFusionTest {

    /**
     * Le recalage doit retrouver un decalage injecte. Deux montres, deux horloges : sans cette
     * correction, deux minutes d'ecart suffisent a faire basculer des mouvements de part et d'autre
     * de l'endormissement, donc a les imputer au mauvais stade.
     */
    @Test
    fun `recherche de decalage - un decalage injecte est retrouve`() {
        val accel = accelMask()
        val hc = shifted(accel.windows, -2 * MIN)

        val agreement = MaskFusion.align(accel, hc)

        assertThat(agreement.bestLagMs).isEqualTo(2 * MIN)
        assertThat(agreement.kappa).isGreaterThan(0.95)
        assertThat(agreement.overlapPct).isGreaterThan(95.0)
        assertThat(agreement.tstDeltaMin).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `recherche de decalage - a accord egal le plus petit decalage gagne`() {
        val accel = accelMask()

        val agreement = MaskFusion.align(accel, accel.windows)

        assertThat(agreement.bestLagMs).isEqualTo(0L)
        assertThat(agreement.kappa).isCloseTo(1.0, within(1e-9))
        assertThat(agreement.overlapPct).isCloseTo(100.0, within(1e-9))
    }

    @Test
    fun `un decalage superieur a cinq minutes est suspect`() {
        val accel = accelMask()
        val hc = shifted(accel.windows, -7 * MIN)

        val agreement = MaskFusion.align(accel, hc)

        assertThat(agreement.bestLagMs).isEqualTo(7 * MIN)
        assertThat(MaskFusion.lagSuspect(agreement)).isTrue()
    }

    @Test
    fun `accord nul quand l'un des masques est vide`() {
        val agreement = MaskFusion.align(accelMask(), emptyList())

        assertThat(agreement.kappa).isNaN()
        assertThat(agreement.bestLagMs).isEqualTo(0L)
    }

    // --- Independance du denominateur ---------------------------------------------------------

    @Test
    fun `independence - Health Connect rompt la circularite`() {
        val hc = MaskFusion.fromHealthConnect(accelMask().windows)

        assertThat(hc.source).isEqualTo(MaskSource.HEALTH_CONNECT)
        assertThat(hc.independence).isEqualTo(DenominatorIndependence.INDEPENDENT_HC)
        assertThat(hc.tstMin).isCloseTo(460.0, within(1e-9))
        assertThat(hc.sptMin).isCloseTo(480.0, within(1e-9))
    }

    @Test
    fun `independence - le journal donne un denominateur totalement independant du signal`() {
        val diary = MaskFusion.fromDiary(DiaryWindow(0L, 8 * HOUR))

        assertThat(diary.source).isEqualTo(MaskSource.DIARY)
        assertThat(diary.independence).isEqualTo(DenominatorIndependence.INDEPENDENT_DIARY)
        // Le temps au lit surestime le TST : l'indice en ressort deflate, biais prudent ET
        // independant du nombre de mouvements — c'est precisement ce qu'on achete.
        assertThat(diary.tstMin).isCloseTo(480.0, within(1e-9))
        assertThat(diary.wasoMin).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `fusion - HC prioritaire, recale, et independant`() {
        val accel = accelMask()
        val hc = shifted(accel.windows, -2 * MIN)

        val fused = MaskFusion.fuse(accel, hc, diary = null)

        assertThat(fused.source).isEqualTo(MaskSource.FUSED)
        assertThat(fused.independence).isEqualTo(DenominatorIndependence.INDEPENDENT_HC)
        assertThat(fused.lagAppliedMs).isEqualTo(2 * MIN)
        assertThat(fused.windows.first().startMsRel).isEqualTo(0L)
        // Le taux de couverture analysable est une propriete de l'ENREGISTREMENT : il se transfere.
        assertThat(fused.analysableSptMin / fused.sptMin).isCloseTo(456.0 / 480.0, within(1e-9))
    }

    @Test
    fun `fusion - sans HC le journal prend le relais`() {
        val fused = MaskFusion.fuse(accelMask(), hc = null, diary = DiaryWindow(0L, 8 * HOUR))

        assertThat(fused.source).isEqualTo(MaskSource.FUSED)
        assertThat(fused.independence).isEqualTo(DenominatorIndependence.INDEPENDENT_DIARY)
        assertThat(fused.tstMin).isCloseTo(480.0, within(1e-9))
    }

    /**
     * Regle non negociable de `SPEC-v2.md` §2.3 : sans source independante, il n'y a rien a fusionner
     * et le masque accelerometrique est renvoye **tel quel**. Le renommer `FUSED` laisserait croire
     * a une fusion, et un `CIRCULAR` deguise finirait par porter un resultat principal.
     */
    @Test
    fun `fusion - sans HC ni journal le masque reste accelerometrique et circulaire`() {
        val accel = accelMask()

        val fused = MaskFusion.fuse(accel, hc = null, diary = null)

        assertThat(fused).isSameAs(accel)
        assertThat(fused.source).isEqualTo(MaskSource.ACCEL_IMMOBILITY)
        assertThat(fused.independence).isEqualTo(DenominatorIndependence.CIRCULAR)
    }

    @Test
    fun `fusion - le journal borne les fenetres HC au temps passe au lit`() {
        val accel = accelMask()
        val hc = accel.windows

        val fused = MaskFusion.fuse(accel, hc, diary = DiaryWindow(HOUR, 7 * HOUR))

        assertThat(fused.windows.minOf { it.startMsRel }).isEqualTo(HOUR)
        assertThat(fused.windows.maxOf { it.endMsRel }).isEqualTo(7 * HOUR)
        assertThat(fused.sptMin).isCloseTo(360.0, within(1e-9))
    }

    // --- Correction apprise -------------------------------------------------------------------

    @Test
    fun `correction - moins de trois nuits, aucune correction`() {
        val fit = MaskFusion.fitCorrection(listOf(400.0 to 460.0, 380.0 to 440.0))

        assertThat(fit.valid).isFalse()
        assertThat(fit.nNights).isEqualTo(2)
    }

    @Test
    fun `correction - entre trois et cinq nuits, mediane du ratio`() {
        val fit = MaskFusion.fitCorrection(listOf(400.0 to 440.0, 300.0 to 360.0, 200.0 to 220.0))

        assertThat(fit.valid).isTrue()
        assertThat(fit.beta).isEqualTo(0.0)
        assertThat(fit.alpha).isCloseTo(1.1, within(1e-9)) // mediane de {1,10 ; 1,20 ; 1,10}
    }

    @Test
    fun `correction - au moins cinq nuits, Theil-Sen retrouve la droite`() {
        val pairs = (0 until 6).map { val x = 300.0 + 20.0 * it; x to (1.2 * x + 15.0) }

        val fit = MaskFusion.fitCorrection(pairs)

        assertThat(fit.alpha).isCloseTo(1.2, within(1e-9))
        assertThat(fit.beta).isCloseTo(15.0, within(1e-6))
        assertThat(fit.valid).isTrue()
    }

    @Test
    fun `correction - une nuit aberrante ne fait pas basculer la pente`() {
        val clean = (0 until 6).map { val x = 300.0 + 20.0 * it; x to (1.2 * x + 15.0) }
        val fit = MaskFusion.fitCorrection(clean + listOf(340.0 to 900.0))

        assertThat(fit.alpha).isCloseTo(1.2, within(0.15))
    }

    /**
     * Recaler la moyenne d'un estimateur ne le rend pas independant de ce qu'il mesure : la
     * retroaction nuit-a-nuit reste entiere, donc `independence` ne bouge pas d'un iota.
     */
    @Test
    fun `correction appliquee - le denominateur reste CIRCULAR`() {
        val accel = accelMask()
        val fit = MaskFusion.fitCorrection((0 until 6).map { 460.0 - it to 470.0 - it })

        val corrected = MaskFusion.applyCorrection(accel, fit)

        assertThat(corrected.corrected).isTrue()
        assertThat(corrected.independence).isEqualTo(DenominatorIndependence.CIRCULAR)
        assertThat(corrected.tstMin).isGreaterThan(accel.tstMin)
        assertThat(corrected.wasoMin).isCloseTo(corrected.sptMin - corrected.tstMin, within(1e-9))
        // Le denominateur publie suit la correction dans le meme rapport que le TST brut.
        assertThat(corrected.analysableTstMin / accel.analysableTstMin)
            .isCloseTo(corrected.tstMin / accel.tstMin, within(1e-9))
    }

    @Test
    fun `correction invalide - le masque est renvoye intact`() {
        val accel = accelMask()

        val out = MaskFusion.applyCorrection(accel, MaskFusion.fitCorrection(listOf(400.0 to 440.0)))

        assertThat(out).isSameAs(accel)
        assertThat(out.corrected).isFalse()
    }

    @Test
    fun `correction - le TST corrige ne depasse jamais le SPT`() {
        val accel = accelMask()
        val huge = TstCorrection(alpha = 2.0, beta = 0.0, nNights = 6, valid = true)

        val corrected = MaskFusion.applyCorrection(accel, huge)

        assertThat(corrected.tstMin).isEqualTo(accel.sptMin)
        assertThat(corrected.wasoMin).isEqualTo(0.0)
    }
}
