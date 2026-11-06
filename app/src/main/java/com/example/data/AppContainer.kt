package com.example.data

import android.content.Context
import com.example.data.capabilities.FakeCapabilitiesProvider
import com.example.data.repository.DataStoreSettingsRepository
import com.example.data.repository.MediaStoreRecordingsRepository
import com.example.domain.repository.CapabilitiesProvider
import com.example.domain.repository.RecordingsRepository
import com.example.domain.repository.SettingsRepository
import com.example.recorder.RealRecorderController
import com.example.recorder.RecorderController
import com.example.recorder.ScreenRecordingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(
  context: Context,
) {
  private val appContext = context.applicationContext
  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val capabilitiesProvider: CapabilitiesProvider = FakeCapabilitiesProvider()
  val settingsRepository: SettingsRepository = DataStoreSettingsRepository(appContext)
  val recordingsRepository: RecordingsRepository = MediaStoreRecordingsRepository(appContext)
  val recorderController: RecorderController = RealRecorderController(appContext)

  init {
    // One-time startup cleanup of stale IS_PENDING = 1 files in Movies/ScreenRecorder older than 10 minutes
    appScope.launch {
      ScreenRecordingEngine.cleanupStalePendingRecordings(appContext)
    }
  }
}
