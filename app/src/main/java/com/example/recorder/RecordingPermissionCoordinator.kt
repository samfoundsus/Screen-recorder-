package com.example.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.domain.model.AppSettings
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Coordinates runtime permissions (`POST_NOTIFICATIONS` on API 33+ and `RECORD_AUDIO` when mic
 * or internal audio is enabled) and the single-use `MediaProjection` screen capture consent flow.
 */
class RecordingPermissionCoordinator(
  private val activity: ComponentActivity,
  private val settingsRepository: SettingsRepository,
) {

  private var pendingSettings: AppSettings? = null

  private val permissionLauncher: ActivityResultLauncher<Array<String>> =
    activity.registerForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
      val settings = pendingSettings
      if (settings == null) {
        RecordingStateHolder.setIdle()
        return@registerForActivityResult
      }

      val denied = results.entries.any { !it.value }
      if (denied) {
        pendingSettings = null
        RecordingStateHolder.reportError("Permission denied. Recording cancelled.")
      } else {
        launchMediaProjectionPrompt(settings)
      }
    }

  private val mediaProjectionLauncher: ActivityResultLauncher<Intent> =
    activity.registerForActivityResult(
      ActivityResultContracts.StartActivityForResult()
    ) { result ->
      val settings = pendingSettings
      pendingSettings = null

      val data = result.data
      if (result.resultCode == Activity.RESULT_OK && data != null && settings != null) {
        try {
          val serviceIntent =
            RecorderService.createStartIntent(
              context = activity.applicationContext,
              resultCode = result.resultCode,
              resultData = data,
              settings = settings,
            )
          ContextCompat.startForegroundService(activity.applicationContext, serviceIntent)
        } catch (t: Throwable) {
          Log.e(ScreenRecordingEngine.TAG, "Failed to start RecorderService", t)
          RecordingStateHolder.reportError("Unable to start recording service.")
        }
      } else {
        RecordingStateHolder.reportError("Screen capture permission denied.")
      }
    }

  fun attach() {
    activity.lifecycleScope.launch {
      activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
        RecordingStateHolder.startRequests.collect {
          beginStartRecordingFlow()
        }
      }
    }
  }

  private suspend fun beginStartRecordingFlow() {
    try {
      val currentSettings = settingsRepository.settings.first()
      pendingSettings = currentSettings

      val neededPermissions = mutableListOf<String>()

      // Request POST_NOTIFICATIONS on API 33+
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
          neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
      }

      // Request RECORD_AUDIO only if mic or internal audio capture is enabled
      val audioSource = currentSettings.audio.source
      val wantsAudio =
        audioSource != "Mute (No audio)" &&
          (currentSettings.audio.micEnabled ||
            audioSource.contains("Device audio", ignoreCase = true) ||
            audioSource.contains("Mic", ignoreCase = true))

      if (wantsAudio && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
        neededPermissions.add(Manifest.permission.RECORD_AUDIO)
      }

      if (neededPermissions.isNotEmpty()) {
        permissionLauncher.launch(neededPermissions.toTypedArray())
      } else {
        launchMediaProjectionPrompt(currentSettings)
      }
    } catch (t: Throwable) {
      Log.e(ScreenRecordingEngine.TAG, "Permission flow failed", t)
      pendingSettings = null
      RecordingStateHolder.reportError("Failed to request recording permissions.")
    }
  }

  private fun launchMediaProjectionPrompt(settings: AppSettings) {
    try {
      pendingSettings = settings
      val projectionManager =
        activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    } catch (t: Throwable) {
      Log.e(ScreenRecordingEngine.TAG, "Failed to launch MediaProjection intent", t)
      pendingSettings = null
      RecordingStateHolder.reportError("Screen capture is unavailable on this device.")
    }
  }

  private fun hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(activity, permission) ==
      PackageManager.PERMISSION_GRANTED
  }
}
