package com.example.recorder

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import android.os.SystemClock
import com.example.BuildConfig
import com.example.recorder.ipc.RecorderSettingsSnapshot
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class StopReasonType(val rawValue: String, val displayName: String) {
  USER("user", "User stop"),
  NOTIFICATION("notification", "Notification stop"),
  TILE("tile", "Quick Settings tile"),
  HANDLE("handle", "Floating controls"),
  PROJECTION_STOPPED("projectionStopped", "Media projection stopped"),
  LOW_STORAGE("lowStorage", "Storage almost full"),
  LOW_BATTERY("lowBattery", "Battery low"),
  THERMAL("thermal", "Device too hot"),
  ENCODER_ERROR("encoderError", "Encoder error"),
  MUXER_ERROR("muxerError", "Muxer error"),
  AUDIO_ERROR("audioError", "Audio error"),
  SERVICE_KILLED("serviceKilled", "Service killed"),
  UNKNOWN("unknown", "Unknown reason");

  val isUserInitiated: Boolean
    get() = this == USER || this == NOTIFICATION || this == TILE || this == HANDLE

  val notificationTitle: String?
    get() = when (this) {
      USER, NOTIFICATION, TILE, HANDLE -> null
      LOW_STORAGE -> "Stopped: storage almost full"
      LOW_BATTERY -> "Stopped: battery low"
      THERMAL -> "Stopped: device too hot"
      PROJECTION_STOPPED -> "Stopped: screen projection stopped"
      ENCODER_ERROR -> "Stopped: video encoder error"
      MUXER_ERROR -> "Stopped: video muxer error"
      AUDIO_ERROR -> "Stopped: audio recording error"
      SERVICE_KILLED -> "Stopped: recording service was terminated"
      UNKNOWN -> "Stopped: recording was interrupted"
    }

  companion object {
    fun fromRawValue(raw: String): StopReasonType {
      return entries.firstOrNull { it.rawValue.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
  }
}

data class StopReason(
  val type: StopReasonType,
  val message: String? = null,
) {
  val isUserInitiated: Boolean get() = type.isUserInitiated
  val notificationTitle: String? get() = type.notificationTitle

  fun formattedReason(): String {
    val base = type.rawValue
    return if (!message.isNullOrBlank()) {
      "$base ($message)"
    } else {
      base
    }
  }

  fun toSnackbarMessage(): String {
    val shortLabel = when (type) {
      StopReasonType.USER -> "user stop"
      StopReasonType.NOTIFICATION -> "notification stop"
      StopReasonType.TILE -> "quick settings tile"
      StopReasonType.HANDLE -> "floating handle"
      StopReasonType.PROJECTION_STOPPED -> "projection stopped"
      StopReasonType.LOW_STORAGE -> "storage almost full"
      StopReasonType.LOW_BATTERY -> "battery low"
      StopReasonType.THERMAL -> "device too hot"
      StopReasonType.ENCODER_ERROR -> "encoder error"
      StopReasonType.MUXER_ERROR -> "muxer error"
      StopReasonType.AUDIO_ERROR -> "audio error"
      StopReasonType.SERVICE_KILLED -> "service killed"
      StopReasonType.UNKNOWN -> "unknown reason"
    }

    return if (!message.isNullOrBlank()) {
      "Stopped: $shortLabel ($message)"
    } else {
      "Stopped: $shortLabel"
    }
  }

  companion object {
    val USER = StopReason(StopReasonType.USER)
    val NOTIFICATION = StopReason(StopReasonType.NOTIFICATION)
    val TILE = StopReason(StopReasonType.TILE)
    val HANDLE = StopReason(StopReasonType.HANDLE)
    val PROJECTION_STOPPED = StopReason(StopReasonType.PROJECTION_STOPPED)
    val LOW_STORAGE = StopReason(StopReasonType.LOW_STORAGE)
    val LOW_BATTERY = StopReason(StopReasonType.LOW_BATTERY)
    val THERMAL = StopReason(StopReasonType.THERMAL)
    val SERVICE_KILLED = StopReason(StopReasonType.SERVICE_KILLED)
    val UNKNOWN = StopReason(StopReasonType.UNKNOWN)

    fun encoderError(t: Throwable? = null): StopReason =
      StopReason(StopReasonType.ENCODER_ERROR, formatThrowable(t))

    fun muxerError(t: Throwable? = null): StopReason =
      StopReason(StopReasonType.MUXER_ERROR, formatThrowable(t))

    fun audioError(t: Throwable? = null): StopReason =
      StopReason(StopReasonType.AUDIO_ERROR, formatThrowable(t))

    fun fromThrowable(t: Throwable): StopReason {
      val className = t.javaClass.simpleName
      val msg = t.message
      val formatted = if (!msg.isNullOrBlank()) "$className: $msg" else className
      return when {
        t is IOException && (msg?.contains("ENOSPC", ignoreCase = true) == true || msg?.contains("No space", ignoreCase = true) == true) ->
          StopReason(StopReasonType.LOW_STORAGE, formatted)
        t is MediaCodec.CodecException || className.contains("Codec", ignoreCase = true) ->
          StopReason(StopReasonType.ENCODER_ERROR, formatted)
        className.contains("Muxer", ignoreCase = true) ->
          StopReason(StopReasonType.MUXER_ERROR, formatted)
        className.contains("Audio", ignoreCase = true) ->
          StopReason(StopReasonType.AUDIO_ERROR, formatted)
        else ->
          StopReason(StopReasonType.ENCODER_ERROR, formatted)
      }
    }

    private fun formatThrowable(t: Throwable?): String? {
      if (t == null) return null
      val className = t.javaClass.simpleName
      val msg = t.message
      return if (!msg.isNullOrBlank()) "$className: $msg" else className
    }
  }
}

/**
 * Diagnostic record of a single recording stop event.
 */
data class StopRecord(
  val timestampMs: Long = System.currentTimeMillis(),
  val reason: StopReason,
  val elapsedDurationMs: Long,
  val resolution: String,
  val fps: Int,
  val codec: String,
  val bitrateMbps: Float,
) {
  fun formatDuration(): String {
    val totalSec = (elapsedDurationMs / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d (%ds)", min, sec, totalSec)
  }

  fun formatDateTime(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return sdf.format(Date(timestampMs))
  }

  fun serialize(): String {
    // Delimiter: \t between fields, message escaped
    val msg = reason.message?.replace("\n", " ") ?: ""
    return "${timestampMs}\t${reason.type.rawValue}\t${msg}\t${elapsedDurationMs}\t${resolution}\t${fps}\t${codec}\t${bitrateMbps}"
  }

  companion object {
    fun deserialize(line: String): StopRecord? {
      val parts = line.split("\t")
      if (parts.size < 8) return null
      val timestampMs = parts[0].toLongOrNull() ?: System.currentTimeMillis()
      val type = StopReasonType.fromRawValue(parts[1])
      val msg = parts[2].ifBlank { null }
      val elapsedMs = parts[3].toLongOrNull() ?: 0L
      val res = parts[4]
      val fps = parts[5].toIntOrNull() ?: 30
      val codec = parts[6]
      val bitrateMbps = parts[7].toFloatOrNull() ?: 0f
      return StopRecord(
        timestampMs = timestampMs,
        reason = StopReason(type, msg),
        elapsedDurationMs = elapsedMs,
        resolution = res,
        fps = fps,
        codec = codec,
        bitrateMbps = bitrateMbps,
      )
    }
  }
}

/**
 * Process-safe and thread-safe manager maintaining an in-memory ring buffer of the
 * last 10 stops, backed by private storage text file.
 */
object StopLogManager {
  private const val LOG_FILE_NAME = "recording_stop_log.txt"
  private const val MAX_RECORDS = 10

  private val memoryRingBuffer = ArrayDeque<StopRecord>(MAX_RECORDS)
  private val lock = Any()

  fun recordStop(
    context: Context,
    reason: StopReason,
    elapsedDurationMs: Long,
    snapshot: RecorderSettingsSnapshot,
  ) {
    val bitrateMbps = snapshot.bitrateMbps.toFloat()

    val record = StopRecord(
      timestampMs = System.currentTimeMillis(),
      reason = reason,
      elapsedDurationMs = elapsedDurationMs,
      resolution = snapshot.resolution,
      fps = snapshot.fps,
      codec = snapshot.codec,
      bitrateMbps = bitrateMbps,
    )

    synchronized(lock) {
      if (memoryRingBuffer.isEmpty()) {
        loadFromFileLocked(context)
      }
      if (memoryRingBuffer.size >= MAX_RECORDS) {
        memoryRingBuffer.removeFirst()
      }
      memoryRingBuffer.addLast(record)
      saveToFileLocked(context)
    }
  }

  fun getRecentStops(context: Context): List<StopRecord> {
    synchronized(lock) {
      loadFromFileLocked(context)
      return memoryRingBuffer.toList().reversed()
    }
  }

  private fun loadFromFileLocked(context: Context) {
    try {
      val file = File(context.filesDir, LOG_FILE_NAME)
      if (!file.exists()) return
      val lines = file.readLines()
      memoryRingBuffer.clear()
      for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val record = StopRecord.deserialize(trimmed)
        if (record != null) {
          if (memoryRingBuffer.size >= MAX_RECORDS) {
            memoryRingBuffer.removeFirst()
          }
          memoryRingBuffer.addLast(record)
        }
      }
    } catch (_: Throwable) {}
  }

  private fun saveToFileLocked(context: Context) {
    try {
      val file = File(context.filesDir, LOG_FILE_NAME)
      val content = buildString {
        for (item in memoryRingBuffer) {
          append(item.serialize())
          append("\n")
        }
      }
      file.writeText(content)
    } catch (_: Throwable) {}
  }
}
