package com.pendulum.phone.ui.nights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.chart.GrapheNuit
import com.pendulum.phone.ui.chart.HypnogrammeSpec
import com.pendulum.phone.ui.chart.Hypnogramme
import com.pendulum.phone.ui.chart.NuitChartSpec
import com.pendulum.phone.ui.chart.XTransform
import com.pendulum.phone.ui.common.DataTableSheet
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.ApercuDonnees
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/** Ce que le detail d'une nuit sait afficher, calcule en amont par le ViewModel. */
data class NuitDetailUi(
    val nuit: NuitUi,
    val auLit: String,
    val graphe: NuitChartSpec,
    val hypnogramme: HypnogrammeSpec,
    val mouvements: Int,
    val plms: Int,
    val plmw: Int,
    val ecartesPosture: Int,
    val ecartesDuree: Int,
    val series: Int,
    val couvertureSeries: String,
    val imiMedianSec: Double,
    val controles: List<Controle>,
    val regleAppliquee: String,
)

/** Un controle de qualite : sa valeur mesuree, son seuil, son etat. Les trois, toujours. */
data class Controle(val libelle: String, val valeur: String, val seuil: String, val ok: Boolean)

/**
 * Le detail d'une nuit — cinq sections.
 *
 * C'est le seul endroit ou le chiffre d'une nuit est presente avec son avertissement **fusionne
 * dans le meme bloc**. La fusion est deliberee : un grand chiffre suivi d'une precaution en
 * dessous produit exactement le comportement qu'on veut eviter, parce que le chiffre est lu et la
 * precaution ne l'est pas. Ici la phrase fait partie du bloc de valeur, a la meme distance de
 * l'oeil.
 *
 * Le panneau de parametres, replie par defaut, est l'endroit ou mord la regle anti-auto-tromperie :
 * il n'y a **pas** de « recalculer cette nuit ». Un changement de parametre est global, bump le
 * `paramsHash`, et declenche un rescore de toutes les nuits depuis le brut.
 */
@Composable
fun NightDetailScreen(
    detail: NuitDetailUi,
    onVoirTendance: () -> Unit,
    onAppliquerATout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    val transform = remember(detail.graphe) { XTransform(detail.graphe.debutMs, detail.graphe.finMs) }
    var curseur by remember { mutableStateOf<Long?>(null) }
    var valeursOuvertes by remember { mutableStateOf(false) }
    var paramsOuverts by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        // --- Section 1 : en-tete + valeur de la nuit, fusionnees
        PendulumCard {
            Text(Textes.Nuits.Detail.titre(detail.nuit.dateLisible), style = PendulumType.titleL, color = c.textPrimary)
            Text(
                "${detail.nuit.debut} → ${detail.nuit.fin}  ·  ${detail.auLit} in bed  ·  ${detail.nuit.sommeilLisible}",
                style = PendulumType.bodyNum,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.sm.dp))
            Text(
                "${Math.round(detail.nuit.rythmeSec)} s  ·  ${Math.round(detail.nuit.comptePlmi)}/h",
                style = PendulumType.metricL,
                color = c.textSecondary,
            )
            Text(Textes.Nuits.VALEUR_UNE_NUIT, style = PendulumType.caption, color = c.textTertiary)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(Textes.Nuits.VALEUR_UNE_NUIT_LONG)
            Spacer(Modifier.height(Spacing.s.dp))
            TextButton(onClick = onVoirTendance) { Text(Textes.Nuits.VOIR_TENDANCE) }
        }

        // --- Section 2 : les deux graphes, un seul axe X, un seul curseur
        PendulumCard {
            GrapheNuit(
                spec = detail.graphe,
                transform = transform,
                curseurMs = curseur,
                onCurseur = { curseur = it },
                onEvenement = {},
                onValeurs = { valeursOuvertes = true },
            )
            Hypnogramme(detail.hypnogramme, transform, curseur)
            Spacer(Modifier.height(Spacing.xs.dp))
            Text(detail.hypnogramme.statistiques, style = PendulumType.caption, color = c.textTertiary)
        }

        // --- Section 3 : evenements detectes
        PendulumCard {
            SectionHeader(Textes.Nuits.Detail.EVENEMENTS)
            InlineValue(Textes.Nuits.Detail.MOUVEMENTS, detail.mouvements.toString())
            InlineValue(Textes.Nuits.Detail.DONT_SOMMEIL, detail.plms.toString())
            InlineValue(Textes.Nuits.Detail.DONT_EVEIL, detail.plmw.toString())
            InlineValue(Textes.Nuits.Detail.ECARTES_POSTURE, detail.ecartesPosture.toString())
            InlineValue(Textes.Nuits.Detail.ECARTES_DUREE, detail.ecartesDuree.toString())
            InlineValue(Textes.Nuits.Detail.SERIES, "${detail.series}   covering ${detail.couvertureSeries}")
            InlineValue(Textes.Nuits.Detail.IMI_MEDIAN, "%.1f s".format(detail.imiMedianSec))
            InlineValue(
                Textes.Nuits.Detail.RYTHME_FONDAMENTAL,
                "${Math.round(detail.nuit.rythmeSec)} s",
                note = "Estimated by deconvolution of the harmonics: a sensor on a single leg " +
                    "sees a doubled interval when movements alternate left/right.",
            )
        }

        // --- Section 4 : qualite de la nuit
        PendulumCard {
            SectionHeader(Textes.Nuits.Detail.QUALITE)
            detail.controles.forEach {
                InlineValue(it.libelle, "${it.valeur}   ${if (it.ok) "✓" else "✗"}", note = "threshold ${it.seuil}")
            }
            InlineValue(Textes.Nuits.Detail.REGLE_APPLIQUEE, detail.regleAppliquee)
        }

        // --- Section 5 : parametres
        PendulumCard {
            TextButton(onClick = { paramsOuverts = !paramsOuverts }) {
                Text(Textes.Nuits.Detail.PARAMS_AVANCES)
            }
            if (paramsOuverts) {
                Paragraphe(Textes.Nuits.Detail.PAS_DE_REGLAGE_PAR_NUIT)
                Spacer(Modifier.height(Spacing.s.dp))
                OutlinedButton(onClick = onAppliquerATout, shape = PendulumShapes.button) {
                    Text(Textes.Nuits.Detail.RECALCULER_TOUTES)
                }
            }
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }

    if (valeursOuvertes) {
        DataTableSheet(
            colonnes = listOf("#", "Start", "Duration", "Ampl."),
            lignes = detail.graphe.marqueurs.take(200).mapIndexed { i, m ->
                listOf("${i + 1}", "${(m.onsetMs - detail.graphe.debutMs) / 1000} s", "2.4 s", "×9.2")
            },
            onFermer = { valeursOuvertes = false },
        )
    }
}

/** Jeu de demonstration, partage par les apercus et par le squelette de navigation. */
val apercuNuitDetail = NuitDetailUi(
    nuit = ApercuDonnees.nuits.first(),
    auLit = "7 h 46",
    graphe = ApercuDonnees.nuit,
    hypnogramme = ApercuDonnees.hypnogramme,
    mouvements = 412,
    plms = 278,
    plmw = 64,
    ecartesPosture = 57,
    ecartesDuree = 13,
    series = 31,
    couvertureSeries = "3 h 12",
    imiMedianSec = 23.4,
    controles = listOf(
        Controle(Textes.Nuits.Detail.COUVERTURE, "99.2%", "97%", true),
        Controle(Textes.Nuits.Detail.PLUS_GRAND_TROU, "1.8 s", "5 s", true),
        Controle(Textes.Nuits.Detail.CUMUL_TROUS, "11 s", "120 s", true),
        Controle(Textes.Nuits.Detail.FREQUENCE, "50.21 Hz", "50 Hz", true),
        Controle(Textes.Nuits.Detail.BATTERIE_FIN, "34%", "20%", true),
        Controle(Textes.Nuits.Detail.PORTE, "96.4%", "90%", true),
        Controle(Textes.Nuits.Detail.SOMMEIL_TOTAL, "6 h 58", "4 h", true),
    ),
    regleAppliquee = "AASM v3 · algo 1.4.0 · profile “default”",
)

@Preview(name = "Night — detail", widthDp = 411, heightDp = 1600, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuDetail() = PendulumTheme {
    NightDetailScreen(apercuNuitDetail, {}, {})
}

@Preview(name = "Night — detail without hypnogram", widthDp = 411, heightDp = 1600, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuDetailSansHypno() = PendulumTheme {
    NightDetailScreen(apercuNuitDetail.copy(hypnogramme = ApercuDonnees.hypnogrammeAbsent), {}, {})
}
