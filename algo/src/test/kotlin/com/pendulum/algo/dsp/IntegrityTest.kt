package com.pendulum.algo.dsp

import com.pendulum.algo.model.IntegrityViolation
import com.pendulum.algo.model.SimpleBlock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IntegrityTest {

    private val fs = 50.0
    private val stepNs = (1e9 / fs).toLong()

    private fun sane(tStartNs: Long, n: Int = 100): SimpleBlock {
        val span = Math.round((n - 1) * 1e9 / fs)
        return SimpleBlock(
            tStartNs, tStartNs + span, 0,
            FloatArray(n), FloatArray(n), FloatArray(n) { 1f },
        )
    }

    @Test
    fun `a healthy session passes with no rejection`() {
        var t = 1_000_000_000L
        val blocks = (0 until 20).map { sane(t).also { b -> t = b.tLastNs + stepNs } }
        val (kept, report) = Integrity.check(blocks, fs)
        assertThat(kept).hasSize(20)
        assertThat(report.blocksRejected).isZero()
        assertThat(report.acceptable).isTrue()
    }

    @Test
    fun `a saturation wraparound is detected by the jerk even if the block is not saturated`() {
        var t = 1_000_000_000L
        val blocks = ArrayList<SimpleBlock>()
        repeat(5) { blocks.add(sane(t).also { t = it.tLastNs + stepNs }) }
        val n = 100
        val x = FloatArray(n)
        // Flip +16 g -> -16 g between two samples: signature of the `toRaw` bug in :format.
        x[50] = 15.99f
        x[51] = -15.99f
        blocks.add(SimpleBlock(t, t + Math.round((n - 1) * 1e9 / fs), 0, x, FloatArray(n), FloatArray(n) { 1f }))

        val (kept, report) = Integrity.check(blocks, fs)
        assertThat(kept).hasSize(5)
        assertThat(report.byViolation[IntegrityViolation.IMPOSSIBLE_JERK]).isEqualTo(1)
    }

    @Test
    fun `a block mostly pinned to the stop is corrupt and not saturated`() {
        var t = 1_000_000_000L
        val blocks = ArrayList<SimpleBlock>()
        repeat(5) { blocks.add(sane(t).also { t = it.tLastNs + stepNs }) }
        val n = 100
        // Constant stop: no jump between neighbours, so the jerk check lets it through.
        // It is indeed the saturation check that must catch the block.
        val z = FloatArray(n) { BlockFlags.SATURATION_G }
        blocks.add(SimpleBlock(t, t + Math.round((n - 1) * 1e9 / fs), 0, FloatArray(n), FloatArray(n), z))

        val (kept, report) = Integrity.check(blocks, fs)
        assertThat(kept).hasSize(5)
        assertThat(report.byViolation[IntegrityViolation.SATURATED]).isEqualTo(1)
        assertThat(report.byViolation[IntegrityViolation.IMPOSSIBLE_JERK] ?: 0).isZero()
    }

    @Test
    fun `a timestamp that goes backwards is rejected`() {
        val b0 = sane(1_000_000_000L)
        val b1 = sane(b0.tLastNs - 5_000_000L) // starts before the end of the previous one
        val (kept, report) = Integrity.check(listOf(b0, b1), fs)
        assertThat(kept).containsExactly(b0)
        assertThat(report.byViolation[IntegrityViolation.NON_MONOTONIC]).isEqualTo(1)
    }

    @Test
    fun `two blocks separated by less than half a sample overlap`() {
        val b0 = sane(1_000_000_000L)
        val b1 = sane(b0.tLastNs + 5_000_000L) // 5 ms, that is a quarter of a sample at 50 Hz
        val (kept, report) = Integrity.check(listOf(b0, b1), fs)
        assertThat(kept).containsExactly(b0)
        assertThat(report.byViolation[IntegrityViolation.OVERLAP]).isEqualTo(1)
    }

    @Test
    fun `a block rate more than 20 percent off nominal is rejected`() {
        val b0 = sane(1_000_000_000L)
        val n = 100
        val bad = SimpleBlock(
            b0.tLastNs + stepNs, b0.tLastNs + stepNs + Math.round((n - 1) * 1e9 / 30.0), 0,
            FloatArray(n), FloatArray(n), FloatArray(n) { 1f },
        )
        val (kept, report) = Integrity.check(listOf(b0, bad), fs)
        assertThat(kept).containsExactly(b0)
        assertThat(report.byViolation[IntegrityViolation.IMPLAUSIBLE_RATE]).isEqualTo(1)
    }

    @Test
    fun `an implausible gravity makes the session suspect without rejecting any block`() {
        var t = 1_000_000_000L
        // Static norm at 0.5 g: wrong scale or out-of-sync decoding.
        val blocks = (0 until 10).map {
            val n = 100
            val b = SimpleBlock(t, t + Math.round((n - 1) * 1e9 / fs), 0, FloatArray(n), FloatArray(n), FloatArray(n) { 0.5f })
            t = b.tLastNs + stepNs
            b
        }
        val (kept, report) = Integrity.check(blocks, fs)
        assertThat(kept).hasSize(10)
        assertThat(report.byViolation[IntegrityViolation.GRAVITY_IMPLAUSIBLE]).isEqualTo(1)
        assertThat(report.decodeSuspect).isTrue()
        assertThat(report.acceptable).isFalse()
    }
}
