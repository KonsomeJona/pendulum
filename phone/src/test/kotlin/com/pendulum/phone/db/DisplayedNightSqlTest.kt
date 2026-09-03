package com.pendulum.phone.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The row a night is **shown** through, and the name of its sleep source — as text, on the JVM.
 *
 * ### Why this test exists
 *
 * Every screen read the `comparable_night` view for `maskSource = 'HEALTH_CONNECT'` and nothing
 * else, while `NightAnalyzer` writes that mask only when Health Connect returned a hypnogram. A
 * night scored without one — the default path of a user with no sleep application, and every
 * night in the hours before its hypnogram arrives — had its accelerometer rows, its `analyzedAtMs`,
 * and **no row on any screen**: absent from the list, "0 nights recorded" on the home card the
 * morning after, a blank detail. And the label of the sleep source came from the preference,
 * not from what was read: a night whose denominator came from Samsung Health read "Fitness" the
 * morning after the setting was switched.
 *
 * ### What this test can and cannot do
 *
 * A JVM test has no SQLite, so it cannot run the query. It pins the **text** that
 * `TrendDao.displayNights` and the view are built from — the same arrangement as
 * `ComparableNightPredicateTest` for the comparability predicate — and catches the likely
 * regressions: the fallback dropped, the existence test moved off the table that carries the
 * unique index, a `LIMIT 1` without an order. The proof on a real database is `DisplayedNightTest`,
 * instrumented.
 */
class DisplayedNightSqlTest {

    @Test
    fun `the screens read one row per night, the preferred mask first and the fallback otherwise`() {
        val sql = DisplayedNightSql.SQL
        // Both masks are parameters: the repository decides which is which, the SQL decides only
        // the precedence between them.
        assertThat(sql).contains("n.maskSource = :preferredMask")
        assertThat(sql).contains("n.maskSource = :fallbackMask")
        // The fallback row is admitted **only** when the preferred one does not exist. Without the
        // `NOT EXISTS`, a night with a hypnogram would appear twice, with two figures.
        assertThat(sql).contains("NOT EXISTS")
        assertThat(sql.indexOf("n.maskSource = :fallbackMask")).isLessThan(sql.indexOf("NOT EXISTS"))
        // The existence test is on `plm_result`, which carries the unique index
        // `(sessionHex, paramsHash, rule, maskSource)`, and not on the view, a join rebuilt at
        // every read.
        assertThat(sql).contains("SELECT 1 FROM plm_result p")
        assertThat(sql).contains("p.sessionHex = n.sessionHex")
        assertThat(sql).contains("p.paramsHash = :paramsHash")
        assertThat(sql).contains("p.rule = :rule")
        assertThat(sql).contains("p.maskSource = :preferredMask")
        // Guard rail 3 still holds here: no read of the view without a hash.
        assertThat(sql).contains("n.paramsHash = :paramsHash AND n.rule = :rule")
        // And nothing here picks a row by chance.
        assertThat(sql).doesNotContain("LIMIT")
    }

    @Test
    fun `the view names the sleep source from the windows the analysis used, not from a preference`() {
        val sql = ComparableNightSql.SQL
        assertThat(sql).contains("AS sourcePackage")
        // From `sleep_window`, joined on the three keys of the row: same night, same hash, same
        // mask. `hc_snapshot` would name the last reading, which is not necessarily the one this
        // hash was scored with.
        assertThat(sql).contains("SELECT MAX(w.sourcePackage) FROM sleep_window w")
        assertThat(sql).contains("w.sessionHex = r.sessionHex")
        assertThat(sql).contains("w.paramsHash = r.paramsHash")
        assertThat(sql).contains("w.source = r.maskSource")
    }

    /**
     * The `LIMIT 1` rule of `ComparableNightPredicateTest` reads the first `LIMIT 1` of the view
     * and checks that the principal-session order precedes it. The `sourcePackage` subquery sits
     * **before** the `ref` subquery in the text, so it must carry no `LIMIT` of its own — an
     * aggregate names its row without an order.
     */
    @Test
    fun `the source subquery aggregates rather than picking a row`() {
        val sql = ComparableNightSql.SQL
        val source = sql.substring(sql.indexOf("MAX(w.sourcePackage)"), sql.indexOf("AS sourcePackage"))
        assertThat(source).doesNotContain("LIMIT")
        assertThat(source).doesNotContain("ORDER BY")
    }

    @Test
    fun `a row built without naming its source has none`() {
        // The fixtures of five test files build this row by hand; the accelerometer mask has no
        // origin to name; and Room reads the column as nullable. All three want the same default.
        val accessor = ComparableNight::class.java.getDeclaredMethod("getSourcePackage")
        assertThat(accessor.returnType).isEqualTo(String::class.java)
        assertThat(
            ComparableNight(
                sessionHex = "abcd",
                startWallMs = 0L,
                zoneId = "UTC",
                paramsHash = "h",
                rule = "AASM_V3",
                maskSource = "ACCEL_IMMOBILITY",
                gate = "TRUNCATED_NO_TREND",
                independence = "CIRCULAR",
                plmi = null,
                plmiSpt = null,
                fundamentalSec = null,
                rhythmValid = false,
                periodicityIndex = 0.0,
                periodicityValid = false,
                missRate = null,
                analysableTstMin = 0.0,
                analysableMin = 0.0,
                truncated = false,
                revealedAtMs = null,
                comparable = false,
                exclusionReason = ComparabilityRule.NO_CONTEXT,
            ).sourcePackage
        ).isNull()
    }
}
