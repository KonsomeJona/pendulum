package com.pendulum.algo.indices

import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Tests of Ferri's Periodicity Index.
 *
 * Half of these tests bear on the CONVENTION, not on the arithmetic: that is where two
 * contradictory forms circulate (strict or inclusive lower bound, numerator in intervals or in
 * movements) and it is therefore there that non-regression has value.
 */
class PeriodicityTest {

    @Test
    fun `a perfectly periodic night gives an index of 1`() {
        val clms = clmsEvery(startSec = 60.0, stepSec = 22.0, count = 100)

        val d = Periodicity.detail(clms, maskOf(), FS_HZ)

        assertThat(d.pi.periodicityIndex).isCloseTo(1.0, within(1e-12))
        assertThat(d.pi.valid).isTrue()
        assertThat(d.pi.totalIntervals).isEqualTo(99)
        assertThat(d.qualifyingIntervals).isEqualTo(99)
        assertThat(d.runCount).isEqualTo(1)
        assertThat(d.longestRunLength).isEqualTo(99)
        // N / analysable_TST_h = 100 / 7 h
        assertThat(d.pi.lmRatePerHour).isCloseTo(100.0 / 7.0, within(1e-9))
    }

    @Test
    fun `the lower bound is STRICT - an interval of exactly 10 s does not qualify`() {
        val d = Periodicity.fromIntervals(doubleArrayOf(10.0, 10.0, 10.0, 10.0), 5, 10.0)

        assertThat(d.qualifyingIntervals).isEqualTo(0)
        assertThat(d.pi.periodicityIndex).isEqualTo(0.0)

        val justAbove = Periodicity.fromIntervals(doubleArrayOf(10.001, 10.001, 10.001), 4, 10.0)
        assertThat(justAbove.pi.periodicityIndex).isCloseTo(1.0, within(1e-12))
    }

    @Test
    fun `the upper bound is INCLUSIVE - an interval of exactly 90 s qualifies`() {
        val d = Periodicity.fromIntervals(doubleArrayOf(90.0, 90.0, 90.0), 4, 10.0)

        assertThat(d.qualifyingIntervals).isEqualTo(3)
        assertThat(d.pi.periodicityIndex).isCloseTo(1.0, within(1e-12))

        val tooLong = Periodicity.fromIntervals(doubleArrayOf(90.001, 90.001, 90.001), 4, 10.0)
        assertThat(tooLong.pi.periodicityIndex).isEqualTo(0.0)
    }

    @Test
    fun `the numerator counts INTERVALS and only those of sequences of at least 3`() {
        // qualifying: Y Y N Y Y Y N  -> one sequence of 2 (ignored) and one of 3 (counted)
        val imi = doubleArrayOf(22.0, 22.0, 200.0, 22.0, 22.0, 22.0, 300.0)

        val d = Periodicity.fromIntervals(imi, sleepClmCount = 8, analysableSleepMin = 10.0)

        assertThat(d.qualifyingIntervals).isEqualTo(5)
        assertThat(d.intervalsInCountedRuns).isEqualTo(3)
        assertThat(d.runCount).isEqualTo(1)
        assertThat(d.longestRunLength).isEqualTo(3)
        // 3 intervals retained out of 7 — and above all NOT 4 movements out of 8, which would be
        // the other form published by Ferri. The two must never be mixed.
        assertThat(d.pi.periodicityIndex).isCloseTo(3.0 / 7.0, within(1e-12))
        assertThat(d.convention).isEqualTo(PiConvention.FERRI_INTERVALS_LOW_EXCLUSIVE)
        assertThat(Periodicity.CONVENTION_DOC).contains("INTERVALS")
    }

    @Test
    fun `a sequence of two intervals does not count at all`() {
        val d = Periodicity.fromIntervals(doubleArrayOf(22.0, 22.0, 500.0, 22.0), 5, 10.0)

        assertThat(d.qualifyingIntervals).isEqualTo(3)
        assertThat(d.intervalsInCountedRuns).isEqualTo(0)
        assertThat(d.pi.periodicityIndex).isEqualTo(0.0)
    }

    @Test
    fun `below 10 movements per hour the index is computed but declared uninterpretable`() {
        // 5 CLM over 1 h: perfect PI, but the N-1 denominator is too small to mean anything at
        // all (Drakatos 2021). This is exactly the instability of the control group.
        val d = Periodicity.fromIntervals(doubleArrayOf(22.0, 22.0, 22.0, 22.0), 5, 60.0)

        assertThat(d.pi.periodicityIndex).isCloseTo(1.0, within(1e-12))
        assertThat(d.pi.lmRatePerHour).isCloseTo(5.0, within(1e-9))
        assertThat(d.pi.valid).isFalse()
    }

    @Test
    fun `the index itself depends on NO temporal denominator`() {
        // This is the central argument of §5 of SPEC-v2: the circularity of the denominator
        // disappears for the follow-up metric. Only the rate guard rail sees sleep time.
        val clms = clmsEvery(startSec = 60.0, stepSec = 22.0, count = 100)

        val short = Periodicity.detail(clms, maskOf(analysableTstMin = 200.0), FS_HZ)
        val long = Periodicity.detail(clms, maskOf(analysableTstMin = 420.0), FS_HZ)

        assertThat(short.pi.periodicityIndex).isEqualTo(long.pi.periodicityIndex)
        assertThat(short.pi.lmRatePerHour).isNotEqualTo(long.pi.lmRatePerHour)
        assertThat(short.pi.valid).isTrue()
        assertThat(long.pi.valid).isTrue()
    }

    @Test
    fun `only the retained CLM during sleep enter the computation`() {
        val windows = listOf(
            SleepWindow(0L, 3_600_000L, Stage.SLEEP),
            SleepWindow(3_600_000L, 7_200_000L, Stage.AWAKE_IN_BED),
            SleepWindow(7_200_000L, 25_200_000L, Stage.SLEEP),
        )
        val clms = buildList {
            addAll(clmsEvery(startSec = 60.0, stepSec = 22.0, count = 50))          // sleep
            add(clmAt(4_000_000L))                                                   // wake: excluded
            add(clmAt(4_022_000L))                                                   // wake: excluded
            add(clmAt(8_000_000L, reject = ClmRejectReason.POSTURAL))                // rejected: excluded
            addAll(clmsEvery(startSec = 9_000.0, stepSec = 22.0, count = 50))        // sleep
        }

        val d = Periodicity.detail(clms, maskOf(windows = windows), FS_HZ)

        assertThat(d.sleepClmCount).isEqualTo(100)
        assertThat(d.totalIntervals).isEqualTo(99)
        // 98 intervals internal to the two bursts + 1 very long joining interval, not qualifying.
        assertThat(d.qualifyingIntervals).isEqualTo(98)
        assertThat(d.pi.periodicityIndex).isCloseTo(98.0 / 99.0, within(1e-12))
    }

    @Test
    fun `no interval - index zero and not valid, without throwing`() {
        val d = Periodicity.fromIntervals(DoubleArray(0), 1, 420.0)

        assertThat(d.pi.periodicityIndex).isEqualTo(0.0)
        assertThat(d.pi.totalIntervals).isEqualTo(0)
        assertThat(d.pi.valid).isFalse()
    }
}
