package com.pendulum.phone.ui

import com.pendulum.phone.ui.text.UiText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The test that makes guard rail 5 executable.
 *
 * `SPEC-v2.md` §3: "No evolution verb in the string resources — verifiable by a unit test on the
 * text file."
 *
 * ### Why this is a test and not a review instruction
 *
 * This project produces a figure that could influence a dosing decision. The main failure mode is
 * not a computation bug: it is a sentence. "9/h improvement" asserts a direction the data does not
 * carry, because below the smallest detectable change the difference is not distinguishable from
 * the noise between one night and the next. A review instruction is forgotten by the third
 * re-reading of a 700-line file; a red test is not.
 *
 * What the application says instead: "Inconclusive", or "Difference larger than the variability
 * between your nights" — a statement about distinguishability, never a direction, and never a
 * cause.
 *
 * ### Two nets, and why two are needed
 *
 * 1. The **scan of `res/values/strings.xml`**, where every text the interface displays lives. The
 *    whole file is scanned, **XML comments included**: the KDoc of the old text file was carried
 *    over into it, and a faulty comment always ends up being copied into a string.
 * 2. The **scan of `ui/text/Texts.kt`**, which now carries only the `UiText` bridge and two
 *    utilities. There is nothing left to find in it, and that is exactly why the scan stays:
 *    putting an English sentence back inline in that file is the most natural shortcut in the
 *    world, and it must stay red.
 *
 * ### The anti-empty-test guard
 *
 * The resource scan starts by checking that it **found its file** and that it **read something**
 * from it. A guard-rail test that cannot find its target must fail, not pass: a `filter` over an
 * empty string returns an empty list, and an empty list is exactly what this test expects on
 * success. That is the only failure mode that would make the guard rail silently useless.
 */
class TextsTest {

    /**
     * The forbidden roots, lower case and unaccented after normalisation.
     *
     * They cover the inflected forms: "improve", "improvement", "improved" share the root
     * `improv`. The list lives **here** and not in the text files — putting them there would make
     * them detected by their own test.
     *
     * ### One language, one rule
     *
     * The interface has been in English since the repository became public, and since the
     * French-to-English pass the comments of `strings.xml` and the KDoc of `Texts.kt` are in
     * English too. The list is therefore English throughout. Every French root it used to carry
     * was replaced by the English root covering the same claim, and the roots below carry that
     * inheritance explicitly, because they are the ones the English list did not already cover:
     *
     *  - `progressi` and `progresse` replace `progres`. Two roots and not the single `progress`,
     *    because "progress indicator" is the Material 3 component name and "fraction of progress"
     *    is how this code describes it: a bare `progress` would fire on the vocabulary of the
     *    interface itself. The pair still catches progression, progressing, progressed,
     *    progresses — that is, every form in which a **result** could be said to have progressed.
     *  - `aggravat` replaces `aggrav`;
     *  - `degrad` is **kept as it stands**. It sat in the French half of the old list, but what it
     *    actually catches is the English `degraded` / `degradation`: that is why the two exceptions
     *    below carve out the mask wording rather than a French one. Dropping it as a French root
     *    would leave those exceptions guarding nothing, and would let "degradation of the index"
     *    through — a direction claimed on a health figure, which is the whole point of this list.
     *  - `increas` and `decreas` replace `augmentation du nombre` and `reduction du nombre`, and
     *    they are wider than what they replace: a bare "the index decreased" is exactly the claim
     *    this guard rail exists to refuse, and the phrase-level entries did not catch it.
     *
     * Two French roots were **dropped rather than transposed**, and both times because the English
     * word that would have replaced them is a legitimate technical term this repository uses:
     * `empir` (empire, empirer) would fire on "empirical", and `pire` on nothing English at all.
     * Both claims are already covered by `worse` and `worsen`, so no detection is lost.
     */
    private val forbiddenRoots = listOf(
        "improv",    // improve, improvement, improved
        "worsen",    // worsen, worsening
        "worse",     // worse, getting worse
        "better",    // better, getting better
        "deteriorat", // deteriorate, deterioration
        "declin",    // decline, declining
        "regress",   // regression of a result
        "progressi", // progression, progressing — of a result
        "progresse", // progressed, progresses
        "aggravat",  // aggravate, aggravation
        "degrad",    // degradation of a result (the word stays allowed for a mask: see below)
        "increas",   // increase, increased, increasing
        "decreas",   // decrease, decreased, decreasing
        "effectiv",  // effective, effectiveness ("efficacy" falls on `efficac`)
        "efficac",   // efficacy, efficacious
        "healing",
        "cured",
        "remission",
        "recovery",
        "it works",
        "reduction in the number",
        "increase in the number",
        "drop in the index",
        "rise in the index",
    )

    /**
     * The only tolerated occurrences, because they do not qualify a **health result**. Each must be
     * justified here before being added; the list is deliberately short and its lengthening is the
     * signal that the rule is being worked around.
     */
    private val exceptions = listOf(
        "degraded mask",      // qualifies the quality of a measurement, not a clinical evolution
        "degraded quality",
    )

    /**
     * Lower case, and nothing else.
     *
     * It used to fold French accents as well. Both scanned files are now written in English and
     * the repository is written unaccented by convention — `tools/ci/verifier-traduction.py`
     * fails on any accented character — so the folding had no input left. Keeping it would have
     * suggested that an accented text could still reach here, which is the opposite of what the CI
     * guarantees.
     */
    private fun normalise(s: String): String = s.lowercase()

    private fun withoutExceptions(s: String): String =
        exceptions.fold(s) { acc, e -> acc.replace(e, "") }

    // ---------------------------------------------------------------------------------
    // Net 1: the string resources
    // ---------------------------------------------------------------------------------

    @Test
    fun `the string resources contain no evolution verb`() {
        val file = resourceFile()
        assertThat(file)
            .withFailMessage(
                "strings.xml cannot be found. This test is guard rail 5: if it cannot read the " +
                    "string resources, it guarantees nothing and must fail rather than pass.",
            )
            .isNotNull()

        val raw = file!!.readText()

        // The anti-empty-test guard. A file that is present but empty, or whose shape has changed,
        // would yield zero strings — and a `filter` over zero strings returns the empty list this
        // test expects on success.
        val strings = stringsFromXml(raw)
        assertThat(strings)
            .withFailMessage(
                "No string read from %s. The guard rail no longer checks anything: either the " +
                    "file is empty, or the `<string name=\"…\">…</string>` shape has changed.",
                file.path,
            )
            .isNotEmpty()

        // The **whole** file, XML comments included: the KDoc was carried over into it, and it is
        // in a comment that a faulty wording appears first.
        val content = withoutExceptions(normalise(raw))
        val found = forbiddenRoots.filter { content.contains(it) }

        assertThat(found)
            .withFailMessage(
                "Evolution verb(s) found in %s: %s.\n" +
                    "Pendulum never says that a figure has moved in one direction: below the " +
                    "smallest detectable change, the direction is not distinguishable from the " +
                    "night-to-night variability. Accepted wordings: \"Inconclusive\" and " +
                    "\"Difference larger than the variability between your nights\".",
                file.path, found,
            )
            .isEmpty()
    }

    // ---------------------------------------------------------------------------------
    // Net 2: what is left in the text file
    // ---------------------------------------------------------------------------------

    @Test
    fun `the text file contains no evolution verb`() {
        val file = textsFile()
        assertThat(file)
            .withFailMessage(
                "Texts.kt cannot be found. This test is guard rail 5: if it cannot read the text " +
                    "file, it guarantees nothing and must fail rather than pass.",
            )
            .isNotNull()

        val raw = file!!.readText()
        assertThat(raw)
            .withFailMessage(
                "%s is empty. The guard rail no longer checks anything.",
                file.path,
            )
            .isNotBlank()

        val content = withoutExceptions(normalise(raw))
        val found = forbiddenRoots.filter { content.contains(it) }

        assertThat(found)
            .withFailMessage(
                "Evolution verb(s) found in %s: %s.\n" +
                    "Same rule as for the resources: moving a sentence from `strings.xml` into " +
                    "this file does not take it out of the guard rail.",
                file.path, found,
            )
            .isEmpty()
    }

    // ---------------------------------------------------------------------------------
    // The wiring: every identifier in the code finds its string
    // ---------------------------------------------------------------------------------

    /**
     * The empty resources that are meant to be empty, each with the one sentence that says why.
     *
     * Same shape as the `tolerances` map of `DurationsInventoryTest`, and for the same reason: a
     * generated baseline would silence the check without recording a single **why**, and the next
     * empty string — the accidental one — would slip in beside the deliberate ones unnoticed.
     * `containsExactly` below means the list has to be maintained in both directions: an
     * unexplained empty string fails, and so does an entry whose resource has been filled in or
     * deleted.
     */
    private val DELIBERATELY_EMPTY = mapOf(
        "trend_unit_none" to
            "The unit of a dimensionless quantity. `Aggregate.Quantity` types its unit as a " +
            "non-null @StringRes, so a miss rate and a periodicity index — which are ratios, and " +
            "carry no unit at all — need a resource that renders as nothing. Writing '(none)' or " +
            "'-' there would print a fake unit next to the figure.",
    )

    /**
     * A resource renamed on one side only does not compile; an **empty** resource compiles
     * perfectly well and makes every `doesNotContain` assertion in the module pass. This test
     * therefore refuses empty strings, which are the only silent failure this format allows —
     * except for the handful listed in [DELIBERATELY_EMPTY], which carry their reason.
     */
    @Test
    fun `no resource string is empty`() {
        val empty = stringsFromXml(resourceFile()!!.readText())
            .filterValues { it.isBlank() }
            .keys
        assertThat(empty).containsExactlyInAnyOrderElementsOf(DELIBERATELY_EMPTY.keys)
    }

    // ---------------------------------------------------------------------------------
    // The non-negotiable content of the notice
    // ---------------------------------------------------------------------------------

    @Test
    fun `the notice says what it must say`() {
        val resources = stringsFromXml(resourceFile()!!.readText())

        val body = normalise(
            resources["notice_body"]
                ?: error("`notice_body` has disappeared from strings.xml: the notice has no body."),
        )
        // The five non-negotiable claims. If one is dropped in a rewrite, the test falls.
        assertThat(body).contains("this is not an official health application")
        assertThat(body).contains("this is not a medical device")
        assertThat(body).contains("makes no diagnosis")
        assertThat(body).contains("no treatment decision should rest on")
        assertThat(body).contains("does not measure your breathing")

        // And it also appears, condensed, at the head of every export.
        val banner = normalise(
            resources["notice_export_banner"]
                ?: error("`notice_export_banner` has disappeared: the export has lost its notice."),
        )
        assertThat(banner).contains("this is not a medical device")
        assertThat(banner).contains("no treatment decision should rest on")
    }

    @Test
    fun `no text promises a diagnosis`() {
        val all = stringsFromXml(resourceFile()!!.readText()).values
        val offending = all.filter {
            val n = normalise(it)
            n.contains("diagnoses your") || n.contains("you have a syndrome")
        }
        assertThat(offending).isEmpty()
    }

    // ---------------------------------------------------------------------------------
    // Tools
    // ---------------------------------------------------------------------------------

    /** Walks up from the working directory until it finds the text file. */
    private fun textsFile(): File? =
        walkUpTo("src/main/kotlin/com/pendulum/phone/ui/text/Texts.kt")

    /** The same walk up, for the string resources. */
    private fun resourceFile(): File? = walkUpTo("src/main/res/values/strings.xml")

    private fun walkUpTo(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "phone/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * The strings of `strings.xml`, by name.
     *
     * A full XML parser would be more rigorous, but it would hide what this test is looking for:
     * the regex fails loudly — zero strings — if the shape of the file changes, and the
     * anti-empty-test guard turns that failure into a red test. A tolerant parser would return a
     * partial list without saying anything.
     */
    private fun stringsFromXml(raw: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(raw)
            .associate { it.groupValues[1] to it.groupValues[2] }

}

internal object Resources {

    private val byId: Map<Int, String> by lazy {
        com.pendulum.phone.R.string::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.isAccessible = true
                (field.get(null) as Int) to field.name
            }
    }

    private val byName: Map<String, String> by lazy {
        val file = stringsFile()
            ?: error("res/values/strings.xml cannot be found from ${System.getProperty("user.dir")}")
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            .also { require(it.isNotEmpty()) { "No string read from ${file.path}" } }
    }

    /** The raw text of a resource, without substitution. */
    fun read(@androidx.annotation.StringRes id: Int): String {
        val name = byId[id] ?: error("No resource name for identifier $id")
        return byName[name] ?: error("`$name` is referenced by the code and absent from strings.xml")
    }

    /** The same resolution as `UiText.resolve`, nested arguments included. */
    fun resolve(t: UiText): String = when (t) {
        is UiText.Raw -> t.value
        is UiText.Res ->
            if (t.args.isEmpty()) {
                read(t.id)
            } else {
                val resolved = t.args.map { if (it is UiText) resolve(it) else it }
                String.format(java.util.Locale.UK, read(t.id), *resolved.toTypedArray())
            }
    }

    /**
     * The aapt escapes, applied by hand.
     *
     * These are not all the ones the format has: they are the ones this file uses. Adding one
     * implies that a string uses it, and that is the moment to check that it displays correctly.
     */
    private fun unescape(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun stringsFile(): java.io.File? {
        val relative = "src/main/res/values/strings.xml"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (c in listOf(java.io.File(dir, relative), java.io.File(dir, "phone/$relative"))) {
                if (c.isFile) return c
            }
            dir = dir.parentFile
        }
        return null
    }
}
