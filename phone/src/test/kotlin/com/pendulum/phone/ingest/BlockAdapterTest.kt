package com.pendulum.phone.ingest

import com.pendulum.format.ChunkFormat
import com.pendulum.format.DecodedBlock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * L'adaptateur convertit des m/s² (ce que rend `ChunkReader`) en g (ce qu'attend `SampleBlock`).
 *
 * Oublier cette division ne provoque aucune erreur : la chaine tourne et produit des chiffres.
 * Simplement, tous les seuils absolus de l'algorithme sont franchis d'un facteur 9,8 — plancher
 * de bruit a 0,020 g, tolerance de gravite 0,80-1,20 g, jerk impossible a 8 g. Le symptome est
 * « le detecteur ne trouve rien » ou « tout est rejete », et rien ne pointe vers une unite.
 * D'ou ce test, qui est plus important qu'il n'en a l'air.
 */
class BlockAdapterTest {

    private fun bloc(vararg valeursMs2: Float): DecodedBlock {
        val n = valeursMs2.size
        return DecodedBlock(
            tFirstNs = 1_000L,
            tLastNs = 1_000L + (n - 1) * 20_000_000L,
            flags = ChunkFormat.FLAG_GAP_BEFORE,
            x = FloatArray(n) { valeursMs2[it] },
            y = FloatArray(n) { 0f },
            z = FloatArray(n) { ChunkFormat.G_IN_MS2.toFloat() },
        )
    }

    @Test
    fun `la gravite vaut 1 g apres conversion, pas 9,8`() {
        val adapte = BlockAdapter.copyOf(bloc(0f, 0f, 0f))
        assertThat(adapte.z[0]).isCloseTo(1.0f, within(1e-5f))
        assertThat(adapte.z[2]).isCloseTo(1.0f, within(1e-5f))
    }

    @Test
    fun `la copie ne touche pas le bloc source`() {
        val source = bloc(9.80665f, -9.80665f)
        BlockAdapter.copyOf(source)
        assertThat(source.x[0]).isEqualTo(9.80665f)
        assertThat(source.x[1]).isEqualTo(-9.80665f)
    }

    @Test
    fun `l'adoption sur place convertit les tableaux du bloc source`() {
        val source = bloc(9.80665f, -19.6133f)
        val adapte = BlockAdapter.adoptInPlace(source)

        assertThat(adapte.x[0]).isCloseTo(1.0f, within(1e-5f))
        assertThat(adapte.x[1]).isCloseTo(-2.0f, within(1e-4f))
        // Le contrat : les tableaux sont partages, le bloc source ne doit plus etre lu.
        assertThat(adapte.x).isSameAs(source.x)
    }

    @Test
    fun `les deux voies donnent le meme resultat`() {
        val valeurs = floatArrayOf(0f, 1f, -3.5f, 40f, -40f, 0.001f)
        val parCopie = BlockAdapter.copyOf(bloc(*valeurs))
        val parAdoption = BlockAdapter.adoptInPlace(bloc(*valeurs))
        for (i in valeurs.indices) {
            assertThat(parAdoption.x[i]).isCloseTo(parCopie.x[i], within(1e-6f))
        }
    }

    @Test
    fun `les timestamps et les drapeaux traversent l'adaptateur inchanges`() {
        val source = bloc(0f, 0f, 0f)
        val adapte = BlockAdapter.copyOf(source)
        assertThat(adapte.tFirstNs).isEqualTo(source.tFirstNs)
        assertThat(adapte.tLastNs).isEqualTo(source.tLastNs)
        // Les drapeaux portent FLAG_GAP_BEFORE, dont `:algo` a besoin pour casser ses segments.
        assertThat(adapte.flags).isEqualTo(ChunkFormat.FLAG_GAP_BEFORE)
    }

    @Test
    fun `un bloc d'un seul echantillon passe`() {
        val adapte = BlockAdapter.copyOf(bloc(9.80665f))
        assertThat(adapte.x).hasSize(1)
        assertThat(adapte.x[0]).isCloseTo(1.0f, within(1e-5f))
    }
}
