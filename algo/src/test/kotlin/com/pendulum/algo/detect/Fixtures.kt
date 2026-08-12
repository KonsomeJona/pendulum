package com.pendulum.algo.detect

import com.pendulum.algo.dsp.Numeric
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DualEnvelope
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import com.pendulum.algo.model.TriAxial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal synthetic fixtures for the `detect` package. Deliberately independent of
 * `com.pendulum.algo.dsp` and `com.pendulum.algo.synth`: these tests must isolate the state
 * machine, not the filtering chain.
 */
internal const val FS = 50.0

internal fun samples(sec: Double): Int = Math.round(sec * FS).toInt()

/** Movement magnitude signal, in g, all zeros. */
internal fun quietMagnitude(durSec: Double): FloatArray = FloatArray(samples(durSec))

/**
 * Adds a burst of amplitude `ampG` over `[startSec, startSec + durSec)`, with raised-cosine edges
 * of `rampSec` included in the duration. A constant plateau `A` gives an RMS envelope exactly equal
 * to `A`, which keeps the thresholds readable by hand in the tests.
 */
internal fun burst(
    m: FloatArray,
    startSec: Double,
    durSec: Double,
    ampG: Float,
    rampSec: Double = 0.10,
) {
    val from = samples(startSec)
    val len = samples(durSec)
    val ramp = samples(rampSec).coerceAtMost(len / 2).coerceAtLeast(1)
    for (i in 0 until len) {
        val idx = from + i
        if (idx !in m.indices) continue
        val shape = when {
            i < ramp -> 0.5 * (1.0 - cos(PI * i / ramp))
            i >= len - ramp -> 0.5 * (1.0 - cos(PI * (len - 1 - i) / ramp))
            else -> 1.0
        }
        m[idx] = (m[idx] + ampG * shape).toFloat()
    }
}

/** **Centred** two-scale RMS envelope, as in step 2. */
internal fun dualEnvelope(m: FloatArray, coarseSec: Double = 0.50, fineSec: Double = 0.15): DualEnvelope =
    DualEnvelope(
        coarse = Signal1D(FS, 0L, rms(m, coarseSec)),
        fine = Signal1D(FS, 0L, rms(m, fineSec)),
        coarseWinSec = coarseSec,
        fineWinSec = fineSec,
    )

private fun rms(m: FloatArray, winSec: Double): FloatArray {
    val w = samples(winSec).coerceAtLeast(1)
    // SAME convention as `Numeric.halfLeft`: even window, the extra cell goes to the RIGHT.
    // With `w / 2` the fixtures were shifted one sample to the left relative to the envelope the
    // production chain builds, and every timing assertion carried that shift without saying so.
    val half = Numeric.halfLeft(w)
    val out = FloatArray(m.size)
    for (i in m.indices) {
        val from = (i - half).coerceAtLeast(0)
        val to = (i - half + w).coerceAtMost(m.size)
        var s = 0.0
        for (k in from until to) s += m[k].toDouble() * m[k]
        out[i] = sqrt(s / (to - from)).toFloat()
    }
    return out
}

internal fun constantFloor(n: Int, valueG: Float = 0.005f): Signal1D =
    Signal1D(FS, 0L, FloatArray(n) { valueG })

internal fun noExtrapolation(n: Int): BooleanArray = BooleanArray(n)

/** Constant gravity: watch immobile, no change of orientation. */
internal fun flatGravity(n: Int): TriAxial =
    TriAxial(FS, 0L, FloatArray(n), FloatArray(n), FloatArray(n) { 1f })

/**
 * Gravity that rotates by `deg` about the X axis between `startSec` and `startSec + durSec`, then
 * stays at its new orientation. If `andBack` is true, it comes back to the original orientation
 * over the same duration: that is a large movement, not a posture change.
 */
internal fun rotatingGravity(
    n: Int,
    startSec: Double,
    durSec: Double,
    deg: Double,
    andBack: Boolean = false,
): TriAxial {
    val x = FloatArray(n)
    val y = FloatArray(n)
    val z = FloatArray(n)
    val from = samples(startSec)
    val len = samples(durSec)
    for (i in 0 until n) {
        val u = when {
            i < from -> 0.0
            i < from + len -> (i - from).toDouble() / len
            !andBack -> 1.0
            i < from + 2 * len -> 1.0 - (i - from - len).toDouble() / len
            else -> 0.0
        }
        val a = Math.toRadians(deg * u)
        y[i] = sin(a).toFloat()
        z[i] = cos(a).toFloat()
    }
    return TriAxial(FS, 0L, x, y, z)
}

internal fun noCalibration(): NightCalibration = NightCalibration(
    sensor = null,
    gainCalG = 0f,
    floorCalG = 0f,
    snrCal = 0f,
    gainSource = GainSource.NONE,
    outlierVsBaseline = false,
)

internal fun wholeNight(n: Int): List<Segment> = listOf(Segment(0, n))

/** Trivial mask: the whole window is sleep. */
internal fun sleepAllNight(durSec: Double): SleepMask {
    val endMs = Math.round(durSec * 1000.0)
    return SleepMask(
        windows = listOf(SleepWindow(0L, endMs, Stage.SLEEP)),
        source = MaskSource.ACCEL_IMMOBILITY,
        sptMin = durSec / 60.0,
        tstMin = durSec / 60.0,
        wasoMin = 0.0,
        analysableTstMin = durSec / 60.0,
        analysableSptMin = durSec / 60.0,
        corrected = false,
        lagAppliedMs = 0L,
        independence = DenominatorIndependence.CIRCULAR,
        fixedPointConverged = true,
    )
}

/** Synthetic CLM for the [SeriesBuilder] tests. */
internal fun clmAt(
    onsetSec: Double,
    durationMs: Int = 2000,
    flags: Int = 0,
    reject: ClmRejectReason? = null,
): Clm {
    val onsetIdx = samples(onsetSec)
    return Clm(
        onsetIdx = onsetIdx,
        offsetIdx = onsetIdx + Math.round(durationMs * FS / 1000.0).toInt(),
        onsetMsRel = Math.round(onsetSec * 1000.0),
        durationMs = durationMs,
        peakAmpG = 0.1f,
        medianAmpG = 0.05f,
        noiseFloorG = 0.005f,
        thresholdOnG = 0.04f,
        thresholdOffG = 0.0125f,
        tiltChangeDeg = 1f,
        tiltExcursionDeg = 5f,
        flags = flags,
        reject = reject,
    )
}
