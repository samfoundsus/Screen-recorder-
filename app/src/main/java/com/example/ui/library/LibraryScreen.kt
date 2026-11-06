package com.example.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.repository.RecordingMediaCache
import com.example.domain.model.DateFilter
import com.example.domain.model.RecordingCategory
import com.example.domain.model.RecordingItem
import com.example.domain.model.SortOption
import com.example.ui.components.FormatUtils
import com.example.ui.components.RecordingThumbnailBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  uiState: LibraryUiState,
  onSearchQueryChange: (String) -> Unit,
  onSelectCategory: (RecordingCategory) -> Unit,
  onSelectDateFilter: (DateFilter) -> Unit,
  onSelectSortOption: (SortOption) -> Unit,
  onToggleItemSelection: (String) -> Unit,
  onSelectAllVisible: () -> Unit,
  onClearSelection: () -> Unit,
  onRequestBatchDelete: () -> Unit,
  onDismissBatchDelete: () -> Unit,
  onConfirmBatchDelete: () -> Unit,
  onClearFilters: () -> Unit,
  onOpenRecording: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (uiState.isSelectionMode) {
    BackHandler { onClearSelection() }
  }

  val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
  var showSortFilterSheet by rememberSaveable { mutableStateOf(false) }

  Scaffold(
    modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
    containerColor = MaterialTheme.colorScheme.surface,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      if (uiState.isSelectionMode) {
        TopAppBar(
          navigationIcon = {
            IconButton(
              onClick = onClearSelection,
              modifier = Modifier.testTag("library_clear_selection_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Clear selection",
              )
            }
          },
          title = {
            Text(
              text = "${uiState.selectedIds.size} selected",
              style = MaterialTheme.typography.titleLarge,
            )
          },
          actions = {
            IconButton(
              onClick = onSelectAllVisible,
              modifier = Modifier.testTag("library_select_all_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.SelectAll,
                contentDescription = "Select all",
              )
            }
            IconButton(
              onClick = onRequestBatchDelete,
              modifier = Modifier.testTag("library_batch_delete_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Delete selected",
                tint = MaterialTheme.colorScheme.error,
              )
            }
          },
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
      } else {
        val count = uiState.filteredRecordings.size
        val subtitleText = "$count ${if (count == 1) "recording" else "recordings"}"

        TopAppBar(
          title = {
            Column {
              Text(
                text = stringResource(R.string.nav_library),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
            }
          },
          actions = {
            TextButton(
              onClick = { showSortFilterSheet = true },
              modifier = Modifier.testTag("library_sort_filter_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(text = "Sort & filter")
            }
            if (uiState.filteredRecordings.isNotEmpty()) {
              IconButton(
                onClick = {
                  uiState.filteredRecordings.firstOrNull()?.let { onToggleItemSelection(it.id) }
                },
                modifier = Modifier.testTag("library_enter_select_mode_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Checklist,
                  contentDescription = "Select recordings",
                )
              }
            }
          },
          scrollBehavior = scrollBehavior,
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
              scrolledContainerColor = MaterialTheme.colorScheme.surface,
            ),
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
            .testTag("library_lazy_column"),
        contentPadding = PaddingValues(bottom = 32.dp),
      ) {
        // Collapsing Search Bar directly below top bar
        item(key = "search_bar") {
          OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(stringResource(R.string.library_search_hint)) },
            leadingIcon = {
              Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search",
              )
            },
            trailingIcon = {
              if (uiState.searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                  Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear search",
                  )
                }
              }
            },
            singleLine = true,
            shape = CircleShape,
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainer,
              ),
            modifier =
              Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("library_search_input"),
          )
        }

        // Category Filter Chips Row (hidden when there is only one category)
        if (uiState.availableCategories.size > 1) {
          item(key = "category_chips") {
            val chipColors =
              FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
              )

            LazyRow(
              contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth().testTag("library_category_row"),
            ) {
              items(uiState.availableCategories, key = { it.name }) { category ->
                val selected = uiState.selectedCategory == category
                FilterChip(
                  selected = selected,
                  onClick = { onSelectCategory(category) },
                  label = { Text(category.label) },
                  leadingIcon =
                    if (selected) {
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
                  colors = chipColors,
                  modifier = Modifier.testTag("library_category_${category.name.lowercase()}"),
                )
              }
            }
          }
        }

        // Recordings List or Minimal Empty State
        val items = uiState.filteredRecordings
        if (items.isEmpty()) {
          item(key = "empty_state") {
            val hasActiveFilters =
              uiState.totalRecordingsCount > 0 &&
                (uiState.searchQuery.isNotBlank() ||
                  uiState.selectedCategory != RecordingCategory.ALL ||
                  uiState.selectedDateFilter != DateFilter.ALL_TIME)
            LibraryMinimalEmptyState(
              hasActiveFilters = hasActiveFilters,
              onResetFilters = onClearFilters,
            )
          }
        } else {
          items(
            items = items,
            key = { item -> item.id },
          ) { item ->
            val isSelected = item.id in uiState.selectedIds
            FlatLibraryRecordingRow(
              item = item,
              isSelected = isSelected,
              isSelectionMode = uiState.isSelectionMode,
              onClick = {
                if (uiState.isSelectionMode) {
                  onToggleItemSelection(item.id)
                } else {
                  onOpenRecording(item.id)
                }
              },
              onLongClick = { onToggleItemSelection(item.id) },
            )
            Spacer(modifier = Modifier.height(6.dp))
          }
        }
      }
    }
  }

  if (showSortFilterSheet) {
    SortAndFilterBottomSheet(
      selectedSortOption = uiState.selectedSortOption,
      selectedDateFilter = uiState.selectedDateFilter,
      onSelectSortOption = onSelectSortOption,
      onSelectDateFilter = onSelectDateFilter,
      onReset = {
        onSelectSortOption(SortOption.DATE_DESC)
        onSelectDateFilter(DateFilter.ALL_TIME)
      },
      onDismiss = { showSortFilterSheet = false },
    )
  }

  if (uiState.showDeleteConfirmation) {
    AlertDialog(
      onDismissRequest = onDismissBatchDelete,
      title = {
        Text(text = stringResource(R.string.library_delete_confirm_title))
      },
      text = {
        val count = uiState.selectedIds.size
        val formattedSize = FormatUtils.formatFileSize(uiState.selectedTotalBytes)
        Text(
          text =
            "Permanently delete $count ${if (count == 1) "recording" else "recordings"} ($formattedSize)? This action cannot be undone.",
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        TextButton(
          onClick = onConfirmBatchDelete,
          modifier = Modifier.testTag("library_confirm_delete_button"),
        ) {
          Text(
            text = "Delete",
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
      dismissButton = {
        TextButton(
          onClick = onDismissBatchDelete,
          modifier = Modifier.testTag("library_cancel_delete_button"),
        ) {
          Text("Cancel")
        }
      },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlatLibraryRecordingRow(
  item: RecordingItem,
  isSelected: Boolean,
  isSelectionMode: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
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

  val rowBackground =
    if (isSelected) {
      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    } else {
      MaterialTheme.colorScheme.surface
    }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(rowBackground)
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        )
        .padding(horizontal = 24.dp, vertical = 16.dp)
        .testTag("library_item_${item.id}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isSelectionMode) {
      Checkbox(
        checked = isSelected,
        onCheckedChange = { onClick() },
        modifier = Modifier.padding(end = 12.dp),
      )
    }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortAndFilterBottomSheet(
  selectedSortOption: SortOption,
  selectedDateFilter: DateFilter,
  onSelectSortOption: (SortOption) -> Unit,
  onSelectDateFilter: (DateFilter) -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(bottom = 28.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Sort & filter",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onReset) {
          Text("Reset")
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = "Sort by",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.height(6.dp))

      SortOption.entries.forEach { option ->
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .clickable { onSelectSortOption(option) }
              .padding(vertical = 8.dp, horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = selectedSortOption == option,
            onClick = null,
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = option.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = "Date range",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.height(6.dp))

      DateFilter.entries.forEach { filter ->
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .clickable { onSelectDateFilter(filter) }
              .padding(vertical = 8.dp, horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = selectedDateFilter == filter,
            onClick = null,
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = filter.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

@Composable
private fun LibraryMinimalEmptyState(
  hasActiveFilters: Boolean,
  onResetFilters: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (!hasActiveFilters) {
      Text(
        text = "No recordings yet",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    } else {
      Text(
        text = "No matching recordings",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(12.dp))
      TextButton(
        onClick = onResetFilters,
        modifier = Modifier.testTag("library_reset_filters_button"),
      ) {
        Text("Reset filters")
      }
    }
  }
}
