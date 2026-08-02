package com.pendulum.phone.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalChartTokens
import com.pendulum.phone.ui.theme.PendulumType

/**
 * Les enveloppes composables des trois graphes.
 *
 * Elles sont **minces par construction** : elles resolvent les tokens, gerent les gestes et
 * l'accessibilite, puis delèguent a la fonction de dessin. Aucune logique de calcul, aucune
 * decision d'echelle. C'est ce qui permet a l'export PDF d'appeler les memes fonctions sans
 * embarquer Compose UI.
 *
 * Chaque graphe expose un bouton **Valeurs** qui ouvre le meme contenu sous forme de tableau.
 * C'est simultanement l'alternative accessible — le seul chemin reellement utilisable sans
 * vision, un `contentDescription` sur un canvas ne rendant pas des donnees — et le mode « je
 * veux le chiffre exact », qui est une demande legitime et frequente ici.
 */

@Composable
fun GrapheNuit(
    spec: NuitChartSpec,
    transform: XTransform,
    curseurMs: Long?,
    onCurseur: (Long?) -> Unit,
    onEvenement: (Long) -> Unit,
    onValeurs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalChartTokens.current
    val mesureur = rememberTextMeasurer()
    val scratch = remember { ChartScratch() }

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = spec.descriptionAccessible }
                .pointerInput(Unit) {
                    // Zoom horizontal seul : l'axe Y ne zoome jamais. Un Y zoomable permettrait
                    // de recadrer sur un detail et de perdre l'echelle du seuil, qui est
                    // justement ce que ce graphe sert a verifier.
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        transform.applyPinch(centroid.x, pan.x, zoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { transform.reset() },
                        onTap = { onEvenement(transform.timeAt(it.x)) },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onCurseur(transform.timeAt(it.x)) },
                        onDrag = { c, _ -> onCurseur(transform.timeAt(c.position.x)) },
                        onDragEnd = { onCurseur(null) },
                    )
                },
        ) {
            dessinerGrapheNuit(spec, tokens, transform, mesureur, scratch, curseurMs)
        }
        TextButton(onClick = onValeurs, modifier = Modifier.padding(start = 4.dp)) {
            Text(Textes.Graphes.VALEURS, style = PendulumType.label)
        }
    }
}

@Composable
fun Hypnogramme(
    spec: HypnogrammeSpec,
    transform: XTransform,
    curseurMs: Long?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalChartTokens.current
    val mesureur = rememberTextMeasurer()
    val scratch = remember { ChartScratch() }

    // Aucun `pointerInput` ici : un seul proprietaire du geste, c'est le graphe de nuit.
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = spec.statistiques },
    ) {
        dessinerHypnogramme(spec, tokens, transform, mesureur, scratch, curseurMs)
    }
}

@Composable
fun GrapheTendance(
    spec: TendanceChartSpec,
    onNuit: (String) -> Unit,
    onValeurs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalChartTokens.current
    val mesureur = rememberTextMeasurer()
    val scratch = remember { ChartScratch() }
    val densite = LocalDensity.current

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semantics { contentDescription = spec.descriptionAccessible }
                .pointerInput(spec) {
                    // Pas de zoom : la periode se choisit au selecteur, pas au pincement. Un
                    // pincement libre permettrait de cadrer sur les seules nuits qui arrangent.
                    detectTapGestures { p ->
                        trouverPointProche(
                            spec = spec,
                            x = p.x,
                            y = p.y,
                            largeur = size.width.toFloat(),
                            hauteur = size.height.toFloat(),
                            // La densite, parce que les marges du trace sont en dp : sans elle,
                            // la recherche viserait la largeur totale du canevas et non la zone
                            // ou les points sont reellement dessines.
                            densite = densite.density,
                            rayonAcceptation = with(densite) { 24.dp.toPx() },
                        )?.let(onNuit)
                    }
                },
        ) {
            dessinerGrapheTendance(spec, tokens, mesureur, scratch)
        }
        spec.reference?.let {
            // La provenance du seuil est en legende, hors de l'aire de trace : dans le graphe,
            // elle deviendrait une annotation de plus a decoder.
            Text(it.legende, style = PendulumType.caption, modifier = Modifier.padding(start = 4.dp))
        }
        TextButton(onClick = onValeurs, modifier = Modifier.padding(start = 4.dp)) {
            Text(Textes.Graphes.VALEURS, style = PendulumType.label)
        }
    }
}
