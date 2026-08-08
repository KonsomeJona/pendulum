package com.pendulum.phone.ui.model

import com.pendulum.phone.R
import com.pendulum.phone.ui.text.texte
import com.pendulum.phone.work.FetchSchedule

/**
 * La machine a cinq etats du reveil. **Une fonction pure, deux entrees, aucun effet de bord.**
 *
 * ### Pourquoi elle n'est pas au fond du ViewModel
 *
 * C'est la logique la plus lue de l'application : elle decide de ce que l'utilisateur voit chaque
 * matin, dans l'etat ou il est le moins capable de faire la part des choses. Les bornes qui
 * comptent — « tous les chunks sont arrives », « l'hypnogramme est la », « on a depasse
 * T+36 h » — sont exactement le genre de conditions qui se cassent en silence a la relecture d'un
 * `when` de trente lignes. Ici elles sont testees une par une, sans Android, sans base.
 *
 * ### L'ordre des branches est le message
 *
 * On lit **l'etat persiste avant l'horloge**, et le plus avance avant le moins avance : une nuit
 * analysee n'est jamais « en attente de transfert », meme si un chunk tardif arrive apres coup.
 * L'inverse produirait un ecran qui recule, et un ecran qui recule fait croire a une perte.
 *
 * ### L'etat 4 est le cas normal, et rien dans sa forme ne doit dire le contraire
 *
 * La synchronisation de la montre de poignet vers Health Connect obeit a la politique batterie du
 * constructeur, sans delai garanti — souvent plusieurs heures. Trois consequences tenues ici :
 *
 * 1. Le mot est « provisional », pas « failed ».
 * 2. Rien de rouge : [EtatReveil.Provisoire] n'est pas une [EtatReveil.Echec], donc il ne passe
 *    pas par `ErrorCard` et ne peut pas heriter de sa teinte.
 * 3. **Aucun indicateur de progression indetermine.** Material 3 calibre son *loading indicator*
 *    pour des attentes de moins de cinq secondes ; un cercle qui tourne six heures **est** un
 *    message de panne, quel que soit le texte a cote. Ce que cet etat a a montrer, il le montre
 *    en clair : derniere tentative, prochaine tentative, et l'echeance datee de l'abandon.
 */
object MachineReveil {

    /** `night_session.state` : la montre enregistre encore. */
    const val OUVERTE = "OPEN"

    /** Plus de chunk depuis 45 min. La montre s'est tue ; elle peut revenir. */
    const val SILENCIEUSE = "STALE"

    /** La nuit s'est arretee sans fermeture propre. Analysable, mais hors tendance. */
    const val TRONQUEE = "TRUNCATED"

    /**
     * Debit de transfert retenu pour l'estimation en minutes de l'etat 1.
     *
     * **C'est une estimation, pas une mesure**, et c'est pour cela qu'elle est ici et nommee.
     * Le Data Layer de Wear OS ne publie aucun debit ; 200 ko/s est l'ordre de grandeur observe
     * sur un lien Bluetooth classique avec la montre sur son socle. L'ecran arrondit a la minute
     * superieure et n'affiche jamais de secondes : une precision a la seconde sur un chiffre
     * devine serait exactement le genre de valeur qu'on cite ensuite dans un rapport de defaut.
     */
    const val DEBIT_ESTIME_OCTETS_PAR_S = 200_000L

    /** Taille moyenne d'un chunk, utilisee quand aucun octet n'est encore arrive. */
    const val TAILLE_CHUNK_ESTIMEE_OCTETS = 1_200_000L

    /**
     * Ce que la base sait de la derniere nuit, reduit a ce dont la machine a besoin.
     *
     * @param etatSession `night_session.state`, tel quel.
     * @param totalChunks annonce par la montre a la fermeture. `null` tant qu'elle n'a pas ferme :
     *   on ne peut alors ni calculer une fraction, ni dire combien il reste.
     * @param masqueSommeilApplique il existe une `sleep_window` de source Health Connect pour le
     *   `paramsHash` courant. C'est le seul fait qui dise que le chiffre affiche repose sur un
     *   denominateur independant — un `hc_snapshot` reussi ne suffit pas, le rescore peut ne pas
     *   avoir eu lieu.
     * @param hypnogrammeRecu le dernier `hc_snapshot` a retenu un enregistrement. Sert a
     *   distinguer « rien n'est arrive » de « c'est arrive, le rescore suit ».
     * @param integriteRejetee fraction des octets rejetes au controle d'integrite.
     */
    data class Faits(
        val sessionHex: String,
        val dateLisible: String,
        /** Fuseau de la nuit, tel que la session le porte. Une nuit se lit a l'heure vecue. */
        val zoneId: String,
        val etatSession: String,
        val chunksRecus: Int,
        val totalChunks: Int?,
        val octetsRecus: Long,
        val analyseeAtMs: Long?,
        val finDeNuitMs: Long?,
        val masqueSommeilApplique: Boolean,
        val hypnogrammeRecu: Boolean,
        val tentativesHc: Int,
        val derniereTentativeMs: Long?,
        val integriteRejetee: Double,
    )

    /** Au-dela, l'analyse n'a pas assemble un signal exploitable : c'est une panne, en rouge. */
    const val INTEGRITE_REJETEE_MAX = 0.05

    /**
     * @param formatHeure mise en forme d'un instant, injectee plutot qu'appelee : la machine
     *   calcule des instants (prochaine tentative, echeance d'abandon) et doit rester testable
     *   sans fuseau ni locale. C'est le meme motif que l'horloge parametree de `MachineAccueil`.
     */
    fun de(faits: Faits?, maintenantMs: Long, formatHeure: (Long) -> String): EtatReveil {
        if (faits == null) return EtatReveil.Rien

        // La montre enregistre : il n'y a pas de « reveil » a annoncer. La carte de l'accueil
        // porte deja l'enregistrement en cours, et le repeter ici volerait la seule ligne que P4
        // accorde a l'etat de la nuit d'hier.
        if (faits.etatSession == OUVERTE) return EtatReveil.Rien

        val panne = panneEventuelle(faits)
        if (panne != null) return EtatReveil.Echec(faits.dateLisible, panne)

        val total = faits.totalChunks
        val transfertFini = total != null && faits.chunksRecus >= total

        if (faits.analyseeAtMs == null) {
            return when {
                // Etat 1 : la montre a ferme, rien n'est encore arrive.
                total == null || faits.chunksRecus == 0 ->
                    EtatReveil.EnAttenteTransfert(
                        date = faits.dateLisible,
                        mo = megaoctets(resteEnOctets(faits)),
                        minutes = minutesEstimees(resteEnOctets(faits)),
                    )

                // Etat 2 : ca arrive. Le pourcentage ne recule jamais — il est calcule sur le
                // nombre de chunks presents en base, et un chunk deja recu ne disparait pas.
                !transfertFini ->
                    EtatReveil.Transfert(
                        recuMo = megaoctets(faits.octetsRecus),
                        totalMo = megaoctets(totalEstimeEnOctets(faits)),
                        chunk = faits.chunksRecus,
                        chunks = total,
                    )

                // Etat 3 : tout est la, l'analyse tourne. L'etape affichee est deduite de ce qui
                // est deja en base et non d'un compteur de progression que rien n'alimenterait :
                // les chunks sont assembles, donc on detecte ; l'hypnogramme est la, donc on
                // croise.
                else -> EtatReveil.Analyse(
                    date = faits.dateLisible,
                    etape = if (faits.hypnogrammeRecu) EtapeAnalyse.CROISEMENT else EtapeAnalyse.DETECTION,
                    secondesRestantes = SECONDES_ANALYSE_ESTIMEES,
                )
            }
        }

        // Etat 5 -> complet : le masque de sommeil independant est applique, la nuit est finie.
        if (faits.masqueSommeilApplique) return EtatReveil.Rien

        // Etat 4 : le cas normal du reveil.
        val fin = faits.finDeNuitMs ?: return EtatReveil.Rien
        val plan = FetchSchedule.plan(faits.tentativesHc, fin, maintenantMs)
        val abandonMs = fin + FetchSchedule.GIVE_UP_MS

        return EtatReveil.Provisoire(
            date = faits.dateLisible,
            derniereTentative = faits.derniereTentativeMs?.let(formatHeure),
            prochaineTentative = (plan as? FetchSchedule.Plan.Retry)
                ?.let { formatHeure(maintenantMs + it.delayMs) },
            abandonA = formatHeure(abandonMs),
            abandonne = plan is FetchSchedule.Plan.GiveUp,
        )
    }

    /**
     * Les deux pannes que la base permet reellement de constater.
     *
     * On s'interdit d'en deviner d'autres. « L'analyse a echoue » ne se lit nulle part : un
     * `AnalyzeWorker` qui rend `retry` ne laisse aucune trace en base, donc l'inferer d'un delai
     * ecoule produirait une carte rouge sur une nuit parfaitement saine dont le telephone etait
     * simplement occupe. Une panne affichee a tort coute plus cher qu'une panne tue : elle apprend
     * a ignorer le rouge.
     */
    private fun panneEventuelle(faits: Faits): ErreurPendulum? {
        if (faits.integriteRejetee > INTEGRITE_REJETEE_MAX) {
            return ErreurPendulum(
                code = "E-ANA-01",
                titre = texte(R.string.error_ana_01_title),
                cause = texte(R.string.error_ana_01_cause),
                action = texte(R.string.error_ana_01_action),
                bouton = texte(R.string.error_ana_01_button),
                technique = true,
            )
        }
        val total = faits.totalChunks
        if (faits.etatSession == TRONQUEE && total != null && faits.chunksRecus < total) {
            return ErreurPendulum(
                code = "E-NIGHT-07",
                titre = texte(R.string.error_night_07_title),
                cause = texte(R.string.error_night_07_cause),
                action = texte(R.string.error_night_07_action),
                bouton = texte(R.string.error_night_07_button),
                technique = true,
            )
        }
        return null
    }

    /** Estimation en secondes de l'analyse restante. Fixe : rien ne mesure encore sa duree. */
    const val SECONDES_ANALYSE_ESTIMEES = 40

    private fun totalEstimeEnOctets(faits: Faits): Long {
        val total = faits.totalChunks ?: return faits.octetsRecus
        if (faits.chunksRecus <= 0) return total * TAILLE_CHUNK_ESTIMEE_OCTETS
        // Extrapolation sur la taille moyenne **observee** pour cette nuit-la, et non sur une
        // constante : le nombre d'echantillons par chunk varie avec le mode FIFO.
        return faits.octetsRecus / faits.chunksRecus * total
    }

    private fun resteEnOctets(faits: Faits): Long =
        (totalEstimeEnOctets(faits) - faits.octetsRecus).coerceAtLeast(0L)

    /** Un chiffre apres la virgule : `8.8`. Au-dela, on afficherait du bruit de comptage. */
    internal fun megaoctets(octets: Long): String =
        "%.1f".format(java.util.Locale.UK, octets / 1_000_000.0)

    /** Arrondi a la minute superieure, jamais zero : « 0 minute » se lit « c'est fini ». */
    internal fun minutesEstimees(octets: Long): Int {
        val secondes = octets.toDouble() / DEBIT_ESTIME_OCTETS_PAR_S
        return kotlin.math.ceil(secondes / 60.0).toInt().coerceAtLeast(1)
    }
}
