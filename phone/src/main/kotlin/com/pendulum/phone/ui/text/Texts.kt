package com.pendulum.phone.ui.text

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext

/**
 * The bridge between the model layer, which has no `Context`, and `res/values/strings.xml`.
 *
 * ### What this file replaced
 *
 * It carried `object Textes`, 461 hard-coded English constants, and its KDoc explained why they
 * were not in `strings.xml`: a Kotlin object can be tested, a `strings.xml` cannot. The argument
 * was true and it cost the translation — an English string compiled into an `object` cannot be
 * localised, however good its test. The two things are now held together: the texts live in the
 * resources, and `ui/TextsTest.kt` reads them there.
 *
 * ### The problem [UiText] solves
 *
 * Half of this product's texts are not written by a screen. They are **assembled by pure
 * functions** — `Aggregate.position`, `Situations.night`, `Mapping.flags`, `ComputationPath.of`,
 * `P1Gate.campaign` — whose correctness is checked by JVM tests without Android. Giving them a
 * `Context` would take them out of that regime, and it is exactly that regime which makes the
 * "three nights minimum" rule, or the refusal to fit a rhythm, verifiable.
 *
 * Those functions therefore return a [UiText]: a **resource identifier and its arguments**, not a
 * text. They stay pure, comparable and testable; the Compose layer resolves at the last moment,
 * with the device locale as it stands at display time — which a string resolved too early could
 * not do.
 *
 * ### What stays in Kotlin, and why it is not text
 *
 * [FileNames] and [Formats]. A file name is sorted, searched for, and pasted into a message:
 * translating it would make the name of the file depend on the language of the phone that wrote
 * it, and two exports from the same user would no longer sort together. A thousands separator is
 * not a string either — it is a formatting rule, which will follow `NumberFormat` the day a second
 * language arrives.
 */
@Immutable
sealed interface UiText {

    /**
     * A resource identifier and its arguments.
     *
     * The arguments are a **`List` and not a `vararg`**: a `vararg` in a `data class` yields an
     * `equals` by array identity, and structural equality is precisely what the JVM tests rely on
     * to check that a pure function returned the right text. An argument can itself be a
     * [UiText] — see [resolve], which descends into it.
     */
    @Immutable
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /**
     * A text that does not come from the resources because it is not translatable: the name of a
     * third-party application read from Health Connect, an already formatted percentage, a date.
     *
     * It is not an escape hatch for interface text: everything written in English in the code
     * belongs in `strings.xml`. What goes through here is what the device or the measurement
     * produced.
     */
    @Immutable
    data class Raw(val value: String) : UiText
}

/** Readable factory: `text(key, n, m)` rather than `UiText.Res(key, listOf(n, m))`. */
fun text(@StringRes id: Int, vararg args: Any): UiText.Res = UiText.Res(id, args.toList())

/** Factory for what the measurement or the device produced. See [UiText.Raw]. */
fun text(value: String): UiText.Raw = UiText.Raw(value)

/**
 * Resolution, outside composition.
 *
 * It is written here rather than in the composable for two reasons. It is **recursive**: an
 * argument can be a [UiText], which allows one to write "a value and its unit" without the pure
 * function that composes it having to know the unit in plain words. And it is usable **outside
 * Compose** — an exporter or a service that happens to hold a `Resources` reads the same text as
 * the screen, without a second piece of formatting existing anywhere.
 */
fun UiText.resolve(res: Resources): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res ->
        if (args.isEmpty()) {
            res.getString(id)
        } else {
            val resolved = args.map { if (it is UiText) it.resolve(res) else it }
            res.getString(id, *resolved.toTypedArray())
        }
}

/** Resolution in composition: the locale is the device's as it stands at display time. */
@Composable
fun UiText.resolve(): String = resolve(LocalContext.current.resources)

/**
 * The file names offered to the SAF picker.
 *
 * These are not interface texts and they are not in `strings.xml`: a file name is sorted, searched
 * for, and pasted into a message. Translating it would make the name of the file depend on the
 * language of the phone that wrote it, and a folder of exports would stop ordering itself — which
 * is the only thing asked of it.
 */
object FileNames {

    /** `pendulum-night-2026-03-12.md`: the report of **one** night, as text. A few kilobytes. */
    fun nightReport(day: String) = "pendulum-night-$day.md"

    /**
     * `pendulum-night-2026-03-12.bundle`: the raw signal, the sealed context and the hypnogram.
     *
     * It is named apart from the report because the two do not serve the same reader: the report
     * is read and printed, the bundle is re-imported. Confusing them means carrying ninety
     * megabytes of accelerometry to the doctor.
     */
    fun nightBundle(day: String) = "pendulum-night-$day.bundle"

    /** `pendulum-report-2026-03-15.md`: the campaign report. Sortable, unambiguous. */
    fun campaignReport(day: String) = "pendulum-report-$day.md"

    /** The P1 gate report, as CSV. One per device, hence no date in the name. */
    const val P1_REPORT = "pendulum-p1.csv"
}

/** The pieces of formatting that are not text. */
object Formats {

    /**
     * Comma as the thousands separator, English convention.
     *
     * This is not a string, it is a formatting rule: the day a second language arrives, it is
     * `NumberFormat` that will have to be called here, not a resource that will have to be
     * translated.
     */
    fun thousands(n: Int): String =
        n.toString().reversed().chunked(3).joinToString(",").reversed()
}
