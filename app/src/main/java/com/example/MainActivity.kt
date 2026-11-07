/*
 * Memory & Hardening Fixes (Low-RAM & Process Split Pass):
 * 1. Binds to out-of-process RecorderService (:recorder) via RecorderClient to monitor live state.
 * 2. Implements onTrimMemory: at TRIM_MEMORY_UI_HIDDEN and above, clears thumbnail caches.
 * 3. Media3 ExoPlayer released in onStop in PlaybackScreen.
 * 4. Tracks app foreground state across rotation and configuration changes with debounce.
 */
package com.example

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.data.AppContainer
import com.example.data.repository.RecordingMediaCache
import com.example.recorder.MemoryDiagnostics
import com.example.recorder.RecordingPermissionCoordinator
import com.example.recorder.RecordingStateHolder
import com.example.recorder.ScreenRecorderTileService
import com.example.recorder.ipc.RecorderClient
import com.example.ui.navigation.ScreenRecorderApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  companion object {
    const val EXTRA_OPEN_DESTINATION = "extra_open_destination"
  }

  private lateinit var appContainer: AppContainer
  private lateinit var permissionCoordinator: RecordingPermissionCoordinator

  private val deleteRequestLauncher =
    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
      if (::appContainer.isInitialized) {
        appContainer.recordingsRepository.refresh()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    RecorderClient.bind(applicationContext)
    RecordingStateHolder.registerActivityLifecycle(application)
    RecordingStateHolder.setAppInForeground(true)
    RecorderClient.setAppInForeground(true)

    MemoryDiagnostics.initialize(applicationContext)
    appContainer = AppContainer(applicationContext)
    permissionCoordinator =
      RecordingPermissionCoordinator(
        activity = this,
        settingsRepository = appContainer.settingsRepository,
      )
    permissionCoordinator.attach()

    handleLaunchIntent(intent)

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        appContainer.recordingsRepository.deletePermissionRequests.collect { intentSender ->
          deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
      }
    }

    enableEdgeToEdge()
    setContent {
      ScreenRecorderApp(appContainer = appContainer)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleLaunchIntent(intent)
  }

  private fun handleLaunchIntent(intent: Intent?) {
    val destination = intent?.getStringExtra(EXTRA_OPEN_DESTINATION)
    if (!destination.isNullOrBlank()) {
      intent.removeExtra(EXTRA_OPEN_DESTINATION)
      RecordingStateHolder.requestNavigateToRoute(destination)
    }
  }

  override fun onResume() {
    super.onResume()
    RecorderClient.ensureConnected(applicationContext)
    RecordingStateHolder.setAppInForeground(true)
    RecorderClient.setAppInForeground(true)
    RecordingStateHolder.loadPendingSnackbarIfNeeded(applicationContext)
    ScreenRecorderTileService.requestTileUpdate(applicationContext)
  }

  override fun onStop() {
    super.onStop()
    RecordingMediaCache.clearThumbnails()
  }

  override fun onDestroy() {
    RecorderClient.unbind(applicationContext)
    super.onDestroy()
  }

  @Suppress("DEPRECATION")
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
      RecordingMediaCache.clearThumbnails()
    }
  }
}
