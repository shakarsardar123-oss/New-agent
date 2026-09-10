package com.aura.aura_assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity

/// Activity launched by the alarm full-screen notification.
/// Receives the alarm ID as an intent extra and forwards it
/// to the Flutter engine via a method channel or intent extra.
class AlarmWakeActivity : FlutterActivity() {

    companion object {
        private const val TAG = "AlarmWakeActivity"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val ACTION_ALARM_WAKE = "com.aura.aura_assistant.ALARM_WAKE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID) ?: ""
        Log.d(TAG, "AlarmWakeActivity launched with alarmId: $alarmId")

        // The alarm ID will be picked up by the Flutter side
        // via SharedPreferences (pending_alarm_id) written by
        // the isolate bridge in _alarmCallback.
        // This activity ensures the app is brought to foreground.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: ""
        Log.d(TAG, "AlarmWakeActivity onNewIntent with alarmId: $alarmId")
    }
}