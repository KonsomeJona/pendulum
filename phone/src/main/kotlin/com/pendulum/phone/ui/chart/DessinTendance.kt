package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import com.pendulum.phone.ui.theme.ChartTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
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
    for (g in graduationsCalendaires(spec.premierJourMs, spec.dernierJourMs, spec.zoneId)) {
        val px = xDe(g.ms)
        drawLine(t.structural, Offset(px, zone.bottom), Offset(px, zone.bottom + dpPx(3f)), t.strokeThin)
        texteAxe(mesureur, scratch, t, g.etiquette, px, size.height - m.bas + dpPx(4f), centre = true)
    }
}

/** Une graduation de l'axe calendaire : ou elle se pose, et ce qu'elle dit. */
internal data class GraduationJour(val ms: Long, val etiquette: String)

/**
 * Les graduations de l'axe des X, calculees dans le fuseau ou les nuits ont ete vecues.
 *
 * ### Le defaut que cette fonction corrige
 *
 * La version precedente avancait par pas de 86 400 000 ms exactement depuis `premierJourMs` —
 * l'instant de **debut** de la premiere nuit, donc 23 h 14 et pas minuit — et convertissait
 * l'epoch en date par division entiere, c'est-a-dire en UTC. Deux erreurs, chacune suffisante :
 *
 *  - une nuit commencee a 23 h 14 a New York tombe le lendemain en UTC, et sa graduation
 *    s'etiquetait au jour suivant ;
 *  - un jour civil ne fait pas 86 400 000 ms deux fois par an. A partir du changement d'heure,
 *    l'heure locale des graduations derive d'une heure, finit par traverser minuit, et l'axe
 *    saute un jour ou en repete un.
 *
 * Le pas est donc un pas **civil** (`plusDays` sur un `ZonedDateTime`, qui reste a la meme heure
 * locale a travers un changement d'heure), et l'etiquette est la date locale de cet instant.
 *
 * Les graduations restent ancrees sur l'instant de la premiere nuit et non sur minuit : sur trois
 * nuits couchees a 23 h, des graduations a minuit tomberaient toutes entre les points, et la
 * premiere nuit n'aurait aucune etiquette.
 */
internal fun graduationsCalendaires(
    premierJourMs: Long,
    dernierJourMs: Long,
    zoneId: String,
): List<GraduationJour> {
    val fuseau = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val depart = Instant.ofEpochMilli(premierJourMs).atZone(fuseau)
    val arrivee = Instant.ofEpochMilli(dernierJourMs).atZone(fuseau)
    val jours = ChronoUnit.DAYS.between(depart.toLocalDate(), arrivee.toLocalDate()).toInt() + 1
    // Au-dela de deux semaines, une graduation par jour devient une bouillie de traits : on passe
    // a la semaine. C'est le seul reglage de densite de cet axe.
    val pas = if (jours <= 14) 1 else 7

    val sortie = ArrayList<GraduationJour>()
    var i = 0
    while (true) {
        val instant = depart.plusDays(i.toLong())
        val ms = instant.toInstant().toEpochMilli()
        if (ms > dernierJourMs) break
        if (i % pas == 0) sortie += GraduationJour(ms, instant.format(FORMAT_JOUR))
        i++
    }
    return sortie
}

/** `JJ/MM`. Le jour d'abord : c'est lui qui change entre deux graduations. */
private val FORMAT_JOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.UK)

/**
 * Recherche du point le plus proche, rayon d'acceptation 24 dp.
 * Renvoie l'identifiant de session, ou `null` si le doigt est tombe dans le vide.
 *
 * ### Pourquoi la densite est un parametre
 *
 * Parce que la zone de trace n'est pas le canevas. Le trace occupe `zoneTrace(margesDefaut)` —
 * 40 dp de marge a gauche pour les etiquettes de l'axe Y, 16 dp a droite, 12 et 22 en haut et en
 * bas — et cette fonction calculait ses coordonnees sur la largeur et la hauteur **totales**.
 * L'ecart atteignait 40 dp en X pour un rayon d'acceptation de 24 : taper un point pouvait le
 * manquer, ou selectionner son voisin. Sur un graphe dont chaque point ouvre le detail d'une
 * nuit, c'est ouvrir la mauvaise nuit — et rien a l'ecran ne dit que ce n'est pas celle qu'on a
 * visee.
 *
 * Le calcul est donc le meme que celui du dessin, marges comprises, y compris l'ecretage de la
 * valeur aux bornes de l'axe : un point hors echelle est dessine sur la borne, donc c'est la
 * qu'on le touche.
 */
fun trouverPointProche(
    spec: TendanceChartSpec,
    x: Float,
    y: Float,
    largeur: Float,
    hauteur: Float,
    densite: Float,
    rayonAcceptation: Float,
): String? {
    val zone = zoneTrace(margesDefaut(densite), largeur, hauteur)
    if (zone.width <= 0f || zone.height <= 0f) return null
    val axeY = AxeLineaire(spec.yMin, spec.yMax, zone.top, zone.height)
    val spanMs = (spec.dernierJourMs - spec.premierJourMs).coerceAtLeast(1L)
    var meilleur: String? = null
    var meilleureDistance = Float.MAX_VALUE
    for (p in spec.points) {
        val px = zone.left + (p.dateMs - spec.premierJourMs).toFloat() / spanMs * zone.width
        val py = axeY.y(p.valeur.coerceIn(spec.yMin, spec.yMax))
        val d = abs(px - x) + abs(py - y)
        if (d < meilleureDistance) {
            meilleureDistance = d
            meilleur = p.sessionHex
        }
    }
    return meilleur.takeIf { meilleureDistance <= rayonAcceptation }
}
