package com.pendulum.phone.ui.tonight

/**
 * What the "Alcohol, units" field keeps of what was just typed.
 *
 * `KeyboardType.Decimal` shows the decimal separator of the device's locale, and on a phone set
 * to French, German or Japanese-with-French-input that separator is a comma. The field used to
 * keep only digits and `.`: the comma was thrown away without a word, so "1,5" was displayed and
 * sealed as "15" — fifteen units, on a row the append-only triggers then refuse to correct, and
 * exported to the physician as such. The comma is therefore read as a decimal point rather than
 * dropped.
 *
 * A second separator is refused rather than kept. "1.2.3" is nothing `toDoubleOrNull` can read,
 * and the sealing used to turn that `null` into `0.0` silently — a night with alcohol recorded as
 * a night without any, with nothing on the screen to say so.
 *
 * Pure, and outside the composable, because this is exactly the kind of rule a JVM test must be
 * able to reach: the defect it prevents is invisible in a screenshot taken on an English phone.
 */
internal fun normaliseAlcoholInput(typed: String): String {
    val kept = typed.replace(',', '.').filter { it.isDigit() || it == '.' }
    val dot = kept.indexOf('.')
    return if (dot < 0) {
        kept
    } else {
        kept.substring(0, dot + 1) + kept.substring(dot + 1).filter { it.isDigit() }
    }
}

/**
 * True when sealing would record the figure on screen rather than a silent `0.0`. After
 * [normaliseAlcoholInput] the only text this rejects is a lone ".", and an empty field is an
 * explicit "none", not an unreadable one.
 */
internal fun alcoholParsable(text: String): Boolean = text.isBlank() || text.toDoubleOrNull() != null
