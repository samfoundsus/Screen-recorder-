package com.example.recorder

import android.content.Context
import android.os.Debug
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MemoryDiagnosticSnapshot(
  val currentPssKb: Int = 0,
  val peakLastRecordingPssKb: Int = 0,
  val nativeHeapSizeKb: Long = 0L,
  val nativeHeapAllocatedKb: Long = 0L,
  val javaHeapUsedKb: Long = 0L,
  val activeCodecs: Int = 0,
  val activeThreads: Int = 0,
) {
  val currentPssMbText: String
    get() = String.format(Locale.US, "%.1f MB", currentPssKb / 1024.0)

  val peakLastRecordingPssMbText: String
    get() =
      if (peakLastRecordingPssKb > 0) {
        String.format(Locale.US, "%.1f MB", peakLastRecordingPssKb / 1024.0)
      } else {
        "None recorded yet"
      }

  val summaryText: String
    get() =
      if (peakLastRecordingPssKb > 0) {
        "Current: $currentPssMbText • Peak last recording: $peakLastRecordingPssMbText"
      } else {
        "Current: $currentPssMbText • Peak last recording: —"
      }
}

/**
 * Debug-only memory diagnostics sampler and logger (excluded from release builds via source sets).
 * Logs TAG "MemCheck" every 10 seconds while recording and tracks current and peak recording PSS.
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

  fun sampleAndLogWhileRecording(context: Context) {
    val snap = captureSnapshot(context, updateSessionPeak = true)
    Log.d(
      TAG,
      "totalPss=${snap.currentPssKb}KB (${snap.currentPssMbText}), " +
        "peakRecordingPss=${snap.peakLastRecordingPssKb}KB, " +
        "nativeHeapSize=${snap.nativeHeapSizeKb}KB (allocated=${snap.nativeHeapAllocatedKb}KB), " +
        "javaHeapUsed=${snap.javaHeapUsedKb}KB, " +
        "activeCodecs=${snap.activeCodecs}, activeThreads=${snap.activeThreads}",
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
    captureSnapshot(context, updateSessionPeak = false)
  }

  fun refreshSnapshot(context: Context): MemoryDiagnosticSnapshot {
    return captureSnapshot(context, updateSessionPeak = false)
  }

  private fun captureSnapshot(
    context: Context,
    updateSessionPeak: Boolean,
  ): MemoryDiagnosticSnapshot {
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

    if (updateSessionPeak && totalPssKb > sessionPeakPssKb) {
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

    val peakToDisplay =
      if (sessionPeakPssKb > persistedPeakPssKb) sessionPeakPssKb else persistedPeakPssKb

    val next =
      MemoryDiagnosticSnapshot(
        currentPssKb = totalPssKb,
        peakLastRecordingPssKb = peakToDisplay,
        nativeHeapSizeKb = nativeHeapSizeKb,
        nativeHeapAllocatedKb = nativeHeapAllocatedKb,
        javaHeapUsedKb = javaHeapUsedKb,
        activeCodecs = activeCodecs,
        activeThreads = activeThreads,
      )
    _snapshot.value = next
    return next
  }
}
