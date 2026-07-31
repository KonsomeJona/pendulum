package com.pendulum.phone.ingest

import com.pendulum.algo.model.SampleBlock
import com.pendulum.format.ChunkFormat
import com.pendulum.format.DecodedBlock

/**
 * L'adaptateur `DecodedBlock` -> `SampleBlock`. **C'est lui qui permet a `:algo` de ne dependre
 * de rien**, et il vit ici pour cette raison precise : si `:algo` connaissait
 * `com.pendulum.format.DecodedBlock`, la chaine de traitement serait liee a un format de fichier
 * binaire qui n'a rien a voir avec du traitement du signal, et le generateur synthetique — qui
 * alimente la chaine avec une verite terrain connue par construction — devrait fabriquer des
 * fichiers au lieu de fabriquer des echantillons.
 *
 * ### La conversion d'unite, qui n'est pas un detail
 *
 * `DecodedBlock` rend des **m/s²** : `ChunkReader` dequantifie avec `ChunkFormat.toMs2`.
 * `SampleBlock` attend des **g** (« Amplitudes en g », `Model.kt`). Le facteur est 9,80665.
 *
 * Oublier cette division ne provoque aucune erreur : la chaine tourne, produit des enveloppes,
 * detecte des evenements. Simplement, tous les seuils absolus de l'algorithme — le plancher de
 * bruit a 0,020 g, la tolerance de gravite 0,80-1,20 g, le jerk impossible a 8 g — sont
 * franchis d'un facteur 9,8, c'est-a-dire jamais franchis dans un sens et toujours dans
 * l'autre. Le symptome serait « le detecteur ne trouve rien » ou « tout est rejete », et rien
 * dans les traces ne pointerait vers une unite. C'est le genre de bug qui coute une campagne de
 * mesure entiere.
 */
object BlockAdapter {

    /** 1 g en m/s². Valeur exacte du SI, la meme que celle utilisee a la quantification. */
    const val G_IN_MS2 = ChunkFormat.G_IN_MS2

    /**
     * Copie defensive : les tableaux du bloc source sont laisses intacts.
     *
     * A utiliser dans les tests et partout ou le `DecodedBlock` sert encore apres. Sur une nuit
     * entiere, preferer [adoptInPlace] : 1,5 million d'echantillons x 3 axes x 4 octets font
     * ~19 Mo, et les dupliquer double la pointe memoire pour rien.
     */
    fun copyOf(block: DecodedBlock): SampleBlock = AdaptedBlock(
        tFirstNs = block.tFirstNs,
        tLastNs = block.tLastNs,
        flags = block.flags,
        x = FloatArray(block.sampleCount) { (block.x[it] / G_IN_MS2).toFloat() },
        y = FloatArray(block.sampleCount) { (block.y[it] / G_IN_MS2).toFloat() },
        z = FloatArray(block.sampleCount) { (block.z[it] / G_IN_MS2).toFloat() },
    )

    /**
     * Convertit **sur place** les tableaux du bloc et les reutilise tels quels.
     *
     * Contrat, a respecter faute de quoi les valeurs sont divisees deux fois : le
     * [DecodedBlock] passe ici **ne doit plus etre lu ensuite**. C'est vrai par construction
     * dans le seul appelant reel, [SessionReassembler], qui consomme le flux de
     * `ChunkReader.forEachBlock` et jette chaque bloc apres l'avoir adapte.
     */
    fun adoptInPlace(block: DecodedBlock): SampleBlock {
        val n = block.sampleCount
        val inv = (1.0 / G_IN_MS2).toFloat()
        for (i in 0 until n) {
            block.x[i] *= inv
            block.y[i] *= inv
            block.z[i] *= inv
        }
        return AdaptedBlock(block.tFirstNs, block.tLastNs, block.flags, block.x, block.y, block.z)
    }

    /**
     * `suspectTimebase` n'est **pas** transmis, et c'est voulu.
     *
     * `SampleBlock` n'a pas de champ pour lui, et le lui ajouter serait un mauvais echange :
     * `:algo` revalide de toute facon la base de temps a l'etape −1 (`Integrity.check`), sur les
     * memes criteres, parce qu'il ne peut pas heriter d'une garantie qu'un CRC de bloc ne donne
     * pas. Faire remonter le drapeau ferait croire a une information supplementaire la ou il n'y
     * a qu'un doublon — et masquerait le fait que la revalidation est faite en aval.
     */
    private class AdaptedBlock(
        override val tFirstNs: Long,
        override val tLastNs: Long,
        override val flags: Int,
        override val x: FloatArray,
        override val y: FloatArray,
        override val z: FloatArray,
    ) : SampleBlock
}
