package com.pendulum.wear.record

import android.content.Context
import android.content.Intent
import android.hardware.SensorManager

/**
 * **release** variant: there is only one source, and it is the sensor.
 *
 * This file has a twin in `src/debug/` carrying the same fully qualified name. That is the form
 * source-set separation takes here: the compiler sees only one of the two depending on the
 * variant, and the release variant does not even know the name `SyntheticSource`. No branch at
 * run time, no flag to forget, no replay class in the published APK — and if anyone tries to call
 * bench code from `src/main/`, it fails to compile instead of silently producing a credible
 * recording made out of nothing.
 *
 * @param intent ignored here. On the debug side it carries the bench configuration; declaring it
 *   in both variants keeps a single call signature in [RecordingService].
 */
object SourceFactory {

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, intent: Intent?): SensorSource =
        HardwareSensorSource(context.getSystemService(SensorManager::class.java))

    /**
     * Always `false`: there is no replay in this variant, and nothing to read to make sure of it.
     * The function exists so that the scale guard rail of `RecordingService` has a single call
     * signature — in release it compiles down to an always-false condition, which is exactly what
     * is wanted, `TimeScaling.DIVISOR` being 1 there by construction.
     */
    @Suppress("UNUSED_PARAMETER")
    fun syntheticSourceEnabled(context: Context): Boolean = false
}
