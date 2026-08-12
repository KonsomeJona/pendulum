package com.pendulum.phone.bench

import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.indices.Periodicity
import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.mask.MaskFusion
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.Stage
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth
import com.pendulum.algo.synth.SeriesSpec
import com.pendulum.algo.synth.SynthNight
import com.pendulum.algo.synth.truthAsClms
import com.pendulum.phone.work.AnalysisParams
import kotlin.random.Random

/**
 * The **pure** half of the seeding: what a bench night is made of, and what result it produces.
 * No Android, no database — that is what makes it verifiable on the JVM, and `CampaignTest`
 * makes use of it.
 *
 * ### Why this separation exists, and what it caught
 *
 * The first version wrote its nine nights into the database with nothing checking what they were
 * worth. On the device, the Trend screen stayed closed: the nights were indeed comparable and
 * publishable, but **the rhythm fit was refused on all nine**, and `TrendUiState` requires at
 * least three nights whose rhythm is identified. The seeding had therefore reached every one of
 * its intermediate goals while missing the only one that counted.
 *
 * The refusal was not a defect: `Rhythm` refuses when the interval train it is handed identifies
 * no period, and a night where isolated movements and RRLMs are interleaved between the series
 * hands it one that identifies none. This is measured and documented (`RhythmMeasurementTest`:
 * 2 fits accepted out of 20 nominal nights). What was needed was therefore to **choose a more
 * periodic night**, which the generator knows how to do, and to prove it somewhere other than on
 * a phone.
 */
internal object Campaign {

    /** The nature of a night. The three states that `Mapping.nightUi` knows how to render. */
    enum class Kind { ELIGIBLE, PROVISIONAL, EXCLUDED }

    /** Frequency of the ground truth reference grid. See `synth.TARGET_FS_HZ`. */
    const val FS_HZ = 50.0

    /**
     * Base seed. Fixed: two successive seedings produce the same campaign, so two captures of the
     * same screen are comparable. A seed drawn from the clock would make any display regression
     * indistinguishable from a change of data.
     */
    private const val SEED = 20_260_807L

    /**
     * The order of the nights, from the oldest to the most recent. It is not immaterial:
     *
     *  - the **first** sealed night is the reference for the whole comparability criterion
     *    (`comparable_night` takes `MIN(sealedAtMs)`), so it has to be an ordinary one — if the
     *    campaign began with the night when one was not alone in bed, every night after it would
     *    be judged against that one;
     *  - the **last** is the one the waking status band and the home card speak about, so it is
     *    eligible too.
     */
    fun kinds(eligibleNights: Int): List<Kind> = buildList {
        add(Kind.ELIGIBLE)
        add(Kind.EXCLUDED)
        add(Kind.PROVISIONAL)
        repeat(eligibleNights - 1) { add(Kind.ELIGIBLE) }
    }

    fun seed(index: Int): Long = SEED + index * 7_919L

    /**
     * A bench night: what the generator produced, and what stages 6 and 7 of `:algo` draw from it.
     *
     * @param measurements the two results on the Health Connect mask, one per rule set. There are
     *   not four of them: the accelerometric mask is the product of the signal processing chain,
     *   precisely the one this seeding does not run, and manufacturing it would amount to
     *   inventing a denominator instead of measuring it.
     */
    class Night(
        val kind: Kind,
        val synth: SynthNight,
        val analysableMin: Double,
        val mask: SleepMask,
        val clms: List<Clm>,
        val measurements: List<PlmiResult>,
    ) {
        val spec: NightSpec get() = synth.spec
        val recordedDurationMin: Double get() = spec.recordedH * 60.0
        val truncated: Boolean get() = synth.truth.truncatedAtMs != null

        /** The result the interface reads: `AASM_V3` on the Health Connect mask. */
        val primary: PlmiResult get() = measurements.first()
    }

    /**
     * Builds a night and puts it through the **same** stages 6 and 7 as a real measurement:
     * `Periodicity`, `Rhythm`, `SeriesBuilder` and `Plmi.compute` are called exactly as
     * `NightAnalyzer.computeOne` calls them. The invariants of the result — the publication gate,
     * the independence of the denominator, the refusal to fit the rhythm — are therefore those of
     * the product.
     */
    fun night(kind: Kind, index: Int, params: AnalysisParams = AnalysisParams.DEFAULT): Night {
        val nightSeed = seed(index)
        val synth = NightSynth.generate(recipe(kind, Random(nightSeed)), nightSeed)
        val truth = synth.truth

        // The genuinely analysable time: the recorded duration minus the FIFO gaps and the
        // off-wrist periods. It is the same subtraction the `:algo` timeline performs; it is
        // redone here because the timeline is only produced by the signal processing chain.
        val durationMin = synth.spec.recordedH * 60.0
        val lostMin = truth.gaps.sumOf { it.durationSec } / 60.0 +
            truth.mask.windows.filter { it.stage == Stage.OUT_OF_BED }.sumOf { it.durationMin }
        val analysableMin = (durationMin - lostMin).coerceAtLeast(0.0)
        val coverage = (analysableMin / durationMin).coerceIn(0.0, 1.0)

        val mask = MaskFusion.fromHealthConnect(truth.mask.windows, coverage)
        val clms = truthAsClms(truth.accelLegMovements, truth.floorG.toFloat())
        val truncated = truth.truncatedAtMs != null

        val pi = Periodicity.ferriIndex(clms, mask, FS_HZ, params.periodicity)
        val rhythm = Rhythm.fromClms(clms, mask, params.rhythm)

        // The same segmentation as the timeline would produce from those holes. `SeriesBuilder`
        // ends a series at a recording restart (WASM 3.3.3); a bench that omitted the segments
        // would score the app against a night without holes and would report an index the app never
        // computes.
        val segments = Segment.between(truth.gaps, Math.round(synth.spec.recordedH * 3600.0 * FS_HZ).toInt())

        val measurements = listOf(SeriesConfig.aasmV3(), SeriesConfig.wasm2016()).map { cfg ->
            val detailed = SeriesBuilder.buildDetailed(clms, mask, FS_HZ, cfg, segments)
            Plmi.compute(
                clms = clms,
                series = detailed.series,
                mask = mask,
                fsHz = FS_HZ,
                rule = cfg.rule,
                pi = pi,
                rhythm = rhythm,
                floorMode = FloorMode.BILATERAL,
                truncated = truncated,
                cfg = params.plmi,
                truncatedSeriesDropped = detailed.truncatedSeriesDropped,
                paramsHash = params.paramsHash,
            )
        }

        return Night(kind, synth, analysableMin, mask, clms, measurements)
    }

    /**
     * A night different from the previous one, but not so different that it leaves the campaign.
     *
     * ### The three constraints, and they contradict each other
     *
     *  1. **Stay comparable.** `gainMultiplier` moves by +/- 6 % only: the tolerance of the rule
     *     is 35 %, but what that tolerance absorbs is the play of the strap, not a variation
     *     decided here. And the recorded duration stays above four analysable hours, below which
     *     the night would come out `TOO_SHORT`.
     *  2. **Stay publishable.** More than four hours of analysable sleep, failing which the gate
     *     falls to `TRUNCATED_NO_TREND` — which is the state wanted for the provisional night, and
     *     for it alone.
     *  3. **Carry an identifiable rhythm.** This is the constraint that cost the most, and the one
     *     that explains the four distractors switched off below. `Rhythm` fits a mixture of
     *     harmonics on the intervals between consecutive movements; the families that
     *     **interleave aperiodic movements between the series** — RRLM, bursts, ALMA, and isolated
     *     movements in too great a number — manufacture intervals that belong to no harmonic, the
     *     total variation distance explodes, and the fit is refused (`GEOMETRIC_MISFIT`). Cutting
     *     them does not make the night unrealistic: it describes a sleeper whose periodic
     *     movements dominate, which is exactly the clinical case the product measures. The eight
     *     other families stay, among them posture changes, gross body movements, breathing, the
     *     mattress, the gaps and the off-wrist periods.
     *
     * `minIntervals` of `RhythmConfig` is 30: at least thirty intervals must therefore be kept
     * inside sleep, hence a generous series count and series length.
     */
    fun recipe(kind: Kind, r: Random): NightSpec {
        val durationH = 7.4 + r.nextDouble() * 1.2
        val base = NightSpec(
            durationH = durationH,
            trueSeries = listOf(
                SeriesSpec(
                    nSeries = 20 + r.nextInt(10),
                    clmPerSeries = 7 + r.nextInt(5),
                    imiMeanSec = 19.0 + r.nextDouble() * 6.0,
                    imiCvPct = 14.0 + r.nextDouble() * 8.0,
                )
            ),
            isolatedClmPerHour = 0.5 + r.nextDouble() * 1.5,
            gainMultiplier = 0.94 + r.nextDouble() * 0.12,
            distractors = DistractorSpec.ALL.copy(
                rrlmSeriesCount = 0,
                clusterCount = 0,
                almaCountMin = 0,
                almaCountMax = 0,
            ),
        )
        return when (kind) {
            // The provisional night is a **truncated** night: the watch stopped before waking. It
            // stays comparable — same leg, same strap, more than four analysable hours — but its
            // publication gate falls to `TRUNCATED_NO_TREND`, because the index of a truncated
            // night is biased upwards with no correction possible. That is exactly the third
            // state of `Mapping.nightUi`.
            Kind.PROVISIONAL -> base.copy(truncateAtH = durationH * 0.72)
            Kind.ELIGIBLE, Kind.EXCLUDED -> base
        }
    }
}
