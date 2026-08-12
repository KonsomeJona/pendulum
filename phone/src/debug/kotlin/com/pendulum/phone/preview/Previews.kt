package com.pendulum.phone.preview

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.pendulum.phone.data.PairingState
import com.pendulum.phone.data.WatchState
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.health.SleepSources
import com.pendulum.phone.ui.HealthState
import com.pendulum.phone.ui.onboarding.DisclaimerPage
import com.pendulum.phone.ui.onboarding.NotificationsPage
import com.pendulum.phone.ui.onboarding.WearingPage
import com.pendulum.phone.ui.onboarding.PairingPage
import com.pendulum.phone.ui.onboarding.RequirementsPage
import com.pendulum.phone.ui.onboarding.SleepSourcePage
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.TonightUi
import com.pendulum.phone.ui.model.WakingState
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.model.TrendUiState
import com.pendulum.phone.ui.nights.Check
import com.pendulum.phone.ui.nights.NightDetailScreen
import com.pendulum.phone.ui.nights.NightListScreen
import com.pendulum.phone.ui.nights.NightDetailUi
import com.pendulum.phone.ui.settings.P1ReportScreen
import com.pendulum.phone.ui.settings.SettingsUi
import com.pendulum.phone.ui.settings.SettingsScreen
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.trend.ComparePeriodsScreen
import com.pendulum.phone.ui.home.HomeScreen
import com.pendulum.phone.ui.trend.TonightCard
import com.pendulum.phone.ui.trend.TrendScreen

/**
 * The Compose previews and the data set that feeds them.
 *
 * ### Why this code is in `src/debug` and not in `src/main`
 *
 * It was there, and the whole navigation of the application was wired onto it: a fresh install
 * opened on a trend of seven nights, a hypnogram and a "Samsung Health" source while the database
 * stayed empty. The wiring was corrected — the screens now read the database — but correcting a
 * piece of wiring guarantees nothing against the next one.
 *
 * The source set does. This file **is not compiled** in the release variant: coming back to it by
 * accident does not produce a misleading screen, it produces a compilation error. That is the same
 * difference as between a warning and a guard rail, and this project makes it everywhere else.
 *
 * The counterpart is real and accepted: Android Studio only renders the previews for the debug
 * variant. That is the default case, and the screenshots of the documentation are produced from a
 * debug build anyway.
 */

// --- from ui/nights/NightDetailScreen.kt ------------------------------------
/** Demonstration set, shared by the previews and by the navigation skeleton. */
val previewNightDetail = NightDetailUi(
    night = PreviewData.nights.first(),
    inBed = "7 h 46",
    chart = PreviewData.night,
    hypnogram = PreviewData.hypnogram,
    metrology = PreviewData.metrology,
    movements = 412,
    plms = 278,
    plmw = 64,
    postureExcluded = 57,
    durationExcluded = 13,
    series = 31,
    imiMedianSec = 23.4,
    checks = listOf(
        qualityCheck(R.string.night_detail_coverage, "99.2%", "97%"),
        qualityCheck(R.string.night_detail_largest_gap, "1.8 s", "5 s"),
        qualityCheck(R.string.night_detail_total_gaps, "11 s", "120 s"),
        qualityCheck(R.string.night_detail_frequency, "50.21 Hz", "50 Hz"),
        qualityCheck(R.string.night_detail_battery_end, "34%", "20%"),
        qualityCheck(R.string.night_detail_worn, "96.4%", "90%"),
        qualityCheck(R.string.night_detail_total_sleep, "6 h 58", "4 h"),
    ),
    appliedRule = text("AASM v3 · algo 1.4.0 · profile “default”"),
)

@Preview(name = "Night — detail", widthDp = 411, heightDp = 1600, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun NightDetailPreview() = PendulumTheme {
    NightDetailScreen(previewNightDetail, {}, {}, {}, {}, {})
}

@Preview(name = "Night — detail without hypnogram", widthDp = 411, heightDp = 1600, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun NightDetailWithoutHypnogramPreview() = PendulumTheme {
    NightDetailScreen(previewNightDetail.copy(hypnogram = PreviewData.hypnogramMissing), {}, {}, {}, {}, {})
}

/**
 * The common case as long as the envelope is not re-read from the raw data: the device state band
 * **alone**, on its axis. It is also the screenshot that best shows that the band stands on its own
 * — it re-ticks the hour axis under itself.
 */
@Preview(name = "Night — device state only", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun NightDetailMetrologyOnlyPreview() = PendulumTheme {
    NightDetailScreen(
        previewNightDetail.copy(chart = null, hypnogram = null),
        {}, {}, {}, {}, {},
    )
}

/** A night from before telemetry: the band says there is none, it does not draw itself empty. */
@Preview(name = "Night — no telemetry", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun NightDetailWithoutTelemetryPreview() = PendulumTheme {
    NightDetailScreen(
        previewNightDetail.copy(chart = null, hypnogram = null, metrology = PreviewData.metrologyMissing),
        {}, {}, {}, {}, {},
    )
}

// --- from ui/nights/NightListScreen.kt --------------------------------------
@Preview(name = "Nights — list", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun NightListPreview() = PendulumTheme {
    NightListScreen(PreviewData.nights, {})
}

// --- from ui/trend/ComparePeriodsScreen.kt ----------------------------------
@Preview(name = "Comparison — not conclusive", widthDp = 411, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ComparisonPreview() = PendulumTheme {
    val a = PreviewData.count.copy(median = 31.0, ciLow = 19.0, ciHigh = 44.0)
    val b = PreviewData.count
    ComparePeriodsScreen(
        result = Aggregate.compare(a, b, -24.0, 5.0, 11),
        unavailableReason = null,
        periodALabel = "1–15 February",
        periodBLabel = "1–15 March",
    )
}

@Preview(name = "Comparison — refused, too few nights", widthDp = 411, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ComparisonRefusedPreview() = PendulumTheme {
    ComparePeriodsScreen(null, text(R.string.compare_period_a) to 3, "1–15 February", "1–15 March")
}

// --- from ui/trend/TonightCard.kt -------------------------------------------
@Preview(name = "Tonight — context sealed", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun TonightPreview() = PendulumTheme {
    TonightCard(PreviewData.tonight, unavailableReason = stringResource(R.string.tonight_seal_done), onSeal = {})
}

@Preview(name = "Tonight — to seal, low battery", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun TonightToSealPreview() = PendulumTheme {
    TonightCard(
        PreviewData.tonight.copy(batteryPct = 62, contextSealed = false),
        unavailableReason = null,
        onSeal = {},
    )
}

// --- from ui/home/HomeScreen.kt ---------------------------------------------
//
// The three cards, in the two states that count: the evening, when there is still something to
// seal, and the morning, when a night is waiting to be revealed.

@Preview(name = "Home - evening, context to seal", widthDp = 411, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun HomeEveningPreview() = PendulumTheme {
    HomeScreen(PreviewData.homeEvening, {}, {}, {}, {}, {})
}

@Preview(name = "Home - morning, result not shown", widthDp = 411, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun HomeMorningPreview() = PendulumTheme {
    HomeScreen(PreviewData.homeMorning, {}, {}, {}, {}, {})
}

// --- from ui/trend/TrendScreen.kt -------------------------------------------
@Preview(name = "Trend — 6 nights, full screen", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun TrendPreview() = PendulumTheme {
    TrendScreen(PreviewData.trendReady, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Trend — refusal below 3 nights", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun TrendRefusalPreview() = PendulumTheme {
    TrendScreen(PreviewData.trendRefusal, {}, {}, {}, {}, {}, {}, {})
}

/**
 * The refusal one will see most often: nine eligible nights, two identified rhythms.
 *
 * It deserves its own preview because its layout is the subject — it must read as the product
 * keeping its word, not as a breakdown, and that cannot be judged from an excerpt of text.
 */
@Preview(name = "Trend — refusal, no identified rhythm", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun TrendRhythmRefusalPreview() = PendulumTheme {
    TrendScreen(PreviewData.trendRefusalRhythm, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Trend — provisional 4 nights", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun TrendProvisionalPreview() = PendulumTheme {
    TrendScreen(
        PreviewData.trendReady.copy(
            rhythm = PreviewData.rhythm.copy(nights = 4, ciLow = 15.0, ciHigh = 30.0),
            count = PreviewData.count.copy(nights = 4, ciLow = 9.0, ciHigh = 38.0),
            position = Aggregate.position(9.0, 38.0, 4),
            qualifiedPeriodicity = null,
        ),
        {}, {}, {}, {}, {}, {}, {},
    )
}

// --- from ui/settings/SettingsScreen.kt -------------------------------------
@Preview(name = "Settings", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun SettingsPreview() = PendulumTheme {
    SettingsScreen(
        SettingsUi(
            rule = text(R.string.settings_rule_aasm),
            preferredSource = text("Samsung Health"),
            profile = text("default"),
            wearingReference = text("4th hole, right leg"),
            autoStop = text("On charger"),
            watch = text("Pixel Watch 3 · 98% · 1.2 GB"),
            healthConnect = text("Sleep read access granted"),
            spaceUsed = text("3.4 GB"),
            appVersion = text("0.1.0"),
            algoVersion = text("1.4.0"),
            theme = text(R.string.settings_theme_dark),
        ),
        {}, {}, {}, {},
    )
}

// --- from ui/settings/P1ReportScreen.kt -------------------------------------
//
// The campaign of the preview set does not cross the gate, and that is the case to show: two
// compliant nights in a row, a third outside the criteria in the middle. A preview where everything
// passes says nothing about the reading one comes here to do.
@Preview(name = "P1 report", widthDp = 411, heightDp = 1500, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun P1ReportPreview() = PendulumTheme {
    P1ReportScreen(PreviewData.p1Report, {})
}


// --- from ui/onboarding/OnboardingPager.kt ----------------------------------
//
// The onboarding previews live here and not next to the composable, for the reason that opens this
// file: they need a watch name and a list of sources, hence fictitious values. Leaving them in
// `src/main` was exactly what had made them creep up into default parameter values, and then be
// displayed to the user.

@Preview(name = "Onboarding 1/5 — notice", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun DisclaimerPreview() = PendulumTheme { DisclaimerPage {} }

@Preview(name = "Onboarding 2/5 — needs", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun RequirementsPreview() = PendulumTheme { RequirementsPage {} }

@Preview(name = "Onboarding 3/5 — no watch paired", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun PairingNoWatchPreview() = PendulumTheme {
    PairingPage(WatchState(PairingState.NO_WATCH), null, { true }, {}, {})
}

@Preview(name = "Onboarding 3/5 — watch paired, app missing", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun PairingAppMissingPreview() = PendulumTheme {
    PairingPage(WatchState(PairingState.APP_MISSING_OR_OUT_OF_RANGE, "Pixel Watch 3"), null, { true }, {}, {})
}

@Preview(name = "Onboarding 3/5 — watch found", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun PairingReadyPreview() = PendulumTheme {
    PairingPage(WatchState(PairingState.READY, "Pixel Watch 3"), null, { true }, {}, {})
}

@Preview(name = "Onboarding 4/5 — two sources", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun SleepSourcePreview() = PendulumTheme {
    SleepSourcePage(
        health = HealthState(
            SleepReader.Availability.READY,
            listOf(
                SleepSources.Observed("com.sec.android.app.shealth", 6, true),
                SleepSources.Observed("com.urbandroid.sleep", 2, false),
            ),
        ),
        preferredSource = "com.sec.android.app.shealth",
        onReread = {}, onChooseSource = {}, onContinue = {},
    )
}

@Preview(name = "Onboarding 4/5 — no source at all", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun SleepSourceNonePreview() = PendulumTheme {
    SleepSourcePage(
        health = HealthState(SleepReader.Availability.READY, emptyList()),
        preferredSource = null,
        onReread = {}, onChooseSource = {}, onContinue = {},
    )
}

@Preview(name = "Onboarding 4/5 — Health Connect absent", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun SleepSourceNoSdkPreview() = PendulumTheme {
    SleepSourcePage(
        health = HealthState(SleepReader.Availability.SDK_UNAVAILABLE, null),
        preferredSource = null,
        onReread = {}, onChooseSource = {}, onContinue = {},
    )
}

/**
 * The step about where to wear the watch. It carries a drawing, so it is the most useful preview in
 * the file: a wrong diagram shows up only to the eye, and a screenshot taken from a device costs a
 * full installation. `heightDp` is generous — the step scrolls.
 */
@Preview(name = "Onboarding 4/6 — where to wear", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun WearingPreview() = PendulumTheme { WearingPage("4th hole", {}, {}) }

@Preview(name = "Onboarding 6/6 — notifications", widthDp = 411, heightDp = 900, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun NotificationsPreview() = PendulumTheme { NotificationsPage(onFinish = {}) }

/**
 * A preview quality check: the label comes from the resources, the value and the threshold are
 * formatted measurements. Every check in this set is met — the detail screen has its own preview
 * for the opposite case.
 */
private fun qualityCheck(@StringRes label: Int, value: String, threshold: String) = Check(
    label = text(label),
    value = text(value),
    threshold = text(threshold),
    ok = true,
)
