package com.pendulum.phone.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.Progression
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Le premier lancement : cinq etapes, non sautables, **dans un pager non swipable**.
 *
 * La progression se fait par bouton uniquement. Ce n'est pas une contrainte gratuite : un pager
 * swipable se survole d'un geste, et l'ecran qu'on survolerait en premier est precisement
 * l'avertissement. Il n'y a pas de bouton « Passer ».
 */
@Composable
fun OnboardingPager(
    onTermine: (repereSerrage: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val etat = rememberPagerState(pageCount = { 5 })
    val portee = rememberCoroutineScope()
    var repere by remember { mutableStateOf("") }

    fun suivant() {
        if (etat.currentPage < 4) {
            portee.launch { etat.animateScrollToPage(etat.currentPage + 1) }
        } else {
            onTermine(repere)
        }
    }

    Column(modifier.fillMaxSize()) {
        Progression((etat.currentPage + 1) / 5f)
        HorizontalPager(
            state = etat,
            // userScrollEnabled = false : la seule facon d'avancer est le bouton.
            userScrollEnabled = false,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> DisclaimerPage(onContinuer = ::suivant)
                1 -> RequirementsPage(onContinuer = ::suivant)
                2 -> PairingPage(onContinuer = ::suivant)
                3 -> SleepSourcePage(onContinuer = ::suivant)
                else -> NotificationsPage(
                    repere = repere,
                    onRepere = { repere = it },
                    onTerminer = ::suivant,
                )
            }
        }
    }
}

/**
 * L'avertissement, en defilement bloquant.
 *
 * Le bouton reste desactive tant que le texte n'a pas ete deroule jusqu'en bas, et il porte
 * pendant ce temps le libelle « Faites defiler jusqu'en bas », pour que l'utilisateur comprenne
 * ce qu'on attend de lui plutot que de conclure a un bug.
 *
 * Le contenu dit sans detour : ce n'est pas une application de sante officielle, ce n'est pas un
 * dispositif medical, elle ne diagnostique rien, et aucune decision de traitement ne doit s'y
 * appuyer. Puis les quatre limites permanentes, en clair. Ce texte est le meme partout — Reglages
 * › A propos le reaffiche mot pour mot, et l'export le reprend litteralement.
 */
@Composable
fun DisclaimerPage(onContinuer: () -> Unit) {
    val c = LocalPendulumColors.current
    val scroll = rememberScrollState()
    // « Au bas du scroll » avec une tolerance de quelques pixels : sur certaines densites le
    // maximum n'est jamais atteint exactement, et un bouton qui ne s'active jamais est pire
    // qu'un avertissement survole.
    val lu by remember {
        derivedStateOf { scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 8 }
    }

    Column(Modifier.fillMaxSize().padding(Spacing.screen.dp)) {
        Text(Textes.Avertissement.TITRE, style = PendulumType.titleL, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            Paragraphe(Textes.Avertissement.CORPS, couleur = c.textPrimary)
            Spacer(Modifier.height(Spacing.l.dp))
        }
        Spacer(Modifier.height(Spacing.sm.dp))
        Button(
            onClick = onContinuer,
            enabled = lu,
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (lu) Textes.Avertissement.BOUTON else Textes.Avertissement.BOUTON_BLOQUE)
        }
        Spacer(Modifier.height(Spacing.s.dp))
        Text(Textes.Avertissement.RAPPEL, style = PendulumType.caption, color = c.textTertiary)
    }
}

@Composable
fun RequirementsPage(onContinuer: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(Textes.Accueil.Besoins.TITRE, style = PendulumType.titleL, color = c.textPrimary)
        Exigence(Textes.Accueil.Besoins.MONTRE_TITRE, Textes.Accueil.Besoins.MONTRE_CORPS)
        Exigence(Textes.Accueil.Besoins.SOMMEIL_TITRE, Textes.Accueil.Besoins.SOMMEIL_CORPS)
        Exigence(Textes.Accueil.Besoins.NUITS_TITRE, Textes.Accueil.Besoins.NUITS_CORPS)
        Button(onClick = onContinuer, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(Textes.Accueil.Besoins.BOUTON)
        }
    }
}

@Composable
private fun Exigence(titre: String, corps: String) {
    val c = LocalPendulumColors.current
    PendulumCard {
        Text(titre, style = PendulumType.titleM, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.xs.dp))
        Paragraphe(corps)
    }
}

/**
 * L'appairage. La ligne de verification du capteur est lue a l'execution : c'est un **controle**,
 * pas de la decoration. Si la montre annonce autre chose que 50 Hz ou un FIFO plus court que
 * prevu, tout le budget d'erreur de la campagne change, et il vaut mieux le savoir maintenant.
 */
@Composable
fun PairingPage(
    onContinuer: () -> Unit,
    trouvee: Boolean = true,
    nom: String = "Pixel Watch 3",
    version: String = "0.1.0",
    batterie: Int = 98,
    espace: String = "1.2 GB",
) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(Textes.Accueil.Appairage.TITRE, style = PendulumType.titleL, color = c.textPrimary)
        if (!trouvee) {
            CircularProgressIndicator()
            Text(Textes.Accueil.Appairage.RECHERCHE, style = PendulumType.body, color = c.textPrimary)
            Paragraphe(Textes.Accueil.Appairage.RECHERCHE_SOUS_TITRE)
        } else {
            PendulumCard {
                Text(nom, style = PendulumType.titleM, color = c.textPrimary)
                Text("version $version · $batterie% · $espace free", style = PendulumType.bodyNum, color = c.textSecondary)
                Spacer(Modifier.height(Spacing.s.dp))
                Text(
                    Textes.Accueil.Appairage.verification(50, 1024, true),
                    style = PendulumType.mono,
                    color = c.textSecondary,
                )
            }
        }
        Button(
            onClick = onContinuer,
            enabled = trouvee,
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(Textes.Accueil.Appairage.BOUTON) }
        TextButton(onClick = onContinuer) { Text(Textes.Accueil.Appairage.SANS_MONTRE) }
    }
}

@Composable
fun SleepSourcePage(onContinuer: () -> Unit, onPermissionsSante: (Boolean) -> Unit = {}) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(Textes.Accueil.SourceSommeil.TITRE, style = PendulumType.titleL, color = c.textPrimary)

        // Sans ce lanceur, le bouton ne fait rien, les permissions Health Connect ne sont jamais
        // accordees, et **chaque nuit est scoree par le seul masque accelerometrique** — c'est-a-dire
        // exactement la circularite numerateur/denominateur que tout le projet existe pour eviter.
        // Le defaut est silencieux : l'application fonctionne, affiche des chiffres, et ils sont faux
        // d'une facon systematique. Les permissions optionnelles sont demandees dans le meme geste :
        // sans `READ_HEALTH_DATA_HISTORY`, un rescore au-dela de 30 jours perd son denominateur.
        val lanceurSante = rememberLauncherForActivityResult(
            contract = SleepReader.permissionRequestContract(),
        ) { accordees ->
            onPermissionsSante(accordees.containsAll(SleepReader.REQUIRED_PERMISSIONS))
        }

        Button(
            onClick = {
                lanceurSante.launch(SleepReader.REQUIRED_PERMISSIONS + SleepReader.OPTIONAL_PERMISSIONS)
            },
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(Textes.Accueil.SourceSommeil.AUTORISER)
        }
        PendulumCard {
            SectionHeader(Textes.Accueil.SourceSommeil.SOURCES_DETECTEES)
            Text("Samsung Health", style = PendulumType.body, color = c.textPrimary)
            Text(
                "${Textes.Accueil.SourceSommeil.couverture(6, 7, true)}   ● ${Textes.Accueil.SourceSommeil.PREFEREE}",
                style = PendulumType.caption,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            Text("Sleep as Android", style = PendulumType.body, color = c.textPrimary)
            Text(Textes.Accueil.SourceSommeil.couverture(2, 7, true), style = PendulumType.caption, color = c.textTertiary)
        }
        TextButton(onClick = onContinuer) { Text(Textes.Accueil.SourceSommeil.SANS_HYPNOGRAMME) }
        Button(onClick = onContinuer, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(Textes.Accueil.Besoins.BOUTON)
        }
    }
}

@Composable
fun NotificationsPage(repere: String, onRepere: (String) -> Unit, onTerminer: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(Textes.Accueil.Notifications.TITRE, style = PendulumType.titleL, color = c.textPrimary)

        // `POST_NOTIFICATIONS` est une permission d'execution depuis Android 13 : la declarer au
        // manifeste ne suffit pas. Sans elle, le rappel du soir n'apparait pas, et l'oubli de
        // demarrer l'enregistrement n'est pas un alea — on l'oublie les soirs de fatigue ou de
        // deplacement, c'est-a-dire des soirs correles au resultat qu'on cherche a mesurer.
        val lanceurNotifs = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { /* refus accepte : le rappel est un confort, pas une condition */ }

        Button(
            onClick = { lanceurNotifs.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(Textes.Accueil.Notifications.AUTORISER)
        }
        Paragraphe(Textes.Accueil.Notifications.RAISON)
        PendulumCard {
            SectionHeader(Textes.Accueil.Notifications.CONDITIONS_TITRE)
            Paragraphe(Textes.Accueil.Notifications.CONDITIONS_CORPS)
        }
        OutlinedTextField(
            value = repere,
            onValueChange = onRepere,
            label = { Text(Textes.Accueil.Notifications.CHAMP_REPERE) },
            singleLine = true,
            shape = PendulumShapes.field,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onTerminer, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(Textes.Accueil.Notifications.BOUTON)
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Preview(name = "Onboarding 1/5 — notice", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAvertissement() = PendulumTheme { DisclaimerPage {} }

@Preview(name = "Onboarding 2/5 — needs", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuBesoins() = PendulumTheme { RequirementsPage {} }

@Preview(name = "Onboarding 3/5 — pairing", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAppairage() = PendulumTheme { PairingPage(onContinuer = {}) }

@Preview(name = "Onboarding 4/5 — sleep source", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuSourceSommeil() = PendulumTheme { SleepSourcePage(onContinuer = {}) }

@Preview(name = "Onboarding 5/5 — notifications and conditions", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuNotifications() = PendulumTheme { NotificationsPage("4th hole", {}, {}) }
