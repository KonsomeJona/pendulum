package com.pendulum.algo.dsp

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.NightCalibration
import kotlin.math.abs

/**
 * §3.3 — normalisation inter-nuits. **Un seul mecanisme subsiste**,
 * [fromGrossBodyMovements] : `gainCal` estime le gain de la chaine cheville -> bracelet ->
 * boitier -> MEMS a partir des retournements du corps, qui sont les seuls gestes d'amplitude
 * connue qu'une nuit fournit gratuitement.
 *
 * Historiquement il portait le nom de **volet B**, parce qu'il en existait un volet A
 * (autocalibration statique du capteur) et, un temps, un rituel guide. Les deux ont ete retires,
 * et les deux sections ci-dessous disent pourquoi. Le critere applique est le meme dans les deux
 * cas, et c'est le seul qui vaille : du code ecrit, teste, et documente comme actif alors
 * qu'aucun appelant de production ne l'invoque ment sur ce que fait reellement le produit.
 *
 * ### Le rituel guide, et pourquoi il n'existe plus
 *
 * Un troisieme mecanisme a vecu ici : un rituel de 70 s — 30 s d'immobilite, dix dorsiflexions au
 * metronome, 10 s de retour au calme — dont la KDoc annoncait « c'est celui qui compte ». Il
 * mesurait le gain sur un geste impose plutot que sur un geste subi, ce qui est effectivement
 * meilleur.
 *
 * **Il a ete retire le 2026-08-05 parce qu'il n'a jamais ete branche.** La fonction etait ecrite,
 * testee, documentee, et aucun appelant de production ne l'a jamais invoquee : l'ecran qui aurait
 * guide l'utilisateur n'existait pas cote montre, et il ne pouvait pas exister simplement — ce
 * module n'est pas dans l'APK publie de la montre (`debugImplementation`), donc le calcul aurait
 * demande de capturer le rituel comme une session et de le renvoyer au telephone. Le cout etait
 * reel, la fonction dormante donnait l'impression contraire.
 *
 * Consequence a assumer plutot qu'a cacher : la comparabilite inter-nuits repose desormais
 * entierement sur [fromGrossBodyMovements] et sur la consigne « meme bracelet, meme trou, meme
 * jambe », que la v1 qualifiait de voeu et non de solution. C'est toujours vrai. Le voeu est
 * simplement redevenu visible.
 *
 * ### Le volet A, autocalibration statique, et pourquoi il n'existe plus non plus
 *
 * Il faisait ce que fait GGIR / van Hees, entierement derive de la nuit elle-meme et sans aucune
 * action de l'utilisateur : reperer les fenetres statiques (`sd < 13 mg` sur 10 s), exiger que les
 * points couvrent la sphere (`max - min >= 0,30 g` sur chacun des trois axes, sans quoi le
 * probleme est mal pose et les moindres carres rendent un resultat confiant et faux), puis cinq
 * iterations de Gauss-Newton sur `(offset o, gain diagonal S)`, avec rejet si `||o|| > 0,10 g` ou
 * `max|S - 1| > 0,05` — au-dela ce n'est plus une derive de MEMS, c'est un capteur suspect.
 *
 * **Retire le 2026-08-07, meme critere que le rituel : aucun appelant de production.**
 * `NightAnalyzer` construisait toujours `sensor = null`. Contrairement au rituel, l'excuse ne
 * pouvait pas etre le cout d'un ecran — ses deux entrees, le signal brut et les segments, sont
 * disponibles des la passe 0. Ce qui manquait n'etait pas une entree, c'etait une **sortie**, et
 * c'est ce qui a decide du retrait plutot que du branchement :
 *
 * 1. **Le resultat n'avait aucun lecteur.** `NightCalibration.sensor` est ecrit et jamais lu :
 *    `Preprocess` ne consulte que `gainCalG`, et la base ne persiste que `gainCalG` et
 *    `gainSource`. Le brancher la aurait paye un Gauss-Newton par nuit pour ne changer aucun
 *    chiffre — le defaut du rituel, plus un cout de calcul.
 * 2. **La seule vraie sortie est hors de ce module.** Corriger le signal suppose de le faire
 *    entre la construction de la ligne de temps et la separation gravite / mouvement :
 *    `Preprocess.run` recoit des blocs, alors que la correction s'applique a un `TriAxial` qui
 *    n'existe qu'apres `TimelineBuilder`. Brancher honnetement demande d'ouvrir cette couture-la,
 *    pas deux lignes dans l'orchestrateur.
 * 3. **Corriger les blocs en amont aurait desarme un controle d'integrite.**
 *    `Integrity.collectStaticNorm` utilise exactement le meme critere de 13 mg pour verifier que
 *    la norme statique vaut bien 1 g, et alimente `decodeSuspect`, lequel commande
 *    `IntegrityReport.acceptable`. Pre-corriger les echantillons rendait ce controle incapable de
 *    se declencher. La KDoc de l'autocalibration disait elle-meme qu'au-dela de ses seuils
 *    « le corriger masquerait la panne » : le pre-appliquer aveuglement faisait precisement cela.
 *
 * Reste une raison de fond, qui explique que son absence n'ait jamais ete remarquee — elle est
 * **estimee et non mesuree**, a verifier si quelqu'un rouvre le sujet : la chaine de detection est
 * passe-haut a 0,50 Hz, ce qui elimine le terme d'offset avant meme l'enveloppe, et le terme de
 * gain est borne a 5 % par la regle de rejet ci-dessus tout en se simplifiant largement dans le
 * rapport enveloppe / plancher, le plancher etant estime sur le meme signal. L'effet attendu sur
 * le PLMI etait donc du second ordre, pour un cout de premier ordre.
 *
 * Le code retire est dans l'historique git ; le rebrancher veut dire ouvrir la couture du point 2
 * et trancher le point 3, pas restaurer la fonction telle quelle.
 */
object Calibration {

    /** Tolerance inter-nuits de §3.3 : au-dela, la nuit est marquee `CALIB_OUTLIER`. */
    const val DEFAULT_OUTLIER_TOLERANCE = 0.35

    /**
     * **Le seul etalon de gain du produit** depuis le retrait du rituel guide.
     *
     * Etalon interne : la **mediane des amplitudes crete des mouvements corporels grossiers** de
     * la nuit. Les retournements sont un evenement physiologiquement stereotype, frequent
     * (20 a 60 par nuit) et d'amplitude relativement stable (Sicbaldi : 377 +/- 63 mg pendant le
     * sommeil, soit un CV de 17 % a travers les sujets). C'est un geste **subi** et non impose :
     * son amplitude varie avec la position de depart, la literie et la profondeur du sommeil, la
     * ou un geste guide aurait ete reproductible. C'est la limite connue de cet etalon, et il n'y
     * en a pas d'autre.
     *
     * `gainSource` doit accompagner **chaque** PLMI publie : il ne vaut plus que `GROSS_BODY` ou
     * `NONE`, et cette derniere valeur signifie qu'aucune comparaison inter-nuits n'est fondee.
     */
    fun fromGrossBodyMovements(
        clms: List<Clm>,
        baselineGainG: Float? = null,
        outlierTolerance: Double = DEFAULT_OUTLIER_TOLERANCE,
    ): NightCalibration {
        val peaks = ArrayList<Float>()
        val floors = ArrayList<Float>()
        for (c in clms) {
            if ((c.flags and ClmFlags.GROSS_BODY) == 0) continue
            if (!c.peakAmpG.isNaN()) peaks.add(c.peakAmpG)
            if (!c.noiseFloorG.isNaN()) floors.add(c.noiseFloorG)
        }
        if (peaks.isEmpty()) {
            return NightCalibration(null, Float.NaN, Float.NaN, Float.NaN, GainSource.NONE, false)
        }
        val gain = Numeric.median(peaks.toFloatArray())
        val floor = if (floors.isEmpty()) Float.NaN else Numeric.median(floors.toFloatArray())
        val snr = if (floor.isNaN() || floor <= 0f) Float.NaN else gain / floor
        return NightCalibration(
            // Plus aucun producteur depuis le retrait du volet A. Le champ survit dans `Model.kt`,
            // ou il n'a desormais ni ecrivain ni lecteur : a supprimer par qui touchera au modele.
            sensor = null,
            gainCalG = gain,
            floorCalG = floor,
            snrCal = snr,
            gainSource = GainSource.GROSS_BODY,
            outlierVsBaseline = isOutlier(gain, baselineGainG, outlierTolerance),
        )
    }

    /**
     * Controle qualite inter-nuits (§3.3). `true` = nuit non comparable a sa campagne :
     * « serrage du bracelet probablement different — resserrer et refaire ».
     */
    private fun isOutlier(gainCalG: Float, baselineGainG: Float?, tolerance: Double = DEFAULT_OUTLIER_TOLERANCE): Boolean {
        val b = baselineGainG ?: return false
        if (b.isNaN() || b <= 0f || gainCalG.isNaN()) return false
        return abs(gainCalG - b) / b > tolerance
    }
}
