package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.PlmResultEntity
import com.pendulum.phone.ui.text.Textes
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
    data class Ligne(val libelle: String, val valeur: String, val note: String? = null)

    /**
     * Le bloc complet. [avertissement] est un champ et non un texte pose par l'ecran : un bloc de
     * chemin de calcul sans sa phrase de non-causalite ne doit pas pouvoir exister.
     */
    data class Bloc(
        val titre: String,
        val lignes: List<Ligne>,
        val avertissement: String = Textes.Nuits.Detail.Pourquoi.AVERTISSEMENT,
    )

    /**
     * @param dureeEnregistreeMin duree de la session, du demarrage a l'arret. Elle sert de
     *   contexte au sommeil analysable : « 5 h 12 » ne dit rien, « 5 h 12 sur 7 h 41
     *   enregistrees » dit ou est passe le reste.
     * @param mouvementsRetenus le numerateur, tel qu'il a servi. Ce n'est pas le nombre
     *   d'evenements detectes : les rejets de posture et de duree sont deja sortis.
     * @param regle le jeu de regles applique, deja mis en forme.
     * @param sourceSommeil le libelle de la source, deja resolu par [Mapping.libelleSource].
     */
    fun de(
        n: ComparableNight,
        resultat: PlmResultEntity?,
        dureeEnregistreeMin: Double,
        mouvementsRetenus: Int,
        regle: String,
        sourceSommeil: String,
    ): Bloc? {
        if (resultat == null) return null
        val p = Textes.Nuits.Detail.Pourquoi
        val masqueIndependant = n.maskSource != Mapping.MASQUE_ACCELERO

        val lignes = buildList {
            add(
                Ligne(
                    libelle = p.SOMMEIL_ANALYSABLE,
                    valeur = Mapping.dureeLisible(n.analysableTstMin),
                    note = p.surEnregistre(Mapping.dureeLisible(dureeEnregistreeMin)),
                ),
            )
            add(Ligne(p.MOUVEMENTS_RETENUS, mouvementsRetenus.toString()))
            add(Ligne(p.REGLE, regle))
            add(
                Ligne(
                    libelle = p.MASQUE,
                    valeur = "$sourceSommeil, " +
                        if (masqueIndependant) p.MASQUE_HYPNOGRAMME else p.MASQUE_ACCELERO,
                ),
            )
            // Le denominateur est la seule ligne qui porte un jugement, et c'est un jugement
            // structurel : il vient du meme capteur que le numerateur, ou il n'en vient pas.
            add(
                Ligne(
                    libelle = p.DENOMINATEUR,
                    valeur = if (masqueIndependant) p.DENOMINATEUR_INDEPENDANT else p.DENOMINATEUR_CIRCULAIRE,
                ),
            )
            add(Ligne(p.TAUX_MANQUES, "%.2f".format(Locale.UK, n.missRate)))

            // L'encadrement respiratoire : `plmiRespWorstCase` est l'index qu'on obtiendrait en
            // retirant tout ce qui pourrait etre lie a la respiration. L'ecart est negatif par
            // construction, et c'est la borne basse d'un intervalle dont le chiffre affiche est
            // la borne haute. Pendulum ne mesure pas la respiration : on ne peut pas trancher
            // dedans, on peut seulement le montrer.
            val ecart = resultat.plmiRespWorstCase - resultat.plmi
            add(
                Ligne(
                    libelle = p.ENCADREMENT_RESPI,
                    valeur = p.bornePessimiste(
                        "%+.1f ".format(Locale.UK, ecart),
                        Textes.Tendance.UNITE_PAR_HEURE,
                    ),
                    note = p.ENCADREMENT_RESPI_NOTE,
                ),
            )
        }

        return Bloc(
            titre = p.titre(
                "%.1f %s".format(Locale.UK, resultat.plmi, Textes.Tendance.UNITE_PAR_HEURE),
            ),
            lignes = lignes,
        )
    }
}
