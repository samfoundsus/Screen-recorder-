/*
 * Memory & Hardening Fixes (Phase 2C):
 * 1. Detached the active NavHost screen composition tree when the Activity is STOPPED (in the
 *    background) so the Compose UI and any child views/animations are not kept alive while
 *    recording in the background; navigation backstack and state re-attach automatically on
 *    returning to the foreground.
 * 2. Observed RecordingStateHolder.pendingNavigationRoute to navigate directly to the Library
 *    (or Settings from Floating Controls) when requested.
 */
package com.example.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.AppContainer
import com.example.domain.model.AppSettings
import com.example.domain.model.MainDestination
import com.example.recorder.RecordingStateHolder
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
import com.example.ui.library.LibraryScreen
import com.example.ui.library.LibraryViewModel
import com.example.ui.playback.PlaybackScreen
import com.example.ui.playback.PlaybackViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.MyApplicationTheme

object AppRoutes {
  const val HOME = "home"
  const val LIBRARY = "library"
  const val SETTINGS = "settings"
  const val PLAYBACK = "playback/{recordingId}"

  fun playbackRoute(recordingId: String): String = "playback/$recordingId"
}

@Composable
fun ScreenRecorderApp(
  appContainer: AppContainer,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var isAppInForeground by remember {
    mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> isAppInForeground = true
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_DESTROY -> isAppInForeground = false
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val appSettings by
    appContainer.settingsRepository.settings.collectAsStateWithLifecycle(
      initialValue = AppSettings()
    )

  MyApplicationTheme(
    themeMode = appSettings.appearance.themeMode,
    dynamicColor = appSettings.appearance.dynamicColor,
    pureBlackDark = appSettings.appearance.pureBlackDark,
  ) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val enabledNavItems = appSettings.appearance.navOrder.filter { it.enabled }
    val showBottomBar =
      isAppInForeground &&
        currentRoute in setOf(AppRoutes.HOME, AppRoutes.LIBRARY, AppRoutes.SETTINGS) &&
        enabledNavItems.isNotEmpty()
    val isSettingsInBottomBar =
      enabledNavItems.any { it.destination == MainDestination.SETTINGS }

    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by RecordingStateHolder.errorMessage.collectAsStateWithLifecycle()
    val pendingNavigationRoute by
      RecordingStateHolder.pendingNavigationRoute.collectAsStateWithLifecycle()

    LaunchedEffect(isAppInForeground) {
      if (isAppInForeground) {
        RecordingStateHolder.loadPendingSnackbarIfNeeded(context)
      }
    }

    LaunchedEffect(pendingNavigationRoute, isAppInForeground) {
      val targetRoute = pendingNavigationRoute
      if (isAppInForeground && !targetRoute.isNullOrBlank()) {
        RecordingStateHolder.consumeNavigationRoute()
        if (currentRoute != targetRoute) {
          navController.navigate(targetRoute) {
            popUpTo(navController.graph.findStartDestination().id) {
              saveState = true
            }
            launchSingleTop = true
            restoreState = true
          }
        }
      }
    }

    LaunchedEffect(errorMessage, isAppInForeground) {
      val msg = errorMessage
      if (isAppInForeground && !msg.isNullOrBlank()) {
        snackbarHostState.showSnackbar(
          message = msg,
          duration = SnackbarDuration.Short,
        )
        RecordingStateHolder.consumeErrorMessage()
      }
    }

    Scaffold(
      modifier = modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.surface,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      bottomBar = {
        if (showBottomBar) {
          NavigationBar(modifier = Modifier.testTag("main_bottom_navigation")) {
            enabledNavItems.forEach { navConfig ->
              val destination = navConfig.destination
              val selected = currentRoute == destination.id
              NavigationBarItem(
                selected = selected,
                alwaysShowLabel = true,
                onClick = {
                  if (currentRoute != destination.id) {
                    navController.navigate(destination.id) {
                      popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                      }
                      launchSingleTop = true
                      restoreState = true
                    }
                  }
                },
                icon = {
                  Icon(
                    imageVector = destination.icon(selected = selected),
                    contentDescription = destination.label,
                  )
                },
                label = { Text(text = destination.label) },
                modifier = Modifier.testTag("bottom_nav_${destination.id}"),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      if (isAppInForeground) {
        NavHost(
          navController = navController,
          startDestination = AppRoutes.HOME,
          enterTransition = { fadeIn(animationSpec = tween(180)) },
          exitTransition = { fadeOut(animationSpec = tween(140)) },
          popEnterTransition = { EnterTransition.None },
          popExitTransition = { ExitTransition.None },
          modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
          composable(AppRoutes.HOME) {
            val homeViewModel: HomeViewModel =
              viewModel(
                factory =
                  HomeViewModel.provideFactory(
                    recorderController = appContainer.recorderController,
                    settingsRepository = appContainer.settingsRepository,
                    recordingsRepository = appContainer.recordingsRepository,
                    capabilitiesProvider = appContainer.capabilitiesProvider,
                  )
              )
            val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()

            HomeScreen(
              uiState = uiState,
              onPrimaryRecordAction = homeViewModel::onPrimaryRecordAction,
              onPauseResumeAction = homeViewModel::onPauseResumeAction,
              onOpenResolutionPicker = homeViewModel::onOpenResolutionPicker,
              onOpenFpsPicker = homeViewModel::onOpenFpsPicker,
              onSelectResolution = homeViewModel::selectResolution,
              onSelectFps = homeViewModel::selectFps,
              onToggleMic = homeViewModel::toggleMic,
              onOpenRecording = { recordingId ->
                navController.navigate(AppRoutes.playbackRoute(recordingId))
              },
              onNavigateToLibrary = {
                navController.navigate(AppRoutes.LIBRARY) {
                  popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                  launchSingleTop = true
                  restoreState = true
                }
              },
              onNavigateToSettings = {
                navController.navigate(AppRoutes.SETTINGS) {
                  popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                  launchSingleTop = true
                  restoreState = true
                }
              },
              showSettingsShortcutInTopBar = !isSettingsInBottomBar,
            )
          }

          composable(AppRoutes.LIBRARY) {
            val libraryViewModel: LibraryViewModel =
              viewModel(
                factory = LibraryViewModel.provideFactory(appContainer.recordingsRepository)
              )
            val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

            LibraryScreen(
              uiState = uiState,
              onSearchQueryChange = libraryViewModel::updateSearchQuery,
              onSelectCategory = libraryViewModel::selectCategory,
              onSelectDateFilter = libraryViewModel::selectDateFilter,
              onSelectSortOption = libraryViewModel::selectSortOption,
              onToggleItemSelection = libraryViewModel::toggleItemSelection,
              onSelectAllVisible = libraryViewModel::selectAllVisible,
              onClearSelection = libraryViewModel::clearSelection,
              onRequestBatchDelete = libraryViewModel::requestBatchDelete,
              onDismissBatchDelete = libraryViewModel::dismissBatchDeleteDialog,
              onConfirmBatchDelete = libraryViewModel::confirmBatchDelete,
              onRenameRecording = libraryViewModel::renameRecording,
              onDeleteSingleRecording = libraryViewModel::deleteSingleRecording,
              onClearFilters = libraryViewModel::clearAllFilters,
              onOpenRecording = { recordingId ->
                navController.navigate(AppRoutes.playbackRoute(recordingId))
              },
            )
          }

          composable(AppRoutes.SETTINGS) {
            val settingsViewModel: SettingsViewModel =
              viewModel(
                factory =
                  SettingsViewModel.provideFactory(
                    settingsRepository = appContainer.settingsRepository,
                    capabilitiesProvider = appContainer.capabilitiesProvider,
                  )
              )
            val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()

            SettingsScreen(
              uiState = uiState,
              onOpenResolutionPicker = settingsViewModel::onOpenResolutionPicker,
              onOpenFpsPicker = settingsViewModel::onOpenFpsPicker,
              onRetestDeviceSupport = settingsViewModel::retestDeviceSupport,
              onRefreshMemoryDiagnostics = { settingsViewModel.refreshMemoryDiagnostics(context) },
              onUpdateShowInQuickSettings = { enabled ->
                settingsViewModel.updateShowInQuickSettings(context, enabled)
              },
              onUpdateCountdownSeconds = settingsViewModel::updateCountdownSeconds,
              onUpdateResolution = settingsViewModel::updateResolution,
              onUpdateFps = settingsViewModel::updateFps,
              onUpdateBitrate = settingsViewModel::updateBitrate,
              onUpdateCodec = settingsViewModel::updateCodec,
              onUpdateContainer = settingsViewModel::updateContainer,
              onUpdateOrientation = settingsViewModel::updateOrientation,
              onUpdateAudioSource = settingsViewModel::updateAudioSource,
              onUpdateMicEnabled = settingsViewModel::updateMicEnabled,
              onUpdateNoiseSuppression = settingsViewModel::updateNoiseSuppression,
              onUpdateAudioChannels = settingsViewModel::updateAudioChannels,
              onUpdateFloatingSize = settingsViewModel::updateFloatingSize,
              onUpdateFloatingOpacity = settingsViewModel::updateFloatingOpacity,
              onUpdateFloatingAutoHide = settingsViewModel::updateFloatingAutoHide,
              onUpdateFloatingEdgeSnapping = settingsViewModel::updateFloatingEdgeSnapping,
              onUpdateFloatingKeepEdgeHandleWhileRecording =
                settingsViewModel::updateFloatingKeepEdgeHandleWhileRecording,
              onUpdateFloatingHideControlsFromVideo =
                settingsViewModel::updateFloatingHideControlsFromVideo,
              onUpdateThemeMode = settingsViewModel::updateThemeMode,
              onUpdatePureBlackDark = settingsViewModel::updatePureBlackDark,
              onMoveNavDestination = settingsViewModel::moveNavDestination,
              onToggleNavDestination = settingsViewModel::toggleNavDestination,
              onResetAllSettings = settingsViewModel::resetAllSettings,
            )
          }

          composable(
            route = AppRoutes.PLAYBACK,
            arguments = listOf(navArgument("recordingId") { type = NavType.StringType }),
          ) { entry ->
            val recordingId = entry.arguments?.getString("recordingId").orEmpty()
            val playbackViewModel: PlaybackViewModel =
              viewModel(
                factory =
                  PlaybackViewModel.provideFactory(
                    recordingId = recordingId,
                    recordingsRepository = appContainer.recordingsRepository,
                  )
              )
            val uiState by playbackViewModel.uiState.collectAsStateWithLifecycle()

            PlaybackScreen(
              uiState = uiState,
              onNavigateBack = { navController.popBackStack() },
              onRenameRecording = playbackViewModel::renameRecording,
              onDeleteRecording = {
                playbackViewModel.deleteRecording { navController.popBackStack() }
              },
            )
          }
        }
      } else {
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding))
      }
    }
  }
}

private fun MainDestination.icon(selected: Boolean): ImageVector {
  return when (this) {
    MainDestination.HOME ->
      if (selected) Icons.Filled.Videocam else Icons.Outlined.Videocam
    MainDestination.LIBRARY ->
      if (selected) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary
    MainDestination.SETTINGS ->
      if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
  }
}
