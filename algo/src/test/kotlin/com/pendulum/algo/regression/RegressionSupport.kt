package com.pendulum.algo.regression

import com.pendulum.algo.detect.ClmConfig
import com.pendulum.algo.detect.ClmDetector
import com.pendulum.algo.detect.PostureConfig
import com.pendulum.algo.detect.PostureDetector
import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.dsp.Preprocess
import com.pendulum.algo.dsp.PreprocessConfig
import com.pendulum.algo.dsp.Preprocessed
import com.pendulum.algo.indices.Periodicity
import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.RespiratoryConfidence
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.Stage
import com.pendulum.algo.model.Timeline
import com.pendulum.algo.synth.AmplitudeScale
import com.pendulum.algo.synth.AmplitudeSpec
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.GroundTruth
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth
import com.pendulum.algo.synth.NoiseSpec
import com.pendulum.algo.synth.SeriesSpec
import com.pendulum.algo.synth.SleepSpec
import com.pendulum.algo.synth.SynthNight
import com.pendulum.algo.synth.TruthEvent
import com.pendulum.algo.synth.TruthKind
import com.pendulum.algo.synth.truthAsClms
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Outillage commun de la suite de non-regression du tableau `docs/ALGO-v2.md` §5.5.
 *
 * Trois choses y sont fixees une fois pour toutes, parce qu'elles conditionnent la lecture de tous
 * les seuils :
 *
 *  1. **Les 20 graines.** Chaque test tourne sur au moins 20 nuits ; l'assertion porte sur la
 *     mediane, avec une assertion secondaire sur le pire cas la ou la specification l'indique.
 *  2. **Le masque de sommeil est celui de la verite terrain**, raffine par le temps reellement
 *     analysable de la ligne de temps. La couche masque accelerometrique de §3.6 n'est pas encore
 *     ecrite, et surtout : la faire porter le denominateur ici melangerait l'erreur du detecteur et
 *     celle du masque, alors que §5.5 ne mesure que la premiere. Le denominateur reste donc
 *     `INDEPENDENT_DIARY`, non circulaire par construction.
 *  3. **L'attendu traverse les memes etapes 6 et 7 que la mesure**, avec le **meme** objet masque
 *     ([Analysis.truthResult]). Toute difference restante est imputable a la detection — ce qui est
 *     exactement ce que T6 pretend mesurer.
 */
internal val SEEDS: List<Long> = (0 until 20).map { 20_260_729L + it * 7_919L }

internal const val FS: Double = 50.0

// ---------------------------------------------------------------------------------------------
// Statistiques d'agregation sur les graines
// ---------------------------------------------------------------------------------------------

internal fun medianOf(values: List<Double>): Double {
    require(values.isNotEmpty()) { "mediane d'une liste vide" }
    val s = values.filter { !it.isNaN() }.sorted()
    if (s.isEmpty()) return Double.NaN
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else 0.5 * (s[m - 1] + s[m])
}

internal fun worstMax(values: List<Double>): Double = values.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN

internal fun worstMin(values: List<Double>): Double = values.filter { !it.isNaN() }.minOrNull() ?: Double.NaN

/** Ecart relatif entre deux valeurs, rapporte a la premiere. `NaN` si la reference est nulle. */
internal fun relDiff(a: Double, b: Double): Double = if (a == 0.0) Double.NaN else abs(a - b) / abs(a)

// ---------------------------------------------------------------------------------------------
// Chaine complete
// ---------------------------------------------------------------------------------------------

internal class Analysis(
    val night: SynthNight,
    val pre: Preprocessed,
    val postures: List<PostureChange>,
    val clms: List<Clm>,
    val mask: SleepMask,
    val calibration: NightCalibration,
    val kOn: Double,
) {
    val timeline: Timeline get() = pre.timeline
    val truth: GroundTruth get() = night.truth

    /** CLM retenus, dans l'ordre chronologique. */
    val retained: List<Clm> get() = clms.filter { it.isClm }

    /** Fraction du temps enregistre reellement analysable. Garde-fou anti-test-vide. */
    val analysableFraction: Double
        get() = if (timeline.signal.n == 0) 0.0
        else timeline.analysableSec / (timeline.signal.n / timeline.signal.fsHz)

    /**
     * Seuil de declenchement `Theta_on`, median sur la nuit. C'est l'amplitude au-dessus de laquelle
     * un evenement fait partie de ceux que le detecteur est **configure** pour trouver — ce qui n'est
     * pas la meme chose que ceux qui sont mecaniquement presents dans le signal. Le denominateur de
     * T6 et la fraction sous seuil de T22 se rapportent tous les deux a cette valeur.
     */
    val thresholdOnG: Double by lazy {
        val v = pre.thresholds.on.v
        val acc = ArrayList<Double>(v.size / 50 + 1)
        var i = 0
        while (i < v.size) {
            val x = v[i]
            if (x.isFinite()) acc.add(x.toDouble())
            i += 50
        }
        medianOf(acc)
    }

    /**
     * Plancher **effectif** du detecteur, `Theta_on / k_on`, median sur la nuit. C'est la reference
     * d'amplitude reellement utilisee par la decision, et donc l'abscisse de la courbe T5.
     *
     * Derive de [thresholdOnG] plutot que recalcule : la mediane commute avec la division par une
     * constante positive, et les deux grandeurs doivent rester exactement coherentes — c'est leur
     * rapport, `k_on`, qui fait tout le sujet du balayage de `ThresholdPolicySweepTest`.
     */
    val effectiveFloorG: Double get() = thresholdOnG / kOn

    fun result(rule: SeriesRule): PlmiResult = indexOf(clms, rule)

    /**
     * Le meme calcul, applique a une verite terrain et au **meme** masque.
     *
     * @param events par defaut `accelTruth` entier, c'est-a-dire l'indice vrai a l'echelle
     *   accelerometrique. Le passer restreint aux evenements au-dessus de `Theta_on` donne l'indice
     *   qu'un detecteur **parfait applique cette politique de seuil** produirait : c'est l'attendu de
     *   T6, tandis que le rapport entre les deux est ce que T22 publie.
     */
    fun truthResult(rule: SeriesRule, events: List<TruthEvent> = truth.accelLegMovements): PlmiResult =
        indexOf(truthAsClms(events, truth.floorG.toFloat()), rule)

    /** Rythme fondamental estime (`SPEC-v2.md` §5), sur les CLM de sommeil consecutifs. */
    fun rhythm() = Rhythm.fromClms(retained, mask)

    /** Le meme ajustement, avec ses diagnostics d'adequation. Sortie de `RhythmMeasurementTest`. */
    fun rhythmFit() = Rhythm.fitFromClms(retained, mask)

    private fun indexOf(events: List<Clm>, rule: SeriesRule): PlmiResult {
        val cfg = if (rule == SeriesRule.AASM_V3) SeriesConfig.aasmV3() else SeriesConfig.wasm2016()
        // `SeriesBuilder` recoit TOUS les evenements — il a besoin des `LM_LONG` et des `TRUNCATED`
        // pour casser les series au bon endroit — mais indexe ses series sur la liste telle que
        // fournie, tandis que `Plmi.compute` interprete `clmIndices` sur la liste des seuls retenus.
        // Les deux contrats different : on re-indexe ici plutot que d'affaiblir l'un des deux appels.
        val built = SeriesBuilder.buildDetailed(events, mask, FS, cfg)
        val toRetained = IntArray(events.size) { -1 }
        var r = 0
        for (i in events.indices) if (events[i].isClm) { toRetained[i] = r; r++ }
        val series = built.series.map { s ->
            s.copy(clmIndices = s.clmIndices.map { toRetained[it] }.filter { it >= 0 }.toIntArray())
        }

        val kept = events.filter { it.isClm }
        val pi = Periodicity.ferriIndex(kept, mask, FS)
        val rhythm = Rhythm.fromClms(kept, mask)
        return Plmi.compute(
            clms = kept,
            series = series,
            mask = mask,
            fsHz = FS,
            rule = rule,
            respiratory = RespiratoryConfidence.HIGH,
            pi = pi,
            rhythm = rhythm,
            floorMode = FloorMode.BILATERAL,
            truncated = timeline.truncated,
            truncatedSeriesDropped = built.truncatedSeriesDropped,
            paramsHash = "regression",
        )
    }
}

/**
 * Chaine v2 complete, etapes −1 a 7, sur une nuit synthetique.
 *
 * Deux passes de pretraitement, comme le prevoit §3.1 : le detecteur de posture travaille sur
 * `g_chapeau`, donc apres l'etape 1, et ses frontieres coupent ensuite les fenetres du plancher.
 *
 * @param calibrated `false` desactive le troisieme terme du seuil (`f_cal x gainCal`). C'est le
 *   bras « calibration inactive » du test T11, dont l'assertion est **inversee**.
 */
internal fun analyse(
    night: SynthNight,
    nominalHz: Double = FS,
    cfg: PreprocessConfig = PreprocessConfig(),
    clmCfg: ClmConfig = ClmConfig(),
    postureCfg: PostureConfig = PostureConfig(),
    calibrated: Boolean = true,
): Analysis = analyseBlocks(night, night.blocks, nominalHz, cfg, clmCfg, postureCfg, calibrated)

/** Variante qui analyse un flux de blocs modifie (decimation T9) tout en gardant la verite terrain. */
internal fun analyseBlocks(
    night: SynthNight,
    blocks: List<SampleBlock>,
    nominalHz: Double = FS,
    cfg: PreprocessConfig = PreprocessConfig(),
    clmCfg: ClmConfig = ClmConfig(),
    postureCfg: PostureConfig = PostureConfig(),
    calibrated: Boolean = true,
): Analysis {
    // Les quatre valeurs de seuil vivent en double dans `dsp` et `detect` : on les synchronise ici,
    // sinon un balayage T12 sur `kOn` ne toucherait que la moitie de la chaine.
    val effCfg = cfg.copy(thresholds = clmCfg.thresholds.toParams())
    val cal = NightCalibration(
        sensor = null,
        gainCalG = if (calibrated) night.truth.gainCalG else Float.NaN,
        floorCalG = night.truth.floorG.toFloat(),
        snrCal = Float.NaN,
        gainSource = if (calibrated) GainSource.RITUAL else GainSource.NONE,
        outlierVsBaseline = false,
    )

    val pass1 = Preprocess.run(blocks, nominalHz, effCfg)
    val postures = PostureDetector.detect(pass1.gravity, pass1.timeline.segments, postureCfg)
    val bounds = postures.map { it.atIdx }.toIntArray()

    val pre = Preprocess.run(blocks, nominalHz, effCfg, bounds, cal)
    val clms = ClmDetector.detect(
        env = pre.envelope,
        floor = pre.floor,
        floorExtrapolated = pre.floorExtrapolated,
        gravity = pre.gravity,
        segments = pre.timeline.segments,
        blindZones = pre.timeline.blindZones,
        postures = postures,
        calibration = cal,
        cfg = clmCfg,
        postureCfg = postureCfg,
    )
    val mask = refinedMask(night.truth, pre.timeline)
    return Analysis(night, pre, postures, clms, mask, cal, clmCfg.thresholds.kOn)
}

/**
 * Masque de la verite terrain, dont le temps analysable est recalcule sur la ligne de temps
 * reellement obtenue : segments valides, prives des zones aveugles et de l'off-body.
 *
 * C'est la soustraction que fera la couche masque de §3.6 ; la faire ici garde le denominateur de la
 * mesure et celui de l'attendu **identiques**, ce qui est la condition pour que T10 mesure l'effet
 * des trous sur la detection et non sur l'arithmetique du denominateur.
 */
internal fun refinedMask(truth: GroundTruth, timeline: Timeline): SleepMask {
    val n = timeline.signal.n
    val fs = timeline.signal.fsHz
    if (n == 0) return truth.mask
    val ok = BooleanArray(n)
    for (s in timeline.segments) for (i in s.fromIdx until minOf(s.toIdx, n)) ok[i] = true
    for (z in timeline.blindZones) for (i in maxOf(0, z.fromIdx) until minOf(z.toIdx, n)) ok[i] = false
    for (z in timeline.offBody) for (i in maxOf(0, z.fromIdx) until minOf(z.toIdx, n)) ok[i] = false

    var tstSamples = 0
    var sptSamples = 0
    for (w in truth.mask.windows) {
        val from = Math.round(w.startMsRel * fs / 1000.0).toInt().coerceIn(0, n)
        val to = Math.round(w.endMsRel * fs / 1000.0).toInt().coerceIn(0, n)
        var c = 0
        for (i in from until to) if (ok[i]) c++
        if (w.stage == Stage.SLEEP) tstSamples += c
        if (w.stage != Stage.OUT_OF_BED) sptSamples += c
    }
    val toMin = 1.0 / (fs * 60.0)
    return truth.mask.copy(
        analysableTstMin = tstSamples * toMin,
        analysableSptMin = sptSamples * toMin,
    )
}

// ---------------------------------------------------------------------------------------------
// Fabriques de nuits
// ---------------------------------------------------------------------------------------------

/** Structure veille/sommeil compacte, pour les scenarios courts (T1 a T5). */
internal fun shortSleep(): SleepSpec = SleepSpec(sleepLatencyMin = 1.0, finalWakeMin = 1.0, wasoCount = 0)

/**
 * Nuit nominale de T6 : tous les distracteurs, un `aPLM-i` vrai de l'ordre de 25/h **a l'echelle
 * accelerometrique**.
 *
 * Le nombre de mouvements injectes est volontairement plus grand que le compte vise : 39 % d'entre
 * eux sont des rotations pures de cheville et ne deplacent pas le capteur (Terrill), et la queue
 * basse de la loi d'amplitude passe sous le seuil de visibilite physique. Viser 25/h a l'echelle EMG
 * donnerait environ 15/h a l'echelle accelerometrique — soit exactement au seuil de l'ICSD-3, ce qui
 * ferait basculer la moitie des nuits d'un cote ou de l'autre pour rien.
 */
internal fun nominalNight(
    seed: Long,
    durationH: Double = 8.0,
    fsRealHz: Double = FS,
    distractors: DistractorSpec = DistractorSpec.ALL,
    gainMultiplier: Double = 1.0,
): SynthNight = NightSynth.generate(
    NightSpec(
        durationH = durationH,
        fsRealHz = fsRealHz,
        // Le nombre de series suit la duree : c'est le TAUX qui doit rester constant d'un scenario
        // a l'autre, sinon une nuit courte deviendrait une nuit tres severe.
        trueSeries = listOf(
            SeriesSpec(
                nSeries = Math.round(4.25 * durationH).toInt().coerceAtLeast(1),
                clmPerSeries = 9,
                imiMeanSec = 22.0,
                imiCvPct = 22.0,
            ),
        ),
        isolatedClmPerHour = 6.0,
        gainMultiplier = gainMultiplier,
        distractors = distractors,
    ),
    seed,
)

/** Nuit negative de T7 : `aPLM-i` vrai de l'ordre de 2/h, tous distracteurs actifs. */
internal fun negativeNight(seed: Long): SynthNight = NightSynth.generate(
    NightSpec(
        durationH = 8.0,
        trueSeries = listOf(SeriesSpec(nSeries = 4, clmPerSeries = 5, imiMeanSec = 24.0, imiCvPct = 25.0)),
        isolatedClmPerHour = 1.0,
        distractors = DistractorSpec.ALL,
    ),
    seed,
)

/** Nuit sans aucun mouvement : le distracteur nomme est le seul contenu du signal. */
internal fun distractorOnlyNight(
    seed: Long,
    minutes: Double,
    distractors: DistractorSpec,
    noise: NoiseSpec = NoiseSpec(),
): SynthNight = NightSynth.generate(
    NightSpec(
        durationH = minutes / 60.0,
        trueSeries = emptyList(),
        isolatedClmPerHour = 0.0,
        distractors = distractors,
        noise = noise,
        sleep = shortSleep(),
    ),
    seed,
)

/**
 * Nuit de CLM isoles a amplitude **imposee sur l'echelle de l'enveloppe grossiere**, pour le
 * balayage de la courbe de sensibilite (T5).
 */
internal fun fixedAmplitudeNight(
    seed: Long,
    minutes: Double,
    envelopeAmplitudeG: Double,
    spacingSec: Double = 20.0,
): SynthNight {
    // Espacement quasi regulier plutot que des arrivees exponentielles : deux evenements qui se
    // recouvrent fusionnent en une seule detection et feraient chuter la sensibilite mesuree pour
    // une raison qui n'a rien a voir avec l'amplitude.
    val count = ((minutes * 60.0 - 120.0) / spacingSec).toInt().coerceAtLeast(4)
    return NightSynth.generate(
        NightSpec(
            durationH = minutes / 60.0,
            trueSeries = listOf(SeriesSpec(1, count, spacingSec, 5.0)),
            isolatedClmPerHour = 0.0,
            ankleOnlyFraction = 0.0,
            visibilityG = 0.0,
            amplitude = AmplitudeSpec(fixedG = envelopeAmplitudeG, scale = AmplitudeScale.COARSE_ENVELOPE),
            distractors = DistractorSpec.NONE,
            sleep = shortSleep(),
        ),
        seed,
    )
}

/**
 * Plancher effectif du detecteur sur une nuit de bruit seul, meme specification de bruit.
 * Sert a normaliser l'abscisse du balayage T5 sans faire tourner le detecteur sur la nuit de test.
 *
 * @param clmCfg la configuration dont on sonde le plancher. Elle est un parametre et non une
 *   constante parce que le plancher effectif vaut `Theta_on / k_on` : sur une nuit calme c'est
 *   `Theta_abs` qui l'emporte, donc l'abscisse de T5 **depend** de `k_on`. Sonder avec la
 *   configuration par defaut tout en detectant avec une autre placerait les evenements ailleurs que
 *   la ou l'enonce de T5 les veut.
 */
internal fun probeEffectiveFloorG(seed: Long, clmCfg: ClmConfig = ClmConfig()): Double {
    val probe = distractorOnlyNight(seed, minutes = 12.0, distractors = DistractorSpec.NONE)
    return analyse(probe, clmCfg = clmCfg, calibrated = false).effectiveFloorG
}

/**
 * Sous-ensemble d'une verite terrain au-dessus d'une amplitude donnee, sur l'echelle de l'enveloppe
 * grossiere — celle que le detecteur compare a `Theta_on`.
 *
 * C'est l'operation qui distingue les trois denominateurs de §4.1 du document de validation :
 * « mecaniquement present dans le signal » (`accelTruth` entier), « au-dessus du garde-fou absolu »
 * et « au-dessus du seuil que le detecteur applique reellement cette nuit-la ».
 */
internal fun aboveEnvelope(events: List<TruthEvent>, amplitudeG: Double): List<TruthEvent> =
    events.filter { it.envPeakG >= amplitudeG }

/**
 * Rythme fondamental **reellement injecte** cette nuit-la : moyenne geometrique des intervalles
 * onset-a-onset entre mouvements consecutifs d'une meme serie, a l'echelle EMG.
 *
 * Mesure plutot que lue dans `NightSpec.imiMeanSec` : la loi est tronquee a [2 ; 120] s et les series
 * sont placees dans des creneaux, si bien que la valeur realisee n'est pas exactement la valeur
 * demandee. Comparer l'estimation a une consigne plutot qu'a la realisation ferait porter a la
 * deconvolution une erreur qui n'est pas la sienne.
 *
 * La moyenne geometrique, et non arithmetique, parce que `fundamentalSec = exp(mu)` est la
 * **mediane** de la log-normale ajustee : c'est la meme grandeur des deux cotes de la comparaison.
 */
internal fun injectedFundamentalSec(truth: GroundTruth): Double {
    val logs = ArrayList<Double>()
    truth.emgTruth
        .filter { it.kind == TruthKind.PLM_IN_SERIES && it.seriesId != null }
        .groupBy { it.seriesId }
        .forEach { (_, events) ->
            val ordered = events.sortedBy { it.onsetMsRel }
            for (i in 1 until ordered.size) {
                val d = (ordered[i].onsetMsRel - ordered[i - 1].onsetMsRel) / 1000.0
                if (d > 0.0) logs.add(ln(d))
            }
        }
    return if (logs.isEmpty()) Double.NaN else exp(logs.sum() / logs.size)
}
