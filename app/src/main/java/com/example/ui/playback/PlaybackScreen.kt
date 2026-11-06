package com.example.ui.playback

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.ui.components.FormatUtils
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
  uiState: PlaybackUiState,
  onNavigateBack: () -> Unit,
  onDeleteRecording: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler { onNavigateBack() }

  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val recording = uiState.recording

  var isPlaying by remember { mutableStateOf(false) }
  var isMuted by remember { mutableStateOf(false) }
  var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
  var currentPositionMs by remember { mutableLongStateOf(0L) }
  var durationMs by remember {
    mutableLongStateOf((recording?.durationMs ?: 5_000L).coerceAtLeast(1_000L))
  }
  var showDeleteDialog by remember { mutableStateOf(false) }

  // Media3 ExoPlayer initialized ONLY while PlaybackScreen is open
  val exoPlayer =
    remember(context, recording?.contentUri) {
      ExoPlayer.Builder(context).build().apply {
        val mediaUri =
          recording?.contentUri?.let { Uri.parse(it) }
            ?: Uri.parse("android.resource://${context.packageName}/${R.raw.sample_recording}")
        setMediaItem(MediaItem.fromUri(mediaUri))
        repeatMode = Player.REPEAT_MODE_ONE
        prepare()
        playWhenReady = true
      }
    }

  DisposableEffect(lifecycleOwner, exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
          isPlaying = playing
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_READY && exoPlayer.duration > 0) {
            durationMs = exoPlayer.duration
          }
        }
      }
    exoPlayer.addListener(listener)

    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
        Lifecycle.Event.ON_DESTROY -> exoPlayer.release()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  LaunchedEffect(exoPlayer, isPlaying) {
    while (isActive) {
      currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
      if (exoPlayer.duration > 0) {
        durationMs = exoPlayer.duration
      }
      delay(200L)
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.testTag("playback_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
            )
          }
        },
        title = {
          Text(
            text = recording?.title ?: "Playback",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        actions = {
          if (recording != null) {
            IconButton(
              onClick = { showDeleteDialog = true },
              modifier = Modifier.testTag("playback_delete_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Delete recording",
              )
            }
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
          ),
      )
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      Column(
        modifier =
          Modifier.widthIn(max = 640.dp)
            .align(Alignment.TopCenter)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        // Media3 Video Surface Card
        Surface(
          shape = RoundedCornerShape(28.dp),
          color = Color.Black,
          modifier =
            Modifier.fillMaxWidth()
              .aspectRatio(16f / 10f)
              .clip(RoundedCornerShape(28.dp))
              .testTag("playback_video_surface"),
        ) {
          AndroidView(
            factory = { ctx ->
              PlayerView(ctx).apply {
                useController = false
                player = exoPlayer
                layoutParams =
                  FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                  )
              }
            },
            update = { playerView ->
              playerView.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize(),
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Pixel Material 3 Transport Controls Card
        Surface(
          shape = RoundedCornerShape(28.dp),
          color = MaterialTheme.colorScheme.surfaceContainer,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            // Seek Bar & Timecodes
            val safeDuration = durationMs.coerceAtLeast(1L).toFloat()
            val safePosition = currentPositionMs.toFloat().coerceIn(0f, safeDuration)

            Slider(
              value = safePosition,
              onValueChange = { newPos ->
                currentPositionMs = newPos.toLong()
                exoPlayer.seekTo(newPos.toLong())
              },
              valueRange = 0f..safeDuration,
              modifier = Modifier.fillMaxWidth().testTag("playback_seek_slider"),
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = FormatUtils.formatDurationBadge(currentPositionMs),
                style =
                  MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium,
                  ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = FormatUtils.formatDurationBadge(durationMs),
                style =
                  MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium,
                  ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Transport Buttons Row
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceEvenly,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              // Mute / Unmute
              FilledTonalIconButton(
                onClick = {
                  val nextMuted = !isMuted
                  isMuted = nextMuted
                  exoPlayer.volume = if (nextMuted) 0f else 1f
                },
                modifier = Modifier.size(48.dp).testTag("playback_mute_button"),
              ) {
                Icon(
                  imageVector =
                    if (isMuted) {
                      Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                      Icons.AutoMirrored.Filled.VolumeUp
                    },
                  contentDescription = if (isMuted) "Unmute" else "Mute",
                )
              }

              // Rewind 5s
              FilledTonalIconButton(
                onClick = {
                  val target = (exoPlayer.currentPosition - 5_000L).coerceAtLeast(0L)
                  exoPlayer.seekTo(target)
                  currentPositionMs = target
                },
                modifier = Modifier.size(48.dp).testTag("playback_rewind_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Replay5,
                  contentDescription = "Rewind 5 seconds",
                )
              }

              // Play / Pause Pill Button
              Button(
                onClick = {
                  if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                  } else {
                    exoPlayer.play()
                  }
                },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                modifier = Modifier.height(56.dp).testTag("playback_play_pause_button"),
              ) {
                Icon(
                  imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                  contentDescription = if (isPlaying) "Pause" else "Play",
                  modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = if (isPlaying) "Pause" else "Play",
                  style = MaterialTheme.typography.titleMedium,
                )
              }

              // Forward 5s
              FilledTonalIconButton(
                onClick = {
                  val target =
                    (exoPlayer.currentPosition + 5_000L).coerceAtMost(durationMs)
                  exoPlayer.seekTo(target)
                  currentPositionMs = target
                },
                modifier = Modifier.size(48.dp).testTag("playback_forward_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Forward5,
                  contentDescription = "Forward 5 seconds",
                )
              }

              // Playback Speed Pill
              FilledTonalButton(
                onClick = {
                  val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)
                  val nextIdx = (speeds.indexOf(playbackSpeed) + 1) % speeds.size
                  val nextSpeed = speeds[nextIdx]
                  playbackSpeed = nextSpeed
                  exoPlayer.setPlaybackSpeed(nextSpeed)
                },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.height(48.dp).testTag("playback_speed_button"),
              ) {
                Text(
                  text = "${playbackSpeed}x",
                  style = MaterialTheme.typography.labelLarge,
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recording Details Grouped Card
        if (recording != null) {
          Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(20.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Text(
                text = "Recording specifications",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
              )
              MetadataDetailRow("Resolution & Frame rate", "${recording.resolution} • ${recording.fps} FPS")
              MetadataDetailRow("Codec & Container", "${recording.codec} • ${recording.container}")
              MetadataDetailRow("Target Bitrate", "${recording.bitrateMbps} Mbps")
              MetadataDetailRow(
                "Audio track",
                if (recording.hasMicAudio) {
                  "Device audio + Mic (${recording.audioChannelMode})"
                } else {
                  "Device audio only (${recording.audioChannelMode})"
                },
              )
              MetadataDetailRow(
                "File size & Recorded",
                "${FormatUtils.formatFileSize(recording.sizeBytes)} • ${FormatUtils.formatRelativeDate(recording.createdAtEpochMs)}",
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }

  if (showDeleteDialog && recording != null) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text("Delete recording?") },
      text = {
        Text("Permanently remove \"${recording.title}\" from your library?")
      },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteDialog = false
            onDeleteRecording()
          }
        ) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun MetadataDetailRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
