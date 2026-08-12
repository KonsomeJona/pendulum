package com.pendulum.wear.time

import com.pendulum.wear.BuildConfig

/**
 * The **debug** variant: the time divisor is the one the compilation was given.
 *
 * `BuildConfig.TEMPS_DIVISEUR` is declared only in the `debug` block of `wear/build.gradle.kts`.
 * This file therefore cannot exist anywhere but here: its `src/release/` twin compiles against a
 * `BuildConfig` class that **does not carry that field**. Splitting by source set is not a
 * tidiness convention here, it is what makes the oversight impossible — an `if (BuildConfig.DEBUG)`
 * can be worked around by inattention during a reshuffle, two compilations that do not see the
 * same code cannot. It is the same discipline as `SourceFactory` two directories away.
 *
 * ### Why a Gradle property and not a preference
 *
 * The bench **deploys a configuration**: `./gradlew -Ppendulum.temps.diviseur=600 :wear:assembleDebug`.
 * Nothing to read at start-up, nothing to install before first use, no window between the launch
 * of the process and the first duration consumed — and a night cannot change scale midway. The APK
 * says what it is, and `adb shell dumpsys package` finds it back in its fingerprint.
 *
 * Default value: 1. An ordinary debug build behaves exactly like the release.
 */
object TimeScaling {

    /** 600 on the bench: five minutes are worth five hundred milliseconds, eight hours forty-eight seconds. */
    val DIVISOR: Long = BuildConfig.TEMPS_DIVISEUR
}
