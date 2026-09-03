package com.pendulum.phone

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pendulum.phone.data.EveningEntry
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.tonight.EveningContextScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The evening form, checked **as composed** and not through its pure rules.
 *
 * Three defects lived in this screen, none of which a JVM test could see: the strap was never
 * pre-filled because `remember` froze a value that arrives a frame later; the alcohol field threw
 * the comma away and sealed "1,5" as fifteen units; and every field went back to its default on a
 * rotation, an unfolding or a process death. All three end in a night sealed with the wrong
 * context, or not sealed at all, which is the product's only gate. Each test here reproduces the
 * exact sequence — the late reference, the French keyboard, the recreation — and reads what would
 * have been sealed.
 */
@RunWith(AndroidJUnit4::class)
class EveningFormTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The ViewModel exposes the strap reference as a `StateFlow` seeded with "", and DataStore
     * answers after the first frame. The field used to be `remember { mutableStateOf(reference) }`,
     * so every evening it was empty and the button greyed out with "strap missing".
     */
    @Test
    fun strapNotedDuringOnboarding_isShownWhenItArrivesAfterTheFirstFrame() {
        var reference by mutableStateOf("")
        compose.setContent {
            PendulumTheme {
                EveningContextScreen(strapReference = reference, result = null, onSeal = {}, onCancel = {})
            }
        }
        compose.onNodeWithText(text(R.string.tonight_field_strap_missing)).assertExists()

        reference = "hole 4"

        compose.onNodeWithText("hole 4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tonight_field_strap_missing)).assertDoesNotExist()
    }

    /** A French keyboard sends "1,5"; the field used to display and seal "15". */
    @Test
    fun aCommaTypedInTheAlcoholField_sealsOneAndAHalfUnits_notFifteen() {
        var sealed: EveningEntry? = null
        compose.setContent {
            PendulumTheme {
                EveningContextScreen(strapReference = "hole 4", result = null, onSeal = { sealed = it }, onCancel = {})
            }
        }

        compose.onNodeWithText(text(R.string.tonight_field_alcohol)).performScrollTo().performTextInput("1,5")
        compose.onNodeWithText("1.5").assertExists()
        seal()

        assertEquals(1.5, sealed?.alcoholUnits ?: 0.0, 0.0)
    }

    /**
     * The recreation a rotation, an unfolding or a background kill produces. Every field was in
     * `remember`, so the leg came back to "right" and a dose typed at 23:00 came back blank.
     */
    @Test
    fun whatWasTypedBeforeARecreation_isWhatGetsSealedAfterIt() {
        val restoration = StateRestorationTester(compose)
        var sealed: EveningEntry? = null
        restoration.setContent {
            PendulumTheme {
                EveningContextScreen(strapReference = "hole 4", result = null, onSeal = { sealed = it }, onCancel = {})
            }
        }
        compose.onNodeWithText(text(R.string.tonight_leg_left)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.tonight_field_dose)).performScrollTo().performTextInput("clonazepam 0.5 mg")
        compose.onNodeWithText(text(R.string.tonight_field_notes)).performScrollTo().performTextInput("late dinner")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("clonazepam 0.5 mg").assertExists()
        seal()
        assertEquals(EveningEntry.LEG_LEFT, sealed?.leg)
        assertEquals("hole 4", sealed?.strap)
        assertEquals("clonazepam 0.5 mg", sealed?.medicationJson)
        assertEquals("late dinner", sealed?.notes)
    }

    /** The button, then the same label inside the confirmation dialog. */
    private fun seal() {
        val seal = text(R.string.tonight_seal_button)
        compose.onNodeWithText(seal).performScrollTo().performClick()
        compose.onNode(hasText(seal) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun text(@StringRes id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
