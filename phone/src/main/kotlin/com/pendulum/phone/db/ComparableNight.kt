package com.pendulum.phone.db

import androidx.room.DatabaseView

/**
 * The comparability criteria, in a single place.
 *
 * ### Why they live in the database and not in the interface
 *
 * Guard rail 4 of `SPEC-v2.md` section 3: **no "exclude this night" button**. An exclusion decided
 * after seeing the figure is a complete self-deception mechanism on its own — the nights that do
 * not go the expected way get set aside, in good faith, and the trend that comes out of it is
 * manufactured. The defence is not to resist the temptation, it is to make the gesture impossible:
 * exclusions are **deterministic predicates evaluated before the computation**, and they are
 * evaluated by SQL, upstream of all display code.
 *
 * The [ComparableNight] view does not *filter*: it **annotates**. Excluded nights stay visible
 * with their reason — an invisible night is a night one forgets to explain.
 *
 * ### The reference night
 *
 * "Same leg" and "same strap" presuppose a frame of reference. It is defined as **the first night
 * whose context was sealed** (`MIN(sealedAtMs)`).
 *
 * Alternative set aside: the majority value of the campaign. It looks more robust, but it
 * **moves** — adding a night can tip the majority and retroactively make incomparable nights that
 * were comparable yesterday. A trend whose set of points changes when a point is added is not a
 * trend. The price is real and accepted: if the first night was done with the wrong strap, the
 * whole campaign comes out incomparable. The remedy is to start again on a new `paramsHash`, not
 * to loosen the criterion.
 *
 * ### The Kotlin double of the predicate
 *
 * [evaluate] reproduces exactly the `CASE` of [ComparableNightSql.SQL]. Two implementations is a
 * risk of divergence — accepted in exchange for the only thing that matters here: the predicate
 * becomes **testable on the JVM**, exhaustively, with no device and no database. Any modification
 * must touch both, and `ComparableNightPredicateTest` checks that the SQL does mention each of the
 * columns the Kotlin consults.
 */
object ComparabilityRule {

    /**
     * Minimum analysable duration, in minutes. Four hours.
     *
     * This is not the *recorded* duration but the *analysable* one: valid segments, outside blind
     * zones, outside off-body. An 8 h night of which 5 h are gappy is not worth 8.
     */
    const val MIN_ANALYSABLE_MIN = 240.0

    /**
     * Relative tolerance on the gain reference, aligned with
     * `com.pendulum.algo.dsp.Calibration.DEFAULT_OUTLIER_TOLERANCE`.
     *
     * 35 % looks wide; it is calibrated on the measured fact that the play of the strap makes the
     * amplitude vary by a factor of 2 to 3. A tight threshold would not detect "a little tighter",
     * it would declare every night incomparable.
     */
    const val GAIN_TOLERANCE = 0.35

    // Exclusion reasons. These are identifiers, not interface texts: the translation belongs to
    // `com.pendulum.phone.ui`, which must be able to render them without reinterpreting them.
    const val OK = "OK"
    const val NO_CONTEXT = "NO_CONTEXT"
    const val LEG_CHANGED = "LEG_CHANGED"
    const val STRAP_CHANGED = "STRAP_CHANGED"
    const val NOT_ALONE = "NOT_ALONE"
    const val CAL_GAIN_UNKNOWN = "CAL_GAIN_UNKNOWN"
    const val CAL_GAIN_OUT_OF_TOLERANCE = "CAL_GAIN_OUT_OF_TOLERANCE"
    const val TOO_SHORT = "TOO_SHORT"
    const val DST_NIGHT = "DST_NIGHT"

    /** The facts, as the view reads them in the tables. None of them is computed here. */
    data class Facts(
        val hasContext: Boolean,
        val leg: String?,
        val refLeg: String?,
        val strapId: String?,
        val refStrapId: String?,
        val aloneInBed: Boolean,
        val gainCalG: Double?,
        val refGainCalG: Double?,
        val analysableMin: Double,
        val tzOffsetStartMin: Int,
        val tzOffsetEndMin: Int,
    )

    /**
     * Kotlin double of the SQL `CASE`. Returns [OK] if the night is comparable, otherwise **the
     * first** exclusion reason in the priority order of the SQL.
     *
     * The order matters and it deliberately goes from the most structural to the most
     * circumstantial: a night with no sealed context does not even have a leg to compare, and
     * announcing "different strap" to someone who simply forgot to fill in the evening form would
     * be a false diagnosis.
     */
    fun evaluate(f: Facts): String = when {
        !f.hasContext -> NO_CONTEXT
        f.refLeg == null || f.refStrapId == null -> NO_CONTEXT
        f.leg != f.refLeg -> LEG_CHANGED
        f.strapId != f.refStrapId -> STRAP_CHANGED
        !f.aloneInBed -> NOT_ALONE
        f.gainCalG == null || f.refGainCalG == null || f.refGainCalG <= 0.0 -> CAL_GAIN_UNKNOWN
        // The epsilon is not a decorative precaution: `1.35 - 1.0` is 0.35000000000000009 in
        // binary, so a tolerance gap landing exactly on the bound would be rejected whereas the
        // rule means to be inclusive. Without it, a night at exactly 35 % of gap drops out of the
        // comparison — and that is the kind of exclusion one never notices, because it looks like
        // a rule doing its job.
        kotlin.math.abs(f.gainCalG - f.refGainCalG) / f.refGainCalG > GAIN_TOLERANCE + 1e-9 ->
            CAL_GAIN_OUT_OF_TOLERANCE
        f.analysableMin < MIN_ANALYSABLE_MIN -> TOO_SHORT
        f.tzOffsetStartMin != f.tzOffsetEndMin -> DST_NIGHT
        else -> OK
    }

    fun isComparable(f: Facts): Boolean = evaluate(f) == OK
}

/**
 * The view that **every** trend query must go through.
 *
 * One row per `(night, paramsHash, rule set, mask)`. The view sets nothing aside: it adds
 * `comparable` and `exclusionReason`. It is `TrendDao` that filters, and that is the only place
 * where a filter exists.
 *
 * The daylight-saving change reads as `tzOffsetStartMin <> tzOffsetEndMin`: both offsets are
 * recorded by ingestion precisely so that this test is an integer comparison and not a calendar
 * computation.
 */
@DatabaseView(viewName = "comparable_night", value = ComparableNightSql.SQL)
data class ComparableNight(
    val sessionHex: String,
    val startWallMs: Long,
    val zoneId: String,
    val paramsHash: String,
    val rule: String,
    val maskSource: String,
    /**
     * The application that published the hypnogram this row's denominator came from —
     * `sleep_window.sourcePackage` of the windows the analysis actually used under this
     * `paramsHash` — or `null` when the mask is Pendulum's own, or when Health Connect did not name
     * the origin.
     *
     * It is in the view because the label of the sleep source was taken from **the preference**:
     * `PendulumRepository` read `PendulumPreferences.preferredSleepSource` and wrote its app name
     * next to every night, past and present. Two things made that wrong at once. The preference
     * was never handed to the read (`SleepFetchWorker` passed `preferredPackage = null`), so the
     * hypnogram came from whichever source the coverage heuristic chose — and the night was
     * labelled with the one the user had ticked. And a preference changed today relabels every
     * night of the campaign: a night whose denominator came from Samsung Health read "Fitness" the
     * morning after the setting was switched, on the list, in the detail, in the quality table.
     * The label now comes from the row, and it is `Mapping.sourceLabel` that turns it into text.
     *
     * `null` by default so that the many fixtures that build this row by hand need not name it.
     */
    val sourcePackage: String? = null,
    val gate: String,
    val independence: String,
    /**
     * `null` when the night has no index to carry: without analysable sleep there is no
     * denominator, hence no rate — and above all not a zero. See [PlmResultEntity.plmi].
     */
    val plmi: Double?,
    val plmiSpt: Double?,
    val fundamentalSec: Double?,
    /**
     * Has the rhythm fit been accepted by `:algo`?
     *
     * **False is the normal case**, not the exception: on the nominal night,
     * `RhythmMeasurementTest` measures 2 valid fits out of 20 — the deconvolution most often
     * refuses, because the detector does not hand it enough usable intervals. `fundamentalSec` is
     * then `null`, and not 0: `:algo` returns a `NaN` — "no value", not "zero period" — and the
     * persistence boundary translates it into `NULL`.
     *
     * The column is in the view because `gate = 'FULL'` does not cover it: the publication gate
     * bears on the denominator and on truncation, never on the rhythm fit. A night can therefore
     * be comparable, publishable, and have no rhythm to show.
     */
    val rhythmValid: Boolean,
    val periodicityIndex: Double,
    /**
     * Whether the periodicity index may be shown at all.
     *
     * It is here for the same reason as [rhythmValid], and it was missing: `Periodicity` states
     * that below its interval rate the value **must not be displayed**, the view did not carry the
     * flag, and the trend took a median over every night regardless — falling back to `0.0`, which
     * is a legitimate index meaning "no periodicity whatsoever". An unknown was published as a
     * measurement, and as the most reassuring one available.
     */
    val periodicityValid: Boolean,
    val missRate: Double?,
    val analysableTstMin: Double,
    val analysableMin: Double,
    val truncated: Boolean,
    val revealedAtMs: Long?,
    val comparable: Boolean,
    val exclusionReason: String,
)

/**
 * **The principal session of an evening**, when an evening has more than one.
 *
 * Two sessions can attach to the same night key — a false start at bedtime, stopped after a
 * minute because the strap was wrong and started again; a nap begun after the noon rollover of
 * `WirePaths.nightKey`; a night interrupted and resumed by hand. Nothing forbade it and nothing
 * defined which of the two *is* the night. Two readers picked one with `LIMIT 1` and no `ORDER
 * BY`, that is to say whichever row SQLite's plan happened to visit first: the `ref` subquery of
 * [ComparableNightSql.SQL], which takes the reference gain from it, and
 * `NightDao.findByNightKey`, which `AnalysisRunner.baselineGainOf` and `NightExporter` read the
 * reference session from.
 *
 * What that produced, on the reference evening — the first sealed one, hence the very evening a
 * first-time user is most likely to have fumbled: with the one-minute false start chosen, its
 * `gainCalG` — `NULL`, since calibration never saw enough signal — became the campaign's
 * reference gain, and **every night of the campaign** came out `CAL_GAIN_UNKNOWN`. With the real
 * night chosen, everything was fine. Which of the two happened depended on the index the query
 * planner walked, and could change with an `ANALYZE` or a Room upgrade: a trend that empties or
 * fills itself with no change in the sleeper.
 *
 * The definition: **the session with the most analysable minutes**, ties broken by the earliest
 * start, then by the session identifier so that the order is total. Analysable minutes rather
 * than recorded minutes because it is the criterion the comparability rule already uses, and
 * because the reference session exists for one thing — its gain — and the session with the most
 * analysable signal is the one whose gain is worth trusting. It is written by the analysis, so a
 * session not yet analysed counts as zero: on the evening of the false start itself, the real
 * night takes over as soon as it is scored, and never gives the place back.
 *
 * One string, used by both readers, and no third copy: [OF_EVENING] and [ComparableNightSql.SQL]
 * are both built from it, as `const val`s — a constant concatenation is itself a compile-time
 * constant, and each annotation then references one constant, the way `@DatabaseView` already
 * did. The columns are unqualified on purpose — in the `ref` subquery they are unambiguous, since
 * `night_context` carries none of them — so that the same text serves a plain `SELECT` on
 * `night_session`.
 */
internal object PrincipalSessionSql {
    const val ORDER_BY = "analysableMin DESC, startWallMs ASC, sessionHex ASC"

    /** `NightDao.findByNightKey`: the principal session of one evening, or nothing. */
    const val OF_EVENING =
        "SELECT * FROM night_session WHERE nightKey = :nightKey ORDER BY " + ORDER_BY + " LIMIT 1"
}

/**
 * The SQL of the view, taken out of the class it annotates: an annotation cannot reference a
 * constant declared in the class it annotates without creating a circular dependency at
 * compilation.
 */
internal object ComparableNightSql {

    /**
     * The order of the `WHEN` clauses reproduces that of [ComparabilityRule.evaluate], and the
     * value of the two numeric constants (`240.0`, `0.35`) is hard-coded there: SQLite cannot
     * read a Kotlin constant. It is the only duplication in the file, and it is the one
     * `ComparableNightPredicateTest` watches over.
     *
     * The `ref` subquery orders by `sealedAtMs` first — the reference *evening* is the first one
     * sealed — and then by [PrincipalSessionSql.ORDER_BY], which picks the reference *session*
     * among those of that evening. Without the second half, `LIMIT 1` fell on either.
     *
     * `sourcePackage` is an aggregate (`MAX`) over the windows of the same `(night, hash, mask)`
     * and not a `LIMIT 1`: every window of one mask carries the same package — one hypnogram, one
     * origin — so the aggregate is that package, and it names its row without an order. It is
     * `NULL` for the accelerometer mask, whose windows carry none.
     */
    const val SQL = """
            SELECT
                s.sessionHex          AS sessionHex,
                s.startWallMs         AS startWallMs,
                s.zoneId              AS zoneId,
                r.paramsHash          AS paramsHash,
                r.rule                AS rule,
                r.maskSource          AS maskSource,
                (
                    SELECT MAX(w.sourcePackage) FROM sleep_window w
                    WHERE w.sessionHex = r.sessionHex
                      AND w.paramsHash = r.paramsHash
                      AND w.source = r.maskSource
                )                     AS sourcePackage,
                r.gate                AS gate,
                r.independence        AS independence,
                r.plmi                AS plmi,
                r.plmiSpt             AS plmiSpt,
                r.fundamentalSec      AS fundamentalSec,
                r.rhythmValid         AS rhythmValid,
                r.periodicityIndex    AS periodicityIndex,
                r.periodicityValid    AS periodicityValid,
                r.missRate            AS missRate,
                r.analysableTstMin    AS analysableTstMin,
                s.analysableMin       AS analysableMin,
                s.truncated           AS truncated,
                s.revealedAtMs        AS revealedAtMs,
                CASE
                    WHEN c.nightKey IS NULL THEN 0
                    WHEN ref.refLeg IS NULL OR ref.refStrapId IS NULL THEN 0
                    WHEN c.leg <> ref.refLeg THEN 0
                    WHEN c.strapId <> ref.refStrapId THEN 0
                    WHEN c.aloneInBed = 0 THEN 0
                    WHEN s.gainCalG IS NULL OR ref.refGainCalG IS NULL OR ref.refGainCalG <= 0 THEN 0
                    WHEN abs(s.gainCalG - ref.refGainCalG) / ref.refGainCalG > 0.35 + 1e-9 THEN 0
                    WHEN s.analysableMin < 240.0 THEN 0
                    WHEN s.tzOffsetStartMin <> s.tzOffsetEndMin THEN 0
                    ELSE 1
                END AS comparable,
                CASE
                    WHEN c.nightKey IS NULL THEN 'NO_CONTEXT'
                    WHEN ref.refLeg IS NULL OR ref.refStrapId IS NULL THEN 'NO_CONTEXT'
                    WHEN c.leg <> ref.refLeg THEN 'LEG_CHANGED'
                    WHEN c.strapId <> ref.refStrapId THEN 'STRAP_CHANGED'
                    WHEN c.aloneInBed = 0 THEN 'NOT_ALONE'
                    WHEN s.gainCalG IS NULL OR ref.refGainCalG IS NULL OR ref.refGainCalG <= 0
                        THEN 'CAL_GAIN_UNKNOWN'
                    WHEN abs(s.gainCalG - ref.refGainCalG) / ref.refGainCalG > 0.35 + 1e-9
                        THEN 'CAL_GAIN_OUT_OF_TOLERANCE'
                    WHEN s.analysableMin < 240.0 THEN 'TOO_SHORT'
                    WHEN s.tzOffsetStartMin <> s.tzOffsetEndMin THEN 'DST_NIGHT'
                    ELSE 'OK'
                END AS exclusionReason
            FROM plm_result r
            JOIN night_session s ON s.sessionHex = r.sessionHex
            LEFT JOIN night_context c ON c.nightKey = s.nightKey
            LEFT JOIN (
                SELECT nc.leg AS refLeg, nc.strapId AS refStrapId, ns.gainCalG AS refGainCalG
                FROM night_context nc
                JOIN night_session ns ON ns.nightKey = nc.nightKey
                ORDER BY nc.sealedAtMs ASC, """ + PrincipalSessionSql.ORDER_BY + """
                LIMIT 1
            ) ref ON 1 = 1
    """
}

/**
 * **The row of a night that the screens show**, when the night has no hypnogram.
 *
 * `NightAnalyzer` writes the `HEALTH_CONNECT` rows only when Health Connect returned a hypnogram
 * — the accelerometer rows exist for every scored night, the external ones only when there was
 * something external. The three readers that fed the screens — the night list, the home card, the
 * detail — all asked the view for `maskSource = 'HEALTH_CONNECT'` and nothing else. So a night
 * scored without a hypnogram — the **default** path of every user without a sleep application,
 * and of every night whose hypnogram has not arrived yet — had its four accelerometer rows in
 * `plm_result`, its `analyzedAtMs` stamped, and **no row at all** for any screen: absent from the
 * list, "0 nights recorded" on the home card after a night that had visibly been analysed, and a
 * detail screen that returned `null` — a blank — for a night the user had just recorded. The
 * documentation says the accelerometer mask "is allowed to exist and to be displayed" with its
 * flag (`Mapping.flags`, `Situations.night` E-NIGHT-01, the circular denominator row of the
 * computation path); none of that code ever received a row.
 *
 * The rule: the preferred mask's row when it exists, the fallback mask's row **only** when it does
 * not. Never both — two rows for one night would show the same night twice, with two figures. The
 * existence test is on `plm_result`, which carries the unique index
 * `(sessionHex, paramsHash, rule, maskSource)`, and not on the view, which is a join built at
 * every read. The trend is untouched: `TrendDao.trendPoints` still admits the independent
 * denominator only, and a night shown through its fallback row is at best `TRUNCATED_NO_TREND`
 * (`Plmi.canCarryPrimaryResult` refuses `FULL` to a circular mask), so it reads as *provisional*
 * on the list — which is exactly the state `Mapping.nightUi` documents for it.
 *
 * One string, referenced by `TrendDao.displayNights`, so that `DisplayedNightSqlTest` can read it
 * on the JVM — the same arrangement as [PrincipalSessionSql].
 */
internal object DisplayedNightSql {
    const val SQL = """
        SELECT n.* FROM comparable_night n
        WHERE n.paramsHash = :paramsHash AND n.rule = :rule
          AND (
              n.maskSource = :preferredMask
              OR (
                  n.maskSource = :fallbackMask
                  AND NOT EXISTS (
                      SELECT 1 FROM plm_result p
                      WHERE p.sessionHex = n.sessionHex
                        AND p.paramsHash = :paramsHash
                        AND p.rule = :rule
                        AND p.maskSource = :preferredMask
                  )
              )
          )
        ORDER BY n.startWallMs ASC
    """
}
