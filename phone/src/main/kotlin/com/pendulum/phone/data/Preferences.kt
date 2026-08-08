package com.pendulum.phone.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Les quatre choses que l'application doit se rappeler d'un lancement a l'autre et qui ne sont
 * pas des donnees de mesure.
 *
 * `androidx.datastore.preferences` etait declaree en dependance depuis le debut et **n'etait
 * instanciee nulle part**. `work/Workers.kt` le disait sans detour dans un TODO : le reglage de
 * source preferee etait decoratif, puisque rien ne le persistait et que la lecture retombait
 * toujours sur l'heuristique de `SleepSourceSelector`.
 *
 * ### Pourquoi pas dans Room
 *
 * La base porte la mesure, et la mesure est ce qu'on n'a pas le droit de perdre. Une preference
 * d'interface n'a pas ce statut : la melanger aux nuits obligerait a lui ecrire une migration
 * chaque fois qu'on ajoute une case a cocher, sur une base ou une migration fausse coute des
 * nuits irrecuperables. Les deux etages sont separes exprès.
 *
 * ### Ce qui n'est deliberement pas ici
 *
 * Rien de ce qui influence un chiffre. Le jeu de regles de comptage et le profil de parametres
 * vivent dans `param_profile`, en base, parce qu'ils portent un `paramsHash` dont depend le
 * rescore de toutes les nuits. Une preference qu'on peut effacer en vidant le cache n'a pas a
 * pouvoir changer un resultat.
 */
class PendulumPreferences(private val context: Context) {

    /**
     * Numero de l'etape d'assistant **deja franchie**, de 0 a [ETAPES_ASSISTANT].
     *
     * C'est ce qui rend l'assistant reprenable : quitter a l'etape 3 y ramene, et non au debut —
     * refaire trois ecrans d'avertissement pour arriver a celui qu'on cherchait est la facon la
     * plus sure de faire desinstaller une application. Il n'est ecrit qu'a la sortie de chaque
     * etape, jamais a l'entree : une etape commencee et abandonnee n'est pas une etape franchie.
     */
    val etapeAssistant: Flow<Int>
        get() = context.dataStore.data.map { it[CLE_ETAPE_ASSISTANT] ?: 0 }

    suspend fun poserEtapeAssistant(etape: Int) {
        context.dataStore.edit { it[CLE_ETAPE_ASSISTANT] = etape.coerceIn(0, ETAPES_ASSISTANT) }
    }

    val assistantTermine: Flow<Boolean>
        get() = etapeAssistant.map { it >= ETAPES_ASSISTANT }

    /**
     * Paquet de l'application choisie comme source de sommeil, ou `null` pour laisser
     * `SleepSourceSelector` decider.
     *
     * Ce n'est pas un confort. Quand deux applications ecrivent des sessions de sommeil qui se
     * chevauchent, le denominateur depend de celle qu'on lit, et un denominateur qui change d'une
     * nuit a l'autre fabrique une tendance qui n'existe pas.
     */
    val sourceSommeilPreferee: Flow<String?>
        get() = context.dataStore.data.map { it[CLE_SOURCE_SOMMEIL] }

    suspend fun poserSourceSommeilPreferee(paquet: String?) {
        context.dataStore.edit {
            if (paquet == null) it.remove(CLE_SOURCE_SOMMEIL) else it[CLE_SOURCE_SOMMEIL] = paquet
        }
    }

    /** Lecture ponctuelle, pour les workers qui n'ont pas de portee pour collecter un flux. */
    suspend fun sourceSommeilPreferreeMaintenant(): String? =
        context.dataStore.data.first()[CLE_SOURCE_SOMMEIL]

    /**
     * Le repere de serrage du bracelet, saisi a la derniere etape de l'assistant et **rejoue
     * chaque soir**.
     *
     * Il etait demande a l'assistant et jete : `OnboardingPager` le remontait a son appelant, qui
     * n'existait pas. Or c'est lui qui rend deux nuits comparables — le jeu du bracelet fait
     * varier l'amplitude d'un facteur 2 a 3, ce que `ComparabilityRule.GAIN_TOLERANCE` encaisse
     * a 35 % sans pouvoir le corriger.
     */
    val repereDeSerrage: Flow<String>
        get() = context.dataStore.data.map { it[CLE_REPERE_SERRAGE] ?: "" }

    suspend fun poserRepereDeSerrage(repere: String) {
        context.dataStore.edit { it[CLE_REPERE_SERRAGE] = repere }
    }

    /** `SYSTEME`, `SOMBRE` ou `CLAIR`. Sombre par defaut, et le defaut est un choix. */
    val theme: Flow<String>
        get() = context.dataStore.data.map { it[CLE_THEME] ?: THEME_SOMBRE }

    suspend fun poserTheme(theme: String) {
        context.dataStore.edit { it[CLE_THEME] = theme }
    }

    companion object {
        const val ETAPES_ASSISTANT = 6

        const val THEME_SYSTEME = "SYSTEME"
        const val THEME_SOMBRE = "SOMBRE"
        const val THEME_CLAIR = "CLAIR"

        private val CLE_ETAPE_ASSISTANT = intPreferencesKey("etape_assistant")
        private val CLE_SOURCE_SOMMEIL = stringPreferencesKey("source_sommeil_preferee")
        private val CLE_REPERE_SERRAGE = stringPreferencesKey("repere_de_serrage")
        private val CLE_THEME = stringPreferencesKey("theme")
    }
}

/**
 * Un seul `DataStore` par processus, impose par la bibliotheque : en instancier deux sur le meme
 * fichier leve une exception a la premiere ecriture. Le delegue d'extension le garantit.
 *
 * Le fichier est exclu de la sauvegarde par `res/xml/data_extraction_rules.xml`, comme le reste :
 * une restauration sur un autre telephone rapporterait un etat d'assistant sans les nuits qui
 * vont avec.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pendulum")
