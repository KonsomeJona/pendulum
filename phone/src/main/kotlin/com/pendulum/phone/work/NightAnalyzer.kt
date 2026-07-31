package com.pendulum.phone.work

import com.pendulum.algo.detect.ClmDetector
import com.pendulum.algo.detect.PostureDetector
import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.dsp.Calibration
import com.pendulum.algo.dsp.Preprocess
import com.pendulum.algo.indices.Periodicity
import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.mask.ImmobilityMask
import com.pendulum.algo.mask.MaskFusion
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.MaskAgreement
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.RespiratoryConfidence
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow

/**
 * L'orchestration de `:algo`. **Aucun import Android** : cette classe tourne telle quelle sur
 * JVM, ce qui est ce qui rend testable la propriete la plus importante de l'export — qu'une base
 * reconstruite depuis un bundle redonne le meme resultat au bit pres.
 *
 * `:algo` n'expose pas de fonction « analyse cette nuit » et c'est deliberé : il expose des
 * etapes pures que quelqu'un doit enchainer. Le quelqu'un, c'est ce fichier, et l'ordre
 * ci-dessous n'est pas negociable.
 *
 * ### Les trois passes de pretraitement, et pourquoi il en faut trois
 *
 * 1. **Passe 0, sans rien.** Elle ne sert qu'a obtenir le canal gravite, dont le detecteur de
 *    posture a besoin. On jette tout le reste.
 * 2. **Passe 1, avec les frontieres de posture.** Un changement de posture coupe les fenetres
 *    d'estimation du plancher de bruit : sans cette coupure, la rotation contamine le plancher
 *    des minutes qui suivent et le seuil monte pour de mauvaises raisons.
 * 3. **Passe 2, avec la calibration.** Le troisieme terme du seuil depend de l'etalon de gain de
 *    la nuit, lequel se mesure sur les mouvements corporels grossiers, lesquels sont produits
 *    par une premiere detection. La boucle est fermee une fois et une seule : re-detecter apres
 *    la passe 2 n'apporterait rien, l'etalon etant deja stable.
 *
 * ### Le point fixe du masque
 *
 * `ImmobilityMask.fixedPoint` recoit les intervalles de mouvement en lambda. On y branche les
 * CLM **deja detectes**, ce qui est exact ici : la detection ne consulte pas le masque, la
 * dependance ne va que dans un sens. Neutraliser les CLM comme preuve d'eveil est la couche 1 de
 * la reponse a la circularite — un PLMS est par definition un mouvement *pendant* le sommeil, et
 * s'en servir comme preuve d'eveil fait exploser l'index du sujet le plus atteint.
 */
object NightAnalyzer {

    /**
     * @param results **quatre lignes** dans le cas nominal : 2 jeux de regles x 2 masques. Deux
     *   seulement si Health Connect n'a rien rendu. Les deux masques sont toujours calcules et
     *   rapportes : l'ecart entre eux est en soi une information, et le cacher reviendrait a
     *   choisir en silence.
     */
    data class Result(
        val fsHz: Double,
        val analysableMin: Double,
        val analysableTstMin: Double,
        val sampleCount: Long,
        val gapCount: Int,
        val gapTotalMs: Long,
        val truncated: Boolean,
        val integrityRejectedFraction: Double,
        val calibration: NightCalibration,
        val clms: List<Clm>,
        val postures: List<PostureChange>,
        val masks: Map<MaskSource, SleepMask>,
        val agreement: MaskAgreement?,
        val results: List<PlmiResult>,
        val paramsHash: String,
        val algoVersion: String,
    )

    /**
     * @param hcWindows hypnogramme Health Connect **deja converti** en millisecondes relatives au
     *   debut de la ligne de temps (voir `TimeAnchor`). La conversion ne se fait pas ici parce
     *   qu'elle a besoin des trois horloges de l'en-tete de chunk, que `:algo` ne voit pas.
     * @param diary journal manuel. Il ne rend pas le masque accelerometrique independant : il
     *   borne la **recherche** du SPT, ce qui empeche une immobilite de canape de prendre la
     *   place du debut de nuit.
     * @param baselineGainG etalon de gain de la nuit de reference, pour detecter un bracelet
     *   resserre differemment. `null` sur la premiere nuit d'une campagne.
     */
    fun analyze(
        blocks: List<SampleBlock>,
        nominalRateHz: Int,
        sessionClosedCleanly: Boolean,
        hcWindows: List<SleepWindow>?,
        diary: DiaryWindow?,
        baselineGainG: Float?,
        params: AnalysisParams = AnalysisParams.DEFAULT,
    ): Result {
        // --- Passe 0 : uniquement pour obtenir la gravite ---------------------------------
        val pass0 = Preprocess.run(
            blocks, nominalRateHz.toDouble(), params.preprocess,
            sessionClosedCleanly = sessionClosedCleanly,
        )
        val postures = PostureDetector.detect(pass0.gravity, pass0.timeline.segments, params.posture)
        val postureBoundaries = postures.map { it.atIdx }.toIntArray()

        // --- Passe 1 : plancher de bruit coupe aux frontieres de posture -------------------
        val pass1 = Preprocess.run(
            blocks, nominalRateHz.toDouble(), params.preprocess, postureBoundaries,
            calibration = null, sessionClosedCleanly = sessionClosedCleanly,
        )

        // Detection provisoire, uniquement pour mesurer l'etalon de gain. `gainCalG = NaN`
        // desactive le troisieme terme du seuil : le detecteur travaille alors sur le seul
        // plancher adaptatif, ce qui suffit largement a reperer un mouvement corporel grossier.
        val noCalibration = NightCalibration(
            sensor = null,
            gainCalG = Float.NaN,
            floorCalG = Float.NaN,
            snrCal = Float.NaN,
            gainSource = GainSource.NONE,
            outlierVsBaseline = false,
        )
        val provisional = ClmDetector.detect(
            pass1.envelope, pass1.floor, pass1.floorExtrapolated, pass1.gravity,
            pass1.timeline.segments, pass1.timeline.blindZones, postures,
            noCalibration, params.clm, params.posture,
        )
        val calibration = Calibration.fromGrossBodyMovements(provisional, baselineGainG)

        // --- Passe 2 : seuils calibres ------------------------------------------------------
        val pre = Preprocess.run(
            blocks, nominalRateHz.toDouble(), params.preprocess, postureBoundaries,
            calibration = calibration, sessionClosedCleanly = sessionClosedCleanly,
        )
        val clms = ClmDetector.detect(
            pre.envelope, pre.floor, pre.floorExtrapolated, pre.gravity,
            pre.timeline.segments, pre.timeline.blindZones, postures,
            calibration, params.clm, params.posture,
        )

        val timeline = pre.timeline
        val fsHz = timeline.signal.fsHz
        val analysableMin = timeline.analysableSec / 60.0
        val coverage = analysableCoverage(timeline.analysableSec, timeline.signal.n, fsHz)

        // --- Masque accelerometrique, point fixe borne a deux iterations ---------------------
        val movementIntervals: (SleepMask) -> List<Segment> = {
            clms.filter { c -> c.isClm }.map { c -> Segment(c.onsetIdx, c.offsetIdx) }
        }
        val fixedPoint = ImmobilityMask.fixedPoint(
            gravity = pre.gravity,
            env = pre.envelope.coarse,
            floor = pre.floor,
            segments = timeline.segments,
            offBody = timeline.offBody,
            diary = diary,
            cfg = params.immobility,
            blindZones = timeline.blindZones,
            movementIntervalsOf = movementIntervals,
        )
        val accelMask = fixedPoint.mask

        val masks = LinkedHashMap<MaskSource, SleepMask>()
        masks[MaskSource.ACCEL_IMMOBILITY] = accelMask

        var agreement: MaskAgreement? = null
        if (!hcWindows.isNullOrEmpty()) {
            masks[MaskSource.HEALTH_CONNECT] = MaskFusion.fromHealthConnect(hcWindows, coverage)
            agreement = MaskFusion.align(accelMask, hcWindows, params.fusion)
        }

        // --- Les quatre resultats -----------------------------------------------------------
        val rules = listOf(SeriesConfig.aasmV3(), SeriesConfig.wasm2016())
        val out = ArrayList<PlmiResult>(rules.size * masks.size)
        for ((source, mask) in masks) {
            for (cfg in rules) {
                out += computeOne(clms, mask, source, cfg, fsHz, timeline.truncated, params)
            }
        }

        return Result(
            fsHz = fsHz,
            analysableMin = analysableMin,
            analysableTstMin = accelMask.analysableTstMin,
            sampleCount = blocks.sumOf { it.x.size.toLong() },
            gapCount = timeline.gaps.size,
            gapTotalMs = timeline.gaps.sumOf { Math.round(it.durationSec * 1000.0) },
            truncated = timeline.truncated,
            integrityRejectedFraction = timeline.integrity.rejectedFraction,
            calibration = calibration,
            clms = clms,
            postures = postures,
            masks = masks,
            agreement = agreement,
            results = out,
            paramsHash = params.paramsHash,
            algoVersion = params.algoVersion,
        )
    }

    private fun computeOne(
        clms: List<Clm>,
        mask: SleepMask,
        source: MaskSource,
        cfg: SeriesConfig,
        fsHz: Double,
        truncated: Boolean,
        params: AnalysisParams,
    ): PlmiResult {
        val built = SeriesBuilder.buildDetailed(clms, mask, fsHz, cfg)
        val pi = Periodicity.ferriIndex(clms, mask, fsHz, params.periodicity)
        // `fromClms` et non `fromSeries` : la construction de serie a deja filtre les intervalles
        // hors [10, 90] s, c'est-a-dire precisement les harmoniques hauts que la deconvolution
        // cherche a modeliser. Partir des series sous-estimerait mecaniquement le taux de manques.
        val rhythm = Rhythm.fromClms(clms, mask, params.rhythm)

        return Plmi.compute(
            clms = clms,
            series = built.series,
            mask = mask,
            fsHz = fsHz,
            rule = cfg.rule,
            respiratory = RESPIRATORY_CONFIDENCE,
            pi = pi,
            rhythm = rhythm,
            floorMode = FloorMode.BILATERAL,
            truncated = truncated,
            cfg = params.plmi,
            truncatedSeriesDropped = built.truncatedSeriesDropped,
            paramsHash = params.paramsHash,
        ).let { r ->
            // `Plmi.compute` ne connait pas la source du masque ; on la porte ici pour que les
            // quatre lignes soient distinguables en base.
            if (r.maskSource == source) r else r.copy(maskSource = source)
        }
    }

    /**
     * Fraction du temps effectivement analysable, passee aux constructeurs de masque.
     *
     * Elle sert a calculer `analysableTstMin`, qui est **le vrai denominateur** : epoques de
     * sommeil intersectees avec les epoques reellement couvertes par des blocs valides. Le TST
     * brut ne fait pas l'affaire — une nuit de 8 h dont 3 h sont trouees n'a pas 8 h de sommeil
     * analysable, et diviser par 8 h sous-estimerait l'index d'un tiers.
     */
    private fun analysableCoverage(analysableSec: Double, gridPoints: Int, fsHz: Double): Double {
        if (gridPoints <= 0 || fsHz <= 0.0) return 0.0
        val totalSec = gridPoints / fsHz
        return if (totalSec <= 0.0) 0.0 else (analysableSec / totalSec).coerceIn(0.0, 1.0)
    }

    /**
     * Aucune voie respiratoire n'est enregistree, et il n'y en aura pas.
     *
     * `HIGH` exigerait une polygraphie ; `LOW` ferme la porte de publication et rendrait toute
     * nuit inexploitable. `MEDIUM` est donc le seul choix honnete : il laisse `plmiRespWorstCase`
     * porter l'incertitude — l'index recalcule en supposant que toute serie dont l'IMI median
     * tombe dans la bande apneique est d'origine respiratoire. L'ecart entre les deux chiffres
     * est ce qu'il faut lire, pas un drapeau binaire.
     */
    val RESPIRATORY_CONFIDENCE = RespiratoryConfidence.MEDIUM

    /** Les deux jeux de regles, exposes pour les tests et pour l'affichage. */
    val RULES: List<SeriesRule> = listOf(SeriesRule.AASM_V3, SeriesRule.WASM_2016)
}
