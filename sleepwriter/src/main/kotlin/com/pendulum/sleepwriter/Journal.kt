package com.pendulum.sleepwriter

import android.util.Log

/**
 * La sortie machine de l'outil : **une ligne, une etiquette stable, aucun espace dans les
 * valeurs**.
 *
 * ### Pourquoi ce format et pas du texte
 *
 * Cette application est pilotee par `adb` et lue par `adb logcat`. Ce qui la rend utile n'est pas
 * ce qu'elle affiche a l'ecran mais le fait qu'un script puisse savoir, sans regarder l'ecran, si
 * l'ecriture a abouti. Une phrase en francais oblige a ecrire une expression reguliere fragile ;
 * une ligne `cle=valeur` se lit avec `grep` puis `tr ' ' '\n'`.
 *
 * Trois regles qui ont chacune une raison :
 *  - **une seule ligne par action.** `logcat` entrelace les processus et coupe les lignes longues
 *    a 4 ko ; un resultat sur trois lignes n'est pas reassemblable de facon fiable ;
 *  - **jamais d'espace dans une valeur**, y compris les listes (separateur `;`), sinon le
 *    decoupage par espaces ne tient plus ;
 *  - **le prefixe `RESULT` n'est utilise que pour un resultat definitif.** Une ecriture differee
 *    emet d'abord une ligne `PENDING`, et un script qui cherche `RESULT` ne peut donc pas prendre
 *    l'annonce pour l'aboutissement.
 *
 * Lecture cote script :
 * `adb -s <serie> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1`
 */
object Journal {

    /** Etiquette `logcat`. Stable : des scripts en dependent. Onze caracteres, sous la limite. */
    const val TAG = "SLEEPWRITER"

    const val RESULTAT = "RESULT"
    const val EN_ATTENTE = "PENDING"

    /**
     * Statuts possibles. Ils sont ASCII et sans accent parce qu'ils sont compares par des scripts,
     * et distincts les uns des autres parce qu'ils ne se reparent pas de la meme facon : une
     * permission manquante se corrige par la sequence UiAutomator, un Health Connect absent par le
     * choix d'une autre image d'emulateur, un parametre invalide par la commande elle-meme.
     */
    object Statut {
        const val OK = "OK"
        const val RIEN_A_ECRIRE = "NOTHING_TO_WRITE"
        const val PERMISSION_MANQUANTE = "PERMISSION_MISSING"
        const val HC_INDISPONIBLE = "HC_UNAVAILABLE"
        const val PARAMETRE_INVALIDE = "BAD_PARAM"
        const val ERREUR = "ERROR"
    }

    /**
     * Emet une ligne et rend son texte, pour que l'appelant puisse l'afficher a l'ecran sans
     * reconstruire un second format — l'ecran et le journal doivent dire exactement la meme chose,
     * sinon on debogue deux verites.
     */
    fun emettre(prefixe: String, champs: List<Pair<String, Any?>>): String {
        val ligne = buildString {
            append(prefixe)
            for ((cle, valeur) in champs) {
                if (valeur == null) continue
                append(' ').append(cle).append('=').append(assainir(valeur.toString()))
            }
        }
        Log.i(TAG, ligne)
        return ligne
    }

    /** Les espaces casseraient le decoupage ; le vide casserait la lecture d'une paire. */
    private fun assainir(valeur: String): String =
        valeur.replace(Regex("\\s+"), "_").ifEmpty { "-" }
}
