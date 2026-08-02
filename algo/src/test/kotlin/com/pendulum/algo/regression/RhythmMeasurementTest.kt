package com.pendulum.algo.regression

import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.indices.RhythmReject
import com.pendulum.algo.synth.GroundTruth
import com.pendulum.algo.synth.Scoring
import com.pendulum.algo.synth.SynthRandom
import com.pendulum.algo.synth.TruthKind
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le rythme fondamental sous un fort taux de manques — la mesure qui decide si le produit tient.
 *
 * **Pourquoi ce fichier existe.** T22 publie que 6 % seulement de l'`aPLM-i` vrai survit a la
 * politique de seuil. Cela n'invaliderait pas Pendulum si l'`aPLM-i` etait une grandeur secondaire :
 * la metrique de suivi annoncee est le **rythme fondamental en secondes**, recupere par
 * deconvolution harmonique (`Rhythm`), et tout son interet tient a ce qu'elle n'a **pas** de
 * denominateur et varie douze fois moins d'une nuit a l'autre que le compte horaire.
 *
 * Mais la deconvolution a une limite d'identifiabilite que sa propre KDoc enonce : les composantes
 * du melange sont separees de `ln 2` nats, et le poids du fondamental vaut `1 - p`. A `p = 0,39`
 * — le cas pour lequel elle a ete concue — le fondamental pese encore 61 %. A `p = 0,70`, il ne pese
 * plus que 30 %, le premier harmonique 21 %, le second 15 % ; si `sigma` est large, les cloches
 * fusionnent en une trainee continue et l'EM peut accrocher le mauvais pic. **Personne n'avait
 * mesure ce qui se passe a ce taux-la**, et c'est exactement le taux ou le detecteur travaille.
 *
 * Deux mesures, aucune assertion de qualite. Elles impriment, comme le balayage de `k_on` : ce sont
 * des chiffres pour decider, et la decision — changer `calFraction`, changer de regle de serie,
 * changer de metrique publiee — est clinique et produit, pas une correction de code.
 */
class RhythmMeasurementTest {

    /**
     * Mesure 1 — la deconvolution telle qu'elle tourne reellement, sur la nuit nominale.
     *
     * Quatre grandeurs, dans l'ordre d'importance :
     *
     *  1. **l'erreur relative sur `fundamentalSec`** contre le rythme reellement injecte. C'est LE
     *     chiffre : si le rythme tient sous 70 % de manques, l'effondrement de l'indice est un
     *     probleme de metrique secondaire ; s'il ne tient pas, c'est le produit ;
     *  2. **`missRate` rendu** contre les deux taux vrais. Il y en a deux parce qu'il y a deux
     *     trains de reference : le train **EMG** (tous les mouvements de serie generes — l'echelle
     *     clinique, et celle que la KDoc de `Rhythm` vise explicitement en citant les 39 % de
     *     Terrill) et le train **accelerometrique** (ceux qui deplacent le capteur). Les rapporter
     *     tous les deux evite de declarer `p` juste ou faux selon la reference qu'on choisit ;
     *  3. **l'adequation** — `geometricMisfit`, `ksStatistic` — et le nombre de graines que le
     *     module **invalide**. Sa KDoc affirme qu'il prefere invalider plutot que rendre un chiffre
     *     faux avec l'air sur. C'est une affirmation verifiable, et elle n'avait pas ete verifiee a
     *     ce taux de manques ;
     *  4. `alternationSuspect`, dont le seuil est a `p >= 0,48` : a 70 % de manques mecaniques il
     *     devrait se lever tout le temps, et dire alors quelque chose de faux.
     */
    @Test
    @DisplayName("Rythme — deconvolution sur la nuit nominale : fondamental, missRate, adequation")
    fun rhythmOnTheNominalNightUnderTheRealMissRate() {
        val fundErr = ArrayList<Double>()
        val fundamental = ArrayList<Double>()
        val injected = ArrayList<Double>()
        val missEstimated = ArrayList<Double>()
        val missTrueEmg = ArrayList<Double>()
        val missTrueAccel = ArrayList<Double>()
        val misfit = ArrayList<Double>()
        val ks = ArrayList<Double>()
        val sigma = ArrayList<Double>()
        val intervals = ArrayList<Double>()
        var valid = 0
        var alternation = 0
        val rejects = HashMap<RhythmReject, Int>()

        for (seed in SEEDS) {
            val night = nominalNight(seed)
            val a = analyse(night)
            val fit = a.rhythmFit()
            val r = fit.result

            val trueFund = injectedFundamentalSec(night.truth)
            injected.add(trueFund)
            fundamental.add(r.fundamentalSec)
            // L'erreur est rapportee meme quand le resultat est invalide : savoir de combien un
            // chiffre refuse se serait trompe est ce qui dit si le refus etait utile.
            fundErr.add(if (trueFund > 0.0) abs(r.fundamentalSec - trueFund) / trueFund else Double.NaN)

            val emgSeries = night.truth.emgTruth.filter { it.kind == TruthKind.PLM_IN_SERIES }
            val accelSeries = night.truth.accelTruth.filter { it.kind == TruthKind.PLM_IN_SERIES }
            missTrueEmg.add(1.0 - Scoring.match(a.retained, emgSeries).sensitivity)
            missTrueAccel.add(1.0 - Scoring.match(a.retained, accelSeries).sensitivity)

            missEstimated.add(r.missRate)
            misfit.add(fit.geometricMisfit)
            ks.add(fit.ksStatistic)
            sigma.add(r.sigmaLog)
            intervals.add(r.intervalsUsed.toDouble())
            if (r.valid) valid++
            if (r.alternationSuspect) alternation++
            fit.reject?.let { rejects[it] = (rejects[it] ?: 0) + 1 }
        }

        val out = StringBuilder()
        out.append("\n=== Rythme sur la nuit nominale, ").append(SEEDS.size).append(" graines ===\n")
        out.append(line("fondamental injecte, s", injected))
        out.append(line("fondamental estime, s", fundamental))
        out.append(line("erreur relative", fundErr))
        out.append(line("missRate estime", missEstimated))
        out.append(line("missRate vrai / train EMG", missTrueEmg))
        out.append(line("missRate vrai / train accel", missTrueAccel))
        out.append(line("sigmaLog estime", sigma))
        out.append(line("geometricMisfit", misfit))
        out.append(line("ksStatistic", ks))
        out.append(line("intervalles utilises", intervals))
        out.append("valides : ").append(valid).append(" / ").append(SEEDS.size)
        out.append(" ; alternationSuspect : ").append(alternation).append(" / ").append(SEEDS.size).append("\n")
        out.append("motifs de refus : ").append(if (rejects.isEmpty()) "aucun" else rejects.toString()).append("\n")
        println(out)

        // Garde-fou de scenario, pas de qualite : si la deconvolution ne recevait plus aucun
        // intervalle, tout ce qui precede serait du vide et le tableau serait trompeur. La borne est
        // volontairement tres basse — le fait que la mediane frole `RhythmConfig.minIntervals` (30)
        // est justement l'un des resultats, pas une condition de validite de la mesure.
        assertThat(medianOf(intervals))
            .`as`("intervalles medians fournis a la deconvolution")
            .isGreaterThan(10.0)

        // Assertion **inversee**, meme esprit que T11 : sur la nuit nominale la grande majorite des
        // ajustements est refusee. Ce n'est pas un echec du module, c'est son contrat qui s'applique.
        // Le jour ou cette assertion echoue, soit la chaine detecte enfin assez de mouvements, soit
        // le garde-fou a ete relache — les deux exigent de rouvrir §4.3 de `docs/07-validation.md`.
        assertThat(valid)
            .`as`("ajustements de rythme declares valides sur la nuit nominale")
            .isLessThanOrEqualTo(SEEDS.size / 4)
    }

    /**
     * Mesure 2 — ou la deconvolution lache, en fonction du taux de manques impose.
     *
     * Le detecteur n'intervient pas ici : on prend le **train vrai** de la nuit nominale, on l'eclaircit
     * avec une probabilite imposee, et on ajuste. C'est le meilleur cas absolu du modele — les
     * manques y sont exactement independants et geometriques, ce qu'ils ne sont jamais en vrai,
     * puisque l'accelerometre rate d'abord les faibles amplitudes. **Une degradation observee ici
     * est donc un plancher sur la degradation reelle, pas une estimation de celle-ci.**
     *
     * C'est aussi ce qui rend la mesure interpretable : elle isole l'identifiabilite du melange de
     * tout le reste de la chaine, et elle est presque gratuite — aucune detection, aucun signal.
     */
    @Test
    @DisplayName("Rythme — courbe de rupture : taux de manques imposes 0,1 / 0,3 / 0,5 / 0,7 sur le train vrai")
    fun rhythmAgainstImposedMissRateOnTheTrueTrain() {
        val rates = doubleArrayOf(0.0, 0.1, 0.3, 0.5, 0.7)
        val out = StringBuilder()
        out.append("\n=== Deconvolution sur le train vrai eclairci, ").append(SEEDS.size)
        out.append(" graines, medianes ===\n")
        out.append(
            String.format(
                Locale.ROOT, "%6s %10s %10s %10s %10s %10s %8s %8s%n",
                "p", "err.rel", "p estime", "sigma", "misfit", "ks", "valides", "altern.",
            ),
        )

        val err = rates.map { ArrayList<Double>() }
        val pEst = rates.map { ArrayList<Double>() }
        val sig = rates.map { ArrayList<Double>() }
        val mis = rates.map { ArrayList<Double>() }
        val kss = rates.map { ArrayList<Double>() }
        val valid = IntArray(rates.size)
        val alternation = IntArray(rates.size)

        // Graine a l'exterieur, taux a l'interieur : la nuit ne depend pas du taux, et la regenerer
        // cinq fois couterait quatre generations de 8 h pour rien.
        for (seed in SEEDS) {
            val night = nominalNight(seed)
            val trueFund = injectedFundamentalSec(night.truth)
            val onsets = night.truth.emgLegMovements.map { it.onsetMsRel }.sorted()

            for ((k, rate) in rates.withIndex()) {
                // Flot nomme : eclaircir a 0,3 ne doit pas dependre des tirages faits pour 0,1,
                // sinon on comparerait cinq trains differents en croyant comparer cinq taux.
                val rnd = SynthRandom(seed).stream("thin-$rate")
                val kept = onsets.filter { rate <= 0.0 || rnd.nextBoolean(1.0 - rate) }
                val imi = DoubleArray((kept.size - 1).coerceAtLeast(0)) {
                    (kept[it + 1] - kept[it]) / 1000.0
                }
                val fit = Rhythm.fit(imi)
                val r = fit.result
                err[k].add(if (trueFund > 0.0) abs(r.fundamentalSec - trueFund) / trueFund else Double.NaN)
                pEst[k].add(r.missRate)
                sig[k].add(r.sigmaLog)
                mis[k].add(fit.geometricMisfit)
                kss[k].add(fit.ksStatistic)
                if (r.valid) valid[k]++
                if (r.alternationSuspect) alternation[k]++
            }
        }

        for ((k, rate) in rates.withIndex()) {
            out.append(
                String.format(
                    Locale.ROOT, "%6.2f %10.3f %10.3f %10.3f %10.3f %10.3f %8s %8s%n",
                    rate, medianOf(err[k]), medianOf(pEst[k]), medianOf(sig[k]), medianOf(mis[k]),
                    medianOf(kss[k]), "${valid[k]}/${SEEDS.size}", "${alternation[k]}/${SEEDS.size}",
                ),
            )
        }
        out.append("\nManques imposes independants et geometriques : c'est le meilleur cas du modele.\n")
        println(out)

        val last = rates.size - 1
        // Deux assertions **inversees**, dans l'esprit de T11 : elles affirment qu'un defaut mesure
        // est encore la, pour qu'on soit prevenu le jour ou il ne l'est plus.
        //
        // 1. L'erreur sur le fondamental empire nettement entre 0,5 et 0,7 de manques. C'est la
        //    limite d'identifiabilite que la KDoc de `Rhythm` annonce sans la chiffrer, et le
        //    detecteur travaille au-dela.
        assertThat(medianOf(err[last]))
            .`as`("erreur sur le fondamental a 70 %% de manques, contre %.3f a 30 %%", medianOf(err[2]))
            .isGreaterThan(2.0 * medianOf(err[2]))
        // 2. La statistique KS **descend** quand le taux de manques monte, c'est-a-dire que la mesure
        //    d'adequation s'ameliore pendant que l'estimation se degrade. Un garde-fou anticorrele a
        //    l'erreur qu'il garde ne peut pas servir de critere de confiance. **Si cette assertion
        //    echoue un jour, l'adequation est devenue informative** — et §4.3 de
        //    `docs/07-validation.md`, qui est ecrit sur cette mesure, doit etre refait.
        assertThat(medianOf(kss[last]))
            .`as`("KS a 70 %% de manques, contre %.3f sans manque", medianOf(kss[0]))
            .isLessThan(medianOf(kss[0]))
    }
}

/**
 * Rythme fondamental **reellement injecte** cette nuit-la : moyenne geometrique des intervalles
 * onset-a-onset entre mouvements consecutifs d'une meme serie, a l'echelle EMG.
 *
 * Mesure plutot que lue dans `NightSpec.imiMeanSec` : la loi est tronquee a [2 ; 120] s et les series
 * sont placees dans des creneaux, si bien que la valeur realisee n'est pas exactement la valeur
 * demandee. Comparer l'estimation a une consigne plutot qu'a la realisation ferait porter a la
 * deconvolution une erreur qui n'est pas la sienne.
 *
 * La moyenne geometrique, et non arithmetique, parce que `fundamentalSec = exp(mu)` est la
 * **mediane** de la log-normale ajustee : c'est la meme grandeur des deux cotes de la comparaison.
 */
private fun injectedFundamentalSec(truth: GroundTruth): Double {
    val logs = ArrayList<Double>()
    truth.emgTruth
        .filter { it.kind == TruthKind.PLM_IN_SERIES && it.seriesId != null }
        .groupBy { it.seriesId }
        .forEach { (_, events) ->
            val ordered = events.sortedBy { it.onsetMsRel }
            for (i in 1 until ordered.size) {
                val d = (ordered[i].onsetMsRel - ordered[i - 1].onsetMsRel) / 1000.0
                if (d > 0.0) logs.add(ln(d))
            }
        }
    return if (logs.isEmpty()) Double.NaN else exp(logs.sum() / logs.size)
}

private fun line(label: String, values: List<Double>): String = String.format(
    Locale.ROOT, "%-30s mediane %8.3f   [%8.3f ; %8.3f]%n",
    label, medianOf(values), worstMin(values), worstMax(values),
)
