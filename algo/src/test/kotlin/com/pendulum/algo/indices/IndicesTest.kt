package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.PublicationGate
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** Tests de l'etape 7 : comptes horaires, encadrement respiratoire, porte de publication. */
class IndicesTest {

    private val pi = PiResult(periodicityIndex = 0.61, valid = true, totalIntervals = 99, lmRatePerHour = 14.3)
    private val rhythm: RhythmResult = Rhythm.estimate(DoubleArray(0))

    private fun compute(
        clms: List<Clm>,
        series: List<PlmSeries>,
        mask: SleepMask,
        rule: SeriesRule = SeriesRule.AASM_V3,
        truncated: Boolean = false,
        truncatedSeriesDropped: Int = 0,
    ) = Plmi.compute(
        clms = clms,
        series = series,
        mask = mask,
        fsHz = FS_HZ,
        rule = rule,
        pi = pi,
        rhythm = rhythm,
        floorMode = FloorMode.BILATERAL,
        truncated = truncated,
        truncatedSeriesDropped = truncatedSeriesDropped,
        paramsHash = "test-hash",
    )

    @Test
    fun `le denominateur est le sommeil ANALYSABLE, jamais le TST brut`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)
        val mask = maskOf(tstMin = 420.0, analysableTstMin = 300.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), mask)

        assertThat(r.plmsCount).isEqualTo(10)
        assertThat(r.plmi).isCloseTo(2.0, within(1e-9))            // 10 / 5 h analysables
        assertThat(r.plmi).isNotEqualTo(10.0 / 7.0)                // et surtout PAS 10 / 7 h brutes
        assertThat(r.tstMin).isEqualTo(420.0)
        assertThat(r.analysableTstMin).isEqualTo(300.0)
    }

    @Test
    fun `le second index est rapporte au temps passe au lit`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 12)
        val mask = maskOf(analysableTstMin = 300.0, analysableSptMin = 360.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 11)), mask)

        assertThat(r.plmiSpt).isCloseTo(2.0, within(1e-9))          // 12 / 6 h de SPT analysable
        assertThat(r.plmi).isCloseTo(2.4, within(1e-9))             // 12 / 5 h de TST analysable
    }

    @Test
    fun `sans denominateur, aucun taux n'est invente`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)
        val mask = maskOf(analysableTstMin = 0.0, tstMin = 0.0, analysableSptMin = 0.0, sptMin = 0.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), mask)

        assertThat(r.plmi.isNaN()).isTrue()
        assertThat(r.plmiSpt.isNaN()).isTrue()
        assertThat(r.gate).isEqualTo(PublicationGate.NO_PLMI)
    }

    @Test
    fun `mouvements isoles, en serie, et intervalles courts sont comptes separement`() {
        val enSerie = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)
        val isoles = clmsAtSec(20_000.0, 20_100.0, 20_200.0)
        val clms = enSerie + isoles

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), maskOf(analysableTstMin = 300.0))

        assertThat(r.plmsCount).isEqualTo(10)
        assertThat(r.isolatedCount).isEqualTo(3)
        assertThat(r.plmwCount).isEqualTo(0)
    }

    @Test
    fun `la borne basse d'intervalle court depend du jeu de regles`() {
        // Intervalles de 7 s : au-dessus de la borne AASM (5 s), en dessous de la borne WASM (10 s).
        val clms = clmsAtSec(600.0, 607.0, 629.0, 651.0, 673.0)
        val mask = maskOf(analysableTstMin = 300.0)

        val aasm = compute(clms, emptyList(), mask, rule = SeriesRule.AASM_V3)
        val wasm = compute(clms, emptyList(), mask, rule = SeriesRule.WASM_2016)

        assertThat(aasm.shortImiCount).isEqualTo(0)
        assertThat(wasm.shortImiCount).isEqualTo(1)
        assertThat(Plmi.imiMinSecOf(SeriesRule.AASM_V3)).isEqualTo(5.0)
        assertThat(Plmi.imiMinSecOf(SeriesRule.WASM_2016)).isEqualTo(10.0)
    }

    @Test
    fun `le PLMW se rapporte au WASO, pas au TST`() {
        val windows = listOf(
            SleepWindow(0L, 3_600_000L, Stage.SLEEP),
            SleepWindow(3_600_000L, 7_200_000L, Stage.AWAKE_IN_BED),
            SleepWindow(7_200_000L, 25_200_000L, Stage.SLEEP),
        )
        val clms = clmsEvery(startSec = 4_000.0, stepSec = 22.0, count = 5)
        val mask = maskOf(
            windows = windows,
            sptMin = 480.0,
            analysableSptMin = 480.0,
            wasoMin = 60.0,
            tstMin = 420.0,
            analysableTstMin = 420.0,
        )

        val r = compute(clms, listOf(seriesOver(clms, 0, 4)), mask)

        assertThat(r.plmsCount).isEqualTo(0)
        assertThat(r.plmwCount).isEqualTo(5)
        assertThat(r.plmw).isCloseTo(5.0, within(1e-9))     // 5 evenements / 1 h de WASO analysable
        assertThat(r.plmi).isCloseTo(0.0, within(1e-12))
    }

    @Test
    fun `le split-half rapporte les deux moities de nuit separement`() {
        // SPT = [0, 420 min], milieu a 210 min. 6 evenements avant, 4 apres.
        val avant = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 6)
        val apres = clmsEvery(startSec = 13_000.0, stepSec = 22.0, count = 4)
        val clms = avant + apres
        val mask = maskOf(tstMin = 420.0, analysableTstMin = 300.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 5), seriesOver(clms, 6, 9)), mask)

        // Chaque moitie porte 210 min de sommeil, ramenes a 300/420 de couverture analysable = 2,5 h.
        assertThat(r.plmiFirstHalf).isCloseTo(6.0 / 2.5, within(1e-9))
        assertThat(r.plmiSecondHalf).isCloseTo(4.0 / 2.5, within(1e-9))
    }

    @Test
    fun `l'histogramme des IMI porte sur tous les CLM de sommeil, bins de 2 s`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), maskOf(analysableTstMin = 300.0))

        assertThat(r.imiHistogram.size).isEqualTo(50)
        assertThat(r.imiBinEdgesSec.size).isEqualTo(51)
        assertThat(r.imiBinEdgesSec[11]).isEqualTo(22.0f)
        assertThat(r.imiHistogram[11]).isEqualTo(9)
        assertThat(r.imiHistogram.sum()).isEqualTo(9)
    }

    @Test
    fun `le pire cas respiratoire retire les series dont l'IMI median est dans la bande apneique`() {
        val plms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)          // rythme PLMS
        val apnee = clmsEvery(startSec = 5_000.0, stepSec = 30.0, count = 10)       // bande 25-45 s
        val clms = plms + apnee
        val mask = maskOf(analysableTstMin = 300.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9), seriesOver(clms, 10, 19)), mask)

        assertThat(r.plmi).isCloseTo(4.0, within(1e-9))                 // 20 / 5 h
        assertThat(r.plmiRespWorstCase).isCloseTo(2.0, within(1e-9))    // 10 / 5 h
        // L'ECART entre les deux bornes est l'indicateur d'incertitude a afficher : sans canal
        // respiratoire, rien ne permet de situer la vraie valeur dans l'intervalle.
        assertThat(Plmi.respiratoryBiasSpread(r)).isCloseTo(2.0, within(1e-9))
    }

    @Test
    fun `l'independance du denominateur est propagee telle quelle`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)
        val series = listOf(seriesOver(clms, 0, 9))

        for (independence in DenominatorIndependence.entries) {
            val mask = maskOf(
                analysableTstMin = 420.0,
                source = if (independence == DenominatorIndependence.CIRCULAR) {
                    MaskSource.ACCEL_IMMOBILITY
                } else {
                    MaskSource.HEALTH_CONNECT
                },
                independence = independence,
            )
            val r = compute(clms, series, mask)
            assertThat(r.independence).isEqualTo(independence)
        }
    }

    @Test
    fun `un denominateur circulaire ne peut pas porter le resultat principal`() {
        val mask = maskOf(
            analysableTstMin = 420.0,
            source = MaskSource.ACCEL_IMMOBILITY,
            independence = DenominatorIndependence.CIRCULAR,
        )

        assertThat(Plmi.canCarryPrimaryResult(mask)).isFalse()
        assertThat(Plmi.publicationGate(mask, truncated = false))
            .isEqualTo(PublicationGate.TRUNCATED_NO_TREND)
        assertThat(Plmi.canCarryPrimaryResult(maskOf())).isTrue()
    }

    @Test
    fun `la porte de publication suit les seuils en dur du paragraphe 3-7-2`() {
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 420.0), false))
            .isEqualTo(PublicationGate.FULL)
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 240.0), false))
            .isEqualTo(PublicationGate.FULL)
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 200.0), false))
            .isEqualTo(PublicationGate.TRUNCATED_NO_TREND)
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 420.0), true))
            .isEqualTo(PublicationGate.TRUNCATED_NO_TREND)
        assertThat(Plmi.publicationGate(maskOf(analysableTstMin = 179.9), false))
            .isEqualTo(PublicationGate.NO_PLMI)
    }

    @Test
    fun `un masque accelerometrique non convergent interdit tout PLMI`() {
        val mask = maskOf(
            analysableTstMin = 420.0,
            source = MaskSource.ACCEL_IMMOBILITY,
            independence = DenominatorIndependence.CIRCULAR,
            fixedPointConverged = false,
        )

        assertThat(Plmi.publicationGate(mask, false))
            .isEqualTo(PublicationGate.NO_PLMI)
    }

    @Test
    fun `une nuit tronquee a 3 h ne publie pas de PLMI mais garde le PI et le compte`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 40)
        val mask = maskOf(tstMin = 170.0, analysableTstMin = 170.0, sptMin = 180.0, analysableSptMin = 180.0)

        val r = compute(clms, listOf(seriesOver(clms, 0, 39)), mask, truncated = true, truncatedSeriesDropped = 2)

        assertThat(r.gate).isEqualTo(PublicationGate.NO_PLMI)
        assertThat(r.plmsCount).isEqualTo(40)
        assertThat(r.pi.valid).isTrue()
        assertThat(r.truncatedSeriesDropped).isEqualTo(2)
    }

    @Test
    fun `les CLM rejetes ne comptent nulle part`() {
        val clms = listOf(
            clmAt(600_000L),
            clmAt(622_000L, reject = com.pendulum.algo.model.ClmRejectReason.POSTURAL),
            clmAt(644_000L),
        )

        val r = compute(clms, emptyList(), maskOf(analysableTstMin = 300.0))

        assertThat(r.isolatedCount).isEqualTo(2)
        // Les deux CLM retenus sont espaces de 44 s : un seul intervalle, dans le bin [44, 46).
        assertThat(r.imiHistogram[22]).isEqualTo(1)
        assertThat(r.imiHistogram.sum()).isEqualTo(1)
    }

    @Test
    fun `les metadonnees du resultat sont celles du masque et des parametres`() {
        val clms = clmsEvery(startSec = 600.0, stepSec = 22.0, count = 10)

        val r = compute(clms, listOf(seriesOver(clms, 0, 9)), maskOf(), rule = SeriesRule.WASM_2016)

        assertThat(r.rule).isEqualTo(SeriesRule.WASM_2016)
        assertThat(r.maskSource).isEqualTo(MaskSource.HEALTH_CONNECT)
        assertThat(r.floorMode).isEqualTo(FloorMode.BILATERAL)
        assertThat(r.paramsHash).isEqualTo("test-hash")
        assertThat(r.pi).isEqualTo(pi)
        assertThat(r.rhythm.valid).isFalse()
    }
}
