package com.example.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.RecordingItem
import com.example.domain.repository.RecordingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class PlaybackUiState(
  val recording: RecordingItem? = null,
)

class PlaybackViewModel(
  private val recordingId: String,
  private val recordingsRepository: RecordingsRepository,
) : ViewModel() {

  val uiState: StateFlow<PlaybackUiState> =
    recordingsRepository.recordings
      .map { list ->
        PlaybackUiState(recording = list.find { it.id == recordingId })
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaybackUiState(recording = recordingsRepository.getRecordingById(recordingId)),
      )

  fun deleteRecording(onDeleted: () -> Unit) {
    recordingsRepository.deleteRecordings(setOf(recordingId))
    onDeleted()
  }

  companion object {
    fun provideFactory(
      recordingId: String,
      recordingsRepository: RecordingsRepository,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          return PlaybackViewModel(
            recordingId = recordingId,
            recordingsRepository = recordingsRepository,
          ) as T
        }
      }
  }
}
