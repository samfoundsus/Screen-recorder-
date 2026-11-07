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
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.domain.model.RecorderState
import com.example.recorder.ipc.RecorderIpcProtocol
import com.example.recorder.ipc.RecorderSettingsSnapshot
import com.example.ui.components.FormatUtils
import com.example.ui.navigation.AppRoutes
import com.example.util.DeviceUtils
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground Service running the screen capture and encoding pipeline in the `:recorder` process.
 *
 * Runs completely detached from Compose, Media3, Coil, DataStore, and Room.
 * Communicates with the main UI process and Quick Settings tile over a bound [Messenger].
 */
class RecorderService : Service() {

  companion object {
    const val ACTION_SHOW_CONTROLS = "com.example.recorder.action.SHOW_CONTROLS"
    const val ACTION_HIDE_CONTROLS = "com.example.recorder.action.HIDE_CONTROLS"
    const val ACTION_START = "com.example.recorder.action.START"
    const val ACTION_PAUSE = "com.example.recorder.action.PAUSE"
    const val ACTION_RESUME = "com.example.recorder.action.RESUME"
    const val ACTION_STOP = "com.example.recorder.action.STOP"

    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"
    const val EXTRA_SETTINGS_SNAPSHOT = "extra_settings_snapshot"
    const val EXTRA_RESTORE_CONTROLS_AFTER_STOP = "extra_restore_controls_after_stop"

    private const val CHANNEL_ID_ONGOING = "pixel_screen_recorder_ongoing"
    private const val CHANNEL_ID_ALERTS = "pixel_screen_recorder_alerts"
    private const val NOTIFICATION_ID_ONGOING = 4102
    private const val NOTIFICATION_ID_STOP_REASON = 4103
    private const val NOTIFICATION_ID_THERMAL_WARNING = 4104
    private const val NOTIFICATION_ID_THERMAL_MODERATE = 4108

    private const val STORAGE_CHECK_INTERVAL_MS = 5_000L
    private const val MEM_CHECK_INTERVAL_MS = 10_000L

    fun createShowControlsIntent(context: Context, snapshot: RecorderSettingsSnapshot): Intent =
      Intent(context, RecorderService::class.java).apply {
        action = ACTION_SHOW_CONTROLS
        putExtra(EXTRA_SETTINGS_SNAPSHOT, snapshot)
      }

    fun createHideControlsIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_HIDE_CONTROLS }

    fun createStartIntent(
      context: Context,
      resultCode: Int,
      resultData: Intent,
      snapshot: RecorderSettingsSnapshot,
      restoreControlsAfterStop: Boolean = false,
    ): Intent {
      return Intent(context, RecorderService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_RESULT_DATA, resultData)
        putExtra(EXTRA_SETTINGS_SNAPSHOT, snapshot)
        putExtra(EXTRA_RESTORE_CONTROLS_AFTER_STOP, restoreControlsAfterStop)
      }
    }

    const val EXTRA_STOP_SOURCE = "extra_stop_source"

    fun createPauseIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_PAUSE }

    fun createResumeIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_RESUME }

    fun createStopIntent(context: Context, source: String = "user"): Intent =
      Intent(context, RecorderService::class.java).apply {
        action = ACTION_STOP
        putExtra(EXTRA_STOP_SOURCE, source)
      }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val isFinalizing = AtomicBoolean(false)
  private val isSessionActive = AtomicBoolean(false)
  private val severeThermalWarningShown = AtomicBoolean(false)
  private val moderateThermalWarningShown = AtomicBoolean(false)
  private val batteryReceiverRegistered = AtomicBoolean(false)
  private val thermalListenerRegistered = AtomicBoolean(false)

  @Volatile private var sessionStartRealtimeMs: Long = 0L

  private val registeredClients = CopyOnWriteArrayList<Messenger>()

  private lateinit var floatingControlsController: FloatingControlsController
  private var currentSnapshot: RecorderSettingsSnapshot = RecorderSettingsSnapshot()

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
  @Volatile private var isAppInForeground: Boolean = false
  @Volatile private var currentCountdownSeconds: Int = 0

  private val incomingHandler =
    object : Handler(Looper.getMainLooper()) {
      override fun handleMessage(msg: Message) {
        when (msg.what) {
          RecorderIpcProtocol.MSG_REGISTER_CLIENT -> {
            val replyTo = msg.replyTo
            if (replyTo != null && !registeredClients.contains(replyTo)) {
              registeredClients.add(replyTo)
              sendStateUpdateToClient(replyTo)
            }
          }

          RecorderIpcProtocol.MSG_UNREGISTER_CLIENT -> {
            val replyTo = msg.replyTo
            if (replyTo != null) {
              registeredClients.remove(replyTo)
            }
          }

          RecorderIpcProtocol.MSG_START_RECORDING -> {
            val data = msg.data ?: return
            data.classLoader = RecorderSettingsSnapshot::class.java.classLoader
            val snapshot = RecorderSettingsSnapshot.fromBundle(data)
            val resultCode = data.getInt(RecorderIpcProtocol.KEY_RESULT_CODE, 0)
            val resultData: Intent? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelable(RecorderIpcProtocol.KEY_RESULT_DATA, Intent::class.java)
              } else {
                @Suppress("DEPRECATION")
                data.getParcelable(RecorderIpcProtocol.KEY_RESULT_DATA)
              }
            val restoreControls = data.getBoolean(RecorderIpcProtocol.KEY_RESTORE_CONTROLS, false)
            if (resultData != null) {
              val intent =
                createStartIntent(
                  context = applicationContext,
                  resultCode = resultCode,
                  resultData = resultData,
                  snapshot = snapshot,
                  restoreControlsAfterStop = restoreControls,
                )
              handleStart(intent)
            }
          }

          RecorderIpcProtocol.MSG_PAUSE_RECORDING -> handlePause()
          RecorderIpcProtocol.MSG_RESUME_RECORDING -> handleResume()
          RecorderIpcProtocol.MSG_STOP_RECORDING ->
            finalizeAndStopSession(
              reason = StopReason.USER,
              fromProjectionCallback = false,
              runSynchronously = false,
            )

          RecorderIpcProtocol.MSG_SHOW_CONTROLS -> {
            val data = msg.data
            if (data != null) {
              data.classLoader = RecorderSettingsSnapshot::class.java.classLoader
              currentSnapshot = RecorderSettingsSnapshot.fromBundle(data)
              if (::floatingControlsController.isInitialized) {
                floatingControlsController.updateSettings(currentSnapshot)
              }
            }
            handleShowControls()
          }

          RecorderIpcProtocol.MSG_HIDE_CONTROLS -> handleHideControls()

          RecorderIpcProtocol.MSG_SET_APP_FOREGROUND -> {
            val inForeground = msg.arg1 == 1
            isAppInForeground = inForeground
            syncFloatingHandleForForegroundState(inForeground)
          }

          RecorderIpcProtocol.MSG_GET_MEMORY_STATS -> {
            val replyTo = msg.replyTo ?: return
            sendMemoryStatsResponse(replyTo)
          }
        }
      }
    }

  private val serviceMessenger = Messenger(incomingHandler)

  private val batteryReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
        if (DeviceUtils.isEmulator()) return
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
            reason = StopReason.LOW_BATTERY,
            fromProjectionCallback = false,
            runSynchronously = false,
          )
        }
      }
    }

  private val thermalStatusListener =
    PowerManager.OnThermalStatusChangedListener { status ->
      if (DeviceUtils.isEmulator()) return@OnThermalStatusChangedListener
      if (!isSessionActive.get() || isFinalizing.get()) return@OnThermalStatusChangedListener

      when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> {
          ScreenRecordingEngine.debugLog(
            "Auto-stopping recording: thermal status reached critical ($status)"
          )
          finalizeAndStopSession(
            reason = StopReason.THERMAL,
            fromProjectionCallback = false,
            runSynchronously = false,
          )
        }
        status == PowerManager.THERMAL_STATUS_SEVERE -> {
          if (severeThermalWarningShown.compareAndSet(false, true)) {
            postThermalSevereWarningNotification()
          }
        }
        status == PowerManager.THERMAL_STATUS_MODERATE -> {
          if (currentSnapshot.isGamingMode && moderateThermalWarningShown.compareAndSet(false, true)) {
            postThermalModerateWarningNotification()
          }
        }
      }
    }

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannels()

    floatingControlsController =
      FloatingControlsController(
        context = applicationContext,
        currentSnapshot = currentSnapshot,
        onStartRequested = { launchConsentFromFloatingControls() },
        onPauseRequested = { handlePause() },
        onResumeRequested = { handleResume() },
        onStopRequested = {
          finalizeAndStopSession(
            reason = StopReason.HANDLE,
            fromProjectionCallback = false,
            runSynchronously = false,
          )
        },
        onCloseRequested = { handleHideControls() },
        onControlsVisibilityChanged = { visible ->
          broadcastStateUpdate(controlsVisible = visible)
        },
        onPositionChanged = { px, py, lx, ly ->
          broadcastPositionChanged(px, py, lx, ly)
        },
        onQuickSettingChanged = { res, fps, audio ->
          currentSnapshot = currentSnapshot.copy(resolution = res, fps = fps, audioSource = audio)
        },
        elapsedMsSupplier = { currentElapsedMs() },
      )
  }

  override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (::floatingControlsController.isInitialized) {
      floatingControlsController.onConfigurationChanged(newConfig)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_SHOW_CONTROLS -> {
        val snapshot = extractSnapshotFromIntent(intent)
        if (snapshot != null) {
          currentSnapshot = snapshot
          if (::floatingControlsController.isInitialized) {
            floatingControlsController.updateSettings(snapshot)
          }
        }
        handleShowControls()
      }
      ACTION_HIDE_CONTROLS -> handleHideControls()
      ACTION_START -> {
        val snapshot = extractSnapshotFromIntent(intent)
        if (snapshot != null) {
          currentSnapshot = snapshot
          if (::floatingControlsController.isInitialized) {
            floatingControlsController.updateSettings(snapshot)
          }
        }
        if (intent != null) handleStart(intent)
      }
      ACTION_PAUSE -> handlePause()
      ACTION_RESUME -> handleResume()
      ACTION_STOP -> {
        val source = intent?.getStringExtra(EXTRA_STOP_SOURCE) ?: "user"
        val reason = when (source) {
          "notification" -> StopReason.NOTIFICATION
          "tile" -> StopReason.TILE
          "handle" -> StopReason.HANDLE
          else -> StopReason.USER
        }
        finalizeAndStopSession(
          reason = reason,
          fromProjectionCallback = false,
          runSynchronously = false,
        )
      }
    }
    return START_NOT_STICKY
  }

  private fun extractSnapshotFromIntent(intent: Intent?): RecorderSettingsSnapshot? {
    if (intent == null) return null
    intent.setExtrasClassLoader(RecorderSettingsSnapshot::class.java.classLoader)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(EXTRA_SETTINGS_SNAPSHOT, RecorderSettingsSnapshot::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(EXTRA_SETTINGS_SNAPSHOT)
    }
  }

  private fun handleShowControls() {
    if (isSessionActive.get()) return
    val shown = floatingControlsController.restoreIdlePillAfterRecording()
    if (!shown) {
      stopSelf()
    } else {
      broadcastStateUpdate(controlsVisible = true)
    }
  }

  private fun handleHideControls() {
    hadControlsOpenBeforeRecording = false
    floatingControlsController.removeOverlayCompletely()
    broadcastStateUpdate(controlsVisible = false)
    if (!isSessionActive.get() && recordingEngine == null && countdownJob == null) {
      stopSelf()
    }
  }

  private fun launchConsentFromFloatingControls() {
    if (isSessionActive.get()) return
    hadControlsOpenBeforeRecording = true

    if (floatingControlsController.keepEdgeHandleWhileRecording) {
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
    hadControlsOpenBeforeRecording =
      (hadControlsOpenBeforeRecording || restoreRequested) && !isAppInForeground

    val snapshot = extractSnapshotFromIntent(intent) ?: currentSnapshot
    currentSnapshot = snapshot
    if (::floatingControlsController.isInitialized) {
      floatingControlsController.updateSettings(snapshot)
    }

    if (isAppInForeground) {
      floatingControlsController.removeOverlayCompletely()
    } else if (snapshot.keepEdgeHandleWhileRecording && Settings.canDrawOverlays(applicationContext)) {
      floatingControlsController.collapseToEdgeHandleForRecording()
    } else {
      floatingControlsController.removeOverlayCompletely()
    }

    if (!ScreenRecordingEngine.hasEnoughStorageToStart(applicationContext)) {
      broadcastStateUpdate(errorMessage = "Not enough storage space to start recording (at least 300 MB required).")
      restoreControlsOrStopSelf(allowRestore = true)
      return
    }

    isFinalizing.set(false)
    isSessionActive.set(true)
    severeThermalWarningShown.set(false)
    moderateThermalWarningShown.set(false)

    val countdownSeconds = snapshot.countdownSeconds.coerceIn(0, 10)
    currentCountdownSeconds = countdownSeconds
    val sessionConfig =
      RecordingSessionConfig(
        resolutionLabel = snapshot.resolution,
        fps = snapshot.fps,
        bitrateMbps = snapshot.bitrateMbps,
        isCustomBitrate = snapshot.isCustomBitrate,
        codecLabel = snapshot.codec,
        containerLabel = snapshot.container,
        orientationLabel = snapshot.orientation,
        audioSourceLabel = snapshot.audioSource,
        micEnabled = snapshot.micEnabled,
        noiseSuppression = snapshot.noiseSuppression,
        audioChannelMode = snapshot.channelMode,
        isGamingMode = snapshot.isGamingMode,
      )

    val hasRecordAudioPerm =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val audioUsed = sessionConfig.hasAnyAudio && hasRecordAudioPerm

    sessionStartRealtimeMs = SystemClock.elapsedRealtime()

    try {
      // 1. startForeground() FIRST
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
      intent.setExtrasClassLoader(Intent::class.java.classLoader)
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

      // 3. Register MediaProjection.Callback BEFORE createVirtualDisplay
      val callback =
        object : MediaProjection.Callback() {
          override fun onStop() {
            val elapsed = SystemClock.elapsedRealtime() - sessionStartRealtimeMs
            if (elapsed < 1000L) {
              ScreenRecordingEngine.debugLog(
                "Ignoring MediaProjection.Callback.onStop during initial 1000 ms (elapsed=$elapsed ms)"
              )
              return
            }
            ScreenRecordingEngine.debugLog("MediaProjection.Callback.onStop received from system")
            finalizeAndStopSession(
              reason = StopReason.PROJECTION_STOPPED,
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
        broadcastStateUpdate(
          state = RecorderState.RECORDING,
          elapsedMs = 0L,
          countdownSeconds = countdownSeconds,
        )
        countdownJob =
          serviceScope.launch {
            var remaining = countdownSeconds
            while (remaining > 0 && isActive && !isFinalizing.get()) {
              currentCountdownSeconds = remaining
              broadcastStateUpdate(
                state = RecorderState.RECORDING,
                elapsedMs = 0L,
                countdownSeconds = remaining,
              )
              updateOngoingNotification(countdownSeconds = remaining)
              delay(1_000L)
              remaining--
            }
            if (!isFinalizing.get() && isActive) {
              currentCountdownSeconds = 0
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
      broadcastStateUpdate(errorMessage = "Could not start screen recording.")
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

      floatingControlsController.updateRecorderState(RecorderState.RECORDING)
      broadcastStateUpdate(state = RecorderState.RECORDING, elapsedMs = 0L)

      registerBatteryAndThermalMonitors()
      startStorageAndMemoryWatchdog()
      startElapsedTicker()
      updateOngoingNotification()

      // Synchronize floating controls visibility with current foreground state
      syncFloatingHandleForForegroundState(isAppInForeground)
    } catch (t: Throwable) {
      ScreenRecordingEngine.debugLog(
        "ScreenRecordingEngine start failed in RecorderService: ${t.message}",
        t,
      )
      isSessionActive.set(false)
      broadcastStateUpdate(errorMessage = "Encoder initialization failed.")
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
        currentSnapshot.keepEdgeHandleWhileRecording &&
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

    engine.pause()
    floatingControlsController.updateRecorderState(RecorderState.PAUSED)
    floatingControlsController.updateElapsedTime(activeTimeMs)
    broadcastStateUpdate(state = RecorderState.PAUSED, elapsedMs = activeTimeMs)
    updateOngoingNotification(isPaused = true)
  }

  private fun handleResume() {
    val engine = recordingEngine ?: return
    if (!isSessionActive.get() || !isPausedState || isFinalizing.get()) return

    isPausedState = false
    lastResumeRealtimeMs = SystemClock.elapsedRealtime()

    engine.resume()
    floatingControlsController.updateRecorderState(RecorderState.RECORDING)
    broadcastStateUpdate(state = RecorderState.RECORDING, elapsedMs = activeTimeMs)
    startElapsedTicker()
    updateOngoingNotification()
  }

  /**
   * Single idempotent finalize routine shared by ALL recording termination paths.
   */
  private fun finalizeAndStopSession(
    reason: StopReason,
    fromProjectionCallback: Boolean,
    runSynchronously: Boolean,
  ) {
    if (!reason.isUserInitiated) {
      val elapsed = SystemClock.elapsedRealtime() - sessionStartRealtimeMs
      if (elapsed < 1000L) {
        ScreenRecordingEngine.debugLog(
          "Ignoring non-user stop callback during initial 1000 ms: reason=${reason.formattedReason()} (elapsed=$elapsed ms)"
        )
        return
      }
    }

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
      var finalizedUri: Uri? = null
      try {
        finalizedUri = engine?.stopAndFinalize()
        MemoryDiagnostics.onRecordingSessionEnded(applicationContext)
        if (finalizedUri != null) {
          ScreenRecordingEngine.debugLog(
            "Finalized Uri ready for MediaStore: uri=$finalizedUri, reason=${reason.formattedReason()}"
          )
        }
      } catch (t: Throwable) {
        ScreenRecordingEngine.debugLog(
          "Exception during RecorderService finalize: ${t.message}",
          t,
        )
      } finally {
        val durationMs = currentElapsedMs()
        StopLogManager.recordStop(
          context = applicationContext,
          reason = reason,
          elapsedDurationMs = durationMs,
          snapshot = currentSnapshot,
        )

        val shouldShowSnackbar = !reason.isUserInitiated || BuildConfig.DEBUG
        val stopSnackbarMessage = if (shouldShowSnackbar) reason.toSnackbarMessage() else null
        if (stopSnackbarMessage != null) {
          RecordingStateHolder.showSnackbarMessage(stopSnackbarMessage)
          RecordingStateHolder.setPendingSnackbarMessage(applicationContext, stopSnackbarMessage)
        }

        floatingControlsController.updateRecorderState(RecorderState.IDLE)
        teardownProjectionAndForeground(stopProjection = !fromProjectionCallback)
        if (!reason.isUserInitiated) {
          postNonUserStopNotification(reason, savedSuccessfully = finalizedUri != null)
        }
        isFinalizing.set(false)
        broadcastStateUpdate(
          state = RecorderState.IDLE,
          elapsedMs = 0L,
          errorMessage = stopSnackbarMessage,
          savedUri = finalizedUri?.toString(),
        )
        if (runSynchronously) {
          hadControlsOpenBeforeRecording = false
          floatingControlsController.removeOverlayCompletely()
          stopSelf()
        } else {
          mainHandler.post {
            restoreControlsOrStopSelf(allowRestore = reason.isUserInitiated)
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
    if (shouldRestore) {
      val shown = floatingControlsController.restoreIdlePillAfterRecording()
      if (!shown) {
        stopSelf()
      } else {
        broadcastStateUpdate(controlsVisible = true)
      }
    } else {
      floatingControlsController.removeOverlayCompletely()
      broadcastStateUpdate(controlsVisible = false)
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
          val stopThreshold = ScreenRecordingEngine.getMinimumStorageStopBytes(applicationContext)
          if (freeBytes < stopThreshold) {
            ScreenRecordingEngine.debugLog(
              "Auto-stopping recording: free storage (${freeBytes / (1024 * 1024)} MB) below threshold (${stopThreshold / (1024 * 1024)} MB)"
            )
            finalizeAndStopSession(
              reason = StopReason.LOW_STORAGE,
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
        while (isActive && !isPausedState && !isFinalizing.get()) {
          val elapsed = currentElapsedMs()
          floatingControlsController.updateElapsedTime(elapsed)
          broadcastTimerTick(elapsed)
          delay(200L)
        }
      }
  }

  private fun currentElapsedMs(): Long {
    return if (isPausedState) {
      activeTimeMs
    } else {
      activeTimeMs + (SystemClock.elapsedRealtime() - lastResumeRealtimeMs).coerceAtLeast(0L)
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
    val elapsed = currentElapsedMs()
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

  private fun postThermalModerateWarningNotification() {
    val notification =
      NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
        .setSmallIcon(R.drawable.ic_qs_videocam)
        .setContentTitle("Your device is warming up")
        .setContentText(
          "Your device is warming up. Lower the resolution or frame rate to keep games smooth."
        )
        .setStyle(
          NotificationCompat.BigTextStyle().bigText(
            "Your device is warming up. Lower the resolution or frame rate to keep games smooth."
          )
        )
        .setOngoing(false)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID_THERMAL_MODERATE, notification)
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
        createStopIntent(this, "notification"),
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
      isAppInForeground = false
      mainHandler.post {
        syncFloatingHandleForForegroundState(inForeground = false)
      }
      super.onTaskRemoved(rootIntent)
      return
    }

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
        reason = StopReason.SERVICE_KILLED,
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

  private fun broadcastStateUpdate(
    state: RecorderState = getCurrentState(),
    elapsedMs: Long = currentElapsedMs(),
    countdownSeconds: Int = currentCountdownSeconds,
    errorMessage: String? = null,
    savedUri: String? = null,
    controlsVisible: Boolean = ::floatingControlsController.isInitialized && floatingControlsController.isAttached,
  ) {
    val deadClients = mutableListOf<Messenger>()
    for (client in registeredClients) {
      try {
        val msg = Message.obtain(null, RecorderIpcProtocol.MSG_STATE_UPDATE)
        val bundle = Bundle().apply {
          putString(RecorderIpcProtocol.KEY_STATE_NAME, state.name)
          putLong(RecorderIpcProtocol.KEY_ELAPSED_MS, elapsedMs)
          putInt(RecorderIpcProtocol.KEY_COUNTDOWN_SECONDS, countdownSeconds)
          putString(RecorderIpcProtocol.KEY_ERROR_MESSAGE, errorMessage)
          putString(RecorderIpcProtocol.KEY_SAVED_URI, savedUri)
          putBoolean(RecorderIpcProtocol.KEY_CONTROLS_VISIBLE, controlsVisible)
        }
        msg.data = bundle
        client.send(msg)
      } catch (_: RemoteException) {
        deadClients.add(client)
      }
    }
    registeredClients.removeAll(deadClients)
  }

  private fun sendStateUpdateToClient(client: Messenger) {
    try {
      val state = getCurrentState()
      val msg = Message.obtain(null, RecorderIpcProtocol.MSG_STATE_UPDATE)
      val bundle = Bundle().apply {
        putString(RecorderIpcProtocol.KEY_STATE_NAME, state.name)
        putLong(RecorderIpcProtocol.KEY_ELAPSED_MS, currentElapsedMs())
        putInt(RecorderIpcProtocol.KEY_COUNTDOWN_SECONDS, currentCountdownSeconds)
        putBoolean(
          RecorderIpcProtocol.KEY_CONTROLS_VISIBLE,
          ::floatingControlsController.isInitialized && floatingControlsController.isAttached,
        )
      }
      msg.data = bundle
      client.send(msg)
    } catch (_: RemoteException) {
      registeredClients.remove(client)
    }
  }

  private fun broadcastTimerTick(elapsedMs: Long) {
    val deadClients = mutableListOf<Messenger>()
    for (client in registeredClients) {
      try {
        val msg = Message.obtain(null, RecorderIpcProtocol.MSG_TIMER_TICK)
        msg.data = Bundle().apply {
          putLong(RecorderIpcProtocol.KEY_ELAPSED_MS, elapsedMs)
        }
        client.send(msg)
      } catch (_: RemoteException) {
        deadClients.add(client)
      }
    }
    registeredClients.removeAll(deadClients)
  }

  private fun broadcastPositionChanged(px: Float, py: Float, lx: Float, ly: Float) {
    val deadClients = mutableListOf<Messenger>()
    for (client in registeredClients) {
      try {
        val msg = Message.obtain(null, RecorderIpcProtocol.MSG_CONTROLS_POSITION_CHANGED)
        msg.data = Bundle().apply {
          putFloat(RecorderIpcProtocol.KEY_PORTRAIT_X_FRAC, px)
          putFloat(RecorderIpcProtocol.KEY_PORTRAIT_Y_FRAC, py)
          putFloat(RecorderIpcProtocol.KEY_LANDSCAPE_X_FRAC, lx)
          putFloat(RecorderIpcProtocol.KEY_LANDSCAPE_Y_FRAC, ly)
        }
        client.send(msg)
      } catch (_: RemoteException) {
        deadClients.add(client)
      }
    }
    registeredClients.removeAll(deadClients)
  }

  private fun sendMemoryStatsResponse(client: Messenger) {
    try {
      val stats = MemoryDiagnostics.getCurrentRecorderStats(applicationContext)
      val msg = Message.obtain(null, RecorderIpcProtocol.MSG_MEMORY_STATS_RESPONSE)
      msg.data = Bundle().apply {
        putInt(RecorderIpcProtocol.KEY_RECORDER_PSS_KB, stats.recorderPssKb)
        putInt(RecorderIpcProtocol.KEY_PEAK_PSS_KB, stats.peakPssKb)
        putLong(RecorderIpcProtocol.KEY_NATIVE_HEAP_SIZE_KB, stats.nativeHeapSizeKb)
        putLong(RecorderIpcProtocol.KEY_NATIVE_HEAP_ALLOC_KB, stats.nativeHeapAllocatedKb)
        putLong(RecorderIpcProtocol.KEY_JAVA_HEAP_USED_KB, stats.javaHeapUsedKb)
        putInt(RecorderIpcProtocol.KEY_ACTIVE_CODECS, stats.activeCodecs)
        putInt(RecorderIpcProtocol.KEY_ACTIVE_THREADS, stats.activeThreads)
      }
      client.send(msg)
    } catch (_: RemoteException) {
      registeredClients.remove(client)
    }
  }

  private fun getCurrentState(): RecorderState {
    return when {
      !isSessionActive.get() && recordingEngine == null && countdownJob == null -> RecorderState.IDLE
      isPausedState -> RecorderState.PAUSED
      else -> RecorderState.RECORDING
    }
  }
}
