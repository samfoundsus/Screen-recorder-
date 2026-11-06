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
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.example.BuildConfig
import com.example.data.capabilities.CapabilityProbe
import com.example.data.repository.DataStoreSettingsRepository
import com.example.data.repository.RecordingMediaCache
import com.example.domain.model.VideoMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
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
) {
  val wantsInternalAudio: Boolean
    get() =
      audioSourceLabel != "Mute (No audio)" &&
        audioSourceLabel.contains("Device audio", ignoreCase = true)

  val wantsMicAudio: Boolean
    get() =
      audioSourceLabel != "Mute (No audio)" &&
        (audioSourceLabel.contains("Mic", ignoreCase = true) ||
          (micEnabled && audioSourceLabel != "Device audio only"))

  val hasAnyAudio: Boolean
    get() = wantsInternalAudio || wantsMicAudio
}

class ScreenRecordingEngine(
  private val context: Context,
  private val mediaProjection: MediaProjection,
  private val config: RecordingSessionConfig,
) {

  companion object {
    const val TAG = "ScreenRecorderEngine"
    private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val AUDIO_SAMPLE_RATE = 44_100
    private const val AUDIO_CHANNEL_COUNT = 2
    private const val AUDIO_BITRATE = 128_000
    private const val PCM_FRAMES_PER_READ = 1024
    private const val DEQUEUE_TIMEOUT_US = 10_000L // 10 ms timeout
    private const val EOS_DRAIN_TIMEOUT_MS = 3_000L
    private const val PARAMETER_KEY_SUSPEND_VIDEO_INPUT = MediaCodec.PARAMETER_KEY_SUSPEND

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
     * One-time startup cleanup that finds files in Movies/ScreenRecorder with IS_PENDING = 1
     * older than 10 minutes and deletes them.
     */
    fun cleanupStalePendingRecordings(context: Context) {
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
          var deletedCount = 0
          while (cursor.moveToNext()) {
            val relPath =
              if (pathCol >= 0 && !cursor.isNull(pathCol)) cursor.getString(pathCol) else ""
            if (relPath.isNullOrEmpty() || relPath.contains("ScreenRecorder", ignoreCase = true)) {
              val id = cursor.getLong(idCol)
              val itemUri =
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
              try {
                resolver.delete(itemUri, null, null)
                deletedCount++
              } catch (_: Throwable) {}
            }
          }
          if (deletedCount > 0) {
            debugLog("Cleaned up $deletedCount stale pending recording(s) older than 10 minutes")
          }
        }
      } catch (t: Throwable) {
        debugLog("Stale pending recordings cleanup error: ${t.message}", t)
      }
    }
  }

  private val isRunning = AtomicBoolean(false)
  private val isPaused = AtomicBoolean(false)
  private val isStopping = AtomicBoolean(false)
  private val isCleanedUp = AtomicBoolean(false)

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

  @Volatile private var audioSamplesWritten: Int = 0
  @Volatile private var audioEosReceived: Boolean = false
  @Volatile private var totalAudioFramesFed: Long = 0L
  @Volatile private var audioStartBasePtsUs: Long = -1L

  private var videoEncoder: MediaCodec? = null
  private var videoInputSurface: Surface? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var videoDrainThread: Thread? = null

  private var audioEncoder: MediaCodec? = null
  private var micAudioRecord: AudioRecord? = null
  private var micIsMono = false
  private var internalAudioRecord: AudioRecord? = null
  private var audioFeederThread: Thread? = null

  private val reusableVideoBufferInfo = MediaCodec.BufferInfo()
  private val reusableAudioBufferInfo = MediaCodec.BufferInfo()

  private var actualWidth = 1072
  private var actualHeight = 1920
  private var actualFps = 60
  private var actualBitrateBps = 12_000_000
  private var actualVideoMime = MediaFormat.MIMETYPE_VIDEO_AVC
  private var actualCodecLabel = "AVC (H.264)"

  fun start() {
    try {
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
        "Recording started: ${actualWidth}x${actualHeight} @ ${actualFps}fps, codec=$actualCodecLabel, bitrate=${actualBitrateBps / 1_000_000}Mbps, audio=$activeAudioPipeline, uri=$pendingOutputUri"
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

  /**
   * Ordered, sequential stop and finalize routine executed on the background thread (steps a-f).
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

    // STEP c: Stop and release the encoders and AudioRecord
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
        RecordingMediaCache.putMetadata(
          uriString,
          VideoMetadata(
            width = actualWidth,
            height = actualHeight,
            resolution = RecordingMediaCache.formatResolutionLabel(actualWidth, actualHeight),
            fps = actualFps,
            bitrateMbps = ((actualBitrateBps + 500_000) / 1_000_000).coerceAtLeast(1),
            codec = actualCodecLabel,
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
    outputDisplayName = "Screen Capture - ${formatter.format(Date())}.mp4"

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

  private fun prepareVideoEncoderAndVirtualDisplay() {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
    val densityDpi = metrics.densityDpi.coerceAtLeast(160)

    val settingsRepository = DataStoreSettingsRepository(context.applicationContext)
    val resolvedSpec =
      runBlocking(Dispatchers.IO) {
        CapabilityProbe.resolveSupportedEncoderSpec(
          context = context.applicationContext,
          settingsRepository = settingsRepository,
          requestedResolution = config.resolutionLabel,
          requestedFps = config.fps,
          orientationLabel = config.orientationLabel,
          customBitrateMbps = config.bitrateMbps.takeIf { config.isCustomBitrate },
          notifyAndPersistOnFallback = true,
        )
      }

    // Width and height are strictly multiples of 16 (rounded down)
    actualWidth = CapabilityProbe.roundDown16(resolvedSpec.width)
    actualHeight = CapabilityProbe.roundDown16(resolvedSpec.height)
    actualFps = resolvedSpec.fps
    actualBitrateBps = resolvedSpec.bitrateBps
    actualVideoMime = resolvedSpec.mimeType
    actualCodecLabel = if (resolvedSpec.codecLabel == "HEVC") "HEVC (H.265)" else "AVC (H.264)"

    fun createEncoderInstance(): MediaCodec {
      val name = resolvedSpec.encoderName
      return if (!name.isNullOrBlank()) {
        MediaCodec.createByCodecName(name)
      } else {
        MediaCodec.createEncoderByType(actualVideoMime)
      }
    }

    var encoder = createEncoderInstance()
    try {
      val primaryFormat = buildPrimaryVideoFormat()
      encoder.configure(primaryFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    } catch (firstErr: Throwable) {
      debugLog(
        "Primary MediaFormat configure() failed (${firstErr.message}); retrying with fallback format"
      )
      try {
        encoder.release()
      } catch (_: Throwable) {}
      encoder = createEncoderInstance()
      val fallbackFormat = buildFallbackVideoFormat()
      encoder.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    val inputSurface = encoder.createInputSurface()
    encoder.start()

    videoEncoder = encoder
    videoInputSurface = inputSurface

    // Create VirtualDisplay at the exact same 16-aligned size
    virtualDisplay =
      mediaProjection.createVirtualDisplay(
        "PixelScreenRecorderDisplay",
        actualWidth,
        actualHeight,
        densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        inputSurface,
        null,
        null,
      )
  }

  /**
   * Uses only: MIME (video/avc or video/hevc), width, height, KEY_COLOR_FORMAT = COLOR_FormatSurface,
   * KEY_BIT_RATE, KEY_FRAME_RATE, and KEY_I_FRAME_INTERVAL = 1.
   * Leaves KEY_BITRATE_MODE unset (device default).
   */
  private fun buildPrimaryVideoFormat(): MediaFormat {
    return MediaFormat.createVideoFormat(actualVideoMime, actualWidth, actualHeight).apply {
      setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
      )
      setInteger(MediaFormat.KEY_BIT_RATE, actualBitrateBps)
      setInteger(MediaFormat.KEY_FRAME_RATE, actualFps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
  }

  /**
   * Fallback format that drops KEY_BITRATE_MODE and KEY_I_FRAME_INTERVAL.
   */
  private fun buildFallbackVideoFormat(): MediaFormat {
    return MediaFormat.createVideoFormat(actualVideoMime, actualWidth, actualHeight).apply {
      setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
      )
      setInteger(MediaFormat.KEY_BIT_RATE, actualBitrateBps)
      setInteger(MediaFormat.KEY_FRAME_RATE, actualFps)
    }
  }

  @SuppressLint("MissingPermission")
  private fun prepareAudioPipeline(): Boolean {
    return try {
      val minStereoBuf =
        AudioRecord.getMinBufferSize(
          AUDIO_SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_STEREO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      val bufferSizeInBytes = max(minStereoBuf * 2, PCM_FRAMES_PER_READ * AUDIO_CHANNEL_COUNT * 4)

      if (config.wantsMicAudio) {
        try {
          val source =
            if (config.noiseSuppression) {
              MediaRecorder.AudioSource.VOICE_RECOGNITION
            } else {
              MediaRecorder.AudioSource.MIC
            }
          val stereoMic =
            AudioRecord(
              source,
              AUDIO_SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_STEREO,
              AudioFormat.ENCODING_PCM_16BIT,
              bufferSizeInBytes,
            )
          if (stereoMic.state == AudioRecord.STATE_INITIALIZED) {
            micAudioRecord = stereoMic
            micIsMono = false
          } else {
            stereoMic.release()
            val monoMic =
              AudioRecord(
                source,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes,
              )
            if (monoMic.state == AudioRecord.STATE_INITIALIZED) {
              micAudioRecord = monoMic
              micIsMono = true
            } else {
              monoMic.release()
            }
          }
        } catch (t: Throwable) {
          debugLog("Mic AudioRecord unavailable, continuing without mic: ${t.message}")
        }
      }

      if (config.wantsInternalAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
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
              .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
              .build()

          val internalRec =
            AudioRecord.Builder()
              .setAudioPlaybackCaptureConfig(captureConfig)
              .setAudioFormat(audioFormat)
              .setBufferSizeInBytes(bufferSizeInBytes)
              .build()

          if (internalRec.state == AudioRecord.STATE_INITIALIZED) {
            internalAudioRecord = internalRec
          } else {
            internalRec.release()
          }
        } catch (t: Throwable) {
          debugLog("AudioPlaybackCapture unavailable, continuing with silence/mic: ${t.message}")
        }
      }

      val aacFormat =
        MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
          setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
          )
          setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
          setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

      val encoder = MediaCodec.createEncoderByType(AUDIO_MIME)
      encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      encoder.start()
      audioEncoder = encoder
      true
    } catch (t: Throwable) {
      debugLog("Audio pipeline initialization failed, falling back to video-only: ${t.message}", t)
      stopAndReleaseAudioOnly()
      false
    }
  }

  /**
   * Dedicated high-priority thread for draining the hardware video encoder output.
   * Never blocks on audio; uses dequeueOutputBuffer with a 10 ms timeout.
   */
  private fun startHighPriorityVideoDrainThread() {
    val thread =
      Thread(
        {
          try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
          } catch (_: Throwable) {}

          val vCodec = videoEncoder ?: return@Thread

          while (isRunning.get() && !isStopping.get() && !videoEosReceived) {
            try {
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
            } catch (_: Throwable) {
              if (!isRunning.get() || isStopping.get()) break
            }
          }
        },
        "RecorderVideoDrain",
      )
    videoDrainThread = thread
    thread.start()
  }

  private fun startAudioInputFeederThread() {
    val thread =
      Thread(
        {
          try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
          } catch (_: Throwable) {}

          val stereoSamplesPerChunk = PCM_FRAMES_PER_READ * AUDIO_CHANNEL_COUNT
          val micPcm = ShortArray(stereoSamplesPerChunk)
          val internalPcm = ShortArray(stereoSamplesPerChunk)
          val mixedPcm = ShortArray(stereoSamplesPerChunk)
          val directPcmBytes =
            ByteBuffer.allocateDirect(stereoSamplesPerChunk * 2).order(ByteOrder.nativeOrder())

          try {
            try {
              micAudioRecord?.startRecording()
            } catch (_: Throwable) {}
            try {
              internalAudioRecord?.startRecording()
            } catch (_: Throwable) {}

            val codec = audioEncoder ?: return@Thread

            while (isRunning.get() && !isStopping.get()) {
              if (isPaused.get()) {
                Thread.sleep(20L)
                continue
              }

              val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
              if (inIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inIndex)
                inputBuf?.clear()

                val micRec = micAudioRecord
                var micSamplesRead = 0
                if (micRec != null && micRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                  if (micIsMono) {
                    val monoRead = micRec.read(micPcm, 0, PCM_FRAMES_PER_READ)
                    if (monoRead > 0) {
                      for (i in monoRead - 1 downTo 0) {
                        val s = micPcm[i]
                        micPcm[i * 2] = s
                        micPcm[i * 2 + 1] = s
                      }
                      micSamplesRead = monoRead * 2
                    }
                  } else {
                    val read = micRec.read(micPcm, 0, stereoSamplesPerChunk)
                    if (read > 0) micSamplesRead = read
                  }
                }

                val intRec = internalAudioRecord
                var intSamplesRead = 0
                if (intRec != null && intRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                  val read = intRec.read(internalPcm, 0, stereoSamplesPerChunk)
                  if (read > 0) intSamplesRead = read
                }

                val samplesToWrite =
                  when {
                    micSamplesRead > 0 && intSamplesRead > 0 -> min(micSamplesRead, intSamplesRead)
                    micSamplesRead > 0 -> micSamplesRead
                    intSamplesRead > 0 -> intSamplesRead
                    else -> {
                      Thread.sleep(20L)
                      stereoSamplesPerChunk
                    }
                  }

                for (i in 0 until samplesToWrite) {
                  val a = if (i < micSamplesRead) micPcm[i].toInt() else 0
                  val b = if (i < intSamplesRead) internalPcm[i].toInt() else 0
                  val sum = (a + b).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                  mixedPcm[i] = sum.toShort()
                }

                directPcmBytes.clear()
                directPcmBytes.asShortBuffer().put(mixedPcm, 0, samplesToWrite)
                val byteCount = samplesToWrite * 2
                directPcmBytes.position(0)
                directPcmBytes.limit(byteCount)

                val capacity = inputBuf?.remaining() ?: 0
                val safeBytes = min(byteCount, capacity)
                directPcmBytes.limit(safeBytes)
                inputBuf?.put(directPcmBytes)

                if (audioStartBasePtsUs < 0L) {
                  audioStartBasePtsUs = (System.nanoTime() / 1_000L) - totalPausedDurationUs
                }
                val ptsUs =
                  audioStartBasePtsUs + ((totalAudioFramesFed * 1_000_000L) / AUDIO_SAMPLE_RATE)
                val framesInChunk = (safeBytes / 2) / AUDIO_CHANNEL_COUNT
                totalAudioFramesFed += framesInChunk

                codec.queueInputBuffer(inIndex, 0, safeBytes, ptsUs, 0)
              }

              drainAudioCodecStep(codec)
            }
          } catch (_: Throwable) {
            // Exits cleanly when stopping
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
          val encodedData = codec.getOutputBuffer(outIndex)
          val flags = reusableVideoBufferInfo.flags
          val isConfigBuffer = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
          val isEos = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

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

            encodedData.position(reusableVideoBufferInfo.offset)
            encodedData.limit(reusableVideoBufferInfo.offset + reusableVideoBufferInfo.size)
            synchronized(muxerLock) {
              if (muxerStarted) {
                mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, reusableVideoBufferInfo)
                videoSamplesWritten++
              }
            }
          }

          codec.releaseOutputBuffer(outIndex, false)

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
                mediaMuxer?.writeSampleData(audioTrackIndex, encodedData, reusableAudioBufferInfo)
                audioSamplesWritten++
              }
            }
          }

          codec.releaseOutputBuffer(outIndex, false)

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
        val baseUs =
          if (audioStartBasePtsUs >= 0L) audioStartBasePtsUs else (System.nanoTime() / 1_000L)
        val ptsUs = baseUs + ((totalAudioFramesFed * 1_000_000L) / AUDIO_SAMPLE_RATE)
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
    try {
      micAudioRecord?.let {
        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          it.stop()
        }
        it.release()
      }
    } catch (_: Throwable) {} finally {
      micAudioRecord = null
    }

    try {
      internalAudioRecord?.let {
        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          it.stop()
        }
        it.release()
      }
    } catch (_: Throwable) {} finally {
      internalAudioRecord = null
    }

    try {
      audioEncoder?.stop()
    } catch (_: Throwable) {}
    try {
      audioEncoder?.release()
    } catch (_: Throwable) {} finally {
      audioEncoder = null
    }
  }

  private fun stopAndReleaseEncodersAndAudioRecord() {
    try {
      videoInputSurface?.release()
    } catch (_: Throwable) {} finally {
      videoInputSurface = null
    }

    try {
      videoEncoder?.stop()
    } catch (_: Throwable) {}
    try {
      videoEncoder?.release()
    } catch (_: Throwable) {} finally {
      videoEncoder = null
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
