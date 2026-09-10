package com.aura.assistant.trigger

import android.content.Intent
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel

import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.CHANNEL_NAME
import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.METHOD_TRIGGER_REQUEST
import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.ARG_TRIGGER_TYPE
import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.ARG_REQUEST_ID
import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.ARG_TEXT_PAYLOAD
import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.ARG_IS_VOICE_INPUT
import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.ARG_LOCALE
import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.RESULT_STATUS
import com.aura.assistant.trigger.TriggerIntegrationPlugin.Companion.STATUS_UNAVAILABLE

private const val TAG = "AuraTileService"

/**
 * Step 24 — Aura Quick Settings Tile Service
 *
 * Provides a Quick Settings tile that launches AURA assistant.
 *
 * FAIL-CLOSED design:
 * - If Flutter engine is unavailable, tile state → STATE_UNAVAILABLE
 * - If MethodChannel call fails, tile state → STATE_UNAVAILABLE
 * - Never claims success when the engine is not running.
 *
 * Kurdish Sorani RTL first (locale='ku').
 *
 * IMPORTANT: Third-party apps CANNOT directly intercept Home button
 * long-press. This tile provides one of the supported trigger paths.
 * The assistant/home long-press path requires system-level integration
 * via Step 15's assistant role APIs.
 */
class AuraQuickSettingsTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Aura QS tile added")
        updateTileState(Tile.STATE_INACTIVE)
    }

    override fun onStartListening() {
        super.onStartListening()
        val engine = getFlutterEngine()
        if (engine == null) {
            Log.w(TAG, "Flutter engine unavailable — tile STATE_UNAVAILABLE")
            updateTileState(Tile.STATE_UNAVAILABLE)
        } else {
            updateTileState(Tile.STATE_INACTIVE)
        }
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Aura QS tile clicked")

        val engine = getFlutterEngine()
        if (engine == null) {
            // FAIL-CLOSED: engine unavailable → mark tile unavailable
            Log.w(TAG, "Flutter engine not available — cannot trigger")
            updateTileState(Tile.STATE_UNAVAILABLE)
            return
        }

        val dartExecutor = engine.dartExecutor
        if (!dartExecutor.isExecutingDart) {
            // FAIL-CLOSED: Dart not executing → unavailable
            Log.w(TAG, "Dart executor not running — cannot trigger")
            updateTileState(Tile.STATE_UNAVAILABLE)
            return
        }

        val requestId = java.util.UUID.randomUUID().toString()

        try {
            val channel = MethodChannel(dartExecutor.binaryMessenger, CHANNEL_NAME)
            channel.invokeMethod(
                METHOD_TRIGGER_REQUEST,
                mapOf(
                    ARG_TRIGGER_TYPE to "quickSettings",
                    ARG_REQUEST_ID to requestId,
                    ARG_TEXT_PAYLOAD to null,
                    ARG_IS_VOICE_INPUT to true,
                    ARG_LOCALE to "ku"
                ),
                object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        val status = (result as? Map<*, *>)?.get(RESULT_STATUS) as? String
                        if (status == "launched") {
                            updateTileState(Tile.STATE_ACTIVE)
                        } else {
                            // FAIL-CLOSED: any non-launched result → inactive
                            updateTileState(Tile.STATE_INACTIVE)
                        }
                    }

                    override fun error(
                        errorCode: String,
                        errorMessage: String?,
                        errorDetails: Any?
                    ) {
                        Log.e(TAG, "MethodChannel error: $errorCode — $errorMessage")
                        // FAIL-CLOSED: error → tile unavailable
                        updateTileState(Tile.STATE_UNAVAILABLE)
                    }

                    override fun notImplemented() {
                        Log.e(TAG, "MethodChannel notImplemented")
                        // FAIL-CLOSED: not implemented → tile unavailable
                        updateTileState(Tile.STATE_UNAVAILABLE)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception invoking MethodChannel", e)
            // FAIL-CLOSED: exception → tile unavailable
            updateTileState(Tile.STATE_UNAVAILABLE)
        }
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Log.d(TAG, "Aura QS tile removed")
    }

    // ---- Internal helpers ----

    private fun getFlutterEngine(): FlutterEngine? {
        return try {
            FlutterEngineCache.getInstance()
                .get(getString(R.string.flutter_engine_id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve Flutter engine from cache", e)
            null
        }
    }

    private fun updateTileState(state: Int) {
        val tile = qsTile ?: return
        tile.state = state
        when (state) {
            Tile.STATE_ACTIVE -> {
                tile.label = "ئاورا — چالاک"
                tile.subtitle = ""
            }
            Tile.STATE_INACTIVE -> {
                tile.label = "ئاورا — چاوەڕوان"
                tile.subtitle = ""
            }
            Tile.STATE_UNAVAILABLE -> {
                tile.label = "ئاورا — بەردەست نییە"
                tile.subtitle = ""
            }
        }
        tile.updateTile()
    }
}
