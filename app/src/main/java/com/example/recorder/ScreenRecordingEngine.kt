package com.example.recorder

import android.annotation.SuppressLint
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
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private const val PARAMETER_KEY_SUSPEND_VIDEO_INPUT = MediaCodec.PARAMETER_KEY_SUSPEND
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

  private var videoEncoder: MediaCodec? = null
  private var videoInputSurface: Surface? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var videoDrainThread: HandlerThread? = null
  private var videoDrainHandler: Handler? = null
  private val videoDrainFinishedLatch = CountDownLatch(1)

  private var audioEncoder: MediaCodec? = null
  private var micAudioRecord: AudioRecord? = null
  private var micIsMono = false
  private var internalAudioRecord: AudioRecord? = null
  private var audioThread: Thread? = null
  private val audioDrainFinishedLatch = CountDownLatch(1)

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

      if (!activeAudioPipeline) {
        audioDrainFinishedLatch.countDown()
      }

      isRunning.set(true)
      startVideoDrainLoop()
      if (activeAudioPipeline) {
        startAudioCaptureAndEncodeLoop()
      }
      Log.i(
        TAG,
        "Recording started: ${actualWidth}x${actualHeight} @ ${actualFps}fps, audio=$activeAudioPipeline"
      )
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to start ScreenRecordingEngine", t)
      cleanupAll(deletePendingUri = true)
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
      Log.w(TAG, "Failed to suspend video encoder input", t)
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
      Log.w(TAG, "Failed to resume video encoder input", t)
    }
  }

  fun stopAndFinalize(recordedDurationMs: Long): SavedRecordingMetadata? {
    if (!isRunning.getAndSet(false)) {
      cleanupAll(deletePendingUri = true)
      return null
    }
    if (isPaused.get()) {
      resume()
    }
    isStopping.set(true)

    return try {
      try {
        videoEncoder?.signalEndOfInputStream()
      } catch (t: Throwable) {
        Log.w(TAG, "signalEndOfInputStream warning", t)
      }

      videoDrainFinishedLatch.await(3_000L, TimeUnit.MILLISECONDS)
      audioDrainFinishedLatch.await(3_000L, TimeUnit.MILLISECONDS)

      stopMuxerSafely()
      val savedUri = finalizeMediaStoreUri()
      if (savedUri != null) {
        val fileSize = queryUriSize(savedUri)
        SavedRecordingMetadata(
          contentUri = savedUri,
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
      } else {
        null
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error stopping and finalizing recording", t)
      null
    } finally {
      cleanupAll(deletePendingUri = !muxerStarted)
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
        ?: throw IllegalStateException("Unable to open ParcelFileDescriptor for $uri")
  }

  private fun finalizeMediaStoreUri(): Uri? {
    val uri = pendingOutputUri ?: return null
    return try {
      try {
        parcelFileDescriptor?.close()
      } catch (_: Throwable) {}
      parcelFileDescriptor = null

      val values =
        ContentValues().apply {
          put(MediaStore.Video.Media.IS_PENDING, 0)
        }
      context.contentResolver.update(uri, values, null, null)
      uri
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to clear IS_PENDING on MediaStore Uri", t)
      null
    }
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
          Log.w(TAG, "Mic AudioRecord unavailable, continuing without mic", t)
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
          Log.w(TAG, "AudioPlaybackCapture unavailable, continuing with silence/mic", t)
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
      Log.w(TAG, "Audio pipeline initialization failed, falling back to video-only", t)
      releaseAudioResources()
      false
    }
  }

  private fun startVideoDrainLoop() {
    val thread = HandlerThread("RecorderVideoDrain")
    thread.start()
    val handler = Handler(thread.looper)
    videoDrainThread = thread
    videoDrainHandler = handler

    handler.post {
      val bufferInfo = MediaCodec.BufferInfo()
      var lastVideoPtsUs = 0L
      try {
        val codec = videoEncoder ?: return@post
        var eosReached = false
        while (!eosReached) {
          val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
          when {
            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
              if (!isRunning.get() && isStopping.get()) {
                // Keep draining until EOS flag arrives
              }
            }
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              registerVideoTrackAndMaybeStartMuxer(codec.outputFormat)
            }
            outIndex >= 0 -> {
              val encodedData = codec.getOutputBuffer(outIndex)
              if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                bufferInfo.size = 0
              }
              if (bufferInfo.size > 0 && encodedData != null && isMuxerReadyForWrite()) {
                val adjustedPts =
                  (bufferInfo.presentationTimeUs - totalPausedDurationUs).coerceAtLeast(
                    lastVideoPtsUs + 1L
                  )
                lastVideoPtsUs = adjustedPts
                bufferInfo.presentationTimeUs = adjustedPts
                encodedData.position(bufferInfo.offset)
                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                synchronized(muxerLock) {
                  if (muxerStarted && videoTrackIndex >= 0) {
                    mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                  }
                }
              }
              eosReached = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
              codec.releaseOutputBuffer(outIndex, false)
            }
          }
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Video drain loop terminated", t)
      } finally {
        videoDrainFinishedLatch.countDown()
      }
    }
  }

  private fun startAudioCaptureAndEncodeLoop() {
    val thread =
      Thread(
        {
          val stereoSamplesPerChunk = PCM_FRAMES_PER_READ * AUDIO_CHANNEL_COUNT
          val micPcm = ShortArray(stereoSamplesPerChunk)
          val internalPcm = ShortArray(stereoSamplesPerChunk)
          val mixedPcm = ShortArray(stereoSamplesPerChunk)
          val directPcmBytes =
            ByteBuffer.allocateDirect(stereoSamplesPerChunk * 2).order(ByteOrder.nativeOrder())
          val audioBufferInfo = MediaCodec.BufferInfo()
          var totalFramesWritten = 0L

          try {
            try {
              micAudioRecord?.startRecording()
            } catch (_: Throwable) {}
            try {
              internalAudioRecord?.startRecording()
            } catch (_: Throwable) {}

            val codec = audioEncoder ?: return@Thread
            var inputEosSent = false
            var outputEosReached = false

            while (!outputEosReached) {
              if (isPaused.get() && isRunning.get()) {
                Thread.sleep(20L)
                continue
              }

              if (!inputEosSent) {
                val running = isRunning.get()
                val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                  val inputBuf = codec.getInputBuffer(inIndex)
                  inputBuf?.clear()
                  if (!running) {
                    val ptsUs = (totalFramesWritten * 1_000_000L) / AUDIO_SAMPLE_RATE
                    codec.queueInputBuffer(
                      inIndex,
                      0,
                      0,
                      ptsUs,
                      MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    inputEosSent = true
                  } else {
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
                        micSamplesRead > 0 && intSamplesRead > 0 ->
                          min(micSamplesRead, intSamplesRead)
                        micSamplesRead > 0 -> micSamplesRead
                        intSamplesRead > 0 -> intSamplesRead
                        else -> stereoSamplesPerChunk
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

                    val ptsUs = (totalFramesWritten * 1_000_000L) / AUDIO_SAMPLE_RATE
                    val framesInChunk = (safeBytes / 2) / AUDIO_CHANNEL_COUNT
                    totalFramesWritten += framesInChunk

                    codec.queueInputBuffer(inIndex, 0, safeBytes, ptsUs, 0)
                  }
                }
              }

              // Drain available audio output buffers
              while (true) {
                val outIndex = codec.dequeueOutputBuffer(audioBufferInfo, 2_000L)
                when {
                  outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                  outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    registerAudioTrackAndMaybeStartMuxer(codec.outputFormat)
                  }
                  outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                      audioBufferInfo.size = 0
                    }
                    if (audioBufferInfo.size > 0 && outBuf != null && isMuxerReadyForWrite()) {
                      outBuf.position(audioBufferInfo.offset)
                      outBuf.limit(audioBufferInfo.offset + audioBufferInfo.size)
                      synchronized(muxerLock) {
                        if (muxerStarted && audioTrackIndex >= 0) {
                          mediaMuxer?.writeSampleData(audioTrackIndex, outBuf, audioBufferInfo)
                        }
                      }
                    }
                    if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                      outputEosReached = true
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (outputEosReached) break
                  }
                }
              }
            }
          } catch (t: Throwable) {
            Log.w(TAG, "Audio loop terminated", t)
          } finally {
            audioDrainFinishedLatch.countDown()
          }
        },
        "RecorderAudioMixEncode",
      )
    audioThread = thread
    thread.start()
  }

  private fun registerVideoTrackAndMaybeStartMuxer(format: MediaFormat) {
    synchronized(muxerLock) {
      if (muxerStarted || videoTrackIndex >= 0) return
      val muxer = mediaMuxer ?: return
      videoTrackIndex = muxer.addTrack(format)
      maybeStartMuxerLocked()
    }
  }

  private fun registerAudioTrackAndMaybeStartMuxer(format: MediaFormat) {
    synchronized(muxerLock) {
      if (muxerStarted || audioTrackIndex >= 0) return
      val muxer = mediaMuxer ?: return
      audioTrackIndex = muxer.addTrack(format)
      maybeStartMuxerLocked()
    }
  }

  private fun maybeStartMuxerLocked() {
    if (muxerStarted) return
    val videoReady = videoTrackIndex >= 0
    val audioReady = !activeAudioPipeline || audioTrackIndex >= 0
    if (videoReady && audioReady) {
      mediaMuxer?.start()
      muxerStarted = true
    }
  }

  private fun isMuxerReadyForWrite(): Boolean = muxerStarted

  private fun stopMuxerSafely() {
    synchronized(muxerLock) {
      val muxer = mediaMuxer ?: return
      try {
        if (muxerStarted) {
          muxer.stop()
        }
      } catch (t: Throwable) {
        Log.w(TAG, "MediaMuxer stop warning", t)
      } finally {
        try {
          muxer.release()
        } catch (_: Throwable) {}
        mediaMuxer = null
        muxerStarted = false
      }
    }
  }

  private fun releaseAudioResources() {
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

  fun cleanupAll(deletePendingUri: Boolean = false) {
    if (isCleanedUp.getAndSet(true)) return
    isRunning.set(false)

    try {
      virtualDisplay?.release()
    } catch (_: Throwable) {} finally {
      virtualDisplay = null
    }

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

    try {
      videoDrainThread?.quitSafely()
    } catch (_: Throwable) {} finally {
      videoDrainThread = null
      videoDrainHandler = null
    }

    releaseAudioResources()
    stopMuxerSafely()

    try {
      parcelFileDescriptor?.close()
    } catch (_: Throwable) {} finally {
      parcelFileDescriptor = null
    }

    if (deletePendingUri) {
      pendingOutputUri?.let { uri ->
        try {
          context.contentResolver.delete(uri, null, null)
        } catch (_: Throwable) {}
      }
      pendingOutputUri = null
    }
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
