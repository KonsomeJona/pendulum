package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.Formats
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text
import java.util.Locale

/**
 * "Why this number" — the computation path, and nothing else.
 *
 * ### The only "why" that is legitimate here
 *
 * It is **generative**, not attributive: it shows what the number went through — how much
 * analysable sleep, how many movements kept, under which rule, divided by what — and it stops
 * there. No ranking of factors by importance, no quantified contribution, no "the signal gap
 * explains 30 % of the difference".
 *
 * This is not caution as a matter of form. Attribution methods based on contribution — Shapley and
 * its derivatives — do not distinguish correlation from causation, and over-attribute as
 * soon as the input variables are correlated with one another. And here they massively are: the
 * analysable sleep duration, the number of movements and the miss rate move together by
 * construction. A ranking shown under a health figure reads as a cause, and that is the failure
 * mode identified as critical in this field.
 *
 * The last line of the block therefore does all the work: *this table shows how the number is
 * obtained; it does not indicate what caused those movements.* It is not optional and it is not
 * configurable — it is part of the type ([Block.disclaimer]).
 *
 * ### Everything is already computed
 *
 * The miss rate and the respiratory bracket are produced by `:algo` and persisted in `plm_result`
 * (`missRate`, `plmiRespWorstCase`) from the start. They simply were not shown anywhere. This file
 * computes no new quantity: it formats.
 */
object ComputationPath {

    /** A row of the table: a label, a value, and a note when the value is open to discussion. */
    data class Line(val label: UiText, val value: UiText, val note: UiText? = null)

    /**
     * The complete block. [disclaimer] is a field and not a text laid down by the screen: a
     * computation path block without its non-causality sentence must not be able to exist.
     */
    data class Block(
        val title: UiText,
        val lines: List<Line>,
        val disclaimer: UiText = text(R.string.night_why_disclaimer),
    )

    /**
     * @param recordedDurationMin the session's duration, from start to stop. It gives context to
     *   the analysable sleep: "5 h 12" says nothing, "5 h 12 out of 7 h 41 recorded" says where the
     *   rest went.
     * @param movementsKept the numerator, as it was used. It is not the number of events detected:
     *   the posture and duration rejections are already out.
     * @param rule the rule set applied, already formatted.
     * @param sleepSource the source label, already resolved by [Mapping.sourceLabel].
     * @param metrology what the night's telemetry says about the device, or `null` when the night
     *   carries none. See [addMetrology] for what those three rows are allowed to say.
     */
    fun of(
        n: ComparableNight,
        result: PlmResultEntity?,
        recordedDurationMin: Double,
        movementsKept: Int,
        rule: UiText,
        sleepSource: UiText,
        metrology: Metrology.Summary? = null,
    ): Block? {
        if (result == null) return null
        val independentMask = n.maskSource != Mapping.ACCEL_MASK

        val lines = buildList {
            add(
                Line(
                    label = text(R.string.night_why_analysable_sleep),
                    value = text(Mapping.readableDuration(n.analysableTstMin)),
                    note = text(
                        R.string.night_why_of_recorded,
                        Mapping.readableDuration(recordedDurationMin),
                    ),
                ),
            )
            add(
                Line(
                    text(R.string.night_why_movements_counted),
                    text(movementsKept.toString()),
                ),
            )
            add(Line(text(R.string.night_why_rule), rule))
            add(
                Line(
                    label = text(R.string.night_why_mask),
                    value = text(
                        R.string.night_why_mask_value,
                        sleepSource,
                        text(
                            if (independentMask) R.string.night_why_mask_hypnogram
                            else R.string.night_why_mask_accel,
                        ),
                    ),
                ),
            )
            // The denominator is the only row that carries a judgement, and it is a structural
            // judgement: it comes from the same sensor as the numerator, or it does not.
            add(
                Line(
                    label = text(R.string.night_why_denominator),
                    value = text(
                        if (independentMask) R.string.night_why_denominator_independent
                        else R.string.night_why_denominator_circular,
                    ),
                ),
            )
            // As a percentage, like the quality table on the same screen (`Checks`): the same
            // `missRate` read "31.1%" there and "0.39" here. Two writings of a single quantity, two
            // cards apart, one of them without a unit — nothing told the reader they were looking
            // at the same number twice.
            //
            // A dash when the rhythm fit was refused: there is then no rate, and "0.0%" would read
            // as a deconvolution that missed nothing.
            add(
                Line(
                    text(R.string.night_why_missed_rate),
                    text(n.missRate?.let { Mapping.percent(it) } ?: Mapping.DASH),
                ),
            )

            // The respiratory bracket: `plmiRespWorstCase` is the index one would obtain by
            // removing everything that could be respiration-related. It is the lower bound of an
            // interval whose upper bound is the number shown at the top of the card. Pendulum does
            // not measure respiration: we cannot decide inside that interval, we can only show it.
            //
            // The **bound**, and not the distance to the bound. The row used to show
            // `plmiRespWorstCase - plmi`, hence a negative number by construction, under a note
            // saying "this line is the value the index would take" — that is, read literally, an
            // index of -15.1/h, which does not exist. The note also says "between the two", which
            // presupposes two values and not a value and a difference. Showing the bound makes both
            // sentences true and spares the reader a subtraction done in their head on the number
            // that carries the diagnosis.
            //
            // The bound shares the denominator of the main number: when that one does not exist,
            // neither does it, and the row returns a dash rather than a bracket both of whose
            // bounds would be invented.
            add(
                Line(
                    label = text(R.string.night_why_resp_bracket),
                    value = result.plmiRespWorstCase?.let { bound ->
                        text(
                            R.string.night_why_worst_bound,
                            "%.1f ".format(Locale.UK, bound),
                            text(R.string.trend_unit_per_hour),
                        )
                    } ?: text(Mapping.DASH),
                    note = text(R.string.night_why_resp_bracket_note),
                ),
            )

            metrology?.let { addMetrology(it) }
        }

        // The title names the number being explained — except when there is none. The block stays
        // shown: its rows then say where the computation stopped, which a removed block would not
        // say.
        return Block(
            title = result.plmi?.let { index ->
                text(
                    R.string.night_why_title,
                    text(
                        R.string.trend_value_with_unit,
                        "%.1f".format(Locale.UK, index),
                        text(R.string.trend_unit_per_hour),
                    ),
                )
            } ?: text(R.string.night_why_title_no_index),
            lines = lines,
        )
    }

    /**
     * The three quantities that explain a **detection decision**, and nothing else.
     *
     * ### Why they belong in a generative block
     *
     * What is forbidden here is attribution: ranking factors by importance, quantifying a
     * contribution, saying that a signal gap "explains 30 % of the difference". These three rows do
     * nothing of the sort. They say under which conditions the measurement was made, exactly on the
     * same footing as "analysable sleep" or "denominator": they are part of the path the number
     * went through.
     *
     * The difference is verifiable: none of the three is compared to the others, none carries a
     * share, and the block does not order them by effect. The eye makes the link if there is one;
     * the text does not, and [Block.disclaimer] goes on saying that this table does not indicate
     * what caused the movements.
     *
     * ### And what each of them decides
     *
     *  - **Sensor clipping** artificially flattens the top of the envelope. Now it is the amplitude
     *    that decides the detection threshold: a movement kept or rejected on a clipped minute was
     *    not decided on the signal, it was decided on its clipping. This is the clipping **of the
     *    sensor** — the real dynamic range, 4 or 8 g — and not the saturation of the **format** at
     *    16 g, which an 8 g sensor never approaches.
     *  - **Jitter** decides the timestamping, and it is its dispersion that decides it, not its
     *    mean. The format does not write a timestamp per sample: it interpolates linearly between
     *    the bounds of a block. A perfect average rate obtained by alternating 10 and 30 ms
     *    therefore yields exactly 50 Hz and timestamps every sample to within 10 ms.
     *  - **Write freezes** are the moments when the processor stops. It is the worst freeze, and
     *    not the total, that explains a sensor interrupt missed at a precise instant.
     */
    private fun MutableList<Line>.addMetrology(m: Metrology.Summary) {
        add(
            Line(
                label = text(R.string.night_why_clipping),
                value = if (m.clippedSamples == 0) {
                    text(R.string.night_why_none)
                } else {
                    text(
                        R.string.night_why_clipping_value,
                        Formats.thousands(m.clippedSamples),
                        m.clippedPoints,
                    )
                },
                note = text(R.string.night_why_clipping_note),
            ),
        )

        add(
            Line(
                label = text(R.string.night_why_timing),
                value = text(
                    R.string.night_why_timing_value,
                    "%.1f ms".format(Locale.UK, m.medianJitterUs / 1000.0),
                    "%.0f ms".format(Locale.UK, m.worstIntervalUs / 1000.0),
                ),
                note = text(R.string.night_why_timing_note),
            ),
        )

        add(
            Line(
                label = text(R.string.night_why_freezes),
                value = if (m.freezes == 0) {
                    text(R.string.night_why_none)
                } else {
                    text(
                        R.string.night_why_freezes_value,
                        m.freezes,
                        "%.0f ms".format(Locale.UK, m.worstFreezeUs / 1000.0),
                    )
                },
                note = text(R.string.night_why_freezes_note),
            ),
        )
    }
}
