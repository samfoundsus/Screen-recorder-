package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.DeviceCapabilities
import com.example.domain.model.MainDestination
import com.example.domain.model.NavDestinationConfig
import com.example.domain.repository.CapabilitiesProvider
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
  val settings: AppSettings = AppSettings(),
  val capabilities: DeviceCapabilities,
)

class SettingsViewModel(
  private val settingsRepository: SettingsRepository,
  capabilitiesProvider: CapabilitiesProvider,
) : ViewModel() {

  private val capabilities = capabilitiesProvider.getCapabilities()

  val uiState: StateFlow<SettingsUiState> =
    settingsRepository.settings
      .map { appSettings ->
        SettingsUiState(
          settings = appSettings,
          capabilities = capabilities,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(capabilities = capabilities),
      )

  // Video
  fun updateResolution(resolution: String) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings { it.copy(resolution = resolution) }
    }
  }

  fun updateFps(fps: Int) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings { it.copy(fps = fps) }
    }
  }

  fun updateBitrate(bitrateMbps: Int) {
    viewModelScope.launch {
      settingsRepository.updateVideoSettings { it.copy(bitrateMbps = bitrateMbps) }
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
      settingsRepository.updateAudioSettings { it.copy(micEnabled = enabled) }
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

  fun updateFloatingGestures(gestureAction: String) {
    viewModelScope.launch {
      settingsRepository.updateFloatingControlsSettings { it.copy(gestureAction = gestureAction) }
    }
  }

  fun updateFloatingInvisibleMode(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.updateFloatingControlsSettings { it.copy(invisibleMode = enabled) }
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
              // Prevent disabling the last remaining enabled destination
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
