/*
 * Memory & Hardening Fixes (Phase 2C & Phase 4):
 * 1. Reused a single pre-allocated direct ByteBuffer and a single ShortBuffer view (directShortBuffer)
 *    alongside reusableVideoBufferInfo and reusableAudioBufferInfo so zero per-frame or per-audio-chunk
 *    heap allocations and zero logging occur inside the audio loop while recording.
 * 2. Tracked active hardware/audio MediaCodec instances via activeCodecCount (AtomicInteger) and
 *    guaranteed decrement on release for accurate debug MemCheck diagnostics.
 * 3. Added resilient mic retry (every 3s) with zero-allocation silence fallback when another app takes
 *    the microphone, plus seamless handling of headset/Bluetooth route changes and blocked internal audio.
 * 4. Locked audio PTS to the same pause-adjusted monotonic clock ((System.nanoTime() / 1_000L) -
 *    totalPausedDurationUs) as the surface video encoder so audio and video stay in sync across pause,
 *    resume, silence fallback, and mic reopen events.
 * 5. Detected IOException / ENOSPC / codec errors during sample writes and routed them to the single
 *    idempotent finalize routine in RecorderService.
 * 6. Phase 4 Audio Extras:
 *    - Uses VOICE_COMMUNICATION for the mic when noise suppression is on, attaches NoiseSuppressor,
 *      AcousticEchoCanceler, and AutomaticGainControl (each when isAvailable() is true) to the mic's
 *      audioSessionId, enables them, and releases them in releaseMicAudioRecordSafely().
 *    - Encodes 44.1 kHz AAC-LC at 128 kbps (2-channel Stereo) or 64 kbps (1-channel Mono).
 *    - Probes AudioRecord and AAC encoder configuration at start, falling back to the alternate channel
 *      layout and showing "Recording audio in mono (device limit)" when needed.
 */
package com.example.recorder

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.example.BuildConfig
import com.example.data.capabilities.CapabilityProbe
import com.example.util.DeviceUtils
import com.example.data.repository.RecordingMediaCache
import com.example.domain.model.VideoMetadata
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

data class RecordingSessionConfig(
  val resolutionLabel: String,
  val fps: Int,
  val bitrateMbps: Int,
  val isCustomBitrate: Boolean,
  val codecLabel: String,
  val containerLabel: String,
  val orientationLabel: String,
  val audioSourceLabel: String,
  val micEnabled: Boolean,
  val noiseSuppression: Boolean,
  val audioChannelMode: String,
  val isGamingMode: Boolean = false,
) {
  val wantsInternalAudio: Boolean
    get() =
      audioSourceLabel != "Mute (No audio)" &&
        audioSourceLabel.contains("Device audio", ignoreCase = true)

  val wantsMicAudio: Boolean
    get() =
      when {
        audioSourceLabel == "Mute (No audio)" -> false
        audioSourceLabel.equals("Device audio only", ignoreCase = true) -> false
        audioSourceLabel.equals("Device audio + Mic", ignoreCase = true) -> micEnabled
        audioSourceLabel.contains("Mic", ignoreCase = true) -> micEnabled
        else -> micEnabled
      }

  val hasAnyAudio: Boolean
    get() = wantsInternalAudio || wantsMicAudio

  val targetAudioChannelCount: Int
    get() = if (audioChannelMode.equals("Mono", ignoreCase = true)) 1 else 2
}

class ScreenRecordingEngine(
  private val context: Context,
  private val mediaProjection: MediaProjection,
  private val config: RecordingSessionConfig,
  private val onEngineError: ((StopReason) -> Unit)? = null,
) {

  companion object {
    const val TAG = "ScreenRecorderEngine"
    const val MIN_START_FREE_BYTES: Long = 300L * 1024L * 1024L // 300 MB
    const val MIN_RECORDING_FREE_BYTES: Long = 150L * 1024L * 1024L // 150 MB
    const val EMULATOR_MIN_START_FREE_BYTES: Long = 50L * 1024L * 1024L // 50 MB
    const val EMULATOR_MIN_RECORDING_FREE_BYTES: Long = 20L * 1024L * 1024L // 20 MB

    fun getMinimumStorageStartBytes(context: Context): Long =
      if (DeviceUtils.isEmulator()) EMULATOR_MIN_START_FREE_BYTES else MIN_START_FREE_BYTES

    fun getMinimumStorageStopBytes(context: Context): Long =
      if (DeviceUtils.isEmulator()) EMULATOR_MIN_RECORDING_FREE_BYTES else MIN_RECORDING_FREE_BYTES

    const val INTERRUPTED_PREVIOUS_RECORDING_SNACKBAR =
      "A previous recording was interrupted and could not be saved."

    private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val AUDIO_SAMPLE_RATE = 44_100
    private const val AUDIO_BITRATE_STEREO = 128_000
    private const val AUDIO_BITRATE_MONO = 64_000
    private const val PCM_FRAMES_PER_READ = 1024
    private const val DEQUEUE_TIMEOUT_US = 10_000L // 10 ms timeout
    private const val EOS_DRAIN_TIMEOUT_MS = 3_000L
    private const val MIC_RETRY_INTERVAL_MS = 3_000L
    private const val PARAMETER_KEY_SUSPEND_VIDEO_INPUT = MediaCodec.PARAMETER_KEY_SUSPEND

    val activeCodecCount = AtomicInteger(0)

    fun debugLog(message: String, tr: Throwable? = null) {
      if (BuildConfig.DEBUG) {
        if (tr != null) {
          Log.d(TAG, message, tr)
        } else {
          Log.d(TAG, message)
        }
      }
    }

    /**
     * Checks available free space on the external Movies volume using [StorageManager] and [StatFs].
     * On an emulator, treats unreliable storage values (zero or negative) as "enough space".
     */
    fun getAvailableStorageBytes(context: Context): Long {
      val targetDir: File =
        try {
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            ?.takeIf { it.exists() || it.mkdirs() }
            ?: context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        } catch (_: Throwable) {
          context.filesDir
        }

      val rawBytes = try {
        val statFs = StatFs(targetDir.absolutePath)
        val statAvailable = statFs.availableBytes
        if (statAvailable > 0L) {
          statAvailable
        } else {
          val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
          if (sm != null) {
            val uuid = sm.getUuidForPath(targetDir)
            sm.getAllocatableBytes(uuid)
          } else {
            statAvailable
          }
        }
      } catch (_: Throwable) {
        try {
          StatFs(context.filesDir.absolutePath).availableBytes
        } catch (_: Throwable) {
          if (DeviceUtils.isEmulator()) Long.MAX_VALUE else 0L
        }
      }

      return if (DeviceUtils.isEmulator() && rawBytes <= 0L) {
        // Treat unreliable storage values (zero or negative from StatFs) as "enough space"
        Long.MAX_VALUE
      } else {
        rawBytes
      }
    }

    fun hasEnoughStorageToStart(context: Context): Boolean {
      return getAvailableStorageBytes(context) >= getMinimumStorageStartBytes(context)
    }

    /**
     * One-time startup cleanup that deletes any Movies/ScreenRecorder entries that still have
     * IS_PENDING = 1 and are older than 10 minutes, and shows a one-time snackbar:
     * "A previous recording was interrupted and could not be saved."
     */
    fun cleanupStalePendingRecordings(context: Context): Int {
      var deletedCount = 0
      try {
        val resolver = context.contentResolver
        val queryUri = MediaStore.setIncludePending(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        val tenMinutesAgoSec = (System.currentTimeMillis() / 1000L) - 600L

        val projection =
          arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.IS_PENDING,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RELATIVE_PATH,
          )
        val selection =
          "${MediaStore.Video.Media.IS_PENDING} = 1 AND ${MediaStore.Video.Media.DATE_ADDED} < ?"
        val selectionArgs = arrayOf(tenMinutesAgoSec.toString())

        resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
          val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
          while (cursor.moveToNext()) {
            val relPath =
              if (pathCol >= 0 && !cursor.isNull(pathCol)) cursor.getString(pathCol) else ""
            if (relPath.isNullOrEmpty() || relPath.contains("ScreenRecorder", ignoreCase = true)) {
              val id = cursor.getLong(idCol)
              val itemUri =
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
              try {
                val rows = resolver.delete(itemUri, null, null)
                if (rows > 0) {
                  deletedCount++
                }
              } catch (_: Throwable) {}
            }
          }
        }
        if (deletedCount > 0) {
          debugLog("Cleaned up $deletedCount stale pending recording(s) older than 10 minutes")
          RecordingStateHolder.setPendingSnackbarMessage(
            context.applicationContext,
            INTERRUPTED_PREVIOUS_RECORDING_SNACKBAR,
          )
        }
      } catch (t: Throwable) {
        debugLog("Stale pending recordings cleanup error: ${t.message}", t)
      }
      return deletedCount
    }
  }

  private val isRunning = AtomicBoolean(false)
  private val isPaused = AtomicBoolean(false)
  private val isStopping = AtomicBoolean(false)
  private val isCleanedUp = AtomicBoolean(false)
  private val errorDispatched = AtomicBoolean(false)

  @Volatile private var pauseStartTimestampNs: Long = 0L
  @Volatile private var totalPausedDurationUs: Long = 0L

  private var pendingOutputUri: Uri? = null
  private var outputDisplayName: String = ""
  private var parcelFileDescriptor: ParcelFileDescriptor? = null
  private var mediaMuxer: MediaMuxer? = null

  private val muxerLock = Any()
  @Volatile private var muxerStarted = false
  @Volatile private var videoTrackIndex = -1
  @Volatile private var audioTrackIndex = -1
  @Volatile private var activeAudioPipeline = false
  @Volatile private var engineStartRealtimeMs = 0L

  @Volatile private var videoSamplesWritten: Int = 0
  @Volatile private var videoEosReceived: Boolean = false
  @Volatile private var lastVideoPtsUs: Long = -1L

  @Volatile private var audioSamplesWritten: Int = 0
  @Volatile private var audioEosReceived: Boolean = false
  @Volatile private var totalAudioFramesFed: Long = 0L
  @Volatile private var audioStartBasePtsUs: Long = -1L
  @Volatile private var lastAudioPtsUs: Long = -1L

  private var videoEncoder: MediaCodec? = null
  private var videoInputSurface: Surface? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var videoDrainThread: Thread? = null

  private var audioChannelCount: Int = 2
  private var audioBufferSizeInBytes: Int = 8192
  private var audioEncoder: MediaCodec? = null
  @Volatile private var micAudioRecord: AudioRecord? = null
  @Volatile private var micNoiseSuppressor: NoiseSuppressor? = null
  @Volatile private var micEchoCanceler: AcousticEchoCanceler? = null
  @Volatile private var micGainControl: AutomaticGainControl? = null
  @Volatile private var actualNoiseSuppressionActive: Boolean = false
  @Volatile private var micIsMono = false
  @Volatile private var internalAudioRecord: AudioRecord? = null
  @Volatile private var internalIsMono = false
  private var audioFeederThread: Thread? = null

  private val reusableVideoBufferInfo = MediaCodec.BufferInfo()
  private val reusableAudioBufferInfo = MediaCodec.BufferInfo()

  private var actualWidth = 1072
  private var actualHeight = 1920
  private var actualFps = 60
  private var lastFrameCheckRealtimeMs: Long = 0L
  private var framesSinceLastFrameCheck: Int = 0
  private var actualBitrateBps = 12_000_000
  private var actualVideoMime = MediaFormat.MIMETYPE_VIDEO_AVC
  private var actualCodecLabel = "AVC (H.264)"

  fun start() {
    try {
      if (!hasEnoughStorageToStart(context)) {
        val minMb = getMinimumStorageStartBytes(context) / (1024 * 1024)
        throw IOException("Not enough storage space to start recording (at least $minMb MB required).")
      }

      prepareOutputMediaStoreUri()
      val pfd =
        parcelFileDescriptor
          ?: throw IllegalStateException("Failed to open MediaStore output descriptor")
      mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

      prepareVideoEncoderAndVirtualDisplay()
      activeAudioPipeline =
        if (config.hasAnyAudio) {
          prepareAudioPipeline()
        } else {
          false
        }

      engineStartRealtimeMs = SystemClock.elapsedRealtime()
      isRunning.set(true)

      startHighPriorityVideoDrainThread()
      if (activeAudioPipeline) {
        startAudioInputFeederThread()
      }

      debugLog(
        "Recording started: ${actualWidth}x${actualHeight} @ ${actualFps}fps, codec=$actualCodecLabel, bitrate=${actualBitrateBps / 1_000_000}Mbps, audio=$activeAudioPipeline (channels=$audioChannelCount, ns=$actualNoiseSuppressionActive), uri=$pendingOutputUri"
      )
    } catch (t: Throwable) {
      debugLog("Failed to start ScreenRecordingEngine: ${t.message}", t)
      cleanupAllOnFailure()
      throw t
    }
  }

  fun pause() {
    if (!isRunning.get() || isPaused.getAndSet(true)) return
    pauseStartTimestampNs = System.nanoTime()
    try {
      val params =
        Bundle().apply {
          putInt(PARAMETER_KEY_SUSPEND_VIDEO_INPUT, 1)
        }
      videoEncoder?.setParameters(params)
    } catch (t: Throwable) {
      debugLog("Failed to suspend video encoder input: ${t.message}", t)
    }
  }

  fun resume() {
    if (!isRunning.get() || !isPaused.getAndSet(false)) return
    val pausedNs = (System.nanoTime() - pauseStartTimestampNs).coerceAtLeast(0L)
    totalPausedDurationUs += pausedNs / 1_000L
    pauseStartTimestampNs = 0L
    try {
      val params =
        Bundle().apply {
          putInt(PARAMETER_KEY_SUSPEND_VIDEO_INPUT, 0)
        }
      videoEncoder?.setParameters(params)
    } catch (t: Throwable) {
      debugLog("Failed to resume video encoder input: ${t.message}", t)
    }
  }

  private fun notifyFatalEngineError(t: Throwable) {
    if (!isRunning.get() || isStopping.get()) return
    if (!errorDispatched.compareAndSet(false, true)) return
    val stopThreshold = getMinimumStorageStopBytes(context)
    val isDiskFullOrIo =
      (t is IOException &&
        ((t.message?.contains("ENOSPC", ignoreCase = true) == true) ||
          (t.message?.contains("No space", ignoreCase = true) == true))) ||
        getAvailableStorageBytes(context) < stopThreshold

    val reason =
      if (isDiskFullOrIo) {
        val msg = t.message ?: "Storage free space below ${stopThreshold / (1024 * 1024)} MB threshold"
        StopReason(StopReasonType.LOW_STORAGE, msg)
      } else {
        StopReason.fromThrowable(t)
      }
    debugLog("Fatal engine error detected (${reason.formattedReason()}): ${t.message}", t)
    onEngineError?.invoke(reason)
  }

  /**
   * Ordered, sequential stop and finalize routine executed on the background thread (steps a-f).
   * Idempotent: a second call while finalizing or after finalizing returns null immediately.
   * Returns the finalized MediaStore [Uri] if successful, or null if failed/empty.
   */
  fun stopAndFinalize(): Uri? {
    if (isStopping.getAndSet(true)) return null
    if (isPaused.get()) {
      resume()
    }
    isRunning.set(false)

    try {
      audioFeederThread?.join(1_000L)
    } catch (_: Throwable) {}
    audioFeederThread = null

    try {
      videoDrainThread?.join(1_000L)
    } catch (_: Throwable) {}
    videoDrainThread = null

    // STEP a: Stop feeding input: release VirtualDisplay, signal EOS on video and audio encoders
    try {
      virtualDisplay?.release()
      virtualDisplay = null

      videoEncoder?.signalEndOfInputStream()

      if (activeAudioPipeline) {
        queueAudioEndOfStream()
      }
      debugLog(
        "Step a (Stop feeding input & signal EOS) succeeded: virtualDisplay released, video/audio EOS signaled"
      )
    } catch (t: Throwable) {
      debugLog("Step a (Stop feeding input & signal EOS) exception: ${t.message}", t)
    }

    // STEP b: Drain both encoders until each emits BUFFER_FLAG_END_OF_STREAM (3s timeout per encoder)
    try {
      drainEncodersUntilEosSequential()
      debugLog(
        "Step b (Drain encoders until EOS) succeeded: videoSamples=$videoSamplesWritten (eos=$videoEosReceived), audioSamples=$audioSamplesWritten (eos=$audioEosReceived)"
      )
    } catch (t: Throwable) {
      debugLog("Step b (Drain encoders until EOS) exception: ${t.message}", t)
    }

    // STEP c: Stop and release the encoders and AudioRecord (including mic audio effects)
    try {
      stopAndReleaseEncodersAndAudioRecord()
      debugLog("Step c (Stop and release encoders and AudioRecord) succeeded")
    } catch (t: Throwable) {
      debugLog("Step c (Stop and release encoders and AudioRecord) exception: ${t.message}", t)
    }

    // STEP d: Call muxer.stop() inside try/catch, log exception if any, then muxer.release()
    var muxerStoppedCleanly = false
    val muxer: MediaMuxer?
    synchronized(muxerLock) {
      muxer = mediaMuxer
      mediaMuxer = null
    }
    if (muxer != null) {
      try {
        if (muxerStarted && (videoSamplesWritten > 0 || audioSamplesWritten > 0)) {
          muxer.stop()
          muxerStoppedCleanly = true
          debugLog("Step d (muxer.stop) succeeded")
        } else {
          debugLog(
            "Step d (muxer.stop) skipped: muxerStarted=$muxerStarted, videoSamples=$videoSamplesWritten, audioSamples=$audioSamplesWritten"
          )
        }
      } catch (t: Throwable) {
        debugLog("Step d (muxer.stop) exception: ${t.message}", t)
      } finally {
        try {
          muxer.release()
          debugLog("Step d (muxer.release) succeeded")
        } catch (t: Throwable) {
          debugLog("Step d (muxer.release) exception: ${t.message}", t)
        }
        muxerStarted = false
      }
    }

    // STEP e: Close the ParcelFileDescriptor
    try {
      parcelFileDescriptor?.let { pfd ->
        try {
          pfd.fileDescriptor?.sync()
        } catch (_: Throwable) {}
        pfd.close()
      }
      parcelFileDescriptor = null
      debugLog("Step e (Close ParcelFileDescriptor) succeeded")
    } catch (t: Throwable) {
      parcelFileDescriptor = null
      debugLog("Step e (Close ParcelFileDescriptor) exception: ${t.message}", t)
    }

    // STEP f: Only after closing ParcelFileDescriptor, update IS_PENDING = 0 (or delete if failed)
    val uri = pendingOutputUri
    pendingOutputUri = null
    isCleanedUp.set(true)

    if (uri == null) {
      debugLog("Step f: No pending MediaStore Uri to finalize")
      return null
    }

    if (muxerStoppedCleanly && videoSamplesWritten > 0) {
      return try {
        val uriString = uri.toString()
        val recordedAudioTrack = activeAudioPipeline && audioSamplesWritten > 0
        val finalChannelLabel = if (audioChannelCount == 1) "Mono" else "Stereo"
        val finalNoiseSuppression =
          recordedAudioTrack && config.wantsMicAudio && actualNoiseSuppressionActive

        RecordingMediaCache.putMetadata(
          context = context,
          uriString = uriString,
          metadata =
            VideoMetadata(
              width = actualWidth,
              height = actualHeight,
              resolution = RecordingMediaCache.formatResolutionLabel(actualWidth, actualHeight),
              fps = actualFps,
              bitrateMbps = ((actualBitrateBps + 500_000) / 1_000_000).coerceAtLeast(1),
              codec = actualCodecLabel,
              hasAudio = recordedAudioTrack,
              audioChannelMode = finalChannelLabel,
              noiseSuppression = finalNoiseSuppression,
              requestedFps = config.fps,
            ),
        )
        val values =
          ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              put(MediaStore.Video.Media.CAPTURE_FRAMERATE, actualFps.toFloat())
            }
          }
        val updatedRows = context.contentResolver.update(uri, values, null, null)
        context.contentResolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
        val fileSize = queryUriSize(uri)
        debugLog(
          "Step f (Clear IS_PENDING=0) succeeded: rowsUpdated=$updatedRows, finalUri=$uri, fileSize=$fileSize bytes"
        )
        uri
      } catch (t: Throwable) {
        debugLog("Step f (Clear IS_PENDING=0) exception: ${t.message}", t)
        try {
          context.contentResolver.delete(uri, null, null)
        } catch (_: Throwable) {}
        null
      }
    } else {
      try {
        context.contentResolver.delete(uri, null, null)
        debugLog(
          "Step f: Deleted pending MediaStore row ($uri) because muxerStoppedCleanly=$muxerStoppedCleanly, videoSamples=$videoSamplesWritten"
        )
      } catch (t: Throwable) {
        debugLog("Step f (Delete failed pending row) exception: ${t.message}", t)
      }
      return null
    }
  }

  private fun prepareOutputMediaStoreUri() {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
    val ext =
      if (config.containerLabel.equals("MP4", ignoreCase = true)) {
        "mp4"
      } else {
        config.containerLabel.lowercase(Locale.US)
      }
    outputDisplayName = "Screen Capture - ${formatter.format(Date())}.$ext"

    val values =
      ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, outputDisplayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder/")
        put(MediaStore.Video.Media.IS_PENDING, 1)
      }

    val resolver = context.contentResolver
    val uri =
      resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Failed to create MediaStore entry")
    pendingOutputUri = uri
    parcelFileDescriptor =
      resolver.openFileDescriptor(uri, "rw")
        ?: throw IllegalStateException("Unable to open ParcelFileDescriptor (rw) for $uri")
  }

  private fun queryUriSize(uri: Uri): Long {
    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
        } else {
          0L
        }
      } ?: 0L
    } catch (_: Throwable) {
      0L
    }
  }

  private data class FallbackVideoCandidate(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val codecLabel: String = "AVC (H.264)",
    val preferredEncoderName: String? = null,
  )

  private val fallbackCandidates = mutableListOf<FallbackVideoCandidate>()
  @Volatile private var currentFallbackIndex: Int = 0
  @Volatile private var firstVideoBufferDrained: Boolean = false
  @Volatile private var firstBufferTimeoutLogged: Boolean = false
  @Volatile private var videoDrainStartRealtimeMs: Long = 0L
  @Volatile private var currentDensityDpi: Int = 160
  @Volatile private var currentDisplayRefreshRateHz: Int = 60
  @Volatile private var activeEncoderName: String = "unknown"
  @Volatile private var appliedKeyFrameRate: Int = 60

  private fun prepareVideoEncoderAndVirtualDisplay() {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
    val densityDpi = metrics.densityDpi.coerceAtLeast(160)
    currentDensityDpi = densityDpi
    currentDisplayRefreshRateHz = CapabilityProbe.getDisplayRefreshRateHz(context)

    val isLandscape = config.orientationLabel.equals("Landscape", ignoreCase = true) ||
      (config.orientationLabel.equals("Auto", ignoreCase = true) && metrics.widthPixels > metrics.heightPixels)
    val displayShort = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(360)
    val displayLong = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(640)
    val aspect = displayLong.toFloat() / displayShort.toFloat()

    val resolvedSpec =
      runBlocking(Dispatchers.Default) {
        CapabilityProbe.resolveSupportedEncoderSpec(
          context = context.applicationContext,
          settingsRepository = null,
          requestedResolution = config.resolutionLabel,
          requestedFps = config.fps,
          orientationLabel = config.orientationLabel,
          preferredCodecLabel = config.codecLabel,
          customBitrateMbps = config.bitrateMbps.takeIf { config.isCustomBitrate },
          notifyAndPersistOnFallback = true,
        )
      }

    fun makeScaledCandidate(shortDim: Int, targetFps: Int, targetBitrate: Int, label: String): FallbackVideoCandidate {
      val shortAligned = CapabilityProbe.roundDown16(shortDim)
      val longAligned = CapabilityProbe.roundDown16((shortDim * aspect).roundToInt())
      val (w, h) = if (isLandscape) longAligned to shortAligned else shortAligned to longAligned
      return FallbackVideoCandidate(
        label = label,
        width = w,
        height = h,
        fps = targetFps,
        bitrateBps = targetBitrate,
        mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
        codecLabel = "AVC (H.264)",
      )
    }

    fallbackCandidates.clear()
    // Step 0: Resolved/requested settings
    fallbackCandidates.add(
      FallbackVideoCandidate(
        label = "${resolvedSpec.resolutionLabel} ${resolvedSpec.fps}fps",
        width = CapabilityProbe.roundDown16(resolvedSpec.width),
        height = CapabilityProbe.roundDown16(resolvedSpec.height),
        fps = resolvedSpec.fps,
        bitrateBps = resolvedSpec.bitrateBps,
        mimeType = resolvedSpec.mimeType,
        codecLabel = if (resolvedSpec.codecLabel.startsWith("HEVC")) "HEVC (H.265)" else "AVC (H.264)",
        preferredEncoderName = resolvedSpec.encoderName,
      )
    )
    // First try scaling resolution while keeping the requested/resolved FPS before lowering FPS
    if (resolvedSpec.fps > 30) {
      fallbackCandidates.add(makeScaledCandidate(1072, resolvedSpec.fps, max(resolvedSpec.bitrateBps, 6_000_000), "1080p ${resolvedSpec.fps}fps"))
      fallbackCandidates.add(makeScaledCandidate(720, resolvedSpec.fps, min(resolvedSpec.bitrateBps, 6_000_000), "720p ${resolvedSpec.fps}fps"))
      fallbackCandidates.add(makeScaledCandidate(480, resolvedSpec.fps, min(resolvedSpec.bitrateBps, 4_000_000), "480p ${resolvedSpec.fps}fps"))
    }
    // Only lower FPS if configure/start fails for all resolutions at the requested FPS
    fallbackCandidates.add(makeScaledCandidate(1072, 30, 6_000_000, "1080p 30fps"))
    fallbackCandidates.add(makeScaledCandidate(720, 30, 3_000_000, "720p 30fps"))
    fallbackCandidates.add(makeScaledCandidate(480, 30, 1_500_000, "480p 30fps"))

    var started = false
    var lastError: Throwable? = null
    for (i in fallbackCandidates.indices) {
      val candidate = fallbackCandidates[i]
      try {
        actualWidth = candidate.width
        actualHeight = candidate.height
        actualFps = candidate.fps
        actualBitrateBps = candidate.bitrateBps
        actualVideoMime = candidate.mimeType
        actualCodecLabel = candidate.codecLabel

        val (encoder, surface) = instantiateAndStartEncoder(candidate)
        videoEncoder = encoder
        videoInputSurface = surface

        virtualDisplay =
          mediaProjection.createVirtualDisplay(
            "PixelScreenRecorderDisplay",
            actualWidth,
            actualHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
          )

        currentFallbackIndex = i
        started = true
        if (i > 0) {
          val fallbackMsg = "Recording at ${candidate.label} (fallback)"
          RecordingStateHolder.showSnackbarMessage(fallbackMsg)
          RecordingStateHolder.setPendingSnackbarMessage(context, fallbackMsg)
        }

        if (BuildConfig.DEBUG) {
          val modeLabel = if (config.isGamingMode) "Gaming" else "Balanced"
          Log.d(
            "EffectiveFormat",
            "mode=$modeLabel, width=$actualWidth, height=$actualHeight, requestedFps=${config.fps}, encoder=$activeEncoderName, KEY_FRAME_RATE=$appliedKeyFrameRate",
          )
        }
        break
      } catch (err: Throwable) {
        debugLog("Fallback candidate ${candidate.label} failed (${err.message}); retrying next", err)
        lastError = err
        try {
          videoEncoder?.release()
        } catch (_: Throwable) {} finally {
          videoEncoder = null
        }
      }
    }

    if (!started) {
      throw lastError ?: IllegalStateException("Failed to configure any video encoder in fallback chain")
    }
  }

  private fun instantiateAndStartEncoder(candidate: FallbackVideoCandidate): Pair<MediaCodec, Surface> {
    fun tryCreateEncoder(useSoftwareFallback: Boolean): MediaCodec {
      val name = if (useSoftwareFallback) {
        CapabilityProbe.findAnyH264EncoderName()
      } else {
        candidate.preferredEncoderName ?: CapabilityProbe.findPreferredEncoderInfo(candidate.mimeType)?.name
      }
      val mime = if (useSoftwareFallback) MediaFormat.MIMETYPE_VIDEO_AVC else candidate.mimeType
      val codec = if (!name.isNullOrBlank()) {
        MediaCodec.createByCodecName(name)
      } else {
        MediaCodec.createEncoderByType(mime)
      }
      activeCodecCount.incrementAndGet()
      return codec
    }

    var encoder = tryCreateEncoder(useSoftwareFallback = false)
    var inputSurface: Surface? = null
    try {
      val format = buildCandidateVideoFormat(candidate)
      val configuredFps = format.getInteger(MediaFormat.KEY_FRAME_RATE)
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      inputSurface = encoder.createInputSurface()
      encoder.start()
      activeEncoderName = runCatching { encoder.name }.getOrDefault(candidate.preferredEncoderName ?: "hardware")
      appliedKeyFrameRate =
        runCatching { encoder.inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }
          .getOrNull()
          ?: configuredFps
      return encoder to inputSurface
    } catch (e: Throwable) {
      debugLog("Preferred encoder configure/start failed (${e.message}); attempting software/any H.264 encoder", e)
      try {
        encoder.release()
      } catch (_: Throwable) {} finally {
        activeCodecCount.decrementAndGet()
      }
      encoder = tryCreateEncoder(useSoftwareFallback = true)
      val swFormat = buildCandidateVideoFormat(candidate.copy(mimeType = MediaFormat.MIMETYPE_VIDEO_AVC))
      val configuredFps = swFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
      encoder.configure(swFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      val surface = encoder.createInputSurface()
      encoder.start()
      activeEncoderName = runCatching { encoder.name }.getOrDefault("software-h264")
      appliedKeyFrameRate =
        runCatching { encoder.inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }
          .getOrNull()
          ?: configuredFps
      return encoder to surface
    }
  }

  /**
   * In Balanced mode, uses only: MIME, width, height, KEY_COLOR_FORMAT = COLOR_FormatSurface,
   * KEY_BIT_RATE, KEY_FRAME_RATE = the selected fps, and KEY_I_FRAME_INTERVAL = 1. No other keys.
   * KEY_MAX_FPS_TO_ENCODER is set only in Gaming mode, and only when the display refresh rate is
   * at least 1.5x the target fps.
   */
  private fun buildCandidateVideoFormat(candidate: FallbackVideoCandidate): MediaFormat {
    return MediaFormat.createVideoFormat(candidate.mimeType, candidate.width, candidate.height).apply {
      setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
      )
      setInteger(MediaFormat.KEY_BIT_RATE, candidate.bitrateBps)
      setInteger(MediaFormat.KEY_FRAME_RATE, candidate.fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
      if (config.isGamingMode && currentDisplayRefreshRateHz >= candidate.fps * 1.5f) {
        setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, candidate.fps.toFloat())
      }
    }
  }

  private fun attachMicPlatformEffects(audioSessionId: Int) {
    var anyEffectEnabled = false

    try {
      if (NoiseSuppressor.isAvailable()) {
        val ns = NoiseSuppressor.create(audioSessionId)
        if (ns != null) {
          ns.enabled = true
          micNoiseSuppressor = ns
          anyEffectEnabled = true
        }
      }
    } catch (_: Throwable) {}

    try {
      if (AcousticEchoCanceler.isAvailable()) {
        val aec = AcousticEchoCanceler.create(audioSessionId)
        if (aec != null) {
          aec.enabled = true
          micEchoCanceler = aec
          anyEffectEnabled = true
        }
      }
    } catch (_: Throwable) {}

    try {
      if (AutomaticGainControl.isAvailable()) {
        val agc = AutomaticGainControl.create(audioSessionId)
        if (agc != null) {
          agc.enabled = true
          micGainControl = agc
          anyEffectEnabled = true
        }
      }
    } catch (_: Throwable) {}

    if (anyEffectEnabled || CapabilityProbe.isNoiseSuppressionSupported()) {
      actualNoiseSuppressionActive = true
    }
  }

  private fun releaseMicPlatformEffects() {
    try {
      micNoiseSuppressor?.release()
    } catch (_: Throwable) {} finally {
      micNoiseSuppressor = null
    }

    try {
      micEchoCanceler?.release()
    } catch (_: Throwable) {} finally {
      micEchoCanceler = null
    }

    try {
      micGainControl?.release()
    } catch (_: Throwable) {} finally {
      micGainControl = null
    }
  }

  @SuppressLint("MissingPermission")
  private fun tryOpenMicAudioRecord(startImmediately: Boolean): Boolean {
    if (!config.wantsMicAudio) return false
    releaseMicAudioRecordSafely()

    val targetChannelMask =
      if (audioChannelCount == 1) {
        AudioFormat.CHANNEL_IN_MONO
      } else {
        AudioFormat.CHANNEL_IN_STEREO
      }

    val isUnprocessed = config.audioSourceLabel.equals("Unprocessed Mic", ignoreCase = true)
    val useNoiseSuppression = config.noiseSuppression && !isUnprocessed
    val preferredSource =
      when {
        useNoiseSuppression -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        isUnprocessed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
          MediaRecorder.AudioSource.UNPROCESSED
        else -> MediaRecorder.AudioSource.MIC
      }

    fun createInitializedMicRecord(source: Int, channelMask: Int): AudioRecord? {
      return try {
        val minBuf =
          AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
          )
        if (minBuf <= 0) return null
        val bufBytes = max(audioBufferSizeInBytes, minBuf * 2)
        val rec =
          AudioRecord(
            source,
            AUDIO_SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            bufBytes,
          )
        if (rec.state == AudioRecord.STATE_INITIALIZED) {
          rec
        } else {
          rec.release()
          null
        }
      } catch (_: Throwable) {
        null
      }
    }

    return try {
      // Phones often have a mono mic, so when stereo is selected and the mic only supports mono,
      // open CHANNEL_IN_MONO and duplicate the sample into both channels in the audio loop.
      val micRec =
        createInitializedMicRecord(preferredSource, targetChannelMask)
          ?: createInitializedMicRecord(MediaRecorder.AudioSource.MIC, targetChannelMask)
          ?: createInitializedMicRecord(preferredSource, AudioFormat.CHANNEL_IN_MONO)
          ?: createInitializedMicRecord(
            MediaRecorder.AudioSource.MIC,
            AudioFormat.CHANNEL_IN_MONO,
          )

      if (micRec != null) {
        if (useNoiseSuppression) {
          attachMicPlatformEffects(micRec.audioSessionId)
        }
        if (startImmediately) {
          micRec.startRecording()
          if (micRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseMicPlatformEffects()
            micRec.release()
            return false
          }
        }
        micAudioRecord = micRec
        micIsMono = micRec.channelCount == 1
        true
      } else {
        false
      }
    } catch (_: Throwable) {
      releaseMicAudioRecordSafely()
      false
    }
  }

  private fun releaseMicAudioRecordSafely() {
    releaseMicPlatformEffects()

    try {
      micAudioRecord?.let { rec ->
        if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          rec.stop()
        }
        rec.release()
      }
    } catch (_: Throwable) {} finally {
      micAudioRecord = null
    }
  }

  @SuppressLint("MissingPermission")
  private fun tryOpenInternalAudioRecord(startImmediately: Boolean): Boolean {
    if (!config.wantsInternalAudio || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    releaseInternalAudioRecordSafely()

    val targetChannelMask =
      if (audioChannelCount == 1) {
        AudioFormat.CHANNEL_IN_MONO
      } else {
        AudioFormat.CHANNEL_IN_STEREO
      }

    fun buildInternalRecord(channelMask: Int): AudioRecord? {
      return try {
        val captureConfig =
          AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat =
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(channelMask)
            .build()

        val minBuf =
          AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
          )
        val bufBytes =
          if (minBuf > 0) max(audioBufferSizeInBytes, minBuf * 2) else audioBufferSizeInBytes

        val rec =
          AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufBytes)
            .build()

        if (rec.state == AudioRecord.STATE_INITIALIZED) {
          rec
        } else {
          rec.release()
          null
        }
      } catch (_: Throwable) {
        null
      }
    }

    return try {
      val fallbackMask =
        if (targetChannelMask == AudioFormat.CHANNEL_IN_MONO) {
          AudioFormat.CHANNEL_IN_STEREO
        } else {
          AudioFormat.CHANNEL_IN_MONO
        }
      val internalRec = buildInternalRecord(targetChannelMask) ?: buildInternalRecord(fallbackMask)
      if (internalRec != null) {
        if (startImmediately) {
          internalRec.startRecording()
          if (internalRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            internalRec.release()
            return false
          }
        }
        internalAudioRecord = internalRec
        internalIsMono = internalRec.channelCount == 1
        true
      } else {
        false
      }
    } catch (_: Throwable) {
      releaseInternalAudioRecordSafely()
      false
    }
  }

  private fun releaseInternalAudioRecordSafely() {
    try {
      internalAudioRecord?.let { rec ->
        if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          rec.stop()
        }
        rec.release()
      }
    } catch (_: Throwable) {} finally {
      internalAudioRecord = null
    }
  }

  @SuppressLint("MissingPermission")
  private fun tryConfigureAudioPipelineForChannels(channels: Int): Boolean {
    stopAndReleaseAudioOnly()
    return try {
      audioChannelCount = channels.coerceIn(1, 2)
      val channelMask =
        if (audioChannelCount == 1) {
          AudioFormat.CHANNEL_IN_MONO
        } else {
          AudioFormat.CHANNEL_IN_STEREO
        }
      val minBuf =
        AudioRecord.getMinBufferSize(
          AUDIO_SAMPLE_RATE,
          channelMask,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      val fallbackStereoBuf =
        AudioRecord.getMinBufferSize(
          AUDIO_SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_STEREO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      val effectiveMinBuf =
        when {
          minBuf > 0 -> minBuf
          fallbackStereoBuf > 0 -> fallbackStereoBuf
          else -> return false
        }
      audioBufferSizeInBytes = max(effectiveMinBuf * 2, PCM_FRAMES_PER_READ * 2 * 4)

      var micOpened = false
      if (config.wantsMicAudio) {
        micOpened = tryOpenMicAudioRecord(startImmediately = false)
      }

      var internalOpened = false
      if (config.wantsInternalAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        internalOpened = tryOpenInternalAudioRecord(startImmediately = false)
      }

      // If only internal audio was requested and stereo failed to initialize, allow fallback probe
      if (!config.wantsMicAudio && config.wantsInternalAudio && !internalOpened) {
        stopAndReleaseAudioOnly()
        return false
      }
      // If only mic audio was requested and failed to initialize for this channel layout, allow fallback probe
      if (config.wantsMicAudio && !config.wantsInternalAudio && !micOpened) {
        stopAndReleaseAudioOnly()
        return false
      }

      val targetBitrate =
        if (config.isGamingMode) {
          if (audioChannelCount == 1) 64_000 else 96_000
        } else {
          if (audioChannelCount == 1) AUDIO_BITRATE_MONO else AUDIO_BITRATE_STEREO
        }
      val aacFormat =
        MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, audioChannelCount).apply {
          setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
          )
          setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
          setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

      val encoder = MediaCodec.createEncoderByType(AUDIO_MIME)
      activeCodecCount.incrementAndGet()
      try {
        encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
      } catch (t: Throwable) {
        try {
          encoder.release()
        } catch (_: Throwable) {} finally {
          activeCodecCount.decrementAndGet()
        }
        throw t
      }
      audioEncoder = encoder
      true
    } catch (_: Throwable) {
      stopAndReleaseAudioOnly()
      false
    }
  }

  /**
   * Probes the AudioRecord and AAC encoder configuration at start.
   * If the requested channel layout fails, falls back to the other one and shows the snackbar
   * "Recording audio in mono (device limit)".
   */
  private fun prepareAudioPipeline(): Boolean {
    val requestedChannels = config.targetAudioChannelCount
    if (tryConfigureAudioPipelineForChannels(requestedChannels)) {
      return true
    }

    val fallbackChannels = if (requestedChannels == 2) 1 else 2
    debugLog(
      "Audio configuration for $requestedChannels channel(s) failed; falling back to $fallbackChannels channel(s)"
    )
    if (tryConfigureAudioPipelineForChannels(fallbackChannels)) {
      RecordingStateHolder.showSnackbarMessage("Recording audio in mono (device limit)")
      return true
    }

    debugLog("Audio pipeline initialization failed for both channel layouts, falling back to video-only")
    stopAndReleaseAudioOnly()
    return false
  }

  /**
   * Dedicated high-priority thread for draining the hardware video encoder output.
   * Never blocks on audio; uses dequeueOutputBuffer with a 10 ms timeout.
   */
  private fun startHighPriorityVideoDrainThread() {
    videoDrainStartRealtimeMs = SystemClock.elapsedRealtime()
    firstVideoBufferDrained = false
    firstBufferTimeoutLogged = false

    val thread =
      Thread(
        {
          try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
          } catch (_: Throwable) {}

          while (isRunning.get() && !isStopping.get() && !videoEosReceived) {
            val vCodec = videoEncoder ?: break
            try {
              if (!firstVideoBufferDrained && !firstBufferTimeoutLogged) {
                val elapsed = SystemClock.elapsedRealtime() - videoDrainStartRealtimeMs
                if (elapsed >= 3000L) {
                  firstBufferTimeoutLogged = true
                  debugLog("First output buffer did not arrive within 3 seconds; requesting sync frame without lowering settings")
                  try {
                    val syncParams = Bundle().apply {
                      putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    }
                    vCodec.setParameters(syncParams)
                  } catch (_: Throwable) {}
                }
              }

              if (
                !muxerStarted &&
                  videoTrackIndex >= 0 &&
                  activeAudioPipeline &&
                  (SystemClock.elapsedRealtime() - engineStartRealtimeMs) > 400L
              ) {
                synchronized(muxerLock) {
                  if (!muxerStarted && videoTrackIndex >= 0) {
                    activeAudioPipeline = false
                    audioEosReceived = true
                    maybeStartMuxerLocked()
                  }
                }
              }

              drainVideoCodecStep(vCodec)
            } catch (t: Throwable) {
              if (!isRunning.get() || isStopping.get()) break
              notifyFatalEngineError(t)
              break
            }
          }
        },
        "RecorderVideoDrain",
      ).apply {
        priority = Thread.NORM_PRIORITY
      }
    videoDrainThread = thread
    thread.start()
  }

  /**
   * Feeds audio samples to the AAC encoder with zero per-chunk heap allocations and zero logging
   * inside the loop.
   * - When stereo is selected and the mic returns mono data, duplicates each sample into both L & R.
   * - When mono is selected and internal audio returns stereo, downmixes by averaging L & R with
   *   clipping protection.
   * - Mixes mic and internal audio in the selected channel layout with clipping protection.
   * - Keeps audio and video timestamps locked to the same pause-adjusted monotonic clock.
   */
  private fun startAudioInputFeederThread() {
    val thread =
      Thread(
        {
          try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
          } catch (_: Throwable) {}

          val channels = audioChannelCount.coerceIn(1, 2)
          val targetSamplesPerChunk = PCM_FRAMES_PER_READ * channels
          val chunkDurationUs = (PCM_FRAMES_PER_READ * 1_000_000L) / AUDIO_SAMPLE_RATE // ~23,219 us
          val micPcm = ShortArray(PCM_FRAMES_PER_READ * 2)
          val internalPcm = ShortArray(PCM_FRAMES_PER_READ * 2)
          val mixedPcm = ShortArray(targetSamplesPerChunk)
          val directPcmBytes =
            ByteBuffer.allocateDirect(targetSamplesPerChunk * 2).order(ByteOrder.nativeOrder())
          val directShortBuffer = directPcmBytes.asShortBuffer()

          var lastMicRetryRealtimeMs = SystemClock.elapsedRealtime()
          var lastInternalRetryRealtimeMs = SystemClock.elapsedRealtime()

          try {
            try {
              micAudioRecord?.startRecording()
              if (micAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseMicAudioRecordSafely()
                lastMicRetryRealtimeMs = SystemClock.elapsedRealtime()
              }
            } catch (_: Throwable) {
              releaseMicAudioRecordSafely()
              lastMicRetryRealtimeMs = SystemClock.elapsedRealtime()
            }

            try {
              internalAudioRecord?.startRecording()
              if (internalAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseInternalAudioRecordSafely()
                lastInternalRetryRealtimeMs = SystemClock.elapsedRealtime()
              }
            } catch (_: Throwable) {
              releaseInternalAudioRecordSafely()
              lastInternalRetryRealtimeMs = SystemClock.elapsedRealtime()
            }

            val codec = audioEncoder ?: return@Thread

            while (isRunning.get() && !isStopping.get()) {
              if (isPaused.get()) {
                Thread.sleep(20L)
                continue
              }

              val nowRealtimeMs = SystemClock.elapsedRealtime()

              // Retry opening microphone every 3 seconds if wanted and currently unavailable/preempted
              if (
                config.wantsMicAudio &&
                  micAudioRecord == null &&
                  (nowRealtimeMs - lastMicRetryRealtimeMs) >= MIC_RETRY_INTERVAL_MS
              ) {
                lastMicRetryRealtimeMs = nowRealtimeMs
                tryOpenMicAudioRecord(startImmediately = true)
              }

              // Retry internal audio capture on route change if wanted and unavailable
              if (
                config.wantsInternalAudio &&
                  Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                  internalAudioRecord == null &&
                  (nowRealtimeMs - lastInternalRetryRealtimeMs) >= MIC_RETRY_INTERVAL_MS
              ) {
                lastInternalRetryRealtimeMs = nowRealtimeMs
                tryOpenInternalAudioRecord(startImmediately = true)
              }

              val inIndex =
                try {
                  codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                } catch (_: Throwable) {
                  -1
                }

              if (inIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inIndex)
                inputBuf?.clear()

                var micSamplesRead = 0
                val micRec = micAudioRecord
                if (micRec != null) {
                  try {
                    if (micRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                      micRec.startRecording()
                    }
                    if (micRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                      if (micIsMono && channels == 2) {
                        val monoRead = micRec.read(micPcm, 0, PCM_FRAMES_PER_READ)
                        if (monoRead > 0) {
                          for (i in monoRead - 1 downTo 0) {
                            val s = micPcm[i]
                            micPcm[i * 2] = s
                            micPcm[i * 2 + 1] = s
                          }
                          micSamplesRead = monoRead * 2
                        } else if (monoRead < 0) {
                          releaseMicAudioRecordSafely()
                          lastMicRetryRealtimeMs = nowRealtimeMs
                        }
                      } else if (!micIsMono && channels == 1) {
                        val stereoRead = micRec.read(micPcm, 0, PCM_FRAMES_PER_READ * 2)
                        if (stereoRead > 0) {
                          val frames = stereoRead / 2
                          for (i in 0 until frames) {
                            val avg =
                              ((micPcm[i * 2].toInt() + micPcm[i * 2 + 1].toInt()) / 2)
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            micPcm[i] = avg.toShort()
                          }
                          micSamplesRead = frames
                        } else if (stereoRead < 0) {
                          releaseMicAudioRecordSafely()
                          lastMicRetryRealtimeMs = nowRealtimeMs
                        }
                      } else {
                        val read = micRec.read(micPcm, 0, targetSamplesPerChunk)
                        if (read > 0) {
                          micSamplesRead = read
                        } else if (read < 0) {
                          releaseMicAudioRecordSafely()
                          lastMicRetryRealtimeMs = nowRealtimeMs
                        }
                      }
                    } else {
                      releaseMicAudioRecordSafely()
                      lastMicRetryRealtimeMs = nowRealtimeMs
                    }
                  } catch (_: Throwable) {
                    releaseMicAudioRecordSafely()
                    lastMicRetryRealtimeMs = nowRealtimeMs
                  }
                }

                var intSamplesRead = 0
                val intRec = internalAudioRecord
                if (intRec != null) {
                  try {
                    if (intRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                      if (internalIsMono && channels == 2) {
                        val monoRead = intRec.read(internalPcm, 0, PCM_FRAMES_PER_READ)
                        if (monoRead > 0) {
                          for (i in monoRead - 1 downTo 0) {
                            val s = internalPcm[i]
                            internalPcm[i * 2] = s
                            internalPcm[i * 2 + 1] = s
                          }
                          intSamplesRead = monoRead * 2
                        } else if (monoRead < 0) {
                          releaseInternalAudioRecordSafely()
                          lastInternalRetryRealtimeMs = nowRealtimeMs
                        }
                      } else if (!internalIsMono && channels == 1) {
                        val stereoRead = intRec.read(internalPcm, 0, PCM_FRAMES_PER_READ * 2)
                        if (stereoRead > 0) {
                          val frames = stereoRead / 2
                          for (i in 0 until frames) {
                            val avg =
                              ((internalPcm[i * 2].toInt() + internalPcm[i * 2 + 1].toInt()) / 2)
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            internalPcm[i] = avg.toShort()
                          }
                          intSamplesRead = frames
                        } else if (stereoRead < 0) {
                          releaseInternalAudioRecordSafely()
                          lastInternalRetryRealtimeMs = nowRealtimeMs
                        }
                      } else {
                        val read = intRec.read(internalPcm, 0, targetSamplesPerChunk)
                        if (read > 0) {
                          intSamplesRead = read
                        } else if (read < 0) {
                          releaseInternalAudioRecordSafely()
                          lastInternalRetryRealtimeMs = nowRealtimeMs
                        }
                      }
                    } else {
                      releaseInternalAudioRecordSafely()
                      lastInternalRetryRealtimeMs = nowRealtimeMs
                    }
                  } catch (_: Throwable) {
                    releaseInternalAudioRecordSafely()
                    lastInternalRetryRealtimeMs = nowRealtimeMs
                  }
                }

                val samplesToWrite =
                  when {
                    micSamplesRead > 0 && intSamplesRead > 0 -> min(micSamplesRead, intSamplesRead)
                    micSamplesRead > 0 -> micSamplesRead
                    intSamplesRead > 0 -> intSamplesRead
                    else -> {
                      // Both sources silent or temporarily preempted: pace silence to true 44.1kHz clock
                      Thread.sleep(chunkDurationUs / 1_000L)
                      targetSamplesPerChunk
                    }
                  }

                for (i in 0 until samplesToWrite) {
                  val a = if (i < micSamplesRead) micPcm[i].toInt() else 0
                  val b = if (i < intSamplesRead) internalPcm[i].toInt() else 0
                  val sum = (a + b).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                  mixedPcm[i] = sum.toShort()
                }

                // Zero-allocation transfer via pre-allocated ShortBuffer view
                directShortBuffer.clear()
                directShortBuffer.put(mixedPcm, 0, samplesToWrite)
                val byteCount = samplesToWrite * 2
                directPcmBytes.position(0)
                directPcmBytes.limit(byteCount)

                val capacity = inputBuf?.remaining() ?: 0
                val safeBytes = min(byteCount, capacity)
                directPcmBytes.limit(safeBytes)
                inputBuf?.put(directPcmBytes)

                // Keep audio timestamps locked to the exact same pause-adjusted monotonic clock as video
                val nowActivePtsUs = (System.nanoTime() / 1_000L) - totalPausedDurationUs
                if (audioStartBasePtsUs < 0L) {
                  audioStartBasePtsUs = nowActivePtsUs
                }
                val sampleCountPtsUs =
                  audioStartBasePtsUs + ((totalAudioFramesFed * 1_000_000L) / AUDIO_SAMPLE_RATE)
                // Re-anchor if mic retry or route switch introduced a gap > 60 ms
                val rawPtsUs =
                  if (kotlin.math.abs(nowActivePtsUs - sampleCountPtsUs) > 60_000L) {
                    audioStartBasePtsUs =
                      nowActivePtsUs - ((totalAudioFramesFed * 1_000_000L) / AUDIO_SAMPLE_RATE)
                    nowActivePtsUs
                  } else {
                    sampleCountPtsUs
                  }
                val monotonicPtsUs = max(lastAudioPtsUs + 100L, rawPtsUs)
                lastAudioPtsUs = monotonicPtsUs

                val framesInChunk = (safeBytes / 2) / channels
                totalAudioFramesFed += framesInChunk

                codec.queueInputBuffer(inIndex, 0, safeBytes, monotonicPtsUs, 0)
              }

              drainAudioCodecStep(codec)
            }
          } catch (_: Throwable) {
            // Audio errors never crash or stop video recording
          }
        },
        "RecorderAudioFeeder",
      )
    audioFeederThread = thread
    thread.start()
  }

  /**
   * Drains output buffers from the video encoder using a 10 ms timeout.
   * Preserves original presentationTimeUs for all non-paused frames and skips codec-config buffers.
   */
  private fun drainVideoCodecStep(codec: MediaCodec): Boolean {
    if (!muxerStarted && videoTrackIndex >= 0) {
      Thread.sleep(2L)
      return false
    }

    var drainedAny = false
    while (true) {
      val outIndex = codec.dequeueOutputBuffer(reusableVideoBufferInfo, DEQUEUE_TIMEOUT_US)
      when {
        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          drainedAny = true
          firstVideoBufferDrained = true
          synchronized(muxerLock) {
            if (!muxerStarted && videoTrackIndex < 0) {
              val outFormat = codec.outputFormat
              try {
                if (!outFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                  outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, actualFps)
                }
                if (
                  Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !outFormat.containsKey(MediaFormat.KEY_CAPTURE_RATE)
                ) {
                  outFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, actualFps)
                }
              } catch (_: Throwable) {}
              videoTrackIndex = mediaMuxer?.addTrack(outFormat) ?: -1
              maybeStartMuxerLocked()
            }
          }
          if (!muxerStarted) break
        }
        outIndex >= 0 -> {
          drainedAny = true
          firstVideoBufferDrained = true
          val encodedData = codec.getOutputBuffer(outIndex)
          val flags = reusableVideoBufferInfo.flags
          val isConfigBuffer = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
          val isEos = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

          try {
            if (
              !isConfigBuffer &&
                reusableVideoBufferInfo.size > 0 &&
                encodedData != null &&
                muxerStarted &&
                videoTrackIndex >= 0
            ) {
              val pausedOffsetUs = totalPausedDurationUs
              if (pausedOffsetUs > 0L) {
                reusableVideoBufferInfo.presentationTimeUs -= pausedOffsetUs
              }
              if (reusableVideoBufferInfo.presentationTimeUs <= lastVideoPtsUs) {
                reusableVideoBufferInfo.presentationTimeUs = lastVideoPtsUs + 100L
              }
              lastVideoPtsUs = reusableVideoBufferInfo.presentationTimeUs

              encodedData.position(reusableVideoBufferInfo.offset)
              encodedData.limit(reusableVideoBufferInfo.offset + reusableVideoBufferInfo.size)
              synchronized(muxerLock) {
                if (muxerStarted) {
                  mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, reusableVideoBufferInfo)
                  videoSamplesWritten++

                  if (BuildConfig.DEBUG) {
                    framesSinceLastFrameCheck++
                    val now = SystemClock.elapsedRealtime()
                    if (lastFrameCheckRealtimeMs == 0L) {
                      lastFrameCheckRealtimeMs = now
                    } else {
                      val elapsed = now - lastFrameCheckRealtimeMs
                      if (elapsed >= 5000L) {
                        val fpsAchieved = (framesSinceLastFrameCheck * 1000.0) / elapsed
                        val dropped =
                          try {
                            codec.metrics?.getInt("android.media.mediacodec.dropped-frames", -1) ?: -1
                          } catch (_: Throwable) {
                            -1
                          }
                        val droppedStr = if (dropped >= 0) dropped.toString() else "unreported"
                        Log.d(
                          "FrameCheck",
                          String.format(
                            Locale.US,
                            "encodedFps=%.1f (target=%d fps), droppedInputFrames=%s",
                            fpsAchieved,
                            actualFps,
                            droppedStr,
                          ),
                        )
                        lastFrameCheckRealtimeMs = now
                        framesSinceLastFrameCheck = 0
                      }
                    }
                  }
                }
              }
            }
          } finally {
            codec.releaseOutputBuffer(outIndex, false)
          }

          if (isEos) {
            videoEosReceived = true
            break
          }
        }
      }
    }
    return drainedAny
  }

  private fun drainAudioCodecStep(codec: MediaCodec): Boolean {
    if (!muxerStarted && audioTrackIndex >= 0) {
      return false
    }

    var drainedAny = false
    while (true) {
      val outIndex = codec.dequeueOutputBuffer(reusableAudioBufferInfo, DEQUEUE_TIMEOUT_US)
      when {
        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          drainedAny = true
          synchronized(muxerLock) {
            if (!muxerStarted && audioTrackIndex < 0) {
              audioTrackIndex = mediaMuxer?.addTrack(codec.outputFormat) ?: -1
              maybeStartMuxerLocked()
            }
          }
          if (!muxerStarted) break
        }
        outIndex >= 0 -> {
          drainedAny = true
          val encodedData = codec.getOutputBuffer(outIndex)
          val flags = reusableAudioBufferInfo.flags
          val isConfigBuffer = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
          val isEos = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

          try {
            if (
              !isConfigBuffer &&
                reusableAudioBufferInfo.size > 0 &&
                encodedData != null &&
                muxerStarted &&
                audioTrackIndex >= 0
            ) {
              encodedData.position(reusableAudioBufferInfo.offset)
              encodedData.limit(reusableAudioBufferInfo.offset + reusableAudioBufferInfo.size)
              synchronized(muxerLock) {
                if (muxerStarted) {
                  try {
                    mediaMuxer?.writeSampleData(
                      audioTrackIndex,
                      encodedData,
                      reusableAudioBufferInfo,
                    )
                    audioSamplesWritten++
                  } catch (writeErr: Throwable) {
                    debugLog("Audio writeSampleData failed (${writeErr.message}), continuing video recording", writeErr)
                  }
                }
              }
            }
          } finally {
            codec.releaseOutputBuffer(outIndex, false)
          }

          if (isEos) {
            audioEosReceived = true
            break
          }
        }
      }
    }
    return drainedAny
  }

  private fun queueAudioEndOfStream() {
    val codec = audioEncoder ?: return
    val deadline = SystemClock.elapsedRealtime() + 500L
    while (SystemClock.elapsedRealtime() < deadline) {
      val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
      if (inIndex >= 0) {
        val nowActivePtsUs = (System.nanoTime() / 1_000L) - totalPausedDurationUs
        val ptsUs = max(lastAudioPtsUs + 100L, nowActivePtsUs)
        lastAudioPtsUs = ptsUs
        codec.queueInputBuffer(
          inIndex,
          0,
          0,
          ptsUs,
          MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
        return
      }
      drainAudioCodecStep(codec)
    }
  }

  private fun drainEncodersUntilEosSequential() {
    val vCodec = videoEncoder
    val aCodec = audioEncoder

    val formatDeadline = SystemClock.elapsedRealtime() + 500L
    while (!muxerStarted && SystemClock.elapsedRealtime() < formatDeadline) {
      if (vCodec != null && videoTrackIndex < 0) {
        drainVideoCodecStep(vCodec)
      }
      if (activeAudioPipeline && aCodec != null && audioTrackIndex < 0) {
        drainAudioCodecStep(aCodec)
      }
      if (muxerStarted) break
      Thread.sleep(5L)
    }

    synchronized(muxerLock) {
      if (!muxerStarted && videoTrackIndex >= 0) {
        activeAudioPipeline = false
        audioEosReceived = true
        maybeStartMuxerLocked()
      }
    }

    if (vCodec != null) {
      val videoDeadline = SystemClock.elapsedRealtime() + EOS_DRAIN_TIMEOUT_MS
      while (!videoEosReceived && SystemClock.elapsedRealtime() < videoDeadline) {
        drainVideoCodecStep(vCodec)
      }
    }

    if (activeAudioPipeline && aCodec != null) {
      val audioDeadline = SystemClock.elapsedRealtime() + EOS_DRAIN_TIMEOUT_MS
      while (!audioEosReceived && SystemClock.elapsedRealtime() < audioDeadline) {
        drainAudioCodecStep(aCodec)
      }
    }
  }

  private fun maybeStartMuxerLocked() {
    if (muxerStarted) return
    val videoReady = videoTrackIndex >= 0
    val audioReady = !activeAudioPipeline || audioTrackIndex >= 0
    if (videoReady && audioReady) {
      mediaMuxer?.start()
      muxerStarted = true
      debugLog("MediaMuxer started (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
    }
  }

  private fun stopAndReleaseAudioOnly() {
    releaseMicAudioRecordSafely()
    releaseInternalAudioRecordSafely()

    val aCodec = audioEncoder
    audioEncoder = null
    if (aCodec != null) {
      try {
        aCodec.stop()
      } catch (_: Throwable) {}
      try {
        aCodec.release()
      } catch (_: Throwable) {} finally {
        activeCodecCount.decrementAndGet()
      }
    }
  }

  private fun stopAndReleaseEncodersAndAudioRecord() {
    try {
      videoInputSurface?.release()
    } catch (_: Throwable) {} finally {
      videoInputSurface = null
    }

    val vCodec = videoEncoder
    videoEncoder = null
    if (vCodec != null) {
      try {
        vCodec.stop()
      } catch (_: Throwable) {}
      try {
        vCodec.release()
      } catch (_: Throwable) {} finally {
        activeCodecCount.decrementAndGet()
      }
    }

    stopAndReleaseAudioOnly()
  }

  fun cleanupAllOnFailure() {
    if (isCleanedUp.getAndSet(true)) return
    isRunning.set(false)
    isStopping.set(true)

    try {
      videoDrainThread?.join(500L)
    } catch (_: Throwable) {} finally {
      videoDrainThread = null
    }

    try {
      audioFeederThread?.join(500L)
    } catch (_: Throwable) {} finally {
      audioFeederThread = null
    }

    try {
      virtualDisplay?.release()
    } catch (_: Throwable) {} finally {
      virtualDisplay = null
    }

    stopAndReleaseEncodersAndAudioRecord()

    val muxer: MediaMuxer?
    synchronized(muxerLock) {
      muxer = mediaMuxer
      mediaMuxer = null
    }
    if (muxer != null) {
      try {
        if (muxerStarted) muxer.stop()
      } catch (_: Throwable) {}
      try {
        muxer.release()
      } catch (_: Throwable) {}
      muxerStarted = false
    }

    try {
      parcelFileDescriptor?.close()
    } catch (_: Throwable) {} finally {
      parcelFileDescriptor = null
    }

    val uri = pendingOutputUri
    pendingOutputUri = null
    if (uri != null) {
      try {
        context.contentResolver.delete(uri, null, null)
      } catch (_: Throwable) {}
    }
  }
}
