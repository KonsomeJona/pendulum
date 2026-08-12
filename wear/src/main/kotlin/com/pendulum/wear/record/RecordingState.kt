package com.pendulum.wear.record

import com.pendulum.format.wire.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The **one** source of state for the interface, emitted by the service every 30 s.
 *
 * The contract is this one and no other: the service writes into this flow at a fixed rate, and
 * the screen collects it with `collectAsStateWithLifecycle()`. With the screen off, collection is
 * stopped and the service emits into the void — no recomposition between going to bed and waking.
 * Any other display route (a fast-rate `LaunchedEffect`, an animation, a
 * `System.currentTimeMillis()` read inside a composable) breaks that criterion without any test
 * noticing.
 *
 * The object is a process singleton: the service and the activity live in the same process, and a
 * `bindService` to carry six integers would cost more in complexity and in wake-ups than it would
 * bring back.
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
    /** Chunks still on the disk, that is, not yet acknowledged by the phone. */
    val chunksPending: Int = 0,
    val chunksTotal: Int = 0,
    /** The ceiling of in-flight items is reached: the transfer is behind, not the measurement. */
    val syncBacklogged: Boolean = false,
    /** Reason for the last stop, shown once on waking. */
    val lastStopReason: StopReason? = null,
)
