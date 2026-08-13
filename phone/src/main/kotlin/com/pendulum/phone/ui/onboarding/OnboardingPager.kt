package com.pendulum.phone.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.data.PairingState
import com.pendulum.phone.data.WatchState
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.health.SleepSources
import com.pendulum.phone.ui.HealthState
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.Progress
import com.pendulum.phone.ui.common.ReasonedButton
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * What onboarding knows, at a given instant.
 *
 * @param startPage the resume step, derived from the persisted counter. It is read only on the
 *   first composition: see [OnboardingPager].
 * @param watch pairing state, **observed** and not read once — step 3 ticks itself when the
 *   application appears on the watch.
 * @param health `null` until Health Connect has been queried. An empty list of sources, on the
 *   other hand, is an answer.
 * @param installation last result of opening the store on the watch, consumed once.
 */
@Immutable
data class OnboardingUi(
    val startPage: Int,
    val watch: WatchState,
    val health: HealthState?,
    val preferredSource: String?,
    val installation: Boolean?,
)

/**
 * What onboarding can ask for. Six callbacks, named, in a single type.
 *
 * Grouping them is not cosmetic: lined up in a signature, `onRereadHealth` and `onInstallOnWatch`
 * have the same shape `() -> Unit`, so swapping them compiles.
 */
@Immutable
data class OnboardingActions(
    val onStepCrossed: (page: Int) -> Unit,
    val onOpenCompanion: () -> Boolean,
    val onInstallOnWatch: () -> Unit,
    val onRereadHealth: () -> Unit,
    val onChooseSource: (String) -> Unit,
    val onStrapReference: (String) -> Unit,
)

/**
 * The first launch: six steps, not skippable, **in a non-swipeable pager**.
 *
 * Progression happens by button only. This is not a gratuitous constraint: a swipeable pager gets
 * skimmed in one gesture, and the screen that would be skimmed first is precisely the disclaimer.
 * There is no "Skip" button.
 *
 * ### Resumable, because abandonment is the main failure mode
 *
 * [OnboardingUi.startPage] comes from the counter of crossed steps persisted in
 * `PendulumPreferences`, and [OnboardingActions.onStepCrossed] increments it **on leaving** each
 * page. Quitting at step 3 comes back there:
 * redoing three disclaimer screens to reach the one that was being looked for is the surest way to
 * get an application uninstalled. The rule itself lives in [OnboardingResume], outside the
 * composable, because that is the part that deserves a test.
 *
 * ### Two groups rather than twelve arguments
 *
 * The state on one side, the actions on the other. The signature carried eleven of them plus the
 * `Modifier`, and the project's architecture rule — "a screen composable takes only its state and
 * lambdas" — was no longer legible in it: five values and six callbacks lined up read like a
 * shopping list, and a mismatch between two neighbouring `() -> Unit` compiles no less well.
 * [OnboardingUi] and [OnboardingActions] carry the separation in the type.
 */
@Composable
fun OnboardingPager(
    uiState: OnboardingUi,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val (startPage, watch, health, preferredSource, installation) = uiState
    val c = LocalPendulumColors.current
    // `initialPage` is read only on the first composition, which is exactly what is wanted: the
    // counter's later emissions — the ones our own page exits provoke — must not take the pager
    // back.
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { OnboardingResume.PAGES })
    val scope = rememberCoroutineScope()
    var strapReference by rememberSaveable { mutableStateOf("") }

    fun next() {
        val page = pagerState.currentPage
        actions.onStepCrossed(page)
        if (page < OnboardingResume.PAGES - 1) {
            scope.launch { pagerState.animateScrollToPage(page + 1) }
        }
    }

    // `safeDrawing`: onboarding is the only screen of the product without a `Scaffold`, therefore
    // the only one for which nobody else reserves the room taken by the system bars. The window
    // having no action bar and the application being edge to edge from Android 15 on, without this
    // line the progress bar passes under the clock and the reminder at the bottom under the
    // navigation bar.
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Progress((pagerState.currentPage + 1) / OnboardingResume.PAGES.toFloat())
        Text(
            stringResource(
                R.string.onboarding_step,
                pagerState.currentPage + 1,
                OnboardingResume.PAGES,
            ),
            style = PendulumType.label,
            color = c.textTertiary,
            modifier = Modifier.padding(horizontal = Spacing.screen.dp, vertical = Spacing.s.dp),
        )
        HorizontalPager(
            state = pagerState,
            // userScrollEnabled = false: the only way forward is the button.
            userScrollEnabled = false,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> DisclaimerPage(onContinue = ::next)
                1 -> RequirementsPage(onContinue = ::next)
                2 -> PairingPage(
                    watch = watch,
                    installation = installation,
                    onOpenCompanion = actions.onOpenCompanion,
                    onInstall = actions.onInstallOnWatch,
                    onContinue = ::next,
                )
                3 -> WearingPage(
                    strapReference = strapReference,
                    onStrapReference = { strapReference = it },
                    onContinue = {
                        // The reference is persisted **on leaving this step** and not at the end of
                        // onboarding: it is entered here, and someone who abandons at the
                        // notifications would otherwise keep an empty field although they filled
                        // it in.
                        actions.onStrapReference(strapReference)
                        next()
                    },
                )
                4 -> SleepSourcePage(
                    health = health,
                    preferredSource = preferredSource,
                    onReread = actions.onRereadHealth,
                    onChooseSource = actions.onChooseSource,
                    onContinue = ::next,
                )
                else -> NotificationsPage(onFinish = ::next)
            }
        }
    }
}

/**
 * The disclaimer: blocking scroll **and** four active confirmations.
 *
 * The button stays disabled until the text has been scrolled to the bottom, and it carries in the
 * meantime the label "Scroll to the bottom", so that the user understands what is expected of them
 * rather than concluding there is a bug.
 *
 * ### Why scrolling is not enough
 *
 * The reference study on privacy policies (Obar & Oeldorf-Hirsch, 543 participants) measures a
 * median reading time of 73 seconds where 29 to 32 minutes would be needed: the gesture actually
 * observed is the thumb scroll. A button that becomes active at the bottom of the scroll therefore
 * attests to a finger movement. The pattern that has evidence of comprehension is the *teach-back*
 * of eConsent in clinical research (Sage Bionetworks, the basis of ResearchKit's consent module),
 * where a 2026 randomised trial gives comprehension non-inferior to face-to-face consent. Four
 * deliberate taps, one per limitation, are worth more than a scroll — and constitute a consent
 * trace of another nature.
 *
 * The content says without detour: this is not an official health application, it is not a medical
 * device, it diagnoses nothing, and no treatment decision must rely on it. Then the four permanent
 * limitations, plainly. This text is the same everywhere — Settings › About displays it again word
 * for word, and the export reproduces it literally.
 */
@Composable
fun DisclaimerPage(onContinue: () -> Unit) {
    val c = LocalPendulumColors.current
    val scroll = rememberScrollState()
    // "At the bottom of the scroll" with a tolerance of a few pixels: on some densities the
    // maximum is never reached exactly, and a button that never becomes active is worse than a
    // skimmed disclaimer.
    val readToEnd by remember {
        derivedStateOf { scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 8 }
    }

    val confirmations = listOf(
        stringResource(R.string.notice_confirm_diagnosis),
        stringResource(R.string.notice_confirm_breathing),
        stringResource(R.string.notice_confirm_one_leg),
        stringResource(R.string.notice_confirm_actigraphy),
    )
    // `rememberSaveable`: a screen rotation must not erase four acknowledgements, without which
    // the guard rail becomes a punishment.
    val ticked = rememberSaveable { mutableStateOf(setOf<Int>()) }
    val allTicked = ticked.value.size == confirmations.size

    Column(Modifier.fillMaxSize().padding(Spacing.screen.dp)) {
        Text(stringResource(R.string.notice_title), style = PendulumType.titleL, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            Paragraph(stringResource(R.string.notice_body), color = c.textPrimary)
            Spacer(Modifier.height(Spacing.l.dp))
            PendulumCard {
                SectionHeader(stringResource(R.string.notice_confirm_title))
                confirmations.forEachIndexed { i, sentence ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = i in ticked.value,
                            onCheckedChange = { isChecked ->
                                ticked.value =
                                    if (isChecked) ticked.value + i else ticked.value - i
                            },
                        )
                        Spacer(Modifier.width(Spacing.s.dp))
                        Text(
                            sentence,
                            style = PendulumType.body,
                            color = c.textPrimary,
                            modifier = Modifier.padding(top = Spacing.sm.dp),
                        )
                    }
                }
                Text(
                    stringResource(
                        R.string.notice_confirm_count,
                        ticked.value.size,
                        confirmations.size,
                    ),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
            }
            Spacer(Modifier.height(Spacing.l.dp))
        }
        Spacer(Modifier.height(Spacing.sm.dp))
        // `ReasonedButton` and not a greyed-out `Button`: the label of the disabled state **carries
        // the instruction** — "scroll to the bottom" — and Material's default disabled colours
        // rendered it at 3.02:1, measured on the device. A user who does not read that sentence
        // concludes there is a bug, which is precisely the reason the KDoc above gives for writing
        // it. The component existed, with the right tints (4.93:1).
        ReasonedButton(
            label = stringResource(R.string.notice_button),
            unavailableReason = when {
                !readToEnd -> stringResource(R.string.notice_button_scroll_first)
                !allTicked -> stringResource(R.string.notice_button_confirm_first)
                else -> null
            },
            onClick = onContinue,
        )
        Spacer(Modifier.height(Spacing.s.dp))
        Text(stringResource(R.string.notice_reminder), style = PendulumType.caption, color = c.textTertiary)
    }
}

@Composable
fun RequirementsPage(onContinue: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.onboarding_needs_title), style = PendulumType.titleL, color = c.textPrimary)
        Requirement(stringResource(R.string.onboarding_needs_watch_title), stringResource(R.string.onboarding_needs_watch_body))
        Requirement(stringResource(R.string.onboarding_needs_sleep_title), stringResource(R.string.onboarding_needs_sleep_body))
        Requirement(stringResource(R.string.onboarding_needs_nights_title), stringResource(R.string.onboarding_needs_nights_body))
        Button(onClick = onContinue, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_needs_button))
        }
    }
}

@Composable
private fun Requirement(title: String, body: String) {
    val c = LocalPendulumColors.current
    PendulumCard {
        Text(title, style = PendulumType.titleM, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.xs.dp))
        Paragraph(body)
    }
}

/**
 * The pairing, and the **three** states that must not be confused.
 *
 * No connected node means that no watch is paired to this phone: the repair is the manufacturer's
 * companion application, and above all **not** the Play Store — installing Pendulum on a watch
 * that is paired to nothing produces an installation nobody will see. A connected node without the
 * capability means the opposite: the watch is there, but our application does not answer on it,
 * and that is where the store listing makes sense. The capability found is the only state that
 * validates the step.
 *
 * ### Nothing blocks during the installation
 *
 * The state comes from a `CapabilityClient.addListener`: the card switches by itself when the
 * capability appears, even if the user has stayed on this screen. There is therefore no "I am
 * done" button — that is, no button one presses too early, and no screen that says no to someone
 * who has done what was asked of them.
 *
 * ### The sensor check row
 *
 * It displayed `50 Hz · FIFO 1024 · wake-up : oui`, hard-coded. It is a **check**, and a check
 * that always displays the same thing checks nothing: it reassures. The watch publishes no
 * `DataItem` describing its sensor, so the row shows dashes and says why.
 */
@Composable
fun PairingPage(
    watch: WatchState,
    installation: Boolean?,
    onOpenCompanion: () -> Boolean,
    onInstall: () -> Unit,
    onContinue: () -> Unit,
) {
    val c = LocalPendulumColors.current
    var companionNotFound by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.onboarding_pairing_title), style = PendulumType.titleL, color = c.textPrimary)

        when (watch.state) {
            PairingState.NO_WATCH -> {
                PendulumCard {
                    Text(
                        stringResource(R.string.onboarding_pairing_no_watch_title),
                        style = PendulumType.titleM,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraph(stringResource(R.string.onboarding_pairing_no_watch_body))
                    if (companionNotFound) {
                        Spacer(Modifier.height(Spacing.s.dp))
                        Paragraph(
                            stringResource(R.string.onboarding_pairing_companion_not_found),
                            color = c.attention,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { companionNotFound = !onOpenCompanion() },
                    shape = PendulumShapes.button,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_pairing_open_companion)) }
            }

            PairingState.APP_MISSING_OR_OUT_OF_RANGE -> {
                PendulumCard {
                    Text(
                        stringResource(R.string.onboarding_pairing_app_missing_title),
                        style = PendulumType.titleM,
                        color = c.textPrimary,
                    )
                    watch.name?.let {
                        Text(it, style = PendulumType.bodyNum, color = c.textSecondary)
                    }
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraph(stringResource(R.string.onboarding_pairing_app_missing_body))
                    installation?.let {
                        Spacer(Modifier.height(Spacing.s.dp))
                        Paragraph(
                            if (it) stringResource(R.string.onboarding_pairing_install_opened)
                            else stringResource(R.string.onboarding_pairing_install_failed),
                            color = if (it) c.textSecondary else c.attention,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onInstall,
                    shape = PendulumShapes.button,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_pairing_install_on_watch)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp))
                    Spacer(Modifier.width(Spacing.s.dp))
                    Paragraph(stringResource(R.string.onboarding_pairing_auto_wait))
                }
            }

            PairingState.READY -> PendulumCard {
                Text(
                    watch.name ?: stringResource(R.string.onboarding_pairing_found_title),
                    style = PendulumType.titleM,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Text(
                    stringResource(R.string.onboarding_pairing_sensor_check_missing),
                    style = PendulumType.mono,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(Spacing.xs.dp))
                Text(
                    stringResource(R.string.onboarding_pairing_sensor_check_note),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
            }
        }

        Button(
            onClick = onContinue,
            enabled = watch.state == PairingState.READY,
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onboarding_pairing_button)) }

        // The only way out of this screen, and it stays: a watch still in delivery must not prevent
        // reading the rest of the onboarding.
        TextButton(onClick = onContinue) { Text(stringResource(R.string.onboarding_pairing_skip)) }
    }
}

/**
 * The sleep source.
 *
 * ### The explanation is an explanation of role, not of plumbing
 *
 * The most disconcerting question of the product is "why is my sleep application not enough?", and
 * the answer that gets through fits in one sentence: the watch at the ankle measures the legs, it
 * does not know when one is asleep; a second device acts as an independent judge of sleep. The
 * complete justification — the circularity between what is counted and what it is divided by,
 * `01-overview.md` §1 — is true but is not read standing up: it sits behind a "learn more".
 *
 * ### Three outcomes, and none of them is a dead screen
 *
 * Health Connect absent is the case of Android 13 and earlier, where it is a Play Store APK and
 * not a piece of the system: installing it is offered. Zero source detected is not a silent
 * failure: it is a help screen listing the applications known to publish sessions. And the list,
 * when it exists, is the **real** list — it used to show two hard-coded names, on phones that had
 * neither of them.
 */
@Composable
fun SleepSourcePage(
    health: HealthState?,
    preferredSource: String?,
    onReread: () -> Unit,
    onChooseSource: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val c = LocalPendulumColors.current
    var learnMore by rememberSaveable { mutableStateOf(false) }

    // Without this launcher, the button does nothing, the Health Connect permissions are never
    // granted, and **every night is scored by the accelerometer mask alone** — that is, exactly the
    // numerator/denominator circularity that the whole project exists to avoid. The defect is
    // silent: the application works, displays figures, and they are wrong in a systematic way. The
    // optional permissions are asked for in the same gesture: without `READ_HEALTH_DATA_HISTORY`,
    // a rescore beyond 30 days loses its denominator.
    val healthLauncher = rememberLauncherForActivityResult(
        contract = SleepReader.permissionRequestContract(),
    ) {
        // The set returned by the contract is not trusted: `availability()` verifies in addition
        // that the platform can honour background reading, which a granted permission does not say.
        onReread()
    }

    LaunchedEffect(Unit) { onReread() }

    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.onboarding_sleep_title), style = PendulumType.titleL, color = c.textPrimary)

        PendulumCard {
            SectionHeader(stringResource(R.string.onboarding_sleep_role_title))
            Paragraph(stringResource(R.string.onboarding_sleep_role_body))
            TextButton(onClick = { learnMore = !learnMore }) {
                Text(stringResource(R.string.onboarding_sleep_role_more))
            }
            if (learnMore) Paragraph(stringResource(R.string.onboarding_sleep_role_more_body))
        }

        when (health?.availability) {
            null -> Unit // Nothing until Health Connect has answered.

            SleepReader.Availability.SDK_UNAVAILABLE -> HealthRepair(
                title = stringResource(R.string.onboarding_sleep_sdk_missing_title),
                body = stringResource(R.string.onboarding_sleep_sdk_missing_body),
                button = stringResource(R.string.onboarding_sleep_install_hc),
            )

            SleepReader.Availability.UPDATE_REQUIRED -> HealthRepair(
                title = stringResource(R.string.onboarding_sleep_update_title),
                body = stringResource(R.string.onboarding_sleep_update_body),
                button = stringResource(R.string.onboarding_sleep_update_button),
            )

            SleepReader.Availability.PERMISSIONS_MISSING -> Button(
                onClick = {
                    healthLauncher.launch(
                        SleepReader.REQUIRED_PERMISSIONS + SleepReader.OPTIONAL_PERMISSIONS,
                    )
                },
                shape = PendulumShapes.button,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_sleep_allow)) }

            SleepReader.Availability.BACKGROUND_READ_UNAVAILABLE -> PendulumCard {
                Text(
                    stringResource(R.string.onboarding_sleep_background_title),
                    style = PendulumType.titleM,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraph(stringResource(R.string.onboarding_sleep_background_body))
            }

            SleepReader.Availability.READY -> SourceList(
                sources = health.sources.orEmpty(),
                preferredSource = preferredSource,
                onChooseSource = onChooseSource,
            )
        }

        ReasonedButton(
            label = stringResource(R.string.onboarding_needs_button),
            unavailableReason = stringResource(R.string.onboarding_sleep_button_blocked)
                .takeIf { health?.availability != SleepReader.Availability.READY },
            onClick = onContinue,
        )
        TextButton(onClick = onContinue) {
            Text(stringResource(R.string.onboarding_sleep_skip))
        }
    }
}

/**
 * The list of the sources actually detected, or the help when there is none.
 *
 * Zero source is not a silent failure: it is the most frequent state of a new phone, and leaving
 * it empty makes one conclude that Pendulum is broken. The screen therefore names the applications
 * known to publish a session, and says what Pendulum reads from them — the session, never the
 * manufacturer's score.
 */
@Composable
private fun SourceList(
    sources: List<SleepSources.Observed>,
    preferredSource: String?,
    onChooseSource: (String) -> Unit,
) {
    val c = LocalPendulumColors.current

    if (sources.isEmpty()) {
        PendulumCard {
            Text(
                stringResource(R.string.onboarding_sleep_none_title),
                style = PendulumType.titleM,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraph(stringResource(R.string.onboarding_sleep_none_body))
            Spacer(Modifier.height(Spacing.m.dp))
            SectionHeader(stringResource(R.string.onboarding_sleep_known_apps_title))
            Paragraph(stringResource(R.string.onboarding_sleep_known_apps_body))
        }
        return
    }

    PendulumCard {
        SectionHeader(stringResource(R.string.onboarding_sleep_sources_detected))
        sources.forEach { source ->
            val chosen = source.packageName == preferredSource
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp)) {
                Text(source.packageName, style = PendulumType.body, color = c.textPrimary)
                Text(
                    stringResource(
                        R.string.onboarding_sleep_coverage,
                        source.nights,
                        SleepSources.OBSERVED_DAYS,
                        stringResource(
                            if (source.hasStages) R.string.onboarding_sleep_coverage_staged
                            else R.string.onboarding_sleep_coverage_total,
                        ),
                    ) + if (chosen) {
                        "   ● " + stringResource(R.string.onboarding_sleep_preferred)
                    } else {
                        ""
                    },
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
                if (!source.hasStages) Paragraph(stringResource(R.string.onboarding_sleep_no_stages))
                if (!chosen) {
                    TextButton(onClick = { onChooseSource(source.packageName) }) {
                        Text(stringResource(R.string.onboarding_sleep_choose))
                    }
                }
            }
        }
    }
}

/**
 * Health Connect absent or too old: a card and an action, never a dead screen.
 *
 * The button opens the provider's listing in the store. `market://` rather than an `https` URL:
 * the application does not declare the Internet permission, and a web URL would in any case be
 * handed to the browser, one more detour to arrive at the same place.
 */
@Composable
private fun HealthRepair(title: String, body: String, button: String) {
    val c = LocalPendulumColors.current
    val localContext = androidx.compose.ui.platform.LocalContext.current
    PendulumCard {
        Text(title, style = PendulumType.titleM, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.s.dp))
        Paragraph(body)
        Spacer(Modifier.height(Spacing.sm.dp))
        OutlinedButton(
            onClick = {
                runCatching {
                    localContext.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(HEALTH_CONNECT_LINK),
                        ),
                    )
                }
            },
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(button) }
    }
}

private const val HEALTH_CONNECT_LINK =
    "market://details?id=com.google.android.apps.healthdata"

/**
 * Where to wear the watch, and what must stay identical from one night to the next.
 *
 * ### Why this step exists now, when the text already existed
 *
 * These instructions lived in the **notifications** step, under a title that announces a permission
 * setting. They are however the four conditions on which the whole comparability of the nights
 * depends, and therefore the entire value of the product: someone crossing the onboarding took
 * away "allow notifications" and not "note your strap hole". Two unrelated subjects on one screen,
 * of which the more important came second.
 *
 * It is placed **just after the pairing** and not at the end: this is the moment when the user has
 * the watch in hand and is about to wear it for the first time. A spatial instruction read three
 * screens too early is an instruction one rarely reads again.
 *
 * The strap reference field followed the instructions rather than staying with the notifications:
 * it belongs to what must stay identical, not to the evening reminder.
 */
@Composable
fun WearingPage(strapReference: String, onStrapReference: (String) -> Unit, onContinue: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.wearing_title), style = PendulumType.titleL, color = c.textPrimary)
        Paragraph(stringResource(R.string.wearing_subtitle))

        // The diagram carries the location, the sentences carry the reason. No text is drawn inside
        // it: a drawn text does not get translated.
        WearingDiagram()

        PendulumCard {
            Paragraph(stringResource(R.string.wearing_point_front))
            Paragraph(stringResource(R.string.wearing_point_not_on_bone))
            Paragraph(stringResource(R.string.wearing_point_orientation))
        }
        Paragraph(stringResource(R.string.wearing_keep_identical))

        OutlinedTextField(
            value = strapReference,
            onValueChange = onStrapReference,
            label = { Text(stringResource(R.string.onboarding_notif_strap_field)) },
            singleLine = true,
            shape = PendulumShapes.field,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onContinue, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wearing_button))
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
fun NotificationsPage(onFinish: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.onboarding_notif_title), style = PendulumType.titleL, color = c.textPrimary)

        // `POST_NOTIFICATIONS` is a runtime permission from Android 13 on: declaring it in the
        // manifest is not enough. Without it, the evening reminder does not appear, and forgetting
        // to start the recording is not a random event — it is forgotten on evenings of tiredness
        // or of travel, that is, on evenings correlated with the result one is trying to measure.
        // Accepting a refusal and saying nothing about it are two different things, and this
        // callback used to be empty. Whatever the answer, the screen was identical before and
        // after: the one gesture the step asks for produced no visible trace, so the only way to
        // know it had worked was to leave the application and open the system settings.
        //
        // The outcome is read from the system rather than remembered from the callback, so a
        // permission granted outside this screen shows correctly on a return.
        val context = LocalContext.current
        fun granted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        var allowed by remember { mutableStateOf(granted()) }
        var answered by remember { mutableStateOf(false) }

        val notificationsLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            allowed = granted()
            answered = true
        }

        Button(
            onClick = { notificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
            shape = PendulumShapes.button,
            enabled = !allowed,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (allowed) R.string.onboarding_notif_allowed
                    else R.string.onboarding_notif_allow
                )
            )
        }

        // Amber and not red on a refusal, following the rule the rest of the product keeps: red is
        // for what is broken, amber for a situation the user chose. Losing the reminder is a
        // situation — the measurement itself is unaffected — and the line says what it costs
        // rather than scolding.
        if (allowed) {
            Paragraph(stringResource(R.string.onboarding_notif_granted), color = c.accent)
        } else if (answered) {
            Paragraph(stringResource(R.string.onboarding_notif_denied), color = c.attention)
        }
        Paragraph(stringResource(R.string.onboarding_notif_reason))
        Button(onClick = onFinish, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_notif_button))
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }
}
