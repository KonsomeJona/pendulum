package com.pendulum.phone.time

import com.pendulum.phone.BuildConfig

/**
 * **Debug** variant: the time divisor is the one the compilation received.
 *
 * Twin of `wear/src/debug/.../TimeScaling.kt`, and for the same reason:
 * `BuildConfig.TEMPS_DIVISEUR` is declared only in the `debug` block of
 * `phone/build.gradle.kts`, so this file compiles in that variant only. Its `src/release/` twin
 * does not even know the name of the field.
 *
 * ### The two halves must receive the same value, and nothing checks it
 *
 * The two modules are two distinct applications, compiled separately, each with its own
 * `BuildConfig`. Nothing in the code can establish that they were built with the same
 * `-Ppendulum.temps.diviseur`: it is up to the bench script to compile them in the same Gradle
 * invocation. A mismatched pair does not produce an error, it produces a phone that declares the
 * night expired while the watch is still recording it — exactly the kind of failure this
 * repository documents rather than wishes away.
 *
 * Default value: 1. An ordinary debug build behaves exactly like the release.
 */
object TimeScaling {

    /** 600 on the bench: thirty minutes are worth three seconds, T+36 h three and a half minutes. */
    val DIVISOR: Long = BuildConfig.TEMPS_DIVISEUR
}
