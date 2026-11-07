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
import com.example.recorder.ipc.RecorderClient
import com.example.recorder.ipc.RecorderSettingsSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Transparent, no-UI trampoline activity supporting two flows:
 * 1. [MODE_SHOW_CONTROLS] (launched from Quick Settings tile when idle):
 *    - If overlay permission ([Settings.canDrawOverlays]) is granted, sends snapshot to
 *      [RecorderClient.showControls] and finishes immediately.
 *    - If overlay permission is missing, displays a dialog offering to open the system
 *      "Display over other apps" settings page.
 * 2. [MODE_START_RECORDING] (launched when tapping Start on the floating controls):
 *    - Requests runtime permissions if missing, launches [MediaProjectionManager.createScreenCaptureIntent],
 *      starts [RecorderClient.startRecording] with the snapshot, and finishes immediately.
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
          val minMb = ScreenRecordingEngine.getMinimumStorageStartBytes(applicationContext) / (1024 * 1024)
          RecordingStateHolder.setPendingSnackbarMessage(
            applicationContext,
            "Not enough storage space to start recording (at least $minMb MB required).",
          )
          restoreControlsIfNeeded()
          ScreenRecorderTileService.requestTileUpdate(applicationContext)
          finishWithoutAnimation()
          return@registerForActivityResult
        }
        try {
          val (px, py) = RecorderClient.loadSavedPositionFractions(applicationContext, isLandscape = false)
          val (lx, ly) = RecorderClient.loadSavedPositionFractions(applicationContext, isLandscape = true)
          val (snapshot, gamingSnackbar) =
            RecorderSettingsSnapshot.createForRecording(
              settings = settings,
              context = applicationContext,
              portraitXFrac = px,
              portraitYFrac = py,
              landscapeXFrac = lx,
              landscapeYFrac = ly,
            )
          if (gamingSnackbar != null) {
            RecordingStateHolder.setPendingSnackbarMessage(applicationContext, gamingSnackbar)
          }
          RecorderClient.startRecording(
            context = applicationContext,
            resultCode = result.resultCode,
            resultData = data,
            snapshot = snapshot,
            restoreControlsAfterStop = launchedFromFloatingControls,
          )
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
    lifecycleScope.launch {
      try {
        val settingsRepository = DataStoreSettingsRepository(applicationContext)
        val settings = settingsRepository.settings.first()
        val (px, py) = RecorderClient.loadSavedPositionFractions(applicationContext, isLandscape = false)
        val (lx, ly) = RecorderClient.loadSavedPositionFractions(applicationContext, isLandscape = true)
        val snapshot =
          RecorderSettingsSnapshot.fromAppSettings(
            settings = settings,
            portraitXFrac = px,
            portraitYFrac = py,
            landscapeXFrac = lx,
            landscapeYFrac = ly,
          )
        RecorderClient.showControls(applicationContext, snapshot)
      } catch (t: Throwable) {
        Log.e(ScreenRecordingEngine.TAG, "Failed to show controls from QuickTileLaunchActivity", t)
      }
      ScreenRecorderTileService.requestTileUpdate(applicationContext)
      finishWithoutAnimation()
    }
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
      lifecycleScope.launch {
        try {
          val settingsRepository = DataStoreSettingsRepository(applicationContext)
          val settings = settingsRepository.settings.first()
          val snapshot = RecorderSettingsSnapshot.fromAppSettings(settings)
          RecorderClient.showControls(applicationContext, snapshot)
        } catch (_: Throwable) {}
      }
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
