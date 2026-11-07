package com.example.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.DeviceCapabilities
import com.example.domain.model.MainDestination
import com.example.domain.model.NavDestinationConfig
import com.example.domain.model.OptionProbeState
import com.example.domain.repository.CapabilitiesProvider
import com.example.domain.repository.SettingsRepository
import com.example.recorder.MemoryDiagnosticSnapshot
import com.example.recorder.MemoryDiagnostics
import com.example.recorder.RecordingStateHolder
import com.example.recorder.ScreenRecorderTileService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
  val settings: AppSettings = AppSettings(),
  val capabilities: DeviceCapabilities,
  val resolutionProbeStates: Map<String, OptionProbeState> = emptyMap(),
  val fpsProbeStates: Map<Int, OptionProbeState> = emptyMap(),
  val displayRefreshRateHz: Int = 60,
  val memorySnapshot: MemoryDiagnosticSnapshot = MemoryDiagnosticSnapshot(),
)

class SettingsViewModel(
  private val settingsRepository: SettingsRepository,
  private val capabilitiesProvider: CapabilitiesProvider,
) : ViewModel() {

  private val capabilities = capabilitiesProvider.getCapabilities()
  private val displayRefreshRateHz = capabilitiesProvider.getDisplayRefreshRateHz()

  val uiState: StateFlow<SettingsUiState> =
    combine(
        settingsRepository.settings,
        capabilitiesProvider.resolutionProbeStates,
        capabilitiesProvider.fpsProbeStates,
        MemoryDiagnostics.snapshot,
      ) { appSettings, resProbes, fpsProbes, memSnap ->
        SettingsUiState(
          settings = appSettings,
          capabilities = capabilities,
          resolutionProbeStates = resProbes,
          fpsProbeStates = fpsProbes,
          displayRefreshRateHz = displayRefreshRateHz,
          memorySnapshot = memSnap,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue =
          SettingsUiState(
            capabilities = capabilities,
            displayRefreshRateHz = displayRefreshRateHz,
            memorySnapshot = MemoryDiagnostics.snapshot.value,
          ),
      )

  fun refreshMemoryDiagnostics(context: Context) {
    if (MemoryDiagnostics.IS_ENABLED) {
      MemoryDiagnostics.refreshSnapshot(context)
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

  fun retestDeviceSupport() {
    viewModelScope.launch {
      capabilitiesProvider.retestDeviceSupport()
      RecordingStateHolder.showSnackbarMessage("Cleared cache and re-testing device support")
    }
  }

  // General
  fun updateShowInQuickSettings(context: Context, enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateGeneralSettings { it.copy(showInQuickSettings = enabled) }
      ScreenRecorderTileService.setTileComponentEnabled(context, enabled)
    }
  }

  fun updateCountdownSeconds(seconds: Int) {
    viewModelScope.launch {
      settingsRepository.updateGeneralSettings {
        it.copy(countdownSeconds = seconds.coerceIn(0, 10))
      }
    }
  }

  // Video
  fun updateResolution(resolution: String) {
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

  fun updateFps(fps: Int) {
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

  fun updateBitrate(bitrateMbps: Int) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings {
        it.copy(bitrateMbps = bitrateMbps, isCustomBitrate = true)
      }
    }
  }

  fun updateCodec(codec: String) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings { it.copy(codec = codec) }
    }
  }

  fun updateContainer(container: String) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings { it.copy(container = container) }
    }
  }

  fun updateOrientation(orientation: String) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings { it.copy(orientation = orientation) }
    }
  }

  // Audio
  fun updateAudioSource(source: String) {
    viewModelScope.launch {
      settingsRepository.updateAudioSettings {
        val micImplicit = source.contains("Mic", ignoreCase = true)
        it.copy(source = source, micEnabled = micImplicit)
      }
    }
  }

  fun updateMicEnabled(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateAudioSettings { current ->
        val nextSource =
          if (enabled && current.source == "Device audio only") {
            "Device audio + Mic"
          } else if (!enabled && current.source == "Device audio + Mic") {
            "Device audio only"
          } else {
            current.source
          }
        current.copy(micEnabled = enabled, source = nextSource)
      }
    }
  }

  fun updateNoiseSuppression(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateAudioSettings { it.copy(noiseSuppression = enabled) }
    }
  }

  fun updateAudioChannels(channelMode: String) {
    viewModelScope.launch {
      settingsRepository.updateAudioSettings { it.copy(channelMode = channelMode) }
    }
  }

  // Floating controls
  fun updateFloatingSize(size: String) {
    viewModelScope.launch {
      settingsRepository.updateFloatingControlsSettings { it.copy(size = size) }
    }
  }

  fun updateFloatingOpacity(opacityPercent: Int) {
    viewModelScope.launch {
      settingsRepository.updateFloatingControlsSettings {
        it.copy(opacityPercent = opacityPercent.coerceIn(20, 100))
      }
    }
  }

  fun updateFloatingAutoHide(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateFloatingControlsSettings { it.copy(autoHide = enabled) }
    }
  }

  fun updateFloatingEdgeSnapping(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateFloatingControlsSettings { it.copy(edgeSnapping = enabled) }
    }
  }

  fun updateFloatingKeepEdgeHandleWhileRecording(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateFloatingControlsSettings {
        it.copy(keepEdgeHandleWhileRecording = enabled)
      }
    }
  }

  fun updateFloatingHideControlsFromVideo(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateFloatingControlsSettings {
        it.copy(hideControlsFromVideo = enabled)
      }
    }
  }

  // Appearance
  fun updateThemeMode(themeMode: AppThemeMode, dynamicColor: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateAppearanceSettings {
        it.copy(themeMode = themeMode, dynamicColor = dynamicColor)
      }
    }
  }

  fun updatePureBlackDark(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateAppearanceSettings {
        it.copy(pureBlackDark = enabled)
      }
    }
  }

  fun moveNavDestination(index: Int, direction: Int) {
    viewModelScope.launch {
      settingsRepository.updateAppearanceSettings { current ->
        val mutable = current.navOrder.toMutableList()
        val targetIndex = index + direction
        if (index in mutable.indices && targetIndex in mutable.indices) {
          val item = mutable.removeAt(index)
          mutable.add(targetIndex, item)
        }
        current.copy(navOrder = mutable)
      }
    }
  }

  fun toggleNavDestination(destination: MainDestination) {
    viewModelScope.launch {
      settingsRepository.updateAppearanceSettings { current ->
        val enabledCount = current.navOrder.count { it.enabled }
        val updated: List<NavDestinationConfig> =
          current.navOrder.map { item ->
            if (item.destination == destination) {
              if (item.enabled && enabledCount <= 1) {
                item
              } else {
                item.copy(enabled = !item.enabled)
              }
            } else {
              item
            }
          }
        current.copy(navOrder = updated)
      }
    }
  }

  fun resetAllSettings() {
    viewModelScope.launch {
      settingsRepository.resetToDefaults()
    }
  }

  companion object {
    fun provideFactory(
      settingsRepository: SettingsRepository,
      capabilitiesProvider: CapabilitiesProvider,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          return SettingsViewModel(
            settingsRepository = settingsRepository,
            capabilitiesProvider = capabilitiesProvider,
          ) as T
        }
      }
  }
}
