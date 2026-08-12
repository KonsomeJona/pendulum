package com.pendulum.phone.banc

import android.content.Context
import android.util.Log
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.Stage
import com.pendulum.algo.synth.GroundTruth
import com.pendulum.algo.synth.TruthKind
import com.pendulum.algo.synth.truthAsClms
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.DataEraser
import com.pendulum.phone.banc.Campagne.Genre
import com.pendulum.phone.data.PendulumPreferences
import com.pendulum.phone.db.ClmEventEntity
import com.pendulum.phone.db.HcSnapshotEntity
import com.pendulum.phone.db.NightContextEntity
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.ParamProfileEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.db.SleepWindowEntity
import com.pendulum.phone.db.eraseEverything
import com.pendulum.phone.health.Hypnogram
import com.pendulum.phone.health.SleepSourceSelector
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.work.AnalysisParams
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * L'ensemencement du banc : remplir la base de nuits synthetiques pour rendre atteignables les
 * ecrans qu'une installation neuve laisse derriere une porte fermee.
 *
 * ### Pourquoi ce fichier est dans `src/debug/` et n'a pas le droit d'en sortir
 *
 * Il fabrique des mesures. Une application qui sait fabriquer ses propres mesures et qui est
 * distribuee est une application dont **aucun chiffre affiche ne prouve plus rien** : il suffit
 * d'un chemin de code oublie, d'un drapeau mal teste, d'une diffusion recue par erreur, pour que
 * la campagne d'un utilisateur contienne des nuits qui n'ont jamais eu lieu — et rien, ni a
 * l'ecran ni dans l'export, ne les distingue des vraies. C'est exactement le defaut que le
 * deplacement d'`ApercuDonnees` vers `src/debug` avait corrige, et la raison pour laquelle le banc
 * (`docs/fr/BANC-ESSAI.md` §14.6) avait **refuse** de fabriquer des nuits pour photographier les
 * cinq ecrans sans porte.
 *
 * La garantie n'est donc pas un garde `if (BuildConfig.DEBUG)`, qui laisserait la classe dans
 * l'APK de release : c'est le source set. La variante release ne compile pas ce repertoire, ne
 * connait pas ces classes, et son manifeste ne porte pas le receveur qui les appelle.
 *
 * ### Ce qui est fabrique, et ce qui ne l'est pas
 *
 * Ce qui est fabrique : le **derive** — evenements, fenetres de sommeil, resultats — et le peu de
 * recu qu'il faut pour qu'il tienne debout : la ligne de session, le contexte scelle du soir,
 * l'instantane Health Connect, la telemetrie.
 *
 * Ce qui ne l'est **pas** : les chunks bruts. Ni les fichiers, ni les lignes de la table `chunk`.
 * Deux raisons, et la seconde est la plus importante :
 *
 *  1. Une ligne de `chunk` qui pointe vers un fichier absent est un mensonge auquel l'export et
 *     l'analyse font toutes les deux confiance — `NightExporter` produirait un bundle qui se
 *     dit complet et ne l'est pas.
 *  2. **Sans chunk, un rescore ne detruit rien.** `AnalysisRunner.analyse` sort sur
 *     `files.isEmpty()` avant de toucher aux tables derivees : les nuits ensemencees survivent
 *     donc a un `RescoreAllWorker` declenche par un changement de parametres. L'inverse a ete
 *     mesure et il est fatal — analysee pour de vrai, une nuit synthetique rend
 *     `gate = NO_PLMI` (le masque accelerometrique seul ne porte pas de denominateur
 *     independant), et l'ensemencement s'effacerait tout seul quelques secondes plus tard.
 *
 * Consequence visible et assumee : la bande de metrologie du detail d'une nuit affiche sa jauge de
 * batterie mais aucune bande temporelle, parce que celles-ci se placent sur la base de temps
 * capteur, dont l'origine (`chunk.tFirstNs`) n'existe pas ici.
 *
 * ### Pourquoi `NightSynth` plutot que des nombres ecrits a la main
 *
 * `:algo` porte deja un generateur de nuits realistes, celui qui sert de reference a toute la
 * suite de non-regression. Ses `SeriesSpec`, sa loi d'amplitude log-normale, ses familles de
 * distracteurs et sa verite terrain produisent des intervalles, des durees et des comptes qui ont
 * la forme de vraies nuits — et c'est cette forme qui fait apparaitre les defauts d'affichage
 * qu'une serie de nombres ronds cache (un axe qui ne bouge pas, un intervalle de confiance qui ne
 * chevauche rien, une mediane pile sur un seuil). Le trajet exact est decrit par [Campagne], qui
 * porte aussi la seule chose que ce fichier ne sait pas faire : se verifier sur JVM.
 *
 * Ce qui est court-circuite est le seul segment 1 a 5 — signal vers CLM. C'est aussi le seul qui
 * coute cher : mesure du 7 aout 2026 sur Apple Silicon, une nuit de 8 h se **genere** en 0,4 s et
 * s'**analyse** en 11 s. Sur le Pixel Fold, une nuit ensemencee coute 1,3 s au premier plan.
 */
object Ensemencement {

    private const val TAG = "PendulumBanc"

    /** Nuits eligibles ensemencees par defaut. Trois suffiraient ; sept donnent un graphe. */
    const val NUITS_PAR_DEFAUT = 7

    /**
     * Plafond du parametre `nuits`. Ce n'est pas une limite de la base : c'est le rappel qu'une
     * nuit coute une seconde et demie de synthese sur telephone, et qu'une campagne de cent nuits
     * demanderait plusieurs minutes pendant lesquelles l'application doit rester au premier plan.
     */
    const val NUITS_MAX = 30

    /**
     * Le paquet qui se declare source de l'hypnogramme.
     *
     * Il n'a pas a exister sur l'appareil : c'est une chaine consignee dans `hc_snapshot`, que
     * l'interface se contente de rendre lisible. En choisir un plausible plutot que `com.exemple`
     * est ce qui permet de juger a l'ecran la ligne « source du sommeil » a sa vraie longueur.
     */
    private const val PAQUET_SOMMEIL = "com.google.android.apps.fitness"

    /** Repere de serrage, identique pour toute la campagne : sinon tout sort `STRAP_CHANGED`. */
    private const val BRACELET = "3"

    /** Cote portant, identique pour toute la campagne : sinon tout sort `LEG_CHANGED`. */
    private const val JAMBE = "LEFT"

    // -------------------------------------------------------------------------------------
    // Entrees
    // -------------------------------------------------------------------------------------

    /**
     * Remplit la base avec [nuitsEligibles] nuits eligibles, plus une nuit provisoire et une nuit
     * ecartee. L'ordre chronologique est celui de [Campagne.genres], qui explique pourquoi il
     * n'est pas indifferent.
     */
    suspend fun ensemencer(context: Context, nuitsEligibles: Int) {
        val eligibles = nuitsEligibles.coerceIn(1, NUITS_MAX)
        val db = PendulumDatabase.get(context)

        // Repartir d'une base vide. Ce n'est pas une precaution de confort : `night_context` est
        // append-only et protege par un declencheur SQLite, donc re-ensemencer sans effacer ferait
        // echouer le premier `seal` sur une cle de nuit deja prise, au milieu de la campagne.
        viderLaBase(context, db)

        val params = AnalysisParams.DEFAULT
        db.paramDao().activate(
            ParamProfileEntity(
                paramsHash = params.paramsHash,
                createdAtMs = System.currentTimeMillis(),
                algoVersion = params.algoVersion,
                paramsJson = params.toJson(),
                active = true,
            )
        )

        val genres = Campagne.genres(eligibles)
        val zone = ZoneId.systemDefault()
        val debuts = debuts(genres.size, zone)

        for ((rang, genre) in genres.withIndex()) {
            val depart = System.currentTimeMillis()
            val nuit = Campagne.nuit(genre, rang, params)
            ecrire(db, params, nuit, rang, debuts[rang], zone)
            val duree = System.currentTimeMillis() - depart
            Log.i(
                TAG,
                "nuit ${rang + 1}/${genres.size} ($genre) en $duree ms : " +
                    "porte=${nuit.principal.gate} rythme=${nuit.principal.rhythm.valid}",
            )
        }

        // L'assistant, sans quoi l'application rouvre sur « Step 1 of 6 » et rien de ce qui vient
        // d'etre ecrit n'est atteignable. Le repere de serrage et la source de sommeil sont poses
        // avec, parce qu'ils sont demandes par ce meme assistant et lus par l'accueil.
        val prefs = PendulumPreferences(context)
        prefs.poserEtapeAssistant(PendulumPreferences.ETAPES_ASSISTANT)
        prefs.poserRepereDeSerrage(BRACELET)
        prefs.poserSourceSommeilPreferee(PAQUET_SOMMEIL)

        Log.i(TAG, "ensemencement termine : ${genres.size} nuits, hash ${params.paramsHash}")
    }

    /**
     * Rend l'appareil a son etat d'installation neuve.
     *
     * `DataEraser` couvre la base, les fichiers de chunks et les travaux planifies ; il ne touche
     * pas au `DataStore`, qui porte l'etat de l'assistant. Sans la seconde moitie, « tout
     * effacer » laisserait une application sans nuits mais qui se croit configuree — ce qui n'est
     * l'etat neuf de rien.
     */
    suspend fun effacer(context: Context) {
        DataEraser.eraseEverything(context)
        val prefs = PendulumPreferences(context)
        prefs.poserEtapeAssistant(0)
        prefs.poserRepereDeSerrage("")
        prefs.poserSourceSommeilPreferee(null)
        prefs.poserTheme(PendulumPreferences.THEME_SOMBRE)
        Log.i(TAG, "base et preferences effacees : l'appareil est rendu a son etat neuf")
    }

    // -------------------------------------------------------------------------------------
    // Ecriture d'une nuit
    // -------------------------------------------------------------------------------------

    private suspend fun ecrire(
        db: PendulumDatabase,
        params: AnalysisParams,
        nuit: Campagne.Nuit,
        rang: Int,
        debutWallMs: Long,
        zone: ZoneId,
    ) {
        val truth = nuit.synth.truth
        val hex = "%016x".format(Campagne.graine(rang) * 31 + rang)
        val finWallMs = debutWallMs + Math.round(nuit.dureeEnregistreeMin * 60_000.0)
        val nightKey = WirePaths.nightKey(debutWallMs, zone)
        val offsetMin = zone.rules.getOffset(Instant.ofEpochMilli(debutWallMs)).totalSeconds / 60

        // --- le recu -------------------------------------------------------------------
        db.nightDao().insertIfAbsent(
            NightSessionEntity(
                sessionHex = hex,
                nightKey = nightKey,
                startWallMs = debutWallMs,
                plannedStopWallMs = debutWallMs + 9 * 3_600_000L,
                endWallMs = finWallMs,
                zoneId = zone.id,
                tzOffsetStartMin = offsetMin,
                tzOffsetEndMin = offsetMin,
                nominalRateHz = 50,
                modeFlags = 0,
                state = "CLOSED",
                stopReason = if (nuit.tronquee) "BATTERY" else "PLANNED",
                // Volontairement `null` : aucune montre n'a annonce de compte de chunks, et
                // ecrire un nombre en face d'une table `chunk` vide ferait dire a la bande
                // d'etat du reveil qu'un transfert est en cours pour toujours.
                totalChunks = null,
                lastChunkArrivalMs = finWallMs,
                batteryPctLast = 100 - (nuit.dureeEnregistreeMin / 12.0).toInt(),
            )
        )

        // Le contexte du soir est scelle **avant** la nuit : c'est le garde-fou 1, et la table le
        // rend structurel (deux declencheurs SQLite interdisent tout UPDATE et tout DELETE). Ici
        // il n'y a rien a contourner — on insere une fois, dans le bon ordre.
        db.contextDao().seal(
            NightContextEntity(
                nightKey = nightKey,
                sealedAtMs = debutWallMs - 40 * 60_000L,
                leg = JAMBE,
                strapId = BRACELET,
                // Le seul motif d'exclusion utilise, et il est plausible : une nuit a deux dans
                // le lit n'est pas comparable, un partenaire transmettant ses mouvements par le
                // matelas. Les autres motifs (jambe ou bracelet change) supposeraient une
                // campagne mal tenue plutot qu'une nuit ordinaire.
                aloneInBed = nuit.genre != Genre.ECARTEE,
                bedTimeLocalMs = debutWallMs,
                riseTimeLocalMs = finWallMs,
                medicationJson = "[]",
                caffeineAfter16h = rang % 3 == 1,
                alcoholUnits = if (rang % 4 == 2) 1.0 else 0.0,
                unusualExercise = rang % 5 == 3,
            )
        )

        val stades = truth.mask.windows.map {
            SleepSourceSelector.StageSpan(
                startMs = debutWallMs + it.startMsRel,
                endMs = debutWallMs + it.endMsRel,
                stageType = typeHc(it.stage),
            )
        }
        db.hcSnapshotDao().append(
            HcSnapshotEntity(
                sessionHex = hex,
                fetchedAtMs = finWallMs + 2 * 3_600_000L,
                attemptIndex = 1,
                selectedPackage = PAQUET_SOMMEIL,
                selectedRecordId = "banc-$hex",
                lastModifiedTimeMs = finWallMs + 90 * 60_000L,
                sessionStartMs = debutWallMs,
                sessionEndMs = finWallMs,
                stageCount = stades.size,
                distinctStageTypes = stades.map { it.stageType }.distinct().size,
                stageCoverageMin = truth.mask.sptMin,
                overlapFraction = 1.0,
                aggregateTstMin = truth.mask.tstMin,
                originCount = 1,
                selectedStagesCsv = Hypnogram.encodeCsv(stades),
                recordsJson = """{"banc":true,"source":"$PAQUET_SOMMEIL"}""",
                outcome = "SELECTED",
            )
        )

        db.telemetryDao().insertAllIfAbsent(
            Telemetrie.points(hex, nuit.spec, truth, nuit.dureeEnregistreeMin),
        )

        // --- le derive -----------------------------------------------------------------
        // Meme fabrique que l'analyse reelle. Le banc doit passer par la conversion `NaN` -> `NULL`
        // exactement comme la production : une nuit synthetique qui s'insererait la ou une vraie
        // echoue ferait rater au banc le seul defaut qu'il est la pour attraper.
        val resultats = nuit.mesures.map { p ->
            PlmResultEntity.depuis(
                sessionHex = hex,
                paramsHash = params.paramsHash,
                computedAtMs = finWallMs + 3 * 3_600_000L,
                algoVersion = params.algoVersion,
                r = p,
            )
        }

        val fenetres = nuit.masque.windows.map {
            SleepWindowEntity(
                sessionHex = hex,
                source = MaskSource.HEALTH_CONNECT.name,
                stage = it.stage.name,
                startMsRel = it.startMsRel,
                endMsRel = it.endMsRel,
                sourcePackage = PAQUET_SOMMEIL,
                paramsHash = params.paramsHash,
            )
        }

        db.derivedDao().replaceAnalysis(
            hex = hex,
            paramsHash = params.paramsHash,
            windows = fenetres,
            events = evenements(hex, params.paramsHash, truth),
            results = resultats,
        )

        db.nightDao().writeAnalysisSummary(
            hex = hex,
            atMs = finWallMs + 3 * 3_600_000L,
            algoVersion = params.algoVersion,
            paramsHash = params.paramsHash,
            fsHz = truth.fsRealHz,
            sampleCount = nuit.synth.blocks.sumOf { it.x.size.toLong() },
            gapCount = truth.gaps.size,
            gapTotalMs = truth.gaps.sumOf { Math.round(it.durationSec * 1000.0) },
            analysableMin = nuit.analysableMin,
            gainCalG = truth.gainCalG.toDouble(),
            gainSource = "RITUAL",
            truncated = nuit.tronquee,
            rejectedFraction = 0.0,
        )
    }

    /**
     * Les instants de debut, du plus ancien au plus recent.
     *
     * ### L'heure du coucher est une donnee de l'ecran, pas un detail
     *
     * La premiere version ancrait la derniere nuit « six heures avant maintenant », ce qui etait
     * commode et faux : ensemencee a 18 h, la campagne affichait des nuits de **04:29 a 12:14**.
     * Aucun chiffre n'etait errone, et pourtant la liste des nuits etait inutilisable comme
     * capture — personne ne se couche a quatre heures et demie du matin sept jours de suite.
     *
     * L'ancre est donc une heure de coucher, [HEURE_COUCHER], la veille. Le decalage d'un jour
     * supplementaire couvre le seul cas ou cela ne suffirait pas : ensemencer le matin, avant que
     * la nuit d'hier ne soit finie — la liste montrerait alors une nuit qui se termine dans le
     * futur.
     *
     * Le recul d'un jour par nuit donne a la cle de nuit — qui bascule a midi — une valeur
     * distincte par nuit, comme l'exige la cle primaire de `night_context`.
     */
    private fun debuts(combien: Int, zone: ZoneId): List<Long> {
        val maintenant = ZonedDateTime.now(zone)
        var derniere = maintenant.toLocalDate().minusDays(1)
            .atTime(HEURE_COUCHER, 10)
            .atZone(zone)
        if (derniere.plusHours(DUREE_MAX_H) > maintenant) derniere = derniere.minusDays(1)

        return (0 until combien).map { rang ->
            derniere
                .minusDays((combien - 1 - rang).toLong())
                // Personne ne se couche a la meme minute deux soirs de suite, et une colonne
                // d'heures identiques dans la liste des nuits se remarque immediatement.
                .minusMinutes((rang * 13L) % 47)
                .toInstant()
                .toEpochMilli()
        }
    }

    /** 23 h : l'heure de coucher de la campagne, a quelques dizaines de minutes pres. */
    private const val HEURE_COUCHER = 23

    /** Duree maximale d'une nuit de la campagne, arrondie au-dessus. Voir `Campagne.recette`. */
    private const val DUREE_MAX_H = 9L

    /**
     * Les evenements persistes : les mouvements de jambe retenus, **et les artefacts rejetes**.
     *
     * Les rejetes sont ecrits avec leur motif, comme le fait l'analyse reelle. Ce n'est pas du
     * remplissage : le detail d'une nuit compte separement les mouvements ecartes pour posture et
     * pour duree, et une nuit ou ces deux compteurs valent zero ne permet pas de juger la ligne
     * qui les affiche.
     */
    private fun evenements(hex: String, hash: String, truth: GroundTruth): List<ClmEventEntity> {
        val retenus = truthAsClms(truth.accelLegMovements, truth.floorG.toFloat()).map {
            ligne(hex, hash, it.onsetMsRel, it.durationMs, it.peakAmpG, it.medianAmpG, truth, null)
        }
        val rejetes = truth.accelTruth.filter { !it.isLegMovement }.map { e ->
            ligne(
                hex, hash, e.onsetMsRel, e.durationMs, e.peakG, e.envPeakG, truth,
                when (e.kind) {
                    TruthKind.POSTURE -> "POSTURAL"
                    TruthKind.GROSS_BODY -> "GROSS_BODY"
                    // Une vibration de matelas dure entre 50 et 400 ms : c'est litteralement le
                    // motif que le detecteur lui opposerait.
                    TruthKind.MATTRESS -> "TOO_SHORT"
                    else -> "MORPHOLOGY"
                },
            )
        }
        return (retenus + rejetes).sortedBy { it.onsetMsRel }
    }

    private fun ligne(
        hex: String,
        hash: String,
        onsetMsRel: Long,
        durationMs: Int,
        peakG: Float,
        medianG: Float,
        truth: GroundTruth,
        motif: String?,
    ): ClmEventEntity = ClmEventEntity(
        sessionHex = hex,
        paramsHash = hash,
        onsetMsRel = onsetMsRel,
        durationMs = durationMs,
        peakAmpG = peakG.toDouble(),
        medianAmpG = medianG.toDouble(),
        noiseFloorG = truth.floorG,
        thresholdOnG = truth.floorG * 8.0,
        tiltChangeDeg = 0.0,
        flags = 0,
        rejectReason = motif,
        duringWake = false,
    )

    /** Correspondance stade `:algo` -> type Health Connect, l'inverse de `Hypnogram.stageOf`. */
    private fun typeHc(stage: Stage): Int = when (stage) {
        Stage.SLEEP -> Hypnogram.STAGE_SLEEPING
        Stage.LIGHT -> Hypnogram.STAGE_LIGHT
        Stage.DEEP -> Hypnogram.STAGE_DEEP
        Stage.REM -> Hypnogram.STAGE_REM
        Stage.AWAKE_IN_BED -> Hypnogram.STAGE_AWAKE_IN_BED
        Stage.OUT_OF_BED -> Hypnogram.STAGE_OUT_OF_BED
        else -> Hypnogram.STAGE_UNKNOWN
    }

    /**
     * L'effacement de la base seule, sans passer par [DataEraser].
     *
     * `DataEraser.eraseEverything` commence par `WorkManager.cancelAllWork()`. C'est juste pour le
     * bouton « tout supprimer » — un `SleepFetchWorker` deja en file recreerait une ligne
     * `hc_snapshot` juste apres — mais deplace ici : l'ensemencement ecrit des nuits deja fermees
     * et deja analysees, et couper au passage le chien de garde serait un effet de bord que la
     * commande ne promet pas. La difference tient a cette ligne et a rien d'autre.
     */
    private suspend fun viderLaBase(context: Context, db: PendulumDatabase) {
        ChunkStore(context).deleteAll()
        db.eraseEverything()
    }
}
