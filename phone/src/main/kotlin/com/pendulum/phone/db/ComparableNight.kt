package com.pendulum.phone.db

import androidx.room.DatabaseView

/**
 * Les criteres de comparabilite, en un seul endroit.
 *
 * ### Pourquoi ils vivent dans la base et non dans l'interface
 *
 * Garde-fou 4 de `SPEC-v2.md` §3 : **aucun bouton « exclure cette nuit »**. Une exclusion
 * decidee apres avoir vu le chiffre est un mecanisme d'auto-tromperie complet a lui seul — on
 * ecarte les nuits qui ne vont pas dans le sens attendu, de bonne foi, et la tendance qui en
 * sort est fabriquee. La parade n'est pas de resister a la tentation, c'est de rendre le geste
 * impossible : les exclusions sont des **predicats deterministes evalues avant le calcul**, et
 * ils sont evalues par SQL, en amont de tout code d'affichage.
 *
 * La vue [ComparableNight] ne *filtre* pas : elle **annote**. Les nuits ecartees restent
 * visibles avec leur motif — une nuit invisible est une nuit qu'on oublie d'expliquer.
 *
 * ### La nuit de reference
 *
 * « Meme jambe » et « meme bracelet » supposent un referentiel. Il est defini comme
 * **la premiere nuit dont le contexte a ete scelle** (`MIN(sealedAtMs)`).
 *
 * Alternative ecartee : la valeur majoritaire de la campagne. Elle a l'air plus robuste, mais
 * elle **bouge** — ajouter une nuit peut basculer la majorite et rendre retroactivement
 * incomparables des nuits qui l'etaient hier. Une tendance dont l'ensemble des points change
 * quand on ajoute un point n'est pas une tendance. Le prix a payer est reel et assume : si la
 * premiere nuit a ete faite avec le mauvais bracelet, toute la campagne sort incomparable.
 * Le remede est de repartir sur un nouveau `paramsHash`, pas d'assouplir le critere.
 *
 * ### Le double du predicat en Kotlin
 *
 * [evaluate] reproduit exactement le `CASE` de [ComparableNightSql.SQL]. Deux implementations,
 * c'est un risque de divergence — assume en echange de la seule chose qui compte ici : le
 * predicat devient **testable sur JVM**, exhaustivement, sans appareil ni base. Toute
 * modification doit toucher les deux, et `ComparableNightPredicateTest` verifie que le SQL
 * mentionne bien chacune des colonnes que le Kotlin consulte.
 */
object ComparabilityRule {

    /**
     * Duree analysable minimale, en minutes. Quatre heures.
     *
     * Ce n'est pas la duree *enregistree* mais la duree *analysable* : segments valides, hors
     * zones aveugles, hors off-body. Une nuit de 8 h dont 5 h sont trouees n'en vaut pas 8.
     */
    const val MIN_ANALYSABLE_MIN = 240.0

    /**
     * Tolerance relative sur l'etalon de gain, alignee sur
     * `com.pendulum.algo.dsp.Calibration.DEFAULT_OUTLIER_TOLERANCE`.
     *
     * 35 % parait large ; il est calibre sur le fait mesure que le jeu du bracelet fait varier
     * l'amplitude d'un facteur 2 a 3. Un seuil serre ne detecterait pas « un peu plus serre »,
     * il declarerait toutes les nuits incomparables.
     */
    const val GAIN_TOLERANCE = 0.35

    // Motifs d'exclusion. Ce sont des identifiants, pas des textes d'interface : la traduction
    // appartient a `com.pendulum.phone.ui`, qui doit pouvoir les rendre sans les reinterpreter.
    const val OK = "OK"
    const val NO_CONTEXT = "NO_CONTEXT"
    const val LEG_CHANGED = "LEG_CHANGED"
    const val STRAP_CHANGED = "STRAP_CHANGED"
    const val NOT_ALONE = "NOT_ALONE"
    const val CAL_GAIN_UNKNOWN = "CAL_GAIN_UNKNOWN"
    const val CAL_GAIN_OUT_OF_TOLERANCE = "CAL_GAIN_OUT_OF_TOLERANCE"
    const val TOO_SHORT = "TOO_SHORT"
    const val DST_NIGHT = "DST_NIGHT"

    /** Les faits, tels que la vue les lit dans les tables. Aucun n'est calcule ici. */
    data class Facts(
        val hasContext: Boolean,
        val leg: String?,
        val refLeg: String?,
        val strapId: String?,
        val refStrapId: String?,
        val aloneInBed: Boolean,
        val gainCalG: Double?,
        val refGainCalG: Double?,
        val analysableMin: Double,
        val tzOffsetStartMin: Int,
        val tzOffsetEndMin: Int,
    )

    /**
     * Double Kotlin du `CASE` SQL. Renvoie [OK] si la nuit est comparable, sinon **le premier**
     * motif d'exclusion dans l'ordre de priorite du SQL.
     *
     * L'ordre compte et il est deliberement du plus structurel au plus circonstanciel : une
     * nuit sans contexte scelle n'a meme pas de jambe a comparer, et annoncer « bracelet
     * different » a quelqu'un qui a simplement oublie de remplir le formulaire du soir serait
     * un diagnostic faux.
     */
    fun evaluate(f: Facts): String = when {
        !f.hasContext -> NO_CONTEXT
        f.refLeg == null || f.refStrapId == null -> NO_CONTEXT
        f.leg != f.refLeg -> LEG_CHANGED
        f.strapId != f.refStrapId -> STRAP_CHANGED
        !f.aloneInBed -> NOT_ALONE
        f.gainCalG == null || f.refGainCalG == null || f.refGainCalG <= 0.0 -> CAL_GAIN_UNKNOWN
        // L'epsilon n'est pas une precaution decorative : `1.35 - 1.0` vaut 0.35000000000000009 en
        // binaire, donc un ecart de tolerance pile a la borne serait rejete alors que la regle se
        // veut inclusive. Sans lui, une nuit a exactement 35 % d'ecart sort de la comparaison — et
        // c'est le genre d'exclusion qu'on ne remarque jamais, parce qu'elle a l'air d'une regle
        // qui s'applique.
        kotlin.math.abs(f.gainCalG - f.refGainCalG) / f.refGainCalG > GAIN_TOLERANCE + 1e-9 ->
            CAL_GAIN_OUT_OF_TOLERANCE
        f.analysableMin < MIN_ANALYSABLE_MIN -> TOO_SHORT
        f.tzOffsetStartMin != f.tzOffsetEndMin -> DST_NIGHT
        else -> OK
    }

    fun isComparable(f: Facts): Boolean = evaluate(f) == OK
}

/**
 * La vue que **toute** requete de tendance doit traverser.
 *
 * Une ligne par `(nuit, paramsHash, jeu de regles, masque)`. La vue n'ecarte rien : elle ajoute
 * `comparable` et `exclusionReason`. C'est `TrendDao` qui filtre, et c'est le seul endroit ou
 * un filtre existe.
 *
 * Le changement d'heure se lit `tzOffsetStartMin <> tzOffsetEndMin` : les deux offsets sont
 * enregistres par l'ingestion precisement pour que ce test soit une comparaison d'entiers et
 * non un calcul de calendrier.
 */
@DatabaseView(viewName = "comparable_night", value = ComparableNightSql.SQL)
data class ComparableNight(
    val sessionHex: String,
    val startWallMs: Long,
    val zoneId: String,
    val paramsHash: String,
    val rule: String,
    val maskSource: String,
    val gate: String,
    val independence: String,
    val plmi: Double,
    val plmiSpt: Double,
    val fundamentalSec: Double,
    /**
     * L'ajustement du rythme a-t-il ete accepte par `:algo` ?
     *
     * **Faux est le cas normal**, pas l'exception : sur la nuit nominale, `RhythmMeasurementTest`
     * mesure 2 ajustements valides sur 20 — la deconvolution refuse le plus souvent, parce que le
     * detecteur ne lui livre pas assez d'intervalles exploitables. `fundamentalSec` vaut alors
     * `NaN`, et non 0, precisement pour empoisonner visiblement tout calcul qui l'ignorerait.
     *
     * La colonne est dans la vue parce que `gate = 'FULL'` ne la couvre pas : la porte de
     * publication porte sur le denominateur et la troncature, jamais sur l'ajustement du rythme.
     * Une nuit peut donc etre comparable, publiable, et n'avoir aucun rythme a montrer.
     */
    val rhythmValid: Boolean,
    val periodicityIndex: Double,
    val missRate: Double,
    val analysableTstMin: Double,
    val analysableMin: Double,
    val truncated: Boolean,
    val revealedAtMs: Long?,
    val comparable: Boolean,
    val exclusionReason: String,
)

/**
 * Le SQL de la vue, sorti de la classe qu'il annote : une annotation ne peut pas referencer
 * une constante declaree dans la classe qu'elle annote sans creer une dependance circulaire a la
 * compilation.
 */
internal object ComparableNightSql {

    /**
     * L'ordre des `WHEN` reproduit celui de [ComparabilityRule.evaluate], et la valeur des
     * deux constantes numeriques (`240.0`, `0.35`) y est ecrite en dur : SQLite ne sait pas
     * lire une constante Kotlin. C'est la seule duplication du fichier, et c'est celle que
     * `ComparableNightPredicateTest` surveille.
     */
    const val SQL = """
            SELECT
                s.sessionHex          AS sessionHex,
                s.startWallMs         AS startWallMs,
                s.zoneId              AS zoneId,
                r.paramsHash          AS paramsHash,
                r.rule                AS rule,
                r.maskSource          AS maskSource,
                r.gate                AS gate,
                r.independence        AS independence,
                r.plmi                AS plmi,
                r.plmiSpt             AS plmiSpt,
                r.fundamentalSec      AS fundamentalSec,
                r.rhythmValid         AS rhythmValid,
                r.periodicityIndex    AS periodicityIndex,
                r.missRate            AS missRate,
                r.analysableTstMin    AS analysableTstMin,
                s.analysableMin       AS analysableMin,
                s.truncated           AS truncated,
                s.revealedAtMs        AS revealedAtMs,
                CASE
                    WHEN c.nightKey IS NULL THEN 0
                    WHEN ref.refLeg IS NULL OR ref.refStrapId IS NULL THEN 0
                    WHEN c.leg <> ref.refLeg THEN 0
                    WHEN c.strapId <> ref.refStrapId THEN 0
                    WHEN c.aloneInBed = 0 THEN 0
                    WHEN s.gainCalG IS NULL OR ref.refGainCalG IS NULL OR ref.refGainCalG <= 0 THEN 0
                    WHEN abs(s.gainCalG - ref.refGainCalG) / ref.refGainCalG > 0.35 + 1e-9 THEN 0
                    WHEN s.analysableMin < 240.0 THEN 0
                    WHEN s.tzOffsetStartMin <> s.tzOffsetEndMin THEN 0
                    ELSE 1
                END AS comparable,
                CASE
                    WHEN c.nightKey IS NULL THEN 'NO_CONTEXT'
                    WHEN ref.refLeg IS NULL OR ref.refStrapId IS NULL THEN 'NO_CONTEXT'
                    WHEN c.leg <> ref.refLeg THEN 'LEG_CHANGED'
                    WHEN c.strapId <> ref.refStrapId THEN 'STRAP_CHANGED'
                    WHEN c.aloneInBed = 0 THEN 'NOT_ALONE'
                    WHEN s.gainCalG IS NULL OR ref.refGainCalG IS NULL OR ref.refGainCalG <= 0
                        THEN 'CAL_GAIN_UNKNOWN'
                    WHEN abs(s.gainCalG - ref.refGainCalG) / ref.refGainCalG > 0.35 + 1e-9
                        THEN 'CAL_GAIN_OUT_OF_TOLERANCE'
                    WHEN s.analysableMin < 240.0 THEN 'TOO_SHORT'
                    WHEN s.tzOffsetStartMin <> s.tzOffsetEndMin THEN 'DST_NIGHT'
                    ELSE 'OK'
                END AS exclusionReason
            FROM plm_result r
            JOIN night_session s ON s.sessionHex = r.sessionHex
            LEFT JOIN night_context c ON c.nightKey = s.nightKey
            LEFT JOIN (
                SELECT nc.leg AS refLeg, nc.strapId AS refStrapId, ns.gainCalG AS refGainCalG
                FROM night_context nc
                JOIN night_session ns ON ns.nightKey = nc.nightKey
                ORDER BY nc.sealedAtMs ASC
                LIMIT 1
            ) ref ON 1 = 1
    """
}
