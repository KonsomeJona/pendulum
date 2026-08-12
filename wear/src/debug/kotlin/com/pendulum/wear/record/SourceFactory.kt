package com.pendulum.wear.record

import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.util.Log
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.NightSpec

/**
 * **debug** variant: the sensor, or the synthetic replay when the bench asks for it.
 *
 * The `src/release/` twin of this file does not know [SyntheticSource] and cannot build it. That
 * is the source-set separation, and it is what makes the oversight impossible: there is no flag
 * left at `true` to forget, there are two compilations that do not see the same code.
 *
 * ### Two ways to enable it, and why both
 *
 *  - **An extra on the start intent**, which is the scriptable form: the bench states explicitly
 *    what it wants at the moment it asks for it.
 *  - **A preference, written by the extra**, read back when the intent carries none. Without it, a
 *    restart by `START_STICKY` — which delivers a null intent and which is the **nominal**
 *    behaviour, not merely the service-death scenario — would resume the night on the real sensor.
 *    Half of the file would come from the replay, the other half from the wrist, and nothing would
 *    say so.
 *
 * An `ACTION_START` carrying no extra resets the preference: a night that begins always states
 * explicitly where its signal comes from.
 */
object SourceFactory {

    private const val TAG = "PendulumBench"
    private const val PREFS = "pendulum_banc"

    /** `--ez pendulum.synth true` */
    const val EXTRA_ENABLED = "pendulum.synth"

    /** `--el pendulum.synth.seed 42` — same seed, bit-identical output (test T13 of `:algo`). */
    const val EXTRA_SEED = "pendulum.synth.seed"

    /** `--ef pendulum.synth.hours 8.0` */
    const val EXTRA_HOURS = "pendulum.synth.hours"

    /** `--ez pendulum.synth.distractors false` for a clean night, without gaps or distractors. */
    const val EXTRA_DISTRACTORS = "pendulum.synth.distractors"

    fun create(context: Context, intent: Intent?): SensorSource {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (intent != null && intent.action == RecordingService.ACTION_START) {
            configure(
                context = context,
                enabled = intent.getBooleanExtra(EXTRA_ENABLED, false),
                seed = intent.getLongExtra(EXTRA_SEED, 1L),
                hours = intent.getFloatExtra(EXTRA_HOURS, 8f),
                distractors = intent.getBooleanExtra(EXTRA_DISTRACTORS, true),
            )
        }
        if (!p.getBoolean(EXTRA_ENABLED, false)) {
            return HardwareSensorSource(context.getSystemService(SensorManager::class.java))
        }

        val hours = p.getFloat(EXTRA_HOURS, 8f).toDouble()
        val seed = p.getLong(EXTRA_SEED, 1L)
        val spec = NightSpec(
            durationH = hours,
            distractors = if (p.getBoolean(EXTRA_DISTRACTORS, true)) {
                DistractorSpec.ALL
            } else {
                DistractorSpec.NONE
            },
        )
        // At WARN, and named unambiguously: a night log read again six months from now must say
        // where its signal came from, otherwise the measurement drawn from it means nothing.
        Log.w(TAG, "SYNTHETIC SOURCE ACTIVE — seed=$seed duration=${hours}h. " +
            "Neither FIFO batching nor SoC wake-ups are exercised by this night.")
        return SyntheticSource(spec, seed)
    }

    /**
     * Whether the source that will be built at the next start is the synthetic replay.
     *
     * It is read **after** [create], never before: it is [create] that transcribes the intent
     * extras into the preference, and querying the latter any earlier would return the state of
     * the previous night. Its only caller is the scale guard rail of `RecordingService`.
     *
     * The `src/release/` twin returns `false` without reading anything: there is no replay there.
     */
    fun syntheticSourceEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(EXTRA_ENABLED, false)

    /**
     * Direct configuration, for an instrumented test that runs inside the application process and
     * therefore has no intent to build.
     */
    fun configure(
        context: Context,
        enabled: Boolean,
        seed: Long = 1L,
        hours: Float = 8f,
        distractors: Boolean = true,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(EXTRA_ENABLED, enabled)
            .putLong(EXTRA_SEED, seed)
            .putFloat(EXTRA_HOURS, hours)
            .putBoolean(EXTRA_DISTRACTORS, distractors)
            .apply()
    }
}
