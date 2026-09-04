package com.pendulum.phone.work

import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The denominator stops at the last sample.
 *
 * The published index is movements per hour of sleep, and the sleep comes from another device that
 * knows nothing of our grid. A Health Connect session can start before our first sample and — the
 * common case — carry on for hours after our last one, because a watch that died at 3 a.m. does not
 * stop the phone that scores the sleep. That sleep enters no numerator, since no movement can be
 * detected where nothing was recorded, but it used to enter `tstMin`: the index came out divided by
 * as much as two.
 *
 * That is the one direction of error this project cannot afford. An index that reads too high gets
 * checked; an index that reads too low is a reassuring figure that ends a search.
 *
 * The function is four lines and has no dependency on Room or on a `Context`, so there was no
 * excuse for leaving it untested: an inverted `maxOf`/`minOf`, or a `>=` where a `>` belongs, halves
 * the index with no exception and no log — precisely the failure mode the code comment describes.
 */
class ClipToSignalTest {

    private companion object {
        const val HOUR = 3_600_000L
        /** Four hours of recording: the watch died in the middle of an eight-hour night. */
        const val RECORDED_MS = 4 * HOUR
    }

    private fun sleep(startMs: Long, endMs: Long) = SleepWindow(startMs, endMs, Stage.SLEEP)

    private fun clip(vararg windows: SleepWindow) =
        NightAnalyzer.clipToSignal(windows.toList(), RECORDED_MS)

    @Test
    fun `sleep reported after the recording stopped does not enter the denominator`() {
        // The hypnogram covers the whole night; the watch covered its first half.
        val kept = clip(sleep(0, 8 * HOUR))

        assertThat(kept).hasSize(1)
        assertThat(kept.single().endMsRel).isEqualTo(RECORDED_MS)
        // Four hours of sleep counted, not eight: the index is no longer halved.
        assertThat(kept.single().durationMin).isEqualTo(4 * 60.0)
    }

    @Test
    fun `sleep reported before the recording started does not enter it either`() {
        // The sleeper dozed off before strapping the watch on, and the other device saw it.
        val kept = clip(sleep(-2 * HOUR, 3 * HOUR))

        assertThat(kept.single().startMsRel).isZero()
        assertThat(kept.single().durationMin).isEqualTo(3 * 60.0)
    }

    @Test
    fun `a window that straddles an edge is cut, never dropped`() {
        // The recorded part is real sleep and belongs in the denominator: dropping the whole window
        // would err in the other direction and inflate the index.
        val kept = clip(sleep(3 * HOUR, 6 * HOUR))

        assertThat(kept).hasSize(1)
        assertThat(kept.single().startMsRel).isEqualTo(3 * HOUR)
        assertThat(kept.single().endMsRel).isEqualTo(RECORDED_MS)
    }

    @Test
    fun `a window entirely outside the recording disappears`() {
        val kept = clip(sleep(5 * HOUR, 7 * HOUR))

        assertThat(kept).isEmpty()
    }

    @Test
    fun `a window that touches the edge without crossing it is not kept as an empty one`() {
        // `b > a` and not `b >= a`: a zero-length window is not sleep, and letting one through
        // would put an empty stage into the mask.
        assertThat(clip(sleep(RECORDED_MS, 6 * HOUR))).isEmpty()
        assertThat(clip(sleep(-2 * HOUR, 0))).isEmpty()
    }

    @Test
    fun `windows entirely inside the recording are returned untouched`() {
        val a = sleep(HOUR, 2 * HOUR)
        val b = sleep(2 * HOUR + 60_000L, 3 * HOUR)

        assertThat(clip(a, b)).containsExactly(a, b)
    }

    @Test
    fun `the stage of a clipped window is preserved`() {
        // The mask distinguishes sleep from wake in bed; a clip that flattened the stage would
        // silently move minutes from one side of the denominator to the other.
        val kept = NightAnalyzer.clipToSignal(
            listOf(SleepWindow(3 * HOUR, 9 * HOUR, Stage.AWAKE_IN_BED)),
            RECORDED_MS,
        )

        assertThat(kept.single().stage).isEqualTo(Stage.AWAKE_IN_BED)
    }
}
