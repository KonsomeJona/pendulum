package com.pendulum.phone.preview

import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.home.MachineAccueil
import com.pendulum.phone.ui.home.SessionAccueil
import com.pendulum.phone.ui.home.SourceAccueil
import com.pendulum.phone.ui.model.CeSoirUi
import com.pendulum.phone.ui.model.Drapeau
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.EtatReveil
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.model.TendanceUiState

import com.pendulum.phone.ui.chart.EnvelopePyramid
import com.pendulum.phone.ui.chart.EtatPoint
import com.pendulum.phone.ui.chart.GenreMarqueur
import com.pendulum.phone.ui.chart.HypnogrammeSpec
import com.pendulum.phone.ui.chart.Intervalle
import com.pendulum.phone.ui.chart.LigneReference
import com.pendulum.phone.ui.chart.Marqueur
import com.pendulum.phone.ui.chart.NuitChartSpec
import com.pendulum.phone.ui.chart.PicAnnote
import com.pendulum.phone.ui.chart.PointNuit
import com.pendulum.phone.ui.chart.SegmentStade
import com.pendulum.phone.ui.chart.StadeUi
import com.pendulum.phone.ui.chart.BandeMediane
import com.pendulum.phone.ui.chart.TendanceChartSpec
import com.pendulum.phone.ui.text.Textes
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Jeux de donnees realistes pour les `@Preview`.
 *
 * Ces apercus ne servent pas seulement a developper : ce sont eux qui produiront les captures
 * d'ecran de la documentation et de la revue. Des donnees vraisemblables sont donc une exigence,
 * pas un confort — une capture avec « Lorem ipsum » et trois points alignes ne permet de juger ni
 * la lisibilite d'un intervalle, ni le comportement de l'axe quand une nuit est ecartee.
 *
 * Les valeurs sont calees sur l'ordre de grandeur attendu : rythme fondamental autour de 21 s
 * (Skeba 2016 : IMI log-normal, moyenne du log stable a 3,6 % pres d'une nuit a l'autre), compte
 * horaire autour de 22/h, intervalle de confiance large parce qu'il l'est reellement sur six
 * nuits.
 */
object ApercuDonnees {

    private const val JOUR = 86_400_000L
    private const val BASE_MS = 1_741_737_600_000L // 12 mars, minuit UTC — repere stable

    // ---------------------------------------------------------------------------------
    // Tendance
    // ---------------------------------------------------------------------------------

    val rythme = Aggregat.Resultat(
        grandeur = Aggregat.Grandeur.RYTHME_SECONDES,
        mediane = 21.0,
        ciBas = 18.0,
        ciHaut = 25.0,
        nuits = 6,
        dispersion = 2.4,
        mdc95 = Aggregat.mdc95(2.4, 6),
    )

    val compte = Aggregat.Resultat(
        grandeur = Aggregat.Grandeur.COMPTE_HORAIRE,
        mediane = 22.0,
        ciBas = 14.0,
        ciHaut = 31.0,
        nuits = 6,
        dispersion = 12.0,
        mdc95 = Aggregat.mdc95(12.0, 6),
    )

    /**
     * Sept nuits, dont une ecartee — dessinee quand meme, en cercle creux, a sa valeur. Cacher
     * la nuit ratee donnerait une image plus propre et une lecture fausse.
     */
    val pointsTendance: List<PointNuit> = listOf(
        PointNuit("a1", BASE_MS - 6 * JOUR, 23.4f, EtatPoint.ELIGIBLE),
        PointNuit("a2", BASE_MS - 5 * JOUR, 19.8f, EtatPoint.ELIGIBLE),
        PointNuit("a3", BASE_MS - 4 * JOUR, 21.2f, EtatPoint.MASQUE_ACCELERO),
        PointNuit("a4", BASE_MS - 3 * JOUR, 28.1f, EtatPoint.ECARTEE),
        PointNuit("a5", BASE_MS - 2 * JOUR, 20.6f, EtatPoint.ELIGIBLE),
        PointNuit("a6", BASE_MS - 1 * JOUR, 22.9f, EtatPoint.ELIGIBLE),
        PointNuit("a7", BASE_MS, 18.7f, EtatPoint.ELIGIBLE),
    )

    val tendanceRythme = TendanceChartSpec(
        grandeur = Aggregat.Grandeur.RYTHME_SECONDES,
        points = pointsTendance,
        bandes = listOf(
            BandeMediane(BASE_MS - 6 * JOUR, BASE_MS, 21f, 18f, 25f, "21 s"),
        ),
        // Pas de ligne de reference sur le rythme : aucun seuil publie ne s'y applique.
        reference = null,
        premierJourMs = BASE_MS - 6 * JOUR,
        dernierJourMs = BASE_MS,
        pivotMs = null,
        descriptionAccessible = Textes.Graphes.descriptionTendance(6, "seconds"),
    )

    val tendanceCompte = tendanceRythme.copy(
        grandeur = Aggregat.Grandeur.COMPTE_HORAIRE,
        points = pointsTendance.map { it.copy(valeur = it.valeur * 1.05f) },
        bandes = listOf(BandeMediane(BASE_MS - 6 * JOUR, BASE_MS, 22f, 14f, 31f, "22/h")),
        reference = LigneReference(15f, "15/h", Textes.Graphes.LEGENDE_SEUIL_15),
        descriptionAccessible = Textes.Graphes.descriptionTendance(6, "movements per hour"),
    )

    // ---------------------------------------------------------------------------------
    // Nuit
    // ---------------------------------------------------------------------------------

    private const val DEBUT_NUIT = BASE_MS + 23 * 3_600_000L + 12 * 60_000L
    private const val FIN_NUIT = DEBUT_NUIT + 7 * 3_600_000L + 46 * 60_000L

    /**
     * Enveloppe synthetique : bruit de fond a ×1, salves periodiques a ~22 s, et un pic isole
     * a ×61 — celui-la sert precisement a verifier que la decimation min/max ne l'efface pas.
     */
    private val enveloppe: FloatArray = FloatArray(28_000) { i ->
        val rng = Random(i / 500)
        val fond = 1f + 0.15f * abs(sin(i * 0.017f)) + rng.nextFloat() * 0.05f
        val salve = if ((i / 55) % 40 < 6) 6f + 4f * abs(sin(i * 0.9f)) else 0f
        val pic = if (i == 12_040) 60f else 0f
        fond + salve + pic
    }

    val nuit = NuitChartSpec(
        debutMs = DEBUT_NUIT,
        finMs = FIN_NUIT,
        pyramide = EnvelopePyramid(enveloppe),
        pasEnveloppeMs = 1_000L,
        plancher = FloatArray(280) { 1f },
        seuilOnset = FloatArray(280) { 8f },
        pasSerieMs = 100_000L,
        horsSommeil = listOf(
            Intervalle(DEBUT_NUIT, DEBUT_NUIT + 18 * 60_000L),
            Intervalle(FIN_NUIT - 26 * 60_000L, FIN_NUIT),
        ),
        trous = listOf(Intervalle(DEBUT_NUIT + 3 * 3_600_000L, DEBUT_NUIT + 3 * 3_600_000L + 47_000L)),
        marqueurs = buildList {
            var t = DEBUT_NUIT + 40 * 60_000L
            var n = 0
            while (t < FIN_NUIT - 30 * 60_000L) {
                val genre = when {
                    n % 13 == 0 -> GenreMarqueur.EXCLU_POSTURE
                    n % 7 == 0 -> GenreMarqueur.EN_EVEIL
                    else -> GenreMarqueur.COMPTE
                }
                add(Marqueur(t, genre, if (genre == GenreMarqueur.COMPTE) n / 6 else null))
                t += 22_400L + (n % 5) * 900L
                n++
            }
        },
        series = listOf(
            Intervalle(DEBUT_NUIT + 55 * 60_000L, DEBUT_NUIT + 78 * 60_000L),
            Intervalle(DEBUT_NUIT + 190 * 60_000L, DEBUT_NUIT + 236 * 60_000L),
        ),
        pic = PicAnnote(61f, "03:12"),
        descriptionAccessible = Textes.Graphes.descriptionNuit("12 March", 412, "23:12", "06:58"),
    )

    val hypnogramme = HypnogrammeSpec(
        debutMs = DEBUT_NUIT,
        finMs = FIN_NUIT,
        stades = buildList {
            var t = DEBUT_NUIT
            val cycle = listOf(
                StadeUi.EVEIL to 18, StadeUi.N1 to 12, StadeUi.N2 to 42, StadeUi.N3 to 38,
                StadeUi.N2 to 20, StadeUi.REM to 24,
            )
            var i = 0
            while (t < FIN_NUIT) {
                val (stade, minutes) = cycle[i % cycle.size]
                val fin = minOf(t + minutes * 60_000L, FIN_NUIT)
                add(SegmentStade(t, fin, stade))
                t = fin
                i++
            }
        },
        immobiliteAccelero = listOf(
            Intervalle(DEBUT_NUIT + 20 * 60_000L, DEBUT_NUIT + 150 * 60_000L),
            Intervalle(DEBUT_NUIT + 165 * 60_000L, FIN_NUIT - 30 * 60_000L),
        ),
        desaccord = listOf(Intervalle(DEBUT_NUIT + 150 * 60_000L, DEBUT_NUIT + 165 * 60_000L)),
        texteIndisponible = Textes.Graphes.HYPNO_INDISPONIBLE,
        statistiques = "6 h 58 of sleep · awake 48 min · REM 1 h 24 · N3 1 h 02 · " +
            "source Samsung Health via Health Connect · agreement with the accelerometer mask: κ = 0.71",
    )

    val hypnogrammeAbsent = hypnogramme.copy(stades = null, desaccord = emptyList())

    // ---------------------------------------------------------------------------------
    // Nuits
    // ---------------------------------------------------------------------------------

    val nuits: List<NuitUi> = listOf(
        NuitUi(
            "a7", "12 March", "Fri", "23:12", "06:58", "6 h 58 of sleep", "Health Connect",
            EtatNuit.ELIGIBLE, null, 18.7, 24.0,
            listOf(Drapeau("gap 47 s")), BASE_MS,
            // Devoilee : c'est l'etat d'une nuit dont on a demande le resultat.
            devoileeAtMs = BASE_MS + 7 * 3_600_000L,
        ),
        NuitUi(
            "a6", "11 March", "Thu", "23:41", "07:04", "7 h 02 of sleep", "Health Connect",
            EtatNuit.PROVISOIRE, null, 22.9, 19.0,
            listOf(Drapeau("accelerometer mask")), BASE_MS - JOUR,
        ),
        NuitUi(
            "a4", "09 March", "Tue", "00:12", "05:22", "2 h 10 of sleep", "accelerometer mask",
            EtatNuit.ECARTEE, Textes.Nuits.motif("TOO_SHORT"), 28.1, 41.0,
            listOf(Drapeau("off-body 12%"), Drapeau("battery 8%")), BASE_MS - 3 * JOUR,
        ),
    )

    val ceSoir = CeSoirUi(
        // Volontairement nuls : rien, cote telephone, ne lit encore la batterie ni l'espace
        // libre de la montre. L'apercu doit montrer les tirets que l'application affiche, pas
        // le chiffre qu'on aimerait y voir un jour.
        batteriePct = null,
        espaceLibre = null,
        bracelet = "4th hole",
        jambe = Textes.CeSoir.JAMBE_DROITE,
        sourceSommeil = "Samsung Health",
        sourceActive = null,
        contexteScelle = true,
    )

    // ---------------------------------------------------------------------------------
    // Accueil
    // ---------------------------------------------------------------------------------

    /** Le soir : rien n'est en vol, le contexte reste a sceller. */
    val accueilSoir = MachineAccueil.de(
        SourceAccueil(
            sessionRecente = null,
            contexteScelle = false,
            jambeScellee = null,
            repereDeSerrage = "4th hole",
            sourceSommeil = "Samsung Health",
            nuitsEnregistrees = 7,
            nuitsEligibles = 6,
            derniereNuitAnalysee = nuits.first(),
        ),
        heureLocale = 22,
    )

    /** Le matin : la nuit est close et scoree, et son resultat n'a pas ete demande. */
    val accueilMatin = MachineAccueil.de(
        SourceAccueil(
            sessionRecente = SessionAccueil(
                sessionHex = "a7",
                etat = "CLOSED",
                analysee = true,
                debutLisible = "23:12",
                dateLisible = "12 March",
            ),
            contexteScelle = true,
            jambeScellee = Textes.CeSoir.JAMBE_DROITE,
            repereDeSerrage = "4th hole",
            sourceSommeil = "Samsung Health",
            nuitsEnregistrees = 7,
            nuitsEligibles = 6,
            derniereNuitAnalysee = nuits.first().copy(devoileeAtMs = null),
        ),
        heureLocale = 7,
    )

    val tendancePrete = TendanceUiState.Pret(
        rythme = rythme,
        compte = compte,
        position = Aggregat.position(compte.ciBas, compte.ciHaut, compte.nuits),
        periodiciteQualifiee = Aggregat.qualifierPeriodicite(0.71, 6),
        tauxManques = 0.31,
        graphe = tendanceRythme,
        nuitsEnregistrees = 7,
        nuitsEligibles = 6,
        nuitsEcartees = 1,
        regle = "AASM v3 (5–90 s)",
        masque = "Health Connect (5/6 nights)",
        plmw = 9.0,
        reveil = EtatReveil.Provisoire("12 March", "07:12", "08:12"),
        profilPersonnalise = null,
        hashsMelanges = false,
        questionnaireEtat = Textes.Questionnaire.NON_REMPLI,
        exportPossible = true,
    )

    val tendanceRefus = TendanceUiState.Refus(
        nuitsEligibles = 2,
        nuitsRequises = Aggregat.MIN_NUITS_AGREGAT,
        nuitsEnregistrees = nuits.take(2),
        reveil = EtatReveil.Rien,
    )
}
