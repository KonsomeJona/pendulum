package com.pendulum.phone.bench

import com.pendulum.algo.model.Stage
import com.pendulum.algo.synth.GroundTruth
import com.pendulum.algo.synth.NightSpec
import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.TelemetryPointEntity
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The telemetry of a seeded night: one point per minute, as the watch produces it.
 *
 * ### Why it is manufactured when the chunks are not
 *
 * It cannot be deduced from any other table. Without it, the detail of a night has **nothing** to
 * put in its device status band — no battery gauge, no wearing state — and the screen is then
 * judged on half of itself. The chunks, for their part, are deliberately absent: see the KDoc of
 * [Seeding], the reason there is a different one.
 *
 * A consequence to know about: the time bands (off wrist, charging, write stall) are placed on the
 * **sensor** time base, whose origin is the `tFirstNs` of the first chunk. Since no seeded night
 * has a chunk, those bands stay empty — the gauge and the wearing state, which depend on the
 * points alone, show up normally.
 *
 * ### The values
 *
 * They are neither round nor constant, for the same reason the nights are synthesised rather than
 * written by hand: a battery that goes down by an exact step and a jitter that is always equal
 * bring out no scaling defect. The battery slope follows the real duration of the night, and the
 * off-wrist period is the one the generator actually injected.
 */
internal object Telemetry {

    /** One point per minute: that is the cadence of the watch (five points per complete chunk). */
    private const val PERIOD_MS = 60_000L

    /** Nominal capacity of a watch battery, in uAh. Used to render `batteryChargeUah`. */
    private const val CAPACITY_UAH = 300_000

    fun points(
        sessionHex: String,
        spec: NightSpec,
        truth: GroundTruth,
        recordedDurationMin: Double,
    ): List<TelemetryPointEntity> {
        val count = recordedDurationMin.roundToInt().coerceAtLeast(1)
        val r = Random(sessionHex.hashCode().toLong())

        // The off-wrist period exactly as the generator laid it down, read back from the mask
        // rather than guessed again: it is the only way for the `offBody` flag of the telemetry to
        // tell the same night as the sleep windows.
        val offWrist = truth.mask.windows.filter { it.stage == Stage.OUT_OF_BED }

        // 45 % of battery over an 8 h night, scaled to the real duration. That is the order of
        // magnitude of a continuous accelerometric recording at 50 Hz.
        val drainPct = 45.0 * (recordedDurationMin / 480.0)

        return (0 until count).map { i ->
            val msRel = i * PERIOD_MS
            val progress = i.toDouble() / count
            val pct = (100.0 - drainPct * progress).roundToInt().coerceIn(1, 100)
            val isOffWrist = offWrist.any { msRel >= it.startMsRel && msRel < it.endMsRel }

            TelemetryPointEntity(
                sessionHex = sessionHex,
                // The monotonic clock carries the uniqueness of the row (`UNIQUE(sessionHex,
                // elapsedRealtimeNs)`): it never passes twice through the same value.
                elapsedRealtimeNs = msRel * 1_000_000L,
                sensorTsNs = spec.startNs + msRel * 1_000_000L,
                batteryChargeUah = (CAPACITY_UAH * pct / 100.0).roundToInt(),
                // Largest interval between two samples over the minute just elapsed: the nominal
                // period, plus the spread of a FIFO flush.
                maxIntervalUs = 20_000L + r.nextInt(9_000).toLong(),
                fsyncTotalUs = 12_000L + r.nextInt(30_000).toLong(),
                fsyncMaxUs = 3_000L + r.nextInt(9_000).toLong(),
                // A watch on the wrist is warmer than the air; it cools down a little when it
                // comes off.
                temperatureDeciC = if (isOffWrist) 240 + r.nextInt(30) else 305 + r.nextInt(25),
                measuredRateCentiHz = 4_990 + r.nextInt(25),
                jitterStdUs = 210 + r.nextInt(180),
                clippedSamples = 0,
                fsyncCount = 10 + r.nextInt(4),
                batteryPct = pct,
                offBody = if (isOffWrist) {
                    TelemetryPoint.OFF_BODY_REMOVED
                } else {
                    TelemetryPoint.OFF_BODY_WORN
                },
                // Never charging: a watch on its dock does not record the night, and a point taken
                // while charging would fall outside any battery slope regression — so
                // manufacturing one would make the slope uninterpretable for nothing.
                charging = false,
            )
        }
    }
}
