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
    @DisplayName("five CLM 20 s apart form a series under both rule sets")
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
    @DisplayName("a missed movement does not break the series: 21 s doubled to 42 s is in window")
    fun missedMovementDoesNotBreakTheSeries() {
        // Point of vigilance from v2: in the typical regime, missing a CLM doubles the interval
        // without leaving [5, 90] s. The series survives, only the count drops. The break happens
        // only if the merged interval exceeds 90 s.
        val events = listOf(100.0, 121.0, 163.0, 184.0).map { clmAt(it) }

        for (series in listOf(aasm(events), wasm(events))) {
            assertThat(series).hasSize(1)
            assertThat(series.single().clmIndices.toList()).containsExactly(0, 1, 2, 3)
            assertThat(series.single().imiSec.toList()).containsExactly(21f, 42f, 21f)
        }
    }

    @Test
    @DisplayName("a merged interval beyond 90 s does break the series")
    fun longImiBreaksTheSeries() {
        val events = listOf(100.0, 121.0, 142.0, 240.0, 261.0, 282.0).map { clmAt(it) }

        assertThat(aasm(events)).isEmpty()
        assertThat(wasm(events)).isEmpty()
    }

    @Test
    @DisplayName("non-periodic burst: AASM builds a series, WASM builds none")
    fun nonPeriodicBurstDivergesBetweenRules() {
        // This is THE structuring divergence (Ferri 2015): WASM breaks the series on a short IMI,
        // the AASM ignores the later movement and measures the period up to the next candidate.
        val events = (0..7).map { clmAt(100.0 + 3.0 * it, durationMs = 800) }

        val aasmSeries = aasm(events)
        assertThat(aasmSeries).hasSize(1)
        assertThat(aasmSeries.single().clmIndices.toList()).containsExactly(0, 2, 4, 6)
        assertThat(aasmSeries.single().imiSec.toList()).containsExactly(6f, 6f, 6f)

        assertThat(wasm(events)).isEmpty()
    }

    @Test
    @DisplayName("a long LM breaks the series under WASM, not under AASM")
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

        // WASM: break at the long LM -> 3 CLM on each side, no series holds the 4 required.
        assertThat(wasm(events)).isEmpty()
    }

    @Test
    @DisplayName("CLM outside sleep are excluded under AASM v3, kept under WASM")
    fun requirePortionInSleepSeparatesTheRules() {
        val mask = sleepAllNight(150.0) // sleep stops at 150 s
        val events = listOf(100.0, 120.0, 140.0, 160.0, 180.0).map { clmAt(it) }

        val aasmSeries = SeriesBuilder.build(events, mask, FS, SeriesConfig.aasmV3())
        assertThat(aasmSeries).isEmpty() // only 3 CLM fall inside sleep

        val wasmSeries = SeriesBuilder.build(events, mask, FS, SeriesConfig.wasm2016())
        assertThat(wasmSeries).hasSize(1) // 2.4.4: a series may cross a transition
        assertThat(wasmSeries.single().duringSleepFraction).isEqualTo(3f / 5f)
    }

    @Test
    @DisplayName("a truncated event breaks the series and marks the next one as truncated at start")
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
    @DisplayName("an incomplete series truncated by the end of the night is dropped and counted")
    fun incompleteTruncatedSeriesIsDropped() {
        val shortNight = sleepAllNight(100.0)
        val events = listOf(10.0, 30.0, 50.0).map { clmAt(it) }

        val result = SeriesBuilder.buildDetailed(events, shortNight, FS, SeriesConfig.wasm2016())

        assertThat(result.series).isEmpty()
        assertThat(result.truncatedSeriesDropped).isEqualTo(1)
    }
}
