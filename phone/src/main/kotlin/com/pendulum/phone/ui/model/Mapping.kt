package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.texte
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
        sourceSommeil: UiText,
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
            motif = if (etat == EtatNuit.ECARTEE) motif(n.exclusionReason) else null,
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
        n.fundamentalSec?.takeIf { n.rhythmValid && it.isFinite() && it > 0.0 }

    /**
     * `21 s`, ou la mention du refus. **Le seul endroit ou un rythme se met en forme** : la liste,
     * le detail et l'export l'ecrivaient chacun a leur facon, et deux d'entre eux arrondissaient un
     * `NaN` en « 0 s » — c'est-a-dire qu'ils annoncaient un rythme nul la ou il n'y en avait aucun.
     */
    fun rythmeLisible(sec: Double?): UiText =
        sec?.let { texte(R.string.night_detail_rhythm_seconds, kotlin.math.round(it).toInt()) }
            ?: texte(R.string.night_detail_rhythm_not_fitted)

    /**
     * `18/h`, ou le tiret — le pendant de [rythmeLisible] pour le compte horaire.
     *
     * Il existe pour la meme raison : deux ecrans arrondissaient eux-memes, et un arrondi
     * applique a une absence rend « 0/h », c'est-a-dire une nuit sans le moindre mouvement. Ce
     * n'est pas ce qu'une nuit sans denominateur veut dire.
     */
    fun compteLisible(parHeure: Double?): String =
        parHeure?.let { "${Math.round(it)}/h" } ?: TIRET

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
    fun libelleJambe(leg: String?): UiText? = when (leg) {
        "LEFT" -> texte(R.string.tonight_leg_left)
        "RIGHT" -> texte(R.string.tonight_leg_right)
        else -> null
    }

    /**
     * Motifs d'exclusion : traduction des identifiants de `ComparabilityRule`, faite ici et nulle
     * part ailleurs. Elle etait dans le fichier de textes ; elle est desormais a cote de la regle
     * qu'elle traduit, ce qui rend visible d'un coup d'oeil qu'aucun code n'est laisse sans phrase.
     */
    fun motif(code: String): UiText = when (code) {
        "NO_CONTEXT" -> texte(R.string.nights_reason_no_context)
        "LEG_CHANGED" -> texte(R.string.nights_reason_leg_changed)
        "STRAP_CHANGED" -> texte(R.string.nights_reason_strap_changed)
        "NOT_ALONE" -> texte(R.string.nights_reason_not_alone)
        "CAL_GAIN_UNKNOWN" -> texte(R.string.nights_reason_cal_gain_unknown)
        "CAL_GAIN_OUT_OF_TOLERANCE" -> texte(R.string.nights_reason_cal_gain_out_of_tolerance)
        "TOO_SHORT" -> texte(R.string.nights_reason_too_short)
        "DST_NIGHT" -> texte(R.string.nights_reason_dst_night)
        else -> texte(R.string.nights_reason_default)
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
        if (n.maskSource == MASQUE_ACCELERO) add(Drapeau(texte(R.string.nights_flag_accel_mask)))
        if (n.truncated) add(Drapeau(texte(R.string.nights_flag_truncated)))
        if (gapTotalMs > 0) {
            add(Drapeau(texte(R.string.nights_flag_gap, (gapTotalMs / 1000).toInt(), gapCount)))
        }
        if (batteryPctLast != null && batteryPctLast < SEUIL_BATTERIE_BASSE_PCT) {
            add(Drapeau(texte(R.string.nights_flag_battery, batteryPctLast)))
        }
        // Pas de drapeau quand le taux de manques est inconnu : un drapeau absent dit deja « rien
        // a signaler », et en poser un sur une valeur qui n'existe pas signalerait une mesure.
        n.missRate?.let { taux ->
            if (taux > SEUIL_MANQUES_NOTABLE) {
                add(Drapeau(texte(R.string.nights_flag_missed, Math.round(taux * 100).toInt())))
            }
        }
    }

    /**
     * L'agregat d'une grandeur sur un ensemble de nuits, ou `null` sous le minimum.
     *
     * Renvoyer `null` et non un `Resultat` degrade est le point : il n'existe aucune valeur
     * agregee sous trois nuits, donc aucun chemin de code ne peut en afficher une par
     * distraction. Le type porte la regle.
     *
     * ### Les nuits sans valeur sortent avant le compte, pas apres
     *
     * [valeurDe] rend `null` quand la nuit ne porte pas la grandeur — pas de sommeil analysable,
     * ou ajustement de rythme refuse. Ces nuits sont **retirees**, et le minimum de trois porte
     * sur ce qui reste. Les compter aurait ete le pire des deux mondes : `Aggregat.mediane`
     * range `NaN` en fin de tri, donc une seule nuit sans valeur pouvait devenir la mediane
     * elle-meme et rendre tout l'intervalle `NaN` — un ecran entier de tirets sans que rien ne
     * dise pourquoi. Les remplacer par zero aurait tire la mediane vers le bas et annonce une
     * amelioration inexistante.
     */
    fun agregat(
        grandeur: Aggregat.Grandeur,
        nuits: List<ComparableNight>,
        valeurDe: (ComparableNight) -> Double?,
    ): Aggregat.Resultat? {
        val mesurees = nuits.filter { valeurDe(it) != null }
        if (mesurees.size < Aggregat.MIN_NUITS_AGREGAT) return null
        val valeurs = DoubleArray(mesurees.size) { valeurDe(mesurees[it])!! }
        val graine = Aggregat.graineDe(mesurees.map { it.sessionHex })
        val (bas, haut) = Aggregat.bootstrapCi(valeurs, graine)
        val dispersion = Aggregat.dispersion(valeurs)
        return Aggregat.Resultat(
            grandeur = grandeur,
            mediane = Aggregat.mediane(valeurs),
            ciBas = bas,
            ciHaut = haut,
            // `mesurees` et non `nuits` : le `n` affiche a cote de l'intervalle est le nombre de
            // valeurs qui l'ont produit. Compter les nuits sans valeur le gonflerait, et la MDC95
            // — qui divise par la racine de ce `n` — annoncerait une precision qu'elle n'a pas.
            nuits = mesurees.size,
            dispersion = dispersion,
            mdc95 = Aggregat.mdc95(dispersion, mesurees.size),
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
     * Le libelle de la source de sommeil, **produit ici et nulle part ailleurs**.
     *
     * Il n'y a pas de troisieme cas : soit une periode de sommeil externe a fourni le
     * denominateur, soit c'est le masque accelerometrique de Pendulum, et il faut le dire — c'est
     * la seule information qui permette de savoir si le chiffre repose sur un denominateur
     * independant.
     *
     * ### Pourquoi le repli n'est plus « source non identifiee »
     *
     * Il l'etait, et le detail d'une nuit affichait alors **deux libelles differents pour le meme
     * champ** : le bloc « pourquoi ce chiffre » passait par cette fonction et rendait
     * `SOURCE_INCONNUE`, pendant que la ligne de controle qualite, trois cartes plus bas, ecrivait
     * `HEALTH_CONNECT` en dur pour la meme nuit. Deux valeurs sur un ecran sont deux valeurs dont
     * une est fausse, et rien ne disait laquelle.
     *
     * Celle qui est juste est `HEALTH_CONNECT`. Ce que le paquet manquant rend inconnu est **quelle
     * application** a publie la session, pas d'ou vient le denominateur : `maskSource` le dit, il
     * vient de la base, et c'est le seul fait qui porte l'independance numerateur/denominateur.
     * Ecrire « source non identifiee » a cote d'un controle qui passe au vert ferait douter du
     * controle. Le nom du paquet reste affiche des qu'il est connu.
     */
    fun libelleSource(maskSource: String, paquet: String?): UiText = when {
        maskSource == MASQUE_ACCELERO -> texte(R.string.settings_accel_mask_only)
        paquet.isNullOrBlank() -> texte(R.string.settings_health_connect)
        else -> texte(nomDApplication(paquet))
    }

    /**
     * Le nom lisible d'un paquet Android : `com.google.android.apps.fitness` donne `Fitness`.
     *
     * Extrait de [libelleSource] pour que les reglages s'en servent aussi. Ils affichaient le
     * paquet **brut** pendant que la liste des nuits affichait « Fitness » : la meme source portait
     * deux noms selon l'ecran, et le paquet entier ne tenait pas dans la largeur — il se collait a
     * son intitule et se coupait au milieu d'un mot.
     *
     * Le nom vient de Health Connect : il ne se traduit pas.
     */
    fun nomDApplication(paquet: String): String =
        paquet.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    /**
     * `2026-03-12` : le jour d'une nuit, pour un **nom de fichier**.
     *
     * Ce n'est pas [dateLisible], et les deux ne doivent pas se confondre : « 12 March » se lit,
     * ne se trie pas, et depend de la locale. Un dossier d'exports doit s'ordonner tout seul.
     */
    fun jourIso(ms: Long, zoneId: String): String {
        val zone = runCatching { java.time.ZoneId.of(zoneId) }
            .getOrDefault(java.time.ZoneId.systemDefault())
        return java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
    }

    /**
     * Une taille sur disque, en unites decimales (Mo = 10^6 octets) et non binaires.
     *
     * C'est l'unite qu'Android affiche dans ses propres reglages de stockage, et l'ecran
     * d'effacement sera lu a cote de celui-la : deux chiffres differents pour la meme chose
     * feraient douter du plus alarmant des deux, qui est justement le notre.
     */
    fun octetsLisibles(octets: Long): String = when {
        octets >= 1_000_000_000L -> "%.1f GB".format(Locale.UK, octets / 1_000_000_000.0)
        octets >= 1_000_000L -> "%.0f MB".format(Locale.UK, octets / 1_000_000.0)
        octets >= 1_000L -> "%.0f kB".format(Locale.UK, octets / 1_000.0)
        else -> "$octets B"
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
