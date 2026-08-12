package com.pendulum.algo.indices

import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.SeriesRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **The output of `SeriesBuilder` goes into `Plmi.compute` exactly as it is.**
 *
 * ### The defect these tests lock down
 *
 * `SeriesBuilder` receives **all** the events, rejects included — it needs them, an `LM_LONG` must
 * break the series at the place where it falls — and therefore indexes its series on the list as
 * it was handed to it. The counts of `Plmi.compute`, for their part, bear on the retained events
 * only. The two bases coincide as long as no event is rejected, and diverge from the first one on.
 *
 * The divergence lived a long time because it showed neither at compile time — both bases are
 * `Int` — nor in the non-regression suite: the harness converted by hand, production did not. The
 * tests were therefore validating a wiring the application did not have, which is the most
 * expensive form of a safety net.
 *
 * ### Why the conversion ended up in `Plmi.compute`
 *
 * A first fix had put the conversion at the producer, leaving it to each caller to invoke it. It
 * did not last a day: out of three callers, two thought of it and the third did not.
 * `Plmi.compute` receives **both** the inputs needed — the complete list and the series — so it is
 * there that the translation is structurally impossible to forget. These tests deliberately call
 * `Plmi.compute` the way a naive caller would: the output of `buildDetailed`, with nothing in
 * between.
 */
class SeriesIndexingTest {

    private val pi = PiResult(periodicityIndex = 0.61, valid = true, totalIntervals = 9, lmRatePerHour = 14.3)
    private val rhythm = Rhythm.estimate(DoubleArray(0))

    /**
     * Two rejects at the head, then four retained movements 22 s apart. The raw indices of the
     * series are therefore 2, 3, 4, 5 for a list of retained events that holds only four: without
     * translation, the last two fall outside the array and the first two designate the wrong
     * movement.
     */
    private fun events() = buildList {
        add(clmAt(600_000L, reject = ClmRejectReason.TOO_SHORT))
        add(clmAt(602_000L, reject = ClmRejectReason.MORPHOLOGY))
        addAll(clmsEvery(startSec = 700.0, stepSec = 22.0, count = 4))
    }

    private fun compute(events: List<com.pendulum.algo.model.Clm>) =
        Plmi.compute(
            clms = events,
            series = SeriesBuilder.buildDetailed(events, maskOf(), FS_HZ, SeriesConfig.aasmV3()).series,
            mask = maskOf(),
            fsHz = FS_HZ,
            rule = SeriesRule.AASM_V3,
            pi = pi,
            rhythm = rhythm,
            floorMode = FloorMode.BILATERAL,
            truncated = false,
        )

    @Test
    fun `the four movements of the series are counted despite two rejects at the head`() {
        val events = events()

        // The premise: the raw base is indeed shifted. It is verified rather than assumed, without
        // which the test could pass for the wrong reason.
        val raw = SeriesBuilder.buildDetailed(events, maskOf(), FS_HZ, SeriesConfig.aasmV3())
        assertThat(raw.series).hasSize(1)
        assertThat(raw.series[0].clmIndices.toList()).containsExactly(2, 3, 4, 5)

        // The figure, in absolute value. Before the fix it was 2: indices 4 and 5 fell outside the
        // array of retained events and disappeared silently.
        val r = compute(events)
        assertThat(r.plmsCount).isEqualTo(4)
        assertThat(r.isolatedCount).isEqualTo(0)
    }

    @Test
    fun `without any reject the result is the same`() {
        val r = compute(clmsEvery(startSec = 700.0, stepSec = 22.0, count = 4))

        assertThat(r.plmsCount).isEqualTo(4)
        assertThat(r.isolatedCount).isEqualTo(0)
    }

    /**
     * The case that tells a translation apart from a simple shift: rejects **between** the
     * retained movements, not only at the head. The correspondence is then no longer a constant.
     */
    @Test
    fun `interleaved rejects shift no count`() {
        val events = buildList {
            add(clmAt(700_000L))
            add(clmAt(710_000L, reject = ClmRejectReason.POSTURAL))
            add(clmAt(722_000L))
            add(clmAt(730_000L, reject = ClmRejectReason.BLIND_ZONE))
            add(clmAt(744_000L))
            add(clmAt(766_000L))
        }

        val r = compute(events)

        assertThat(r.plmsCount).isEqualTo(4)
        assertThat(r.isolatedCount).isEqualTo(0)
    }
}
