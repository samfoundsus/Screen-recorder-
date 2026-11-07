package com.example.recorder.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.core.content.ContextCompat
import com.example.domain.model.RecorderState
import com.example.recorder.MemoryDiagnostics
import com.example.recorder.RecorderService
import com.example.recorder.RecordingStateHolder
import com.example.recorder.ScreenRecorderTileService

/**
 * UI-process client for the out-of-process [RecorderService] (`:recorder`).
 *
 * Maintains a bound Messenger connection, forwards user/UI commands to the recorder process,
 * and distributes state updates (recording/paused/idle, timer ticks, overlay positions,
 * error messages) to [RecordingStateHolder] and [ScreenRecorderTileService].
 */
object RecorderClient {

  private const val PREFS_POSITION = "pixel_floating_controls_position"
  private const val KEY_PORTRAIT_X_FRAC = "portrait_x_frac"
  private const val KEY_PORTRAIT_Y_FRAC = "portrait_y_frac"
  private const val KEY_LANDSCAPE_X_FRAC = "landscape_x_frac"
  private const val KEY_LANDSCAPE_Y_FRAC = "landscape_y_frac"

  @Volatile private var applicationContext: Context? = null
  @Volatile private var serviceMessenger: Messenger? = null
  @Volatile private var isBound = false

  fun getInstance(context: Context): RecorderClient {
    val appCtx = context.applicationContext
    applicationContext = appCtx
    ensureConnected(appCtx)
    return this
  }

  fun peekInstance(): RecorderClient = this

  private val incomingHandler =
    object : Handler(Looper.getMainLooper()) {
      override fun handleMessage(msg: Message) {
        val context = applicationContext ?: return
        when (msg.what) {
          RecorderIpcProtocol.MSG_STATE_UPDATE -> {
            val data = msg.data ?: return
            data.classLoader = RecorderSettingsSnapshot::class.java.classLoader
            val stateName = data.getString(RecorderIpcProtocol.KEY_STATE_NAME) ?: "IDLE"
            val elapsedMs = data.getLong(RecorderIpcProtocol.KEY_ELAPSED_MS, 0L)
            val countdown = data.getInt(RecorderIpcProtocol.KEY_COUNTDOWN_SECONDS, 0)
            val errorMsg = data.getString(RecorderIpcProtocol.KEY_ERROR_MESSAGE)
            val controlsVisible = data.getBoolean(RecorderIpcProtocol.KEY_CONTROLS_VISIBLE, false)

            RecordingStateHolder.updateElapsedTime(elapsedMs)
            RecordingStateHolder.setFloatingControlsVisible(controlsVisible)

            when (stateName) {
              "RECORDING" -> {
                if (countdown > 0) {
                  RecordingStateHolder.setCountingDown(countdown)
                } else {
                  RecordingStateHolder.setRecording()
                }
              }
              "PAUSED" -> RecordingStateHolder.setPaused()
              else -> {
                if (errorMsg != null) {
                  RecordingStateHolder.reportError(errorMsg)
                } else {
                  RecordingStateHolder.setIdle()
                }
              }
            }
            ScreenRecorderTileService.requestTileUpdate(context)
          }

          RecorderIpcProtocol.MSG_TIMER_TICK -> {
            val elapsedMs = msg.data.getLong(RecorderIpcProtocol.KEY_ELAPSED_MS, 0L)
            RecordingStateHolder.updateElapsedTime(elapsedMs)
            ScreenRecorderTileService.requestTileUpdate(context)
          }

          RecorderIpcProtocol.MSG_CONTROLS_POSITION_CHANGED -> {
            val data = msg.data ?: return
            val px = data.getFloat(RecorderIpcProtocol.KEY_PORTRAIT_X_FRAC, -1f)
            val py = data.getFloat(RecorderIpcProtocol.KEY_PORTRAIT_Y_FRAC, -1f)
            val lx = data.getFloat(RecorderIpcProtocol.KEY_LANDSCAPE_X_FRAC, -1f)
            val ly = data.getFloat(RecorderIpcProtocol.KEY_LANDSCAPE_Y_FRAC, -1f)
            saveSavedPositionFractions(context, px, py, lx, ly)
          }

          RecorderIpcProtocol.MSG_MEMORY_STATS_RESPONSE -> {
            val data = msg.data ?: return
            val recorderPssKb = data.getInt(RecorderIpcProtocol.KEY_RECORDER_PSS_KB, 0)
            val peakPssKb = data.getInt(RecorderIpcProtocol.KEY_PEAK_PSS_KB, 0)
            val nativeSizeKb = data.getLong(RecorderIpcProtocol.KEY_NATIVE_HEAP_SIZE_KB, 0L)
            val nativeAllocKb = data.getLong(RecorderIpcProtocol.KEY_NATIVE_HEAP_ALLOC_KB, 0L)
            val javaUsedKb = data.getLong(RecorderIpcProtocol.KEY_JAVA_HEAP_USED_KB, 0L)
            val codecs = data.getInt(RecorderIpcProtocol.KEY_ACTIVE_CODECS, 0)
            val threads = data.getInt(RecorderIpcProtocol.KEY_ACTIVE_THREADS, 0)
            MemoryDiagnostics.updateRecorderMemoryStats(
              recorderPssKb = recorderPssKb,
              peakPssKb = peakPssKb,
              nativeHeapSizeKb = nativeSizeKb,
              nativeHeapAllocatedKb = nativeAllocKb,
              javaHeapUsedKb = javaUsedKb,
              activeCodecs = codecs,
              activeThreads = threads,
            )
          }
        }
      }
    }

  private val clientMessenger = Messenger(incomingHandler)

  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        serviceMessenger = Messenger(service)
        registerWithService()
        // Notify service of current foreground state immediately
        setAppInForeground(RecordingStateHolder.isAppInForeground.value)
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        serviceMessenger = null
        isBound = false
      }
    }

  fun bind(context: Context) {
    val appCtx = context.applicationContext
    applicationContext = appCtx
    if (isBound) return
    try {
      val intent = Intent(appCtx, RecorderService::class.java)
      isBound = appCtx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    } catch (_: Throwable) {}
  }

  fun ensureConnected(context: Context) {
    if (!isBound || serviceMessenger == null) {
      bind(context)
    }
  }

  fun ensureConnected() {
    val ctx = applicationContext
    if (ctx != null) {
      ensureConnected(ctx)
    }
  }

  fun unbind(context: Context) {
    if (!isBound) return
    unregisterFromService()
    try {
      context.applicationContext.unbindService(serviceConnection)
    } catch (_: Throwable) {}
    serviceMessenger = null
    isBound = false
  }

  fun startRecording(
    context: Context,
    resultCode: Int,
    resultData: Intent,
    snapshot: RecorderSettingsSnapshot,
    restoreControlsAfterStop: Boolean = false,
  ) {
    ensureConnected(context)
    val appCtx = context.applicationContext
    try {
      val startIntent =
        RecorderService.createStartIntent(
          context = appCtx,
          resultCode = resultCode,
          resultData = resultData,
          snapshot = snapshot,
          restoreControlsAfterStop = restoreControlsAfterStop,
        )
      ContextCompat.startForegroundService(appCtx, startIntent)
    } catch (_: Throwable) {}
  }

  fun pause(context: Context) {
    val messenger = serviceMessenger
    if (messenger != null) {
      sendIpcMessage(RecorderIpcProtocol.MSG_PAUSE_RECORDING)
    } else {
      try {
        context.applicationContext.startService(RecorderService.createPauseIntent(context))
      } catch (_: Throwable) {}
    }
  }

  fun pause() {
    val ctx = applicationContext
    if (ctx != null) pause(ctx) else sendIpcMessage(RecorderIpcProtocol.MSG_PAUSE_RECORDING)
  }

  fun resume(context: Context) {
    val messenger = serviceMessenger
    if (messenger != null) {
      sendIpcMessage(RecorderIpcProtocol.MSG_RESUME_RECORDING)
    } else {
      try {
        context.applicationContext.startService(RecorderService.createResumeIntent(context))
      } catch (_: Throwable) {}
    }
  }

  fun resume() {
    val ctx = applicationContext
    if (ctx != null) resume(ctx) else sendIpcMessage(RecorderIpcProtocol.MSG_RESUME_RECORDING)
  }

  fun stop(context: Context) {
    val messenger = serviceMessenger
    if (messenger != null) {
      sendIpcMessage(RecorderIpcProtocol.MSG_STOP_RECORDING)
    } else {
      try {
        context.applicationContext.startService(RecorderService.createStopIntent(context))
      } catch (_: Throwable) {}
    }
  }

  fun stop() {
    val ctx = applicationContext
    if (ctx != null) stop(ctx) else sendIpcMessage(RecorderIpcProtocol.MSG_STOP_RECORDING)
  }

  fun closeControls() {
    val ctx = applicationContext
    if (ctx != null) hideControls(ctx) else sendIpcMessage(RecorderIpcProtocol.MSG_HIDE_CONTROLS)
  }

  fun showControls(context: Context, snapshot: RecorderSettingsSnapshot) {
    ensureConnected(context)
    val messenger = serviceMessenger
    if (messenger != null) {
      val msg = Message.obtain(null, RecorderIpcProtocol.MSG_SHOW_CONTROLS)
      msg.data = snapshot.toBundle()
      try {
        messenger.send(msg)
        return
      } catch (_: RemoteException) {}
    }
    try {
      context.applicationContext.startService(
        RecorderService.createShowControlsIntent(context, snapshot)
      )
    } catch (_: Throwable) {}
  }

  fun hideControls(context: Context) {
    val messenger = serviceMessenger
    if (messenger != null) {
      sendIpcMessage(RecorderIpcProtocol.MSG_HIDE_CONTROLS)
    } else {
      try {
        context.applicationContext.startService(RecorderService.createHideControlsIntent(context))
      } catch (_: Throwable) {}
    }
  }

  fun setAppInForeground(inForeground: Boolean) {
    val messenger = serviceMessenger ?: return
    val msg = Message.obtain(null, RecorderIpcProtocol.MSG_SET_APP_FOREGROUND)
    msg.arg1 = if (inForeground) 1 else 0
    try {
      messenger.send(msg)
    } catch (_: RemoteException) {}
  }

  fun requestMemoryStats() {
    val messenger = serviceMessenger ?: return
    val msg = Message.obtain(null, RecorderIpcProtocol.MSG_GET_MEMORY_STATS)
    msg.replyTo = clientMessenger
    try {
      messenger.send(msg)
    } catch (_: RemoteException) {}
  }

  private fun registerWithService() {
    val messenger = serviceMessenger ?: return
    val msg = Message.obtain(null, RecorderIpcProtocol.MSG_REGISTER_CLIENT)
    msg.replyTo = clientMessenger
    try {
      messenger.send(msg)
    } catch (_: RemoteException) {}
  }

  private fun unregisterFromService() {
    val messenger = serviceMessenger ?: return
    val msg = Message.obtain(null, RecorderIpcProtocol.MSG_UNREGISTER_CLIENT)
    msg.replyTo = clientMessenger
    try {
      messenger.send(msg)
    } catch (_: RemoteException) {}
  }

  private fun sendIpcMessage(what: Int) {
    val messenger = serviceMessenger ?: return
    val msg = Message.obtain(null, what)
    try {
      messenger.send(msg)
    } catch (_: RemoteException) {}
  }

  fun loadSavedPositionFractions(
    context: Context,
    isLandscape: Boolean,
  ): Pair<Float, Float> {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_POSITION, Context.MODE_PRIVATE)
    return if (isLandscape) {
      val x = prefs.getFloat(KEY_LANDSCAPE_X_FRAC, 1.0f)
      val y = prefs.getFloat(KEY_LANDSCAPE_Y_FRAC, 0.35f)
      Pair(x, y)
    } else {
      val x = prefs.getFloat(KEY_PORTRAIT_X_FRAC, 1.0f)
      val y = prefs.getFloat(KEY_PORTRAIT_Y_FRAC, 0.35f)
      Pair(x, y)
    }
  }

  private fun saveSavedPositionFractions(
    context: Context,
    px: Float,
    py: Float,
    lx: Float,
    ly: Float,
  ) {
    val editor =
      context.applicationContext
        .getSharedPreferences(PREFS_POSITION, Context.MODE_PRIVATE)
        .edit()
    if (px >= 0f) editor.putFloat(KEY_PORTRAIT_X_FRAC, px)
    if (py >= 0f) editor.putFloat(KEY_PORTRAIT_Y_FRAC, py)
    if (lx >= 0f) editor.putFloat(KEY_LANDSCAPE_X_FRAC, lx)
    if (ly >= 0f) editor.putFloat(KEY_LANDSCAPE_Y_FRAC, ly)
    editor.apply()
  }
}
