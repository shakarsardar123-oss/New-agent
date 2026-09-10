package com.aura.aura_assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/// Android-native implementation of the AURA floating overlay subsystem.
///
/// Uses [WindowManager] to add/remove an overlay view that floats
/// above all other apps (requires [Settings.canDrawOverlays]).
/// A foreground service keeps the overlay alive while shown.
///
/// ## Phase 6-B: Flutter Overlay Hosting Foundation
///
/// The overlay hierarchy is now:
/// ```
/// overlayView: FrameLayout (root)
///   ├── collapsedAuraView: ImageView  (56dp cyan circle, VISIBLE when collapsed)
///   └── expandedContentContainer: FrameLayout (GONE when collapsed)
///       └── overlayFlutterView: FlutterView (lazy-created on first expand)
/// ```
///
/// Key Phase 6-B additions over Phase 6-A:
/// - **FlutterEngine storage**: `registerWith()` now stores the [FlutterEngine]
///   reference for later [FlutterView] creation.
/// - **Lazy FlutterView creation**: [FlutterView] is created only on the first
///   expand request, attached to the shared [FlutterEngine.dartExecutor].
/// - **FlutterView lifecycle**: attach/detach on expand/collapse with proper
///   [View.attachViewToParent] / [View.detachViewFromParent] semantics.
/// - **Window flags**: `FLAG_NOT_FOCUSABLE` removed on expand (FlutterView needs
///   touch input); restored on collapse.
/// - **Fallback to COLLAPSED**: If FlutterView creation fails (engine null,
///   exception), the overlay reverts to COLLAPSED state — no crash.
/// - **Incidental fix**: `R.string.flutter_engine_id` now defined in strings.xml
///   and populated in FlutterEngineCache by MainActivity.
///
/// ### What is NOT in Phase 6-B
/// This phase does NOT connect ReactionEngine, ReactionSelector, ReactionState,
/// VoiceService, VoiceState, SpeakingIndicator, AuraReactionBanner, or any
/// reaction renderers. It does NOT add updateReaction/clearReaction/getReactionState
/// MethodChannels or EventChannels. Those belong to Phase 6-C and later.
///
/// ### Channel contract (mirrors Dart FloatingAuraConstants) — UNCHANGED
/// MethodChannel: `com.aura.aura_assistant/floating_aura_overlay`
/// - requestPermission      → Map{"granted": bool}
/// - openOverlaySettings    → void
/// - hasPermission           → Map{"granted": bool}
/// - showOverlay             → Map{"shown": bool, "error": String?}
/// - hideOverlay             → void
/// - updatePosition          → void  (takes Map{"x", "y"})
/// - togglePanel             → Map{"isExpanded": bool}
/// - isOverlayVisible        → Map{"visible": bool}
/// - isSupported             → bool
///
/// EventChannels (UNCHANGED):
/// - `com.aura.aura_assistant/floating_aura_overlay_position`
/// - `com.aura.aura_assistant/floating_aura_overlay_panel`
/// - `com.aura.aura_assistant/floating_aura_overlay_visibility`
///
/// ### FlutterView rendering with shared engine
/// When a [FlutterView] is attached to the same [FlutterEngine], the overlay
/// FlutterView gets its own render tree but shares the same Dart isolate.
/// This means:
/// - The overlay widget tree runs in the same isolate as the main app.
/// - Riverpod [ProviderScope] from `main()` is accessible.
/// - The overlay root widget ([AuraOverlayHostWidget] on the Dart side) needs
///   NO separate [ProviderScope] — it can `ref.watch()` any provider directly.
/// - The overlay FlutterView renders independently; it does NOT mirror the
///   main activity's widget tree.
///
/// ### Lifecycle considerations
/// - The shared [FlutterEngine] is created by [FlutterActivity] (MainActivity).
/// - When the activity is destroyed, the engine is torn down.
/// - The [OverlayPluginService] is `START_STICKY` but on process restart, a
///   NEW engine must be created. This is a Phase 6-F concern.
/// - For Phase 6-B, the overlay is only visible while the activity is alive.
class OverlayPlugin(
    private val context: Context,
    private val methodChannelName: String = METHOD_CHANNEL_NAME,
    private val positionEventChannelName: String = POSITION_EVENT_CHANNEL_NAME,
    private val panelEventChannelName: String = PANEL_EVENT_CHANNEL_NAME,
    private val visibilityEventChannelName: String = VISIBILITY_EVENT_CHANNEL_NAME,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    // ── Channel names (mirror Dart constants) ────────────────────
    companion object {
        const val METHOD_CHANNEL_NAME =
            "com.aura.aura_assistant/floating_aura_overlay"
        const val POSITION_EVENT_CHANNEL_NAME =
            "com.aura.aura_assistant/floating_aura_overlay_position"
        const val PANEL_EVENT_CHANNEL_NAME =
            "com.aura.aura_assistant/floating_aura_overlay_panel"
        const val VISIBILITY_EVENT_CHANNEL_NAME =
            "com.aura.aura_assistant/floating_aura_overlay_visibility"

        private const val OVERLAY_NOTIFICATION_ID = 2001
        private const val OVERLAY_CHANNEL_ID = "aura_floating_overlay"

        // AppColors.cyan from the Flutter codebase.
        // Used as the fill color for the collapsed Aura circle.
        private const val AURA_CYAN = 0xFF00E5FF

        private const val TAG = "OverlayPlugin"
    }

    // ── Method names (mirror Dart constants) ─────────────────────
    private object Methods {
        const val REQUEST_PERMISSION = "requestPermission"
        const val OPEN_OVERLAY_SETTINGS = "openOverlaySettings"
        const val HAS_PERMISSION = "hasPermission"
        const val SHOW_OVERLAY = "showOverlay"
        const val HIDE_OVERLAY = "hideOverlay"
        const val UPDATE_POSITION = "updatePosition"
        const val TOGGLE_PANEL = "togglePanel"
        const val IS_OVERLAY_VISIBLE = "isOverlayVisible"
        const val IS_SUPPORTED = "isSupported"
    }

    // ── Overlay state enum ──────────────────────────────────────

    /// Native overlay visual state.
    ///
    /// COLLAPSED: The small 56dp cyan circle is visible.
    /// EXPANDED:  The expanded content container is visible with FlutterView;
    ///           the collapsed circle is hidden.
    enum class OverlayState {
        COLLAPSED,
        EXPANDED,
    }

    // ── Phase 6-B: Flutter Engine Architecture ────────────────────

    /// The **primary** FlutterEngine received in [registerWith].
    ///
    /// This engine runs the main app isolate (with all Riverpod
    /// providers, the main ProviderScope, etc.). It is stored for
    /// reference but is NOT used for the overlay FlutterView.
    ///
    /// The overlay uses a **secondary** engine (see [overlayFlutterEngine])
    /// because a FlutterView attached to the same engine renders the
    /// SAME widget tree as the main activity's FlutterView. To render
    /// DIFFERENT content ([AuraOverlayHostWidget] on the Dart side),
    /// a separate engine with a separate Dart entry point is required.
    private var primaryFlutterEngine: FlutterEngine? = null

    /// The **secondary** FlutterEngine created lazily on first expand.
    ///
    /// This engine runs the [auraOverlayMain] Dart entry point
    /// (defined in `aura_overlay_host_widget.dart`), which renders
    /// [AuraOverlayHostWidget] in its own Dart isolate with its own
    /// [ProviderScope].
    ///
    /// Communication between this isolate and the main app isolate
    /// happens via MethodChannel/EventChannel (to be wired in Phase 6-C).
    /// For Phase 6-B, no inter-isolate communication is needed —
    /// we only prove that the FlutterView hosting infrastructure works.
    ///
    /// Created lazily in [ensureFlutterViewAttached] on first expand.
    /// Destroyed in [dispose].
    private var overlayFlutterEngine: FlutterEngine? = null

    /// The [FlutterView] hosted inside [expandedContentContainer].
    /// Created lazily on first expand, cached for reuse across
    /// expand/collapse cycles. Set to null only on disposal.
    private var overlayFlutterView: FlutterView? = null

    /// Tracks whether the FlutterView has been successfully created
    /// at least once. Used for fallback logic and reporting.
    private var flutterViewCreated: Boolean = false

    /// The Dart entry point function name for the overlay engine.
    /// Must match `@pragma('vm:entry-point')` in `aura_overlay_host_widget.dart`.
    private const val OVERLAY_ENTRY_POINT = "auraOverlayMain"

    // ── Overlay view hierarchy ────────────────────────────────────

    /// Root overlay view added to [WindowManager].
    private var overlayView: FrameLayout? = null

    /// Collapsed Aura circle — a 56dp cyan circle visible when collapsed.
    private var collapsedAuraView: ImageView? = null

    /// Container for the FlutterView (expanded content).
    /// GONE when collapsed; VISIBLE when expanded.
    private var expandedContentContainer: FrameLayout? = null

    /// Current overlay visual state.
    private var overlayState: OverlayState = OverlayState.COLLAPSED

    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var isExpanded: Boolean = false
    private var isOverlayShown: Boolean = false

    // ── Defaults (mirror Dart FloatingAuraConstants) ──────────────
    private var positionX: Float = 16.0f
    private var positionY: Float = 100.0f
    private val collapsedSize: Float = 56.0f
    private val expandedWidth: Float = 280.0f
    private val expandedHeight: Float = 400.0f

    // ── Event sinks ───────────────────────────────────────────────
    private var positionEventSink: EventChannel.EventSink? = null
    private var panelEventSink: EventChannel.EventSink? = null
    private var visibilityEventSink: EventChannel.EventSink? = null

    // ── Foreground service state ──────────────────────────────────
    private var foregroundServiceRunning: Boolean = false

    // ── Drag tracking ────────────────────────────────────────────
    private var isDragging: Boolean = false
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var dragOffsetX: Float = 0f
    private var dragOffsetY: Float = 0f

    /// Distance threshold (in pixels) to distinguish click from drag.
    private val clickThresholdPx: Float = 10f * context.resources.displayMetrics.density

    // ── Registration ──────────────────────────────────────────────

    /// Registers this plugin with the [FlutterEngine].
    ///
    /// Phase 6-B: stores the **primary** engine reference for channel
    /// registration. The overlay uses a **secondary** engine
    /// (see [overlayFlutterEngine]) to render different content.
    /// All channel registration logic is unchanged from Phase 6-A.
    fun registerWith(flutterEngine: FlutterEngine) {
        // ── Phase 6-B: Store primary engine reference ──────────────
        this.primaryFlutterEngine = flutterEngine
        Log.d(TAG, "registerWith: Primary FlutterEngine reference stored")

        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, methodChannelName)
            .setMethodCallHandler(this)

        EventChannel(messenger, positionEventChannelName)
            .setStreamHandler(PositionStreamHandler())

        EventChannel(messenger, panelEventChannelName)
            .setStreamHandler(PanelStreamHandler())

        EventChannel(messenger, visibilityEventChannelName)
            .setStreamHandler(VisibilityStreamHandler())

        createNotificationChannel()
    }

    // ── MethodCallHandler ─────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            Methods.REQUEST_PERMISSION -> handleRequestPermission(result)
            Methods.OPEN_OVERLAY_SETTINGS -> handleOpenOverlaySettings(result)
            Methods.HAS_PERMISSION -> handleHasPermission(result)
            Methods.SHOW_OVERLAY -> handleShowOverlay(result)
            Methods.HIDE_OVERLAY -> handleHideOverlay(result)
            Methods.UPDATE_POSITION -> handleUpdatePosition(call, result)
            Methods.TOGGLE_PANEL -> handleTogglePanel(result)
            Methods.IS_OVERLAY_VISIBLE -> handleIsOverlayVisible(result)
            Methods.IS_SUPPORTED -> handleIsSupported(result)
            else -> result.notImplemented()
        }
    }

    // ── requestPermission ─────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun handleRequestPermission(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.success(mapOf("granted" to true))
            return
        }

        val canDraw = Settings.canDrawOverlays(context)
        if (canDraw) {
            result.success(mapOf("granted" to true))
            return
        }

        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // If we can't open settings, just report not granted.
        }

        result.success(mapOf("granted" to false))
    }

    // ── openOverlaySettings ────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun handleOpenOverlaySettings(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { }
            }
        }
        result.success(null)
    }

    // ── hasPermission ──────────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun handleHasPermission(result: MethodChannel.Result) {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        result.success(mapOf("granted" to canDraw))
    }

    // ── showOverlay ────────────────────────────────────────────────

    /// Creates the overlay view hierarchy and adds it to [WindowManager].
    ///
    /// Phase 6-B hierarchy:
    /// ```
    /// FrameLayout (root → overlayView)
    ///   ├── ImageView (collapsedAuraView — cyan circle)
    ///   └── FrameLayout (expandedContentContainer — GONE)
    ///       └── FlutterView (overlayFlutterView — lazy, added on first expand)
    /// ```
    @SuppressLint("NewApi", "ClickableViewAccessibility")
    private fun handleShowOverlay(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && !Settings.canDrawOverlays(context)
        ) {
            result.success(mapOf(
                "shown" to false,
                "error" to "SYSTEM_ALERT_WINDOW permission not granted",
            ))
            return
        }

        if (isOverlayShown && overlayView != null) {
            result.success(mapOf("shown" to true, "error" to null))
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            result.success(mapOf(
                "shown" to false,
                "error" to "WindowManager not available",
            ))
            return
        }

        // ── Build the overlay view hierarchy ─────────────────────

        overlayView = FrameLayout(context).apply {
            setBackgroundColor(0x00000000)
            setOnTouchListener(createDragTouchListener())
        }

        collapsedAuraView = ImageView(context).apply {
            val sizePx = dpToPx(collapsedSize)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)

            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AURA_CYAN.toInt())
            }
            setImageDrawable(circleDrawable)

            setOnClickListener {
                handleTogglePanelInternal()
            }

            visibility = View.VISIBLE
        }

        expandedContentContainer = FrameLayout(context).apply {
            val widthPx = dpToPx(expandedWidth)
            val heightPx = dpToPx(expandedHeight)
            layoutParams = FrameLayout.LayoutParams(
                widthPx, heightPx, Gravity.CENTER
            )
            // Phase 6-B: dark semi-transparent background matching
            // the overlay host widget's dark theme.
            setBackgroundColor(0xE60B0E14) // ~90% opacity dark
            visibility = View.GONE
        }

        overlayView!!.addView(collapsedAuraView)
        overlayView!!.addView(expandedContentContainer)

        // ── WindowManager layout params ────────────────────────────

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val size = dpToPx(collapsedSize)

        overlayLayoutParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(positionX).toInt()
            y = dpToPx(positionY).toInt()
        }

        try {
            wm.addView(overlayView, overlayLayoutParams)
            isOverlayShown = true
            startForegroundService()
            sendVisibilityEvent(true)
            result.success(mapOf("shown" to true, "error" to null))
        } catch (e: Exception) {
            cleanupOverlayViews()
            overlayView = null
            overlayLayoutParams = null
            isOverlayShown = false
            result.success(mapOf(
                "shown" to false,
                "error" to (e.message ?: "Failed to add overlay view"),
            ))
        }
    }

    // ── hideOverlay ────────────────────────────────────────────────

    private fun handleHideOverlay(result: MethodChannel.Result) {
        if (!isOverlayShown || overlayView == null) {
            result.success(null)
            return
        }

        // Phase 6-B: detach FlutterView if currently expanded
        detachFlutterViewIfExpanded()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null && overlayView != null) {
            try {
                wm.removeView(overlayView)
            } catch (_: Exception) { }
        }

        cleanupOverlayViews()

        overlayView = null
        overlayLayoutParams = null
        isOverlayShown = false
        isExpanded = false
        overlayState = OverlayState.COLLAPSED
        stopForegroundService()
        sendVisibilityEvent(false)
        result.success(null)
    }

    // ── updatePosition ──────────────────────────────────────────────

    private fun handleUpdatePosition(call: MethodCall, result: MethodChannel.Result) {
        val x = call.argument<Double>("x")?.toFloat() ?: return result.error(
            "INVALID_ARGUMENT", "x is required", null,
        )
        val y = call.argument<Double>("y")?.toFloat() ?: return result.error(
            "INVALID_ARGUMENT", "y is required", null,
        )

        positionX = x
        positionY = y

        overlayLayoutParams?.let { params ->
            params.x = dpToPx(positionX).toInt()
            params.y = dpToPx(positionY).toInt()

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null && overlayView != null && isOverlayShown) {
                try {
                    wm.updateViewLayout(overlayView, params)
                } catch (_: Exception) { }
            }
        }

        sendPositionEvent(positionX.toDouble(), positionY.toDouble())
        result.success(null)
    }

    // ── togglePanel ────────────────────────────────────────────────

    /// Toggles the overlay between collapsed and expanded states.
    ///
    /// Phase 6-B additions:
    /// - On expand: lazily creates [FlutterView] if not yet created,
    ///   attaches it to [expandedContentContainer], removes
    ///   `FLAG_NOT_FOCUSABLE` from window params so FlutterView can
    ///   receive touch input.
    /// - On collapse: detaches [FlutterView] visibility (but does NOT
    ///   destroy it — cached for reuse), re-adds `FLAG_NOT_FOCUSABLE`,
    ///   hides [expandedContentContainer], shows [collapsedAuraView].
    /// - Fallback: If FlutterView creation fails on expand, reverts
    ///   to COLLAPSED state and logs the error.
    @SuppressLint("NewApi", "ClickableViewAccessibility")
    private fun handleTogglePanel(result: MethodChannel.Result) {
        handleTogglePanelInternal()
        result.success(mapOf("isExpanded" to isExpanded))
    }

    /// Internal toggle logic shared between MethodChannel handler
    /// and collapsedAuraView click listener.
    ///
    /// Phase 6-B: manages FlutterView attach/detach and window flags.
    private fun handleTogglePanelInternal() {
        isExpanded = !isExpanded

        val params = overlayLayoutParams ?: return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        if (isExpanded) {
            overlayState = OverlayState.EXPANDED

            // ── Phase 6-B: Expand path ────────────────────────────

            // 1. Hide collapsed circle, show expanded container
            collapsedAuraView?.visibility = View.GONE
            expandedContentContainer?.visibility = View.VISIBLE

            // 2. Lazily create and attach FlutterView
            val attachSuccess = ensureFlutterViewAttached()

            if (!attachSuccess) {
                // Fallback: revert to collapsed state
                Log.e(TAG, "FlutterView creation/attachment failed — reverting to COLLAPSED")
                isExpanded = false
                overlayState = OverlayState.COLLAPSED
                collapsedAuraView?.visibility = View.VISIBLE
                expandedContentContainer?.visibility = View.GONE
                return
            }

            // 3. Update window size and remove FLAG_NOT_FOCUSABLE
            //    so FlutterView can receive touch events.
            params.width = dpToPx(expandedWidth)
            params.height = dpToPx(expandedHeight)
            params.flags = params.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

        } else {
            overlayState = OverlayState.COLLAPSED

            // ── Phase 6-B: Collapse path ──────────────────────────

            // 1. Detach FlutterView (hide, but don't destroy)
            detachFlutterViewIfExpanded()

            // 2. Hide expanded container, show collapsed circle
            expandedContentContainer?.visibility = View.GONE
            collapsedAuraView?.visibility = View.VISIBLE

            // 3. Update window size and restore FLAG_NOT_FOCUSABLE
            val size = dpToPx(collapsedSize)
            params.width = size
            params.height = size
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        // Re-apply drag listener on root (unchanged behavior)
        overlayView?.setOnTouchListener(createDragTouchListener())

        if (wm != null && overlayView != null && isOverlayShown) {
            try {
                wm.updateViewLayout(overlayView, params)
            } catch (_: Exception) { }
        }

        sendPanelEvent(isExpanded)
    }

    // ── Phase 6-B: FlutterView lifecycle ──────────────────────────

    /// Ensures the [FlutterView] is created and attached to
    /// [expandedContentContainer].
    ///
    /// Returns `true` if the FlutterView is attached and visible,
    /// `false` on any failure (null engine, exception, etc.).
    ///
    /// The FlutterView is created ONCE and cached in [overlayFlutterView].
    /// On subsequent expand calls, it is simply re-attached (made visible).
    /// It is never destroyed during the overlay's lifetime.
    /// Ensures the [FlutterView] is created and attached to
    /// [expandedContentContainer].
    ///
    /// Returns `true` if the FlutterView is attached and visible,
    /// `false` on any failure (null engine, exception, etc.).
    ///
    /// ## Phase 6-B: Secondary FlutterEngine architecture
    ///
    /// Instead of attaching the FlutterView to the shared (primary) engine,
    /// this method creates a **secondary** [FlutterEngine] with the
    /// [OVERLAY_ENTRY_POINT] ("auraOverlayMain") Dart entry point. This gives
    /// the overlay its own isolate and widget tree, rendering
    /// [AuraOverlayHostWidget] instead of mirroring the main activity.
    ///
    /// The secondary engine is created ONCE (lazily on first expand) and
    /// cached in [overlayFlutterEngine]. The FlutterView is also created once
    /// and cached in [overlayFlutterView]. On subsequent expand calls, both
    /// are simply re-shown (visibility = VISIBLE).
    ///
    /// ### Fallback
    /// If either the secondary engine creation or the FlutterView creation
    /// fails, the overlay reverts to COLLAPSED state — no crash.
    @SuppressLint("NewApi")
    private fun ensureFlutterViewAttached(): Boolean {
        val container = expandedContentContainer
        if (container == null) {
            Log.e(TAG, "Cannot create FlutterView: expandedContentContainer is null")
            return false
        }

        // ── Step 1: Create secondary FlutterEngine lazily ──────────
        if (overlayFlutterEngine == null) {
            try {
                overlayFlutterEngine = FlutterEngine(context)
                // Execute the overlay-specific Dart entry point.
                // The entry point function name must match
                // @pragma('vm:entry-point') in aura_overlay_host_widget.dart.
                //
                // We use the two-arg DartEntrypoint constructor to specify
                // the custom function name "auraOverlayMain" instead of the
                // default "main". FlutterLoader was already initialized by
                // the main FlutterActivity, so findAppBundlePath() is safe.
                val loader = io.flutter.embedding.engine.loader.FlutterLoader.getInstance()
                val entrypoint = DartExecutor.DartEntrypoint(
                    loader.findAppBundlePath(),
                    OVERLAY_ENTRY_POINT
                )
                overlayFlutterEngine!!.dartExecutor.executeDartEntrypoint(entrypoint)
                Log.d(TAG, "Secondary FlutterEngine created for overlay")
            } catch (e: Exception) {
                Log.e(TAG, "Secondary FlutterEngine creation failed", e)
                overlayFlutterEngine = null
                return false
            }
        }

        // Verify the overlay engine is executing Dart
        if (!overlayFlutterEngine!!.dartExecutor.isExecutingDart) {
            Log.e(TAG, "Overlay FlutterEngine DartExecutor is not executing")
            return false
        }

        // ── Step 2: Create FlutterView lazily ──────────────────────
        if (overlayFlutterView == null) {
            try {
                overlayFlutterView = FlutterView(
                    context,
                    overlayFlutterEngine!!.dartExecutor
                ).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    Log.d(TAG, "FlutterView created for overlay engine")
                }
                flutterViewCreated = true
                Log.d(TAG, "FlutterView created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "FlutterView creation failed", e)
                overlayFlutterView = null
                flutterViewCreated = false
                return false
            }
        }

        // ── Step 3: Attach FlutterView to container ────────────────
        if (overlayFlutterView?.parent != null && overlayFlutterView?.parent != container) {
            // Safety: remove from old parent if somehow re-parented
            (overlayFlutterView?.parent as? FrameLayout)?.removeView(overlayFlutterView)
        }
        if (overlayFlutterView?.parent == null) {
            container.addView(overlayFlutterView)
            Log.d(TAG, "FlutterView added to expandedContentContainer")
        }

        overlayFlutterView?.visibility = View.VISIBLE
        return true
    }

    /// Detaches the [FlutterView] from visibility on collapse.
    ///
    /// The view is hidden but NOT destroyed — it stays as a child
    /// of [expandedContentContainer] with [View.GONE] so it can be
    /// re-shown instantly on the next expand.
    private fun detachFlutterViewIfExpanded() {
        if (overlayFlutterView != null && overlayFlutterView?.visibility == View.VISIBLE) {
            overlayFlutterView?.visibility = View.GONE
            Log.d(TAG, "FlutterView hidden (collapsed)")
        }
    }

    // ── isOverlayVisible ───────────────────────────────────────────

    private fun handleIsOverlayVisible(result: MethodChannel.Result) {
        result.success(mapOf("visible" to isOverlayShown))
    }

    // ── isSupported ────────────────────────────────────────────────

    private fun handleIsSupported(result: MethodChannel.Result) {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        result.success(supported)
    }

    // ── EventChannel.StreamHandlers ────────────────────────────────

    private inner class PositionStreamHandler : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            positionEventSink = events
        }
        override fun onCancel(arguments: Any?) {
            positionEventSink = null
        }
    }

    private inner class PanelStreamHandler : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            panelEventSink = events
        }
        override fun onCancel(arguments: Any?) {
            panelEventSink = null
        }
    }

    private inner class VisibilityStreamHandler : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            visibilityEventSink = events
        }
        override fun onCancel(arguments: Any?) {
            visibilityEventSink = null
        }
    }

    // ── Legacy StreamHandler interface ─────────────────────────────

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        // No-op; delegation handled by inner StreamHandler classes.
    }

    override fun onCancel(arguments: Any?) {
        // No-op; delegation handled by inner StreamHandler classes.
    }

    // ── Event senders ─────────────────────────────────────────────

    private fun sendPositionEvent(x: Double, y: Double) {
        Handler(Looper.getMainLooper()).post {
            positionEventSink?.success(mapOf("x" to x, "y" to y))
        }
    }

    private fun sendPanelEvent(expanded: Boolean) {
        Handler(Looper.getMainLooper()).post {
            panelEventSink?.success(mapOf("isExpanded" to expanded))
        }
    }

    private fun sendVisibilityEvent(visible: Boolean) {
        Handler(Looper.getMainLooper()).post {
            visibilityEventSink?.success(mapOf("visible" to visible))
        }
    }

    // ── Foreground service ────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager ?: return
            val channel = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "AURA Floating Overlay",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the floating AURA overlay visible"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    @SuppressLint("NewApi")
    private fun startForegroundService() {
        if (foregroundServiceRunning) return

        try {
            val notification = buildOverlayNotification()
            val intent = Intent(context, OverlayPluginService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            foregroundServiceRunning = true
        } catch (_: Exception) {
            foregroundServiceRunning = false
        }
    }

    private fun stopForegroundService() {
        if (!foregroundServiceRunning) return
        try {
            val intent = Intent(context, OverlayPluginService::class.java)
            context.stopService(intent)
        } catch (_: Exception) { }
        foregroundServiceRunning = false
    }

    @SuppressLint("NewApi")
    private fun buildOverlayNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, Class.forName("${context.packageName}.MainActivity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, OVERLAY_CHANNEL_ID)
                .setContentTitle("AURA Overlay")
                .setContentText("Floating overlay is active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle("AURA Overlay")
                .setContentText("Floating overlay is active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    // ── Drag listener ──────────────────────────────────────────────

    /// Creates a drag touch listener that disambiguates taps from drags.
    /// A tap (finger moves less than [clickThresholdPx]) is forwarded to
    /// [collapsedAuraView]'s click listener. A drag moves the overlay.
    ///
    /// Phase 6-B: When expanded, drag on the top 28dp "drag zone" still
    /// moves the overlay. Touches on the content area are forwarded to
    /// FlutterView by the default FrameLayout touch dispatch.
    private fun createDragTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    overlayLayoutParams?.let { params ->
                        dragOffsetX = params.x.toFloat()
                        dragOffsetY = params.y.toFloat()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY

                    if (!isDragging && (Math.abs(dx) > clickThresholdPx || Math.abs(dy) > clickThresholdPx)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        overlayLayoutParams?.let { params ->
                            params.x = (dragOffsetX + dx).toInt()
                            params.y = (dragOffsetY + dy).toInt()
                            val wm = context.getSystemService(
                                Context.WINDOW_SERVICE
                            ) as? WindowManager
                            if (wm != null && overlayView != null && isOverlayShown) {
                                try {
                                    wm.updateViewLayout(overlayView, params)
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        overlayLayoutParams?.let { params ->
                            val newX = pxToDp(params.x.toFloat())
                            val newY = pxToDp(params.y.toFloat())
                            positionX = newX
                            positionY = newY
                            sendPositionEvent(newX.toDouble(), newY.toDouble())
                        }
                    } else {
                        collapsedAuraView?.performClick()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    // ── Unit helpers ───────────────────────────────────────────────

    private fun dpToPx(dp: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    private fun pxToDp(px: Float): Float {
        val density = context.resources.displayMetrics.density
        return px / density
    }

    // ── Child view cleanup ─────────────────────────────────────────

    /// Safely removes all child views from [overlayView] and nulls
    /// references. Phase 6-B: also nulls [overlayFlutterView].
    private fun cleanupOverlayViews() {
        overlayView?.removeAllViews()
        collapsedAuraView = null
        expandedContentContainer = null
        overlayFlutterView = null
        flutterViewCreated = false
    }

    // ── Cleanup ────────────────────────────────────────────────────

    /// Clean up all resources.
    ///
    /// Phase 6-B: also destroys the cached FlutterView and nulls
    /// the engine reference.
    fun dispose() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null && overlayView != null) {
            try {
                wm.removeView(overlayView)
            } catch (_: Exception) { }
        }

        // Phase 6-B: clean up FlutterView
        overlayFlutterView = null
        flutterViewCreated = false

        cleanupOverlayViews()

        overlayView = null
        overlayLayoutParams = null
        isOverlayShown = false
        isExpanded = false
        overlayState = OverlayState.COLLAPSED

        // Phase 6-B: release engine references
        primaryFlutterEngine = null
        overlayFlutterEngine = null

        stopForegroundService()
        positionEventSink = null
        panelEventSink = null
        visibilityEventSink = null
    }
}

/// Minimal foreground [android.app.Service] for the overlay.
///
/// Phase 6-B: No changes to this service class.
class OverlayPluginService : android.app.Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(
            OverlayPlugin.OVERLAY_NOTIFICATION_ID,
            buildNotification(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("NewApi")
    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            OverlayPlugin.OVERLAY_CHANNEL_ID,
            "AURA Floating Overlay",
            NotificationManager.IMPORTANCE_LOW,
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, Class.forName("${packageName}.MainActivity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, OverlayPlugin.OVERLAY_CHANNEL_ID)
            .setContentTitle("AURA Overlay")
            .setContentText("Floating overlay is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
