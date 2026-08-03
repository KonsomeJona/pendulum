package com.pendulum.format.temps

import com.pendulum.format.wire.WireProtocol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** La loi d'echelle, seule. Ce qu'elle fait et surtout ce qu'elle refuse de faire. */
class TempsTest {

    @Test
    fun `le diviseur reel est l'identite`() {
        val durees = longArrayOf(1L, 10_000L, 300_000L, 36L * 3_600_000L)
        for (d in durees) {
            assertThat(Temps.ms(d, Temps.DIVISEUR_REEL)).isEqualTo(d)
        }
        assertThat(Temps.DIVISEUR_REEL).isEqualTo(1L)
    }

    @Test
    fun `l'echelle du banc comprime exactement`() {
        val d = 250L
        assertThat(Temps.ms(300_000L, d)).isEqualTo(1_200L)          // rotation de chunk
        assertThat(Temps.ms(8L * 3_600_000L, d)).isEqualTo(115_200L) // une nuit
        assertThat(Temps.ms(36L * 3_600_000L, d)).isEqualTo(518_400L) // abandon Health Connect
    }

    @Test
    fun `une duree non nulle ne devient jamais nulle`() {
        // Un delai a zero ne serait pas « plus rapide », ce serait un autre comportement :
        // « toutes les cinq minutes » deviendrait « a chaque bloc ».
        assertThat(Temps.ms(10L, 1_000_000L)).isEqualTo(1L)
        assertThat(Temps.ms(1L, Long.MAX_VALUE)).isEqualTo(1L)
    }

    @Test
    fun `zero et negatif traversent inchanges`() {
        // « tout de suite » et « deja passe » n'ont pas d'echelle.
        assertThat(Temps.ms(0L, 600L)).isEqualTo(0L)
        assertThat(Temps.ms(-5L, 600L)).isEqualTo(-5L)
    }

    @Test
    fun `un diviseur inferieur a un est refuse`() {
        // Zero est une division par zero, negatif inverserait l'ordre du temps, et une valeur
        // fractionnaire n'existe pas ici : le diviseur est entier pour que l'ordre relatif des
        // durees survive a l'arrondi.
        for (mauvais in longArrayOf(0L, -1L, -600L)) {
            assertThatThrownBy { Temps.ms(1_000L, mauvais) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `les durees du produit restent strictement ordonnees a toutes les echelles`() {
        // C'est la raison d'etre du diviseur entier : le banc teste des sequences (la salve part
        // avant l'expiration, le rang T+1 h tombe avant le rang T+2 h), et deux durees qui
        // ressortiraient egales apres arrondi ne casseraient pas une valeur mais un scenario.
        //
        // La propriete n'est **pas** generale — 1 000 ms et 1 001 ms se confondent des le
        // diviseur 600. Elle vaut pour les durees reellement portees par le produit, qui sont
        // espacees d'un facteur au moins deux, et c'est de celles-la qu'on a besoin.
        val nominal = longArrayOf(10_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)
        for (d in longArrayOf(1L, 2L, 250L, 600L, 3_000L)) {
            val comprime = Temps.ms(nominal, d)
            for (i in 1 until comprime.size) {
                assertThat(comprime[i])
                    .`as`("diviseur %d : %d ms doit rester au-dessus de %d ms", d, nominal[i], nominal[i - 1])
                    .isGreaterThan(comprime[i - 1])
            }
        }
    }

    @Test
    fun `le plafond d'octets de la rotation n'est pas une duree`() {
        // Assertion de frontiere. `CHUNK_ROTATION_BYTES` est le garde-fou dur des deux conditions
        // de rotation : il verifie que les tampons memoire ne debordent pas et que la charge utile
        // reste sous les 100 Ko d'un `DataItem`. Le jour ou quelqu'un le fait passer par `Temps`,
        // le banc cesse de verifier ces deux choses tout en continuant de paraitre vert.
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES).isEqualTo(92_160L)
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES)
            .isLessThan(WireProtocol.MAX_DATA_ITEM_BYTES.toLong())
    }
}
