package com.pendulum.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pendulum.phone.ui.model.Flag
import com.pendulum.phone.ui.model.NightState
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.nights.NightRow
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.theme.PendulumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A night row is one gesture and one announcement.
 *
 * The quality chips used to be `AssistChip(onClick = {})`: a button with nothing behind it, laid
 * over a row whose whole body opens the night. The chip is the most visible element of the row, so
 * the most natural tap did nothing while the tap next to it worked — the kind of dead button the
 * `ErrorCard` KDoc says teaches one to distrust the application. TalkBack, for its part, heard one
 * row plus one dead button per flag.
 */
@RunWith(AndroidJUnit4::class)
class NightRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val night = NightUi(
        sessionHex = "0a1b",
        readableDate = "3 Sep",
        shortDay = "Thu",
        start = "23:10",
        end = "06:40",
        readableSleep = "6 h 50",
        sleepSource = UiText.Raw("watch"),
        state = NightState.ELIGIBLE,
        reason = null,
        rhythmSec = null,
        plmiCount = null,
        flags = listOf(Flag(UiText.Raw("gap 12 min"))),
        startWallMs = 0L,
    )

    /** The unmerged tree, so that the click lands on the chip itself and not on the row's centre. */
    @Test
    fun tappingAQualityChip_opensTheNight() {
        var opened: String? = null
        compose.setContent { PendulumTheme { NightRow(night) { opened = it } } }

        compose.onNodeWithText("gap 12 min", useUnmergedTree = true).performClick()

        assertEquals("0a1b", opened)
    }

    @Test
    fun aNightRow_exposesOneAction_notOnePlusItsFlags() {
        compose.setContent { PendulumTheme { NightRow(night) {} } }

        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }
}
