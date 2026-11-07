/*
 * Polished Playback Screen:
 * 1. Video area shows video in its real aspect ratio (portrait stays portrait, landscape stays landscape)
 *    centered with 16dp rounded corners and a maximum height of 55% of the screen, with no wide black letterbox.
 *    Tapping the video toggles play/pause.
 * 2. Seek bar is a thin standard Material 3 Slider (4dp track, small thumb) with dynamic colors,
 *    current time on left and total time on right in regular proportional font (labelSmall, onSurfaceVariant).
 * 3. Controls directly on screen background (no card container), centered in one row fitting 360dp screens:
 *    - Order: playback speed, rewind 10 s, play/pause, forward 10 s, mute.
 *    - Play/pause is 64dp, icon-only, morphing between circle (paused) and squircle (playing, ~24dp radius)
 *      with 200ms animation and cross-fading icon, colored with primary / onPrimary.
 *    - Rewind 10s, forward 10s and mute are 40dp icon-only buttons in secondaryContainer.
 *    - Playback speed is a 40dp tall pill showing "0.5×", "1×", "1.5×", "2×", using setPlaybackParameters.
 *    - When playback ends, pauses and seeks back to start in circle (play) state.
 * 4. Flat two-line Details rows under a small "Details" header without card containers or side-by-side columns.
 * 5. Compact TopAppBar with Back, title, and Delete with confirmation dialog.
 */
package com.example.ui.playback

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.repository.RecordingMediaCache
import com.example.ui.components.FormatUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
  uiState: PlaybackUiState,
  onNavigateBack: () -> Unit,
  onRenameRecording: (String) -> Unit = {},
  onDeleteRecording: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler { onNavigateBack() }

  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val recording = uiState.recording

  var metadata by remember(recording?.contentUri) {
    mutableStateOf(recording?.contentUri?.let { RecordingMediaCache.getCachedMetadata(it) })
  }

  LaunchedEffect(recording?.contentUri) {
    val uriStr = recording?.contentUri ?: return@LaunchedEffect
    if (uriStr.isNotBlank()) {
      metadata = RecordingMediaCache.loadMetadata(context, uriStr)
    }
  }

  var isPlaying by rememberSaveable { mutableStateOf(false) }
  var shouldPlayWhenReady by rememberSaveable { mutableStateOf(true) }
  var isMuted by rememberSaveable { mutableStateOf(false) }
  var playbackSpeed by rememberSaveable { mutableFloatStateOf(1.0f) }
  var currentPositionMs by rememberSaveable { mutableLongStateOf(0L) }
  var durationMs by rememberSaveable(recording?.durationMs) {
    mutableLongStateOf((recording?.durationMs ?: 1_000L).coerceAtLeast(1_000L))
  }
  var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

  var rawVideoWidth by remember { mutableIntStateOf(metadata?.width ?: 0) }
  var rawVideoHeight by remember { mutableIntStateOf(metadata?.height ?: 0) }

  LaunchedEffect(metadata) {
    val meta = metadata ?: return@LaunchedEffect
    if ((meta.width ?: 0) > 0 && (meta.height ?: 0) > 0) {
      if (rawVideoWidth <= 0 || rawVideoHeight <= 0) {
        rawVideoWidth = meta.width!!
        rawVideoHeight = meta.height!!
      }
    }
  }

  // Media3 ExoPlayer is alive ONLY while PlaybackScreen is in the foreground (STARTED..RESUMED)
  var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

  DisposableEffect(lifecycleOwner, recording?.contentUri) {
    val playerListener =
      object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
          isPlaying = playing
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          val player = exoPlayer ?: return
          if (playbackState == Player.STATE_READY && player.duration > 0) {
            durationMs = player.duration
          } else if (playbackState == Player.STATE_ENDED) {
            shouldPlayWhenReady = false
            isPlaying = false
            player.pause()
            player.seekTo(0L)
            currentPositionMs = 0L
          }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
          if (videoSize.width > 0 && videoSize.height > 0) {
            rawVideoWidth = videoSize.width
            rawVideoHeight = videoSize.height
          }
        }
      }

    fun createAndPreparePlayer() {
      if (exoPlayer != null) return
      val uriStr = recording?.contentUri
      val player =
        ExoPlayer.Builder(context.applicationContext).build().apply {
          addListener(playerListener)
          volume = if (isMuted) 0f else 1f
          setPlaybackParameters(PlaybackParameters(playbackSpeed))
          repeatMode = Player.REPEAT_MODE_OFF
          if (!uriStr.isNullOrBlank()) {
            setMediaItem(MediaItem.fromUri(Uri.parse(uriStr)))
            prepare()
            if (currentPositionMs > 0L) {
              seekTo(currentPositionMs)
            }
            playWhenReady = shouldPlayWhenReady
          }
        }
      exoPlayer = player
    }

    fun releasePlayerNow() {
      val player = exoPlayer ?: return
      currentPositionMs = player.currentPosition.coerceAtLeast(0L)
      shouldPlayWhenReady = player.playWhenReady
      isPlaying = false
      player.removeListener(playerListener)
      player.release()
      exoPlayer = null
    }

    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
      createAndPreparePlayer()
    }

    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> createAndPreparePlayer()
        Lifecycle.Event.ON_PAUSE -> {
          exoPlayer?.let { player ->
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            shouldPlayWhenReady = player.playWhenReady
            player.pause()
          }
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_DESTROY -> releasePlayerNow()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      releasePlayerNow()
    }
  }

  LaunchedEffect(exoPlayer, isPlaying) {
    val player = exoPlayer ?: return@LaunchedEffect
    while (isActive) {
      currentPositionMs = player.currentPosition.coerceAtLeast(0L)
      if (player.duration > 0) {
        durationMs = player.duration
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
            style = MaterialTheme.typography.titleMedium,
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
        // 1. VIDEO AREA: real aspect ratio, centered, 16dp rounded corners, max 55% screen height
        val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
        val maxVideoHeight = screenHeightDp * 0.55f

        BoxWithConstraints(
          modifier = Modifier.fillMaxWidth(),
          contentAlignment = Alignment.Center,
        ) {
          val aspect =
            if (rawVideoWidth > 0 && rawVideoHeight > 0) {
              rawVideoWidth.toFloat() / rawVideoHeight.toFloat()
            } else {
              16f / 9f
            }

          val widthFromMaxHeight = maxVideoHeight * aspect
          val (calculatedWidth, calculatedHeight) =
            if (widthFromMaxHeight <= maxWidth) {
              widthFromMaxHeight to maxVideoHeight
            } else {
              maxWidth to (maxWidth / aspect)
            }

          Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black,
            modifier =
              Modifier.size(width = calculatedWidth, height = calculatedHeight)
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                  val player = exoPlayer ?: return@clickable
                  if (player.isPlaying) {
                    shouldPlayWhenReady = false
                    player.pause()
                  } else {
                    shouldPlayWhenReady = true
                    player.play()
                  }
                }
                .testTag("playback_video_surface"),
          ) {
            val activePlayer = exoPlayer
            if (activePlayer != null) {
              AndroidView(
                factory = { ctx ->
                  PlayerView(ctx).apply {
                    useController = false
                    player = activePlayer
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams =
                      FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                      )
                  }
                },
                update = { playerView ->
                  playerView.player = activePlayer
                },
                modifier = Modifier.fillMaxSize(),
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. SEEK BAR: thin standard Material 3 Slider (4dp track, small thumb), full width
        val safeDuration = durationMs.coerceAtLeast(1L).toFloat()
        val safePosition = currentPositionMs.toFloat().coerceIn(0f, safeDuration)

        Slider(
          value = safePosition,
          onValueChange = { newPos ->
            currentPositionMs = newPos.toLong()
            exoPlayer?.seekTo(newPos.toLong())
          },
          valueRange = 0f..safeDuration,
          thumb = {
            SliderDefaults.Thumb(
              interactionSource = remember { MutableInteractionSource() },
              thumbSize = DpSize(14.dp, 14.dp),
            )
          },
          track = { sliderState ->
            SliderDefaults.Track(
              sliderState = sliderState,
              modifier = Modifier.height(4.dp),
            )
          },
          modifier = Modifier.fillMaxWidth().testTag("playback_seek_slider"),
        )

        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = FormatUtils.formatDurationBadge(currentPositionMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = FormatUtils.formatDurationBadge(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. CONTROLS: no card container, directly on background, one centered row (speed, rew 10s, play/pause, fwd 10s, mute)
        val playPauseCornerRadius by
          animateDpAsState(
            targetValue = if (isPlaying) 24.dp else 32.dp,
            animationSpec = tween(durationMillis = 200),
            label = "playPauseCornerRadius",
          )

        val speedText =
          when (playbackSpeed) {
            0.5f -> "0.5×"
            1.5f -> "1.5×"
            2.0f -> "2×"
            else -> "1×"
          }

        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Playback speed: small pill button, 40dp tall
          Surface(
            onClick = {
              val speedSteps = listOf(0.5f, 1.0f, 1.5f, 2.0f)
              val currentIndex =
                speedSteps.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.05f }
              val nextIndex =
                if (currentIndex in speedSteps.indices) (currentIndex + 1) % speedSteps.size else 0
              val nextSpeed = speedSteps[nextIndex]
              playbackSpeed = nextSpeed
              exoPlayer?.setPlaybackParameters(PlaybackParameters(nextSpeed))
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier =
              Modifier.height(40.dp)
                .widthIn(min = 48.dp)
                .testTag("playback_speed_button"),
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier.padding(horizontal = 10.dp),
            ) {
              Text(
                text = speedText,
                style =
                  MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
              )
            }
          }

          Spacer(modifier = Modifier.width(12.dp))

          // Rewind 10 s: 40dp icon-only button
          Surface(
            onClick = {
              val current = exoPlayer?.currentPosition ?: currentPositionMs
              val target = (current - 10_000L).coerceAtLeast(0L)
              exoPlayer?.seekTo(target)
              currentPositionMs = target
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp).testTag("playback_rewind_button"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = "Rewind 10 seconds",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
              )
            }
          }

          Spacer(modifier = Modifier.width(14.dp))

          // Play / Pause: 64dp main button, circle when paused, squircle (24dp radius) when playing
          Surface(
            onClick = {
              val player = exoPlayer ?: return@Surface
              if (player.isPlaying) {
                shouldPlayWhenReady = false
                player.pause()
              } else {
                shouldPlayWhenReady = true
                player.play()
              }
            },
            shape = RoundedCornerShape(playPauseCornerRadius),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp).testTag("playback_play_pause_button"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                  fadeIn(animationSpec = tween(200)) togetherWith
                    fadeOut(animationSpec = tween(200))
                },
                label = "playPauseCrossfade",
              ) { playing ->
                Icon(
                  imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                  contentDescription = if (playing) "Pause" else "Play",
                  tint = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.size(32.dp),
                )
              }
            }
          }

          Spacer(modifier = Modifier.width(14.dp))

          // Forward 10 s: 40dp icon-only button
          Surface(
            onClick = {
              val current = exoPlayer?.currentPosition ?: currentPositionMs
              val target = (current + 10_000L).coerceAtMost(durationMs)
              exoPlayer?.seekTo(target)
              currentPositionMs = target
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp).testTag("playback_forward_button"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = "Forward 10 seconds",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
              )
            }
          }

          Spacer(modifier = Modifier.width(12.dp))

          // Mute / Unmute: 40dp icon-only button
          Surface(
            onClick = {
              val nextMuted = !isMuted
              isMuted = nextMuted
              exoPlayer?.volume = if (nextMuted) 0f else 1f
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp).testTag("playback_mute_button"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector =
                  if (isMuted) {
                    Icons.AutoMirrored.Filled.VolumeOff
                  } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                  },
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 4. DETAILS SECTION: flat two-line rows under a small "Details" header, no card container
        if (recording != null) {
          val resolvedResolution = metadata?.resolution ?: recording.resolution
          val resolvedFps = metadata?.fps ?: recording.fps
          val resolvedBitrateMbps = metadata?.bitrateMbps ?: recording.bitrateMbps
          val resolvedCodec = metadata?.codec ?: recording.codec
          val resolvedHasAudio = metadata?.hasAudio ?: recording.hasMicAudio
          val resolvedAudioChannel =
            if (
              (metadata?.audioChannelMode ?: recording.audioChannelMode).equals(
                "Mono",
                ignoreCase = true,
              )
            ) {
              "Mono"
            } else {
              "Stereo"
            }
          val resolvedNoiseSuppression = metadata?.noiseSuppression ?: recording.noiseSuppression
          val audioLine =
            if (resolvedHasAudio) {
              if (resolvedNoiseSuppression) {
                "AAC · $resolvedAudioChannel (Noise suppression on)"
              } else {
                "AAC · $resolvedAudioChannel"
              }
            } else {
              "Muted"
            }

          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
          ) {
            Text(
              text = "Details",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(bottom = 12.dp),
            )

            FlatDetailRow(label = "Resolution", value = resolvedResolution)
            FlatDetailRow(
              label = "Frame rate",
              value = if (resolvedFps != null && resolvedFps > 0) "$resolvedFps FPS" else "Variable",
            )
            FlatDetailRow(
              label = "Codec and container",
              value = "$resolvedCodec • ${recording.container}",
            )
            FlatDetailRow(
              label = "Bitrate",
              value =
                if (resolvedBitrateMbps != null && resolvedBitrateMbps > 0) {
                  "$resolvedBitrateMbps Mbps"
                } else {
                  "Auto"
                },
            )
            FlatDetailRow(label = "Audio", value = audioLine)
            FlatDetailRow(
              label = "File size",
              value = FormatUtils.formatFileSize(recording.sizeBytes),
            )
            FlatDetailRow(
              label = "Recorded",
              value = FormatUtils.formatRelativeDate(recording.createdAtEpochMs),
            )
          }
        }

        Spacer(modifier = Modifier.height(32.dp))
      }
    }
  }

  // Delete confirmation dialog
  if (showDeleteDialog && recording != null) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text("Delete recording?") },
      text = {
        Text("Permanently remove \"${recording.title}\" from your library? This action cannot be undone.")
      },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteDialog = false
            onDeleteRecording()
          },
          modifier = Modifier.testTag("playback_confirm_delete_button"),
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
private fun FlatDetailRow(label: String, value: String) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
