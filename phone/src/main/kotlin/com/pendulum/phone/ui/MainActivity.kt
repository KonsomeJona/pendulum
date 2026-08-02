package com.pendulum.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pendulum.phone.data.AppairageMontre
import com.pendulum.phone.export.PorteP1Exporter
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.ui.export.ExportScreen
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.ui.export.ExportUi
import com.pendulum.phone.ui.home.HomeScreen
import com.pendulum.phone.ui.nights.NightDetailScreen
import com.pendulum.phone.ui.nights.NightListScreen
import com.pendulum.phone.ui.onboarding.AssistantActions
import com.pendulum.phone.ui.onboarding.AssistantUi
import com.pendulum.phone.ui.onboarding.OnboardingPager
import com.pendulum.phone.ui.onboarding.RepriseAssistant
import com.pendulum.phone.ui.quiz.ScreeningQuizScreen
import com.pendulum.phone.ui.settings.RapportP1Screen
import com.pendulum.phone.ui.settings.SettingsScreen
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.tonight.EveningContextScreen
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.trend.ComparePeriodsScreen
import com.pendulum.phone.ui.trend.TrendScreen
import com.pendulum.phone.work.DeclencheurOpportuniste
import kotlinx.coroutines.launch

/**
 * L'unique activite du telephone.
 *
 * `launchMode="singleTask"` est declare au manifeste : la notification du matin doit ramener sur
 * l'instance existante, pas en empiler une seconde.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PendulumTheme {
                PortailPendulum()
            }
        }
    }

    /**
     * Le second declencheur opportuniste de la lecture Health Connect.
     *
     * L'echelle de reprise de `FetchSchedule` fait un repli exponentiel parce qu'elle ignore quand
     * l'hypnogramme arrivera. Mais la synchronisation **est correlee a l'usage** : l'application
     * source ecrit dans Health Connect quand on l'ouvre, c'est-a-dire souvent quelques secondes
     * avant qu'on ouvre Pendulum pour regarder sa nuit. Attendre le rang T+4 h alors que la donnee
     * est arrivee a T+2 h 05 coute deux heures de latence percue pour rien.
     *
     * Le premier declencheur est le branchement du chargeur (`PowerConnectedReceiver`). Ni l'un ni
     * l'autre ne consomme l'echelle : voir `FetchSchedule.INDEX_OPPORTUNISTE`.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            runCatching { DeclencheurOpportuniste.declencher(this@MainActivity) }
        }
    }
}

/**
 * Le portail : l'assistant, ou l'application.
 *
 * ### Ce qu'il repare
 *
 * `OnboardingPager` etait ecrit, complet, en cinq etapes, et **n'avait aucun appelant**. La
 * consequence n'etait pas cosmetique : le seul `rememberLauncherForActivityResult` de
 * l'application vivait dans cet ecran inatteignable, donc aucun chemin utilisateur n'accordait
 * jamais les permissions Health Connect. Chaque nuit etait alors scoree par le seul masque
 * accelerometrique, sans que rien ne le dise — la circularite numerateur/denominateur que tout le
 * projet existe pour eviter.
 *
 * ### Rien tant que le compteur n'est pas lu
 *
 * Le compteur d'etapes vient du DataStore, donc d'une lecture asynchrone. Composer la navigation
 * en attendant la ferait apparaitre une fraction de seconde avant que l'assistant ne la remplace,
 * ce qui apprend a l'utilisateur que l'application clignote au demarrage. Meme regle qu'a
 * l'accueil : on n'affiche rien plutot qu'un etat provisoire.
 */
@Composable
fun PortailPendulum() {
    val vm: OnboardingViewModel = viewModel()
    val etape by vm.etape.collectAsStateWithLifecycle()

    val e = etape ?: return
    if (!RepriseAssistant.assistantAFaire(e)) {
        PendulumNavHost()
        return
    }

    val montre by vm.montre.collectAsStateWithLifecycle()
    val sante by vm.sante.collectAsStateWithLifecycle()
    val sourcePreferee by vm.sourcePreferee.collectAsStateWithLifecycle()
    val installation by vm.installation.collectAsStateWithLifecycle()
    val contexte = LocalContext.current

    OnboardingPager(
        etatUi = AssistantUi(
            startPage = RepriseAssistant.pageDeDepart(e),
            montre = montre,
            sante = sante,
            sourcePreferee = sourcePreferee,
            installation = installation,
        ),
        actions = AssistantActions(
            onEtapeFranchie = vm::franchir,
            onOuvrirCompagnon = { AppairageMontre.ouvrirLApplicationCompagnon(contexte) },
            onInstallerSurLaMontre = vm::installerSurLaMontre,
            onRelireLaSante = vm::relireLaSante,
            onChoisirSource = vm::choisirSource,
            onRepere = vm::poserLeRepere,
        ),
    )
}

/**
 * Trois destinations racines, et **Accueil en destination de depart**.
 *
 * Trois et pas quatre : au-dela, la hierarchie se dilue et l'utilisateur cherche
 * (`06-interface.md` §6). Le questionnaire, la liste des nuits, le detail d'une nuit, la
 * comparaison et l'export sont des destinations empilees, sans barre de navigation — ce sont des
 * taches, pas des lieux.
 *
 * ### Pourquoi Tendance n'est plus l'accueil
 *
 * Elle l'etait, et l'ecran de depart melangeait alors deux regimes cognitifs incompatibles : le
 * geste quotidien — rapide, memorise, fait d'une main — et la lecture d'un resultat statistique,
 * lente et chargee. Le premier se payait du second : on venait appuyer sur un bouton et on lisait
 * un chiffre en chemin, a l'heure ou l'on est le moins capable de le juger. La Tendance reste une
 * destination racine ; elle n'est plus la porte d'entree.
 *
 * ### Pourquoi Nuits sort de la barre
 *
 * La liste des nuits est une consultation, pas un lieu de sejour : on y va depuis la carte
 * HISTORIQUE de l'accueil, avec une question en tete, et on en revient. Lui garder une entree
 * permanente aurait fait quatre destinations racines, ce que `06-interface.md` §6 exclut
 * explicitement — « trois destinations suffisent ; au-dela la hierarchie se dilue ».
 *
 * Pas de bouton d'action flottant : il n'existe aucune action de creation sur le telephone.
 * L'enregistrement demarre sur la montre, et la seule porte est le scellement du contexte.
 */
enum class Destination(val route: String, val libelle: String) {
    ACCUEIL("home", Textes.EcranAccueil.TITRE),
    TENDANCE("trend", Textes.Tendance.TITRE),
    REGLAGES("settings", Textes.Reglages.TITRE),
}

/** La liste des nuits, atteinte depuis la carte HISTORIQUE. Empilee : c'est une consultation. */
const val ROUTE_NUITS = "nights"

/**
 * Le formulaire du soir. Empile, sans barre de navigation : c'est une tache, pas un lieu.
 *
 * Il n'est pas une quatrieme entree de navigation, et ce n'est pas qu'une question de hierarchie.
 * On ne « va » pas dans le contexte du soir comme on va dans les reglages : on le remplit une
 * fois, le soir, et il devient inaccessible — les declencheurs SQLite refusent toute modification
 * ensuite. Une entree permanente vers un ecran qu'on ne peut ouvrir qu'une fois par jour, et qui
 * echoue si on l'ouvre deux fois, serait une invitation a l'erreur.
 */
const val ROUTE_SOIR = "evening"

/**
 * Le rapport de la porte P1, atteint depuis Reglages › Mesure. Empile, sans barre de navigation :
 * on y va pour verifier un chiffre, et on en revient.
 */
const val ROUTE_P1 = "p1"

/**
 * Les trois icones sont dessinees a la main, en contour, plutot que tirees d'un jeu importe.
 *
 * Deux raisons. Le jeu `Filled` de Material est ecarte par principe — il est visuellement lourd
 * et lit « consommateur » — et le jeu `Outlined` complet est une dependance de plusieurs
 * megaoctets pour trois glyphes. Trois traits suffisent, et ils suivent exactement l'epaisseur du
 * reste de l'interface.
 */
private fun DrawScope.iconeDestination(d: Destination, couleur: Color) {
    val e = size.minDimension * 0.09f
    val s = size.minDimension
    when (d) {
        // Accueil : un toit et son mur. Le glyphe le plus lu de toute l'informatique grand
        // public, et c'est exactement la raison de le prendre — cette entree-la ne doit demander
        // aucune interpretation, puisqu'elle est celle qu'on vise a 23 h et a 7 h.
        Destination.ACCUEIL -> {
            drawLine(couleur, Offset(s * 0.14f, s * 0.46f), Offset(s * 0.5f, s * 0.18f), e)
            drawLine(couleur, Offset(s * 0.5f, s * 0.18f), Offset(s * 0.86f, s * 0.46f), e)
            drawLine(couleur, Offset(s * 0.24f, s * 0.42f), Offset(s * 0.24f, s * 0.82f), e)
            drawLine(couleur, Offset(s * 0.76f, s * 0.42f), Offset(s * 0.76f, s * 0.82f), e)
            drawLine(couleur, Offset(s * 0.2f, s * 0.82f), Offset(s * 0.8f, s * 0.82f), e)
        }
        // Tendance : trois points a des hauteurs differentes, sans ligne qui les relie —
        // exactement ce que le graphe de tendance fait, et pour la meme raison.
        Destination.TENDANCE -> {
            drawCircle(couleur, e, Offset(s * 0.2f, s * 0.72f))
            drawCircle(couleur, e, Offset(s * 0.5f, s * 0.38f))
            drawCircle(couleur, e, Offset(s * 0.8f, s * 0.55f))
        }
        // Reglages : deux curseurs, la metaphore la plus directe pour des parametres.
        Destination.REGLAGES -> {
            drawLine(couleur, Offset(s * 0.15f, s * 0.35f), Offset(s * 0.85f, s * 0.35f), e)
            drawLine(couleur, Offset(s * 0.15f, s * 0.68f), Offset(s * 0.85f, s * 0.68f), e)
            drawCircle(couleur, e * 1.6f, Offset(s * 0.62f, s * 0.35f))
            drawCircle(couleur, e * 1.6f, Offset(s * 0.34f, s * 0.68f))
        }
    }
}

/** `E-HC-02` : la permission de lecture du sommeil a ete retiree. Voir `Situations.sommeil`. */
private const val CODE_PERMISSION_REVOQUEE = "E-HC-02"

private fun codeSituationSommeil(etat: TendanceUiState): String? = when (etat) {
    is TendanceUiState.Pret -> etat.situationSommeil?.code
    is TendanceUiState.Refus -> etat.situationSommeil?.code
    TendanceUiState.Chargement -> null
}

/**
 * Ouvre l'ecran Health Connect. `runCatching` parce que l'action n'est pas resolue partout : sur
 * un appareil ou Health Connect a ete desinstalle entre l'affichage de la carte et l'appui, une
 * `ActivityNotFoundException` non rattrapee ferait planter l'application sur un bouton d'aide.
 */
private fun ouvrirHealthConnect(contexte: android.content.Context) {
    runCatching {
        contexte.startActivity(
            android.content.Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
        )
    }
}

@Composable
fun PendulumNavHost(nav: NavHostController = rememberNavController()) {
    val entree by nav.currentBackStackEntryAsState()
    val routeCourante = entree?.destination?.route

    Scaffold(
        bottomBar = {
            // La barre disparait sur les destinations empilees : une tache en cours ne propose
            // pas de partir ailleurs d'un pouce distrait.
            if (Destination.entries.any { it.route == routeCourante }) {
                NavigationBar {
                    Destination.entries.forEach { d ->
                        NavigationBarItem(
                            selected = routeCourante == d.route,
                            onClick = {
                                nav.navigate(d.route) {
                                    popUpTo(Destination.ACCUEIL.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                val teinte = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                Canvas(Modifier.size(22.dp)) { iconeDestination(d, teinte) }
                            },
                            label = { Text(d.libelle, style = PendulumType.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Destination.ACCUEIL.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.ACCUEIL.route) {
                val vm: HomeViewModel = viewModel()
                val etat by vm.etat.collectAsStateWithLifecycle()

                // Rien tant que la premiere lecture n'a pas abouti. Pas de squelette anime, pas
                // de cartes vides : trois cartes qui se remplissent apres coup deplaceraient
                // exactement ce que cet ecran existe pour ne plus deplacer.
                etat?.let { a ->
                    HomeScreen(
                        etat = a,
                        onSceller = { nav.navigate(ROUTE_SOIR) },
                        onFinDeNuit = { a.sessionAFermer?.let(vm::finDeNuit) },
                        // Un seul geste : la trace est ecrite et l'ecran de detail s'ouvre dans
                        // la foulee. Deux appuis pour un chiffre qu'on a le droit de voir
                        // seraient un peage, pas un ralentisseur.
                        onDevoiler = { hex ->
                            vm.devoiler(hex)
                            nav.navigate("night/$hex")
                        },
                        onHistorique = { nav.navigate(ROUTE_NUITS) },
                    )
                }
            }
            composable(Destination.TENDANCE.route) {
                val vm: TrendViewModel = viewModel()
                val etat by vm.etat.collectAsStateWithLifecycle()
                val contexte = LocalContext.current

                // La reparation de `E-HC-02` est une demande de permission, pas un lien vers un
                // ecran : envoyer quelqu'un dans les reglages de Health Connect pour retrouver
                // une case a cocher alors que le systeme sait afficher la boite de dialogue est
                // exactement le genre de detour qui fait abandonner.
                val lanceurSante = rememberLauncherForActivityResult(
                    contract = SleepReader.permissionRequestContract(),
                ) { vm.relireLaSante() }

                TrendScreen(
                    etat = etat,
                    onNuit = { nav.navigate("night/$it") },
                    onComparer = { nav.navigate("compare") },
                    onQuestionnaire = { nav.navigate("quiz") },
                    onExport = { nav.navigate("export") },
                    onActionReveil = {},
                    onSituationSommeil = {
                        if (codeSituationSommeil(etat) == CODE_PERMISSION_REVOQUEE) {
                            lanceurSante.launch(
                                SleepReader.REQUIRED_PERMISSIONS + SleepReader.OPTIONAL_PERMISSIONS,
                            )
                        } else {
                            ouvrirHealthConnect(contexte)
                        }
                    },
                )
            }
            // La liste des nuits : empilee, atteinte depuis la carte HISTORIQUE de l'accueil.
            composable(ROUTE_NUITS) {
                val vm: NightsViewModel = viewModel()
                val nuits by vm.nuits.collectAsStateWithLifecycle()
                NightListScreen(nuits, onNuit = { nav.navigate("night/$it") })
            }
            composable(Destination.REGLAGES.route) {
                val vm: SettingsViewModel = viewModel()
                val reglages by vm.reglages.collectAsStateWithLifecycle()
                SettingsScreen(reglages, {}, {}, {}, onRapportP1 = { nav.navigate(ROUTE_P1) })
            }
            // Le rapport de la porte P1. Empile : c'est une verification, pas un lieu.
            composable(ROUTE_P1) {
                val vm: RapportP1ViewModel = viewModel()
                val rapport by vm.rapport.collectAsStateWithLifecycle()
                val contexte = LocalContext.current
                val portee = rememberCoroutineScope()

                // Le meme chemin SAF que l'export d'une nuit : l'utilisateur choisit
                // l'emplacement, geste par geste. L'application n'a aucun repertoire a elle dans
                // le stockage partage, et aucune permission reseau pour envoyer le fichier
                // ailleurs.
                val createur = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv"),
                ) { uri ->
                    uri?.let { portee.launch { PorteP1Exporter.exportVers(contexte, it) } }
                }

                // Rien tant que la lecture n'a pas abouti : un verdict qui s'affiche avant d'etre
                // calcule est un verdict qu'on a lu faux une fois.
                rapport?.let {
                    RapportP1Screen(
                        etat = it,
                        onExporter = { createur.launch(Textes.P1.NOM_FICHIER) },
                    )
                }
            }
            // Destinations empilees : pas de barre de navigation, ce sont des taches.
            //
            // Le formulaire du soir en fait partie, et c'est la plus consequente : c'est la seule
            // porte du produit. Tant qu'il n'a pas ete rempli et scelle, la montre refuse de
            // demarrer — non par avertissement, mais parce que le `DataItem` que `Preflight`
            // attend n'existe pas.
            composable(ROUTE_SOIR) {
                val vm: EveningViewModel = viewModel()
                val repere by vm.repereDeSerrage.collectAsStateWithLifecycle()
                val resultat by vm.resultat.collectAsStateWithLifecycle()

                EveningContextScreen(
                    repereDeSerrage = repere,
                    onSceller = { vm.sceller(it) },
                    onAnnuler = { nav.popBackStack() },
                )

                // Le retour n'a lieu qu'une fois le scellement acte en base. Fermer l'ecran des
                // le clic laisserait croire au succes d'une insertion qui peut echouer — sceller
                // deux fois la meme soiree leve, et c'est voulu.
                LaunchedEffect(resultat) {
                    if (resultat != null) {
                        vm.resultatConsomme()
                        nav.popBackStack()
                    }
                }
            }
            composable("night/{hex}") { entree ->
                val hex = entree.arguments?.getString("hex").orEmpty()
                val vm: NightDetailViewModel = viewModel()
                val detail by vm.detail.collectAsStateWithLifecycle()
                LaunchedEffect(hex) { vm.charger(hex) }

                // Rien tant que la lecture n'a pas abouti. Pas de squelette anime, pas de valeurs
                // par defaut : un ecran de detail qui affiche des zeros pendant deux cents
                // millisecondes apprend a lire des chiffres avant qu'ils ne soient vrais.
                detail?.let {
                    NightDetailScreen(
                        detail = it,
                        onVoirTendance = { nav.popBackStack() },
                        onAppliquerATout = {},
                        onDevoiler = { vm.devoiler(hex) },
                    )
                }
            }
            composable("compare") {
                ComparePeriodsScreen(
                    resultat = null,
                    motifIndisponible = Textes.Comparaison.PERIODE_A to 3,
                    libellePeriodeA = "1–15 February",
                    libellePeriodeB = "1–15 March",
                )
            }
            composable("quiz") {
                ScreeningQuizScreen(null, {}, {}, {})
            }
            composable("export") {
                ExportScreen(
                    ExportUi(true, false, true, true, 6, "1–15 March", null),
                    {}, {}, {}, {}, {},
                )
            }
        }
    }
}

