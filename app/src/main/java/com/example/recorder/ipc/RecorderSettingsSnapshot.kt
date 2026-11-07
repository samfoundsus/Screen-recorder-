package com.example.recorder.ipc

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.domain.model.AppSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Plain, Parcelable snapshot of all settings required by [com.example.recorder.RecorderService]
 * and [com.example.recorder.FloatingControlsController] in the `:recorder` process.
 * Eliminates DataStore and Room dependencies from the recorder process.
 */
data class RecorderSettingsSnapshot(
  val performanceMode: String = "Balanced",
  val resolution: String = "1080p",
  val fps: Int = 60,
  val bitrateMbps: Int = 12,
  val isCustomBitrate: Boolean = false,
  val codec: String = "AVC (H.264)",
  val container: String = "MP4",
  val orientation: String = "Auto",
  val audioSource: String = "Device audio + Mic",
  val micEnabled: Boolean = true,
  val noiseSuppression: Boolean = true,
  val channelMode: String = "Stereo",
  val countdownSeconds: Int = 0,
  val floatingControlsSize: String = "Medium",
  val floatingControlsOpacityPercent: Int = 85,
  val floatingControlsAutoHide: Boolean = true,
  val floatingControlsEdgeSnapping: Boolean = true,
  val keepEdgeHandleWhileRecording: Boolean = true,
  val hideControlsFromVideo: Boolean = false,
  val portraitXFrac: Float = 1.0f,
  val portraitYFrac: Float = 0.35f,
  val landscapeXFrac: Float = 1.0f,
  val landscapeYFrac: Float = 0.35f,
  val probedResolutions: List<String> = listOf("1080p", "720p", "480p"),
  val maxSupportedFps: Int = 60,
) : Parcelable {

  val isGamingMode: Boolean
    get() = performanceMode.equals("Gaming", ignoreCase = true)

  constructor(parcel: Parcel) : this(
    performanceMode = parcel.readString() ?: "Balanced",
    resolution = parcel.readString() ?: "1080p",
    fps = parcel.readInt(),
    bitrateMbps = parcel.readInt(),
    isCustomBitrate = parcel.readByte() != 0.toByte(),
    codec = parcel.readString() ?: "AVC (H.264)",
    container = parcel.readString() ?: "MP4",
    orientation = parcel.readString() ?: "Auto",
    audioSource = parcel.readString() ?: "Device audio + Mic",
    micEnabled = parcel.readByte() != 0.toByte(),
    noiseSuppression = parcel.readByte() != 0.toByte(),
    channelMode = parcel.readString() ?: "Stereo",
    countdownSeconds = parcel.readInt(),
    floatingControlsSize = parcel.readString() ?: "Medium",
    floatingControlsOpacityPercent = parcel.readInt(),
    floatingControlsAutoHide = parcel.readByte() != 0.toByte(),
    floatingControlsEdgeSnapping = parcel.readByte() != 0.toByte(),
    keepEdgeHandleWhileRecording = parcel.readByte() != 0.toByte(),
    hideControlsFromVideo = parcel.readByte() != 0.toByte(),
    portraitXFrac = parcel.readFloat(),
    portraitYFrac = parcel.readFloat(),
    landscapeXFrac = parcel.readFloat(),
    landscapeYFrac = parcel.readFloat(),
    probedResolutions = parcel.createStringArrayList() ?: listOf("1080p", "720p", "480p"),
    maxSupportedFps = parcel.readInt(),
  )

  override fun writeToParcel(parcel: Parcel, flags: Int) {
    parcel.writeString(performanceMode)
    parcel.writeString(resolution)
    parcel.writeInt(fps)
    parcel.writeInt(bitrateMbps)
    parcel.writeByte(if (isCustomBitrate) 1 else 0)
    parcel.writeString(codec)
    parcel.writeString(container)
    parcel.writeString(orientation)
    parcel.writeString(audioSource)
    parcel.writeByte(if (micEnabled) 1 else 0)
    parcel.writeByte(if (noiseSuppression) 1 else 0)
    parcel.writeString(channelMode)
    parcel.writeInt(countdownSeconds)
    parcel.writeString(floatingControlsSize)
    parcel.writeInt(floatingControlsOpacityPercent)
    parcel.writeByte(if (floatingControlsAutoHide) 1 else 0)
    parcel.writeByte(if (floatingControlsEdgeSnapping) 1 else 0)
    parcel.writeByte(if (keepEdgeHandleWhileRecording) 1 else 0)
    parcel.writeByte(if (hideControlsFromVideo) 1 else 0)
    parcel.writeFloat(portraitXFrac)
    parcel.writeFloat(portraitYFrac)
    parcel.writeFloat(landscapeXFrac)
    parcel.writeFloat(landscapeYFrac)
    parcel.writeStringList(probedResolutions)
    parcel.writeInt(maxSupportedFps)
  }

  override fun describeContents(): Int = 0

  fun toBundle(bundle: Bundle = Bundle()): Bundle {
    bundle.putParcelable(RecorderIpcProtocol.KEY_SNAPSHOT, this)
    return bundle
  }

  companion object {
    @JvmField
    val CREATOR =
      object : Parcelable.Creator<RecorderSettingsSnapshot> {
        override fun createFromParcel(parcel: Parcel): RecorderSettingsSnapshot =
          RecorderSettingsSnapshot(parcel)

        override fun newArray(size: Int): Array<RecorderSettingsSnapshot?> =
          arrayOfNulls(size)
      }

    fun fromAppSettings(
      settings: AppSettings,
      portraitXFrac: Float = 1.0f,
      portraitYFrac: Float = 0.35f,
      landscapeXFrac: Float = 1.0f,
      landscapeYFrac: Float = 0.35f,
      probedResolutions: List<String> = listOf("1080p", "720p", "480p"),
      maxSupportedFps: Int = 60,
    ): RecorderSettingsSnapshot {
      return RecorderSettingsSnapshot(
        performanceMode = settings.video.performanceMode,
        resolution = settings.video.resolution,
        fps = settings.video.fps,
        bitrateMbps = settings.video.bitrateMbps,
        isCustomBitrate = settings.video.isCustomBitrate,
        codec = settings.video.codec,
        container = settings.video.container,
        orientation = settings.video.orientation,
        audioSource = settings.audio.source,
        micEnabled = settings.audio.micEnabled,
        noiseSuppression = settings.audio.noiseSuppression,
        channelMode = settings.audio.channelMode,
        countdownSeconds = settings.general.countdownSeconds,
        floatingControlsSize = settings.floatingControls.size,
        floatingControlsOpacityPercent = settings.floatingControls.opacityPercent,
        floatingControlsAutoHide = settings.floatingControls.autoHide,
        floatingControlsEdgeSnapping = settings.floatingControls.edgeSnapping,
        keepEdgeHandleWhileRecording = settings.floatingControls.keepEdgeHandleWhileRecording,
        hideControlsFromVideo = settings.floatingControls.hideControlsFromVideo,
        portraitXFrac = portraitXFrac,
        portraitYFrac = portraitYFrac,
        landscapeXFrac = landscapeXFrac,
        landscapeYFrac = landscapeYFrac,
        probedResolutions = probedResolutions,
        maxSupportedFps = maxSupportedFps,
      )
    }

    /**
     * Resolves the effective snapshot for recording. In Gaming mode, applies performance
     * caps (resolution capped at 1080p, fps capped at 60, H.264 only, bitrate capped at 8 Mbps for
     * 1080p and 5 Mbps for <=720p using pixels x fps x 0.07, noise suppression off, countdown 0)
     * without modifying saved user preferences. Returns the snapshot and a snackbar message if any
     * cap altered the user's selected options.
     */
    fun createForRecording(
      settings: AppSettings,
      context: Context,
      portraitXFrac: Float = 1.0f,
      portraitYFrac: Float = 0.35f,
      landscapeXFrac: Float = 1.0f,
      landscapeYFrac: Float = 0.35f,
      probedResolutions: List<String> = listOf("1080p", "720p", "480p"),
      maxSupportedFps: Int = 60,
    ): Pair<RecorderSettingsSnapshot, String?> {
      val isGaming = settings.video.performanceMode.equals("Gaming", ignoreCase = true)
      if (!isGaming) {
        val snapshot =
          fromAppSettings(
            settings = settings,
            portraitXFrac = portraitXFrac,
            portraitYFrac = portraitYFrac,
            landscapeXFrac = landscapeXFrac,
            landscapeYFrac = landscapeYFrac,
            probedResolutions = probedResolutions,
            maxSupportedFps = maxSupportedFps,
          )
        return Pair(snapshot, null)
      }

      val origRes = settings.video.resolution
      val effectiveRes =
        when {
          origRes.startsWith("1440") || origRes.startsWith("4K") || origRes.startsWith("Native") -> "1080p"
          else -> origRes
        }

      val origFps = settings.video.fps
      val effectiveFps = if (origFps > 60) 60 else origFps

      val origCodec = settings.video.codec
      val effectiveCodec = "AVC (H.264)"

      val isLowerRes = effectiveRes.startsWith("720") || effectiveRes.startsWith("480")
      val maxBitrateCap = if (isLowerRes) 5 else 8

      val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION") wm?.defaultDisplay?.getRealMetrics(metrics)
      val screenW = metrics.widthPixels.coerceAtLeast(360)
      val screenH = metrics.heightPixels.coerceAtLeast(640)
      val aspect = max(screenW, screenH).toDouble() / min(screenW, screenH).toDouble()

      val shortSide =
        when {
          effectiveRes.startsWith("1080") -> 1080
          effectiveRes.startsWith("720") -> 720
          effectiveRes.startsWith("480") -> 480
          else -> 1080
        }
      val longSide = (shortSide * aspect).roundToInt()
      val w = ((shortSide / 16) * 16).coerceAtLeast(16)
      val h = ((longSide / 16) * 16).coerceAtLeast(16)

      val formulaBps = (w.toDouble() * h.toDouble() * effectiveFps.toDouble() * 0.07).toLong()
      val formulaMbps = ((formulaBps + 500_000L) / 1_000_000L).toInt().coerceAtLeast(1)

      val effectiveBitrate =
        if (settings.video.isCustomBitrate) {
          settings.video.bitrateMbps.coerceAtMost(maxBitrateCap).coerceAtLeast(1)
        } else {
          formulaMbps.coerceAtMost(maxBitrateCap).coerceAtLeast(1)
        }

      val origNoiseSuppression = settings.audio.noiseSuppression
      val effectiveNoiseSuppression = false

      val origCountdown = settings.general.countdownSeconds
      val effectiveCountdown = 0

      val resChanged = effectiveRes != origRes
      val fpsChanged = effectiveFps != origFps
      val codecChanged = !origCodec.contains("264")
      val bitrateChanged = effectiveBitrate != settings.video.bitrateMbps
      val nsChanged = origNoiseSuppression != effectiveNoiseSuppression
      val countdownChanged = origCountdown != effectiveCountdown

      val anyCapChanged =
        resChanged || fpsChanged || codecChanged || bitrateChanged || nsChanged || countdownChanged

      val snackbarMessage =
        if (anyCapChanged) {
          val changedParts = mutableListOf<String>()
          if (resChanged) changedParts.add(effectiveRes)
          if (fpsChanged) changedParts.add("${effectiveFps}fps")
          if (codecChanged) changedParts.add("H.264")
          if (bitrateChanged) changedParts.add("${effectiveBitrate} Mbps")
          val x =
            if (changedParts.isNotEmpty()) {
              changedParts.joinToString(" ")
            } else {
              "$effectiveRes ${effectiveFps}fps"
            }
          "Recording at $x (gaming mode)"
        } else {
          null
        }

      val snapshot =
        RecorderSettingsSnapshot(
          performanceMode = "Gaming",
          resolution = effectiveRes,
          fps = effectiveFps,
          bitrateMbps = effectiveBitrate,
          isCustomBitrate = settings.video.isCustomBitrate,
          codec = effectiveCodec,
          container = settings.video.container,
          orientation = settings.video.orientation,
          audioSource = settings.audio.source,
          micEnabled = settings.audio.micEnabled,
          noiseSuppression = effectiveNoiseSuppression,
          channelMode = settings.audio.channelMode,
          countdownSeconds = effectiveCountdown,
          floatingControlsSize = settings.floatingControls.size,
          floatingControlsOpacityPercent = settings.floatingControls.opacityPercent,
          floatingControlsAutoHide = settings.floatingControls.autoHide,
          floatingControlsEdgeSnapping = settings.floatingControls.edgeSnapping,
          keepEdgeHandleWhileRecording = settings.floatingControls.keepEdgeHandleWhileRecording,
          hideControlsFromVideo = settings.floatingControls.hideControlsFromVideo,
          portraitXFrac = portraitXFrac,
          portraitYFrac = portraitYFrac,
          landscapeXFrac = landscapeXFrac,
          landscapeYFrac = landscapeYFrac,
          probedResolutions = probedResolutions,
          maxSupportedFps = maxSupportedFps,
        )

      return Pair(snapshot, snackbarMessage)
    }

    fun fromBundle(bundle: Bundle?): RecorderSettingsSnapshot {
      if (bundle == null) return RecorderSettingsSnapshot()
      bundle.classLoader = RecorderSettingsSnapshot::class.java.classLoader
      return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        bundle.getParcelable(
          RecorderIpcProtocol.KEY_SNAPSHOT,
          RecorderSettingsSnapshot::class.java,
        ) ?: RecorderSettingsSnapshot()
      } else {
        @Suppress("DEPRECATION")
        bundle.getParcelable(RecorderIpcProtocol.KEY_SNAPSHOT) ?: RecorderSettingsSnapshot()
      }
    }
  }
}
