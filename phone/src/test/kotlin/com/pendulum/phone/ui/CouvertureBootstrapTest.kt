package com.pendulum.phone.ui

import com.pendulum.phone.ui.model.Aggregat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * La couverture reelle de l'intervalle affiche, mesuree plutot que supposee.
 *
 * ### Pourquoi ce test existe
 *
 * L'intervalle **est** l'argument de ce produit : c'est lui qui transforme un chiffre en mesure.
 * L'etiqueter « 95 % » sans avoir verifie qu'il couvre 95 % du temps serait exactement le defaut
 * que le reste de l'application existe pour eviter — une precision affirmee, jamais estimee.
 *
 * ### Ce qu'on mesure, et comment
 *
 * Simulation de Monte-Carlo : on tire `n` nuits d'une loi dont la mediane est connue, on calcule
 * l'intervalle percentile bootstrap exactement comme [Aggregat.bootstrapCi] le fait a l'ecran, et
 * on compte la proportion de replicats ou la mediane **vraie** tombe dedans. C'est la definition
 * operationnelle de la couverture, et rien d'autre ne la remplace.
 *
 * La loi de reference est log-normale de coefficient de variation 0,43 : c'est la variabilite
 * nuit a nuit du compte horaire mesuree par Skeba 2016 (43,2 % ± 37,1), donc le regime dans
 * lequel cette application travaille reellement. Le second jeu, gaussien, sert de temoin — si la
 * sous-couverture n'etait qu'un artefact d'asymetrie, elle disparaitrait la.
 *
 * ### Le plafond, qui est un theoreme et non un defaut d'implementation
 *
 * Toute mediane d'un reechantillonnage avec remise de `n` valeurs est **l'une de ces `n` valeurs**.
 * L'intervalle bootstrap est donc contenu dans `[min, max]` de l'echantillon, quel que soit le
 * nombre de tirages et quelle que soit la variante — percentile, BCa, ou percentile-t. Or
 *
 * ```
 * P(min < theta < max) = 1 - 2 x 2^-n
 * ```
 *
 * pour une mediane vraie `theta` (chaque valeur tombe au-dessus ou au-dessous avec probabilite
 * 1/2). Cela donne 75 % a n = 3, 87,5 % a n = 4, 93,75 % a n = 5, 96,9 % a n = 6.
 *
 * **Consequence directe : BCa ne repare rien ici.** La correction de biais et l'acceleration
 * deplacent les percentiles *a l'interieur* de la distribution bootstrap ; elles ne peuvent pas
 * la faire sortir de l'enveloppe `[min, max]`. Un intervalle a 95 % sur la mediane de trois
 * nuits n'existe pas, par construction. C'est pourquoi Pendulum n'implemente pas BCa et
 * **etiquette** l'intervalle comme non calibre sous [Aggregat.MIN_NUITS_IC_CALIBRE] nuits.
 */
class CouvertureBootstrapTest {

    private companion object {
        const val REPLICATS = 10_000
        const val MEDIANE_VRAIE = 20.0

        /** CV 0,43 : la variabilite nuit a nuit du compte horaire (Skeba 2016). */
        val SIGMA_LOG = sqrt(ln(1.0 + 0.43 * 0.43))
    }

    private fun couverture(n: Int, tirer: (Random) -> Double, replicats: Int = REPLICATS): Double {
        var dedans = 0
        val rng = Random(20260802L + n)
        repeat(replicats) { r ->
            val valeurs = DoubleArray(n) { tirer(rng) }
            val (bas, haut) = Aggregat.bootstrapCi(valeurs, graine = r.toLong() * 31 + n)
            if (MEDIANE_VRAIE in bas..haut) dedans++
        }
        return dedans.toDouble() / replicats
    }

    private fun logNormale(rng: Random): Double =
        exp(ln(MEDIANE_VRAIE) + SIGMA_LOG * gaussienne(rng))

    private fun gaussienne(rng: Random): Double {
        // Box-Muller. Une seule des deux sorties est utilisee : le cout est negligeable devant
        // les 2 000 reechantillonnages de chaque replicat.
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private fun normale(rng: Random): Double = MEDIANE_VRAIE + 6.0 * gaussienne(rng)

    /** `1 - 2 x 2^-n` : la couverture maximale atteignable par n'importe quel bootstrap. */
    private fun plafond(n: Int): Double = 1.0 - 2.0 * 2.0.pow(-n.toDouble())

    @Test
    fun `couverture empirique de l'intervalle percentile, n de 3 a 8`() {
        val lignes = StringBuilder("\ncouverture empirique du bootstrap percentile a 95 %\n")
        lignes.append("n   log-normale   gaussienne   plafond theorique\n")

        val mesures = HashMap<Int, Double>()
        for (n in 3..8) {
            val cLog = couverture(n, ::logNormale)
            val cNorm = couverture(n, ::normale)
            mesures[n] = cLog
            lignes.append(
                "%d   %.3f         %.3f        %.3f%n".format(n, cLog, cNorm, plafond(n)),
            )
        }
        println(lignes)

        // Le fait qui decide de l'etiquetage : sous six nuits, l'intervalle est nettement
        // au-dessous de son etiquette. On borne largement pour que ce test mesure sans devenir
        // fragile — ce sont les ordres de grandeur qui portent la decision.
        assertThat(mesures[3]!!).isLessThan(0.80)
        assertThat(mesures[4]!!).isLessThan(0.90)
        assertThat(mesures[5]!!).isLessThan(0.95)
    }

    @Test
    fun `balayage jusqu'a vingt nuits - ou la couverture rejoint son etiquette`() {
        val lignes = StringBuilder("\nbalayage de couverture, n de 3 a 20 (loi log-normale)\n")
        var premierBon = -1
        for (n in 3..20) {
            val c = couverture(n, ::logNormale, replicats = 3_000)
            lignes.append("%2d  %.3f   plafond %.3f%n".format(n, c, plafond(n)))
            if (premierBon < 0 && c >= 0.93) premierBon = n
        }
        lignes.append("premier n a couverture >= 0,93 : $premierBon\n")
        println(lignes)
        assertThat(premierBon).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun `aucun bootstrap ne peut depasser le plafond, quelle que soit sa variante`() {
        // La verification du theoreme sur l'implementation reelle : l'intervalle rendu est
        // toujours contenu dans l'etendue de l'echantillon. C'est ce qui rend BCa inutile ici.
        val rng = Random(7)
        repeat(2_000) { r ->
            val n = 3 + r % 6
            val valeurs = DoubleArray(n) { logNormale(rng) }
            val (bas, haut) = Aggregat.bootstrapCi(valeurs, graine = r.toLong())
            assertThat(bas).isGreaterThanOrEqualTo(valeurs.min())
            assertThat(haut).isLessThanOrEqualTo(valeurs.max())
        }
    }

    @Test
    fun `la couverture maximale publiee par Aggregat est celle du theoreme`() {
        assertThat(Aggregat.couvertureMaxBootstrap(3)).isEqualTo(0.75)
        assertThat(Aggregat.couvertureMaxBootstrap(4)).isEqualTo(0.875)
        assertThat(Aggregat.couvertureMaxBootstrap(5)).isEqualTo(0.9375)
        assertThat(Aggregat.couvertureMaxBootstrap(6)).isEqualTo(0.96875)
        // Le seuil d'etiquetage est le premier n dont le plafond depasse 95 %.
        assertThat(Aggregat.couvertureMaxBootstrap(Aggregat.MIN_NUITS_IC_CALIBRE))
            .isGreaterThan(0.95)
        assertThat(Aggregat.couvertureMaxBootstrap(Aggregat.MIN_NUITS_IC_CALIBRE - 1))
            .isLessThan(0.95)
    }
}
