package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.sp
import com.pendulum.phone.ui.theme.ChartTokens

/**
 * La bande d'etat de l'appareil — troisieme bande, meme axe, **grammaire etrangere**.
 *
 * | Voie | Hauteur | Contenu | Forme |
 * |---|---|---|---|
 * | `worn` | 12 dp | hors du poignet | blocs haches |
 * | `charger` | 8 dp | sous charge | blocs pleins |
 * | `timing` | 30 dp | classe de datation | escalier dur, trois niveaux nommes |
 * | `clip` | 10 dp | ecretage du capteur | *rug plot* |
 * | `freeze` | 10 dp | gels d'ecriture | *rug plot* |
 * | `battery` | 12 dp | charge restante | jauge de completion |
 *
 * ### Ce que la forme interdit de lire
 *
 * Au-dessus, la physiologie : continue, analogique, traits fins. Ici, rien de tout cela. Aucune
 * courbe, aucun trait qui relie deux instants, aucun axe Y gradue. Ce sont des blocs, des marches
 * dures, des tics de meme hauteur et une jauge — la convention du *housekeeping* en telemetrie
 * scientifique, isolee precisement pour ne pas etre lue comme du signal.
 *
 * L'axe X, lui, est **le meme**, et c'est une necessite : sans lui on lit un artefact de mesure
 * comme un evenement physiologique — un signal plat pris pour du calme alors que la montre etait
 * hors du poignet. La bande regradue donc l'axe horaire sous elle, ce qui est aussi ce qui lui
 * permet d'exister seule quand l'enveloppe n'a pas ete reconstruite.
 *
 * ### La jauge de batterie ne fait pas la largeur du trace, et c'est deliberé
 *
 * Une barre qui s'etendrait sur toute l'aire aurait une abscisse en face de chaque instant, et son
 * bord se lirait comme « la batterie est tombee a 3 h 12 ». La jauge est donc **volontairement plus
 * courte** que le trace, ancree a gauche, et fermee par un bord dur : aucune de ses positions ne
 * correspond a une heure. La question posee est « la montre a-t-elle tenu la nuit », pas « quelle
 * charge a 3 h 12 », et le chiffre exact est ecrit a cote.
 *
 * ### Rouge et vert
 *
 * Ils codent ici un etat **technique** — la batterie a tenu, ou non — ce qui est leur seul usage
 * autorise (`PendulumColors`). Et ils ne sont jamais seuls : la jauge tenue est un aplat plein, la
 * jauge non tenue est hachuree, et le chiffre est ecrit dans les deux cas.
 *
 * ### Aucun geste
 *
 * Comme l'hypnogramme, cette fonction ne gere aucune interaction : elle recoit `curseurMs`. Le
 * proprietaire unique du geste est le graphe de nuit — deux gestionnaires de zoom independants
 * derivent, et une bande decalee d'un pixel fait attribuer un mouvement a la mauvaise minute.
 */
/**
 * Les six mots de la marge gauche, **deja resolus**.
 *
 * Ils descendent en parametre plutot que d'etre lus ici : un `DrawScope` n'a ni composition ni
 * `Context`, et les resoudre a chaque trame de dessin serait payer une lecture de ressource pour
 * six mots qui ne changent pas. `BandeMetrologie` les lit une fois, en composition.
 */
data class LibellesBande(
    val port: String,
    val sansCapteur: String,
    val charge: String,
    val ecretage: String,
    val gels: String,
    val batterie: String,
)

fun DrawScope.dessinerBandeMetrologie(
    spec: MetrologieSpec,
    t: ChartTokens,
    x: XTransform,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    libelles: LibellesBande,
    curseurMs: Long? = null,
) {
    val gouttiere = dpPx(ChartTokens.GOUTTIERE_DP)
    val m = margesDefaut
    val zone = zoneTrace(m.copy(haut = m.haut + gouttiere))
    x.widthPx = zone.width

    // --- la gouttiere : un filet dur, pleine largeur, opaque. Il n'appartient a aucune des deux
    //     moities et c'est ce qui le rend lisible comme une frontiere plutot que comme une voie.
    drawLine(
        t.structural,
        Offset(0f, gouttiere / 2f),
        Offset(size.width, gouttiere / 2f),
        t.strokeBold,
    )

    fun xDe(ms: Long) = zone.left + x.xOf(ms)
    fun borne(v: Float) = v.coerceIn(zone.left, zone.right)

    // --- pas de telemetrie : une bande sourde et une phrase, jamais des voies vides. Un axe vide
    //     avec ses libelles fait chercher une panne la ou il y a une nuit enregistree avant que la
    //     telemetrie n'existe.
    //
    //     `datation` est le second test et non un doublon du premier : c'est la seule voie qui a un
    //     palier par point place, donc elle est vide exactement quand aucun point n'a pu etre
    //     ancre sur la base de temps des echantillons. Sans ce test, une nuit dont la telemetrie
    //     est arrivee mais dont aucun chunk n'est en base dessinerait six voies vides.
    if (spec.points == 0 || spec.datation.isEmpty()) {
        drawRect(t.mutedBand, Offset(zone.left, zone.top), Size(zone.width, zone.height))
        texteAxe(
            mesureur, scratch, t, spec.texteIndisponible,
            zone.left + zone.width / 2f, zone.top + zone.height / 2f - dpPx(6f),
            couleur = t.annotationText, centre = true,
        )
        return
    }

    val ecart = dpPx(5f)
    val hPort = dpPx(12f)
    val hCharge = dpPx(8f)
    val hRug = dpPx(10f)
    val hJauge = dpPx(12f)
    // L'escalier prend ce qui reste, avec un plancher : sur un canevas ecrase il vaut mieux une
    // voie tassee qu'une voie de hauteur negative, qui dessinerait ses marches a l'envers.
    val hDatation = (zone.height - hPort - hCharge - 2 * hRug - hJauge - 5 * ecart)
        .coerceAtLeast(dpPx(18f))

    var y = zone.top
    val yPort = y; y += hPort + ecart
    val yCharge = y; y += hCharge + ecart
    val yDatation = y; y += hDatation + ecart
    val yEcretage = y; y += hRug + ecart
    val yGels = y; y += hRug + ecart
    val yJauge = y

    // --- voie 1 : le port. Trois etats, et « pas de capteur » n'est pas « porte ».
    etiquette(mesureur, scratch, t, libelles.port, yPort, hPort)
    when (spec.etatPort) {
        EtatPort.SANS_CAPTEUR -> {
            drawRect(t.mutedBand, Offset(zone.left, yPort), Size(zone.width, hPort))
            texteAxe(
                mesureur, scratch, t, libelles.sansCapteur,
                zone.left + dpPx(4f), yPort + hPort / 2f - dpPx(5f),
                couleur = t.annotationText, taille = (t.axisTextSize * 0.85f).sp,
            )
        }
        else -> for (iv in spec.horsPoignet) {
            val x0 = borne(xDe(iv.debutMs))
            val x1 = borne(xDe(iv.finMs))
            if (x1 <= x0) continue
            // Hachure et non aplat : le detecteur off-body lit tres probablement « non porte » en
            // permanence a la cheville, donc cette voie est une indication a croiser avec la
            // temperature, pas un verdict. La hachure dit « signale », l'aplat aurait dit « acquis ».
            hachurer(Rect(x0, yPort, x1, yPort + hPort), t.attention, t.strokeThin, dpPx(4f))
        }
    }

    // --- voie 2 : la charge. Aplat plein : celui-la est un fait binaire lu sur l'appareil, et il
    //     est ce qui fait sortir un point de la regression de pente de la batterie.
    etiquette(mesureur, scratch, t, libelles.charge, yCharge, hCharge)
    for (iv in spec.charge) {
        val x0 = borne(xDe(iv.debutMs))
        val x1 = borne(xDe(iv.finMs))
        if (x1 <= x0) continue
        drawRect(t.secondSignal, Offset(x0, yCharge), Size(x1 - x0, hCharge))
    }

    // --- voie 3 : la datation, escalier dur sur trois niveaux nommes
    dessinerEscalierDatation(spec, t, zone, yDatation, hDatation, mesureur, scratch, ::xDe)

    // --- voies 4 et 5 : les deux rug plots. Tics de meme hauteur : la hauteur serait une echelle,
    //     et il n'y en a pas ici. Le volume se lit dans le panneau « pourquoi ce chiffre ».
    etiquette(mesureur, scratch, t, libelles.ecretage, yEcretage, hRug)
    dessinerRug(spec.ecretage, t.technicalFail, t.strokeBold, zone, yEcretage, hRug, ::xDe)

    etiquette(mesureur, scratch, t, libelles.gels, yGels, hRug)
    dessinerRug(spec.gels, t.attention, t.strokeNormal, zone, yGels, hRug, ::xDe)

    // --- voie 6 : la jauge. Volontairement plus courte que le trace, voir la KDoc.
    etiquette(mesureur, scratch, t, libelles.batterie, yJauge, hJauge)
    spec.batterie?.let { dessinerJauge(it, t, zone, yJauge, hJauge, mesureur, scratch) }

    // --- l'axe horaire, regradue sous la bande : c'est lui qui rend l'alignement verifiable.
    dessinerGraduationsHoraires(
        debutMs = spec.debutMs,
        finMs = spec.finMs,
        aire = Rect(zone.left, zone.top, zone.right, zone.bottom),
        yTexte = size.height - m.bas + dpPx(4f),
        t = t,
        mesureur = mesureur,
        scratch = scratch,
        xDe = ::xDe,
    )

    // --- le curseur, partage avec les deux bandes du dessus : c'est la seule chose qui traverse
    //     la gouttiere, et c'est justement son role — lire le meme instant dans les trois bandes.
    curseurMs?.let {
        val cx = xDe(it)
        if (cx in zone.left..zone.right) {
            drawLine(t.annotationText, Offset(cx, zone.top), Offset(cx, zone.bottom), t.strokeThin)
        }
    }
}

/** Le nom de la voie, dans la marge gauche, centre verticalement. Une echelle nominale. */
private fun DrawScope.etiquette(
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    t: ChartTokens,
    texte: String,
    haut: Float,
    hauteur: Float,
) = texteAxe(
    mesureur, scratch, t, texte, dpPx(2f), haut + hauteur / 2f - dpPx(5f),
    taille = (t.axisTextSize * 0.85f).sp,
)

/**
 * L'escalier de datation : marche horizontale, marche verticale, aucune interpolation.
 *
 * Il n'y a **pas** de `Path` continu comme dans l'hypnogramme : chaque palier est un segment
 * independant, et le changement de niveau est un trait vertical pose entre deux paliers contigus.
 * La difference se voit a l'oeil sur un trou — l'hypnogramme relie ses stades par-dessus, celui-ci
 * ne relie rien, parce qu'une minute sans point de telemetrie est une minute sur laquelle on n'a
 * aucune classe a annoncer.
 */
private fun DrawScope.dessinerEscalierDatation(
    spec: MetrologieSpec,
    t: ChartTokens,
    zone: Rect,
    haut: Float,
    hauteur: Float,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
    xDe: (Long) -> Float,
) {
    val niveaux = NiveauDatation.entries
    val hNiveau = hauteur / niveaux.size
    fun yDe(n: NiveauDatation) = haut + n.ordinal * hNiveau + hNiveau / 2f

    // Les trois noms, a gauche. C'est l'echelle : elle est nominale, elle n'est pas graduee, et
    // aucune valeur intermediaire n'existe entre deux de ses classes.
    for (n in niveaux) {
        texteAxe(
            mesureur, scratch, t, n.libelle, dpPx(2f), yDe(n) - dpPx(5f),
            taille = (t.axisTextSize * 0.85f).sp,
        )
    }

    var precedent: Pair<Float, Float>? = null
    for (p in spec.datation) {
        val x0 = xDe(p.debutMs).coerceIn(zone.left, zone.right)
        val x1 = xDe(p.finMs).coerceIn(zone.left, zone.right)
        val y = yDe(p.niveau)
        if (x1 > x0) drawLine(t.structural, Offset(x0, y), Offset(x1, y), t.strokeBold)
        // La marche verticale n'est posee que si les deux paliers se touchent : sauter un trou
        // dessinerait une transition qui n'a pas ete observee.
        precedent?.let { (xFin, yPrecedent) ->
            if (x0 - xFin < 1f && y != yPrecedent) {
                drawLine(t.structural, Offset(x0, yPrecedent), Offset(x0, y), t.strokeBold)
            }
        }
        precedent = x1 to y
    }
}

/**
 * Un *rug plot* : des tics de meme hauteur, poses sur une ligne de base.
 *
 * La hauteur ne code rien — ni le nombre d'echantillons ecretes, ni la duree du gel. Elle serait
 * une echelle, et cette bande n'en porte aucune : le volume se lit en chiffres dans le panneau
 * « pourquoi ce chiffre », ou il est ecrit et non estime a l'oeil.
 *
 * La ligne de base est tres discrete et **volontairement exemptee** du 3:1, pour la meme raison que
 * les graduations d'axe : elle situe les tics, elle n'informe pas. Ce sont les tics qui portent.
 */
private fun DrawScope.dessinerRug(
    instants: List<Long>,
    couleur: androidx.compose.ui.graphics.Color,
    epaisseur: Float,
    zone: Rect,
    haut: Float,
    hauteur: Float,
    xDe: (Long) -> Float,
) {
    val base = haut + hauteur
    drawLine(
        couleur, Offset(zone.left, base), Offset(zone.right, base), epaisseur,
        alpha = ChartTokens.GRADUATION_ALPHA,
    )
    for (ms in instants) {
        val px = xDe(ms)
        if (px < zone.left || px > zone.right) continue
        drawLine(couleur, Offset(px, base), Offset(px, haut + hauteur * 0.15f), epaisseur)
    }
}

/**
 * La jauge de completion. Piste creuse, remplissage a gauche, bord dur, chiffre a cote.
 *
 * Sa largeur est **fixe et plus courte que le trace** : c'est ce qui empeche de lire une abscisse
 * en face de son bord, donc de la prendre pour une courbe de decharge. Sur un ecran etroit elle est
 * ramenee a la moitie de l'aire, ce qui la garde visiblement plus courte.
 */
private fun DrawScope.dessinerJauge(
    jauge: JaugeBatterie,
    t: ChartTokens,
    zone: Rect,
    haut: Float,
    hauteur: Float,
    mesureur: TextMeasurer,
    scratch: ChartScratch,
) {
    val largeur = dpPx(96f).coerceAtMost(zone.width / 2f)
    val piste = Rect(zone.left, haut, zone.left + largeur, haut + hauteur)
    drawRect(
        t.structural,
        Offset(piste.left, piste.top),
        Size(piste.width, piste.height),
        style = Stroke(t.strokeNormal),
    )
    val remplie = largeur * jauge.fraction.coerceIn(0f, 1f)
    if (remplie > 0f) {
        val r = Rect(piste.left, piste.top, piste.left + remplie, piste.bottom)
        if (jauge.tenue) {
            // Aplat plein : tenu.
            drawRect(t.technicalOk, Offset(r.left, r.top), Size(r.width, r.height))
        } else {
            // Hachure : non tenu. La forme porte l'etat, la couleur ne fait que le confirmer (P6).
            hachurer(r, t.technicalFail, t.strokeNormal, dpPx(3f))
            drawRect(t.technicalFail, Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(t.strokeThin))
        }
        // Le bord dur : il ferme la jauge et dit qu'elle s'arrete la, au lieu de s'estomper comme
        // le ferait la fin d'une courbe.
        drawLine(t.structural, Offset(r.right, piste.top), Offset(r.right, piste.bottom), t.strokeBold)
    }
    texteAxe(
        mesureur, scratch, t, jauge.libelle,
        piste.right + dpPx(6f), piste.top + hauteur / 2f - dpPx(5f),
        couleur = t.annotationText, taille = (t.axisTextSize * 0.85f).sp,
    )
}
