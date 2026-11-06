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
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.example.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RecordingSessionConfig(
  val resolutionLabel: String,
  val fps: Int,
  val bitrateMbps: Int,
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
    private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val AUDIO_SAMPLE_RATE = 44_100
    private const val AUDIO_CHANNEL_COUNT = 2
    private const val AUDIO_BITRATE = 128_000
    private const val PCM_FRAMES_PER_READ = 1024
    private const val DEQUEUE_TIMEOUT_US = 10_000L
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
            val relPath = if (pathCol >= 0 && !cursor.isNull(pathCol)) cursor.getString(pathCol) else ""
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

  private val drainLock = Any()
  @Volatile private var muxerStarted = false
  @Volatile private var videoTrackIndex = -1
  @Volatile private var audioTrackIndex = -1
  @Volatile private var activeAudioPipeline = false
  @Volatile private var engineStartRealtimeMs = 0L

  // Timestamp normalization and monotonic tracking (starting near 0 us per track)
  private var videoBaseRawPtsUs: Long = -1L
  private var lastVideoMuxedPtsUs: Long = -1L
  private var videoSamplesWritten: Int = 0
  private var videoEosReceived: Boolean = false

  private var audioBaseRawPtsUs: Long = -1L
  private var lastAudioMuxedPtsUs: Long = -1L
  private var audioSamplesWritten: Int = 0
  private var audioEosReceived: Boolean = false
  @Volatile private var totalAudioFramesFed: Long = 0L

  private var videoEncoder: MediaCodec? = null
  private var videoInputSurface: Surface? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var drainThread: HandlerThread? = null
  private var drainHandler: Handler? = null

  private var audioEncoder: MediaCodec? = null
  private var micAudioRecord: AudioRecord? = null
  private var micIsMono = false
  private var internalAudioRecord: AudioRecord? = null
  private var audioFeederThread: Thread? = null

  private val reusableVideoBufferInfo = MediaCodec.BufferInfo()
  private val reusableAudioBufferInfo = MediaCodec.BufferInfo()

  private var actualWidth = 1080
  private var actualHeight = 1920
  private var actualFps = 60
  private var actualBitrateBps = 16_000_000

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

      if (activeAudioPipeline) {
        startAudioInputFeederThread()
      }
      startPeriodicEncoderDrain()

      debugLog(
        "Recording started: ${actualWidth}x${actualHeight} @ ${actualFps}fps, audio=$activeAudioPipeline, uri=$pendingOutputUri"
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
   * Step g (publishing to library/playback) is performed by the caller immediately after this returns.
   */
  fun stopAndFinalize(recordedDurationMs: Long): SavedRecordingMetadata? {
    if (isStopping.getAndSet(true)) return null
    if (isPaused.get()) {
      resume()
    }
    isRunning.set(false)

    // Stop the background audio feeder and periodic drain handler so this background thread
    // has exclusive, sequential ownership of the encoders and muxer.
    try {
      audioFeederThread?.join(1_000L)
    } catch (_: Throwable) {}
    audioFeederThread = null

    try {
      drainHandler?.removeCallbacksAndMessages(null)
      drainThread?.quitSafely()
      drainThread?.join(1_000L)
    } catch (_: Throwable) {}
    drainHandler = null
    drainThread = null

    synchronized(drainLock) {
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
      val muxer = mediaMuxer
      mediaMuxer = null
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
          val values =
            ContentValues().apply {
              put(MediaStore.Video.Media.IS_PENDING, 0)
            }
          val updatedRows = context.contentResolver.update(uri, values, null, null)
          val fileSize = queryUriSize(uri)
          debugLog(
            "Step f (Clear IS_PENDING=0) succeeded: rowsUpdated=$updatedRows, finalUri=$uri, fileSize=$fileSize bytes"
          )

          SavedRecordingMetadata(
            contentUri = uri,
            displayName = outputDisplayName.removeSuffix(".mp4"),
            durationMs = recordedDurationMs.coerceAtLeast(1_000L),
            sizeBytes = fileSize.coerceAtLeast(1_024L),
            resolution = "${min(actualWidth, actualHeight)}p (${actualWidth}×${actualHeight})",
            fps = actualFps,
            bitrateMbps = (actualBitrateBps / 1_000_000).coerceAtLeast(1),
            codec = "AVC (H.264)",
            container = "MP4",
            hasMicAudio = config.wantsMicAudio && activeAudioPipeline,
            audioChannelMode = config.audioChannelMode,
          )
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
    val screenW = metrics.widthPixels.coerceAtLeast(360)
    val screenH = metrics.heightPixels.coerceAtLeast(640)
    val densityDpi = metrics.densityDpi.coerceAtLeast(160)

    val wantLandscape =
      when (config.orientationLabel) {
        "Landscape" -> true
        "Portrait" -> false
        else -> screenW > screenH
      }

    val shortSideTarget =
      when {
        config.resolutionLabel.startsWith("1440") -> 1440
        config.resolutionLabel.startsWith("1080") -> 1080
        config.resolutionLabel.startsWith("720") -> 720
        config.resolutionLabel.startsWith("480") -> 480
        else -> min(screenW, screenH)
      }

    val aspect = max(screenW, screenH).toDouble() / min(screenW, screenH).toDouble()
    val rawShort = roundEven(shortSideTarget)
    val rawLong = roundEven((rawShort * aspect).roundToInt())

    val desiredWidth = if (wantLandscape) rawLong else rawShort
    val desiredHeight = if (wantLandscape) rawShort else rawLong
    val desiredFps = config.fps.coerceIn(15, 120)
    val desiredBitrate = (config.bitrateMbps.coerceIn(1, 64)) * 1_000_000

    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val avcEncoderInfo =
      codecList.codecInfos.firstOrNull { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(VIDEO_MIME, ignoreCase = true) }
      }

    val videoCaps: MediaCodecInfo.VideoCapabilities? =
      avcEncoderInfo?.getCapabilitiesForType(VIDEO_MIME)?.videoCapabilities

    if (videoCaps != null) {
      val wAlign = max(2, videoCaps.widthAlignment)
      val hAlign = max(2, videoCaps.heightAlignment)

      val supportedWidths = videoCaps.supportedWidths
      val clampedW =
        alignTo(
          desiredWidth.coerceIn(supportedWidths.lower, supportedWidths.upper),
          wAlign,
        ).coerceIn(supportedWidths.lower, supportedWidths.upper)

      val supportedHeights =
        try {
          videoCaps.getSupportedHeightsFor(clampedW)
        } catch (_: Throwable) {
          videoCaps.supportedHeights
        }
      val clampedH =
        alignTo(
          desiredHeight.coerceIn(supportedHeights.lower, supportedHeights.upper),
          hAlign,
        ).coerceIn(supportedHeights.lower, supportedHeights.upper)

      val fpsRange =
        try {
          videoCaps.getSupportedFrameRatesFor(clampedW, clampedH)
        } catch (_: Throwable) {
          videoCaps.supportedFrameRates
        }
      val clampedFps =
        desiredFps
          .toDouble()
          .coerceIn(fpsRange.lower.toDouble(), fpsRange.upper.toDouble())
          .roundToInt()
          .coerceAtLeast(15)

      val bitrateRange = videoCaps.bitrateRange
      val clampedBitrate = desiredBitrate.coerceIn(bitrateRange.lower, bitrateRange.upper)

      actualWidth = clampedW
      actualHeight = clampedH
      actualFps = clampedFps
      actualBitrateBps = clampedBitrate
    } else {
      actualWidth = roundEven(desiredWidth)
      actualHeight = roundEven(desiredHeight)
      actualFps = desiredFps
      actualBitrateBps = desiredBitrate
    }

    val format =
      MediaFormat.createVideoFormat(VIDEO_MIME, actualWidth, actualHeight).apply {
        setInteger(
          MediaFormat.KEY_COLOR_FORMAT,
          MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        setInteger(MediaFormat.KEY_BIT_RATE, actualBitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, actualFps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // Repeat previous frame every 100ms when screen is static so surface encoder always emits frames
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
      }

    val encoder =
      if (avcEncoderInfo != null) {
        MediaCodec.createByCodecName(avcEncoderInfo.name)
      } else {
        MediaCodec.createEncoderByType(VIDEO_MIME)
      }
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val inputSurface = encoder.createInputSurface()
    encoder.start()

    videoEncoder = encoder
    videoInputSurface = inputSurface

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

  private fun startAudioInputFeederThread() {
    val thread =
      Thread(
        {
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
                      // When generating silence, pace to real time (~23ms per 1024 frames)
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

                val ptsUs = (totalAudioFramesFed * 1_000_000L) / AUDIO_SAMPLE_RATE
                val framesInChunk = (safeBytes / 2) / AUDIO_CHANNEL_COUNT
                totalAudioFramesFed += framesInChunk

                codec.queueInputBuffer(inIndex, 0, safeBytes, ptsUs, 0)
              }
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

  private fun startPeriodicEncoderDrain() {
    val thread = HandlerThread("RecorderVideoDrain")
    thread.start()
    val handler = Handler(thread.looper)
    drainThread = thread
    drainHandler = handler

    val drainRunnable =
      object : Runnable {
        override fun run() {
          if (!isRunning.get() || isStopping.get()) return
          synchronized(drainLock) {
            if (isRunning.get() && !isStopping.get()) {
              drainAvailableOutputNonBlocking()
            }
          }
          if (isRunning.get() && !isStopping.get()) {
            handler.postDelayed(this, 10L)
          }
        }
      }
    handler.post(drainRunnable)
  }

  private fun drainAvailableOutputNonBlocking() {
    val vCodec = videoEncoder
    val aCodec = audioEncoder

    // 1. If video format is not yet known, peek video encoder for INFO_OUTPUT_FORMAT_CHANGED
    if (vCodec != null && videoTrackIndex < 0) {
      drainSingleCodecPass(codec = vCodec, isVideo = true, waitForMuxerStart = true)
    }

    // 2. If audio is active and audio format is not yet known, peek audio encoder for INFO_OUTPUT_FORMAT_CHANGED
    if (activeAudioPipeline && aCodec != null && audioTrackIndex < 0) {
      drainSingleCodecPass(codec = aCodec, isVideo = false, waitForMuxerStart = true)
    }

    // Fallback: if audio encoder hasn't produced a format after 800ms, start muxer with video-only
    if (
      !muxerStarted &&
        videoTrackIndex >= 0 &&
        activeAudioPipeline &&
        (SystemClock.elapsedRealtime() - engineStartRealtimeMs) > 800L
    ) {
      activeAudioPipeline = false
      audioEosReceived = true
      maybeStartMuxer()
    }

    // 3. Once muxer has started, drain all available encoded samples from both tracks
    if (muxerStarted) {
      if (vCodec != null && !videoEosReceived) {
        drainSingleCodecPass(codec = vCodec, isVideo = true, waitForMuxerStart = false)
      }
      if (activeAudioPipeline && aCodec != null && !audioEosReceived) {
        drainSingleCodecPass(codec = aCodec, isVideo = false, waitForMuxerStart = false)
      }
    }
  }

  private fun queueAudioEndOfStream() {
    val codec = audioEncoder ?: return
    val deadline = SystemClock.elapsedRealtime() + 500L
    while (SystemClock.elapsedRealtime() < deadline) {
      val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
      if (inIndex >= 0) {
        val ptsUs = (totalAudioFramesFed * 1_000_000L) / AUDIO_SAMPLE_RATE
        codec.queueInputBuffer(
          inIndex,
          0,
          0,
          ptsUs,
          MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
        return
      }
      // Drain output buffer in case encoder queue is full
      drainSingleCodecPass(codec = codec, isVideo = false, waitForMuxerStart = !muxerStarted)
    }
  }

  private fun drainEncodersUntilEosSequential() {
    val vCodec = videoEncoder
    val aCodec = audioEncoder

    // Ensure formats are registered so muxer starts before draining remaining EOS samples
    val formatDeadline = SystemClock.elapsedRealtime() + 1_000L
    while (!muxerStarted && SystemClock.elapsedRealtime() < formatDeadline) {
      if (vCodec != null && videoTrackIndex < 0) {
        drainSingleCodecPass(codec = vCodec, isVideo = true, waitForMuxerStart = true)
      }
      if (activeAudioPipeline && aCodec != null && audioTrackIndex < 0) {
        drainSingleCodecPass(codec = aCodec, isVideo = false, waitForMuxerStart = true)
      }
      if (muxerStarted) break
      Thread.sleep(10L)
    }

    // If video track format is ready but audio format never arrived, start muxer with video track
    if (!muxerStarted && videoTrackIndex >= 0) {
      activeAudioPipeline = false
      audioEosReceived = true
      maybeStartMuxer()
    }

    // Drain video encoder until BUFFER_FLAG_END_OF_STREAM (3-second timeout)
    if (vCodec != null) {
      val videoDeadline = SystemClock.elapsedRealtime() + EOS_DRAIN_TIMEOUT_MS
      while (!videoEosReceived && SystemClock.elapsedRealtime() < videoDeadline) {
        val drainedAny =
          drainSingleCodecPass(codec = vCodec, isVideo = true, waitForMuxerStart = false)
        if (!drainedAny && !videoEosReceived) {
          Thread.sleep(10L)
        }
      }
    }

    // Drain audio encoder until BUFFER_FLAG_END_OF_STREAM (3-second timeout)
    if (activeAudioPipeline && aCodec != null) {
      val audioDeadline = SystemClock.elapsedRealtime() + EOS_DRAIN_TIMEOUT_MS
      while (!audioEosReceived && SystemClock.elapsedRealtime() < audioDeadline) {
        val drainedAny =
          drainSingleCodecPass(codec = aCodec, isVideo = false, waitForMuxerStart = false)
        if (!drainedAny && !audioEosReceived) {
          Thread.sleep(10L)
        }
      }
    }
  }

  /**
   * Drains output buffers from [codec].
   * When [waitForMuxerStart] is true and the track format is already registered, stops before
   * consuming sample buffers so the initial keyframe is preserved until [mediaMuxer] starts.
   */
  private fun drainSingleCodecPass(
    codec: MediaCodec,
    isVideo: Boolean,
    waitForMuxerStart: Boolean,
  ): Boolean {
    if (waitForMuxerStart) {
      if (isVideo && videoTrackIndex >= 0) return false
      if (!isVideo && audioTrackIndex >= 0) return false
    }

    val bufferInfo = if (isVideo) reusableVideoBufferInfo else reusableAudioBufferInfo
    var processedAny = false

    while (true) {
      val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
      when {
        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          processedAny = true
          val muxer = mediaMuxer
          if (muxer != null && !muxerStarted) {
            if (isVideo && videoTrackIndex < 0) {
              videoTrackIndex = muxer.addTrack(codec.outputFormat)
              maybeStartMuxer()
            } else if (!isVideo && audioTrackIndex < 0) {
              audioTrackIndex = muxer.addTrack(codec.outputFormat)
              maybeStartMuxer()
            }
          }
          if (waitForMuxerStart && !muxerStarted) {
            break
          }
        }
        outIndex >= 0 -> {
          processedAny = true
          val encodedData = codec.getOutputBuffer(outIndex)
          val isConfigBuffer = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
          val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

          // Never write BUFFER_FLAG_CODEC_CONFIG buffers to the muxer
          if (!isConfigBuffer && bufferInfo.size > 0 && encodedData != null && muxerStarted) {
            if (isVideo && videoTrackIndex >= 0) {
              val rawAdjustedUs = bufferInfo.presentationTimeUs - totalPausedDurationUs
              if (videoBaseRawPtsUs < 0L) {
                videoBaseRawPtsUs = rawAdjustedUs
              }
              var normalizedPtsUs = (rawAdjustedUs - videoBaseRawPtsUs).coerceAtLeast(0L)
              if (lastVideoMuxedPtsUs >= 0L && normalizedPtsUs <= lastVideoMuxedPtsUs) {
                // Ensure strictly monotonically increasing presentation timestamps
                normalizedPtsUs = lastVideoMuxedPtsUs + 1_000L
              }
              lastVideoMuxedPtsUs = normalizedPtsUs
              bufferInfo.presentationTimeUs = normalizedPtsUs

              encodedData.position(bufferInfo.offset)
              encodedData.limit(bufferInfo.offset + bufferInfo.size)
              mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
              videoSamplesWritten++
            } else if (!isVideo && audioTrackIndex >= 0) {
              val rawPtsUs = bufferInfo.presentationTimeUs
              if (audioBaseRawPtsUs < 0L) {
                audioBaseRawPtsUs = rawPtsUs
              }
              var normalizedPtsUs = (rawPtsUs - audioBaseRawPtsUs).coerceAtLeast(0L)
              if (lastAudioMuxedPtsUs >= 0L && normalizedPtsUs <= lastAudioMuxedPtsUs) {
                normalizedPtsUs = lastAudioMuxedPtsUs + 500L
              }
              lastAudioMuxedPtsUs = normalizedPtsUs
              bufferInfo.presentationTimeUs = normalizedPtsUs

              encodedData.position(bufferInfo.offset)
              encodedData.limit(bufferInfo.offset + bufferInfo.size)
              mediaMuxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
              audioSamplesWritten++
            }
          }

          codec.releaseOutputBuffer(outIndex, false)

          if (isEos) {
            if (isVideo) {
              videoEosReceived = true
            } else {
              audioEosReceived = true
            }
            break
          }
        }
      }
    }
    return processedAny
  }

  private fun maybeStartMuxer() {
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
      drainHandler?.removeCallbacksAndMessages(null)
      drainThread?.quitSafely()
    } catch (_: Throwable) {} finally {
      drainHandler = null
      drainThread = null
    }

    try {
      virtualDisplay?.release()
    } catch (_: Throwable) {} finally {
      virtualDisplay = null
    }

    stopAndReleaseEncodersAndAudioRecord()

    val muxer = mediaMuxer
    mediaMuxer = null
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

    pendingOutputUri?.let { uri ->
      try {
        context.contentResolver.delete(uri, null, null)
      } catch (_: Throwable) {}
    }
    pendingOutputUri = null
  }

  private fun roundEven(value: Int): Int {
    val clamped = max(2, value)
    return if (clamped % 2 == 0) clamped else clamped - 1
  }

  private fun alignTo(value: Int, alignment: Int): Int {
    if (alignment <= 1) return value
    return (value / alignment) * alignment
  }
}
