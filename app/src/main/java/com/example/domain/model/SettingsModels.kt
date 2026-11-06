package com.example.domain.model

enum class AppThemeMode(val label: String) {
  SYSTEM("System default"),
  LIGHT("Light"),
  DARK("Dark"),
}

enum class MainDestination(val id: String, val label: String) {
  HOME("home", "Recorder"),
  LIBRARY("library", "Library"),
  SETTINGS("settings", "Settings");

  companion object {
    fun fromId(id: String): MainDestination? = entries.find { it.id == id }
  }
}

data class NavDestinationConfig(
  val destination: MainDestination,
  val enabled: Boolean,
)

data class VideoSettings(
  val resolution: String = "1080p (FHD)",
  val fps: Int = 60,
  val bitrateMbps: Int = 16,
  val codec: String = "HEVC (H.265)",
  val container: String = "MP4",
  val orientation: String = "Auto",
)

data class AudioSettings(
  val source: String = "Device audio + Mic",
  val micEnabled: Boolean = true,
  val noiseSuppression: Boolean = true,
  val channelMode: String = "Stereo",
)

data class FloatingControlsSettings(
  val size: String = "Compact",
  val opacityPercent: Int = 85,
  val autoHide: Boolean = true,
  val edgeSnapping: Boolean = true,
  val gestureAction: String = "Double-tap to pause, swipe to dismiss",
  val invisibleMode: Boolean = false,
)

data class AppearanceSettings(
  val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
  val dynamicColor: Boolean = true,
  val pureBlackDark: Boolean = false,
  val navOrder: List<NavDestinationConfig> =
    listOf(
      NavDestinationConfig(MainDestination.HOME, enabled = true),
      NavDestinationConfig(MainDestination.LIBRARY, enabled = true),
      NavDestinationConfig(MainDestination.SETTINGS, enabled = true),
    ),
)

data class AppSettings(
  val video: VideoSettings = VideoSettings(),
  val audio: AudioSettings = AudioSettings(),
  val floatingControls: FloatingControlsSettings = FloatingControlsSettings(),
  val appearance: AppearanceSettings = AppearanceSettings(),
)
