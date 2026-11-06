package com.example.recorder

import com.example.domain.model.RecorderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FakeRecorderController(
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
  private val onRecordingSaved: ((durationMs: Long) -> Unit)? = null,
) : RecorderController {

  private val _state = MutableStateFlow(RecorderState.IDLE)
  override val state: StateFlow<RecorderState> = _state.asStateFlow()

  private val _elapsedTimeMs = MutableStateFlow(0L)
  override val elapsedTimeMs: StateFlow<Long> = _elapsedTimeMs.asStateFlow()

  private var timerJob: Job? = null

  override fun start() {
    if (_state.value != RecorderState.IDLE) return
    _elapsedTimeMs.value = 0L
    _state.value = RecorderState.RECORDING
    startTicker()
  }

  override fun pause() {
    if (_state.value != RecorderState.RECORDING) return
    _state.value = RecorderState.PAUSED
    timerJob?.cancel()
    timerJob = null
  }

  override fun resume() {
    if (_state.value != RecorderState.PAUSED) return
    _state.value = RecorderState.RECORDING
    startTicker()
  }

  override fun stop(): Long {
    if (_state.value == RecorderState.IDLE) return 0L
    timerJob?.cancel()
    timerJob = null
    val finalDuration = _elapsedTimeMs.value.coerceAtLeast(1_000L)
    _state.value = RecorderState.IDLE
    _elapsedTimeMs.value = 0L
    onRecordingSaved?.invoke(finalDuration)
    return finalDuration
  }

  private fun startTicker() {
    timerJob?.cancel()
    timerJob =
      scope.launch {
        val tickIntervalMs = 100L
        while (isActive && _state.value == RecorderState.RECORDING) {
          delay(tickIntervalMs)
          _elapsedTimeMs.value += tickIntervalMs
        }
      }
  }
}
