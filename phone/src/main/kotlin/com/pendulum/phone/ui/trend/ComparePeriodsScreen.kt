package com.pendulum.phone.ui.trend

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.BlockingState
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.formaterValeur
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.text.resoudre
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.texte
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * La comparaison de deux periodes.
 *
 * ### L'ordre d'affichage est le message (P3)
 *
 * 1. le verdict de distinguabilite ;
 * 2. les deux estimations avec leurs intervalles ;
 * 3. la difference avec son intervalle ;
 * 4. la dispersion propre de l'utilisateur, comme etalon du bruit ;
 * 5. le nombre de nuits qu'il faudrait pour trancher.
 *
 * Le verdict passe **avant** le chiffre parce qu'un lecteur presse lit la premiere ligne et
 * s'arrete. Mettre « −9/h » en tete et la reserve en dessous, c'est publier « −9/h ».
 *
 * ### Ce que cet ecran ne dit jamais
 *
 * Aucun verbe d'evolution, dans aucun des deux cas. Quand l'ecart est indistinguable, la premiere
 * ligne est « Variation non concluante ». Quand il est distinguable, elle est « Différence
 * supérieure à la variabilité entre vos nuits » — et l'application precise aussitot qu'elle ne
 * sait pas ce qui l'a causee : traitement, sommeil, alcool, fer, literie ou position de la montre
 * produiraient le meme effet a l'ecran.
 */
@Composable
fun ComparePeriodsScreen(
    resultat: Aggregat.Comparaison?,
    motifIndisponible: Pair<UiText, Int>?,
    libellePeriodeA: String,
    libellePeriodeB: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    PendulumScreen(modifier) {
        if (motifIndisponible != null || resultat == null) {
            val (periode, nuits) = motifIndisponible ?: (texte(R.string.compare_period_a) to 0)
            BlockingState(
                titre = stringResource(R.string.compare_unavailable_title),
                corps = stringResource(R.string.compare_unavailable_body, periode.resoudre(), nuits),
            )
            return@PendulumScreen
        }

        PendulumCard {
            // 1. Le verdict, en premier, en gras, sans chiffre.
            Text(resultat.verdict.resoudre(), style = PendulumType.bodyEmph, color = c.textPrimary)
            Spacer(Modifier.height(Spacing.sm.dp))

            // 2. Les deux estimations, chacune avec son intervalle et son n (P2).
            InlineValue(
                stringResource(R.string.compare_period_a),
                ligneEstimation(resultat.a),
                note = libellePeriodeA,
            )
            InlineValue(
                stringResource(R.string.compare_period_b),
                ligneEstimation(resultat.b),
                note = libellePeriodeB,
            )

            // 3. La difference, avec son intervalle. Jamais dessinee comme une fleche : une
            // fleche vers le bas se lit « ca va dans le bon sens ».
            InlineValue(
                stringResource(R.string.compare_difference),
                "${signe(resultat.difference)}${formaterValeur(kotlin.math.abs(resultat.difference), resultat.a.grandeur)} " +
                    "${stringResource(resultat.a.grandeur.unite)}  (95% CI " +
                    "${signe(resultat.diffCiBas)}${formaterValeur(kotlin.math.abs(resultat.diffCiBas), resultat.a.grandeur)} to " +
                    "${signe(resultat.diffCiHaut)}${formaterValeur(kotlin.math.abs(resultat.diffCiHaut), resultat.a.grandeur)})",
            )

            Spacer(Modifier.height(Spacing.sm.dp))
            resultat.motifNonConcluant?.let { Paragraphe(it.resoudre()) }
            if (resultat.distinguable) Paragraphe(stringResource(R.string.compare_no_cause))

            Spacer(Modifier.height(Spacing.s.dp))
            // 4. L'etalon du bruit : la dispersion des nuits de l'utilisateur lui-meme.
            Text(
                stringResource(
                    R.string.trend_dispersion,
                    formaterValeur(resultat.dispersion, resultat.a.grandeur),
                    stringResource(resultat.a.grandeur.unite),
                ),
                style = PendulumType.caption,
                color = c.textTertiary,
            )

            Spacer(Modifier.height(Spacing.s.dp))
            // 5. Le nombre de nuits necessaires — un ordre de grandeur, et le texte le dit.
            Paragraphe(
                resultat.nuitsNecessaires
                    ?.let { stringResource(R.string.compare_nights_needed, it) }
                    ?: stringResource(R.string.compare_out_of_reach),
            )
        }
    }
}

/**
 * La ligne d'estimation d'une periode : la mediane, son intervalle, le nombre de nuits.
 *
 * Elle etait assemblee ici a coups de concatenation, ce qui figeait a la fois « CI » et « nights »
 * en anglais **et l'ordre des trois membres** : une langue qui place le nombre de nuits en tete
 * n'avait aucun moyen de le dire. La phrase entiere est donc une seule ressource, a arguments
 * positionnels, et le code ne fournit plus que les valeurs.
 */
@Composable
private fun ligneEstimation(r: Aggregat.Resultat): String = stringResource(
    R.string.compare_estimate_line,
    formaterValeur(r.mediane, r.grandeur),
    stringResource(r.grandeur.unite),
    formaterValeur(r.ciBas, r.grandeur),
    formaterValeur(r.ciHaut, r.grandeur),
    r.nuits,
)

private fun signe(v: Double): String = if (v < 0) "−" else "+"

