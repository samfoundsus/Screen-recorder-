package com.example.recorder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MemoryDiagnosticSnapshot(
  val recorderPssKb: Int = 0,
  val uiPssKb: Int = 0,
  val peakLastRecordingPssKb: Int = 0,
  val nativeHeapSizeKb: Long = 0L,
  val nativeHeapAllocatedKb: Long = 0L,
  val javaHeapUsedKb: Long = 0L,
  val activeCodecs: Int = 0,
  val activeThreads: Int = 0,
) {
  val recorderPssMbText: String
    get() = ""

  val uiPssMbText: String
    get() = ""

  val currentPssMbText: String
    get() = ""

  val currentPssKb: Int
    get() = 0

  val peakLastRecordingPssMbText: String
    get() = ""

  val summaryText: String
    get() = ""
}

/**
 * Release no-op stub for [MemoryDiagnostics] so no diagnostic logging or UI exists in release builds.
 */
object MemoryDiagnostics {
  const val IS_ENABLED = false

  private val _snapshot = MutableStateFlow(MemoryDiagnosticSnapshot())
  val snapshot: StateFlow<MemoryDiagnosticSnapshot> = _snapshot.asStateFlow()

  fun initialize(context: Context) = Unit

  fun onRecordingSessionStarted() = Unit

  fun sampleAndLogWhileRecording(context: Context) = Unit

  fun onRecordingSessionEnded(context: Context) = Unit

  fun refreshSnapshot(context: Context): MemoryDiagnosticSnapshot = _snapshot.value

  fun updateRecorderMemoryStats(
    recorderPssKb: Int,
    peakPssKb: Int,
    nativeHeapSizeKb: Long,
    nativeHeapAllocatedKb: Long,
    javaHeapUsedKb: Long,
    activeCodecs: Int,
    activeThreads: Int,
  ) = Unit

  data class RecorderStatsPayload(
    val recorderPssKb: Int = 0,
    val peakPssKb: Int = 0,
    val nativeHeapSizeKb: Long = 0L,
    val nativeHeapAllocatedKb: Long = 0L,
    val javaHeapUsedKb: Long = 0L,
    val activeCodecs: Int = 0,
    val activeThreads: Int = 0,
  )

  fun getCurrentRecorderStats(context: Context): RecorderStatsPayload = RecorderStatsPayload()
}
