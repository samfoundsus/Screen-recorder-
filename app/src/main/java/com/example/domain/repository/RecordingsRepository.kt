package com.example.domain.repository

import android.content.IntentSender
import com.example.domain.model.RecordingItem
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RecordingsRepository {
  val recordings: StateFlow<List<RecordingItem>>
  val deletePermissionRequests: SharedFlow<IntentSender>

  fun getRecordingById(id: String): RecordingItem?

  fun refresh()

  fun deleteRecordings(ids: Set<String>)

  fun renameRecording(id: String, newTitle: String)
}
