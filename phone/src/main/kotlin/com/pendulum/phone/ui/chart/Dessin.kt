package com.pendulum.phone.ui.chart

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.pendulum.phone.ui.theme.ChartTokens
import kotlin.math.log2
import kotlin.math.max

/**
 * Ce qui est commun aux trois graphes.
 *
 * Rappel du principe de `UX.md` §8.3, qui gouverne tout ce paquet : **le dessin est une fonction
 * d'extension de `DrawScope`, jamais un composable**. Le meme code est appele depuis un `Canvas`
 * Compose (ecran, sombre) et depuis un `DrawScope` monte sur le canvas d'une `PdfDocument.Page`
 * (export, clair). Un seul chemin de rendu, donc un seul endroit ou une divergence entre l'ecran
 * et le papier pourrait apparaitre — et cette divergence-la, personne ne la verrait avant qu'un
 * medecin ait la feuille en main.
 */

/**
 * Les tampons reutilises d'une frame a l'autre.
 *
 * Un pincement redessine soixante fois par seconde. Allouer 4 400 flottants et un `Paint` a
 * chaque passage declenche des ramassages visibles a l'oeil : le graphe saccade exactement au
 * moment ou l'utilisateur l'explore. Ce sac est cree une fois par graphe (`remember`) et passe
 * aux fonctions de dessin.
 */
class ChartScratch {
    /** `[min0, max0, min1, max1, …]` remplis par la pyramide. */
    var minMax: FloatArray = FloatArray(0)
        private set

    /** `[x0, y0, x1, y1, …]` au format attendu par `Canvas.drawLines`. */
    var segments: FloatArray = FloatArray(0)
        private set

    val paint: Paint = Paint().apply { isAntiAlias = true }

    private val texte = HashMap<String, TextLayoutResult>()

    fun preparer(colonnes: Int) {
        if (minMax.size < colonnes * 2) minMax = FloatArray(colonnes * 2)
        if (segments.size < colonnes * 4) segments = FloatArray(colonnes * 4)
    }

    /**
     * Mesurer du texte est le premier goulot d'un `Canvas` Compose : c'est un aller-retour vers
     * le moteur de rendu, par etiquette et par frame. Les libelles d'axes changent rarement, donc
     * on les memorise par chaine.
     */
    fun mesurer(mesureur: TextMeasurer, s: String, style: TextStyle): TextLayoutResult =
        texte.getOrPut(s + style.fontSize.value) { mesureur.measure(s, style) }
}

/** Marges internes d'un graphe, en pixels de dessin. */
data class Marges(val gauche: Float, val haut: Float, val droite: Float, val bas: Float)

/** Conversion dp → unites de dessin. A l'export, `density` porte le facteur points/dp. */
fun DrawScope.dpPx(v: Float): Float = v * density

val DrawScope.margesDefaut: Marges
    get() = Marges(gauche = dpPx(40f), haut = dpPx(12f), droite = dpPx(16f), bas = dpPx(22f))

/** Zone de trace effective. */
fun DrawScope.zoneTrace(m: Marges): Rect = Rect(
    left = m.gauche,
    top = m.haut,
    right = size.width - m.droite,
    bottom = size.height - m.bas,
)

/**
 * Axe Y logarithmique en base 2, pour le graphe de nuit.
 *
 * Le seuil de detection etant un multiple fixe du plancher de bruit, il devient en log₂ une
 * **droite horizontale** : la logique du detecteur se lit alors d'un coup d'oeil, ce qui est
 * exactement ce que l'utilisateur technicien vient verifier.
 */
class AxeLog2(private val min: Float, private val max: Float, private val haut: Float, private val hauteur: Float) {
    private val l0 = log2(min.coerceAtLeast(1e-3f))
    private val l1 = log2(max.coerceAtLeast(min * 2f))
    fun y(ratio: Float): Float {
        val l = log2(ratio.coerceAtLeast(min * 0.5f))
        return haut + hauteur * (1f - (l - l0) / (l1 - l0))
    }
}

/** Axe Y lineaire ancre a zero. Utilise par la tendance, et jamais tronque. */
class AxeLineaire(private val min: Float, private val max: Float, private val haut: Float, private val hauteur: Float) {
    fun y(v: Float): Float = haut + hauteur * (1f - (v - min) / (max - min).coerceAtLeast(1e-6f))
}

/** Hachure a 45°, dessinee par clip + boucle. Un `PathEffect` de trait ne remplit pas une zone. */
fun DrawScope.hachurer(rect: Rect, couleur: Color, epaisseur: Float, espacement: Float) {
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        val h = rect.height
        var i = rect.left - h
        while (i < rect.right) {
            drawLine(
                color = couleur,
                start = Offset(i, rect.bottom),
                end = Offset(i + h, rect.top),
                strokeWidth = epaisseur,
            )
            i += espacement
        }
    }
}

/** Ligne tiretee : le second porteur d'information a cote de la couleur (P6). */
fun DrawScope.ligneTiretee(
    y: Float,
    de: Float,
    a: Float,
    couleur: Color,
    epaisseur: Float,
    dash: FloatArray,
    alpha: Float = 1f,
) = drawLine(
    color = couleur,
    start = Offset(de, y),
    end = Offset(a, y),
    strokeWidth = epaisseur,
    alpha = alpha,
    pathEffect = PathEffect.dashPathEffect(dash, 0f),
)

/**
 * Le trace de l'enveloppe : un segment vertical `min → max` par colonne de pixels, pousse en une
 * seule fois sur le canvas natif.
 *
 * On descend volontairement sous `DrawScope` pour cette couche, et pour elle seule.
 * `DrawScope.drawPoints` attend une `List<Offset>` : construire cette liste alloue 1 100 objets
 * par frame, ce qui annule l'interet de la pyramide. `Canvas.drawLines(FloatArray, Paint)` prend
 * le tableau pre-alloue tel quel. Le canvas natif est disponible aussi bien sous un `Canvas`
 * Compose que sous une page de PDF, donc cette optimisation ne casse pas l'unicite du chemin de
 * rendu.
 */
fun DrawScope.dessinerEnveloppe(
    scratch: ChartScratch,
    colonnes: Int,
    xDe: Float,
    largeurColonne: Float,
    yDe: (Float) -> Float,
    couleur: Color,
    epaisseur: Float,
) {
    val seg = scratch.segments
    var n = 0
    for (c in 0 until colonnes) {
        val mn = scratch.minMax[c * 2]
        val mx = scratch.minMax[c * 2 + 1]
        if (mn > mx) continue
        val x = xDe + c * largeurColonne
        seg[n++] = x
        seg[n++] = yDe(mx)
        seg[n++] = x
        seg[n++] = yDe(mn)
    }
    scratch.paint.color = couleur.toArgb()
    scratch.paint.strokeWidth = epaisseur
    scratch.paint.strokeCap = Paint.Cap.BUTT
    drawContext.canvas.nativeCanvas.drawLines(seg, 0, n, scratch.paint)
}

/** Petit utilitaire : texte d'axe, deja mesure et memorise. */
fun DrawScope.texteAxe(
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    t: ChartTokens,
    s: String,
    x: Float,
    y: Float,
    couleur: Color = t.axisText,
    taille: TextUnit = t.axisTextSize.sp,
    centre: Boolean = false,
) {
    val style = TextStyle(color = couleur, fontSize = taille, fontFeatureSettings = "tnum")
    val mesure = scratch.mesurer(mesureur, s, style)
    val dx = if (centre) -mesure.size.width / 2f else 0f
    drawText(mesure, topLeft = Offset(x + dx, y))
}

/** Cercle plein / cercle plein cercle / cercle creux : les trois etats de nuit, en formes. */
fun DrawScope.pointNuit(centre: Offset, etat: EtatPoint, t: ChartTokens, rayon: Float) {
    when (etat) {
        EtatPoint.ELIGIBLE -> drawCircle(t.primaryData, rayon, centre)
        EtatPoint.MASQUE_ACCELERO -> {
            drawCircle(t.primaryData, rayon, centre)
            drawCircle(t.attention, rayon + t.strokeNormal, centre, style = Stroke(t.strokeNormal))
        }
        EtatPoint.ECARTEE -> drawCircle(t.structural, rayon, centre, style = Stroke(t.strokeNormal))
    }
}

internal fun bornerColonnes(largeur: Float): Int = max(1, largeur.toInt())
