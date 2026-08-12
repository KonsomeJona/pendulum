package com.pendulum.phone.ui

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableLongStateOf
import android.os.SystemClock
import androidx.activity.compose.setContent
import com.pendulum.phone.ui.theme.ThemeMode
import com.pendulum.phone.data.PendulumPreferences
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.pendulum.phone.R
import com.pendulum.phone.data.AppairageMontre
import com.pendulum.phone.export.PorteP1Exporter
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.temps.Durees
import com.pendulum.phone.ui.export.ExportScreen
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.ui.home.HomeScreen
import com.pendulum.phone.ui.nights.NightDetailScreen
import com.pendulum.phone.ui.nights.NightListScreen
import com.pendulum.phone.ui.onboarding.AssistantActions
import com.pendulum.phone.ui.onboarding.AssistantUi
import com.pendulum.phone.ui.onboarding.OnboardingPager
import com.pendulum.phone.ui.onboarding.RepriseAssistant
import com.pendulum.phone.ui.quiz.ScreeningQuizScreen
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.settings.AvertissementScreen
import com.pendulum.phone.ui.settings.EffacementScreen
import com.pendulum.phone.ui.settings.RapportP1Screen
import com.pendulum.phone.ui.settings.SettingsScreen
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.text.NomsDeFichier
import com.pendulum.phone.ui.text.texte
import com.pendulum.phone.ui.tonight.EveningContextScreen
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.trend.ComparePeriodsScreen
import com.pendulum.phone.ui.trend.TrendScreen
import com.pendulum.phone.work.DeclencheurOpportuniste
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * L'unique activite du telephone.
 *
 * `launchMode="singleTask"` est declare au manifeste : la notification du matin doit ramener sur
 * l'instance existante, pas en empiler une seconde.
 */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { PendulumPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Le theme choisi dans les reglages, applique a la racine.
            //
            // `PendulumTheme` etait appele **sans argument**, donc toujours sur son defaut sombre.
            // La preference existait pourtant de bout en bout — stockee, ecrite par les reglages,
            // et affichee par eux avec sa valeur : l'ecran montrait « System » ou « Light » et
            // l'application restait sombre. Un reglage qui affiche un etat qu'il n'a pas est de
            // la meme famille que les boutons muets deja retires, en plus trompeur : celui-ci
            // repond quelque chose.
            //
            // `null` tant que le DataStore n'a pas rendu sa premiere valeur : on garde le sombre
            // d'ici la, plutot que d'ouvrir en clair pour basculer en sombre un instant apres —
            // a 7 h du matin, cet eclair est exactement ce que le theme sombre existe pour eviter.
            val jeton by prefs.theme.collectAsStateWithLifecycle(initialValue = null)
            PendulumTheme(mode = modeDeTheme(jeton)) {
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
enum class Destination(val route: String, @StringRes val libelle: Int) {
    ACCUEIL("home", R.string.home_title),
    TENDANCE("trend", R.string.trend_title),
    REGLAGES("settings", R.string.settings_title),
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
 * L'avertissement, relu depuis Reglages › A propos.
 *
 * `06-interface.md` demande qu'il reste accessible en permanence : quelqu'un qui consulte un
 * chiffre trois mois plus tard doit pouvoir relire, en deux gestes, pourquoi ce chiffre n'est pas
 * un diagnostic. La ligne existait et appelait un `{}`.
 */
const val ROUTE_AVERTISSEMENT = "notice"

/**
 * L'effacement total. Empile, et non une boite de dialogue posee sur les reglages : le texte qui
 * dit ce qui part fait dix lignes, et une modale de dix lignes se ferme sans etre lue.
 */
const val ROUTE_EFFACER = "erase"

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


/**
 * La nuit dont la bande d'etat parle. Elle vient de l'etat et non d'une seconde lecture : l'action
 * doit porter sur la nuit que l'utilisateur a sous les yeux, pas sur la plus recente au moment ou
 * il appuie.
 */
private fun sessionDeLaBande(etat: TendanceUiState): String? = when (etat) {
    is TendanceUiState.Pret -> etat.sessionReveil
    is TendanceUiState.Refus -> etat.sessionReveil
    TendanceUiState.Chargement -> null
}

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
                            label = { Text(stringResource(d.libelle), style = PendulumType.label) },
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
                val retourDemarrage by vm.demarrage.collectAsStateWithLifecycle()
                val contexteAccueil = LocalContext.current

                // Le meme mecanisme que sur la tendance, et pour la meme raison : la boite ne
                // s'affiche plus apres deux refus, et il faut alors emmener dans Health Connect
                // plutot que de proposer une demande qui ne montrerait rien.
                var demandeAccueilA by remember { mutableLongStateOf(0L) }
                var accueilEtouffe by remember { mutableStateOf(false) }
                val lanceurAccueil = rememberLauncherForActivityResult(
                    contract = SleepReader.permissionRequestContract(),
                ) { accordees ->
                    val ecoule = SystemClock.elapsedRealtime() - demandeAccueilA
                    accueilEtouffe = !accordees.containsAll(SleepReader.REQUIRED_PERMISSIONS) &&
                        ecoule < SleepReader.DELAI_DIALOGUE_ETOUFFE_MS
                    vm.relireLaSante()
                }

                // La permission se donne hors de l'application : au retour, on relit.
                LifecycleResumeEffect(Unit) {
                    vm.relireLaSante()
                    onPauseOrDispose { }
                }

                // Rien tant que la premiere lecture n'a pas abouti. Pas de squelette anime, pas
                // de cartes vides : trois cartes qui se remplissent apres coup deplaceraient
                // exactement ce que cet ecran existe pour ne plus deplacer.
                etat?.let { a ->
                    HomeScreen(
                        etat = a,
                        onSceller = { nav.navigate(ROUTE_SOIR) },
                        onFinDeNuit = { a.sessionAFermer?.let(vm::finDeNuit) },
                        onDemarrer = vm::demarrerSurLaMontre,
                        retourDemarrage = retourDemarrage,
                        // Un seul geste : la trace est ecrite et l'ecran de detail s'ouvre dans
                        // la foulee. Deux appuis pour un chiffre qu'on a le droit de voir
                        // seraient un peage, pas un ralentisseur.
                        onDevoiler = { hex ->
                            vm.devoiler(hex)
                            nav.navigate("night/$hex")
                        },
                        onHistorique = { nav.navigate(ROUTE_NUITS) },
                        onSituationSommeil = {
                            if (accueilEtouffe) {
                                SleepReader.intentPermissionsManuelles(contexteAccueil)
                                    ?.let(contexteAccueil::startActivity)
                                    ?: ouvrirHealthConnect(contexteAccueil)
                            } else {
                                demandeAccueilA = SystemClock.elapsedRealtime()
                                lanceurAccueil.launch(
                                    SleepReader.REQUIRED_PERMISSIONS +
                                        SleepReader.OPTIONAL_PERMISSIONS,
                                )
                            }
                        },
                    )

                    // Le compte rendu du demarrage s'efface tout seul, de deux facons.
                    //
                    // Par le temps d'abord : c'est le resultat d'un geste, pas un etat. Une phrase
                    // qui resterait sous le bouton jusqu'au lendemain finirait par decrire une
                    // demande qui n'a plus rien a voir avec la nuit en cours.
                    //
                    // Par la transition ensuite : des que la phase change — typiquement quand la
                    // montre ouvre sa session et que l'accueil passe en ENREGISTREMENT — la carte
                    // dit elle-meme ce qui se passe, et repeter « demande envoyee » sous une carte
                    // qui affiche « RECORDING » ferait douter de celle des deux qu'il faut croire.
                    LaunchedEffect(retourDemarrage) {
                        if (retourDemarrage != null) {
                            delay(Durees.ACTIVES.retourDemarrageMs)
                            vm.demarrageConsomme()
                        }
                    }
                    LaunchedEffect(a.phase) { vm.demarrageConsomme() }
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
                // La boite de dialogue cesse d'apparaitre apres deux refus, et le contrat rend
                // alors la main immediatement, sans rien montrer. Aucune API ne distingue ce cas
                // d'un refus ordinaire : le temps ecoule est le seul signal. En dessous du seuil,
                // on arrete de proposer une demande qui ne peut plus aboutir et on montre le
                // chemin manuel.
                var demandeLanceeA by remember { mutableLongStateOf(0L) }
                var dialogueEtouffe by remember { mutableStateOf(false) }
                val lanceurSante = rememberLauncherForActivityResult(
                    contract = SleepReader.permissionRequestContract(),
                ) { accordees ->
                    val ecoule = SystemClock.elapsedRealtime() - demandeLanceeA
                    val manquantes = !accordees.containsAll(SleepReader.REQUIRED_PERMISSIONS)
                    dialogueEtouffe =
                        manquantes && ecoule < SleepReader.DELAI_DIALOGUE_ETOUFFE_MS
                    vm.relireLaSante()
                }

                TrendScreen(
                    etat = etat,
                    onNuit = { nav.navigate("night/$it") },
                    onNuits = { nav.navigate(ROUTE_NUITS) },
                    onComparer = { nav.navigate("compare") },
                    onQuestionnaire = { nav.navigate("quiz") },
                    onExport = { nav.navigate("export") },
                    onActionReveil = { sessionDeLaBande(etat)?.let(vm::relancerLeReveil) },
                    onSituationSommeil = {
                        val permission = codeSituationSommeil(etat) == CODE_PERMISSION_REVOQUEE
                        when {
                            permission && !dialogueEtouffe -> {
                                demandeLanceeA = SystemClock.elapsedRealtime()
                                lanceurSante.launch(
                                    SleepReader.REQUIRED_PERMISSIONS +
                                        SleepReader.OPTIONAL_PERMISSIONS,
                                )
                            }
                            // Le systeme ne montrera plus rien : le seul geste qui reste est
                            // manuel, et il faut y emmener plutot que de le decrire.
                            permission -> SleepReader.intentPermissionsManuelles(contexte)
                                ?.let(contexte::startActivity)
                                ?: ouvrirHealthConnect(contexte)
                            else -> ouvrirHealthConnect(contexte)
                        }
                    },
                    permissionEtouffee = dialogueEtouffe,
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

                // Le retour du paquet d'une nuit. `OpenDocument` et non `GetContent` : le premier
                // rend un `Uri` de document persistable et laisse choisir dans n'importe quel
                // fournisseur, le second passe par une intention de partage que tous n'honorent
                // pas. Le filtre est `*/*` parce qu'un `.bundle` n'a pas de type MIME enregistre :
                // filtrer sur un type inconnu grise le seul fichier que l'on cherche.
                val ouvreur = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(vm::importerNuit) }

                // L'espace occupe est relu a chaque entree sur l'ecran : il vient d'une lecture
                // disque, et un effacement ou un import a pu avoir lieu entre deux passages.
                LaunchedEffect(Unit) { vm.relireLEspace() }

                SettingsScreen(
                    reglages,
                    onRelireAvertissement = { nav.navigate(ROUTE_AVERTISSEMENT) },
                    onEffacer = { nav.navigate(ROUTE_EFFACER) },
                    onImporterNuit = { ouvreur.launch(arrayOf("*/*")) },
                    onRapportP1 = { nav.navigate(ROUTE_P1) },
                )
            }
            // L'avertissement, en lecture seule. Pas de defilement bloquant ni de cases a cocher :
            // la porte est celle de l'assistant, et la redemander a chaque relecture ferait de la
            // relecture une corvee, donc une chose qu'on ne fait pas.
            composable(ROUTE_AVERTISSEMENT) { AvertissementScreen() }
            composable(ROUTE_EFFACER) {
                val vm: EffacementViewModel = viewModel()
                val espace by vm.espace.collectAsStateWithLifecycle()
                val efface by vm.efface.collectAsStateWithLifecycle()
                EffacementScreen(espaceOccupe = espace, efface = efface, onEffacer = vm::effacer)
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
                        onExporter = { createur.launch(NomsDeFichier.RAPPORT_P1) },
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
                    resultat = resultat,
                    onSceller = { vm.sceller(it) },
                    onAnnuler = { nav.popBackStack() },
                )

                // Le retour n'a lieu que sur `Scelle`, et sur lui seul.
                //
                // Les trois issues etaient traitees a l'identique — l'ecran se fermait — et les
                // deux autres ne sont pas des succes. `PublicationEchouee` est la pire des trois
                // parce qu'elle est silencieuse et contradictoire : la base a le contexte, donc
                // l'accueil dit qu'il est scelle, pendant que la montre, qui n'a pas recu le
                // `DataItem`, continue de reclamer le formulaire du soir. Fermer l'ecran a cet
                // instant, c'est envoyer chercher pendant dix minutes pourquoi START reste bloque.
                //
                // Sur les deux autres, l'ecran reste et dit quoi faire. Le resultat n'est donc pas
                // consomme : il porte ce que la carte affiche, et l'ecran ne se quitte plus que
                // par « Not now ».
                LaunchedEffect(resultat) {
                    if (resultat == ResultatScellement.Scelle) {
                        vm.resultatConsomme()
                        nav.popBackStack()
                    }
                }
            }
            composable("night/{hex}") { entree ->
                val hex = entree.arguments?.getString("hex").orEmpty()
                val vm: NightDetailViewModel = viewModel()
                val detail by vm.detail.collectAsStateWithLifecycle()
                val ecriture by vm.ecriture.collectAsStateWithLifecycle()
                LaunchedEffect(hex) { vm.charger(hex) }

                // Rien tant que la lecture n'a pas abouti. Pas de squelette anime, pas de valeurs
                // par defaut : un ecran de detail qui affiche des zeros pendant deux cents
                // millisecondes apprend a lire des chiffres avant qu'ils ne soient vrais.
                // Les deux sorties d'une nuit, par le meme chemin SAF que tout le reste :
                // `ACTION_CREATE_DOCUMENT`, emplacement choisi par l'utilisateur. L'application
                // n'ecrit dans aucun repertoire partage de sa propre initiative et ne declare pas
                // la permission `INTERNET` — le fichier ne peut aller qu'ou il a ete demande.
                // Le nom propose est retenu jusqu'au retour du selecteur : c'est lui que le compte
                // rendu affiche ensuite. Meme motif qu'a l'ecran d'export — le recalculer dans le
                // rappel donnerait un autre nom si le choix de l'emplacement a traverse minuit.
                var nomPropose by remember { mutableStateOf("") }
                val createurRapport = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/markdown"),
                ) { uri -> uri?.let { vm.exporterRapport(hex, it, nomPropose) } }
                val createurPaquet = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri -> uri?.let { vm.exporterPaquet(hex, it, nomPropose) } }

                detail?.let { d ->
                    val jour = Mapping.jourIso(
                        d.nuit.startWallMs,
                        java.time.ZoneId.systemDefault().id,
                    )
                    NightDetailScreen(
                        detail = d,
                        onVoirTendance = { nav.popBackStack() },
                        onAppliquerATout = vm::appliquerATout,
                        onDevoiler = { vm.devoiler(hex) },
                        onExporterRapport = {
                            nomPropose = NomsDeFichier.rapportDeNuit(jour)
                            createurRapport.launch(nomPropose)
                        },
                        onExporterPaquet = {
                            nomPropose = NomsDeFichier.paquetDeNuit(jour)
                            createurPaquet.launch(nomPropose)
                        },
                        ecriture = ecriture,
                    )
                }
            }
            // La comparaison de deux periodes n'a pas de selecteur de dates, donc pas de
            // periodes a comparer. L'ecran affichait jusqu'ici un refus assorti de deux
            // etiquettes fabriquees — « 1-15 February », « 1-15 March » — c'est-a-dire le meme
            // defaut que la navigation cablee sur le jeu d'apercu : pas un chiffre invente, mais
            // un contexte invente, ce qui se lit tout aussi bien comme une donnee reelle.
            //
            // Les etiquettes sont donc vides tant que le selecteur n'existe pas. `Aggregat.comparer`
            // est ecrit et teste et n'attend que lui ; c'est une fonctionnalite a faire, pas un
            // cablage a poser, et l'ecran doit dire qu'elle n'est pas la plutot que la mimer.
            composable("compare") {
                ComparePeriodsScreen(
                    resultat = null,
                    motifIndisponible = texte(R.string.compare_period_a) to Aggregat.MIN_NUITS_COMPARAISON,
                    libellePeriodeA = "",
                    libellePeriodeB = "",
                )
            }
            composable("quiz") {
                val vm: QuizViewModel = viewModel()
                val issue by vm.issue.collectAsStateWithLifecycle()
                ScreeningQuizScreen(
                    issue = issue,
                    onOui = { vm.repondre(true) },
                    onNon = { vm.repondre(false) },
                    onRevoir = vm::revoir,
                )
            }
            // L'export du rapport pour le medecin — le seul but que `README.md` juge defendable,
            // et qu'aucun geste n'atteignait : les cinq lambdas de cet ecran etaient vides et
            // `ReportExporter` n'avait aucun appelant.
            composable("export") {
                val vm: ExportViewModel = viewModel()
                val etat by vm.etat.collectAsStateWithLifecycle()

                // Le nom propose est retenu jusqu'au retour du selecteur : c'est lui que l'ecran
                // affiche ensuite. Le recalculer dans le rappel donnerait un autre nom si le
                // choix de l'emplacement a traverse minuit.
                var nomPropose by remember { mutableStateOf("") }
                val createur = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/markdown"),
                ) { uri -> uri?.let { vm.enregistrer(it, nomPropose) } }

                // Rien tant que la lecture n'a pas abouti : un compteur de nuits eligibles qui
                // s'affiche a zero avant d'etre lu ferait apparaitre le bouton desactive avec son
                // motif, puis actif — c'est-a-dire un refus qui se retracte.
                etat?.let {
                    ExportScreen(
                        etat = it,
                        onQuestionnaire = vm::poserQuestionnaire,
                        onEcartees = vm::poserEcartees,
                        onEnregistrer = {
                            nomPropose = vm.nomFichier()
                            createur.launch(nomPropose)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Le jeton stocke vers le mode du theme. `null` — premiere lecture non aboutie — vaut sombre,
 * comme le defaut du produit.
 */
private fun modeDeTheme(jeton: String?): ThemeMode = when (jeton) {
    PendulumPreferences.THEME_SYSTEME -> ThemeMode.System
    PendulumPreferences.THEME_CLAIR -> ThemeMode.Light
    else -> ThemeMode.Dark
}
