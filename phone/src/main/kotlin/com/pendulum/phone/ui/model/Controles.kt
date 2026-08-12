package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.ui.nights.Controle
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.texte
import java.util.Locale

/**
 * La liste de controles qualite d'une nuit — **c'est ici qu'est le « pourquoi ce chiffre »**.
 *
 * Chaque ligne porte les trois choses ensemble : la valeur mesuree, le seuil qu'elle doit tenir,
 * et son etat. Les trois, toujours. Une valeur sans son seuil ne se juge pas, et un etat sans sa
 * valeur ne se verifie pas.
 *
 * L'explication est **generative et non attributive** : elle montre le chemin de calcul, pas des
 * contributions. On ne classe pas des facteurs par importance, on ne dit pas qu'un trou de signal
 * « explique » un indice bas. On dit ce qui a ete mesure, ce qui a ete retenu, et sur quel
 * denominateur — l'oeil fait le lien, le texte ne le fait pas.
 *
 * Tout est pur : aucun `Context`, aucune horloge, aucune E/S. La couverture d'echantillons en
 * particulier merite d'etre testee sur ses bornes, parce que c'est elle qui decide si la porte
 * P1 est franchie.
 */
object Controles {

    /**
     * Couverture d'echantillons, sur **le temps capteur** et jamais sur l'heure d'arrivee.
     *
     * C'est la regle non negociable de la porte P1, et elle est la meme que celle que
     * `GapMonitor` applique cote montre : en mode batche les echantillons arrivent par salves —
     * trente secondes de silence puis 1 500 evenements d'un coup — et une regle fondee sur
     * l'heure de livraison se declenche a chaque nuit en ne mesurant rien.
     *
     * Le denominateur est donc la duree **nominale** de la session multipliee par la cadence
     * demandee. Il reste une approximation : la duree horloge murale n'est pas exactement la
     * duree capteur quand l'horloge du systeme est ajustee en cours de nuit. L'ecart est de
     * l'ordre de la seconde sur huit heures, soit trois ordres de grandeur sous le critere de
     * 99 %, et le corriger demanderait de persister les bornes de `SensorEvent.timestamp` que le
     * format porte deja mais que la base n'extrait pas.
     *
     * ### Le numerateur n'existe qu'apres l'analyse, et le taire coutait un faux verdict
     *
     * `night_session.sampleCount` est ecrit par `AnalyzeWorker` et par lui seul. Tant qu'il n'a pas
     * tourne, la colonne vaut 0 — non pas parce qu'aucun echantillon n'est arrive, mais parce que
     * personne ne les a encore comptes. Ce fichier divisait alors 0 par le denominateur et rendait
     * `0.0`, que [couvertureTenue] juge `false` : une nuit **non analysee** etait rapportee « hors
     * P1 ».
     *
     * Le defaut n'est pas theorique. Le 3 aout 2026, a la premiere utilisation reelle de la porte,
     * une nuit de 32 minutes parfaitement transferee — sept chunks, sept accuses, 94 502
     * echantillons sur le disque du telephone — est sortie `NON_CONFORME` avec
     * `coverage=0.00000` (`docs/fr/BANC-ESSAI.md` §12.5). L'analyse n'avait pas tourne :
     * `analyzedAtMs` etait nul cinquante minutes apres la fermeture, l'application etant dans le
     * seau de veille `RESTRICTED` ou elle atterrit parce que personne ne l'ouvre jamais — ce qui
     * est **exactement** le cas d'usage decrit par le produit.
     *
     * Le test est donc `analyzedAtMs`, et non `sampleCount > 0` : une nuit analysee dont l'analyse
     * n'a effectivement retenu aucun echantillon a une couverture de 0 %, et celle-la est vraie.
     * Distinguer les deux est tout l'objet des trois etats de [PorteP1.Conformite] — « on ne sait
     * pas » n'est pas une commodite.
     *
     * @return la couverture dans `[0, 1]` ; `null` si la nuit n'a pas de fin connue — une session
     *   ouverte n'a pas de couverture, elle a une couverture *pour l'instant* — ou si l'analyse
     *   n'a pas encore tourne, auquel cas le numerateur n'a pas ete compte.
     */
    fun couverture(session: NightSessionEntity): Double? {
        if (session.analyzedAtMs == null) return null
        val fin = session.endWallMs ?: return null
        val dureeMs = fin - session.startWallMs
        if (dureeMs <= 0 || session.nominalRateHz <= 0) return null
        val attendus = dureeMs * session.nominalRateHz / 1000.0
        if (attendus <= 0.0) return null
        return (session.sampleCount / attendus).coerceIn(0.0, 1.0)
    }

    /** Seuil de la porte P1 : au moins 99 % des echantillons attendus. */
    const val COUVERTURE_MIN = 0.99

    /**
     * Seuil de la porte P1 : **strictement plus** de 20 % de batterie restante a huit heures.
     *
     * La comparaison est stricte parce que `01-overview.md` §5 ecrit « battery **above** 20 % » la
     * ou la couverture est ecrite « **at or above** 99 % » — la difference entre les deux
     * formulations est portee par le document et n'est pas une maladresse de redaction. A 20 %
     * pile, le code rendait « conforme » et la documentation « echec » : la borne exacte est
     * precisement celle qui bascule en silence, puisqu'elle ne se produit qu'une nuit sur
     * cinquante et ne ressemble jamais a un defaut. Le desaccord est tranche en faveur du
     * document, qui est ce que P1 signifie.
     */
    const val BATTERIE_MIN_PCT = 20

    /** Ecart tolere entre la cadence demandee et la cadence delivree. */
    const val TOLERANCE_FS = 0.05

    // -------------------------------------------------------------------------------------
    // Les trois predicats de la porte P1, ecrits une seule fois
    // -------------------------------------------------------------------------------------
    //
    // Ils sont ici et non dans [PorteP1] pour la raison que ce dernier donne deja de ne pas
    // recalculer la couverture : deux implementations d'un meme seuil finissent par diverger, et
    // la divergence porte sur le chiffre qui decide de la suite du projet. Le seuil de batterie
    // l'a fait — deux `>=` la ou le document ecrit « above » — et cela n'est apparu qu'en les
    // relisant cote a cote.
    //
    // `null` veut dire **on ne sait pas**, et non « non tenu » : la ligne de controle rend alors
    // un tiret, et la porte rend `INDETERMINE`. Les deux ecrans lisent la meme inconnue.

    /** Vrai si la couverture atteint le seuil, bornes comprises. `null` si elle est inconnue. */
    fun couvertureTenue(couverture: Double?): Boolean? = couverture?.let { it >= COUVERTURE_MIN }

    /** Vrai si la batterie est **strictement** au-dessus du seuil. Voir [BATTERIE_MIN_PCT]. */
    fun batterieTenue(pct: Int?): Boolean? = pct?.let { it > BATTERIE_MIN_PCT }

    /**
     * Vrai si la cadence delivree tient la tolerance. `null` quand elle n'a pas ete mesuree, ou
     * quand la cadence nominale est absurde — diviser par elle donnerait un verdict, pas une
     * mesure.
     *
     * La cadence demandee n'est pas la cadence delivree : 50 Hz sort couramment a 50,3 ou
     * 52,6 Hz, et un `fs` faux decale toute la datation des mouvements.
     */
    fun cadenceTenue(fs: Double?, nominalHz: Int): Boolean? =
        if (fs == null || nominalHz <= 0) null
        else kotlin.math.abs(fs - nominalHz) / nominalHz <= TOLERANCE_FS

    /** Le plus grand trou tolerable avant que le signal ne cesse d'etre exploitable. */
    const val PLUS_GRAND_TROU_MAX_S = 5.0

    /** Cumul de trous tolerable sur une nuit. */
    const val CUMUL_TROUS_MAX_S = 120.0

    /**
     * @param sourceSommeil le libelle **deja resolu** par [Mapping.libelleSource], et resolu une
     *   seule fois par l'appelant. Cette ligne l'ecrivait en dur (`HEALTH_CONNECT` des que le
     *   masque n'etait pas l'accelerometre) pendant que le bloc « pourquoi ce chiffre », sur le
     *   meme ecran, passait par `Mapping` : la meme nuit portait donc deux libelles de source
     *   differents, et rien ne disait lequel etait le bon. Le libelle a une seule origine.
     */
    fun de(
        session: NightSessionEntity,
        nuit: ComparableNight,
        resultat: PlmResultEntity?,
        sourceSommeil: UiText,
    ): List<Controle> = buildList {
        val couv = couverture(session)
        add(
            Controle(
                libelle = texte(R.string.night_detail_coverage),
                valeur = texte(couv?.let { pourcent(it) } ?: TIRET),
                seuil = texte(pourcent(COUVERTURE_MIN)),
                ok = couvertureTenue(couv),
            )
        )

        // Le cumul des trous est connu ; le plus grand trou individuel ne l'est pas — `GapMonitor`
        // le mesure sur la montre mais seul son total remonte dans la session. La ligne est
        // conservee avec un tiret plutot que supprimee : sa disparition ferait croire que le
        // controle n'existe pas, alors qu'il n'est pas encore transmis.
        add(
            Controle(
                libelle = texte(R.string.night_detail_largest_gap),
                valeur = texte(TIRET),
                seuil = texte("%.0f s".format(Locale.UK, PLUS_GRAND_TROU_MAX_S)),
                // `null` et non `true` : rien n'a ete mesure ici, donc rien n'est tenu. Un `✓`
                // sur une valeur absente affirme un controle qui n'a pas eu lieu.
                ok = null,
            )
        )
        val cumulS = session.gapTotalMs / 1000.0
        add(
            Controle(
                libelle = texte(R.string.night_detail_total_gaps),
                valeur = texte("%.0f s".format(Locale.UK, cumulS)),
                seuil = texte("%.0f s".format(Locale.UK, CUMUL_TROUS_MAX_S)),
                ok = cumulS <= CUMUL_TROUS_MAX_S,
            )
        )

        val fs = session.fsMeasuredHz
        add(
            Controle(
                libelle = texte(R.string.night_detail_frequency),
                valeur = texte(cadenceLisible(fs)),
                seuil = texte("${session.nominalRateHz} Hz"),
                ok = cadenceTenue(fs, session.nominalRateHz),
            )
        )

        val batterie = session.batteryPctLast
        add(
            Controle(
                libelle = texte(R.string.night_detail_battery_end),
                valeur = texte(batterie?.let { "$it%" } ?: TIRET),
                seuil = texte("$BATTERIE_MIN_PCT%"),
                ok = batterieTenue(batterie),
            )
        )

        // Le sommeil analysable, pas le sommeil enregistre : une nuit de 8 h dont 5 h sont
        // trouees n'en vaut pas 8, et c'est ce chiffre-la qui sert de denominateur.
        add(
            Controle(
                libelle = texte(R.string.night_detail_total_sleep),
                valeur = texte(Mapping.dureeLisible(nuit.analysableTstMin)),
                seuil = texte("4 h"),
                ok = nuit.analysableTstMin >= MIN_TST_MIN,
            )
        )

        add(
            Controle(
                libelle = texte(R.string.night_detail_sleep_source),
                valeur = sourceSommeil,
                seuil = texte(R.string.settings_health_connect),
                // Le denominateur doit venir d'un **autre** capteur que le numerateur. Quand il
                // vient du meme, le chiffre est circulaire : un traitement qui supprime des
                // mouvements baisse le numerateur et, du meme geste, monte le denominateur.
                ok = nuit.maskSource != Mapping.MASQUE_ACCELERO,
            )
        )

        // Le taux de manques est **mesure** par la deconvolution harmonique, pas suppose. Il est
        // a la fois un indicateur de qualite et le critere qui dit si deux nuits mesurent la
        // meme chose : deux nuits dont les taux different beaucoup ne se comparent pas.
        //
        // Quand l'ajustement du rythme a ete refuse — le cas frequent — il n'y a pas de taux du
        // tout. La ligne reste, avec un tiret et un etat inconnu : c'est la meme convention que la
        // ligne du plus grand trou ci-dessus, et pour la meme raison — un `✓` ou un `✗` sur une
        // valeur absente affirme un controle qui n'a pas eu lieu.
        resultat?.let {
            val taux = nuit.missRate
            add(
                Controle(
                    libelle = texte(R.string.night_detail_missed_rate),
                    valeur = texte(taux?.let { t -> pourcent(t) } ?: TIRET),
                    seuil = texte(pourcent(Mapping.SEUIL_MANQUES_NOTABLE)),
                    ok = taux?.let { t -> t <= Mapping.SEUIL_MANQUES_NOTABLE },
                )
            )
        }
    }

    /** Quatre heures. Sous ce seuil l'indice explose sur une poignee de mouvements groupes. */
    const val MIN_TST_MIN = 240.0

    /** `50.31 Hz`, ou le tiret. Partage avec [PorteP1], qui affiche la meme valeur. */
    internal fun cadenceLisible(fs: Double?): String =
        fs?.let { "%.2f Hz".format(Locale.UK, it) } ?: TIRET

    private const val TIRET = Mapping.TIRET

    private fun pourcent(v: Double) = Mapping.pourcent(v)
}
