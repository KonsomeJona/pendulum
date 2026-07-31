package com.pendulum.algo.synth

/**
 * Parametres d'une nuit synthetique. Transcription de `docs/ALGO-v2.md` §5.2, etendue aux douze
 * familles de distracteurs du tableau du meme paragraphe.
 *
 * Tout est parametrable et rien n'a de valeur cachee : c'est le generateur qui sert de **reference**
 * a l'ensemble de l'algorithme, et un generateur dont on ne peut pas durcir un distracteur ne
 * prouve rien (defaut F-21 de `docs/REVUE-CRITIQUE.md`, « validation circulaire »).
 */

/** Echelle sur laquelle se lit l'amplitude tiree pour un mouvement. */
enum class AmplitudeScale {
    /**
     * Crete de la **norme** du signal de mouvement (accelerometre, hors gravite statique) : les
     * trois contributions de §5.1 sommees — tangentielle, reprojection de la gravite, centripete.
     *
     * Ce n'est **pas** la grandeur du tableau de calibration de §5.1 (30 / 184 / 985 mg), contrairement
     * a ce que ce KDoc affirmait. Ce tableau chiffre le seul terme **tangentiel**, `r . theta''`, que
     * calcule [MovementKinematics.peakTangentialG] ; §5.1 note d'ailleurs juste apres qu'aux grands
     * angles c'est le terme gravitaire qui domine, ce qui interdit de confondre les deux. Les valeurs
     * du tableau sont asserties par `MovementModelTest`, qui appelle `peakTangentialG` directement.
     */
    PEAK,

    /**
     * Crete de l'**enveloppe RMS grossiere de 0,5 s**, c'est-a-dire exactement la grandeur que le
     * detecteur compare a `Theta_on`. Indispensable au test T5 : « 8x le plancher = le seuil, par
     * construction » n'a de sens que si l'axe des abscisses de la courbe de sensibilite est celui
     * sur lequel la decision est prise.
     */
    COARSE_ENVELOPE,
}

/**
 * Loi d'amplitude des mouvements (§5.1, derniere ligne).
 *
 * @param sigmaLog **parametre**, pas constante : la specification precise que c'est un choix
 *   d'ingenierie et qu'il pilote directement la pente de la courbe de sensibilite (T5).
 * @param fixedG si non nul, court-circuite la loi et impose cette amplitude a tous les mouvements.
 *   Sert au balayage de la courbe de sensibilite.
 */
data class AmplitudeSpec(
    val medianG: Double = 0.080,
    val sigmaLog: Double = 0.60,
    val minG: Double = 0.010,
    val maxG: Double = 0.800,
    val fixedG: Double? = null,
    val scale: AmplitudeScale = AmplitudeScale.PEAK,
)

/**
 * Loi de duree et geometrie du mouvement (§5.1).
 *
 * `meanSec` reprend Sforza 2005 : **4,2 s** de duree moyenne a la cheville en SJSR.
 *
 * `sdSec` **n'est pas** un chiffre de Sforza, contrairement a ce que ce KDoc affirmait. Le « +/- 0,14 »
 * publie est une **erreur type de la moyenne** — le §2.5 de l'article dit « results in the text and in
 * the tables are expressed as mean +/- standard error of the mean », et le groupe SJSR compte 11
 * patients. L'ecart-type inter-patients des moyennes individuelles vaut donc `0,14 x sqrt(11) ~ 0,46 s`,
 * et la dispersion **evenement par evenement** — la seule dont un generateur a besoin — n'est publiee
 * nulle part. Le 1,4 s retenu ici est un **choix de modelisation** : il donne un CV de 0,33, dont les
 * quantiles a +/-2 sigma (~1,9 a 8,0 s) restent confortablement dans la fenetre de cotation 0,5-10 s des
 * criteres de Coleman. Une valeur de 0,14 s ferait de la loi une masse de Dirac, ce qui est incompatible
 * avec l'existence meme de cette fenetre et avec la moyenne de 3,2 s du groupe TMPJ du meme article.
 * Le mode court de ~2-3 s parfois avance n'est confirme par aucun histogramme publie et n'est pas code.
 */
data class DurationSpec(
    val meanSec: Double = 4.2,
    val sdSec: Double = 1.4,
    val minSec: Double = 0.5,
    val maxSec: Double = 10.0,
    /** Duree de la phase de flexion, `T_rise`. Donne `f_pic = 0,8 / T_rise` dans [1,6 ; 5,3] Hz. */
    val tRiseMinSec: Double = 0.15,
    val tRiseMaxSec: Double = 0.50,
    /** Bras de levier capteur / centre de rotation, en metres. */
    val radiusMinM: Double = 0.15,
    val radiusMaxM: Double = 0.30,
    /**
     * Rapport « pic d'acceleration pendant le maintien / pic balistique de la flexion ».
     *
     * La phase de maintien d'un CLM n'est pas un plateau immobile : voir la KDoc de
     * [MovementKinematics]. La valeur 0,50 est le rapport **seuil de decroissance / seuil d'entree**
     * du PAM-RL (100 mg / 200 mg, Sforza 2005 §2.4), le dispositif qui a mesure les 4,2 s : c'est le
     * minimum qu'un evenement doit soutenir pour avoir ete compte comme un seul kick de 4,2 s plutot
     * que scinde par le drop-out de 1 s.
     *
     * `0.0` restaure le plateau immobile du modele d'origine — utile pour reproduire le defaut.
     */
    val holdActivityRatio: Double = 0.50,
)

/** Une population de series periodiques. `NightSpec.trueSeries` en contient autant qu'on veut. */
data class SeriesSpec(
    val nSeries: Int,
    val clmPerSeries: Int,
    val imiMeanSec: Double,
    val imiCvPct: Double,
)

/**
 * Structure veille / sommeil de la nuit. Elle fournit le **denominateur vrai** : c'est le journal
 * de sommeil parfait, totalement independant du signal, donc non circulaire par construction.
 */
data class SleepSpec(
    val sleepLatencyMin: Double = 18.0,
    val finalWakeMin: Double = 8.0,
    val wasoCount: Int = 4,
    val wasoMinMin: Double = 3.0,
    val wasoMaxMin: Double = 12.0,
)

/** Famille 5 du tableau §5.2 : bruit MEMS et quantification. */
data class NoiseSpec(
    /** Densite spectrale du bruit, en g/sqrt(Hz). Plage du tableau : 150-300 ug/sqrt(Hz). */
    val densityMinG: Double = 150e-6,
    val densityMaxG: Double = 300e-6,
    /** Pas de quantification du format, 1/2048 g. `0` desactive la quantification. */
    val lsbG: Double = 1.0 / 2048.0,
    /**
     * Derive posturale lente du membre porteur, en degres RMS (processus d'Ornstein-Uhlenbeck de
     * constante `wanderTauSec`). **Ce n'est pas du bruit de capteur** : c'est le fait qu'une jambe
     * vivante ne tient jamais exactement la meme orientation dix minutes de suite. Sans ce terme,
     * une nuit calme presente un ecart-type brut inferieur au seuil `offBodySdG` (5 mg) et l'etape 0
     * la classe integralement **off-body** — le test T1 passerait alors a vide, faute de temps
     * analysable. Le contenu est sous 0,01 Hz : il tombe entierement dans le canal gravite et ne
     * touche pas le canal mouvement.
     */
    val wanderDeg: Double = 0.8,
    val wanderTauSec: Double = 120.0,
)

/**
 * Les douze familles de distracteurs du tableau §5.2. Les comptes sont tires uniformement dans
 * `[...Min, ...Max]`, les amplitudes log-uniformement (une amplitude uniforme dans [3 ; 40] mg
 * mettrait la moitie de la masse au-dessus de 21 mg, ce qui n'est pas ce que decrit la source).
 */
data class DistractorSpec(
    // --- 1. Changements de posture -------------------------------------------------------
    val postureCountMin: Int = 15,
    val postureCountMax: Int = 40,
    val postureDegMin: Double = 20.0,
    val postureDegMax: Double = 120.0,
    val postureDurMinSec: Double = 0.5,
    val postureDurMaxSec: Double = 3.0,

    // --- 2. Mouvements corporels grossiers ------------------------------------------------
    val grossBodyCountMin: Int = 20,
    val grossBodyCountMax: Int = 60,
    val grossBodyDurMinSec: Double = 2.0,
    val grossBodyDurMaxSec: Double = 20.0,
    val grossBodyAmpMinG: Double = 0.300,
    val grossBodyAmpMaxG: Double = 2.500,

    // --- 3. Artefact respiratoire ---------------------------------------------------------
    val respiratory: Boolean = true,
    val respHzMin: Double = 0.20,
    val respHzMax: Double = 0.33,
    val respAmpMinG: Double = 0.001,
    val respAmpMaxG: Double = 0.010,
    val respAmPeriodMinSec: Double = 60.0,
    val respAmPeriodMaxSec: Double = 300.0,

    // --- 4. Vibration de matelas ----------------------------------------------------------
    val mattressCountMin: Int = 50,
    val mattressCountMax: Int = 500,
    val mattressDurMinSec: Double = 0.05,
    val mattressDurMaxSec: Double = 0.40,
    val mattressAmpMinG: Double = 0.003,
    val mattressAmpMaxG: Double = 0.040,
    val mattressRingHzMin: Double = 8.0,
    val mattressRingHzMax: Double = 20.0,

    // --- 6. Trous FIFO --------------------------------------------------------------------
    val gapCountMin: Int = 0,
    val gapCountMax: Int = 0,
    val gapMinSec: Double = 0.1,
    val gapMaxSec: Double = 5.0,
    val longGap: Boolean = false,
    val longGapMinSec: Double = 30.0,
    val longGapMaxSec: Double = 120.0,

    // --- 8. Off-body ----------------------------------------------------------------------
    val offBody: Boolean = false,
    val offBodyMin: Double = 12.0,
    /** La montre pose elle-meme son drapeau materiel. `false` par defaut : le detecteur d'immobilite
     *  absolue doit savoir se debrouiller seul, sinon on ne teste que la confiance au drapeau. */
    val offBodyHardwareFlag: Boolean = false,

    // --- 9. Tremblement hypnagogique / ALMA -----------------------------------------------
    val almaCountMin: Int = 0,
    val almaCountMax: Int = 0,
    val almaDurMinSec: Double = 10.0,
    val almaDurMaxSec: Double = 15.0,
    val almaHzMin: Double = 0.3,
    val almaHzMax: Double = 4.0,
    val almaAmpMinG: Double = 0.020,
    val almaAmpMaxG: Double = 0.080,

    // --- 10. Salves non periodiques -------------------------------------------------------
    val clusterCount: Int = 0,
    val clusterSizeMin: Int = 5,
    val clusterSizeMax: Int = 10,
    val clusterImiMinSec: Double = 1.0,
    val clusterImiMaxSec: Double = 8.0,

    // --- 11. RRLM (mouvements lies a la respiration) --------------------------------------
    val rrlmSeriesCount: Int = 0,
    val rrlmPerSeries: Int = 6,
    val rrlmImiMinSec: Double = 25.0,
    val rrlmImiMaxSec: Double = 45.0,

    // --- 12. Saut de gain mecanique en cours de nuit --------------------------------------
    /** Facteur applique au couplage mecanique a partir de `gainStepAtFraction` de la nuit. */
    val gainStep: Double? = null,
    val gainStepAtFraction: Double = 0.5,
) {
    companion object {
        /** Nuit sans aucun distracteur : seuls le signal utile et le bruit MEMS subsistent. */
        val NONE: DistractorSpec = DistractorSpec(
            postureCountMin = 0, postureCountMax = 0,
            grossBodyCountMin = 0, grossBodyCountMax = 0,
            respiratory = false,
            mattressCountMin = 0, mattressCountMax = 0,
        )

        /** Les douze familles actives, valeurs par defaut du tableau §5.2. C'est la nuit du test T6. */
        val ALL: DistractorSpec = DistractorSpec(
            gapCountMin = 20, gapCountMax = 60, longGap = true,
            offBody = true,
            almaCountMin = 2, almaCountMax = 8,
            clusterCount = 3,
            rrlmSeriesCount = 2,
        )
    }
}

/**
 * Rituel de calibration mecanique de §3.3 volet B. Le generateur le **rend physiquement**, avec le
 * meme modele que les CLM, plutot que de poser une constante : `gainCal` doit varier avec le
 * couplage mecanique de la nuit exactement comme le signal utile, sinon le test T11 se contente de
 * verifier une tautologie.
 */
data class RitualSpec(
    val thetaMaxDeg: Double = 25.0,
    val tRiseSec: Double = 0.30,
    val holdSec: Double = 0.40,
    val radiusM: Double = 0.22,
)

/**
 * Nuit complete. `durationH` est la duree d'enregistrement ; `truncateAtH` la coupe (montre morte).
 *
 * @param ankleOnlyFraction fraction des mouvements de jambe qui sont une **rotation pure de la
 *   cheville** : le boitier etant au-dessus de l'axe talo-cruraire, il ne se deplace pas. C'est le
 *   mecanisme physique du taux de manques de Terrill (39,0 %), et c'est ce qui separe `emgTruth` de
 *   `accelTruth`. Plage publiee 0,25-0,55.
 * @param gainMultiplier couplage mecanique cheville -> bracelet -> boitier de la nuit entiere
 *   (serrage du bracelet). `1,0` = nuit de reference.
 * @param visibilityG sous cette crete simulee, un mouvement n'est pas mecaniquement visible et sort
 *   de `accelTruth` (defaut 8 mg, ~0,4x le plancher absolu).
 */
data class NightSpec(
    val durationH: Double = 8.0,
    val fsRealHz: Double = 50.0,
    /** Derive lineaire de `fs` sur la nuit, en pourcent (plage du tableau : +/- 0,5 %). */
    val fsDriftPct: Double = 0.0,
    val trueSeries: List<SeriesSpec> = listOf(SeriesSpec(24, 7, 22.0, 25.0)),
    val isolatedClmPerHour: Double = 6.0,
    val ankleOnlyFraction: Double = 0.39,
    val gainMultiplier: Double = 1.0,
    val truncateAtH: Double? = null,
    val distractors: DistractorSpec = DistractorSpec.ALL,
    val noise: NoiseSpec = NoiseSpec(),
    val amplitude: AmplitudeSpec = AmplitudeSpec(),
    val duration: DurationSpec = DurationSpec(),
    val sleep: SleepSpec = SleepSpec(),
    val ritual: RitualSpec = RitualSpec(),
    val visibilityG: Double = 0.008,
    /** Orientation initiale du segment jambier, en degres par rapport a l'horizontale. */
    val initialTiltDeg: Double = 15.0,
    /** Taille nominale d'un bloc du format. 250 echantillons = 5 s a 50 Hz. */
    val blockSamples: Int = 250,
    /** Instant du premier echantillon, en ns. Fixe : aucune horloge murale n'entre ici. */
    val startNs: Long = 1_000_000_000L,
) {
    init {
        require(durationH > 0.0) { "durationH doit etre > 0" }
        require(fsRealHz > 0.0) { "fsRealHz doit etre > 0" }
        require(ankleOnlyFraction in 0.0..1.0) { "ankleOnlyFraction hors [0,1]" }
        require(blockSamples in 2..512) { "blockSamples hors [2, 512] (limite du format)" }
    }

    /** Duree effectivement enregistree, troncature comprise. */
    val recordedH: Double get() = truncateAtH?.coerceAtMost(durationH) ?: durationH
}
