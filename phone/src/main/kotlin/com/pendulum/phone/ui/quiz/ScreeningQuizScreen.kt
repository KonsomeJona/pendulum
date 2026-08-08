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
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * Les trois issues possibles. Aucune echelle de severite : l'IRLS est sous copyright, exclue.
 *
 * Le titre est un **identifiant de ressource** et non une chaine : un constructeur d'`enum` n'a pas
 * de `Context`, et le resoudre ici figerait la langue au chargement de la classe.
 */
enum class IssueQuestionnaire(@StringRes val titre: Int) {
    COMPATIBLE(R.string.quiz_outcome_consistent),
    NON_COMPATIBLE(R.string.quiz_outcome_not_consistent),
    INCOMPLET(R.string.quiz_outcome_incomplete),
}

/**
 * Le questionnaire de depistage.
 *
 * ### Aucun score en gros
 *
 * L'issue est une phrase, pas un nombre. Un score affiche en grand se compare, se suit dans le
 * temps et finit par etre traite comme une mesure — alors que c'est un depistage a trois issues.
 *
 * ### Il ne remplace pas la mesure, et la mesure ne le remplace pas
 *
 * L'en-tete le dit explicitement : ce questionnaire porte sur ce qui est ressenti **a l'eveil**,
 * la montre mesure ce qui se passe **pendant le sommeil**. Les deux se completent. Le diagnostic
 * du syndrome des jambes sans repos est clinique et repose sur les symptomes, pas sur un capteur.
 */
@Composable
fun ScreeningQuizScreen(
    issue: IssueQuestionnaire?,
    onOui: () -> Unit,
    onNon: () -> Unit,
    onRevoir: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    Column(
        modifier.fillMaxSize().padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.quiz_title), style = PendulumType.titleL, color = c.textPrimary)

        if (issue == null) {
            PendulumCard {
                Paragraphe(stringResource(R.string.quiz_header))
                Spacer(Modifier.height(Spacing.m.dp))
                Paragraphe(stringResource(R.string.quiz_single_question), couleur = c.textPrimary)
                Spacer(Modifier.height(Spacing.m.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm.dp)) {
                    Button(onClick = onOui, shape = PendulumShapes.button) { Text(stringResource(R.string.quiz_yes)) }
                    OutlinedButton(onClick = onNon, shape = PendulumShapes.button) { Text(stringResource(R.string.quiz_no)) }
                }
            }
        } else {
            PendulumCard {
                // Titre neutre, aucune couleur alarmante : c'est un depistage, pas un verdict.
                Text(stringResource(issue.titre), style = PendulumType.bodyEmph, color = c.textPrimary)
                Spacer(Modifier.height(Spacing.s.dp))
                // L'avertissement s'affiche sur les **trois** issues, et la reponse negative s'y
                // ajoute au lieu de le remplacer. Le remplacement etait le defaut : le seul chemin
                // ou l'avertissement disparaissait etait le chemin rassurant, c'est-a-dire celui
                // ou il faut le plus rappeler que ce depistage ne conclut rien et que cinq
                // criteres cliniques restent a verifier par un medecin.
                if (issue == IssueQuestionnaire.NON_COMPATIBLE) {
                    Paragraphe(stringResource(R.string.quiz_answer_no))
                    Spacer(Modifier.height(Spacing.s.dp))
                }
                Paragraphe(stringResource(R.string.quiz_outcome_body))
                Spacer(Modifier.height(Spacing.s.dp))
                Text(stringResource(R.string.quiz_outcome_export), style = PendulumType.caption, color = c.textTertiary)
                Spacer(Modifier.height(Spacing.s.dp))
                TextButton(onClick = onRevoir) { Text(stringResource(R.string.quiz_review)) }
            }
        }
    }
}

@Preview(name = "Questionnaire — single question", widthDp = 411, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuQuestion() = PendulumTheme { ScreeningQuizScreen(null, {}, {}, {}) }

@Preview(name = "Questionnaire — outcome consistent", widthDp = 411, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuIssue() = PendulumTheme {
    ScreeningQuizScreen(IssueQuestionnaire.COMPATIBLE, {}, {}, {})
}
