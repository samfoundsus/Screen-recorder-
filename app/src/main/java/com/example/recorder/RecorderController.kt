package com.example.recorder

import com.example.domain.model.RecorderState
import kotlinx.coroutines.flow.StateFlow

interface RecorderController {
  val state: StateFlow<RecorderState>
  val elapsedTimeMs: StateFlow<Long>

  fun start()

  fun pause()

  fun resume()

  fun stop(): Long
}
