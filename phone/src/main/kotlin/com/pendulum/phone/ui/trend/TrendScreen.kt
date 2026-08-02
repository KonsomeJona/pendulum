package com.pendulum.phone.ui.trend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.chart.GrapheTendance
import com.pendulum.phone.ui.common.BlockingState
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.CollectionProgress
import com.pendulum.phone.ui.common.DataTableSheet
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.MetricHeadline
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PositionBox
import com.pendulum.phone.ui.common.StatusStrip
import com.pendulum.phone.ui.common.formaterValeur
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * L'ecran Tendance : destination de depart, et le premier ecran concu du produit.
 *
 * ### Ce qu'il n'est plus
 *
 * La destination de depart. Elle l'etait, et l'accueil melangeait alors le geste quotidien et la
 * lecture d'un resultat statistique — deux regimes cognitifs incompatibles sur le meme ecran. La
 * carte « ce soir » est partie avec, vers `ui/home/HomeScreen.kt` : cet ecran-ci ne porte plus
 * que la lecture.
 *
 * ### Ce qu'il affiche, dans cet ordre, et pourquoi cet ordre
 *
 * 1. La bande d'etat du reveil — ou en est la nuit d'hier.
 * 2. Le **rythme fondamental en secondes**, avec son intervalle et son `n` sur la ligne suivante.
 *    C'est la grandeur suivie depuis `SPEC-v2.md` §5 : douze fois plus stable d'une nuit a
 *    l'autre que le compte horaire, et sans denominateur.
 * 3. Le **compte horaire au second rang**, avec sa phrase de position et son seuil de 15/h.
 *    Il reste parce que c'est la langue des somnologues, mais il ne pilote plus le suivi.
 * 4. Le graphe.
 * 5. Les regles actives, le compteur de nuits, le questionnaire, le rapport.
 *
 * ### Ce qu'il n'affiche jamais
 *
 * Sous trois nuits eligibles : rien. Pas de mediane, pas de categorie, pas de graphe — **pas
 * meme un graphe vide avec ses axes**, parce qu'un axe vide invite l'oeil a imaginer la courbe
 * qui manque. Le refus est une branche a part entiere, pas un etat degrade de l'ecran complet.
 */
@Composable
fun TrendScreen(
    etat: TendanceUiState,
    onNuit: (String) -> Unit,
    onComparer: () -> Unit,
    onQuestionnaire: () -> Unit,
    onExport: () -> Unit,
    onActionReveil: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    var valeursOuvertes by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        when (etat) {
            TendanceUiState.Chargement -> Unit

            is TendanceUiState.Refus -> {
                StatusStrip(etat.reveil, onActionReveil)
                RefusCard(etat, onNuit)
                // L'export est visible mais desactive, avec son motif ecrit sur le bouton :
                // jamais un bouton actif qui echoue.
                BoutonMotive(
                    libelle = Textes.Tendance.RAPPORT,
                    motifIndisponible = Textes.Export.indisponible(Aggregat.MIN_NUITS_AGREGAT),
                    onClick = onExport,
                )
            }

            is TendanceUiState.Pret -> {
                StatusStrip(etat.reveil, onActionReveil)

                etat.profilPersonnalise?.let {
                    // Bandeau permanent : il apparait ici ET dans l'export, des qu'un profil non
                    // par defaut est actif. Sans lui, un chiffre obtenu avec un seuil modifie
                    // ressemble a s'y meprendre a un chiffre de reference.
                    PendulumCard {
                        Paragraphe(Textes.Tendance.PARAMS_PERSONNALISES.format(it), couleur = c.attention)
                    }
                }
                if (etat.hashsMelanges) {
                    PendulumCard { Paragraphe(Textes.Tendance.HASHS_MELANGES, couleur = c.attention) }
                }

                PendulumCard {
                    etat.bandeauProvisoire?.let {
                        Paragraphe(it, couleur = c.attention)
                        Spacer(Modifier.height(Spacing.sm.dp))
                    }

                    // Niveau 1 et 2 de la hierarchie d'affichage : la grandeur suivie, son
                    // intervalle, son n. Seul site d'usage de metricXL dans l'application.
                    MetricHeadline(
                        resultat = etat.rythme,
                        libelle = Textes.Tendance.RYTHME_LABEL,
                        qualificatif = etat.periodiciteQualifiee,
                    )

                    Spacer(Modifier.height(Spacing.sm.dp))
                    Paragraphe(Textes.Tendance.RYTHME_SANS_SEUIL)

                    Spacer(Modifier.height(Spacing.m.dp))

                    // Second rang : le compte horaire. Meme regle P2 — valeur, intervalle et n
                    // dans la meme ligne — mais a la taille du corps de texte.
                    Text(
                        Textes.Tendance.compteSecondRang(
                            Math.round(etat.compte.mediane).toInt(),
                            Math.round(etat.compte.ciBas).toInt(),
                            Math.round(etat.compte.ciHaut).toInt(),
                            etat.compte.nuits,
                        ),
                        style = PendulumType.bodyNum,
                        color = c.textSecondary,
                    )
                    Text(Textes.Tendance.COMPTE_LABEL, style = PendulumType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraphe(Textes.Tendance.COMPTE_NOTE)

                    Spacer(Modifier.height(Spacing.sm.dp))
                    // La phrase de position, cadre neutre dans les cinq cas.
                    PositionBox(etat.position.texte())

                    Spacer(Modifier.height(Spacing.s.dp))
                    Text(
                        Textes.Tendance.dispersion(
                            formaterValeur(etat.rythme.dispersion, etat.rythme.grandeur),
                            etat.rythme.grandeur.unite,
                        ),
                        style = PendulumType.caption,
                        color = c.textTertiary,
                    )
                }

                PendulumCard {
                    GrapheTendance(
                        spec = etat.graphe,
                        onNuit = onNuit,
                        onValeurs = { valeursOuvertes = true },
                    )
                }

                PendulumCard {
                    LigneAction(Textes.Tendance.COMPARER, Textes.Tendance.COMPARER_SOUS_TITRE, onComparer)
                }

                PendulumCard {
                    InlineValue(Textes.Tendance.REGLE_COMPTAGE, etat.regle)
                    InlineValue(Textes.Tendance.MASQUE_SOMMEIL, etat.masque)
                    InlineValue(Textes.Tendance.MOUVEMENTS_EVEIL, "${Math.round(etat.plmw)}/h")
                    InlineValue(
                        Textes.Tendance.PERIODICITE,
                        // Jamais l'indice nu : un qualificatif, ou rien.
                        etat.periodiciteQualifiee ?: "—",
                        note = Textes.Tendance.PERIODICITE_NOTE,
                    )
                    InlineValue(
                        Textes.Tendance.TAUX_MANQUES,
                        "${Math.round(etat.tauxManques * 100)}%",
                        note = "Estimated by the mixture model on the harmonics. " +
                            "A rate that jumps from one night to the next signals nights that are not comparable.",
                    )
                }

                PendulumCard {
                    LigneAction(
                        Textes.Reveil.compteur(etat.nuitsEnregistrees, etat.nuitsEligibles, etat.nuitsEcartees),
                        null,
                    ) { }
                    LigneAction(Textes.Tendance.QUESTIONNAIRE, etat.questionnaireEtat, onQuestionnaire)
                }

                BoutonMotive(
                    libelle = Textes.Tendance.RAPPORT,
                    motifIndisponible = if (etat.exportPossible) null else Textes.Export.indisponible(Aggregat.MIN_NUITS_AGREGAT),
                    onClick = onExport,
                )

                Spacer(Modifier.height(Spacing.l.dp))
            }
        }
    }

    if (valeursOuvertes && etat is TendanceUiState.Pret) {
        DataTableSheet(
            colonnes = listOf("Date", "Rhythm", "State"),
            lignes = etat.graphe.points.map {
                listOf(
                    formaterJourCourt(it.dateMs),
                    "${Math.round(it.valeur)} s",
                    it.etat.name.lowercase(),
                )
            },
            onFermer = { valeursOuvertes = false },
        )
    }
}

/**
 * Le refus d'agreger.
 *
 * La raison est donnee **en chiffres** et non en consigne. L'utilisateur vise est technicien : le
 * chiffre « une nuit sur trois » le convainc, « veuillez patienter » l'agace et le pousse a
 * chercher un contournement.
 *
 * Le lien vers le detail d'une nuit reste actif : c'est la que vit le chiffre par nuit, et le
 * technicien doit pouvoir verifier que sa mesure a fonctionne.
 */
@Composable
private fun RefusCard(etat: TendanceUiState.Refus, onNuit: (String) -> Unit) {
    val c = LocalPendulumColors.current
    BlockingState(
        titre = Textes.Tendance.REFUS_TITRE,
        corps = Textes.Tendance.REFUS_CORPS,
        action = Textes.Tendance.REFUS_ACTION,
        entete = {
            Column(
                Modifier.fillMaxWidth().padding(bottom = Spacing.m.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    Textes.Tendance.compteurNuits(etat.nuitsEligibles, etat.nuitsRequises),
                    style = PendulumType.titleL,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                CollectionProgress(etat.nuitsEligibles, etat.nuitsRequises)
            }
        },
        pied = {
            Text(Textes.Tendance.REFUS_LISTE, style = PendulumType.label, color = c.textTertiary)
            etat.nuitsEnregistrees.forEach { n -> LigneNuitCompacte(n, onNuit) }
        },
    )
}

@Composable
private fun LigneNuitCompacte(n: NuitUi, onNuit: (String) -> Unit) {
    val c = LocalPendulumColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onNuit(n.sessionHex) }
            .padding(vertical = Spacing.s.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(n.dateLisible, style = PendulumType.body, color = c.textPrimary)
        Text(n.sommeilLisible, style = PendulumType.bodyNum, color = c.textSecondary)
    }
}

@Composable
private fun LigneAction(titre: String, sousTitre: String?, onClick: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s.dp),
    ) {
        Text(titre, style = PendulumType.body, color = c.textPrimary)
        sousTitre?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
    }
}

private fun formaterJourCourt(ms: Long): String {
    val jours = ms / 86_400_000L
    val z = jours + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val mois = if (mp < 10) mp + 3 else mp - 9
    return "%02d/%02d".format(d, mois)
}

// -----------------------------------------------------------------------------------------
// Apercus
// -----------------------------------------------------------------------------------------

