package com.example.data.repository

import com.example.domain.model.RecordingCategory
import com.example.domain.model.RecordingItem
import com.example.domain.repository.RecordingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeRecordingsRepository : RecordingsRepository {

  private val now = System.currentTimeMillis()
  private val hourMs = 3_600_000L
  private val dayMs = 86_400_000L

  private val _recordings =
    MutableStateFlow(
      listOf(
        RecordingItem(
          id = "rec_01",
          title = "Pixel Launcher Motion Study",
          createdAtEpochMs = now - (2 * hourMs),
          durationMs = 46_000L,
          sizeBytes = 68_400_000L,
          resolution = "1080p (FHD)",
          fps = 60,
          bitrateMbps = 16,
          codec = "HEVC (H.265)",
          container = "MP4",
          hasMicAudio = true,
          audioChannelMode = "Stereo",
          category = RecordingCategory.SYSTEM,
          accentHue = 212f,
        ),
        RecordingItem(
          id = "rec_02",
          title = "Compose Navigation Walkthrough",
          createdAtEpochMs = now - (6 * hourMs),
          durationMs = 148_000L,
          sizeBytes = 192_800_000L,
          resolution = "1440p (QHD)",
          fps = 60,
          bitrateMbps = 24,
          codec = "HEVC (H.265)",
          container = "MP4",
          hasMicAudio = true,
          audioChannelMode = "Stereo",
          category = RecordingCategory.TUTORIALS,
          accentHue = 158f,
        ),
        RecordingItem(
          id = "rec_03",
          title = "Alto's Odyssey 120Hz Frame Pacing",
          createdAtEpochMs = now - (2 * dayMs),
          durationMs = 215_000L,
          sizeBytes = 418_500_000L,
          resolution = "1440p (QHD)",
          fps = 120,
          bitrateMbps = 32,
          codec = "AV1 (Hardware)",
          container = "MP4",
          hasMicAudio = false,
          audioChannelMode = "Stereo",
          category = RecordingCategory.GAMES,
          accentHue = 18f,
        ),
        RecordingItem(
          id = "rec_04",
          title = "Quick Settings Tile Interaction Clip",
          createdAtEpochMs = now - (4 * dayMs),
          durationMs = 19_000L,
          sizeBytes = 21_200_000L,
          resolution = "1080p (FHD)",
          fps = 60,
          bitrateMbps = 12,
          codec = "HEVC (H.265)",
          container = "MP4",
          hasMicAudio = false,
          audioChannelMode = "Mono",
          category = RecordingCategory.CLIPS,
          accentHue = 268f,
        ),
        RecordingItem(
          id = "rec_05",
          title = "Material You Dynamic Color Demo",
          createdAtEpochMs = now - (9 * dayMs),
          durationMs = 94_000L,
          sizeBytes = 114_900_000L,
          resolution = "1080p (FHD)",
          fps = 60,
          bitrateMbps = 16,
          codec = "HEVC (H.265)",
          container = "MP4",
          hasMicAudio = true,
          audioChannelMode = "Stereo",
          category = RecordingCategory.TUTORIALS,
          accentHue = 335f,
        ),
        RecordingItem(
          id = "rec_06",
          title = "Split-Screen Multitasking Stress Test",
          createdAtEpochMs = now - (18 * dayMs),
          durationMs = 312_000L,
          sizeBytes = 342_000_000L,
          resolution = "1080p (FHD)",
          fps = 60,
          bitrateMbps = 16,
          codec = "AVC (H.264)",
          container = "MKV",
          hasMicAudio = false,
          audioChannelMode = "Stereo",
          category = RecordingCategory.SYSTEM,
          accentHue = 190f,
        ),
        RecordingItem(
          id = "rec_07",
          title = "Shader Ripple Micro-Interaction",
          createdAtEpochMs = now - (42 * dayMs),
          durationMs = 12_000L,
          sizeBytes = 14_600_000L,
          resolution = "720p (HD)",
          fps = 30,
          bitrateMbps = 8,
          codec = "HEVC (H.265)",
          container = "WebM",
          hasMicAudio = false,
          audioChannelMode = "Mono",
          category = RecordingCategory.CLIPS,
          accentHue = 42f,
        ),
      )
    )

  override val recordings: StateFlow<List<RecordingItem>> = _recordings.asStateFlow()

  override fun getRecordingById(id: String): RecordingItem? {
    return _recordings.value.find { it.id == id }
  }

  override fun addRecording(item: RecordingItem) {
    _recordings.update { listOf(item) + it }
  }

  override fun deleteRecordings(ids: Set<String>) {
    _recordings.update { list -> list.filterNot { it.id in ids } }
  }

  override fun renameRecording(id: String, newTitle: String) {
    if (newTitle.isBlank()) return
    _recordings.update { list ->
      list.map { item -> if (item.id == id) item.copy(title = newTitle.trim()) else item }
    }
  }
}
