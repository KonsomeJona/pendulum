package com.pendulum.wear.time

import com.pendulum.format.time.TimeScale

/**
 * The **release** variant: time is time, and there is nothing to set.
 *
 * This file has a twin in `src/debug/` bearing the same fully qualified name. The compiler sees
 * only one of them depending on the variant, and this one has **no input at all**: no
 * `BuildConfig` field, no preference, no intent extra, no Gradle property. There is therefore no
 * path — not even a faulty one — by which a release could run at a scale other than real time.
 *
 * `const` and not `val`: the value is inlined at its call sites. There is no static field to
 * reflect over, no setter to synthesise, nothing but a copied literal — which also closes the back
 * door a mutable property reached by reflection would have left open.
 */
object TimeScaling {

    const val DIVISOR: Long = TimeScale.REAL_TIME_DIVISOR
}
