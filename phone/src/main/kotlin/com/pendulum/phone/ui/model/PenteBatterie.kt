package com.pendulum.phone.ui.model

import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.TelemetryPointEntity

/**
 * La batterie a huit heures, **extrapolee sur une pente** et non lue sur un dernier pourcentage.
 *
 * ### Le probleme que ce fichier resout
 *
 * Le second critere de P1 est « batterie au-dessus de 20 % a huit heures ». Jusqu'ici la base ne
 * portait que `night_session.batteryPctLast`, le dernier niveau rapporte, et rien de plus : ni le
 * niveau de depart, ni la serie. Trois lectures seulement etaient exactes, et [PorteP1] les fait
 * toujours — une nuit de huit heures dit son niveau a huit heures, une nuit courte deja sous le
 * seuil a echoue, une nuit courte encore au-dessus ne dit rien.
 *
 * La troisieme est la plus frequente, et c'est celle qui bloquait. Le 3 aout 2026, la premiere
 * veille reelle a dure 32 minutes avec `level: 100` aux six pas de mesure et `Discharge: 0 mAh`
 * dans `batterystats` (`docs/fr/BANC-ESSAI.md` §12.4) : le pourcentage est trop quantifie pour
 * bouger sur une demi-heure. Le seul moyen envisage pour le chiffrer etait de garder un lien ADB
 * pendant la nuit, c'est-a-dire de laisser la montre sur son socle — donc de fausser la grandeur
 * mesuree, puisqu'une montre sur son socle est une montre en charge.
 *
 * ### Ce qu'on regresse, et pourquoi c'est celui-la
 *
 * `batteryChargeUah` — `BATTERY_PROPERTY_CHARGE_COUNTER`, le compteur coulombmetrique. Il compte
 * des micro-amperes-heures, pas des paliers de un pour cent : il bouge pendant qu'un pourcentage
 * ne bouge pas, et une pente s'extrapole. C'est ce champ qui rend le critere batterie decidable
 * **sans garder la montre sur son socle**, et c'est la raison pour laquelle il est dans le bloc de
 * telemetrie.
 *
 * ### Ce que l'extrapolation suppose — et il faut le dire, parce que rien ne le verifie
 *
 *  1. **Que la decharge est affine sur les huit heures.** Elle ne l'est pas exactement : une
 *     batterie lithium-ion se decharge un peu plus vite pres du plafond et pres du plancher, et
 *     une nuit n'a pas une charge de fond constante — un reveil, une salve Bluetooth, une reprise
 *     de service consomment par a-coups. Ce que la pente mesure est donc la **consommation
 *     moyenne de la fenetre observee**, projetee telle quelle. Sur une fenetre courte prise au
 *     debut de la nuit, c'est optimiste dans les deux sens et on ne sait pas dans lequel.
 *  2. **Que la capacite pleine est celle qu'on estime ici**, par la mediane de
 *     `charge x 100 / pourcentage` sur les points ou les deux sont lisibles. La capacite nominale
 *     de l'appareil n'est nulle part dans le protocole, et l'estimer depuis les deux grandeurs
 *     transmises evite d'en faire une constante qui vieillirait avec la batterie. Le prix est que
 *     le pourcentage extrapole herite de la quantification du pourcentage : a 1 % pres, c'est-a-dire
 *     bien assez pour un seuil a 20 %, pas assez pour une lecture au dixieme.
 *  3. **Que les points sous charge ne disent rien de l'autonomie**, donc qu'ils sortent. C'est le
 *     seul point ou l'exclusion est certaine plutot que raisonnee : un compteur qui remonte pendant
 *     une charge inverserait le signe de la pente, et le §12.4 est precisement une mesure faite
 *     montre sur son socle.
 *
 * ### Sur combien de points elle refuse de conclure
 *
 * [POINTS_MIN] points retenus, et [DUREE_MIN_H] d'etendue observee entre le premier et le
 * dernier. A un point par minute, les deux conditions decrivent la meme demi-heure : trente et un
 * points consecutifs couvrent trente intervalles d'une minute, et c'est de la que sort le chiffre
 * impair.
 *
 * Le seuil n'est pas rond par gout. Sous une demi-heure, deux choses dominent la pente et ni l'une
 * ni l'autre n'est de la consommation de nuit : le pas du compteur lui-meme, qui avance par bonds
 * de plusieurs dizaines de micro-amperes-heures, et le transitoire de demarrage — l'ecran vient
 * d'etre eteint, le service vient de s'enregistrer, le capteur vient de remplir son premier FIFO.
 * **Une pente sur trois points n'est pas une pente**, c'est une droite qui passe par trois points.
 *
 * Le refus est un `null` et jamais une valeur prudente. Une extrapolation qu'on n'a pas les moyens
 * de faire doit rendre « pas decidable », ce que [PorteP1] traduit en `INDETERMINE` — le troisieme
 * etat existe pour ca.
 *
 * Tout est pur : ni `Context`, ni horloge, ni E/S.
 */
object PenteBatterie {

    /**
     * Points retenus minimum.
     *
     * Trente et un et non trente : a un point par minute, trente et un points couvrent trente
     * intervalles, c'est-a-dire exactement la demi-heure que [DUREE_MIN_H] exige. Les deux
     * conditions se rejoindraient mal a trente — le compte passerait, l'etendue non, et le refus
     * serait attribue a la mauvaise cause en lisant le code.
     *
     * Voir la KDoc de l'objet pour ce que ce seuil ecarte.
     */
    const val POINTS_MIN = 31

    /**
     * Etendue minimale entre le premier et le dernier point retenu, en heures.
     *
     * Elle n'est pas redondante avec [POINTS_MIN], meme si les deux coincident a la cadence
     * actuelle : le compte dit **combien de mesures** portent la droite, l'etendue dit **sur quelle
     * duree** — et c'est l'etendue, elle seule, qui borne ce qu'une extrapolation a huit heures
     * peut valoir. Le jour ou la montre publierait un point toutes les dix secondes, trente et un
     * points ne feraient plus que cinq minutes, et le compte laisserait passer une droite ajustee
     * sur le transitoire de demarrage. La condition qui protege ce cas est celle-ci.
     */
    const val DUREE_MIN_H = 0.5

    /**
     * Une extrapolation aboutie.
     *
     * @param pointsRetenus points effectivement entres dans la regression, apres retrait des
     *   points sous charge et des lectures de compteur absentes.
     * @param pointsSousCharge points retires **parce qu'ils etaient sous charge**. Ils sont
     *   comptes et rendus : une nuit dont la moitie des points sort pour cette raison est une nuit
     *   passee sur son socle, et ce fait vaut d'etre lisible a cote du chiffre.
     * @param etendueObserveeH duree entre le premier et le dernier point retenu.
     * @param penteUahParH pente de la droite des moindres carres, en micro-amperes-heures par
     *   heure. Negative en decharge, et c'est le seul signe accepte — voir [de].
     * @param capaciteUah capacite pleine estimee, mediane de `charge x 100 / pourcentage`.
     * @param pctA8h pourcentage restant extrapole a huit heures **depuis le debut de la nuit**, et
     *   non depuis le premier point retenu : l'origine des temps est le premier point de la
     *   telemetrie, y compris s'il etait sous charge. Peut etre negatif — une batterie qui serait
     *   a plat avant huit heures doit le dire, et pas s'arreter poliment a zero.
     */
    data class Extrapolation(
        val pointsRetenus: Int,
        val pointsSousCharge: Int,
        val etendueObserveeH: Double,
        val penteUahParH: Double,
        val capaciteUah: Double,
        val pctA8h: Double,
    ) {
        /** Consommation moyenne de la fenetre, en points de pourcentage par heure. Positive. */
        val pctParHeure: Double get() = -penteUahParH * 100.0 / capaciteUah
    }

    /**
     * @param points la telemetrie d'une nuit, dans n'importe quel ordre — la regression n'en
     *   depend pas.
     * @param dureeCibleH l'horizon d'extrapolation. [PorteP1.DUREE_CIBLE_H] en pratique ; c'est un
     *   parametre pour que le test puisse verifier la droite sur un horizon ou le calcul se fait
     *   de tete.
     * @return `null` des que l'une des conditions de refus est atteinte. Les quatre sont
     *   explicites dans le corps, et aucune ne rend une valeur de repli.
     */
    fun de(points: List<TelemetryPointEntity>, dureeCibleH: Double): Extrapolation? {
        if (points.isEmpty()) return null

        // L'origine des temps est le premier point de la nuit, charge comprise : le critere parle
        // de huit heures **de nuit**, pas de huit heures de decharge. Prendre le premier point
        // retenu comme origine decalerait l'horizon d'autant de minutes que la montre a passe sur
        // son socle, et l'ecart ne se verrait nulle part.
        val origineNs = points.minOf { it.elapsedRealtimeNs }

        val sousCharge = points.count { it.charging }
        val retenus = points.filter {
            !it.charging && it.batteryChargeUah != TelemetryPoint.CHARGE_INCONNUE
        }
        if (retenus.size < POINTS_MIN) return null

        val heuresDe = { p: TelemetryPointEntity ->
            (p.elapsedRealtimeNs - origineNs) / 3_600_000_000_000.0
        }
        val etendue = retenus.maxOf(heuresDe) - retenus.minOf(heuresDe)
        if (etendue < DUREE_MIN_H) return null

        // Capacite pleine, estimee sur les seuls points ou les deux grandeurs sont lisibles. Le
        // pourcentage a zero est ecarte : il ferait une division par zero, et un appareil a 0 %
        // qui enregistre encore n'est de toute facon pas une lecture a croire.
        val capacites = retenus
            .filter { it.batteryPct in 1..100 }
            .map { it.batteryChargeUah * 100.0 / it.batteryPct }
        val capacite = mediane(capacites) ?: return null
        if (capacite <= 0.0) return null

        // Moindres carres ordinaires. Trois sommes, aucune bibliotheque : la droite est le seul
        // estimateur dont on puisse ecrire les hypotheses en entier, ce qui est precisement ce que
        // la KDoc de cet objet doit faire.
        val n = retenus.size
        val tMoyen = retenus.sumOf(heuresDe) / n
        val yMoyen = retenus.sumOf { it.batteryChargeUah.toDouble() } / n
        var num = 0.0
        var den = 0.0
        for (p in retenus) {
            val dt = heuresDe(p) - tMoyen
            num += dt * (p.batteryChargeUah - yMoyen)
            den += dt * dt
        }
        if (den <= 0.0) return null
        val pente = num / den

        // Une pente nulle ou positive hors charge n'est pas une bonne nouvelle, c'est une lecture
        // qu'on ne sait pas interpreter : soit le compteur n'a pas bouge de son pas de
        // quantification, soit il compte autre chose que ce qu'on croit. Extrapoler dessus
        // annoncerait une autonomie infinie, ce qui est le faux vert le plus cher possible ici.
        if (pente >= 0.0) return null

        val ordonnee = yMoyen - pente * tMoyen
        val chargeCible = ordonnee + pente * dureeCibleH
        return Extrapolation(
            pointsRetenus = n,
            pointsSousCharge = sousCharge,
            etendueObserveeH = etendue,
            penteUahParH = pente,
            capaciteUah = capacite,
            pctA8h = chargeCible * 100.0 / capacite,
        )
    }

    private fun mediane(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val t = v.sorted()
        val m = t.size / 2
        return if (t.size % 2 == 1) t[m] else (t[m - 1] + t[m]) / 2.0
    }
}
