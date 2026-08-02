package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.ui.nights.Controle
import com.pendulum.phone.ui.text.Textes
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
     * @return la couverture dans `[0, 1]`, ou `null` si la nuit n'a pas de fin connue — une
     *   session ouverte n'a pas de couverture, elle a une couverture *pour l'instant*.
     */
    fun couverture(session: NightSessionEntity): Double? {
        val fin = session.endWallMs ?: return null
        val dureeMs = fin - session.startWallMs
        if (dureeMs <= 0 || session.nominalRateHz <= 0) return null
        val attendus = dureeMs * session.nominalRateHz / 1000.0
        if (attendus <= 0.0) return null
        return (session.sampleCount / attendus).coerceIn(0.0, 1.0)
    }

    /** Seuil de la porte P1 : au moins 99 % des echantillons attendus. */
    const val COUVERTURE_MIN = 0.99

    /** Seuil de la porte P1 : plus de 20 % de batterie restante a huit heures. */
    const val BATTERIE_MIN_PCT = 20

    /** Ecart tolere entre la cadence demandee et la cadence delivree. */
    const val TOLERANCE_FS = 0.05

    /** Le plus grand trou tolerable avant que le signal ne cesse d'etre exploitable. */
    const val PLUS_GRAND_TROU_MAX_S = 5.0

    /** Cumul de trous tolerable sur une nuit. */
    const val CUMUL_TROUS_MAX_S = 120.0

    fun de(
        session: NightSessionEntity,
        nuit: ComparableNight,
        resultat: PlmResultEntity?,
    ): List<Controle> = buildList {
        val couv = couverture(session)
        add(
            Controle(
                libelle = Textes.Nuits.Detail.COUVERTURE,
                valeur = couv?.let { pourcent(it) } ?: TIRET,
                seuil = pourcent(COUVERTURE_MIN),
                ok = couv != null && couv >= COUVERTURE_MIN,
            )
        )

        // Le cumul des trous est connu ; le plus grand trou individuel ne l'est pas — `GapMonitor`
        // le mesure sur la montre mais seul son total remonte dans la session. La ligne est
        // conservee avec un tiret plutot que supprimee : sa disparition ferait croire que le
        // controle n'existe pas, alors qu'il n'est pas encore transmis.
        add(
            Controle(
                libelle = Textes.Nuits.Detail.PLUS_GRAND_TROU,
                valeur = TIRET,
                seuil = "%.0f s".format(Locale.UK, PLUS_GRAND_TROU_MAX_S),
                ok = true,
            )
        )
        val cumulS = session.gapTotalMs / 1000.0
        add(
            Controle(
                libelle = Textes.Nuits.Detail.CUMUL_TROUS,
                valeur = "%.0f s".format(Locale.UK, cumulS),
                seuil = "%.0f s".format(Locale.UK, CUMUL_TROUS_MAX_S),
                ok = cumulS <= CUMUL_TROUS_MAX_S,
            )
        )

        val fs = session.fsMeasuredHz
        add(
            Controle(
                libelle = Textes.Nuits.Detail.FREQUENCE,
                valeur = fs?.let { "%.2f Hz".format(Locale.UK, it) } ?: TIRET,
                seuil = "${session.nominalRateHz} Hz",
                // La cadence demandee n'est pas la cadence delivree : 50 Hz sort couramment a
                // 50,3 ou 52,6 Hz, et un `fs` faux decale toute la datation des mouvements.
                ok = fs != null &&
                    kotlin.math.abs(fs - session.nominalRateHz) / session.nominalRateHz <= TOLERANCE_FS,
            )
        )

        val batterie = session.batteryPctLast
        add(
            Controle(
                libelle = Textes.Nuits.Detail.BATTERIE_FIN,
                valeur = batterie?.let { "$it%" } ?: TIRET,
                seuil = "$BATTERIE_MIN_PCT%",
                ok = batterie != null && batterie >= BATTERIE_MIN_PCT,
            )
        )

        // Le sommeil analysable, pas le sommeil enregistre : une nuit de 8 h dont 5 h sont
        // trouees n'en vaut pas 8, et c'est ce chiffre-la qui sert de denominateur.
        add(
            Controle(
                libelle = Textes.Nuits.Detail.SOMMEIL_TOTAL,
                valeur = Mapping.dureeLisible(nuit.analysableTstMin),
                seuil = "4 h",
                ok = nuit.analysableTstMin >= MIN_TST_MIN,
            )
        )

        add(
            Controle(
                libelle = Textes.Nuits.Detail.SOURCE_SOMMEIL,
                valeur = if (nuit.maskSource == Mapping.MASQUE_ACCELERO) {
                    Textes.Reglages.MASQUE_ACCELERO_SEUL
                } else {
                    Textes.Reglages.HEALTH_CONNECT
                },
                seuil = Textes.Reglages.HEALTH_CONNECT,
                // Le denominateur doit venir d'un **autre** capteur que le numerateur. Quand il
                // vient du meme, le chiffre est circulaire : un traitement qui supprime des
                // mouvements baisse le numerateur et, du meme geste, monte le denominateur.
                ok = nuit.maskSource != Mapping.MASQUE_ACCELERO,
            )
        )

        // Le taux de manques est **mesure** par la deconvolution harmonique, pas suppose. Il est
        // a la fois un indicateur de qualite et le critere qui dit si deux nuits mesurent la
        // meme chose : deux nuits dont les taux different beaucoup ne se comparent pas.
        resultat?.let {
            add(
                Controle(
                    libelle = Textes.Nuits.Detail.TAUX_MANQUES,
                    valeur = pourcent(nuit.missRate),
                    seuil = pourcent(Mapping.SEUIL_MANQUES_NOTABLE),
                    ok = nuit.missRate <= Mapping.SEUIL_MANQUES_NOTABLE,
                )
            )
        }
    }

    /** Quatre heures. Sous ce seuil l'indice explose sur une poignee de mouvements groupes. */
    const val MIN_TST_MIN = 240.0

    private const val TIRET = "—"

    private fun pourcent(v: Double) = "%.1f%%".format(Locale.UK, v * 100)
}
