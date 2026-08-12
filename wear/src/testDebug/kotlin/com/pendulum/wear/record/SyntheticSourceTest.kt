package com.pendulum.wear.record

import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth
import com.pendulum.algo.synth.SleepSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The fidelity of the injector, and nothing else.
 *
 * What these tests protect fits in one sentence: **if the synthetic source manufactures gaps, the
 * bench will measure nothing but the injector.** [GapMonitor] responds to gaps by escalating, each
 * step takes a `PARTIAL_WAKE_LOCK`, and a bench night under a wake lock looks nothing like what we
 * are trying to observe. The central test of this file is therefore the one that checks that a
 * `GapMonitor` fed by the replay sees **no** gap on a continuous signal — while the progression in
 * real time is two hundred and fifty times faster.
 *
 * The nights generated here are short (30 min): the property under test is local — the spacing of
 * two consecutive samples — and nothing makes it any truer over eight hours than over thirty
 * minutes, whereas a full night would cost sixteen times the generation time on every test.
 *
 * **Why `src/testDebug/` and not `src/test/`.** It tests `SyntheticSource`, which lives in
 * `src/debug/`, and it imports `:algo`, declared as `debugImplementation`. From `src/test/` —
 * shared by both variants — it made `:wear:testReleaseUnitTest` uncompilable, hence never run: a
 * test task that has never run protects nothing, and its silence is indistinguishable from a
 * success.
 */
class SyntheticSourceTest {

    private companion object {
        const val PERIOD_NS = 20_000_000L // 50 Hz
        const val SEED = 7L

        /** An arbitrary time base, to check that the rebasing is indeed applied. */
        const val ORIGIN_NS = 987_654_321_000L

        /**
         * Thirty minutes, with no distractor and no gap: intra-SPT awakenings are disabled because
         * they make no sense over half an hour, not because they were in the way.
         */
        val CONTINUOUS_NIGHT = NightSpec(
            durationH = 0.5,
            distractors = DistractorSpec.NONE,
            sleep = SleepSpec(sleepLatencyMin = 2.0, finalWakeMin = 2.0, wasoCount = 0),
        )

        /** The same, with three scheduled gaps of 4 to 5 s. */
        val NIGHT_WITH_GAPS = CONTINUOUS_NIGHT.copy(
            distractors = DistractorSpec.NONE.copy(
                gapCountMin = 3,
                gapCountMax = 3,
                gapMinSec = 4.0,
                gapMaxSec = 5.0,
            ),
        )
    }

    private fun blocks(spec: NightSpec): List<SampleBlock> =
        NightSynth.generate(spec, SEED).blocks

    /** Replays the whole night and returns the bursts in order. */
    private fun replayAll(blocks: List<SampleBlock>, size: Int): List<Burst> {
        val replay = SyntheticReplay(blocks, ORIGIN_NS)
        val bursts = ArrayList<Burst>()
        while (true) bursts += replay.nextBurst(size) ?: break
        return bursts
    }

    private fun allTs(bursts: List<Burst>): LongArray {
        val total = bursts.sumOf { it.n }
        val out = LongArray(total)
        var k = 0
        for (s in bursts) for (i in 0 until s.n) out[k++] = s.tsNs[i]
        return out
    }

    // -------------------------------------------------------------------------------------
    // The test that decides what the bench is worth
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a GapMonitor fed by the replay sees no gap on a continuous signal")
    fun `the injector does not manufacture gaps`() {
        val monitor = GapMonitor(50)
        val bursts = replayAll(blocks(CONTINUOUS_NIGHT), size = 1500)

        // Burst by burst, exactly as the hardware delivers: between two bursts 120 ms of real time
        // elapse, and that arrival time appears nowhere here — because the GapMonitor API does not
        // consume it, and because the replay does not use it either to date its samples.
        for (s in bursts) for (i in 0 until s.n) monitor.onSample(s.tsNs[i])

        assertThat(monitor.gapCount)
            .withFailMessage(
                "The synthetic replay manufactured %d gap(s) on a night that contains none. The " +
                    "escalation would take a PARTIAL_WAKE_LOCK, and the bench would measure " +
                    "nothing but its own injector: every bench night would look degraded, and " +
                    "the failure would look like a capture defect.",
                monitor.gapCount,
            )
            .isZero()
        assertThat(monitor.gapTotalMs).isZero()
        assertThat(monitor.step).isZero()
        assertThat(monitor.consumePendingStep()).isNull()
        assertThat(monitor.measuredRateHz).isCloseTo(50.0, within(0.1))
        assertThat(monitor.rateDeviates).isFalse()
    }

    @Test
    @DisplayName("the gaps the generator scheduled, for their part, do come through the replay")
    fun `the injector does not mask the real gaps`() {
        val bursts = replayAll(blocks(NIGHT_WITH_GAPS), size = 1500)
        val ts = allTs(bursts)

        // An injector that manufactures no gaps but would erase them would be just as useless: the
        // degraded scenarios of the bench rely on gaps known in advance.
        val discontinuities = (1 until ts.size).count { ts[it] - ts[it - 1] > 3 * PERIOD_NS }
        assertThat(discontinuities).isEqualTo(3)

        val monitor = GapMonitor(50)
        for (t in ts) monitor.onSample(t)
        assertThat(monitor.gapCount).isGreaterThanOrEqualTo(3)
        assertThat(monitor.gapTotalMs).isGreaterThanOrEqualTo(12_000)
    }

    // -------------------------------------------------------------------------------------
    // Time base
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("timestamps are strictly increasing and spaced by the nominal period")
    fun `consistent time base`() {
        val ts = allTs(replayAll(blocks(CONTINUOUS_NIGHT), size = 1500))

        assertThat(ts.size).isGreaterThan(80_000) // 30 min at 50 Hz, gaps excluded
        assertThat(ts.first())
            .withFailMessage(
                "The first sample is not rebased on the origin supplied. Real " +
                    "`SensorEvent.timestamp` values share the `elapsedRealtimeNanos` base and " +
                    "the reconstruction on the phone side depends on it to produce a wall-clock " +
                    "time.",
            )
            .isEqualTo(ORIGIN_NS)

        var offNominalIntervals = 0
        for (i in 1 until ts.size) {
            assertThat(ts[i]).isGreaterThan(ts[i - 1])
            if (ts[i] - ts[i - 1] != PERIOD_NS) offNominalIntervals++
        }
        assertThat(offNominalIntervals)
            .withFailMessage(
                "%d interval(s) outside the nominal period on a continuous night. A source whose " +
                    "timestamps followed the accelerated real clock would produce exactly that, " +
                    "and every burst would become a gap.",
                offNominalIntervals,
            )
            .isZero()
    }

    // -------------------------------------------------------------------------------------
    // Delivery by bursts
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("delivery happens in full bursts, the last one excepted, without losing a sample")
    fun `delivery by bursts`() {
        val blocks = blocks(CONTINUOUS_NIGHT)
        val expected = blocks.sumOf { it.x.size }
        val bursts = replayAll(blocks, size = 1500)

        assertThat(bursts.size).isGreaterThan(1)
        // Bursts deliberately ignore the generator's block boundaries: a FIFO flush knows nothing
        // of the slicing a generator has chosen.
        assertThat(bursts.dropLast(1).map { it.n }.distinct()).containsExactly(1500)
        assertThat(bursts.last().n).isBetween(1, 1500)
        assertThat(bursts.sumOf { it.n }).isEqualTo(expected)

        // And the night stops: a source that looped back round would produce a backwards
        // timestamp, which GapMonitor ignores — the bench would then run indefinitely without
        // writing anything.
        val replay = SyntheticReplay(blocks, ORIGIN_NS)
        while (replay.nextBurst(1500) != null) Unit
        assertThat(replay.finished).isTrue()
        assertThat(replay.nextBurst(1500)).isNull()
    }

    // -------------------------------------------------------------------------------------
    // The two constants that are not free
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a burst equals one FIFO flush: 30 s at 50 Hz, that is 1500 events")
    fun `the burst size falls out of the strategy`() {
        val d = SyntheticSource.SIMULATED_SENSOR
        val mode = SensorStrategy.decide(d.wakeUp, d.fifoReserved, RecordingService.RATE_HZ)

        // The simulated sensor is chosen so as to land on the nominal mode of the product: that is
        // the one the bench must exercise, and its report latency fixes the burst size.
        assertThat(mode.kind).isEqualTo(AcquisitionKind.BATCHED_WAKEUP)
        assertThat(mode.maxReportLatencyUs).isEqualTo(30_000_000)
        assertThat(mode.maxReportLatencyUs.toLong() * mode.rateHz / 1_000_000L)
            .withFailMessage(
                "The burst no longer equals 1500 events — the very figure the KDoc of " +
                    "GapMonitor cites to describe a nominal batched delivery.",
            )
            .isEqualTo(1500L)
    }

    @Test
    @DisplayName("the pause between bursts exceeds the flush boundary threshold of the pipeline")
    fun `the pause keeps the boundary observable`() {
        assertThat(SyntheticSource.BURST_PAUSE_MS * 1_000_000L)
            .withFailMessage(
                "The pause between two bursts has fallen below FLUSH_GAP_NS. SensorPipeline will " +
                    "no longer tell two flushes from a continuous stream, FLAG_FIFO_BOUNDARY " +
                    "will disappear from the chunks, and the replay will test a delivery shape " +
                    "the hardware does not produce.",
            )
            .isGreaterThan(SensorPipeline.FLUSH_GAP_NS)
    }
}
