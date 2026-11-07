package com.example.recorder

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.R
import com.example.domain.model.RecorderState
import com.example.ui.components.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ScreenRecorderTileService : TileService() {

  companion object {
    fun requestTileUpdate(context: Context) {
      try {
        requestListeningState(
          context.applicationContext,
          ComponentName(context.applicationContext, ScreenRecorderTileService::class.java),
        )
      } catch (_: Throwable) {}
    }

    fun setTileComponentEnabled(context: Context, enabled: Boolean) {
      try {
        val appContext = context.applicationContext
        val component = ComponentName(appContext, ScreenRecorderTileService::class.java)
        val newState =
          if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
          } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
          }
        appContext.packageManager.setComponentEnabledSetting(
          component,
          newState,
          PackageManager.DONT_KILL_APP,
        )
        if (enabled) {
          requestTileUpdate(appContext)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = appContext.getSystemService(StatusBarManager::class.java)
            statusBarManager?.requestAddTileService(
              component,
              appContext.getString(R.string.app_name),
              Icon.createWithResource(appContext, R.drawable.ic_qs_videocam),
              appContext.mainExecutor,
            ) {}
          }
        }
      } catch (_: Throwable) {}
    }
  }

  private var listeningScope: CoroutineScope? = null
  private var stateJob: Job? = null

  override fun onTileAdded() {
    super.onTileAdded()
    syncTileWithCurrentState()
  }

  override fun onStartListening() {
    super.onStartListening()
    syncTileWithCurrentState()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    listeningScope = scope
    stateJob =
      scope.launch {
        combine(
            RecordingStateHolder.recorderState,
            RecordingStateHolder.elapsedTimeMs,
            RecordingStateHolder.countdownSecondsRemaining,
            RecordingStateHolder.floatingControlsVisible,
          ) { state, elapsedMs, countdown, controlsVisible ->
            TileUiSnapshot(
              state = state,
              elapsedMs = elapsedMs,
              countdown = countdown,
              controlsVisible = controlsVisible,
            )
          }
          .collect { snapshot ->
            applyTileUi(
              state = snapshot.state,
              elapsedMs = snapshot.elapsedMs,
              countdown = snapshot.countdown,
              controlsVisible = snapshot.controlsVisible,
            )
          }
      }
  }

  override fun onStopListening() {
    stateJob?.cancel()
    stateJob = null
    listeningScope?.cancel()
    listeningScope = null
    super.onStopListening()
  }

  override fun onClick() {
    super.onClick()
    val currentState = RecordingStateHolder.recorderState.value
    val countdown = RecordingStateHolder.countdownSecondsRemaining.value
    val controlsVisible = RecordingStateHolder.floatingControlsVisible.value

    if (currentState != RecorderState.IDLE || countdown > 0) {
      // Tapping while recording or counting down stops the recording directly via the service
      try {
        startService(RecorderService.createStopIntent(applicationContext))
      } catch (_: Throwable) {
        RecordingStateHolder.setIdle()
      }
      syncTileWithCurrentState()
      return
    }

    if (controlsVisible) {
      // Tapping the tile again while floating controls are visible and idle closes them
      try {
        startService(RecorderService.createHideControlsIntent(applicationContext))
      } catch (_: Throwable) {
        RecordingStateHolder.setFloatingControlsVisible(false)
      }
      syncTileWithCurrentState()
      return
    }

    // Idle and floating controls are not visible: launch transparent trampoline activity
    // to ensure overlay permission and show floating controls (or fall back to consent dialog)
    val launchIntent =
      QuickTileLaunchActivity.createShowControlsIntent(this).apply {
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
      }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val pendingIntent =
        PendingIntent.getActivity(
          this,
          4201,
          launchIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      startActivityAndCollapse(pendingIntent)
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(launchIntent)
    }
  }

  private fun syncTileWithCurrentState() {
    applyTileUi(
      state = RecordingStateHolder.recorderState.value,
      elapsedMs = RecordingStateHolder.elapsedTimeMs.value,
      countdown = RecordingStateHolder.countdownSecondsRemaining.value,
      controlsVisible = RecordingStateHolder.floatingControlsVisible.value,
    )
  }

  private fun applyTileUi(
    state: RecorderState,
    elapsedMs: Long,
    countdown: Int,
    controlsVisible: Boolean,
  ) {
    val tile = qsTile ?: return
    tile.label = getString(R.string.app_name)
    val isRecordingOrCountdown = state != RecorderState.IDLE || countdown > 0
    when {
      isRecordingOrCountdown -> {
        tile.state = Tile.STATE_ACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_stop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          tile.subtitle =
            when {
              countdown > 0 -> "Starting in ${countdown}s…"
              state == RecorderState.PAUSED ->
                "Paused · ${FormatUtils.formatElapsedTimer(elapsedMs)}"
              else -> FormatUtils.formatElapsedTimer(elapsedMs)
            }
        }
      }

      controlsVisible -> {
        tile.state = Tile.STATE_ACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_videocam)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          tile.subtitle = "Controls open"
        }
      }

      else -> {
        tile.state = Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_videocam)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          tile.subtitle = null
        }
      }
    }
    try {
      tile.updateTile()
    } catch (_: Throwable) {}
  }

  private data class TileUiSnapshot(
    val state: RecorderState,
    val elapsedMs: Long,
    val countdown: Int,
    val controlsVisible: Boolean,
  )
}
