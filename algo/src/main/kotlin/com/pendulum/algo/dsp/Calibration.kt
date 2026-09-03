package com.pendulum.algo.dsp

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.NightCalibration
import kotlin.math.abs

/**
 * §3.3 — inter-night normalisation. **A single mechanism remains**,
 * [fromGrossBodyMovements]: `gainCal` estimates the gain of the ankle -> strap -> case -> MEMS
 * chain from body turns, which are the only gestures of known amplitude that a night supplies for
 * free.
 *
 * Historically it went by the name of **part B**, because there existed a part A (static sensor
 * autocalibration) and, for a time, a guided ritual. Both have been removed, and the two sections
 * below say why. The criterion applied is the same in both cases, and it is the only one that
 * counts: code that is written, tested and documented as active while no production caller invokes
 * it lies about what the product actually does.
 *
 * ### The guided ritual, and why it no longer exists
 *
 * A third mechanism lived here: a 70 s ritual — 30 s of immobility, ten metronome-paced
 * dorsiflexions, 10 s of return to calm — whose KDoc announced "this is the one that counts". It
 * measured the gain on an imposed gesture rather than on an undergone one, which is indeed better.
 *
 * **It was removed on 2026-08-05 because it was never wired up.** The function was written,
 * tested, documented, and no production caller ever invoked it: the screen that would have guided
 * the user did not exist on the watch side, and it could not exist easily — this module is not in
 * the watch's published APK (`debugImplementation`), so the computation would have required
 * capturing the ritual as a session and sending it back to the phone. The cost was real, the
 * dormant function gave the opposite impression.
 *
 * A consequence to own rather than hide: inter-night comparability now rests entirely on
 * [fromGrossBodyMovements] and on the instruction "same strap, same hole, same leg", which v1
 * called a wish and not a solution. That is still true. The wish has simply become visible again.
 *
 * ### Part A, static autocalibration, and why it no longer exists either
 *
 * It did what GGIR / van Hees does, entirely derived from the night itself and with no user action
 * at all: locate the static windows (`sd < 13 mg` over 10 s), require the points to cover the
 * sphere (`max - min >= 0.30 g` on each of the three axes, failing which the problem is ill-posed
 * and least squares return a confident and false result), then five Gauss-Newton iterations on
 * `(offset o, diagonal gain S)`, with rejection if `||o|| > 0.10 g` or `max|S - 1| > 0.05` —
 * beyond that it is no longer MEMS drift, it is a suspect sensor.
 *
 * **Removed on 2026-08-07, same criterion as the ritual: no production caller.**
 * `NightAnalyzer` always built `sensor = null`. Unlike the ritual, the excuse could not be the
 * cost of a screen — its two inputs, the raw signal and the segments, are available from pass 0
 * onwards. What was missing was not an input, it was an **output**, and that is what settled
 * removal rather than wiring:
 *
 * 1. **The result had no reader.** `NightCalibration.sensor` is written and never read:
 *    `Preprocess` only consults `gainCalG`, and the database only persists `gainCalG` and
 *    `gainSource`. Wiring it there would have paid for one Gauss-Newton per night without changing
 *    a single figure — the ritual's flaw, plus a computation cost.
 * 2. **The only real output lies outside this module.** Correcting the signal means doing it
 *    between the construction of the timeline and the gravity / movement separation:
 *    `Preprocess.run` receives blocks, whereas the correction applies to a `TriAxial` that only
 *    exists after `TimelineBuilder`. Wiring it honestly requires opening that seam, not two lines
 *    in the orchestrator.
 * 3. **Correcting the blocks upstream would have disarmed an integrity check.**
 *    `Integrity.collectStaticNorm` uses exactly the same 13 mg criterion to verify that the static
 *    norm really is 1 g, and feeds `decodeSuspect`, which commands
 *    `IntegrityReport.acceptable`. Pre-correcting the samples made that check incapable of firing.
 *    The autocalibration KDoc itself said that beyond its thresholds
 *    "correcting it would mask the failure": blindly pre-applying it did precisely that.
 *
 * One underlying reason remains, which explains why its absence was never noticed — it is
 * **estimated and not measured**, to be verified if anyone reopens the subject: the detection
 * chain is high-pass at 0.50 Hz, which eliminates the offset term even before the envelope, and
 * the gain term is bounded to 5 % by the rejection rule above while largely cancelling out in the
 * envelope / floor ratio, the floor being estimated on the same signal. The expected effect on the
 * PLMI was therefore second order, for a first-order cost.
 *
 * The removed code is in the git history; rewiring it means opening the seam of point 2 and
 * settling point 3, not restoring the function as it was.
 */
object Calibration {

    /** Inter-night tolerance of §3.3: beyond it, the night is flagged `CALIB_OUTLIER`. */
    const val DEFAULT_OUTLIER_TOLERANCE = 0.35

    /**
     * **The product's only gain reference** since the guided ritual was removed.
     *
     * Internal standard: the **median of the peak amplitudes of the night's gross body
     * movements**. Turns are a physiologically stereotyped event, frequent (20 to 60 per night)
     * and of relatively stable amplitude (Sicbaldi: 377 +/- 63 mg during sleep, i.e. a CV of 17 %
     * across subjects). It is an **undergone** gesture and not an imposed one: its amplitude
     * varies with the starting position, the bedding and the depth of sleep, where a guided
     * gesture would have been reproducible. That is the known limit of this standard, and there is
     * no other.
     *
     * `gainSource` must accompany **every** published PLMI: it is now only ever `GROSS_BODY` or
     * `NONE`, and that latter value means that no inter-night comparison is founded.
     */
    fun fromGrossBodyMovements(
        clms: List<Clm>,
        baselineGainG: Float? = null,
        outlierTolerance: Double = DEFAULT_OUTLIER_TOLERANCE,
    ): NightCalibration {
        val peaks = ArrayList<Float>()
        val floors = ArrayList<Float>()
        for (c in clms) {
            if ((c.flags and ClmFlags.GROSS_BODY) == 0) continue
            // Filter on the **flags**, not on `reject`: a gross body movement always carries
            // `reject == GROSS_BODY`, which would hide everything else, and `reject` holds a single
            // reason whose priority order can bury the blind zone behind another one. Flags
            // accumulate, so they are the only place where both facts are readable at once.
            //
            //  - IN_BLIND_ZONE: inside a hole the movement channel is fed zeros (step 0), and the
            //    step back to ~1 g of gravity at the end of the hole rings through the 0.5 Hz
            //    high-pass at 390-550 mg on the coarse envelope. That is *larger* than a real turn
            //    (Sicbaldi, 377 +/- 63 mg), so the artefact is classified GROSS_BODY and pulled the
            //    night's only gain reference upwards — by an amount that depends on the number of
            //    FIFO holes rather than on anything about the sleeper. Two nights with different
            //    hole counts stopped being comparable, which is the one thing this standard exists
            //    to make possible. The blind zone bounds the ringing for the *detector*; it never
            //    protected the gain, because this loop never looked at it.
            //  - TRUNCATED: an event clipped by a segment edge has its peak measured on a partial
            //    event, which biases the median downwards.
            if ((c.flags and (ClmFlags.IN_BLIND_ZONE or ClmFlags.TRUNCATED)) != 0) continue
            if (!c.peakAmpG.isNaN()) peaks.add(c.peakAmpG)
            if (!c.noiseFloorG.isNaN()) floors.add(c.noiseFloorG)
        }
        if (peaks.isEmpty()) {
            return NightCalibration(null, Float.NaN, Float.NaN, Float.NaN, GainSource.NONE, false)
        }
        val gain = Numeric.median(peaks.toFloatArray())
        val floor = if (floors.isEmpty()) Float.NaN else Numeric.median(floors.toFloatArray())
        val snr = if (floor.isNaN() || floor <= 0f) Float.NaN else gain / floor
        return NightCalibration(
            // No producer at all since part A was removed. The field survives in `Model.kt`,
            // where it now has neither writer nor reader: to be deleted by whoever touches the
            // model.
            sensor = null,
            gainCalG = gain,
            floorCalG = floor,
            snrCal = snr,
            gainSource = GainSource.GROSS_BODY,
            outlierVsBaseline = isOutlier(gain, baselineGainG, outlierTolerance),
        )
    }

    /**
     * Inter-night quality check (§3.3). `true` = night not comparable to its campaign:
     * "strap tightness probably different — retighten and redo".
     */
    private fun isOutlier(gainCalG: Float, baselineGainG: Float?, tolerance: Double = DEFAULT_OUTLIER_TOLERANCE): Boolean {
        val b = baselineGainG ?: return false
        if (b.isNaN() || b <= 0f || gainCalG.isNaN()) return false
        return abs(gainCalG - b) / b > tolerance
    }
}
