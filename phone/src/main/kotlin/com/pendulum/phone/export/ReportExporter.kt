package com.pendulum.phone.export

import android.content.Context
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.work.WorkScheduler
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Le rapport, destine a etre lu par un medecin du sommeil.
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
 */
object ReportExporter {

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.UK).withZone(ZoneId.systemDefault())

    suspend fun exportNight(context: Context, sessionHex: String, out: OutputStream) {
        val db = PendulumDatabase.get(context)
        val params = WorkScheduler.activeParams(context)
        val session = db.nightDao().find(sessionHex) ?: error("session inconnue : $sessionHex")
        val results = db.derivedDao().resultsOf(sessionHex, params.paramsHash)
        val nightContext = db.contextDao().findForSession(sessionHex)
        val hc = db.hcSnapshotDao().latest(sessionHex)
        val comparable = db.trendDao().forNight(sessionHex, params.paramsHash).firstOrNull()

        val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val text = buildString {
            appendLine("# Pendulum — nuit du ${format(session.startWallMs, zone)}")
            appendLine()
            appendLine(PREAMBLE)
            appendLine()

            appendLine("## Metrique de suivi")
            appendLine()
            val primary = results.firstOrNull { it.maskSource == "HEALTH_CONNECT" }
                ?: results.firstOrNull()
            if (primary == null) {
                appendLine("Aucun resultat calcule pour cette nuit.")
            } else {
                if (primary.rhythmValid) {
                    appendLine("- Rythme fondamental : ${fmt(primary.fundamentalSec)} s")
                } else {
                    appendLine("- Rythme fondamental : **non rapporte pour cette nuit**")
                    appendLine()
                    appendLine(RYTHME_REFUSE)
                    appendLine()
                }
                appendLine("- Index de periodicite : ${fmt(primary.periodicityIndex)}" +
                    if (primary.periodicityValid) "" else " (non valide : trop peu d'intervalles)")
                appendLine("- Taux de manques estime : ${fmt(primary.missRate)}")
                if (primary.alternationSuspect) {
                    appendLine(
                        "- Profil des harmoniques compatible avec une **alternance gauche/droite** : " +
                            "un capteur unilateral voit alors un intervalle double."
                    )
                }
            }
            appendLine()

            appendLine("## Compte horaire — aPLM-i")
            appendLine()
            appendLine(APLMI_NOTE)
            appendLine()
            appendLine(SOUS_COMPTAGE)
            appendLine()
            appendLine("| Regles | Masque | Mouvements | Sommeil analysable | aPLM-i | Borne haute respiratoire | Publication |")
            appendLine("|---|---|---|---|---|---|---|")
            for (r in results.sortedWith(compareBy({ it.maskSource }, { it.rule }))) {
                appendLine(row(r))
            }
            appendLine()

            appendLine("## Denominateur")
            appendLine()
            if (hc?.selectedPackage != null) {
                appendLine("- Source de l'hypnogramme : `${hc.selectedPackage}`")
                appendLine("- Stades distincts : ${hc.distinctStageTypes} · segments : ${hc.stageCount}")
                appendLine("- Couverture par les stades : ${fmt(hc.stageCoverageMin)} min")
                hc.aggregateTstMin?.let {
                    appendLine("- Controle croise `aggregate()` (dedoublonne par le systeme) : ${fmt(it)} min")
                }
                appendLine("- Derniere modification cote fournisseur : ${hc.lastModifiedTimeMs?.let { format(it, zone) } ?: "inconnue"}")
            } else {
                appendLine(NO_HC_NOTE)
            }
            appendLine()

            appendLine("## Qualite de l'enregistrement")
            appendLine()
            appendLine("- Duree analysable : ${fmt(session.analysableMin)} min")
            appendLine("- Trous : ${session.gapCount} (${session.gapTotalMs / 1000} s cumulees)")
            appendLine("- Frequence mesuree : ${session.fsMeasuredHz?.let { fmt(it) } ?: "?"} Hz")
            appendLine("- Blocs rejetes par le controle d'integrite : ${fmt(session.integrityRejectedFraction * 100)} %")
            if (session.truncated) appendLine("- **Nuit tronquee** : index biaise a la hausse, hors tendance.")
            session.batteryPctLast?.let { appendLine("- Derniere batterie montre rapportee : $it %") }
            session.stopReason?.let { appendLine("- Cause d'arret annoncee : $it") }
            appendLine()

            appendLine("## Comparabilite")
            appendLine()
            if (comparable == null) {
                appendLine("Non evaluee (aucun resultat pour le jeu de parametres courant).")
            } else if (comparable.comparable) {
                appendLine("Nuit comparable aux autres nuits de la campagne.")
            } else {
                appendLine("Nuit **ecartee de la tendance**, motif : `${comparable.exclusionReason}`.")
                appendLine()
                appendLine(EXCLUSION_NOTE)
            }
            appendLine()

            appendLine("## Contexte du soir (scelle avant l'enregistrement)")
            appendLine()
            if (nightContext == null) {
                appendLine("Aucun contexte scelle pour cette nuit.")
            } else {
                appendLine("- Scelle le : ${format(nightContext.sealedAtMs, zone)}")
                appendLine("- Jambe : ${nightContext.leg} · bracelet : ${nightContext.strapId}")
                appendLine("- Seul dans le lit : ${if (nightContext.aloneInBed) "oui" else "non"}")
                appendLine("- Traitement : ${nightContext.medicationJson}")
                appendLine("- Alcool : ${nightContext.alcoholUnits} unite(s) · cafeine apres 16 h : " +
                    if (nightContext.caffeineAfter16h) "oui" else "non")
                nightContext.notes?.let { appendLine("- Notes : $it") }
            }
            appendLine()

            appendLine("## Tracabilite")
            appendLine()
            appendLine("- Jeu de parametres : `${params.paramsHash}` · version de l'algorithme : `${params.algoVersion}`")
            appendLine("- Rapport genere le ${format(System.currentTimeMillis(), zone)}")
            appendLine("- Devoilement du resultat : " +
                (session.revealedAtMs?.let { format(it, zone) } ?: "jamais"))
            appendLine()
            appendLine(LIMITS)
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun row(r: PlmResultEntity): String =
        "| ${r.rule} | ${r.maskSource} | ${r.plmsCount} | ${fmt(r.analysableTstMin)} min | " +
            "${fmt(r.plmi)} /h | ${fmt(r.plmiRespWorstCase)} /h | ${r.gate} |"

    /**
     * `21,34` en francais et `21.34` en anglais **ne sont pas le meme document**, et un rapport
     * qu'un medecin recopie ou qu'un tableur relit doit avoir une seule ponctuation decimale.
     * Le reste du module epingle deja `Locale.UK` partout — `Mapping`, `Controles`, `PorteP1`,
     * `PorteP1Exporter`, dont la KDoc documente precisement ce piege ; ce fichier etait le seul a
     * l'avoir manque, et il est celui qui sort de l'application.
     */
    private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(Locale.UK, v) else "—"

    private fun format(ms: Long, zone: ZoneId): String = stamp.withZone(zone).format(Instant.ofEpochMilli(ms))

    private val PREAMBLE = """
        Ce document est produit par un dispositif personnel non valide cliniquement : un
        accelerometre de montre grand public porte a la cheville. Il ne remplace aucun examen.

        La personne mesuree suit deja un traitement. Sans periode de reference sans traitement,
        ce dispositif ne peut pas mesurer l'effet du traitement — il mesure la variabilite sous
        traitement. Aucune dose ne devrait etre ajustee a partir de ces chiffres.
    """.trimIndent()

    private val APLMI_NOTE = """
        La metrique est nommee **aPLM-i** — « index de mouvements periodiques de cheville, estime,
        non valide » — et non PLMI. Ce n'est pas une precaution de langage : 39 % des mouvements
        scores a l'electromyographie ne s'accompagnent d'aucun mouvement detectable en
        accelerometrie (une dorsiflexion pure ne deplace pas un capteur situe au-dessus de l'axe
        articulaire). Le compte accelerometrique est donc sur une **autre echelle** que le compte
        polysomnographique, et le seuil publie de 15/h ne s'y transpose pas.
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
        ### Ce que la politique de seuil retire, en trois chiffres

        Ils sont **mesures sur signal synthetique** — nuit nominale, mediane de 20 tirages, verite
        connue par construction — et **jamais valides contre polysomnographie**. Ils ne mesurent
        donc pas l'erreur de cet appareil chez cette personne : ils mesurent ce que cette chaine
        de traitement retire d'un signal dont on connait la reponse.

        | Grandeur | Valeur |
        |---|---|
        | Mouvements de jambe presents dans le signal mais **sous le seuil de detection** | 0,70 |
        | Compte brut retenu, rapporte a la verite accelerometrique, avant toute regle de serie | 0,30 |
        | **Part de l'indice vrai qui survit au seuil** | **0,06** |

        Le troisieme n'est pas la moyenne des deux premiers et il ne s'en deduit pas. Une serie
        demande **quatre mouvements consecutifs** : ecarter 70 % des evenements ne dilue pas les
        series, il les detruit, parce qu'il faut trois intervalles consecutifs survivants pour
        qu'une serie subsiste. Sur ces donnees, l'indice publie vaut de l'ordre de **6 % du compte
        vrai**.

        **Un chiffre bas dans ce rapport ne peut donc pas se lire « peu de mouvements ».** Un
        index bas est le comportement attendu de cette chaine, y compris lorsque les mouvements
        sont nombreux. L'ecart entre 0,06 et 1 n'est pas une marge d'erreur, c'est un changement
        d'echelle : la comparaison au seuil de 15/h reste sans objet, et la seule lecture que ce
        nombre autorise est sa variation d'une nuit a l'autre, chez la meme personne, avec le
        meme montage.
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
      La deconvolution des harmoniques a **refuse** l'ajustement pour cette nuit : les intervalles
      recueillis n'identifient pas de periode. Ce refus est le comportement ordinaire du modele et
      non un incident — sur des nuits simulees, 2 ajustements sur 20 sont acceptes. Une periode
      ajustee sur trop peu d'intervalles porterait un chiffre et aucune information, et rien ne la
      distinguerait, sur cette feuille, d'une periode reellement mesuree.
    """.trimIndent()

    private val NO_HC_NOTE = """
        Aucun hypnogramme externe pour cette nuit. Le denominateur provient alors du masque
        d'immobilite calcule sur le meme accelerometre que les mouvements comptes : il est
        **circulaire**. Chaque salve de mouvements pousse le masque a declarer « eveil », donc le
        numerateur monte pendant que le denominateur descend. Le chiffre reste affiche a titre
        indicatif ; il ne porte pas le resultat principal et n'entre dans aucune tendance.
    """.trimIndent()

    private val EXCLUSION_NOTE = """
        Les exclusions sont des predicats deterministes evalues avant le calcul (meme jambe, meme
        bracelet, seul dans le lit, etalon de calibration dans la tolerance, au moins 4 h
        analysables, hors nuit de changement d'heure). Il n'existe aucun moyen d'exclure une nuit
        apres avoir vu son resultat : cette nuit reste visible avec son motif.
    """.trimIndent()

    private val LIMITS = """
        ## Limites, nommees separement

        - **Le numerateur est massivement sous-compte, et c'est mesure.** Trois chiffres, dans la
          section « Compte horaire » : 70 % des mouvements presents dans le signal tombent sous le
          seuil de detection, le compte brut retenu vaut 0,30 de la verite accelerometrique, et il
          n'en reste que **0,06 sur l'indice publie**. C'est la limite qui commande toutes les
          autres, et la raison pour laquelle un index bas ne se lit pas « peu de mouvements ».
        - **Le denominateur est surestime.** Les montres grand public declarent « endormi » pres
          d'une epoque d'eveil sur deux (specificite ~0,52). Le temps de sommeil etant au
          denominateur, l'index en ressort **sous-estime**.
        - **Le numerateur peut etre surestime** en presence de mouvements lies a la respiration.
          La colonne « borne haute respiratoire » donne l'index recalcule en supposant que toute
          serie dont l'intervalle median tombe dans la bande apneique est d'origine respiratoire.
        - **Ces deux biais ne se compensent pas.** Ils vont en sens inverse, leurs amplitudes sont
          inconnues et independantes ; les additionner mentalement pour conclure « ca s'annule »
          serait une erreur.
        - **La mesure est unilaterale.** En cas d'alternance entre les jambes, un capteur unique
          voit un intervalle exactement double — un harmonique, pas du bruit. Le taux de manques
          estime est l'indicateur a surveiller.
        - **Une nuit isolee ne signifie rien.** La variabilite nuit a nuit du compte horaire est
          de l'ordre de 43 % de sa moyenne chez les sujets non traites ; celle du rythme
          fondamental est d'environ 3,6 %.
    """.trimIndent()
}
