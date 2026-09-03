package com.pendulum.phone.export

import android.content.Context
import android.content.res.Resources
import com.pendulum.phone.data.TrendState
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.NightState
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.work.WorkScheduler
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The report, meant to be read by a sleep physician.
 *
 * ### Why it is in English when it used to be in French
 *
 * It used to be, and that was an inconsistency rather than a decision: the whole interface has
 * been in English since the repository became public (see the KDoc of `TextsTest`, whose banned
 * roots are English for that reason), and this document is **the only thing the application
 * produces for a third party**. A report written in a language its bearer does not read cannot be
 * read over before being handed on, therefore cannot be corrected nor stood behind by the person
 * handing it on — and this whole file rests on the idea that a document circulates without its
 * context and must therefore carry its own limits. The document now follows the language of the
 * interface that produced it.
 *
 * ### The framing, written into the report itself and not only into the documentation
 *
 * The user is **already** under treatment. Without a reference period without treatment, this
 * device cannot measure the effect of the treatment: it measures the variability under treatment.
 * The only defensible objective is to **obtain a real examination**, not to replace one, and no
 * dose is adjusted from this figure. That sentence is in the report because a report circulates
 * without its context.
 *
 * ### What the report shows, in this order
 *
 * 1. the **fundamental rhythm** in seconds and the periodicity — the follow-up metric, with no
 *    denominator, therefore with no circularity, and twelve times more stable from one night to
 *    the next. It is often **absent**, and the report then says why: the model refuses to fit a
 *    period that the intervals do not identify, and it refuses far more often than it accepts;
 * 2. the **hourly count** with its explicit denominator, its two rule sets and its two masks —
 *    this is the language of sleep physicians, and the published thresholds rest on it — preceded
 *    by the three figures that say **on what scale** it is to be read;
 * 3. the **estimated miss rate**, without which the previous two cannot be read;
 * 4. the quality and the limits, named separately and never netted off against one another.
 *
 * ### What the report does not do
 *
 * It writes no verb of change ("improved", "worsened", "decreased"). A variation between two
 * nights below the smallest detectable change is not a change, it is night-to-night variability —
 * and the variability of the hourly count is of the order of 43 % of its mean. The report gives
 * values and bounds; the interpretation belongs to the reader.
 *
 * ### Two documents, and not one
 *
 * [exportNight] reports on **one** night: it is the document of an anomaly, the one produced when
 * a particular night raises a question. [exportCampaign] reports on **the campaign**, with its
 * aggregates, their uncertainty and their `n` — it is the one put in front of a physician,
 * because an isolated night means nothing and the product refuses to aggregate below three
 * nights.
 */
object ReportExporter {

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.UK).withZone(ZoneId.systemDefault())

    // -------------------------------------------------------------------------------------
    // The campaign report — the one put in front of a physician
    // -------------------------------------------------------------------------------------

    /**
     * The campaign report, built from the **already computed** state of the trend.
     *
     * It recomputes nothing: the medians, the deterministic-seed bootstrap intervals and the
     * smallest detectable change come from `Aggregate` by the same path as the screen. A report
     * that recomputed on its own side could show a figure other than the one on the screen, and
     * that is exactly the discrepancy one would not be able to explain in front of a physician.
     *
     * @param includeExcluded when it is false, the table does not carry the excluded nights — but
     *   their number is still written. Removing nights from a medical document without saying how
     *   many would be picking one's evidence in silence.
     */
    suspend fun exportCampaign(
        context: Context,
        state: TrendState,
        includeQuestionnaire: Boolean,
        includeExcluded: Boolean,
        out: OutputStream,
    ) {
        val db = PendulumDatabase.get(context)
        val params = WorkScheduler.activeParams(context)
        // The report reads exactly the same strings as the screen: a second formatting somewhere
        // would be a second truth about the same figure.
        val res = context.resources
        val zone = runCatching { ZoneId.of(state.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val nights = state.nights.sortedBy { it.startWallMs }
        val questionnaires = if (includeQuestionnaire) db.questionnaireDao().all() else emptyList()

        val text = buildString {
            appendLine("# Pendulum — report for the physician")
            appendLine()
            appendLine(PREAMBLE)
            appendLine()

            appendLine("## The campaign")
            appendLine()
            if (nights.isEmpty()) {
                appendLine("No night recorded.")
            } else {
                appendLine("- Period: ${nights.first().readableDate} → ${nights.last().readableDate}")
            }
            appendLine(
                "- Nights recorded: ${state.recordedNights} · eligible: ${state.eligibleNights} " +
                    "· excluded: ${state.excludedNights}"
            )
            state.customProfile?.let {
                appendLine("- Non-default parameter profile in use: `$it`")
            }
            if (state.mixedHashes) {
                appendLine("- **Two parameter sets are present across the recorded nights.** " +
                    "Only the nights of the active set enter the figures below.")
            }
            appendLine()

            appendLine("## Follow-up metric — fundamental rhythm")
            appendLine()
            val rhythm = state.rhythm
            if (rhythm == null) {
                appendLine(
                    "Not reported: ${state.eligibleNights} eligible night(s) and " +
                        "${state.nightsWithFittedRhythm} of them with an accepted rhythm fit, for " +
                        "${Aggregate.MIN_NIGHTS_AGGREGATE} required."
                )
                appendLine()
                appendLine(RHYTHM_REFUSED)
            } else {
                appendLine(aggregateRow(rhythm, "s"))
                appendLine("- Night-to-night dispersion: ${fmt(rhythm.dispersion)} s")
                appendLine("- Smallest detectable change (95 %): ${fmt(rhythm.mdc95)} s")
                appendLine()
                appendLine(RHYTHM_NO_THRESHOLD)
            }
            appendLine()

            appendLine("## Hourly count — aPLM-i")
            appendLine()
            appendLine(APLMI_NOTE)
            appendLine()
            appendLine(UNDER_COUNTING)
            appendLine()
            val count = state.count
            if (count == null) {
                appendLine(
                    "Not reported: ${state.eligibleNights} eligible night(s) for " +
                        "${Aggregate.MIN_NIGHTS_AGGREGATE} required."
                )
            } else {
                appendLine(aggregateRow(count, "/h"))
                appendLine("- Night-to-night dispersion: ${fmt(count.dispersion)} /h")
                appendLine("- Smallest detectable change (95 %): ${fmt(count.mdc95)} /h")
                appendLine()
                // The position sentence is the screen's, word for word. There are only five of
                // them in the whole application, and none of them states a direction.
                Aggregate.position(count.ciLow, count.ciHigh, count.nights).sentence()
                    ?.let { appendLine(it.resolve(res)) }
            }
            appendLine()

            appendLine("## Estimated miss rate and periodicity")
            appendLine()
            appendLine("- Median estimated miss rate: ${fmt(state.medianMissRate)}")
            appendLine("- Median periodicity index: ${fmt(state.medianPeriodicity)}")
            appendLine()
            appendLine(MISS_RATE_NOTE)
            appendLine()

            appendLine("## Night by night")
            appendLine()
            appendLine("| Date | From → to | Analysable sleep | Sleep source | Rhythm (s) | aPLM-i (/h) | State | Reason |")
            appendLine("|---|---|---|---|---|---|---|---|")
            val retained = if (includeExcluded) nights else nights.filter { it.state != NightState.EXCLUDED }
            for (n in retained) appendLine(nightRow(n, res))
            appendLine()
            if (!includeExcluded && state.excludedNights > 0) {
                appendLine(
                    "**${state.excludedNights} excluded night(s) are not listed above**, at the " +
                        "request of the person who produced this document. They are counted in " +
                        "the campaign totals and were never part of the aggregates."
                )
                appendLine()
            }
            appendLine(EXCLUSION_NOTE)
            appendLine()

            if (includeQuestionnaire) {
                appendLine("## Screening questionnaire")
                appendLine()
                if (questionnaires.isEmpty()) {
                    appendLine("Not filled in.")
                } else {
                    for (q in questionnaires) {
                        appendLine("- ${format(q.answeredAtMs, zone)} · `${q.kind}` · ${q.answersJson}")
                    }
                }
                appendLine()
                appendLine(QUESTIONNAIRE_NOTE)
                appendLine()
            }

            appendLine("## Traceability")
            appendLine()
            appendLine("- Parameter set: `${params.paramsHash}` · algorithm version: `${params.algoVersion}`")
            appendLine("- Nights whose result was never asked for: " +
                nights.count { it.revealedAtMs == null })
            appendLine("- Report generated on ${format(System.currentTimeMillis(), zone)}")
            appendLine()
            appendLine(LIMITS)
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // -------------------------------------------------------------------------------------
    // The report of one night
    // -------------------------------------------------------------------------------------

    suspend fun exportNight(context: Context, sessionHex: String, out: OutputStream) {
        val db = PendulumDatabase.get(context)
        val params = WorkScheduler.activeParams(context)
        // The report reads exactly the same strings as the screen: a second formatting somewhere
        // would be a second truth about the same figure.
        val res = context.resources
        val session = db.nightDao().find(sessionHex) ?: error("unknown session: $sessionHex")
        val results = db.derivedDao().resultsOf(sessionHex, params.paramsHash)
        val nightContext = db.contextDao().findForSession(sessionHex)
        // The last **reading**, not the last attempt: `hc_snapshot` logs every rung of the fetch
        // ladder, and the ladder carries on after a success. With `latest` here, a rung that read
        // nothing at T+4 h made the "Denominator" section print `NO_HC_NOTE` — "no external
        // hypnogram … circular" — under a table that still carried `HEALTH_CONNECT` rows: the
        // figure and its explanation contradicted each other in the document handed to the
        // physician.
        val hc = db.hcSnapshotDao().latestWithSession(sessionHex)
        val comparable = db.trendDao().forNight(sessionHex, params.paramsHash).firstOrNull()

        val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val text = buildString {
            appendLine("# Pendulum — night of ${format(session.startWallMs, zone)}")
            appendLine()
            appendLine(PREAMBLE)
            appendLine()
            appendLine(SINGLE_NIGHT)
            appendLine()

            appendLine("## Follow-up metric")
            appendLine()
            val primary = results.firstOrNull { it.maskSource == "HEALTH_CONNECT" }
                ?: results.firstOrNull()
            if (primary == null) {
                appendLine("No result computed for this night.")
            } else {
                if (primary.rhythmValid) {
                    appendLine("- Fundamental rhythm: ${fmt(primary.fundamentalSec)} s")
                } else {
                    appendLine("- Fundamental rhythm: **not reported for this night**")
                    appendLine()
                    appendLine(RHYTHM_REFUSED)
                    appendLine()
                }
                appendLine("- Periodicity index: ${fmt(primary.periodicityIndex)}" +
                    if (primary.periodicityValid) "" else " (not valid: too few intervals)")
                appendLine("- Estimated miss rate: ${fmt(primary.missRate)}")
                if (primary.alternationSuspect) {
                    appendLine(
                        "- Harmonic profile consistent with **left/right alternation**: a " +
                            "one-sided sensor then sees a doubled interval."
                    )
                }
            }
            appendLine()

            appendLine("## Hourly count — aPLM-i")
            appendLine()
            appendLine(APLMI_NOTE)
            appendLine()
            appendLine(UNDER_COUNTING)
            appendLine()
            appendLine(NIGHT_TABLE_HEADER)
            appendLine("|---|---|---|---|---|---|---|")
            for (r in results.sortedWith(compareBy({ it.maskSource }, { it.rule }))) {
                appendLine(row(r))
            }
            appendLine()

            appendLine("## Denominator")
            appendLine()
            if (hc?.selectedPackage != null) {
                appendLine("- Hypnogram source: `${hc.selectedPackage}`")
                appendLine("- Distinct stages: ${hc.distinctStageTypes} · segments: ${hc.stageCount}")
                appendLine("- Coverage by stages: ${fmt(hc.stageCoverageMin)} min")
                hc.aggregateTstMin?.let {
                    appendLine("- Cross-check with `aggregate()` (de-duplicated by the system): ${fmt(it)} min")
                }
                appendLine("- Last modified on the provider side: ${hc.lastModifiedTimeMs?.let { format(it, zone) } ?: "unknown"}")
            } else {
                appendLine(NO_HC_NOTE)
            }
            appendLine()

            appendLine("## Recording quality")
            appendLine()
            appendLine("- Analysable duration: ${fmt(session.analysableMin)} min")
            appendLine("- Gaps: ${session.gapCount} (${session.gapTotalMs / 1000} s in total)")
            appendLine("- Measured sampling rate: ${session.fsMeasuredHz?.let { fmt(it) } ?: "?"} Hz")
            appendLine("- Blocks rejected by the integrity check: ${fmt(session.integrityRejectedFraction * 100)} %")
            if (session.truncated) appendLine("- **Truncated night**: index biased upwards, kept out of the trend.")
            session.batteryPctLast?.let { appendLine("- Last watch battery level reported: $it %") }
            session.stopReason?.let { appendLine("- Stop reason announced: $it") }
            appendLine()

            appendLine("## Comparability")
            appendLine()
            if (comparable == null) {
                appendLine("Not assessed (no result for the current parameter set).")
            } else if (comparable.comparable) {
                appendLine("Night comparable with the other nights of the campaign.")
            } else {
                appendLine("Night **kept out of the trend**, reason: `${comparable.exclusionReason}`.")
                appendLine()
                appendLine(EXCLUSION_NOTE)
            }
            appendLine()

            appendLine("## Evening context (sealed before the recording)")
            appendLine()
            if (nightContext == null) {
                appendLine("No context sealed for this night.")
            } else {
                appendLine("- Sealed on: ${format(nightContext.sealedAtMs, zone)}")
                appendLine("- Leg: ${nightContext.leg} · strap: ${nightContext.strapId}")
                appendLine("- Alone in bed: ${if (nightContext.aloneInBed) "yes" else "no"}")
                appendLine("- Medication: ${nightContext.medicationJson}")
                appendLine("- Alcohol: ${nightContext.alcoholUnits} unit(s) · caffeine after 16:00: " +
                    if (nightContext.caffeineAfter16h) "yes" else "no")
                nightContext.notes?.let { appendLine("- Notes: $it") }
            }
            appendLine()

            appendLine("## Traceability")
            appendLine()
            appendLine("- Parameter set: `${params.paramsHash}` · algorithm version: `${params.algoVersion}`")
            appendLine("- Report generated on ${format(System.currentTimeMillis(), zone)}")
            appendLine("- Result asked for on: " +
                (session.revealedAtMs?.let { format(it, zone) } ?: "never"))
            appendLine()
            appendLine(LIMITS)
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // -------------------------------------------------------------------------------------

    /**
     * The header of the single-night table, and the row that goes under it. Both `internal` so
     * that `ReportTableTest` can pin the pairing between the sixth cell and `plmiRespWorstCase`.
     *
     * That cell used to read "Respiratory upper bound". `plmiRespWorstCase` is the index
     * recomputed after removing every series whose median interval falls in the apnoeic band — by
     * construction (`Indices.kt`, `plmsCountRespWorst <= plmsCount` over the same denominator) it
     * is never above the aPLM-i beside it: a **lower** bound, exactly as the night detail names
     * it (`ComputationPath`) and as the LIMITS block below now says. The report therefore printed
     * `12.40 /h | 8.20 /h` under "aPLM-i | Respiratory upper bound": an upper bound smaller than
     * the value. A physician reading that either distrusts the table or reads 12.4 as already
     * corrected for respiration — the one misreading the column exists to prevent.
     */
    internal const val NIGHT_TABLE_HEADER =
        "| Rules | Mask | Movements | Analysable sleep | aPLM-i | " +
            "aPLM-i excluding apnoeic-band series (lower bound) | Publication |"

    internal fun row(r: PlmResultEntity): String =
        "| ${r.rule} | ${r.maskSource} | ${r.plmsCount} | ${fmt(r.analysableTstMin)} min | " +
            "${fmt(r.plmi)} /h | ${fmt(r.plmiRespWorstCase)} /h | ${r.gate} |"

    private fun nightRow(n: NightUi, res: Resources): String = listOf(
        n.readableDate,
        "${n.start} → ${n.end}",
        n.readableSleep,
        n.sleepSource.resolve(res),
        n.rhythmSec?.let { fmt(it) } ?: "—",
        fmt(n.plmiCount),
        when (n.state) {
            NightState.ELIGIBLE -> "eligible"
            NightState.PROVISIONAL -> "provisional"
            NightState.EXCLUDED -> "excluded"
        },
        n.reason?.resolve(res) ?: "",
    ).joinToString(" | ", prefix = "| ", postfix = " |")

    /**
     * A median never leaves without its interval or its `n`: that is P2, and the report is the
     * last place where one could be tempted to keep only the round figure.
     */
    private fun aggregateRow(r: Aggregate.Result, unit: String): String {
        val label = if (r.ciCalibrated) "95 % CI" else "interval (not calibrated below ${Aggregate.MIN_NIGHTS_CALIBRATED_CI} nights)"
        return "- Median: ${fmt(r.median)} $unit — $label ${fmt(r.ciLow)}–${fmt(r.ciHigh)} $unit, " +
            "n = ${r.nights} nights"
    }

    /**
     * `21,34` in French and `21.34` in English **are not the same document**, and a report that a
     * physician copies out or that a spreadsheet reads back must have a single decimal
     * punctuation. The rest of the module already pins `Locale.UK` everywhere — `Mapping`,
     * `Checks`, `P1Gate`, `P1GateExporter`, whose KDoc documents precisely this trap; this file
     * was the only one to have missed it, and it is the one that leaves the application.
     */
    /**
     * `18.40`, or the dash. It accepts `null` now that rates with no denominator are `NULL` in the
     * database rather than `NaN`: both say the same thing — the quantity does not exist for this
     * night — and the medical report must say so rather than write `0.00`.
     */
    private fun fmt(v: Double?): String =
        if (v != null && v.isFinite()) "%.2f".format(Locale.UK, v) else "—"

    private fun format(ms: Long, zone: ZoneId): String = stamp.withZone(zone).format(Instant.ofEpochMilli(ms))

    private val PREAMBLE = """
        This document is produced by a personal device that has never been clinically validated:
        the accelerometer of a consumer smartwatch worn at the ankle. It replaces no examination.

        The person measured is already under treatment. Without a reference period without
        treatment, this device cannot measure the effect of that treatment — it measures the
        night-to-night variability under treatment. No dose should be adjusted from these figures.
    """.trimIndent()

    /**
     * The report of a single night says that it is the report of a single night.
     *
     * It is the only one of the two documents that can be read as a result, and it must not be:
     * the night-to-night variability of the hourly count is of the order of 43 % of its mean, so a
     * night taken alone situates nothing. The product refuses to aggregate below three nights; a
     * single-night document that did not say so would get around that refusal through the back
     * door.
     */
    private val SINGLE_NIGHT = """
        **This is the report of a single night, and a single night situates nothing.** The
        night-to-night variability of the hourly count is of the order of 43 % of its mean, so the
        figures below describe this night and not the person. Pendulum computes no aggregate below
        three eligible nights; the campaign report is the document meant to be read as a whole.
    """.trimIndent()

    private val APLMI_NOTE = """
        The metric is called **aPLM-i** — "ankle periodic limb movement index, estimated, not
        validated" — and not PLMI. This is not a wording precaution: 39 % of the movements scored
        by electromyography come with no movement detectable by accelerometry (a pure
        dorsiflexion does not displace a sensor sitting above the joint axis). The accelerometric
        count is therefore on a **different scale** from the polysomnographic count, and the
        published threshold of 15/h does not transpose onto it.

        That 39 % comes from Terrill et al. (2013), on nine subjects, with a between-subject range
        of 4.8 to 69.6 %, and it was **measured at the great toe**. An ankle-worn sensor sits above
        the joint axis and moves less than the toe does, so the proportion of movements it misses
        is higher than 39 % — by an amount that has not, to this project's knowledge, been
        published. The figures in this report are therefore an underestimate of unknown size, and
        that is on top of the threshold policy described above.
    """.trimIndent()

    /**
     * The three under-counting figures, and why they are in the report.
     *
     * They are measured by `NominalNightRegressionTest` (T22) in `:algo`, medians over 20 seeds of
     * the nominal night: `SUB_THRESHOLD_FRACTION = 0.70`, `RAW_COUNT_RATIO = 0.30`,
     * `INDEX_RATIO = 0.06`. They are copied here rather than imported because they live in a
     * **test** source that the application module does not compile; the test that watches them is
     * the protection against their drift, and its KDoc says they must travel up to here.
     *
     * **The third is the only one that decides how the document is read.** The first two describe
     * the detector; that one describes what is left of the published index, and it does not follow
     * from the other two: the AASM rule requires four consecutive movements, so discarding 70 % of
     * the events does not divide the count, it makes most of the series disappear. Without it, a
     * physician reads the table below as a count, and a low count as few movements.
     */
    private val UNDER_COUNTING = """
        ### What the threshold policy removes, in three figures

        They are **measured on synthetic signal** — nominal night, median of 20 draws, ground
        truth known by construction — and **never validated against polysomnography**. They
        therefore do not measure the error of this device on this person: they measure what this
        processing chain removes from a signal whose answer is known.

        | Quantity | Value |
        |---|---|
        | Leg movements present in the signal but **below the detection threshold** | 0.70 |
        | Raw count retained, against accelerometric ground truth, before any series rule | 0.30 |
        | **Share of the true index that survives the threshold** | **0.06** |

        The third is not the average of the first two and does not follow from them. A series
        requires **four consecutive movements**: discarding 70 % of the events does not dilute the
        series, it destroys them, because three consecutive surviving intervals are needed for a
        series to remain. On this data, the published index is of the order of **6 % of the true
        count**.

        **A low figure in this report can therefore not be read as "few movements".** A low index
        is the expected behaviour of this chain, including when movements are numerous. The gap
        between 0.06 and 1 is not a margin of error, it is a change of scale: the comparison with
        the 15/h threshold remains beside the point, and the only reading this number allows is
        its variation from one night to the next, in the same person, with the same setup.
    """.trimIndent()

    /**
     * The unreported rhythm, presented as what it is.
     *
     * `RhythmMeasurementTest` measures 2 accepted fits out of 20 nominal nights: refusal is the
     * ordinary case, and a report that presented it as an incident would send the reader looking
     * for a sensor fault. The figure is quoted because a refusal without an order of magnitude
     * reads as an exception.
     */
    private val RHYTHM_REFUSED = """
      The harmonic deconvolution **refused** the fit: the intervals collected do not identify a
      period. This refusal is the ordinary behaviour of the model and not an incident — on
      simulated nights, 2 fits out of 20 are accepted. A period fitted on too few intervals would
      carry a figure and no information, and nothing on this sheet would tell it apart from a
      period that was really measured.
    """.trimIndent()

    /**
     * The rhythm has no published threshold, and that is written next to it.
     *
     * It is the same sentence the screen carries under the headline figure. Without it, a reader
     * used to the 15/h of the hourly count looks for the equivalent threshold for the rhythm, and
     * invents it.
     */
    private val RHYTHM_NO_THRESHOLD = """
        No published threshold applies to this quantity. It is measured at the ankle, on a
        consumer accelerometer, and the thresholds of the literature are established on
        polysomnography for the hourly count. What this figure allows is a comparison with
        itself, from one night to the next, in the same person.
    """.trimIndent()

    private val MISS_RATE_NOTE = """
        The miss rate is **measured** by the harmonic deconvolution, not assumed. It is both a
        quality indicator and the criterion that says whether two nights measure the same thing:
        two nights whose miss rates differ widely do not compare with one another.
    """.trimIndent()

    private val QUESTIONNAIRE_NOTE = """
        This questionnaire is a screening tool and makes no diagnosis. The five diagnostic
        criteria must be checked by a physician, in particular to rule out the conditions that
        mimic restless legs syndrome (cramps, neuropathy, positional discomfort, drug-induced
        akathisia). It is about what is felt while awake; the watch measures what happens during
        sleep. Neither replaces the other.
    """.trimIndent()

    private val NO_HC_NOTE = """
        No external hypnogram for this night. The denominator then comes from the immobility mask
        computed on the same accelerometer as the movements counted: it is **circular**. Every
        burst of movement pushes the mask towards "awake", so the numerator rises while the
        denominator falls. The figure is still shown for information; it does not carry the main
        result and enters no trend.
    """.trimIndent()

    private val EXCLUSION_NOTE = """
        Exclusions are deterministic predicates evaluated before the computation (same leg, same
        strap, alone in bed, calibration reference within tolerance, at least 4 h analysable,
        outside a daylight-saving night). There is no way to exclude a night after seeing its
        result: such a night stays visible with its reason.
    """.trimIndent()

    private val LIMITS = """
        ## Limits, named separately

        - **The numerator is massively under-counted, and this is measured.** Three figures, in
          the "Hourly count" section: 70 % of the movements present in the signal fall below the
          detection threshold, the raw count retained is 0.30 of the accelerometric ground truth,
          and only **0.06 of the published index remains**. This is the limit that commands all
          the others, and the reason a low index cannot be read as "few movements".
        - **The denominator is overestimated.** Consumer watches declare "asleep" on close to one
          awake epoch in two (specificity ~0.52). Sleep time being the denominator, the index
          comes out **under-estimated**.
        - **The numerator may be overestimated** in the presence of breathing-related movements.
          The single-night report carries a second column, "aPLM-i excluding apnoeic-band series
          (lower bound)": the index recomputed after removing every series whose median interval
          falls in the apnoeic band, as if all of them were of respiratory origin. It is a
          guaranteed **lower** bound — by construction never above the aPLM-i beside it — and the
          true figure lies between the two, nothing allowing it to be located inside that
          interval.
        - **These two biases do not cancel out.** They run in opposite directions, their
          magnitudes are unknown and independent; adding them up mentally to conclude "it evens
          out" would be a mistake.
        - **The measurement is one-sided.** With alternation between the legs, a single sensor
          sees an exactly doubled interval — a harmonic, not noise. The estimated miss rate is
          the indicator to watch.
        - **A single night means nothing.** The night-to-night variability of the hourly count is
          of the order of 43 % of its mean in untreated subjects; that of the fundamental rhythm
          is about 3.6 %.
    """.trimIndent()
}
