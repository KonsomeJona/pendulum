package com.pendulum.phone.banc

import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.indices.Periodicity
import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.mask.MaskFusion
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.Stage
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth
import com.pendulum.algo.synth.SeriesSpec
import com.pendulum.algo.synth.SynthNight
import com.pendulum.algo.synth.truthAsClms
import com.pendulum.phone.work.AnalysisParams
import kotlin.random.Random

/**
 * La moitie **pure** de l'ensemencement : de quoi est faite une nuit du banc, et quel resultat
 * elle produit. Aucun Android, aucune base — c'est ce qui la rend verifiable sur JVM, et
 * `EnsemencementCampagneTest` s'en sert.
 *
 * ### Pourquoi cette separation existe, et ce qu'elle a rattrape
 *
 * La premiere version ecrivait ses neuf nuits en base sans que rien ne verifie ce qu'elles
 * valaient. Sur l'appareil, l'ecran Tendance est reste ferme : les nuits etaient bien
 * comparables et publiables, mais **l'ajustement du rythme etait refuse sur les neuf**, et
 * `TendanceUiState` exige au moins trois nuits dont le rythme est identifie. L'ensemencement
 * avait donc atteint tous ses buts intermediaires en manquant le seul qui comptait.
 *
 * Le refus n'etait pas un defaut : `Rhythm` refuse quand le train d'intervalles qu'on lui donne
 * n'identifie pas de periode, et une nuit ou des mouvements isoles et des RRLM s'intercalent entre
 * les series lui en donne un qui n'en identifie aucune. C'est mesure et documente
 * (`RhythmMeasurementTest` : 2 ajustements acceptes sur 20 nuits nominales). Il fallait donc
 * **choisir une nuit plus periodique**, ce que le generateur sait faire, et le prouver ailleurs
 * que sur un telephone.
 */
internal object Campagne {

    /** Nature d'une nuit. Les trois etats que `Mapping.nuitUi` sait rendre. */
    enum class Genre { ELIGIBLE, PROVISOIRE, ECARTEE }

    /** Frequence de la grille de reference de la verite terrain. Voir `synth.TARGET_FS_HZ`. */
    const val FS_HZ = 50.0

    /**
     * Graine de base. Fixe : deux ensemencements successifs produisent la meme campagne, donc deux
     * captures du meme ecran sont comparables. Une graine tiree de l'horloge rendrait toute
     * regression d'affichage indiscernable d'un changement de donnees.
     */
    private const val GRAINE = 20_260_807L

    /**
     * L'ordre des nuits, de la plus ancienne a la plus recente. Il n'est pas indifferent :
     *
     *  - la **premiere** nuit scellee sert de reference a tout le critere de comparabilite
     *    (`comparable_night` prend `MIN(sealedAtMs)`), donc elle doit etre ordinaire — si la
     *    campagne commencait par la nuit ou l'on n'etait pas seul au lit, toutes les suivantes
     *    seraient jugees contre elle ;
     *  - la **derniere** est celle dont parlent la bande d'etat du reveil et la carte d'accueil,
     *    donc elle est eligible elle aussi.
     */
    fun genres(eligibles: Int): List<Genre> = buildList {
        add(Genre.ELIGIBLE)
        add(Genre.ECARTEE)
        add(Genre.PROVISOIRE)
        repeat(eligibles - 1) { add(Genre.ELIGIBLE) }
    }

    fun graine(rang: Int): Long = GRAINE + rang * 7_919L

    /**
     * Une nuit du banc : ce que le generateur a produit, et ce que les etapes 6 et 7 de `:algo` en
     * tirent.
     *
     * @param mesures les deux resultats du masque Health Connect, un par jeu de regles. Il n'y en
     *   a pas quatre : le masque accelerometrique est le produit de la chaine de traitement du
     *   signal, precisement celle que cet ensemencement ne joue pas, et le fabriquer reviendrait a
     *   inventer un denominateur au lieu de le mesurer.
     */
    class Nuit(
        val genre: Genre,
        val synth: SynthNight,
        val analysableMin: Double,
        val masque: SleepMask,
        val clms: List<Clm>,
        val mesures: List<PlmiResult>,
    ) {
        val spec: NightSpec get() = synth.spec
        val dureeEnregistreeMin: Double get() = spec.recordedH * 60.0
        val tronquee: Boolean get() = synth.truth.truncatedAtMs != null

        /** Le resultat que l'interface lit : `AASM_V3` sur le masque Health Connect. */
        val principal: PlmiResult get() = mesures.first()
    }

    /**
     * Fabrique une nuit et la fait traverser les **memes** etapes 6 et 7 que la mesure reelle :
     * `Periodicity`, `Rhythm`, `SeriesBuilder` et `Plmi.compute` sont appeles exactement comme
     * `NightAnalyzer.computeOne` les appelle. Les invariants du resultat — la porte de publication,
     * l'independance du denominateur, le refus d'ajustement du rythme — sont donc ceux du produit.
     */
    fun nuit(genre: Genre, rang: Int, params: AnalysisParams = AnalysisParams.DEFAULT): Nuit {
        val graine = graine(rang)
        val synth = NightSynth.generate(recette(genre, Random(graine)), graine)
        val truth = synth.truth

        // Le temps reellement analysable : la duree enregistree moins les trous FIFO et les
        // periodes hors poignet. C'est la meme soustraction que fait la ligne de temps de `:algo` ;
        // elle est refaite ici parce que la ligne de temps n'est produite que par la chaine de
        // traitement du signal.
        val dureeMin = synth.spec.recordedH * 60.0
        val perduMin = truth.gaps.sumOf { it.durationSec } / 60.0 +
            truth.mask.windows.filter { it.stage == Stage.OUT_OF_BED }.sumOf { it.durationMin }
        val analysableMin = (dureeMin - perduMin).coerceAtLeast(0.0)
        val couverture = (analysableMin / dureeMin).coerceIn(0.0, 1.0)

        val masque = MaskFusion.fromHealthConnect(truth.mask.windows, couverture)
        val clms = truthAsClms(truth.accelLegMovements, truth.floorG.toFloat())
        val tronquee = truth.truncatedAtMs != null

        val pi = Periodicity.ferriIndex(clms, masque, FS_HZ, params.periodicity)
        val rythme = Rhythm.fromClms(clms, masque, params.rhythm)

        val mesures = listOf(SeriesConfig.aasmV3(), SeriesConfig.wasm2016()).map { cfg ->
            val serie = SeriesBuilder.buildDetailed(clms, masque, FS_HZ, cfg)
            Plmi.compute(
                clms = clms,
                series = serie.series,
                mask = masque,
                fsHz = FS_HZ,
                rule = cfg.rule,
                pi = pi,
                rhythm = rythme,
                floorMode = FloorMode.BILATERAL,
                truncated = tronquee,
                cfg = params.plmi,
                truncatedSeriesDropped = serie.truncatedSeriesDropped,
                paramsHash = params.paramsHash,
            )
        }

        return Nuit(genre, synth, analysableMin, masque, clms, mesures)
    }

    /**
     * Une nuit differente de la precedente, mais pas au point de sortir de la campagne.
     *
     * ### Les trois contraintes, et elles se contredisent
     *
     *  1. **Rester comparable.** `gainMultiplier` bouge de +/- 6 % seulement : la tolerance de la
     *     regle est de 35 %, mais c'est le jeu du bracelet qu'elle encaisse, pas une variation
     *     decidee. Et la duree enregistree reste au-dessus de quatre heures analysables, sous quoi
     *     la nuit sortirait `TOO_SHORT`.
     *  2. **Rester publiable.** Plus de quatre heures de sommeil analysable, sans quoi la porte
     *     tombe a `TRUNCATED_NO_TREND` — ce qui est l'etat qu'on veut pour la nuit provisoire, et
     *     seulement pour elle.
     *  3. **Porter un rythme identifiable.** C'est la contrainte qui a coute le plus cher, et
     *     celle qui explique les quatre distracteurs coupes ci-dessous. `Rhythm` ajuste un melange
     *     d'harmoniques sur les intervalles entre mouvements consecutifs ; les familles qui
     *     **intercalent des mouvements aperiodiques entre les series** — RRLM, salves, ALMA, et
     *     les isoles en trop grand nombre — fabriquent des intervalles qui n'appartiennent a aucun
     *     harmonique, la distance en variation totale explose, et l'ajustement est refuse
     *     (`GEOMETRIC_MISFIT`). Les couper ne rend pas la nuit irrealiste : elle decrit un dormeur
     *     dont les mouvements periodiques dominent, ce qui est exactement le cas clinique que le
     *     produit mesure. Les huit autres familles restent, dont les changements de posture, les
     *     mouvements corporels grossiers, la respiration, le matelas, les trous et le hors-poignet.
     *
     * `minIntervals` de `RhythmConfig` vaut 30 : il faut donc au moins trente intervalles retenus
     * dans le sommeil, d'ou un nombre de series et une longueur de serie genereux.
     */
    fun recette(genre: Genre, r: Random): NightSpec {
        val duree = 7.4 + r.nextDouble() * 1.2
        val base = NightSpec(
            durationH = duree,
            trueSeries = listOf(
                SeriesSpec(
                    nSeries = 20 + r.nextInt(10),
                    clmPerSeries = 7 + r.nextInt(5),
                    imiMeanSec = 19.0 + r.nextDouble() * 6.0,
                    imiCvPct = 14.0 + r.nextDouble() * 8.0,
                )
            ),
            isolatedClmPerHour = 0.5 + r.nextDouble() * 1.5,
            gainMultiplier = 0.94 + r.nextDouble() * 0.12,
            distractors = DistractorSpec.ALL.copy(
                rrlmSeriesCount = 0,
                clusterCount = 0,
                almaCountMin = 0,
                almaCountMax = 0,
            ),
        )
        return when (genre) {
            // La nuit provisoire est une nuit **tronquee** : la montre s'est arretee avant le
            // reveil. Elle reste comparable — meme jambe, meme bracelet, plus de quatre heures
            // analysables — mais sa porte de publication tombe a `TRUNCATED_NO_TREND`, parce que
            // l'index d'une nuit tronquee est biaise a la hausse sans correction possible. C'est
            // exactement le troisieme etat de `Mapping.nuitUi`.
            Genre.PROVISOIRE -> base.copy(truncateAtH = duree * 0.72)
            Genre.ELIGIBLE, Genre.ECARTEE -> base
        }
    }
}
