package com.pendulum.phone.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * Le meme contenu qu'un graphe, sous forme de tableau.
 *
 * Deux usages qui n'en font qu'un :
 *
 * - **l'alternative accessible.** Un `contentDescription` sur un canvas resume ; il ne restitue
 *   pas des donnees. Un lecteur d'ecran a besoin d'un tableau, et c'est le seul chemin
 *   reellement utilisable sans vision.
 * - **le mode « je veux le chiffre exact ».** L'utilisateur vise est technicien ; lui refuser la
 *   valeur precise au motif qu'elle est incertaine serait a la fois condescendant et
 *   contre-productif — il en a besoin pour verifier que la mesure a fonctionne.
 *
 * Les deux besoins ont exactement la meme reponse, ce qui est le signe qu'elle est la bonne.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTableSheet(
    colonnes: List<String>,
    lignes: List<List<String>>,
    onFermer: () -> Unit,
) {
    val c = LocalPendulumColors.current
    val etat = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onFermer,
        sheetState = etat,
        shape = PendulumShapes.sheet,
        containerColor = c.surfaceElevated,
    ) {
        Column(Modifier.padding(horizontal = Spacing.m.dp).padding(bottom = Spacing.l.dp)) {
            Text(Textes.Graphes.VALEURS, style = PendulumType.titleM, color = c.textPrimary)
            Text(Textes.Graphes.VALEURS_NOTE, style = PendulumType.caption, color = c.textTertiary)
            Spacer(Modifier.height(Spacing.sm.dp))

            val scroll = rememberScrollState()
            Column(Modifier.horizontalScroll(scroll)) {
                Row(Modifier.fillMaxWidth()) {
                    colonnes.forEach {
                        Text(it, style = PendulumType.label, color = c.textTertiary, modifier = Modifier.width(96.dp))
                    }
                }
                Spacer(Modifier.height(Spacing.xs.dp))
                LazyColumn {
                    items(lignes) { ligne ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            ligne.forEach {
                                // Chiffres tabulaires : sans eux les colonnes ne s'alignent pas.
                                Text(it, style = PendulumType.bodyNum, color = c.textPrimary, modifier = Modifier.width(96.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
