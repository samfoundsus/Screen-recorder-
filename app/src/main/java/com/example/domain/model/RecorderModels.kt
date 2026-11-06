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

data class RecordingItem(
  val id: String,
  val title: String,
  val createdAtEpochMs: Long,
  val durationMs: Long,
  val sizeBytes: Long,
  val resolution: String,
  val fps: Int,
  val bitrateMbps: Int,
  val codec: String,
  val container: String,
  val hasMicAudio: Boolean,
  val audioChannelMode: String,
  val category: RecordingCategory,
  val accentHue: Float,
  val contentUri: String? = null,
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
