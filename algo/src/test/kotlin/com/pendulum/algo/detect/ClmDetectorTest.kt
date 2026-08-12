package com.pendulum.algo.detect

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.TriAxial
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ClmDetectorTest {

    private fun detect(
        m: FloatArray,
        gravity: TriAxial = flatGravity(m.size),
        postures: List<PostureChange> = emptyList(),
    ): List<Clm> = ClmDetector.detect(
        env = dualEnvelope(m),
        floor = constantFloor(m.size),
        floorExtrapolated = noExtrapolation(m.size),
        gravity = gravity,
        segments = wholeNight(m.size),
        blindZones = emptyList(),
        postures = postures,
        calibration = noCalibration(),
    )

    @Test
    @DisplayName("a simple synthetic movement is detected and correctly dated")
    fun simpleMovement() {
        // Floor 5 mg -> Theta_on = max(8x5, 20, 0) = 40 mg, Theta_off = max(2.5x5, 6.25) = 12.5 mg.
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 2.0, ampG = 0.15f)

        val clms = detect(m)

        assertThat(clms).hasSize(1)
        val e = clms.single()
        assertThat(e.isClm).isTrue()
        assertThat(e.reject).isNull()
        assertThat(e.onsetMsRel).isBetween(9_800L, 10_150L)
        assertThat(e.durationMs).isBetween(1_900, 2_400)
        assertThat(e.peakAmpG).isCloseTo(0.15f, Offset.offset(0.01f))
        assertThat(e.thresholdOnG).isCloseTo(0.04f, Offset.offset(1e-6f))
        assertThat(e.thresholdOffG).isCloseTo(0.0125f, Offset.offset(1e-6f))
    }

    @Test
    @DisplayName("two bursts less than 0.5 s apart merge into a single movement")
    fun closeBurstsMerge() {
        // §1.5-iv: no dedicated merge step. It is the definition of the offset (staying below
        // Theta_off for 0.5 s) that mechanically prevents two events from being any closer.
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 1.0, ampG = 0.15f)
        burst(m, startSec = 11.3, durSec = 1.0, ampG = 0.15f)

        val clms = detect(m)

        assertThat(clms).hasSize(1)
        assertThat(clms.single().isClm).isTrue()
        assertThat(clms.single().durationMs).isBetween(2_200, 2_600)
    }

    @Test
    @DisplayName("two bursts more than 0.5 s apart stay two movements")
    fun distantBurstsStaySeparate() {
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 1.0, ampG = 0.15f)
        burst(m, startSec = 12.5, durSec = 1.0, ampG = 0.15f)

        val clms = detect(m)

        assertThat(clms).hasSize(2)
        assertThat(clms).allMatch { it.isClm }
        assertThat(clms[1].onsetMsRel - clms[0].onsetMsRel).isBetween(2_400L, 2_600L)
    }

    @Test
    @DisplayName("a movement longer than 10 s is flagged LM_LONG and is never a CLM")
    fun longMovementIsNeverAClm() {
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 12.0, ampG = 0.15f)

        val clms = detect(m)

        assertThat(clms).hasSize(1)
        val e = clms.single()
        assertThat(e.durationMs).isGreaterThan(10_000)
        assertThat(e.flags and ClmFlags.LM_LONG).isNotZero()
        assertThat(e.isClm).isFalse()
    }

    @Test
    @DisplayName("a short mattress ring is rejected by the morphology criterion")
    fun mattressRingRejectedByMorphology() {
        // §3.2: a transmitted vibration has a high peak but no 0.5 s period whose median exceeds
        // Theta_off. The tilt does not move either -> TRANSMITTED_SUSPECT.
        //
        // The ring lasts 0.15 s, not 0.25 s, and that is not a detail of convenience. The WASM
        // 3.2.1-d criterion requires a `morphologyWinSec = 0.50 s` window **contained within the
        // event**, and it compares the median of `env_f` against `Theta_off = 0.3125 x Theta_on`.
        // But the detected event is always ~0.24 s longer than the physical ring (the rising edge
        // is dated on `env_c`, whose 0.50 s window is **centred** — §7.2 of the algorithm dossier:
        // "the coarse onset leads the physical start by up to 12 samples"), and `env_f` spreads
        // the ring by a further 0.15 s. For a 0.25 s ring (13 samples), the event is 26 samples
        // long, the only morphology window available is centred on the ring, and `env_f` is
        // non-zero over 21 samples out of 25: the median then reaches 0.785 x the peak of `env_c`,
        // far above the 0.3125 hysteresis ratio. The criterion can therefore **never** reject a
        // 0.25 s ring, whatever its amplitude — the conclusion of §3.2 ("a 0.3 s ring has a high
        // peak but a low median over 0.5 s") only holds below ~0.17 s.
        // 0.15 s stays inside the 0.05-0.40 s range of family 4 in the §5.2 table.
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 0.15, ampG = 0.30f, rampSec = 0.05)

        val clms = detect(m)

        assertThat(clms).hasSize(1)
        val e = clms.single()
        assertThat(e.isClm).isFalse()
        assertThat(e.reject).isEqualTo(ClmRejectReason.MORPHOLOGY)
        assertThat(e.flags and ClmFlags.TRANSMITTED_SUSPECT).isNotZero()
    }

    @Test
    @DisplayName("a posture change produces no CLM")
    fun postureProducesNoClm() {
        // The posture transient is 5 to 30 times larger than a CLM and lasts exactly the right
        // duration to be counted: without the posture detector it would be counted as a CLM.
        val n = samples(60.0)
        val gravity = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 90.0)
        val m = quietMagnitude(60.0)
        burst(m, startSec = 30.0, durSec = 2.0, ampG = 0.50f)

        val postures = PostureDetector.detect(gravity, wholeNight(n))
        assertThat(postures).hasSize(1)

        val clms = detect(m, gravity, postures)

        assertThat(clms).hasSize(1)
        assertThat(clms).noneMatch { it.isClm }
        assertThat(clms.single().flags and ClmFlags.POSTURAL).isNotZero()
        assertThat(clms.single().reject).isEqualTo(ClmRejectReason.POSTURAL)
    }

    @Test
    @DisplayName("without the posture input, the same transient is stopped by tilt alone")
    fun sameTransientWithoutPostureInput() {
        val n = samples(60.0)
        val gravity = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 90.0)
        val m = quietMagnitude(60.0)
        burst(m, startSec = 30.0, durSec = 2.0, ampG = 0.10f)

        val clms = detect(m, gravity, postures = emptyList())

        // Without the posture input it is still rejected, but by the tilt criterion (GBM) alone:
        // that is the second line of defence, and it does not cover rotations below `postureDeg`.
        assertThat(clms).hasSize(1)
        assertThat(clms.single().tiltChangeDeg).isGreaterThan(80f)
        assertThat(clms.single().reject).isEqualTo(ClmRejectReason.GROSS_BODY)
    }
}
