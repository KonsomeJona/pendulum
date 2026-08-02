package com.pendulum.phone.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.pendulum.phone.data.EtatAppairage
import com.pendulum.phone.data.EtatMontre
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.health.SourcesSommeil
import com.pendulum.phone.ui.EtatSante
import com.pendulum.phone.ui.onboarding.DisclaimerPage
import com.pendulum.phone.ui.onboarding.NotificationsPage
import com.pendulum.phone.ui.onboarding.PairingPage
import com.pendulum.phone.ui.onboarding.RequirementsPage
import com.pendulum.phone.ui.onboarding.SleepSourcePage
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.CeSoirUi
import com.pendulum.phone.ui.model.EtatReveil
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.ui.nights.Controle
import com.pendulum.phone.ui.nights.NightDetailScreen
import com.pendulum.phone.ui.nights.NightListScreen
import com.pendulum.phone.ui.nights.NuitDetailUi
import com.pendulum.phone.ui.settings.ReglagesUi
import com.pendulum.phone.ui.settings.SettingsScreen
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.trend.ComparePeriodsScreen
import com.pendulum.phone.ui.home.HomeScreen
import com.pendulum.phone.ui.trend.TonightCard
import com.pendulum.phone.ui.trend.TrendScreen

/**
 * Les apercus Compose et le jeu de donnees qui les alimente.
 *
 * ### Pourquoi ce code est dans `src/debug` et pas dans `src/main`
 *
 * Il y etait, et toute la navigation de l'application etait cablee dessus : une installation
 * neuve ouvrait sur une tendance de sept nuits, un hypnogramme et une source « Samsung Health »
 * pendant que la base restait vide. Le cablage a ete corrige — les ecrans lisent maintenant la
 * base — mais corriger un cablage ne garantit rien contre le prochain.
 *
 * Le source set, si. Ce fichier **n'est pas compile** dans la variante release : y revenir
 * accidentellement ne produit pas un ecran trompeur, il produit une erreur de compilation. C'est
 * la meme difference qu'entre un avertissement et un garde-fou, et ce projet la fait partout
 * ailleurs.
 *
 * La contrepartie est reelle et assumee : Android Studio ne rend les apercus que pour la variante
 * debug. C'est le cas par defaut, et les captures d'ecran de la documentation sont produites
 * depuis un build debug de toute facon.
 */

// --- issu de ui/nights/NightDetailScreen.kt ---------------------------------
/** Jeu de demonstration, partage par les apercus et par le squelette de navigation. */
val apercuNuitDetail = NuitDetailUi(
    nuit = ApercuDonnees.nuits.first(),
    auLit = "7 h 46",
    graphe = ApercuDonnees.nuit,
    hypnogramme = ApercuDonnees.hypnogramme,
    mouvements = 412,
    plms = 278,
    plmw = 64,
    ecartesPosture = 57,
    ecartesDuree = 13,
    series = 31,
    couvertureSeries = "3 h 12",
    imiMedianSec = 23.4,
    controles = listOf(
        Controle(Textes.Nuits.Detail.COUVERTURE, "99.2%", "97%", true),
        Controle(Textes.Nuits.Detail.PLUS_GRAND_TROU, "1.8 s", "5 s", true),
        Controle(Textes.Nuits.Detail.CUMUL_TROUS, "11 s", "120 s", true),
        Controle(Textes.Nuits.Detail.FREQUENCE, "50.21 Hz", "50 Hz", true),
        Controle(Textes.Nuits.Detail.BATTERIE_FIN, "34%", "20%", true),
        Controle(Textes.Nuits.Detail.PORTE, "96.4%", "90%", true),
        Controle(Textes.Nuits.Detail.SOMMEIL_TOTAL, "6 h 58", "4 h", true),
    ),
    regleAppliquee = "AASM v3 · algo 1.4.0 · profile “default”",
)

@Preview(name = "Night — detail", widthDp = 411, heightDp = 1600, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuDetail() = PendulumTheme {
    NightDetailScreen(apercuNuitDetail, {}, {}, {})
}

@Preview(name = "Night — detail without hypnogram", widthDp = 411, heightDp = 1600, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuDetailSansHypno() = PendulumTheme {
    NightDetailScreen(apercuNuitDetail.copy(hypnogramme = ApercuDonnees.hypnogrammeAbsent), {}, {}, {})
}

// --- issu de ui/nights/NightListScreen.kt -----------------------------------
@Preview(name = "Nights — list", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuListe() = PendulumTheme {
    NightListScreen(ApercuDonnees.nuits, {})
}

// --- issu de ui/trend/ComparePeriodsScreen.kt -------------------------------
@Preview(name = "Comparison — not conclusive", widthDp = 411, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuComparaison() = PendulumTheme {
    val a = ApercuDonnees.compte.copy(mediane = 31.0, ciBas = 19.0, ciHaut = 44.0)
    val b = ApercuDonnees.compte
    ComparePeriodsScreen(
        resultat = Aggregat.comparer(a, b, -24.0, 5.0, 11),
        motifIndisponible = null,
        libellePeriodeA = "1–15 February",
        libellePeriodeB = "1–15 March",
    )
}

@Preview(name = "Comparison — refused, too few nights", widthDp = 411, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuComparaisonRefus() = PendulumTheme {
    ComparePeriodsScreen(null, Textes.Comparaison.PERIODE_A to 3, "1–15 February", "1–15 March")
}

// --- issu de ui/trend/TonightCard.kt ----------------------------------------
@Preview(name = "Tonight — context sealed", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuCeSoir() = PendulumTheme {
    TonightCard(ApercuDonnees.ceSoir, motifIndisponible = Textes.CeSoir.SCELLEMENT_FAIT, onSceller = {})
}

@Preview(name = "Tonight — to seal, low battery", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuCeSoirASceller() = PendulumTheme {
    TonightCard(
        ApercuDonnees.ceSoir.copy(batteriePct = 62, contexteScelle = false),
        motifIndisponible = null,
        onSceller = {},
    )
}

// --- issu de ui/home/HomeScreen.kt ------------------------------------------
//
// Les trois cartes, dans les deux etats qui comptent : le soir, quand il reste a sceller, et le
// matin, quand une nuit attend d'etre devoilee.

@Preview(name = "Home - evening, context to seal", widthDp = 411, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAccueilSoir() = PendulumTheme {
    HomeScreen(ApercuDonnees.accueilSoir, {}, {}, {}, {})
}

@Preview(name = "Home - morning, result not shown", widthDp = 411, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAccueilMatin() = PendulumTheme {
    HomeScreen(ApercuDonnees.accueilMatin, {}, {}, {}, {})
}

// --- issu de ui/trend/TrendScreen.kt ----------------------------------------
@Preview(name = "Trend — 6 nights, full screen", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuTendance() = PendulumTheme {
    TrendScreen(ApercuDonnees.tendancePrete, {}, {}, {}, {}, {})
}

@Preview(name = "Trend — refusal below 3 nights", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuTendanceRefus() = PendulumTheme {
    TrendScreen(ApercuDonnees.tendanceRefus, {}, {}, {}, {}, {})
}

@Preview(name = "Trend — provisional 4 nights", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuTendanceProvisoire() = PendulumTheme {
    TrendScreen(
        ApercuDonnees.tendancePrete.copy(
            rythme = ApercuDonnees.rythme.copy(nuits = 4, ciBas = 15.0, ciHaut = 30.0),
            compte = ApercuDonnees.compte.copy(nuits = 4, ciBas = 9.0, ciHaut = 38.0),
            position = Aggregat.position(9.0, 38.0, 4),
            periodiciteQualifiee = null,
        ),
        {}, {}, {}, {}, {},
    )
}

// --- issu de ui/settings/SettingsScreen.kt ----------------------------------
@Preview(name = "Settings", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuReglages() = PendulumTheme {
    SettingsScreen(
        ReglagesUi(
            regle = Textes.Reglages.REGLE_AASM,
            sourcePreferee = "Samsung Health",
            profil = "default",
            repereDePort = "4th hole, right leg",
            arretAutomatique = "On charger",
            montre = "Pixel Watch 3 · 98% · 1.2 GB",
            healthConnect = "Sleep read access granted",
            espaceOccupe = "3.4 GB",
            versionApp = "0.1.0",
            versionAlgo = "1.4.0",
            theme = Textes.Reglages.THEME_SOMBRE,
        ),
        {}, {}, {},
    )
}


// --- issu de ui/onboarding/OnboardingPager.kt -------------------------------
//
// Les apercus de l'assistant vivent ici et non a cote du composable, pour la raison qui ouvre ce
// fichier : ils ont besoin d'un nom de montre et d'une liste de sources, donc de valeurs
// fictives. Les laisser dans `src/main` etait exactement ce qui les avait fait remonter en
// valeurs par defaut de parametres, puis s'afficher a l'utilisateur.

@Preview(name = "Onboarding 1/5 — notice", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAvertissement() = PendulumTheme { DisclaimerPage {} }

@Preview(name = "Onboarding 2/5 — needs", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuBesoins() = PendulumTheme { RequirementsPage {} }

@Preview(name = "Onboarding 3/5 — no watch paired", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAppairageAucuneMontre() = PendulumTheme {
    PairingPage(EtatMontre(EtatAppairage.AUCUNE_MONTRE), null, { true }, {}, {})
}

@Preview(name = "Onboarding 3/5 — watch paired, app missing", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAppairageAppAbsente() = PendulumTheme {
    PairingPage(EtatMontre(EtatAppairage.APP_ABSENTE_OU_HORS_PORTEE, "Pixel Watch 3"), null, { true }, {}, {})
}

@Preview(name = "Onboarding 3/5 — watch found", widthDp = 411, heightDp = 891, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuAppairagePrete() = PendulumTheme {
    PairingPage(EtatMontre(EtatAppairage.PRETE, "Pixel Watch 3"), null, { true }, {}, {})
}

@Preview(name = "Onboarding 4/5 — two sources", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuSourceSommeil() = PendulumTheme {
    SleepSourcePage(
        sante = EtatSante(
            SleepReader.Availability.READY,
            listOf(
                SourcesSommeil.Observee("com.sec.android.app.shealth", 6, true),
                SourcesSommeil.Observee("com.urbandroid.sleep", 2, false),
            ),
        ),
        sourcePreferee = "com.sec.android.app.shealth",
        onRelire = {}, onChoisirSource = {}, onContinuer = {},
    )
}

@Preview(name = "Onboarding 4/5 — no source at all", widthDp = 411, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuSourceSommeilAucune() = PendulumTheme {
    SleepSourcePage(
        sante = EtatSante(SleepReader.Availability.READY, emptyList()),
        sourcePreferee = null,
        onRelire = {}, onChoisirSource = {}, onContinuer = {},
    )
}

@Preview(name = "Onboarding 4/5 — Health Connect absent", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuSourceSommeilSansSdk() = PendulumTheme {
    SleepSourcePage(
        sante = EtatSante(SleepReader.Availability.SDK_UNAVAILABLE, null),
        sourcePreferee = null,
        onRelire = {}, onChoisirSource = {}, onContinuer = {},
    )
}

@Preview(name = "Onboarding 5/5 — notifications and conditions", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuNotifications() = PendulumTheme { NotificationsPage("4th hole", {}, {}) }
