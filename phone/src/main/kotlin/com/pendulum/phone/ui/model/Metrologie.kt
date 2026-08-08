package com.pendulum.phone.ui.model

import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.TelemetryPointEntity
import com.pendulum.phone.ui.chart.EtatPort
import com.pendulum.phone.ui.chart.Intervalle
import com.pendulum.phone.ui.chart.JaugeBatterie
import com.pendulum.phone.ui.chart.MetrologieSpec
import com.pendulum.phone.ui.chart.NiveauDatation
import com.pendulum.phone.ui.chart.PalierDatation
import android.content.res.Resources
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.resoudre
import com.pendulum.phone.ui.text.texte
import java.util.Locale

/**
 * Ce que la telemetrie d'une nuit dit de l'appareil — agrege une fois, lu a trois endroits.
 *
 * La bande de metrologie, le panneau « pourquoi ce chiffre » et le critere batterie de la porte P1
 * lisent tous les memes points. Les agreger trois fois donnerait trois chiffres qui finiraient par
 * differer, et la difference porterait sur des grandeurs qui decident du seuil de detection.
 *
 * Tout est pur : ni `Context`, ni horloge, ni E/S. Les sentinelles du format sont interpretees
 * **ici et une seule fois** — `TelemetryAdapter` les laisse passer telles quelles, precisement pour
 * qu'il n'y ait qu'une convention a tenir.
 */
object Metrologie {

    /**
     * Au-dela de deux millisecondes d'ecart-type, la datation d'un echantillon n'est plus fine.
     *
     * A 50 Hz la periode vaut 20 ms : deux millisecondes sont un dixieme de periode, c'est-a-dire
     * l'ordre de grandeur sous lequel l'interpolation lineaire du format ne coute rien. Au-dessus,
     * elle commence a couter, et le seuil suivant est la periode elle-meme.
     */
    const val GIGUE_FINE_US = 2_000

    /** Dix millisecondes : la moitie d'une periode a 50 Hz. Au-dela, l'instant devient discutable. */
    const val GIGUE_MOYENNE_US = 10_000

    /**
     * Un gel d'ecriture est notable a partir d'**une periode d'echantillonnage**.
     *
     * En dessous, le processeur a repris la main avant que l'interruption suivante n'arrive et
     * aucun echantillon ne peut avoir ete manque de ce fait. Au-dessus, il a pu l'etre — c'est
     * `fsyncMaxUs` et non `fsyncTotalUs` qui repond a cette question, parce que c'est le pire gel
     * qui explique une interruption ratee a un instant precis, pas le cumul.
     */
    fun seuilGelUs(nominalRateHz: Int): Long =
        if (nominalRateHz <= 0) 20_000L else (1_000_000L / nominalRateHz)

    /**
     * Les chiffres que le panneau « pourquoi ce chiffre » affiche.
     *
     * @param echantillonsEcretes total des echantillons ayant touche la dynamique du capteur. Ce
     *   n'est **pas** la saturation du format a 16 g : un capteur a 4 ou 8 g s'ecrete a la moitie
     *   ou au quart de ce que le format sait coder, et l'ecretage etait jusqu'ici totalement
     *   invisible a la relecture.
     * @param gigueMedianeUs mediane des ecarts-types d'intervalle. Mediane et non moyenne : une
     *   seule fenetre catastrophique tirerait la moyenne et ferait croire a une nuit entiere mal
     *   datee.
     * @param pireIntervalleUs le pire ecart entre deux echantillons consecutifs de toute la nuit.
     *   C'est lui qui borne l'erreur de datation, la moyenne le cache.
     */
    data class Resume(
        val points: Int,
        val pointsEcretes: Int,
        val echantillonsEcretes: Int,
        val gigueMedianeUs: Int,
        val pireIntervalleUs: Long,
        val gels: Int,
        val pireGelUs: Long,
    ) {
        val aQuelqueChoseADire: Boolean
            get() = points > 0
    }

    fun resume(points: List<TelemetryPointEntity>, nominalRateHz: Int): Resume? {
        if (points.isEmpty()) return null
        val seuilGel = seuilGelUs(nominalRateHz)
        return Resume(
            points = points.size,
            pointsEcretes = points.count { it.clippedSamples > 0 },
            echantillonsEcretes = points.sumOf { it.clippedSamples },
            gigueMedianeUs = medianeEntiere(points.map { it.jitterStdUs }),
            pireIntervalleUs = points.maxOf { it.maxIntervalUs },
            gels = points.count { it.fsyncMaxUs >= seuilGel },
            pireGelUs = points.maxOf { it.fsyncMaxUs },
        )
    }

    /**
     * La bande d'etat de l'appareil, sur l'axe temporel des deux bandes du dessus.
     *
     * ### L'ancrage, et pourquoi il passe par `sensorTsNs`
     *
     * Chaque point est place a `debutMs + (sensorTsNs - t0Ns)`, c'est-a-dire sur la **base de temps
     * des echantillons** — la seule qui date les mouvements. Passer par l'horloge murale ou par
     * l'horloge monotone demanderait une conversion dont le §12.4 du banc montre qu'elle derive :
     * `SensorEvent.timestamp` n'est pas garanti egal a `elapsedRealtimeNanos`, certains
     * constructeurs en excluant le temps de suspend. Un decalage de quelques minutes entre la bande
     * de metrologie et l'enveloppe ferait attribuer un ecretage a un mouvement voisin, ce qui est
     * exactement l'erreur que la bande existe pour empecher.
     *
     * Les points dont `sensorTsNs` vaut 0 — aucun echantillon vu au moment du point, donc les tout
     * premiers d'une session — sont **ecartes des voies temporelles** : on ne sait pas ou les
     * poser, et les poser au debut par defaut inventerait un etat. Ils restent comptes dans
     * [Resume] et dans la jauge, qui ne dependent pas de l'axe.
     *
     * ### Un intervalle couvre la minute qui *precede* son point
     *
     * Les compteurs du format comptent depuis le point precedent. Le bloc d'etat d'un point s'etend
     * donc du point precedent au point courant. Poser l'intervalle a l'envers decalerait toute la
     * bande d'une minute — l'ordre de grandeur d'un mouvement.
     *
     * @param debutMs, finMs les bornes de l'axe, deja decalees a l'heure murale locale par
     *   l'appelant, comme pour les deux autres bandes.
     * @param t0Ns `tFirstNs` du premier bloc de la nuit : l'origine de la base de temps capteur.
     *   `null` quand aucun chunk n'est en base, auquel cas rien ne peut etre place et la bande rend
     *   sa phrase d'indisponibilite.
     * @param res les ressources, parce que la spec porte des chaines **deja resolues**.
     *
     * C'est la seule fonction de ce fichier qui ne soit pas pure, et la raison est en aval : la
     * bande est peinte par une extension de `DrawScope`, qui n'a ni composition ni `Context`. Faire
     * porter a la spec un `UiText` obligerait le dessin a resoudre au milieu d'un `Canvas`. Tout ce
     * qui **decide** — [resume], [etatPort], les paliers, la jauge — reste pur et testable ; seule
     * la mise en mots passe par ici.
     */
    fun spec(
        session: NightSessionEntity,
        points: List<TelemetryPointEntity>,
        debutMs: Long,
        finMs: Long,
        t0Ns: Long?,
        res: Resources,
    ): MetrologieSpec {
        val jauge = jauge(points, res)
        val places = if (t0Ns == null) emptyList() else points
            .filter { it.sensorTsNs > 0L }
            .sortedBy { it.sensorTsNs }
            .map { it to debutMs + (it.sensorTsNs - t0Ns) / 1_000_000L }

        val seuilGel = seuilGelUs(session.nominalRateHz)

        // L'intervalle couvert par un point : du point precedent a lui-meme, borne au debut de la
        // nuit pour le premier.
        val intervalles = places.mapIndexed { i, (p, ms) ->
            val precedent = if (i == 0) debutMs else places[i - 1].second
            p to Intervalle(precedent.coerceAtLeast(debutMs), ms)
        }

        return MetrologieSpec(
            debutMs = debutMs,
            finMs = finMs,
            etatPort = etatPort(points),
            horsPoignet = fusionner(
                intervalles.filter { it.first.offBody == TelemetryPoint.OFF_BODY_RETIRE }.map { it.second }
            ),
            charge = fusionner(intervalles.filter { it.first.charging }.map { it.second }),
            datation = paliers(intervalles),
            ecretage = intervalles.filter { it.first.clippedSamples > 0 }.map { it.second.finMs },
            gels = intervalles.filter { it.first.fsyncMaxUs >= seuilGel }.map { it.second.finMs },
            batterie = jauge,
            points = points.size,
            texteIndisponible = res.getString(R.string.chart_metrology_unavailable),
            descriptionAccessible = description(res, points, jauge, session.nominalRateHz),
        )
    }

    // -------------------------------------------------------------------------------------
    // Les voies, une par une
    // -------------------------------------------------------------------------------------

    /**
     * `SANS_CAPTEUR` des que **tous** les points l'annoncent : un appareil n'acquiert pas un
     * detecteur off-body en cours de nuit. Un melange est donc une lecture partielle, et elle est
     * traitee comme telle — les segments annonces « retire » sont dessines, le reste ne l'est pas.
     */
    private fun etatPort(points: List<TelemetryPointEntity>): EtatPort = when {
        points.isEmpty() -> EtatPort.SANS_CAPTEUR
        points.all { it.offBody == TelemetryPoint.OFF_BODY_ABSENT } -> EtatPort.SANS_CAPTEUR
        points.any { it.offBody == TelemetryPoint.OFF_BODY_RETIRE } -> EtatPort.RETIRE
        else -> EtatPort.PORTE
    }

    private fun niveau(jitterStdUs: Int): NiveauDatation = when {
        jitterStdUs <= GIGUE_FINE_US -> NiveauDatation.FINE
        jitterStdUs <= GIGUE_MOYENNE_US -> NiveauDatation.MOYENNE
        else -> NiveauDatation.GROSSIERE
    }

    /** Paliers de datation, fusionnes tant que la classe ne change pas. */
    private fun paliers(
        intervalles: List<Pair<TelemetryPointEntity, Intervalle>>,
    ): List<PalierDatation> {
        val sortie = ArrayList<PalierDatation>()
        for ((p, iv) in intervalles) {
            val n = niveau(p.jitterStdUs)
            val dernier = sortie.lastOrNull()
            if (dernier != null && dernier.niveau == n && dernier.finMs == iv.debutMs) {
                sortie[sortie.size - 1] = dernier.copy(finMs = iv.finMs)
            } else {
                sortie += PalierDatation(iv.debutMs, iv.finMs, n)
            }
        }
        return sortie
    }

    /**
     * Fusionne les intervalles contigus. Sans cela, une nuit de huit heures produirait quatre cent
     * quatre-vingts rectangles d'une minute — c'est le meme motif que la voie d'immobilite de
     * l'hypnogramme, et pour la meme raison.
     */
    private fun fusionner(intervalles: List<Intervalle>): List<Intervalle> {
        val sortie = ArrayList<Intervalle>()
        for (iv in intervalles.sortedBy { it.debutMs }) {
            val dernier = sortie.lastOrNull()
            if (dernier != null && iv.debutMs <= dernier.finMs) {
                sortie[sortie.size - 1] = dernier.copy(finMs = maxOf(dernier.finMs, iv.finMs))
            } else {
                sortie += iv
            }
        }
        return sortie
    }

    /**
     * La jauge : le fait — combien il restait a la fin — et le verdict — le critere P1 est-il tenu.
     *
     * Le verdict n'est **pas** recalcule ici. Il vient de [PenteBatterie] quand la pente aboutit,
     * du dernier pourcentage sinon, avec le meme comparateur strict que [Controles.BATTERIE_MIN_PCT]
     * et [PorteP1]. Deux lectures du meme seuil finissent par diverger, et celle-ci serait la
     * troisieme.
     */
    private fun jauge(points: List<TelemetryPointEntity>, res: Resources): JaugeBatterie? {
        val dernier = points
            .filter { it.batteryPct in 0..100 }
            .maxByOrNull { it.elapsedRealtimeNs }
            ?: return null
        val pente = PenteBatterie.de(points, PorteP1.DUREE_CIBLE_H)
        val tenue = if (pente != null) {
            pente.pctA8h > Controles.BATTERIE_MIN_PCT
        } else {
            dernier.batteryPct > Controles.BATTERIE_MIN_PCT
        }
        val pctFin = "${dernier.batteryPct}%"
        return JaugeBatterie(
            fraction = dernier.batteryPct / 100f,
            tenue = tenue,
            libelle = if (pente != null) {
                texte(
                    R.string.chart_battery_gauge_projected,
                    pctFin,
                    "%.0f%%".format(Locale.UK, pente.pctA8h),
                    PorteP1.DUREE_CIBLE_H.toInt(),
                ).resoudre(res)
            } else {
                texte(R.string.chart_battery_gauge, pctFin).resoudre(res)
            },
        )
    }

    /**
     * Le resume lu par un lecteur d'ecran. Il rend ce que les voies montrent — combien de points,
     * la datation la plus grossiere observee, l'ecretage, les gels, la batterie — et **la phrase de
     * non-causalite**, qui est ce que la separation graphique dit a l'oeil et que rien ne dirait
     * autrement a qui ne voit pas la bande.
     */
    private fun description(
        res: Resources,
        points: List<TelemetryPointEntity>,
        jauge: JaugeBatterie?,
        nominalRateHz: Int,
    ): String {
        if (points.isEmpty()) return res.getString(R.string.chart_metrology_unavailable)
        val r = resume(points, nominalRateHz)!!
        return res.getString(
            R.string.chart_metrology_description,
            r.points,
            "%.1f ms".format(Locale.UK, r.gigueMedianeUs / 1000.0),
            r.echantillonsEcretes,
            r.gels,
            jauge?.libelle ?: Mapping.TIRET,
        )
    }

    private fun medianeEntiere(v: List<Int>): Int {
        if (v.isEmpty()) return 0
        val t = v.sorted()
        val m = t.size / 2
        return if (t.size % 2 == 1) t[m] else (t[m - 1] + t[m]) / 2
    }
}
