package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.RecordingMediaCache
import com.example.domain.model.RecordingItem

fun pixelGroupedItemShape(
  index: Int,
  totalCount: Int,
  outerRadius: Dp = 24.dp,
  innerRadius: Dp = 4.dp,
): Shape {
  if (totalCount <= 1) {
    return RoundedCornerShape(outerRadius)
  }
  return when (index) {
    0 ->
      RoundedCornerShape(
        topStart = outerRadius,
        topEnd = outerRadius,
        bottomStart = innerRadius,
        bottomEnd = innerRadius,
      )
    totalCount - 1 ->
      RoundedCornerShape(
        topStart = innerRadius,
        topEnd = innerRadius,
        bottomStart = outerRadius,
        bottomEnd = outerRadius,
      )
    else -> RoundedCornerShape(innerRadius)
  }
}

@Composable
fun RecordingThumbnailBox(
  item: RecordingItem,
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 12.dp,
) {
  val context = LocalContext.current
  var thumbnailBitmap by remember(item.contentUri) {
    mutableStateOf(RecordingMediaCache.getCachedThumbnail(item.contentUri))
  }

  LaunchedEffect(item.contentUri) {
    if (thumbnailBitmap == null && item.contentUri.isNotBlank()) {
      thumbnailBitmap = RecordingMediaCache.loadThumbnail(context, item.contentUri)
    }
  }

  val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
  val glyphColor = MaterialTheme.colorScheme.onSurfaceVariant

  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(cornerRadius))
        .background(containerColor)
  ) {
    val bmp = thumbnailBitmap
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = item.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val triSize = h * 0.26f
        val cx = w / 2f
        val cy = h / 2f
        val path =
          Path().apply {
            moveTo(cx - triSize * 0.35f, cy - triSize * 0.5f)
            lineTo(cx + triSize * 0.55f, cy)
            lineTo(cx - triSize * 0.35f, cy + triSize * 0.5f)
            close()
          }
        drawPath(path = path, color = glyphColor.copy(alpha = 0.65f))
      }
    }

    Box(
      modifier =
        Modifier.align(Alignment.BottomEnd)
          .padding(4.dp)
          .clip(RoundedCornerShape(4.dp))
          .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
          .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
      Text(
        text = FormatUtils.formatDurationBadge(item.durationMs),
        style =
          MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
          ),
      )
    }
  }
}
