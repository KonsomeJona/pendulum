package com.pendulum.algo.mask

import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

private const val NIGHT_SEC = 8.0 * 3600.0
private const val NIGHT_MIN = 480.0

/** Burst plainly above `moveFactor x floor` (6 x 0.004 = 0.024 g). */
private const val MOVE_G = 0.10f

class ImmobilityMaskTest {

    private fun build(
        night: Night,
        ignoreIntervals: List<Segment> = emptyList(),
        offBody: List<Segment> = emptyList(),
        blindZones: List<Segment> = emptyList(),
        diary: DiaryWindow? = null,
        cfg: ImmobilityConfig = ImmobilityConfig(),
    ): SleepMask = ImmobilityMask.build(
        gravity = night.gravity(),
        env = night.env(),
        floor = night.floor(),
        segments = night.segments(),
        offBody = offBody,
        ignoreIntervals = ignoreIntervals,
        diary = diary,
        cfg = cfg,
        blindZones = blindZones,
    )

    @Test
    fun `night without any movement - plausible SPT and TST equal to the SPT`() {
        val mask = build(Night(NIGHT_SEC))

        assertThat(mask.sptMin).isCloseTo(NIGHT_MIN, within(0.1))
        assertThat(mask.tstMin).isCloseTo(NIGHT_MIN, within(0.1))
        assertThat(mask.wasoMin).isCloseTo(0.0, within(1e-9))
        assertThat(mask.windows).hasSize(1)
        assertThat(mask.windows[0].stage).isEqualTo(Stage.SLEEP)
        assertThat(mask.source).isEqualTo(MaskSource.ACCEL_IMMOBILITY)
    }

    /**
     * The disqualifying failure mode of §3.6.3, made explicit. This test **documents** the naive
     * behaviour; it is not here to endorse that behaviour but so that its disappearance (next
     * test) reads as a result and not as a coincidence.
     *
     * A series at IMI 22 s places ~13 movements in any 5 min window: no stretch of sustained
     * inactivity can exist, so **the whole night is scored as wake**.
     */
    @Test
    fun `dense periodic series - without neutralisation the mask collapses`() {
        val night = Night(NIGHT_SEC)
        night.periodic(startSec = 10.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 1300)

        val mask = build(night)

        assertThat(mask.tstMin).isEqualTo(0.0)
        assertThat(mask.sptMin).isEqualTo(0.0)
    }

    /**
     * **The test that guards against the disqualifying failure mode.** Same data, same
     * parameters; only layer 1 changes: periodic movement intervals stop being evidence of wake.
     * The most severely affected subject must become again the one whose sleep is measured, not
     * the one who is refused any.
     */
    @Test
    fun `dense periodic series - neutralised it is NOT scored as wake`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 10.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 1300)

        val mask = build(night, ignoreIntervals = clms)

        assertThat(mask.sptMin).isCloseTo(NIGHT_MIN, within(0.1))
        assertThat(mask.tstMin).isGreaterThan(0.98 * NIGHT_MIN)
        assertThat(mask.windows.filter { it.stage != Stage.SLEEP }).isEmpty()
    }

    @Test
    fun `large body movement - remains evidence of wake`() {
        val night = Night(NIGHT_SEC)
        night.tilt(atSec = 4.0 * 3600.0, deg = 40.0)

        val mask = build(night)

        assertThat(mask.wasoMin).isGreaterThan(0.0)
        assertThat(mask.windows.map { it.stage }).contains(Stage.AWAKE_IN_BED)
        // An isolated reorientation must not for all that amputate the SPT: its bounds depend only
        // on the two extreme transitions of the night.
        assertThat(mask.sptMin).isCloseTo(NIGHT_MIN, within(0.1))
    }

    /**
     * Guard rail of layer 1. Even if a periodic movement is detected at the exact moment of a turn
     * in bed, the persistent reorientation remains evidence of wake: it is the only one really
     * available, and erasing it on a coincidence of timing would be the worst possible trade.
     */
    @Test
    fun `posture guard rail - a large reorientation is never neutralised`() {
        val night = Night(NIGHT_SEC)
        val at = 4.0 * 3600.0
        night.tilt(atSec = at, deg = 40.0)
        val covering = listOf(Segment(samples(at - 10.0), samples(at + 20.0)))

        val mask = build(night, ignoreIntervals = covering)

        assertThat(mask.wasoMin).isGreaterThan(0.0)
        assertThat(mask.windows.map { it.stage }).contains(Stage.AWAKE_IN_BED)
    }

    /**
     * Invariance under rotation of the case: the angular Delta bears on the unit gravity vector,
     * therefore on a quantity that names no axis. A night replayed with the strap turned must
     * produce the **same** mask — this is the property for which van Hees was preferred, and the
     * only one that makes two nights comparable when the way the watch is worn changes.
     */
    @Test
    fun `invariance under rotation of the case`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 10.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 600)
        night.tilt(atSec = 5.0 * 3600.0, deg = 40.0)

        val direct = ImmobilityMask.build(
            night.gravity(), night.env(), night.floor(), night.segments(), emptyList(), clms,
        )
        val rotated = ImmobilityMask.build(
            rotate(night.gravity(), 1.0, 2.0, 3.0, 37.0),
            night.env(), night.floor(), night.segments(), emptyList(), clms,
        )

        assertThat(rotated.windows).isEqualTo(direct.windows)
        assertThat(rotated.sptMin).isEqualTo(direct.sptMin)
        assertThat(rotated.tstMin).isEqualTo(direct.tstMin)
        assertThat(rotated.wasoMin).isEqualTo(direct.wasoMin)
    }

    @Test
    fun `an accelerometer mask is always CIRCULAR, diary included`() {
        val night = Night(NIGHT_SEC)

        val plain = build(night)
        val withDiary = build(night, diary = DiaryWindow(3_600_000L, 25_200_000L))

        assertThat(plain.independence).isEqualTo(DenominatorIndependence.CIRCULAR)
        assertThat(withDiary.independence).isEqualTo(DenominatorIndependence.CIRCULAR)
    }

    @Test
    fun `the diary bounds the search for the SPT without providing the denominator`() {
        val night = Night(NIGHT_SEC)

        val mask = build(night, diary = DiaryWindow(3_600_000L, 25_200_000L))

        assertThat(mask.sptMin).isCloseTo(360.0, within(0.2))
        assertThat(mask.tstMin).isCloseTo(360.0, within(0.2))
    }

    @Test
    fun `the analysable denominator removes the blind zones from the TST`() {
        val night = Night(NIGHT_SEC)
        val blind = listOf(Segment(samples(3600.0), samples(7200.0)))

        val mask = build(night, blindZones = blind)

        assertThat(mask.tstMin).isCloseTo(NIGHT_MIN, within(0.1))
        assertThat(mask.analysableTstMin).isCloseTo(NIGHT_MIN - 60.0, within(0.2))
        assertThat(mask.analysableSptMin).isCloseTo(NIGHT_MIN - 60.0, within(0.2))
    }

    @Test
    fun `off-body proves nothing and closes the SPT`() {
        val night = Night(NIGHT_SEC)
        val off = listOf(Segment(samples(7.0 * 3600.0), night.n))

        val mask = build(night, offBody = off)

        assertThat(mask.sptMin).isCloseTo(420.0, within(0.2))
        assertThat(mask.tstMin).isCloseTo(420.0, within(0.2))
    }

    @Test
    fun `fixed point - a localised series converges`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 7200.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 122)

        val fp = ImmobilityMask.fixedPoint(
            night.gravity(), night.env(), night.floor(), night.segments(), emptyList(),
        ) { clms }

        assertThat(fp.iterations).isEqualTo(2)
        assertThat(fp.provisionalTstMin).isCloseTo(NIGHT_MIN - 45.0, within(2.0))
        assertThat(fp.tstDeltaFraction).isLessThan(0.25)
        assertThat(fp.mask.fixedPointConverged).isTrue()
        assertThat(fp.mask.tstMin).isCloseTo(NIGHT_MIN, within(0.2))
    }

    /**
     * The case where the provisional TST collapses. Two things must happen together, and this is
     * the heart of the answer to circularity: the **final** mask keeps the night (layer 1), and
     * the non-convergence is reported honestly (layer 2) so that the publication gate refuses an
     * aPLM-i on a night where the accelerometer TST is not determinable. The Periodicity Index,
     * for its part, has no temporal denominator and survives that refusal.
     */
    @Test
    fun `fixed point - non-convergence when the provisional TST collapses`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 10.0, imiSec = 22.0, durSec = 1.5, ampG = MOVE_G, count = 1300)

        val fp = ImmobilityMask.fixedPoint(
            night.gravity(), night.env(), night.floor(), night.segments(), emptyList(),
        ) { clms }

        assertThat(fp.provisionalTstMin).isEqualTo(0.0)
        assertThat(fp.tstDeltaFraction).isNaN()
        assertThat(fp.mask.fixedPointConverged).isFalse()
        assertThat(fp.mask.tstMin).isGreaterThan(0.98 * NIGHT_MIN)
    }

    @Test
    fun `more than two iterations is refused by construction`() {
        val night = Night(60.0)

        assertThatThrownBy {
            ImmobilityMask.fixedPoint(
                night.gravity(), night.env(), night.floor(), night.segments(), emptyList(),
                cfg = ImmobilityConfig(maxFixedPointIterations = 3),
            ) { emptyList() }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the mask is deterministic - two identical builds give the same result`() {
        val night = Night(NIGHT_SEC)
        val clms = night.periodic(startSec = 30.0, imiSec = 25.0, durSec = 1.0, ampG = MOVE_G, count = 500)

        val a = build(night, ignoreIntervals = clms)
        val b = build(night, ignoreIntervals = clms)

        assertThat(b.windows).isEqualTo(a.windows)
        assertThat(b.analysableTstMin).isEqualTo(a.analysableTstMin)
    }
}
