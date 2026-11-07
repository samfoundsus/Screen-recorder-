package com.example.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.domain.model.RecordingCategory
import com.example.domain.model.RecordingItem
import com.example.domain.repository.RecordingsRepository
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MediaStoreRecordingsRepository(
  context: Context,
) : RecordingsRepository {

  private val appContext = context.applicationContext
  private val contentResolver: ContentResolver = appContext.contentResolver
  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _recordings = MutableStateFlow<List<RecordingItem>>(emptyList())
  override val recordings: StateFlow<List<RecordingItem>> = _recordings.asStateFlow()

  private val _deletePermissionRequests =
    MutableSharedFlow<IntentSender>(
      replay = 0,
      extraBufferCapacity = 1,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  override val deletePermissionRequests: SharedFlow<IntentSender> =
    _deletePermissionRequests.asSharedFlow()

  @Volatile private var pendingRenameOperation: Pair<Uri, String>? = null

  private val mediaStoreObserver =
    object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        refresh()
      }

      override fun onChange(selfChange: Boolean, uri: Uri?) {
        refresh()
      }
    }

  init {
    RecordingMediaCache.setOnMetadataLoadedListener { uriString, meta ->
      _recordings.update { currentList ->
        currentList.map { item ->
          if (item.contentUri == uriString) {
            val nextFps = meta.fps ?: item.fps
            item.copy(
              resolution = meta.resolution ?: item.resolution,
              fps = nextFps,
              bitrateMbps = meta.bitrateMbps ?: item.bitrateMbps,
              codec = meta.codec,
              hasMicAudio = meta.hasAudio,
              audioChannelMode = meta.audioChannelMode,
              noiseSuppression = meta.noiseSuppression,
              category = inferCategory(item.title, item.durationMs, nextFps),
            )
          } else {
            item
          }
        }
      }
    }

    try {
      contentResolver.registerContentObserver(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        true,
        mediaStoreObserver,
      )
    } catch (_: Throwable) {}

    refresh()
  }

  override fun getRecordingById(id: String): RecordingItem? {
    return _recordings.value.find { it.id == id }
  }

  override fun refresh() {
    repositoryScope.launch {
      pendingRenameOperation?.let { (uri, newDisplayName) ->
        pendingRenameOperation = null
        try {
          val values =
            ContentValues().apply {
              put(MediaStore.Video.Media.DISPLAY_NAME, newDisplayName)
            }
          contentResolver.update(uri, values, null, null)
        } catch (_: Throwable) {}
      }

      val items = queryRecordingsFromMediaStore()
      _recordings.value = items
      items.forEach { item ->
        if (
          item.contentUri.isNotBlank() &&
            (item.fps == null ||
              item.fps <= 0 ||
              RecordingMediaCache.getCachedMetadata(item.contentUri) == null)
        ) {
          RecordingMediaCache.loadMetadata(appContext, item.contentUri)
        }
      }
    }
  }

  override fun deleteRecordings(ids: Set<String>) {
    if (ids.isEmpty()) return
    repositoryScope.launch {
      val requiresUserConsentUris = mutableListOf<Uri>()
      for (id in ids) {
        val uri = resolveContentUri(id) ?: continue
        try {
          val deletedRows = contentResolver.delete(uri, null, null)
          if (deletedRows > 0) {
            RecordingMediaCache.remove(uri.toString())
          } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requiresUserConsentUris.add(uri)
          }
        } catch (_: SecurityException) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requiresUserConsentUris.add(uri)
          }
        } catch (_: Throwable) {}
      }

      if (requiresUserConsentUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
          val pendingIntent =
            MediaStore.createDeleteRequest(contentResolver, requiresUserConsentUris)
          _deletePermissionRequests.tryEmit(pendingIntent.intentSender)
        } catch (_: Throwable) {}
      }

      val items = queryRecordingsFromMediaStore()
      _recordings.value = items
    }
  }

  override fun renameRecording(id: String, newTitle: String) {
    val cleanTitle = newTitle.trim()
    if (cleanTitle.isBlank()) return
    repositoryScope.launch {
      val existing = _recordings.value.find { it.id == id }
      val uri = resolveContentUri(id) ?: return@launch
      val ext = existing?.container?.lowercase(Locale.US) ?: "mp4"
      val baseTitle =
        if (cleanTitle.endsWith(".$ext", ignoreCase = true)) {
          cleanTitle.substringBeforeLast(".")
        } else {
          cleanTitle
        }
      val newDisplayName = "$baseTitle.$ext"

      try {
        val values =
          ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, newDisplayName)
          }
        val updated = contentResolver.update(uri, values, null, null)
        if (updated > 0) {
          _recordings.value = queryRecordingsFromMediaStore()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          pendingRenameOperation = uri to newDisplayName
          val writeIntent = MediaStore.createWriteRequest(contentResolver, listOf(uri))
          _deletePermissionRequests.tryEmit(writeIntent.intentSender)
        }
      } catch (_: SecurityException) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          try {
            pendingRenameOperation = uri to newDisplayName
            val writeIntent = MediaStore.createWriteRequest(contentResolver, listOf(uri))
            _deletePermissionRequests.tryEmit(writeIntent.intentSender)
          } catch (_: Throwable) {}
        }
      } catch (_: Throwable) {}
    }
  }

  private fun resolveContentUri(id: String): Uri? {
    _recordings.value.find { it.id == id }?.contentUri?.takeIf { it.isNotBlank() }?.let {
      return Uri.parse(it)
    }
    val numericId = id.removePrefix("ms_").toLongOrNull() ?: id.toLongOrNull() ?: return null
    return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, numericId)
  }

  private fun inferCategory(
    title: String,
    durationMs: Long,
    fps: Int?,
  ): RecordingCategory {
    val lower = title.lowercase(Locale.US)
    return when {
      lower.contains("tutorial") ||
        lower.contains("walkthrough") ||
        lower.contains("guide") ||
        lower.contains("demo") -> RecordingCategory.TUTORIALS
      lower.contains("game") ||
        lower.contains("gameplay") ||
        (fps != null && fps >= 90) -> RecordingCategory.GAMES
      lower.contains("system") ||
        lower.contains("launcher") ||
        lower.contains("ui") ||
        lower.contains("settings") -> RecordingCategory.SYSTEM
      durationMs in 1L..45_000L -> RecordingCategory.CLIPS
      else -> RecordingCategory.SYSTEM
    }
  }

  private fun queryRecordingsFromMediaStore(): List<RecordingItem> {
    val result = mutableListOf<RecordingItem>()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val projectionList =
      mutableListOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DATE_TAKEN,
      )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      projectionList.add(MediaStore.Video.Media.CAPTURE_FRAMERATE)
    }
    val projection = projectionList.toTypedArray()

    val selection =
      "${MediaStore.Video.Media.IS_PENDING} = 0 AND (" +
        "${MediaStore.Video.Media.RELATIVE_PATH} = ? OR " +
        "${MediaStore.Video.Media.RELATIVE_PATH} = ? OR " +
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?)"
    val selectionArgs =
      arrayOf(
        "Movies/ScreenRecorder/",
        "Movies/ScreenRecorder",
        "%Movies/ScreenRecorder/%",
      )
    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

    try {
      contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use {
        cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
        val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
        val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
        val captureFpsCol =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Video.Media.CAPTURE_FRAMERATE)
          } else {
            -1
          }

        while (cursor.moveToNext()) {
          val mediaId = cursor.getLong(idCol)
          val contentUri =
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
          val uriString = contentUri.toString()

          val rawDisplayName =
            if (!cursor.isNull(displayNameCol)) {
              cursor.getString(displayNameCol).orEmpty()
            } else {
              "Recording $mediaId.mp4"
            }
          val title = rawDisplayName.substringBeforeLast(".").ifBlank { rawDisplayName }
          val extension =
            rawDisplayName.substringAfterLast(".", "MP4").uppercase(Locale.US).ifBlank { "MP4" }

          val sizeBytes =
            if (!cursor.isNull(sizeCol)) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
          val durationMs =
            if (!cursor.isNull(durationCol)) cursor.getLong(durationCol).coerceAtLeast(0L) else 0L
          val width = if (!cursor.isNull(widthCol)) cursor.getInt(widthCol) else 0
          val height = if (!cursor.isNull(heightCol)) cursor.getInt(heightCol) else 0
          val dateAddedSec = if (!cursor.isNull(dateAddedCol)) cursor.getLong(dateAddedCol) else 0L
          val dateTakenMs = if (!cursor.isNull(dateTakenCol)) cursor.getLong(dateTakenCol) else 0L
          val mediaStoreFps =
            if (captureFpsCol >= 0 && !cursor.isNull(captureFpsCol)) {
              cursor.getFloat(captureFpsCol).roundToInt().takeIf { it > 0 }
            } else {
              null
            }

          val createdAtEpochMs =
            when {
              dateAddedSec > 0L -> dateAddedSec * 1000L
              dateTakenMs > 0L -> dateTakenMs
              else -> System.currentTimeMillis()
            }

          val cachedMeta = RecordingMediaCache.getCachedMetadata(uriString)
          val resolvedW = cachedMeta?.width ?: width.takeIf { it > 0 }
          val resolvedH = cachedMeta?.height ?: height.takeIf { it > 0 }

          val resolutionLabel =
            when {
              resolvedW != null && resolvedH != null ->
                RecordingMediaCache.formatResolutionLabel(resolvedW, resolvedH)
              cachedMeta?.resolution != null -> cachedMeta.resolution
              else -> "1080p"
            }

          val resolvedFps = cachedMeta?.fps ?: mediaStoreFps

          result.add(
            RecordingItem(
              id = mediaId.toString(),
              title = title,
              createdAtEpochMs = createdAtEpochMs,
              durationMs = durationMs,
              sizeBytes = sizeBytes,
              resolution = resolutionLabel,
              fps = resolvedFps,
              bitrateMbps = cachedMeta?.bitrateMbps,
              codec = cachedMeta?.codec ?: "AVC (H.264)",
              container = extension,
              hasMicAudio = cachedMeta?.hasAudio ?: true,
              audioChannelMode = cachedMeta?.audioChannelMode ?: "Stereo",
              noiseSuppression = cachedMeta?.noiseSuppression ?: false,
              category = inferCategory(title, durationMs, resolvedFps),
              accentHue = ((mediaId * 47L) % 360L).toFloat(),
              contentUri = uriString,
            )
          )
        }
      }
    } catch (_: Throwable) {}

    return result
  }
}
