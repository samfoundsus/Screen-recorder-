package com.example.data.capabilities

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
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
  val configureFps: Int,
  val bitrateBps: Int,
  val codecLabel: String,
  val mimeType: String,
  val encoderName: String?,
  val fellBackFromSaved: Boolean,
)

private data class CodecProbeSuccess(
  val encoderName: String,
  val width: Int,
  val height: Int,
  val configureFps: Int,
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

  /**
   * Returns the maximum hardware refresh rate supported by the device's built-in display
   * (e.g., 120 Hz on Infinix Note 30 5G), falling back to the current refresh rate.
   */
  fun getDisplayRefreshRateHz(context: Context): Int {
    return try {
      val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
      val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
      val maxModeHz =
        display?.supportedModes?.maxOfOrNull { it.refreshRate.roundToInt() } ?: 0
      val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      @Suppress("DEPRECATION")
      val currentHz = wm.defaultDisplay.refreshRate.roundToInt()
      max(maxModeHz, currentHz).coerceIn(30, 240)
    } catch (_: Throwable) {
      60
    }
  }

  fun findHardwareEncoders(mimeType: String): List<MediaCodecInfo> {
    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val matching =
      codecList.codecInfos.filter { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
      }
    val hardware =
      matching.filter { info ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          info.isHardwareAccelerated
        } else {
          !info.name.startsWith("OMX.google.", ignoreCase = true) &&
            !info.name.startsWith("c2.android.", ignoreCase = true)
        }
      }
    return hardware.ifEmpty { matching }
  }

  fun findPreferredEncoderInfo(mimeType: String): MediaCodecInfo? {
    return findHardwareEncoders(mimeType).firstOrNull()
  }

  private fun getMaxHardwareEncoderDimensions(): Pair<Int, Int> {
    var maxShort = 1088
    var maxLong = 1920
    val mimeTypes = listOf(MIME_AVC, MIME_HEVC)
    for (mime in mimeTypes) {
      for (info in findHardwareEncoders(mime)) {
        try {
          val vc = info.getCapabilitiesForType(mime)?.videoCapabilities ?: continue
          val wUpper = vc.supportedWidths.upper
          val hUpper = vc.supportedHeights.upper
          val s = min(wUpper, hUpper)
          val l = max(wUpper, hUpper)
          if (s > maxShort) maxShort = s
          if (l > maxLong) maxLong = l
        } catch (_: Throwable) {}
      }
    }
    return maxShort to maxLong
  }

  /**
   * Detects which resolutions in [ALL_RESOLUTIONS] are supported by the device's hardware encoders.
   */
  fun getDeviceSupportedResolutions(context: Context): List<String> {
    val (maxEncoderShort, maxEncoderLong) = getMaxHardwareEncoderDimensions()
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
    val realShort =
      min(metrics.widthPixels.coerceAtLeast(360), metrics.heightPixels.coerceAtLeast(640))

    return ALL_RESOLUTIONS.filter { res ->
      when (normalizeResolutionLabel(res)) {
        "480p" -> true
        "720p" -> maxEncoderShort >= 720 || realShort >= 720
        "1080p" -> maxEncoderShort >= 1072 || realShort >= 1080
        "1440p" -> maxEncoderShort >= 1440 && maxEncoderLong >= 2560
        "Native" -> true
        else -> true
      }
    }
  }

  /**
   * Detects the supported frame rates for a specific [resolutionLabel] on this device.
   * - At 1440p (2560x1440), uses the hardware encoder's actual 2K frame rate capability
   *   (e.g., 24, 30 fps on 2K@30fps encoders such as MediaTek Dimensity 6080 / Infinix Note 30 5G,
   *   or 60+ fps on 4K@60fps encoders).
   * - At 1080p, 720p, 480p, and Native (when short side <= 1080), supports up to the maximum of
   *   the encoder's 1080p frame rate capability and the display's hardware refresh rate
   *   (e.g., up to 120 fps on 120Hz devices like Infinix Note 30 5G).
   */
  fun getSupportedFpsForResolution(context: Context, resolutionLabel: String): List<Int> {
    val normalized = normalizeResolutionLabel(resolutionLabel)
    val maxDisplayHz = getDisplayRefreshRateHz(context)

    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
    val realShort =
      min(metrics.widthPixels.coerceAtLeast(360), metrics.heightPixels.coerceAtLeast(640))

    val isAbove1080p =
      normalized == "1440p" || (normalized == "Native" && realShort > 1200)

    if (isAbove1080p) {
      val (maxEncoderShort, maxEncoderLong) = getMaxHardwareEncoderDimensions()
      if (maxEncoderShort < 1440 || maxEncoderLong < 2560) {
        return emptyList()
      }
      var maxQhdFps = 0.0
      for (mime in listOf(MIME_AVC, MIME_HEVC)) {
        for (info in findHardwareEncoders(mime)) {
          try {
            val vc = info.getCapabilitiesForType(mime)?.videoCapabilities ?: continue
            val r1 =
              runCatching { vc.getSupportedFrameRatesFor(2560, 1440).upper }.getOrNull() ?: 0.0
            val r2 =
              runCatching { vc.getSupportedFrameRatesFor(1440, 2560).upper }.getOrNull() ?: 0.0
            maxQhdFps = max(maxQhdFps, max(r1, r2))
          } catch (_: Throwable) {}
        }
      }
      val effectiveMax1440pFps =
        when {
          maxQhdFps >= 115.0 -> min(120, max(maxDisplayHz, 60))
          maxQhdFps >= 88.0 -> min(90, max(maxDisplayHz, 60))
          maxQhdFps >= 58.0 -> 60
          maxQhdFps >= 47.0 -> 48
          else -> 30
        }
      return ALL_FPS.filter { it <= effectiveMax1440pFps }
    } else {
      val (tierLong, tierShort) =
        when (normalized) {
          "480p" -> 848 to 480
          "720p" -> 1280 to 720
          else -> 1920 to 1072
        }
      var maxTierEncoderFps = 0.0
      for (mime in listOf(MIME_AVC, MIME_HEVC)) {
        for (info in findHardwareEncoders(mime)) {
          try {
            val vc = info.getCapabilitiesForType(mime)?.videoCapabilities ?: continue
            val r1 =
              runCatching { vc.getSupportedFrameRatesFor(tierLong, tierShort).upper }.getOrNull()
                ?: 0.0
            val r2 =
              runCatching { vc.getSupportedFrameRatesFor(tierShort, tierLong).upper }.getOrNull()
                ?: 0.0
            maxTierEncoderFps = max(maxTierEncoderFps, max(r1, r2))
          } catch (_: Throwable) {}
        }
      }
      val effectiveMaxFps = max(maxDisplayHz, maxTierEncoderFps.roundToInt()).coerceIn(30, 120)
      return ALL_FPS.filter { it <= effectiveMaxFps }
    }
  }

  fun getMaxSupportedFpsForResolution(context: Context, resolutionLabel: String): Int {
    return getSupportedFpsForResolution(context, resolutionLabel).maxOrNull() ?: 30
  }

  /**
   * Computes the primary 16-aligned (width, height) for [resolutionLabel], honoring
   * [orientationLabel] ("Auto", "Portrait", "Landscape") and clamping the long side to the
   * hardware encoder's maximum supported dimension.
   */
  fun compute16AlignedDimensions(
    context: Context,
    resolutionLabel: String,
    orientationLabel: String = "Auto",
  ): Pair<Int, Int> {
    return candidate16AlignedDimensions(context, resolutionLabel, orientationLabel).first()
  }

  /**
   * Returns ordered candidate 16-aligned (width, height) pairs for [resolutionLabel] honoring
   * [orientationLabel] ("Auto", "Portrait", "Landscape").
   */
  fun candidate16AlignedDimensions(
    context: Context,
    resolutionLabel: String,
    orientationLabel: String = "Auto",
  ): List<Pair<Int, Int>> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
    val screenW = metrics.widthPixels.coerceAtLeast(360)
    val screenH = metrics.heightPixels.coerceAtLeast(640)

    val wantLandscape =
      when {
        orientationLabel.equals("Landscape", ignoreCase = true) -> true
        orientationLabel.equals("Portrait", ignoreCase = true) -> false
        else -> screenW > screenH
      }

    val realShort = min(screenW, screenH)
    val realLong = max(screenW, screenH)
    val aspect = realLong.toDouble() / realShort.toDouble()
    val (_, maxEncoderLong) = getMaxHardwareEncoderDimensions()

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

    val standard16by9Long =
      when (normalized) {
        "1440p" -> 2560
        "1080p" -> 1920
        "720p" -> 1280
        "480p" -> 848
        else -> min(realLong, maxEncoderLong)
      }

    val rawAspectLong =
      if (normalized == "Native") {
        realLong
      } else {
        (targetShort * aspect).roundToInt()
      }

    val maxAllowedLong =
      if (normalized == "1440p") {
        min(maxEncoderLong, 2560)
      } else {
        maxEncoderLong
      }
    val clampedAspectLong = min(rawAspectLong, maxAllowedLong)

    val alignedShort = roundDown16(targetShort)
    val candidatesLong =
      listOf(
          roundDown16(clampedAspectLong),
          roundDown16(standard16by9Long),
          roundDown16(min(realLong, maxEncoderLong)),
        )
        .distinct()
        .filter { it >= alignedShort }

    return candidatesLong.map { alignedLong ->
      if (wantLandscape) {
        alignedLong to alignedShort
      } else {
        alignedShort to alignedLong
      }
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

  /**
   * Computes a safe nominal KEY_FRAME_RATE for MediaCodec.configure() when an OEM's
   * media_codecs.xml blocks-per-second limit was authored for 16:9 1920x1080 rather than
   * tall 20.5:9 screens (e.g. 1072x2448 @ 120fps on Infinix Note 30 5G).
   */
  fun computeSafeConfigureFps(
    encoderInfo: MediaCodecInfo?,
    mimeType: String,
    width: Int,
    height: Int,
    requestedFps: Int,
  ): Int {
    return try {
      val vc = encoderInfo?.getCapabilitiesForType(mimeType)?.videoCapabilities
      if (vc == null) {
        requestedFps.coerceIn(15, 60)
      } else {
        val supportedUpper =
          runCatching { vc.getSupportedFrameRatesFor(width, height).upper.toInt() }.getOrNull()
            ?: runCatching { vc.getSupportedFrameRatesFor(height, width).upper.toInt() }.getOrNull()
            ?: vc.supportedFrameRates.upper.toInt()
        min(requestedFps, supportedUpper).coerceIn(15, requestedFps)
      }
    } catch (_: Throwable) {
      min(requestedFps, 60)
    }
  }

  /**
   * Probes a resolution and fps combination using the candidate 16-aligned dimensions for that
   * resolution, honoring [preferredCodecLabel] (defaulting to H.264 first, then HEVC) and
   * caching results in DataStore.
   */
  suspend fun probeResolutionAndFps(
    context: Context,
    settingsRepository: SettingsRepository,
    resolutionLabel: String,
    fps: Int,
    orientationLabel: String = "Auto",
    preferredCodecLabel: String = "AVC (H.264)",
  ): OptionProbeState.Supported? {
    val normRes = normalizeResolutionLabel(resolutionLabel)
    if (normRes !in getDeviceSupportedResolutions(context)) {
      return null
    }
    val allowedFps = getSupportedFpsForResolution(context, normRes)
    if (fps !in allowedFps) {
      return null
    }

    val candidates = candidate16AlignedDimensions(context, normRes, orientationLabel)
    for ((w, h) in candidates) {
      val result = probeCombination(settingsRepository, w, h, fps, preferredCodecLabel)
      if (result != null) {
        return result
      }
    }
    return null
  }

  /**
   * Probes (width, height, fps) by trying the preferred codec first (H.264 by default, or HEVC
   * if selected in Settings), then the alternate codec.
   * Uses DataStore cache keyed by codec + width + height + fps so each probe runs once per device.
   */
  suspend fun probeCombination(
    settingsRepository: SettingsRepository,
    width: Int,
    height: Int,
    fps: Int,
    preferredCodecLabel: String = "AVC (H.264)",
  ): OptionProbeState.Supported? {
    val alignedW = roundDown16(width)
    val alignedH = roundDown16(height)

    val preferHevc =
      preferredCodecLabel.contains("HEVC", ignoreCase = true) ||
        preferredCodecLabel.contains("265", ignoreCase = true)

    val codecOrder =
      if (preferHevc) {
        listOf("HEVC" to MIME_HEVC, "H.264" to MIME_AVC)
      } else {
        listOf("H.264" to MIME_AVC, "HEVC" to MIME_HEVC)
      }

    for ((codecKey, mimeType) in codecOrder) {
      val cached = settingsRepository.getCachedProbeResult(codecKey, alignedW, alignedH, fps)
      val supported =
        if (cached != null) {
          cached
        } else {
          val probed =
            hardwareProbeMutex.withLock {
              realProbeSingleCodec(mimeType, alignedW, alignedH, fps) != null
            }
          settingsRepository.saveCachedProbeResult(codecKey, alignedW, alignedH, fps, probed)
          probed
        }

      if (supported) {
        return OptionProbeState.Supported(
          codecLabel = codecKey,
          mimeType = mimeType,
          width = alignedW,
          height = alignedH,
        )
      }
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
  ): CodecProbeSuccess? {
    val encoders = findHardwareEncoders(mimeType)
    if (encoders.isEmpty()) return null

    for (encoderInfo in encoders) {
      val representativeBitrate = computeClampedBitrateBps(width, height, fps, mimeType, null)
      val safeNominalFps = computeSafeConfigureFps(encoderInfo, mimeType, width, height, fps)
      val candidateFpsValues = listOf(fps, safeNominalFps, min(fps, 60), min(fps, 30)).distinct()

      fun tryConfigureAndStart(configureFps: Int, includeIFrameInterval: Boolean): Boolean {
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
              setInteger(MediaFormat.KEY_FRAME_RATE, configureFps)
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

      for (candidateFps in candidateFpsValues) {
        if (
          tryConfigureAndStart(candidateFps, includeIFrameInterval = true) ||
            tryConfigureAndStart(candidateFps, includeIFrameInterval = false)
        ) {
          return CodecProbeSuccess(
            encoderName = encoderInfo.name,
            width = width,
            height = height,
            configureFps = candidateFps,
          )
        }
      }
    }
    return null
  }

  /**
   * Validates the requested (resolution, fps, codec, orientation, bitrate) using CapabilityProbe.
   * If the saved selection is no longer supported, falls back to the nearest supported frame rate
   * first, then the resolution, and shows the snackbar "Recording at X (device limit)".
   */
  suspend fun resolveSupportedEncoderSpec(
    context: Context,
    settingsRepository: SettingsRepository,
    requestedResolution: String,
    requestedFps: Int,
    orientationLabel: String,
    preferredCodecLabel: String = "AVC (H.264)",
    customBitrateMbps: Int?,
    notifyAndPersistOnFallback: Boolean = true,
  ): ResolvedEncoderSpec {
    val normRes = normalizeResolutionLabel(requestedResolution)

    val directMatch =
      probeResolutionAndFps(
        context = context,
        settingsRepository = settingsRepository,
        resolutionLabel = normRes,
        fps = requestedFps,
        orientationLabel = orientationLabel,
        preferredCodecLabel = preferredCodecLabel,
      )
    if (directMatch != null) {
      val encoderInfo = findPreferredEncoderInfo(directMatch.mimeType)
      val configureFps =
        computeSafeConfigureFps(
          encoderInfo = encoderInfo,
          mimeType = directMatch.mimeType,
          width = directMatch.width,
          height = directMatch.height,
          requestedFps = requestedFps,
        )
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
        configureFps = configureFps,
        bitrateBps = bitrateBps,
        codecLabel = directMatch.codecLabel,
        mimeType = directMatch.mimeType,
        encoderName = encoderInfo?.name,
        fellBackFromSaved = false,
      )
    }

    // Step 1: Fall back to nearest supported frame rate at the requested resolution
    val supportedFpsAtRes = getSupportedFpsForResolution(context, normRes)
    val sortedFpsCandidates =
      supportedFpsAtRes
        .filter { it != requestedFps }
        .sortedWith(compareBy<Int> { abs(it - requestedFps) }.thenByDescending { it })

    for (candidateFps in sortedFpsCandidates) {
      val match =
        probeResolutionAndFps(
          context = context,
          settingsRepository = settingsRepository,
          resolutionLabel = normRes,
          fps = candidateFps,
          orientationLabel = orientationLabel,
          preferredCodecLabel = preferredCodecLabel,
        )
      if (match != null) {
        val encoderInfo = findPreferredEncoderInfo(match.mimeType)
        val configureFps =
          computeSafeConfigureFps(
            encoderInfo = encoderInfo,
            mimeType = match.mimeType,
            width = match.width,
            height = match.height,
            requestedFps = candidateFps,
          )
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
          configureFps = configureFps,
          bitrateBps = bitrateBps,
          codecLabel = match.codecLabel,
          mimeType = match.mimeType,
          encoderName = encoderInfo?.name,
          fellBackFromSaved = true,
        )
      }
    }

    // Step 2: Fall back to nearest supported resolution, checking nearest frame rates
    val reqShortPx = resolutionShortSideValue(context, normRes)
    val supportedResolutions = getDeviceSupportedResolutions(context)
    val sortedResCandidates =
      supportedResolutions
        .filter { it != normRes }
        .sortedWith(
          compareBy<String> { abs(resolutionShortSideValue(context, it) - reqShortPx) }
            .thenBy { resolutionShortSideValue(context, it) }
        )

    for (candidateRes in sortedResCandidates) {
      val candidateFpsList =
        getSupportedFpsForResolution(context, candidateRes)
          .sortedWith(compareBy<Int> { abs(it - requestedFps) }.thenByDescending { it })
      for (candidateFps in candidateFpsList) {
        val match =
          probeResolutionAndFps(
            context = context,
            settingsRepository = settingsRepository,
            resolutionLabel = candidateRes,
            fps = candidateFps,
            orientationLabel = orientationLabel,
            preferredCodecLabel = preferredCodecLabel,
          )
        if (match != null) {
          val encoderInfo = findPreferredEncoderInfo(match.mimeType)
          val configureFps =
            computeSafeConfigureFps(
              encoderInfo = encoderInfo,
              mimeType = match.mimeType,
              width = match.width,
              height = match.height,
              requestedFps = candidateFps,
            )
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
            configureFps = configureFps,
            bitrateBps = bitrateBps,
            codecLabel = match.codecLabel,
            mimeType = match.mimeType,
            encoderName = encoderInfo?.name,
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
      configureFps = 30,
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
    val cleanCodec = if (codecLabel.startsWith("HEVC")) "HEVC (H.265)" else "AVC (H.264)"
    settingsRepository.updateVideoSettings { current ->
      current.copy(
        resolution = fallbackResolution,
        fps = fallbackFps,
        codec = cleanCodec,
      )
    }
    RecordingStateHolder.showSnackbarMessage(
      "Recording at $fallbackResolution · $fallbackFps fps (device limit)"
    )
  }

  fun isNoiseSuppressionSupported(): Boolean {
    return try {
      NoiseSuppressor.isAvailable() ||
        AcousticEchoCanceler.isAvailable() ||
        AutomaticGainControl.isAvailable()
    } catch (_: Throwable) {
      false
    }
  }
}

class RealCapabilitiesProvider(
  context: Context,
  private val settingsRepository: SettingsRepository,
) : CapabilitiesProvider {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val deviceResolutions: List<String> by lazy {
    CapabilityProbe.getDeviceSupportedResolutions(appContext)
  }

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
    val maxDeviceFps =
      deviceResolutions
        .flatMap { CapabilityProbe.getSupportedFpsForResolution(appContext, it) }
        .distinct()
        .sorted()
        .ifEmpty { listOf(24, 30, 60) }

    return DeviceCapabilities(
      supportedResolutions = deviceResolutions,
      supportedFps = maxDeviceFps,
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
      supportedAudioChannels = listOf("Mono", "Stereo"),
      supportsHardwareNoiseSuppression = CapabilityProbe.isNoiseSuppressionSupported(),
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
        val preferredCodec = settingsRepository.settings.first().video.codec
        _resolutionProbeStates.value =
          CapabilityProbe.ALL_RESOLUTIONS.associateWith { res ->
            if (res in deviceResolutions) OptionProbeState.Probing else OptionProbeState.Unsupported
          }

        for (res in deviceResolutions) {
          val maxResFps = CapabilityProbe.getMaxSupportedFpsForResolution(appContext, res)
          val probeFps = min(fps, maxResFps)
          val supported =
            CapabilityProbe.probeResolutionAndFps(
              context = appContext,
              settingsRepository = settingsRepository,
              resolutionLabel = res,
              fps = probeFps,
              orientationLabel = orientation,
              preferredCodecLabel = preferredCodec,
            )
          val state =
            if (supported != null) {
              if (probeFps < fps) {
                supported.copy(codecLabel = "${supported.codecLabel} · up to $maxResFps fps")
              } else {
                supported
              }
            } else {
              OptionProbeState.Unsupported
            }
          _resolutionProbeStates.update { current -> current + (res to state) }
        }
      }
  }

  override fun probeFpsForResolution(resolution: String, orientation: String) {
    val normRes = CapabilityProbe.normalizeResolutionLabel(resolution)
    fpsProbeJob?.cancel()
    fpsProbeJob =
      scope.launch {
        val preferredCodec = settingsRepository.settings.first().video.codec
        val supportedFpsForRes = CapabilityProbe.getSupportedFpsForResolution(appContext, normRes)
        _fpsProbeStates.value =
          CapabilityProbe.ALL_FPS.associateWith { fps ->
            if (fps in supportedFpsForRes) {
              OptionProbeState.Probing
            } else {
              OptionProbeState.Unsupported
            }
          }

        for (fps in supportedFpsForRes) {
          val supported =
            CapabilityProbe.probeResolutionAndFps(
              context = appContext,
              settingsRepository = settingsRepository,
              resolutionLabel = normRes,
              fps = fps,
              orientationLabel = orientation,
              preferredCodecLabel = preferredCodec,
            )
          val state = supported ?: OptionProbeState.Unsupported
          _fpsProbeStates.update { current -> current + (fps to state) }
        }

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
            preferredCodecLabel = currentVideo.codec,
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
