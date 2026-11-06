package com.example.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppSettings
import com.example.domain.model.DeviceCapabilities
import com.example.domain.model.RecorderState
import com.example.domain.model.RecordingItem
import com.example.domain.repository.CapabilitiesProvider
import com.example.domain.repository.RecordingsRepository
import com.example.domain.repository.SettingsRepository
import com.example.recorder.RecorderController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
  val recorderState: RecorderState = RecorderState.IDLE,
  val elapsedTimeMs: Long = 0L,
  val settings: AppSettings = AppSettings(),
  val recentRecordings: List<RecordingItem> = emptyList(),
  val capabilities: DeviceCapabilities,
)

class HomeViewModel(
  private val recorderController: RecorderController,
  private val settingsRepository: SettingsRepository,
  private val recordingsRepository: RecordingsRepository,
  capabilitiesProvider: CapabilitiesProvider,
) : ViewModel() {

  private val capabilities = capabilitiesProvider.getCapabilities()

  val uiState: StateFlow<HomeUiState> =
    combine(
        recorderController.state,
        recorderController.elapsedTimeMs,
        settingsRepository.settings,
        recordingsRepository.recordings,
      ) { recState, elapsed, appSettings, allRecordings ->
        HomeUiState(
          recorderState = recState,
          elapsedTimeMs = elapsed,
          settings = appSettings,
          recentRecordings =
            allRecordings.sortedByDescending { it.createdAtEpochMs }.take(4),
          capabilities = capabilities,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(capabilities = capabilities),
      )

  fun onPrimaryRecordAction() {
    when (recorderController.state.value) {
      RecorderState.IDLE -> recorderController.start()
      RecorderState.RECORDING,
      RecorderState.PAUSED -> recorderController.stop()
    }
  }

  fun onPauseResumeAction() {
    when (recorderController.state.value) {
      RecorderState.RECORDING -> recorderController.pause()
      RecorderState.PAUSED -> recorderController.resume()
      RecorderState.IDLE -> Unit
    }
  }

  fun selectResolution(resolution: String) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings { it.copy(resolution = resolution) }
    }
  }

  fun selectFps(fps: Int) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings { it.copy(fps = fps) }
    }
  }

  fun toggleMic() {
    viewModelScope.launch {
      settingsRepository.updateAudioSettings { current ->
        val nextMic = !current.micEnabled
        val nextSource =
          if (nextMic && current.source == "Device audio only") {
            "Device audio + Mic"
          } else if (!nextMic && current.source == "Device audio + Mic") {
            "Device audio only"
          } else {
            current.source
          }
        current.copy(micEnabled = nextMic, source = nextSource)
      }
    }
  }

  companion object {
    fun provideFactory(
      recorderController: RecorderController,
      settingsRepository: SettingsRepository,
      recordingsRepository: RecordingsRepository,
      capabilitiesProvider: CapabilitiesProvider,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          return HomeViewModel(
            recorderController = recorderController,
            settingsRepository = settingsRepository,
            recordingsRepository = recordingsRepository,
            capabilitiesProvider = capabilitiesProvider,
          ) as T
        }
      }
  }
}
