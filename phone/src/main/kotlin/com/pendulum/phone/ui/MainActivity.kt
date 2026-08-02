package com.pendulum.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pendulum.phone.ui.export.ExportScreen
import com.pendulum.phone.ui.export.ExportUi
import com.pendulum.phone.ui.nights.NightDetailScreen
import com.pendulum.phone.ui.nights.NightListScreen
import com.pendulum.phone.ui.quiz.ScreeningQuizScreen
import com.pendulum.phone.ui.settings.SettingsScreen
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.tonight.EveningContextScreen
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.trend.ComparePeriodsScreen
import com.pendulum.phone.ui.trend.TrendScreen

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
                PendulumNavHost()
            }
        }
    }
}

/**
 * Trois destinations racines, et **Tendance en destination de depart**.
 *
 * Trois et pas quatre : au-dela, la hierarchie se dilue et l'utilisateur cherche. Le
 * questionnaire, le detail d'une nuit, la comparaison et l'export sont des destinations
 * empilees, sans barre de navigation — ce sont des taches, pas des lieux.
 *
 * Pas de bouton d'action flottant : il n'existe aucune action de creation sur le telephone.
 * L'enregistrement demarre sur la montre, et la seule porte est le scellement du contexte.
 */
enum class Destination(val route: String, val libelle: String) {
    TENDANCE("trend", Textes.Tendance.TITRE),
    NUITS("nights", Textes.Nuits.TITRE),
    REGLAGES("settings", Textes.Reglages.TITRE),
}

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
        // Tendance : trois points a des hauteurs differentes, sans ligne qui les relie —
        // exactement ce que le graphe de tendance fait, et pour la meme raison.
        Destination.TENDANCE -> {
            drawCircle(couleur, e, Offset(s * 0.2f, s * 0.72f))
            drawCircle(couleur, e, Offset(s * 0.5f, s * 0.38f))
            drawCircle(couleur, e, Offset(s * 0.8f, s * 0.55f))
        }
        // Nuits : un croissant, obtenu par soustraction visuelle de deux arcs.
        Destination.NUITS -> {
            drawArc(couleur, 120f, 300f, false, Offset(s * 0.18f, s * 0.18f), Size(s * 0.64f, s * 0.64f), style = Stroke(e))
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
                                    popUpTo(Destination.TENDANCE.route) { saveState = true }
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
            startDestination = Destination.TENDANCE.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.TENDANCE.route) {
                val vm: TrendViewModel = viewModel()
                val etat by vm.etat.collectAsStateWithLifecycle()
                TrendScreen(
                    etat = etat,
                    onNuit = { nav.navigate("night/$it") },
                    onComparer = { nav.navigate("compare") },
                    onQuestionnaire = { nav.navigate("quiz") },
                    onExport = { nav.navigate("export") },
                    onSceller = { nav.navigate(ROUTE_SOIR) },
                    onActionReveil = {},
                )
            }
            composable(Destination.NUITS.route) {
                val vm: NightsViewModel = viewModel()
                val nuits by vm.nuits.collectAsStateWithLifecycle()
                NightListScreen(nuits, onNuit = { nav.navigate("night/$it") })
            }
            composable(Destination.REGLAGES.route) {
                val vm: SettingsViewModel = viewModel()
                val reglages by vm.reglages.collectAsStateWithLifecycle()
                SettingsScreen(reglages, {}, {}, {})
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
                    NightDetailScreen(it, onVoirTendance = { nav.popBackStack() }, {})
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

