package com.example.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppSettings
import com.example.domain.model.DeviceCapabilities
import com.example.domain.model.OptionProbeState
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
  val resolutionProbeStates: Map<String, OptionProbeState> = emptyMap(),
  val fpsProbeStates: Map<Int, OptionProbeState> = emptyMap(),
  val displayRefreshRateHz: Int = 60,
)

class HomeViewModel(
  private val recorderController: RecorderController,
  private val settingsRepository: SettingsRepository,
  private val recordingsRepository: RecordingsRepository,
  private val capabilitiesProvider: CapabilitiesProvider,
) : ViewModel() {

  private val capabilities = capabilitiesProvider.getCapabilities()
  private val displayRefreshRateHz = capabilitiesProvider.getDisplayRefreshRateHz()

  val uiState: StateFlow<HomeUiState> =
    combine(
        combine(
          recorderController.state,
          recorderController.elapsedTimeMs,
          settingsRepository.settings,
          recordingsRepository.recordings,
        ) { recState, elapsed, appSettings, allRecordings ->
          Quad(recState, elapsed, appSettings, allRecordings)
        },
        capabilitiesProvider.resolutionProbeStates,
        capabilitiesProvider.fpsProbeStates,
      ) { (recState, elapsed, appSettings, allRecordings), resProbes, fpsProbes ->
        HomeUiState(
          recorderState = recState,
          elapsedTimeMs = elapsed,
          settings = appSettings,
          recentRecordings =
            allRecordings.sortedByDescending { it.createdAtEpochMs }.take(4),
          capabilities = capabilities,
          resolutionProbeStates = resProbes,
          fpsProbeStates = fpsProbes,
          displayRefreshRateHz = displayRefreshRateHz,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue =
          HomeUiState(
            capabilities = capabilities,
            displayRefreshRateHz = displayRefreshRateHz,
          ),
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

  fun onOpenResolutionPicker() {
    val video = uiState.value.settings.video
    capabilitiesProvider.probeResolutionsForFps(video.fps, video.orientation)
  }

  fun onOpenFpsPicker() {
    val video = uiState.value.settings.video
    capabilitiesProvider.probeFpsForResolution(video.resolution, video.orientation)
  }

  fun selectResolution(resolution: String) {
    viewModelScope.launch {
      val probeState = uiState.value.resolutionProbeStates[resolution]
      val matchedCodec =
        if (probeState is OptionProbeState.Supported) {
          if (probeState.codecLabel.startsWith("HEVC")) "HEVC (H.265)" else "AVC (H.264)"
        } else {
          null
        }
      val cappedFps =
        if (probeState is OptionProbeState.Supported && probeState.codecLabel.contains("up to ")) {
          probeState.codecLabel
            .substringAfter("up to ")
            .substringBefore(" ")
            .toIntOrNull()
        } else {
          null
        }
      settingsRepository.updateVideoSettings { current ->
        val nextFps =
          if (cappedFps != null && current.fps > cappedFps) {
            cappedFps
          } else {
            current.fps
          }
        current.copy(
          resolution = resolution,
          fps = nextFps,
          codec = matchedCodec ?: current.codec,
        )
      }
      val orientation = uiState.value.settings.video.orientation
      capabilitiesProvider.probeFpsForResolution(resolution, orientation)
    }
  }

  fun selectFps(fps: Int) {
    viewModelScope.launch {
      val probeState = uiState.value.fpsProbeStates[fps]
      val matchedCodec =
        if (probeState is OptionProbeState.Supported) {
          if (probeState.codecLabel.startsWith("HEVC")) "HEVC (H.265)" else "AVC (H.264)"
        } else {
          null
        }
      settingsRepository.updateVideoSettings {
        it.copy(
          fps = fps,
          codec = matchedCodec ?: it.codec,
        )
      }
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

  private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
