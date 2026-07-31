package com.pendulum.phone.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.ErreurPendulum
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * Une carte : surface, bordure 1 dp, **aucune ombre**.
 *
 * L'elevation tonale empilee de Material 3 est illisible en sombre et ne survit pas a
 * l'impression. Une bordure fine donne exactement la meme image a l'ecran et sur le papier — ce
 * qui est la condition pour qu'une capture d'ecran et une page de PDF se ressemblent, et donc
 * pour qu'un utilisateur puisse montrer l'un en parlant de l'autre.
 */
@Composable
fun PendulumCard(
    modifier: Modifier = Modifier,
    contenu: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = LocalPendulumColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PendulumShapes.card)
            .background(c.surface)
            .border(1.dp, c.outline, PendulumShapes.card)
            .padding(Spacing.card.dp),
        content = contenu,
    )
}

@Composable
fun SectionHeader(texte: String, modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    Text(
        texte.uppercase(),
        style = PendulumType.label,
        color = c.textTertiary,
        modifier = modifier.padding(bottom = Spacing.s.dp),
    )
}

/** Paragraphe explicatif : largeur bornee a ~60 caracteres, sinon il devient illisible. */
@Composable
fun Paragraphe(texte: String, modifier: Modifier = Modifier, couleur: Color? = null) {
    val c = LocalPendulumColors.current
    Text(
        texte,
        style = PendulumType.body,
        color = couleur ?: c.textSecondary,
        modifier = modifier.widthIn(max = Spacing.paragraphMax.dp),
    )
}

/**
 * Le chiffre agrege et son incertitude — **le seul site d'usage de `metricXL` dans l'application**.
 *
 * La signature est le garde-fou : ce composable prend un [Aggregat.Resultat], qui ne peut pas
 * exister sans son intervalle ni son `n`. Il est donc **impossible** d'afficher une mediane nue
 * par oubli : il n'y a pas de parametre a omettre (P2).
 *
 * L'intervalle et le nombre de nuits sont sur la ligne suivante, a un cran de taille en dessous,
 * jamais en note de bas de page, jamais dans une infobulle. Une incertitude qu'il faut aller
 * chercher n'est pas affichee.
 */
@Composable
fun MetricHeadline(
    resultat: Aggregat.Resultat,
    libelle: String,
    qualificatif: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formaterValeur(resultat.mediane, resultat.grandeur),
                style = PendulumType.metricXL,
                color = c.textPrimary,
            )
            Spacer(Modifier.width(Spacing.xs.dp))
            Text(
                resultat.grandeur.unite,
                style = PendulumType.titleM,
                color = c.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            qualificatif?.let {
                Spacer(Modifier.width(Spacing.sm.dp))
                Text(
                    "· $it",
                    style = PendulumType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        Text(
            Textes.Tendance.intervalleEtN(
                formaterValeur(resultat.ciBas, resultat.grandeur),
                formaterValeur(resultat.ciHaut, resultat.grandeur),
                resultat.nuits,
            ),
            style = PendulumType.bodyNum,
            color = c.textSecondary,
        )
        Text(libelle, style = PendulumType.caption, color = c.textTertiary)
    }
}

/** Arrondi a l'entier : jamais de decimale sur un rythme ni sur un index horaire. */
fun formaterValeur(v: Double, g: Aggregat.Grandeur): String =
    if (g.decimales == 0) Math.round(v).toString() else "%.${g.decimales}f".format(v)

/**
 * La phrase de position, dans un cadre **neutre dans les cinq cas**.
 *
 * Colorer le cadre en rouge quand l'intervalle est entierement au-dessus du seuil
 * transformerait une mesure en verdict, et un verdict rouge lu a 7 h du matin ne se discute plus
 * avec un medecin — il s'agit deja d'une certitude.
 */
@Composable
fun PositionBox(texte: String, modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    if (texte.isBlank()) return
    Box(
        modifier
            .fillMaxWidth()
            .clip(PendulumShapes.field)
            .border(1.dp, c.outline, PendulumShapes.field)
            .padding(Spacing.sm.dp),
    ) {
        Paragraphe(texte, couleur = c.textPrimary)
    }
}

/** Une valeur en ligne : libelle a gauche, valeur a droite, note optionnelle en dessous. */
@Composable
fun InlineValue(libelle: String, valeur: String, note: String? = null, barre: Boolean = false) {
    val c = LocalPendulumColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(libelle, style = PendulumType.body, color = c.textSecondary)
            Text(
                valeur,
                style = PendulumType.bodyNum,
                color = c.textPrimary,
                textDecoration = if (barre) TextDecoration.LineThrough else null,
            )
        }
        note?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
    }
}

/** Drapeau de qualite. Ce n'est pas une alerte : c'est une propriete mesuree de la nuit. */
@Composable
fun QualityChip(libelle: String) {
    val c = LocalPendulumColors.current
    AssistChip(
        onClick = {},
        label = { Text(libelle, style = PendulumType.caption) },
        shape = PendulumShapes.chip,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = c.surfaceMuted,
            labelColor = c.textSecondary,
        ),
    )
}

/**
 * Pastilles pleines/vides, **jamais une barre de progression**.
 *
 * Une barre suggere un score qui monte, donc une performance, donc quelque chose a battre. Il n'y
 * a rien a battre ici : le seul objectif encourage par l'application est d'enregistrer assez de
 * nuits, et il se represente par des faits — trois cercles, dont deux sont pleins.
 */
@Composable
fun CollectionProgress(faites: Int, requises: Int, modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm.dp)) {
        repeat(requises) { i ->
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (i < faites) c.accent else Color.Transparent)
                    .border(1.dp, if (i < faites) c.accent else c.outline, CircleShape),
            )
        }
    }
}

/**
 * L'ecran de refus : sous trois nuits, sous cinq nuits en comparaison, export impossible.
 *
 * Ce n'est pas une erreur et ce n'est pas un etat vide decoratif. C'est le comportement normal du
 * produit, motive **en chiffres** plutot qu'en consigne : l'utilisateur est technicien, et
 * « le seuil de 15/h n'est franchi que sur environ une nuit sur trois » le convainc la ou
 * « veuillez enregistrer plus de nuits » l'agace.
 */
@Composable
fun BlockingState(
    titre: String,
    corps: String,
    action: String? = null,
    entete: @Composable (() -> Unit)? = null,
    pied: @Composable (() -> Unit)? = null,
) {
    val c = LocalPendulumColors.current
    PendulumCard {
        entete?.invoke()
        Text(titre, style = PendulumType.titleM, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm.dp))
        Paragraphe(corps)
        action?.let {
            Spacer(Modifier.height(Spacing.sm.dp))
            Text(it, style = PendulumType.bodyEmph, color = c.textPrimary)
        }
        pied?.let {
            Spacer(Modifier.height(Spacing.m.dp))
            it()
        }
    }
}

/**
 * Une erreur, toujours de la meme forme : titre neutre, cause, action, bouton, code.
 *
 * La teinte suit [ErreurPendulum.technique] et non la gravite ressentie : rouge seulement si quelque
 * chose est reellement casse (transfert, permission, integrite, stockage), ambre pour tout le
 * reste, qui n'est pas une panne mais une **situation**. Employer le rouge pour une nuit courte
 * apprend a l'utilisateur a ignorer le rouge.
 */
@Composable
fun ErrorCard(
    erreur: ErreurPendulum,
    onAction: () -> Unit = {},
    onActionSecondaire: () -> Unit = {},
) {
    val c = LocalPendulumColors.current
    val teinte = if (erreur.technique) c.error else c.attention
    PendulumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(teinte))
            Spacer(Modifier.width(Spacing.s.dp))
            Text(erreur.titre, style = PendulumType.titleM, color = c.textPrimary)
        }
        Spacer(Modifier.height(Spacing.s.dp))
        Paragraphe(erreur.cause)
        Spacer(Modifier.height(Spacing.s.dp))
        Paragraphe(erreur.action)
        if (erreur.bouton != null || erreur.boutonSecondaire != null) {
            Spacer(Modifier.height(Spacing.sm.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s.dp)) {
                erreur.bouton?.let {
                    Button(onClick = onAction, shape = PendulumShapes.button) { Text(it) }
                }
                erreur.boutonSecondaire?.let {
                    OutlinedButton(onClick = onActionSecondaire, shape = PendulumShapes.button) { Text(it) }
                }
            }
        }
        Spacer(Modifier.height(Spacing.s.dp))
        // Le code est utile a un utilisateur technicien, et il est le meme dans le journal.
        Text(erreur.code, style = PendulumType.caption, color = c.textTertiary)
    }
}

/**
 * Un bouton dont le motif d'indisponibilite est ecrit **sur le bouton lui-meme**.
 *
 * Jamais un bouton actif qui echoue, jamais un bouton grise sans explication : l'utilisateur qui
 * appuie sur « Exporter » et recoit une erreur apprend a se mefier de tous les boutons.
 */
@Composable
fun BoutonMotive(
    libelle: String,
    motifIndisponible: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    Button(
        onClick = onClick,
        enabled = motifIndisponible == null,
        shape = PendulumShapes.button,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accent,
            contentColor = c.onAccent,
            disabledContainerColor = c.surfaceMuted,
            disabledContentColor = c.textTertiary,
        ),
    ) {
        Text(motifIndisponible ?: libelle, style = PendulumType.body)
    }
}

/** Barre de progression : l'un des deux seuls elements animes autorises dans l'application. */
@Composable
fun Progression(fraction: Float, modifier: Modifier = Modifier) {
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
    titre: String,
    corps: String,
    confirmer: String,
    annuler: String = "Annuler",
    onConfirmer: () -> Unit,
    onAnnuler: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(titre, style = PendulumType.titleM) },
        text = { Paragraphe(corps) },
        confirmButton = { TextButton(onClick = onConfirmer) { Text(confirmer) } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text(annuler) } },
        shape = PendulumShapes.card,
    )
}
