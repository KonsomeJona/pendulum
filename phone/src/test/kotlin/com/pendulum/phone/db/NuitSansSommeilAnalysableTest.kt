package com.pendulum.phone.db

import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.PublicationGate
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.FloorMode
import com.pendulum.phone.R
import com.pendulum.phone.ui.Ressources
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.CheminDeCalcul
import com.pendulum.phone.ui.model.Controles
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.text.texte
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Le defaut qui faisait disparaitre une nuit entiere, sans un mot.**
 *
 * `safeRate` rend `Double.NaN` quand le denominateur n'existe pas, et c'est la bonne decision :
 * un taux sans denominateur n'est pas zero, il n'existe pas. Mais SQLite ne connait pas `NaN` —
 * `sqlite3_bind_double` d'un `NaN` **ecrit un `NULL`**. Une colonne `NOT NULL` refuse alors
 * l'insertion :
 *
 * ```
 * android.database.sqlite.SQLiteConstraintException: NOT NULL constraint failed: plm_result.plmi
 * ```
 *
 * `AnalyzeWorker` attrape et rend `Result.retry()`. La nuit est donc reessayee sans fin, ne
 * s'analyse jamais, et n'apparait nulle part — `analyzedAtMs` reste vide. Mesure sur deux
 * enregistrements reels du Pixel Fold : transferes entiers, verifies au CRC, invisibles.
 *
 * Ce n'est pas un cas limite. C'est le chemin **par defaut** de tout utilisateur sans
 * hypnogramme Health Connect : sans sommeil analysable, six taux valent `NaN` d'un coup, et
 * `Rhythm.emptyFit` en ajoute quatre autres — un ajustement de rythme refuse est lui aussi le
 * cas frequent (2 acceptes sur 20 nuits nominales).
 *
 * La reparation ne remplace **jamais** le `NaN` par zero : afficher « 0 mouvement par heure »
 * pour une nuit ou l'on n'a rien pu mesurer serait un mensonge clinique pire que l'absence. Elle
 * rend la colonne nullable, et `null` se lit « on ne sait pas » jusqu'au tiret de l'ecran.
 */
class NuitSansSommeilAnalysableTest {

    /**
     * Les colonnes de `plm_result` que `:algo` peut rendre `NaN`, et qui doivent donc accepter
     * `NULL` en base. Six viennent de `safeRate` (`Indices.kt`), quatre de `Rhythm.emptyFit`.
     *
     * `periodicityIndex` n'y est pas et n'a rien a y faire : `Periodicity.fromIntervals` rend
     * `0.0` quand il n'y a aucun intervalle, et porte son propre `valid`.
     */
    private val grandeursQuiPeuventNePasExister = listOf(
        "plmi", "plmiSpt", "plmw", "plmiFirstHalf", "plmiSecondHalf", "plmiRespWorstCase",
        "fundamentalSec", "muLog", "sigmaLog", "missRate",
    )

    @Test
    @DisplayName("une nuit sans sommeil analysable produit bien des taux inexistants")
    fun `sans denominateur les taux valent NaN`() {
        val r = resultatSansSommeil()

        assertThat(r.plmi).isNaN()
        assertThat(r.plmiSpt).isNaN()
        assertThat(r.plmiRespWorstCase).isNaN()
        // Et la porte de publication le dit deja : cette nuit ne porte aucun index.
        assertThat(r.gate).isEqualTo(PublicationGate.NO_PLMI)
    }

    /**
     * Le test se fait sur le **type de retour de l'accesseur**, et non sur une annotation : c'est
     * exactement ce que Room lit pour decider de la contrainte. Un `Double` non nul est compile en
     * `double` primitif et donne une colonne `NOT NULL` ; un `Double?` devient `java.lang.Double`
     * et donne une colonne qui accepte `NULL`. Le primitif est donc la signature du defaut.
     */
    @Test
    @DisplayName("chaque grandeur qui peut ne pas exister accepte NULL en base")
    fun `les colonnes NaN sont nullables`() {
        for (nom in grandeursQuiPeuventNePasExister) {
            val accesseur = PlmResultEntity::class.java
                .getDeclaredMethod("get" + nom.replaceFirstChar { it.uppercase() })
            assertThat(accesseur.returnType.isPrimitive)
                .withFailMessage(
                    "`plm_result.%s` est declaree NOT NULL alors que `:algo` peut y mettre un " +
                        "`NaN`. SQLite convertit ce `NaN` en `NULL` a la liaison, l'insertion " +
                        "leve `SQLiteConstraintException: NOT NULL constraint failed: " +
                        "plm_result.%s`, `AnalyzeWorker` rend `retry()` — et la nuit n'apparait " +
                        "jamais. Rendre la colonne nullable ; surtout pas ecrire un zero, qui " +
                        "annoncerait une mesure la ou il n'y en a pas.",
                    nom, nom,
                )
                .isFalse()
        }
    }

    /**
     * Le coeur de la reparation : cette nuit doit produire une ligne **insérable**, dont chaque
     * grandeur absente est `null` — jamais zero.
     */
    @Test
    @DisplayName("la ligne persistee porte des NULL, et surtout pas des zeros")
    fun `le resultat est persistable et se relit avec null`() {
        val ligne = ligneSansSommeil()

        assertThat(
            listOf(
                ligne.plmi, ligne.plmiSpt, ligne.plmw,
                ligne.plmiFirstHalf, ligne.plmiSecondHalf, ligne.plmiRespWorstCase,
                ligne.fundamentalSec, ligne.muLog, ligne.sigmaLog, ligne.missRate,
            )
        ).containsOnlyNulls()

        // `containsOnlyNulls` porte tout le poids du test : la tentation naturelle en corrigeant
        // ce defaut est d'ecrire `0.0`, qui inserait sans broncher — et annoncerait « aucun
        // mouvement par heure » pour une nuit ou rien n'a pu etre mesure.

        // Ce qui est **compte**, lui, existe et reste ecrit : le compte de mouvements n'a pas de
        // denominateur, donc rien ne l'empeche de valoir zero pour de bon.
        assertThat(ligne.plmsCount).isZero()
        assertThat(ligne.gate).isEqualTo(PublicationGate.NO_PLMI.name)
        assertThat(ligne.rhythmValid).isFalse()
    }

    @Test
    @DisplayName("elle n'entre ni dans les nuits eligibles ni dans une mediane")
    fun `une nuit sans index ne compte dans aucun agregat`() {
        // Eligibilite : `TrendDao.trendPoints` filtre `gate = 'FULL'`, et cette nuit est
        // `NO_PLMI`. La requete exige en plus `plmi IS NOT NULL`, pour que la porte de
        // publication et l'existence du chiffre restent deux conditions distinctes.
        assertThat(ligneSansSommeil().gate).isNotEqualTo(PublicationGate.FULL.name)

        // Mediane : meme si une telle nuit franchissait la porte, elle sort de l'agregat avant
        // d'etre comptee. Deux nuits mesurees plus une sans index ne font pas trois nuits.
        val nuits = listOf(
            nuitUi(hex = "a", plmi = 18.0),
            nuitUi(hex = "b", plmi = 22.0),
            nuitUi(hex = "sans", plmi = null),
        )
        assertThat(Mapping.agregat(Aggregat.Grandeur.COMPTE_HORAIRE, nuits) { it.plmi })
            .withFailMessage(
                "Une nuit sans index a ete comptee comme nuit eligible. Sous %d nuits mesurees, " +
                    "il ne doit exister aucun agregat — et une nuit qui ne porte aucun chiffre " +
                    "n'en est pas une.",
                Aggregat.MIN_NUITS_AGREGAT,
            )
            .isNull()

        // Et avec trois nuits mesurees, la mediane ne voit que celles-la.
        val avecTrois = nuits + nuitUi(hex = "c", plmi = 20.0)
        val r = Mapping.agregat(Aggregat.Grandeur.COMPTE_HORAIRE, avecTrois) { it.plmi }
        assertThat(r).isNotNull
        assertThat(r!!.nuits).isEqualTo(3)
        assertThat(r.mediane).isEqualTo(20.0)
    }

    @Test
    @DisplayName("les ecrans rendent un tiret, jamais un zero")
    fun `l absence se lit comme une absence`() {
        val ligne = ligneSansSommeil()
        val nuit = nuitUi(hex = "sans", plmi = null)

        // La liste et le detail : le compte horaire et le rythme.
        assertThat(Mapping.compteLisible(nuit.plmi)).isEqualTo(Mapping.TIRET)
        assertThat(Mapping.rythmeSec(nuit)).isNull()
        assertThat(Mapping.nuitUi(nuit, finWallMs = null, sourceSommeil = texte("x")).comptePlmi)
            .isNull()

        // Le bloc « pourquoi ce chiffre » : il reste affiche — ses lignes disent ou le calcul
        // s'est arrete — mais son titre ne nomme aucun chiffre et ses valeurs absentes sont des
        // tirets.
        val bloc = requireNotNull(
            CheminDeCalcul.de(
                n = nuit,
                resultat = ligne,
                dureeEnregistreeMin = 42.0,
                mouvementsRetenus = 0,
                regle = texte("AASM"),
                sourceSommeil = texte("Pendulum"),
            )
        )
        assertThat(Ressources.resoudre(bloc.titre))
            .isEqualTo(Ressources.lire(R.string.night_why_title_no_index))
        assertThat(Ressources.resoudre(bloc.lignes[5].valeur)).isEqualTo(Mapping.TIRET)
        assertThat(Ressources.resoudre(bloc.lignes[6].valeur)).isEqualTo(Mapping.TIRET)

        // La table de qualite : tiret **et** etat inconnu. Un `✓` ou un `✗` sur une valeur
        // absente affirmerait un controle qui n'a pas eu lieu.
        val tauxManques = Controles.de(session(), nuit, ligne, texte("Pendulum"))
            .single { it.libelle == texte(R.string.night_detail_missed_rate) }
        assertThat(Ressources.resoudre(tauxManques.valeur)).isEqualTo(Mapping.TIRET)
        assertThat(tauxManques.ok).isNull()
    }

    // -------------------------------------------------------------------------------------

    private fun ligneSansSommeil() = PlmResultEntity.depuis(
        sessionHex = "abcd",
        paramsHash = "h",
        computedAtMs = 0L,
        algoVersion = "1.4.0",
        r = resultatSansSommeil(),
    )

    private fun nuitUi(hex: String, plmi: Double?) = ComparableNight(
        sessionHex = hex,
        startWallMs = 1_700_000_000_000L,
        zoneId = "Europe/Paris",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = "ACCEL_IMMOBILITY",
        gate = if (plmi == null) PublicationGate.NO_PLMI.name else "FULL",
        independence = "CIRCULAR",
        plmi = plmi,
        plmiSpt = plmi,
        fundamentalSec = null,
        rhythmValid = false,
        periodicityIndex = 0.0,
        missRate = null,
        analysableTstMin = if (plmi == null) 0.0 else 312.0,
        analysableMin = 42.0,
        truncated = false,
        revealedAtMs = null,
        comparable = plmi != null,
        exclusionReason = ComparabilityRule.OK,
    )

    private fun session() = NightSessionEntity(
        sessionHex = "abcd",
        startWallMs = 1_700_000_000_000L,
        plannedStopWallMs = 1_700_028_800_000L,
        zoneId = "Europe/Paris",
        tzOffsetStartMin = 60,
        tzOffsetEndMin = 60,
        nominalRateHz = 50,
        modeFlags = 0,
        state = "CLOSED",
    )

    /** Un masque sans une seule minute de sommeil analysable : le cas par defaut sans Health Connect. */
    private fun resultatSansSommeil() = Plmi.compute(
        clms = emptyList(),
        series = emptyList(),
        mask = SleepMask(
            windows = emptyList(),
            source = MaskSource.ACCEL_IMMOBILITY,
            sptMin = 0.0,
            tstMin = 0.0,
            wasoMin = 0.0,
            analysableTstMin = 0.0,
            analysableSptMin = 0.0,
            corrected = false,
            lagAppliedMs = 0L,
            independence = DenominatorIndependence.CIRCULAR,
            fixedPointConverged = true,
        ),
        fsHz = 50.0,
        rule = SeriesRule.AASM_V3,
        pi = PiResult(periodicityIndex = 0.0, valid = false, totalIntervals = 0, lmRatePerHour = 0.0),
        rhythm = RhythmResult(
            fundamentalSec = Double.NaN,
            muLog = Double.NaN,
            sigmaLog = Double.NaN,
            missRate = Double.NaN,
            harmonicWeights = DoubleArray(0),
            alternationSuspect = false,
            intervalsUsed = 0,
            converged = false,
            valid = false,
        ),
        floorMode = FloorMode.BILATERAL,
        truncated = false,
        truncatedSeriesDropped = 0,
        paramsHash = "h",
    )
}
