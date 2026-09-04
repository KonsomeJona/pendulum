package com.pendulum.phone.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.PendulumError
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * A card: surface, 1 dp border, **no shadow**.
 *
 * The stacked tonal elevation of Material 3 is illegible in dark and does not survive printing. A
 * thin border gives exactly the same image on screen and on paper — which is the condition for a
 * screenshot and a PDF page to look alike, and therefore for a user to be able to show one while
 * talking about the other.
 */
@Composable
fun PendulumCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPendulumColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PendulumShapes.card)
            .background(c.surface)
            .border(1.dp, c.outline, PendulumShapes.card)
            .padding(Spacing.card.dp),
        content = content,
    )
}

/**
 * The scrolling column that eight screens used to open the same way.
 *
 * Screen margin, vertical scrolling, spacing between cards: five lines copied identically into
 * `Trend`, `Home`, `Night detail`, `P1 report`, `Settings`, `Tonight`, `Comparison` and `Export`.
 * The problem was not the volume — it is that the screen margin and the gap between cards are a
 * **single layout rule**, and a single rule written eight times gets corrected seven times out of
 * eight.
 *
 * The `@Composable` takes no `verticalArrangement`: a screen that wanted a different spacing does
 * not want this composable, it wants its own column, and saying so is more readable than a default
 * parameter nobody overrides.
 */
@Composable
fun PendulumScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
        content = content,
    )
}

/**
 * The "custom settings" banner, and the invariant it carries.
 *
 * It must appear **everywhere a figure is shown**, as soon as a profile other than the default one
 * is active: without it, a figure obtained with a modified threshold is indistinguishable from a
 * reference figure. This is a rule, not a decoration — and it was written twice, on the trend and
 * on the export, with a comment in one of the two saying that it was written in the other. An
 * invariant guaranteed by a comment is not guaranteed.
 *
 * Nothing is rendered when [profile] is null, which allows it to be called unconditionally.
 */
@Composable
fun CustomProfileBanner(profile: String?, modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    profile?.let {
        PendulumCard(modifier) {
            Paragraph(stringResource(R.string.trend_custom_params, it), color = c.attention)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    Text(
        title.uppercase(),
        style = PendulumType.label,
        color = c.textTertiary,
        modifier = modifier.padding(bottom = Spacing.s.dp),
    )
}

/** Explanatory paragraph: width bounded to ~60 characters, beyond which it becomes illegible. */
@Composable
fun Paragraph(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val c = LocalPendulumColors.current
    Text(
        text,
        style = PendulumType.body,
        color = color ?: c.textSecondary,
        modifier = modifier.widthIn(max = Spacing.paragraphMax.dp),
    )
}

/**
 * The aggregate figure and its uncertainty — **the only use site of `metricXL` in the
 * application**.
 *
 * The signature is the guard rail: this composable takes an [Aggregate.Result], which cannot exist
 * without its interval nor its `n`. It is therefore **impossible** to display a bare median by
 * oversight: there is no parameter to omit (P2).
 *
 * The interval and the number of nights are on the next line, one size step down, never in a
 * footnote, never in a tooltip. An uncertainty one has to go and look for is not displayed.
 */
@Composable
fun MetricHeadline(
    result: Aggregate.Result,
    label: String,
    qualifier: UiText? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatValue(result.median, result.quantity),
                style = PendulumType.metricXL,
                color = c.textPrimary,
            )
            result.quantity.unit?.let { unit ->
                Spacer(Modifier.width(Spacing.xs.dp))
                Text(
                    stringResource(unit),
                    style = PendulumType.titleM,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            qualifier?.let {
                Spacer(Modifier.width(Spacing.sm.dp))
                Text(
                    "· " + it.resolve(),
                    style = PendulumType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        // The "95 %" tag is written only when it is earned. Below six nights, the real coverage
        // measured by simulation falls to 75 % — an interval narrower than its promise, on a
        // product whose interval is the argument. The label therefore changes, and there is no
        // branch through which "95 % CI" could be displayed at three nights.
        val low = formatValue(result.ciLow, result.quantity)
        val high = formatValue(result.ciHigh, result.quantity)
        Text(
            stringResource(
                if (result.ciCalibrated) R.string.trend_interval_and_n
                else R.string.trend_interval_uncalibrated,
                low, high, result.nights, stringResource(result.quantity.nightsNoun),
            ),
            style = PendulumType.bodyNum,
            color = c.textSecondary,
        )
        Text(label, style = PendulumType.caption, color = c.textTertiary)
    }
}

/** Rounded to the integer: never a decimal on a rhythm nor on an hourly index. */
fun formatValue(v: Double, quantity: Aggregate.Quantity): String =
    if (quantity.decimals == 0) Math.round(v).toString() else "%.${quantity.decimals}f".format(v)

/**
 * The unit written after a figure, or nothing at all when the quantity has none.
 *
 * For the sentences that take the unit as a positional argument (`trend_dispersion`,
 * `compare_estimate_line`): a dimensionless [Aggregate.Quantity] carries `unit = null`, and the
 * sentence must still be assembled. Nothing is the only honest thing to print there — "(none)" or
 * "-" would read as a unit beside the figure.
 */
@Composable
fun formatUnit(quantity: Aggregate.Quantity): String =
    quantity.unit?.let { stringResource(it) }.orEmpty()

/**
 * The position sentence, in a frame that is **neutral in all five cases**.
 *
 * Colouring the frame red when the interval sits entirely above the threshold would turn a
 * measurement into a verdict, and a red verdict read at 7 am is no longer something one discusses
 * with a doctor — it is already a certainty.
 */
@Composable
fun PositionBox(sentence: String, modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    if (sentence.isBlank()) return
    Box(
        modifier
            .fillMaxWidth()
            .clip(PendulumShapes.field)
            .border(1.dp, c.outline, PendulumShapes.field)
            .padding(Spacing.sm.dp),
    ) {
        Paragraph(sentence, color = c.textPrimary)
    }
}

/** An inline value: label on the left, value on the right, optional note underneath. */
@Composable
fun InlineValue(
    label: String,
    value: String,
    note: String? = null,
    struckThrough: Boolean = false,
    valueDescription: String? = null,
) {
    val c = LocalPendulumColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // `weight(fill = false)` on both: each takes what it needs, and neither can spill over
            // the other any more. Without it, `SpaceBetween` let a long value stick to its heading
            // and break anywhere — "Automatic stopon the charger, on waking, or after 10" then a
            // lone "h" on the following line, and the package `com.google.android.apps.fitness`
            // broken in the middle of a word. The gutter guarantees that the two texts never touch,
            // whatever their length.
            Text(
                label,
                style = PendulumType.body,
                color = c.textSecondary,
                modifier = Modifier.weight(1f, fill = false).padding(end = Spacing.sm.dp),
            )
            Text(
                value,
                style = PendulumType.bodyNum,
                color = c.textPrimary,
                textAlign = TextAlign.End,
                textDecoration = if (struckThrough) TextDecoration.LineThrough else null,
                // When the value carries a sign — `✓`, `✗`, `—` — the sign **is** the information,
                // and a screen reader says nothing of it, or says its Unicode name. The caller then
                // supplies the same information in words. Without this parameter the value is read
                // as it stands, which stays right for every other row.
                modifier = (
                    valueDescription
                        ?.let { d -> Modifier.semantics { contentDescription = d } }
                        ?: Modifier
                    ).weight(1f, fill = false),
            )
        }
        note?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
    }
}

/**
 * Quality flag. This is not an alert: it is a measured property of the night.
 *
 * Not a Material chip component, on purpose. It used to be an `AssistChip(onClick = {})`, and an
 * assist chip is a button: it consumed the tap and announced itself to TalkBack as an action. On
 * the night list it sits inside a row whose whole body opens the night, so tapping "gap 12 min" —
 * the most visible element of the row — did nothing, while tapping next to it opened the night;
 * and a screen reader heard one row plus n buttons of which n were dead. A plain box has no
 * click of its own: the tap falls through to the row, and the label merges into the row's
 * announcement.
 */
@Composable
fun QualityChip(label: UiText) {
    val c = LocalPendulumColors.current
    Box(
        Modifier
            .clip(PendulumShapes.chip)
            .background(c.surfaceMuted)
            .padding(horizontal = Spacing.sm.dp, vertical = Spacing.xs.dp),
    ) {
        Text(label.resolve(), style = PendulumType.caption, color = c.textSecondary)
    }
}

/**
 * Filled/empty dots, **never a progress bar**.
 *
 * A bar suggests a score that climbs, therefore a performance, therefore something to beat. There
 * is nothing to beat here: the only goal the application encourages is to record enough nights,
 * and that is represented by facts — three circles, two of which are filled.
 */
@Composable
fun CollectionProgress(done: Int, required: Int, modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm.dp)) {
        repeat(required) { i ->
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (i < done) c.accent else Color.Transparent)
                    .border(1.dp, if (i < done) c.accent else c.outline, CircleShape),
            )
        }
    }
}

/**
 * The refusal screen: under three nights, under five nights for a comparison, export impossible.
 *
 * This is not an error and it is not a decorative empty state. It is the product's normal
 * behaviour, justified **in figures** rather than as an instruction: the user is a technician, and
 * "the 15/h threshold is crossed on only about one night in three" convinces where "please record
 * more nights" annoys.
 */
@Composable
fun BlockingState(
    title: String,
    body: String,
    action: String? = null,
    header: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
) {
    val c = LocalPendulumColors.current
    PendulumCard {
        header?.invoke()
        Text(title, style = PendulumType.titleM, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm.dp))
        Paragraph(body)
        action?.let {
            Spacer(Modifier.height(Spacing.sm.dp))
            Text(it, style = PendulumType.bodyEmph, color = c.textPrimary)
        }
        footer?.let {
            Spacer(Modifier.height(Spacing.m.dp))
            it()
        }
    }
}

/**
 * An error, always of the same shape: neutral title, cause, action, button, code.
 *
 * The tint follows [PendulumError.technical] and not the felt severity: red only if something is
 * really broken (transfer, permission, integrity, storage), amber for all the rest, which is not a
 * failure but a **situation**. Using red for a short night teaches the user to ignore red.
 *
 * ### One button only, and it leads somewhere
 *
 * The card used to carry a second button, fed by a `secondaryButton` that two messages filled with
 * "See the technical detail". There is no technical log screen in this module — no table, no file,
 * no function — and the "Technical log" row of the settings had already been withdrawn for that
 * reason. The second button therefore called a default `{}`: it did not even fail, it did nothing.
 * The rule of [ReasonedButton] holds here too, and harder — an enabled button that fails teaches
 * one to distrust every button, a button that does not even have an error teaches one to distrust
 * the application.
 */
@Composable
fun ErrorCard(
    error: PendulumError,
    onAction: () -> Unit = {},
) {
    val c = LocalPendulumColors.current
    val tint = if (error.technical) c.error else c.attention
    PendulumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(Spacing.s.dp))
            Text(error.title.resolve(), style = PendulumType.titleM, color = c.textPrimary)
        }
        Spacer(Modifier.height(Spacing.s.dp))
        Paragraph(error.cause.resolve())
        Spacer(Modifier.height(Spacing.s.dp))
        Paragraph(error.action.resolve())
        error.button?.let {
            Spacer(Modifier.height(Spacing.sm.dp))
            Button(onClick = onAction, shape = PendulumShapes.button) { Text(it.resolve()) }
        }
        Spacer(Modifier.height(Spacing.s.dp))
        // The code is useful to a technician user, and it is the same one as in the log.
        Text(error.code, style = PendulumType.caption, color = c.textTertiary)
    }
}

/**
 * A greyed-out button that says **why** it is greyed out, without ever ceasing to say **what it
 * does**.
 *
 * Never an enabled button that fails, never a greyed-out button without an explanation: the user
 * who presses "Export" and gets an error learns to distrust every button.
 *
 * ### Why the reason sits below the button and not on it
 *
 * The first version wrote the reason **in place of** the label. Seen on a device, the result is
 * illegible as soon as the reason does not speak of the button itself:
 *
 * - at step 4 of the onboarding, two stacked buttons showed "Allow Health Connect" and "Allow
 *   Health Connect first" — the second being *Continue*, whose reason named the first. Nothing
 *   made it possible to tell which one did what;
 * - on the home screen, the "End of night" and "History" cards already state their situation in
 *   their title. The button repeated it word for word, twice at 200 px apart.
 *
 * A button whose label changes with its state stops being recognisable: it is the same reason that
 * forbids, elsewhere, moving the home cards according to the hour. The label is therefore
 * invariant, and the reason takes a line under the button — present, read, and never confused with
 * the action.
 */
@Composable
fun ReasonedButton(
    label: String,
    unavailableReason: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    Column(modifier.fillMaxWidth()) {
        Button(
            onClick = onClick,
            enabled = unavailableReason == null,
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = c.accent,
                contentColor = c.onAccent,
                disabledContainerColor = c.surfaceMuted,
                disabledContentColor = c.textTertiary,
            ),
        ) {
            Text(label, style = PendulumType.body)
        }
        unavailableReason?.let {
            Spacer(Modifier.height(Spacing.xs.dp))
            // `textSecondary` and not `textTertiary`: the reason is the only thing that explains a
            // dead button, and `TextContrastTest` guarantees 4.5:1 for the tertiary only on solid
            // surfaces. See the KDoc of `PendulumColors`.
            Text(it, style = PendulumType.caption, color = c.textSecondary)
        }
    }
}

/** Progress bar: one of the only two animated elements allowed in the application. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(4.dp),
        color = c.accent,
        trackColor = c.surfaceMuted,
        drawStopIndicator = {},
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    cancel: String = stringResource(R.string.dialog_cancel),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, style = PendulumType.titleM) },
        text = { Paragraph(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(cancel) } },
        shape = PendulumShapes.card,
    )
}
