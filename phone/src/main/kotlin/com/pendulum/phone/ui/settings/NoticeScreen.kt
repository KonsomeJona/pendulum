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
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The notice, read again on demand from Settings › About.
 *
 * ### Why this is not [com.pendulum.phone.ui.onboarding.DisclaimerPage]
 *
 * The onboarding screen carries the same text, but also a blocking scroll and four
 * acknowledgements to tick: it is a **gate**, and it only makes sense once. Asking for it again
 * from someone who comes back to re-read one sentence three months later would turn the re-reading
 * into a chore, and therefore into something one does not do. Here the text is simply readable.
 *
 * The link to this screen is not a comfort: `06-interface.md` requires the notice to stay
 * permanently reachable, and the settings row that was meant to lead to it called a `{}`. An entry
 * that can be tapped without doing anything is worse than a missing entry — it teaches that the
 * application is broken.
 */
@Composable
fun NoticeScreen(modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    PendulumScreen(modifier) {
        Text(stringResource(R.string.notice_title), style = PendulumType.titleL, color = c.textPrimary)
        PendulumCard {
            Paragraph(stringResource(R.string.notice_body), color = c.textPrimary)
        }
        Text(stringResource(R.string.notice_reminder), style = PendulumType.caption, color = c.textTertiary)
        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Preview(name = "Notice — read again", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun NoticePreview() = PendulumTheme { NoticeScreen() }
