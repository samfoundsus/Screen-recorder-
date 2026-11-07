package com.example.recorder

import android.content.Context
import android.os.Debug
import android.util.Log
import java.util.Locale
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
    get() = if (recorderPssKb > 0) String.format(Locale.US, "%.1f MB", recorderPssKb / 1024.0) else "Idle"

  val uiPssMbText: String
    get() = String.format(Locale.US, "%.1f MB", uiPssKb / 1024.0)

  val currentPssMbText: String
    get() = if (recorderPssKb > 0) recorderPssMbText else uiPssMbText

  val currentPssKb: Int
    get() = if (recorderPssKb > 0) recorderPssKb else uiPssKb

  val peakLastRecordingPssMbText: String
    get() =
      if (peakLastRecordingPssKb > 0) {
        String.format(Locale.US, "%.1f MB", peakLastRecordingPssKb / 1024.0)
      } else {
        "None recorded yet"
      }

  val summaryText: String
    get() =
      "Recorder: $recorderPssMbText • UI: $uiPssMbText • Peak: $peakLastRecordingPssMbText"
}

/**
 * Debug-only memory diagnostics sampler and logger (excluded from release builds via source sets).
 * Logs TAG "MemCheck" every 10 seconds in the :recorder process while recording and tracks
 * recorder process PSS, UI process PSS, and peak recorder PSS fetched over Messenger IPC.
 */
object MemoryDiagnostics {
  const val TAG = "MemCheck"
  const val IS_ENABLED = true
  private const val PREFS_NAME = "pixel_recorder_debug_mem_prefs"
  private const val KEY_PEAK_LAST_RECORDING_PSS_KB = "peak_last_recording_pss_kb"

  @Volatile private var sessionPeakPssKb: Int = 0
  @Volatile private var persistedPeakPssKb: Int = 0

  private val _snapshot = MutableStateFlow(MemoryDiagnosticSnapshot())
  val snapshot: StateFlow<MemoryDiagnosticSnapshot> = _snapshot.asStateFlow()

  fun initialize(context: Context) {
    try {
      val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      persistedPeakPssKb = prefs.getInt(KEY_PEAK_LAST_RECORDING_PSS_KB, 0).coerceAtLeast(0)
      refreshSnapshot(context)
    } catch (_: Throwable) {}
  }

  fun onRecordingSessionStarted() {
    sessionPeakPssKb = 0
  }

  /**
   * Called in the :recorder process every 10 seconds while recording.
   * Logs TAG "MemCheck": totalPss, native heap, Java heap, number of threads.
   */
  fun sampleAndLogWhileRecording(context: Context) {
    val memInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memInfo)
    val totalPssKb = memInfo.totalPss.coerceAtLeast(0)

    if (totalPssKb > sessionPeakPssKb) {
      sessionPeakPssKb = totalPssKb
      persistedPeakPssKb = sessionPeakPssKb
      try {
        context
          .applicationContext
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putInt(KEY_PEAK_LAST_RECORDING_PSS_KB, persistedPeakPssKb)
          .apply()
      } catch (_: Throwable) {}
    }

    val nativeHeapSizeKb = Debug.getNativeHeapSize() / 1024L
    val nativeHeapAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1024L
    val runtime = Runtime.getRuntime()
    val javaHeapUsedKb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024L).coerceAtLeast(0L)
    val activeCodecs = ScreenRecordingEngine.activeCodecCount.get().coerceAtLeast(0)
    val activeThreads = Thread.activeCount()

    val pssMbText = String.format(Locale.US, "%.1f MB", totalPssKb / 1024.0)

    Log.d(
      TAG,
      "totalPss=${totalPssKb}KB ($pssMbText), " +
        "peakRecordingPss=${sessionPeakPssKb}KB, " +
        "nativeHeapSize=${nativeHeapSizeKb}KB (allocated=${nativeHeapAllocatedKb}KB), " +
        "javaHeapUsed=${javaHeapUsedKb}KB, " +
        "activeCodecs=$activeCodecs, activeThreads=$activeThreads",
    )
  }

  fun getCurrentRecorderStats(context: Context): RecorderStatsPayload {
    if (persistedPeakPssKb == 0) {
      try {
        val prefs =
          context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        persistedPeakPssKb = prefs.getInt(KEY_PEAK_LAST_RECORDING_PSS_KB, 0).coerceAtLeast(0)
      } catch (_: Throwable) {}
    }
    val memInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memInfo)
    val totalPssKb = memInfo.totalPss.coerceAtLeast(0)

    if (totalPssKb > sessionPeakPssKb) {
      sessionPeakPssKb = totalPssKb
      persistedPeakPssKb = sessionPeakPssKb
    }

    val nativeHeapSizeKb = Debug.getNativeHeapSize() / 1024L
    val nativeHeapAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1024L
    val runtime = Runtime.getRuntime()
    val javaHeapUsedKb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024L).coerceAtLeast(0L)
    val activeCodecs = ScreenRecordingEngine.activeCodecCount.get().coerceAtLeast(0)
    val activeThreads = Thread.activeCount()

    return RecorderStatsPayload(
      recorderPssKb = totalPssKb,
      peakPssKb = if (sessionPeakPssKb > persistedPeakPssKb) sessionPeakPssKb else persistedPeakPssKb,
      nativeHeapSizeKb = nativeHeapSizeKb,
      nativeHeapAllocatedKb = nativeHeapAllocatedKb,
      javaHeapUsedKb = javaHeapUsedKb,
      activeCodecs = activeCodecs,
      activeThreads = activeThreads,
    )
  }

  fun onRecordingSessionEnded(context: Context) {
    if (sessionPeakPssKb > 0) {
      persistedPeakPssKb = sessionPeakPssKb
      try {
        context
          .applicationContext
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putInt(KEY_PEAK_LAST_RECORDING_PSS_KB, persistedPeakPssKb)
          .apply()
      } catch (_: Throwable) {}
    }
  }

  /**
   * Refreshes UI process snapshot and triggers Messenger query to :recorder.
   */
  fun refreshSnapshot(context: Context): MemoryDiagnosticSnapshot {
    if (persistedPeakPssKb == 0) {
      try {
        val prefs =
          context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        persistedPeakPssKb = prefs.getInt(KEY_PEAK_LAST_RECORDING_PSS_KB, 0).coerceAtLeast(0)
      } catch (_: Throwable) {}
    }

    val memInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memInfo)
    val uiPssKb = memInfo.totalPss.coerceAtLeast(0)

    val nativeHeapSizeKb = Debug.getNativeHeapSize() / 1024L
    val nativeHeapAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1024L
    val runtime = Runtime.getRuntime()
    val javaHeapUsedKb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024L).coerceAtLeast(0L)
    val activeThreads = Thread.activeCount()

    val current = _snapshot.value
    val next =
      current.copy(
        uiPssKb = uiPssKb,
        peakLastRecordingPssKb = if (current.peakLastRecordingPssKb > 0) current.peakLastRecordingPssKb else persistedPeakPssKb,
        nativeHeapSizeKb = nativeHeapSizeKb,
        nativeHeapAllocatedKb = nativeHeapAllocatedKb,
        javaHeapUsedKb = javaHeapUsedKb,
        activeThreads = activeThreads,
      )
    _snapshot.value = next

    // Trigger asynchronous query to recorder process via Messenger
    try {
      com.example.recorder.ipc.RecorderClient.requestMemoryStats()
    } catch (_: Throwable) {}

    return next
  }

  fun updateRecorderMemoryStats(
    recorderPssKb: Int,
    peakPssKb: Int,
    nativeHeapSizeKb: Long,
    nativeHeapAllocatedKb: Long,
    javaHeapUsedKb: Long,
    activeCodecs: Int,
    activeThreads: Int,
  ) {
    val peak = if (peakPssKb > 0) peakPssKb else persistedPeakPssKb
    _snapshot.value =
      _snapshot.value.copy(
        recorderPssKb = recorderPssKb,
        peakLastRecordingPssKb = peak,
        activeCodecs = activeCodecs,
      )
  }

  data class RecorderStatsPayload(
    val recorderPssKb: Int,
    val peakPssKb: Int,
    val nativeHeapSizeKb: Long,
    val nativeHeapAllocatedKb: Long,
    val javaHeapUsedKb: Long,
    val activeCodecs: Int,
    val activeThreads: Int,
  )
}
