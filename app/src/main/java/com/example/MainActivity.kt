package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.data.AppContainer
import com.example.recorder.RecordingPermissionCoordinator
import com.example.ui.navigation.ScreenRecorderApp

class MainActivity : ComponentActivity() {

  private lateinit var appContainer: AppContainer
  private lateinit var permissionCoordinator: RecordingPermissionCoordinator

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    appContainer = AppContainer(applicationContext)
    permissionCoordinator =
      RecordingPermissionCoordinator(
        activity = this,
        settingsRepository = appContainer.settingsRepository,
      )
    permissionCoordinator.attach()

    enableEdgeToEdge()
    setContent {
      ScreenRecorderApp(appContainer = appContainer)
    }
  }
}
