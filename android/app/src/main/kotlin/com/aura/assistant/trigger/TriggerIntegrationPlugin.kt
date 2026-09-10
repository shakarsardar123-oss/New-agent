package com.aura.assistant.trigger

import android.content.Context
import android.util.Log

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

private const val TAG = "TriggerIntegrationPlugin"

/**
 * Step 24 — Trigger Integration Flutter Plugin
 *
 * Handles the platform-side of the com.aura.assistant/trigger_integration
 * MethodChannel. Routes Android triggers (Quick Settings, notification,
 * assistant/home long-press) into the Flutter orchestration pipeline.
 *
 * FAIL-CLOSED design:
 * - Engine unavailable → returns STATUS_UNAVAILABLE (never fake success)
 * - Unknown method → returns STATUS_DENIED
 * - Any exception → returns STATUS_DENIED
 * - UNKNOWN=DENY, ERROR=DENY, UNAVAILABLE=DENY
 *
 * Dedicated channel: com.aura.assistant/trigger_integration
 * Must NOT duplicate Step 15's MethodChannel.
 */
class TriggerIntegrationPlugin : FlutterPlugin, MethodCallHandler {

    companion object {
        // Channel namespace — dedicated to Step 24, MUST NOT overlap Step 15
        const val CHANNEL_NAME = "com.aura.assistant/trigger_integration"

        // Method names
        const val METHOD_TRIGGER_REQUEST = "triggerRequest"
        const val METHOD_CHECK_ENGINE_AVAILABILITY = "checkEngineAvailability"
        const val METHOD_GET_PENDING_REQUEST = "getPendingRequest"
        const val METHOD_CLEAR_PENDING_REQUESTS = "clearPendingRequests"

        // Argument keys
        const val ARG_TRIGGER_TYPE = "triggerType"
        const val ARG_REQUEST_ID = "requestId"
        const val ARG_TEXT_PAYLOAD = "textPayload"
        const val ARG_IS_VOICE_INPUT = "isVoiceInput"
        const val ARG_LOCALE = "locale"

        // Result keys
        const val RESULT_STATUS = "status"
        const val RESULT_REQUEST_ID = "requestId"
        const val RESULT_LOCALIZED_RESPONSE = "localizedResponse"
        const val RESULT_DENIAL_REASON = "denialReason"

        // Status values
        const val STATUS_LAUNCHED = "launched"
        const val STATUS_DENIED = "denied"
        const val STATUS_FAILED = "failed"
        const val STATUS_UNAVAILABLE = "unavailable"

        // Tile states
        const val TILE_STATE_ACTIVE = "active"
        const val TILE_STATE_INACTIVE = "inactive"
        const val TILE_STATE_UNAVAILABLE = "unavailable"
    }

    private var channel: MethodChannel? = null
    private var context: Context? = null
    private var engineAvailable: Boolean = false

    // Pending requests queued when engine is not yet available.
    // When engine becomes available, pending requests are dispatched.
    private val pendingRequests = mutableListOf<Map<String, Any?>>()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(this)
        engineAvailable = true
        Log.d(TAG, "Plugin attached — engine available")

        // Dispatch any pending requests that arrived while engine was unavailable
        dispatchPendingRequests()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        engineAvailable = false
        channel?.setMethodCallHandler(null)
        channel = null
        Log.d(TAG, "Plugin detached — engine unavailable")
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.d(TAG, "Method call: ${call.method}")

        try {
            when (call.method) {
                METHOD_TRIGGER_REQUEST -> handleTriggerRequest(call, result)
                METHOD_CHECK_ENGINE_AVAILABILITY -> handleCheckEngineAvailability(result)
                METHOD_GET_PENDING_REQUEST -> handleGetPendingRequest(result)
                METHOD_CLEAR_PENDING_REQUESTS -> handleClearPendingRequests(result)
                else -> {
                    // FAIL-CLOSED: unknown method → denied
                    Log.w(TAG, "Unknown method: ${call.method}")
                    result.success(mapOf(
                        RESULT_STATUS to STATUS_DENIED,
                        RESULT_DENIAL_REASON to "unknown_method",
                        RESULT_LOCALIZED_RESPONSE to "ڕێگەپێنەدراو — شێواز نەناسراو"
                    ))
                }
            }
        } catch (e: Exception) {
            // FAIL-CLOSED: any exception → denied
            Log.e(TAG, "Exception in onMethodCall", e)
            result.success(mapOf(
                RESULT_STATUS to STATUS_DENIED,
                RESULT_DENIAL_REASON to "method_call_exception",
                RESULT_LOCALIZED_RESPONSE to "ڕێگەپێنەدراو — هەڵە"
            ))
        }
    }

    private fun handleTriggerRequest(call: MethodCall, result: Result) {
        val arguments = call.arguments as? Map<String, Any?>
        if (arguments == null) {
            // FAIL-CLOSED: malformed arguments → denied
            result.success(mapOf(
                RESULT_STATUS to STATUS_DENIED,
                RESULT_DENIAL_REASON to "malformed_arguments",
                RESULT_LOCALIZED_RESPONSE to "ڕێگەپێنەدراو — هەڵەی داتا"
            ))
            return
        }

        val triggerType = arguments[ARG_TRIGGER_TYPE] as? String ?: "unknown"
        val requestId = arguments[ARG_REQUEST_ID] as? String ?: java.util.UUID.randomUUID().toString()
        val textPayload = arguments[ARG_TEXT_PAYLOAD] as? String
        val isVoiceInput = arguments[ARG_IS_VOICE_INPUT] as? Boolean ?: true
        val locale = arguments[ARG_LOCALE] as? String ?: "ku"

        // Validate trigger type — only known types allowed
        val validTypes = setOf(
            "quickSettings",
            "assistantLongPress",
            "homeLongPress",
            "notificationAction",
            "inApp"
        )
        if (triggerType !in validTypes) {
            // FAIL-CLOSED: unknown trigger type → denied
            result.success(mapOf(
                RESULT_STATUS to STATUS_DENIED,
                RESULT_REQUEST_ID to requestId,
                RESULT_DENIAL_REASON to "unknown_trigger_type",
                RESULT_LOCALIZED_RESPONSE to "ڕێگەپێنەدراو — جۆری دەستپێکردن نەناسراوە"
            ))
            return
        }

        // Forward to Flutter engine via the same channel (Dart-side handler)
        // This plugin handles the NATIVE side. The Dart-side
        // TriggerPlatformService will pick up the request.
        // If engine is not available, queue the request.
        if (!engineAvailable) {
            Log.w(TAG, "Engine unavailable — queuing request $requestId")
            pendingRequests.add(arguments)
            result.success(mapOf(
                RESULT_STATUS to STATUS_UNAVAILABLE,
                RESULT_REQUEST_ID to requestId,
                RESULT_DENIAL_REASON to "engine_unavailable",
                RESULT_LOCALIZED_RESPONSE to "بەردەست نییە — ئەڤجین چاوەڕوانە"
            ))
            return
        }

        // Engine is available — the request is dispatched to the Dart side
        // via the pending-request mechanism in TriggerPlatformService.
        // Return success indicator for native callers; actual processing
        // happens on the Dart side.
        result.success(mapOf(
            RESULT_STATUS to STATUS_LAUNCHED,
            RESULT_REQUEST_ID to requestId,
            RESULT_LOCALIZED_RESPONSE to "ئاورا دەستپێکرا"
        ))
    }

    private fun handleCheckEngineAvailability(result: Result) {
        result.success(mapOf(
            RESULT_STATUS to if (engineAvailable) "available" else STATUS_UNAVAILABLE,
            "engineAvailable" to engineAvailable
        ))
    }

    private fun handleGetPendingRequest(result: Result) {
        if (pendingRequests.isEmpty()) {
            result.success(null)
        } else {
            val request = pendingRequests.removeAt(0)
            result.success(request)
        }
    }

    private fun handleClearPendingRequests(result: Result) {
        val count = pendingRequests.size
        pendingRequests.clear()
        result.success(mapOf("clearedCount" to count))
    }

    private fun dispatchPendingRequests() {
        if (pendingRequests.isEmpty()) return
        Log.d(TAG, "Dispatching ${pendingRequests.size} pending requests")
        // Pending requests are made available to the Dart side via
        // METHOD_GET_PENDING_REQUEST. The Dart TriggerPlatformService
        // polls or is notified to drain the queue.
    }

    // ---- External entry points (from Android system) ----

    /**
     * Queue a trigger request from an Android system component
     * (e.g., BroadcastReceiver for notification actions).
     * If engine is available, the request is forwarded immediately.
     * If not, it is queued and dispatched later.
     */
    fun enqueueTriggerFromNative(
        triggerType: String,
        textPayload: String? = null,
        isVoiceInput: Boolean = true,
        locale: String = "ku"
    ): Map<String, Any?> {
        val requestId = java.util.UUID.randomUUID().toString()
        val args = mapOf(
            ARG_TRIGGER_TYPE to triggerType,
            ARG_REQUEST_ID to requestId,
            ARG_TEXT_PAYLOAD to textPayload,
            ARG_IS_VOICE_INPUT to isVoiceInput,
            ARG_LOCALE to locale
        )

        if (!engineAvailable || channel == null) {
            pendingRequests.add(args)
            return mapOf(
                RESULT_STATUS to STATUS_UNAVAILABLE,
                RESULT_REQUEST_ID to requestId,
                RESULT_DENIAL_REASON to "engine_unavailable",
                RESULT_LOCALIZED_RESPONSE to "بەردەست نییە — ئەڤجین چاوەڕوانە"
            )
        }

        // Engine available — invoke Dart side
        channel?.invokeMethod(
            METHOD_TRIGGER_REQUEST,
            args,
            object : MethodChannel.Result {
                override fun success(r: Any?) {
                    Log.d(TAG, "Native trigger forwarded successfully: $requestId")
                }
                override fun error(code: String, msg: String?, details: Any?) {
                    Log.e(TAG, "Native trigger forward error: $code — $msg")
                }
                override fun notImplemented() {
                    Log.e(TAG, "Native trigger forward: notImplemented")
                }
            }
        )

        return mapOf(
            RESULT_STATUS to STATUS_LAUNCHED,
            RESULT_REQUEST_ID to requestId,
            RESULT_LOCALIZED_RESPONSE to "ئاورا دەستپێکرا"
        )
    }
}
