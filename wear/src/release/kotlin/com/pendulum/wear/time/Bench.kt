package com.pendulum.wear.time

/**
 * The bench overrides, **release** variant: there are none.
 *
 * `const val` and not `val`: the value is known at compile time, so the branch that depends on it
 * is eliminated. A bench override must not be able to exist in a published application, not even
 * disabled — see the debug variant for what it allows and why.
 */
object Bench {
    const val IGNORE_CHARGER: Boolean = false
}
