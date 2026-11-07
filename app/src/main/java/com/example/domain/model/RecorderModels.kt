package com.example.domain.model

enum class RecorderState {
  IDLE,
  RECORDING,
  PAUSED,
}

enum class RecordingCategory(val label: String) {
  ALL("All"),
  TUTORIALS("Tutorials"),
  GAMES("Games"),
  SYSTEM("System UI"),
  CLIPS("Clips"),
}

enum class SortOption(val label: String) {
  DATE_DESC("Date (newest)"),
  DATE_ASC("Date (oldest)"),
  SIZE_DESC("Size (largest)"),
  SIZE_ASC("Size (smallest)"),
  DURATION_DESC("Duration (longest)"),
  DURATION_ASC("Duration (shortest)"),
}

enum class DateFilter(val label: String) {
  ALL_TIME("All time"),
  TODAY("Today"),
  LAST_7_DAYS("Last 7 days"),
  LAST_30_DAYS("Last 30 days"),
}

sealed interface OptionProbeState {
  data object Idle : OptionProbeState

  data object Probing : OptionProbeState

  data class Supported(
    val codecLabel: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
  ) : OptionProbeState

  data object Unsupported : OptionProbeState
}

data class VideoMetadata(
  val width: Int?,
  val height: Int?,
  val resolution: String?,
  val fps: Int?,
  val bitrateMbps: Int?,
  val codec: String,
  val hasAudio: Boolean = true,
  val audioChannelMode: String = "Stereo",
  val noiseSuppression: Boolean = false,
)

data class RecordingItem(
  val id: String,
  val title: String,
  val createdAtEpochMs: Long,
  val durationMs: Long,
  val sizeBytes: Long,
  val resolution: String,
  val fps: Int?,
  val bitrateMbps: Int?,
  val codec: String,
  val container: String,
  val hasMicAudio: Boolean,
  val audioChannelMode: String,
  val noiseSuppression: Boolean = false,
  val category: RecordingCategory,
  val accentHue: Float,
  val contentUri: String,
)

data class DeviceCapabilities(
  val supportedResolutions: List<String>,
  val supportedFps: List<Int>,
  val supportedBitratesMbps: List<Int>,
  val supportedCodecs: List<String>,
  val supportedContainers: List<String>,
  val supportedOrientations: List<String>,
  val supportedAudioSources: List<String>,
  val supportedAudioChannels: List<String>,
  val supportsHardwareNoiseSuppression: Boolean,
  val deviceModelSummary: String,
  val hardwareEncoderSummary: String,
)
