package com.example.data.capabilities

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.example.domain.model.DeviceCapabilities
import com.example.domain.model.OptionProbeState
import com.example.domain.repository.CapabilitiesProvider
import com.example.domain.repository.SettingsRepository
import com.example.recorder.RecordingStateHolder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ResolvedEncoderSpec(
  val resolutionLabel: String,
  val width: Int,
  val height: Int,
  val fps: Int,
  val bitrateBps: Int,
  val codecLabel: String,
  val mimeType: String,
  val encoderName: String?,
  val fellBackFromSaved: Boolean,
)

object CapabilityProbe {

  val ALL_RESOLUTIONS: List<String> = listOf("480p", "720p", "1080p", "1440p", "Native")
  val ALL_FPS: List<Int> = listOf(24, 30, 48, 60, 90, 120)

  private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
  private const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

  private val hardwareProbeMutex = Mutex()

  fun roundDown16(value: Int): Int = ((value / 16) * 16).coerceAtLeast(16)

  fun normalizeResolutionLabel(raw: String): String {
    return when {
      raw.startsWith("Native", ignoreCase = true) -> "Native"
      raw.startsWith("1440") -> "1440p"
      raw.startsWith("1080") -> "1080p"
      raw.startsWith("720") -> "720p"
      raw.startsWith("480") -> "480p"
      else -> "1080p"
    }
  }

  fun getDisplayRefreshRateHz(context: Context): Int {
    return try {
      val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      @Suppress("DEPRECATION")
      val rate = wm.defaultDisplay.refreshRate
      rate.roundToInt().coerceIn(30, 240)
    } catch (_: Throwable) {
      60
    }
  }

  fun compute16AlignedDimensions(
    context: Context,
    resolutionLabel: String,
    orientationLabel: String = "Auto",
  ): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
    val screenW = metrics.widthPixels.coerceAtLeast(360)
    val screenH = metrics.heightPixels.coerceAtLeast(640)

    val wantLandscape =
      when (orientationLabel) {
        "Landscape" -> true
        "Portrait" -> false
        else -> screenW > screenH
      }

    val realShort = min(screenW, screenH)
    val realLong = max(screenW, screenH)
    val aspect = realLong.toDouble() / realShort.toDouble()

    val normalized = normalizeResolutionLabel(resolutionLabel)
    val targetShort =
      when (normalized) {
        "Native" -> realShort
        "1440p" -> 1440
        "1080p" -> 1080
        "720p" -> 720
        "480p" -> 480
        else -> 1080
      }
    val targetLong =
      if (normalized == "Native") {
        realLong
      } else {
        (targetShort * aspect).roundToInt()
      }

    val alignedShort = roundDown16(targetShort)
    val alignedLong = roundDown16(targetLong)

    return if (wantLandscape) {
      alignedLong to alignedShort
    } else {
      alignedShort to alignedLong
    }
  }

  fun resolutionShortSideValue(context: Context, resolutionLabel: String): Int {
    val (w, h) = compute16AlignedDimensions(context, resolutionLabel, "Portrait")
    return min(w, h)
  }

  /**
   * Scales default bitrate from resolution and fps: roughly pixels * fps * 0.1 bits
   * (about 12 Mbps for 1080p at 60 fps), clamped to the encoder's getBitrateRange().
   * Keeps the user's custom bitrate setting if one is set.
   */
  fun computeClampedBitrateBps(
    width: Int,
    height: Int,
    fps: Int,
    mimeType: String,
    customBitrateMbps: Int? = null,
  ): Int {
    val rawBps =
      if (customBitrateMbps != null && customBitrateMbps > 0) {
        customBitrateMbps.toLong() * 1_000_000L
      } else {
        (width.toDouble() * height.toDouble() * fps.toDouble() * 0.1).toLong()
      }
    val safeRawBps = rawBps.coerceIn(500_000L, 100_000_000L).toInt()
    val encoderInfo = findPreferredEncoderInfo(mimeType)
    val bitrateRange =
      try {
        encoderInfo?.getCapabilitiesForType(mimeType)?.videoCapabilities?.bitrateRange
      } catch (_: Throwable) {
        null
      }
    return if (bitrateRange != null) {
      safeRawBps.coerceIn(bitrateRange.lower, bitrateRange.upper)
    } else {
      safeRawBps
    }
  }

  fun findPreferredEncoderInfo(mimeType: String): MediaCodecInfo? {
    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val matching =
      codecList.codecInfos.filter { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
      }
    return matching.firstOrNull { info ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        info.isHardwareAccelerated
      } else {
        !info.name.startsWith("OMX.google.", ignoreCase = true) &&
          !info.name.startsWith("c2.android.", ignoreCase = true)
      }
    } ?: matching.firstOrNull()
  }

  /**
   * Probes (width, height, fps) by trying H.264 first, then HEVC.
   * Uses DataStore cache keyed by codec + width + height + fps so each probe runs once per device.
   */
  suspend fun probeCombination(
    settingsRepository: SettingsRepository,
    width: Int,
    height: Int,
    fps: Int,
  ): OptionProbeState.Supported? {
    val alignedW = roundDown16(width)
    val alignedH = roundDown16(height)

    // 1. Try H.264 first
    val cachedAvc = settingsRepository.getCachedProbeResult("H.264", alignedW, alignedH, fps)
    val avcSupported =
      if (cachedAvc != null) {
        cachedAvc
      } else {
        val probed =
          hardwareProbeMutex.withLock {
            realProbeSingleCodec(MIME_AVC, alignedW, alignedH, fps)
          }
        settingsRepository.saveCachedProbeResult("H.264", alignedW, alignedH, fps, probed)
        probed
      }

    if (avcSupported) {
      return OptionProbeState.Supported(
        codecLabel = "H.264",
        mimeType = MIME_AVC,
        width = alignedW,
        height = alignedH,
      )
    }

    // 2. Try HEVC second
    val cachedHevc = settingsRepository.getCachedProbeResult("HEVC", alignedW, alignedH, fps)
    val hevcSupported =
      if (cachedHevc != null) {
        cachedHevc
      } else {
        val probed =
          hardwareProbeMutex.withLock {
            realProbeSingleCodec(MIME_HEVC, alignedW, alignedH, fps)
          }
        settingsRepository.saveCachedProbeResult("HEVC", alignedW, alignedH, fps, probed)
        probed
      }

    if (hevcSupported) {
      return OptionProbeState.Supported(
        codecLabel = "HEVC",
        mimeType = MIME_HEVC,
        width = alignedW,
        height = alignedH,
      )
    }

    return null
  }

  /**
   * Really creates, configures, calls createInputSurface(), starts, stops, and releases
   * the encoder at the exact 16-aligned size and fps with a representative bitrate.
   */
  private fun realProbeSingleCodec(
    mimeType: String,
    width: Int,
    height: Int,
    fps: Int,
  ): Boolean {
    val encoderInfo = findPreferredEncoderInfo(mimeType) ?: return false
    val representativeBitrate = computeClampedBitrateBps(width, height, fps, mimeType, null)

    fun tryConfigureAndStart(includeIFrameInterval: Boolean): Boolean {
      var codec: MediaCodec? = null
      var inputSurface: Surface? = null
      var started = false
      return try {
        codec = MediaCodec.createByCodecName(encoderInfo.name)
        val format =
          MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(
              MediaFormat.KEY_COLOR_FORMAT,
              MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, representativeBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            if (includeIFrameInterval) {
              setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
          }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        started = true
        codec.stop()
        started = false
        true
      } catch (_: Throwable) {
        false
      } finally {
        try {
          inputSurface?.release()
        } catch (_: Throwable) {}
        if (started) {
          try {
            codec?.stop()
          } catch (_: Throwable) {}
        }
        try {
          codec?.release()
        } catch (_: Throwable) {}
      }
    }

    return tryConfigureAndStart(includeIFrameInterval = true) ||
      tryConfigureAndStart(includeIFrameInterval = false)
  }

  /**
   * Validates the requested (resolution, fps) using CapabilityProbe.
   * If the saved selection is no longer supported, falls back to the nearest supported frame rate
   * first, then the resolution, and shows the snackbar "Recording at X (device limit)".
   */
  suspend fun resolveSupportedEncoderSpec(
    context: Context,
    settingsRepository: SettingsRepository,
    requestedResolution: String,
    requestedFps: Int,
    orientationLabel: String,
    customBitrateMbps: Int?,
    notifyAndPersistOnFallback: Boolean = true,
  ): ResolvedEncoderSpec {
    val normRes = normalizeResolutionLabel(requestedResolution)
    val (reqW, reqH) = compute16AlignedDimensions(context, normRes, orientationLabel)

    val directMatch = probeCombination(settingsRepository, reqW, reqH, requestedFps)
    if (directMatch != null) {
      val bitrateBps =
        computeClampedBitrateBps(
          width = directMatch.width,
          height = directMatch.height,
          fps = requestedFps,
          mimeType = directMatch.mimeType,
          customBitrateMbps = customBitrateMbps,
        )
      return ResolvedEncoderSpec(
        resolutionLabel = normRes,
        width = directMatch.width,
        height = directMatch.height,
        fps = requestedFps,
        bitrateBps = bitrateBps,
        codecLabel = directMatch.codecLabel,
        mimeType = directMatch.mimeType,
        encoderName = findPreferredEncoderInfo(directMatch.mimeType)?.name,
        fellBackFromSaved = false,
      )
    }

    // Step 1: Fall back to nearest supported frame rate at the requested resolution
    val sortedFpsCandidates =
      ALL_FPS.filter { it != requestedFps }
        .sortedWith(compareBy<Int> { abs(it - requestedFps) }.thenBy { it })

    for (candidateFps in sortedFpsCandidates) {
      val match = probeCombination(settingsRepository, reqW, reqH, candidateFps)
      if (match != null) {
        val bitrateBps =
          computeClampedBitrateBps(
            width = match.width,
            height = match.height,
            fps = candidateFps,
            mimeType = match.mimeType,
            customBitrateMbps = customBitrateMbps,
          )
        if (notifyAndPersistOnFallback) {
          applyFallbackSelection(settingsRepository, normRes, candidateFps, match.codecLabel)
        }
        return ResolvedEncoderSpec(
          resolutionLabel = normRes,
          width = match.width,
          height = match.height,
          fps = candidateFps,
          bitrateBps = bitrateBps,
          codecLabel = match.codecLabel,
          mimeType = match.mimeType,
          encoderName = findPreferredEncoderInfo(match.mimeType)?.name,
          fellBackFromSaved = true,
        )
      }
    }

    // Step 2: Fall back to nearest supported resolution, checking nearest frame rates
    val reqShortPx = resolutionShortSideValue(context, normRes)
    val sortedResCandidates =
      ALL_RESOLUTIONS.filter { it != normRes }
        .sortedWith(
          compareBy<String> { abs(resolutionShortSideValue(context, it) - reqShortPx) }
            .thenBy { resolutionShortSideValue(context, it) }
        )
    val allFpsOrdered =
      listOf(requestedFps) +
        ALL_FPS.filter { it != requestedFps }
          .sortedWith(compareBy<Int> { abs(it - requestedFps) }.thenBy { it })

    for (candidateRes in sortedResCandidates) {
      val (candW, candH) = compute16AlignedDimensions(context, candidateRes, orientationLabel)
      for (candidateFps in allFpsOrdered) {
        val match = probeCombination(settingsRepository, candW, candH, candidateFps)
        if (match != null) {
          val bitrateBps =
            computeClampedBitrateBps(
              width = match.width,
              height = match.height,
              fps = candidateFps,
              mimeType = match.mimeType,
              customBitrateMbps = customBitrateMbps,
            )
          if (notifyAndPersistOnFallback) {
            applyFallbackSelection(
              settingsRepository,
              candidateRes,
              candidateFps,
              match.codecLabel,
            )
          }
          return ResolvedEncoderSpec(
            resolutionLabel = candidateRes,
            width = match.width,
            height = match.height,
            fps = candidateFps,
            bitrateBps = bitrateBps,
            codecLabel = match.codecLabel,
            mimeType = match.mimeType,
            encoderName = findPreferredEncoderInfo(match.mimeType)?.name,
            fellBackFromSaved = true,
          )
        }
      }
    }

    // Ultimate safe fallback if all probes failed
    val (fbW, fbH) = compute16AlignedDimensions(context, "720p", orientationLabel)
    val fbBitrate = computeClampedBitrateBps(fbW, fbH, 30, MIME_AVC, customBitrateMbps)
    if (notifyAndPersistOnFallback) {
      applyFallbackSelection(settingsRepository, "720p", 30, "H.264")
    }
    return ResolvedEncoderSpec(
      resolutionLabel = "720p",
      width = fbW,
      height = fbH,
      fps = 30,
      bitrateBps = fbBitrate,
      codecLabel = "H.264",
      mimeType = MIME_AVC,
      encoderName = findPreferredEncoderInfo(MIME_AVC)?.name,
      fellBackFromSaved = true,
    )
  }

  private suspend fun applyFallbackSelection(
    settingsRepository: SettingsRepository,
    fallbackResolution: String,
    fallbackFps: Int,
    codecLabel: String,
  ) {
    settingsRepository.updateVideoSettings { current ->
      current.copy(
        resolution = fallbackResolution,
        fps = fallbackFps,
        codec = if (codecLabel == "HEVC") "HEVC (H.265)" else "AVC (H.264)",
      )
    }
    RecordingStateHolder.showSnackbarMessage(
      "Recording at $fallbackResolution · $fallbackFps fps (device limit)"
    )
  }
}

class RealCapabilitiesProvider(
  context: Context,
  private val settingsRepository: SettingsRepository,
) : CapabilitiesProvider {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _resolutionProbeStates =
    MutableStateFlow<Map<String, OptionProbeState>>(
      CapabilityProbe.ALL_RESOLUTIONS.associateWith { OptionProbeState.Idle }
    )
  override val resolutionProbeStates: StateFlow<Map<String, OptionProbeState>> =
    _resolutionProbeStates.asStateFlow()

  private val _fpsProbeStates =
    MutableStateFlow<Map<Int, OptionProbeState>>(
      CapabilityProbe.ALL_FPS.associateWith { OptionProbeState.Idle }
    )
  override val fpsProbeStates: StateFlow<Map<Int, OptionProbeState>> =
    _fpsProbeStates.asStateFlow()

  private var resolutionProbeJob: Job? = null
  private var fpsProbeJob: Job? = null

  override fun getCapabilities(): DeviceCapabilities {
    val avcEncoder = CapabilityProbe.findPreferredEncoderInfo(MediaFormat.MIMETYPE_VIDEO_AVC)?.name
    val hevcEncoder =
      CapabilityProbe.findPreferredEncoderInfo(MediaFormat.MIMETYPE_VIDEO_HEVC)?.name
    val encoderSummary =
      listOfNotNull(avcEncoder, hevcEncoder).joinToString(" • ").ifBlank { "Hardware MediaCodec" }
    val modelSummary = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    return DeviceCapabilities(
      supportedResolutions = CapabilityProbe.ALL_RESOLUTIONS,
      supportedFps = CapabilityProbe.ALL_FPS,
      supportedBitratesMbps = listOf(4, 8, 12, 16, 20, 24, 32, 48),
      supportedCodecs = listOf("AVC (H.264)", "HEVC (H.265)"),
      supportedContainers = listOf("MP4"),
      supportedOrientations = listOf("Auto", "Portrait", "Landscape"),
      supportedAudioSources =
        listOf(
          "Device audio + Mic",
          "Device audio only",
          "Microphone only",
          "Unprocessed Mic",
          "Mute (No audio)",
        ),
      supportedAudioChannels = listOf("Stereo", "Mono"),
      supportsHardwareNoiseSuppression = true,
      deviceModelSummary = modelSummary,
      hardwareEncoderSummary = encoderSummary,
    )
  }

  override fun getDisplayRefreshRateHz(): Int {
    return CapabilityProbe.getDisplayRefreshRateHz(appContext)
  }

  override fun probeResolutionsForFps(fps: Int, orientation: String) {
    resolutionProbeJob?.cancel()
    resolutionProbeJob =
      scope.launch {
        _resolutionProbeStates.value =
          CapabilityProbe.ALL_RESOLUTIONS.associateWith { OptionProbeState.Probing }

        for (res in CapabilityProbe.ALL_RESOLUTIONS) {
          val (w, h) = CapabilityProbe.compute16AlignedDimensions(appContext, res, orientation)
          val supported = CapabilityProbe.probeCombination(settingsRepository, w, h, fps)
          val state = supported ?: OptionProbeState.Unsupported
          _resolutionProbeStates.update { current -> current + (res to state) }
        }

        // If the saved selection is no longer supported, fall back to nearest FPS first, then resolution
        val currentVideo = settingsRepository.settings.first().video
        val currentRes = CapabilityProbe.normalizeResolutionLabel(currentVideo.resolution)
        if (currentVideo.fps == fps && _resolutionProbeStates.value[currentRes] is OptionProbeState.Unsupported) {
          CapabilityProbe.resolveSupportedEncoderSpec(
            context = appContext,
            settingsRepository = settingsRepository,
            requestedResolution = currentRes,
            requestedFps = currentVideo.fps,
            orientationLabel = currentVideo.orientation,
            customBitrateMbps = currentVideo.bitrateMbps.takeIf { currentVideo.isCustomBitrate },
            notifyAndPersistOnFallback = true,
          )
        }
      }
  }

  override fun probeFpsForResolution(resolution: String, orientation: String) {
    val normRes = CapabilityProbe.normalizeResolutionLabel(resolution)
    fpsProbeJob?.cancel()
    fpsProbeJob =
      scope.launch {
        _fpsProbeStates.value =
          CapabilityProbe.ALL_FPS.associateWith { OptionProbeState.Probing }

        val (w, h) = CapabilityProbe.compute16AlignedDimensions(appContext, normRes, orientation)
        for (fps in CapabilityProbe.ALL_FPS) {
          val supported = CapabilityProbe.probeCombination(settingsRepository, w, h, fps)
          val state = supported ?: OptionProbeState.Unsupported
          _fpsProbeStates.update { current -> current + (fps to state) }
        }

        // If the saved selection is no longer supported, fall back to nearest FPS first, then resolution
        val currentVideo = settingsRepository.settings.first().video
        val currentRes = CapabilityProbe.normalizeResolutionLabel(currentVideo.resolution)
        if (
          currentRes == normRes &&
            _fpsProbeStates.value[currentVideo.fps] is OptionProbeState.Unsupported
        ) {
          CapabilityProbe.resolveSupportedEncoderSpec(
            context = appContext,
            settingsRepository = settingsRepository,
            requestedResolution = currentRes,
            requestedFps = currentVideo.fps,
            orientationLabel = currentVideo.orientation,
            customBitrateMbps = currentVideo.bitrateMbps.takeIf { currentVideo.isCustomBitrate },
            notifyAndPersistOnFallback = true,
          )
        }
      }
  }

  override suspend fun retestDeviceSupport() {
    resolutionProbeJob?.cancel()
    fpsProbeJob?.cancel()
    settingsRepository.clearProbeCache()
    _resolutionProbeStates.value =
      CapabilityProbe.ALL_RESOLUTIONS.associateWith { OptionProbeState.Idle }
    _fpsProbeStates.value =
      CapabilityProbe.ALL_FPS.associateWith { OptionProbeState.Idle }

    val currentVideo = settingsRepository.settings.first().video
    probeResolutionsForFps(currentVideo.fps, currentVideo.orientation)
    probeFpsForResolution(currentVideo.resolution, currentVideo.orientation)
  }
}
