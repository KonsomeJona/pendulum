package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import com.pendulum.phone.ui.theme.ChartTokens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Le graphe de tendance.
 *
 * Peu de points — soixante nuits au plus — donc aucun probleme de performance : tout le travail
 * est dans la justesse. Trois proprietes s'y jouent, et chacune a deja ete « corrigee » a tort
 * dans un produit comparable :
 *
 * - **l'axe Y part de zero, en dur** (voir la KDoc de [TendanceChartSpec.yMin]) ;
 * - **les points ne sont pas relies** — c'est une decision de conception, expliquee plus bas ;
 * - **les nuits ecartees sont dessinees**, a leur valeur, en cercle creux, et exclues de tout
 *   calcul. Les cacher donnerait une image plus propre et une lecture fausse : on ne verrait plus
 *   qu'une semaine sur trois a ete jetee.
 */
fun DrawScope.dessinerGrapheTendance(
    spec: TendanceChartSpec,
    t: ChartTokens,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
) {
    val m = margesDefaut
    val zone = zoneTrace(m)
    val axeY = AxeLineaire(spec.yMin, spec.yMax, zone.top, zone.height)
    val spanMs = (spec.dernierJourMs - spec.premierJourMs).coerceAtLeast(1L)

    fun xDe(ms: Long): Float =
        zone.left + (ms - spec.premierJourMs).toFloat() / spanMs * zone.width

    // --- graduations Y
    var v = spec.yMin
    while (v <= spec.yMax) {
        val y = axeY.y(v)
        drawLine(
            t.structural, Offset(zone.left, y), Offset(zone.right, y), t.strokeThin,
            alpha = ChartTokens.GRADUATION_ALPHA,
        )
        texteAxe(mesureur, scratch, t, v.roundToInt().toString(), dpPx(4f), y - dpPx(6f))
        v += spec.pasGraduation
    }

    // --- ligne de reference (seuil clinique). Elle n'existe que pour le compte horaire :
    //     aucun seuil publie ne s'applique au rythme mesure a la cheville, et en dessiner un
    //     serait fabriquer une frontiere.
    spec.reference?.let { ref ->
        val y = axeY.y(ref.valeur)
        ligneTiretee(y, zone.left, zone.right, t.structural, t.strokeNormal, t.dashReference)
        texteAxe(mesureur, scratch, t, ref.libelle, zone.right - dpPx(28f), y - dpPx(14f))
    }

    // --- bandes d'IC 95 % de la mediane, puis la mediane elle-meme
    for (b in spec.bandes) {
        val x0 = xDe(b.debutMs)
        val x1 = xDe(b.finMs)
        val yHaut = axeY.y(b.ciHaut)
        val yBas = axeY.y(b.ciBas)
        // Le remplissage est une emphase, **les bornes sont l'information**. C'est donc sur les
        // deux tiretes que porte l'exigence de 3:1 de WCAG 1.4.11, et elles sont tracees en trait
        // normal et non fin : mesures dans `ContrasteGraphesTest`. Monter le remplissage au meme
        // contraste demanderait une opacite d'environ 0,50, qui noierait les points de nuit
        // dessines par-dessus — un element conforme echange contre un element illisible.
        drawRect(t.primaryData, Offset(x0, yHaut), Size(x1 - x0, yBas - yHaut), alpha = t.ciBandAlpha)
        ligneTiretee(
            yHaut, x0, x1, t.primaryData, t.strokeNormal, t.dashThreshold,
            alpha = ChartTokens.BORNES_IC_ALPHA,
        )
        ligneTiretee(
            yBas, x0, x1, t.primaryData, t.strokeNormal, t.dashThreshold,
            alpha = ChartTokens.BORNES_IC_ALPHA,
        )

        val yMed = axeY.y(b.mediane)
        drawLine(t.primaryData, Offset(x0, yMed), Offset(x1, yMed), t.strokeBold)
        b.etiquette?.let { texteAxe(mesureur, scratch, t, it, x1 - dpPx(26f), yMed - dpPx(14f), couleur = t.annotationText) }
    }

    // --- separateur de comparaison : une ligne verticale a la date pivot, jamais une fleche.
    //     Une fleche vers le bas se lit « ca va dans le bon sens » ; ce graphe ne dit pas cela.
    spec.pivotMs?.let { pivot ->
        val px = xDe(pivot)
        drawLine(t.annotationText, Offset(px, zone.top), Offset(px, zone.bottom), t.strokeThin)
    }

    // --- points par nuit
    //
    // AUCUNE POLYLIGNE ENTRE LES POINTS. Ce n'est pas un oubli : une ligne brisee entre deux
    // nuits dessine une trajectoire continue entre deux mesures qui n'ont rien de continu, et
    // suggere une causalite qui n'existe pas. Si une evolution monotone est reellement la, la
    // position des points suffit a la montrer. Merci de ne pas « reparer » ceci.
    val rayon = dpPx(2.5f)
    for (p in spec.points) {
        val cx = xDe(p.dateMs)
        val cy = axeY.y(p.valeur.coerceIn(spec.yMin, spec.yMax))
        pointNuit(Offset(cx, cy), p.etat, t, rayon)
    }

    // --- axe X calendaire : les nuits sont a leur date reelle, un trou reste un trou
    dessinerAxeXCalendaire(spec, t, zone, m, mesureur, scratch, ::xDe)
}

private fun DrawScope.dessinerAxeXCalendaire(
    spec: TendanceChartSpec,
    t: ChartTokens,
    zone: Rect,
    m: Marges,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    xDe: (Long) -> Float,
) {
    val jourMs = 86_400_000L
    val jours = ((spec.dernierJourMs - spec.premierJourMs) / jourMs).toInt() + 1
    val pas = if (jours <= 14) 1 else 7
    var ms = spec.premierJourMs
    var i = 0
    while (ms <= spec.dernierJourMs) {
        if (i % pas == 0) {
            val px = xDe(ms)
            drawLine(t.structural, Offset(px, zone.bottom), Offset(px, zone.bottom + dpPx(3f)), t.strokeThin)
            texteAxe(mesureur, scratch, t, formatJour(ms), px, size.height - m.bas + dpPx(4f), centre = true)
        }
        ms += jourMs
        i++
    }
}

/**
 * `JJ/MM`, sans dependance a un fuseau. Comme pour le graphe de nuit, l'horodatage arrive deja
 * ramene a l'heure murale locale par le ViewModel : la fonction de dessin doit rester identique
 * a l'ecran et a l'export, donc ignorante du calendrier.
 */
private fun formatJour(ms: Long): String {
    val jours = ms / 86_400_000L
    // Conversion civile minimale depuis l'epoch (algorithme de Howard Hinnant, jours -> y/m/d).
    val z = jours + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val mois = if (mp < 10) mp + 3 else mp - 9
    return "%02d/%02d".format(d, mois)
}

/**
 * Recherche du point le plus proche, rayon d'acceptation 24 dp.
 * Renvoie l'identifiant de session, ou `null` si le doigt est tombe dans le vide.
 */
fun trouverPointProche(
    spec: TendanceChartSpec,
    x: Float,
    y: Float,
    largeur: Float,
    hauteur: Float,
    rayonAcceptation: Float,
): String? {
    val spanMs = (spec.dernierJourMs - spec.premierJourMs).coerceAtLeast(1L)
    var meilleur: String? = null
    var meilleureDistance = Float.MAX_VALUE
    for (p in spec.points) {
        val px = (p.dateMs - spec.premierJourMs).toFloat() / spanMs * largeur
        val py = hauteur * (1f - (p.valeur - spec.yMin) / (spec.yMax - spec.yMin))
        val d = abs(px - x) + abs(py - y)
        if (d < meilleureDistance) {
            meilleureDistance = d
            meilleur = p.sessionHex
        }
    }
    return meilleur.takeIf { meilleureDistance <= rayonAcceptation }
}
