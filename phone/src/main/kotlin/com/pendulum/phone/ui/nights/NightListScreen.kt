package com.pendulum.phone.ui.nights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.QualityChip
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * La liste des nuits, antichronologique.
 *
 * ### Une nuit ratee n'est jamais silencieusement effacee
 *
 * Elle apparait, barree, avec son motif. Le compteur explique toujours d'ou vient l'ecart entre
 * « enregistrees » et « eligibles ». Une nuit qui disparaitrait sans trace est une nuit qu'on
 * oublie d'expliquer, et un compteur qui ne tombe pas juste fait douter du reste.
 *
 * ### Il n'y a pas de bouton « exclure cette nuit »
 *
 * C'est le garde-fou 4 de `SPEC-v2.md` §3, et l'absence est aussi importante que ce qui est
 * present. Une exclusion decidee **apres** avoir vu le chiffre est un mecanisme d'auto-tromperie
 * complet a lui seul : on ecarte de bonne foi les nuits qui ne vont pas dans le sens attendu, et
 * la tendance qui en sort est fabriquee. La parade n'est pas de resister a la tentation, c'est de
 * rendre le geste impossible — les exclusions sont des predicats deterministes evalues en SQL
 * avant tout calcul. L'ecran l'explique une fois, en clair, plutot que de laisser l'utilisateur
 * chercher un bouton qui n'existe pas.
 */
@Composable
fun NightListScreen(
    nuits: List<NuitUi>,
    onNuit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filtre by remember { mutableStateOf(FiltreNuits.TOUTES) }
    val visibles = remember(nuits, filtre) { filtre.filtrer(nuits) }

    Column(modifier.fillMaxSize().padding(horizontal = Spacing.screen.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Spacing.s.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s.dp),
        ) {
            FiltreNuits.entries.forEach { f ->
                FilterChip(
                    selected = filtre == f,
                    onClick = { filtre = f },
                    label = { Text(f.libelle, style = PendulumType.label) },
                    shape = PendulumShapes.chip,
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s.dp)) {
            items(visibles, key = { it.sessionHex }) { NightRow(it, onNuit) }
            item {
                Spacer(Modifier.height(Spacing.m.dp))
                PendulumCard { Paragraphe(Textes.Nuits.PAS_DE_BOUTON_EXCLURE) }
                Spacer(Modifier.height(Spacing.l.dp))
            }
        }
    }
}

enum class FiltreNuits(val libelle: String) {
    TOUTES(Textes.Nuits.FILTRE_TOUTES),
    ELIGIBLES(Textes.Nuits.FILTRE_ELIGIBLES),
    ECARTEES(Textes.Nuits.FILTRE_ECARTEES),
    ;

    fun filtrer(l: List<NuitUi>): List<NuitUi> = when (this) {
        TOUTES -> l
        ELIGIBLES -> l.filter { it.etat != EtatNuit.ECARTEE }
        ECARTEES -> l.filter { it.etat == EtatNuit.ECARTEE }
    }
}

/**
 * Une ligne de nuit.
 *
 * Le chiffre de la nuit y figure — l'utilisateur est technicien et le lui cacher serait a la fois
 * condescendant et contre-productif — mais **au corps de texte, en couleur secondaire**, avec la
 * mention « valeur d'une seule nuit ». La contrainte est sur la mise en avant, pas sur la
 * disponibilite (P7). Ce chiffre n'apparait jamais en titre, ni dans une notification, ni dans le
 * resume de l'export.
 *
 * ### Sauf tant qu'il n'a pas ete demande
 *
 * Une nuit dont le resultat n'a jamais ete devoile affiche « result not shown » a la place de sa
 * valeur (garde-fou 2). Sans cela le masque du detail ne masquerait rien : il suffirait d'ouvrir
 * la liste pour lire, sans trace, le chiffre qu'on est cense demander. Le devoilement se fait sur
 * l'ecran de detail, en un geste, et il est horodate.
 */
@Composable
fun NightRow(n: NuitUi, onNuit: (String) -> Unit) {
    val c = LocalPendulumColors.current
    val ecartee = n.etat == EtatNuit.ECARTEE
    val devoile = n.devoileeAtMs != null
    PendulumCard(
        Modifier
            .heightIn(min = 72.dp)
            .clickable { onNuit(n.sessionHex) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${n.dateLisible}   ${n.jourAbrege}", style = PendulumType.titleM, color = c.textPrimary)
            PastilleEtat(n.etat)
        }
        Text(
            "${n.debut} → ${n.fin} · ${n.sommeilLisible} (${n.sourceSommeil})",
            style = PendulumType.caption,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(Spacing.xs.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (devoile) Mapping.rythmeLisible(n.rythmeSec) else Textes.EcranAccueil.Resultat.MASQUE_LIGNE,
                style = PendulumType.bodyNum,
                color = if (devoile) c.textSecondary else c.textTertiary,
                textDecoration = if (ecartee && devoile) TextDecoration.LineThrough else null,
            )
            if (devoile) {
                Text(
                    "   ${Textes.Nuits.VALEUR_UNE_NUIT}",
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
            }
        }
        n.motif?.let {
            Text("${Textes.Nuits.ETAT_ECARTEE}: $it", style = PendulumType.caption, color = c.attention)
        }
        if (n.drapeaux.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs.dp)) {
                n.drapeaux.take(3).forEach { QualityChip(it.libelle) }
                if (n.drapeaux.size > 3) {
                    Text("+${n.drapeaux.size - 3}", style = PendulumType.caption, color = c.textTertiary)
                }
            }
        }
    }
}

/** Trois etats, trois glyphes distincts : la couleur ne porte jamais seule l'information. */
@Composable
private fun PastilleEtat(etat: EtatNuit) {
    val c = LocalPendulumColors.current
    val (glyphe, libelle, teinte) = when (etat) {
        EtatNuit.ELIGIBLE -> Triple("●", Textes.Nuits.ETAT_ELIGIBLE, c.accent)
        EtatNuit.PROVISOIRE -> Triple("◐", Textes.Nuits.ETAT_PROVISOIRE, c.attention)
        EtatNuit.ECARTEE -> Triple("○", Textes.Nuits.ETAT_ECARTEE, c.textTertiary)
    }
    Text("$glyphe $libelle", style = PendulumType.label, color = teinte)
}

