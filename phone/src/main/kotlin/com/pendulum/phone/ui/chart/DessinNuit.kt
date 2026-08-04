package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import com.pendulum.phone.ui.theme.ChartTokens
import kotlin.math.roundToInt

/**
 * Le graphe de nuit — 1,44 million de points, six couches, aucun ecretage.
 *
 * Les couches sont dessinees de l'arriere vers l'avant, et l'ordre porte du sens :
 *
 * 1. **bandes hors sommeil** — elles disent visuellement « ici, rien n'est compte » ;
 * 2. **trous de signal** — haches, avec leur duree ecrite au-dessus si elle depasse 5 s ;
 * 3. **plancher de bruit** — pointille fin ;
 * 4. **seuil d'onset adaptatif** — tirete ; en echelle log₂ c'est une droite horizontale ;
 * 5. **enveloppe RMS** — trait plein, decimee min/max, jamais moyennee ;
 * 6. **marqueurs d'evenements** — sur une bande dediee **sous** l'aire du signal, jamais
 *    superposes a la courbe : superposes, ils cachent precisement la partie du signal qu'ils
 *    pretendent designer.
 *
 * Les trois lignes se distinguent par leur trace — plein / tirete / pointille — et pas seulement
 * par leur teinte (P6).
 */
fun DrawScope.dessinerGrapheNuit(
    spec: NuitChartSpec,
    t: ChartTokens,
    x: XTransform,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    curseurMs: Long? = null,
) {
    val m = margesDefaut
    val bandeMarqueurs = dpPx(14f)
    val zone = zoneTrace(m)
    val aireSignal = Rect(zone.left, zone.top, zone.right, zone.bottom - bandeMarqueurs)
    x.widthPx = aireSignal.width

    val axe = AxeLog2(spec.ratioMin, spec.ratioMax, aireSignal.top, aireSignal.height)
    fun yDe(ratio: Float) = axe.y(ratio)
    fun xDe(ms: Long) = aireSignal.left + x.xOf(ms)

    // --- 1. bandes hors sommeil
    for (iv in spec.horsSommeil) {
        val x0 = xDe(iv.debutMs).coerceIn(aireSignal.left, aireSignal.right)
        val x1 = xDe(iv.finMs).coerceIn(aireSignal.left, aireSignal.right)
        if (x1 <= x0) continue
        drawRect(t.mutedBand, Offset(x0, aireSignal.top), Size(x1 - x0, aireSignal.height))
    }

    // --- 2. trous de signal, haches, duree annotee au-dela de 5 s
    for (iv in spec.trous) {
        val x0 = xDe(iv.debutMs).coerceIn(aireSignal.left, aireSignal.right)
        val x1 = xDe(iv.finMs).coerceIn(aireSignal.left, aireSignal.right)
        if (x1 <= x0) continue
        val r = Rect(x0, aireSignal.top, x1, aireSignal.bottom)
        hachurer(r, t.structural, t.strokeThin, dpPx(2f))
        val secondes = (iv.finMs - iv.debutMs) / 1000
        if (secondes > 5) {
            texteAxe(mesureur, scratch, t, "$secondes s", (x0 + x1) / 2f, aireSignal.top, centre = true)
        }
    }

    // --- 3 et 4. plancher de bruit et seuil : quelques milliers de points, tres peu de colonnes
    dessinerSerieFine(spec.plancher, spec, aireSignal, x, t.structural, t.strokeThin, t.dashNoiseFloor, ::yDe)
    dessinerSerieFine(
        spec.seuilOnset, spec, aireSignal, x, t.primaryData, t.strokeNormal, t.dashThreshold, ::yDe,
        alpha = t.thresholdAlpha,
    )

    // --- 5. enveloppe, decimee min/max par colonne de pixels
    val colonnes = bornerColonnes(aireSignal.width)
    scratch.preparer(colonnes)
    val total = spec.pyramide.taille
    val de = (x.fenetre.start * total).roundToInt().coerceIn(0, total)
    val a = (x.fenetre.endInclusive * total).roundToInt().coerceIn(de + 1, total)
    spec.pyramide.remplirMinMax(de, a, colonnes, scratch.minMax)
    dessinerEnveloppe(
        scratch = scratch,
        colonnes = colonnes,
        xDe = aireSignal.left,
        largeurColonne = aireSignal.width / colonnes,
        yDe = ::yDe,
        couleur = t.primaryData,
        epaisseur = t.strokeNormal,
    )

    // --- pic hors echelle : annote, jamais coupe
    spec.pic?.let { pic ->
        if (pic.ratio > 32f) {
            texteAxe(
                mesureur, scratch, t,
                "peak ×${pic.ratio.roundToInt()} at ${pic.heure}",
                aireSignal.left + dpPx(4f), aireSignal.top + dpPx(2f),
                couleur = t.annotationText,
            )
        }
    }

    // --- 6. marqueurs, sur leur bande dediee
    dessinerMarqueurs(spec, t, aireSignal, bandeMarqueurs, ::xDe)

    // --- axes
    dessinerAxeYLog(spec, t, aireSignal, mesureur, scratch, ::yDe)
    dessinerAxeXHoraire(spec, t, aireSignal, m, mesureur, scratch, ::xDe)

    // --- curseur, partage avec l'hypnogramme
    curseurMs?.let {
        val cx = xDe(it)
        if (cx in aireSignal.left..aireSignal.right) {
            drawLine(t.annotationText, Offset(cx, zone.top), Offset(cx, zone.bottom), t.strokeThin)
        }
    }
}

/**
 * Recherche binaire sur la fenetre visible, puis boucle sur les seuls evenements visibles.
 * Boucler sur les 412 evenements de la nuit a chaque frame de pincement se sent au doigt.
 */
private fun DrawScope.dessinerMarqueurs(
    spec: NuitChartSpec,
    t: ChartTokens,
    aire: Rect,
    hauteurBande: Float,
    xDe: (Long) -> Float,
) {
    val hautBande = aire.bottom + dpPx(2f)
    val basBande = hautBande + hauteurBande - dpPx(6f)

    // Series : barre horizontale continue sous les marqueurs.
    for (s in spec.series) {
        val x0 = xDe(s.debutMs).coerceIn(aire.left, aire.right)
        val x1 = xDe(s.finMs).coerceIn(aire.left, aire.right)
        if (x1 <= x0) continue
        drawRect(
            t.primaryData,
            Offset(x0, basBande + dpPx(1f)),
            Size(x1 - x0, dpPx(4f)),
            alpha = t.seriesBarAlpha,
        )
    }

    val liste = spec.marqueurs
    var i = premierVisible(liste, aire.left, xDe)
    while (i < liste.size) {
        val mk = liste[i]
        val mx = xDe(mk.onsetMs)
        if (mx > aire.right) break
        if (mx >= aire.left) {
            when (mk.genre) {
                // Plein : compte.
                GenreMarqueur.COMPTE -> drawLine(
                    t.primaryData, Offset(mx, hautBande), Offset(mx, basBande), t.strokeBold,
                )
                // Croix fine : ecarte pour changement de posture.
                GenreMarqueur.EXCLU_POSTURE -> {
                    val r = dpPx(3f)
                    val cy = (hautBande + basBande) / 2f
                    drawLine(t.structural, Offset(mx - r, cy - r), Offset(mx + r, cy + r), t.strokeThin)
                    drawLine(t.structural, Offset(mx - r, cy + r), Offset(mx + r, cy - r), t.strokeThin)
                }
                // Creux : mouvement survenu en eveil, compte a part (PLMW).
                GenreMarqueur.EN_EVEIL -> {
                    drawLine(t.primaryData, Offset(mx, hautBande), Offset(mx, hautBande + dpPx(3f)), t.strokeNormal)
                    drawLine(t.primaryData, Offset(mx, basBande - dpPx(3f)), Offset(mx, basBande), t.strokeNormal)
                }
            }
        }
        i++
    }
}

private fun premierVisible(liste: List<Marqueur>, xMin: Float, xDe: (Long) -> Float): Int {
    var bas = 0
    var haut = liste.size
    while (bas < haut) {
        val mid = (bas + haut) / 2
        if (xDe(liste[mid].onsetMs) < xMin) bas = mid + 1 else haut = mid
    }
    return bas
}

private fun DrawScope.dessinerSerieFine(
    serie: FloatArray,
    spec: NuitChartSpec,
    aire: Rect,
    x: XTransform,
    couleur: androidx.compose.ui.graphics.Color,
    epaisseur: Float,
    dash: FloatArray,
    yDe: (Float) -> Float,
    alpha: Float = 1f,
) {
    if (serie.isEmpty()) return
    val effet = PathEffect.dashPathEffect(dash, 0f)
    var precedent: Offset? = null
    val pas = spec.pasSerieMs
    for (i in serie.indices) {
        val ms = spec.debutMs + i * pas
        val px = aire.left + x.xOf(ms)
        if (px < aire.left - 8f || px > aire.right + 8f) {
            precedent = null
            continue
        }
        val p = Offset(px, yDe(serie[i]))
        precedent?.let { drawLine(couleur, it, p, epaisseur, alpha = alpha, pathEffect = effet) }
        precedent = p
    }
}

/** Graduations `×1 ×2 ×4 ×8 ×16 ×32` : les puissances de deux, ecrites en clair. */
private fun DrawScope.dessinerAxeYLog(
    spec: NuitChartSpec,
    t: ChartTokens,
    aire: Rect,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    yDe: (Float) -> Float,
) {
    var ratio = 1f
    while (ratio <= spec.ratioMax) {
        val y = yDe(ratio)
        drawLine(t.structural, Offset(aire.left, y), Offset(aire.right, y), t.strokeThin, alpha = 0.25f)
        texteAxe(mesureur, scratch, t, "×${ratio.roundToInt()}", dpPx(4f), y - dpPx(6f))
        ratio *= 2f
    }
}

/**
 * Heure murale locale. Une nuit contenant un changement d'heure porte sa marque sur l'axe.
 *
 * Les graduations elles-memes vivent dans `Dessin.kt` : les trois bandes empilees partagent le
 * meme axe, donc elles doivent partager la fonction qui le gradue.
 */
private fun DrawScope.dessinerAxeXHoraire(
    spec: NuitChartSpec,
    t: ChartTokens,
    aire: Rect,
    m: Marges,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    xDe: (Long) -> Float,
) = dessinerGraduationsHoraires(
    debutMs = spec.debutMs,
    finMs = spec.finMs,
    aire = aire,
    yTexte = size.height - m.bas + dpPx(4f),
    t = t,
    mesureur = mesureur,
    scratch = scratch,
    xDe = xDe,
)
