package com.pendulum.phone.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.data.EtatAppairage
import com.pendulum.phone.data.EtatMontre
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.health.SourcesSommeil
import com.pendulum.phone.ui.EtatSante
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.Progression
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Ce que l'assistant sait, a un instant donne.
 *
 * @param startPage l'etape de reprise, derivee du compteur persiste. Elle n'est lue qu'a la
 *   premiere composition : voir [OnboardingPager].
 * @param montre etat de l'appairage, **observe** et non lu une fois — l'etape 3 se coche
 *   d'elle-meme quand l'application apparait sur la montre.
 * @param sante `null` tant que Health Connect n'a pas ete interroge. Une liste vide de sources,
 *   elle, est une reponse.
 * @param installation dernier resultat d'ouverture du magasin sur la montre, consomme une fois.
 */
@Immutable
data class AssistantUi(
    val startPage: Int,
    val montre: EtatMontre,
    val sante: EtatSante?,
    val sourcePreferee: String?,
    val installation: Boolean?,
)

/**
 * Ce que l'assistant peut demander. Six rappels, nommes, dans un seul type.
 *
 * Les regrouper n'est pas cosmetique : alignes dans une signature, `onRelireLaSante` et
 * `onInstallerSurLaMontre` ont la meme forme `() -> Unit`, donc les intervertir compile.
 */
@Immutable
data class AssistantActions(
    val onEtapeFranchie: (page: Int) -> Unit,
    val onOuvrirCompagnon: () -> Boolean,
    val onInstallerSurLaMontre: () -> Unit,
    val onRelireLaSante: () -> Unit,
    val onChoisirSource: (String) -> Unit,
    val onRepere: (String) -> Unit,
)

/**
 * Le premier lancement : six etapes, non sautables, **dans un pager non swipable**.
 *
 * La progression se fait par bouton uniquement. Ce n'est pas une contrainte gratuite : un pager
 * swipable se survole d'un geste, et l'ecran qu'on survolerait en premier est precisement
 * l'avertissement. Il n'y a pas de bouton « Passer ».
 *
 * ### Reprenable, parce que l'abandon est le mode de defaillance principal
 *
 * [AssistantUi.startPage] vient du compteur d'etapes franchies persiste dans
 * `PendulumPreferences`, et [AssistantActions.onEtapeFranchie] l'incremente **a la sortie** de
 * chaque page. Quitter a l'etape 3 y ramene :
 * refaire trois ecrans d'avertissement pour arriver a celui qu'on cherchait est la facon la plus
 * sure de faire desinstaller une application. La regle elle-meme vit dans [RepriseAssistant],
 * hors du composable, parce que c'est la partie qui merite un test.
 *
 * ### Deux paquets plutot que douze arguments
 *
 * L'etat d'un cote, les actions de l'autre. La signature en portait onze plus le `Modifier`, et
 * la regle d'architecture du projet — « un composable d'ecran ne prend que son etat et des
 * lambdas » — n'y etait plus lisible : cinq valeurs et six rappels alignes se lisent comme une
 * liste de courses, et une erreur d'appariement entre deux `() -> Unit` voisins ne se compile pas
 * moins bien. [AssistantUi] et [AssistantActions] portent la separation dans le type.
 */
@Composable
fun OnboardingPager(
    etatUi: AssistantUi,
    actions: AssistantActions,
    modifier: Modifier = Modifier,
) {
    val (startPage, montre, sante, sourcePreferee, installation) = etatUi
    val c = LocalPendulumColors.current
    // `initialPage` n'est lu qu'a la premiere composition, ce qui est exactement ce qu'on veut :
    // les emissions suivantes du compteur — celles que nos propres sorties de page provoquent —
    // ne doivent pas ramener le pager en arriere.
    val etat = rememberPagerState(initialPage = startPage, pageCount = { RepriseAssistant.PAGES })
    val portee = rememberCoroutineScope()
    var repere by rememberSaveable { mutableStateOf("") }

    fun suivant() {
        val page = etat.currentPage
        actions.onEtapeFranchie(page)
        if (page < RepriseAssistant.PAGES - 1) {
            portee.launch { etat.animateScrollToPage(page + 1) }
        }
    }

    // `safeDrawing` : l'assistant est le seul ecran du produit sans `Scaffold`, donc le seul dont
    // personne d'autre ne reserve la place des barres systeme. La fenetre etant sans barre
    // d'action et l'application bord a bord des Android 15, sans cette ligne la barre de
    // progression passe sous l'horloge et le rappel du bas sous la barre de navigation.
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Progression((etat.currentPage + 1) / RepriseAssistant.PAGES.toFloat())
        Text(
            stringResource(R.string.onboarding_step, etat.currentPage + 1),
            style = PendulumType.label,
            color = c.textTertiary,
            modifier = Modifier.padding(horizontal = Spacing.screen.dp, vertical = Spacing.s.dp),
        )
        HorizontalPager(
            state = etat,
            // userScrollEnabled = false : la seule facon d'avancer est le bouton.
            userScrollEnabled = false,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> DisclaimerPage(onContinuer = ::suivant)
                1 -> RequirementsPage(onContinuer = ::suivant)
                2 -> PairingPage(
                    montre = montre,
                    installation = installation,
                    onOuvrirCompagnon = actions.onOuvrirCompagnon,
                    onInstaller = actions.onInstallerSurLaMontre,
                    onContinuer = ::suivant,
                )
                3 -> WearingPage(
                    repere = repere,
                    onRepere = { repere = it },
                    onContinuer = {
                        // Le repere est persiste **a la sortie de cette etape** et non a la fin de
                        // l'assistant : il est saisi ici, et quelqu'un qui abandonne aux
                        // notifications garderait sinon un champ vide alors qu'il l'a rempli.
                        actions.onRepere(repere)
                        suivant()
                    },
                )
                4 -> SleepSourcePage(
                    sante = sante,
                    sourcePreferee = sourcePreferee,
                    onRelire = actions.onRelireLaSante,
                    onChoisirSource = actions.onChoisirSource,
                    onContinuer = ::suivant,
                )
                else -> NotificationsPage(onTerminer = ::suivant)
            }
        }
    }
}

/**
 * L'avertissement : defilement bloquant **et** quatre confirmations actives.
 *
 * Le bouton reste desactive tant que le texte n'a pas ete deroule jusqu'en bas, et il porte
 * pendant ce temps le libelle « Faites defiler jusqu'en bas », pour que l'utilisateur comprenne
 * ce qu'on attend de lui plutot que de conclure a un bug.
 *
 * ### Pourquoi le defilement ne suffit pas
 *
 * L'etude de reference sur les politiques de confidentialite (Obar & Oeldorf-Hirsch, 543
 * participants) mesure 73 secondes de lecture mediane la ou 29 a 32 minutes seraient
 * necessaires : le geste reellement observe est le defilement au pouce. Un bouton qui s'active
 * au bas du scroll atteste donc d'un mouvement de doigt. Le pattern qui a des preuves de
 * comprehension est le *teach-back* de l'eConsent en recherche clinique (Sage Bionetworks, base
 * du module de consentement de ResearchKit), ou un essai randomise de 2026 donne une
 * comprehension non inferieure au consentement en face a face. Quatre tapes deliberees, une par
 * limite, valent mieux qu'un defilement — et constituent une trace de consentement d'une autre
 * nature.
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

    val confirmations = listOf(
        stringResource(R.string.notice_confirm_diagnosis),
        stringResource(R.string.notice_confirm_breathing),
        stringResource(R.string.notice_confirm_one_leg),
        stringResource(R.string.notice_confirm_actigraphy),
    )
    // `rememberSaveable` : une rotation d'ecran ne doit pas effacer quatre acquittements, sans
    // quoi le garde-fou devient une punition.
    val cochees = rememberSaveable { mutableStateOf(setOf<Int>()) }
    val toutesCochees = cochees.value.size == confirmations.size

    Column(Modifier.fillMaxSize().padding(Spacing.screen.dp)) {
        Text(stringResource(R.string.notice_title), style = PendulumType.titleL, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            Paragraphe(stringResource(R.string.notice_body), couleur = c.textPrimary)
            Spacer(Modifier.height(Spacing.l.dp))
            PendulumCard {
                SectionHeader(stringResource(R.string.notice_confirm_title))
                confirmations.forEachIndexed { i, phrase ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = i in cochees.value,
                            onCheckedChange = { coche ->
                                cochees.value =
                                    if (coche) cochees.value + i else cochees.value - i
                            },
                        )
                        Spacer(Modifier.width(Spacing.s.dp))
                        Text(
                            phrase,
                            style = PendulumType.body,
                            color = c.textPrimary,
                            modifier = Modifier.padding(top = Spacing.sm.dp),
                        )
                    }
                }
                Text(
                    stringResource(
                        R.string.notice_confirm_count,
                        cochees.value.size,
                        confirmations.size,
                    ),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
            }
            Spacer(Modifier.height(Spacing.l.dp))
        }
        Spacer(Modifier.height(Spacing.sm.dp))
        // `BoutonMotive` et non un `Button` grise : le libelle de l'etat desactive **porte
        // l'instruction** — « faites defiler jusqu'en bas » — et les couleurs desactivees par
        // defaut de Material le rendaient a 3,02:1, mesure sur l'appareil. Un utilisateur qui ne
        // lit pas cette phrase conclut au bug, ce que la KDoc ci-dessus donne precisement comme
        // motif de l'ecrire. Le composant existait, avec les bonnes teintes (4,93:1).
        BoutonMotive(
            libelle = stringResource(R.string.notice_button),
            motifIndisponible = when {
                !lu -> stringResource(R.string.notice_button_scroll_first)
                !toutesCochees -> stringResource(R.string.notice_button_confirm_first)
                else -> null
            },
            onClick = onContinuer,
        )
        Spacer(Modifier.height(Spacing.s.dp))
        Text(stringResource(R.string.notice_reminder), style = PendulumType.caption, color = c.textTertiary)
    }
}

@Composable
fun RequirementsPage(onContinuer: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.onboarding_needs_title), style = PendulumType.titleL, color = c.textPrimary)
        Exigence(stringResource(R.string.onboarding_needs_watch_title), stringResource(R.string.onboarding_needs_watch_body))
        Exigence(stringResource(R.string.onboarding_needs_sleep_title), stringResource(R.string.onboarding_needs_sleep_body))
        Exigence(stringResource(R.string.onboarding_needs_nights_title), stringResource(R.string.onboarding_needs_nights_body))
        Button(onClick = onContinuer, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_needs_button))
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
 * L'appairage, et les **trois** etats qu'il ne faut pas confondre.
 *
 * Aucun noeud connecte veut dire qu'aucune montre n'est appairee a ce telephone : la reparation
 * est l'application compagnon du constructeur, et surtout **pas** le Play Store — installer
 * Pendulum sur une montre qui n'est appairee a rien produit une installation que personne ne
 * verra. Un noeud connecte sans la capacite veut dire l'inverse : la montre est la, mais notre
 * application n'y repond pas, et c'est la que la fiche de magasin a un sens. La capacite trouvee
 * est le seul etat qui valide l'etape.
 *
 * ### Rien ne bloque pendant l'installation
 *
 * L'etat vient d'un `CapabilityClient.addListener` : la carte bascule d'elle-meme quand la
 * capacite apparait, meme si l'utilisateur est reste sur cet ecran. Il n'y a donc pas de bouton
 * « j'ai fini » — c'est-a-dire pas de bouton qu'on appuie trop tot, et pas d'ecran qui dit non a
 * quelqu'un qui a fait ce qu'on lui demandait.
 *
 * ### La ligne de verification du capteur
 *
 * Elle affichait `50 Hz · FIFO 1024 · wake-up : oui` en dur. C'est un **controle**, et un
 * controle qui affiche toujours la meme chose ne controle rien : il rassure. La montre ne publie
 * aucun `DataItem` decrivant son capteur, donc la ligne montre des tirets et dit pourquoi.
 */
@Composable
fun PairingPage(
    montre: EtatMontre,
    installation: Boolean?,
    onOuvrirCompagnon: () -> Boolean,
    onInstaller: () -> Unit,
    onContinuer: () -> Unit,
) {
    val c = LocalPendulumColors.current
    var compagnonIntrouvable by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.onboarding_pairing_title), style = PendulumType.titleL, color = c.textPrimary)

        when (montre.etat) {
            EtatAppairage.AUCUNE_MONTRE -> {
                PendulumCard {
                    Text(
                        stringResource(R.string.onboarding_pairing_no_watch_title),
                        style = PendulumType.titleM,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraphe(stringResource(R.string.onboarding_pairing_no_watch_body))
                    if (compagnonIntrouvable) {
                        Spacer(Modifier.height(Spacing.s.dp))
                        Paragraphe(
                            stringResource(R.string.onboarding_pairing_companion_not_found),
                            couleur = c.attention,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { compagnonIntrouvable = !onOuvrirCompagnon() },
                    shape = PendulumShapes.button,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_pairing_open_companion)) }
            }

            EtatAppairage.APP_ABSENTE_OU_HORS_PORTEE -> {
                PendulumCard {
                    Text(
                        stringResource(R.string.onboarding_pairing_app_missing_title),
                        style = PendulumType.titleM,
                        color = c.textPrimary,
                    )
                    montre.nom?.let {
                        Text(it, style = PendulumType.bodyNum, color = c.textSecondary)
                    }
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraphe(stringResource(R.string.onboarding_pairing_app_missing_body))
                    installation?.let {
                        Spacer(Modifier.height(Spacing.s.dp))
                        Paragraphe(
                            if (it) stringResource(R.string.onboarding_pairing_install_opened)
                            else stringResource(R.string.onboarding_pairing_install_failed),
                            couleur = if (it) c.textSecondary else c.attention,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onInstaller,
                    shape = PendulumShapes.button,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_pairing_install_on_watch)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp))
                    Spacer(Modifier.width(Spacing.s.dp))
                    Paragraphe(stringResource(R.string.onboarding_pairing_auto_wait))
                }
            }

            EtatAppairage.PRETE -> PendulumCard {
                Text(
                    montre.nom ?: stringResource(R.string.onboarding_pairing_found_title),
                    style = PendulumType.titleM,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Text(
                    stringResource(R.string.onboarding_pairing_sensor_check_missing),
                    style = PendulumType.mono,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(Spacing.xs.dp))
                Text(
                    stringResource(R.string.onboarding_pairing_sensor_check_note),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
            }
        }

        Button(
            onClick = onContinuer,
            enabled = montre.etat == EtatAppairage.PRETE,
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onboarding_pairing_button)) }

        // Le seul echappatoire de cet ecran, et il reste : une montre en cours de livraison ne
        // doit pas empecher de lire le reste de l'assistant.
        TextButton(onClick = onContinuer) { Text(stringResource(R.string.onboarding_pairing_skip)) }
    }
}

/**
 * La source de sommeil.
 *
 * ### L'explication est une explication de role, pas de plomberie
 *
 * La question la plus deroutante du produit est « pourquoi mon application de sommeil ne
 * suffit-elle pas ? », et la reponse qui passe tient en une phrase : la montre a la cheville
 * mesure les jambes, elle ne sait pas quand on dort ; un deuxieme appareil sert de juge
 * independant du sommeil. La justification complete — la circularite entre ce qu'on compte et ce
 * par quoi on divise, `01-overview.md` §1 — est vraie mais ne se lit pas debout : elle tient
 * derriere un « en savoir plus ».
 *
 * ### Trois issues, et aucune n'est un ecran mort
 *
 * Health Connect absent est le cas d'Android 13 et anterieur, ou c'est un APK du Play Store et
 * non une brique du systeme : on propose de l'installer. Zero source detectee n'est pas un echec
 * silencieux : c'est un ecran d'aide qui liste les applications connues pour publier des
 * sessions. Et la liste, quand elle existe, est la **vraie** liste — elle affichait deux noms
 * ecrits en dur, sur des telephones qui n'avaient ni l'un ni l'autre.
 */
@Composable
fun SleepSourcePage(
    sante: EtatSante?,
    sourcePreferee: String?,
    onRelire: () -> Unit,
    onChoisirSource: (String) -> Unit,
    onContinuer: () -> Unit,
) {
    val c = LocalPendulumColors.current
    var enSavoirPlus by rememberSaveable { mutableStateOf(false) }

    // Sans ce lanceur, le bouton ne fait rien, les permissions Health Connect ne sont jamais
    // accordees, et **chaque nuit est scoree par le seul masque accelerometrique** — c'est-a-dire
    // exactement la circularite numerateur/denominateur que tout le projet existe pour eviter.
    // Le defaut est silencieux : l'application fonctionne, affiche des chiffres, et ils sont faux
    // d'une facon systematique. Les permissions optionnelles sont demandees dans le meme geste :
    // sans `READ_HEALTH_DATA_HISTORY`, un rescore au-dela de 30 jours perd son denominateur.
    val lanceurSante = rememberLauncherForActivityResult(
        contract = SleepReader.permissionRequestContract(),
    ) {
        // On ne se fie pas au jeu rendu par le contrat : `availability()` verifie en plus que la
        // plateforme sait honorer la lecture de fond, ce qu'une permission accordee ne dit pas.
        onRelire()
    }

    LaunchedEffect(Unit) { onRelire() }

    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.onboarding_sleep_title), style = PendulumType.titleL, color = c.textPrimary)

        PendulumCard {
            SectionHeader(stringResource(R.string.onboarding_sleep_role_title))
            Paragraphe(stringResource(R.string.onboarding_sleep_role_body))
            TextButton(onClick = { enSavoirPlus = !enSavoirPlus }) {
                Text(stringResource(R.string.onboarding_sleep_role_more))
            }
            if (enSavoirPlus) Paragraphe(stringResource(R.string.onboarding_sleep_role_more_body))
        }

        when (sante?.disponibilite) {
            null -> Unit // Rien tant que Health Connect n'a pas repondu.

            SleepReader.Availability.SDK_UNAVAILABLE -> ReparationSante(
                titre = stringResource(R.string.onboarding_sleep_sdk_missing_title),
                corps = stringResource(R.string.onboarding_sleep_sdk_missing_body),
                bouton = stringResource(R.string.onboarding_sleep_install_hc),
            )

            SleepReader.Availability.UPDATE_REQUIRED -> ReparationSante(
                titre = stringResource(R.string.onboarding_sleep_update_title),
                corps = stringResource(R.string.onboarding_sleep_update_body),
                bouton = stringResource(R.string.onboarding_sleep_update_button),
            )

            SleepReader.Availability.PERMISSIONS_MISSING -> Button(
                onClick = {
                    lanceurSante.launch(
                        SleepReader.REQUIRED_PERMISSIONS + SleepReader.OPTIONAL_PERMISSIONS,
                    )
                },
                shape = PendulumShapes.button,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_sleep_allow)) }

            SleepReader.Availability.BACKGROUND_READ_UNAVAILABLE -> PendulumCard {
                Text(
                    stringResource(R.string.onboarding_sleep_background_title),
                    style = PendulumType.titleM,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraphe(stringResource(R.string.onboarding_sleep_background_body))
            }

            SleepReader.Availability.READY -> ListeDesSources(
                sources = sante.sources.orEmpty(),
                sourcePreferee = sourcePreferee,
                onChoisirSource = onChoisirSource,
            )
        }

        BoutonMotive(
            libelle = stringResource(R.string.onboarding_needs_button),
            motifIndisponible = stringResource(R.string.onboarding_sleep_button_blocked)
                .takeIf { sante?.disponibilite != SleepReader.Availability.READY },
            onClick = onContinuer,
        )
        TextButton(onClick = onContinuer) {
            Text(stringResource(R.string.onboarding_sleep_skip))
        }
    }
}

/**
 * La liste des sources reellement detectees, ou l'aide quand il n'y en a aucune.
 *
 * Zero source n'est pas un echec silencieux : c'est l'etat le plus frequent d'un telephone neuf,
 * et le laisser vide fait conclure a une panne de Pendulum. L'ecran nomme donc les applications
 * connues pour publier une session, et dit ce que Pendulum lit d'elles — la session, jamais le
 * score du constructeur.
 */
@Composable
private fun ListeDesSources(
    sources: List<SourcesSommeil.Observee>,
    sourcePreferee: String?,
    onChoisirSource: (String) -> Unit,
) {
    val c = LocalPendulumColors.current

    if (sources.isEmpty()) {
        PendulumCard {
            Text(
                stringResource(R.string.onboarding_sleep_none_title),
                style = PendulumType.titleM,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(stringResource(R.string.onboarding_sleep_none_body))
            Spacer(Modifier.height(Spacing.m.dp))
            SectionHeader(stringResource(R.string.onboarding_sleep_known_apps_title))
            Paragraphe(stringResource(R.string.onboarding_sleep_known_apps_body))
        }
        return
    }

    PendulumCard {
        SectionHeader(stringResource(R.string.onboarding_sleep_sources_detected))
        sources.forEach { source ->
            val choisie = source.paquet == sourcePreferee
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp)) {
                Text(source.paquet, style = PendulumType.body, color = c.textPrimary)
                Text(
                    stringResource(
                        R.string.onboarding_sleep_coverage,
                        source.nuits,
                        SourcesSommeil.JOURS_OBSERVES,
                        stringResource(
                            if (source.stades) R.string.onboarding_sleep_coverage_staged
                            else R.string.onboarding_sleep_coverage_total,
                        ),
                    ) + if (choisie) {
                        "   ● " + stringResource(R.string.onboarding_sleep_preferred)
                    } else {
                        ""
                    },
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
                if (!source.stades) Paragraphe(stringResource(R.string.onboarding_sleep_no_stages))
                if (!choisie) {
                    TextButton(onClick = { onChoisirSource(source.paquet) }) {
                        Text(stringResource(R.string.onboarding_sleep_choose))
                    }
                }
            }
        }
    }
}

/**
 * Health Connect absent ou trop ancien : une carte et une action, jamais un ecran mort.
 *
 * Le bouton ouvre la fiche du fournisseur dans le magasin. `market://` plutot qu'une URL
 * `https` : l'application ne declare pas la permission Internet, et une URL web serait de toute
 * facon rendue au navigateur, un detour de plus pour arriver au meme endroit.
 */
@Composable
private fun ReparationSante(titre: String, corps: String, bouton: String) {
    val c = LocalPendulumColors.current
    val contexte = androidx.compose.ui.platform.LocalContext.current
    PendulumCard {
        Text(titre, style = PendulumType.titleM, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.s.dp))
        Paragraphe(corps)
        Spacer(Modifier.height(Spacing.sm.dp))
        OutlinedButton(
            onClick = {
                runCatching {
                    contexte.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(LIEN_HEALTH_CONNECT),
                        ),
                    )
                }
            },
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(bouton) }
    }
}

private const val LIEN_HEALTH_CONNECT =
    "market://details?id=com.google.android.apps.healthdata"

/**
 * Ou porter la montre, et ce qui doit rester identique d'une nuit a l'autre.
 *
 * ### Pourquoi cette etape existe maintenant, alors que le texte existait deja
 *
 * Ces consignes vivaient dans l'etape des **notifications**, sous un titre qui annonce un reglage
 * de permission. Ce sont pourtant les quatre conditions dont depend toute la comparabilite des
 * nuits, donc la valeur entiere du produit : quelqu'un qui traversait l'assistant en retenait
 * « autoriser les notifications » et pas « notez votre trou de bracelet ». Deux sujets sans
 * rapport dans un meme ecran, dont le plus important arrivait en second.
 *
 * Elle est placee **juste apres l'appairage** et non a la fin : c'est le moment ou l'utilisateur a
 * la montre en main et s'apprete a la porter pour la premiere fois. Une consigne spatiale lue
 * trois ecrans trop tot est une consigne qu'on relit rarement.
 *
 * Le champ du repere de bracelet a suivi les consignes plutot que de rester avec les
 * notifications : il appartient a ce qui doit rester identique, pas au rappel du soir.
 */
@Composable
fun WearingPage(repere: String, onRepere: (String) -> Unit, onContinuer: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.wearing_title), style = PendulumType.titleL, color = c.textPrimary)
        Paragraphe(stringResource(R.string.wearing_subtitle))

        // Le schema porte l'emplacement, les phrases portent la raison. Aucun texte n'est dessine
        // dedans : un texte dessine ne se traduit pas.
        SchemaDePort()

        PendulumCard {
            Paragraphe(stringResource(R.string.wearing_point_front))
            Paragraphe(stringResource(R.string.wearing_point_not_on_bone))
            Paragraphe(stringResource(R.string.wearing_point_orientation))
        }
        Paragraphe(stringResource(R.string.wearing_keep_identical))

        OutlinedTextField(
            value = repere,
            onValueChange = onRepere,
            label = { Text(stringResource(R.string.onboarding_notif_strap_field)) },
            singleLine = true,
            shape = PendulumShapes.field,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onContinuer, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wearing_button))
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
fun NotificationsPage(onTerminer: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxSize().padding(Spacing.screen.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        Text(stringResource(R.string.onboarding_notif_title), style = PendulumType.titleL, color = c.textPrimary)

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
            Text(stringResource(R.string.onboarding_notif_allow))
        }
        Paragraphe(stringResource(R.string.onboarding_notif_reason))
        Button(onClick = onTerminer, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_notif_button))
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }
}
