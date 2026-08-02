package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SleepMask
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rythme fondamental des mouvements périodiques, estimé par **déconvolution des harmoniques** de
 * l'intervalle inter-mouvements (`SPEC-v2.md` §5.3). C'est la métrique de suivi du produit.
 *
 * # Pourquoi la moyenne brute du log est inutilisable
 *
 * L'IMI suit une loi log-normale (Skeba 2016), et la moyenne de son log a une variabilité nuit à
 * nuit de **3,6 %** contre **43,2 %** pour le compte horaire — douze fois moins. C'est ce qui
 * justifie de changer de métrique principale. Mais un taux de mouvements manqués détruit cette
 * grandeur :
 *
 * si chaque mouvement est manqué indépendamment avec une probabilité `p`, un intervalle observé
 * recouvre `N` intervalles vrais, `N` géométrique, et le biais sur la moyenne du log vaut
 * `E[ln N] = Σ_k p^(k−1)(1−p)·ln k` :
 *
 * | `p`  | biais (nats) | facteur sur l'intervalle |
 * |------|--------------|--------------------------|
 * | 0,20 | +0,158       | ×1,17 |
 * | 0,30 | +0,255       | ×1,29 |
 * | 0,39 | **+0,357**   | **×1,43** |
 * | 0,50 | +0,508       | ×1,66 |
 * | 0,70 | +0,915       | ×2,50 |
 *
 * (La dernière ligne corrige le tableau du §5.3, qui donne +0,901 / ×2,46 : la série
 * `Σ p^(k−1)(1−p)·ln k` converge lentement à `p = 0,70` et 0,901 correspond à une somme arrêtée
 * vers `k = 15`. Les quatre autres lignes sont exactes à 3 décimales. Voir [rawLogBias].)
 *
 * À 39 % de manqués — le chiffre mesuré par Terrill 2013 : 39 % des mouvements visibles à l'EMG ne
 * déplacent **aucun** capteur de cheville, une dorsiflexion pure ne déplaçant pas un boîtier situé
 * au-dessus de l'axe articulaire — le biais vaut **+0,357 nats**, soit **3,3 fois** la variabilité
 * nuit à nuit qu'on cherche justement à exploiter (≈ 0,11 nats pour un IMI de 21 s).
 *
 * **La déconvolution n'est donc pas un raffinement : c'est la condition d'existence de la métrique.**
 *
 * # Le modèle
 *
 * ```
 * log I_obs  ~  Σ_{k=1..K} w_k · N(μ + ln k, σ²)        w_k ∝ p^(k−1)(1−p)
 * ```
 *
 * estimé par espérance-maximisation sur `(μ, σ, p)`. Les centroïdes sont séparés de `ln 2 ≈ 0,69`
 * nats pour un `σ` typique de 0,2 à 0,4 : la séparation vaut 1,7 à 3,5 σ, la déconvolution est bien
 * posée même à `p = 0,39` où le pic fondamental pèse encore 61 % et le premier harmonique 24 %.
 *
 * Trois sorties, **d'importance égale** :
 *  - `exp(μ)` = période fondamentale, débarrassée du taux de manqués ;
 *  - `p` = taux de manqués **mesuré**. Métrique de qualité gratuite, et critère de comparabilité :
 *    un `p` qui saute d'une nuit à l'autre signale deux nuits non comparables ;
 *  - `alternationSuspect` : un `p` proche de 0,5 avec un pic fondamental faible évoque des
 *    mouvements qui alternent entre les jambes, qu'un capteur unilatéral ne voit qu'une fois sur
 *    deux — un résultat clinique en soi.
 *
 * # Trois approximations, énoncées plutôt que cachées
 *
 * 1. **Composantes de même `σ`.** Un intervalle observé de rang `k` est la *somme* de `k`
 *    intervalles log-normaux, dont le log a une dispersion plus faible (≈ `σ/√k`) et une moyenne
 *    légèrement supérieure à `μ + ln k`. Le modèle littéral du §5.3 ignore ces deux corrections ;
 *    à `σ ≤ 0,3` elles valent moins de 1,5 % sur la période et le gain de complexité ne le vaut pas.
 *    Elles sont en revanche la première chose à revoir si `σ` estimé dépasse 0,4.
 * 2. **Troncature à `K` harmoniques.** La queue `k > K` s'agglomère sur la dernière composante et
 *    tire `p` **vers le haut**. À `K = 5` et `p = 0,39`, la queue pèse 0,9 % : biais négligeable.
 *    À `p = 0,65` elle pèse 7,5 % et l'estimation de `p` n'est plus fiable — ce que la mesure
 *    d'adéquation ci-dessous détecte.
 * 3. **Indépendance des manqués — probablement fausse, et c'est codé comme tel.** L'accéléromètre
 *    rate d'abord les mouvements de faible amplitude ; si une salve décroît en amplitude, les
 *    manqués s'agglomèrent en fin de série et le pic 2× est **sous-peuplé** par rapport au modèle
 *    géométrique. Le module mesure donc l'adéquation du modèle aux données ([RhythmFit.geometricMisfit]
 *    et [RhythmFit.ksStatistic]) et **invalide** le résultat quand elle est mauvaise, plutôt que de
 *    rendre un chiffre faux avec l'air sûr.
 *
 *    Ce que la mesure d'adéquation attrape et ce qu'elle n'attrape pas, vérifié par simulation :
 *    - **attrapé** — une distribution dont le pic 2× manque (harmoniques peuplés dans le désordre) :
 *      distance en variation totale de 0,12 à 0,25 contre 0,04 au pire pour un mélange conforme ;
 *    - **non attrapé, et ce n'est pas un défaut** — une probabilité de manqué qui *croît le long de
 *      la salve* (0 au début, 0,85 à la fin). Le mélange de géométriques qui en résulte reste de
 *      forme quasi géométrique (écart 0,012) et l'estimation de la période reste juste à 0,5 % ;
 *      `p` s'y lit alors comme un **taux moyen sur la nuit**, ce qu'il est. La dépendance ne fait
 *      donc pas dérailler l'estimation tant qu'elle ne creuse pas un harmonique particulier.
 *
 *    **Réserve mesurée, et elle est sérieuse : ces deux mesures d'adéquation ne sont pas des mesures
 *    de confiance.** `docs/07-validation.md` §4.3 les mesure à taux de manqués imposé sur le train
 *    vrai : quand `p` monte de 0,00 à 0,70, l'erreur sur le fondamental est multipliée par trois
 *    (0,067 → 0,201) alors que le KS **descend** de 0,116 à 0,071 et que le `geometricMisfit` reste
 *    plat. Il est accepté davantage d'ajustements à `p = 0,70` (4/20) qu'à `p = 0,00` (0/20), où
 *    l'estimation est trois fois meilleure. Le mécanisme se comprend après coup : éclaircir un train
 *    étale la distribution des intervalles, et un mélange log-normal à `σ` libre épouse **mieux** un
 *    histogramme large et lisse, quoi qu'il advienne de la position du mode. Ces deux statistiques
 *    mesurent l'**adéquation globale** du mélange ; ce qu'il faudrait borner est l'**identifiabilité
 *    de `μ`**, qui est une autre grandeur. Ce sont donc `TOO_FEW_INTERVALS` et
 *    `MISS_RATE_SATURATED` — des gardes de capacité, pas d'adéquation — qui font tout le refus utile
 *    aujourd'hui. §4.3 propose ce qu'il faudrait à la place ; la décision n'est pas prise ici.
 *
 * # Une limite d'identifiabilité, à connaître avant de lire `alternationSuspect`
 *
 * Une alternance **strictement déterministe** gauche/droite (un mouvement sur deux exactement) est
 * mathématiquement **indiscernable** d'un rythme deux fois plus lent sans aucun manqué : les deux
 * produisent exactement la même suite d'intervalles. Aucune méthode fondée sur les seuls intervalles
 * ne peut les séparer. Ce que le modèle détecte, c'est la latéralisation **stochastique** (chaque
 * mouvement visible avec une probabilité ≈ 1/2), qui, elle, laisse une signature nette : `p ≈ 0,5`
 * avec des harmoniques peuplés. Deux nuits avec le capteur sur la jambe opposée restent le seul
 * moyen de trancher le cas déterministe (`SPEC-v2.md` §6 question 6).
 */

/** Paramètres de la déconvolution. Aucun n'introduit d'aléa ; l'initialisation est déterministe. */
data class RhythmConfig(
    /**
     * Nombre d'harmoniques du mélange (`k = 1..maxHarmonics`). Borné : au-delà, les composantes
     * lointaines ne captent plus que du bruit et gonflent `p`.
     */
    val maxHarmonics: Int = 5,
    /** Sous ce nombre d'intervalles, aucun résultat n'est produit. */
    val minIntervals: Int = 30,
    /** Borne basse de sélection : écarte les fragments intra-salve (bimodalité 2–4 s). */
    val minIntervalSec: Double = 5.0,
    /**
     * Borne haute de sélection. À régler avec [maxHarmonics] : elle doit couvrir
     * `maxHarmonics × fondamental attendu` (≈ 22–26 s), sans quoi les harmoniques hauts sont
     * amputés et `p` sous-estimé.
     */
    val maxIntervalSec: Double = 150.0,
    val maxIterations: Int = 300,
    /** Critère d'arrêt : plus grande variation d'un paramètre entre deux itérations. */
    val tolerance: Double = 1e-10,
    val sigmaFloor: Double = 1e-4,
    val sigmaCeiling: Double = 1.5,
    /** Au-delà, le signal ne contient plus assez de fondamental pour parler de rythme. */
    val maxMissRate: Double = 0.90,
    /**
     * Seuil d'adéquation distributionnelle (Kolmogorov–Smirnov). **Délibérément absolu et non
     * indexé sur `n`** : avec ~2 000 intervalles, un test formel rejetterait n'importe quel modèle
     * paramétrique. On ne teste pas une hypothèse, on refuse une inadéquation grossière.
     */
    val maxKs: Double = 0.08,
    /**
     * Seuil de la distance en variation totale entre les poids géométriques ajustés et la part
     * réellement attribuée à chaque harmonique. C'est **le** garde-fou contre l'hypothèse
     * d'indépendance des manqués (approximation 3 ci-dessus).
     *
     * Calibré sur simulation : un mélange conforme au modèle donne 0,007 à 0,041 (jusqu'à
     * `p = 0,65`, `σ` de 0,10 à 0,40) ; une distribution dont le pic 2× est absent donne 0,118 à
     * 0,250. Le seuil est posé au milieu de cet écart, du côté conservateur.
     */
    val maxGeometricMisfit: Double = 0.10,
    /** Sous cette dispersion, la statistique KS n'a plus de sens : le gain KS est neutralisé. */
    val ksMinSigma: Double = 0.02,
    /**
     * Borne **basse** du drapeau d'alternance. Posée à 0,48 et non à 0,50 parce que sur un train
     * purement périodique l'estimation de `p` est biaisée **vers le haut** d'environ +0,03 par la
     * troncature à [maxHarmonics] : un taux vrai de 0,39 (manqués purement mécaniques, Terrill)
     * ressort entre 0,40 et 0,44, et un taux vrai de 0,50 (latéralisation stochastique) entre 0,53
     * et 0,55. Le seuil sépare les deux avec une marge des deux côtés, et `RhythmTest` l'assertionne
     * dans les deux sens.
     *
     * **Réserve mesurée, de signe opposé.** Ce +0,03 vaut pour un train dont *tous* les intervalles
     * appartiennent au mélange harmonique. Sur une nuit nominale complète — où des mouvements isolés
     * et des RRLM s'intercalent entre les séries — `p` est au contraire **sous**-estimé :
     * `docs/07-validation.md` §4.3 le mesure à 0,098 pour un taux vrai de 0,00, 0,213 pour 0,30 et
     * 0,333 pour 0,50. Sur une telle nuit, une latéralisation réelle ressortirait donc **sous** 0,48
     * et ce drapeau ne se lèverait pas. Le corriger demanderait de calibrer le seuil sur des trains
     * mêlés, ce qui est une décision de calibration clinique et non une correction : elle n'est pas
     * prise ici, elle est écrite.
     */
    val alternationMinMissRate: Double = 0.48,
    /**
     * Borne **haute** du drapeau d'alternance. Sans elle, la condition était une demi-droite, et
     * « une fois sur deux » ne se distinguait pas de « presque tout le temps ».
     *
     * Le drapeau affirme quelque chose de clinique — les mouvements alternent peut-être entre les
     * jambes — et il le déduisait d'un `p` élevé, quelle qu'en soit la cause. Mesuré sur la nuit
     * nominale (§4.3), où le générateur ne produit **aucune** alternance et où le taux de manqués
     * vaut 0,73 à 0,83 pour une raison purement amplitudinaire, il se levait **14 fois sur 20**.
     * C'était la seule sortie du système à être activement fausse plutôt que simplement absente.
     *
     * La valeur 0,65 n'est pas choisie pour faire passer une mesure : c'est celle que ce fichier
     * énonçait déjà deux paragraphes plus haut, à l'approximation nº 2 — au-delà de `p = 0,65` la
     * queue tronquée pèse 7,5 % et « l'estimation de `p` n'est plus fiable ». Un drapeau ne peut pas
     * s'appuyer sur une grandeur que le module déclare lui-même non fiable. Elle laisse intacte la
     * plage 0,53–0,55 où ressort une latéralisation vraie sur train pur, que `RhythmTest` assertionne.
     *
     * **Ce que la borne ne répare pas, et il faut le lire avant de croire ce drapeau.** Elle fait
     * tomber le compte de 14/20 à 1/20 sur la nuit nominale, mais le balayage de `calFraction` (§4.4)
     * montre qu'à `f_cal = 0,06`, où le taux de manqués descend justement vers 0,5, il remonte à
     * **11/20** — toujours sans la moindre alternance dans le générateur. C'est attendu et ce n'est
     * pas réglable : `p` et la part du fondamental sont **les mêmes** selon qu'une moitié des
     * mouvements manque parce qu'ils sont sous le seuil ou parce qu'ils sont sur l'autre jambe. La
     * seule grandeur qui séparerait les deux est l'amplitude des événements détectés — une
     * latéralisation est aveugle à l'amplitude, un seuil ne l'est pas — et `Rhythm` ne reçoit que des
     * intervalles. Symétriquement, au taux fait pour lui (0,50) le drapeau ne se lève que 3 fois sur
     * 20 sur un train réaliste. **Faux positif d'un côté, presque aveugle de l'autre :** ce drapeau
     * demande une décision de conception, pas un réglage.
     */
    val alternationMaxMissRate: Double = 0.65,
    /**
     * « Pic fondamental faible », mesuré sur la part **empirique** de la première composante et non
     * sur le poids du modèle — sans quoi la condition serait une simple redite de `p`.
     */
    val alternationMaxFundamentalShare: Double = 0.55,
)

/** Pourquoi un ajustement a été refusé. `null` = résultat valide. */
enum class RhythmReject {
    TOO_FEW_INTERVALS,
    NOT_CONVERGED,
    /** `p` collé au plafond : plus de fondamental exploitable. */
    MISS_RATE_SATURATED,
    /** `σ` collé au plafond : la distribution n'a pas de mode identifiable. */
    SIGMA_SATURATED,
    /** Les manqués ne sont pas géométriques — probablement agglomérés en fin de salve. */
    GEOMETRIC_MISFIT,
    /** La forme globale de la distribution n'est pas celle du mélange ajusté. */
    DISTRIBUTION_MISFIT,
}

/**
 * Ajustement complet. [RhythmResult] est la sortie contractuelle ; ce type ajoute les diagnostics
 * qui permettent de savoir **pourquoi** on a le droit — ou pas — de croire le chiffre.
 *
 * @param componentShare part moyenne des observations attribuée à chaque harmonique (responsabilités
 *   moyennes). À comparer à `RhythmResult.harmonicWeights`, qui sont les poids géométriques du
 *   modèle : leur écart est [geometricMisfit].
 */
data class RhythmFit(
    val result: RhythmResult,
    val logLikelihood: Double,
    val iterations: Int,
    val ksStatistic: Double,
    val geometricMisfit: Double,
    val componentShare: DoubleArray,
    val reject: RhythmReject?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RhythmFit) return false
        return result == other.result && logLikelihood == other.logLikelihood &&
            iterations == other.iterations && ksStatistic == other.ksStatistic &&
            geometricMisfit == other.geometricMisfit &&
            componentShare.contentEquals(other.componentShare) && reject == other.reject
    }

    override fun hashCode(): Int {
        var r = result.hashCode()
        r = 31 * r + logLikelihood.hashCode()
        r = 31 * r + iterations
        r = 31 * r + ksStatistic.hashCode()
        r = 31 * r + geometricMisfit.hashCode()
        r = 31 * r + componentShare.contentHashCode()
        r = 31 * r + (reject?.hashCode() ?: 0)
        return r
    }
}

private const val LN_2PI = 1.8378770664093453

object Rhythm {

    // --- Points d'entrée -------------------------------------------------------------------

    /**
     * Entrée **recommandée** : tous les CLM de sommeil consécutifs.
     *
     * Ne pas partir des séries déjà construites : la construction de série a **déjà** filtré les
     * intervalles hors [10, 90] s, c'est-à-dire précisément les harmoniques hauts que l'on cherche
     * à modéliser. Estimer `p` sur des intervalles pré-filtrés le sous-estime mécaniquement.
     */
    fun fromClms(
        clms: List<Clm>,
        mask: SleepMask,
        cfg: RhythmConfig = RhythmConfig(),
    ): RhythmResult = fitFromClms(clms, mask, cfg).result

    fun fitFromClms(
        clms: List<Clm>,
        mask: SleepMask,
        cfg: RhythmConfig = RhythmConfig(),
    ): RhythmFit = fit(intervalsOf(clms, mask, cfg), cfg)

    /**
     * Repli quand seules les séries sont disponibles (mode incrémental). Voir la réserve de
     * [fromClms] : `p` y est structurellement sous-estimé.
     */
    fun fromSeries(series: List<PlmSeries>, cfg: RhythmConfig = RhythmConfig()): RhythmResult {
        val acc = ArrayList<Double>()
        for (s in series) for (v in s.imiSec) acc.add(v.toDouble())
        return fit(DoubleArray(acc.size) { acc[it] }, cfg).result
    }

    /** Intervalles onset-à-onset entre CLM de sommeil consécutifs, filtrés par la fenêtre de [cfg]. */
    fun intervalsOf(clms: List<Clm>, mask: SleepMask, cfg: RhythmConfig = RhythmConfig()): DoubleArray {
        val lookup = SleepLookup(mask.windows)
        val acc = ArrayList<Double>(clms.size)
        var prev = Long.MIN_VALUE
        for (c in clms) {
            if (!c.isClm) continue
            if (!lookup.isSleepAt(c.onsetMsRel)) continue
            if (prev != Long.MIN_VALUE) acc.add((c.onsetMsRel - prev) / 1000.0)
            prev = c.onsetMsRel
        }
        return DoubleArray(acc.size) { acc[it] }
    }

    fun estimate(imiSec: DoubleArray, cfg: RhythmConfig = RhythmConfig()): RhythmResult =
        fit(imiSec, cfg).result

    // --- Ajustement -------------------------------------------------------------------------

    /**
     * Déconvolution proprement dite. Pure, déterministe, sans aléa : l'initialisation est une
     * grille fixe de points de départ (quantiles des données × trois valeurs de `p`), et le
     * meilleur en vraisemblance gagne. Deux exécutions sur la même entrée sont identiques au bit.
     */
    fun fit(imiSec: DoubleArray, cfg: RhythmConfig = RhythmConfig()): RhythmFit {
        require(cfg.maxHarmonics >= 1) { "maxHarmonics doit etre >= 1" }
        require(cfg.minIntervalSec > 0.0 && cfg.maxIntervalSec > cfg.minIntervalSec) {
            "fenetre de selection des intervalles incoherente"
        }

        // Selection : on ne garde que ce que le modele peut expliquer. Le filtre haut coupe aussi
        // les intervalles inter-series (plusieurs minutes) qui ne sont pas des harmoniques.
        var kept = 0
        for (v in imiSec) {
            if (v >= cfg.minIntervalSec && v <= cfg.maxIntervalSec && v.isFinite()) kept++
        }
        val x = DoubleArray(kept)
        var w = 0
        for (v in imiSec) {
            if (v >= cfg.minIntervalSec && v <= cfg.maxIntervalSec && v.isFinite()) x[w++] = ln(v)
        }

        if (kept < cfg.minIntervals) return emptyFit(kept, RhythmReject.TOO_FEW_INTERVALS)

        val k = cfg.maxHarmonics

        // --- Grille d'initialisation deterministe ---------------------------------------
        // mu0 : deux quantiles bas. Sous un taux de manques eleve, la mediane peut deja tomber
        // entre le fondamental et le premier harmonique ; le quartile bas, lui, reste dans le
        // fondamental tant que p < 0,75.
        val mu0s = doubleArrayOf(quantileOf(x, 0.25), quantileOf(x, 0.50))
        val p0s = doubleArrayOf(0.10, 0.40, 0.65)
        val mad = madOf(x)
        val sigma0 = if (mad.isFinite() && mad > 0.0) mad.coerceIn(0.08, 0.50) else 0.25

        var em = runEm(x, mu0s[0], sigma0, p0s[0], cfg)
        var bestLogLik = em.logLik
        for (mu0 in mu0s) {
            for (p0 in p0s) {
                val s = runEm(x, mu0, sigma0, p0, cfg)
                // Comparaison stricte : a vraisemblance egale, le premier de la grille gagne.
                // C'est ce qui rend le choix reproductible au bit.
                if (s.logLik > bestLogLik) {
                    bestLogLik = s.logLik
                    em = s
                }
            }
        }

        val weights = geometricWeights(em.p, k)
        val share = responsibilityShare(x, em.mu, em.sigma, weights)

        // --- Adequation du modele aux donnees -------------------------------------------
        // (a) Forme des poids : les manques sont-ils vraiment geometriques ? Si l'accelerometre
        //     rate preferentiellement les fins de salve, le pic 2x est sous-peuple et cette
        //     distance en variation totale explose, alors meme que la moyenne des rangs colle.
        var tv = 0.0
        for (i in 0 until k) tv += abs(share[i] - weights[i])
        val geometricMisfit = 0.5 * tv
        // (b) Forme globale de la distribution.
        val ks = ksStatistic(x, em.mu, em.sigma, weights)

        var reject: RhythmReject? = null
        if (!em.converged || !em.mu.isFinite() || !em.sigma.isFinite() || !em.p.isFinite()) {
            reject = RhythmReject.NOT_CONVERGED
        } else if (em.p >= cfg.maxMissRate) reject = RhythmReject.MISS_RATE_SATURATED
        else if (em.sigma >= cfg.sigmaCeiling) reject = RhythmReject.SIGMA_SATURATED
        else if (geometricMisfit > cfg.maxGeometricMisfit) reject = RhythmReject.GEOMETRIC_MISFIT
        else if (em.sigma > cfg.ksMinSigma && ks > cfg.maxKs) reject = RhythmReject.DISTRIBUTION_MISFIT

        val valid = reject == null
        // Le drapeau d'alternance ne depend pas de `valid` : il se lit AVEC lui. Une nuit invalide
        // dont p vaut 0,5 reste une nuit ou l'hypothese d'alternance merite d'etre posee.
        //
        // C'est une **bande** et non une demi-droite : au-dela de `alternationMaxMissRate`, `p` ne
        // dit plus « une fois sur deux » mais « presque tout le temps », ce qui est un defaut de
        // detection et non une lateralisation. Voir la KDoc de ce parametre.
        val alternation = em.p >= cfg.alternationMinMissRate &&
            em.p <= cfg.alternationMaxMissRate &&
            share[0] <= cfg.alternationMaxFundamentalShare

        return RhythmFit(
            result = RhythmResult(
                fundamentalSec = exp(em.mu),
                muLog = em.mu,
                sigmaLog = em.sigma,
                missRate = em.p,
                harmonicWeights = weights,
                alternationSuspect = alternation,
                intervalsUsed = kept,
                converged = em.converged,
                valid = valid,
            ),
            logLikelihood = em.logLik,
            iterations = em.iterations,
            ksStatistic = ks,
            geometricMisfit = geometricMisfit,
            componentShare = share,
            reject = reject,
        )
    }

    private fun emptyFit(intervals: Int, reject: RhythmReject): RhythmFit = RhythmFit(
        result = RhythmResult(
            // NaN et non 0.0 : « pas de valeur » doit empoisonner visiblement tout calcul aval,
            // pas se faire passer pour une periode nulle.
            fundamentalSec = Double.NaN,
            muLog = Double.NaN,
            sigmaLog = Double.NaN,
            missRate = Double.NaN,
            harmonicWeights = DoubleArray(0),
            alternationSuspect = false,
            intervalsUsed = intervals,
            converged = false,
            valid = false,
        ),
        logLikelihood = Double.NaN,
        iterations = 0,
        ksStatistic = Double.NaN,
        geometricMisfit = Double.NaN,
        componentShare = DoubleArray(0),
        reject = reject,
    )

    // --- Espérance-maximisation --------------------------------------------------------------

    private class EmState(
        val mu: Double,
        val sigma: Double,
        val p: Double,
        val logLik: Double,
        val iterations: Int,
        val converged: Boolean,
    )

    private fun runEm(
        x: DoubleArray,
        mu0: Double,
        sigma0: Double,
        p0: Double,
        cfg: RhythmConfig,
    ): EmState {
        val n = x.size
        val k = cfg.maxHarmonics
        val lnK = DoubleArray(k) { ln((it + 1).toDouble()) }
        var mu = mu0
        var sigma = sigma0.coerceIn(cfg.sigmaFloor, cfg.sigmaCeiling)
        var p = p0.coerceIn(0.0, cfg.maxMissRate)
        var iterations = 0
        var converged = false
        val lw = DoubleArray(k)
        val lp = DoubleArray(k)

        while (iterations < cfg.maxIterations) {
            iterations++
            val weights = geometricWeights(p, k)
            for (i in 0 until k) lw[i] = if (weights[i] > 0.0) ln(weights[i]) else Double.NEGATIVE_INFINITY
            val lnSigma = ln(sigma)

            var s1 = 0.0   // Σ r·y          avec y = x − ln k
            var s2 = 0.0   // Σ r·y²
            var sk = 0.0   // Σ r·k          → rang moyen, ce qui identifie p

            for (i in 0 until n) {
                var maxLp = Double.NEGATIVE_INFINITY
                for (c in 0 until k) {
                    val z = (x[i] - mu - lnK[c]) / sigma
                    lp[c] = lw[c] - lnSigma - 0.5 * LN_2PI - 0.5 * z * z
                    if (lp[c] > maxLp) maxLp = lp[c]
                }
                var sum = 0.0
                for (c in 0 until k) sum += exp(lp[c] - maxLp)
                val lse = maxLp + ln(sum)
                for (c in 0 until k) {
                    val r = exp(lp[c] - lse)
                    if (r == 0.0) continue
                    val y = x[i] - lnK[c]
                    s1 += r * y
                    s2 += r * y * y
                    sk += r * (c + 1)
                }
            }

            val muNew = s1 / n
            val varNew = s2 / n - muNew * muNew
            val sigmaNew = sqrt(max(varNew, 0.0)).coerceIn(cfg.sigmaFloor, cfg.sigmaCeiling)
            // MLE exacte de p pour une geometrique TRONQUEE a k composantes : le rang moyen du
            // modele est strictement croissant en p, donc la racine est unique et la dichotomie
            // converge sans risque de cycle.
            val pNew = solveMissRate(sk / n, k).coerceIn(0.0, cfg.maxMissRate)

            val delta = max(abs(muNew - mu), max(abs(sigmaNew - sigma), abs(pNew - p)))
            mu = muNew
            sigma = sigmaNew
            p = pNew
            if (delta < cfg.tolerance) {
                converged = true
                break
            }
        }

        return EmState(mu, sigma, p, logLikelihood(x, mu, sigma, geometricWeights(p, k)), iterations, converged)
    }

    // --- Primitives du mélange ---------------------------------------------------------------

    /** Poids `w_k ∝ p^(k−1)(1−p)`, tronqués à `k` composantes et renormalisés. */
    internal fun geometricWeights(p: Double, k: Int): DoubleArray {
        val w = DoubleArray(k)
        var acc = 0.0
        var pk = 1.0
        for (i in 0 until k) {
            w[i] = pk * (1.0 - p)
            acc += w[i]
            pk *= p
        }
        if (!(acc > 0.0)) {          // p = 1 : degenere, on repartit uniformement
            w.fill(1.0 / k)
            return w
        }
        for (i in 0 until k) w[i] /= acc
        return w
    }

    /** Rang moyen `E[k]` sous les poids tronqués. Strictement croissant en `p`, de 1 à (k+1)/2. */
    internal fun meanRank(p: Double, k: Int): Double {
        val w = geometricWeights(p, k)
        var m = 0.0
        for (i in 0 until k) m += (i + 1) * w[i]
        return m
    }

    /**
     * Inverse [meanRank] par dichotomie — 80 tours, donc convergence à ~1e-24 : le résultat ne
     * dépend d'aucun ordre d'évaluation et reste identique au bit d'une exécution à l'autre.
     */
    internal fun solveMissRate(meanK: Double, k: Int): Double {
        if (k <= 1) return 0.0
        if (!(meanK > 1.0)) return 0.0
        var hi = 1.0 - 1e-12
        if (meanK >= meanRank(hi, k)) return hi
        var lo = 0.0
        repeat(80) {
            val mid = 0.5 * (lo + hi)
            if (meanRank(mid, k) < meanK) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    private fun logLikelihood(x: DoubleArray, mu: Double, sigma: Double, weights: DoubleArray): Double {
        val k = weights.size
        val lnSigma = ln(sigma)
        val lp = DoubleArray(k)
        var acc = 0.0
        for (i in x.indices) {
            var maxLp = Double.NEGATIVE_INFINITY
            for (c in 0 until k) {
                val lw = if (weights[c] > 0.0) ln(weights[c]) else Double.NEGATIVE_INFINITY
                val z = (x[i] - mu - ln((c + 1).toDouble())) / sigma
                lp[c] = lw - lnSigma - 0.5 * LN_2PI - 0.5 * z * z
                if (lp[c] > maxLp) maxLp = lp[c]
            }
            var sum = 0.0
            for (c in 0 until k) sum += exp(lp[c] - maxLp)
            acc += maxLp + ln(sum)
        }
        return acc
    }

    /** Part moyenne des observations attribuée à chaque harmonique (responsabilités moyennes). */
    private fun responsibilityShare(
        x: DoubleArray,
        mu: Double,
        sigma: Double,
        weights: DoubleArray,
    ): DoubleArray {
        val k = weights.size
        val share = DoubleArray(k)
        val lp = DoubleArray(k)
        val lnSigma = ln(sigma)
        for (i in x.indices) {
            var maxLp = Double.NEGATIVE_INFINITY
            for (c in 0 until k) {
                val lw = if (weights[c] > 0.0) ln(weights[c]) else Double.NEGATIVE_INFINITY
                val z = (x[i] - mu - ln((c + 1).toDouble())) / sigma
                lp[c] = lw - lnSigma - 0.5 * LN_2PI - 0.5 * z * z
                if (lp[c] > maxLp) maxLp = lp[c]
            }
            var sum = 0.0
            for (c in 0 until k) sum += exp(lp[c] - maxLp)
            val lse = maxLp + ln(sum)
            for (c in 0 until k) share[c] += exp(lp[c] - lse)
        }
        if (x.isNotEmpty()) for (c in 0 until k) share[c] /= x.size
        return share
    }

    /** Statistique de Kolmogorov–Smirnov entre l'empirique et le mélange ajusté. */
    private fun ksStatistic(
        x: DoubleArray,
        mu: Double,
        sigma: Double,
        weights: DoubleArray,
    ): Double {
        val n = x.size
        if (n == 0) return Double.NaN
        val s = x.copyOf()
        s.sort()
        var d = 0.0
        for (i in 0 until n) {
            val f = mixtureCdf(s[i], mu, sigma, weights)
            val above = (i + 1).toDouble() / n - f
            val below = f - i.toDouble() / n
            if (above > d) d = above
            if (below > d) d = below
        }
        return d
    }

    internal fun mixtureCdf(v: Double, mu: Double, sigma: Double, weights: DoubleArray): Double {
        var acc = 0.0
        for (c in weights.indices) {
            if (weights[c] <= 0.0) continue
            acc += weights[c] * normalCdf((v - mu - ln((c + 1).toDouble())) / sigma)
        }
        return acc
    }

    internal fun normalCdf(z: Double): Double = 0.5 * erfc(-z * 0.7071067811865476)

    /**
     * `erfc` par l'approximation rationnelle de Numerical Recipes (erreur relative < 1,2e-7).
     * Aucune dépendance externe, aucun tirage, résultat identique à chaque exécution.
     */
    internal fun erfc(x: Double): Double {
        val z = abs(x)
        val t = 1.0 / (1.0 + 0.5 * z)
        val ans = t * exp(
            -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
                t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 +
                    t * (-0.82215223 + t * 0.17087277))))))))
        )
        return if (x >= 0.0) ans else 2.0 - ans
    }

    /**
     * Biais théorique de la moyenne du log dû aux manqués : `E[ln N] = Σ p^(k−1)(1−p)·ln k`.
     * Exposé pour le rapport et pour vérifier, sur données simulées, que la déconvolution enlève
     * bien ce que la moyenne brute contient (§5.3).
     */
    fun rawLogBias(p: Double, terms: Int = 200): Double {
        if (!(p > 0.0)) return 0.0
        var acc = 0.0
        var pk = 1.0
        for (i in 1..terms) {
            acc += pk * (1.0 - p) * ln(i.toDouble())
            pk *= p
        }
        return acc
    }
}
