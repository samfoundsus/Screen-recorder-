package com.example.domain.repository

import com.example.domain.model.AppSettings
import com.example.domain.model.AppearanceSettings
import com.example.domain.model.AudioSettings
import com.example.domain.model.FloatingControlsSettings
import com.example.domain.model.GeneralSettings
import com.example.domain.model.VideoSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
  val settings: Flow<AppSettings>

  suspend fun updateGeneralSettings(transform: (GeneralSettings) -> GeneralSettings)

  suspend fun updateVideoSettings(transform: (VideoSettings) -> VideoSettings)

  suspend fun updateAudioSettings(transform: (AudioSettings) -> AudioSettings)

  suspend fun updateFloatingControlsSettings(
    transform: (FloatingControlsSettings) -> FloatingControlsSettings
  )

  suspend fun updateAppearanceSettings(transform: (AppearanceSettings) -> AppearanceSettings)

  suspend fun getCachedProbeResult(codec: String, width: Int, height: Int, fps: Int): Boolean?

  suspend fun saveCachedProbeResult(
    codec: String,
    width: Int,
    height: Int,
    fps: Int,
    supported: Boolean,
  )

  suspend fun clearProbeCache()

  suspend fun resetToDefaults()
}
