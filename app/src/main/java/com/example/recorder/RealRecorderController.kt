package com.example.recorder

import android.content.Context
import com.example.domain.model.RecorderState
import com.example.recorder.ipc.RecorderClient
import kotlinx.coroutines.flow.StateFlow

class RealRecorderController(
  context: Context,
) : RecorderController {

  private val appContext = context.applicationContext

  override val state: StateFlow<RecorderState> = RecordingStateHolder.recorderState

  override val elapsedTimeMs: StateFlow<Long> = RecordingStateHolder.elapsedTimeMs

  override fun start() {
    if (state.value != RecorderState.IDLE) return
    RecordingStateHolder.requestStart()
  }

  override fun pause() {
    if (state.value != RecorderState.RECORDING) return
    RecorderClient.getInstance(appContext).pause()
    try {
      appContext.startService(RecorderService.createPauseIntent(appContext))
    } catch (_: Throwable) {}
  }

  override fun resume() {
    if (state.value != RecorderState.PAUSED) return
    RecorderClient.getInstance(appContext).resume()
    try {
      appContext.startService(RecorderService.createResumeIntent(appContext))
    } catch (_: Throwable) {}
  }

  override fun stop(): Long {
    if (state.value == RecorderState.IDLE) return 0L
    val finalElapsed = elapsedTimeMs.value
    RecorderClient.getInstance(appContext).stop()
    try {
      appContext.startService(RecorderService.createStopIntent(appContext))
    } catch (_: Throwable) {}
    return finalElapsed
  }
}
