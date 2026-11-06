package com.example.recorder

import android.content.Context
import com.example.domain.model.RecorderState
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
    appContext.startService(RecorderService.createPauseIntent(appContext))
  }

  override fun resume() {
    if (state.value != RecorderState.PAUSED) return
    appContext.startService(RecorderService.createResumeIntent(appContext))
  }

  override fun stop(): Long {
    if (state.value == RecorderState.IDLE) return 0L
    val finalElapsed = elapsedTimeMs.value
    appContext.startService(RecorderService.createStopIntent(appContext))
    return finalElapsed
  }
}
