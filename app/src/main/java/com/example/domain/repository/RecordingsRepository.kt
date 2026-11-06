package com.example.domain.repository

import com.example.domain.model.RecordingItem
import kotlinx.coroutines.flow.StateFlow

interface RecordingsRepository {
  val recordings: StateFlow<List<RecordingItem>>

  fun getRecordingById(id: String): RecordingItem?

  fun addRecording(item: RecordingItem)

  fun deleteRecordings(ids: Set<String>)

  fun renameRecording(id: String, newTitle: String)
}
