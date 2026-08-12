package com.pendulum.phone.health

import com.pendulum.phone.health.SleepSourceSelector.Candidate
import com.pendulum.phone.health.SleepSourceSelector.StageSpan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Deduplication is the most counter-intuitive point of the Health Connect integration:
 * `readRecords()` deduplicates nothing. If two applications have written the night, we receive two
 * overlapping sessions, and concatenating them roughly doubles the sleep time — hence halves the
 * index, without the slightest warning.
 */
class SleepSourceSelectorTest {

    private val nightStart = 1_000_000_000L
    private val nightEnd = nightStart + 8 * 3_600_000L

    private fun stages(from: Long, count: Int, types: List<Int>): List<StageSpan> =
        (0 until count).map {
            StageSpan(from + it * 600_000L, from + (it + 1) * 600_000L, types[it % types.size])
        }

    private fun candidate(
        pkg: String,
        start: Long = nightStart,
        end: Long = nightEnd,
        stageTypes: List<Int> = listOf(4, 5, 6, 1),
        stageCount: Int = 40,
        lastModified: Long = 0L,
    ) = Candidate(
        recordId = "$pkg-rec",
        packageName = pkg,
        startMs = start,
        endMs = end,
        lastModifiedMs = lastModified,
        stages = if (stageCount == 0) emptyList() else stages(start, stageCount, stageTypes),
    )

    @Test
    fun `a single source is kept when two applications write the same night`() {
        val samsung = candidate("com.sec.android.app.shealth")
        val sleepAsAndroid = candidate("com.urbandroid.sleep")

        val s = SleepSourceSelector.select(
            listOf(samsung, sleepAsAndroid), nightStart, nightEnd, preferredPackage = null,
        )

        assertThat(s.chosen).isNotNull()
        assertThat(s.rejected).hasSize(1)
        // We never merge: the kept set plus the rejected set must cover the input exactly.
        assertThat(listOfNotNull(s.chosen) + s.rejected)
            .containsExactlyInAnyOrder(samsung, sleepAsAndroid)
    }

    @Test
    fun `the preferred source wins as soon as it covers half the window`() {
        val preferred = candidate("com.pref", start = nightStart, end = nightStart + 5 * 3_600_000L)
        val other = candidate("com.other")

        val s = SleepSourceSelector.select(
            listOf(other, preferred), nightStart, nightEnd, preferredPackage = "com.pref",
        )

        assertThat(s.chosen?.packageName).isEqualTo("com.pref")
        assertThat(s.reason).isEqualTo("PREFERRED_SOURCE")
    }

    @Test
    fun `the preferred source does not win if it covers almost nothing`() {
        // A 30 min session over an 8 h night: the preferred provider has plainly not synchronised.
        // Giving it priority would make a 30 min denominator and an index sixteen times too big.
        val preferredTooShort = candidate("com.pref", start = nightStart, end = nightStart + 1_800_000L)
        val complete = candidate("com.other")

        val s = SleepSourceSelector.select(
            listOf(preferredTooShort, complete), nightStart, nightEnd, preferredPackage = "com.pref",
        )

        assertThat(s.chosen?.packageName).isEqualTo("com.other")
    }

    @Test
    fun `a real hypnogram beats a duration disguised as a hypnogram`() {
        val durationOnly = candidate("com.duration", stageTypes = listOf(2), stageCount = 40)
        val realHypnogram = candidate("com.stages", stageTypes = listOf(4, 5, 6, 1), stageCount = 40)

        val s = SleepSourceSelector.select(
            listOf(durationOnly, realHypnogram), nightStart, nightEnd, preferredPackage = null,
        )

        assertThat(s.chosen?.packageName).isEqualTo("com.stages")
        assertThat(s.reason).isEqualTo("MORE_STAGES")
    }

    @Test
    fun `STAGE_TYPE_UNKNOWN does not count as a stage`() {
        // A source that fills the field without filling it in must not pass for a hypnogram: this
        // is the "distinctStageTypes = [0]" trap.
        val unknown = candidate("com.unknown", stageTypes = listOf(0), stageCount = 40)
        assertThat(unknown.distinctStageTypes).isEqualTo(0)
    }

    @Test
    fun `on a tie in stages, the longest coverage wins`() {
        val short = candidate("com.short", stageTypes = listOf(4, 5), stageCount = 10)
        val long = candidate("com.long", stageTypes = listOf(4, 5), stageCount = 40)

        val s = SleepSourceSelector.select(
            listOf(short, long), nightStart, nightEnd, preferredPackage = null,
        )
        assertThat(s.chosen?.packageName).isEqualTo("com.long")
    }

    @Test
    fun `the selection is deterministic on a perfect tie`() {
        // Two reads of the same data must choose the same source, whatever order the API returned
        // them in — otherwise two analyses of the same night would give two figures.
        val a = candidate("com.aaa")
        val b = candidate("com.bbb")

        val s1 = SleepSourceSelector.select(listOf(a, b), nightStart, nightEnd, null)
        val s2 = SleepSourceSelector.select(listOf(b, a), nightStart, nightEnd, null)

        assertThat(s1.chosen?.packageName).isEqualTo(s2.chosen?.packageName)
        assertThat(s1.chosen?.packageName).isEqualTo("com.aaa")
    }

    @Test
    fun `an afternoon nap does not concern the night`() {
        val nap = candidate(
            "com.nap",
            start = nightEnd + 6 * 3_600_000L,
            end = nightEnd + 7 * 3_600_000L,
        )
        val s = SleepSourceSelector.select(listOf(nap), nightStart, nightEnd, null)
        assertThat(s.chosen).isNull()
        assertThat(s.reason).isEqualTo("NO_OVERLAPPING_SESSION")
    }

    @Test
    fun `the verdict tells a latency problem from a stages problem`() {
        val tooShort = candidate("com.x", start = nightStart, end = nightStart + 1_800_000L)
        assertThat(SleepSourceSelector.verdictOf(tooShort, nightStart, nightEnd)).isEqualTo("LATENCY")

        val withoutStages = candidate("com.y", stageCount = 0)
        assertThat(SleepSourceSelector.verdictOf(withoutStages, nightStart, nightEnd))
            .isEqualTo("STAGES_MISSING")

        // Stages present but covering less than 80 % of the session: gappy hypnogram.
        val gappy = candidate("com.z", stageTypes = listOf(4, 5, 6), stageCount = 10)
        assertThat(SleepSourceSelector.verdictOf(gappy, nightStart, nightEnd))
            .isEqualTo("HYPNOGRAM_GAPPY")

        val good = candidate("com.ok", stageTypes = listOf(4, 5, 6, 1), stageCount = 48)
        assertThat(SleepSourceSelector.verdictOf(good, nightStart, nightEnd)).isEqualTo("OK")
    }
}
