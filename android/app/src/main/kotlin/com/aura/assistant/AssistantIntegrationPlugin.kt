package com.aura.assistant

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Step 15: Android platform handler for the assistant integration MethodChannel.
 *
 * Channel name: com.aura.assistant/assistant_integration
 *
 * Methods:
 * - checkIsDefaultAssistant: Returns whether AURA is the current default assistant.
 * - getAssistantAvailability: Returns 'unsupported' | 'available' | 'active'.
 * - openAssistantSettings: Opens the Android assistant settings activity.
 * - getInvocationData: Extracts data from the incoming ASSIST intent.
 * - getAndroidApiLevel: Returns the device's API level.
 *
 * IMPORTANT: This plugin NEVER silently changes the default assistant.
 * The user must always choose AURA through the Android system UI.
 */
class AssistantIntegrationPlugin(
    private val activity: Activity
) : MethodChannel.MethodCallHandler {

    companion object {
        private const val CHANNEL_NAME = "com.aura.assistant/assistant_integration"

        /** Register this plugin with the FlutterEngine. */
        fun registerWith(flutterEngine: FlutterEngine, activity: Activity) {
            MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                CHANNEL_NAME
            ).setMethodCallHandler(AssistantIntegrationPlugin(activity))
        }
    }

    /** Store the incoming intent for later extraction by getInvocationData. */
    private var pendingAssistIntent: Intent? = null

    /**
     * Called by the Activity when a new ASSIST intent arrives.
     * Store it so [handleGetInvocationData] can extract the payload.
     */
    fun onNewIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_ASSIST) {
            pendingAssistIntent = Intent(intent)
        }
    }

    override fun onMethodCall(call: MethodChannel.MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "checkIsDefaultAssistant" -> handleCheckIsDefaultAssistant(result)
            "getAssistantAvailability" -> handleGetAssistantAvailability(result)
            "openAssistantSettings" -> handleOpenAssistantSettings(result)
            "getInvocationData" -> handleGetInvocationData(result)
            "getAndroidApiLevel" -> handleGetAndroidApiLevel(result)
            else -> result.notImplemented()
        }
    }

    // ── Method Handlers ────────────────────────────────────────────────

    /**
     * Check if AURA is the default assistant.
     * Uses the RoleManager on API 29+ or the default-assistant setting.
     */
    private fun handleCheckIsDefaultAssistant(result: MethodChannel.Result) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = activity.getSystemService(RoleManager::class.java)
                val isAssistant = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false
                result.success(isAssistant)
            } else {
                // Pre-API 29: check secure settings
                val setting = Settings.Secure.getString(
                    activity.contentResolver,
                    "assistant"
                )
                val isAura = setting?.contains("com.aura.assistant") == true
                result.success(isAura)
            }
        } catch (e: Exception) {
            result.error("DETECTION_ERROR", e.message, null)
        }
    }

    /**
     * Get the assistant availability status.
     * Returns: 'unsupported' | 'available' | 'active'
     */
    private fun handleGetAssistantAvailability(result: MethodChannel.Result) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                result.success("unsupported")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = activity.getSystemService(RoleManager::class.java)
                val isAvailable = roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ?: false
                val isActive = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false
                result.success(if (isActive) "active" else if (isAvailable) "available" else "unsupported")
            } else {
                // Pre-API 29: assistant role is always "available" if we have the intent filter
                val setting = Settings.Secure.getString(
                    activity.contentResolver,
                    "assistant"
                )
                val isAura = setting?.contains("com.aura.assistant") == true
                result.success(if (isAura) "active" else "available")
            }
        } catch (e: Exception) {
            result.error("AVAILABILITY_ERROR", e.message, null)
        }
    }

    /**
     * Open the Android assistant settings activity.
     *
     * NEVER silently changes the default — always goes through system UI.
     *
     * On API 29+: Uses RoleManager.createRequestRoleIntent()
     * On older: Opens the Default Apps settings screen
     */
    private fun handleOpenAssistantSettings(result: MethodChannel.Result) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = activity.getSystemService(RoleManager::class.java)
                val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                if (intent != null) {
                    activity.startActivity(intent)
                    result.success(null)
                } else {
                    result.error("SETTINGS_ERROR", "Could not create assistant settings intent", null)
                }
            } else {
                // Open Default Apps settings
                val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                result.success(null)
            }
        } catch (e: Exception) {
            result.error("SETTINGS_ERROR", e.message, null)
        }
    }

    /**
     * Extract invocation data from the pending ASSIST intent.
     *
     * Returns a Map with keys: trigger, assistContext, assistUri, callingPackage
     */
    private fun handleGetInvocationData(result: MethodChannel.Result) {
        try {
            val intent = pendingAssistIntent
            if (intent == null) {
                result.error("NO_INVOCATION", "No pending assistant invocation", null)
                return
            }

            val data = hashMapOf<String, String?>()

            // Detect trigger type (best-effort)
            data["trigger"] = when {
                intent.hasExtra("android.intent.extra.ASSIST_INPUT") -> "voiceTrigger"
                intent.component != null -> "externalApp"
                else -> "homeButton"
            }

            // Extract standard assistant extras
            data["assistContext"] = intent.getStringExtra(Intent.EXTRA_ASSIST_CONTEXT)
            data["assistUri"] = intent.getStringExtra(Intent.EXTRA_ASSIST_URI)
            data["callingPackage"] = intent.`package`

            // Clear the pending intent after extraction
            pendingAssistIntent = null

            result.success(data)
        } catch (e: Exception) {
            result.error("INVOCATION_ERROR", e.message, null)
        }
    }

    /**
     * Return the device's Android API level.
     */
    private fun handleGetAndroidApiLevel(result: MethodChannel.Result) {
        result.success(Build.VERSION.SDK_INT)
    }
}
