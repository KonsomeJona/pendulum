package com.pendulum.phone.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pendulum.phone.R
import com.pendulum.phone.ui.theme.LocalPendulumColors

/**
 * Ou porter la montre, en dessin.
 *
 * ### Pourquoi un dessin et pas trois phrases de plus
 *
 * Les conditions a garder identiques d'une nuit a l'autre sont **spatiales** : devant le tibia,
 * au-dessus de l'os, pas sur l'os. Une phrase decrit un emplacement, un dessin le montre — et
 * c'est celle des quatre consignes qu'un utilisateur applique de travers sans jamais le savoir,
 * parce que rien a l'ecran ne lui dira que sa montre est dix centimetres trop bas.
 *
 * ### Pourquoi un `DrawScope` et pas une image
 *
 * C'est la regle du paquet `ui/chart` (`UX.md` §8.3), et elle vaut ici pour la meme raison : le
 * theme de cette application est sombre a l'ecran et **clair a l'impression**
 * (`RenderTarget.Print`). Une image figee porterait ses couleurs et sortirait noire sur noir dans
 * un cas ou l'autre ; un dessin prend celles du theme au moment ou il est peint.
 *
 * Le meme dessin existe en SVG dans `docs/images/where-to-wear.svg` pour la documentation. Les
 * deux se ressemblent volontairement, mais celui-ci est la reference : c'est lui que l'utilisateur
 * voit.
 *
 * ### Ce que le dessin dit, et ce qu'il ne dit pas
 *
 * Il ne porte **aucun texte**. Les libelles vivent dans `strings.xml`, donc ils se traduisent, ce
 * qu'un texte dessine ne ferait pas. Le composable porte en revanche une description pour les
 * lecteurs d'ecran : un schema sans alternative textuelle ne dit rien a qui ne le voit pas, et ce
 * serait la deuxieme fois que ce produit oublie cette question.
 */
@Composable
fun SchemaDePort(modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    val description = stringResource(R.string.wearing_diagram_description)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(RATIO)
            .semantics { contentDescription = description },
    ) {
        dessinerSchemaDePort(
            membre = c.textSecondary,
            os = c.outline,
            montre = c.textPrimary,
            refus = c.error,
            rotation = c.accent,
        )
    }
}

/** Largeur sur hauteur. La jambe occupe le haut, les trois orientations le bas. */
private const val RATIO = 600f / 540f

/**
 * Le dessin, en coordonnees d'un canevas de reference de 600 x 540, mis a l'echelle de la taille
 * reelle. Travailler en coordonnees fixes puis mettre a l'echelle evite d'avoir a raisonner en
 * fractions a chaque trait, et garde le dessin identique quelle que soit la largeur disponible.
 */
fun DrawScope.dessinerSchemaDePort(
    membre: Color,
    os: Color,
    montre: Color,
    refus: Color,
    rotation: Color,
) {
    val k = size.width / 600f
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    val traitMembre = Stroke(width = 2.2f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val traitMontre = Stroke(width = 2.4f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val traitFin = Stroke(width = 1.6f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val tirets = Stroke(
        width = 1.6f * k,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * k, 4f * k)),
    )

    // --- La jambe, vue de face -----------------------------------------------------------
    val jambe = Path().apply {
        moveTo(232f * k, 40f * k); quadraticBezierTo(224f * k, 140f * k, 228f * k, 206f * k)
        // malleole gauche
        quadraticBezierTo(220f * k, 226f * k, 224f * k, 242f * k)
        quadraticBezierTo(230f * k, 252f * k, 240f * k, 248f * k)
        // cou-de-pied et orteils
        quadraticBezierTo(236f * k, 272f * k, 234f * k, 290f * k)
        quadraticBezierTo(232f * k, 310f * k, 252f * k, 314f * k)
        lineTo(292f * k, 314f * k)
        quadraticBezierTo(312f * k, 310f * k, 310f * k, 290f * k)
        quadraticBezierTo(308f * k, 272f * k, 304f * k, 248f * k)
        // malleole droite, en remontant
        quadraticBezierTo(314f * k, 252f * k, 320f * k, 242f * k)
        quadraticBezierTo(324f * k, 226f * k, 316f * k, 206f * k)
        quadraticBezierTo(320f * k, 140f * k, 312f * k, 40f * k)
    }
    drawPath(jambe, membre, style = traitMembre)
    // la ligne des orteils
    val orteils = Path().apply {
        moveTo(236f * k, 294f * k); quadraticBezierTo(272f * k, 302f * k, 308f * k, 294f * k)
    }
    drawPath(orteils, membre, style = traitMembre)

    // Le tibia, en pointilles : c'est l'os qu'on longe, et il donne le « devant ».
    drawLine(os, p(272f, 48f), p(272f, 236f), strokeWidth = 1.5f * k, pathEffect = tirets.pathEffect)

    // --- La montre a sa place : devant, au-dessus des bosses -------------------------------
    boitier(p(245f, 146f), 54f * k, 36f * k, montre, traitMontre, k)
    drawCircle(montre, radius = 5f * k, center = p(272f, 164f), style = traitFin)
    val brins = Path().apply {
        moveTo(245f * k, 157f * k); quadraticBezierTo(230f * k, 164f * k, 245f * k, 171f * k)
        moveTo(299f * k, 157f * k); quadraticBezierTo(314f * k, 164f * k, 299f * k, 171f * k)
    }
    drawPath(brins, montre, style = traitMontre)

    // --- Ce qu'il ne faut pas faire : la montre posee sur les bosses -----------------------
    for (x in listOf(200f, 298f)) {
        boitier(p(x, 210f), 46f * k, 30f * k, refus, tirets, k)
        val croix = Path().apply {
            moveTo((x + 9f) * k, 218f * k); lineTo((x + 37f) * k, 232f * k)
            moveTo((x + 37f) * k, 218f * k); lineTo((x + 9f) * k, 232f * k)
        }
        drawPath(croix, refus, style = traitMontre)
    }

    // --- La meme montre, tournee sur elle-meme : le repere change de place, pas l'endroit ---
    val positionsDuBouton = listOf(
        Triple(120f, 183f, 460f),  // bouton a droite
        Triple(270f, 300f, 436f),  // bouton en haut
        Triple(420f, 417f, 460f),  // bouton a gauche
    )
    positionsDuBouton.forEachIndexed { i, (xBoitier, xBouton, yBouton) ->
        val alpha = if (i == 0) 1f else 0.55f
        boitier(p(xBoitier, 440f), 60f * k, 40f * k, montre.copy(alpha = alpha), traitMontre, k)
        drawCircle(montre.copy(alpha = alpha), radius = 4.5f * k, center = p(xBouton, yBouton), style = traitFin)
    }

    // La fleche de rotation, pointe comprise : `DrawScope` n'a pas de marqueur de fin.
    val arc = Path().apply {
        moveTo(196f * k, 418f * k)
        cubicTo(250f * k, 372f * k, 350f * k, 372f * k, 404f * k, 418f * k)
    }
    drawPath(arc, rotation, style = traitFin)
    val pointe = Path().apply {
        moveTo(404f * k, 418f * k); lineTo(392f * k, 404f * k)
        moveTo(404f * k, 418f * k); lineTo(390f * k, 414f * k)
    }
    drawPath(pointe, rotation, style = traitFin)
}

/** Un boitier de montre : un rectangle aux coins arrondis, en contour. */
private fun DrawScope.boitier(
    coin: Offset,
    largeur: Float,
    hauteur: Float,
    couleur: Color,
    trait: Stroke,
    k: Float,
) {
    drawRoundRect(
        color = couleur,
        topLeft = coin,
        size = androidx.compose.ui.geometry.Size(largeur, hauteur),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * k, 8f * k),
        style = trait,
    )
}
