package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.RecordingMediaCache
import com.example.domain.model.RecorderState
import com.example.domain.model.RecordingItem
import com.example.ui.components.FormatUtils
import com.example.ui.components.RecordingThumbnailBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  uiState: HomeUiState,
  onPrimaryRecordAction: () -> Unit,
  onPauseResumeAction: () -> Unit,
  onSelectResolution: (String) -> Unit,
  onSelectFps: (Int) -> Unit,
  onToggleMic: () -> Unit,
  onOpenRecording: (String) -> Unit,
  onNavigateToLibrary: () -> Unit,
  onNavigateToSettings: () -> Unit,
  showSettingsShortcutInTopBar: Boolean = false,
  modifier: Modifier = Modifier,
) {
  var showResolutionDialog by rememberSaveable { mutableStateOf(false) }
  var showFpsDialog by rememberSaveable { mutableStateOf(false) }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.surface,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      Box(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.app_name),
              style =
                MaterialTheme.typography.titleLarge.copy(
                  fontWeight = FontWeight.Normal,
                ),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(start = 8.dp),
            )
          },
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = Color.Transparent,
              scrolledContainerColor = Color.Transparent,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
          modifier =
            Modifier.widthIn(max = 600.dp)
              .align(Alignment.Center)
              .fillMaxWidth()
              .testTag("home_top_app_bar"),
        )
      }
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.surface)
          .padding(innerPadding)
    ) {
      LazyColumn(
        modifier =
          Modifier.widthIn(max = 600.dp)
            .align(Alignment.TopCenter)
            .fillMaxSize()
            .testTag("home_lazy_column"),
        contentPadding = PaddingValues(bottom = 32.dp),
      ) {
        item(key = "recorder_hero") {
          RecorderMinimalHero(
            recorderState = uiState.recorderState,
            elapsedTimeMs = uiState.elapsedTimeMs,
            onPrimaryRecordAction = onPrimaryRecordAction,
            onPauseResumeAction = onPauseResumeAction,
          )
        }

        item(key = "quick_options_row") {
          QuickOptionsChipRow(
            resolution = uiState.settings.video.resolution,
            fps = uiState.settings.video.fps,
            micEnabled = uiState.settings.audio.micEnabled,
            onResolutionClick = { showResolutionDialog = true },
            onFpsClick = { showFpsDialog = true },
            onMicToggle = onToggleMic,
          )
          Spacer(modifier = Modifier.height(44.dp))
        }

        item(key = "recent_header") {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.home_recent_recordings),
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.recentRecordings.isNotEmpty()) {
              TextButton(
                onClick = onNavigateToLibrary,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.testTag("home_view_all_button"),
              ) {
                Text(
                  text = stringResource(R.string.home_view_all),
                  style = MaterialTheme.typography.labelLarge,
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
        }

        val recentList = uiState.recentRecordings
        if (recentList.isEmpty()) {
          item(key = "empty_recent") {
            Box(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "No recordings yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        } else {
          items(
            items = recentList,
            key = { item -> item.id },
          ) { item ->
            FlatRecentRecordingRow(
              item = item,
              onClick = { onOpenRecording(item.id) },
            )
            Spacer(modifier = Modifier.height(6.dp))
          }
        }
      }
    }
  }

  if (showResolutionDialog) {
    ResolutionPickerDialog(
      options = uiState.capabilities.supportedResolutions,
      selected = uiState.settings.video.resolution,
      onSelect = {
        onSelectResolution(it)
        showResolutionDialog = false
      },
      onDismiss = { showResolutionDialog = false },
    )
  }

  if (showFpsDialog) {
    FpsPickerDialog(
      options = uiState.capabilities.supportedFps,
      selected = uiState.settings.video.fps,
      onSelect = {
        onSelectFps(it)
        showFpsDialog = false
      },
      onDismiss = { showFpsDialog = false },
    )
  }
}

@Composable
private fun RecorderMinimalHero(
  recorderState: RecorderState,
  elapsedTimeMs: Long,
  onPrimaryRecordAction: () -> Unit,
  onPauseResumeAction: () -> Unit,
) {
  val isIdle = recorderState == RecorderState.IDLE

  val outerCornerRadius by
    animateDpAsState(
      targetValue = if (isIdle) 44.dp else 26.dp,
      animationSpec = tween(durationMillis = 200),
      label = "outerCornerRadius",
    )

  // 28% of 88dp is ~25dp
  val innerGlyphCorner by
    animateDpAsState(
      targetValue = if (isIdle) 12.5.dp else 6.dp,
      animationSpec = tween(durationMillis = 200),
      label = "innerGlyphCorner",
    )

  val containerColor = MaterialTheme.colorScheme.primaryContainer
  val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = 68.dp, bottom = 52.dp, start = 24.dp, end = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Light-weight proportional system sans timer (00:00 format), always onSurface
    Text(
      text = FormatUtils.formatElapsedTimer(elapsedTimeMs),
      style =
        TextStyle(
          fontFamily = FontFamily.SansSerif,
          fontWeight = FontWeight.Light,
          fontSize = 72.sp,
          lineHeight = 80.sp,
          letterSpacing = (-1.5).sp,
          fontFeatureSettings = "tnum",
        ),
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.testTag("home_elapsed_timer"),
    )

    Spacer(modifier = Modifier.height(48.dp))

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      AnimatedVisibility(
        visible = !isIdle,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(150)),
      ) {
        FilledTonalButton(
          onClick = onPauseResumeAction,
          shape = CircleShape,
          contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
          modifier = Modifier.height(52.dp).testTag("home_pause_resume_button"),
        ) {
          Text(
            text =
              if (recorderState == RecorderState.PAUSED) {
                stringResource(R.string.home_resume_recording)
              } else {
                stringResource(R.string.home_pause_recording)
              },
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }

      // 88dp button in primaryContainer with onPrimaryContainer inner glyph and M3 ripple
      Box(
        contentAlignment = Alignment.Center,
        modifier =
          Modifier.size(88.dp)
            .clip(RoundedCornerShape(outerCornerRadius))
            .background(containerColor)
            .clickable(
              role = Role.Button,
              onClickLabel =
                if (isIdle) {
                  stringResource(R.string.home_start_recording)
                } else {
                  stringResource(R.string.home_stop_recording)
                },
              onClick = onPrimaryRecordAction,
            )
            .testTag("home_record_button"),
      ) {
        if (recorderState == RecorderState.PAUSED) {
          // Two-bar pause glyph in onPrimaryContainer
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier =
                Modifier.size(width = 7.dp, height = 24.dp)
                  .clip(RoundedCornerShape(3.5.dp))
                  .background(contentColor)
            )
            Box(
              modifier =
                Modifier.size(width = 7.dp, height = 24.dp)
                  .clip(RoundedCornerShape(3.5.dp))
                  .background(contentColor)
            )
          }
        } else {
          // Solid filled circle (idle, ~28% of 88dp = 25dp) morphing into rounded-square (recording)
          Box(
            modifier =
              Modifier.size(25.dp)
                .clip(RoundedCornerShape(innerGlyphCorner))
                .background(contentColor)
          )
        }
      }
    }
  }
}

@Composable
private fun QuickOptionsChipRow(
  resolution: String,
  fps: Int,
  micEnabled: Boolean,
  onResolutionClick: () -> Unit,
  onFpsClick: () -> Unit,
  onMicToggle: () -> Unit,
) {
  val shortResolution = resolution.substringBefore(" ").trim()
  val tonalColors =
    FilterChipDefaults.filterChipColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      labelColor = MaterialTheme.colorScheme.onSurface,
      selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
      selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
      selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Resolution text-only tonal chip (minimum 48dp touch target)
    FilterChip(
      selected = false,
      onClick = onResolutionClick,
      label = {
        Text(
          text = shortResolution,
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
      },
      shape = CircleShape,
      border = null,
      colors = tonalColors,
      modifier =
        Modifier.minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("home_option_resolution"),
    )

    // FPS text-only tonal chip (minimum 48dp touch target)
    FilterChip(
      selected = false,
      onClick = onFpsClick,
      label = {
        Text(
          text = "$fps fps",
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
      },
      shape = CircleShape,
      border = null,
      colors = tonalColors,
      modifier =
        Modifier.minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("home_option_fps"),
    )

    // Mic filter chip with checkmark when on (minimum 48dp touch target)
    FilterChip(
      selected = micEnabled,
      onClick = onMicToggle,
      label = {
        Text(
          text = "Mic",
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
      },
      leadingIcon =
        if (micEnabled) {
          {
            Icon(
              imageVector = Icons.Filled.Check,
              contentDescription = null,
              modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
          }
        } else {
          null
        },
      shape = CircleShape,
      border = null,
      colors = tonalColors,
      modifier =
        Modifier.minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("home_option_mic"),
    )
  }
}

@Composable
private fun FlatRecentRecordingRow(
  item: RecordingItem,
  onClick: () -> Unit,
) {
  val context = LocalContext.current
  var metadata by remember(item.contentUri) {
    mutableStateOf(RecordingMediaCache.getCachedMetadata(item.contentUri))
  }

  LaunchedEffect(item.contentUri) {
    if (metadata == null && item.contentUri.isNotBlank()) {
      metadata = RecordingMediaCache.loadMetadata(context, item.contentUri)
    }
  }

  val resolvedResolution = metadata?.resolution ?: item.resolution
  val shortResolution = resolvedResolution.substringBefore(" ").trim()
  val resolvedFps = metadata?.fps ?: item.fps

  val summaryText =
    if (resolvedFps != null && resolvedFps > 0) {
      "$shortResolution · $resolvedFps fps · ${FormatUtils.formatFileSize(item.sizeBytes)} · ${FormatUtils.formatRelativeDate(item.createdAtEpochMs)}"
    } else {
      "$shortResolution · ${FormatUtils.formatFileSize(item.sizeBytes)} · ${FormatUtils.formatRelativeDate(item.createdAtEpochMs)}"
    }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 16.dp)
        .testTag("home_recent_item_${item.id}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RecordingThumbnailBox(
      item = item,
      cornerRadius = 12.dp,
      modifier = Modifier.size(width = 68.dp, height = 46.dp),
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = summaryText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ResolutionPickerDialog(
  options: List<String>,
  selected: String,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Resolution") },
    text = {
      Column {
        options.forEach { option ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onSelect(option) }
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = option == selected, onClick = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = option, style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
private fun FpsPickerDialog(
  options: List<Int>,
  selected: Int,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Frame rate") },
    text = {
      Column {
        options.forEach { fps ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onSelect(fps) }
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = fps == selected, onClick = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "$fps fps", style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
