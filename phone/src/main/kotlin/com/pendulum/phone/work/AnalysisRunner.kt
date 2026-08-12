package com.pendulum.phone.work

import android.content.Context
import android.util.Log
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.SleepWindow
import com.pendulum.phone.db.ClmEventEntity
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.ParamProfileEntity
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.SleepWindowEntity
import com.pendulum.phone.health.Hypnogram
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.ingest.SessionReassembler
import com.pendulum.phone.ingest.TimeAnchor

/**
 * Analyse d'une nuit, de la base a la base. C'est le seul endroit qui ecrit dans les tables
 * derivees.
 *
 * ### L'idempotence est une exigence, pas une propriete heureuse
 *
 * La meme nuit est analysee plusieurs fois par construction : une premiere fois au reveil avec le
 * seul masque accelerometrique, une deuxieme quand l'hypnogramme arrive, une troisieme si le
 * fournisseur reecrit sa session, et une n-ieme a chaque changement de parametre. Chaque passage
 * **remplace** ce qui existait pour `(nuit, paramsHash)` au lieu d'y ajouter. Un rescore qui
 * empilerait doublerait le nombre d'evenements a chaque tour, et le symptome — un index qui
 * double — ressemblerait exactement a une aggravation clinique.
 */
object AnalysisRunner {

    private const val TAG = "PendulumAnalysis"

    /**
     * @return `true` si l'analyse a produit des resultats, `false` si la nuit n'etait pas
     *   analysable (aucun chunk, ou aucun bloc valide).
     */
    suspend fun analyse(
        context: Context,
        sessionHex: String,
        params: AnalysisParams = AnalysisParams.DEFAULT,
    ): Boolean {
        val db = PendulumDatabase.get(context)
        val store = ChunkStore(context)
        val session = db.nightDao().find(sessionHex) ?: return false

        // Le profil de parametres est enregistre comme actif s'il ne l'est pas deja : la
        // tendance filtre sur `paramsHash`, et un resultat dont le hash n'existe pas dans
        // `param_profile` serait un chiffre orphelin, impossible a expliquer plus tard.
        db.paramDao().insertIfAbsent(
            ParamProfileEntity(
                paramsHash = params.paramsHash,
                createdAtMs = System.currentTimeMillis(),
                algoVersion = params.algoVersion,
                paramsJson = params.toJson(),
                active = db.paramDao().active() == null,
            )
        )

        val files = store.listChunkFiles(sessionHex)
        if (files.isEmpty()) return false

        val night = SessionReassembler.reassemble(
            sessionHex = sessionHex,
            files = files,
            sessionStateClosed = session.state == "CLOSED",
            declaredChunks = session.totalChunks,
        )
        if (night.blocks.isEmpty() || night.anchor == null) {
            Log.w(TAG, "$sessionHex : aucun bloc exploitable")
            return false
        }

        val hcWindows = loadHypnogram(db, sessionHex, night.anchor!!, params)
        val diary = loadDiary(db, sessionHex, night.anchor!!)
        val baselineGain = baselineGainOf(db, sessionHex)

        val result = NightAnalyzer.analyze(
            blocks = night.blocks,
            nominalRateHz = night.nominalRateHz,
            sessionClosedCleanly = night.closedCleanly,
            hcWindows = hcWindows,
            diary = diary,
            baselineGainG = baselineGain,
            params = params,
        )

        persist(db, session, night, result, params)
        return true
    }

    // ------------------------------------------------------------------

    /**
     * L'hypnogramme retenu par la derniere lecture Health Connect, relu depuis
     * `hc_snapshot.selectedStagesCsv`.
     *
     * On ne relit **pas** Health Connect ici. Deux raisons : un rescore doit pouvoir tourner sur
     * une base restauree depuis un bundle, sur un telephone qui n'a jamais vu cette nuit ; et
     * relire donnerait une reponse potentiellement differente (le fournisseur reecrit ses
     * sessions), ce qui rendrait le rescore non reproductible — deux executions successives sans
     * changement de parametre pourraient produire deux chiffres.
     */
    private suspend fun loadHypnogram(
        db: PendulumDatabase,
        sessionHex: String,
        anchor: TimeAnchor,
        params: AnalysisParams,
    ): List<SleepWindow>? {
        val snap = db.hcSnapshotDao().latest(sessionHex) ?: return null
        if (snap.selectedStagesCsv.isBlank() && snap.sessionStartMs == null) return null
        val stages = Hypnogram.decodeCsv(snap.selectedStagesCsv)
        val start = snap.sessionStartMs ?: return null
        val end = snap.sessionEndMs ?: return null
        return Hypnogram.toWindows(stages, start, end, anchor, params.hypnogramHolePolicy)
    }

    /**
     * Le journal manuel, depuis le contexte scelle du soir. Il ne rend pas le masque
     * accelerometrique independant du signal — il borne la recherche du SPT, ce qui empeche une
     * sieste ou une immobilite de canape de preempter le debut de nuit.
     */
    private suspend fun loadDiary(
        db: PendulumDatabase,
        sessionHex: String,
        anchor: TimeAnchor,
    ): DiaryWindow? {
        val ctx = db.contextDao().findForSession(sessionHex) ?: return null
        val bed = ctx.bedTimeLocalMs ?: return null
        val rise = ctx.riseTimeLocalMs ?: return null
        return DiaryWindow(anchor.toMsRel(bed), anchor.toMsRel(rise))
    }

    /**
     * L'etalon de gain de la **nuit de reference** — la premiere dont le contexte a ete scelle,
     * la meme que celle qui sert de reference a la vue `comparable_night`. Utiliser la moyenne
     * de la campagne ferait bouger la reference a chaque nuit ajoutee, et une nuit hier
     * comparable pourrait cesser de l'etre aujourd'hui sans que rien n'ait change chez le
     * dormeur.
     */
    private suspend fun baselineGainOf(db: PendulumDatabase, sessionHex: String): Float? {
        val ref = db.contextDao().reference() ?: return null
        // Le contexte de reference est cle par la soiree, pas par la session : c'est la nuit
        // enregistree sous cette soiree qui porte l'etalon de gain. Elle peut ne pas exister —
        // un formulaire scelle un soir ou la montre n'a finalement pas demarre.
        val refSession = db.nightDao().findByNightKey(ref.nightKey) ?: return null
        if (refSession.sessionHex == sessionHex) return null
        return refSession.gainCalG?.toFloat()
    }

    // ------------------------------------------------------------------

    private suspend fun persist(
        db: PendulumDatabase,
        session: NightSessionEntity,
        night: SessionReassembler.Night,
        r: NightAnalyzer.Result,
        params: AnalysisParams,
    ) {
        val hex = session.sessionHex

        val windows = r.masks.flatMap { (source, mask) ->
            mask.windows.map { w ->
                SleepWindowEntity(
                    sessionHex = hex,
                    source = source.name,
                    stage = w.stage.name,
                    startMsRel = w.startMsRel,
                    endMsRel = w.endMsRel,
                    sourcePackage = if (source == MaskSource.HEALTH_CONNECT) {
                        db.hcSnapshotDao().latest(hex)?.selectedPackage
                    } else {
                        null
                    },
                    paramsHash = r.paramsHash,
                )
            }
        }

        // Appartenance aux series, par jeu de regles. On la reconstruit depuis les resultats
        // plutot que de la deviner : c'est `SeriesBuilder` qui decide, et lui seul.
        val events = r.clms.map { c ->
            ClmEventEntity(
                sessionHex = hex,
                paramsHash = r.paramsHash,
                onsetMsRel = c.onsetMsRel,
                durationMs = c.durationMs,
                peakAmpG = c.peakAmpG.toDouble(),
                medianAmpG = c.medianAmpG.toDouble(),
                noiseFloorG = c.noiseFloorG.toDouble(),
                thresholdOnG = c.thresholdOnG.toDouble(),
                tiltChangeDeg = c.tiltChangeDeg.toDouble(),
                flags = c.flags,
                rejectReason = c.reject?.name,
                duringWake = (c.flags and ClmFlags.DURING_WAKE) != 0,
            )
        }

        // `depuis` et non le constructeur : c'est elle qui traduit les `NaN` de `:algo` en `NULL`.
        // Une nuit sans sommeil analysable — le cas par defaut sans hypnogramme Health Connect —
        // n'a pas de PLMI du tout, et l'ecrire en dur ici la ferait echouer a l'insertion.
        val computedAtMs = System.currentTimeMillis()
        val results = r.results.map { p ->
            PlmResultEntity.depuis(
                sessionHex = hex,
                paramsHash = r.paramsHash,
                computedAtMs = computedAtMs,
                algoVersion = r.algoVersion,
                r = p,
            )
        }

        db.derivedDao().replaceAnalysis(hex, r.paramsHash, windows, events, results)

        db.nightDao().writeAnalysisSummary(
            hex = hex,
            atMs = System.currentTimeMillis(),
            algoVersion = r.algoVersion,
            paramsHash = r.paramsHash,
            fsHz = r.fsHz,
            sampleCount = r.sampleCount,
            gapCount = r.gapCount,
            gapTotalMs = r.gapTotalMs,
            analysableMin = r.analysableMin,
            gainCalG = r.calibration.gainCalG.takeIf { it.isFinite() }?.toDouble(),
            gainSource = r.calibration.gainSource.name,
            truncated = r.truncated || !night.closedCleanly,
            rejectedFraction = r.integrityRejectedFraction,
        )
    }
}
