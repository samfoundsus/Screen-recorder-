package com.example.recorder

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.R
import com.example.domain.model.RecorderState
import com.example.recorder.ipc.RecorderSettingsSnapshot
import com.example.ui.navigation.AppRoutes
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lightweight plain-View floating controls controller owned by [RecorderService].
 * Runs entirely in the `:recorder` process without DataStore, Room, Compose, or Media3 dependencies.
 *
 * Theming:
 * - Builds all overlay views with a themed context wrapping the service context in
 *   `Theme.Material3.DayNight.NoActionBar` followed by `DynamicColors.wrapContextIfAvailable()`.
 * - Reads all colors from Material 3 theme attributes via [MaterialColors.getColor] with zero
 *   hardcoded color literals.
 * - Live-updates colors when system configuration changes.
 */
class FloatingControlsController(
  private val context: Context,
  private var currentSnapshot: RecorderSettingsSnapshot,
  private val onStartRequested: () -> Unit,
  private val onPauseRequested: () -> Unit,
  private val onResumeRequested: () -> Unit,
  private val onStopRequested: () -> Unit,
  private val onCloseRequested: () -> Unit,
  private val onControlsVisibilityChanged: ((Boolean) -> Unit)? = null,
  private val onPositionChanged: ((portraitX: Float, portraitY: Float, landscapeX: Float, landscapeY: Float) -> Unit)? = null,
  private val onQuickSettingChanged: ((resolution: String, fps: Int, audioSource: String) -> Unit)? = null,
  private val elapsedMsSupplier: () -> Long = { 0L },
) {

  companion object {
    private const val PREFS_POSITION = "pixel_floating_controls_position"
    private const val KEY_PORTRAIT_X_FRAC = "portrait_x_frac"
    private const val KEY_PORTRAIT_Y_FRAC = "portrait_y_frac"
    private const val KEY_LANDSCAPE_X_FRAC = "landscape_x_frac"
    private const val KEY_LANDSCAPE_Y_FRAC = "landscape_y_frac"

    private const val AUTO_HIDE_DELAY_MS = 3_000L
    private const val SNAP_ANIMATION_MS = 180L
    private const val TIMER_TICK_MS = 1_000L

    private const val EDGE_HANDLE_WIDTH_DP = 4
    private const val EDGE_HANDLE_HEIGHT_DP = 48
    private const val EDGE_HANDLE_ALPHA = 0.60f

    private val AUDIO_SOURCES =
      listOf(
        "Device audio + Mic",
        "Device audio only",
        "Microphone only",
        "Mute (No audio)",
      )
  }

  private val windowManager =
    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val mainHandler = Handler(Looper.getMainLooper())

  private var themedContext: Context = createOverlayThemedContext(context)

  private var currentRecorderState: RecorderState = RecorderState.IDLE
  private var isInRecordingSession: Boolean = false
  private var currentElapsedMs: Long = 0L

  // Window views
  private var pillRootView: FrameLayout? = null
  private var pillContentLayout: LinearLayout? = null
  private var edgeHandleView: View? = null
  private var pillLayoutParams: WindowManager.LayoutParams? = null

  private var timerTextView: TextView? = null
  private var primaryButton: ImageButton? = null
  private var secondaryButton: ImageButton? = null
  private var tertiaryButton: ImageButton? = null

  private var panelRootView: LinearLayout? = null
  private var panelLayoutParams: WindowManager.LayoutParams? = null
  private val panelRowViews = mutableListOf<LinearLayout>()
  private val panelLabelViews = mutableListOf<TextView>()
  private var panelResolutionValueView: TextView? = null
  private var panelFpsValueView: TextView? = null
  private var panelAudioValueView: TextView? = null
  private var panelDividerView: View? = null
  private var panelMoreSettingsView: TextView? = null

  private var snapAnimator: ValueAnimator? = null
  private var isShrunkToEdgeHandle: Boolean = false
  private var isDockedLeft: Boolean = false

  // Reusable StringBuilder for 1 Hz timer updates (zero StringBuilder allocations per tick)
  private val timerStringBuilder = StringBuilder(8)

  private val autoHideRunnable = Runnable {
    if (
      pillRootView != null &&
        panelRootView == null &&
        shouldAutoCollapseToHandle() &&
        !isShrunkToEdgeHandle
    ) {
      shrinkToEdgeHandle()
    }
  }

  private val timerTickRunnable =
    object : Runnable {
      override fun run() {
        val tv = timerTextView
        if (
          pillRootView == null ||
            tv == null ||
            isShrunkToEdgeHandle ||
            currentRecorderState == RecorderState.IDLE
        ) {
          return
        }
        currentElapsedMs = elapsedMsSupplier()
        updateTimerTextInPlace(tv, currentElapsedMs)
        if (currentRecorderState == RecorderState.RECORDING && !isShrunkToEdgeHandle) {
          mainHandler.postDelayed(this, TIMER_TICK_MS)
        }
      }
    }

  val isAttached: Boolean
    get() = pillRootView != null

  val keepEdgeHandleWhileRecording: Boolean
    get() = currentSnapshot.keepEdgeHandleWhileRecording

  fun updateSettings(snapshot: RecorderSettingsSnapshot) {
    val prevSize = currentSnapshot.floatingControlsSize
    currentSnapshot = snapshot
    refreshPanelValues()
    if (pillRootView != null) {
      if (prevSize != snapshot.floatingControlsSize) {
        rebuildPillIfAttached()
      } else {
        syncWindowFlagSecure()
        applyIdleOpacity()
        if (shouldAutoCollapseToHandle()) {
          scheduleAutoHide()
        } else if (isShrunkToEdgeHandle && !isRecordingMode()) {
          expandFromEdgeHandle()
        }
      }
    }
  }

  fun updateRecorderState(state: RecorderState) {
    val previousState = currentRecorderState
    currentRecorderState = state
    isInRecordingSession = state != RecorderState.IDLE

    if (pillRootView != null) {
      syncWindowFlagSecure()
      bindPillState()
      if (state != RecorderState.IDLE) {
        if (previousState == RecorderState.IDLE && !isShrunkToEdgeHandle) {
          shrinkToEdgeHandle()
        } else {
          applyIdleOpacity()
          scheduleAutoHide()
        }
      } else {
        applyIdleOpacity()
        scheduleAutoHide()
      }
    }
  }

  fun updateElapsedTime(elapsedMs: Long) {
    currentElapsedMs = elapsedMs
    val tv = timerTextView
    if (tv != null && !isShrunkToEdgeHandle && currentRecorderState != RecorderState.IDLE) {
      updateTimerTextInPlace(tv, elapsedMs)
    }
  }

  fun showOverlay(): Boolean {
    if (!Settings.canDrawOverlays(context)) {
      onControlsVisibilityChanged?.invoke(false)
      return false
    }
    if (pillRootView != null) {
      themedContext = createOverlayThemedContext(context)
      onControlsVisibilityChanged?.invoke(true)
      reTintOverlayViews()
      applyIdleOpacity()
      scheduleAutoHide()
      return true
    }

    return try {
      themedContext = createOverlayThemedContext(context)
      attachPillWindow()
      onControlsVisibilityChanged?.invoke(true)
      scheduleAutoHide()
      true
    } catch (t: Throwable) {
      ScreenRecordingEngine.debugLog("Failed to attach floating controls overlay: ${t.message}", t)
      removeOverlayCompletely()
      false
    }
  }

  /**
   * Called when the user taps Start on the expanded pill or when recording starts while controls
   * are active and "Keep edge handle while recording" is enabled.
   */
  fun collapseToEdgeHandleForRecording(): Boolean {
    if (!Settings.canDrawOverlays(context)) {
      removeOverlayCompletely()
      return false
    }
    isInRecordingSession = true
    if (pillRootView == null) {
      val shown = showOverlay()
      if (!shown) return false
    }
    dismissSettingsPanel()
    syncWindowFlagSecure()
    if (!isShrunkToEdgeHandle) {
      shrinkToEdgeHandle()
    } else {
      applyIdleOpacity()
      updateViewLayoutSafely(pillRootView, pillLayoutParams)
    }
    return true
  }

  /**
   * Restores idle pill after recording stops.
   */
  fun restoreIdlePillAfterRecording(): Boolean {
    if (!Settings.canDrawOverlays(context)) {
      removeOverlayCompletely()
      return false
    }
    isInRecordingSession = false
    currentRecorderState = RecorderState.IDLE
    if (pillRootView == null) {
      return showOverlay()
    }
    syncWindowFlagSecure()
    bindPillState()
    if (isShrunkToEdgeHandle) {
      expandFromEdgeHandle(resetPosition = true)
    } else {
      applyIdleOpacity()
      updateViewLayoutSafely(pillRootView, pillLayoutParams)
      scheduleAutoHide()
    }
    onControlsVisibilityChanged?.invoke(true)
    return true
  }

  /**
   * Completely removes the floating controls and any open quick-settings panel from
   * [WindowManager] via `removeViewImmediate` / `removeView` (not `View.GONE`).
   */
  fun removeOverlayCompletely() {
    mainHandler.removeCallbacks(autoHideRunnable)
    mainHandler.removeCallbacks(timerTickRunnable)
    snapAnimator?.cancel()
    snapAnimator = null
    dismissSettingsPanel()

    val view = pillRootView
    pillRootView = null
    pillContentLayout = null
    edgeHandleView = null
    pillLayoutParams = null
    timerTextView = null
    primaryButton = null
    secondaryButton = null
    tertiaryButton = null
    isShrunkToEdgeHandle = false
    isInRecordingSession = false

    if (view != null) {
      try {
        windowManager.removeViewImmediate(view)
      } catch (_: Throwable) {
        try {
          windowManager.removeView(view)
        } catch (_: Throwable) {}
      }
    }
    onControlsVisibilityChanged?.invoke(false)
  }

  fun onConfigurationChanged(newConfig: Configuration) {
    themedContext = createOverlayThemedContext(context, newConfig)
    val root = pillRootView ?: return
    val params = pillLayoutParams ?: return
    reTintOverlayViews()

    val (screenW, screenH) = getScreenDimensions()
    val (savedXFrac, savedYFrac) = loadSavedPositionFractions(isLandscape(newConfig))
    val margin = dpToPx(8)
    val pillW = pillContentLayout?.measuredWidth?.takeIf { it > 0 } ?: dpToPx(156)
    val pillH = pillContentLayout?.measuredHeight?.takeIf { it > 0 } ?: dpToPx(56)
    val maxX = (screenW - pillW - margin).coerceAtLeast(margin)
    val maxY = (screenH - pillH - dpToPx(48)).coerceAtLeast(margin)

    val targetPillX = (savedXFrac * maxX).roundToInt().coerceIn(margin, maxX)
    val targetPillY = (savedYFrac * maxY).roundToInt().coerceIn(margin, maxY)
    isDockedLeft = targetPillX < (screenW - pillW) / 2

    if (isShrunkToEdgeHandle) {
      val handleW = dpToPx(EDGE_HANDLE_WIDTH_DP)
      params.x = if (isDockedLeft) 0 else (screenW - handleW).coerceAtLeast(0)
      params.y = targetPillY
    } else {
      params.x = targetPillX
      params.y = targetPillY
    }
    updateViewLayoutSafely(root, params)

    val panel = panelRootView
    val pParams = panelLayoutParams
    if (panel != null && pParams != null) {
      val panelWidth = dpToPx(244)
      val estPanelH = dpToPx(210)
      pParams.x =
        (params.x + (pillW - panelWidth) / 2).coerceIn(
          margin,
          (screenW - panelWidth - margin).coerceAtLeast(margin),
        )
      val spaceBelow = screenH - (params.y + pillH)
      pParams.y =
        if (spaceBelow >= estPanelH + margin) {
          params.y + pillH + dpToPx(8)
        } else {
          (params.y - estPanelH - dpToPx(8)).coerceAtLeast(margin)
        }
      updateViewLayoutSafely(panel, pParams)
    }

    scheduleAutoHide()
  }

  fun destroy() {
    removeOverlayCompletely()
  }

  private fun isRecordingMode(): Boolean {
    return currentRecorderState != RecorderState.IDLE || isInRecordingSession
  }

  private fun shouldApplyFlagSecure(): Boolean {
    return isRecordingMode() && currentSnapshot.hideControlsFromVideo
  }

  private fun shouldAutoCollapseToHandle(): Boolean {
    return isRecordingMode() || currentSnapshot.floatingControlsAutoHide
  }

  private fun syncWindowFlagSecure() {
    val secure = shouldApplyFlagSecure()
    val root = pillRootView ?: return
    val params = pillLayoutParams ?: return
    val oldFlags = params.flags
    val newFlags =
      if (secure) {
        oldFlags or WindowManager.LayoutParams.FLAG_SECURE
      } else {
        oldFlags and WindowManager.LayoutParams.FLAG_SECURE.inv()
      }
    if (oldFlags != newFlags) {
      params.flags = newFlags
      updateViewLayoutSafely(root, params)
    }

    val panel = panelRootView
    val pParams = panelLayoutParams
    if (panel != null && pParams != null) {
      val oldPanelFlags = pParams.flags
      val newPanelFlags =
        if (secure) {
          oldPanelFlags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
          oldPanelFlags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
      if (oldPanelFlags != newPanelFlags) {
        pParams.flags = newPanelFlags
        updateViewLayoutSafely(panel, pParams)
      }
    }
  }

  private fun rebuildPillIfAttached() {
    if (pillRootView == null) return
    val wasShrunk = isShrunkToEdgeHandle
    val wasInRecordingSession = isInRecordingSession
    dismissSettingsPanel()
    removeOverlayCompletely()
    isInRecordingSession = wasInRecordingSession
    if (showOverlay() && wasShrunk) {
      shrinkToEdgeHandle()
    }
  }

  private fun reTintOverlayViews() {
    val palette = resolvePalette()

    pillContentLayout?.background = createPillContainerDrawable(palette)
    timerTextView?.setTextColor(palette.colorOnSurface)

    edgeHandleView?.apply {
      background = createEdgeHandleDrawable(palette)
      alpha = EDGE_HANDLE_ALPHA
    }

    bindPillState()

    panelRootView?.background = createPanelContainerDrawable(palette)
    panelRowViews.forEach { row ->
      row.background =
        createRoundedRowRipple(palette.colorControlHighlight, palette.colorSurfaceContainerHigh)
    }
    panelLabelViews.forEach { labelTv ->
      labelTv.setTextColor(palette.colorOnSurface)
    }
    listOfNotNull(panelResolutionValueView, panelFpsValueView, panelAudioValueView).forEach { chip ->
      chip.setTextColor(palette.colorOnSecondaryContainer)
      chip.background = createSelectedChipDrawable(palette)
    }
    panelDividerView?.apply {
      setBackgroundColor(palette.colorOnSurfaceVariant)
      alpha = 0.2f
    }
    panelMoreSettingsView?.apply {
      setTextColor(palette.colorOnSurfaceVariant)
      background =
        createRoundedRowRipple(palette.colorControlHighlight, palette.colorSurfaceContainerHigh)
    }
  }

  private fun createPillContainerDrawable(palette: OverlayPalette): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dpToPx(999).toFloat()
      setColor(palette.colorSurfaceContainerHigh)
    }
  }

  private fun createEdgeHandleDrawable(palette: OverlayPalette): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dpToPx(999).toFloat()
      setColor(palette.colorPrimary)
    }
  }

  private fun createPanelContainerDrawable(palette: OverlayPalette): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dpToPx(24).toFloat()
      setColor(palette.colorSurfaceContainerHigh)
    }
  }

  private fun createSelectedChipDrawable(palette: OverlayPalette): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dpToPx(999).toFloat()
      setColor(palette.colorSecondaryContainer)
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun attachPillWindow() {
    val palette = resolvePalette()
    val sizeSpec = resolveSizeSpec(currentSnapshot.floatingControlsSize)
    val (screenW, screenH) = getScreenDimensions()
    val landscape = isLandscape(themedContext.resources.configuration)
    val (savedXFrac, savedYFrac) = loadSavedPositionFractions(landscape)

    val rootContainer = FrameLayout(themedContext)

    // 1. Expanded pill container
    val pillLayout =
      LinearLayout(themedContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
          sizeSpec.horizontalPaddingPx,
          sizeSpec.verticalPaddingPx,
          sizeSpec.horizontalPaddingPx,
          sizeSpec.verticalPaddingPx,
        )
        background = createPillContainerDrawable(palette)
        elevation = dpToPx(4).toFloat()
      }

    // Timer text (visible when expanded during recording/paused)
    val timerTv =
      TextView(themedContext).apply {
        setTextColor(palette.colorOnSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSpec.timerTextSp)
        typeface = Typeface.MONOSPACE
        setPadding(dpToPx(8), 0, dpToPx(6), 0)
        visibility = View.GONE
      }
    pillLayout.addView(
      timerTv,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      ),
    )

    val btn1 = createRoundIconButton(sizeSpec, palette)
    val btn2 = createRoundIconButton(sizeSpec, palette)
    val btn3 = createRoundIconButton(sizeSpec, palette)

    val spacing = dpToPx(6)
    pillLayout.addView(
      btn1,
      LinearLayout.LayoutParams(sizeSpec.buttonSizePx, sizeSpec.buttonSizePx),
    )
    pillLayout.addView(
      btn2,
      LinearLayout.LayoutParams(sizeSpec.buttonSizePx, sizeSpec.buttonSizePx).apply {
        marginStart = spacing
      },
    )
    pillLayout.addView(
      btn3,
      LinearLayout.LayoutParams(sizeSpec.buttonSizePx, sizeSpec.buttonSizePx).apply {
        marginStart = spacing
      },
    )

    // 2. Thin edge handle (4dp wide x 48dp tall, colorPrimary at 60% opacity)
    val handleView =
      View(themedContext).apply {
        background = createEdgeHandleDrawable(palette)
        alpha = EDGE_HANDLE_ALPHA
        elevation = dpToPx(2).toFloat()
        visibility = View.GONE
      }

    rootContainer.addView(
      pillLayout,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
      ),
    )
    rootContainer.addView(
      handleView,
      FrameLayout.LayoutParams(
        dpToPx(EDGE_HANDLE_WIDTH_DP),
        dpToPx(EDGE_HANDLE_HEIGHT_DP),
        Gravity.CENTER_VERTICAL,
      ),
    )

    val margin = dpToPx(8)
    val estimatedWidth = dpToPx(156)
    val estimatedHeight = dpToPx(56)
    val maxX = (screenW - estimatedWidth - margin).coerceAtLeast(margin)
    val maxY = (screenH - estimatedHeight - dpToPx(48)).coerceAtLeast(margin)
    val initialX = (savedXFrac * maxX).roundToInt().coerceIn(margin, maxX)
    val initialY = (savedYFrac * maxY).coerceIn(margin.toFloat(), maxY.toFloat()).roundToInt()
    isDockedLeft = initialX < (screenW - estimatedWidth) / 2

    var windowFlags =
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (shouldApplyFlagSecure()) {
      windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_SECURE
    }

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        windowFlags,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = initialX
        y = initialY
      }

    pillRootView = rootContainer
    pillContentLayout = pillLayout
    edgeHandleView = handleView
    pillLayoutParams = params
    timerTextView = timerTv
    primaryButton = btn1
    secondaryButton = btn2
    tertiaryButton = btn3
    isShrunkToEdgeHandle = false

    installDragAndTouchHandling(rootContainer, handleView, listOf(btn1, btn2, btn3))
    bindPillState()

    val shouldStartShrunk = isShrunkToEdgeHandle || isInRecordingSession
    if (shouldStartShrunk) {
      isShrunkToEdgeHandle = true
      pillLayout.visibility = View.GONE
      handleView.visibility = View.VISIBLE
      val handleW = dpToPx(EDGE_HANDLE_WIDTH_DP)
      params.x = if (isDockedLeft) 0 else (screenW - handleW).coerceAtLeast(0)
    }

    applyIdleOpacity()
    windowManager.addView(rootContainer, params)
  }

  private fun createRoundIconButton(
    sizeSpec: PillSizeSpec,
    palette: OverlayPalette,
  ): ImageButton {
    return ImageButton(themedContext).apply {
      scaleType = ImageView.ScaleType.CENTER_INSIDE
      val pad = ((sizeSpec.buttonSizePx - sizeSpec.iconSizePx) / 2).coerceAtLeast(dpToPx(6))
      setPadding(pad, pad, pad, pad)
      background =
        createRoundButtonBackground(
          fillColor = null,
          rippleColor = palette.colorControlHighlight,
          maskColor = palette.colorSurfaceContainerHigh,
        )
    }
  }

  private fun createRoundButtonBackground(
    fillColor: Int?,
    rippleColor: Int,
    maskColor: Int,
  ): RippleDrawable {
    val mask =
      GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(maskColor)
      }
    val content =
      if (fillColor != null) {
        GradientDrawable().apply {
          shape = GradientDrawable.OVAL
          setColor(fillColor)
        }
      } else {
        null
      }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
  }

  private fun bindPillState() {
    val palette = resolvePalette()
    val tv = timerTextView ?: return
    val btn1 = primaryButton ?: return
    val btn2 = secondaryButton ?: return
    val btn3 = tertiaryButton ?: return

    mainHandler.removeCallbacks(timerTickRunnable)
    tv.setTextColor(palette.colorOnSurface)

    when (currentRecorderState) {
      RecorderState.IDLE -> {
        tv.visibility = View.GONE
        // Button 1: Start recording (filled with colorPrimary, icon in colorOnPrimary)
        btn1.visibility = View.VISIBLE
        btn1.setImageResource(R.drawable.ic_qs_videocam)
        btn1.imageTintList = ColorStateList.valueOf(palette.colorOnPrimary)
        btn1.background =
          createRoundButtonBackground(
            fillColor = palette.colorPrimary,
            rippleColor = palette.colorControlHighlight,
            maskColor = palette.colorSurfaceContainerHigh,
          )
        btn1.contentDescription = "Start recording"
        btn1.setOnClickListener {
          dismissSettingsPanel()
          onStartRequested()
        }

        // Button 2: Quick Settings panel (no fill, icon in colorOnSurfaceVariant)
        btn2.visibility = View.VISIBLE
        btn2.setImageResource(R.drawable.ic_fc_settings)
        btn2.imageTintList = ColorStateList.valueOf(palette.colorOnSurfaceVariant)
        btn2.background =
          createRoundButtonBackground(
            fillColor = null,
            rippleColor = palette.colorControlHighlight,
            maskColor = palette.colorSurfaceContainerHigh,
          )
        btn2.contentDescription = "Quick settings"
        btn2.setOnClickListener {
          toggleSettingsPanel()
        }

        // Button 3: Close floating controls (no fill, icon in colorOnSurfaceVariant)
        btn3.visibility = View.VISIBLE
        btn3.setImageResource(R.drawable.ic_fc_close)
        btn3.imageTintList = ColorStateList.valueOf(palette.colorOnSurfaceVariant)
        btn3.background =
          createRoundButtonBackground(
            fillColor = null,
            rippleColor = palette.colorControlHighlight,
            maskColor = palette.colorSurfaceContainerHigh,
          )
        btn3.contentDescription = "Close controls"
        btn3.setOnClickListener {
          dismissSettingsPanel()
          onCloseRequested()
        }
      }

      RecorderState.RECORDING -> {
        dismissSettingsPanel()
        tv.visibility = View.VISIBLE
        updateTimerTextInPlace(tv, currentElapsedMs)
        if (!isShrunkToEdgeHandle) {
          mainHandler.postDelayed(timerTickRunnable, TIMER_TICK_MS)
        }

        // Button 1: Pause (filled with colorSecondaryContainer, icon in colorOnSecondaryContainer)
        btn1.visibility = View.VISIBLE
        btn1.setImageResource(R.drawable.ic_fc_pause)
        btn1.imageTintList = ColorStateList.valueOf(palette.colorOnSecondaryContainer)
        btn1.background =
          createRoundButtonBackground(
            fillColor = palette.colorSecondaryContainer,
            rippleColor = palette.colorControlHighlight,
            maskColor = palette.colorSurfaceContainerHigh,
          )
        btn1.contentDescription = "Pause recording"
        btn1.setOnClickListener {
          onPauseRequested()
          scheduleAutoHide()
        }

        // Button 2: Stop (filled with colorTertiaryContainer, icon in colorOnTertiaryContainer)
        btn2.visibility = View.VISIBLE
        btn2.setImageResource(R.drawable.ic_qs_stop)
        btn2.imageTintList = ColorStateList.valueOf(palette.colorOnTertiaryContainer)
        btn2.background =
          createRoundButtonBackground(
            fillColor = palette.colorTertiaryContainer,
            rippleColor = palette.colorControlHighlight,
            maskColor = palette.colorSurfaceContainerHigh,
          )
        btn2.contentDescription = "Stop recording"
        btn2.setOnClickListener {
          onStopRequested()
        }

        btn3.visibility = View.GONE
        btn3.setOnClickListener(null)
      }

      RecorderState.PAUSED -> {
        dismissSettingsPanel()
        tv.visibility = View.VISIBLE
        updateTimerTextInPlace(tv, currentElapsedMs)

        // Button 1: Resume (filled with colorSecondaryContainer, icon in colorOnSecondaryContainer)
        btn1.visibility = View.VISIBLE
        btn1.setImageResource(R.drawable.ic_fc_play)
        btn1.imageTintList = ColorStateList.valueOf(palette.colorOnSecondaryContainer)
        btn1.background =
          createRoundButtonBackground(
            fillColor = palette.colorSecondaryContainer,
            rippleColor = palette.colorControlHighlight,
            maskColor = palette.colorSurfaceContainerHigh,
          )
        btn1.contentDescription = "Resume recording"
        btn1.setOnClickListener {
          onResumeRequested()
          scheduleAutoHide()
        }

        // Button 2: Stop (filled with colorTertiaryContainer, icon in colorOnTertiaryContainer)
        btn2.visibility = View.VISIBLE
        btn2.setImageResource(R.drawable.ic_qs_stop)
        btn2.imageTintList = ColorStateList.valueOf(palette.colorOnTertiaryContainer)
        btn2.background =
          createRoundButtonBackground(
            fillColor = palette.colorTertiaryContainer,
            rippleColor = palette.colorControlHighlight,
            maskColor = palette.colorSurfaceContainerHigh,
          )
        btn2.contentDescription = "Stop recording"
        btn2.setOnClickListener {
          onStopRequested()
        }

        btn3.visibility = View.GONE
        btn3.setOnClickListener(null)
      }
    }
  }

  private fun updateTimerTextInPlace(textView: TextView, elapsedMs: Long) {
    val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
    val minutes = (totalSeconds / 60L).toInt()
    val seconds = (totalSeconds % 60L).toInt()
    timerStringBuilder.setLength(0)
    if (minutes < 10) timerStringBuilder.append('0')
    timerStringBuilder.append(minutes)
    timerStringBuilder.append(':')
    if (seconds < 10) timerStringBuilder.append('0')
    timerStringBuilder.append(seconds)
    textView.text = timerStringBuilder
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installDragAndTouchHandling(
    rootView: FrameLayout,
    handleView: View,
    buttons: List<ImageButton>,
  ) {
    val touchSlop = ViewConfiguration.get(themedContext).scaledTouchSlop
    var downRawX = 0f
    var downRawY = 0f
    var startParamX = 0
    var startParamY = 0
    var isDragging = false

    val gestureDetector =
      GestureDetector(
        themedContext,
        object : GestureDetector.SimpleOnGestureListener() {
          override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isShrunkToEdgeHandle) {
              expandFromEdgeHandle()
              return true
            }
            if (currentRecorderState == RecorderState.IDLE) {
              dismissSettingsPanel()
              onStartRequested()
            } else {
              onStopRequested()
            }
            return true
          }
        },
      )

    handleView.setOnClickListener {
      expandFromEdgeHandle()
    }

    val dragTouchListener =
      View.OnTouchListener { v, event ->
        val params = pillLayoutParams ?: return@OnTouchListener false
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            snapAnimator?.cancel()
            mainHandler.removeCallbacks(autoHideRunnable)
            if (!isShrunkToEdgeHandle) {
              rootView.alpha = 1.0f
            }
            downRawX = event.rawX
            downRawY = event.rawY
            startParamX = params.x
            startParamY = params.y
            isDragging = false
            false
          }

          MotionEvent.ACTION_MOVE -> {
            val dx = event.rawX - downRawX
            val dy = event.rawY - downRawY
            if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
              isDragging = true
              v.isPressed = false
              if (panelRootView != null) {
                dismissSettingsPanel()
              }
              if (isShrunkToEdgeHandle) {
                expandFromEdgeHandle(resetPosition = false)
                val (screenW, _) = getScreenDimensions()
                val estW = pillContentLayout?.measuredWidth?.takeIf { it > 0 } ?: dpToPx(156)
                startParamX =
                  (event.rawX.roundToInt() - estW / 2).coerceIn(
                    dpToPx(4),
                    max(dpToPx(4), screenW - estW - dpToPx(4)),
                  )
              }
            }
            if (isDragging) {
              val (screenW, screenH) = getScreenDimensions()
              val viewW = rootView.width.takeIf { it > 0 } ?: dpToPx(156)
              val viewH = rootView.height.takeIf { it > 0 } ?: dpToPx(56)
              val margin = dpToPx(4)
              params.x =
                (startParamX + dx.roundToInt()).coerceIn(
                  margin,
                  max(margin, screenW - viewW - margin),
                )
              params.y =
                (startParamY + dy.roundToInt()).coerceIn(
                  margin,
                  max(margin, screenH - viewH - dpToPx(32)),
                )
              updateViewLayoutSafely(rootView, params)
              true
            } else {
              false
            }
          }

          MotionEvent.ACTION_UP,
          MotionEvent.ACTION_CANCEL -> {
            if (isDragging) {
              isDragging = false
              finishDragAndPersistPosition()
              scheduleAutoHide()
              true
            } else {
              applyIdleOpacity()
              scheduleAutoHide()
              false
            }
          }

          else -> false
        }
      }

    rootView.setOnTouchListener { v, event ->
      val consumed = dragTouchListener.onTouch(v, event)
      if (!consumed && event.actionMasked == MotionEvent.ACTION_DOWN) {
        true
      } else {
        consumed
      }
    }

    buttons.forEach { button ->
      button.setOnTouchListener(dragTouchListener)
    }
    handleView.setOnTouchListener(dragTouchListener)
  }

  private fun finishDragAndPersistPosition() {
    val root = pillRootView ?: return
    val params = pillLayoutParams ?: return
    val (screenW, screenH) = getScreenDimensions()
    val viewW = root.width.takeIf { it > 0 } ?: dpToPx(156)
    val viewH = root.height.takeIf { it > 0 } ?: dpToPx(56)
    val margin = dpToPx(8)
    val minX = margin
    val maxX = (screenW - viewW - margin).coerceAtLeast(minX)
    val minY = margin
    val maxY = (screenH - viewH - dpToPx(48)).coerceAtLeast(minY)

    params.y = params.y.coerceIn(minY, maxY)
    val centerX = params.x + viewW / 2
    isDockedLeft = centerX < screenW / 2

    if (currentSnapshot.floatingControlsEdgeSnapping || isRecordingMode()) {
      val targetX = if (isDockedLeft) minX else maxX
      val startX = params.x
      snapAnimator?.cancel()
      if (currentSnapshot.isGamingMode) {
        params.x = targetX
        updateViewLayoutSafely(root, params)
      } else {
        snapAnimator =
          ValueAnimator.ofInt(startX, targetX).apply {
            duration = SNAP_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
              val p = pillLayoutParams ?: return@addUpdateListener
              val r = pillRootView ?: return@addUpdateListener
              p.x = anim.animatedValue as Int
              updateViewLayoutSafely(r, p)
            }
            start()
          }
      }
      savePositionFractions(
        isLandscape = isLandscape(themedContext.resources.configuration),
        xFrac = if (maxX > minX) (targetX - minX).toFloat() / (maxX - minX).toFloat() else 1f,
        yFrac = if (maxY > minY) (params.y - minY).toFloat() / (maxY - minY).toFloat() else 0.35f,
      )
    } else {
      params.x = params.x.coerceIn(minX, maxX)
      updateViewLayoutSafely(root, params)
      savePositionFractions(
        isLandscape = isLandscape(themedContext.resources.configuration),
        xFrac = if (maxX > minX) (params.x - minX).toFloat() / (maxX - minX).toFloat() else 1f,
        yFrac = if (maxY > minY) (params.y - minY).toFloat() / (maxY - minY).toFloat() else 0.35f,
      )
    }
    applyIdleOpacity()
  }

  private fun shrinkToEdgeHandle() {
    val root = pillRootView ?: return
    val pill = pillContentLayout ?: return
    val handle = edgeHandleView ?: return
    val params = pillLayoutParams ?: return
    if (isShrunkToEdgeHandle || panelRootView != null) return

    snapAnimator?.cancel()
    mainHandler.removeCallbacks(timerTickRunnable)

    val (screenW, _) = getScreenDimensions()
    val pillW = root.width.takeIf { it > 0 } ?: dpToPx(156)
    isDockedLeft = (params.x + pillW / 2) < screenW / 2

    isShrunkToEdgeHandle = true
    pill.visibility = View.GONE
    handle.visibility = View.VISIBLE
    handle.alpha = EDGE_HANDLE_ALPHA

    val handleW = dpToPx(EDGE_HANDLE_WIDTH_DP)
    params.x = if (isDockedLeft) 0 else (screenW - handleW).coerceAtLeast(0)
    applyIdleOpacity()
    updateViewLayoutSafely(root, params)
  }

  private fun expandFromEdgeHandle(resetPosition: Boolean = true) {
    val root = pillRootView ?: return
    val pill = pillContentLayout ?: return
    val handle = edgeHandleView ?: return
    val params = pillLayoutParams ?: return
    if (!isShrunkToEdgeHandle) return

    isShrunkToEdgeHandle = false
    handle.visibility = View.GONE
    pill.visibility = View.VISIBLE

    if (resetPosition) {
      val (screenW, screenH) = getScreenDimensions()
      val (savedXFrac, savedYFrac) =
        loadSavedPositionFractions(isLandscape(themedContext.resources.configuration))
      val margin = dpToPx(8)
      val pillW = pill.measuredWidth.takeIf { it > 0 } ?: dpToPx(156)
      val pillH = pill.measuredHeight.takeIf { it > 0 } ?: dpToPx(56)
      val maxX = (screenW - pillW - margin).coerceAtLeast(margin)
      val maxY = (screenH - pillH - dpToPx(48)).coerceAtLeast(margin)
      params.x =
        if (currentSnapshot.floatingControlsEdgeSnapping || isRecordingMode()) {
          if (isDockedLeft) margin else maxX
        } else {
          (savedXFrac * maxX).roundToInt().coerceIn(margin, maxX)
        }
      params.y = (savedYFrac * maxY).roundToInt().coerceIn(margin, maxY)
    }

    if (currentRecorderState != RecorderState.IDLE) {
      timerTextView?.let { updateTimerTextInPlace(it, currentElapsedMs) }
      if (currentRecorderState == RecorderState.RECORDING) {
        mainHandler.removeCallbacks(timerTickRunnable)
        mainHandler.postDelayed(timerTickRunnable, TIMER_TICK_MS)
      }
    }

    applyIdleOpacity()
    updateViewLayoutSafely(root, params)
    scheduleAutoHide()
  }

  private fun scheduleAutoHide() {
    mainHandler.removeCallbacks(autoHideRunnable)
    if (
      shouldAutoCollapseToHandle() &&
        panelRootView == null &&
        !isShrunkToEdgeHandle
    ) {
      mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }
  }

  private fun applyIdleOpacity() {
    val root = pillRootView ?: return
    edgeHandleView?.alpha = EDGE_HANDLE_ALPHA
    if (panelRootView != null) {
      root.alpha = 1.0f
      return
    }
    val pct = currentSnapshot.floatingControlsOpacityPercent.coerceIn(20, 100)
    root.alpha = pct / 100f
  }

  private fun toggleSettingsPanel() {
    if (currentRecorderState != RecorderState.IDLE) return
    if (panelRootView != null) {
      dismissSettingsPanel()
      scheduleAutoHide()
    } else {
      showSettingsPanel()
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun showSettingsPanel() {
    if (panelRootView != null || pillRootView == null || currentRecorderState != RecorderState.IDLE) {
      return
    }
    mainHandler.removeCallbacks(autoHideRunnable)
    if (isShrunkToEdgeHandle) {
      expandFromEdgeHandle()
    }
    pillRootView?.alpha = 1.0f

    val palette = resolvePalette()
    val panelWidth = dpToPx(244)
    panelRowViews.clear()
    panelLabelViews.clear()

    val panel =
      LinearLayout(themedContext).apply {
        orientation = LinearLayout.VERTICAL
        val padH = dpToPx(16)
        val padV = dpToPx(12)
        setPadding(padH, padV, padH, padV)
        background = createPanelContainerDrawable(palette)
        elevation = dpToPx(6).toFloat()
        setOnTouchListener { _, event ->
          if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            dismissSettingsPanel()
            scheduleAutoHide()
            true
          } else {
            false
          }
        }
      }

    // Row 1: Resolution
    val (resRow, resValueTv) =
      createQuickSettingRow(
        label = "Resolution",
        initialValue = currentSnapshot.resolution,
        palette = palette,
        onClick = { cycleResolutionOption() },
      )
    panelResolutionValueView = resValueTv
    panel.addView(resRow)

    // Row 2: Frame rate
    val (fpsRow, fpsValueTv) =
      createQuickSettingRow(
        label = "Frame rate",
        initialValue = "${currentSnapshot.fps} fps",
        palette = palette,
        onClick = { cycleFpsOption() },
      )
    panelFpsValueView = fpsValueTv
    panel.addView(fpsRow)

    // Row 3: Audio source
    val (audioRow, audioValueTv) =
      createQuickSettingRow(
        label = "Audio source",
        initialValue = currentSnapshot.audioSource,
        palette = palette,
        onClick = { cycleAudioSourceOption() },
      )
    panelAudioValueView = audioValueTv
    panel.addView(audioRow)

    // Divider
    val divider =
      View(themedContext).apply {
        setBackgroundColor(palette.colorOnSurfaceVariant)
        alpha = 0.2f
      }
    panelDividerView = divider
    panel.addView(
      divider,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
        topMargin = dpToPx(6)
        bottomMargin = dpToPx(4)
      },
    )

    // "More settings" button
    val moreSettingsTv =
      TextView(themedContext).apply {
        text = "More settings"
        setTextColor(palette.colorOnSurfaceVariant)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(6))
        background =
          createRoundedRowRipple(palette.colorControlHighlight, palette.colorSurfaceContainerHigh)
        setOnClickListener {
          dismissSettingsPanel()
          openAppSettingsScreen()
        }
      }
    panelMoreSettingsView = moreSettingsTv
    panel.addView(
      moreSettingsTv,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      ),
    )

    val pParams = calculatePanelLayoutParams(panelWidth)
    panelLayoutParams = pParams
    panelRootView = panel
    windowManager.addView(panel, pParams)
  }

  private fun dismissSettingsPanel() {
    val panel = panelRootView ?: return
    panelRootView = null
    panelLayoutParams = null
    panelRowViews.clear()
    panelLabelViews.clear()
    panelResolutionValueView = null
    panelFpsValueView = null
    panelAudioValueView = null
    panelDividerView = null
    panelMoreSettingsView = null
    try {
      windowManager.removeView(panel)
    } catch (_: Throwable) {}
    applyIdleOpacity()
  }

  private fun calculatePanelLayoutParams(panelWidthPx: Int): WindowManager.LayoutParams {
    val pillParams = pillLayoutParams ?: WindowManager.LayoutParams()
    val (screenW, screenH) = getScreenDimensions()
    val margin = dpToPx(8)
    val pillW = pillContentLayout?.measuredWidth?.takeIf { it > 0 } ?: dpToPx(156)
    val pillH = pillContentLayout?.measuredHeight?.takeIf { it > 0 } ?: dpToPx(56)
    val estPanelH = dpToPx(210)

    val targetX =
      (pillParams.x + (pillW - panelWidthPx) / 2).coerceIn(
        margin,
        (screenW - panelWidthPx - margin).coerceAtLeast(margin),
      )
    val spaceBelow = screenH - (pillParams.y + pillH)
    val targetY =
      if (spaceBelow >= estPanelH + margin) {
        pillParams.y + pillH + dpToPx(8)
      } else {
        (pillParams.y - estPanelH - dpToPx(8)).coerceAtLeast(margin)
      }

    var windowFlags =
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (shouldApplyFlagSecure()) {
      windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_SECURE
    }

    return WindowManager.LayoutParams(
      panelWidthPx,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      windowFlags,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = targetX
      y = targetY
    }
  }

  private fun createQuickSettingRow(
    label: String,
    initialValue: String,
    palette: OverlayPalette,
    onClick: () -> Unit,
  ): Pair<LinearLayout, TextView> {
    val row =
      LinearLayout(themedContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = dpToPx(8)
        val padV = dpToPx(8)
        setPadding(padH, padV, padH, padV)
        background =
          createRoundedRowRipple(palette.colorControlHighlight, palette.colorSurfaceContainerHigh)
        setOnClickListener { onClick() }
      }
    panelRowViews.add(row)

    val labelTv =
      TextView(themedContext).apply {
        text = label
        setTextColor(palette.colorOnSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
      }
    panelLabelViews.add(labelTv)
    row.addView(
      labelTv,
      LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )

    val valueChip =
      TextView(themedContext).apply {
        text = initialValue
        setTextColor(palette.colorOnSecondaryContainer)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
        background = createSelectedChipDrawable(palette)
      }
    row.addView(
      valueChip,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      ),
    )

    return row to valueChip
  }

  private fun createRoundedRowRipple(rippleColor: Int, maskColor: Int): RippleDrawable {
    val mask =
      GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpToPx(12).toFloat()
        setColor(maskColor)
      }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
  }

  private fun cycleResolutionOption() {
    val supported =
      currentSnapshot.probedResolutions.ifEmpty {
        listOf("720p", "1080p")
      }
    val current = currentSnapshot.resolution
    val currentIdx = supported.indexOfFirst { it.equals(current, ignoreCase = true) }
    val nextRes = supported[(currentIdx + 1).mod(supported.size)]
    val nextFps = currentSnapshot.fps

    panelResolutionValueView?.text = nextRes
    panelFpsValueView?.text = "$nextFps fps"

    currentSnapshot = currentSnapshot.copy(resolution = nextRes, fps = nextFps)
    onQuickSettingChanged?.invoke(nextRes, nextFps, currentSnapshot.audioSource)
  }

  private fun cycleFpsOption() {
    val supportedFps = listOf(24, 30, 60)
    val currentFps = currentSnapshot.fps
    val currentIdx = supportedFps.indexOf(currentFps)
    val nextFps = supportedFps[(currentIdx + 1).mod(supportedFps.size)]

    panelFpsValueView?.text = "$nextFps fps"

    currentSnapshot = currentSnapshot.copy(fps = nextFps)
    onQuickSettingChanged?.invoke(currentSnapshot.resolution, nextFps, currentSnapshot.audioSource)
  }

  private fun cycleAudioSourceOption() {
    val currentSource = currentSnapshot.audioSource
    val currentIdx = AUDIO_SOURCES.indexOfFirst { it.equals(currentSource, ignoreCase = true) }
    val nextSource = AUDIO_SOURCES[(currentIdx + 1).mod(AUDIO_SOURCES.size)]
    val micImplicit = nextSource.contains("Mic", ignoreCase = true)

    panelAudioValueView?.text = nextSource

    currentSnapshot = currentSnapshot.copy(audioSource = nextSource, micEnabled = micImplicit)
    onQuickSettingChanged?.invoke(currentSnapshot.resolution, currentSnapshot.fps, nextSource)
  }

  private fun refreshPanelValues() {
    panelResolutionValueView?.text = currentSnapshot.resolution
    panelFpsValueView?.text = "${currentSnapshot.fps} fps"
    panelAudioValueView?.text = currentSnapshot.audioSource
  }

  private fun openAppSettingsScreen() {
    try {
      val intent =
        Intent(context, MainActivity::class.java).apply {
          addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_SINGLE_TOP or
              Intent.FLAG_ACTIVITY_CLEAR_TOP
          )
          putExtra(MainActivity.EXTRA_OPEN_DESTINATION, AppRoutes.SETTINGS)
        }
      context.startActivity(intent)
    } catch (_: Throwable) {}
  }

  private fun updateViewLayoutSafely(view: View?, params: WindowManager.LayoutParams?) {
    if (view == null || params == null) return
    try {
      windowManager.updateViewLayout(view, params)
    } catch (_: Throwable) {}
  }

  private fun loadSavedPositionFractions(isLandscape: Boolean): Pair<Float, Float> {
    return if (isLandscape) {
      currentSnapshot.landscapeXFrac to currentSnapshot.landscapeYFrac
    } else {
      currentSnapshot.portraitXFrac to currentSnapshot.portraitYFrac
    }
  }

  private fun savePositionFractions(isLandscape: Boolean, xFrac: Float, yFrac: Float) {
    if (isLandscape) {
      currentSnapshot = currentSnapshot.copy(landscapeXFrac = xFrac, landscapeYFrac = yFrac)
    } else {
      currentSnapshot = currentSnapshot.copy(portraitXFrac = xFrac, portraitYFrac = yFrac)
    }
    onPositionChanged?.invoke(
      currentSnapshot.portraitXFrac,
      currentSnapshot.portraitYFrac,
      currentSnapshot.landscapeXFrac,
      currentSnapshot.landscapeYFrac,
    )
    try {
      val prefs = context.getSharedPreferences(PREFS_POSITION, Context.MODE_PRIVATE)
      val xKey = if (isLandscape) KEY_LANDSCAPE_X_FRAC else KEY_PORTRAIT_X_FRAC
      val yKey = if (isLandscape) KEY_LANDSCAPE_Y_FRAC else KEY_PORTRAIT_Y_FRAC
      prefs.edit().putFloat(xKey, xFrac.coerceIn(0f, 1f)).putFloat(yKey, yFrac.coerceIn(0f, 1f)).apply()
    } catch (_: Throwable) {}
  }

  private fun getScreenDimensions(): Pair<Int, Int> {
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
    return metrics.widthPixels.coerceAtLeast(360) to metrics.heightPixels.coerceAtLeast(640)
  }

  private fun isLandscape(config: Configuration): Boolean {
    return config.orientation == Configuration.ORIENTATION_LANDSCAPE
  }

  private fun dpToPx(dp: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        themedContext.resources.displayMetrics,
      )
      .roundToInt()
  }

  private data class PillSizeSpec(
    val buttonSizePx: Int,
    val iconSizePx: Int,
    val horizontalPaddingPx: Int,
    val verticalPaddingPx: Int,
    val timerTextSp: Float,
  )

  private data class OverlayPalette(
    val colorPrimary: Int,
    val colorOnPrimary: Int,
    val colorSecondaryContainer: Int,
    val colorOnSecondaryContainer: Int,
    val colorTertiaryContainer: Int,
    val colorOnTertiaryContainer: Int,
    val colorSurfaceContainerHigh: Int,
    val colorOnSurface: Int,
    val colorOnSurfaceVariant: Int,
    val colorControlHighlight: Int,
  )

  private fun resolveSizeSpec(sizeSetting: String): PillSizeSpec {
    return when (sizeSetting) {
      "Compact" ->
        PillSizeSpec(
          buttonSizePx = dpToPx(36),
          iconSizePx = dpToPx(20),
          horizontalPaddingPx = dpToPx(8),
          verticalPaddingPx = dpToPx(6),
          timerTextSp = 13f,
        )
      "Large" ->
        PillSizeSpec(
          buttonSizePx = dpToPx(48),
          iconSizePx = dpToPx(28),
          horizontalPaddingPx = dpToPx(14),
          verticalPaddingPx = dpToPx(10),
          timerTextSp = 16f,
        )
      else ->
        PillSizeSpec(
          buttonSizePx = dpToPx(40),
          iconSizePx = dpToPx(24),
          horizontalPaddingPx = dpToPx(10),
          verticalPaddingPx = dpToPx(8),
          timerTextSp = 14f,
        )
    }
  }

  private fun createOverlayThemedContext(
    baseContext: Context,
    config: Configuration = baseContext.resources.configuration,
  ): Context {
    val configContext = baseContext.createConfigurationContext(config)
    val materialThemeContext =
      ContextThemeWrapper(
        configContext,
        com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar,
      )
    return DynamicColors.wrapContextIfAvailable(materialThemeContext)
  }

  private fun resolvePalette(themeCtx: Context = themedContext): OverlayPalette {
    return OverlayPalette(
      colorPrimary =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorPrimary,
          0,
        ),
      colorOnPrimary =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorOnPrimary,
          0,
        ),
      colorSecondaryContainer =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorSecondaryContainer,
          0,
        ),
      colorOnSecondaryContainer =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorOnSecondaryContainer,
          0,
        ),
      colorTertiaryContainer =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorTertiaryContainer,
          0,
        ),
      colorOnTertiaryContainer =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorOnTertiaryContainer,
          0,
        ),
      colorSurfaceContainerHigh =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorSurfaceContainerHigh,
          0,
        ),
      colorOnSurface =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorOnSurface,
          0,
        ),
      colorOnSurfaceVariant =
        MaterialColors.getColor(
          themeCtx,
          com.google.android.material.R.attr.colorOnSurfaceVariant,
          0,
        ),
      colorControlHighlight =
        MaterialColors.getColor(
          themeCtx,
          android.R.attr.colorControlHighlight,
          0,
        ),
    )
  }
}
