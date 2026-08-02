package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.ui.text.Textes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * La traduction de ce que la base sait vers ce que les ecrans affichent.
 *
 * **Tout ce fichier est pur** : pas de `Context`, pas de Room, pas de `System.currentTimeMillis`.
 * C'est la meme discipline que `Aggregat` et `ComparabilityRule`, et pour la meme raison — la
 * regle qui decide qu'une nuit est « provisoire » plutot qu'« eligible » merite un test sur ses
 * bornes exactes, pas une verification a l'oeil sur un emulateur.
 *
 * Ce que ces fonctions ne font **jamais** : decider d'une eligibilite. Elle est deja decidee, par
 * SQL, dans la vue `comparable_night`, avant qu'aucun code d'affichage ne tourne. Ici on ne fait
 * que la rendre lisible.
 */
object Mapping {

    /**
     * Une nuit, telle que la liste et la tendance la montrent.
     *
     * ### Les trois etats, et pourquoi « provisoire » n'est pas « ecartee »
     *
     * Une nuit comparable dont le `gate` n'est pas `FULL` a ete mesuree correctement : ce qui
     * manque est son denominateur, parce que l'hypnogramme n'est pas encore arrive de Health
     * Connect. Elle sera recalculee toute seule. La ranger avec les nuits ecartees ferait lire
     * comme une panne le cas le plus frequent au reveil.
     *
     * @param zone fuseau de la nuit, lu dans la session — et non le fuseau courant. Une nuit
     *   passee a l'etranger doit s'afficher a l'heure ou elle a ete vecue, sinon la liste dit que
     *   l'utilisateur s'est couche a 4 h du matin.
     */
    fun nuitUi(
        n: ComparableNight,
        finWallMs: Long?,
        sourceSommeil: String,
        drapeaux: List<Drapeau> = emptyList(),
    ): NuitUi {
        val zone = runCatching { ZoneId.of(n.zoneId) }.getOrDefault(ZoneId.of("UTC"))
        val debut = Instant.ofEpochMilli(n.startWallMs).atZone(zone)
        val fin = finWallMs?.let { Instant.ofEpochMilli(it).atZone(zone) }

        val etat = when {
            !n.comparable -> EtatNuit.ECARTEE
            n.gate != GATE_COMPLET -> EtatNuit.PROVISOIRE
            else -> EtatNuit.ELIGIBLE
        }

        return NuitUi(
            sessionHex = n.sessionHex,
            dateLisible = debut.format(FORMAT_DATE),
            jourAbrege = debut.format(FORMAT_JOUR),
            debut = debut.format(FORMAT_HEURE),
            fin = fin?.format(FORMAT_HEURE) ?: TIRET,
            sommeilLisible = dureeLisible(n.analysableTstMin),
            sourceSommeil = sourceSommeil,
            etat = etat,
            // Non nul si et seulement si l'etat est ECARTEE : le modele l'exige, et afficher un
            // motif a cote d'une nuit retenue serait incomprehensible.
            motif = if (etat == EtatNuit.ECARTEE) Textes.Nuits.motif(n.exclusionReason) else null,
            rythmeSec = rythmeSec(n),
            comptePlmi = n.plmi,
            drapeaux = drapeaux,
            startWallMs = n.startWallMs,
            devoileeAtMs = n.revealedAtMs,
        )
    }

    /**
     * Le rythme d'une nuit, ou `null` quand il n'y en a pas a montrer.
     *
     * **`null` est le cas frequent et non l'exception** : `RhythmMeasurementTest` mesure 2
     * ajustements acceptes sur 20 nuits nominales. La deconvolution refuse de rendre une periode
     * quand le train d'intervalles ne l'identifie pas, ce qui est la qualite qu'on lui demande —
     * mais l'interface lisait `fundamentalSec` sans consulter ce refus, et deux des six motifs de
     * refus laissent un nombre **fini** dans la colonne. Un rythme refuse s'affichait donc comme
     * un rythme mesure, et un rythme absent (`NaN`) s'arrondissait a « 0 s ».
     *
     * Le type porte desormais la regle : il n'existe aucune valeur a afficher quand l'ajustement
     * n'a pas ete accepte, donc aucun ecran ne peut en montrer une par distraction.
     */
    fun rythmeSec(n: ComparableNight): Double? =
        n.fundamentalSec.takeIf { n.rhythmValid && it.isFinite() && it > 0.0 }

    /**
     * `21 s`, ou la mention du refus. **Le seul endroit ou un rythme se met en forme** : la liste,
     * le detail et l'export l'ecrivaient chacun a leur facon, et deux d'entre eux arrondissaient un
     * `NaN` en « 0 s » — c'est-a-dire qu'ils annoncaient un rythme nul la ou il n'y en avait aucun.
     */
    fun rythmeLisible(sec: Double?): String =
        sec?.let { "${kotlin.math.round(it).toInt()} s" } ?: Textes.Nuits.Detail.RYTHME_NON_AJUSTE

    /** `23:12`, dans le fuseau ou la nuit a ete vecue. */
    fun heureLisible(ms: Long, zoneId: String): String =
        Instant.ofEpochMilli(ms).atZone(zoneDe(zoneId)).format(FORMAT_HEURE)

    /** `12 March`, dans le fuseau ou la nuit a ete vecue. */
    fun dateLisible(ms: Long, zoneId: String): String =
        Instant.ofEpochMilli(ms).atZone(zoneDe(zoneId)).format(FORMAT_DATE)

    /** `12 March, 07:04` — l'instant d'un devoilement, tel qu'il partira dans l'export. */
    fun instantLisible(ms: Long, zoneId: String): String =
        "${dateLisible(ms, zoneId)}, ${heureLisible(ms, zoneId)}"

    /**
     * Le cote portant, traduit depuis `night_context.leg`.
     *
     * Il n'y a pas de valeur par defaut : un capteur unilateral voit un intervalle double quand
     * les mouvements alternent, donc une jambe devinee fausserait le critere de comparabilite
     * sans que rien ne le signale. Une valeur inconnue reste inconnue.
     */
    fun libelleJambe(leg: String?): String? = when (leg) {
        "LEFT" -> Textes.CeSoir.JAMBE_GAUCHE
        "RIGHT" -> Textes.CeSoir.JAMBE_DROITE
        else -> null
    }

    private fun zoneDe(zoneId: String): ZoneId =
        runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())

    /**
     * Les drapeaux de qualite d'une nuit. Ce ne sont **pas** des erreurs : ce sont des proprietes
     * mesurees, affichees pour que l'utilisateur sache dans quelles conditions son chiffre a ete
     * obtenu. Au plus trois sont visibles a l'ecran, la liste sort dans l'ordre de gravite.
     */
    fun drapeaux(
        n: ComparableNight,
        gapCount: Int,
        gapTotalMs: Long,
        batteryPctLast: Int?,
    ): List<Drapeau> = buildList {
        // Le masque accelerometrique en premier : c'est celui qui change la nature du chiffre,
        // puisque le denominateur cesse alors d'etre independant du numerateur.
        if (n.maskSource == MASQUE_ACCELERO) add(Drapeau(Textes.Nuits.Drapeaux.MASQUE_ACCELERO))
        if (n.truncated) add(Drapeau(Textes.Nuits.Drapeaux.TRONQUEE))
        if (gapTotalMs > 0) add(Drapeau(Textes.Nuits.Drapeaux.trous(gapCount, gapTotalMs / 1000)))
        if (batteryPctLast != null && batteryPctLast < SEUIL_BATTERIE_BASSE_PCT) {
            add(Drapeau(Textes.Nuits.Drapeaux.batterie(batteryPctLast)))
        }
        if (n.missRate > SEUIL_MANQUES_NOTABLE) {
            add(Drapeau(Textes.Nuits.Drapeaux.manques(n.missRate)))
        }
    }

    /**
     * L'agregat d'une grandeur sur un ensemble de nuits, ou `null` sous le minimum.
     *
     * Renvoyer `null` et non un `Resultat` degrade est le point : il n'existe aucune valeur
     * agregee sous trois nuits, donc aucun chemin de code ne peut en afficher une par
     * distraction. Le type porte la regle.
     */
    fun agregat(
        grandeur: Aggregat.Grandeur,
        nuits: List<ComparableNight>,
        valeurDe: (ComparableNight) -> Double,
    ): Aggregat.Resultat? {
        if (nuits.size < Aggregat.MIN_NUITS_AGREGAT) return null
        val valeurs = DoubleArray(nuits.size) { valeurDe(nuits[it]) }
        val graine = Aggregat.graineDe(nuits.map { it.sessionHex })
        val (bas, haut) = Aggregat.bootstrapCi(valeurs, graine)
        val dispersion = Aggregat.dispersion(valeurs)
        return Aggregat.Resultat(
            grandeur = grandeur,
            mediane = Aggregat.mediane(valeurs),
            ciBas = bas,
            ciHaut = haut,
            nuits = nuits.size,
            dispersion = dispersion,
            mdc95 = Aggregat.mdc95(dispersion, nuits.size),
        )
    }

    /**
     * `5 h 12` — jamais `5,2 h` ni `312 min`.
     *
     * Une duree de sommeil se lit en heures et minutes ; un decimal d'heure oblige a une
     * conversion mentale, et une duree en minutes seules ne se compare pas d'un coup d'oeil.
     */
    fun dureeLisible(minutes: Double): String {
        if (minutes <= 0.0) return TIRET
        val total = kotlin.math.round(minutes).toInt()
        return "%d h %02d".format(Locale.UK, total / 60, total % 60)
    }

    /**
     * Le libelle de la source de sommeil, ou la mention explicite du repli.
     *
     * Il n'y a pas de troisieme cas : soit une application tierce a fourni la periode de sommeil,
     * soit c'est le masque accelerometrique de Pendulum, et il faut le dire — c'est la seule
     * information qui permette de savoir si le chiffre repose sur un denominateur independant.
     */
    fun libelleSource(maskSource: String, paquet: String?): String = when {
        maskSource == MASQUE_ACCELERO -> Textes.Reglages.MASQUE_ACCELERO_SEUL
        paquet.isNullOrBlank() -> Textes.Reglages.SOURCE_INCONNUE
        else -> paquet.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** `FULL` : la nuit a le droit de porter un chiffre. Voir `PublicationGate` dans `:algo`. */
    const val GATE_COMPLET = "FULL"

    /** `MaskSource.ACCEL_IMMOBILITY` : le denominateur vient du meme capteur que le numerateur. */
    const val MASQUE_ACCELERO = "ACCEL_IMMOBILITY"

    /**
     * Au-dela, le taux de manques estime par la deconvolution merite d'etre signale : la nuit
     * reste exploitable, mais un mouvement sur cinq n'a pas ete vu, et deux nuits dont les taux
     * de manques different beaucoup ne sont pas tout a fait la meme mesure.
     */
    const val SEUIL_MANQUES_NOTABLE = 0.20

    /**
     * Sous ce niveau en fin de nuit, la montre a probablement arrete de mesurer avant le reveil.
     * C'est une information sur la nuit, pas une alerte : `StopConditions` a deja decide de
     * l'arret, et les mouvements se concentrent dans la seconde moitie de la nuit, donc le
     * chiffre est vraisemblablement sous-estime.
     */
    const val SEUIL_BATTERIE_BASSE_PCT = 10

    /**
     * Le tiret cadratin, une seule fois.
     *
     * Il ne veut pas dire zero, il veut dire « pas de valeur », et c'est pour cela qu'il merite
     * une constante : trois fichiers le declaraient chacun de leur cote, et un `-` ASCII glisse
     * dans l'un des trois passerait la relecture sans se voir.
     */
    const val TIRET = "—"

    /** `99,0%`, avec la ponctuation decimale epinglee. Voir [dureeLisible] pour le motif. */
    fun pourcent(v: Double): String = "%.1f%%".format(Locale.UK, v * 100)

    private val FORMAT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
    private val FORMAT_JOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    private val FORMAT_HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
}

/** Raccourci de lecture : les identifiants de motif restent ceux de la regle, jamais recopies. */
val ComparableNight.ecartee: Boolean get() = exclusionReason != ComparabilityRule.OK
