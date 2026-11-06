package com.example.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.domain.model.AppSettings
import com.example.ui.components.FormatUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecorderService : Service() {

  companion object {
    const val ACTION_START = "com.example.recorder.action.START"
    const val ACTION_PAUSE = "com.example.recorder.action.PAUSE"
    const val ACTION_RESUME = "com.example.recorder.action.RESUME"
    const val ACTION_STOP = "com.example.recorder.action.STOP"

    private const val EXTRA_RESULT_CODE = "extra_result_code"
    private const val EXTRA_RESULT_DATA = "extra_result_data"
    private const val EXTRA_RESOLUTION = "extra_resolution"
    private const val EXTRA_FPS = "extra_fps"
    private const val EXTRA_BITRATE_MBPS = "extra_bitrate_mbps"
    private const val EXTRA_CODEC = "extra_codec"
    private const val EXTRA_CONTAINER = "extra_container"
    private const val EXTRA_ORIENTATION = "extra_orientation"
    private const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
    private const val EXTRA_MIC_ENABLED = "extra_mic_enabled"
    private const val EXTRA_NOISE_SUPPRESSION = "extra_noise_suppression"
    private const val EXTRA_AUDIO_CHANNELS = "extra_audio_channels"

    private const val CHANNEL_ID = "pixel_screen_recorder_ongoing"
    private const val NOTIFICATION_ID = 4102

    fun createStartIntent(
      context: Context,
      resultCode: Int,
      resultData: Intent,
      settings: AppSettings,
    ): Intent {
      return Intent(context, RecorderService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_RESULT_DATA, resultData)
        putExtra(EXTRA_RESOLUTION, settings.video.resolution)
        putExtra(EXTRA_FPS, settings.video.fps)
        putExtra(EXTRA_BITRATE_MBPS, settings.video.bitrateMbps)
        putExtra(EXTRA_CODEC, settings.video.codec)
        putExtra(EXTRA_CONTAINER, settings.video.container)
        putExtra(EXTRA_ORIENTATION, settings.video.orientation)
        putExtra(EXTRA_AUDIO_SOURCE, settings.audio.source)
        putExtra(EXTRA_MIC_ENABLED, settings.audio.micEnabled)
        putExtra(EXTRA_NOISE_SUPPRESSION, settings.audio.noiseSuppression)
        putExtra(EXTRA_AUDIO_CHANNELS, settings.audio.channelMode)
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
  private val isStopping = AtomicBoolean(false)

  private var mediaProjection: MediaProjection? = null
  private var projectionCallback: MediaProjection.Callback? = null
  private var recordingEngine: ScreenRecordingEngine? = null
  private var timerJob: Job? = null

  @Volatile private var activeTimeMs: Long = 0L
  @Volatile private var lastResumeRealtimeMs: Long = 0L
  @Volatile private var isPausedState: Boolean = false

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> handleStart(intent)
      ACTION_PAUSE -> handlePause()
      ACTION_RESUME -> handleResume()
      ACTION_STOP -> handleStop(fromProjectionCallback = false)
    }
    return START_NOT_STICKY
  }

  private fun handleStart(intent: Intent) {
    if (recordingEngine != null) return
    isStopping.set(false)

    val sessionConfig =
      RecordingSessionConfig(
        resolutionLabel = intent.getStringExtra(EXTRA_RESOLUTION) ?: "1080p (FHD)",
        fps = intent.getIntExtra(EXTRA_FPS, 60),
        bitrateMbps = intent.getIntExtra(EXTRA_BITRATE_MBPS, 16),
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
      val initialNotification = buildNotification(isPaused = false, elapsedMs = 0L)
      val fgsType =
        if (audioUsed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

      startForeground(NOTIFICATION_ID, initialNotification, fgsType)

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
            handleStop(fromProjectionCallback = true)
          }
        }
      projectionCallback = callback
      projection.registerCallback(callback, mainHandler)

      // 4. Start hardware video + audio + muxer engine
      val effectiveConfig =
        if (audioUsed) {
          sessionConfig
        } else {
          sessionConfig.copy(audioSourceLabel = "Mute (No audio)", micEnabled = false)
        }

      val engine =
        ScreenRecordingEngine(
          context = applicationContext,
          mediaProjection = projection,
          config = effectiveConfig,
        )
      recordingEngine = engine
      engine.start()

      activeTimeMs = 0L
      lastResumeRealtimeMs = SystemClock.elapsedRealtime()
      isPausedState = false
      RecordingStateHolder.updateElapsedTime(0L)
      RecordingStateHolder.setRecording()
      startElapsedTicker()
      updateOngoingNotification()
    } catch (t: Throwable) {
      ScreenRecordingEngine.debugLog("Failed to start recording in RecorderService: ${t.message}", t)
      RecordingStateHolder.reportError(
        t.localizedMessage ?: "Unable to start screen recording."
      )
      teardownServiceResourcesOnFailure()
      stopSelf()
    }
  }

  private fun handlePause() {
    val engine = recordingEngine ?: return
    if (isPausedState || isStopping.get()) return
    val now = SystemClock.elapsedRealtime()
    activeTimeMs += (now - lastResumeRealtimeMs).coerceAtLeast(0L)
    isPausedState = true
    timerJob?.cancel()
    timerJob = null

    engine.pause()
    RecordingStateHolder.updateElapsedTime(activeTimeMs)
    RecordingStateHolder.setPaused()
    updateOngoingNotification()
  }

  private fun handleResume() {
    val engine = recordingEngine ?: return
    if (!isPausedState || isStopping.get()) return
    lastResumeRealtimeMs = SystemClock.elapsedRealtime()
    isPausedState = false

    engine.resume()
    RecordingStateHolder.setRecording()
    startElapsedTicker()
    updateOngoingNotification()
  }

  /**
   * Executes steps a-g on the background thread BEFORE calling stopForeground or stopSelf,
   * including when triggered from notification Stop or MediaProjection.Callback.onStop.
   */
  private fun handleStop(fromProjectionCallback: Boolean) {
    if (isStopping.getAndSet(true)) return
    timerJob?.cancel()
    timerJob = null

    val engine = recordingEngine
    recordingEngine = null

    serviceScope.launch {
      try {
        // Steps a-f run sequentially inside stopAndFinalize on this background thread
        val finalizedUri = engine?.stopAndFinalize()

        // Step g: Recording finalized in MediaStore (IS_PENDING = 0); ContentObserver refreshes library/Home
        if (finalizedUri != null) {
          ScreenRecordingEngine.debugLog(
            "Step g (Finalized Uri ready for MediaStore ContentObserver) succeeded: uri=$finalizedUri"
          )
        } else {
          ScreenRecordingEngine.debugLog(
            "Step g: No finalized recording published (recording was empty or failed)"
          )
        }
      } catch (t: Throwable) {
        ScreenRecordingEngine.debugLog("Step g exception during RecorderService stop: ${t.message}", t)
      } finally {
        RecordingStateHolder.setIdle()
        // Keep foreground service alive until after step g has finished
        teardownProjectionAndForeground(stopProjection = !fromProjectionCallback)
        stopSelf()
      }
    }
  }

  private fun startElapsedTicker() {
    timerJob?.cancel()
    timerJob =
      serviceScope.launch {
        while (isActive && !isPausedState && !isStopping.get()) {
          val currentElapsed =
            activeTimeMs +
              (SystemClock.elapsedRealtime() - lastResumeRealtimeMs).coerceAtLeast(0L)
          RecordingStateHolder.updateElapsedTime(currentElapsed)
          delay(200L)
        }
      }
  }

  private fun ensureNotificationChannel() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel =
      NotificationChannel(
          CHANNEL_ID,
          "Screen Recording",
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply {
          description = "Ongoing screen recording status and controls"
          setSound(null, null)
          enableVibration(false)
          setShowBadge(false)
        }
    nm.createNotificationChannel(channel)
  }

  private fun updateOngoingNotification() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val currentElapsed =
      if (isPausedState) {
        activeTimeMs
      } else {
        activeTimeMs + (SystemClock.elapsedRealtime() - lastResumeRealtimeMs).coerceAtLeast(0L)
      }
    nm.notify(NOTIFICATION_ID, buildNotification(isPausedState, currentElapsed))
  }

  private fun buildNotification(isPaused: Boolean, elapsedMs: Long): Notification {
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

    val title =
      if (isPaused) {
        "Recording paused (${FormatUtils.formatElapsedTimer(elapsedMs)})"
      } else {
        "Recording screen"
      }
    val subText = if (isPaused) "Tap Resume to continue" else "Tap Stop to save recording"

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_camera)
      .setContentTitle(title)
      .setContentText(subText)
      .setContentIntent(contentPendingIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setShowWhen(!isPaused)
      .setUsesChronometer(!isPaused)
      .setWhen(System.currentTimeMillis() - elapsedMs)
      .addAction(
        0,
        if (isPaused) "Resume" else "Pause",
        pauseResumePendingIntent,
      )
      .addAction(
        0,
        "Stop",
        stopPendingIntent,
      )
      .build()
  }

  private fun teardownServiceResourcesOnFailure() {
    timerJob?.cancel()
    timerJob = null
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

  override fun onDestroy() {
    try {
      if (!isStopping.get()) {
        teardownServiceResourcesOnFailure()
      }
    } finally {
      serviceScope.cancel()
      super.onDestroy()
    }
  }
}
