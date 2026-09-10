package com.aura.aura_assistant

import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/// Android-side handler for the `com.aura.aura_assistant/device`
/// MethodChannel.
///
/// Phase 6 skeleton — each method returns basic data or a
/// `notImplemented` error so that the Flutter side can degrade
/// gracefully via [DeviceChannelResult.failure].
///
/// ## Phase 6-B: FlutterEngineCache Population
///
/// The [FlutterEngine] created by FlutterActivity is now stored
/// in [FlutterEngineCache] under the key `R.string.flutter_engine_id`
/// ("aura_main_engine"). This enables:
///
/// 1. **AuraQuickSettingsTileService** to access the engine via
///    `FlutterEngineCache.getInstance().get(getString(R.string.flutter_engine_id))`
///    — a pre-existing code path that previously failed because
///    `R.string.flutter_engine_id` was undefined and the cache was
///    never populated. This is an **incidental fix** (not a 6-B
///    deliverable, but necessary for correctness).
///
/// 2. **OverlayPlugin** to retrieve the same engine for FlutterView
///    creation, although in the current implementation OverlayPlugin
///    receives the engine directly via `registerWith(flutterEngine)`
///    and does NOT use FlutterEngineCache.
///
/// **Important:** FlutterEngineCache.put() is called in
/// configureFlutterEngine, AFTER super.configureFlutterEngine() has
/// initialized the engine. The engine is NOT removed from cache in
/// onDestroy — the Flutter framework handles engine teardown, and
/// the cache entry becomes stale automatically when the process dies.
class MainActivity : FlutterActivity() {

    private val CHANNEL_NAME = "com.aura.aura_assistant/device"

    // Screen-capture plugin (handles MediaProjection + VirtualDisplay).
    private var screenCapturePlugin: ScreenCapturePlugin? = null

    // Overlay plugin (handles SYSTEM_ALERT_WINDOW + WindowManager).
    private var overlayPlugin: OverlayPlugin? = null

    // ── Method names (mirrored in Dart) ───────────────────────────
    private object Methods {
        const val GET_DEVICE_INFO     = "getDeviceInfo"
        const val GET_BATTERY_INFO    = "getBatteryInfo"
        const val GET_NETWORK_INFO    = "getNetworkInfo"
        const val LAUNCH_APP          = "launchApp"
        const val OPEN_SYSTEM_SETTINGS = "openSystemSettings"
        const val LAUNCH_URL          = "launchUrl"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // ── Phase 6-B: Populate FlutterEngineCache ──────────────────
        // Store the engine so that AuraQuickSettingsTileService and
        // other components can retrieve it. The key matches
        // R.string.flutter_engine_id ("aura_main_engine").
        FlutterEngineCache.getInstance()
            .put(getString(R.string.flutter_engine_id), flutterEngine)

        // Register the screen-capture plugin.
        screenCapturePlugin = ScreenCapturePlugin(this)
        screenCapturePlugin?.registerWith(flutterEngine)

        // Register the floating overlay plugin.
        // Phase 6-B: OverlayPlugin now stores the engine reference
        // for later FlutterView creation.
        overlayPlugin = OverlayPlugin(this)
        overlayPlugin?.registerWith(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                Methods.GET_DEVICE_INFO -> handleGetDeviceInfo(result)
                Methods.GET_BATTERY_INFO -> handleGetBatteryInfo(result)
                Methods.GET_NETWORK_INFO -> handleGetNetworkInfo(result)
                Methods.LAUNCH_APP -> handleLaunchApp(call, result)
                Methods.OPEN_SYSTEM_SETTINGS -> handleOpenSystemSettings(call, result)
                Methods.LAUNCH_URL -> handleLaunchUrl(call, result)
                else -> result.notImplemented()
            }
        }
    }

    // ── Forward MediaProjection onActivityResult to the plugin ───

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // Give the ScreenCapturePlugin a chance to consume the result first.
        val consumed = screenCapturePlugin?.onActivityResult(requestCode, resultCode, data) ?: false
        if (!consumed) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        screenCapturePlugin?.dispose()
        screenCapturePlugin = null
        overlayPlugin?.dispose()
        overlayPlugin = null
        super.onDestroy()
    }

    // ── getDeviceInfo ──────────────────────────────────────────────

    private fun handleGetDeviceInfo(result: MethodChannel.Result) {
        try {
            val map = hashMapOf<String, Any>(
                "brand"           to android.os.Build.BRAND,
                "model"           to android.os.Build.MODEL,
                "manufacturer"    to android.os.Build.MANUFACTURER,
                "androidVersion"  to android.os.Build.VERSION.RELEASE,
                "sdkInt"          to android.os.Build.VERSION.SDK_INT,
                "device"          to android.os.Build.DEVICE,
                "isPhysicalDevice" to (android.os.Build.PRODUCT != "sdk"),
                "board"           to android.os.Build.BOARD,
                "hardware"        to android.os.Build.HARDWARE
            )
            result.success(map)
        } catch (e: Exception) {
            result.error("DEVICE_INFO_ERROR", e.message, null)
        }
    }

    // ── getBatteryInfo ─────────────────────────────────────────────

    private fun handleGetBatteryInfo(result: MethodChannel.Result) {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = bm.isCharging

            val chargingType = when {
                isCharging -> "ac"  // simplified; can be refined with Intent sticky broadcast
                else -> "none"
            }

            val map = hashMapOf<String, Any>(
                "level"       to level,
                "isCharging"   to isCharging,
                "chargingType" to chargingType
            )
            result.success(map)
        } catch (e: Exception) {
            result.error("BATTERY_INFO_ERROR", e.message, null)
        }
    }

    // ── getNetworkInfo ─────────────────────────────────────────────

    private fun handleGetNetworkInfo(result: MethodChannel.Result) {
        try {
            // Basic connectivity check via active network
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            val isConnected = activeNetwork?.isConnectedOrConnecting == true
            val type = when (activeNetwork?.type) {
                android.net.ConnectivityManager.TYPE_WIFI    -> "wifi"
                android.net.ConnectivityManager.TYPE_MOBILE   -> "mobile"
                android.net.ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                else -> if (isConnected) "unknown" else "none"
            }

            val map = hashMapOf<String, Any>(
                "isConnected" to isConnected,
                "type"       to type
            )
            result.success(map)
        } catch (e: Exception) {
            result.error("NETWORK_INFO_ERROR", e.message, null)
        }
    }

    // ── launchApp ──────────────────────────────────────────────────

    private fun handleLaunchApp(call: MethodCall, result: MethodChannel.Result) {
        try {
            val packageId = call.argument<String>("packageId")
                ?: return result.error("INVALID_ARGUMENT", "packageId is required", null)

            val intent = packageManager.getLaunchIntentForPackage(packageId)
                ?: return result.error("APP_NOT_FOUND", "App $packageId not found", null)

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            result.success(hashMapOf<String, Any>("launched" to true))
        } catch (e: Exception) {
            result.error("LAUNCH_FAILED", e.message, null)
        }
    }

    // ── openSystemSettings ─────────────────────────────────────────

    private fun handleOpenSystemSettings(call: MethodCall, result: MethodChannel.Result) {
        try {
            val action = call.argument<String>("action")
                ?: return result.error("INVALID_ARGUMENT", "action is required", null)

            val settingsAction = when (action) {
                "wifi"      -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "location"  -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "display"   -> Settings.ACTION_DISPLAY_SETTINGS
                "sound"     -> Settings.ACTION_SOUND_SETTINGS
                "battery"   -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "apps"      -> Settings.ACTION_APPLICATION_SETTINGS
                "storage"   -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                "security"  -> Settings.ACTION_SECURITY_SETTINGS
                "about"     -> Settings.ACTION_DEVICE_INFO_SETTINGS
                else         -> Settings.ACTION_SETTINGS  // fallback to main settings
            }

            val intent = Intent(settingsAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            result.success(hashMapOf<String, Any>("opened" to true))
        } catch (e: Exception) {
            result.error("SETTINGS_OPEN_FAILED", e.message, null)
        }
    }

    // ── launchUrl ──────────────────────────────────────────────────

    private fun handleLaunchUrl(call: MethodCall, result: MethodChannel.Result) {
        try {
            val url = call.argument<String>("url")
                ?: return result.error("INVALID_ARGUMENT", "url is required", null)

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            result.success(hashMapOf<String, Any>("launched" to true))
        } catch (e: Exception) {
            result.error("URL_LAUNCH_FAILED", e.message, null)
        }
    }
}
