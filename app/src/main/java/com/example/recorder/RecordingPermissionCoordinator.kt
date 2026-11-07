package com.example.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import com.example.recorder.ipc.RecorderClient
import com.example.recorder.ipc.RecorderSettingsSnapshot
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Coordinates pre-flight storage space checks (minimum 300 MB), optional overlay permission
 * dialog ("Allow floating controls?" with "Open settings" and "Not now"), runtime permissions
 * (`POST_NOTIFICATIONS` on API 33+ and `RECORD_AUDIO` when mic or internal audio is enabled),
 * and the single-use `MediaProjection` screen capture consent flow.
 * Passes a serializable [RecorderSettingsSnapshot] to [RecorderClient] to start the `:recorder` process.
 */
class RecordingPermissionCoordinator(
  private val activity: ComponentActivity,
  private val settingsRepository: SettingsRepository,
) {

  private var pendingSettings: AppSettings? = null

  private val overlayPermissionLauncher: ActivityResultLauncher<Intent> =
    activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      val settings = pendingSettings
      if (settings != null) {
        continueWithPermissionsAndProjection(settings)
      }
    }

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
        if (!ScreenRecordingEngine.hasEnoughStorageToStart(activity.applicationContext)) {
          RecordingStateHolder.reportError(
            "Not enough storage space to start recording (at least 300 MB required)."
          )
          return@registerForActivityResult
        }
        try {
          val (px, py) = RecorderClient.loadSavedPositionFractions(activity, isLandscape = false)
          val (lx, ly) = RecorderClient.loadSavedPositionFractions(activity, isLandscape = true)
          val (snapshot, gamingSnackbar) =
            RecorderSettingsSnapshot.createForRecording(
              settings = settings,
              context = activity.applicationContext,
              portraitXFrac = px,
              portraitYFrac = py,
              landscapeXFrac = lx,
              landscapeYFrac = ly,
            )
          if (gamingSnackbar != null) {
            RecordingStateHolder.showSnackbarMessage(gamingSnackbar)
          }
          RecorderClient.startRecording(
            context = activity.applicationContext,
            resultCode = result.resultCode,
            resultData = data,
            snapshot = snapshot,
            restoreControlsAfterStop = false,
          )
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
      // Pre-flight storage check: refuse to start below 300 MB and show a snackbar
      if (!ScreenRecordingEngine.hasEnoughStorageToStart(activity.applicationContext)) {
        val minMb = ScreenRecordingEngine.getMinimumStorageStartBytes(activity.applicationContext) / (1024 * 1024)
        pendingSettings = null
        RecordingStateHolder.reportError(
          "Not enough storage space to start recording (at least $minMb MB required)."
        )
        return
      }

      val currentSettings = settingsRepository.settings.first()
      pendingSettings = currentSettings

      if (Settings.canDrawOverlays(activity)) {
        continueWithPermissionsAndProjection(currentSettings)
      } else {
        showOverlayPermissionDialog(currentSettings)
      }
    } catch (t: Throwable) {
      Log.e(ScreenRecordingEngine.TAG, "Permission flow failed", t)
      pendingSettings = null
      RecordingStateHolder.reportError("Failed to request recording permissions.")
    }
  }

  private fun showOverlayPermissionDialog(currentSettings: AppSettings) {
    try {
      MaterialAlertDialogBuilder(activity)
        .setTitle("Allow floating controls?")
        .setMessage(
          "Screen Recorder can show a floating edge handle to pause or stop recording when you leave the app."
        )
        .setPositiveButton("Open settings") { dialog, _ ->
          dialog.dismiss()
          launchOverlaySettings(currentSettings)
        }
        .setNegativeButton("Not now") { dialog, _ ->
          dialog.dismiss()
          continueWithPermissionsAndProjection(currentSettings)
        }
        .setOnCancelListener {
          continueWithPermissionsAndProjection(currentSettings)
        }
        .show()
    } catch (_: Throwable) {
      // Fallback if dialog display fails: do not block recording
      continueWithPermissionsAndProjection(currentSettings)
    }
  }

  private fun launchOverlaySettings(settings: AppSettings) {
    pendingSettings = settings
    try {
      val intent =
        Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:${activity.packageName}"),
        )
      overlayPermissionLauncher.launch(intent)
    } catch (_: Throwable) {
      try {
        overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
      } catch (_: Throwable) {
        continueWithPermissionsAndProjection(settings)
      }
    }
  }

  private fun continueWithPermissionsAndProjection(currentSettings: AppSettings) {
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
