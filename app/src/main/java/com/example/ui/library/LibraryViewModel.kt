package com.example.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.DateFilter
import com.example.domain.model.RecordingCategory
import com.example.domain.model.RecordingItem
import com.example.domain.model.SortOption
import com.example.domain.repository.RecordingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class LibraryUiState(
  val searchQuery: String = "",
  val selectedCategory: RecordingCategory = RecordingCategory.ALL,
  val availableCategories: List<RecordingCategory> = emptyList(),
  val selectedDateFilter: DateFilter = DateFilter.ALL_TIME,
  val selectedSortOption: SortOption = SortOption.DATE_DESC,
  val filteredRecordings: List<RecordingItem> = emptyList(),
  val selectedIds: Set<String> = emptySet(),
  val showDeleteConfirmation: Boolean = false,
  val totalRecordingsCount: Int = 0,
) {
  val isSelectionMode: Boolean
    get() = selectedIds.isNotEmpty()

  val selectedTotalBytes: Long
    get() = filteredRecordings.filter { it.id in selectedIds }.sumOf { it.sizeBytes }
}

private data class FilterCriteria(
  val query: String = "",
  val category: RecordingCategory = RecordingCategory.ALL,
  val dateFilter: DateFilter = DateFilter.ALL_TIME,
  val sortOption: SortOption = SortOption.DATE_DESC,
)

class LibraryViewModel(
  private val recordingsRepository: RecordingsRepository,
) : ViewModel() {

  private val filterCriteria = MutableStateFlow(FilterCriteria())
  private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
  private val showDeleteDialog = MutableStateFlow(false)

  val uiState: StateFlow<LibraryUiState> =
    combine(
        recordingsRepository.recordings,
        filterCriteria,
        selectedIds,
        showDeleteDialog,
      ) { allItems, criteria, currentSelectedIds, confirmDelete ->
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L

        val visibleCategoryChips =
          if (allItems.isNotEmpty()) {
            RecordingCategory.entries.toList()
          } else {
            emptyList()
          }

        val effectiveCategory = criteria.category

        val filtered =
          allItems
            .asSequence()
            .filter { item ->
              if (criteria.query.isBlank()) {
                true
              } else {
                item.title.contains(criteria.query.trim(), ignoreCase = true)
              }
            }
            .filter { item ->
              when (effectiveCategory) {
                RecordingCategory.ALL -> true
                else -> item.category == effectiveCategory
              }
            }
            .filter { item ->
              when (criteria.dateFilter) {
                DateFilter.ALL_TIME -> true
                DateFilter.TODAY -> (now - item.createdAtEpochMs) <= dayMs
                DateFilter.LAST_7_DAYS -> (now - item.createdAtEpochMs) <= 7 * dayMs
                DateFilter.LAST_30_DAYS -> (now - item.createdAtEpochMs) <= 30 * dayMs
              }
            }
            .let { seq ->
              when (criteria.sortOption) {
                SortOption.DATE_DESC -> seq.sortedByDescending { it.createdAtEpochMs }
                SortOption.DATE_ASC -> seq.sortedBy { it.createdAtEpochMs }
                SortOption.SIZE_DESC -> seq.sortedByDescending { it.sizeBytes }
                SortOption.SIZE_ASC -> seq.sortedBy { it.sizeBytes }
                SortOption.DURATION_DESC -> seq.sortedByDescending { it.durationMs }
                SortOption.DURATION_ASC -> seq.sortedBy { it.durationMs }
              }
            }
            .toList()

        val validSelectedIds = currentSelectedIds.intersect(allItems.map { it.id }.toSet())

        LibraryUiState(
          searchQuery = criteria.query,
          selectedCategory = effectiveCategory,
          availableCategories = visibleCategoryChips,
          selectedDateFilter = criteria.dateFilter,
          selectedSortOption = criteria.sortOption,
          filteredRecordings = filtered,
          selectedIds = validSelectedIds,
          showDeleteConfirmation = confirmDelete && validSelectedIds.isNotEmpty(),
          totalRecordingsCount = allItems.size,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
      )

  fun updateSearchQuery(query: String) {
    filterCriteria.update { it.copy(query = query) }
  }

  fun selectCategory(category: RecordingCategory) {
    filterCriteria.update { it.copy(category = category) }
  }

  fun selectDateFilter(dateFilter: DateFilter) {
    filterCriteria.update { it.copy(dateFilter = dateFilter) }
  }

  fun selectSortOption(sortOption: SortOption) {
    filterCriteria.update { it.copy(sortOption = sortOption) }
  }

  fun toggleItemSelection(id: String) {
    selectedIds.update { current ->
      if (id in current) current - id else current + id
    }
  }

  fun selectAllVisible() {
    val visibleIds = uiState.value.filteredRecordings.map { it.id }.toSet()
    selectedIds.update { current ->
      if (current.containsAll(visibleIds) && visibleIds.isNotEmpty()) {
        emptySet()
      } else {
        visibleIds
      }
    }
  }

  fun clearSelection() {
    selectedIds.value = emptySet()
    showDeleteDialog.value = false
  }

  fun requestBatchDelete() {
    if (selectedIds.value.isNotEmpty()) {
      showDeleteDialog.value = true
    }
  }

  fun dismissBatchDeleteDialog() {
    showDeleteDialog.value = false
  }

  fun confirmBatchDelete() {
    val idsToDelete = selectedIds.value
    if (idsToDelete.isNotEmpty()) {
      recordingsRepository.deleteRecordings(idsToDelete)
    }
    selectedIds.value = emptySet()
    showDeleteDialog.value = false
  }

  fun renameRecording(id: String, newTitle: String) {
    recordingsRepository.renameRecording(id, newTitle)
  }

  fun deleteSingleRecording(id: String) {
    recordingsRepository.deleteRecordings(setOf(id))
    selectedIds.update { it - id }
  }

  fun clearAllFilters() {
    filterCriteria.value = FilterCriteria()
  }

  companion object {
    fun provideFactory(recordingsRepository: RecordingsRepository): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          return LibraryViewModel(recordingsRepository) as T
        }
      }
  }
}
