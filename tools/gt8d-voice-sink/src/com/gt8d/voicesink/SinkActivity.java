package com.gt8d.voicesink;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;

/** Ends only the standard voice-search entry point. Never reads recognition extras. */
public final class SinkActivity extends Activity {
    public static final String TAG = "GT8DVoiceSink";
    public static final String PREFS = "received_actions";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String action = intent == null ? null : intent.getAction();
        if (RecognizerIntent.ACTION_WEB_SEARCH.equals(action)
                || RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE.equals(action)) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putInt(action, prefs.getInt(action, 0) + 1)
                    .putLong("last_received", System.currentTimeMillis()).apply();
            Log.i(TAG, "consumed action=" + action);
        }
        // Theme.NoDisplay requires finishing before onResume. Do not start another component.
        finish();
    }
}
