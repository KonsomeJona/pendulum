package com.pendulum.phone.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Le premier lancement : cinq etapes, non sautables, **dans un pager non swipable**.
 *
 * La progression se fait par bouton uniquement. Ce n'est pas une contrainte gratuite : un pager
 * swipable se survole d'un geste, et l'ecran qu'on survolerait en premier est precisement
 * l'avertissement. Il n'y a pas de bouton « Passer ».
 *
 * ### Reprenable, parce que l'abandon est le mode de defaillance principal
 *
 * [startPage] vient du compteur d'etapes franchies persiste dans `PendulumPreferences`, et
 * [onEtapeFranchie] l'incremente **a la sortie** de chaque page. Quitter a l'etape 3 y ramene :
 * refaire trois ecrans d'avertissement pour arriver a celui qu'on cherchait est la facon la plus
 * sure de faire desinstaller une application. La regle elle-meme vit dans [RepriseAssistant],
 * hors du composable, parce que c'est la partie qui merite un test.
 *
 * @param montre etat de l'appairage, **observe** et non lu une fois : l'etape 3 se coche
 *   d'elle-meme quand l'application apparait sur la montre.
 * @param sante `null` tant que Health Connect n'a pas ete interroge.
 */
@Composable
fun OnboardingPager(
    startPage: Int,
    montre: EtatMontre,
    sante: EtatSante?,
    sourcePreferee: String?,
    installation: Boolean?,
    onEtapeFranchie: (page: Int) -> Unit,
    onOuvrirCompagnon: () -> Boolean,
    onInstallerSurLaMontre: () -> Unit,
    onRelireLaSante: () -> Unit,
    onChoisirSource: (String) -> Unit,
    onRepere: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    // `initialPage` n'est lu qu'a la premiere composition, ce qui est exactement ce qu'on veut :
    // les emissions suivantes du compteur — celles que nos propres sorties de page provoquent —
    // ne doivent pas ramener le pager en arriere.
    val etat = rememberPagerState(initialPage = startPage, pageCount = { RepriseAssistant.PAGES })
    val portee = rememberCoroutineScope()
    var repere by rememberSaveable { mutableStateOf("") }

    fun suivant() {
        val page = etat.currentPage
        onEtapeFranchie(page)
        if (page < RepriseAssistant.PAGES - 1) {
            portee.launch { etat.animateScrollToPage(page + 1) }
        }
    }

    Column(modifier.fillMaxSize()) {
        Progression((etat.currentPage + 1) / RepriseAssistant.PAGES.toFloat())
        Text(
            Textes.Accueil.ETAPE.format(etat.currentPage + 1),
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
                    onOuvrirCompagnon = onOuvrirCompagnon,
                    onInstaller = onInstallerSurLaMontre,
                    onContinuer = ::suivant,
                )
                3 -> SleepSourcePage(
                    sante = sante,
                    sourcePreferee = sourcePreferee,
                    onRelire = onRelireLaSante,
                    onChoisirSource = onChoisirSource,
                    onContinuer = ::suivant,
                )
                else -> NotificationsPage(
                    repere = repere,
                    onRepere = { repere = it },
                    onTerminer = {
                        // Le repere est persiste **avant** de fermer l'assistant. Il etait saisi
                        // et jete : le champ existait, son texte remontait a un appelant qui
                        // n'existait pas, et le formulaire du soir repartait vide chaque nuit.
                        onRepere(repere)
                        suivant()
                    },
                )
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
        Textes.Avertissement.Confirmations.DIAGNOSTIC,
        Textes.Avertissement.Confirmations.RESPIRATION,
        Textes.Avertissement.Confirmations.UNE_JAMBE,
        Textes.Avertissement.Confirmations.ACTIGRAPHIE,
    )
    // `rememberSaveable` : une rotation d'ecran ne doit pas effacer quatre acquittements, sans
    // quoi le garde-fou devient une punition.
    val cochees = rememberSaveable { mutableStateOf(setOf<Int>()) }
    val toutesCochees = cochees.value.size == confirmations.size

    Column(Modifier.fillMaxSize().padding(Spacing.screen.dp)) {
        Text(Textes.Avertissement.TITRE, style = PendulumType.titleL, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            Paragraphe(Textes.Avertissement.CORPS, couleur = c.textPrimary)
            Spacer(Modifier.height(Spacing.l.dp))
            PendulumCard {
                SectionHeader(Textes.Avertissement.Confirmations.TITRE)
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
                    Textes.Avertissement.Confirmations.compte(
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
        Button(
            onClick = onContinuer,
            enabled = lu && toutesCochees,
            shape = PendulumShapes.button,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    !lu -> Textes.Avertissement.BOUTON_BLOQUE
                    !toutesCochees -> Textes.Avertissement.BOUTON_A_CONFIRMER
                    else -> Textes.Avertissement.BOUTON
                }
            )
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
        Text(Textes.Accueil.Appairage.TITRE, style = PendulumType.titleL, color = c.textPrimary)

        when (montre.etat) {
            EtatAppairage.AUCUNE_MONTRE -> {
                PendulumCard {
                    Text(
                        Textes.Accueil.Appairage.AUCUNE_MONTRE_TITRE,
                        style = PendulumType.titleM,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraphe(Textes.Accueil.Appairage.AUCUNE_MONTRE_CORPS)
                    if (compagnonIntrouvable) {
                        Spacer(Modifier.height(Spacing.s.dp))
                        Paragraphe(
                            Textes.Accueil.Appairage.COMPAGNON_INTROUVABLE,
                            couleur = c.attention,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { compagnonIntrouvable = !onOuvrirCompagnon() },
                    shape = PendulumShapes.button,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(Textes.Accueil.Appairage.OUVRIR_COMPAGNON) }
            }

            EtatAppairage.APP_ABSENTE_OU_HORS_PORTEE -> {
                PendulumCard {
                    Text(
                        Textes.Accueil.Appairage.APP_ABSENTE_TITRE,
                        style = PendulumType.titleM,
                        color = c.textPrimary,
                    )
                    montre.nom?.let {
                        Text(it, style = PendulumType.bodyNum, color = c.textSecondary)
                    }
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraphe(Textes.Accueil.Appairage.APP_ABSENTE_CORPS)
                    installation?.let {
                        Spacer(Modifier.height(Spacing.s.dp))
                        Paragraphe(
                            if (it) Textes.Accueil.Appairage.INSTALLATION_OUVERTE
                            else Textes.Accueil.Appairage.INSTALLATION_ECHOUEE,
                            couleur = if (it) c.textSecondary else c.attention,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onInstaller,
                    shape = PendulumShapes.button,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(Textes.Accueil.Appairage.INSTALLER_SUR_MONTRE) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp))
                    Spacer(Modifier.width(Spacing.s.dp))
                    Paragraphe(Textes.Accueil.Appairage.ATTENTE_AUTOMATIQUE)
                }
            }

            EtatAppairage.PRETE -> PendulumCard {
                Text(
                    montre.nom ?: Textes.Accueil.Appairage.TROUVEE_TITRE,
                    style = PendulumType.titleM,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Text(
                    Textes.Accueil.Appairage.VERIFICATION_ABSENTE,
                    style = PendulumType.mono,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(Spacing.xs.dp))
                Text(
                    Textes.Accueil.Appairage.VERIFICATION_ABSENTE_NOTE,
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
        ) { Text(Textes.Accueil.Appairage.BOUTON) }

        // Le seul echappatoire de cet ecran, et il reste : une montre en cours de livraison ne
        // doit pas empecher de lire le reste de l'assistant.
        TextButton(onClick = onContinuer) { Text(Textes.Accueil.Appairage.SANS_MONTRE) }
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
        Text(Textes.Accueil.SourceSommeil.TITRE, style = PendulumType.titleL, color = c.textPrimary)

        PendulumCard {
            SectionHeader(Textes.Accueil.SourceSommeil.ROLE_TITRE)
            Paragraphe(Textes.Accueil.SourceSommeil.ROLE_CORPS)
            TextButton(onClick = { enSavoirPlus = !enSavoirPlus }) {
                Text(Textes.Accueil.SourceSommeil.ROLE_PLUS)
            }
            if (enSavoirPlus) Paragraphe(Textes.Accueil.SourceSommeil.ROLE_PLUS_CORPS)
        }

        when (sante?.disponibilite) {
            null -> Unit // Rien tant que Health Connect n'a pas repondu.

            SleepReader.Availability.SDK_UNAVAILABLE -> ReparationSante(
                titre = Textes.Accueil.SourceSommeil.SDK_ABSENT_TITRE,
                corps = Textes.Accueil.SourceSommeil.SDK_ABSENT_CORPS,
                bouton = Textes.Accueil.SourceSommeil.INSTALLER_HEALTH_CONNECT,
            )

            SleepReader.Availability.UPDATE_REQUIRED -> ReparationSante(
                titre = Textes.Accueil.SourceSommeil.MISE_A_JOUR_TITRE,
                corps = Textes.Accueil.SourceSommeil.MISE_A_JOUR_CORPS,
                bouton = Textes.Accueil.SourceSommeil.METTRE_A_JOUR,
            )

            SleepReader.Availability.PERMISSIONS_MISSING -> Button(
                onClick = {
                    lanceurSante.launch(
                        SleepReader.REQUIRED_PERMISSIONS + SleepReader.OPTIONAL_PERMISSIONS,
                    )
                },
                shape = PendulumShapes.button,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(Textes.Accueil.SourceSommeil.AUTORISER) }

            SleepReader.Availability.BACKGROUND_READ_UNAVAILABLE -> PendulumCard {
                Text(
                    Textes.Accueil.SourceSommeil.FOND_INDISPONIBLE_TITRE,
                    style = PendulumType.titleM,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraphe(Textes.Accueil.SourceSommeil.FOND_INDISPONIBLE_CORPS)
            }

            SleepReader.Availability.READY -> ListeDesSources(
                sources = sante.sources.orEmpty(),
                sourcePreferee = sourcePreferee,
                onChoisirSource = onChoisirSource,
            )
        }

        BoutonMotive(
            libelle = Textes.Accueil.Besoins.BOUTON,
            motifIndisponible = Textes.Accueil.SourceSommeil.BOUTON_BLOQUE
                .takeIf { sante?.disponibilite != SleepReader.Availability.READY },
            onClick = onContinuer,
        )
        TextButton(onClick = onContinuer) {
            Text(Textes.Accueil.SourceSommeil.SANS_HYPNOGRAMME)
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
                Textes.Accueil.SourceSommeil.AUCUNE_TITRE,
                style = PendulumType.titleM,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(Textes.Accueil.SourceSommeil.AUCUNE_CORPS)
            Spacer(Modifier.height(Spacing.m.dp))
            SectionHeader(Textes.Accueil.SourceSommeil.APPLICATIONS_CONNUES_TITRE)
            Paragraphe(Textes.Accueil.SourceSommeil.APPLICATIONS_CONNUES_CORPS)
        }
        return
    }

    PendulumCard {
        SectionHeader(Textes.Accueil.SourceSommeil.SOURCES_DETECTEES)
        sources.forEach { source ->
            val choisie = source.paquet == sourcePreferee
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp)) {
                Text(source.paquet, style = PendulumType.body, color = c.textPrimary)
                Text(
                    Textes.Accueil.SourceSommeil.couverture(
                        source.nuits,
                        SourcesSommeil.JOURS_OBSERVES,
                        source.stades,
                    ) + if (choisie) "   ● ${Textes.Accueil.SourceSommeil.PREFEREE}" else "",
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
                if (!source.stades) Paragraphe(Textes.Accueil.SourceSommeil.SANS_STADES)
                if (!choisie) {
                    TextButton(onClick = { onChoisirSource(source.paquet) }) {
                        Text(Textes.Accueil.SourceSommeil.CHOISIR)
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
