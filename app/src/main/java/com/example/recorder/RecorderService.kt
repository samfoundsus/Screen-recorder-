package com.example.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.DataStoreSettingsRepository
import com.example.domain.model.AppSettings
import com.example.ui.components.FormatUtils
import com.example.ui.navigation.AppRoutes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground Service running the screen capture and encoding pipeline.
 *
 * Foreground state & floating handle behavior:
 * - When started from the app's Start button, no overlay exists while the app is in the foreground.
 * - When the app leaves the foreground (home, recents, another app, app swiped away), the floating
 *   edge handle appears at the saved edge position (if "Keep edge handle while recording" is on
 *   and overlay permission is granted).
 * - When the app returns to the foreground, the handle is removed because the app's in-app controls
 *   are visible.
 * - Closing the app / swiping it from recents (`onTaskRemoved`) does NOT stop active recording!
 *   The service keeps recording and the handle stays visible.
 * - After Stop, the handle is removed immediately and normal idle behavior returns.
 */
class RecorderService : Service() {

  companion object {
    private const val ACTION_SHOW_CONTROLS = "com.example.recorder.action.SHOW_CONTROLS"
    private const val ACTION_HIDE_CONTROLS = "com.example.recorder.action.HIDE_CONTROLS"
    private const val ACTION_START = "com.example.recorder.action.START"
    private const val ACTION_PAUSE = "com.example.recorder.action.PAUSE"
    private const val ACTION_RESUME = "com.example.recorder.action.RESUME"
    private const val ACTION_STOP = "com.example.recorder.action.STOP"

    private const val EXTRA_RESULT_CODE = "extra_result_code"
    private const val EXTRA_RESULT_DATA = "extra_result_data"
    private const val EXTRA_COUNTDOWN_SECONDS = "extra_countdown_seconds"
    private const val EXTRA_RESOLUTION = "extra_resolution"
    private const val EXTRA_FPS = "extra_fps"
    private const val EXTRA_BITRATE_MBPS = "extra_bitrate_mbps"
    private const val EXTRA_CUSTOM_BITRATE = "extra_custom_bitrate"
    private const val EXTRA_CODEC = "extra_codec"
    private const val EXTRA_CONTAINER = "extra_container"
    private const val EXTRA_ORIENTATION = "extra_orientation"
    private const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
    private const val EXTRA_MIC_ENABLED = "extra_mic_enabled"
    private const val EXTRA_NOISE_SUPPRESSION = "extra_noise_suppression"
    private const val EXTRA_AUDIO_CHANNELS = "extra_audio_channels"
    private const val EXTRA_KEEP_EDGE_HANDLE = "extra_keep_edge_handle"
    private const val EXTRA_RESTORE_CONTROLS_AFTER_STOP = "extra_restore_controls_after_stop"

    private const val CHANNEL_ID_ONGOING = "pixel_screen_recorder_ongoing"
    private const val CHANNEL_ID_ALERTS = "pixel_screen_recorder_alerts"
    private const val NOTIFICATION_ID_ONGOING = 4102
    private const val NOTIFICATION_ID_STOP_REASON = 4103
    private const val NOTIFICATION_ID_THERMAL_WARNING = 4104

    private const val STORAGE_CHECK_INTERVAL_MS = 5_000L
    private const val MEM_CHECK_INTERVAL_MS = 10_000L

    fun createShowControlsIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_SHOW_CONTROLS }

    fun createHideControlsIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_HIDE_CONTROLS }

    fun createStartIntent(
      context: Context,
      resultCode: Int,
      resultData: Intent,
      settings: AppSettings,
      restoreControlsAfterStop: Boolean = false,
    ): Intent {
      return Intent(context, RecorderService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_RESULT_DATA, resultData)
        putExtra(EXTRA_COUNTDOWN_SECONDS, settings.general.countdownSeconds)
        putExtra(EXTRA_RESOLUTION, settings.video.resolution)
        putExtra(EXTRA_FPS, settings.video.fps)
        putExtra(EXTRA_BITRATE_MBPS, settings.video.bitrateMbps)
        putExtra(EXTRA_CUSTOM_BITRATE, settings.video.isCustomBitrate)
        putExtra(EXTRA_CODEC, settings.video.codec)
        putExtra(EXTRA_CONTAINER, settings.video.container)
        putExtra(EXTRA_ORIENTATION, settings.video.orientation)
        putExtra(EXTRA_AUDIO_SOURCE, settings.audio.source)
        putExtra(EXTRA_MIC_ENABLED, settings.audio.micEnabled)
        putExtra(EXTRA_NOISE_SUPPRESSION, settings.audio.noiseSuppression)
        putExtra(EXTRA_AUDIO_CHANNELS, settings.audio.channelMode)
        putExtra(
          EXTRA_KEEP_EDGE_HANDLE,
          settings.floatingControls.keepEdgeHandleWhileRecording,
        )
        putExtra(EXTRA_RESTORE_CONTROLS_AFTER_STOP, restoreControlsAfterStop)
      }
    }

    fun createPauseIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_PAUSE }

    fun createResumeIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_RESUME }

    fun createStopIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_STOP }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val isFinalizing = AtomicBoolean(false)
  private val isSessionActive = AtomicBoolean(false)
  private val severeThermalWarningShown = AtomicBoolean(false)
  private val batteryReceiverRegistered = AtomicBoolean(false)
  private val thermalListenerRegistered = AtomicBoolean(false)

  private lateinit var settingsRepository: DataStoreSettingsRepository
  private lateinit var floatingControlsController: FloatingControlsController

  private var mediaProjection: MediaProjection? = null
  private var projectionCallback: MediaProjection.Callback? = null
  private var recordingEngine: ScreenRecordingEngine? = null
  private var countdownJob: Job? = null
  private var timerJob: Job? = null
  private var watchdogJob: Job? = null

  @Volatile private var activeTimeMs: Long = 0L
  @Volatile private var lastResumeRealtimeMs: Long = 0L
  @Volatile private var isPausedState: Boolean = false
  @Volatile private var hadControlsOpenBeforeRecording: Boolean = false
  @Volatile private var keepEdgeHandleDuringSession: Boolean = true

  private val batteryReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
        if (!isSessionActive.get() || isFinalizing.get()) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val batteryPct = (level * 100) / scale

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging =
          status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        if (batteryPct <= 3 && !isCharging) {
          ScreenRecordingEngine.debugLog(
            "Auto-stopping recording: battery level dropped to $batteryPct% (unplugged)"
          )
          finalizeAndStopSession(
            reason = StopReason.BATTERY_LOW,
            fromProjectionCallback = false,
            runSynchronously = false,
          )
        }
      }
    }

  private val thermalStatusListener =
    PowerManager.OnThermalStatusChangedListener { status ->
      if (!isSessionActive.get() || isFinalizing.get()) return@OnThermalStatusChangedListener

      when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> {
          ScreenRecordingEngine.debugLog(
            "Auto-stopping recording: thermal status reached critical ($status)"
          )
          finalizeAndStopSession(
            reason = StopReason.DEVICE_TOO_HOT,
            fromProjectionCallback = false,
            runSynchronously = false,
          )
        }
        status == PowerManager.THERMAL_STATUS_SEVERE -> {
          if (severeThermalWarningShown.compareAndSet(false, true)) {
            postThermalSevereWarningNotification()
          }
        }
      }
    }

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannels()
    settingsRepository = DataStoreSettingsRepository(applicationContext)
    floatingControlsController =
      FloatingControlsController(
        context = applicationContext,
        settingsRepository = settingsRepository,
        onStartRequested = { launchConsentFromFloatingControls() },
        onPauseRequested = { handlePause() },
        onResumeRequested = { handleResume() },
        onStopRequested = {
          finalizeAndStopSession(
            reason = StopReason.USER_STOP,
            fromProjectionCallback = false,
            runSynchronously = false,
          )
        },
        onCloseRequested = { handleHideControls() },
      )

    // React to app foreground / background transitions on the main thread
    serviceScope.launch {
      RecordingStateHolder.isAppInForeground.collectLatest { inForeground ->
        mainHandler.post {
          syncFloatingHandleForForegroundState(inForeground)
        }
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (::floatingControlsController.isInitialized) {
      floatingControlsController.onConfigurationChanged(newConfig)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_SHOW_CONTROLS -> handleShowControls()
      ACTION_HIDE_CONTROLS -> handleHideControls()
      ACTION_START -> handleStart(intent)
      ACTION_PAUSE -> handlePause()
      ACTION_RESUME -> handleResume()
      ACTION_STOP ->
        finalizeAndStopSession(
          reason = StopReason.USER_STOP,
          fromProjectionCallback = false,
          runSynchronously = false,
        )
    }
    return START_NOT_STICKY
  }

  private fun handleShowControls() {
    if (isSessionActive.get()) return
    val shown = floatingControlsController.restoreIdlePillAfterRecording()
    if (!shown) {
      stopSelf()
    }
  }

  private fun handleHideControls() {
    hadControlsOpenBeforeRecording = false
    floatingControlsController.removeOverlayCompletely()
    if (!isSessionActive.get() && recordingEngine == null && countdownJob == null) {
      stopSelf()
    }
  }

  private fun launchConsentFromFloatingControls() {
    if (isSessionActive.get()) return
    hadControlsOpenBeforeRecording = true
    keepEdgeHandleDuringSession = floatingControlsController.keepEdgeHandleWhileRecording

    if (keepEdgeHandleDuringSession) {
      floatingControlsController.collapseToEdgeHandleForRecording()
    } else {
      floatingControlsController.removeOverlayCompletely()
    }

    try {
      val launchIntent =
        QuickTileLaunchActivity.createStartRecordingFromControlsIntent(applicationContext).apply {
          addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_CLEAR_TOP or
              Intent.FLAG_ACTIVITY_SINGLE_TOP or
              Intent.FLAG_ACTIVITY_NO_ANIMATION
          )
        }
      startActivity(launchIntent)
    } catch (t: Throwable) {
      ScreenRecordingEngine.debugLog(
        "Failed to launch consent activity from floating controls: ${t.message}",
        t,
      )
      if (Settings.canDrawOverlays(applicationContext)) {
        floatingControlsController.restoreIdlePillAfterRecording()
      } else {
        stopSelf()
      }
    }
  }

  private fun handleStart(intent: Intent) {
    if (isSessionActive.get() || recordingEngine != null || countdownJob?.isActive == true) return

    val restoreRequested = intent.getBooleanExtra(EXTRA_RESTORE_CONTROLS_AFTER_STOP, false)
    val inForeground = RecordingStateHolder.isAppInForeground.value
    hadControlsOpenBeforeRecording =
      (hadControlsOpenBeforeRecording || restoreRequested) && !inForeground
    keepEdgeHandleDuringSession =
      intent.getBooleanExtra(EXTRA_KEEP_EDGE_HANDLE, true)

    if (inForeground) {
      floatingControlsController.removeOverlayCompletely()
    } else if (keepEdgeHandleDuringSession && Settings.canDrawOverlays(applicationContext)) {
      floatingControlsController.collapseToEdgeHandleForRecording()
    } else {
      floatingControlsController.removeOverlayCompletely()
    }

    if (!ScreenRecordingEngine.hasEnoughStorageToStart(applicationContext)) {
      RecordingStateHolder.setPendingSnackbarMessage(
        applicationContext,
        "Not enough storage space to start recording (at least 300 MB required).",
      )
      ScreenRecorderTileService.requestTileUpdate(applicationContext)
      restoreControlsOrStopSelf(allowRestore = true)
      return
    }

    isFinalizing.set(false)
    isSessionActive.set(true)
    severeThermalWarningShown.set(false)

    val countdownSeconds = intent.getIntExtra(EXTRA_COUNTDOWN_SECONDS, 0).coerceIn(0, 10)
    val sessionConfig =
      RecordingSessionConfig(
        resolutionLabel = intent.getStringExtra(EXTRA_RESOLUTION) ?: "1080p",
        fps = intent.getIntExtra(EXTRA_FPS, 60),
        bitrateMbps = intent.getIntExtra(EXTRA_BITRATE_MBPS, 12),
        isCustomBitrate = intent.getBooleanExtra(EXTRA_CUSTOM_BITRATE, false),
        codecLabel = intent.getStringExtra(EXTRA_CODEC) ?: "AVC (H.264)",
        containerLabel = intent.getStringExtra(EXTRA_CONTAINER) ?: "MP4",
        orientationLabel = intent.getStringExtra(EXTRA_ORIENTATION) ?: "Auto",
        audioSourceLabel = intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: "Device audio + Mic",
        micEnabled = intent.getBooleanExtra(EXTRA_MIC_ENABLED, true),
        noiseSuppression = intent.getBooleanExtra(EXTRA_NOISE_SUPPRESSION, true),
        audioChannelMode = intent.getStringExtra(EXTRA_AUDIO_CHANNELS) ?: "Stereo",
      )

    val hasRecordAudioPerm =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val audioUsed = sessionConfig.hasAnyAudio && hasRecordAudioPerm

    try {
      // 1. Call startForeground() FIRST with mediaProjection (+ microphone only when audio is used)
      val initialNotification =
        buildOngoingNotification(
          isPaused = false,
          elapsedMs = 0L,
          countdownSeconds = countdownSeconds,
        )
      val fgsType =
        if (audioUsed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

      startForeground(NOTIFICATION_ID_ONGOING, initialNotification, fgsType)

      // 2. Obtain single-use MediaProjection token AFTER startForeground()
      val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
      val resultData: Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
          @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

      if (resultData == null) {
        throw IllegalArgumentException("Missing MediaProjection consent data")
      }

      val projectionManager =
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      val projection =
        projectionManager.getMediaProjection(resultCode, resultData)
          ?: throw IllegalStateException("MediaProjectionManager returned null projection")
      mediaProjection = projection

      // 3. Register MediaProjection.Callback BEFORE createVirtualDisplay() (Android 14+ requirement)
      val callback =
        object : MediaProjection.Callback() {
          override fun onStop() {
            ScreenRecordingEngine.debugLog("MediaProjection.Callback.onStop received from system")
            finalizeAndStopSession(
              reason = StopReason.INTERRUPTED,
              fromProjectionCallback = true,
              runSynchronously = false,
            )
          }
        }
      projectionCallback = callback
      projection.registerCallback(callback, mainHandler)

      val effectiveConfig =
        if (audioUsed) {
          sessionConfig
        } else {
          sessionConfig.copy(audioSourceLabel = "Mute (No audio)", micEnabled = false)
        }

      if (countdownSeconds > 0) {
        RecordingStateHolder.updateElapsedTime(0L)
        RecordingStateHolder.setCountingDown(countdownSeconds)
        ScreenRecorderTileService.requestTileUpdate(applicationContext)
        countdownJob =
          serviceScope.launch {
            var remaining = countdownSeconds
            while (remaining > 0 && isActive && !isFinalizing.get()) {
              RecordingStateHolder.setCountingDown(remaining)
              updateOngoingNotification(countdownSeconds = remaining)
              ScreenRecorderTileService.requestTileUpdate(applicationContext)
              delay(1_000L)
              remaining--
            }
            if (!isFinalizing.get() && isActive) {
              RecordingStateHolder.setCountingDown(0)
              mainHandler.post {
                if (!isFinalizing.get()) {
                  startEngineNow(projection, effectiveConfig)
                }
              }
            }
          }
      } else {
        startEngineNow(projection, effectiveConfig)
      }
    } catch (t: Throwable) {
      ScreenRecordingEngine.debugLog(
        "Failed to initialize MediaProjection in RecorderService: ${t.message}",
        t,
      )
      isSessionActive.set(false)
      RecordingStateHolder.reportError("Could not start screen recording.")
      ScreenRecorderTileService.requestTileUpdate(applicationContext)
      cleanupFailureAndTeardown()
      restoreControlsOrStopSelf(allowRestore = true)
    }
  }

  private fun startEngineNow(projection: MediaProjection, config: RecordingSessionConfig) {
    try {
      activeTimeMs = 0L
      lastResumeRealtimeMs = SystemClock.elapsedRealtime()
      isPausedState = false

      val engine =
        ScreenRecordingEngine(
          context = applicationContext,
          mediaProjection = projection,
          config = config,
          onEngineError = { stopReason ->
            ScreenRecordingEngine.debugLog("ScreenRecordingEngine onEngineError received: $stopReason")
            finalizeAndStopSession(
              reason = stopReason,
              fromProjectionCallback = false,
              runSynchronously = false,
            )
          },
        )
      recordingEngine = engine
      engine.start()

      RecordingStateHolder.setRecording()
      ScreenRecorderTileService.requestTileUpdate(applicationContext)

      registerBatteryAndThermalMonitors()
      startStorageAndMemoryWatchdog()
      startElapsedTicker()
      updateOngoingNotification()

      // Synchronize floating controls visibility with current foreground state
      syncFloatingHandleForForegroundState(RecordingStateHolder.isAppInForeground.value)
    } catch (t: Throwable) {
      ScreenRecordingEngine.debugLog(
        "ScreenRecordingEngine start failed in RecorderService: ${t.message}",
        t,
      )
      isSessionActive.set(false)
      RecordingStateHolder.reportError("Encoder initialization failed.")
      ScreenRecorderTileService.requestTileUpdate(applicationContext)
      cleanupFailureAndTeardown()
      restoreControlsOrStopSelf(allowRestore = true)
    }
  }

  private fun syncFloatingHandleForForegroundState(inForeground: Boolean) {
    val isRecording =
      isSessionActive.get() || recordingEngine != null || countdownJob?.isActive == true
    if (!isRecording) return

    if (inForeground) {
      if (::floatingControlsController.isInitialized) {
        floatingControlsController.removeOverlayCompletely()
      }
    } else {
      val canKeepHandle =
        keepEdgeHandleDuringSession &&
          floatingControlsController.keepEdgeHandleWhileRecording &&
          Settings.canDrawOverlays(applicationContext)
      if (canKeepHandle) {
        floatingControlsController.collapseToEdgeHandleForRecording()
      } else {
        floatingControlsController.removeOverlayCompletely()
      }
    }
  }

  private fun handlePause() {
    val engine = recordingEngine ?: return
    if (!isSessionActive.get() || isPausedState || isFinalizing.get()) return

    isPausedState = true
    timerJob?.cancel()
    timerJob = null
    activeTimeMs += (SystemClock.elapsedRealtime() - lastResumeRealtimeMs).coerceAtLeast(0L)
    RecordingStateHolder.updateElapsedTime(activeTimeMs)

    engine.pause()
    RecordingStateHolder.setPaused()
    ScreenRecorderTileService.requestTileUpdate(applicationContext)
    updateOngoingNotification(isPaused = true)
  }

  private fun handleResume() {
    val engine = recordingEngine ?: return
    if (!isSessionActive.get() || !isPausedState || isFinalizing.get()) return

    isPausedState = false
    lastResumeRealtimeMs = SystemClock.elapsedRealtime()

    engine.resume()
    RecordingStateHolder.setRecording()
    ScreenRecorderTileService.requestTileUpdate(applicationContext)
    startElapsedTicker()
    updateOngoingNotification()
  }

  /**
   * Single idempotent finalize routine shared by ALL recording termination paths:
   * user Stop (in-app, notification, Quick Settings tile, or floating handle), system
   * MediaProjection.Callback.onStop, service onDestroy, low storage (< 150 MB), low battery
   * (<= 3% unplugged), thermal critical, or an encoder/muxer/IO error.
   */
  private fun finalizeAndStopSession(
    reason: StopReason,
    fromProjectionCallback: Boolean,
    runSynchronously: Boolean,
  ) {
    if (!isSessionActive.get() && recordingEngine == null && countdownJob == null) {
      if (runSynchronously) {
        floatingControlsController.removeOverlayCompletely()
      }
      return
    }
    if (!isFinalizing.compareAndSet(false, true)) return

    isSessionActive.set(false)
    countdownJob?.cancel()
    countdownJob = null
    timerJob?.cancel()
    timerJob = null
    watchdogJob?.cancel()
    watchdogJob = null

    unregisterBatteryAndThermalMonitors()

    val engine = recordingEngine
    recordingEngine = null

    val finalizeBlock: suspend () -> Unit = {
      var finalizedUri: android.net.Uri? = null
      try {
        finalizedUri = engine?.stopAndFinalize()
        MemoryDiagnostics.onRecordingSessionEnded(applicationContext)
        if (finalizedUri != null) {
          ScreenRecordingEngine.debugLog(
            "Finalized Uri ready for MediaStore: uri=$finalizedUri, reason=$reason"
          )
          if (reason != StopReason.USER_STOP) {
            RecordingStateHolder.setPendingSnackbarMessage(
              applicationContext,
              "Recording stopped and saved",
            )
          }
        } else {
          ScreenRecordingEngine.debugLog("No finalized recording published (reason=$reason)")
        }
      } catch (t: Throwable) {
        ScreenRecordingEngine.debugLog(
          "Exception during RecorderService finalize: ${t.message}",
          t,
        )
      } finally {
        RecordingStateHolder.setIdle()
        ScreenRecorderTileService.requestTileUpdate(applicationContext)
        teardownProjectionAndForeground(stopProjection = !fromProjectionCallback)
        if (reason != StopReason.USER_STOP) {
          postNonUserStopNotification(reason, savedSuccessfully = finalizedUri != null)
        }
        isFinalizing.set(false)
        if (runSynchronously) {
          hadControlsOpenBeforeRecording = false
          floatingControlsController.removeOverlayCompletely()
          stopSelf()
        } else {
          mainHandler.post {
            restoreControlsOrStopSelf(allowRestore = reason == StopReason.USER_STOP)
          }
        }
      }
    }

    if (runSynchronously) {
      runBlocking(Dispatchers.IO) {
        finalizeBlock()
      }
    } else {
      serviceScope.launch {
        finalizeBlock()
      }
    }
  }

  private fun restoreControlsOrStopSelf(allowRestore: Boolean) {
    val shouldRestore =
      allowRestore &&
        hadControlsOpenBeforeRecording &&
        Settings.canDrawOverlays(applicationContext)
    hadControlsOpenBeforeRecording = false
    keepEdgeHandleDuringSession = true
    if (shouldRestore) {
      val shown = floatingControlsController.restoreIdlePillAfterRecording()
      if (!shown) {
        stopSelf()
      }
    } else {
      floatingControlsController.removeOverlayCompletely()
      stopSelf()
    }
  }

  private fun registerBatteryAndThermalMonitors() {
    if (batteryReceiverRegistered.compareAndSet(false, true)) {
      try {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = registerReceiver(batteryReceiver, filter)
        if (sticky != null) {
          batteryReceiver.onReceive(applicationContext, sticky)
        }
      } catch (_: Throwable) {
        batteryReceiverRegistered.set(false)
      }
    }

    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        thermalListenerRegistered.compareAndSet(false, true)
    ) {
      try {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.addThermalStatusListener(ContextCompat.getMainExecutor(this), thermalStatusListener)
      } catch (_: Throwable) {
        thermalListenerRegistered.set(false)
      }
    }
  }

  private fun unregisterBatteryAndThermalMonitors() {
    if (batteryReceiverRegistered.compareAndSet(true, false)) {
      try {
        unregisterReceiver(batteryReceiver)
      } catch (_: Throwable) {}
    }

    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        thermalListenerRegistered.compareAndSet(true, false)
    ) {
      try {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.removeThermalStatusListener(thermalStatusListener)
      } catch (_: Throwable) {}
    }
  }

  /**
   * Checks free storage every 5 seconds (< 150 MB triggers clean finalize & stop)
   * and logs debug MemCheck diagnostics every 10 seconds.
   */
  private fun startStorageAndMemoryWatchdog() {
    watchdogJob?.cancel()
    watchdogJob =
      serviceScope.launch {
        var tickCount = 0
        if (MemoryDiagnostics.IS_ENABLED) {
          MemoryDiagnostics.sampleAndLogWhileRecording(applicationContext)
        }
        while (isActive && isSessionActive.get() && !isFinalizing.get()) {
          delay(STORAGE_CHECK_INTERVAL_MS)
          if (!isActive || !isSessionActive.get() || isFinalizing.get()) break

          val freeBytes = ScreenRecordingEngine.getAvailableStorageBytes(applicationContext)
          if (freeBytes < ScreenRecordingEngine.MIN_RECORDING_FREE_BYTES) {
            ScreenRecordingEngine.debugLog(
              "Auto-stopping recording: free storage (${freeBytes / (1024 * 1024)} MB) below 150 MB threshold"
            )
            finalizeAndStopSession(
              reason = StopReason.STORAGE_ALMOST_FULL,
              fromProjectionCallback = false,
              runSynchronously = false,
            )
            break
          }

          tickCount++
          if (MemoryDiagnostics.IS_ENABLED && tickCount % 2 == 0) {
            MemoryDiagnostics.sampleAndLogWhileRecording(applicationContext)
          }
        }
      }
  }

  private fun startElapsedTicker() {
    timerJob?.cancel()
    timerJob =
      serviceScope.launch {
        var lastSecondNotified = -1L
        while (isActive && !isPausedState && !isFinalizing.get()) {
          val currentElapsed =
            activeTimeMs +
              (SystemClock.elapsedRealtime() - lastResumeRealtimeMs).coerceAtLeast(0L)
          RecordingStateHolder.updateElapsedTime(currentElapsed)
          val elapsedSec = currentElapsed / 1_000L
          if (elapsedSec != lastSecondNotified) {
            lastSecondNotified = elapsedSec
            ScreenRecorderTileService.requestTileUpdate(applicationContext)
          }
          delay(200L)
        }
      }
  }

  private fun ensureNotificationChannels() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val ongoingChannel =
      NotificationChannel(
          CHANNEL_ID_ONGOING,
          "Screen Recording",
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply {
          description = "Ongoing screen recording status and controls"
          setSound(null, null)
          enableVibration(false)
          setShowBadge(false)
        }
    val alertsChannel =
      NotificationChannel(
          CHANNEL_ID_ALERTS,
          "Recording Alerts",
          NotificationManager.IMPORTANCE_DEFAULT,
        )
        .apply {
          description = "Notifications when screen recording stops due to storage, battery, or heat"
        }
    nm.createNotificationChannel(ongoingChannel)
    nm.createNotificationChannel(alertsChannel)
  }

  private fun updateOngoingNotification(isPaused: Boolean = isPausedState, countdownSeconds: Int = 0) {
    if (!isSessionActive.get() && countdownSeconds <= 0) return
    val elapsed = RecordingStateHolder.elapsedTimeMs.value
    val notification =
      buildOngoingNotification(
        isPaused = isPaused,
        elapsedMs = elapsed,
        countdownSeconds = countdownSeconds,
      )
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID_ONGOING, notification)
  }

  private fun postThermalSevereWarningNotification() {
    val notification =
      NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
        .setSmallIcon(R.drawable.ic_qs_videocam)
        .setContentTitle("Device is getting hot")
        .setContentText(
          "Recording may stop automatically if the device reaches critical temperature."
        )
        .setOngoing(false)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID_THERMAL_WARNING, notification)
  }

  private fun buildOngoingNotification(
    isPaused: Boolean,
    elapsedMs: Long,
    countdownSeconds: Int = 0,
  ): Notification {
    val openAppIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val contentPendingIntent =
      PendingIntent.getActivity(
        this,
        100,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopPendingIntent =
      PendingIntent.getService(
        this,
        101,
        createStopIntent(this),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val pauseResumeIntent =
      if (isPaused) createResumeIntent(this) else createPauseIntent(this)
    val pauseResumePendingIntent =
      PendingIntent.getService(
        this,
        102,
        pauseResumeIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val isCountingDown = countdownSeconds > 0
    val title =
      when {
        isCountingDown -> "Starting in $countdownSeconds…"
        isPaused -> "Recording paused · ${FormatUtils.formatElapsedTimer(elapsedMs)}"
        else -> "Recording screen"
      }
    val subText =
      when {
        isCountingDown -> "Tap Cancel to abort"
        isPaused -> "Tap Resume to continue or Stop to save"
        else -> "Tap notification to open · Stop to save"
      }

    val builder =
      NotificationCompat.Builder(this, CHANNEL_ID_ONGOING)
        .setSmallIcon(R.drawable.ic_qs_videocam)
        .setContentTitle(title)
        .setContentText(subText)
        .setContentIntent(contentPendingIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setShowWhen(!isPaused && !isCountingDown)
        .setUsesChronometer(!isPaused && !isCountingDown)
        .setWhen(System.currentTimeMillis() - elapsedMs)

    if (!isCountingDown) {
      builder.addAction(
        0,
        if (isPaused) "Resume" else "Pause",
        pauseResumePendingIntent,
      )
    }
    builder.addAction(
      0,
      if (isCountingDown) "Cancel" else "Stop",
      stopPendingIntent,
    )

    return builder.build()
  }

  /**
   * Posts a normal (non-ongoing) notification when a recording ends for any reason other than
   * a user Stop. Tapping it opens the Library.
   */
  private fun postNonUserStopNotification(reason: StopReason, savedSuccessfully: Boolean) {
    val title = reason.notificationTitle ?: return
    val openLibraryIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_DESTINATION, AppRoutes.LIBRARY)
      }
    val contentPendingIntent =
      PendingIntent.getActivity(
        this,
        201,
        openLibraryIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val bodyText =
      if (savedSuccessfully) {
        "Recording was finalized and saved. Tap to open Library."
      } else {
        "Tap to open Library."
      }

    val notification =
      NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
        .setSmallIcon(R.drawable.ic_qs_videocam)
        .setContentTitle(title)
        .setContentText(bodyText)
        .setContentIntent(contentPendingIntent)
        .setOngoing(false)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()

    try {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.notify(NOTIFICATION_ID_STOP_REASON, notification)
    } catch (_: Throwable) {}
  }

  private fun cleanupFailureAndTeardown() {
    timerJob?.cancel()
    timerJob = null
    countdownJob?.cancel()
    countdownJob = null
    watchdogJob?.cancel()
    watchdogJob = null
    unregisterBatteryAndThermalMonitors()
    try {
      recordingEngine?.cleanupAllOnFailure()
    } catch (_: Throwable) {} finally {
      recordingEngine = null
    }
    teardownProjectionAndForeground(stopProjection = true)
  }

  private fun teardownProjectionAndForeground(stopProjection: Boolean) {
    val proj = mediaProjection
    val cb = projectionCallback
    mediaProjection = null
    projectionCallback = null

    if (proj != null && cb != null) {
      try {
        proj.unregisterCallback(cb)
      } catch (_: Throwable) {}
    }
    if (stopProjection && proj != null) {
      try {
        proj.stop()
      } catch (_: Throwable) {}
    }

    try {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } catch (_: Throwable) {}
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val isRecording =
      isSessionActive.get() || isFinalizing.get() || recordingEngine != null || countdownJob?.isActive == true
    if (isRecording) {
      // Swiping the app from recents must NOT stop active recording!
      // The foreground service keeps recording and the floating handle remains visible.
      RecordingStateHolder.setAppInForeground(false)
      mainHandler.post {
        syncFloatingHandleForForegroundState(inForeground = false)
      }
      super.onTaskRemoved(rootIntent)
      return
    }

    // When no recording is active, closing the app stops the service as before
    hadControlsOpenBeforeRecording = false
    if (::floatingControlsController.isInitialized) {
      floatingControlsController.removeOverlayCompletely()
    }
    stopSelf()
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    try {
      hadControlsOpenBeforeRecording = false
      finalizeAndStopSession(
        reason = StopReason.INTERRUPTED,
        fromProjectionCallback = false,
        runSynchronously = true,
      )
    } finally {
      if (::floatingControlsController.isInitialized) {
        floatingControlsController.destroy()
      }
      serviceScope.cancel()
      super.onDestroy()
    }
  }
}
