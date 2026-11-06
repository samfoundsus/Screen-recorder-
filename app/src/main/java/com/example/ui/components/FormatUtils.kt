package com.example.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object FormatUtils {

  fun formatElapsedTimer(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L

    return if (hours > 0L) {
      String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
  }

  fun formatDurationBadge(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
  }

  fun formatFileSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
      bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
      bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
      bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
      else -> "$bytes B"
    }
  }

  fun formatRelativeDate(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val targetCal = Calendar.getInstance().apply { timeInMillis = epochMs }
    val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
    val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)

    val sameYear = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR)
    val dayDiff = nowCal.get(Calendar.DAY_OF_YEAR) - targetCal.get(Calendar.DAY_OF_YEAR)

    return when {
      sameYear && dayDiff == 0 -> "Today, ${timeFmt.format(Date(epochMs))}"
      sameYear && dayDiff == 1 -> "Yesterday, ${timeFmt.format(Date(epochMs))}"
      else -> dateFmt.format(Date(epochMs))
    }
  }
}
