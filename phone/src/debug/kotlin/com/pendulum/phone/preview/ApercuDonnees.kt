package com.pendulum.phone.preview

import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.PorteP1
import com.pendulum.phone.ui.settings.RapportP1Ui
import com.pendulum.phone.ui.home.MachineAccueil
import com.pendulum.phone.ui.home.SessionAccueil
import com.pendulum.phone.ui.home.SourceAccueil
import com.pendulum.phone.ui.model.CeSoirUi
import com.pendulum.phone.ui.model.Drapeau
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.texte
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.EtatReveil
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.model.TendanceUiState

import com.pendulum.phone.ui.chart.EnvelopePyramid
import com.pendulum.phone.ui.chart.EtatPoint
import com.pendulum.phone.ui.chart.EtatPort
import com.pendulum.phone.ui.chart.GenreMarqueur
import com.pendulum.phone.ui.chart.HypnogrammeSpec
import com.pendulum.phone.ui.chart.Intervalle
import com.pendulum.phone.ui.chart.JaugeBatterie
import com.pendulum.phone.ui.chart.MetrologieSpec
import com.pendulum.phone.ui.chart.NiveauDatation
import com.pendulum.phone.ui.chart.PalierDatation
import com.pendulum.phone.ui.chart.LigneReference
import com.pendulum.phone.ui.chart.Marqueur
import com.pendulum.phone.ui.chart.NuitChartSpec
import com.pendulum.phone.ui.chart.PicAnnote
import com.pendulum.phone.ui.chart.PointNuit
import com.pendulum.phone.ui.chart.SegmentStade
import com.pendulum.phone.ui.chart.StadeUi
import com.pendulum.phone.ui.chart.BandeMediane
import com.pendulum.phone.ui.chart.TendanceChartSpec
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

    /** Le fuseau des nuits d'apercu. L'axe calendaire de la tendance en a besoin pour dater. */
    private const val FUSEAU = "Europe/Paris"

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
        zoneId = FUSEAU,
        pivotMs = null,
        descriptionAccessible = descriptionTendance(
            points = 6,
            debut = "6 March",
            fin = "12 March",
            mediane = "21",
            minimum = "18",
            maximum = "26",
            unite = "seconds",
        ),
    )

    val tendanceCompte = tendanceRythme.copy(
        grandeur = Aggregat.Grandeur.COMPTE_HORAIRE,
        points = pointsTendance.map { it.copy(valeur = it.valeur * 1.05f) },
        bandes = listOf(BandeMediane(BASE_MS - 6 * JOUR, BASE_MS, 22f, 14f, 31f, "22/h")),
        reference = LigneReference(15f, "15/h", LEGENDE_SEUIL_15),
        descriptionAccessible = descriptionTendance(
            points = 6,
            debut = "6 March",
            fin = "12 March",
            mediane = "22",
            minimum = "11",
            maximum = "27",
            unite = "movements per hour",
        ),
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
        descriptionAccessible = "Chart of the night of 12 March, 412 movements detected " +
            "between 23:12 and 06:58. Values button for the table.",
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
        texteIndisponible = "Hypnogram unavailable — accelerometer immobility mask used.",
        statistiques = "6 h 58 of sleep · awake 48 min · REM 1 h 24 · N3 1 h 02 · " +
            "source Samsung Health via Health Connect · agreement with the accelerometer mask: κ = 0.71",
    )

    val hypnogrammeAbsent = hypnogramme.copy(stades = null, desaccord = emptyList())

    // ---------------------------------------------------------------------------------
    // Etat de l'appareil — la troisieme bande
    // ---------------------------------------------------------------------------------

    /**
     * Une nuit de metrologie **volontairement imparfaite**.
     *
     * Un apercu ou tout va bien ne permet de juger aucune des trois choses que cette bande doit
     * rendre lisibles : que la datation se degrade par paliers, que l'ecretage se produit par
     * salves et non en continu, et qu'une jauge non tenue se distingue d'une jauge tenue **sans la
     * couleur**. Il y a donc ici une heure sous chargeur au debut, une periode ou le capteur a
     * ecrete, une degradation de gigue au milieu de la nuit, et une batterie qui ne passe pas le
     * critere — c'est-a-dire une jauge hachuree.
     *
     * Le pluriel des voies est aussi ce qui montre la gouttiere a l'echelle : sur une capture, la
     * separation doit sauter aux yeux avant qu'on ait lu une seule etiquette.
     */
    val metrologie = MetrologieSpec(
        debutMs = DEBUT_NUIT,
        finMs = FIN_NUIT,
        etatPort = EtatPort.RETIRE,
        horsPoignet = listOf(
            Intervalle(DEBUT_NUIT, DEBUT_NUIT + 12 * 60_000L),
            Intervalle(FIN_NUIT - 22 * 60_000L, FIN_NUIT),
        ),
        charge = listOf(Intervalle(DEBUT_NUIT, DEBUT_NUIT + 58 * 60_000L)),
        datation = buildList {
            var t = DEBUT_NUIT
            var i = 0
            while (t < FIN_NUIT) {
                val fin = minOf(t + 37 * 60_000L, FIN_NUIT)
                val niveau = when {
                    i in 4..5 -> NiveauDatation.GROSSIERE
                    i % 3 == 2 -> NiveauDatation.MOYENNE
                    else -> NiveauDatation.FINE
                }
                add(PalierDatation(t, fin, niveau))
                t = fin
                i++
            }
        },
        // Deux salves : l'ecretage n'est jamais uniforme, il suit les mouvements amples.
        ecretage = buildList {
            var t = DEBUT_NUIT + 62 * 60_000L
            while (t < DEBUT_NUIT + 78 * 60_000L) { add(t); t += 60_000L }
            t = DEBUT_NUIT + 196 * 60_000L
            while (t < DEBUT_NUIT + 203 * 60_000L) { add(t); t += 60_000L }
        },
        gels = listOf(
            DEBUT_NUIT + 41 * 60_000L,
            DEBUT_NUIT + 154 * 60_000L,
            DEBUT_NUIT + 155 * 60_000L,
            DEBUT_NUIT + 302 * 60_000L,
        ),
        batterie = JaugeBatterie(
            fraction = 0.34f,
            tenue = false,
            libelle = "34% at the end of the night  ·  12% projected at 8 h",
        ),
        points = 468,
        texteIndisponible = "No device telemetry for this night — recorded before the watch sent any.",
        descriptionAccessible = descriptionMetrologie(
            points = 468,
            gigue = "1.4 ms",
            ecretes = 1_204,
            gels = 4,
            batterie = "34% at the end of the night",
        ),
    )

    /** La meme nuit, mais sans un seul point : c'est ce que rend une nuit d'avant la telemetrie. */
    val metrologieAbsente = metrologie.copy(points = 0)

    // ---------------------------------------------------------------------------------
    // Nuits
    // ---------------------------------------------------------------------------------

    val nuits: List<NuitUi> = listOf(
        NuitUi(
            "a7", "12 March", "Fri", "23:12", "06:58", "6 h 58 of sleep", texte(R.string.settings_health_connect),
            EtatNuit.ELIGIBLE, null, 18.7, 24.0,
            listOf(Drapeau(texte("gap 47 s"))), BASE_MS,
            // Devoilee : c'est l'etat d'une nuit dont on a demande le resultat.
            devoileeAtMs = BASE_MS + 7 * 3_600_000L,
        ),
        // Rythme nul : l'ajustement a ete refuse, ce qui est le cas le plus frequent. L'apercu le
        // porte parce que c'est cette ligne-la que la liste affichera le plus souvent, et qu'un
        // jeu d'apercu ou toutes les nuits ont un rythme donne une idee fausse de l'ecran.
        NuitUi(
            "a6", "11 March", "Thu", "23:41", "07:04", "7 h 02 of sleep", texte(R.string.settings_health_connect),
            EtatNuit.PROVISOIRE, null, null, 19.0,
            listOf(Drapeau(texte("accelerometer mask"))), BASE_MS - JOUR,
        ),
        NuitUi(
            "a4", "09 March", "Tue", "00:12", "05:22", "2 h 10 of sleep", texte(R.string.settings_accel_mask_only),
            EtatNuit.ECARTEE, Mapping.motif("TOO_SHORT"), 28.1, 41.0,
            listOf(Drapeau(texte("off-body 12%")), Drapeau(texte("battery 8%"))), BASE_MS - 3 * JOUR,
        ),
    )

    val ceSoir = CeSoirUi(
        // Volontairement nuls : rien, cote telephone, ne lit encore la batterie ni l'espace
        // libre de la montre. L'apercu doit montrer les tirets que l'application affiche, pas
        // le chiffre qu'on aimerait y voir un jour.
        batteriePct = null,
        espaceLibre = null,
        bracelet = "4th hole",
        jambe = texte(R.string.tonight_leg_right),
        sourceSommeil = texte("Samsung Health"),
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
            sourceSommeil = texte("Samsung Health"),
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
            jambeScellee = texte(R.string.tonight_leg_right),
            repereDeSerrage = "4th hole",
            sourceSommeil = texte("Samsung Health"),
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
        regle = texte("AASM v3 (5–90 s)"),
        masque = texte("Health Connect (5/6 nights)"),
        plmw = 9.0,
        reveil = EtatReveil.Provisoire("12 March", "07:12", "08:12", "19:04", abandonne = false),
        profilPersonnalise = null,
        hashsMelanges = false,
        questionnaireEtat = texte(R.string.quiz_not_filled),
        exportPossible = true,
    )

    val tendanceRefus = TendanceUiState.Refus(
        nuitsEligibles = 2,
        nuitsRythmeAjuste = 2,
        nuitsRequises = Aggregat.MIN_NUITS_AGREGAT,
        nuitsEnregistrees = nuits.take(2),
        reveil = EtatReveil.Rien,
    )

    /**
     * Le second motif de refus : les nuits sont la, le rythme non.
     *
     * C'est le cas **frequent** — 2 ajustements acceptes sur 20 nuits nominales — et l'apercu
     * existe parce qu'un ecran qu'on ne voit jamais en maquette est un ecran qu'on redige a
     * l'aveugle. Neuf nuits eligibles, deux rythmes identifies.
     */
    val tendanceRefusRythme = tendanceRefus.copy(
        nuitsEligibles = 9,
        nuitsRythmeAjuste = 2,
        nuitsEnregistrees = nuits,
    )

    // ---------------------------------------------------------------------------------
    // Porte P1
    // ---------------------------------------------------------------------------------

    /**
     * Trois nuits de campagne, dont une hors criteres.
     *
     * Le jeu passe par de **vraies** lignes de `night_session` et par [PorteP1] plutot que par des
     * verdicts ecrits a la main : un apercu qui court-circuite le calcul montre l'ecran et ne dit
     * rien de ce que l'ecran affichera. Deux nuits conformes de suite et une troisieme sous la
     * couverture — la porte n'est donc pas franchie, et c'est le cas qu'il faut savoir lire.
     */
    val rapportP1: RapportP1Ui = run {
        val verdicts = listOf(
            nuitP1(jours = 0, heures = 8.2, couverture = 0.994, batterie = 34, fs = 50.31),
            nuitP1(jours = 1, heures = 8.05, couverture = 0.992, batterie = 27, fs = 50.28),
            nuitP1(jours = 2, heures = 6.1, couverture = 0.978, batterie = 41, fs = 50.31),
        ).map { PorteP1.de(it) }
        RapportP1Ui(nuits = verdicts, campagne = PorteP1.campagne(verdicts))
    }

    private fun nuitP1(
        jours: Long,
        heures: Double,
        couverture: Double,
        batterie: Int,
        fs: Double,
    ): NightSessionEntity {
        val debut = BASE_MS - jours * JOUR + 22 * 3_600_000L // 23 h locales, heure d'hiver
        val dureeMs = (heures * 3_600_000L).toLong()
        val cadence = 50
        return NightSessionEntity(
            sessionHex = "p1$jours",
            startWallMs = debut,
            plannedStopWallMs = debut + 8 * 3_600_000L,
            endWallMs = debut + dureeMs,
            zoneId = FUSEAU,
            tzOffsetStartMin = 60,
            tzOffsetEndMin = 60,
            nominalRateHz = cadence,
            modeFlags = 0,
            state = "CLOSED",
            batteryPctLast = batterie,
            // Sans cette date la couverture est `null` — une nuit non analysee n'a pas de
            // numerateur — et l'apercu montrerait trois tirets a la place des trois chiffres qu'il
            // existe pour montrer. C'est le comportement voulu du produit, pas un contournement :
            // ces trois nuits d'apercu **ont** ete analysees.
            analyzedAtMs = debut + dureeMs + 1_800_000L,
            fsMeasuredHz = fs,
            sampleCount = (dureeMs * cadence / 1000.0 * couverture).toLong(),
            gapCount = 3,
            gapTotalMs = 4_200L,
        )
    }
}

/**
 * Les textes que les `Spec` de graphe portent **deja resolus**.
 *
 * Ils sont ecrits en clair ici et non lus dans les ressources : un apercu Compose n'a pas de
 * `Context` d'application, et surtout ce jeu de donnees existe pour montrer l'ecran, pas pour
 * verifier le cablage — c'est `ui/TextesTest.kt` qui verifie que chaque identifiant a sa chaine.
 */
private fun descriptionTendance(
    points: Int,
    debut: String,
    fin: String,
    mediane: String,
    minimum: String,
    maximum: String,
    unite: String,
) = "Trend, $points points, from $debut to $fin, median $mediane $unite, values from " +
    "$minimum to $maximum $unite, points not joined. Values button for the table."

private fun descriptionMetrologie(
    points: Int,
    gigue: String,
    ecretes: Int,
    gels: Int,
    batterie: String,
) = "Device state, $points points, one per minute. Median timing dispersion $gigue. " +
    "$ecretes samples at the sensor range limit. $gels write freezes longer than one " +
    "sampling period. Battery: $batterie. These lanes describe the recorder, not the " +
    "sleeper: none of them caused the movements shown above."

private const val LEGENDE_SEUIL_15 =
    "15/h — above this, periodic limb movements are usually counted as frequent in " +
        "polysomnography (ICSD-3). Many physicians are not concerned below it."
