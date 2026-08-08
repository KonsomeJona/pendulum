package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.Formats
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.texte
import java.util.Locale

/**
 * « Pourquoi ce chiffre » — le chemin de calcul, et rien d'autre.
 *
 * ### Le seul « pourquoi » qui soit legitime ici
 *
 * Il est **generatif**, pas attributif : il montre par ou le chiffre est passe — combien de
 * sommeil analysable, combien de mouvements retenus, sous quelle regle, divise par quoi — et il
 * s'arrete la. Aucun classement de facteurs par importance, aucune contribution chiffree, aucun
 * « le trou de signal explique 30 % de l'ecart ».
 *
 * Ce n'est pas de la prudence de forme. Les methodes d'attribution par contribution — Shapley et
 * ses derives — ne distinguent pas correlation et causalite, et sur-attribuent des que les
 * variables d'entree sont correlees entre elles. Or elles le sont massivement ici : la duree de
 * sommeil analysable, le nombre de mouvements et le taux de manques bougent ensemble par
 * construction. Un classement affiche sous un chiffre de sante se lit comme une cause, et c'est
 * le mode de defaillance identifie comme critique dans ce domaine.
 *
 * La derniere ligne du bloc fait donc tout le travail : *ce tableau montre comment le chiffre est
 * obtenu ; il n'indique pas ce qui a cause ces mouvements.* Elle n'est pas optionnelle et elle
 * n'est pas parametrable — elle fait partie du type ([Bloc.avertissement]).
 *
 * ### Tout est deja calcule
 *
 * Le taux de manques et l'encadrement respiratoire sont produits par `:algo` et persistes dans
 * `plm_result` (`missRate`, `plmiRespWorstCase`) depuis le debut. Ils n'etaient simplement
 * affiches nulle part. Ce fichier ne calcule aucune grandeur nouvelle : il met en forme.
 */
object CheminDeCalcul {

    /** Une ligne du tableau : un libelle, une valeur, et une note quand la valeur se discute. */
    data class Ligne(val libelle: UiText, val valeur: UiText, val note: UiText? = null)

    /**
     * Le bloc complet. [avertissement] est un champ et non un texte pose par l'ecran : un bloc de
     * chemin de calcul sans sa phrase de non-causalite ne doit pas pouvoir exister.
     */
    data class Bloc(
        val titre: UiText,
        val lignes: List<Ligne>,
        val avertissement: UiText = texte(R.string.night_why_disclaimer),
    )

    /**
     * @param dureeEnregistreeMin duree de la session, du demarrage a l'arret. Elle sert de
     *   contexte au sommeil analysable : « 5 h 12 » ne dit rien, « 5 h 12 sur 7 h 41
     *   enregistrees » dit ou est passe le reste.
     * @param mouvementsRetenus le numerateur, tel qu'il a servi. Ce n'est pas le nombre
     *   d'evenements detectes : les rejets de posture et de duree sont deja sortis.
     * @param regle le jeu de regles applique, deja mis en forme.
     * @param sourceSommeil le libelle de la source, deja resolu par [Mapping.libelleSource].
     * @param metrologie ce que la telemetrie de la nuit dit de l'appareil, ou `null` quand la nuit
     *   n'en porte pas. Voir [ajouterMetrologie] pour ce que ces trois lignes ont le droit de dire.
     */
    fun de(
        n: ComparableNight,
        resultat: PlmResultEntity?,
        dureeEnregistreeMin: Double,
        mouvementsRetenus: Int,
        regle: UiText,
        sourceSommeil: UiText,
        metrologie: Metrologie.Resume? = null,
    ): Bloc? {
        if (resultat == null) return null
        val masqueIndependant = n.maskSource != Mapping.MASQUE_ACCELERO

        val lignes = buildList {
            add(
                Ligne(
                    libelle = texte(R.string.night_why_analysable_sleep),
                    valeur = texte(Mapping.dureeLisible(n.analysableTstMin)),
                    note = texte(
                        R.string.night_why_of_recorded,
                        Mapping.dureeLisible(dureeEnregistreeMin),
                    ),
                ),
            )
            add(
                Ligne(
                    texte(R.string.night_why_movements_counted),
                    texte(mouvementsRetenus.toString()),
                ),
            )
            add(Ligne(texte(R.string.night_why_rule), regle))
            add(
                Ligne(
                    libelle = texte(R.string.night_why_mask),
                    valeur = texte(
                        R.string.night_why_mask_value,
                        sourceSommeil,
                        texte(
                            if (masqueIndependant) R.string.night_why_mask_hypnogram
                            else R.string.night_why_mask_accel,
                        ),
                    ),
                ),
            )
            // Le denominateur est la seule ligne qui porte un jugement, et c'est un jugement
            // structurel : il vient du meme capteur que le numerateur, ou il n'en vient pas.
            add(
                Ligne(
                    libelle = texte(R.string.night_why_denominator),
                    valeur = texte(
                        if (masqueIndependant) R.string.night_why_denominator_independent
                        else R.string.night_why_denominator_circular,
                    ),
                ),
            )
            // En pourcentage, comme la table de qualite du meme ecran (`Controles`) : le meme
            // `missRate` s'y lisait « 31.1% » et ici « 0.39 ». Deux ecritures d'une seule grandeur,
            // a deux cartes de distance, dont l'une sans unite — rien ne disait au lecteur qu'il
            // regardait deux fois le meme nombre.
            add(
                Ligne(
                    texte(R.string.night_why_missed_rate),
                    texte(Mapping.pourcent(n.missRate)),
                ),
            )

            // L'encadrement respiratoire : `plmiRespWorstCase` est l'index qu'on obtiendrait en
            // retirant tout ce qui pourrait etre lie a la respiration. C'est la borne basse d'un
            // intervalle dont le chiffre affiche en tete de carte est la borne haute. Pendulum ne
            // mesure pas la respiration : on ne peut pas trancher dedans, on peut seulement le
            // montrer.
            //
            // La **borne**, et non l'ecart a la borne. La ligne affichait `plmiRespWorstCase -
            // plmi`, donc un nombre negatif par construction, sous une note qui dit « this line is
            // the value the index would take » — soit, lu au mot, un index de −15,1/h, ce qui
            // n'existe pas. La note dit aussi « between the two », qui suppose deux valeurs et non
            // une valeur et un ecart. Montrer la borne rend les deux phrases vraies et epargne au
            // lecteur une soustraction faite de tete sur le chiffre qui porte le diagnostic.
            add(
                Ligne(
                    libelle = texte(R.string.night_why_resp_bracket),
                    valeur = texte(
                        R.string.night_why_worst_bound,
                        "%.1f ".format(Locale.UK, resultat.plmiRespWorstCase),
                        texte(R.string.trend_unit_per_hour),
                    ),
                    note = texte(R.string.night_why_resp_bracket_note),
                ),
            )

            metrologie?.let { ajouterMetrologie(it) }
        }

        return Bloc(
            titre = texte(
                R.string.night_why_title,
                texte(
                    R.string.trend_value_with_unit,
                    "%.1f".format(Locale.UK, resultat.plmi),
                    texte(R.string.trend_unit_per_hour),
                ),
            ),
            lignes = lignes,
        )
    }

    /**
     * Les trois grandeurs qui expliquent une **decision de detection**, et rien d'autre.
     *
     * ### Pourquoi elles ont leur place dans un bloc generatif
     *
     * Ce qui est interdit ici est l'attribution : classer des facteurs par importance, chiffrer
     * une contribution, dire qu'un trou de signal « explique 30 % de l'ecart ». Ces trois lignes ne
     * font rien de tel. Elles disent dans quelles conditions la mesure a ete faite, exactement au
     * meme titre que « sommeil analysable » ou « denominateur » : elles font partie du chemin par
     * lequel le chiffre est passe.
     *
     * La difference est verifiable : aucune des trois n'est comparee aux autres, aucune ne porte
     * de part, et le bloc ne les ordonne pas par effet. L'oeil fait le lien s'il y en a un ; le
     * texte ne le fait pas, et [Bloc.avertissement] continue de dire que ce tableau n'indique pas
     * ce qui a cause les mouvements.
     *
     * ### Et ce que chacune decide
     *
     *  - **L'ecretage du capteur** aplatit artificiellement le sommet de l'enveloppe. Or c'est
     *    l'amplitude qui decide du seuil de detection : un mouvement retenu ou rejete sur une
     *    minute ecretee n'a pas ete decide sur le signal, il a ete decide sur son ecretage. C'est
     *    l'ecretage **du capteur** — la dynamique reelle, 4 ou 8 g — et non la saturation du
     *    **format** a 16 g, qu'un capteur a 8 g n'approche jamais.
     *  - **La gigue** decide de la datation, et c'est sa dispersion qui la decide, pas sa moyenne.
     *    Le format n'ecrit pas d'horodatage par echantillon : il interpole lineairement entre les
     *    bornes d'un bloc. Une cadence moyenne parfaite obtenue en alternant 10 et 30 ms rend donc
     *    50 Hz pile et date chaque echantillon a 10 ms pres.
     *  - **Les gels d'ecriture** sont les instants ou le processeur s'arrete. C'est le pire gel, et
     *    non le cumul, qui explique une interruption capteur ratee a un instant precis.
     */
    private fun MutableList<Ligne>.ajouterMetrologie(m: Metrologie.Resume) {
        add(
            Ligne(
                libelle = texte(R.string.night_why_clipping),
                valeur = if (m.echantillonsEcretes == 0) {
                    texte(R.string.night_why_none)
                } else {
                    texte(
                        R.string.night_why_clipping_value,
                        Formats.milliers(m.echantillonsEcretes),
                        m.pointsEcretes,
                    )
                },
                note = texte(R.string.night_why_clipping_note),
            ),
        )

        add(
            Ligne(
                libelle = texte(R.string.night_why_timing),
                valeur = texte(
                    R.string.night_why_timing_value,
                    "%.1f ms".format(Locale.UK, m.gigueMedianeUs / 1000.0),
                    "%.0f ms".format(Locale.UK, m.pireIntervalleUs / 1000.0),
                ),
                note = texte(R.string.night_why_timing_note),
            ),
        )

        add(
            Ligne(
                libelle = texte(R.string.night_why_freezes),
                valeur = if (m.gels == 0) {
                    texte(R.string.night_why_none)
                } else {
                    texte(
                        R.string.night_why_freezes_value,
                        m.gels,
                        "%.0f ms".format(Locale.UK, m.pireGelUs / 1000.0),
                    )
                },
                note = texte(R.string.night_why_freezes_note),
            ),
        )
    }
}
