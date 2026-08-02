package com.pendulum.phone.ui.model

import com.pendulum.phone.db.ComparabilityRule
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.ui.text.Textes

/**
 * Le cablage des messages d'erreur : deux fonctions pures, un seul gabarit.
 *
 * ### Le gabarit, invariable
 *
 * **Titre neutre, une phrase de cause, une phrase d'action, un bouton quand une action existe, un
 * code stable.** [ErreurPendulum] ne peut pas etre construit autrement — il n'y a pas de
 * constructeur qui accepte un message seul. « Une erreur est survenue » n'a donc pas d'endroit ou
 * s'ecrire.
 *
 * ### La regle transversale, qui est portee par un booleen et non par une convention
 *
 * Une nuit sans hypnogramme, une nuit courte, une montre restee sur la table sont des
 * **situations**. Elles s'affichent en ambre. Le rouge est reserve a ce qui est reellement
 * casse : transfert, permissions, integrite de fichier, stockage. C'est exactement
 * [ErreurPendulum.technique], lu par `ErrorCard` pour choisir la teinte — employer le rouge pour
 * une nuit courte apprend a ignorer le rouge, et le jour ou le transfert casse vraiment, plus
 * personne ne regarde.
 *
 * ### Ce qui n'est pas cable, et pourquoi c'est ecrit ici
 *
 * `E-NIGHT-05` (montre sur la table) et `E-NIGHT-06` (bracelet lache) demandent la fraction
 * hors-corps et le plancher de bruit de la nuit ; ni l'un ni l'autre n'est persiste au niveau de
 * la nuit — le plancher n'existe que par evenement, dans `clm_event.noiseFloorG`. `E-NIGHT-08`
 * (derive d'horloge entre les deux appareils) demande une comparaison des horodatages montre et
 * telephone que l'ingestion ne conserve pas. Les trois textes existent et restent en place :
 * les cabler sur une approximation donnerait un message faux, ce qui est pire qu'un message
 * absent. `E-HC-02` en revanche est cable, parce que la permission, elle, se lit.
 */
object Situations {

    // -------------------------------------------------------------------------------------
    // Health Connect : les trois codes E-HC-*
    // -------------------------------------------------------------------------------------

    /**
     * La situation de la source de sommeil, ou `null` quand tout va.
     *
     * @param sourcesRecentes nombre de sources ayant ecrit une session sur les sept derniers
     *   jours. `null` quand la question n'a pas encore ete posee — a distinguer de zero, qui est
     *   une reponse.
     * @param originesDerniereNuit nombre d'applications distinctes ayant publie une session
     *   recouvrant la derniere nuit. Deux ou plus, et le denominateur depend de celle qu'on lit.
     */
    fun sommeil(
        disponibilite: SleepReader.Availability?,
        sourcesRecentes: Int?,
        originesDerniereNuit: Int,
    ): ErreurPendulum? = when {
        disponibilite == null -> null

        // La permission est **cassee**, pas absente : rouge. C'est la seule des trois qui empeche
        // reellement quelque chose, et la seule qui se repare en un geste.
        disponibilite == SleepReader.Availability.PERMISSIONS_MISSING -> ErreurPendulum(
            code = "E-HC-02",
            titre = Textes.Erreurs.HC_02_TITRE,
            cause = Textes.Erreurs.HC_02_CAUSE,
            action = Textes.Erreurs.HC_02_ACTION,
            bouton = Textes.Erreurs.HC_02_BOUTON,
            technique = true,
        )

        // Health Connect absent ou trop ancien : l'assistant de premier lancement porte deja ces
        // deux cas avec leur bouton d'installation. Les repeter en tete de la tendance ferait
        // deux endroits pour une meme reparation, et celui-ci n'est pas le bon.
        disponibilite != SleepReader.Availability.READY -> null

        // Aucune source : une **situation**, en ambre. L'application continue, sur son propre
        // masque, et chaque nuit concernee le porte. Ce n'est pas une panne de Pendulum.
        sourcesRecentes == 0 -> ErreurPendulum(
            code = "E-HC-01",
            titre = Textes.Erreurs.HC_01_TITRE,
            cause = Textes.Erreurs.HC_01_CAUSE,
            action = Textes.Erreurs.HC_01_ACTION,
            bouton = Textes.Erreurs.HC_01_BOUTON,
            technique = false,
        )

        // Deux sources contradictoires : on ne fusionne jamais, on choisit et on le dit.
        originesDerniereNuit >= 2 -> ErreurPendulum(
            code = "E-HC-03",
            titre = Textes.Erreurs.HC_03_TITRE,
            cause = Textes.Erreurs.HC_03_CAUSE,
            action = Textes.Erreurs.HC_03_ACTION,
            bouton = Textes.Erreurs.HC_03_BOUTON,
            technique = false,
        )

        else -> null
    }

    // -------------------------------------------------------------------------------------
    // Une nuit : les codes E-NIGHT-*
    // -------------------------------------------------------------------------------------

    /**
     * La situation d'une nuit, ou `null` quand elle n'appelle aucune explication.
     *
     * Une seule est rendue, et l'ordre est celui de la **consequence** et non de la gravite
     * ressentie : ce qui met la nuit hors de la tendance passe avant ce qui la laisse dedans. Un
     * utilisateur qui lit « pas de stades de sommeil » sur une nuit par ailleurs trop courte
     * reparerait la mauvaise chose.
     */
    fun nuit(
        n: ComparableNight,
        session: NightSessionEntity?,
    ): ErreurPendulum? {
        // `E-NIGHT-07` (transfert incomplet) n'est **pas** rendu ici, et ce n'est pas un oubli :
        // `night_session.truncated` dit que l'enregistrement s'est arrete sans fermeture propre,
        // pas qu'il manque des fichiers. Le fait qui distingue les deux est le nombre de chunks
        // recus contre `totalChunks`, que la vue `comparable_night` ne porte pas. Ce code est donc
        // rendu par `MachineReveil`, qui a les deux compteurs sous la main. Le deduire ici de
        // `truncated` afficherait « des fichiers manquent » sur une nuit integralement transferee
        // dont la montre s'est simplement arretee tot — un message faux, avec un bouton rouge.
        //
        // Consequence : **toutes les situations de nuit sont ambre**. C'est exactement ce que dit
        // la regle transversale — une nuit courte, une nuit sans hypnogramme, une montre
        // dechargee sont des situations, pas des pannes.

        // 1. Nuit trop courte : elle sort de la tendance, et il n'y a rien a faire. Ambre.
        if (n.exclusionReason == ComparabilityRule.TOO_SHORT) {
            return ErreurPendulum(
                code = "E-NIGHT-02",
                titre = Textes.Erreurs.NIGHT_02_TITRE,
                cause = Textes.Erreurs.NIGHT_02_CAUSE,
                action = Textes.Erreurs.NIGHT_02_ACTION,
                technique = false,
            )
        }

        // 2. Montre dechargee en cours de nuit. Le chiffre reste, mais il est vraisemblablement
        //    sous-estime : les mouvements se concentrent dans la seconde moitie de la nuit.
        val batterie = session?.batteryPctLast
        if (batterie != null && batterie < Mapping.SEUIL_BATTERIE_BASSE_PCT) {
            return ErreurPendulum(
                code = "E-NIGHT-03",
                titre = Textes.Erreurs.NIGHT_03_TITRE,
                cause = Textes.Erreurs.NIGHT_03_CAUSE,
                action = Textes.Erreurs.NIGHT_03_ACTION,
                technique = false,
            )
        }

        // 3. Trous de signal au-dela du cumul tolerable. Une action existe, et elle a un cout —
        //    le mode continu vide la batterie plus vite — donc elle est proposee, pas appliquee.
        val cumulS = (session?.gapTotalMs ?: 0L) / 1000.0
        if (cumulS > Controles.CUMUL_TROUS_MAX_S) {
            return ErreurPendulum(
                code = "E-NIGHT-04",
                titre = Textes.Erreurs.NIGHT_04_TITRE,
                cause = Textes.Erreurs.NIGHT_04_CAUSE,
                action = Textes.Erreurs.NIGHT_04_ACTION,
                bouton = Textes.Erreurs.NIGHT_04_BOUTON,
                technique = false,
            )
        }

        // 4. Pas d'hypnogramme. C'est le cas le plus frequent et le moins grave, donc le dernier :
        //    la nuit compte, elle porte son drapeau, et son temps de sommeil est estime.
        if (n.maskSource == Mapping.MASQUE_ACCELERO) {
            return ErreurPendulum(
                code = "E-NIGHT-01",
                titre = Textes.Erreurs.NIGHT_01_TITRE,
                cause = Textes.Erreurs.NIGHT_01_CAUSE,
                action = Textes.Erreurs.NIGHT_01_ACTION,
                technique = false,
            )
        }

        return null
    }
}
