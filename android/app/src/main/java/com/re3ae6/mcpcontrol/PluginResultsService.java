package com.re3ae6.mcpcontrol;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;

public class PluginResultsService extends IntentService {
    static final String BUNDLE = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE";
    static final String STDOUT = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT";
    static final String STDERR = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR";
    static final String EXIT = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE";

    public PluginResultsService() { super("McpControlResults"); }

    @Override protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        Bundle b = intent.getBundleExtra(BUNDLE);
        if (b == null) return;
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("stdout", b.getString(STDOUT, ""))
                .putString("stderr", b.getString(STDERR, ""))
                .putInt("exit", b.getInt(EXIT, -1))
                .putLong("received_at", System.currentTimeMillis())
                .apply();
    }
}
