package com.example.data.repository

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.AppearanceSettings
import com.example.domain.model.AudioSettings
import com.example.domain.model.FloatingControlsSettings
import com.example.domain.model.MainDestination
import com.example.domain.model.NavDestinationConfig
import com.example.domain.model.VideoSettings
import com.example.domain.repository.SettingsRepository
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by
  preferencesDataStore(name = "pixel_recorder_settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

  private object Keys {
    // Video
    val VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
    val VIDEO_FPS = intPreferencesKey("video_fps")
    val VIDEO_BITRATE = intPreferencesKey("video_bitrate_mbps")
    val VIDEO_CUSTOM_BITRATE = booleanPreferencesKey("video_custom_bitrate")
    val VIDEO_CODEC = stringPreferencesKey("video_codec")
    val VIDEO_CONTAINER = stringPreferencesKey("video_container")
    val VIDEO_ORIENTATION = stringPreferencesKey("video_orientation")

    // Audio
    val AUDIO_SOURCE = stringPreferencesKey("audio_source")
    val AUDIO_MIC = booleanPreferencesKey("audio_mic_enabled")
    val AUDIO_NOISE_SUPPRESSION = booleanPreferencesKey("audio_noise_suppression")
    val AUDIO_CHANNEL_MODE = stringPreferencesKey("audio_channel_mode")

    // Floating controls
    val FLOATING_SIZE = stringPreferencesKey("floating_size")
    val FLOATING_OPACITY = intPreferencesKey("floating_opacity")
    val FLOATING_AUTO_HIDE = booleanPreferencesKey("floating_auto_hide")
    val FLOATING_EDGE_SNAPPING = booleanPreferencesKey("floating_edge_snapping")
    val FLOATING_GESTURES = stringPreferencesKey("floating_gestures")
    val FLOATING_INVISIBLE = booleanPreferencesKey("floating_invisible_mode")

    // Appearance
    val APPEARANCE_THEME = stringPreferencesKey("appearance_theme")
    val APPEARANCE_DYNAMIC_COLOR = booleanPreferencesKey("appearance_dynamic_color")
    val APPEARANCE_PURE_BLACK = booleanPreferencesKey("appearance_pure_black")
    val APPEARANCE_NAV_ORDER = stringPreferencesKey("appearance_nav_order")

    const val PROBE_PREFIX = "probe_"
  }

  override val settings: Flow<AppSettings> =
    context.dataStore.data
      .catch { exception ->
        if (exception is IOException) {
          emit(emptyPreferences())
        } else {
          throw exception
        }
      }
      .map { prefs -> mapPreferencesToSettings(prefs) }

  override suspend fun updateVideoSettings(transform: (VideoSettings) -> VideoSettings) {
    val current = settings.first().video
    val updated = transform(current)
    val normalizedRes = normalizeResolutionOption(updated.resolution)
    val effectiveBitrateMbps =
      if (updated.isCustomBitrate) {
        updated.bitrateMbps.coerceAtLeast(1)
      } else {
        computeDefaultBitrateMbps(normalizedRes, updated.fps)
      }
    context.dataStore.edit { prefs ->
      prefs[Keys.VIDEO_RESOLUTION] = normalizedRes
      prefs[Keys.VIDEO_FPS] = updated.fps
      prefs[Keys.VIDEO_BITRATE] = effectiveBitrateMbps
      prefs[Keys.VIDEO_CUSTOM_BITRATE] = updated.isCustomBitrate
      prefs[Keys.VIDEO_CODEC] = updated.codec
      prefs[Keys.VIDEO_CONTAINER] = updated.container
      prefs[Keys.VIDEO_ORIENTATION] = updated.orientation
    }
  }

  override suspend fun updateAudioSettings(transform: (AudioSettings) -> AudioSettings) {
    val current = settings.first().audio
    val updated = transform(current)
    context.dataStore.edit { prefs ->
      prefs[Keys.AUDIO_SOURCE] = updated.source
      prefs[Keys.AUDIO_MIC] = updated.micEnabled
      prefs[Keys.AUDIO_NOISE_SUPPRESSION] = updated.noiseSuppression
      prefs[Keys.AUDIO_CHANNEL_MODE] = updated.channelMode
    }
  }

  override suspend fun updateFloatingControlsSettings(
    transform: (FloatingControlsSettings) -> FloatingControlsSettings
  ) {
    val current = settings.first().floatingControls
    val updated = transform(current)
    context.dataStore.edit { prefs ->
      prefs[Keys.FLOATING_SIZE] = updated.size
      prefs[Keys.FLOATING_OPACITY] = updated.opacityPercent
      prefs[Keys.FLOATING_AUTO_HIDE] = updated.autoHide
      prefs[Keys.FLOATING_EDGE_SNAPPING] = updated.edgeSnapping
      prefs[Keys.FLOATING_GESTURES] = updated.gestureAction
      prefs[Keys.FLOATING_INVISIBLE] = updated.invisibleMode
    }
  }

  override suspend fun updateAppearanceSettings(
    transform: (AppearanceSettings) -> AppearanceSettings
  ) {
    val current = settings.first().appearance
    val updated = transform(current)
    context.dataStore.edit { prefs ->
      prefs[Keys.APPEARANCE_THEME] = updated.themeMode.name
      prefs[Keys.APPEARANCE_DYNAMIC_COLOR] = updated.dynamicColor
      prefs[Keys.APPEARANCE_PURE_BLACK] = updated.pureBlackDark
      prefs[Keys.APPEARANCE_NAV_ORDER] = serializeNavOrder(updated.navOrder)
    }
  }

  override suspend fun getCachedProbeResult(
    codec: String,
    width: Int,
    height: Int,
    fps: Int,
  ): Boolean? {
    val key = probeKey(codec, width, height, fps)
    val prefs =
      context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .first()
    return prefs[key]
  }

  override suspend fun saveCachedProbeResult(
    codec: String,
    width: Int,
    height: Int,
    fps: Int,
    supported: Boolean,
  ) {
    val key = probeKey(codec, width, height, fps)
    context.dataStore.edit { prefs ->
      prefs[key] = supported
    }
  }

  override suspend fun clearProbeCache() {
    context.dataStore.edit { prefs ->
      val keysToRemove =
        prefs.asMap().keys.filter { it.name.startsWith(Keys.PROBE_PREFIX) }
      keysToRemove.forEach { prefs.remove(it) }
    }
  }

  override suspend fun resetToDefaults() {
    context.dataStore.edit { prefs ->
      val nonProbeKeys =
        prefs.asMap().keys.filter { !it.name.startsWith(Keys.PROBE_PREFIX) }
      nonProbeKeys.forEach { prefs.remove(it) }
    }
  }

  private fun probeKey(codec: String, width: Int, height: Int, fps: Int): Preferences.Key<Boolean> {
    val cleanCodec = codec.replace(" ", "_").replace(".", "")
    return booleanPreferencesKey("${Keys.PROBE_PREFIX}${cleanCodec}_${width}_${height}_$fps")
  }

  private fun mapPreferencesToSettings(prefs: Preferences): AppSettings {
    val defaultVideo = VideoSettings()
    val defaultAudio = AudioSettings()
    val defaultFloating = FloatingControlsSettings()
    val defaultAppearance = AppearanceSettings()

    val resolution =
      normalizeResolutionOption(prefs[Keys.VIDEO_RESOLUTION] ?: defaultVideo.resolution)
    val fps = prefs[Keys.VIDEO_FPS] ?: defaultVideo.fps
    val isCustomBitrate = prefs[Keys.VIDEO_CUSTOM_BITRATE] ?: false
    val bitrateMbps =
      if (isCustomBitrate) {
        prefs[Keys.VIDEO_BITRATE] ?: computeDefaultBitrateMbps(resolution, fps)
      } else {
        computeDefaultBitrateMbps(resolution, fps)
      }

    val video =
      VideoSettings(
        resolution = resolution,
        fps = fps,
        bitrateMbps = bitrateMbps,
        isCustomBitrate = isCustomBitrate,
        codec = prefs[Keys.VIDEO_CODEC] ?: defaultVideo.codec,
        container = prefs[Keys.VIDEO_CONTAINER] ?: defaultVideo.container,
        orientation = prefs[Keys.VIDEO_ORIENTATION] ?: defaultVideo.orientation,
      )

    val audio =
      AudioSettings(
        source = prefs[Keys.AUDIO_SOURCE] ?: defaultAudio.source,
        micEnabled = prefs[Keys.AUDIO_MIC] ?: defaultAudio.micEnabled,
        noiseSuppression = prefs[Keys.AUDIO_NOISE_SUPPRESSION] ?: defaultAudio.noiseSuppression,
        channelMode = prefs[Keys.AUDIO_CHANNEL_MODE] ?: defaultAudio.channelMode,
      )

    val floating =
      FloatingControlsSettings(
        size = prefs[Keys.FLOATING_SIZE] ?: defaultFloating.size,
        opacityPercent = prefs[Keys.FLOATING_OPACITY] ?: defaultFloating.opacityPercent,
        autoHide = prefs[Keys.FLOATING_AUTO_HIDE] ?: defaultFloating.autoHide,
        edgeSnapping = prefs[Keys.FLOATING_EDGE_SNAPPING] ?: defaultFloating.edgeSnapping,
        gestureAction = prefs[Keys.FLOATING_GESTURES] ?: defaultFloating.gestureAction,
        invisibleMode = prefs[Keys.FLOATING_INVISIBLE] ?: defaultFloating.invisibleMode,
      )

    val themeMode =
      prefs[Keys.APPEARANCE_THEME]?.let { name ->
        AppThemeMode.entries.find { it.name == name }
      } ?: defaultAppearance.themeMode

    val navOrder =
      prefs[Keys.APPEARANCE_NAV_ORDER]?.let { deserializeNavOrder(it) }
        ?: defaultAppearance.navOrder

    val appearance =
      AppearanceSettings(
        themeMode = themeMode,
        dynamicColor = prefs[Keys.APPEARANCE_DYNAMIC_COLOR] ?: defaultAppearance.dynamicColor,
        pureBlackDark = prefs[Keys.APPEARANCE_PURE_BLACK] ?: defaultAppearance.pureBlackDark,
        navOrder = navOrder,
      )

    return AppSettings(
      video = video,
      audio = audio,
      floatingControls = floating,
      appearance = appearance,
    )
  }

  private fun normalizeResolutionOption(raw: String): String {
    return when {
      raw.startsWith("Native", ignoreCase = true) -> "Native"
      raw.startsWith("1440") -> "1440p"
      raw.startsWith("1080") -> "1080p"
      raw.startsWith("720") -> "720p"
      raw.startsWith("480") -> "480p"
      else -> "1080p"
    }
  }

  private fun computeDefaultBitrateMbps(resolution: String, fps: Int): Int {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
    val screenW = metrics.widthPixels.coerceAtLeast(360)
    val screenH = metrics.heightPixels.coerceAtLeast(640)
    val aspect = max(screenW, screenH).toDouble() / min(screenW, screenH).toDouble()

    val shortSide =
      when {
        resolution.startsWith("Native", ignoreCase = true) -> min(screenW, screenH)
        resolution.startsWith("1440") -> 1440
        resolution.startsWith("1080") -> 1080
        resolution.startsWith("720") -> 720
        resolution.startsWith("480") -> 480
        else -> 1080
      }
    val longSide =
      if (resolution.startsWith("Native", ignoreCase = true)) {
        max(screenW, screenH)
      } else {
        (shortSide * aspect).roundToInt()
      }
    val w = ((shortSide / 16) * 16).coerceAtLeast(16)
    val h = ((longSide / 16) * 16).coerceAtLeast(16)
    val bps = (w.toDouble() * h.toDouble() * fps.toDouble() * 0.1).toLong()
    return ((bps + 500_000L) / 1_000_000L).toInt().coerceIn(1, 64)
  }

  private fun serializeNavOrder(order: List<NavDestinationConfig>): String {
    return order.joinToString(",") { "${it.destination.id}:${it.enabled}" }
  }

  private fun deserializeNavOrder(raw: String): List<NavDestinationConfig> {
    if (raw.isBlank()) return AppearanceSettings().navOrder
    val parsed =
      raw.split(",").mapNotNull { token ->
        val parts = token.split(":")
        if (parts.size != 2) return@mapNotNull null
        val dest = MainDestination.fromId(parts[0]) ?: return@mapNotNull null
        val enabled = parts[1].toBooleanStrictOrNull() ?: true
        NavDestinationConfig(dest, enabled)
      }
    val missing =
      MainDestination.entries.filter { dest -> parsed.none { it.destination == dest } }.map {
        NavDestinationConfig(it, enabled = true)
      }
    val combined = (parsed + missing).distinctBy { it.destination }
    return if (combined.none { it.enabled }) {
      combined.mapIndexed { index, item -> if (index == 0) item.copy(enabled = true) else item }
    } else {
      combined
    }
  }
}
