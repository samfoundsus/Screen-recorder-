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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory cache and off-main-thread loader for MediaStore video thumbnails (LruCache max 40 items)
 * and on-demand MediaMetadataRetriever / MediaExtractor video specifications per Uri.
 */
object RecordingMediaCache {

  private const val MAX_THUMBNAIL_CACHE_ITEMS = 40
  private val STANDARD_FPS_RATES = listOf(24, 30, 60, 90, 120)

  private val thumbnailCache =
    object : LruCache<String, Bitmap>(MAX_THUMBNAIL_CACHE_ITEMS) {
      override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

  private val metadataCache = ConcurrentHashMap<String, VideoMetadata>()

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
    metadataCache[uriString] = metadata
    metadataListener?.invoke(uriString, metadata)
  }

  fun getCachedThumbnail(uriString: String): Bitmap? {
    if (uriString.isBlank()) return null
    return synchronized(thumbnailCache) {
      thumbnailCache.get(uriString)
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
    return metadataCache[uriString]
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
      )
    }
    metadataCache[uriString]?.let { cached ->
      if (cached.fps != null && cached.fps > 0) {
        return cached
      }
    }

    return withContext(Dispatchers.IO) {
      metadataCache[uriString]?.let { cached ->
        if (cached.fps != null && cached.fps > 0) {
          return@withContext cached
        }
      }

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

          val extractedFps =
            captureFramerate
              ?: extractFpsWithMediaExtractor(context, uri)
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

          VideoMetadata(
            width = width,
            height = height,
            resolution = resolutionLabel,
            fps = extractedFps,
            bitrateMbps = bitrateMbps,
            codec = codecLabel,
          )
        } catch (_: Throwable) {
          VideoMetadata(
            width = null,
            height = null,
            resolution = null,
            fps = null,
            bitrateMbps = null,
            codec = "AVC (H.264)",
          )
        } finally {
          try {
            retriever.release()
          } catch (_: Throwable) {}
        }

      metadataCache[uriString] = metadata
      metadataListener?.invoke(uriString, metadata)
      metadata
    }
  }

  private fun extractFpsWithMediaExtractor(context: Context, uri: Uri): Int? {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(context, uri, null)
      var videoTrackIndex = -1
      var formatFps: Int? = null

      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("video/", ignoreCase = true)) {
          videoTrackIndex = i
          formatFps =
            readNumericKeyAsInt(format, MediaFormat.KEY_CAPTURE_RATE)
              ?: readNumericKeyAsInt(format, MediaFormat.KEY_FRAME_RATE)
          break
        }
      }

      if (formatFps != null && formatFps > 0) {
        return snapToStandardFps(formatFps.toDouble())
      }

      if (videoTrackIndex >= 0) {
        extractor.selectTrack(videoTrackIndex)
        val deltasUs = mutableListOf<Long>()
        var prevPtsUs = -1L
        var samplesRead = 0
        while (samplesRead < 90) {
          val ptsUs = extractor.sampleTime
          if (ptsUs < 0L) break
          if (prevPtsUs >= 0L) {
            val delta = ptsUs - prevPtsUs
            if (delta in 4_000L..100_000L) {
              deltasUs.add(delta)
            }
          }
          prevPtsUs = ptsUs
          samplesRead++
          if (!extractor.advance()) break
        }
        if (deltasUs.isNotEmpty()) {
          deltasUs.sort()
          // Use 25th percentile delta so static-screen pauses don't skew the active capture FPS
          val idx = (deltasUs.size * 0.25).toInt().coerceIn(0, deltasUs.lastIndex)
          val representativeDeltaUs = deltasUs[idx]
          if (representativeDeltaUs > 0L) {
            val rawFps = 1_000_000.0 / representativeDeltaUs.toDouble()
            return snapToStandardFps(rawFps)
          }
        }
      }
      null
    } catch (_: Throwable) {
      null
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
    metadataCache.remove(uriString)
  }
}
