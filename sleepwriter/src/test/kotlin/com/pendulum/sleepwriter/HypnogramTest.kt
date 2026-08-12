package com.pendulum.sleepwriter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What these tests protect: the **plausibility** of the hypnogram, not its shape.
 *
 * A hypnogram generator producing an incoherent sequence of stages would pass through Pendulum's
 * chain without exercising anything, and the bench would say "it works". The invariants below are
 * the ones on which what the superposition with movement really tests depends; they are checked
 * here because no emulator test will ever check them.
 */
class HypnogramTest {

    private val start = 1_754_000_000_000L
    private val eightHours = start + 8 * 3_600_000L

    @Test
    fun `the stages cover the window with no hole and no overlap`() {
        val stages = Hypnogram.fullNight(start, eightHours)

        assertThat(stages.first().startMs).isEqualTo(start)
        assertThat(stages.last().endMs).isEqualTo(eightHours)
        stages.zipWithNext().forEach { (a, b) ->
            assertThat(b.startMs).isEqualTo(a.endMs)
        }
        assertThat(stages).allSatisfy { assertThat(it.endMs).isGreaterThan(it.startMs) }
    }

    @Test
    fun `waking never leads straight into deep sleep`() {
        val stages = Hypnogram.fullNight(start, eightHours)
        val wakings = setOf(Hypnogram.WAKE, Hypnogram.AWAKE_IN_BED)

        assertThat(stages.zipWithNext()).noneMatch { (a, b) ->
            a.type in wakings && b.type == Hypnogram.DEEP
        }
    }

    @Test
    fun `REM never immediately follows deep sleep`() {
        val stages = Hypnogram.fullNight(start, eightHours)

        assertThat(stages.zipWithNext()).noneMatch { (a, b) ->
            a.type == Hypnogram.DEEP && b.type == Hypnogram.REM
        }
    }

    @Test
    fun `deep sleep is in the first half of the night and REM in the second`() {
        val stages = Hypnogram.fullNight(start, eightHours)
        val middle = start + (eightHours - start) / 2

        fun duration(type: Int, beforeMiddle: Boolean) = stages
            .filter { it.type == type }
            .sumOf {
                val a = if (beforeMiddle) it.startMs else maxOf(it.startMs, middle)
                val b = if (beforeMiddle) minOf(it.endMs, middle) else it.endMs
                (b - a).coerceAtLeast(0L)
            }

        assertThat(duration(Hypnogram.DEEP, true))
            .isGreaterThan(4 * duration(Hypnogram.DEEP, false))
        assertThat(duration(Hypnogram.REM, false))
            .isGreaterThan(duration(Hypnogram.REM, true))
    }

    /**
     * `SleepSourceSelector` picks the source that has the most distinct stage **types**. A night
     * carrying only two of them would be technically valid and would settle nothing.
     */
    @Test
    fun `a full night carries at least four distinct stage types`() {
        val types = Hypnogram.fullNight(start, eightHours).map { it.type }.distinct()

        assertThat(types).contains(
            Hypnogram.LIGHT,
            Hypnogram.DEEP,
            Hypnogram.REM,
            Hypnogram.AWAKE_IN_BED,
        )
    }

    /**
     * `verdictOf` requires the stages to cover at least 80 % of the session, failing which it
     * returns `HYPNOGRAM_GAPPY`. Coverage being total here, the nominal scenario must never trigger
     * that verdict — if it ever does, it is the chain that lost stages, not the generator.
     */
    @Test
    fun `stage coverage is total`() {
        val stages = Hypnogram.fullNight(start, eightHours)
        val coverage = stages.sumOf { it.endMs - it.startMs }

        assertThat(coverage).isEqualTo(eightHours - start)
    }

    @Test
    fun `a window too short produces no stage rather than a false night`() {
        assertThat(Hypnogram.fullNight(start, start + 5 * 60_000L)).isEmpty()
    }

    /** A long night must not start producing deep sleep again. */
    @Test
    fun `a ten hour night does not manufacture deep sleep at the end of the night`() {
        val tenHours = start + 10 * 3_600_000L
        val stages = Hypnogram.fullNight(start, tenHours)
        val lastHour = tenHours - 3_600_000L

        assertThat(stages.filter { it.startMs >= lastHour })
            .noneMatch { it.type == Hypnogram.DEEP }
    }
}
