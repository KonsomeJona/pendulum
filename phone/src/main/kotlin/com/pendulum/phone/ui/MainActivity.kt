package com.pendulum.phone.ui

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableLongStateOf
import android.os.SystemClock
import androidx.activity.compose.setContent
import com.pendulum.phone.ui.theme.ThemeMode
import com.pendulum.phone.data.PendulumPreferences
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pendulum.phone.R
import com.pendulum.phone.data.WatchPairing
import com.pendulum.phone.export.P1GateExporter
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.time.Durations
import com.pendulum.phone.ui.export.ExportScreen
import com.pendulum.phone.ui.model.TrendUiState
import com.pendulum.phone.ui.home.HomeScreen
import com.pendulum.phone.ui.nights.NightDetailScreen
import com.pendulum.phone.ui.nights.NightListScreen
import com.pendulum.phone.ui.onboarding.OnboardingActions
import com.pendulum.phone.ui.onboarding.OnboardingUi
import com.pendulum.phone.ui.onboarding.OnboardingPager
import com.pendulum.phone.ui.onboarding.OnboardingResume
import com.pendulum.phone.ui.quiz.ScreeningQuizScreen
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.settings.NoticeScreen
import com.pendulum.phone.ui.settings.ErasureScreen
import com.pendulum.phone.ui.settings.P1ReportScreen
import com.pendulum.phone.ui.settings.SettingsScreen
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.text.FileNames
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.ui.tonight.EveningContextScreen
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.trend.ComparePeriodsScreen
import com.pendulum.phone.ui.trend.TrendScreen
import com.pendulum.phone.work.OpportunisticTrigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The phone's single activity.
 *
 * `launchMode="singleTask"` is declared in the manifest: the morning notification has to come back
 * to the existing instance, not stack a second one.
 */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { PendulumPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The theme chosen in the settings, applied at the root.
            //
            // `PendulumTheme` was called **without an argument**, so always on its dark default.
            // The preference existed from end to end though — stored, written by the settings, and
            // displayed by them with its value: the screen showed "System" or "Light" and the
            // application stayed dark. A setting that displays a state it does not have is of the
            // same family as the mute buttons already removed, only more misleading: this one
            // answers something.
            //
            // `null` as long as the DataStore has not returned its first value: dark is kept until
            // then, rather than opening in light and switching to dark an instant later — at 7 am,
            // that flash is exactly what the dark theme exists to avoid.
            val token by prefs.theme.collectAsStateWithLifecycle(initialValue = null)
            PendulumTheme(mode = themeMode(token)) {
                PendulumPortal()
            }
        }
    }

    /**
     * The second opportunistic trigger of the Health Connect read.
     *
     * `FetchSchedule`'s retry ladder uses an exponential backoff because it does not know when the
     * hypnogram will arrive. But synchronisation **is correlated with usage**: the source app
     * writes to Health Connect when it is opened, that is, often a few seconds before Pendulum is
     * opened to look at the night. Waiting for the T+4 h rung when the data arrived at T+2 h 05
     * costs two hours of perceived latency for nothing.
     *
     * The first trigger is plugging in the charger (`PowerConnectedReceiver`). Neither of the two
     * consumes the ladder: see `FetchSchedule.OPPORTUNISTIC_INDEX`.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            runCatching { OpportunisticTrigger.trigger(this@MainActivity) }
        }
    }
}

/**
 * The portal: the onboarding, or the application.
 *
 * ### What it repairs
 *
 * `OnboardingPager` was written, complete, in five steps, and **had no caller at all**. The
 * consequence was not cosmetic: the application's only `rememberLauncherForActivityResult` lived
 * in that unreachable screen, so no user path ever granted the Health Connect permissions. Every
 * night was then scored by the accelerometric mask alone, without anything saying so — the
 * numerator/denominator circularity that the whole project exists to avoid.
 *
 * ### Nothing until the counter has been read
 *
 * The step counter comes from the DataStore, hence from an asynchronous read. Composing the
 * navigation while waiting would make it appear for a fraction of a second before the onboarding
 * replaced it, which teaches the user that the application flickers on start-up. Same rule as on
 * the home screen: nothing is displayed rather than a provisional state.
 */
@Composable
fun PendulumPortal() {
    val vm: OnboardingViewModel = viewModel()
    val step by vm.step.collectAsStateWithLifecycle()

    val s = step ?: return
    if (!OnboardingResume.onboardingPending(s)) {
        PendulumNavHost()
        return
    }

    val watch by vm.watch.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val preferredSource by vm.preferredSource.collectAsStateWithLifecycle()
    val installation by vm.installation.collectAsStateWithLifecycle()
    val context = LocalContext.current

    OnboardingPager(
        uiState = OnboardingUi(
            startPage = OnboardingResume.startPage(s),
            watch = watch,
            health = health,
            preferredSource = preferredSource,
            installation = installation,
        ),
        actions = OnboardingActions(
            onStepCrossed = vm::crossStep,
            onOpenCompanion = { WatchPairing.openCompanionApp(context) },
            onInstallOnWatch = vm::installOnWatch,
            onRereadHealth = vm::rereadHealth,
            onChooseSource = vm::chooseSource,
            onStrapReference = vm::setStrapReference,
        ),
    )
}

/**
 * Three root destinations, and **Home as the start destination**.
 *
 * Three and not four: beyond that the hierarchy dilutes and the user starts searching
 * (`06-interface.md` §6). The questionnaire, the night list, a night's detail, the
 * comparison and the export are stacked destinations, without a navigation bar — they are tasks,
 * not places.
 *
 * ### Why Trend is no longer the home screen
 *
 * It was, and the start screen then mixed two incompatible cognitive regimes: the daily gesture —
 * fast, memorised, done one-handed — and the reading of a statistical result, slow and heavy. The
 * first paid for the second: you came to press a button and read a figure on the way, at the hour
 * when you are least able to judge it. Trend remains a root destination; it is no longer the front
 * door.
 *
 * ### Why Nights leaves the bar
 *
 * The night list is a consultation, not a place to stay: you go there from the HISTORY card on the
 * home screen, with a question in mind, and you come back. Keeping a permanent entry for it would
 * have made four root destinations, which `06-interface.md` §6 excludes explicitly — "three
 * destinations are enough; beyond that the hierarchy dilutes".
 *
 * No floating action button: there is no creation action on the phone. Recording starts on the
 * watch, and the only gate is the sealing of the context.
 */
enum class Destination(val route: String, @StringRes val label: Int) {
    HOME("home", R.string.home_title),
    TREND("trend", R.string.trend_title),
    SETTINGS("settings", R.string.settings_title),
}

/** The night list, reached from the HISTORY card. Stacked: it is a consultation. */
const val ROUTE_NIGHTS = "nights"

/**
 * The evening form. Stacked, without a navigation bar: it is a task, not a place.
 *
 * It is not a fourth navigation entry, and that is not merely a question of hierarchy. You do not
 * "go" into the evening context the way you go into the settings: you fill it in once, in the
 * evening, and it becomes inaccessible — the SQLite triggers refuse any modification afterwards. A
 * permanent entry to a screen that can only be opened once a day, and that fails if you open it
 * twice, would be an invitation to error.
 */
const val ROUTE_EVENING = "evening"

/**
 * The P1 gate report, reached from Settings > Measurement. Stacked, without a navigation bar: you
 * go there to check a figure, and you come back.
 */
const val ROUTE_P1 = "p1"

/**
 * The notice, re-read from Settings > About.
 *
 * `06-interface.md` requires it to stay permanently accessible: somebody consulting a figure three
 * months later must be able to re-read, in two gestures, why that figure is not a diagnosis. The
 * row existed and called a `{}`.
 */
const val ROUTE_NOTICE = "notice"

/**
 * Total erasure. Stacked, and not a dialog laid over the settings: the text saying what goes away
 * runs to ten lines, and a ten-line modal gets dismissed without being read.
 */
const val ROUTE_ERASE = "erase"

/**
 * The three icons are drawn by hand, in outline, rather than taken from an imported set.
 *
 * Two reasons. Material's `Filled` set is ruled out on principle — it is visually heavy and reads
 * as "consumer" — and the complete `Outlined` set is a multi-megabyte dependency for three glyphs.
 * Three strokes are enough, and they follow exactly the stroke weight of the rest of the interface.
 */
private fun DrawScope.destinationIcon(d: Destination, color: Color) {
    val e = size.minDimension * 0.09f
    val s = size.minDimension
    when (d) {
        // Home: a roof and its wall. The most-read glyph in all of consumer computing, and that is
        // exactly the reason to take it — this entry must call for no interpretation at all, since
        // it is the one aimed at at 11 pm and at 7 am.
        Destination.HOME -> {
            drawLine(color, Offset(s * 0.14f, s * 0.46f), Offset(s * 0.5f, s * 0.18f), e)
            drawLine(color, Offset(s * 0.5f, s * 0.18f), Offset(s * 0.86f, s * 0.46f), e)
            drawLine(color, Offset(s * 0.24f, s * 0.42f), Offset(s * 0.24f, s * 0.82f), e)
            drawLine(color, Offset(s * 0.76f, s * 0.42f), Offset(s * 0.76f, s * 0.82f), e)
            drawLine(color, Offset(s * 0.2f, s * 0.82f), Offset(s * 0.8f, s * 0.82f), e)
        }
        // Trend: three dots at different heights, with no line joining them — exactly what the
        // trend chart does, and for the same reason.
        Destination.TREND -> {
            drawCircle(color, e, Offset(s * 0.2f, s * 0.72f))
            drawCircle(color, e, Offset(s * 0.5f, s * 0.38f))
            drawCircle(color, e, Offset(s * 0.8f, s * 0.55f))
        }
        // Settings: two sliders, the most direct metaphor for parameters.
        Destination.SETTINGS -> {
            drawLine(color, Offset(s * 0.15f, s * 0.35f), Offset(s * 0.85f, s * 0.35f), e)
            drawLine(color, Offset(s * 0.15f, s * 0.68f), Offset(s * 0.85f, s * 0.68f), e)
            drawCircle(color, e * 1.6f, Offset(s * 0.62f, s * 0.35f))
            drawCircle(color, e * 1.6f, Offset(s * 0.34f, s * 0.68f))
        }
    }
}

/** `E-HC-02`: the sleep read permission has been revoked. See `Situations.sleep`. */
private const val CODE_PERMISSION_REVOKED = "E-HC-02"


/**
 * The night the status strip is talking about. It comes from the state and not from a second read:
 * the action must apply to the night the user has in front of them, not to the most recent one at
 * the moment they press.
 */
private fun stripSession(state: TrendUiState): String? = when (state) {
    is TrendUiState.Ready -> state.wakingSession
    is TrendUiState.Refusal -> state.wakingSession
    TrendUiState.Loading -> null
}

private fun sleepSituationCode(state: TrendUiState): String? = when (state) {
    is TrendUiState.Ready -> state.sleepSituation?.code
    is TrendUiState.Refusal -> state.sleepSituation?.code
    TrendUiState.Loading -> null
}

/**
 * Opens the Health Connect screen. `runCatching` because the action does not resolve everywhere:
 * on a device where Health Connect has been uninstalled between the display of the card and the
 * press, an uncaught `ActivityNotFoundException` would crash the application on a help button.
 */
private fun openHealthConnect(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
        )
    }
}

@Composable
fun PendulumNavHost(nav: NavHostController = rememberNavController()) {
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route

    Scaffold(
        bottomBar = {
            // The bar disappears on stacked destinations: a task under way does not offer to leave
            // for somewhere else under a distracted thumb.
            if (Destination.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    Destination.entries.forEach { d ->
                        NavigationBarItem(
                            selected = currentRoute == d.route,
                            onClick = {
                                nav.navigate(d.route) {
                                    popUpTo(Destination.HOME.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                val tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                Canvas(Modifier.size(22.dp)) { destinationIcon(d, tint) }
                            },
                            label = { Text(stringResource(d.label), style = PendulumType.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Destination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.HOME.route) {
                val vm: HomeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val startFeedback by vm.startFeedback.collectAsStateWithLifecycle()
                val homeContext = LocalContext.current

                // The same mechanism as on the trend screen, and for the same reason: the dialog
                // stops being shown after two refusals, and you then have to take the user into
                // Health Connect rather than offer a request that would show nothing.
                var homeRequestedAt by remember { mutableLongStateOf(0L) }
                var homeSuppressed by remember { mutableStateOf(false) }
                val homeLauncher = rememberLauncherForActivityResult(
                    contract = SleepReader.permissionRequestContract(),
                ) { granted ->
                    val elapsed = SystemClock.elapsedRealtime() - homeRequestedAt
                    homeSuppressed = !granted.containsAll(SleepReader.REQUIRED_PERMISSIONS) &&
                        elapsed < SleepReader.SUPPRESSED_DIALOG_MS
                    vm.rereadHealth()
                }

                // The permission is granted outside the application: on return, we re-read.
                LifecycleResumeEffect(Unit) {
                    vm.rereadHealth()
                    onPauseOrDispose { }
                }

                // Nothing until the first read has completed. No animated skeleton, no empty
                // cards: three cards filling in after the fact would move exactly what this screen
                // exists to stop moving.
                state?.let { home ->
                    HomeScreen(
                        state = home,
                        onSeal = { nav.navigate(ROUTE_EVENING) },
                        onEndOfNight = { home.sessionToClose?.let(vm::endOfNight) },
                        onStart = vm::startOnWatch,
                        startFeedback = startFeedback,
                        // A single gesture: the trace is written and the detail screen opens in
                        // the same movement. Two taps for a figure one is entitled to see would be
                        // a toll, not a speed bump.
                        onReveal = { hex ->
                            vm.reveal(hex)
                            nav.navigate("night/$hex")
                        },
                        onHistory = { nav.navigate(ROUTE_NIGHTS) },
                        onSleepSituation = {
                            if (homeSuppressed) {
                                SleepReader.manualPermissionsIntent(homeContext)
                                    ?.let(homeContext::startActivity)
                                    ?: openHealthConnect(homeContext)
                            } else {
                                homeRequestedAt = SystemClock.elapsedRealtime()
                                homeLauncher.launch(
                                    SleepReader.REQUIRED_PERMISSIONS +
                                        SleepReader.OPTIONAL_PERMISSIONS,
                                )
                            }
                        },
                    )

                    // The start feedback clears itself in two ways.
                    //
                    // By time first: it is the result of a gesture, not a state. A sentence that
                    // stayed under the button until the next day would end up describing a request
                    // that has nothing to do with the current night any more.
                    //
                    // By the transition next: as soon as the phase changes — typically when the
                    // watch opens its session and the home screen moves to RECORDING — the card
                    // says for itself what is going on, and repeating "request sent" under a card
                    // showing "RECORDING" would make you wonder which of the two to believe.
                    LaunchedEffect(startFeedback) {
                        if (startFeedback != null) {
                            delay(Durations.ACTIVE.startFeedbackMs)
                            vm.startFeedbackConsumed()
                        }
                    }
                    LaunchedEffect(home.phase) { vm.startFeedbackConsumed() }
                }
            }
            composable(Destination.TREND.route) {
                val vm: TrendViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current

                // Repairing `E-HC-02` is a permission request, not a link to a screen: sending
                // somebody into the Health Connect settings to hunt for a checkbox when the system
                // knows how to show the dialog is exactly the kind of detour that makes people
                // give up.
                // The dialog stops appearing after two refusals, and the contract then returns
                // immediately, without showing anything. No API distinguishes that case from an
                // ordinary refusal: the elapsed time is the only signal. Below the threshold, we
                // stop offering a request that can no longer succeed and we show the manual path.
                var requestedAt by remember { mutableLongStateOf(0L) }
                var dialogSuppressed by remember { mutableStateOf(false) }
                val healthLauncher = rememberLauncherForActivityResult(
                    contract = SleepReader.permissionRequestContract(),
                ) { granted ->
                    val elapsed = SystemClock.elapsedRealtime() - requestedAt
                    val missing = !granted.containsAll(SleepReader.REQUIRED_PERMISSIONS)
                    dialogSuppressed =
                        missing && elapsed < SleepReader.SUPPRESSED_DIALOG_MS
                    vm.rereadHealth()
                }

                TrendScreen(
                    state = state,
                    onNight = { nav.navigate("night/$it") },
                    onNights = { nav.navigate(ROUTE_NIGHTS) },
                    onCompare = { nav.navigate("compare") },
                    onQuestionnaire = { nav.navigate("quiz") },
                    onExport = { nav.navigate("export") },
                    onWakingAction = { stripSession(state)?.let(vm::retryWaking) },
                    onSleepSituation = {
                        val permission = sleepSituationCode(state) == CODE_PERMISSION_REVOKED
                        when {
                            permission && !dialogSuppressed -> {
                                requestedAt = SystemClock.elapsedRealtime()
                                healthLauncher.launch(
                                    SleepReader.REQUIRED_PERMISSIONS +
                                        SleepReader.OPTIONAL_PERMISSIONS,
                                )
                            }
                            // The system will show nothing more: the only gesture left is manual,
                            // and the user has to be taken there rather than told about it.
                            permission -> SleepReader.manualPermissionsIntent(context)
                                ?.let(context::startActivity)
                                ?: openHealthConnect(context)
                            else -> openHealthConnect(context)
                        }
                    },
                    permissionSuppressed = dialogSuppressed,
                )
            }
            // The night list: stacked, reached from the HISTORY card on the home screen.
            composable(ROUTE_NIGHTS) {
                val vm: NightsViewModel = viewModel()
                val nights by vm.nights.collectAsStateWithLifecycle()
                NightListScreen(nights, onNight = { nav.navigate("night/$it") })
            }
            composable(Destination.SETTINGS.route) {
                val vm: SettingsViewModel = viewModel()
                val settings by vm.settings.collectAsStateWithLifecycle()

                // Reading a night bundle back in. `OpenDocument` and not `GetContent`: the first
                // returns a persistable document `Uri` and lets you choose from any provider, the
                // second goes through a share intent that not all of them honour. The filter is
                // `*/*` because a `.bundle` has no registered MIME type: filtering on an unknown
                // type greys out the very file being looked for.
                val opener = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(vm::importNight) }

                // The space used is re-read on every entry to the screen: it comes from a disk
                // read, and an erasure or an import may have taken place between two visits.
                LaunchedEffect(Unit) { vm.rereadSpace() }

                SettingsScreen(
                    settings,
                    onReadNoticeAgain = { nav.navigate(ROUTE_NOTICE) },
                    onErase = { nav.navigate(ROUTE_ERASE) },
                    onImportNight = { opener.launch(arrayOf("*/*")) },
                    onP1Report = { nav.navigate(ROUTE_P1) },
                )
            }
            // The notice, read-only. No blocking scroll and no checkboxes: the gate is the
            // onboarding's, and asking for it again on every re-reading would make re-reading a
            // chore, hence something one does not do.
            composable(ROUTE_NOTICE) { NoticeScreen() }
            composable(ROUTE_ERASE) {
                val vm: ErasureViewModel = viewModel()
                val spaceUsed by vm.spaceUsed.collectAsStateWithLifecycle()
                val erased by vm.erased.collectAsStateWithLifecycle()
                ErasureScreen(spaceUsed = spaceUsed, erased = erased, onErase = vm::erase)
            }
            // The P1 gate report. Stacked: it is a check, not a place.
            composable(ROUTE_P1) {
                val vm: P1ReportViewModel = viewModel()
                val report by vm.report.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                // The same SAF path as a night's export: the user chooses the location, gesture by
                // gesture. The application has no directory of its own in shared storage, and no
                // network permission to send the file anywhere else.
                val creator = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv"),
                ) { uri ->
                    uri?.let { scope.launch { P1GateExporter.exportTo(context, it) } }
                }

                // Nothing until the read has completed: a verdict displayed before it is computed
                // is a verdict that has been read wrong once.
                report?.let {
                    P1ReportScreen(
                        state = it,
                        onExport = { creator.launch(FileNames.P1_REPORT) },
                    )
                }
            }
            // Stacked destinations: no navigation bar, they are tasks.
            //
            // The evening form is one of them, and the most consequential: it is the product's
            // only gate. Until it has been filled in and sealed, the watch refuses to start — not
            // as a warning, but because the `DataItem` that `Preflight` expects does not exist.
            composable(ROUTE_EVENING) {
                val vm: EveningViewModel = viewModel()
                val reference by vm.strapReference.collectAsStateWithLifecycle()
                val result by vm.result.collectAsStateWithLifecycle()

                EveningContextScreen(
                    strapReference = reference,
                    result = result,
                    onSeal = { vm.seal(it) },
                    onCancel = { nav.popBackStack() },
                )

                // The return only happens on `Sealed`, and on that alone.
                //
                // The three outcomes used to be treated identically — the screen closed — and the
                // other two are not successes. `PublicationFailed` is the worst of the three
                // because it is silent and contradictory: the database has the context, so the
                // home screen says it is sealed, while the watch, which did not receive the
                // `DataItem`, keeps asking for the evening form. Closing the screen at that moment
                // means sending somebody off to spend ten minutes wondering why START stays
                // blocked.
                //
                // On the other two, the screen stays and says what to do. The result is therefore
                // not consumed: it carries what the card displays, and the screen can now only be
                // left through "Not now".
                LaunchedEffect(result) {
                    if (result == SealingResult.Sealed) {
                        vm.resultConsumed()
                        nav.popBackStack()
                    }
                }
            }
            composable("night/{hex}") { entry ->
                val hex = entry.arguments?.getString("hex").orEmpty()
                val vm: NightDetailViewModel = viewModel()
                val detail by vm.detail.collectAsStateWithLifecycle()
                val feedback by vm.feedback.collectAsStateWithLifecycle()
                LaunchedEffect(hex) { vm.load(hex) }

                // Nothing until the read has completed. No animated skeleton, no default values: a
                // detail screen showing zeros for two hundred milliseconds teaches you to read
                // figures before they are true.
                // The two ways out of a night, by the same SAF path as everything else:
                // `ACTION_CREATE_DOCUMENT`, location chosen by the user. The application writes
                // into no shared directory of its own initiative and does not declare the
                // `INTERNET` permission — the file can only go where it was asked to go.
                // The proposed name is held until the picker returns: it is the one the feedback
                // displays afterwards. Same reason as on the export screen — recomputing it in the
                // callback would give a different name if choosing the location crossed midnight.
                var proposedName by remember { mutableStateOf("") }
                val reportCreator = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/markdown"),
                ) { uri -> uri?.let { vm.exportReport(hex, it, proposedName) } }
                val bundleCreator = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri -> uri?.let { vm.exportBundle(hex, it, proposedName) } }

                detail?.let { d ->
                    val day = Mapping.isoDay(
                        d.night.startWallMs,
                        java.time.ZoneId.systemDefault().id,
                    )
                    NightDetailScreen(
                        detail = d,
                        onSeeTrend = { nav.popBackStack() },
                        onApplyToAll = vm::applyToAll,
                        onReveal = { vm.reveal(hex) },
                        onExportReport = {
                            proposedName = FileNames.nightReport(day)
                            reportCreator.launch(proposedName)
                        },
                        onExportBundle = {
                            proposedName = FileNames.nightBundle(day)
                            bundleCreator.launch(proposedName)
                        },
                        writeFeedback = feedback,
                    )
                }
            }
            // The comparison of two periods has no date picker, hence no periods to compare. Up to
            // now the screen displayed a refusal accompanied by two fabricated labels — "1-15
            // February", "1-15 March" — that is, the same defect as the navigation wired onto the
            // preview data set: not an invented figure, but an invented context, which reads just
            // as well as real data.
            //
            // The labels are therefore empty as long as the picker does not exist.
            // `Aggregate.compare` is written and tested and waits for nothing else; it is a feature
            // still to be built, not a wiring to be laid, and the screen must say it is not there
            // rather than mimic it.
            composable("compare") {
                // `unavailableReason` is null and not a fabricated pair: there is no period A, so
                // there is no count of its nights either. Passing
                // `MIN_NIGHTS_COMPARISON` in that slot printed the threshold where a measurement
                // belongs — "Period A: 5 eligible nights. At least 5 are needed in each period."
                // The screen carries its own text for this state.
                ComparePeriodsScreen(
                    result = null,
                    unavailableReason = null,
                    periodALabel = "",
                    periodBLabel = "",
                )
            }
            composable("quiz") {
                val vm: QuizViewModel = viewModel()
                val outcome by vm.outcome.collectAsStateWithLifecycle()
                ScreeningQuizScreen(
                    outcome = outcome,
                    onYes = { vm.answer(true) },
                    onNo = { vm.answer(false) },
                    onReview = vm::review,
                )
            }
            // The export of the report for the doctor — the only purpose `README.md` considers
            // defensible, and one that no gesture reached: this screen's five lambdas were empty
            // and `ReportExporter` had no caller.
            composable("export") {
                val vm: ExportViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()

                // The proposed name is held until the picker returns: it is the one the screen
                // displays afterwards. Recomputing it in the callback would give a different name
                // if choosing the location crossed midnight.
                var proposedName by remember { mutableStateOf("") }
                val creator = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/markdown"),
                ) { uri -> uri?.let { vm.save(it, proposedName) } }

                // Nothing until the read has completed: an eligible night counter displayed at
                // zero before being read would show the button disabled with its reason, then
                // enabled — that is, a refusal that retracts itself.
                state?.let {
                    ExportScreen(
                        state = it,
                        onQuestionnaire = vm::setQuestionnaire,
                        onExcluded = vm::setExcluded,
                        onSave = {
                            proposedName = vm.fileName()
                            creator.launch(proposedName)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The stored token to the theme mode. `null` — first read not yet completed — means dark, like the
 * product's default.
 */
private fun themeMode(token: String?): ThemeMode = when (token) {
    PendulumPreferences.THEME_SYSTEM -> ThemeMode.System
    PendulumPreferences.THEME_LIGHT -> ThemeMode.Light
    else -> ThemeMode.Dark
}
