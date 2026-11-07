package com.example.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.domain.model.AppThemeMode
import com.example.domain.model.MainDestination
import com.example.domain.model.NavDestinationConfig
import com.example.domain.model.OptionProbeState
import com.example.recorder.MemoryDiagnostics
import com.example.recorder.StopLogManager
import com.example.recorder.StopRecord
import com.example.ui.components.pixelGroupedItemShape
import java.util.Locale
import kotlin.math.roundToInt

private enum class ActiveSettingsDialog {
  NONE,
  GENERAL_COUNTDOWN,
  VIDEO_PERFORMANCE_MODE,
  VIDEO_RESOLUTION,
  VIDEO_FPS,
  VIDEO_BITRATE,
  VIDEO_CODEC,
  VIDEO_CONTAINER,
  VIDEO_ORIENTATION,
  AUDIO_SOURCE,
  AUDIO_CHANNELS,
  FLOATING_SIZE,
  FLOATING_OPACITY,
  APPEARANCE_NAV_ORDER,
  APPEARANCE_THEME,
  ABOUT_MEMORY_DEBUG,
  ABOUT_STOP_LOG,
  ABOUT_RESET,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  uiState: SettingsUiState,
  onOpenResolutionPicker: () -> Unit,
  onOpenFpsPicker: () -> Unit,
  onRetestDeviceSupport: () -> Unit,
  onRefreshMemoryDiagnostics: () -> Unit,
  onUpdateShowInQuickSettings: (Boolean) -> Unit,
  onUpdateCountdownSeconds: (Int) -> Unit,
  onUpdatePerformanceMode: (String) -> Unit = {},
  onUpdateResolution: (String) -> Unit,
  onUpdateFps: (Int) -> Unit,
  onUpdateBitrate: (Int) -> Unit,
  onUpdateCodec: (String) -> Unit,
  onUpdateContainer: (String) -> Unit,
  onUpdateOrientation: (String) -> Unit,
  onUpdateAudioSource: (String) -> Unit,
  onUpdateMicEnabled: (Boolean) -> Unit,
  onUpdateNoiseSuppression: (Boolean) -> Unit,
  onUpdateAudioChannels: (String) -> Unit,
  onUpdateFloatingSize: (String) -> Unit,
  onUpdateFloatingOpacity: (Int) -> Unit,
  onUpdateFloatingAutoHide: (Boolean) -> Unit,
  onUpdateFloatingEdgeSnapping: (Boolean) -> Unit,
  onUpdateFloatingKeepEdgeHandleWhileRecording: (Boolean) -> Unit,
  onUpdateFloatingHideControlsFromVideo: (Boolean) -> Unit,
  onUpdateThemeMode: (AppThemeMode, Boolean) -> Unit,
  onUpdatePureBlackDark: (Boolean) -> Unit,
  onMoveNavDestination: (Int, Int) -> Unit,
  onToggleNavDestination: (MainDestination) -> Unit,
  onResetAllSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  var activeDialog by rememberSaveable { mutableStateOf(ActiveSettingsDialog.NONE) }

  LaunchedEffect(Unit) {
    if (BuildConfig.DEBUG && MemoryDiagnostics.IS_ENABLED) {
      onRefreshMemoryDiagnostics()
    }
  }

  val general = uiState.settings.general
  val video = uiState.settings.video
  val audio = uiState.settings.audio
  val floating = uiState.settings.floatingControls
  val appearance = uiState.settings.appearance
  val capabilities = uiState.capabilities
  val memorySnapshot = uiState.memorySnapshot

  Scaffold(
    modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      LargeTopAppBar(
        title = { Text(text = stringResource(R.string.nav_settings)) },
        scrollBehavior = scrollBehavior,
        colors =
          TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
          ),
      )
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      LazyColumn(
        modifier =
          Modifier.widthIn(max = 640.dp)
            .align(Alignment.TopCenter)
            .fillMaxSize()
            .testTag("settings_lazy_column"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        // SECTION 0: GENERAL
        item(key = "header_general") {
          PixelSectionHeader(text = stringResource(R.string.settings_section_general))
        }
        item(key = "general_quick_settings") {
          PixelSettingsSwitchRow(
            title = "Show in Quick Settings",
            summary =
              "Swipe down twice from the top of the screen, tap Edit, and drag Screen Recorder to your active tiles",
            checked = general.showInQuickSettings,
            onCheckedChange = onUpdateShowInQuickSettings,
            shape = pixelGroupedItemShape(index = 0, totalCount = 2),
            testTag = "settings_general_quick_settings",
          )
        }
        item(key = "general_countdown") {
          val countdownSummary =
            if (general.countdownSeconds > 0) {
              "${general.countdownSeconds} seconds (3-2-1 countdown before recording)"
            } else {
              "Off (start recording immediately)"
            }
          PixelSettingsRow(
            title = "Countdown",
            summary = countdownSummary,
            shape = pixelGroupedItemShape(index = 1, totalCount = 2),
            onClick = { activeDialog = ActiveSettingsDialog.GENERAL_COUNTDOWN },
            testTag = "settings_general_countdown",
          )
        }

        // SECTION 1: VIDEO
        item(key = "header_video") {
          PixelSectionHeader(text = stringResource(R.string.settings_section_video))
        }
        item(key = "video_performance_mode") {
          PixelSettingsRow(
            title = "Performance mode",
            summary =
              if (video.performanceMode == "Gaming") {
                "Gaming · Lower load on the device so games run smoother while recording."
              } else {
                "Balanced"
              },
            shape = pixelGroupedItemShape(index = 0, totalCount = 8),
            onClick = { activeDialog = ActiveSettingsDialog.VIDEO_PERFORMANCE_MODE },
            testTag = "settings_video_performance_mode",
          )
        }
        item(key = "video_resolution") {
          PixelSettingsRow(
            title = "Resolution",
            summary = video.resolution,
            shape = pixelGroupedItemShape(index = 1, totalCount = 8),
            onClick = {
              onOpenResolutionPicker()
              activeDialog = ActiveSettingsDialog.VIDEO_RESOLUTION
            },
            testTag = "settings_video_resolution",
          )
        }
        item(key = "video_fps") {
          PixelSettingsRow(
            title = "Frame rate",
            summary = "${video.fps} FPS",
            shape = pixelGroupedItemShape(index = 2, totalCount = 8),
            onClick = {
              onOpenFpsPicker()
              activeDialog = ActiveSettingsDialog.VIDEO_FPS
            },
            testTag = "settings_video_fps",
          )
        }
        item(key = "video_bitrate") {
          PixelSettingsRow(
            title = "Bitrate",
            summary =
              if (video.isCustomBitrate) {
                "${video.bitrateMbps} Mbps (custom)"
              } else {
                "${video.bitrateMbps} Mbps (auto-scaled)"
              },
            shape = pixelGroupedItemShape(index = 3, totalCount = 8),
            onClick = { activeDialog = ActiveSettingsDialog.VIDEO_BITRATE },
            testTag = "settings_video_bitrate",
          )
        }
        item(key = "video_codec") {
          PixelSettingsRow(
            title = "Codec",
            summary = video.codec,
            shape = pixelGroupedItemShape(index = 4, totalCount = 8),
            onClick = { activeDialog = ActiveSettingsDialog.VIDEO_CODEC },
            testTag = "settings_video_codec",
          )
        }
        item(key = "video_container") {
          PixelSettingsRow(
            title = "Container",
            summary = video.container,
            shape = pixelGroupedItemShape(index = 5, totalCount = 8),
            onClick = { activeDialog = ActiveSettingsDialog.VIDEO_CONTAINER },
            testTag = "settings_video_container",
          )
        }
        item(key = "video_orientation") {
          PixelSettingsRow(
            title = "Orientation",
            summary = video.orientation,
            shape = pixelGroupedItemShape(index = 6, totalCount = 8),
            onClick = { activeDialog = ActiveSettingsDialog.VIDEO_ORIENTATION },
            testTag = "settings_video_orientation",
          )
        }
        item(key = "video_retest_support") {
          PixelSettingsRow(
            title = "Re-test device support",
            summary = "Clear cached encoder probe results and test again",
            shape = pixelGroupedItemShape(index = 7, totalCount = 8),
            onClick = onRetestDeviceSupport,
            testTag = "settings_video_retest_support",
          )
        }

        // SECTION 2: AUDIO
        item(key = "header_audio") {
          PixelSectionHeader(text = stringResource(R.string.settings_section_audio))
        }
        item(key = "audio_source") {
          PixelSettingsRow(
            title = "Audio source",
            summary = audio.source,
            shape = pixelGroupedItemShape(index = 0, totalCount = 4),
            onClick = { activeDialog = ActiveSettingsDialog.AUDIO_SOURCE },
            testTag = "settings_audio_source",
          )
        }
        item(key = "audio_mic") {
          PixelSettingsSwitchRow(
            title = "Microphone",
            summary =
              if (audio.micEnabled) {
                "Record voice audio from built-in microphone"
              } else {
                "Microphone muted during screen capture"
              },
            checked = audio.micEnabled,
            onCheckedChange = onUpdateMicEnabled,
            shape = pixelGroupedItemShape(index = 1, totalCount = 4),
            testTag = "settings_audio_mic",
          )
        }
        item(key = "audio_noise_suppression") {
          val isDeviceAudioOnly =
            audio.source.equals("Device audio only", ignoreCase = true) ||
              (audio.source.equals("Device audio + Mic", ignoreCase = true) && !audio.micEnabled)
          val isMicActive =
            !isDeviceAudioOnly &&
              !audio.source.equals("Mute (No audio)", ignoreCase = true) &&
              (audio.micEnabled || audio.source.contains("Mic", ignoreCase = true))
          val nsSupported = capabilities.supportsHardwareNoiseSuppression
          val nsSwitchEnabled = nsSupported && isMicActive
          val nsChecked = if (!nsSupported) false else audio.noiseSuppression
          val nsSummary =
            when {
              !nsSupported -> "Not supported on this device"
              !isMicActive -> "Only applies to the microphone."
              else -> "Reduces background noise in the microphone track."
            }

          PixelSettingsSwitchRow(
            title = "Noise suppression",
            summary = nsSummary,
            checked = nsChecked,
            onCheckedChange = onUpdateNoiseSuppression,
            shape = pixelGroupedItemShape(index = 2, totalCount = 4),
            testTag = "settings_audio_noise_suppression",
            enabled = nsSwitchEnabled,
          )
        }
        item(key = "audio_channels") {
          PixelSettingsRow(
            title = "Channels",
            summary = audio.channelMode,
            shape = pixelGroupedItemShape(index = 3, totalCount = 4),
            onClick = { activeDialog = ActiveSettingsDialog.AUDIO_CHANNELS },
            testTag = "settings_audio_channels",
          )
        }

        // SECTION 3: FLOATING CONTROLS
        item(key = "header_floating") {
          PixelSectionHeader(text = stringResource(R.string.settings_section_floating))
        }
        item(key = "floating_size") {
          PixelSettingsRow(
            title = "Size",
            summary = floating.size,
            shape = pixelGroupedItemShape(index = 0, totalCount = 6),
            onClick = { activeDialog = ActiveSettingsDialog.FLOATING_SIZE },
            testTag = "settings_floating_size",
          )
        }
        item(key = "floating_opacity") {
          PixelSettingsRow(
            title = "Opacity",
            summary = "${floating.opacityPercent}% idle surface opacity",
            shape = pixelGroupedItemShape(index = 1, totalCount = 6),
            onClick = { activeDialog = ActiveSettingsDialog.FLOATING_OPACITY },
            testTag = "settings_floating_opacity",
          )
        }
        item(key = "floating_auto_hide") {
          PixelSettingsSwitchRow(
            title = "Auto-hide",
            summary =
              if (floating.autoHide) {
                "Shrink to a thin edge handle after 3 seconds idle"
              } else {
                "Keep floating controls expanded while idle"
              },
            checked = floating.autoHide,
            onCheckedChange = onUpdateFloatingAutoHide,
            shape = pixelGroupedItemShape(index = 2, totalCount = 6),
            testTag = "settings_floating_auto_hide",
          )
        }
        item(key = "floating_edge_snapping") {
          PixelSettingsSwitchRow(
            title = "Edge snapping",
            summary =
              if (floating.edgeSnapping) {
                "Snap controls to nearest screen edge on release"
              } else {
                "Free-floating placement anywhere on screen"
              },
            checked = floating.edgeSnapping,
            onCheckedChange = onUpdateFloatingEdgeSnapping,
            shape = pixelGroupedItemShape(index = 3, totalCount = 6),
            testTag = "settings_floating_edge_snapping",
          )
        }
        item(key = "floating_keep_edge_handle_while_recording") {
          PixelSettingsSwitchRow(
            title = "Keep edge handle while recording",
            summary =
              "A thin handle stays on the screen edge so you can pause or stop. It may appear as a thin dark line in the video on some devices. Turn off to remove the controls completely during recording.",
            checked = floating.keepEdgeHandleWhileRecording,
            onCheckedChange = onUpdateFloatingKeepEdgeHandleWhileRecording,
            shape = pixelGroupedItemShape(index = 4, totalCount = 6),
            testTag = "settings_floating_keep_edge_handle_while_recording",
          )
        }
        item(key = "floating_hide_controls_from_video") {
          PixelSettingsSwitchRow(
            title = "Hide controls from video (experimental)",
            summary =
              "Tries to keep the controls out of the recorded video. They may look like a black box on screen in some previews, and results vary by device.",
            checked = floating.hideControlsFromVideo,
            onCheckedChange = onUpdateFloatingHideControlsFromVideo,
            shape = pixelGroupedItemShape(index = 5, totalCount = 6),
            testTag = "settings_floating_hide_controls_from_video",
          )
        }

        // SECTION 4: APPEARANCE
        item(key = "header_appearance") {
          PixelSectionHeader(text = stringResource(R.string.settings_section_appearance))
        }
        item(key = "appearance_nav_order") {
          val activeLabels =
            appearance.navOrder.filter { it.enabled }.joinToString(" • ") { it.destination.label }
          PixelSettingsRow(
            title = "Navigation order",
            summary = activeLabels,
            shape = pixelGroupedItemShape(index = 0, totalCount = 3),
            onClick = { activeDialog = ActiveSettingsDialog.APPEARANCE_NAV_ORDER },
            testTag = "settings_appearance_nav_order",
          )
        }
        item(key = "appearance_theme") {
          val dynamicSummary =
            if (appearance.dynamicColor) "Material You dynamic color" else "Standard palette"
          PixelSettingsRow(
            title = "Theme",
            summary = "${appearance.themeMode.label} ($dynamicSummary)",
            shape = pixelGroupedItemShape(index = 1, totalCount = 3),
            onClick = { activeDialog = ActiveSettingsDialog.APPEARANCE_THEME },
            testTag = "settings_appearance_theme",
          )
        }
        item(key = "appearance_pure_black") {
          PixelSettingsSwitchRow(
            title = "Pure black",
            summary =
              if (appearance.pureBlackDark) {
                "AMOLED black surface active in dark theme"
              } else {
                "Use dynamic tinted dark surface in dark theme"
              },
            checked = appearance.pureBlackDark,
            onCheckedChange = onUpdatePureBlackDark,
            shape = pixelGroupedItemShape(index = 2, totalCount = 3),
            testTag = "settings_appearance_pure_black",
          )
        }

        // SECTION 5: ABOUT
        val hasMemoryDebug = BuildConfig.DEBUG && MemoryDiagnostics.IS_ENABLED
        val hasStopLog = BuildConfig.DEBUG
        val aboutTotalCount = 2 + (if (hasMemoryDebug) 1 else 0) + (if (hasStopLog) 1 else 0) + 1
        var nextAboutIndex = 0

        item(key = "header_about") {
          PixelSectionHeader(text = stringResource(R.string.settings_section_about))
        }
        item(key = "about_version") {
          val currentIndex = nextAboutIndex++
          PixelSettingsRow(
            title = "Version",
            summary = "${BuildConfig.VERSION_NAME} (Pixel UI Architecture)",
            shape = pixelGroupedItemShape(index = currentIndex, totalCount = aboutTotalCount),
            onClick = {},
            testTag = "settings_about_version",
          )
        }
        item(key = "about_hardware") {
          val currentIndex = nextAboutIndex++
          PixelSettingsRow(
            title = "Device capability profile",
            summary = "${capabilities.deviceModelSummary} (${capabilities.hardwareEncoderSummary})",
            shape = pixelGroupedItemShape(index = currentIndex, totalCount = aboutTotalCount),
            onClick = {},
            testTag = "settings_about_hardware",
          )
        }
        if (hasMemoryDebug) {
          item(key = "about_memory_debug") {
            val currentIndex = nextAboutIndex++
            PixelSettingsRow(
              title = "Memory (debug)",
              summary = memorySnapshot.summaryText,
              shape = pixelGroupedItemShape(index = currentIndex, totalCount = aboutTotalCount),
              onClick = {
                onRefreshMemoryDiagnostics()
                activeDialog = ActiveSettingsDialog.ABOUT_MEMORY_DEBUG
              },
              testTag = "settings_about_memory_debug",
            )
          }
        }
        if (hasStopLog) {
          item(key = "about_stop_log") {
            val currentIndex = nextAboutIndex++
            PixelSettingsRow(
              title = "Stop log",
              summary = "View reasons and diagnostics for the last 10 stops",
              shape = pixelGroupedItemShape(index = currentIndex, totalCount = aboutTotalCount),
              onClick = {
                activeDialog = ActiveSettingsDialog.ABOUT_STOP_LOG
              },
              testTag = "settings_about_stop_log",
            )
          }
        }
        item(key = "about_reset") {
          val currentIndex = nextAboutIndex++
          PixelSettingsRow(
            title = "Reset preferences",
            summary = "Restore default video, audio, and navigation settings",
            shape = pixelGroupedItemShape(index = currentIndex, totalCount = aboutTotalCount),
            onClick = { activeDialog = ActiveSettingsDialog.ABOUT_RESET },
            testTag = "settings_about_reset",
          )
        }

        item(key = "bottom_spacer") {
          Spacer(modifier = Modifier.height(28.dp))
        }
      }
    }
  }

  // DIALOGS
  when (activeDialog) {
    ActiveSettingsDialog.NONE -> Unit
    ActiveSettingsDialog.GENERAL_COUNTDOWN -> {
      val selectedLabel = if (general.countdownSeconds > 0) "3 seconds" else "Off"
      SingleChoiceSettingsDialog(
        title = "Countdown before recording",
        options = listOf("Off", "3 seconds"),
        selectedOption = selectedLabel,
        onSelect = { choice ->
          onUpdateCountdownSeconds(if (choice.startsWith("3")) 3 else 0)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.VIDEO_PERFORMANCE_MODE -> {
      SingleChoiceSettingsDialog(
        title = "Performance mode",
        options = listOf("Balanced", "Gaming"),
        selectedOption = video.performanceMode,
        optionSummaries =
          mapOf("Gaming" to "Lower load on the device so games run smoother while recording."),
        onSelect = { choice ->
          onUpdatePerformanceMode(choice)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.VIDEO_RESOLUTION -> {
      LaunchedEffect(Unit) {
        onOpenResolutionPicker()
      }
      ProbedResolutionSettingsDialog(
        options = capabilities.supportedResolutions,
        selectedOption = video.resolution,
        probeStates = uiState.resolutionProbeStates,
        onSelect = {
          onUpdateResolution(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.VIDEO_FPS -> {
      LaunchedEffect(Unit) {
        onOpenFpsPicker()
      }
      ProbedFpsSettingsDialog(
        options = capabilities.supportedFps,
        selectedFps = video.fps,
        probeStates = uiState.fpsProbeStates,
        displayRefreshRateHz = uiState.displayRefreshRateHz,
        onSelect = { fps ->
          onUpdateFps(fps)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.VIDEO_BITRATE -> {
      BitrateSliderDialog(
        currentBitrate = video.bitrateMbps,
        presets = capabilities.supportedBitratesMbps,
        onConfirm = {
          onUpdateBitrate(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.VIDEO_CODEC -> {
      SingleChoiceSettingsDialog(
        title = "Codec",
        options = capabilities.supportedCodecs,
        selectedOption = video.codec,
        onSelect = {
          onUpdateCodec(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.VIDEO_CONTAINER -> {
      SingleChoiceSettingsDialog(
        title = "Container",
        options = capabilities.supportedContainers,
        selectedOption = video.container,
        onSelect = {
          onUpdateContainer(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.VIDEO_ORIENTATION -> {
      SingleChoiceSettingsDialog(
        title = "Orientation",
        options = capabilities.supportedOrientations,
        selectedOption = video.orientation,
        onSelect = {
          onUpdateOrientation(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.AUDIO_SOURCE -> {
      SingleChoiceSettingsDialog(
        title = "Audio source",
        options = capabilities.supportedAudioSources,
        selectedOption = audio.source,
        onSelect = {
          onUpdateAudioSource(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.AUDIO_CHANNELS -> {
      SingleChoiceSettingsDialog(
        title = "Channels",
        options = listOf("Mono", "Stereo"),
        selectedOption = audio.channelMode,
        onSelect = {
          onUpdateAudioChannels(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.FLOATING_SIZE -> {
      SingleChoiceSettingsDialog(
        title = "Floating control size",
        options = listOf("Small", "Medium", "Large"),
        selectedOption = floating.size,
        onSelect = {
          onUpdateFloatingSize(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.FLOATING_OPACITY -> {
      OpacitySliderDialog(
        currentOpacity = floating.opacityPercent,
        onConfirm = {
          onUpdateFloatingOpacity(it)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.APPEARANCE_NAV_ORDER -> {
      NavigationOrderDialog(
        navOrder = appearance.navOrder,
        onMove = onMoveNavDestination,
        onToggle = onToggleNavDestination,
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.APPEARANCE_THEME -> {
      ThemePickerDialog(
        currentMode = appearance.themeMode,
        currentDynamicColor = appearance.dynamicColor,
        onConfirm = { mode, dynamic ->
          onUpdateThemeMode(mode, dynamic)
          activeDialog = ActiveSettingsDialog.NONE
        },
        onDismiss = { activeDialog = ActiveSettingsDialog.NONE },
      )
    }
    ActiveSettingsDialog.ABOUT_MEMORY_DEBUG -> {
      if (BuildConfig.DEBUG && MemoryDiagnostics.IS_ENABLED) {
        val nativeMb =
          String.format(Locale.US, "%.1f MB", memorySnapshot.nativeHeapAllocatedKb / 1024.0)
        val javaMb =
          String.format(Locale.US, "%.1f MB", memorySnapshot.javaHeapUsedKb / 1024.0)
        AlertDialog(
          onDismissRequest = { activeDialog = ActiveSettingsDialog.NONE },
          title = { Text("Memory (debug)") },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text =
                  "Recorder process PSS: ${memorySnapshot.recorderPssMbText}" +
                    if (memorySnapshot.recorderPssKb > 0) " (${memorySnapshot.recorderPssKb} KB)" else "",
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = "UI process PSS: ${memorySnapshot.uiPssMbText} (${memorySnapshot.uiPssKb} KB)",
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text =
                  "Peak recorder PSS: ${memorySnapshot.peakLastRecordingPssMbText}" +
                    if (memorySnapshot.peakLastRecordingPssKb > 0) {
                      " (${memorySnapshot.peakLastRecordingPssKb} KB)"
                    } else {
                      ""
                    },
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text =
                  "Native heap allocated: $nativeMb (${memorySnapshot.nativeHeapAllocatedKb} KB / ${memorySnapshot.nativeHeapSizeKb} KB)",
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = "Java heap used: $javaMb (${memorySnapshot.javaHeapUsedKb} KB)",
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text =
                  "Active codecs: ${memorySnapshot.activeCodecs} • Active threads: ${memorySnapshot.activeThreads}",
                style = MaterialTheme.typography.bodyMedium,
              )
            }
          },
          confirmButton = {
            TextButton(onClick = { activeDialog = ActiveSettingsDialog.NONE }) {
              Text("Close")
            }
          },
          dismissButton = {
            TextButton(onClick = onRefreshMemoryDiagnostics) {
              Text("Refresh")
            }
          },
        )
      } else {
        activeDialog = ActiveSettingsDialog.NONE
      }
    }
    ActiveSettingsDialog.ABOUT_STOP_LOG -> {
      val context = LocalContext.current
      val recentStops = remember { StopLogManager.getRecentStops(context) }
      AlertDialog(
        onDismissRequest = { activeDialog = ActiveSettingsDialog.NONE },
        title = { Text("Recording stop log") },
        text = {
          if (recentStops.isEmpty()) {
            Text(
              text = "No recorded stop events yet.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(recentStops) { stop ->
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Text(
                        text = stop.reason.formattedReason(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                      )
                      Text(
                        text = stop.formatDuration(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                      )
                    }
                    val message = stop.reason.message
                    if (!message.isNullOrBlank()) {
                      Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                      )
                    }
                    Text(
                      text = "${stop.resolution} • ${stop.fps} fps • ${stop.codec} • ${stop.bitrateMbps} Mbps",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                      text = stop.formatDateTime(),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { activeDialog = ActiveSettingsDialog.NONE }) {
            Text("Close")
          }
        },
      )
    }
    ActiveSettingsDialog.ABOUT_RESET -> {
      AlertDialog(
        onDismissRequest = { activeDialog = ActiveSettingsDialog.NONE },
        title = { Text("Reset all settings?") },
        text = {
          Text(
            "This will restore default video, audio, floating controls, and bottom navigation preferences."
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              onResetAllSettings()
              activeDialog = ActiveSettingsDialog.NONE
            }
          ) {
            Text("Reset", color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = {
          TextButton(onClick = { activeDialog = ActiveSettingsDialog.NONE }) {
            Text("Cancel")
          }
        },
      )
    }
  }
}

@Composable
private fun PixelSectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
  )
}

@Composable
private fun PixelSettingsRow(
  title: String,
  summary: String,
  shape: Shape,
  onClick: () -> Unit,
  testTag: String,
) {
  Surface(
    shape = shape,
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape)
        .clickable(onClick = onClick)
        .testTag(testTag),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun PixelSettingsSwitchRow(
  title: String,
  summary: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  shape: Shape,
  testTag: String,
  enabled: Boolean = true,
) {
  Surface(
    shape = shape,
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape)
        .clickable(enabled = enabled) { onCheckedChange(!checked) }
        .testTag(testTag),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color =
            if (enabled) {
              MaterialTheme.colorScheme.onSurface
            } else {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (enabled) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = null,
      )
    }
  }
}

@Composable
private fun ProbedResolutionSettingsDialog(
  options: List<String>,
  selectedOption: String,
  probeStates: Map<String, OptionProbeState>,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val visibleOptions =
    options
      .filter { probeStates[it] !is OptionProbeState.Unsupported }
      .ifEmpty { options }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = "Resolution") },
    text = {
      Column {
        visibleOptions.forEach { option ->
          val probeState = probeStates[option] ?: OptionProbeState.Probing
          val isUnsupported = probeState is OptionProbeState.Unsupported
          val isProbing =
            probeState is OptionProbeState.Probing || probeState is OptionProbeState.Idle
          val summaryText =
            when (probeState) {
              is OptionProbeState.Supported -> probeState.codecLabel
              is OptionProbeState.Unsupported -> "Not supported on this device"
              else -> null
            }

          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !isUnsupported) { onSelect(option) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = option == selectedOption,
              onClick = null,
              enabled = !isUnsupported,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = option,
                style = MaterialTheme.typography.bodyLarge,
                color =
                  if (isUnsupported) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (summaryText != null) {
                Text(
                  text = summaryText,
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    if (isUnsupported) {
                      MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            if (isProbing) {
              Spacer(modifier = Modifier.width(8.dp))
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun ProbedFpsSettingsDialog(
  options: List<Int>,
  selectedFps: Int,
  probeStates: Map<Int, OptionProbeState>,
  displayRefreshRateHz: Int,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  val visibleOptions =
    options
      .filter { probeStates[it] !is OptionProbeState.Unsupported }
      .ifEmpty { listOf(24, 30) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = "Frame rate") },
    text = {
      Column {
        visibleOptions.forEach { fps ->
          val probeState = probeStates[fps] ?: OptionProbeState.Probing
          val isUnsupported = probeState is OptionProbeState.Unsupported
          val isProbing =
            probeState is OptionProbeState.Probing || probeState is OptionProbeState.Idle
          val summaryText =
            when (probeState) {
              is OptionProbeState.Supported -> probeState.codecLabel
              is OptionProbeState.Unsupported -> "Not supported on this device"
              else -> null
            }

          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !isUnsupported) { onSelect(fps) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = fps == selectedFps,
              onClick = null,
              enabled = !isUnsupported,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "$fps fps",
                style = MaterialTheme.typography.bodyLarge,
                color =
                  if (isUnsupported) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (summaryText != null) {
                Text(
                  text = summaryText,
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    if (isUnsupported) {
                      MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            if (isProbing) {
              Spacer(modifier = Modifier.width(8.dp))
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
              )
            }
          }
        }

        if (selectedFps > displayRefreshRateHz) {
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text =
              "Your screen is running at $displayRefreshRateHz Hz, so real frame rate may be lower.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun SingleChoiceSettingsDialog(
  title: String,
  options: List<String>,
  selectedOption: String,
  optionSummaries: Map<String, String>? = null,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = title) },
    text = {
      Column {
        options.forEach { option ->
          val summary = optionSummaries?.get(option)
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onSelect(option) }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = option == selectedOption,
              onClick = null,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = option,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (!summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = summary,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun BitrateSliderDialog(
  currentBitrate: Int,
  presets: List<Int>,
  onConfirm: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  val minRate = (presets.minOrNull() ?: 4).toFloat()
  val maxRate = (presets.maxOrNull() ?: 48).toFloat()
  var sliderValue by remember(currentBitrate) { mutableFloatStateOf(currentBitrate.toFloat()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Video bitrate") },
    text = {
      Column {
        Text(
          text = "${sliderValue.roundToInt()} Mbps",
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Higher bitrates improve clarity during fast motion but increase file size.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Slider(
          value = sliderValue,
          onValueChange = { sliderValue = it },
          valueRange = minRate..maxRate,
          steps = 10,
          modifier = Modifier.testTag("settings_bitrate_slider"),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(sliderValue.roundToInt()) }) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun OpacitySliderDialog(
  currentOpacity: Int,
  onConfirm: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  var sliderValue by remember(currentOpacity) { mutableFloatStateOf(currentOpacity.toFloat()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Floating controls opacity") },
    text = {
      Column {
        Text(
          text = "${sliderValue.roundToInt()}%",
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Slider(
          value = sliderValue,
          onValueChange = { sliderValue = it },
          valueRange = 20f..100f,
          steps = 15,
          modifier = Modifier.testTag("settings_opacity_slider"),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(sliderValue.roundToInt()) }) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun NavigationOrderDialog(
  navOrder: List<NavDestinationConfig>,
  onMove: (Int, Int) -> Unit,
  onToggle: (MainDestination) -> Unit,
  onDismiss: () -> Unit,
) {
  val enabledCount = navOrder.count { it.enabled }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Navigation order") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Reorder or toggle destinations shown in the bottom bar.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        navOrder.forEachIndexed { index, item ->
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${index + 1}. ${item.destination.label}",
                  style = MaterialTheme.typography.titleSmall,
                  maxLines = 1,
                )
                Text(
                  text = if (item.enabled) "Visible" else "Hidden",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                )
              }
              TextButton(
                onClick = { onMove(index, -1) },
                enabled = index > 0,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTag("nav_move_up_${item.destination.id}"),
              ) {
                Text("Up")
              }
              TextButton(
                onClick = { onMove(index, 1) },
                enabled = index < navOrder.lastIndex,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTag("nav_move_down_${item.destination.id}"),
              ) {
                Text("Down")
              }
              Switch(
                checked = item.enabled,
                onCheckedChange = { onToggle(item.destination) },
                enabled = !item.enabled || enabledCount > 1,
                thumbContent = null,
                modifier = Modifier.testTag("nav_toggle_${item.destination.id}"),
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Done")
      }
    },
  )
}

@Composable
private fun ThemePickerDialog(
  currentMode: AppThemeMode,
  currentDynamicColor: Boolean,
  onConfirm: (AppThemeMode, Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  var selectedMode by remember(currentMode) { mutableStateOf(currentMode) }
  var dynamicColor by remember(currentDynamicColor) { mutableStateOf(currentDynamicColor) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Theme") },
    text = {
      Column {
        AppThemeMode.entries.forEach { mode ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { selectedMode = mode }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = mode == selectedMode,
              onClick = null,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = mode.label,
              style = MaterialTheme.typography.bodyLarge,
            )
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .clickable { dynamicColor = !dynamicColor },
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Dynamic color",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
              )
              Text(
                text = "Match wallpaper palette on Android 12+",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
            }
            Switch(
              checked = dynamicColor,
              onCheckedChange = { dynamicColor = it },
              thumbContent = null,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(selectedMode, dynamicColor) }) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
