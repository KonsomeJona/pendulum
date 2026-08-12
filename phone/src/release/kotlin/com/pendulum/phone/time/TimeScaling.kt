package com.pendulum.phone.time

import com.pendulum.format.time.TimeScale

/**
 * **Release** variant: time is time, and there is nothing to adjust.
 *
 * Twin of `wear/src/release/.../TimeScale.kt`. No input whatsoever: no `BuildConfig` field, no
 * preference, no intent extra, no Gradle property. A release does not know how to run at any
 * scale other than real time, and that is not an instruction — it is the only code it contains.
 *
 * This is the first `src/release/` of `:phone`. The module already had a `src/debug/` — the
 * preview fixtures — but with no twin, because a Compose preview is only referenced by code that
 * is itself debug. Here the calling code lives in `src/main/`, so both variants must supply the
 * symbol, and the absence of the twin would be a compilation error.
 */
object TimeScaling {

    const val DIVISOR: Long = TimeScale.REAL_TIME_DIVISOR
}
