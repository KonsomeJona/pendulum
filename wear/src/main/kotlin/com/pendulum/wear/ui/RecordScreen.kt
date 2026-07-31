package com.pendulum.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.pendulum.format.wire.StopReason
import com.pendulum.wear.R
import com.pendulum.wear.record.Issue
import com.pendulum.wear.record.IssueId
import com.pendulum.wear.record.Preflight
import com.pendulum.wear.record.PreflightResult
import com.pendulum.wear.record.RecordPhase
import com.pendulum.wear.record.RecordUiState
import com.pendulum.wear.record.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * **Un seul ecran, statique.** Pas de navigation, pas de liste, pas de graphe, pas d'historique,
 * aucun resultat : la montre est un instrument de verification au coucher et de constat au
 * reveil, l'analyse vit sur le telephone.
 *
 * Trois raisons de n'avoir aucune animation, dans cet ordre de poids :
 *
 * 1. **L'energie.** Chaque recomposition reveille le SoC. Sur huit heures, meme une animation
 *    discrete coute plus que l'acquisition a 50 Hz elle-meme.
 * 2. **La contamination de la mesure.** Un ecran qui donne envie d'etre regarde donne envie de
 *    bouger la jambe. L'ecran est a la cheville : le consulter, c'est se pencher, c'est produire
 *    un artefact. Il doit etre ennuyeux exprès.
 * 3. **L'absence de benefice.** Il n'y a rien a regarder en temps reel. Les chiffres affiches
 *    sont des controles de bon fonctionnement, lus deux fois par nuit au plus.
 *
 * Le critere est testable : **entre le coucher et le reveil, la couche UI ne doit provoquer
 * aucune recomposition.** L'ecran est eteint, [collectAsStateWithLifecycle] est arrete, et le
 * service emet dans le vide.
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

    // Une seule execution par affichage, jamais periodique : le preflight fait des E/S et une
    // lecture du Data Layer, ce n'est pas quelque chose qu'on repete en boucle sous la couette.
    LaunchedEffect(refreshKey, state.phase) {
        preflight = if (state.phase == RecordPhase.IDLE) {
            withContext(Dispatchers.IO) { Preflight.check(context) }
        } else {
            null
        }
    }

    RecordScreen(
        state = state,
        preflight = preflight,
        onStart = onStart,
        onStop = onStop,
        onRecheck = { refreshKey++ },
        onOpenSettings = onOpenSettings,
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
) {
    // Marges calculees depuis la forme reelle de l'ecran, et non fixees en dur.
    //
    // Sur un cadran rond, la seule marge qui garantisse qu'aucun pixel ne sorte **a n'importe
    // quelle hauteur** est celle du carre inscrit dans le cercle : de cote D/racine(2), soit un
    // retrait de (1 - 1/racine(2))/2 = 14,6 % du diametre sur chaque bord. Toute valeur
    // inferieure fonctionne au centre, ou le cercle est large, et coupe le texte pres du haut
    // et du bas, ou il se resserre — ce qui est exactement ce qu'on observait : un padding de
    // 16 dp laissait les dernieres lignes du message d'erreur passer sous le verre.
    //
    // Defaut invisible en previsualisation, qui est carree. Trouve sur capture reelle, et le
    // premier correctif (10,4 %) ne suffisait pas non plus : verifie une seconde fois sur
    // capture avant d'etre retenu.
    val config = LocalConfiguration.current
    val rond = config.isScreenRound
    val cote = config.screenWidthDp.dp
    // Correction du correctif : 14,6 % borne le carre inscrit, ce qui suffirait pour un contenu
    // **statique** centre. Ici la colonne defile, donc n'importe quelle ligne finit par passer
    // pres du haut ou du bas du cadran, la ou la largeur utile chute a 2R*racine(1-t²). A 77 %
    // du rayon, elle ne vaut plus que 64 % du diametre. La marge horizontale doit donc etre
    // dimensionnee sur ce pire cas et non sur le centre : 18 % de chaque cote. La marge
    // verticale, elle, reste celle du carre inscrit — le defilement s'occupe du reste.
    val margeH = if (rond) cote * 0.18f else 16.dp
    val margeV = if (rond) cote * 0.146f else 24.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = margeH, vertical = margeV),
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
            RecordPhase.IDLE -> IdleContent(state, preflight, onStart, onRecheck, onOpenSettings)
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
) {
    Text(
        text = stringResource(R.string.idle_title),
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

    // Les bloqueurs en premier, en rouge : ils sont la seule chose a lire quand ils existent.
    preflight.blockers.forEach { issue ->
        Text(
            text = issueText(issue),
            color = MaterialTheme.colors.error,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
        )
    }
    // Les avertissements en ambre : ce sont des situations, pas des pannes, et l'utilisateur
    // decide. Le telephone injoignable en fait partie — toute l'architecture de transfert
    // existe pour que ce cas soit sans consequence.
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
        FlatButton(
            label = stringResource(R.string.preflight_recheck),
            enabled = true,
            color = MaterialTheme.colors.surface,
            contentColor = MaterialTheme.colors.onSurface,
            onClick = onRecheck,
        )
        if (preflight.blockers.any { it.id == IssueId.NOTIFICATIONS_DENIED }) {
            FlatButton(
                label = stringResource(R.string.open_settings),
                enabled = true,
                color = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                onClick = onOpenSettings,
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
        text = stringResource(R.string.written_line, "%.1f".format(state.bytesWritten / 1_048_576.0)),
        style = captionStyle(),
    )
    Text(text = stringResource(R.string.battery_line, state.batteryPct), style = captionStyle())
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

    // Un STOP accidentel a 3 h du matin coute la nuit entiere : appui long, puis confirmation
    // explicite. Deux gestes, aucun des deux involontaire.
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
 * Bouton pleine largeur, sans elevation, sans ondulation animee et sans forme de pilule — la
 * pilule signale « application grand public », et l'ombre ne survit ni au theme sombre ni a une
 * capture d'ecran.
 */
@Composable
private fun FlatButton(
    label: String,
    enabled: Boolean,
    color: Color,
    contentColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val longPress: ((Offset) -> Unit)? = onLongClick?.let { action -> { _: Offset -> action() } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (enabled) color else MaterialTheme.colors.surface.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            )
            .pointerInput(enabled, onClick, longPress) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onClick() }, onLongPress = longPress)
            }
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
    IssueId.FGS_REFUSED -> stringResource(R.string.blocker_fgs_refused)
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
    // Un arret demande par l'utilisateur n'a pas besoin d'etre annonce a l'utilisateur.
    StopReason.USER, StopReason.UNKNOWN -> ""
}

/** Virgule comme separateur de milliers : l'interface est en anglais. */
private fun groupDigits(v: Long): String = "%,d".format(Locale.UK, v)

private fun formatSeconds(ms: Long): String = "%d s".format(ms / 1000)

/** Ambre d'attention. `error` et `success` ne decrivent jamais un resultat de sante ; ici,
 *  l'ambre ne decrit qu'un etat technique degrade mais tolerable. */
private val Amber = Color(0xFFE0A030)

/**
 * Fond noir pur : sur OLED, un pixel noir n'est pas allume. Aucune couleur dynamique — un
 * affichage montre par-dessus l'epaule ne doit pas dependre d'un fond d'ecran.
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
