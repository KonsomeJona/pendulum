package com.pendulum.phone.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Le predicat de comparabilite est le garde-fou qui remplace le bouton « exclure cette nuit ».
 * S'il est faux, l'exclusion redevient une decision, et une decision prise apres avoir vu le
 * chiffre est exactement le mecanisme d'auto-tromperie que tout le projet cherche a empecher.
 *
 * D'ou une couverture exhaustive : chaque critere, dans les deux sens, plus l'ordre de priorite.
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
    fun `une nuit nominale est comparable`() {
        assertThat(ComparabilityRule.evaluate(facts())).isEqualTo(ComparabilityRule.OK)
        assertThat(ComparabilityRule.isComparable(facts())).isTrue()
    }

    @Test
    fun `sans contexte scelle, rien n'est comparable`() {
        // Et le motif doit etre NO_CONTEXT, pas « bracelet different » : dire a quelqu'un qui a
        // oublie le formulaire du soir que son bracelet a change serait un diagnostic faux.
        assertThat(ComparabilityRule.evaluate(facts(hasContext = false)))
            .isEqualTo(ComparabilityRule.NO_CONTEXT)
    }

    @Test
    fun `changer de jambe rend la nuit incomparable`() {
        assertThat(ComparabilityRule.evaluate(facts(leg = "LEFT")))
            .isEqualTo(ComparabilityRule.LEG_CHANGED)
    }

    @Test
    fun `changer de bracelet rend la nuit incomparable`() {
        assertThat(ComparabilityRule.evaluate(facts(strapId = "strap-b")))
            .isEqualTo(ComparabilityRule.STRAP_CHANGED)
    }

    @Test
    fun `un partenaire dans le lit rend la nuit incomparable`() {
        assertThat(ComparabilityRule.evaluate(facts(aloneInBed = false)))
            .isEqualTo(ComparabilityRule.NOT_ALONE)
    }

    @Test
    fun `un gain inconnu n'est pas un gain conforme`() {
        // Le cas piege : on ne peut pas verifier, donc on n'affirme pas. Traiter « inconnu »
        // comme « conforme » ferait passer en tendance une nuit dont rien ne dit qu'elle est
        // comparable.
        assertThat(ComparabilityRule.evaluate(facts(gainCalG = null)))
            .isEqualTo(ComparabilityRule.CAL_GAIN_UNKNOWN)
        assertThat(ComparabilityRule.evaluate(facts(refGainCalG = null)))
            .isEqualTo(ComparabilityRule.CAL_GAIN_UNKNOWN)
        assertThat(ComparabilityRule.evaluate(facts(refGainCalG = 0.0)))
            .isEqualTo(ComparabilityRule.CAL_GAIN_UNKNOWN)
    }

    @Test
    fun `le gain est tolere jusqu'a 35 pourcent d'ecart, exclu au-dela`() {
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
    fun `quatre heures analysables sont un plancher inclusif`() {
        assertThat(ComparabilityRule.evaluate(facts(analysableMin = 240.0)))
            .isEqualTo(ComparabilityRule.OK)
        assertThat(ComparabilityRule.evaluate(facts(analysableMin = 239.9)))
            .isEqualTo(ComparabilityRule.TOO_SHORT)
    }

    @Test
    fun `la nuit du changement d'heure est ecartee`() {
        // Le seul critere qui se lit sur deux entiers plutot que sur un calcul de calendrier :
        // les deux offsets sont enregistres precisement pour ca.
        assertThat(ComparabilityRule.evaluate(facts(tzStart = 60, tzEnd = 120)))
            .isEqualTo(ComparabilityRule.DST_NIGHT)
    }

    @Test
    fun `l'ordre de priorite va du plus structurel au plus circonstanciel`() {
        // Une nuit qui viole tout ne doit rapporter qu'un motif, et c'est le plus fondamental.
        val toutFaux = facts(
            hasContext = false, leg = "LEFT", strapId = "strap-b",
            aloneInBed = false, gainCalG = null, analysableMin = 10.0, tzEnd = 120,
        )
        assertThat(ComparabilityRule.evaluate(toutFaux)).isEqualTo(ComparabilityRule.NO_CONTEXT)

        val contexteOk = toutFaux.copy(hasContext = true)
        assertThat(ComparabilityRule.evaluate(contexteOk)).isEqualTo(ComparabilityRule.LEG_CHANGED)

        val jambeOk = contexteOk.copy(leg = "RIGHT")
        assertThat(ComparabilityRule.evaluate(jambeOk)).isEqualTo(ComparabilityRule.STRAP_CHANGED)

        val braceletOk = jambeOk.copy(strapId = "strap-a")
        assertThat(ComparabilityRule.evaluate(braceletOk)).isEqualTo(ComparabilityRule.NOT_ALONE)

        val seulOk = braceletOk.copy(aloneInBed = true)
        assertThat(ComparabilityRule.evaluate(seulOk)).isEqualTo(ComparabilityRule.CAL_GAIN_UNKNOWN)

        val gainOk = seulOk.copy(gainCalG = 1.0)
        assertThat(ComparabilityRule.evaluate(gainOk)).isEqualTo(ComparabilityRule.TOO_SHORT)

        val dureeOk = gainOk.copy(analysableMin = 480.0)
        assertThat(ComparabilityRule.evaluate(dureeOk)).isEqualTo(ComparabilityRule.DST_NIGHT)

        assertThat(ComparabilityRule.evaluate(dureeOk.copy(tzOffsetEndMin = 60)))
            .isEqualTo(ComparabilityRule.OK)
    }

    /**
     * Le double Kotlin et le SQL sont deux implementations du meme predicat : ce test ne peut
     * pas prouver qu'ils sont d'accord (il faudrait une base), mais il attrape le cas de loin le
     * plus probable — un critere ajoute d'un cote et oublie de l'autre.
     */
    @Test
    fun `le SQL de la vue mentionne les six criteres et les deux constantes`() {
        val sql = ComparableNightSql.SQL
        assertThat(sql).contains("c.leg <> ref.refLeg")
        assertThat(sql).contains("c.strapId <> ref.refStrapId")
        assertThat(sql).contains("c.aloneInBed = 0")
        assertThat(sql).contains("abs(s.gainCalG - ref.refGainCalG) / ref.refGainCalG > 0.35")
        assertThat(sql).contains("s.analysableMin < 240.0")
        assertThat(sql).contains("s.tzOffsetStartMin <> s.tzOffsetEndMin")

        // Les constantes SQL sont ecrites en dur (SQLite ne lit pas une constante Kotlin) :
        // on verifie au moins qu'elles n'ont pas diverge de leur source.
        assertThat(sql).contains(ComparabilityRule.GAIN_TOLERANCE.toString())
        assertThat(sql).contains(ComparabilityRule.MIN_ANALYSABLE_MIN.toString())

        // Tous les motifs du Kotlin doivent exister dans le SQL, faute de quoi l'interface
        // recevrait un motif qu'elle ne sait pas traduire.
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
     * La vue **annote**, elle ne filtre pas : une nuit ecartee doit rester visible avec son
     * motif. Un `WHERE` dans la vue la ferait disparaitre, et une nuit invisible est une nuit
     * qu'on oublie d'expliquer.
     */
    @Test
    fun `la vue ne filtre pas les nuits ecartees`() {
        assertThat(ComparableNightSql.SQL.uppercase()).doesNotContain("WHERE COMPARABLE")
        assertThat(ComparableNightSql.SQL).contains("AS comparable")
        assertThat(ComparableNightSql.SQL).contains("AS exclusionReason")
    }
}
