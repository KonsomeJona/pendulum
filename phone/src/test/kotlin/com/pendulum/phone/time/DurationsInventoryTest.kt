package com.pendulum.phone.time

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The inventory: **no wall-clock duration is allowed to live outside [Durations]**.
 *
 * `DurationsTest` checks that everything in the catalogue scales. It can say nothing about what
 * never entered it — and that is the real failure mode: nobody will write a duration into
 * `Durations` without scaling it, everybody will one day write
 * `setInitialDelay(30, TimeUnit.SECONDS)` in a file where that was the natural gesture. The bench
 * would then run with one real-time path in the middle of compressed ones, and the symptom would
 * be a scenario that times out instead of a message saying why.
 *
 * ### An inverted assertion, in the spirit of T11
 *
 * The [tolerances] list is not a list of debts to pay down: these are the values it was **decided**
 * do not compress, each with its reason. The test fails in both directions — a new, uninventoried
 * duration makes it fall, but so does a tolerance that has become obsolete. In both cases the
 * decision has to be read again rather than endured.
 *
 * ### What it does not see
 *
 * A source scan reads text, not meaning: a duration assembled at run time (`aNumber * aUnit` read
 * elsewhere) escapes it. It catches the forms people actually write, which is enough for what it
 * is worth — not a proof, a net.
 */
class DurationsInventoryTest {

    /**
     * The shapes under which a wall-clock duration slips into Kotlin code.
     *
     * `motif` here is a **shape** and not a reason, so these are `patterns`: the glossary's
     * `motif = reason` applies to the reason a night is excluded, which is a different word doing
     * a different job.
     */
    private val patterns = listOf(
        "wall-clock duration built with TimeUnit" to
            Regex("""TimeUnit\.\w+\.to\w+\("""),
        "a ...Ms constant defined from a numeric literal" to
            Regex("""\bval\s+\w*(?:_MS|Ms)\b[^=\n]*=\s*[^=\n]*(?<![A-Za-z0-9_])\d[\d_]*[\d_]"""),
        "hours written out in the open" to
            Regex("""\d[\d_]*\s*\*\s*3_600_000"""),
        "comparison against a literal in thousands" to
            Regex("""[<>]=?\s*\d[\d_]*_000L?\b"""),
        "a WorkManager period or delay in the open" to
            Regex("""(?:PeriodicWorkRequestBuilder<[^>]*>\(\s*\d|setInitialDelay\(\s*\d)"""),
    )

    /**
     * What is allowed to stay inline, and why. The key is `FileName.kt:excerpt`.
     *
     * Two families here: **conversion factors** (an hour is 3,600,000 ms, whatever the speed at
     * which it is traversed) and **volumes**. The three thresholds of `Mapping.kt` are the example
     * to keep in mind: they are bytes, and bytes are precisely what this bench refuses to compress.
     */
    private val tolerances = mapOf(
        "TimeAnchor.kt:1_000_000L" to
            "nanoseconds -> milliseconds conversion, not a delay",
        // The hour ticks moved out of `NightDrawing.kt` into `Drawing.kt`: the three stacked bands
        // share the same axis, so they must share the function that ticks it. The tolerance follows
        // the code — a conversion factor stays a conversion factor. The file was `Dessin.kt`
        // before the English pass; only the key changed, the reason did not.
        "Drawing.kt:3_600_000L" to
            "one hour in milliseconds, scale factor of the time axis shared by the three bands",
        "Mapping.kt:1_000_000_000L" to
            "display threshold in gigabytes — a volume, never a duration",
        "Mapping.kt:1_000_000L" to
            "display threshold in megabytes — a volume, never a duration",
        "Mapping.kt:1_000L" to
            "display threshold in kilobytes — a volume, never a duration",
        // A third family, and the only one that really is a duration: those measured on the clock
        // of the **outside world** and not on the product's. The catalogue would compress them
        // along with the rest, which would break them — at divisor 600 this threshold would be
        // 0.67 ms and every permission request would look suppressed.
        "SleepReader.kt:400L" to
            "the round trip of a system dialog — it does not open any faster " +
            "because a bench compresses time",
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
        // anything means the code changed under it, and that we no longer know what it protected.
        assertThat(tolerancesUsed)
            .`as`("tolerances that have become pointless")
            .containsExactlyInAnyOrderElementsOf(tolerances.keys)
    }

    @Test
    fun `the scan does read something`() {
        // A wrong path would make the test above green over zero files.
        assertThat(mainSources()).hasSizeGreaterThan(15)
    }

    /**
     * `src/main/` only: the bench sources of `src/debug/` are entitled to their own delays.
     *
     * The working directory of a Gradle test is the module's, but no contract guarantees that: we
     * therefore walk up until we find it rather than returning zero files and a green test.
     * `phone` is the name of the module directory to look for from the root.
     */
    private fun mainSources(): List<File> = root()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        // The catalogue itself: it is the place where the nominal values must be written.
        .filterNot { it.name == "Durations.kt" }
        .toList()

    private fun root(): File {
        val direct = File("src/main/kotlin")
        if (direct.isDirectory) return direct
        var candidate = File("").absoluteFile
        repeat(4) {
            val attempt = File(candidate, "phone/src/main/kotlin")
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
