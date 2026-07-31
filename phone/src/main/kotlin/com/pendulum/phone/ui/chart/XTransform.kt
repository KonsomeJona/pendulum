package com.pendulum.phone.ui.chart

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * La transformation horizontale, partagee entre le graphe de nuit et l'hypnogramme.
 *
 * ### Pourquoi c'est un objet hisse dans le parent et pas un etat interne a chaque graphe
 *
 * Les deux graphes doivent rester alignes au pixel : un mouvement marque a 02:14 dans le graphe
 * du haut doit tomber exactement au-dessus du stade N2 dans l'hypnogramme du bas. Deux etats de
 * zoom independants derivent des qu'un geste est perdu, et le desalignement qui en resulte est
 * un mensonge visuel — il fait lire un mouvement dans le mauvais stade.
 *
 * Un seul proprietaire de l'interaction, donc : le graphe de nuit gere les gestes,
 * l'hypnogramme ne fait que lire.
 *
 * ### Pourquoi des `mutableFloatStateOf`
 *
 * Un pincement modifie [scale] soixante fois par seconde. Si ces valeurs vivaient dans l'etat de
 * l'ecran, toute la hierarchie se recomposerait. En `@Stable` avec des etats flottants lus
 * uniquement dans le `Canvas`, seule la phase de dessin est invalidee : pas de recomposition,
 * pas de remesure.
 */
@Stable
class XTransform(
    val t0Ms: Long,
    val t1Ms: Long,
    val zoomMax: Float = 480f,
) {
    /** 1 = nuit entiere. 480 = une minute visible sur huit heures. */
    var scale by mutableFloatStateOf(1f)
        private set

    /** Debut de la fenetre visible, en fraction 0..1 de la nuit. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** Largeur de la zone de trace, en pixels. Ecrite par le `Canvas` a chaque dessin. */
    var widthPx by mutableFloatStateOf(1f)

    private val spanMs: Float get() = (t1Ms - t0Ms).toFloat().coerceAtLeast(1f)

    fun xOf(timeMs: Long): Float {
        val u = (timeMs - t0Ms) / spanMs
        return (u - offset) * scale * widthPx
    }

    fun timeAt(x: Float): Long {
        val u = offset + (x / widthPx) / scale
        return t0Ms + (u * spanMs).toLong()
    }

    /** Fraction visible de la nuit, en 0..1. Sert a choisir le niveau de la pyramide. */
    val fenetre: ClosedFloatingPointRange<Float> get() = offset..(offset + 1f / scale)

    fun applyPinch(centroidX: Float, panX: Float, zoom: Float) {
        val avant = offset + (centroidX / widthPx) / scale
        scale = (scale * zoom).coerceIn(1f, zoomMax)
        // On garde le point sous le doigt immobile, puis on applique le deplacement.
        offset = avant - (centroidX / widthPx) / scale - (panX / widthPx) / scale
        clamp()
    }

    fun reset() {
        scale = 1f
        offset = 0f
    }

    private fun clamp() {
        val largeur = 1f / scale
        offset = offset.coerceIn(0f, (1f - largeur).coerceAtLeast(0f))
    }
}
