package com.pendulum.phone.ui.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The three possible outcomes. No severity scale: the IRLS is under copyright, excluded.
 *
 * The title is a **resource identifier** and not a string: an `enum` constructor has no `Context`,
 * and resolving it here would freeze the language at the moment the class is loaded.
 */
enum class QuizOutcome(@StringRes val title: Int) {
    CONSISTENT(R.string.quiz_outcome_consistent),
    NOT_CONSISTENT(R.string.quiz_outcome_not_consistent),
    INCOMPLETE(R.string.quiz_outcome_incomplete),
}

/**
 * The screening questionnaire.
 *
 * ### No score in large type
 *
 * The outcome is a sentence, not a number. A score displayed large gets compared, gets tracked
 * over time and ends up being treated as a measurement — whereas this is a screening with three
 * outcomes.
 *
 * ### It does not replace the measurement, and the measurement does not replace it
 *
 * The header says it explicitly: this questionnaire is about what is felt **while awake**, the
 * watch measures what happens **during sleep**. The two complement each other. The diagnosis of
 * restless legs syndrome is clinical and rests on the symptoms, not on a sensor.
 */
@Composable
fun ScreeningQuizScreen(
    outcome: QuizOutcome?,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    Column(
        modifier.fillMaxSize().padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.quiz_title), style = PendulumType.titleL, color = c.textPrimary)

        if (outcome == null) {
            PendulumCard {
                Paragraph(stringResource(R.string.quiz_header))
                Spacer(Modifier.height(Spacing.m.dp))
                Paragraph(stringResource(R.string.quiz_single_question), color = c.textPrimary)
                Spacer(Modifier.height(Spacing.m.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm.dp)) {
                    Button(onClick = onYes, shape = PendulumShapes.button) { Text(stringResource(R.string.quiz_yes)) }
                    OutlinedButton(onClick = onNo, shape = PendulumShapes.button) { Text(stringResource(R.string.quiz_no)) }
                }
            }
        } else {
            PendulumCard {
                // Neutral title, no alarming colour: this is a screening, not a verdict.
                Text(stringResource(outcome.title), style = PendulumType.bodyEmph, color = c.textPrimary)
                Spacer(Modifier.height(Spacing.s.dp))
                // The warning is displayed on **all three** outcomes, and the negative answer is
                // added to it instead of replacing it. Replacement was the defect: the only path
                // where the warning disappeared was the reassuring path, that is, the one where it
                // most needs to be recalled that this screening concludes nothing and that five
                // clinical criteria remain to be verified by a doctor.
                if (outcome == QuizOutcome.NOT_CONSISTENT) {
                    Paragraph(stringResource(R.string.quiz_answer_no))
                    Spacer(Modifier.height(Spacing.s.dp))
                }
                Paragraph(stringResource(R.string.quiz_outcome_body))
                Spacer(Modifier.height(Spacing.s.dp))
                Text(stringResource(R.string.quiz_outcome_export), style = PendulumType.caption, color = c.textTertiary)
                Spacer(Modifier.height(Spacing.s.dp))
                TextButton(onClick = onReview) { Text(stringResource(R.string.quiz_review)) }
            }
        }
    }
}

@Preview(name = "Questionnaire — single question", widthDp = 411, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun PreviewQuestion() = PendulumTheme { ScreeningQuizScreen(null, {}, {}, {}) }

@Preview(name = "Questionnaire — outcome consistent", widthDp = 411, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun PreviewOutcome() = PendulumTheme {
    ScreeningQuizScreen(QuizOutcome.CONSISTENT, {}, {}, {})
}
