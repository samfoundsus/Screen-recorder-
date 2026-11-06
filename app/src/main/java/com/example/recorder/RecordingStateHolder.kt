package com.example.recorder

import com.example.domain.model.RecorderState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecordingEngineStatus {
  data object Idle : RecordingEngineStatus

  data object Recording : RecordingEngineStatus

  data object Paused : RecordingEngineStatus

  data class Error(val message: String) : RecordingEngineStatus
}

/**
 * UI-free singleton state holder bridging [RecorderService], [RecordingPermissionCoordinator],
 * and [RealRecorderController].
 */
object RecordingStateHolder {

  private val _engineStatus = MutableStateFlow<RecordingEngineStatus>(RecordingEngineStatus.Idle)
  val engineStatus: StateFlow<RecordingEngineStatus> = _engineStatus.asStateFlow()

  private val _recorderState = MutableStateFlow(RecorderState.IDLE)
  val recorderState: StateFlow<RecorderState> = _recorderState.asStateFlow()

  private val _elapsedTimeMs = MutableStateFlow(0L)
  val elapsedTimeMs: StateFlow<Long> = _elapsedTimeMs.asStateFlow()

  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

  private val _startRequests =
    MutableSharedFlow<Unit>(
      replay = 0,
      extraBufferCapacity = 1,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  val startRequests: SharedFlow<Unit> = _startRequests.asSharedFlow()

  fun requestStart() {
    _startRequests.tryEmit(Unit)
  }

  fun setRecording() {
    _engineStatus.value = RecordingEngineStatus.Recording
    _recorderState.value = RecorderState.RECORDING
  }

  fun setPaused() {
    _engineStatus.value = RecordingEngineStatus.Paused
    _recorderState.value = RecorderState.PAUSED
  }

  fun setIdle() {
    _engineStatus.value = RecordingEngineStatus.Idle
    _recorderState.value = RecorderState.IDLE
    _elapsedTimeMs.value = 0L
  }

  fun updateElapsedTime(elapsedMs: Long) {
    _elapsedTimeMs.value = elapsedMs
  }

  fun showSnackbarMessage(message: String) {
    _errorMessage.value = message
  }

  fun reportError(message: String) {
    _engineStatus.value = RecordingEngineStatus.Error(message)
    _recorderState.value = RecorderState.IDLE
    _elapsedTimeMs.value = 0L
    _errorMessage.value = message
  }

  fun consumeErrorMessage() {
    _errorMessage.value = null
  }
}
