package com.pendulum.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.pendulum.wear.record.estUnePanne
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
    var ouvertureTelephone by remember { mutableStateOf(OuvertureTelephone.Aucune) }
    val portee = rememberCoroutineScope()

    // Une seule execution par affichage, jamais periodique : le preflight fait des E/S et une
    // lecture du Data Layer, ce n'est pas quelque chose qu'on repete en boucle sous la couette.
    LaunchedEffect(refreshKey, state.phase) {
        preflight = if (state.phase == RecordPhase.IDLE) {
            withContext(Dispatchers.IO) { Preflight.check(context) }
        } else {
            null
        }
    }

    // ------------------------------------------------------------------------------------
    // Trois declencheurs de revalidation. Aucun ne s'arme quand l'ecran est eteint.
    // ------------------------------------------------------------------------------------

    // 1. L'octroi d'une permission depuis l'application. Le rappel **revalide tout le preflight**
    //    et pas seulement la permission accordee : accorder les notifications pendant que la
    //    montre se remplissait ne dit rien de l'espace disque.
    val demandeNotifications = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { refreshKey++ }

    // Demandee a l'ouverture de l'ecran et pas au moment du START : une invite systeme au
    // coucher, ecran a la cheville, est exactement ce qu'on ne veut pas faire lire a quelqu'un
    // d'allonge. Elle vivait dans `MainActivity.onCreate` avec un rappel vide — c'est-a-dire que
    // l'accorder ne mettait rien a jour et que le bloqueur restait affiche.
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            demandeNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 2 et 3, dans un seul effet parce qu'ils partagent la meme fenetre de vie : **l'ecran
    //    allume**.
    //
    // 2. Le retour de n'importe ou — et surtout des reglages systeme, ou l'utilisateur peut
    //    accorder la permission hors de tout `ActivityResultLauncher`. Sans cela, le bouton
    //    « Open settings » renvoie sur un ecran qui continue d'afficher le bloqueur qu'on vient
    //    de lever, et la seule issue est de tuer l'application.
    //
    // 3. Le telephone scelle le contexte du soir. C'est le bloqueur le plus frequent, et le seul
    //    que l'utilisateur leve depuis un autre appareil : sans ce guetteur, il faut revenir sur
    //    la montre et appuyer sur « Check again » pour voir disparaitre un bloqueur deja leve.
    //    Un guetteur du Data Layer, pas une interrogation periodique.
    //
    // `LifecycleResumeEffect` plutot qu'un `LifecycleEventObserver` monte a la main : c'est le
    // meme observateur sur ON_RESUME, en une ligne, avec sa liberation. Et pas
    // `repeatOnLifecycle`, qui sert a collecter un flux depuis une portee non-Compose.
    //
    // **Le guetteur est enregistre a la reprise et retire a la pause, pas au sort de la
    // composition.** Un `DisposableEffect` le laisserait arme tant que l'activite existe, donc
    // ecran eteint, donc pendant la nuit : le telephone qui republie un item a 2 h du matin
    // reveillerait alors le processus pour recalculer un preflight que personne ne regarde. Le
    // critere « aucune recomposition entre le coucher et le reveil » se perd exactement par ce
    // genre de detail. Et un preflight inchange produit un `PreflightResult` structurellement
    // egal au precedent, que l'egalite structurelle de `mutableStateOf` absorbe sans recomposer.
    LifecycleResumeEffect(context) {
        refreshKey++

        val client = Wearable.getDataClient(context)
        val guetteur = DataClient.OnDataChangedListener { refreshKey++ }
        client.addListener(
            guetteur,
            Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME)
                .path(DataLayerTransfer.CONTEXT_PREFIX).build(),
            DataClient.FILTER_PREFIX,
        )

        onPauseOrDispose { client.removeListener(guetteur) }
    }

    RecordScreen(
        state = state,
        preflight = preflight,
        onStart = onStart,
        onStop = onStop,
        onRecheck = { refreshKey++ },
        onOpenSettings = onOpenSettings,
        onDemanderNotifications = {
            demandeNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onOuvrirLeTelephone = {
            portee.launch {
                // Le resultat est affiche, pas suppose : « ouvert » quand le telephone a bien
                // recu la demande, « telephone injoignable » sinon. Annoncer un succes alors que
                // rien ne s'est ouvert envoie quelqu'un chercher un ecran qui n'est pas apparu.
                ouvertureTelephone = if (RemoteCommands.ouvrirLeTelephone(context)) {
                    OuvertureTelephone.Envoyee
                } else {
                    OuvertureTelephone.Injoignable
                }
            }
        },
        ouverture = ouvertureTelephone,
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
    onDemanderNotifications: () -> Unit = {},
    onOuvrirLeTelephone: () -> Unit = {},
    ouverture: OuvertureTelephone = OuvertureTelephone.Aucune,
) {
    // Le contenu ne doit toucher le verre a aucune position de defilement, et Google le verifie :
    // le motif de rejet « aucun texte ou controle n'est coupe par les bords de l'ecran » se teste
    // sur le plus petit cadran rond, police au maximum.
    //
    // Deux valeurs, et une seule chose qui les rend suffisantes.
    //
    // 14,6 % est le retrait du **carre inscrit** : pour un diametre D, le carre inscrit a pour
    // cote D/racine(2), donc (1 - 1/racine(2))/2 = 14,6 % de retrait par bord. 18 % horizontal
    // vient d'un calcul plus severe : le contenu defile, donc une ligne finit toujours par passer
    // pres du haut ou du bas, la ou la corde 2R*racine(1-t²) se resserre.
    //
    // **Mais aucune marge ne suffit si elle est du mauvais cote du defilement.** C'est le defaut
    // qui a survecu a deux corrections successives (16 dp, puis 10,4 %) : `padding` etait applique
    // **apres** `verticalScroll` dans la chaine, donc il appartenait au contenu defilant. Il
    // n'ecartait le texte du verre qu'a la position de repos ; des qu'on faisait defiler, la marge
    // partait avec le contenu et la ligne du haut se faisait trancher par le cadran. Ce qu'on
    // voyait sur capture reelle : « Free space 12.2 GB » coupe net a mi-hauteur des glyphes.
    //
    // Inverser les deux modificateurs suffit, et c'est demontrable plutot que constatable :
    // `padding` avant `verticalScroll` fait de la zone marginee le **viewport**, et
    // `verticalScroll` clippe son contenu au viewport. Le texte disparait donc dans le noir a la
    // limite du rectangle, jamais sous le verre. Reste a prouver que ce rectangle tient dans le
    // cercle — c'est le seul calcul qui compte :
    //
    //   demi-largeur = (1 - 2*0,18)/2 * D  = 0,64 R
    //   demi-hauteur = (1 - 2*0,146)/2 * D = 0,708 R
    //   coin         = R*racine(0,64² + 0,708²) = 0,954 R  <  R
    //
    // Les quatre coins sont strictement interieurs. Aucun pixel du viewport n'atteint le verre,
    // a n'importe quelle position de defilement et a n'importe quelle echelle de police.
    //
    // Defaut invisible en previsualisation, qui est carree, et invisible au repos. Il a fallu
    // faire defiler un vrai cadran pour le voir.
    val config = LocalConfiguration.current
    val rond = config.isScreenRound
    val cote = config.screenWidthDp.dp
    val margeH = if (rond) cote * 0.18f else 16.dp
    val margeV = if (rond) cote * 0.146f else 24.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // L'ordre est le correctif. Ne pas intervertir ces deux lignes.
            .padding(horizontal = margeH, vertical = margeV)
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
                onDemanderNotifications = onDemanderNotifications,
                onOuvrirLeTelephone = onOuvrirLeTelephone,
                ouverture = ouverture,
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
    onDemanderNotifications: () -> Unit,
    onOuvrirLeTelephone: () -> Unit,
    ouverture: OuvertureTelephone,
) {
    // Le titre suit le verdict du preflight. Tant qu'il n'a pas rendu son avis (`null`), on ne
    // sait pas encore si le depart est possible et on ne prejuge de rien : « Ready » reste, comme
    // avant. Des qu'un bloqueur est connu, le titre le dit, sinon il contredit la ligne qui le
    // suit et le bouton grise qui la suit encore.
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

    // Les bloqueurs en premier : ils sont la seule chose a lire quand ils existent. Rouge s'ils
    // decrivent une panne, ambre s'ils decrivent une etape que l'utilisateur n'a pas encore
    // faite — voir `IssueId.estUnePanne`, qui porte la regle et la raison.
    preflight.blockers.forEach { issue ->
        Text(
            text = issueText(issue),
            color = if (issue.id.estUnePanne) MaterialTheme.colors.error else Amber,
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
        // La permission de notification est le seul bloqueur que l'utilisateur peut lever depuis
        // la montre elle-meme. On la lui redemande directement plutot que de le renvoyer d'abord
        // dans les reglages : Android accorde deux invites avant de refuser d'en afficher une
        // troisieme, et la seconde vaut mieux qu'un detour par une arborescence de reglages lu a
        // la cheville. Les reglages restent en second recours, pour le cas ou l'invite ne
        // s'affiche plus.
        // Le contexte non scelle est le bloqueur le plus frequent, et le seul qui se leve sur
        // l'autre appareil. Sans ce bouton, la montre disait quoi faire et laissait l'utilisateur
        // reposer la montre, trouver son telephone, deverrouiller et retrouver l'application —
        // au coucher, ecran a la cheville.
        if (preflight.blockers.any { it.id == IssueId.CONTEXT_NOT_SEALED }) {
            FlatButton(
                label = stringResource(R.string.open_on_phone),
                enabled = ouverture != OuvertureTelephone.Envoyee,
                color = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                onClick = onOuvrirLeTelephone,
            )
            when (ouverture) {
                OuvertureTelephone.Envoyee -> Text(
                    text = stringResource(R.string.open_on_phone_sent),
                    style = captionStyle(),
                    textAlign = TextAlign.Center,
                )
                OuvertureTelephone.Injoignable -> Text(
                    text = stringResource(R.string.open_on_phone_unreachable),
                    color = Amber,
                    style = captionStyle(),
                    textAlign = TextAlign.Center,
                )
                OuvertureTelephone.Aucune -> Unit
            }
        }

        if (preflight.blockers.any { it.id == IssueId.NOTIFICATIONS_DENIED }) {
            FlatButton(
                label = stringResource(R.string.allow_notifications),
                enabled = true,
                color = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                onClick = onDemanderNotifications,
            )
            FlatButton(
                label = stringResource(R.string.open_settings),
                enabled = true,
                color = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                onClick = onOpenSettings,
            )
        } else {
            // « Verifier a nouveau » ne subsiste que pour les bloqueurs qu'aucun des trois
            // declencheurs de `RecordRoute` ne couvre : l'espace disque libere par une
            // synchronisation en cours, ou le drapeau de refus du service de premier plan.
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
        // `Locale.UK` explicite, comme `Preflight.formatBytes` : sans lui le format suit la locale
        // de la montre, et l'ecran affichait « 0,0 MB written » a trois lignes de « Free space
        // 12.0 GB ». Deux separateurs decimaux sur le meme ecran du meme appareil.
        text = stringResource(
            R.string.written_line,
            "%.1f".format(Locale.UK, state.bytesWritten / 1_048_576.0),
        ),
        style = captionStyle(),
    )
    // La batterie n'est connue qu'a la premiere lecture du capteur. `RecordUiState` porte `-1`
    // jusque-la, et cette ligne l'affichait tel quel : « Battery -1% » pendant les premieres
    // secondes de chaque nuit. Un pourcentage negatif n'existe pas — tant qu'on ne sait pas, on
    // le dit avec le meme tiret que partout ailleurs dans le produit.
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
    IssueId.BENCH_SCALE_MISMATCH -> stringResource(R.string.blocker_bench_scale, issue.args[0])
    IssueId.BANC_CHARGEUR_IGNORE -> stringResource(R.string.warning_banc_chargeur)
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

/**
 * Ce que la derniere tentative d'ouverture du telephone a donne.
 *
 * Trois etats et non un booleen : « pas encore demande » et « demande, telephone injoignable » ne
 * disent pas la meme chose a quelqu'un qui attend qu'un ecran s'allume a l'autre bout de la piece.
 */
enum class OuvertureTelephone { Aucune, Envoyee, Injoignable }
