package com.pendulum.phone.ui

import com.pendulum.phone.ui.tonight.alcoholParsable
import com.pendulum.phone.ui.tonight.normaliseAlcoholInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The evening form's alcohol field, on a keyboard that is not English.
 *
 * The field is sealed once and never corrected, and it goes into the export the physician reads.
 * The rule under test is small, but the figure it protects is the one figure of the context that
 * nothing downstream can sanity-check: fifteen units is a legal value.
 */
class AlcoholInputTest {

    /**
     * The defect this file exists for. `KeyboardType.Decimal` on a French phone sends a comma, and
     * the field used to keep digits and dots only — so the comma vanished and "1,5" was sealed as
     * fifteen units.
     */
    @Test
    fun `a comma typed as the decimal separator is read as one, not dropped`() {
        assertThat(normaliseAlcoholInput("1,5")).isEqualTo("1.5")
        assertThat(normaliseAlcoholInput("1.5")).isEqualTo("1.5")
        assertThat(normaliseAlcoholInput("1.5").toDoubleOrNull()).isEqualTo(1.5)
    }

    /**
     * Refused, not kept: "1.2.3" reads as `null`, and the sealing turned that into 0.0 in silence.
     * The second separator is simply not accepted, whatever character produced it.
     */
    @Test
    fun `a second decimal separator is refused rather than sealed as zero`() {
        assertThat(normaliseAlcoholInput("1.2.3")).isEqualTo("1.23")
        assertThat(normaliseAlcoholInput("1,2,3")).isEqualTo("1.23")
        assertThat(normaliseAlcoholInput("1.5,")).isEqualTo("1.5")
    }

    @Test
    fun `letters and spaces are still dropped, and an empty field stays empty`() {
        assertThat(normaliseAlcoholInput("2 units")).isEqualTo("2")
        assertThat(normaliseAlcoholInput("")).isEmpty()
    }

    /** A lone "." is the only text the normalised field can hold that no parser reads. */
    @Test
    fun `only a lone separator is unparsable, an empty field counts as none`() {
        assertThat(alcoholParsable("")).isTrue()
        assertThat(alcoholParsable("1.5")).isTrue()
        assertThat(alcoholParsable("5.")).isTrue()
        assertThat(alcoholParsable(".5")).isTrue()
        assertThat(alcoholParsable(".")).isFalse()
    }
}
