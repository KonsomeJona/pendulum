package com.pendulum.phone.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * L'avertissement, relu a la demande depuis Reglages › A propos.
 *
 * ### Pourquoi ce n'est pas [com.pendulum.phone.ui.onboarding.DisclaimerPage]
 *
 * L'ecran d'assistant porte le meme texte, mais aussi un defilement bloquant et quatre
 * acquittements a cocher : c'est une **porte**, et elle n'a de sens qu'une fois. La redemander a
 * quelqu'un qui vient relire une phrase trois mois plus tard transformerait la relecture en
 * corvee, donc en chose qu'on ne fait pas. Ici le texte est simplement lisible.
 *
 * Le lien vers cet ecran n'est pas un confort : `06-interface.md` demande que l'avertissement
 * reste accessible en permanence, et la ligne de reglages qui devait y mener appelait un `{}`.
 * Une entree qui se clique sans rien faire est pire qu'une entree absente — elle apprend que
 * l'application est cassee.
 */
@Composable
fun AvertissementScreen(modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    PendulumScreen(modifier) {
        Text(stringResource(R.string.notice_title), style = PendulumType.titleL, color = c.textPrimary)
        PendulumCard {
            Paragraphe(stringResource(R.string.notice_body), couleur = c.textPrimary)
        }
        Text(stringResource(R.string.notice_reminder), style = PendulumType.caption, color = c.textTertiary)
        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Preview(name = "Notice — read again", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAvertissement() = PendulumTheme { AvertissementScreen() }
