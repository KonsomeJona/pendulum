package com.pendulum.wear.time

import com.pendulum.wear.BuildConfig

/**
 * The bench overrides, **debug** variant.
 *
 * Same construction as [TimeScaling] and for the same reason: the value comes from a `BuildConfig`
 * field that is declared only in the debug variant. The release variant of this file returns a
 * `false` constant, so the compiler removes the branch — the override is not disabled there, it
 * does not exist there.
 */
object Bench {

    /**
     * Ignore the automatic stop on charger.
     *
     * The watch stops after sixty seconds of sustained charging, which is exactly what is wanted
     * at night — and what makes any run longer than a minute impossible while it is wired by USB
     * to the machine driving it. An instrumented run on a wired watch is the only case where that
     * rule gets in the way, and it is a bench case.
     *
     * On the bench: `./gradlew -Ppendulum.banc.ignorer.chargeur=true :wear:assembleDebug`.
     */
    val IGNORE_CHARGER: Boolean = BuildConfig.BANC_IGNORER_CHARGEUR
}
