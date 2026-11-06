package com.example.recorder

import android.net.Uri
import com.example.domain.model.RecorderState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class SavedRecordingMetadata(
  val contentUri: Uri,
  val displayName: String,
  val durationMs: Long,
  val sizeBytes: Long,
  val resolution: String,
  val fps: Int,
  val bitrateMbps: Int,
  val codec: String,
  val container: String,
  val hasMicAudio: Boolean,
  val audioChannelMode: String,
)

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

  private val _savedRecordings =
    MutableSharedFlow<SavedRecordingMetadata>(
      replay = 0,
      extraBufferCapacity = 4,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  val savedRecordings: SharedFlow<SavedRecordingMetadata> = _savedRecordings.asSharedFlow()

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

  fun reportError(message: String) {
    _engineStatus.value = RecordingEngineStatus.Error(message)
    _recorderState.value = RecorderState.IDLE
    _elapsedTimeMs.value = 0L
    _errorMessage.value = message
  }

  fun consumeErrorMessage() {
    _errorMessage.value = null
  }

  fun publishSavedRecording(metadata: SavedRecordingMetadata) {
    _savedRecordings.tryEmit(metadata)
  }
}
