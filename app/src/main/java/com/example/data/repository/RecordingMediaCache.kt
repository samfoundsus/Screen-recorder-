/*
 * Memory & Hardening Fixes (Phase 2C & Phase 4):
 * 1. Capped the video thumbnail LruCache at 40 items (256x256) and added clearThumbnails() so
 *    in-memory Bitmaps are evicted when the app moves to the background during recording.
 * 2. Replaced the unbounded metadata ConcurrentHashMap with a bounded LruCache (max 120 entries).
 * 3. Guaranteed MediaMetadataRetriever and MediaExtractor are always closed/released in finally
 *    blocks after each metadata extraction.
 * 4. Extracted real AAC channel count (Mono vs Stereo) via MediaExtractor and persisted per-URI
 *    noise suppression status so the Recording Details dialog and Playback specifications show
 *    the real audio track ("AAC · Mono" or "AAC · Stereo") and whether noise suppression was on.
 */
package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.example.domain.model.VideoMetadata
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory cache and off-main-thread loader for MediaStore video thumbnails (LruCache max 40 items)
 * and on-demand MediaMetadataRetriever / MediaExtractor video & audio specifications per Uri.
 */
object RecordingMediaCache {

  private const val MAX_THUMBNAIL_CACHE_ITEMS = 40
  private const val MAX_METADATA_CACHE_ITEMS = 120
  private const val PREFS_AUDIO_META = "pixel_recording_audio_meta"
  private val STANDARD_FPS_RATES = listOf(24, 30, 48, 60, 90, 120)

  private val thumbnailCache =
    object : LruCache<String, Bitmap>(MAX_THUMBNAIL_CACHE_ITEMS) {
      override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

  private val metadataCache =
    object : LruCache<String, VideoMetadata>(MAX_METADATA_CACHE_ITEMS) {
      override fun sizeOf(key: String, value: VideoMetadata): Int = 1
    }

  @Volatile
  private var metadataListener: ((String, VideoMetadata) -> Unit)? = null

  fun setOnMetadataLoadedListener(listener: ((String, VideoMetadata) -> Unit)?) {
    metadataListener = listener
  }

  fun formatResolutionLabel(width: Int, height: Int): String {
    val shortSide = min(width, height)
    val normalizedTier =
      when (shortSide) {
        in 1400..1460 -> 1440
        in 1050..1090 -> 1080
        in 700..740 -> 720
        in 460..500 -> 480
        else -> shortSide
      }
    return "${normalizedTier}p (${width}×${height})"
  }

  fun putMetadata(uriString: String, metadata: VideoMetadata) {
    if (uriString.isBlank()) return
    synchronized(metadataCache) {
      metadataCache.put(uriString, metadata)
    }
    metadataListener?.invoke(uriString, metadata)
  }

  fun putMetadata(context: Context, uriString: String, metadata: VideoMetadata) {
    if (uriString.isBlank()) return
    savePersistedAudioMeta(
      context = context,
      uriString = uriString,
      hasAudio = metadata.hasAudio,
      audioChannelMode = metadata.audioChannelMode,
      noiseSuppression = metadata.noiseSuppression,
      requestedFps = metadata.requestedFps,
    )
    putMetadata(uriString, metadata)
  }

  fun getCachedThumbnail(uriString: String): Bitmap? {
    if (uriString.isBlank()) return null
    return synchronized(thumbnailCache) {
      thumbnailCache.get(uriString)
    }
  }

  fun clearThumbnails() {
    synchronized(thumbnailCache) {
      thumbnailCache.evictAll()
    }
  }

  suspend fun loadThumbnail(context: Context, uriString: String): Bitmap? {
    if (uriString.isBlank()) return null
    getCachedThumbnail(uriString)?.let { return it }

    return withContext(Dispatchers.IO) {
      getCachedThumbnail(uriString)?.let { return@withContext it }
      try {
        val uri = Uri.parse(uriString)
        val bitmap = context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        synchronized(thumbnailCache) {
          thumbnailCache.put(uriString, bitmap)
        }
        bitmap
      } catch (_: Throwable) {
        null
      }
    }
  }

  fun getCachedMetadata(uriString: String): VideoMetadata? {
    if (uriString.isBlank()) return null
    return synchronized(metadataCache) {
      metadataCache.get(uriString)
    }
  }

  suspend fun loadMetadata(context: Context, uriString: String): VideoMetadata {
    if (uriString.isBlank()) {
      return VideoMetadata(
        width = null,
        height = null,
        resolution = null,
        fps = null,
        bitrateMbps = null,
        codec = "AVC (H.264)",
        hasAudio = true,
        audioChannelMode = "Stereo",
        noiseSuppression = false,
      )
    }
    getCachedMetadata(uriString)?.let { cached ->
      if (cached.fps != null && cached.fps > 0) {
        return cached
      }
    }

    return withContext(Dispatchers.IO) {
      getCachedMetadata(uriString)?.let { cached ->
        if (cached.fps != null && cached.fps > 0) {
          return@withContext cached
        }
      }

      val persistedAudio = loadPersistedAudioMeta(context, uriString)
      val uri = Uri.parse(uriString)
      val retriever = MediaMetadataRetriever()
      val metadata =
        try {
          retriever.setDataSource(context, uri)

          val width =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
              ?.toIntOrNull()
              ?.takeIf { it > 0 }
          val height =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
              ?.toIntOrNull()
              ?.takeIf { it > 0 }
          val bitrateBps =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
              ?.toLongOrNull()
              ?.takeIf { it > 0L }
          val durationMs =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
              ?.toLongOrNull()
              ?.takeIf { it > 0L }
          val frameCount =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            } else {
              null
            }
          val captureFramerate =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
              ?.toFloatOrNull()
              ?.roundToInt()
              ?.takeIf { it > 0 }
          val mimeType =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
          val hasAudioRetriever =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null

          val extractorInfo = extractTrackInfoWithMediaExtractor(context, uri, durationMs)
          val extractedFps =
            captureFramerate
              ?: extractorInfo.fps
              ?: computeFpsFromFrameCount(frameCount, durationMs)

          val resolutionLabel =
            if (width != null && height != null) {
              formatResolutionLabel(width, height)
            } else {
              null
            }
          val bitrateMbps =
            bitrateBps?.let { ((it + 500_000L) / 1_000_000L).toInt().coerceAtLeast(1) }
          val codecLabel =
            when {
              mimeType.contains("hevc", ignoreCase = true) ||
                mimeType.contains("h265", ignoreCase = true) -> "HEVC (H.265)"
              mimeType.contains("av01", ignoreCase = true) ||
                mimeType.contains("av1", ignoreCase = true) -> "AV1"
              else -> "AVC (H.264)"
            }

          val resolvedHasAudio =
            extractorInfo.hasAudioTrack ?: persistedAudio?.hasAudio ?: hasAudioRetriever
          val resolvedChannelMode =
            when {
              extractorInfo.audioChannelCount == 1 -> "Mono"
              extractorInfo.audioChannelCount != null && extractorInfo.audioChannelCount >= 2 ->
                "Stereo"
              persistedAudio != null -> persistedAudio.audioChannelMode
              else -> "Stereo"
            }
          val resolvedNoiseSuppression = persistedAudio?.noiseSuppression ?: false

          VideoMetadata(
            width = width,
            height = height,
            resolution = resolutionLabel,
            fps = extractedFps,
            bitrateMbps = bitrateMbps,
            codec = codecLabel,
            hasAudio = resolvedHasAudio,
            audioChannelMode = resolvedChannelMode,
            noiseSuppression = resolvedNoiseSuppression,
            measuredAvgFps = extractorInfo.measuredAvgFps,
            requestedFps = persistedAudio?.requestedFps,
          )
        } catch (_: Throwable) {
          VideoMetadata(
            width = null,
            height = null,
            resolution = null,
            fps = null,
            bitrateMbps = null,
            codec = "AVC (H.264)",
            hasAudio = persistedAudio?.hasAudio ?: true,
            audioChannelMode = persistedAudio?.audioChannelMode ?: "Stereo",
            noiseSuppression = persistedAudio?.noiseSuppression ?: false,
            measuredAvgFps = null,
            requestedFps = persistedAudio?.requestedFps,
          )
        } finally {
          try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              retriever.close()
            } else {
              retriever.release()
            }
          } catch (_: Throwable) {
            try {
              retriever.release()
            } catch (_: Throwable) {}
          }
        }

      synchronized(metadataCache) {
        metadataCache.put(uriString, metadata)
      }
      metadataListener?.invoke(uriString, metadata)
      metadata
    }
  }

  private data class ExtractorTrackInfo(
    val fps: Int?,
    val hasAudioTrack: Boolean?,
    val audioChannelCount: Int?,
    val measuredAvgFps: Double? = null,
  )

  private data class PersistedAudioMeta(
    val hasAudio: Boolean,
    val audioChannelMode: String,
    val noiseSuppression: Boolean,
    val requestedFps: Int? = null,
  )

  private fun savePersistedAudioMeta(
    context: Context,
    uriString: String,
    hasAudio: Boolean,
    audioChannelMode: String,
    noiseSuppression: Boolean,
    requestedFps: Int? = null,
  ) {
    try {
      val prefs = context.applicationContext.getSharedPreferences(PREFS_AUDIO_META, Context.MODE_PRIVATE)
      val normalizedChannel = if (audioChannelMode.equals("Mono", ignoreCase = true)) "Mono" else "Stereo"
      val reqFpsStr = requestedFps?.toString() ?: ""
      prefs
        .edit()
        .putString(uriString, "$hasAudio|$normalizedChannel|$noiseSuppression|$reqFpsStr")
        .apply()
    } catch (_: Throwable) {}
  }

  private fun loadPersistedAudioMeta(context: Context, uriString: String): PersistedAudioMeta? {
    return try {
      val prefs = context.applicationContext.getSharedPreferences(PREFS_AUDIO_META, Context.MODE_PRIVATE)
      val raw = prefs.getString(uriString, null) ?: return null
      val parts = raw.split("|")
      if (parts.size < 3) return null
      val reqFps = if (parts.size >= 4) parts[3].toIntOrNull() else null
      PersistedAudioMeta(
        hasAudio = parts[0].toBooleanStrictOrNull() ?: true,
        audioChannelMode = if (parts[1].equals("Mono", ignoreCase = true)) "Mono" else "Stereo",
        noiseSuppression = parts[2].toBooleanStrictOrNull() ?: false,
        requestedFps = reqFps,
      )
    } catch (_: Throwable) {
      null
    }
  }

  private fun extractTrackInfoWithMediaExtractor(context: Context, uri: Uri, durationMs: Long?): ExtractorTrackInfo {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(context, uri, null)
      var videoTrackIndex = -1
      var formatFps: Int? = null
      var foundAudioTrack = false
      var audioChannels: Int? = null

      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("video/", ignoreCase = true) && videoTrackIndex < 0) {
          videoTrackIndex = i
          formatFps =
            readNumericKeyAsInt(format, MediaFormat.KEY_CAPTURE_RATE)
              ?: readNumericKeyAsInt(format, MediaFormat.KEY_FRAME_RATE)
        } else if (mime.startsWith("audio/", ignoreCase = true) && !foundAudioTrack) {
          foundAudioTrack = true
          audioChannels = readNumericKeyAsInt(format, MediaFormat.KEY_CHANNEL_COUNT)
        }
      }

      var resolvedFps: Int? = null
      var measuredAvgFps: Double? = null

      if (videoTrackIndex >= 0) {
        extractor.selectTrack(videoTrackIndex)
        var totalVideoSamples = 0L
        var firstPtsUs = -1L
        var lastPtsUs = -1L
        val deltasUs = mutableListOf<Long>()
        var prevPtsUs = -1L

        while (true) {
          val ptsUs = extractor.sampleTime
          if (ptsUs < 0L) break
          if (firstPtsUs < 0L) firstPtsUs = ptsUs
          lastPtsUs = ptsUs
          totalVideoSamples++

          if (deltasUs.size < 90 && prevPtsUs >= 0L) {
            val delta = ptsUs - prevPtsUs
            if (delta in 4_000L..100_000L) {
              deltasUs.add(delta)
            }
          }
          prevPtsUs = ptsUs
          if (!extractor.advance()) break
        }

        if (totalVideoSamples > 0L) {
          val durationSec = when {
            durationMs != null && durationMs > 0L -> durationMs.toDouble() / 1000.0
            firstPtsUs >= 0L && lastPtsUs > firstPtsUs -> (lastPtsUs - firstPtsUs).toDouble() / 1_000_000.0
            else -> 0.0
          }
          if (durationSec > 0.0) {
            measuredAvgFps = totalVideoSamples.toDouble() / durationSec
          }
        }

        if (formatFps != null && formatFps > 0) {
          resolvedFps = snapToStandardFps(formatFps.toDouble())
        } else if (measuredAvgFps != null && measuredAvgFps > 0.0) {
          resolvedFps = snapToStandardFps(measuredAvgFps)
        } else if (deltasUs.isNotEmpty()) {
          deltasUs.sort()
          val idx = (deltasUs.size * 0.25).toInt().coerceIn(0, deltasUs.lastIndex)
          val representativeDeltaUs = deltasUs[idx]
          if (representativeDeltaUs > 0L) {
            val rawFps = 1_000_000.0 / representativeDeltaUs.toDouble()
            resolvedFps = snapToStandardFps(rawFps)
          }
        }
      } else if (formatFps != null && formatFps > 0) {
        resolvedFps = snapToStandardFps(formatFps.toDouble())
      }

      ExtractorTrackInfo(
        fps = resolvedFps,
        hasAudioTrack = foundAudioTrack,
        audioChannelCount = audioChannels,
        measuredAvgFps = measuredAvgFps,
      )
    } catch (_: Throwable) {
      ExtractorTrackInfo(
        fps = null,
        hasAudioTrack = null,
        audioChannelCount = null,
        measuredAvgFps = null,
      )
    } finally {
      try {
        extractor.release()
      } catch (_: Throwable) {}
    }
  }

  private fun readNumericKeyAsInt(format: MediaFormat, key: String): Int? {
    if (!format.containsKey(key)) return null
    return try {
      format.getInteger(key).takeIf { it > 0 }
    } catch (_: Throwable) {
      try {
        format.getFloat(key).roundToInt().takeIf { it > 0 }
      } catch (_: Throwable) {
        null
      }
    }
  }

  private fun computeFpsFromFrameCount(frameCount: Long?, durationMs: Long?): Int? {
    if (frameCount == null || durationMs == null || frameCount <= 0L || durationMs <= 0L) {
      return null
    }
    val rawFps = (frameCount.toDouble() * 1000.0) / durationMs.toDouble()
    if (rawFps <= 0.0) return null
    return snapToStandardFps(rawFps)
  }

  private fun snapToStandardFps(rawFps: Double): Int {
    val nearest = STANDARD_FPS_RATES.minByOrNull { abs(it - rawFps) } ?: rawFps.roundToInt()
    return if (abs(nearest - rawFps) <= 8.0) {
      nearest
    } else {
      rawFps.roundToInt().coerceIn(15, 120)
    }
  }

  fun remove(uriString: String) {
    if (uriString.isBlank()) return
    synchronized(thumbnailCache) {
      thumbnailCache.remove(uriString)
    }
    synchronized(metadataCache) {
      metadataCache.remove(uriString)
    }
  }
}
