package com.pendulum.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.wear.transfer.DataLayerTransfer
import com.pendulum.wear.transfer.RemoteCommands
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.pendulum.format.wire.StopReason
import com.pendulum.wear.R
import com.pendulum.wear.record.isFailure
import com.pendulum.wear.record.Issue
import com.pendulum.wear.record.IssueId
import com.pendulum.wear.record.Preflight
import com.pendulum.wear.record.PreflightResult
import com.pendulum.wear.record.RecordPhase
import com.pendulum.wear.record.RecordUiState
import com.pendulum.wear.record.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * **A single, static screen.** No navigation, no list, no chart, no history, no result at all: the
 * watch is an instrument for checking at bedtime and for taking note on waking, the analysis lives
 * on the phone.
 *
 * Three reasons to have no animation, in this order of weight:
 *
 * 1. **Energy.** Every recomposition wakes the SoC. Over eight hours, even a discreet animation
 *    costs more than the 50 Hz acquisition itself.
 * 2. **Contamination of the measurement.** A screen that makes you want to look at it makes you
 *    want to move the leg. The screen is at the ankle: consulting it means leaning over, which
 *    means producing an artefact. It has to be boring on purpose.
 * 3. **The absence of any benefit.** There is nothing to watch in real time. The figures shown are
 *    checks that things are working, read twice a night at most.
 *
 * The criterion is testable: **between going to bed and waking, the UI layer must cause no
 * recomposition.** The screen is off, [collectAsStateWithLifecycle] is stopped, and the service
 * emits into the void.
 */
@Composable
fun RecordRoute(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by RecordingState.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var preflight by remember { mutableStateOf<PreflightResult?>(null) }
    var phoneOpening by remember { mutableStateOf(PhoneOpening.None) }
    val scope = rememberCoroutineScope()

    // A single run per display, never periodic: the preflight does I/O and one Data Layer read,
    // which is not something to repeat in a loop under the duvet.
    LaunchedEffect(refreshKey, state.phase) {
        preflight = if (state.phase == RecordPhase.IDLE) {
            withContext(Dispatchers.IO) { Preflight.check(context) }
        } else {
            null
        }
    }

    // ------------------------------------------------------------------------------------
    // Three revalidation triggers. None of them arms while the screen is off.
    // ------------------------------------------------------------------------------------

    // 1. A permission granted from inside the application. The callback **revalidates the whole
    //    preflight** and not only the permission just granted: allowing notifications while the
    //    watch was filling up says nothing about disk space.
    val notificationsRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { refreshKey++ }

    // Asked when the screen opens and not at START: a system prompt at bedtime, screen at the
    // ankle, is exactly what we do not want to make someone lying down read. It used to live in
    // `MainActivity.onCreate` with an empty callback — that is, granting it updated nothing and
    // the blocker stayed on screen.
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 2 and 3, in a single effect because they share the same lifetime window: **the screen on**.
    //
    // 2. Coming back from anywhere — and above all from the system settings, where the user can
    //    grant the permission outside any `ActivityResultLauncher`. Without this, the "Open
    //    settings" button returns to a screen that carries on showing the blocker just lifted, and
    //    the only way out is to kill the application.
    //
    // 3. The phone seals the evening context. It is the most frequent blocker, and the only one
    //    the user lifts from another device: without this listener, one has to come back to the
    //    watch and press "Check again" to see an already-lifted blocker disappear. A Data Layer
    //    listener, not a periodic poll.
    //
    // `LifecycleResumeEffect` rather than a hand-wired `LifecycleEventObserver`: it is the same
    // observer on ON_RESUME, in one line, with its release. And not `repeatOnLifecycle`, which is
    // there to collect a flow from a non-Compose scope.
    //
    // **The listener is registered on resume and removed on pause, not on the fate of the
    // composition.** A `DisposableEffect` would leave it armed as long as the activity exists, so
    // with the screen off, so during the night: the phone republishing an item at 2 a.m. would then
    // wake the process to recompute a preflight nobody is looking at. The "no recomposition between
    // going to bed and waking" criterion is lost by exactly this kind of detail. And an unchanged
    // preflight produces a `PreflightResult` structurally equal to the previous one, which the
    // structural equality of `mutableStateOf` absorbs without recomposing.
    LifecycleResumeEffect(context) {
        refreshKey++

        // The outcome of the last "open on phone" is a **result of a gesture**, not a state, and it
        // was never cleared. So the confirmation stayed on screen across a resume, with its own
        // button greyed out behind it — and the person most likely to come back is precisely the
        // one whose phone did not light up. Retrying was the only useful thing left, and it was the
        // one thing the screen forbade. Coming back to this screen means the question is being
        // asked again.
        phoneOpening = PhoneOpening.None

        val client = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { refreshKey++ }
        client.addListener(
            listener,
            Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME)
                .path(DataLayerTransfer.CONTEXT_PREFIX).build(),
            DataClient.FILTER_PREFIX,
        )

        onPauseOrDispose { client.removeListener(listener) }
    }

    RecordScreen(
        state = state,
        preflight = preflight,
        onStart = onStart,
        onStop = onStop,
        onRecheck = { refreshKey++ },
        onOpenSettings = onOpenSettings,
        onRequestNotifications = {
            notificationsRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onOpenPhone = {
            scope.launch {
                // The result is displayed, not assumed: "opened" when the phone did receive the
                // request, "phone unreachable" otherwise. Announcing a success when nothing was
                // opened sends someone looking for a screen that never lit up.
                phoneOpening = if (RemoteCommands.openPhone(context)) {
                    PhoneOpening.Sent
                } else {
                    PhoneOpening.Unreachable
                }
            }
        },
        opening = phoneOpening,
    )
}

@Composable
fun RecordScreen(
    state: RecordUiState,
    preflight: PreflightResult?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRecheck: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestNotifications: () -> Unit = {},
    onOpenPhone: () -> Unit = {},
    opening: PhoneOpening = PhoneOpening.None,
) {
    // The content must not touch the glass at any scroll position, and Google checks it: the
    // rejection reason "no text or control is cut off by the edges of the screen" is tested on the
    // smallest round display, font size at maximum.
    //
    // Two values, and a single thing that makes them sufficient.
    //
    // 14.6 % is the inset of the **inscribed square**: for a diameter D, the inscribed square has
    // side D/sqrt(2), so (1 - 1/sqrt(2))/2 = 14.6 % of inset per edge. The horizontal 18 % comes
    // from a stricter calculation: the content scrolls, so a line always ends up passing near the
    // top or the bottom, where the chord 2R*sqrt(1-t²) narrows.
    //
    // **But no margin is enough if it is on the wrong side of the scroll.** That is the defect
    // which survived two successive corrections (16 dp, then 10.4 %): `padding` was applied
    // **after** `verticalScroll` in the chain, so it belonged to the scrolling content. It only
    // kept the text away from the glass at the rest position; as soon as one scrolled, the margin
    // went away with the content and the top line was sliced off by the round display. What a real
    // screenshot showed: "Free space 12.2 GB" cut clean through the middle of the glyphs.
    //
    // Swapping the two modifiers is enough, and it is provable rather than merely observable:
    // `padding` before `verticalScroll` makes the padded area the **viewport**, and
    // `verticalScroll` clips its content to the viewport. The text therefore disappears into the
    // black at the edge of the rectangle, never under the glass. What remains is to prove that this
    // rectangle fits inside the circle — that is the only calculation that counts:
    //
    //   half-width  = (1 - 2*0.18)/2 * D  = 0.64 R
    //   half-height = (1 - 2*0.146)/2 * D = 0.708 R
    //   corner      = R*sqrt(0.64² + 0.708²) = 0.954 R  <  R
    //
    // The four corners are strictly interior. No viewport pixel reaches the glass, at any scroll
    // position and at any font scale.
    //
    // A defect invisible in the preview, which is square, and invisible at rest. It took scrolling
    // on a real round display to see it.
    val config = LocalConfiguration.current
    val isRound = config.isScreenRound
    val side = config.screenWidthDp.dp
    val marginH = if (isRound) side * 0.18f else 16.dp
    val marginV = if (isRound) side * 0.146f else 24.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // The order is the fix. Do not swap these two lines.
            .padding(horizontal = marginH, vertical = marginV)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.phase) {
            RecordPhase.RECORDING -> RecordingContent(state, onStop)
            RecordPhase.FINALIZING -> Text(
                text = stringResource(R.string.finalizing),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2,
            )
            RecordPhase.IDLE -> IdleContent(
                state = state,
                preflight = preflight,
                onStart = onStart,
                onRecheck = onRecheck,
                onOpenSettings = onOpenSettings,
                onRequestNotifications = onRequestNotifications,
                onOpenPhone = onOpenPhone,
                opening = opening,
            )
        }
    }
}

@Composable
private fun IdleContent(
    state: RecordUiState,
    preflight: PreflightResult?,
    onStart: () -> Unit,
    onRecheck: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenPhone: () -> Unit,
    opening: PhoneOpening,
) {
    // The title follows the preflight verdict. As long as it has not returned its opinion
    // (`null`), we do not yet know whether starting is possible and we prejudge nothing: "Ready"
    // stays, as before. As soon as a blocker is known, the title says so, otherwise it contradicts
    // the line below it and the greyed-out button below that.
    Text(
        text = stringResource(
            if (preflight != null && !preflight.canStart) R.string.idle_title_blocked
            else R.string.idle_title
        ),
        style = MaterialTheme.typography.title2,
    )

    state.lastStopReason?.let { Text(text = stopReasonText(it), style = captionStyle(), textAlign = TextAlign.Center) }

    if (preflight == null) return

    Text(
        text = stringResource(R.string.battery_line, preflight.batteryPct),
        style = captionStyle(),
    )
    Text(
        text = stringResource(R.string.free_space_line, Preflight.formatBytes(preflight.freeBytes)),
        style = captionStyle(),
    )
    if (preflight.pendingChunks > 0) {
        Text(
            text = stringResource(R.string.pending_sync_line, preflight.pendingChunks),
            style = captionStyle(),
        )
    }

    // Blockers first: they are the only thing to read when they exist. Red when they describe a
    // failure, amber when they describe a step the user has not carried out yet — see
    // `IssueId.isFailure`, which carries the rule and the reason for it.
    preflight.blockers.forEach { issue ->
        Text(
            text = issueText(issue),
            color = if (issue.id.isFailure) MaterialTheme.colors.error else Amber,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
        )
    }
    // Warnings in amber: these are situations, not failures, and the user decides. The unreachable
    // phone is one of them — the whole transfer architecture exists so that this case is without
    // consequence.
    preflight.warnings.forEach { issue ->
        Text(
            text = issueText(issue),
            color = Amber,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
        )
    }

    FlatButton(
        label = stringResource(R.string.start),
        enabled = preflight.canStart,
        color = MaterialTheme.colors.primary,
        contentColor = MaterialTheme.colors.onPrimary,
        onClick = onStart,
    )

    if (!preflight.canStart) {
        // The notification permission is the only blocker the user can lift from the watch itself.
        // We ask for it again directly rather than sending them into the settings first: Android
        // grants two prompts before refusing to show a third, and the second one is worth more than
        // a detour through a settings tree read at ankle height. The settings remain the second
        // resort, for the case where the prompt no longer appears.
        // The unsealed context is the most frequent blocker, and the only one that is lifted on the
        // other device. Without this button, the watch said what to do and left the user to put the
        // watch back down, find their phone, unlock it and find the application again — at bedtime,
        // screen at the ankle.
        if (preflight.blockers.any { it.id == IssueId.CONTEXT_NOT_SEALED }) {
            FlatButton(
                label = stringResource(R.string.open_on_phone),
                enabled = opening != PhoneOpening.Sent,
                color = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                onClick = onOpenPhone,
            )
            when (opening) {
                PhoneOpening.Sent -> Text(
                    text = stringResource(R.string.open_on_phone_sent),
                    style = captionStyle(),
                    textAlign = TextAlign.Center,
                )
                PhoneOpening.Unreachable -> Text(
                    text = stringResource(R.string.open_on_phone_unreachable),
                    color = Amber,
                    style = captionStyle(),
                    textAlign = TextAlign.Center,
                )
                PhoneOpening.None -> Unit
            }
        }

        if (preflight.blockers.any { it.id == IssueId.NOTIFICATIONS_DENIED }) {
            FlatButton(
                label = stringResource(R.string.allow_notifications),
                enabled = true,
                color = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                onClick = onRequestNotifications,
            )
            FlatButton(
                label = stringResource(R.string.open_settings),
                enabled = true,
                color = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                onClick = onOpenSettings,
            )
        } else {
            // "Check again" only survives for the blockers that none of `RecordRoute`'s three
            // triggers covers: disk space freed by a sync in progress, or the foreground service
            // refusal flag.
            FlatButton(
                label = stringResource(R.string.preflight_recheck),
                enabled = true,
                color = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                onClick = onRecheck,
            )
        }
    }
}

@Composable
private fun RecordingContent(state: RecordUiState, onStop: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val minutes = state.elapsedMs / 60_000
    Text(
        text = stringResource(R.string.elapsed_hm, minutes / 60, minutes % 60),
        style = MaterialTheme.typography.display1,
    )
    Text(text = stringResource(R.string.samples_line, groupDigits(state.samples)), style = captionStyle())
    Text(
        // Explicit `Locale.UK`, like `Preflight.formatBytes`: without it the format follows the
        // watch's locale, and the screen displayed "0,0 MB written" three lines away from "Free
        // space 12.0 GB". Two decimal separators on the same screen of the same device.
        text = stringResource(
            R.string.written_line,
            "%.1f".format(Locale.UK, state.bytesWritten / 1_048_576.0),
        ),
        style = captionStyle(),
    )
    // The battery is only known at the first sensor reading. `RecordUiState` carries `-1` until
    // then, and this line displayed it as it was: "Battery -1%" during the first seconds of every
    // night. A negative percentage does not exist — as long as we do not know, we say so with the
    // same dash as everywhere else in the product.
    Text(
        text = if (state.batteryPct >= 0) {
            stringResource(R.string.battery_line, state.batteryPct)
        } else {
            stringResource(R.string.battery_line_unknown)
        },
        style = captionStyle(),
    )
    Text(
        text = if (state.gapTotalMs > 0) {
            stringResource(R.string.gaps_line_total, state.gapCount, formatSeconds(state.gapTotalMs))
        } else {
            stringResource(R.string.gaps_line, state.gapCount)
        },
        color = if (state.gapCount > 0) Amber else MaterialTheme.colors.onSurfaceVariant,
        style = captionStyle(),
    )
    Text(text = stringResource(R.string.mode_line, state.modeLabel), style = captionStyle())
    Text(
        text = stringResource(R.string.sync_line, state.chunksTotal - state.chunksPending, state.chunksTotal),
        style = captionStyle(),
    )
    if (state.syncBacklogged) {
        Text(
            text = stringResource(R.string.sync_backlogged),
            color = Amber,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
        )
    }

    // An accidental STOP at 3 a.m. costs the whole night: long press, then explicit confirmation.
    // Two gestures, neither of them involuntary.
    if (!confirming) {
        Text(
            text = stringResource(R.string.stop_hint),
            style = captionStyle(),
            textAlign = TextAlign.Center,
        )
        FlatButton(
            label = stringResource(R.string.stop),
            enabled = true,
            color = MaterialTheme.colors.surface,
            contentColor = MaterialTheme.colors.onSurface,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                confirming = true
            },
            onClick = { },
        )
    } else {
        FlatButton(
            label = stringResource(R.string.stop_confirm),
            enabled = true,
            color = MaterialTheme.colors.error,
            contentColor = MaterialTheme.colors.onError,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                confirming = false
                onStop()
            },
        )
        FlatButton(
            label = stringResource(R.string.stop_cancel),
            enabled = true,
            color = MaterialTheme.colors.surface,
            contentColor = MaterialTheme.colors.onSurface,
            onClick = { confirming = false },
        )
    }
}

/**
 * Full-width button, with no elevation, no animated ripple and no pill shape — the pill signals
 * "consumer application", and the shadow survives neither the dark theme nor a screenshot.
 *
 * The input goes through `combinedClickable` and not through `pointerInput { detectTapGestures }`,
 * which is what the absence of ripple had first been bought with. `detectTapGestures` emits no
 * semantics at all: no `Role.Button`, no click action, no long-click action, and no disabled
 * state. Under TalkBack the node was read as a plain label — START did nothing on double-tap, and
 * STOP, which only answers a long press, offered no long-click action, so a recording could not be
 * ended other than by waiting for the automatic stop. Switch Access had the same dead end.
 * `combinedClickable(indication = null)` keeps the absence of ripple and exposes all of the above;
 * with `enabled = false` it sets `disabled()` itself. The minimum height is the 48 dp touch
 * target, which 12 dp of padding around one line of `button` type fell just short of.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlatButton(
    label: String,
    enabled: Boolean,
    color: Color,
    contentColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                color = if (enabled) color else MaterialTheme.colors.surface.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            )
            // `padding` stays after the click modifier so that the touch area covers it, as the
            // `pointerInput` used to.
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            textAlign = TextAlign.Center,
            color = if (enabled) contentColor else MaterialTheme.colors.onSurfaceVariant,
            style = MaterialTheme.typography.button,
        )
    }
}

@Composable
private fun captionStyle() = MaterialTheme.typography.caption1

@Composable
private fun issueText(issue: Issue): String = when (issue.id) {
    IssueId.CONTEXT_NOT_SEALED -> stringResource(R.string.blocker_context_not_sealed)
    IssueId.NOTIFICATIONS_DENIED -> stringResource(R.string.blocker_notifications)
    IssueId.NO_ACCELEROMETER -> stringResource(R.string.blocker_no_accelerometer)
    IssueId.STORAGE_FULL -> stringResource(R.string.blocker_storage_full, issue.args[0], issue.args[1])
    IssueId.BACKLOG_AT_CAP -> stringResource(R.string.blocker_backlog_at_cap, issue.args[0])
    IssueId.FGS_REFUSED -> stringResource(R.string.blocker_fgs_refused)
    IssueId.BENCH_SCALE_MISMATCH -> stringResource(R.string.blocker_bench_scale, issue.args[0])
    IssueId.BENCH_CHARGER_IGNORED -> stringResource(R.string.warning_bench_charger)
    IssueId.LOW_BATTERY -> stringResource(R.string.warning_low_battery, issue.args[0])
    IssueId.PHONE_UNREACHABLE -> stringResource(R.string.warning_phone_unreachable)
    IssueId.NO_WAKEUP_SENSOR -> stringResource(R.string.warning_no_wakeup_sensor)
    IssueId.PENDING_SYNC -> stringResource(R.string.warning_pending_sync, issue.args[0])
}

@Composable
private fun stopReasonText(reason: StopReason): String = when (reason) {
    StopReason.CHARGING -> stringResource(R.string.stopped_charging)
    StopReason.LOW_BATTERY -> stringResource(R.string.stopped_low_battery)
    StopReason.TIME_LIMIT -> stringResource(R.string.stopped_time_limit)
    StopReason.MAX_DURATION -> stringResource(R.string.stopped_max_duration)
    StopReason.WAKE_DETECTED -> stringResource(R.string.stopped_wake)
    StopReason.DISK_FULL -> stringResource(R.string.stopped_disk_full)
    StopReason.CRASH -> stringResource(R.string.stopped_crash)
    // A stop requested by the user does not need to be announced to the user.
    StopReason.USER, StopReason.UNKNOWN -> ""
}

/** Comma as the thousands separator: the interface is in English. */
private fun groupDigits(v: Long): String = "%,d".format(Locale.UK, v)

private fun formatSeconds(ms: Long): String = "%d s".format(ms / 1000)

/** Attention amber. `error` and `success` never describe a health result; here, the amber only
 *  describes a technical state that is degraded but tolerable. */
private val Amber = Color(0xFFE0A030)

/**
 * Pure black background: on OLED, a black pixel is not lit. No dynamic colour — a display shown
 * over the shoulder must not depend on a wallpaper.
 */
@Composable
fun PendulumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Color(0xFF4C8DF6),
            onPrimary = Color(0xFF0E1116),
            surface = Color(0xFF1B1F26),
            onSurface = Color(0xFFE6E9EF),
            onSurfaceVariant = Color(0xFFA8B0BD),
            background = Color.Black,
            onBackground = Color(0xFFE6E9EF),
            error = Color(0xFFE05252),
            onError = Color(0xFF0E1116),
        ),
        content = content,
    )
}

/**
 * What the last attempt to open the phone produced.
 *
 * Three states and not a boolean: "not asked yet" and "asked, phone unreachable" do not say the
 * same thing to someone waiting for a screen to light up at the other end of the room.
 */
enum class PhoneOpening { None, Sent, Unreachable }
