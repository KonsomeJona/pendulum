package com.pendulum.phone.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The comparability predicate is the guard rail that replaces the "exclude this night" button. If
 * it is wrong, exclusion becomes a decision again, and a decision taken after seeing the figure is
 * exactly the self-deception mechanism the whole project sets out to prevent.
 *
 * Hence exhaustive coverage: every criterion, in both directions, plus the priority order.
 */
class ComparableNightPredicateTest {

    private fun facts(
        hasContext: Boolean = true,
        leg: String? = "RIGHT",
        refLeg: String? = "RIGHT",
        strapId: String? = "strap-a",
        refStrapId: String? = "strap-a",
        aloneInBed: Boolean = true,
        gainCalG: Double? = 1.0,
        refGainCalG: Double? = 1.0,
        analysableMin: Double = 480.0,
        tzStart: Int = 60,
        tzEnd: Int = 60,
    ) = ComparabilityRule.Facts(
        hasContext, leg, refLeg, strapId, refStrapId, aloneInBed,
        gainCalG, refGainCalG, analysableMin, tzStart, tzEnd,
    )

    @Test
    fun `a nominal night is comparable`() {
        assertThat(ComparabilityRule.evaluate(facts())).isEqualTo(ComparabilityRule.OK)
        assertThat(ComparabilityRule.isComparable(facts())).isTrue()
    }

    @Test
    fun `without a sealed context, nothing is comparable`() {
        // And the reason must be NO_CONTEXT, not "different strap": telling someone who forgot the
        // evening form that their strap changed would be a false diagnosis.
        assertThat(ComparabilityRule.evaluate(facts(hasContext = false)))
            .isEqualTo(ComparabilityRule.NO_CONTEXT)
    }

    @Test
    fun `changing leg makes the night incomparable`() {
        assertThat(ComparabilityRule.evaluate(facts(leg = "LEFT")))
            .isEqualTo(ComparabilityRule.LEG_CHANGED)
    }

    @Test
    fun `changing strap makes the night incomparable`() {
        assertThat(ComparabilityRule.evaluate(facts(strapId = "strap-b")))
            .isEqualTo(ComparabilityRule.STRAP_CHANGED)
    }

    @Test
    fun `a partner in the bed makes the night incomparable`() {
        assertThat(ComparabilityRule.evaluate(facts(aloneInBed = false)))
            .isEqualTo(ComparabilityRule.NOT_ALONE)
    }

    @Test
    fun `an unknown gain is not a compliant gain`() {
        // The trap case: we cannot check, so we do not assert. Treating "unknown" as "compliant"
        // would let into the trend a night that nothing says is comparable.
        assertThat(ComparabilityRule.evaluate(facts(gainCalG = null)))
            .isEqualTo(ComparabilityRule.CAL_GAIN_UNKNOWN)
        assertThat(ComparabilityRule.evaluate(facts(refGainCalG = null)))
            .isEqualTo(ComparabilityRule.CAL_GAIN_UNKNOWN)
        assertThat(ComparabilityRule.evaluate(facts(refGainCalG = 0.0)))
            .isEqualTo(ComparabilityRule.CAL_GAIN_UNKNOWN)
    }

    @Test
    fun `the gain is tolerated up to 35 percent of deviation, excluded beyond`() {
        assertThat(ComparabilityRule.evaluate(facts(gainCalG = 1.35, refGainCalG = 1.0)))
            .isEqualTo(ComparabilityRule.OK)
        assertThat(ComparabilityRule.evaluate(facts(gainCalG = 0.65, refGainCalG = 1.0)))
            .isEqualTo(ComparabilityRule.OK)
        assertThat(ComparabilityRule.evaluate(facts(gainCalG = 1.36, refGainCalG = 1.0)))
            .isEqualTo(ComparabilityRule.CAL_GAIN_OUT_OF_TOLERANCE)
        assertThat(ComparabilityRule.evaluate(facts(gainCalG = 0.64, refGainCalG = 1.0)))
            .isEqualTo(ComparabilityRule.CAL_GAIN_OUT_OF_TOLERANCE)
    }

    @Test
    fun `four analysable hours are an inclusive floor`() {
        assertThat(ComparabilityRule.evaluate(facts(analysableMin = 240.0)))
            .isEqualTo(ComparabilityRule.OK)
        assertThat(ComparabilityRule.evaluate(facts(analysableMin = 239.9)))
            .isEqualTo(ComparabilityRule.TOO_SHORT)
    }

    @Test
    fun `the night of the clock change is excluded`() {
        // The only criterion that reads off two integers rather than off a calendar computation:
        // the two offsets are recorded for precisely this.
        assertThat(ComparabilityRule.evaluate(facts(tzStart = 60, tzEnd = 120)))
            .isEqualTo(ComparabilityRule.DST_NIGHT)
    }

    @Test
    fun `the priority order goes from the most structural to the most circumstantial`() {
        // A night that violates everything must report only one reason, and it is the most
        // fundamental one.
        val allWrong = facts(
            hasContext = false, leg = "LEFT", strapId = "strap-b",
            aloneInBed = false, gainCalG = null, analysableMin = 10.0, tzEnd = 120,
        )
        assertThat(ComparabilityRule.evaluate(allWrong)).isEqualTo(ComparabilityRule.NO_CONTEXT)

        val contextOk = allWrong.copy(hasContext = true)
        assertThat(ComparabilityRule.evaluate(contextOk)).isEqualTo(ComparabilityRule.LEG_CHANGED)

        val legOk = contextOk.copy(leg = "RIGHT")
        assertThat(ComparabilityRule.evaluate(legOk)).isEqualTo(ComparabilityRule.STRAP_CHANGED)

        val strapOk = legOk.copy(strapId = "strap-a")
        assertThat(ComparabilityRule.evaluate(strapOk)).isEqualTo(ComparabilityRule.NOT_ALONE)

        val aloneOk = strapOk.copy(aloneInBed = true)
        assertThat(ComparabilityRule.evaluate(aloneOk)).isEqualTo(ComparabilityRule.CAL_GAIN_UNKNOWN)

        val gainOk = aloneOk.copy(gainCalG = 1.0)
        assertThat(ComparabilityRule.evaluate(gainOk)).isEqualTo(ComparabilityRule.TOO_SHORT)

        val durationOk = gainOk.copy(analysableMin = 480.0)
        assertThat(ComparabilityRule.evaluate(durationOk)).isEqualTo(ComparabilityRule.DST_NIGHT)

        assertThat(ComparabilityRule.evaluate(durationOk.copy(tzOffsetEndMin = 60)))
            .isEqualTo(ComparabilityRule.OK)
    }

    /**
     * The Kotlin double and the SQL are two implementations of the same predicate: this test
     * cannot prove that they agree (that would take a database), but it catches by far the most
     * likely case — a criterion added on one side and forgotten on the other.
     */
    @Test
    fun `the view's SQL mentions the six criteria and the two constants`() {
        val sql = ComparableNightSql.SQL
        assertThat(sql).contains("c.leg <> ref.refLeg")
        assertThat(sql).contains("c.strapId <> ref.refStrapId")
        assertThat(sql).contains("c.aloneInBed = 0")
        assertThat(sql).contains("abs(s.gainCalG - ref.refGainCalG) / ref.refGainCalG > 0.35")
        assertThat(sql).contains("s.analysableMin < 240.0")
        assertThat(sql).contains("s.tzOffsetStartMin <> s.tzOffsetEndMin")

        // The SQL constants are hard-coded (SQLite cannot read a Kotlin constant):
        // we at least check that they have not drifted away from their source.
        assertThat(sql).contains(ComparabilityRule.GAIN_TOLERANCE.toString())
        assertThat(sql).contains(ComparabilityRule.MIN_ANALYSABLE_MIN.toString())

        // Every reason in the Kotlin must exist in the SQL, failing which the interface would
        // receive a reason it does not know how to translate.
        listOf(
            ComparabilityRule.NO_CONTEXT,
            ComparabilityRule.LEG_CHANGED,
            ComparabilityRule.STRAP_CHANGED,
            ComparabilityRule.NOT_ALONE,
            ComparabilityRule.CAL_GAIN_UNKNOWN,
            ComparabilityRule.CAL_GAIN_OUT_OF_TOLERANCE,
            ComparabilityRule.TOO_SHORT,
            ComparabilityRule.DST_NIGHT,
            ComparabilityRule.OK,
        ).forEach { assertThat(sql).contains("'$it'") }
    }

    /**
     * Two sessions under one evening — a false start stopped after a minute and started again —
     * and the reference subquery took *either* with a bare `LIMIT 1`: the one-minute session's
     * `NULL` gain became the campaign's reference gain, and every night came out
     * `CAL_GAIN_UNKNOWN`, or not, depending on the row the planner visited first. The principal
     * session of an evening is now one definition, [PrincipalSessionSql.ORDER_BY], and this test
     * pins that both readers use it: the view here, `NightDao.findByNightKey` through
     * [PrincipalSessionSql.OF_EVENING]. The real proof, on SQLite, is `PrincipalSessionTest`.
     */
    @Test
    fun `the reference session of an evening is the principal one, in both readers`() {
        val order = PrincipalSessionSql.ORDER_BY
        assertThat(order).isEqualTo("analysableMin DESC, startWallMs ASC, sessionHex ASC")

        // The evening first — the reference evening is the first one sealed — then the session.
        assertThat(ComparableNightSql.SQL).contains("ORDER BY nc.sealedAtMs ASC, $order")
        assertThat(PrincipalSessionSql.OF_EVENING)
            .isEqualTo("SELECT * FROM night_session WHERE nightKey = :nightKey ORDER BY $order LIMIT 1")

        // No `LIMIT 1` left in the view without an order that names its row.
        val sql = ComparableNightSql.SQL
        val limit = sql.indexOf("LIMIT 1")
        assertThat(limit).isPositive()
        assertThat(sql.substring(0, limit)).contains(order)
    }

    /**
     * The view **annotates**, it does not filter: an excluded night must stay visible with its
     * reason. A `WHERE` in the view would make it disappear, and an invisible night is a night one
     * forgets to explain.
     */
    @Test
    fun `the view does not filter out excluded nights`() {
        assertThat(ComparableNightSql.SQL.uppercase()).doesNotContain("WHERE COMPARABLE")
        assertThat(ComparableNightSql.SQL).contains("AS comparable")
        assertThat(ComparableNightSql.SQL).contains("AS exclusionReason")
    }
}
