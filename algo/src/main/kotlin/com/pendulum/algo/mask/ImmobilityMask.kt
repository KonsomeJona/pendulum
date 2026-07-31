package com.pendulum.algo.mask

import com.pendulum.algo.dsp.Gravity
import com.pendulum.algo.dsp.Numeric
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import com.pendulum.algo.model.TriAxial
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Paramètres du masque d'immobilité (`docs/ALGO-v2.md` §3.6.2, tableau §6.6).
 *
 * @param epochSec durée d'une époque. **Fixe** dans le tableau §6.6 (van Hees 2015), mais laissé
 *   constructible parce que les tests doivent pouvoir descendre la grille sans réécrire le module.
 * @param angleDeg seuil de changement d'orientation, en degrés (plage 3–8). **Deuxième paramètre
 *   le plus sensible de toute la chaîne : ±20 % déplacent l'indice de 8 à 15 %.**
 * @param moveFactor critère d'amplitude additionnel, en multiples du plancher de bruit (plage
 *   4–10). C'est l'ajout par rapport à van Hees : il rattrape les mouvements *vibratoires*, qui ne
 *   réorientent rien et sont donc rigoureusement invisibles à un critère purement angulaire.
 * @param sustainedMin durée minimale d'une bouffée d'inactivité pour valoir du sommeil (plage 3–10).
 * @param sptMinMin durée minimale d'une bouffée pour pouvoir **borner** le SPT (plage 10–30).
 * @param maxFixedPointIterations §3.6.3 couche 2. **Fixe à 2** : un point fixe itéré sans borne sur
 *   un critère non monotone peut osciller. Vérifié par un `require`, pas seulement documenté.
 * @param convergenceTstFraction écart relatif de TST au-delà duquel le masque est déclaré non
 *   convergent (plage 0,15–0,40).
 * @param neutralizeMaxAngleDeg garde-fou de la couche 1 : une époque dont le Δφ atteint l'ampleur
 *   d'un **changement de posture** (§6.4 : `postureDeg` = 20°) n'est **jamais** neutralisée, même
 *   si un CLM y a été détecté. Sans ce garde-fou, il suffirait qu'un mouvement de jambe coïncide
 *   avec un retournement pour effacer la seule preuve d'éveil réellement fiable dont on dispose.
 * @param minEpochCoverage fraction minimale d'échantillons exploitables pour qu'une époque soit
 *   scorable ; en dessous, l'époque est `UNKNOWN` — ni preuve de sommeil, ni preuve d'éveil.
 */
data class ImmobilityConfig(
    val epochSec: Double = 5.0,
    val angleDeg: Double = 5.0,
    val moveFactor: Double = 6.0,
    val sustainedMin: Double = 5.0,
    val sptMinMin: Double = 15.0,
    val maxFixedPointIterations: Int = 2,
    val convergenceTstFraction: Double = 0.25,
    val neutralizeMaxAngleDeg: Double = 20.0,
    val minEpochCoverage: Double = 0.50,
)

/**
 * Sortie du point fixe borné (§3.6.3, couche 2). Les grandeurs intermédiaires sont exposées parce
 * que la **non-convergence est un signal clinique**, pas un détail d'implémentation : c'est elle
 * qui remonte dans `QualityReport.maskNonConvergent` et qui ferme la porte de publication.
 *
 * @param provisionalTstMin TST du masque M₀, celui construit **sans** neutralisation. Sur un sujet
 *   très atteint il vaut zéro : c'est exactement le mode de défaillance décrit en §3.6.3, et le
 *   fait de le conserver permet de le mesurer au lieu de le subir.
 * @param tstDeltaFraction `|TST(M₁) − TST(M₀)| / TST(M₀)`. `NaN` si `TST(M₀)` est nul.
 */
data class FixedPointResult(
    val mask: SleepMask,
    val provisionalTstMin: Double,
    val tstDeltaFraction: Double,
    val iterations: Int,
)

/**
 * Étape 6 — masque de sommeil accélérométrique, règle d'**inactivité soutenue de type van Hees**
 * (`docs/ALGO-v2.md` §3.6.2), adaptée à la cheville et rendue invariante par orientation.
 *
 * **Ce que ce fichier n'implémente pas, et pourquoi.** Cole-Kripke est rejeté (§3.6.1), sans
 * variante ni « adaptation » : il consomme des *activity counts* ActiGraph — une transformation
 * propriétaire dont il n'existe aucune conversion publiée depuis des g — il est validé au poignet,
 * et à la cheville il **surestime le TST de +43 min**. Or un TST surestimé **déflate** l'indice
 * (+43 min sur 420 → ×0,907 : un aPLM-i vrai de 15,0 s'affiche à 13,6, sous le seuil de
 * dépistage). L'algorithme le plus précis au poignet est celui qui fait rater le diagnostic à la
 * cheville. Les algorithmes GGIR/van Hees sont à l'inverse les **seuls** de la littérature à ne
 * montrer aucune différence significative poignet/cheville — exactement la propriété requise ici,
 * puisque la position du boîtier varie d'une nuit à l'autre.
 *
 * **Deux écarts assumés par rapport à van Hees 2015.**
 *  1. Le **Δ angulaire du vecteur gravité unitaire** remplace l'angle sur un axe nommé. L'original
 *     calcule `atan(a_z / √(a_x²+a_y²))`, ce qui présuppose une orientation anatomique connue du
 *     boîtier. Nous ne la connaissons pas, et elle change d'une nuit à l'autre. `Δφ = angle(ĝ_k,
 *     ĝ_{k−1})` mesure la même chose — la réorientation du segment — sans jamais nommer d'axe : il
 *     est **invariant par rotation constante** du boîtier, donc reproductible d'une pose à l'autre.
 *  2. Le **critère d'amplitude** `amp_k < moveFactor · floor_k` s'ajoute au critère angulaire.
 *     Une secousse qui revient à sa position de départ (le cas typique d'un CLM, et de tout
 *     mouvement vibratoire) ne laisse **aucune trace angulaire** : sans ce second critère, le
 *     masque serait aveugle à une catégorie entière de mobilité.
 *
 * **Biais connu, assumé, et corrigé ailleurs** : van Hees à la cheville sous-estime le TST de
 * −89 min dans la seule étude disponible, ce qui **inflate** l'indice d'environ 27 %. On échange
 * un biais de −9 % (Cole-Kripke) contre un biais de +27 %, en gagnant l'invariance du site.
 * Aucun des deux n'est acceptable non corrigé : la correction vit dans [MaskFusion].
 *
 * Toutes les fonctions sont pures. `fs` vient toujours du signal, jamais d'une constante ; aucune
 * horloge murale n'est lue ; les accumulations se font en `Double` — même entrée, même sortie.
 */
object ImmobilityMask {

    /**
     * Construit un masque en **une passe**.
     *
     * @param gravity `ĝ` estimé à l'étape 1. Le canal gravité maintient sa dernière valeur dans les
     *   micro-trous : on ne s'attend donc pas à des `NaN` à l'intérieur d'un segment.
     * @param env enveloppe **grossière** (0,50 s) de l'étape 2. C'est celle qui porte la décision :
     *   la fine garde l'ondulation à `2f` et fabriquerait des époques mobiles au hasard.
     * @param floor plancher de bruit adaptatif de l'étape 3, sur la même grille.
     * @param segments intervalles continus. Une époque à cheval sur une frontière n'est jamais
     *   comparée à sa voisine : de part et d'autre, l'état des filtres n'a rien de commun et le Δφ
     *   ne mesurerait qu'un transitoire de réinitialisation.
     * @param offBody périodes hors-corps. Ni sommeil, ni éveil : elles ne prouvent rien.
     * @param ignoreIntervals intervalles (typiquement les CLM détectés) **neutralisés** comme
     *   preuve de mobilité. Couche 1 de la réponse à la circularité (§3.6.3) : un PLMS est par
     *   définition un mouvement **pendant** le sommeil ; s'en servir comme preuve d'éveil est une
     *   erreur de catégorie, et c'est celle qui fait exploser l'indice du sujet le plus atteint.
     * @param diary journal manuel. Il ne fabrique **pas** d'indépendance ici (voir la valeur de
     *   `independence` renvoyée) : il borne la **recherche** du SPT, comme van Hees 2015 l'exigeait,
     *   ce qui empêche une sieste ou une immobilité de canapé de préempter le début de nuit.
     * @param blindZones trous trop longs pour être interpolés. **Paramètre ajouté en fin de liste,
     *   et non inséré après [offBody], délibérément** : `blindZones` et `ignoreIntervals` ont le
     *   même type, et les insérer côte à côte ferait qu'un appel positionnel écrit sur la signature
     *   §4.4 compilerait en silence avec les deux arguments intervertis. Une erreur silencieuse sur
     *   la couche 1 est précisément ce qu'il ne faut pas rendre possible.
     *
     * `fixedPointConverged` vaut `true` : une passe unique n'a **rien** à faire converger. Le
     * drapeau n'a de sens que rempli par [fixedPoint], qui seul dispose des deux TST à comparer.
     */
    fun build(
        gravity: TriAxial,
        env: Signal1D,
        floor: Signal1D,
        segments: List<Segment>,
        offBody: List<Segment>,
        ignoreIntervals: List<Segment> = emptyList(),
        diary: DiaryWindow? = null,
        cfg: ImmobilityConfig = ImmobilityConfig(),
        blindZones: List<Segment> = emptyList(),
    ): SleepMask =
        Scorer(gravity, env, floor, segments, offBody, ignoreIntervals, diary, cfg, blindZones)
            .run()
            .toMask(converged = true)

    /**
     * Couche 2 de la réponse à la circularité — **point fixe borné à deux itérations** (§3.6.3).
     *
     * ```
     * 1. M₀ : immobilité SANS ignoreIntervals            (dégradé, mais borne le SPT)
     * 2. C₀ : intervalles de mouvement obtenus avec M₀
     * 3. M₁ : immobilité AVEC ignoreIntervals = C₀
     * 4. |TST(M₁) − TST(M₀)| < convergenceTstFraction · TST(M₀) ?  sinon MASK_NON_CONVERGENT
     * ```
     *
     * **Deux itérations, jamais plus.** Ce n'est pas une économie de calcul : la couche 1 *supprime*
     * la boucle de rétroaction au lieu de l'atténuer, donc une passe supplémentaire n'apporterait
     * rien, tandis qu'un point fixe non borné sur un critère non monotone peut osciller
     * indéfiniment entre deux scorages également défendables.
     *
     * **Conséquence à connaître et à assumer.** La couche 1 ne peut que *rendre* du sommeil
     * (elle ne transforme jamais une époque immobile en époque mobile), donc `TST(M₁) ≥ TST(M₀)`.
     * Sur un sujet très atteint, `TST(M₀)` s'effondre à zéro et l'écart relatif explose : la nuit
     * sort **non convergente**, et la porte de publication refuse l'aPLM-i. C'est voulu — sur une
     * telle nuit, le TST accélérométrique n'est tout simplement pas déterminable — et c'est sans
     * conséquence pour la métrique de suivi : le Periodicity Index et le rythme fondamental n'ont
     * pas de dénominateur temporel et survivent à `NO_PLMI`. La bonne réponse à ce cas n'est pas
     * d'assouplir le critère, c'est de fournir un dénominateur indépendant (couche 3).
     *
     * @param movementIntervalsOf détection injectée : `M → intervalles de mouvement à neutraliser`.
     *   Passée en lambda pour que `mask` ne dépende pas de `detect` — la dépendance naturelle va
     *   dans l'autre sens (les séries consomment le masque), et la refermer ici créerait un cycle.
     *   L'appelant y branche `clms.filter { it.isClm }.map { Segment(it.onsetIdx, it.offsetIdx) }`.
     */
    fun fixedPoint(
        gravity: TriAxial,
        env: Signal1D,
        floor: Signal1D,
        segments: List<Segment>,
        offBody: List<Segment>,
        diary: DiaryWindow? = null,
        cfg: ImmobilityConfig = ImmobilityConfig(),
        blindZones: List<Segment> = emptyList(),
        movementIntervalsOf: (SleepMask) -> List<Segment>,
    ): FixedPointResult {
        require(cfg.maxFixedPointIterations in 1..2) {
            "maxFixedPointIterations est fixe a 1 ou 2 (§6.6) : au-dela, le point fixe peut osciller"
        }
        val m0 = build(gravity, env, floor, segments, offBody, emptyList(), diary, cfg, blindZones)
        if (cfg.maxFixedPointIterations == 1) {
            return FixedPointResult(m0, m0.tstMin, 0.0, 1)
        }
        val ignore = movementIntervalsOf(m0)
        val m1 = build(gravity, env, floor, segments, offBody, ignore, diary, cfg, blindZones)

        val delta = abs(m1.tstMin - m0.tstMin)
        val fraction = if (m0.tstMin > 0.0) delta / m0.tstMin else Double.NaN
        // `TST(M₀) == 0` n'est pas une convergence parfaite, c'est l'effondrement décrit ci-dessus :
        // on ne peut rien conclure d'un rapport dont le dénominateur est nul, donc non convergent.
        val converged = fraction.isFinite() && fraction < cfg.convergenceTstFraction
        return FixedPointResult(
            mask = m1.copy(fixedPointConverged = converged),
            provisionalTstMin = m0.tstMin,
            tstDeltaFraction = fraction,
            iterations = 2,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Scorage par époque
// ---------------------------------------------------------------------------------------------

private const val ST_UNKNOWN: Byte = 0
private const val ST_IMMOBILE: Byte = 1
private const val ST_MOBILE: Byte = 2

/** Résultat intermédiaire du scorage : tout ce qu'il faut pour fabriquer le [SleepMask]. */
private class Scored(
    val fsHz: Double,
    val epochLen: Int,
    val epochSec: Double,
    /** `ST_*` après neutralisation (couche 1). */
    val state: ByteArray,
    /** Époque immobile appartenant à une bouffée d'au moins `sustainedMin`. */
    val sleepEpoch: BooleanArray,
    val sptFromEpoch: Int,
    val sptToEpoch: Int,
    val analysable: IntervalSet,
) {
    private fun msOf(idx: Int): Long = Math.round(idx * 1000.0 / fsHz)

    private fun stageOf(k: Int): Stage = when {
        sleepEpoch[k] -> Stage.SLEEP
        state[k] == ST_UNKNOWN -> Stage.UNKNOWN
        // Immobile mais dans une bouffée trop courte : c'est de l'éveil calme au lit, pas du
        // sommeil. Le regrouper avec le mouvement est la lettre de van Hees (le sommeil est
        // l'inactivité *soutenue*, jamais l'inactivité instantanée).
        else -> Stage.AWAKE_IN_BED
    }

    fun toMask(converged: Boolean): SleepMask {
        if (sptToEpoch <= sptFromEpoch) {
            return SleepMask(
                windows = emptyList(),
                source = MaskSource.ACCEL_IMMOBILITY,
                sptMin = 0.0, tstMin = 0.0, wasoMin = 0.0,
                analysableTstMin = 0.0, analysableSptMin = 0.0,
                corrected = false, lagAppliedMs = 0L,
                independence = DenominatorIndependence.CIRCULAR,
                fixedPointConverged = converged,
            )
        }

        val windows = ArrayList<SleepWindow>()
        var runStart = sptFromEpoch
        var runStage = stageOf(sptFromEpoch)
        for (k in sptFromEpoch + 1..sptToEpoch) {
            val s = if (k < sptToEpoch) stageOf(k) else null
            if (s != runStage) {
                windows.add(
                    SleepWindow(msOf(runStart * epochLen), msOf(k * epochLen), runStage),
                )
                if (s == null) break
                runStart = k
                runStage = s
            }
        }

        // Le SPT est aligné sur la grille d'époques, donc `waso = spt − tst` est exact : aucune
        // milliseconde ne se perd entre les deux comptages.
        var sleepEpochs = 0
        for (k in sptFromEpoch until sptToEpoch) if (sleepEpoch[k]) sleepEpochs++
        val sptEpochs = sptToEpoch - sptFromEpoch
        val sptMin = sptEpochs * epochSec / 60.0
        val tstMin = sleepEpochs * epochSec / 60.0

        // Dénominateur exposé = temps de sommeil **analysable** (§2.4 et `SPEC-v2.md` §2.3) :
        // TST ∩ segments valides ∩ hors zones aveugles ∩ hors off-body. Compter des mouvements sur
        // une durée pendant laquelle on n'aurait pas pu en voir gonfle le dénominateur et déflate
        // l'indice — dans le sens exact qui fait rater un dépistage. Jamais le TST brut.
        var analysableTstSamples = 0L
        for (w in windows) {
            if (w.stage != Stage.SLEEP) continue
            val a = Math.round(w.startMsRel * fsHz / 1000.0).toInt()
            val b = Math.round(w.endMsRel * fsHz / 1000.0).toInt()
            analysableTstSamples += analysable.intersectLength(a, b).toLong()
        }
        val analysableSptSamples =
            analysable.intersectLength(sptFromEpoch * epochLen, sptToEpoch * epochLen).toLong()
        val toMin = 1.0 / (fsHz * 60.0)

        return SleepMask(
            windows = windows,
            source = MaskSource.ACCEL_IMMOBILITY,
            sptMin = sptMin,
            tstMin = tstMin,
            wasoMin = sptMin - tstMin,
            analysableTstMin = analysableTstSamples * toMin,
            analysableSptMin = analysableSptSamples * toMin,
            corrected = false,
            lagAppliedMs = 0L,
            // **Règle non négociable** (`SPEC-v2.md` §2.3) : un masque dérivé de l'accéléromètre est
            // circulaire — numérateur et dénominateur sortent du même signal et sont anti-corrélés
            // par construction — et ne peut donc JAMAIS porter le résultat principal. Le journal
            // manuel passé à `build` ne change rien à cela : il borne la recherche du SPT, il ne
            // fournit pas le dénominateur. Un dénominateur indépendant se produit dans [MaskFusion].
            independence = DenominatorIndependence.CIRCULAR,
            fixedPointConverged = converged,
        )
    }
}

private class Scorer(
    private val gravity: TriAxial,
    private val env: Signal1D,
    private val floor: Signal1D,
    segments: List<Segment>,
    offBody: List<Segment>,
    ignoreIntervals: List<Segment>,
    private val diary: DiaryWindow?,
    private val cfg: ImmobilityConfig,
    blindZones: List<Segment>,
) {
    private val fs = env.fsHz
    private val n = env.n

    /** Scorable = dans un segment continu et hors du corps exclu. */
    private val scorable = IntervalSet.of(segments).minus(IntervalSet.of(offBody))

    /** Analysable = scorable, moins les zones aveugles. Sert au seul dénominateur. */
    private val analysable = scorable.minus(IntervalSet.of(blindZones))
    private val ignore = IntervalSet.of(ignoreIntervals)

    private val epochLen = Numeric.samples(cfg.epochSec, fs)
    private val nEpochs = n / epochLen

    private val state = ByteArray(nEpochs)
    private val dphiDeg = FloatArray(nEpochs)
    private val ux = DoubleArray(nEpochs)
    private val uy = DoubleArray(nEpochs)
    private val uz = DoubleArray(nEpochs)
    private val hasDir = BooleanArray(nEpochs)
    private val scratch = FloatArray(epochLen)

    init {
        require(fs > 0.0) { "fsHz doit etre > 0" }
        require(gravity.n == n && floor.n == n) { "gravite, enveloppe et plancher doivent partager la grille" }
        require(cfg.epochSec > 0.0 && cfg.sustainedMin > 0.0) { "durees strictement positives attendues" }
    }

    fun run(): Scored {
        scoreRaw()
        neutralize()
        val sleepEpoch = sustainedBouts()
        val (from, to) = sptBounds()
        return Scored(fs, epochLen, cfg.epochSec, state, sleepEpoch, from, to, analysable)
    }

    // --- 1. état brut ------------------------------------------------------------------------

    private fun scoreRaw() {
        val minValid = (cfg.minEpochCoverage * epochLen).toInt().coerceAtLeast(1)
        val bedIdx = diary?.let { Math.round(it.bedTimeMsRel * fs / 1000.0).toInt() } ?: Int.MIN_VALUE
        val riseIdx = diary?.let { Math.round(it.riseTimeMsRel * fs / 1000.0).toInt() } ?: Int.MAX_VALUE

        for (k in 0 until nEpochs) {
            val a = k * epochLen
            val b = a + epochLen

            // Une époque n'est scorable que si l'enregistrement la couvre vraiment. En dessous,
            // elle n'est ni une preuve de sommeil ni une preuve d'éveil : UNKNOWN, et rien d'autre.
            if (scorable.intersectLength(a, b) < minValid) continue

            // Le journal borne la **recherche** : hors de la fenêtre déclarée, aucune bouffée ne
            // peut ouvrir ou fermer le SPT. Sans cela, une immobilité de canapé avant le coucher
            // préempte le début de nuit et allonge le SPT de plusieurs dizaines de minutes.
            if (a < bedIdx || b > riseIdx) continue

            meanUnitGravity(a, b)
            if (!hasDir[k]) continue

            // Δφ contre l'époque précédente, et seulement si les deux sont **contiguës dans le
            // même segment** : à travers une frontière, les filtres ont été réinitialisés et
            // l'angle mesurerait un transitoire d'amorçage, pas un mouvement du sujet.
            val contiguous = k > 0 && hasDir[k - 1] && scorable.containsRange(a - 1, a + 1)
            val d = if (contiguous) {
                Gravity.angleDeg(
                    ux[k - 1].toFloat(), uy[k - 1].toFloat(), uz[k - 1].toFloat(),
                    ux[k].toFloat(), uy[k].toFloat(), uz[k].toFloat(),
                )
            } else {
                0f // absence de comparaison possible = absence de preuve, jamais preuve d'éveil
            }
            dphiDeg[k] = if (d.isNaN()) 0f else d

            val amp = Numeric.percentile(env.v, a, b, 95.0, scratch)
            val fl = Numeric.median(floor.v, a, b, scratch)
            val amplitudeMobile = !amp.isNaN() && !fl.isNaN() && amp >= cfg.moveFactor * fl
            val angularMobile = dphiDeg[k] > cfg.angleDeg

            state[k] = if (angularMobile || amplitudeMobile) ST_MOBILE else ST_IMMOBILE
        }
    }

    /**
     * `ĝ_k` = moyenne des vecteurs gravité **unitaires** de l'époque, renormalisée.
     *
     * Normaliser chaque échantillon *avant* de moyenner, et non l'inverse : la moyenne des vecteurs
     * bruts est pondérée par la norme, si bien qu'une seconde où `‖ĝ‖` dérive à 1,1 g pèse 10 % de
     * plus dans la direction moyenne. On mesure une orientation ; le module n'a rien à y faire.
     */
    private fun meanUnitGravity(a: Int, b: Int) {
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var cnt = 0
        for (i in a until b) {
            val x = gravity.x[i].toDouble()
            val y = gravity.y[i].toDouble()
            val z = gravity.z[i].toDouble()
            val norm = sqrt(x * x + y * y + z * z)
            if (norm.isNaN() || norm <= 1e-9) continue
            sx += x / norm; sy += y / norm; sz += z / norm; cnt++
        }
        val k = a / epochLen
        if (cnt == 0) return
        val norm = sqrt(sx * sx + sy * sy + sz * sz)
        if (norm <= 1e-9) return // époque dont les directions s'annulent : aucune direction moyenne
        ux[k] = sx / norm; uy[k] = sy / norm; uz[k] = sz / norm
        hasDir[k] = true
    }

    // --- 2. couche 1 : neutralisation des mouvements périodiques ------------------------------

    /**
     * Couche 1 de §3.6.3 — **rendre le masque aveugle aux mouvements périodiques**.
     *
     * L'énoncé du problème, chiffré : la règle exige ≥ 5 min consécutives sans mouvement, or une
     * série à IMI 22 s place ~13 mouvements dans *n'importe quelle* fenêtre de 5 min. La
     * probabilité qu'une fenêtre de 5 min soit libre de tout mouvement pendant une série est
     * **nulle**. Appliqué naïvement, le masque score donc toute la période de crise comme de
     * l'éveil : le TST s'effondre, l'indice explose, et simultanément la règle AASM « au moins une
     * partie du mouvement dans une époque de sommeil » supprime les mouvements eux-mêmes. Le sujet
     * le plus atteint est celui pour lequel l'algorithme se comporte le plus mal — mode de
     * défaillance disqualifiant.
     *
     * Position clinique tenable, et la seule : un PLMS est *par définition* un mouvement **pendant**
     * le sommeil. L'AASM score les PLMS *dans* des époques de sommeil ; un mouvement de jambe ne
     * rend pas l'époque éveillée sauf critère d'éveil cortical, que nous ne pouvons pas évaluer
     * sans EEG. Restent comme preuves d'éveil : les mouvements corporels **grossiers**, les
     * **changements de posture**, et la mobilité soutenue non attribuable à un mouvement périodique
     * — c'est-à-dire tout ce que l'appelant n'a pas placé dans `ignoreIntervals`.
     *
     * Deux invariants tiennent l'implémentation :
     *  - le rescorage lit **toujours** les états bruts, jamais des états déjà rescorés : sans cela
     *    l'ordre de parcours changerait le résultat, et le déterminisme au bit tomberait ;
     *  - il ne peut que transformer MOBILE → IMMOBILE. Il ne fabrique jamais d'éveil, ce qui rend
     *    `TST(M₁) ≥ TST(M₀)` et donne son sens au contrôle de convergence de la couche 2.
     */
    private fun neutralize() {
        if (ignore.isEmpty()) return
        val raw = state.copyOf()
        val neutral = BooleanArray(nEpochs)
        for (k in 0 until nEpochs) {
            val a = k * epochLen
            neutral[k] = ignore.intersectLength(a, a + epochLen) > 0
        }
        for (k in 0 until nEpochs) {
            if (raw[k] != ST_MOBILE || !neutral[k]) continue
            // Garde-fou posture : une réorientation persistante de l'ampleur d'un retournement
            // n'est pas neutralisable. C'est la seule preuve d'éveil vraiment fiable du dispositif,
            // et un CLM coïncidant ne doit pas suffire à l'effacer.
            if (dphiDeg[k] > cfg.neutralizeMaxAngleDeg) continue
            if (nearestNonNeutralState(raw, neutral, k) == ST_IMMOBILE) state[k] = ST_IMMOBILE
        }
    }

    /**
     * Interpolation au plus proche voisin non neutralisé. Égalité de distance → **la gauche
     * gagne**, arbitrairement mais de façon fixée : le déterminisme importe plus que le choix.
     * Aucun voisin exploitable (série couvrant toute la nuit) → `ST_IMMOBILE` : dans ce cas la
     * seule mobilité observée est celle qu'on a justement décidé de ne pas compter.
     */
    private fun nearestNonNeutralState(raw: ByteArray, neutral: BooleanArray, k: Int): Byte {
        var d = 1
        while (d < nEpochs) {
            val l = k - d
            if (l >= 0 && !neutral[l] && raw[l] != ST_UNKNOWN) return raw[l]
            val r = k + d
            if (r < nEpochs && !neutral[r] && raw[r] != ST_UNKNOWN) return raw[r]
            if (l < 0 && r >= nEpochs) break
            d++
        }
        return ST_IMMOBILE
    }

    // --- 3. bouffées d'inactivité soutenue et bornes du SPT ------------------------------------

    private fun sustainedBouts(): BooleanArray {
        val sleep = BooleanArray(nEpochs)
        val minEpochs = boutEpochs(cfg.sustainedMin)
        forEachBout { from, to -> if (to - from >= minEpochs) for (k in from until to) sleep[k] = true }
        return sleep
    }

    /**
     * `SPT = du début de la première bouffée ≥ sptMinMin jusqu'à la fin de la dernière`.
     *
     * Le SPT ne dépend ainsi que des **deux transitions extrêmes** de la nuit, qui sont loin du
     * cœur dense en mouvements : il est quasi insensible à la circularité, là où le TST y est
     * directement exposé via le WASO. C'est ce qui fait de `plmiSpt` un repli défendable en
     * l'absence de dénominateur indépendant (§3.6.5-b).
     */
    private fun sptBounds(): Pair<Int, Int> {
        val minEpochs = boutEpochs(cfg.sptMinMin)
        var first = -1
        var last = -1
        forEachBout { from, to ->
            if (to - from >= minEpochs) {
                if (first < 0) first = from
                last = to
            }
        }
        return if (first < 0) 0 to 0 else first to last
    }

    private fun boutEpochs(minutes: Double): Int =
        Math.ceil(minutes * 60.0 / cfg.epochSec).toInt().coerceAtLeast(1)

    /**
     * Parcourt les plages maximales d'époques `ST_IMMOBILE` consécutives. Une époque `UNKNOWN`
     * **rompt** la plage : on ne peut pas certifier la continuité d'une immobilité à travers une
     * période où l'on ne mesurait rien, et le contraire ferait passer une montre posée sur la table
     * de nuit pour du sommeil.
     */
    private inline fun forEachBout(action: (Int, Int) -> Unit) {
        var k = 0
        while (k < nEpochs) {
            if (state[k] != ST_IMMOBILE) { k++; continue }
            var e = k
            while (e < nEpochs && state[e] == ST_IMMOBILE) e++
            action(k, e)
            k = e
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Ensemble d'intervalles
// ---------------------------------------------------------------------------------------------

/**
 * Union normalisée d'intervalles d'index d'échantillons `[from, to)` — triée, disjointe, fusionnée.
 * Sert exclusivement à l'arithmétique du dénominateur analysable, où la moindre double comptabilité
 * se traduit directement en erreur sur l'indice.
 */
internal class IntervalSet private constructor(private val from: IntArray, private val to: IntArray) {

    fun isEmpty(): Boolean = from.isEmpty()

    /** Nombre d'échantillons de `[a, b)` couverts par l'ensemble. */
    fun intersectLength(a: Int, b: Int): Int {
        if (b <= a) return 0
        var acc = 0
        var i = lowerBound(a)
        while (i < from.size && from[i] < b) {
            val lo = if (from[i] > a) from[i] else a
            val hi = if (to[i] < b) to[i] else b
            if (hi > lo) acc += hi - lo
            i++
        }
        return acc
    }

    fun containsRange(a: Int, b: Int): Boolean = b <= a || intersectLength(a, b) == b - a

    fun minus(other: IntervalSet): IntervalSet {
        if (other.isEmpty() || isEmpty()) return this
        val outFrom = ArrayList<Int>(from.size)
        val outTo = ArrayList<Int>(from.size)
        for (i in from.indices) {
            var cur = from[i]
            var j = other.lowerBound(cur)
            while (j < other.from.size && other.from[j] < to[i]) {
                if (other.from[j] > cur) { outFrom.add(cur); outTo.add(other.from[j]) }
                if (other.to[j] > cur) cur = other.to[j]
                if (cur >= to[i]) break
                j++
            }
            if (cur < to[i]) { outFrom.add(cur); outTo.add(to[i]) }
        }
        return IntervalSet(outFrom.toIntArray(), outTo.toIntArray())
    }

    /** Premier intervalle dont la borne haute dépasse `x`. Recherche dichotomique. */
    private fun lowerBound(x: Int): Int {
        var lo = 0
        var hi = from.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (to[mid] <= x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        fun of(segments: List<Segment>): IntervalSet {
            if (segments.isEmpty()) return IntervalSet(IntArray(0), IntArray(0))
            val sorted = segments.filter { it.toIdx > it.fromIdx }
                .sortedWith(compareBy<Segment>({ it.fromIdx }, { it.toIdx }))
            if (sorted.isEmpty()) return IntervalSet(IntArray(0), IntArray(0))
            val f = ArrayList<Int>(sorted.size)
            val t = ArrayList<Int>(sorted.size)
            var curFrom = sorted[0].fromIdx
            var curTo = sorted[0].toIdx
            for (i in 1 until sorted.size) {
                val s = sorted[i]
                if (s.fromIdx <= curTo) {
                    if (s.toIdx > curTo) curTo = s.toIdx
                } else {
                    f.add(curFrom); t.add(curTo)
                    curFrom = s.fromIdx; curTo = s.toIdx
                }
            }
            f.add(curFrom); t.add(curTo)
            return IntervalSet(f.toIntArray(), t.toIntArray())
        }
    }
}
