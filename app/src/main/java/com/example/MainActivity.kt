package com.example

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
import com.example.recorder.RecordingPermissionCoordinator
import com.example.ui.navigation.ScreenRecorderApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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
    appContainer = AppContainer(applicationContext)
    permissionCoordinator =
      RecordingPermissionCoordinator(
        activity = this,
        settingsRepository = appContainer.settingsRepository,
      )
    permissionCoordinator.attach()

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
}
