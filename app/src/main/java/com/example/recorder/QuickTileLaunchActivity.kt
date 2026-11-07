package com.example.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.data.repository.DataStoreSettingsRepository
import com.example.domain.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Transparent, no-UI trampoline activity supporting two flows:
 * 1. [MODE_SHOW_CONTROLS] (launched from Quick Settings tile when idle):
 *    - If overlay permission ([Settings.canDrawOverlays]) is granted, starts [RecorderService] in
 *      "controls only" mode and finishes immediately.
 *    - If overlay permission is missing, displays a dialog offering to open the system
 *      "Display over other apps" settings page. If the user declines (or returns without granting),
 *      gracefully falls back to the direct [MediaProjection] consent flow and never crashes.
 * 2. [MODE_START_RECORDING] (launched when tapping Start on the floating controls):
 *    - Requests runtime permissions if missing, launches [MediaProjectionManager.createScreenCaptureIntent],
 *      starts [RecorderService] with the result, and finishes immediately.
 */
class QuickTileLaunchActivity : ComponentActivity() {

  companion object {
    const val EXTRA_LAUNCH_MODE = "extra_launch_mode"
    const val EXTRA_FROM_FLOATING_CONTROLS = "extra_from_floating_controls"

    const val MODE_SHOW_CONTROLS = "mode_show_controls"
    const val MODE_START_RECORDING = "mode_start_recording"

    fun createShowControlsIntent(context: Context): Intent {
      return Intent(context, QuickTileLaunchActivity::class.java).apply {
        putExtra(EXTRA_LAUNCH_MODE, MODE_SHOW_CONTROLS)
        putExtra(EXTRA_FROM_FLOATING_CONTROLS, false)
      }
    }

    fun createStartRecordingFromControlsIntent(context: Context): Intent {
      return Intent(context, QuickTileLaunchActivity::class.java).apply {
        putExtra(EXTRA_LAUNCH_MODE, MODE_START_RECORDING)
        putExtra(EXTRA_FROM_FLOATING_CONTROLS, true)
      }
    }
  }

  private var pendingSettings: AppSettings? = null
  private var launchedFromFloatingControls: Boolean = false
  private var overlayDialog: AlertDialog? = null

  private val overlaySettingsLauncher: ActivityResultLauncher<Intent> =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      if (Settings.canDrawOverlays(applicationContext)) {
        startControlsOnlyServiceAndFinish()
      } else {
        // User returned without granting overlay permission: fall back to direct MediaProjection flow
        launchedFromFloatingControls = false
        startDirectMediaProjectionFlow()
      }
    }

  private val permissionLauncher: ActivityResultLauncher<Array<String>> =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
      val settings = pendingSettings
      if (settings == null) {
        restoreControlsIfNeeded()
        finishWithoutAnimation()
        return@registerForActivityResult
      }
      val denied = results.entries.any { !it.value }
      if (denied) {
        pendingSettings = null
        RecordingStateHolder.setPendingSnackbarMessage(
          applicationContext,
          "Permission denied. Recording cancelled.",
        )
        restoreControlsIfNeeded()
        ScreenRecorderTileService.requestTileUpdate(applicationContext)
        finishWithoutAnimation()
      } else {
        launchMediaProjectionPrompt(settings)
      }
    }

  private val mediaProjectionLauncher: ActivityResultLauncher<Intent> =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val settings = pendingSettings
      pendingSettings = null
      val data = result.data
      if (result.resultCode == Activity.RESULT_OK && data != null && settings != null) {
        if (!ScreenRecordingEngine.hasEnoughStorageToStart(applicationContext)) {
          RecordingStateHolder.setPendingSnackbarMessage(
            applicationContext,
            "Not enough storage space to start recording (at least 300 MB required).",
          )
          restoreControlsIfNeeded()
          ScreenRecorderTileService.requestTileUpdate(applicationContext)
          finishWithoutAnimation()
          return@registerForActivityResult
        }
        try {
          val serviceIntent =
            RecorderService.createStartIntent(
              context = applicationContext,
              resultCode = result.resultCode,
              resultData = data,
              settings = settings,
              restoreControlsAfterStop = launchedFromFloatingControls,
            )
          ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } catch (t: Throwable) {
          Log.e(ScreenRecordingEngine.TAG, "Failed to start RecorderService", t)
          RecordingStateHolder.setPendingSnackbarMessage(
            applicationContext,
            "Unable to start recording service.",
          )
          restoreControlsIfNeeded()
        }
      } else {
        RecordingStateHolder.setPendingSnackbarMessage(
          applicationContext,
          "Screen capture permission denied.",
        )
        restoreControlsIfNeeded()
      }
      ScreenRecorderTileService.requestTileUpdate(applicationContext)
      finishWithoutAnimation()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) {
      finishWithoutAnimation()
      return
    }

    val mode = intent?.getStringExtra(EXTRA_LAUNCH_MODE) ?: MODE_SHOW_CONTROLS
    launchedFromFloatingControls =
      intent?.getBooleanExtra(EXTRA_FROM_FLOATING_CONTROLS, false) == true

    if (mode == MODE_SHOW_CONTROLS) {
      handleShowControlsMode()
    } else {
      startDirectMediaProjectionFlow()
    }
  }

  override fun onDestroy() {
    overlayDialog?.dismiss()
    overlayDialog = null
    super.onDestroy()
  }

  private fun handleShowControlsMode() {
    if (Settings.canDrawOverlays(applicationContext)) {
      startControlsOnlyServiceAndFinish()
      return
    }

    try {
      val dialog =
        AlertDialog.Builder(
            this,
            android.R.style.Theme_DeviceDefault_Dialog_Alert,
          )
          .setTitle("Display over other apps")
          .setMessage(
            "Allow Screen Recorder to display floating controls over other apps so you can adjust settings and start recording anytime."
          )
          .setPositiveButton("Open settings") { d, _ ->
            d.dismiss()
            openOverlayPermissionSettings()
          }
          .setNegativeButton("Record directly") { d, _ ->
            d.dismiss()
            launchedFromFloatingControls = false
            startDirectMediaProjectionFlow()
          }
          .setOnCancelListener {
            launchedFromFloatingControls = false
            startDirectMediaProjectionFlow()
          }
          .create()
      overlayDialog = dialog
      dialog.show()
    } catch (t: Throwable) {
      Log.w(
        ScreenRecordingEngine.TAG,
        "Overlay dialog failed, falling back to MediaProjection consent",
        t,
      )
      launchedFromFloatingControls = false
      startDirectMediaProjectionFlow()
    }
  }

  private fun openOverlayPermissionSettings() {
    try {
      val settingsIntent =
        Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:$packageName"),
        )
      overlaySettingsLauncher.launch(settingsIntent)
    } catch (t: Throwable) {
      Log.w(
        ScreenRecordingEngine.TAG,
        "Unable to open ACTION_MANAGE_OVERLAY_PERMISSION, falling back to direct recording",
        t,
      )
      launchedFromFloatingControls = false
      startDirectMediaProjectionFlow()
    }
  }

  private fun startControlsOnlyServiceAndFinish() {
    try {
      startService(RecorderService.createShowControlsIntent(applicationContext))
    } catch (t: Throwable) {
      Log.e(ScreenRecordingEngine.TAG, "Failed to start controls-only RecorderService", t)
    }
    ScreenRecorderTileService.requestTileUpdate(applicationContext)
    finishWithoutAnimation()
  }

  private fun startDirectMediaProjectionFlow() {
    if (!ScreenRecordingEngine.hasEnoughStorageToStart(applicationContext)) {
      RecordingStateHolder.setPendingSnackbarMessage(
        applicationContext,
        "Not enough storage space to start recording (at least 300 MB required).",
      )
      restoreControlsIfNeeded()
      ScreenRecorderTileService.requestTileUpdate(applicationContext)
      finishWithoutAnimation()
      return
    }

    lifecycleScope.launch {
      try {
        val settingsRepository = DataStoreSettingsRepository(applicationContext)
        val currentSettings = settingsRepository.settings.first()
        pendingSettings = currentSettings

        val neededPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
          }
        }

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
        Log.e(ScreenRecordingEngine.TAG, "QuickTileLaunchActivity failed", t)
        pendingSettings = null
        restoreControlsIfNeeded()
        finishWithoutAnimation()
      }
    }
  }

  private fun launchMediaProjectionPrompt(settings: AppSettings) {
    try {
      pendingSettings = settings
      val projectionManager =
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    } catch (t: Throwable) {
      Log.e(ScreenRecordingEngine.TAG, "Failed to launch MediaProjection", t)
      pendingSettings = null
      RecordingStateHolder.setPendingSnackbarMessage(
        applicationContext,
        "Screen capture is unavailable on this device.",
      )
      restoreControlsIfNeeded()
      finishWithoutAnimation()
    }
  }

  private fun restoreControlsIfNeeded() {
    if (launchedFromFloatingControls && Settings.canDrawOverlays(applicationContext)) {
      try {
        startService(RecorderService.createShowControlsIntent(applicationContext))
      } catch (_: Throwable) {}
    }
  }

  private fun hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
  }

  private fun finishWithoutAnimation() {
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)
  }
}
