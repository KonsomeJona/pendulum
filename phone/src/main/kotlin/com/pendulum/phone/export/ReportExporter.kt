package com.pendulum.phone.export

import android.content.Context
import android.content.res.Resources
import com.pendulum.phone.data.EtatTendance
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.text.resoudre
import com.pendulum.phone.work.WorkScheduler
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Le rapport, destine a etre lu par un medecin du sommeil.
 *
 * ### Pourquoi il est en anglais alors qu'il l'etait en francais
 *
 * Il l'etait, et c'etait une incoherence et non une decision : toute l'interface est en anglais
 * depuis que le depot est public (voir la KDoc de `TextesTest`, dont les racines proscrites sont
 * anglaises pour cette raison), et ce document est **la seule chose que l'application produise
 * pour un tiers**. Un rapport ecrit dans une langue que son porteur ne lit pas ne peut pas etre
 * relu avant d'etre remis, donc ne peut pas etre corrige ni assume par celui qui le remet — or
 * tout ce fichier repose sur l'idee qu'un document circule sans son contexte et doit donc porter
 * ses propres limites. Le document suit desormais la langue de l'interface qui l'a produit.
 *
 * La KDoc, elle, reste en francais sans accents : c'est la langue de travail du projet, et elle
 * s'adresse a qui lit le code, pas a qui lit le rapport.
 *
 * ### Le cadrage, ecrit dans le rapport lui-meme et pas seulement dans la documentation
 *
 * L'utilisateur suit **deja** un traitement. Sans periode de reference sans traitement, ce
 * dispositif ne peut pas mesurer l'effet du traitement : il mesure la variabilite sous
 * traitement. Le seul objectif defendable est d'**obtenir un examen reel**, pas de le remplacer,
 * et aucune dose ne s'ajuste sur ce chiffre. Cette phrase est dans le rapport parce qu'un
 * rapport circule sans son contexte.
 *
 * ### Ce que le rapport montre, dans cet ordre
 *
 * 1. le **rythme fondamental** en secondes et la periodicite — la metrique de suivi, sans
 *    denominateur, donc sans circularite, et douze fois plus stable d'une nuit a l'autre. Il est
 *    souvent **absent**, et le rapport dit alors pourquoi : le modele refuse d'ajuster une periode
 *    que les intervalles n'identifient pas, et il refuse bien plus souvent qu'il n'accepte ;
 * 2. le **compte horaire** avec son denominateur explicite, ses deux jeux de regles et ses deux
 *    masques — c'est la langue des somnologues, et les seuils publies reposent dessus — precede
 *    des trois chiffres qui disent **a quelle echelle** il se lit ;
 * 3. le **taux de manques estime**, sans lequel les deux precedents ne se lisent pas ;
 * 4. la qualite et les limites, nommees separement et jamais compensees entre elles.
 *
 * ### Ce que le rapport ne fait pas
 *
 * Il n'ecrit aucun verbe d'evolution (« ameliore », « aggrave », « diminue »). Une variation
 * entre deux nuits sous le seuil de plus petite variation detectable n'est pas une evolution,
 * c'est de la variabilite nuit a nuit — et la variabilite du compte horaire est de l'ordre de
 * 43 % de sa moyenne. Le rapport donne des valeurs et des bornes ; l'interpretation appartient
 * au lecteur.
 *
 * ### Deux documents, et pas un
 *
 * [exportNight] rend compte d'**une** nuit : c'est le document d'une anomalie, celui qu'on sort
 * quand une nuit precise pose question. [exportCampagne] rend compte de **la campagne**, avec ses
 * agregats, leur incertitude et leur `n` — c'est celui qu'on pose devant un medecin, parce
 * qu'une nuit isolee ne signifie rien et que le produit refuse d'agreger sous trois nuits.
 */
object ReportExporter {

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.UK).withZone(ZoneId.systemDefault())

    // -------------------------------------------------------------------------------------
    // Le rapport de campagne — celui qu'on pose devant un medecin
    // -------------------------------------------------------------------------------------

    /**
     * Le rapport de la campagne, construit a partir de l'etat **deja calcule** de la tendance.
     *
     * Il ne recalcule rien : les medianes, les intervalles bootstrap a graine deterministe et la
     * plus petite variation detectable viennent de `Aggregat` par le meme chemin que l'ecran. Un
     * rapport qui recalculerait de son cote pourrait afficher un autre chiffre que celui qui est
     * a l'ecran, et c'est exactement l'ecart qu'on ne saurait pas expliquer devant un medecin.
     *
     * @param inclureEcartees quand il est faux, le tableau ne porte pas les nuits ecartees — mais
     *   leur nombre reste ecrit. Retirer des nuits d'un document medical sans dire combien serait
     *   choisir ses preuves en silence.
     */
    suspend fun exportCampagne(
        context: Context,
        etat: EtatTendance,
        inclureQuestionnaire: Boolean,
        inclureEcartees: Boolean,
        out: OutputStream,
    ) {
        val db = PendulumDatabase.get(context)
        val params = WorkScheduler.activeParams(context)
        // Le rapport lit exactement les memes chaines que l'ecran : une seconde mise en forme
        // quelque part serait une seconde verite sur le meme chiffre.
        val res = context.resources
        val zone = runCatching { ZoneId.of(etat.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val nuits = etat.nuits.sortedBy { it.startWallMs }
        val questionnaires = if (inclureQuestionnaire) db.questionnaireDao().all() else emptyList()

        val text = buildString {
            appendLine("# Pendulum — report for the physician")
            appendLine()
            appendLine(PREAMBLE)
            appendLine()

            appendLine("## The campaign")
            appendLine()
            if (nuits.isEmpty()) {
                appendLine("No night recorded.")
            } else {
                appendLine("- Period: ${nuits.first().dateLisible} → ${nuits.last().dateLisible}")
            }
            appendLine(
                "- Nights recorded: ${etat.nuitsEnregistrees} · eligible: ${etat.nuitsEligibles} " +
                    "· excluded: ${etat.nuitsEcartees}"
            )
            etat.profilPersonnalise?.let {
                appendLine("- Non-default parameter profile in use: `$it`")
            }
            if (etat.hashsMelanges) {
                appendLine("- **Two parameter sets are present across the recorded nights.** " +
                    "Only the nights of the active set enter the figures below.")
            }
            appendLine()

            appendLine("## Follow-up metric — fundamental rhythm")
            appendLine()
            val rythme = etat.rythme
            if (rythme == null) {
                appendLine(
                    "Not reported: ${etat.nuitsEligibles} eligible night(s) and " +
                        "${etat.nuitsRythmeAjuste} of them with an accepted rhythm fit, for " +
                        "${Aggregat.MIN_NUITS_AGREGAT} required."
                )
                appendLine()
                appendLine(RYTHME_REFUSE)
            } else {
                appendLine(ligneAgregat(rythme, "s"))
                appendLine("- Night-to-night dispersion: ${fmt(rythme.dispersion)} s")
                appendLine("- Smallest detectable change (95 %): ${fmt(rythme.mdc95)} s")
                appendLine()
                appendLine(RYTHME_SANS_SEUIL)
            }
            appendLine()

            appendLine("## Hourly count — aPLM-i")
            appendLine()
            appendLine(APLMI_NOTE)
            appendLine()
            appendLine(SOUS_COMPTAGE)
            appendLine()
            val compte = etat.compte
            if (compte == null) {
                appendLine(
                    "Not reported: ${etat.nuitsEligibles} eligible night(s) for " +
                        "${Aggregat.MIN_NUITS_AGREGAT} required."
                )
            } else {
                appendLine(ligneAgregat(compte, "/h"))
                appendLine("- Night-to-night dispersion: ${fmt(compte.dispersion)} /h")
                appendLine("- Smallest detectable change (95 %): ${fmt(compte.mdc95)} /h")
                appendLine()
                // La phrase de position est celle de l'ecran, mot pour mot. Il n'en existe que
                // cinq dans toute l'application, et aucune ne dit une direction.
                Aggregat.position(compte.ciBas, compte.ciHaut, compte.nuits).phrase()
                    ?.let { appendLine(it.resoudre(res)) }
            }
            appendLine()

            appendLine("## Estimated miss rate and periodicity")
            appendLine()
            appendLine("- Median estimated miss rate: ${fmt(etat.tauxManquesMedian)}")
            appendLine("- Median periodicity index: ${fmt(etat.periodiciteMediane)}")
            appendLine()
            appendLine(MANQUES_NOTE)
            appendLine()

            appendLine("## Night by night")
            appendLine()
            appendLine("| Date | From → to | Analysable sleep | Sleep source | Rhythm (s) | aPLM-i (/h) | State | Reason |")
            appendLine("|---|---|---|---|---|---|---|---|")
            val retenues = if (inclureEcartees) nuits else nuits.filter { it.etat != EtatNuit.ECARTEE }
            for (n in retenues) appendLine(ligneNuit(n, res))
            appendLine()
            if (!inclureEcartees && etat.nuitsEcartees > 0) {
                appendLine(
                    "**${etat.nuitsEcartees} excluded night(s) are not listed above**, at the " +
                        "request of the person who produced this document. They are counted in " +
                        "the campaign totals and were never part of the aggregates."
                )
                appendLine()
            }
            appendLine(EXCLUSION_NOTE)
            appendLine()

            if (inclureQuestionnaire) {
                appendLine("## Screening questionnaire")
                appendLine()
                if (questionnaires.isEmpty()) {
                    appendLine("Not filled in.")
                } else {
                    for (q in questionnaires) {
                        appendLine("- ${format(q.answeredAtMs, zone)} · `${q.kind}` · ${q.answersJson}")
                    }
                }
                appendLine()
                appendLine(QUESTIONNAIRE_NOTE)
                appendLine()
            }

            appendLine("## Traceability")
            appendLine()
            appendLine("- Parameter set: `${params.paramsHash}` · algorithm version: `${params.algoVersion}`")
            appendLine("- Nights whose result was never asked for: " +
                nuits.count { it.devoileeAtMs == null })
            appendLine("- Report generated on ${format(System.currentTimeMillis(), zone)}")
            appendLine()
            appendLine(LIMITS)
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // -------------------------------------------------------------------------------------
    // Le rapport d'une nuit
    // -------------------------------------------------------------------------------------

    suspend fun exportNight(context: Context, sessionHex: String, out: OutputStream) {
        val db = PendulumDatabase.get(context)
        val params = WorkScheduler.activeParams(context)
        // Le rapport lit exactement les memes chaines que l'ecran : une seconde mise en forme
        // quelque part serait une seconde verite sur le meme chiffre.
        val res = context.resources
        val session = db.nightDao().find(sessionHex) ?: error("session inconnue : $sessionHex")
        val results = db.derivedDao().resultsOf(sessionHex, params.paramsHash)
        val nightContext = db.contextDao().findForSession(sessionHex)
        val hc = db.hcSnapshotDao().latest(sessionHex)
        val comparable = db.trendDao().forNight(sessionHex, params.paramsHash).firstOrNull()

        val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val text = buildString {
            appendLine("# Pendulum — night of ${format(session.startWallMs, zone)}")
            appendLine()
            appendLine(PREAMBLE)
            appendLine()
            appendLine(UNE_NUIT_SEULE)
            appendLine()

            appendLine("## Follow-up metric")
            appendLine()
            val primary = results.firstOrNull { it.maskSource == "HEALTH_CONNECT" }
                ?: results.firstOrNull()
            if (primary == null) {
                appendLine("No result computed for this night.")
            } else {
                if (primary.rhythmValid) {
                    appendLine("- Fundamental rhythm: ${fmt(primary.fundamentalSec)} s")
                } else {
                    appendLine("- Fundamental rhythm: **not reported for this night**")
                    appendLine()
                    appendLine(RYTHME_REFUSE)
                    appendLine()
                }
                appendLine("- Periodicity index: ${fmt(primary.periodicityIndex)}" +
                    if (primary.periodicityValid) "" else " (not valid: too few intervals)")
                appendLine("- Estimated miss rate: ${fmt(primary.missRate)}")
                if (primary.alternationSuspect) {
                    appendLine(
                        "- Harmonic profile consistent with **left/right alternation**: a " +
                            "one-sided sensor then sees a doubled interval."
                    )
                }
            }
            appendLine()

            appendLine("## Hourly count — aPLM-i")
            appendLine()
            appendLine(APLMI_NOTE)
            appendLine()
            appendLine(SOUS_COMPTAGE)
            appendLine()
            appendLine("| Rules | Mask | Movements | Analysable sleep | aPLM-i | Respiratory upper bound | Publication |")
            appendLine("|---|---|---|---|---|---|---|")
            for (r in results.sortedWith(compareBy({ it.maskSource }, { it.rule }))) {
                appendLine(row(r))
            }
            appendLine()

            appendLine("## Denominator")
            appendLine()
            if (hc?.selectedPackage != null) {
                appendLine("- Hypnogram source: `${hc.selectedPackage}`")
                appendLine("- Distinct stages: ${hc.distinctStageTypes} · segments: ${hc.stageCount}")
                appendLine("- Coverage by stages: ${fmt(hc.stageCoverageMin)} min")
                hc.aggregateTstMin?.let {
                    appendLine("- Cross-check with `aggregate()` (de-duplicated by the system): ${fmt(it)} min")
                }
                appendLine("- Last modified on the provider side: ${hc.lastModifiedTimeMs?.let { format(it, zone) } ?: "unknown"}")
            } else {
                appendLine(NO_HC_NOTE)
            }
            appendLine()

            appendLine("## Recording quality")
            appendLine()
            appendLine("- Analysable duration: ${fmt(session.analysableMin)} min")
            appendLine("- Gaps: ${session.gapCount} (${session.gapTotalMs / 1000} s in total)")
            appendLine("- Measured sampling rate: ${session.fsMeasuredHz?.let { fmt(it) } ?: "?"} Hz")
            appendLine("- Blocks rejected by the integrity check: ${fmt(session.integrityRejectedFraction * 100)} %")
            if (session.truncated) appendLine("- **Truncated night**: index biased upwards, kept out of the trend.")
            session.batteryPctLast?.let { appendLine("- Last watch battery level reported: $it %") }
            session.stopReason?.let { appendLine("- Stop reason announced: $it") }
            appendLine()

            appendLine("## Comparability")
            appendLine()
            if (comparable == null) {
                appendLine("Not assessed (no result for the current parameter set).")
            } else if (comparable.comparable) {
                appendLine("Night comparable with the other nights of the campaign.")
            } else {
                appendLine("Night **kept out of the trend**, reason: `${comparable.exclusionReason}`.")
                appendLine()
                appendLine(EXCLUSION_NOTE)
            }
            appendLine()

            appendLine("## Evening context (sealed before the recording)")
            appendLine()
            if (nightContext == null) {
                appendLine("No context sealed for this night.")
            } else {
                appendLine("- Sealed on: ${format(nightContext.sealedAtMs, zone)}")
                appendLine("- Leg: ${nightContext.leg} · strap: ${nightContext.strapId}")
                appendLine("- Alone in bed: ${if (nightContext.aloneInBed) "yes" else "no"}")
                appendLine("- Medication: ${nightContext.medicationJson}")
                appendLine("- Alcohol: ${nightContext.alcoholUnits} unit(s) · caffeine after 16:00: " +
                    if (nightContext.caffeineAfter16h) "yes" else "no")
                nightContext.notes?.let { appendLine("- Notes: $it") }
            }
            appendLine()

            appendLine("## Traceability")
            appendLine()
            appendLine("- Parameter set: `${params.paramsHash}` · algorithm version: `${params.algoVersion}`")
            appendLine("- Report generated on ${format(System.currentTimeMillis(), zone)}")
            appendLine("- Result asked for on: " +
                (session.revealedAtMs?.let { format(it, zone) } ?: "never"))
            appendLine()
            appendLine(LIMITS)
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // -------------------------------------------------------------------------------------

    private fun row(r: PlmResultEntity): String =
        "| ${r.rule} | ${r.maskSource} | ${r.plmsCount} | ${fmt(r.analysableTstMin)} min | " +
            "${fmt(r.plmi)} /h | ${fmt(r.plmiRespWorstCase)} /h | ${r.gate} |"

    private fun ligneNuit(n: NuitUi, res: Resources): String = listOf(
        n.dateLisible,
        "${n.debut} → ${n.fin}",
        n.sommeilLisible,
        n.sourceSommeil.resoudre(res),
        n.rythmeSec?.let { fmt(it) } ?: "—",
        fmt(n.comptePlmi),
        when (n.etat) {
            EtatNuit.ELIGIBLE -> "eligible"
            EtatNuit.PROVISOIRE -> "provisional"
            EtatNuit.ECARTEE -> "excluded"
        },
        n.motif?.resoudre(res) ?: "",
    ).joinToString(" | ", prefix = "| ", postfix = " |")

    /**
     * Une mediane ne sort jamais sans son intervalle ni son `n` : c'est P2, et le rapport est le
     * dernier endroit ou l'on pourrait etre tente de ne garder que le chiffre rond.
     */
    private fun ligneAgregat(r: Aggregat.Resultat, unite: String): String {
        val etiquette = if (r.icCalibre) "95 % CI" else "interval (not calibrated below ${Aggregat.MIN_NUITS_IC_CALIBRE} nights)"
        return "- Median: ${fmt(r.mediane)} $unite — $etiquette ${fmt(r.ciBas)}–${fmt(r.ciHaut)} $unite, " +
            "n = ${r.nuits} nights"
    }

    /**
     * `21,34` en francais et `21.34` en anglais **ne sont pas le meme document**, et un rapport
     * qu'un medecin recopie ou qu'un tableur relit doit avoir une seule ponctuation decimale.
     * Le reste du module epingle deja `Locale.UK` partout — `Mapping`, `Controles`, `PorteP1`,
     * `PorteP1Exporter`, dont la KDoc documente precisement ce piege ; ce fichier etait le seul a
     * l'avoir manque, et il est celui qui sort de l'application.
     */
    /**
     * `18.40`, ou le tiret. Il accepte `null` depuis que les taux sans denominateur sont
     * `NULL` en base plutot que `NaN` : les deux disent la meme chose — la grandeur n'existe pas
     * pour cette nuit — et le rapport medical doit le dire plutot que d'ecrire `0.00`.
     */
    private fun fmt(v: Double?): String =
        if (v != null && v.isFinite()) "%.2f".format(Locale.UK, v) else "—"

    private fun format(ms: Long, zone: ZoneId): String = stamp.withZone(zone).format(Instant.ofEpochMilli(ms))

    private val PREAMBLE = """
        This document is produced by a personal device that has never been clinically validated:
        the accelerometer of a consumer smartwatch worn at the ankle. It replaces no examination.

        The person measured is already under treatment. Without a reference period without
        treatment, this device cannot measure the effect of that treatment — it measures the
        night-to-night variability under treatment. No dose should be adjusted from these figures.
    """.trimIndent()

    /**
     * Le rapport d'une seule nuit dit qu'il est le rapport d'une seule nuit.
     *
     * C'est le seul des deux documents qui peut etre lu comme un resultat, et il ne doit pas
     * l'etre : la variabilite nuit a nuit du compte horaire est de l'ordre de 43 % de sa moyenne,
     * donc une nuit prise seule ne situe rien. Le produit refuse d'agreger sous trois nuits ; un
     * document d'une nuit qui ne le rappellerait pas contournerait ce refus par la porte de
     * derriere.
     */
    private val UNE_NUIT_SEULE = """
        **This is the report of a single night, and a single night situates nothing.** The
        night-to-night variability of the hourly count is of the order of 43 % of its mean, so the
        figures below describe this night and not the person. Pendulum computes no aggregate below
        three eligible nights; the campaign report is the document meant to be read as a whole.
    """.trimIndent()

    private val APLMI_NOTE = """
        The metric is called **aPLM-i** — "ankle periodic limb movement index, estimated, not
        validated" — and not PLMI. This is not a wording precaution: 39 % of the movements scored
        by electromyography come with no movement detectable by accelerometry (a pure
        dorsiflexion does not displace a sensor sitting above the joint axis). The accelerometric
        count is therefore on a **different scale** from the polysomnographic count, and the
        published threshold of 15/h does not transpose onto it.

        That 39 % comes from Terrill et al. (2013), on nine subjects, with a between-subject range
        of 4.8 to 69.6 %, and it was **measured at the great toe**. An ankle-worn sensor sits above
        the joint axis and moves less than the toe does, so the proportion of movements it misses
        is higher than 39 % — by an amount that has not, to this project's knowledge, been
        published. The figures in this report are therefore an underestimate of unknown size, and
        that is on top of the threshold policy described above.
    """.trimIndent()

    /**
     * Les trois chiffres du sous-comptage, et pourquoi ils sont dans le rapport.
     *
     * Ils sont mesures par `NominalNightRegressionTest` (T22) dans `:algo`, medianes sur 20
     * graines de la nuit nominale : `SUB_THRESHOLD_FRACTION = 0,70`, `RAW_COUNT_RATIO = 0,30`,
     * `INDEX_RATIO = 0,06`. Ils sont recopies ici et non importes parce qu'ils vivent dans une
     * source de **test** que le module applicatif ne compile pas ; le test qui les surveille est
     * la protection contre leur derive, et sa KDoc dit qu'ils doivent remonter jusqu'ici.
     *
     * **Le troisieme est le seul qui decide de la lecture du document.** Les deux premiers
     * decrivent le detecteur ; celui-la decrit ce qui reste de l'indice publie, et il n'est pas
     * deductible des deux autres : la regle AASM exige quatre mouvements consecutifs, donc
     * ecarter 70 % des evenements ne divise pas le compte, il fait disparaitre la plupart des
     * series. Sans lui, un medecin lit le tableau du dessous comme un compte, et un compte bas
     * comme peu de mouvements.
     */
    private val SOUS_COMPTAGE = """
        ### What the threshold policy removes, in three figures

        They are **measured on synthetic signal** — nominal night, median of 20 draws, ground
        truth known by construction — and **never validated against polysomnography**. They
        therefore do not measure the error of this device on this person: they measure what this
        processing chain removes from a signal whose answer is known.

        | Quantity | Value |
        |---|---|
        | Leg movements present in the signal but **below the detection threshold** | 0.70 |
        | Raw count retained, against accelerometric ground truth, before any series rule | 0.30 |
        | **Share of the true index that survives the threshold** | **0.06** |

        The third is not the average of the first two and does not follow from them. A series
        requires **four consecutive movements**: discarding 70 % of the events does not dilute the
        series, it destroys them, because three consecutive surviving intervals are needed for a
        series to remain. On this data, the published index is of the order of **6 % of the true
        count**.

        **A low figure in this report can therefore not be read as "few movements".** A low index
        is the expected behaviour of this chain, including when movements are numerous. The gap
        between 0.06 and 1 is not a margin of error, it is a change of scale: the comparison with
        the 15/h threshold remains beside the point, and the only reading this number allows is
        its variation from one night to the next, in the same person, with the same setup.
    """.trimIndent()

    /**
     * Le rythme non rapporte, presente comme ce qu'il est.
     *
     * `RhythmMeasurementTest` mesure 2 ajustements acceptes sur 20 nuits nominales : le refus est
     * le cas ordinaire, et un rapport qui le presenterait comme un incident ferait chercher une
     * panne de capteur. Le chiffre est cite parce qu'un refus sans ordre de grandeur se lit comme
     * une exception.
     */
    private val RYTHME_REFUSE = """
      The harmonic deconvolution **refused** the fit: the intervals collected do not identify a
      period. This refusal is the ordinary behaviour of the model and not an incident — on
      simulated nights, 2 fits out of 20 are accepted. A period fitted on too few intervals would
      carry a figure and no information, and nothing on this sheet would tell it apart from a
      period that was really measured.
    """.trimIndent()

    /**
     * Le rythme n'a pas de seuil publie, et c'est ecrit a cote de lui.
     *
     * C'est la meme phrase que l'ecran porte sous le chiffre de tete. Sans elle, un lecteur
     * habitue au 15/h du compte horaire cherche le seuil equivalent du rythme, et l'invente.
     */
    private val RYTHME_SANS_SEUIL = """
        No published threshold applies to this quantity. It is measured at the ankle, on a
        consumer accelerometer, and the thresholds of the literature are established on
        polysomnography for the hourly count. What this figure allows is a comparison with
        itself, from one night to the next, in the same person.
    """.trimIndent()

    private val MANQUES_NOTE = """
        The miss rate is **measured** by the harmonic deconvolution, not assumed. It is both a
        quality indicator and the criterion that says whether two nights measure the same thing:
        two nights whose miss rates differ widely do not compare with one another.
    """.trimIndent()

    private val QUESTIONNAIRE_NOTE = """
        This questionnaire is a screening tool and makes no diagnosis. The five diagnostic
        criteria must be checked by a physician, in particular to rule out the conditions that
        mimic restless legs syndrome (cramps, neuropathy, positional discomfort, drug-induced
        akathisia). It is about what is felt while awake; the watch measures what happens during
        sleep. Neither replaces the other.
    """.trimIndent()

    private val NO_HC_NOTE = """
        No external hypnogram for this night. The denominator then comes from the immobility mask
        computed on the same accelerometer as the movements counted: it is **circular**. Every
        burst of movement pushes the mask towards "awake", so the numerator rises while the
        denominator falls. The figure is still shown for information; it does not carry the main
        result and enters no trend.
    """.trimIndent()

    private val EXCLUSION_NOTE = """
        Exclusions are deterministic predicates evaluated before the computation (same leg, same
        strap, alone in bed, calibration reference within tolerance, at least 4 h analysable,
        outside a daylight-saving night). There is no way to exclude a night after seeing its
        result: such a night stays visible with its reason.
    """.trimIndent()

    private val LIMITS = """
        ## Limits, named separately

        - **The numerator is massively under-counted, and this is measured.** Three figures, in
          the "Hourly count" section: 70 % of the movements present in the signal fall below the
          detection threshold, the raw count retained is 0.30 of the accelerometric ground truth,
          and only **0.06 of the published index remains**. This is the limit that commands all
          the others, and the reason a low index cannot be read as "few movements".
        - **The denominator is overestimated.** Consumer watches declare "asleep" on close to one
          awake epoch in two (specificity ~0.52). Sleep time being the denominator, the index
          comes out **under-estimated**.
        - **The numerator may be overestimated** in the presence of breathing-related movements.
          The "respiratory upper bound" column gives the index recomputed assuming that every
          series whose median interval falls in the apnoeic band is of respiratory origin.
        - **These two biases do not cancel out.** They run in opposite directions, their
          magnitudes are unknown and independent; adding them up mentally to conclude "it evens
          out" would be a mistake.
        - **The measurement is one-sided.** With alternation between the legs, a single sensor
          sees an exactly doubled interval — a harmonic, not noise. The estimated miss rate is
          the indicator to watch.
        - **A single night means nothing.** The night-to-night variability of the hourly count is
          of the order of 43 % of its mean in untreated subjects; that of the fundamental rhythm
          is about 3.6 %.
    """.trimIndent()
}
