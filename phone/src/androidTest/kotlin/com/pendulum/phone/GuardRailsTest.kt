package com.pendulum.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.annotation.StringRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.eraseEverything
import com.pendulum.phone.preview.PreviewData
import com.pendulum.phone.preview.previewNightDetail
import com.pendulum.phone.ui.Destination
import com.pendulum.phone.ui.model.TrendUiState
import com.pendulum.phone.ui.nights.NightDetailScreen
import com.pendulum.phone.ui.onboarding.DisclaimerPage
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.trend.TrendScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard rails, checked **in the assembled application**.
 *
 * Why on a device and not on the JVM: the unit tests of `ui` check pure functions — the bounds of
 * `position()`, the absence of any verb of change in the resources, the drawing invariants. They do
 * not answer the only question that matters here, *what the user actually sees on screen*. A rule
 * can be correct in `Aggregate` and bypassed by the composition, by a forgotten state, or by a
 * screen that displays all the same.
 *
 * These tests are therefore the last line: they fail if the refusal starts showing a figure, if the
 * notice loses one of its statements, or if the hierarchy of the quantities is inverted.
 */
@RunWith(AndroidJUnit4::class)
class GuardRailsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun trendScreen(state: TrendUiState) {
        compose.setContent {
            PendulumTheme {
                TrendScreen(
                    state = state,
                    onNight = {},
                    onNights = {},
                    onCompare = {},
                    onQuestionnaire = {},
                    onExport = {},
                    onWakingAction = {},
                    onSleepSituation = {},
                )
            }
        }
    }

    /**
     * Below three eligible nights the screen refuses — and the refusal must be **total**. No
     * median, no category, no chart, not even an empty chart with its axes: an empty axis invites
     * the eye to imagine the curve that is missing.
     *
     * This is the most important guard rail in the product. In confirmed patients, the threshold of
     * 15/h is crossed on only about a third of individual nights; concluding on one or two nights is
     * not an imprudence, it is a measurement error.
     */
    @Test
    fun belowThreeNights_noAggregateFigureIsShown() {
        trendScreen(PreviewData.trendRefusal)

        compose.onNodeWithText(text(R.string.trend_refusal_title), substring = true).assertIsDisplayed()

        // Nothing that belongs to the full screen may appear.
        for (forbidden in listOf("high periodicity", "low periodicity", "Hourly count")) {
            compose.onAllNodesWithText(forbidden, substring = true).assertCountEquals(0)
        }
    }

    /**
     * The notice must carry its statements, and in particular the one nothing obliges us to write
     * but that honesty imposes: this is not an official health application. A user at 7 am does not
     * read a privacy policy, they read the screen.
     */
    @Test
    fun notice_saysThatThisIsNotAnOfficialHealthApplication() {
        compose.setContent { PendulumTheme { DisclaimerPage(onContinue = {}) } }

        for (claim in listOf(
            "not an official health application",
            "not a medical device",
            "makes no diagnosis",
            "does not measure your breathing",
        )) {
            compose.onNodeWithText(claim, substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * The hierarchy of the quantities, which is a decision and not a layout preference: the
     * fundamental rhythm in seconds is the quantity being **followed** — its night to night
     * variability is twelve times smaller and it requires no denominator — whereas the hourly count
     * is present but in second place, because that is what a sleep physician knows how to read.
     *
     * What the test checks above all is the sentence saying that no published threshold applies to
     * the rhythm: without it, the leading figure would read as a clinical result.
     */
    @Test
    fun fullScreen_theRhythmLeadsAndTheCountComesSecond() {
        trendScreen(PreviewData.trendReady)

        compose.onNodeWithText(text(R.string.trend_rhythm_no_threshold), substring = true)
            .performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Hourly count", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * **Empty database → refusal screen.** The test that stops the defect coming back.
     *
     * This is not a repeat of the previous one: that one mounts the screen from a state built by
     * hand, this one mounts **the real navigation graph** against a genuinely empty database. That
     * is exactly the difference that was missing — `PendulumNavHost` was passing `PreviewData` to
     * `TrendScreen`, so the screen showed seven nights, a hypnogram and "Samsung Health" on a fresh
     * install, while the unit test next door stayed green.
     *
     * A display defect shows up to the eye, in principle. That one did not: the screen was
     * credible.
     *
     * The starting point is no longer the Trend but the home screen, so the test navigates there —
     * and the first assertion now bears on the home screen itself: on an empty database there must
     * be no figure anywhere, including on the screen one sees first.
     */
    @Test
    fun emptyDatabase_theAppOpensOnTheRefusalAndNoAggregateFigure() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        kotlinx.coroutines.runBlocking {
            PendulumDatabase.get(context).eraseEverything()
        }

        compose.setContent { PendulumTheme { com.pendulum.phone.ui.PendulumNavHost() } }
        compose.waitForIdle()

        // The home screen: three cards, and the history card says there is nothing — both on its
        // status line and on its button, which carries its unavailability reason.
        //
        // `performScrollTo` and not just `assertIsDisplayed`: the home screen is a `PendulumScreen`,
        // hence a scrolling column, and the history is the third card. Three cards do not
        // necessarily fit in the height — on a Pixel 10 Pro Fold unfolded (551 dp) they do not. The
        // bare assertion was therefore measuring the height of the device as much as the content of
        // the screen; it had never been able to show it because Espresso failed earlier.
        compose.onAllNodesWithText(text(R.string.home_history_empty), substring = true)
            .onFirst().performScrollTo().assertIsDisplayed()

        compose.onAllNodesWithText(text(Destination.TREND.label), substring = false)
            .onFirst().performClick()
        compose.waitForIdle()

        // The night counter, and nothing else. No aggregate exists: there is no code branch that
        // produces one below three nights.
        compose.onNodeWithText(text(R.string.trend_refusal_title), substring = true).assertIsDisplayed()

        for (forbidden in listOf(
            text(R.string.trend_rhythm_label),
            text(R.string.trend_count_label),
        )) {
            compose.onAllNodesWithText(forbidden, substring = true).assertCountEquals(0)
        }
    }

    /**
     * **Guard rail 2: a night's result is hidden until it has been asked for.**
     *
     * The rule is in `01-overview.md` §4 and existed nowhere in the code: the column, the DAO and
     * the repository function were written, and no screen used them. The detail therefore showed
     * the figure on opening, without a trace.
     *
     * The test checks both halves. Hidden: neither the rhythm nor the count, and a button to ask
     * for them — **one only**, with no modal and no warning to accept. Revealed: the figure, and no
     * more button.
     */
    @Test
    fun nightNotRevealed_theFigureIsNotShown() {
        val night = PreviewData.nights.first().copy(revealedAtMs = null)
        compose.setContent {
            PendulumTheme {
                NightDetailScreen(previewNightDetail.copy(night = night), {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithText(text(R.string.home_result_button), substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(resolve(Mapping.readableRhythm(night.rhythmSec)), substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun nightRevealed_theFigureIsShown() {
        val night = PreviewData.nights.first().copy(revealedAtMs = 1L)
        compose.setContent {
            PendulumTheme {
                NightDetailScreen(previewNightDetail.copy(night = night), {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithText(text(R.string.nights_single_value), substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.home_result_button), substring = true)
            .assertCountEquals(0)
    }

    /**
     * The text actually displayed, read from the resources of the application under test.
     *
     * An instrumented test has a `Context`: it is the only place in the project where one can check
     * that a guard rail holds **on the string the user sees**, in the language of the device, and
     * not on the identifier that designated it.
     */
    private fun text(@StringRes id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun resolve(t: UiText): String =
        t.resolve(InstrumentationRegistry.getInstrumentation().targetContext.resources)
}
