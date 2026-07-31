package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import com.pendulum.phone.ui.theme.ChartTokens

/**
 * L'hypnogramme : escalier discret, trois voies, alignement strict avec le graphe de nuit.
 *
 * | Voie | Hauteur | Contenu |
 * |---|---|---|
 * | Masque accelero | 12 dp | mobile / immobile |
 * | Hypnogramme Health Connect | 72 dp | Eveil, REM, N1, N2, N3, de haut en bas |
 * | Desaccord | 6 dp | segments ou les deux masques divergent, hachure ambre |
 *
 * ### Ce que la mise en page dit sans l'ecrire
 *
 * L'ordre vertical est celui des laboratoires de sommeil : eveil en haut, sommeil profond en bas.
 * C'est la **position** qui porte le stade, pas la couleur — un daltonien lit cet hypnogramme
 * aussi bien que n'importe qui, et une capture en niveaux de gris ne perd rien.
 *
 * Le REM est le seul stade a recevoir un traitement visuel distinct, parce que c'est le seul dont
 * l'interpretation change la lecture : peu de mouvements periodiques y sont attendus. Il est
 * marque par une **hachure diagonale**, pas par une teinte seule.
 *
 * ### Aucun geste
 *
 * Cette fonction ne gere aucune interaction : elle recoit `curseurMs`. Le proprietaire unique du
 * geste est le graphe de nuit. Deux gestionnaires de zoom independants derivent, et un
 * hypnogramme decale d'un pixel fait lire un mouvement dans le mauvais stade.
 */
fun DrawScope.dessinerHypnogramme(
    spec: HypnogrammeSpec,
    t: ChartTokens,
    x: XTransform,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    curseurMs: Long? = null,
) {
    val m = margesDefaut
    val zone = zoneTrace(m.copy(bas = dpPx(4f)))
    x.widthPx = zone.width

    val hMasque = dpPx(12f)
    val hDesaccord = dpPx(6f)
    val ecart = dpPx(4f)
    val hStades = (zone.height - hMasque - hDesaccord - 2 * ecart).coerceAtLeast(dpPx(24f))

    val yMasque = zone.top
    val yStades = yMasque + hMasque + ecart
    val yDesaccord = yStades + hStades + ecart

    fun xDe(ms: Long) = zone.left + x.xOf(ms)

    // --- voie 1 : masque accelero. Les intervalles contigus sont deja fusionnes dans le Spec :
    //     sans cela, une nuit de 8 h produirait un millier de rectangles de 30 s.
    for (iv in spec.immobiliteAccelero) {
        val x0 = xDe(iv.debutMs).coerceIn(zone.left, zone.right)
        val x1 = xDe(iv.finMs).coerceIn(zone.left, zone.right)
        if (x1 <= x0) continue
        drawRect(t.structural, Offset(x0, yMasque), Size(x1 - x0, hMasque), alpha = 0.65f)
    }
    texteAxe(mesureur, scratch, t, "immobile", dpPx(2f), yMasque - dpPx(1f))

    // --- voie 2 : hypnogramme, ou son absence
    if (spec.stades == null) {
        // Pas de voie vide avec ses graduations : un axe vide invite l'oeil a chercher une
        // courbe, et fait passer une donnee absente pour une panne de rendu.
        drawRect(t.mutedBand, Offset(zone.left, yStades), Size(zone.width, hStades))
        texteAxe(
            mesureur, scratch, t, spec.texteIndisponible,
            zone.left + zone.width / 2f, yStades + hStades / 2f - dpPx(6f),
            couleur = t.annotationText, centre = true,
        )
    } else {
        dessinerEscalier(spec.stades, t, zone, yStades, hStades, mesureur, scratch, ::xDe)

        // --- voie 3 : desaccord entre les deux masques. Elle n'existe que si les deux existent.
        for (iv in spec.desaccord) {
            val x0 = xDe(iv.debutMs).coerceIn(zone.left, zone.right)
            val x1 = xDe(iv.finMs).coerceIn(zone.left, zone.right)
            if (x1 <= x0) continue
            hachurer(Rect(x0, yDesaccord, x1, yDesaccord + hDesaccord), t.attention, t.strokeThin, dpPx(3f))
        }
    }

    curseurMs?.let {
        val cx = xDe(it)
        if (cx in zone.left..zone.right) {
            drawLine(t.annotationText, Offset(cx, zone.top), Offset(cx, zone.bottom), t.strokeThin)
        }
    }
}

/**
 * L'escalier proprement dit.
 *
 * Le `Path` est construit en coordonnees **normalisees 0..1 sur X**, ce qui permet de le
 * memoriser une fois par jeu de donnees et de le transformer au dessin : zoomer ne reconstruit
 * jamais le chemin. Ici il est reconstruit a chaque appel parce que la fonction doit rester une
 * fonction pure de `DrawScope` — la memorisation appartient au composable enveloppe, qui garde
 * le `Path` dans un `remember(spec)`.
 */
private fun DrawScope.dessinerEscalier(
    stades: List<SegmentStade>,
    t: ChartTokens,
    zone: Rect,
    haut: Float,
    hauteur: Float,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    xDe: (Long) -> Float,
) {
    val hauteurNiveau = hauteur / StadeUi.NIVEAUX
    fun yDe(s: StadeUi) = haut + s.rang * hauteurNiveau + hauteurNiveau / 2f

    // Libelles de niveau : la position verticale est le porteur principal de l'information.
    for (s in StadeUi.entries) {
        texteAxe(mesureur, scratch, t, s.libelle, dpPx(2f), yDe(s) - dpPx(6f))
    }

    val chemin = Path()
    var premier = true
    var yPrecedent = 0f
    for (seg in stades) {
        val x0 = xDe(seg.debutMs)
        val x1 = xDe(seg.finMs)
        val y = yDe(seg.stade)
        if (premier) {
            chemin.moveTo(x0, y)
            premier = false
        } else {
            // Marche verticale au changement de stade, puis palier horizontal.
            chemin.lineTo(x0, yPrecedent)
            chemin.lineTo(x0, y)
        }
        chemin.lineTo(x1, y)
        yPrecedent = y

        // REM : hachure diagonale dans sa marche. Un PathEffect sur le trait ne remplit pas une
        // zone — il faut clipper et boucler.
        if (seg.stade == StadeUi.REM && x1 > x0) {
            val r = Rect(
                x0.coerceIn(zone.left, zone.right),
                y - hauteurNiveau / 2f,
                x1.coerceIn(zone.left, zone.right),
                y + hauteurNiveau / 2f,
            )
            if (r.width > 0f) hachurer(r, t.secondSignal, t.strokeThin, dpPx(6f))
        }
    }
    drawPath(chemin, t.primaryData, style = Stroke(width = t.strokeBold))
}
