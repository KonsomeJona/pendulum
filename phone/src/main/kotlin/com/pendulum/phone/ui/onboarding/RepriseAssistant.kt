package com.pendulum.phone.ui.onboarding

import com.pendulum.phone.data.PendulumPreferences

/**
 * La regle de reprise de l'assistant, isolee du composable pour etre verifiable.
 *
 * ### Le compteur compte des etapes **franchies**, pas l'etape courante
 *
 * C'est toute la subtilite, et s'en ecarter donne un assistant qui se saute lui-meme. Le nombre
 * persiste est celui des etapes terminees : 0 au premier lancement, 5 quand l'assistant est fini.
 * L'ecrire a l'entree d'une etape ferait qu'ouvrir l'application, arriver sur l'avertissement et
 * la refermer suffirait a le considerer comme lu — soit exactement le contraire de ce que le
 * garde-fou 1 demande.
 *
 * ### Pourquoi reprendre, et pas recommencer
 *
 * Refaire trois ecrans d'avertissement pour arriver a celui qu'on cherchait est la facon la plus
 * sure de faire desinstaller une application. L'assistant a cinq etapes dont deux demandent une
 * permission systeme : celui qui a ete interrompu par un appel telephonique pendant la demande
 * Health Connect doit revenir la, pas au debut.
 */
object RepriseAssistant {

    /** Nombre d'ecrans. Le meme que la borne haute du compteur persiste. */
    const val PAGES = PendulumPreferences.ETAPES_ASSISTANT

    /** Vrai tant qu'il reste au moins une etape a franchir. */
    fun assistantAFaire(etapesFranchies: Int): Boolean = etapesFranchies < PAGES

    /**
     * La page a ouvrir. Bornee a la derniere page : un compteur superieur au nombre d'ecrans —
     * ce que produirait un assistant ramene a moins d'etapes dans une version ulterieure, sur un
     * telephone deja installe — ne doit pas ouvrir une page qui n'existe pas.
     */
    fun pageDeDepart(etapesFranchies: Int): Int = etapesFranchies.coerceIn(0, PAGES - 1)

    /**
     * Le compteur a ecrire en **sortant** de [page].
     *
     * Monotone par construction : il ne redescend jamais sous ce qui est deja acquis. Sans ce
     * `maxOf`, revenir en arriere d'une facon ou d'une autre — et une version ulterieure de
     * l'assistant en offrira peut-etre le moyen — effacerait des etapes deja franchies, donc
     * redemanderait des permissions deja accordees.
     */
    fun etapeApres(page: Int, etapesFranchies: Int): Int =
        maxOf(etapesFranchies, page + 1).coerceIn(0, PAGES)
}
