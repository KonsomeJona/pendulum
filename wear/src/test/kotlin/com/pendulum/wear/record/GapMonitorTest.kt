package com.pendulum.wear.record

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Timing integrity as seen by [GapMonitor], and above all the rule that is not negotiable:
 * a gap is measured on `SensorEvent.timestamp` differences, never on arrival time.
 *
 * The first test in this file is the one that protects the behaviour that is most costly to break.
 * If somebody one day reintroduces a detection founded on delivery time, every batched burst
 * becomes a "gap" again, the escalation takes a wake lock, and every test night silently burns
 * 65 % of the battery — the failure then looks like "batching does not work", when it is the
 * monitor that is wrong.
 */
class GapMonitorTest {

    private companion object {
        const val PERIOD = 20_000_000L // 50 Hz
        const val T0 = 1_000_000_000L
    }

    /**
     * Simulates the feed seen by `onSensorChanged`: only sensor timestamps exist.
     * [flagged] counts the samples marked `FLAG_GAP_BEFORE`.
     */
    private class Feed(val monitor: GapMonitor, startNs: Long, val periodNs: Long = PERIOD) {
        var ts = startNs
            private set
        var flagged = 0
            private set

        init {
            monitor.onSample(ts)
        }

        /** Regular samples up to [targetNs] inclusive (a multiple of the period is expected). */
        fun regularUntil(targetNs: Long) {
            while (ts < targetNs) {
                ts += periodNs
                if (monitor.onSample(ts)) flagged++
            }
        }

        /** Sensor silence of [durationNs], then the sample that closes it. */
        fun hole(durationNs: Long) {
            ts += durationNs
            if (monitor.onSample(ts)) flagged++
        }
    }

    // -------------------------------------------------------------------------------------
    // The non-negotiable rule
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a batched burst — 30 s of silence then 1500 events at once — is not a gap")
    fun `nominal batching triggers nothing`() {
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)

        // Two 30 s batches delivered "at once": the tight loop below IS the burst. Arrival time
        // appears nowhere, because GapMonitor's API does not consume it — it is the signature
        // itself that locks the rule in. A future monitor that wanted to look at delivery time
        // would have to break this test in order to do so.
        feed.regularUntil(T0 + 30_000_000_000L) // batch 1: 1500 events, regular timestamps
        feed.regularUntil(T0 + 60_000_000_000L) // batch 2, after 30 s of delivery silence

        assertThat(monitor.gapCount)
            .withFailMessage(
                "Nominal batching operation was counted as %d gap(s). Direct consequence: the " +
                    "escalation takes a PARTIAL_WAKE_LOCK on a healthy night, the battery falls " +
                    "to 35 %%, and the battery-life measurement of the test phase is invalidated " +
                    "without any error message.",
                monitor.gapCount,
            )
            .isZero()
        assertThat(monitor.gapTotalMs).isZero()
        assertThat(feed.flagged).isZero()
        assertThat(monitor.step).isZero()
        assertThat(monitor.consumePendingStep()).isNull()
        // The 60 s window closed along the way: the measured fs is nominal.
        assertThat(monitor.measuredRateHz).isCloseTo(50.0, within(0.1))
        assertThat(monitor.rateDeviates).isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Intra-batch signal: threshold at 3 times the nominal period
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("an interval of exactly 3 periods passes; one nanosecond more is a gap")
    fun `exact bound of the intra-batch threshold`() {
        val monitor = GapMonitor(50)
        monitor.onSample(T0)

        // 60 ms exactly: the delivery jitter of a real sensor commonly reaches two periods;
        // counting a gap here would drown the log in false positives.
        assertThat(monitor.onSample(T0 + 3 * PERIOD)).isFalse()
        assertThat(monitor.gapCount).isZero()

        // 60 ms + 1 ns: the gap is real, and the sample that follows it must carry the
        // FLAG_GAP_BEFORE flag — it is what will let the analysis exclude the block.
        assertThat(monitor.onSample(T0 + 3 * PERIOD + 3 * PERIOD + 1)).isTrue()
        assertThat(monitor.gapCount).isEqualTo(1)
        // Missing duration = dt minus the expected period: 60.000001 - 20 = 40 ms.
        assertThat(monitor.gapTotalMs).isEqualTo(40)
    }

    // -------------------------------------------------------------------------------------
    // Escalation: three big gaps in ten minutes
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("three 3 s gaps in less than ten minutes climb one step, not before")
    fun `escalation on the third big gap`() {
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)

        // Three gaps of exactly 3 s (the inclusive bound of a "big gap"), each one in a different
        // measurement window so that only the intra-batch signal sees them: 3 s missing out of
        // 60 s leave 2851 samples received for 2850 required — just above the 0.95 deficit
        // threshold, which checks that bound too along the way.
        feed.regularUntil(T0 + 10_000_000_000L)
        feed.hole(3_000_000_000L) // gap 1, at t=13 s
        assertThat(monitor.step).isZero()

        feed.regularUntil(T0 + 70_000_000_000L)
        feed.hole(3_000_000_000L) // gap 2, at t=73 s
        assertThat(monitor.step)
            .withFailMessage(
                "Two big gaps were enough to escalate. The wake lock of step 1 must be earned: " +
                    "a device that loses two isolated batches during the night does not justify " +
                    "sacrificing the battery life of all the remaining hours.",
            )
            .isZero()

        feed.regularUntil(T0 + 130_000_000_000L)
        feed.hole(3_000_000_000L) // gap 3, at t=133 s: all three fit within 10 min

        assertThat(monitor.gapCount).isEqualTo(3)
        assertThat(feed.flagged).isEqualTo(3)
        assertThat(monitor.step).isEqualTo(1)
        assertThat(monitor.consumePendingStep()).isEqualTo(1)
        // The step is consumed only once: the service must not re-apply the same degradation on
        // every turn of its loop.
        assertThat(monitor.consumePendingStep()).isNull()
    }

    @Test
    @DisplayName("gaps more than ten minutes apart never add up")
    fun `the escalation window slides`() {
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)

        feed.regularUntil(T0 + 10_000_000_000L)
        feed.hole(3_000_000_000L) // gap 1, at t=13 s
        feed.regularUntil(T0 + 70_000_000_000L)
        feed.hole(3_000_000_000L) // gap 2, at t=73 s
        feed.regularUntil(T0 + 612_000_000_000L)
        feed.hole(3_000_000_000L) // gap 3, at t=615 s: gap 1 has left the window

        // A whole night inevitably accumulates a few isolated gaps. If they counted for ever, any
        // sufficiently long night would end up under a wake lock — the escalation would no longer
        // be answering a failure but merely duration.
        assertThat(monitor.gapCount).isEqualTo(3)
        assertThat(monitor.step).isZero()
        assertThat(monitor.consumePendingStep()).isNull()
    }

    @Test
    @DisplayName("the escalation never goes back down and caps at step 3")
    fun `monotonic and capped escalation`() {
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)
        val steps = mutableListOf<Int>()

        // A continuous degradation: big gaps one after another. It does not matter here that some
        // of them are also counted by the window deficit — what is checked is the trajectory of
        // the steps, not the counting.
        repeat(30) {
            feed.regularUntil(feed.ts + 1_000_000_000L)
            feed.hole(3_000_000_000L)
            monitor.consumePendingStep()?.let { steps += it }
        }

        assertThat(steps)
            .withFailMessage(
                "The steps emitted are %s. They must climb strictly — 1 then 2 then 3 — and stop " +
                    "there: a step that goes back down or repeats makes the service oscillate " +
                    "between two modes all night long, one chunk rotation each time.",
                steps,
            )
            .isEqualTo(listOf(1, 2, 3))
        assertThat(monitor.step).isEqualTo(3)
    }

    // -------------------------------------------------------------------------------------
    // Window signal: deficit over 60 s of sensor time
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a sensor delivering 40 Hz for 50 is seen by the window, not by the intra-batch")
    fun `window deficit without an intra-batch gap`() {
        val monitor = GapMonitor(50)
        // Intervals of 25 ms: each one is very far from the 60 ms intra-batch threshold, yet 20 %
        // of the samples are missing. Without the window signal, this night would pass for
        // healthy and every filter of the analysis would run with an fs wrong by 20 %.
        val feed = Feed(monitor, T0, periodNs = 25_000_000L)
        feed.regularUntil(T0 + 60_000_000_000L)

        assertThat(feed.flagged).isZero() // the intra-batch signal sees nothing: that is the point
        assertThat(monitor.gapCount).isEqualTo(1)
        // 3000 expected, 2401 received: 599 missing at 20 ms each = 11,980 ms.
        assertThat(monitor.gapTotalMs).isEqualTo(11_980)
        assertThat(monitor.measuredRateHz).isCloseTo(40.0, within(0.1))
        assertThat(monitor.rateDeviates)
            .withFailMessage(
                "fs measured at 40 Hz for a nominal 50 without rateDeviates being raised. A " +
                    "wrong fs shifts every movement duration of the analysis chain by 20 %%.",
            )
            .isTrue()
    }

    @Test
    @DisplayName("the measured fs stays at zero as long as no 60 s window has closed")
    fun `measured fs before the first window`() {
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)
        feed.regularUntil(T0 + 59_000_000_000L)

        // An fs "measured" over three seconds of data would be a lie of precision: 0 honestly
        // says "no measurement yet", and the caller can display it as it is.
        assertThat(monitor.measuredRateHz).isEqualTo(0.0)

        feed.regularUntil(T0 + 60_000_000_000L)
        assertThat(monitor.measuredRateHz).isCloseTo(50.0, within(0.1))
        assertThat(monitor.rateDeviates).isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Dispersion: what the mean does not say
    // -------------------------------------------------------------------------------------

    /** Alternates two intervals whose mean is exactly the nominal period. */
    private fun alternatingFeed(monitor: GapMonitor, shortNs: Long, longNs: Long, untilNs: Long) {
        var t = T0
        monitor.onSample(t)
        var i = 0
        while (t < untilNs) {
            t += if (i % 2 == 0) shortNs else longNs
            monitor.onSample(t)
            i++
        }
    }

    @Test
    @DisplayName("a perfect rate has zero dispersion")
    fun `zero dispersion on a regular rate`() {
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)
        feed.regularUntil(T0 + 60_000_000_000L)

        // An inverted assertion: a dispersion that was never zero would no longer distinguish
        // anything. And the difference-of-moments computation must give 0, not a cancellation NaN.
        assertThat(monitor.jitterStdUs).isEqualTo(0.0)
        assertThat(monitor.maxIntervalUs).isEqualTo(20_000L)
    }

    @Test
    @DisplayName("a rate alternating 10 and 30 ms yields a perfect fs — and a dispersion of 10 ms")
    fun `the mean does not see the jitter`() {
        val monitor = GapMonitor(50)
        alternatingFeed(monitor, 10_000_000L, 30_000_000L, T0 + 60_000_000_000L)

        // The point of this whole mechanism, in three lines: everything the monitor could say
        // before is **green**. Exactly 50 Hz, no deviation, no gap.
        assertThat(monitor.measuredRateHz).isCloseTo(50.0, within(0.1))
        assertThat(monitor.rateDeviates).isFalse()
        assertThat(monitor.gapCount).isZero()

        // And yet every sample is dated only to within 10 ms. The format has no per-sample
        // timestamp: it interpolates linearly between `tFirstNs` and `tLastNs`, and that
        // interpolation is wrong in proportion to how dispersed the intervals are. It is this
        // figure, and it alone, that says whether a movement was dated or merely located.
        assertThat(monitor.jitterStdUs).isCloseTo(10_000.0, within(1.0))
        assertThat(monitor.maxIntervalUs).isEqualTo(30_000L)
    }

    @Test
    @DisplayName("the dispersion of one window does not spill over into the next")
    fun `dispersion restarts from zero at each window`() {
        val monitor = GapMonitor(50)
        alternatingFeed(monitor, 10_000_000L, 30_000_000L, T0 + 60_000_000_000L)
        assertThat(monitor.jitterStdUs).isGreaterThan(1_000.0)

        // Second window, regular. Without resetting the accumulators, the telemetry would keep
        // announcing a jitter that has been gone for a minute — a failure that has been resolved
        // but stays on display is as misleading as a failure that was missed.
        var t = T0 + 60_000_000_000L
        while (t < T0 + 120_000_000_000L) {
            t += PERIOD
            monitor.onSample(t)
        }
        assertThat(monitor.jitterStdUs).isEqualTo(0.0)
        assertThat(monitor.maxIntervalUs).isEqualTo(20_000L)
    }

    @Test
    @DisplayName("a big gap does not overflow the variance accumulator")
    fun `no overflow on a gap of several seconds`() {
        // The sum of squares is done in microseconds and not in nanoseconds: in nanoseconds, a
        // 3 s gap is worth 9e18, within a factor of 1.03 of the largest Long, and a single one
        // was enough to yield a negative variance — hence a NaN standard deviation.
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)
        feed.regularUntil(T0 + 10_000_000_000L)
        feed.hole(30_000_000_000L)
        feed.regularUntil(T0 + 61_000_000_000L)

        assertThat(monitor.jitterStdUs).isNotNaN().isGreaterThan(0.0)
        assertThat(monitor.maxIntervalUs).isEqualTo(30_000_000L)
    }

    @Test
    @DisplayName("the last sensor timestamp is readable, and reset to zero by a re-registration")
    fun `last timestamp exposed`() {
        // It is what anchors a telemetry point on the samples' time base: without it, aligning
        // "the temperature dropped" with "this movement was rejected" would go through a clock
        // conversion that the measurement of 3 August 2026 shows to drift.
        val monitor = GapMonitor(50)
        assertThat(monitor.lastTimestampNs).isZero()
        monitor.onSample(T0)
        monitor.onSample(T0 + PERIOD)
        assertThat(monitor.lastTimestampNs).isEqualTo(T0 + PERIOD)

        monitor.onRateChanged(25)
        assertThat(monitor.lastTimestampNs).isZero()
    }

    // -------------------------------------------------------------------------------------
    // Sensor re-registration
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("after onRateChanged, the discontinuity of the re-registration is not counted")
    fun `re-registration without a self-inflicted gap`() {
        val monitor = GapMonitor(50)
        monitor.onSample(T0)
        monitor.onSample(T0 + PERIOD)

        monitor.onRateChanged(25)

        // The re-registration breaks continuity: the first sample of the new regime arrives much
        // later, and that silence is our doing, not the sensor's.
        val t1 = T0 + 100_000_000_000L
        assertThat(monitor.onSample(t1)).isFalse()
        assertThat(monitor.gapCount).isZero()

        // And the threshold follows the new period: 100 ms passes at 25 Hz (threshold 120 ms)
        // where it was a gap at 50 Hz — otherwise step 3 would punish itself.
        assertThat(monitor.onSample(t1 + 100_000_000L)).isFalse()
        assertThat(monitor.onSample(t1 + 100_000_000L + 121_000_000L)).isTrue()
    }

    // -------------------------------------------------------------------------------------
    // Double counting, and the two defects found along with it
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a physical gap is counted only once, even when the window sees it too")
    fun `no double counting of a big gap`() {
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)

        // A single gap of 25 s. The intra-batch signal counts it on arrival; the 60 s window then
        // observes the same deficit, and must recognise that it has already been attributed.
        feed.regularUntil(T0 + 10_000_000_000L)
        feed.hole(25_000_000_000L)
        feed.regularUntil(T0 + 60_000_000_000L)

        // One physical gap, one entry. Before the fix there were two: `gapCount` and `gapTotalMs`
        // doubled, and above all **two** physical gaps within a single window were enough to
        // escalate where the rule announces three.
        //
        // This was not a cosmetic imprecision. Every step takes a `PARTIAL_WAKE_LOCK`: escalating
        // one and a half times too fast means spending the night under wake lock, spending 65 %
        // of the battery and invalidating the battery-life measurement of phase P1 — the exact
        // cost that the KDoc of `GapMonitor` says it wants to avoid.
        assertThat(monitor.gapCount).isEqualTo(1)
        assertThat(monitor.step).isZero()

        // Two physical gaps: still no escalation. The rule of three holds.
        feed.regularUntil(T0 + 70_000_000_000L)
        feed.hole(25_000_000_000L)
        assertThat(monitor.step).isZero()

        // Three: it climbs, and not before.
        feed.regularUntil(T0 + 130_000_000_000L)
        feed.hole(25_000_000_000L)
        assertThat(monitor.step).isEqualTo(1)
        assertThat(monitor.consumePendingStep()).isEqualTo(1)
    }

    @Test
    @DisplayName("a sensor faster than its nominal rate does not make the lost time GO BACKWARDS")
    fun `no negative deficit`() {
        val monitor = GapMonitor(50)

        // 51 Hz delivered for 50 requested: the window receives more samples than expected.
        // Before the fix, `expected - windowCount` was negative and `gapTotalMs` **decreased** —
        // the lost-time counter started winning time back, which no reading detects.
        val period = 1_000_000_000L / 51
        var t = T0
        repeat(3_500) {
            monitor.onSample(t)
            t += period
        }

        assertThat(monitor.gapTotalMs).isGreaterThanOrEqualTo(0)
        assertThat(monitor.gapCount).isZero()
    }

    @Test
    @DisplayName("a timestamp that goes backwards is ignored, it does not corrupt the window")
    fun `backwards timestamp`() {
        val monitor = GapMonitor(50)
        val feed = Feed(monitor, T0)
        feed.regularUntil(T0 + 30_000_000_000L)
        val countsBefore = monitor.gapCount

        // Android's sensor layers sometimes make the timestamps restart backwards in batched
        // mode. Letting the sample through made `spanNs` negative when the window closed, so
        // `expected` too, so the deficit never fired again — and `windowStartNs` restarted in the
        // past, which the next window did not recover from.
        assertThat(monitor.onSample(T0 + 10_000_000_000L)).isFalse()
        assertThat(monitor.gapCount).isEqualTo(countsBefore)

        // The normal sequence resumes with no after-effects.
        feed.regularUntil(T0 + 65_000_000_000L)
        assertThat(monitor.measuredRateHz).isCloseTo(50.0, within(2.0))
    }
}
