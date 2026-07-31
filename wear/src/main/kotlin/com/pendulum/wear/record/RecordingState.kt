package com.pendulum.wear.record

import com.pendulum.format.wire.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * **Unique** source d'etat pour l'interface, emise par le service toutes les 30 s.
 *
 * Le contrat est celui-la et pas un autre : le service ecrit dans ce flux a cadence fixe, et
 * l'ecran le collecte avec `collectAsStateWithLifecycle()`. Ecran eteint, la collecte est
 * arretee et le service emet dans le vide — aucune recomposition entre le coucher et le reveil.
 * Toute autre voie d'affichage (un `LaunchedEffect` a cadence rapide, une animation, un
 * `System.currentTimeMillis()` lu dans une composable) casse ce critere sans qu'aucun test ne
 * s'en apercoive.
 *
 * L'objet est un singleton de processus : le service et l'activite vivent dans le meme
 * processus, et un `bindService` pour transporter six entiers couterait plus cher en
 * complexite et en reveils que ce qu'il rapporterait.
 */
object RecordingState {

    private val _state = MutableStateFlow(RecordUiState())
    val state: StateFlow<RecordUiState> = _state

    fun update(block: (RecordUiState) -> RecordUiState) {
        _state.value = block(_state.value)
    }

    fun set(value: RecordUiState) {
        _state.value = value
    }
}

enum class RecordPhase { IDLE, RECORDING, FINALIZING }

data class RecordUiState(
    val phase: RecordPhase = RecordPhase.IDLE,
    val elapsedMs: Long = 0,
    val samples: Long = 0,
    val bytesWritten: Long = 0,
    val batteryPct: Int = -1,
    val gapCount: Int = 0,
    val gapTotalMs: Long = 0,
    val modeLabel: String = "",
    /** Chunks encore sur le disque, c'est-a-dire pas encore acquittes par le telephone. */
    val chunksPending: Int = 0,
    val chunksTotal: Int = 0,
    /** Le plafond d'items en vol est atteint : le transfert est en retard, pas la mesure. */
    val syncBacklogged: Boolean = false,
    /** Raison du dernier arret, affichee une fois au reveil. */
    val lastStopReason: StopReason? = null,
)
