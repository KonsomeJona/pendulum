package com.pendulum.phone.export

import com.pendulum.phone.db.PlmResultEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The single-night table names its columns for what they carry.
 *
 * ### Why a test on a header
 *
 * No test asserted any header of the exporters, and the sixth column of the hourly-count table
 * read "Respiratory upper bound" while it carried `plmiRespWorstCase` — the index recomputed after
 * removing every apnoeic-band series, hence by construction never **above** the aPLM-i beside it
 * (`Indices.kt`: `plmsCountRespWorst <= plmsCount`, same denominator). The document handed to the
 * physician therefore said `12.40 /h | 8.20 /h` under `aPLM-i | Respiratory upper bound`: an
 * upper bound smaller than the value. Nothing in the pipeline could catch it, since the figure
 * itself was right; only the word was wrong, and the word is what the reader trusts.
 *
 * The test binds the two: the header cell over the sixth figure must say "lower bound", and the
 * smaller figure must land under it.
 */
class ReportTableTest {

    @Test
    fun `the column that carries the respiratory bracket is named a lower bound, and the smaller figure sits under it`() {
        val header = cells(ReportExporter.NIGHT_TABLE_HEADER)
        val row = cells(ReportExporter.row(result(plmi = 12.4, plmiRespWorstCase = 8.2)))
        assertThat(row).hasSameSizeAs(header)

        val bracketColumn = header.indexOfFirst { it.contains("lower bound") }
        assertThat(bracketColumn)
            .withFailMessage("no column of the single-night table announces a lower bound: $header")
            .isNotNegative()
        assertThat(header.none { it.contains("upper bound") })
            .withFailMessage("a column still calls the respiratory bracket an upper bound: $header")
            .isTrue()

        assertThat(row[bracketColumn]).isEqualTo("8.20 /h")
        assertThat(row[header.indexOf("aPLM-i")]).isEqualTo("12.40 /h")
    }

    /** The cells of a Markdown table line, without the outer pipes. */
    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun result(plmi: Double, plmiRespWorstCase: Double) = PlmResultEntity(
        sessionHex = "a1b2",
        paramsHash = "hash",
        rule = "AASM_V3",
        maskSource = "HEALTH_CONNECT",
        computedAtMs = 0L,
        algoVersion = "test",
        plmsCount = 62,
        plmwCount = 0,
        isolatedCount = 3,
        shortImiCount = 0,
        tstMin = 320.0,
        analysableTstMin = 300.0,
        sptMin = 400.0,
        wasoMin = 80.0,
        plmi = plmi,
        plmiSpt = 9.3,
        plmw = 0.0,
        plmiFirstHalf = 12.0,
        plmiSecondHalf = 12.8,
        plmiRespWorstCase = plmiRespWorstCase,
        periodicityIndex = 0.7,
        periodicityValid = true,
        fundamentalSec = 22.0,
        muLog = 3.1,
        sigmaLog = 0.2,
        missRate = 0.3,
        alternationSuspect = false,
        rhythmConverged = true,
        rhythmValid = true,
        truncatedSeriesDropped = 0,
        independence = "INDEPENDENT",
        gate = "FULL",
        floorMode = "GLOBAL",
    )
}
