package com.example.data

import android.content.Context
import com.example.data.capabilities.FakeCapabilitiesProvider
import com.example.data.repository.DataStoreSettingsRepository
import com.example.data.repository.FakeRecordingsRepository
import com.example.domain.model.RecordingCategory
import com.example.domain.model.RecordingItem
import com.example.domain.repository.CapabilitiesProvider
import com.example.domain.repository.RecordingsRepository
import com.example.domain.repository.SettingsRepository
import com.example.recorder.FakeRecorderController
import com.example.recorder.RealRecorderController
import com.example.recorder.RecorderController
import com.example.recorder.RecordingStateHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(
  context: Context,
  useFakeRecorder: Boolean = false,
) {
  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  val capabilitiesProvider: CapabilitiesProvider = FakeCapabilitiesProvider()
  val settingsRepository: SettingsRepository =
    DataStoreSettingsRepository(context.applicationContext)
  val recordingsRepository: RecordingsRepository = FakeRecordingsRepository()

  val recorderController: RecorderController =
    if (useFakeRecorder) {
      FakeRecorderController(
        scope = appScope,
        onRecordingSaved = { durationMs ->
          appScope.launch {
            val currentSettings = settingsRepository.settings.first()
            val timestampLabel =
              SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
            val bitrateBytesPerSec = (currentSettings.video.bitrateMbps * 1_000_000L) / 8L
            val estimatedSize =
              ((durationMs / 1000.0) * bitrateBytesPerSec).toLong().coerceAtLeast(1_250_000L)
            val newItem =
              RecordingItem(
                id = "rec_${System.currentTimeMillis()}",
                title = "Screen Capture - $timestampLabel",
                createdAtEpochMs = System.currentTimeMillis(),
                durationMs = durationMs,
                sizeBytes = estimatedSize,
                resolution = currentSettings.video.resolution,
                fps = currentSettings.video.fps,
                bitrateMbps = currentSettings.video.bitrateMbps,
                codec = currentSettings.video.codec,
                container = currentSettings.video.container,
                hasMicAudio = currentSettings.audio.micEnabled,
                audioChannelMode = currentSettings.audio.channelMode,
                category = RecordingCategory.CLIPS,
                accentHue = (System.currentTimeMillis() % 360).toFloat(),
                contentUri = null,
              )
            recordingsRepository.addRecording(newItem)
          }
        },
      )
    } else {
      RealRecorderController(context.applicationContext)
    }

  init {
    appScope.launch {
      RecordingStateHolder.savedRecordings.collect { metadata ->
        val newItem =
          RecordingItem(
            id = "rec_${System.currentTimeMillis()}",
            title = metadata.displayName,
            createdAtEpochMs = System.currentTimeMillis(),
            durationMs = metadata.durationMs,
            sizeBytes = metadata.sizeBytes,
            resolution = metadata.resolution,
            fps = metadata.fps,
            bitrateMbps = metadata.bitrateMbps,
            codec = metadata.codec,
            container = metadata.container,
            hasMicAudio = metadata.hasMicAudio,
            audioChannelMode = metadata.audioChannelMode,
            category = RecordingCategory.CLIPS,
            accentHue = (System.currentTimeMillis() % 360).toFloat(),
            contentUri = metadata.contentUri.toString(),
          )
        recordingsRepository.addRecording(newItem)
      }
    }
  }
}
