package com.pendulum.phone.health

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The summary of the sources, as step 4 of onboarding displays it.
 *
 * What these tests hold: the number announced is a number of **nights**, not of sessions. An
 * application that republishes the same night three times is not a source covering three nights,
 * and the opposite would make a chatty source get picked over a regular one — hence would change
 * the denominator of the index with no way for the user to notice it.
 */
class SleepSourcesTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun instant(day: Int, hour: Int): Long =
        ZonedDateTime.of(2026, 3, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun session(
        pkg: String,
        day: Int,
        hour: Int = 23,
        stages: List<Int> = emptyList(),
        id: String = "$pkg-$day-$hour",
    ) = SleepSourceSelector.Candidate(
        recordId = id,
        packageName = pkg,
        startMs = instant(day, hour),
        endMs = instant(day, hour) + 7 * 3_600_000L,
        lastModifiedMs = instant(day, hour),
        stages = stages.mapIndexed { i, type ->
            SleepSourceSelector.StageSpan(
                startMs = instant(day, hour) + i * 3_600_000L,
                endMs = instant(day, hour) + (i + 1) * 3_600_000L,
                stageType = type,
            )
        },
    )

    @Test
    fun `no session, no source`() {
        assertThat(SleepSources.summarise(emptyList(), zone)).isEmpty()
    }

    @Test
    fun `two sources, each with its own nights`() {
        val summary = SleepSources.summarise(
            listOf(
                session("com.sec.android.app.shealth", 10, stages = listOf(4, 5, 6)),
                session("com.sec.android.app.shealth", 11, stages = listOf(4, 5, 6)),
                session("com.urbandroid.sleep", 11),
            ),
            zone,
        )

        assertThat(summary.map { it.packageName })
            .containsExactly("com.sec.android.app.shealth", "com.urbandroid.sleep")
        assertThat(summary[0].nights).isEqualTo(2)
        assertThat(summary[0].hasStages).isTrue()
        assertThat(summary[1].nights).isEqualTo(1)
        assertThat(summary[1].hasStages).isFalse()
    }

    @Test
    fun `three republications of the same night count for one night`() {
        // The point of the test. Three records, a single evening: 10 pm and 11.30 pm are the same
        // evening, and 6 in the morning still belongs to the previous night — this is the noon
        // rollover of the night key, the same one as the evening context's.
        val summary = SleepSources.summarise(
            listOf(
                session("app", 10, hour = 22, id = "a"),
                session("app", 10, hour = 23, id = "b"),
                session("app", 11, hour = 6, id = "c"),
            ),
            zone,
        )

        assertThat(summary).hasSize(1)
        assertThat(summary.single().nights).isEqualTo(1)
    }

    @Test
    fun `a single night with stages is enough to announce stages`() {
        val summary = SleepSources.summarise(
            listOf(
                session("app", 10),
                session("app", 11, stages = listOf(4, 5)),
            ),
            zone,
        )
        assertThat(summary.single().hasStages).isTrue()
    }

    @Test
    fun `an unknown stage does not make a hypnogram`() {
        // `STAGE_TYPE_UNKNOWN` fills nothing in: a source that writes only that one returns a
        // duration disguised as a hypnogram, and the screen must say "duration only".
        val summary = SleepSources.summarise(
            listOf(
                session(
                    "app", 10,
                    stages = listOf(
                        SleepSourceSelector.STAGE_TYPE_UNKNOWN,
                        SleepSourceSelector.STAGE_TYPE_UNKNOWN,
                    ),
                ),
            ),
            zone,
        )
        assertThat(summary.single().hasStages).isFalse()
    }

    @Test
    fun `the order is total, hence stable from one display to the next`() {
        // Two sources tied on nights and on stages: the package name is what settles it. Without
        // that last criterion, two openings of onboarding could offer the same rows in a different
        // order, and one taps the row next to the intended one.
        val summary = SleepSources.summarise(
            listOf(session("b.app", 10), session("a.app", 10)),
            zone,
        )
        assertThat(summary.map { it.packageName }).containsExactly("a.app", "b.app")
    }
}
