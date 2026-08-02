package com.pendulum.phone.ui.model

import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.ui.text.Textes
import java.time.LocalDate
import java.time.ZoneId

/**
 * La porte P1, rendue **calculable** — c'est-a-dire verifiable sans le souvenir de personne.
 *
 * `01-overview.md` §5 declare P1 bloquante et lui donne trois criteres : couverture d'au moins
 * 99 % des echantillons attendus **mesuree sur les ecarts de `SensorEvent.timestamp`**, batterie
 * au-dessus de 20 % a huit heures, et reproductibilite sur **trois nuits consecutives**. Aucune
 * ligne d'algorithme n'est censee etre ecrite avant que ces trois-la soient tenus, et c'est
 * pourtant le seul jalon du projet qu'aucun code ne verifiait.
 *
 * ### Ce que ce fichier peut faire, et ce qu'il ne peut pas
 *
 * Il ne fait pas porter la montre. Il rend le verdict **lisible et exportable**, pour que la
 * decision qui suit — continuer sur Wear OS, ou basculer sur un enregistreur dedie type Axivity
 * AX3 — repose sur un fichier plutot que sur une impression.
 *
 * ### Tout est pur
 *
 * Aucun `Context`, aucune horloge, aucune E/S : les bornes de cette porte sont exactement le
 * genre de conditions qui se cassent en silence, et elles se testent une par une. La couverture
 * elle-meme n'est pas recalculee ici — elle est deja dans [Controles.couverture], qui applique la
 * regle du temps capteur et porte sa propre justification. Deux implementations du meme
 * pourcentage finiraient par diverger, et la divergence porterait sur le chiffre qui decide de
 * la suite du projet.
 */
object PorteP1 {

    /**
     * Trois nuits **consecutives**, et non trois nuits qui passent.
     *
     * La distinction n'est pas une lecture stricte du document, c'est la seule qui mesure ce que
     * la porte veut mesurer. « Trois nuits conformes » se satisfait d'une nuit reussie en mars,
     * d'une en avril et d'une en mai, c'est-a-dire de trois coups de chance separes par des
     * echecs qu'on ne compte pas. La reproductibilite est justement la propriete qui distingue
     * un montage qui tient d'un montage qui a tenu une fois.
     *
     * Une nuit non enregistree casse donc la serie au meme titre qu'une nuit non conforme : la
     * porte demande trois nuits **de suite**, et sauter la nuit qui suit un echec est exactement
     * le geste que les garde-fous de ce projet existent pour rendre impossible.
     */
    const val NUITS_CONSECUTIVES = 3

    /** Huit heures : la duree sur laquelle la porte demande la batterie restante. */
    const val DUREE_CIBLE_H = 8.0

    /**
     * Quatorze nuits a l'ecran. Deux semaines : assez pour contenir une serie de trois et les
     * echecs qui l'entourent, ce qui est precisement ce qu'il faut voir ensemble. L'export CSV,
     * lui, ne tronque rien.
     */
    const val NUITS_RAPPORT = 14

    /**
     * Trois etats et non deux.
     *
     * [INDETERMINE] n'est pas une commodite : une nuit de six heures ne dit rien sur la batterie
     * a huit heures, et la ranger avec les nuits conformes ferait franchir la porte a une
     * campagne qui ne l'a pas franchie. La ranger avec les echecs accuserait un montage dont
     * rien ne prouve qu'il a echoue. Une porte qui ne sait pas doit le dire.
     */
    enum class Conformite { CONFORME, NON_CONFORME, INDETERMINE }

    /** Un critere : sa valeur mesuree, son seuil, son etat. Les trois, toujours — comme [Controles]. */
    data class Critere(
        val libelle: String,
        val valeur: String,
        val seuil: String,
        val etat: Conformite,
    )

    /**
     * Le verdict d'une nuit.
     *
     * @param soiree la date de la **soiree** a laquelle la nuit se rattache, bascule a midi, dans
     *   le fuseau ou la nuit a ete vecue. C'est elle qui porte la consecutivite : un coucher a
     *   1 h 30 appartient a la soiree de la veille, et compter deux soirees la ou il n'y en a
     *   qu'une allongerait artificiellement une serie.
     */
    data class VerdictNuit(
        val sessionHex: String,
        val dateLisible: String,
        val soiree: LocalDate,
        val couverture: Critere,
        val batterie: Critere,
        val frequence: Critere,
    ) {
        val criteres: List<Critere> get() = listOf(couverture, batterie, frequence)

        /**
         * Le pire des trois, dans cet ordre : un echec l'emporte sur une inconnue, et une
         * inconnue sur une reussite. Une nuit dont la batterie est tombee sous le seuil n'est pas
         * « indeterminee » parce que sa frequence manque.
         */
        val verdict: Conformite
            get() = when {
                criteres.any { it.etat == Conformite.NON_CONFORME } -> Conformite.NON_CONFORME
                criteres.any { it.etat == Conformite.INDETERMINE } -> Conformite.INDETERMINE
                else -> Conformite.CONFORME
            }
    }

    /**
     * Le verdict de campagne.
     *
     * @param serieMax la plus longue suite de nuits conformes **sur des soirees consecutives**.
     *   C'est le seul chiffre qui reponde a la question posee par la porte ; [nuitsConformes] est
     *   la pour le contexte et ne franchit rien a lui seul.
     */
    data class Campagne(
        val nuitsExaminees: Int,
        val nuitsConformes: Int,
        val serieMax: Int,
        val debutSerie: String?,
        val finSerie: String?,
    ) {
        val franchie: Boolean get() = serieMax >= NUITS_CONSECUTIVES
    }

    fun de(session: NightSessionEntity): VerdictNuit {
        val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.systemDefault())
        return VerdictNuit(
            sessionHex = session.sessionHex,
            dateLisible = Mapping.dateLisible(session.startWallMs, session.zoneId),
            // La cle de nuit de `WirePaths`, et non la date civile du debut : c'est la convention
            // qui rattache deja une nuit a son contexte scelle, et en poser une seconde ici
            // produirait deux calendriers dans la meme application.
            soiree = LocalDate.parse(WirePaths.nightKey(session.startWallMs, zone)),
            couverture = couverture(session),
            batterie = batterie(session),
            frequence = frequence(session),
        )
    }

    fun campagne(verdicts: List<VerdictNuit>): Campagne {
        val tries = verdicts.sortedBy { it.soiree }
        var courante = ArrayList<VerdictNuit>()
        var meilleure = emptyList<VerdictNuit>()
        for (v in tries) {
            val precedente = courante.lastOrNull()
            courante = when {
                v.verdict != Conformite.CONFORME -> ArrayList()
                // La veille exactement. Une soiree sautee rompt la serie ; une soiree repetee —
                // deux sessions rattachees a la meme nuit — ne l'allonge pas.
                precedente != null && v.soiree == precedente.soiree.plusDays(1) ->
                    courante.apply { add(v) }
                else -> arrayListOf(v)
            }
            if (courante.size > meilleure.size) meilleure = courante.toList()
        }
        return Campagne(
            nuitsExaminees = verdicts.size,
            nuitsConformes = verdicts.count { it.verdict == Conformite.CONFORME },
            serieMax = meilleure.size,
            debutSerie = meilleure.firstOrNull()?.dateLisible,
            finSerie = meilleure.lastOrNull()?.dateLisible,
        )
    }

    // -------------------------------------------------------------------------------------
    // Les trois criteres
    // -------------------------------------------------------------------------------------

    /**
     * Couverture d'echantillons, telle que [Controles.couverture] la calcule — sur le temps
     * capteur, jamais sur l'heure d'arrivee — et jugee par [Controles.couvertureTenue].
     *
     * Ni le calcul ni le comparateur ne sont refaits ici. Deux ecritures d'un meme seuil rendent
     * deux verdicts sur la meme nuit, et le desaccord ne se voit qu'au moment de trancher.
     */
    private fun couverture(session: NightSessionEntity): Critere {
        val c = Controles.couverture(session)
        return Critere(
            libelle = Textes.Nuits.Detail.COUVERTURE,
            valeur = c?.let(::pourcent) ?: TIRET,
            seuil = Textes.P1.auMoins(pourcent(Controles.COUVERTURE_MIN)),
            etat = verdict(Controles.couvertureTenue(c)),
        )
    }

    /**
     * Un predicat a trois issues. `null` est **on ne sait pas**, jamais « non tenu » : une nuit
     * dont la cadence n'a pas ete mesuree n'a pas echoue, elle n'a pas ete jugee.
     */
    private fun verdict(tenu: Boolean?): Conformite = when (tenu) {
        null -> Conformite.INDETERMINE
        true -> Conformite.CONFORME
        false -> Conformite.NON_CONFORME
    }

    /**
     * La batterie restante **a huit heures**, et l'inference qu'on a le droit d'en tirer.
     *
     * Ce que la base porte est le dernier niveau rapporte par la montre et lui seul : le niveau
     * de depart n'est stocke nulle part (`night_session` n'a que `batteryPctLast`, la montre
     * garde sa serie complete et n'en publie que le dernier point). La consommation d'une nuit
     * n'est donc pas calculable, et l'extrapoler depuis un depart suppose a 100 % reviendrait a
     * fabriquer le chiffre meme que la porte existe pour mesurer.
     *
     * Restent trois lectures, toutes exactes :
     *  - la nuit a dure huit heures ou plus : le niveau rapporte **est** le niveau a huit heures ;
     *  - la nuit a ete plus courte et le niveau est deja au seuil ou en dessous : il ne remontera
     *    pas, donc la nuit echoue, et l'affirmer ne suppose rien ;
     *  - la nuit a ete plus courte et le niveau tient encore : on ne sait pas, et on le dit.
     *
     * Le comparateur est **strict** — 20 % pile echoue. C'est la lecture de `01-overview.md` §5
     * (« battery above 20 % »), et c'est celle de [Controles.BATTERIE_MIN_PCT], ou elle porte sa
     * justification. Les deux fichiers doivent rendre le meme verdict sur la meme nuit.
     */
    private fun batterie(session: NightSessionEntity): Critere {
        val pct = session.batteryPctLast
        val heures = heuresEnregistrees(session)
        val atteintHuitHeures = heures != null && heures >= DUREE_CIBLE_H
        return Critere(
            libelle = Textes.Nuits.Detail.BATTERIE_FIN,
            valeur = when {
                pct == null -> TIRET
                heures == null -> "$pct%"
                else -> "$pct%  ·  ${Mapping.dureeLisible(heures * 60.0)}"
            },
            seuil = Textes.P1.batterieSeuil(Controles.BATTERIE_MIN_PCT, DUREE_CIBLE_H.toInt()),
            // Le seul des trois criteres qui ne se reduise pas a [verdict] : un niveau tenu ne
            // conclut que si la nuit a effectivement atteint huit heures.
            etat = when (Controles.batterieTenue(pct)) {
                null -> Conformite.INDETERMINE
                false -> Conformite.NON_CONFORME
                true -> if (atteintHuitHeures) Conformite.CONFORME else Conformite.INDETERMINE
            },
        )
    }

    /**
     * La cadence delivree contre la cadence demandee. Elle n'est pas un critere ecrit de P1, et
     * elle est ici parce qu'elle conditionne les deux autres : un `fs` faux decale toute la
     * datation des mouvements, et une couverture calculee sur un nominal que le capteur ne tient
     * pas mesure le nominal, pas le capteur.
     */
    private fun frequence(session: NightSessionEntity): Critere {
        val nominal = session.nominalRateHz
        return Critere(
            libelle = Textes.Nuits.Detail.FREQUENCE,
            valeur = Controles.cadenceLisible(session.fsMeasuredHz),
            seuil = Textes.P1.frequenceSeuil(nominal, pourcent(Controles.TOLERANCE_FS)),
            etat = verdict(Controles.cadenceTenue(session.fsMeasuredHz, nominal)),
        )
    }

    /**
     * Duree de la nuit en heures, ou `null` tant qu'elle n'a pas de fin connue.
     *
     * C'est une duree d'horloge murale, la meme approximation que celle qu'assume
     * [Controles.couverture] : l'ecart avec la duree capteur est de l'ordre de la seconde sur
     * huit heures, soit trois ordres de grandeur sous ce qui separerait une nuit de sept heures
     * cinquante d'une nuit de huit heures.
     */
    internal fun heuresEnregistrees(session: NightSessionEntity): Double? {
        val fin = session.endWallMs ?: return null
        val ms = fin - session.startWallMs
        return if (ms <= 0) null else ms / 3_600_000.0
    }

    private const val TIRET = Mapping.TIRET

    private fun pourcent(v: Double) = Mapping.pourcent(v)
}
