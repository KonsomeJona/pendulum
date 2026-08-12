package com.pendulum.phone.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The four things the application must remember from one launch to the next and which are not
 * measurement data.
 *
 * `androidx.datastore.preferences` had been declared as a dependency from the start and **was
 * instantiated nowhere**. `work/Workers.kt` said it plainly in a TODO: the preferred-source setting
 * was decorative, since nothing persisted it and the read always fell back on the heuristic of
 * `SleepSourceSelector`.
 *
 * ### Why not in Room
 *
 * The database carries the measurement, and the measurement is what we have no right to lose. An
 * interface preference does not have that status: mixing it in with the nights would force a
 * migration to be written for it every time a checkbox is added, on a database where a wrong
 * migration costs unrecoverable nights. The two layers are kept apart on purpose.
 *
 * ### What is deliberately not here
 *
 * Nothing that influences a figure. The counting rule set and the parameter profile live in
 * `param_profile`, in the database, because they carry a `paramsHash` on which the rescore of every
 * night depends. A preference that can be erased by clearing the cache must not be able to change
 * a result.
 */
class PendulumPreferences(private val context: Context) {

    /**
     * Number of the onboarding step **already crossed**, from 0 to [ONBOARDING_STEPS].
     *
     * This is what makes onboarding resumable: leaving at step 3 brings you back there, and not to
     * the beginning — redoing three notice screens to reach the one you were after is the surest
     * way to get an application uninstalled. It is written only on leaving each step, never on
     * entering it: a step started and abandoned is not a step crossed.
     */
    val onboardingStep: Flow<Int>
        get() = context.dataStore.data.map { it[KEY_ONBOARDING_STEP] ?: 0 }

    suspend fun setOnboardingStep(step: Int) {
        context.dataStore.edit { it[KEY_ONBOARDING_STEP] = step.coerceIn(0, ONBOARDING_STEPS) }
    }

    val onboardingDone: Flow<Boolean>
        get() = onboardingStep.map { it >= ONBOARDING_STEPS }

    /**
     * Package of the application chosen as the sleep source, or `null` to let
     * `SleepSourceSelector` decide.
     *
     * This is not a convenience. When two applications write sleep sessions that overlap, the
     * denominator depends on which one is read, and a denominator that changes from one night to
     * the next manufactures a trend that does not exist.
     */
    val preferredSleepSource: Flow<String?>
        get() = context.dataStore.data.map { it[KEY_SLEEP_SOURCE] }

    suspend fun setPreferredSleepSource(pkg: String?) {
        context.dataStore.edit {
            if (pkg == null) it.remove(KEY_SLEEP_SOURCE) else it[KEY_SLEEP_SOURCE] = pkg
        }
    }

    /** One-off read, for the workers that have no scope in which to collect a flow. */
    suspend fun preferredSleepSourceNow(): String? =
        context.dataStore.data.first()[KEY_SLEEP_SOURCE]

    /**
     * The strap tightness reference, entered at the last onboarding step and **replayed every
     * evening**.
     *
     * It was asked for during onboarding and thrown away: `OnboardingPager` handed it back to its
     * caller, which did not exist. Yet it is what makes two nights comparable — the play of the
     * strap makes the amplitude vary by a factor of 2 to 3, which
     * `ComparabilityRule.GAIN_TOLERANCE` absorbs at 35 % without being able to correct it.
     */
    val strapReference: Flow<String>
        get() = context.dataStore.data.map { it[KEY_STRAP_REFERENCE] ?: "" }

    suspend fun setStrapReference(reference: String) {
        context.dataStore.edit { it[KEY_STRAP_REFERENCE] = reference }
    }

    /** `SYSTEME`, `SOMBRE` or `CLAIR`. Dark by default, and the default is a choice. */
    val theme: Flow<String>
        get() = context.dataStore.data.map { it[KEY_THEME] ?: THEME_DARK }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[KEY_THEME] = theme }
    }

    companion object {
        const val ONBOARDING_STEPS = 6

        // The three stored tokens keep their original spelling: they are persisted values, and
        // renaming them would silently discard the theme already chosen by a user.
        const val THEME_SYSTEM = "SYSTEME"
        const val THEME_DARK = "SOMBRE"
        const val THEME_LIGHT = "CLAIR"

        private val KEY_ONBOARDING_STEP = intPreferencesKey("etape_assistant")
        private val KEY_SLEEP_SOURCE = stringPreferencesKey("source_sommeil_preferee")
        private val KEY_STRAP_REFERENCE = stringPreferencesKey("repere_de_serrage")
        private val KEY_THEME = stringPreferencesKey("theme")
    }
}

/**
 * A single `DataStore` per process, imposed by the library: instantiating two of them on the same
 * file raises an exception at the first write. The extension delegate guarantees it.
 *
 * The file is excluded from backup by `res/xml/data_extraction_rules.xml`, like the rest: a restore
 * onto another phone would bring back an onboarding state without the nights that go with it.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pendulum")
