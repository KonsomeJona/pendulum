package com.pendulum.wear.time

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The inventory: **no wall-clock duration is allowed to live outside [Durations]**.
 *
 * `DurationsTest` checks that everything already in the catalogue is scaled. It can say nothing
 * about what never entered it — and that is the real failure mode: nobody will write a duration
 * into `Durations` without scaling it, everybody will one day write
 * `setInitialDelay(30, TimeUnit.SECONDS)` in a file where it was the natural gesture. The bench
 * would then run with one path at real time in the middle of compressed paths, and the symptom
 * would be a scenario timing out instead of a message saying why.
 *
 * ### An inverted assertion, in the spirit of T11
 *
 * The [tolerances] list is not a list of debts to be paid down: these are the values it has been
 * **decided** do not compress, each with its reason. The test fails in both directions — a new and
 * uninventoried duration makes it fail, but so does a tolerance gone obsolete. In both cases the
 * decision has to be read again rather than endured.
 *
 * ### What it does not see
 *
 * A source scan reads text, not meaning: a duration assembled at run time (`aNumber * aUnit` read
 * from elsewhere) escapes it. It catches the forms people actually write, which is enough for what
 * it is worth — not a proof, a net.
 */
class DurationsInventoryTest {

    /** The forms under which a wall-clock duration slips into Kotlin code. */
    private val patterns = listOf(
        "wall-clock duration built with TimeUnit" to
            Regex("""TimeUnit\.\w+\.to\w+\("""),
        "...Ms constant defined from a numeric literal" to
            Regex("""\bval\s+\w*(?:_MS|Ms)\b[^=\n]*=\s*[^=\n]*(?<![A-Za-z0-9_])\d[\d_]*[\d_]"""),
        "hours written out in the open" to
            Regex("""\d[\d_]*\s*\*\s*3_600_000"""),
        "comparison against a literal in thousands" to
            Regex("""[<>]=?\s*\d[\d_]*_000L?\b"""),
        "WorkManager period or delay written in the open" to
            Regex("""(?:PeriodicWorkRequestBuilder<[^>]*>\(\s*\d|setInitialDelay\(\s*\d)"""),
    )

    /**
     * What is allowed to stay hard-coded, and why. The key is `FileName.kt:excerpt`.
     *
     * Two families, and a single rule to tell them apart: **sensor time** does not compress. The
     * bench's synthetic source keeps the nominal period in sensor time while running fast in
     * wall-clock time; a window expressed in `SensorEvent` nanoseconds therefore measures exactly
     * the same thing on the bench and on the wrist, and dividing it would make it measure
     * something else.
     */
    private val tolerances = mapOf(
        "PreviewEnvelope.kt:1_000_000_000L" to
            "one-second bucket of sensor time, not of wall-clock time",
        "StopConditions.kt:30_000_000_000L" to
            "30 s epoch of WakeDetector, in SensorEvent nanoseconds",
    )

    @Test
    fun `no wall-clock duration lives outside the catalogue`() {
        val findings = mutableListOf<String>()
        val tolerancesUsed = mutableSetOf<String>()

        for (file in mainSources()) {
            file.readLines().forEachIndexed { index, line ->
                val code = codeOnly(line)
                for ((what, pattern) in patterns) {
                    if (!pattern.containsMatchIn(code)) continue
                    val key = tolerances.keys.firstOrNull {
                        val (name, excerpt) = it.split(":", limit = 2)
                        file.name == name && code.contains(excerpt)
                    }
                    if (key != null) {
                        tolerancesUsed += key
                    } else {
                        findings += "${file.name}:${index + 1} [$what] " +
                            "${line.trim().take(90)} — to be moved into Durations"
                    }
                    break
                }
            }
        }

        assertThat(findings)
            .`as`("wall-clock durations written outside Durations.kt")
            .isEmpty()

        // The other direction of the inverted assertion: a tolerance that no longer matches
        // anything means the code has changed underneath it, and that what it was protecting is no
        // longer known.
        assertThat(tolerancesUsed)
            .`as`("tolerances that have become pointless")
            .containsExactlyInAnyOrderElementsOf(tolerances.keys)
    }

    @Test
    fun `the scan really does read something`() {
        // A wrong path would turn the test above green on zero files.
        assertThat(mainSources()).hasSizeGreaterThan(15)
    }

    /**
     * `src/main/` only: the bench sources under `src/debug/` are entitled to their own delays.
     *
     * The working directory of a Gradle test is the module's, but no contract guarantees it: so we
     * walk up until we find it rather than returning zero files and a green test. `wear` is the
     * name of the module directory to look for from the root.
     */
    private fun mainSources(): List<File> = root()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        // The catalogue itself: this is the place where the nominal values are meant to be written.
        .filterNot { it.name == "Durations.kt" }
        .toList()

    private fun root(): File {
        val direct = File("src/main/kotlin")
        if (direct.isDirectory) return direct
        var candidate = File("").absoluteFile
        repeat(4) {
            val attempt = File(candidate, "wear/src/main/kotlin")
            if (attempt.isDirectory) return attempt
            candidate = candidate.parentFile ?: return direct
        }
        return direct
    }

    /**
     * Strips the comments. A KDoc that **quotes** a value — "fifteen minutes is the minimum of a
     * `PeriodicWorkRequest`" — explains the decision, it does not carry it, and a test that
     * silenced those explanations would have killed what it claims to protect.
     */
    private fun codeOnly(line: String): String {
        val bare = line.trim()
        if (bare.startsWith("*") || bare.startsWith("/*") || bare.startsWith("//")) return ""
        return line.substringBefore("//")
    }
}
