package com.pendulum.algo.detect

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.ClmRejectReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SeriesBuilderTest {

    private val night = sleepAllNight(3600.0)

    private fun aasm(events: List<Clm>) = SeriesBuilder.build(events, night, FS, SeriesConfig.aasmV3())
    private fun wasm(events: List<Clm>) = SeriesBuilder.build(events, night, FS, SeriesConfig.wasm2016())

    @Test
    @DisplayName("cinq CLM a 20 s d'intervalle forment une serie dans les deux jeux de regles")
    fun regularSeries() {
        val events = listOf(100.0, 120.0, 140.0, 160.0, 180.0).map { clmAt(it) }

        for (series in listOf(aasm(events), wasm(events))) {
            assertThat(series).hasSize(1)
            assertThat(series.single().clmIndices.toList()).containsExactly(0, 1, 2, 3, 4)
            assertThat(series.single().imiSec.toList()).containsExactly(20f, 20f, 20f, 20f)
            assertThat(series.single().truncatedAtStart).isFalse()
            assertThat(series.single().truncatedAtEnd).isFalse()
            assertThat(series.single().duringSleepFraction).isEqualTo(1f)
        }
    }

    @Test
    @DisplayName("un mouvement manque ne casse pas la serie : 21 s double a 42 s reste dans la fenetre")
    fun missedMovementDoesNotBreakTheSeries() {
        // Point de vigilance de la v2 : dans le regime typique, rater un CLM double l'intervalle
        // sans sortir de [5, 90] s. La serie survit, seul le compte baisse. La rupture n'arrive que
        // si l'intervalle fusionne depasse 90 s.
        val events = listOf(100.0, 121.0, 163.0, 184.0).map { clmAt(it) }

        for (series in listOf(aasm(events), wasm(events))) {
            assertThat(series).hasSize(1)
            assertThat(series.single().clmIndices.toList()).containsExactly(0, 1, 2, 3)
            assertThat(series.single().imiSec.toList()).containsExactly(21f, 42f, 21f)
        }
    }

    @Test
    @DisplayName("un intervalle fusionne au-dela de 90 s casse bien la serie")
    fun longImiBreaksTheSeries() {
        val events = listOf(100.0, 121.0, 142.0, 240.0, 261.0, 282.0).map { clmAt(it) }

        assertThat(aasm(events)).isEmpty()
        assertThat(wasm(events)).isEmpty()
    }

    @Test
    @DisplayName("salve non periodique : AASM construit une serie, WASM n'en construit aucune")
    fun nonPeriodicBurstDivergesBetweenRules() {
        // C'est LA divergence structurante (Ferri 2015) : WASM rompt la serie sur un IMI court,
        // l'AASM ignore le mouvement posterieur et mesure la periode jusqu'au candidat suivant.
        val events = (0..7).map { clmAt(100.0 + 3.0 * it, durationMs = 800) }

        val aasmSeries = aasm(events)
        assertThat(aasmSeries).hasSize(1)
        assertThat(aasmSeries.single().clmIndices.toList()).containsExactly(0, 2, 4, 6)
        assertThat(aasmSeries.single().imiSec.toList()).containsExactly(6f, 6f, 6f)

        assertThat(wasm(events)).isEmpty()
    }

    @Test
    @DisplayName("un LM long casse la serie en WASM, pas en AASM")
    fun longLmBreaksOnlyWasm() {
        val events = listOf(
            clmAt(100.0), clmAt(120.0), clmAt(140.0),
            clmAt(150.0, durationMs = 12_000, flags = ClmFlags.LM_LONG or ClmFlags.GROSS_BODY,
                reject = ClmRejectReason.GROSS_BODY),
            clmAt(160.0), clmAt(180.0), clmAt(200.0),
        )

        val aasmSeries = aasm(events)
        assertThat(aasmSeries).hasSize(1)
        assertThat(aasmSeries.single().clmIndices.toList()).containsExactly(0, 1, 2, 4, 5, 6)

        // WASM : rupture au LM long -> 3 CLM de part et d'autre, aucune serie ne tient les 4 requis.
        assertThat(wasm(events)).isEmpty()
    }

    @Test
    @DisplayName("les CLM hors sommeil sont ecartes en AASM v3, conserves en WASM")
    fun requirePortionInSleepSeparatesTheRules() {
        val mask = sleepAllNight(150.0) // le sommeil s'arrete a 150 s
        val events = listOf(100.0, 120.0, 140.0, 160.0, 180.0).map { clmAt(it) }

        val aasmSeries = SeriesBuilder.build(events, mask, FS, SeriesConfig.aasmV3())
        assertThat(aasmSeries).isEmpty() // seuls 3 CLM tombent dans le sommeil

        val wasmSeries = SeriesBuilder.build(events, mask, FS, SeriesConfig.wasm2016())
        assertThat(wasmSeries).hasSize(1) // 2.4.4 : une serie peut traverser une transition
        assertThat(wasmSeries.single().duringSleepFraction).isEqualTo(3f / 5f)
    }

    @Test
    @DisplayName("un evenement tronque casse la serie et marque la suivante comme tronquee au debut")
    fun truncatedEventBreaksAndMarks() {
        val events = listOf(
            clmAt(100.0), clmAt(120.0), clmAt(140.0), clmAt(160.0),
            clmAt(170.0, flags = ClmFlags.TRUNCATED, reject = ClmRejectReason.TRUNCATED),
            clmAt(200.0), clmAt(220.0), clmAt(240.0), clmAt(260.0),
        )

        val series = wasm(events)

        assertThat(series).hasSize(2)
        assertThat(series[0].truncatedAtEnd).isTrue()
        assertThat(series[1].truncatedAtStart).isTrue()
    }

    @Test
    @DisplayName("une serie incomplete tronquee par la fin de la nuit est abandonnee et comptee")
    fun incompleteTruncatedSeriesIsDropped() {
        val shortNight = sleepAllNight(100.0)
        val events = listOf(10.0, 30.0, 50.0).map { clmAt(it) }

        val result = SeriesBuilder.buildDetailed(events, shortNight, FS, SeriesConfig.wasm2016())

        assertThat(result.series).isEmpty()
        assertThat(result.truncatedSeriesDropped).isEqualTo(1)
    }
}
