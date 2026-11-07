package com.example.recorder

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.MainActivity
import com.example.domain.model.RecorderState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StopReason(val notificationTitle: String?) {
  USER_STOP(null),
  STORAGE_ALMOST_FULL("Stopped: storage almost full"),
  BATTERY_LOW("Stopped: battery low"),
  DEVICE_TOO_HOT("Stopped: device too hot"),
  INTERRUPTED("Stopped: recording was interrupted"),
}

sealed interface RecordingEngineStatus {
  data object Idle : RecordingEngineStatus

  data object Recording : RecordingEngineStatus

  data object Paused : RecordingEngineStatus

  data class Error(val message: String) : RecordingEngineStatus
}

/**
 * UI-free singleton state holder bridging [RecorderService], [ScreenRecorderTileService],
 * [FloatingControlsController], [RecordingPermissionCoordinator], and [RealRecorderController].
 * Survives Activity rotation and re-attachment seamlessly.
 */
object RecordingStateHolder {

  private const val PREFS_NAME = "pixel_recorder_runtime_prefs"
  private const val KEY_PENDING_SNACKBAR = "pending_snackbar_message"

  private val _engineStatus = MutableStateFlow<RecordingEngineStatus>(RecordingEngineStatus.Idle)
  val engineStatus: StateFlow<RecordingEngineStatus> = _engineStatus.asStateFlow()

  private val _recorderState = MutableStateFlow(RecorderState.IDLE)
  val recorderState: StateFlow<RecorderState> = _recorderState.asStateFlow()

  private val _elapsedTimeMs = MutableStateFlow(0L)
  val elapsedTimeMs: StateFlow<Long> = _elapsedTimeMs.asStateFlow()

  private val _countdownSecondsRemaining = MutableStateFlow(0)
  val countdownSecondsRemaining: StateFlow<Int> = _countdownSecondsRemaining.asStateFlow()

  private val _floatingControlsVisible = MutableStateFlow(false)
  val floatingControlsVisible: StateFlow<Boolean> = _floatingControlsVisible.asStateFlow()

  private val _isAppInForeground = MutableStateFlow(false)
  val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

  private val _pendingNavigationRoute = MutableStateFlow<String?>(null)
  val pendingNavigationRoute: StateFlow<String?> = _pendingNavigationRoute.asStateFlow()

  private val _startRequests =
    MutableSharedFlow<Unit>(
      replay = 0,
      extraBufferCapacity = 1,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  val startRequests: SharedFlow<Unit> = _startRequests.asSharedFlow()

  private var startedActivitiesCount = 0
  private var pendingBackgroundRunnable: Runnable? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var isLifecycleRegistered = false

  fun requestStart() {
    _startRequests.tryEmit(Unit)
  }

  fun setFloatingControlsVisible(visible: Boolean) {
    _floatingControlsVisible.value = visible
  }

  fun setAppInForeground(inForeground: Boolean) {
    pendingBackgroundRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingBackgroundRunnable = null
    _isAppInForeground.value = inForeground
  }

  fun registerActivityLifecycle(app: Application) {
    if (isLifecycleRegistered) return
    isLifecycleRegistered = true

    app.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
          if (activity is MainActivity) {
            startedActivitiesCount++
            pendingBackgroundRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingBackgroundRunnable = null
            _isAppInForeground.value = true
          }
        }

        override fun onActivityResumed(activity: Activity) {
          if (activity is MainActivity) {
            startedActivitiesCount = maxOf(startedActivitiesCount, 1)
            pendingBackgroundRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingBackgroundRunnable = null
            _isAppInForeground.value = true
          }
        }

        override fun onActivityStopped(activity: Activity) {
          if (activity is MainActivity) {
            startedActivitiesCount = (startedActivitiesCount - 1).coerceAtLeast(0)
            if (startedActivitiesCount == 0) {
              if (activity.isChangingConfigurations) {
                // Activity recreation due to rotation or configuration change. Do not mark as background.
                return
              }
              pendingBackgroundRunnable?.let { mainHandler.removeCallbacks(it) }
              val runnable = Runnable {
                if (startedActivitiesCount == 0) {
                  _isAppInForeground.value = false
                }
              }
              pendingBackgroundRunnable = runnable
              mainHandler.postDelayed(runnable, 300L)
            }
          }
        }

        override fun onActivityDestroyed(activity: Activity) {
          if (activity is MainActivity) {
            if (!activity.isChangingConfigurations && startedActivitiesCount == 0) {
              _isAppInForeground.value = false
            }
          }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      }
    )
  }

  fun setCountingDown(seconds: Int) {
    _countdownSecondsRemaining.value = seconds.coerceAtLeast(0)
    if (seconds > 0) {
      _recorderState.value = RecorderState.RECORDING
      _engineStatus.value = RecordingEngineStatus.Recording
    }
  }

  fun setRecording() {
    _countdownSecondsRemaining.value = 0
    _engineStatus.value = RecordingEngineStatus.Recording
    _recorderState.value = RecorderState.RECORDING
  }

  fun setPaused() {
    _countdownSecondsRemaining.value = 0
    _engineStatus.value = RecordingEngineStatus.Paused
    _recorderState.value = RecorderState.PAUSED
  }

  fun setIdle() {
    _countdownSecondsRemaining.value = 0
    _engineStatus.value = RecordingEngineStatus.Idle
    _recorderState.value = RecorderState.IDLE
    _elapsedTimeMs.value = 0L
  }

  fun updateElapsedTime(elapsedMs: Long) {
    _elapsedTimeMs.value = elapsedMs
  }

  fun showSnackbarMessage(message: String) {
    _errorMessage.value = message
  }

  fun setPendingSnackbarMessage(context: Context, message: String) {
    _errorMessage.value = message
    try {
      context
        .applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_PENDING_SNACKBAR, message)
        .apply()
    } catch (_: Throwable) {}
  }

  fun loadPendingSnackbarIfNeeded(context: Context) {
    try {
      val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val saved = prefs.getString(KEY_PENDING_SNACKBAR, null)
      if (!saved.isNullOrBlank()) {
        prefs.edit().remove(KEY_PENDING_SNACKBAR).apply()
        if (_errorMessage.value.isNullOrBlank()) {
          _errorMessage.value = saved
        }
      }
    } catch (_: Throwable) {}
  }

  fun requestNavigateToRoute(route: String) {
    _pendingNavigationRoute.value = route
  }

  fun consumeNavigationRoute() {
    _pendingNavigationRoute.value = null
  }

  fun reportError(message: String) {
    _countdownSecondsRemaining.value = 0
    _engineStatus.value = RecordingEngineStatus.Error(message)
    _recorderState.value = RecorderState.IDLE
    _elapsedTimeMs.value = 0L
    _errorMessage.value = message
  }

  fun consumeErrorMessage() {
    _errorMessage.value = null
  }
}
