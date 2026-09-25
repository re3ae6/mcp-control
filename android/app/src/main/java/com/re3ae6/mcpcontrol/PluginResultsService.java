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

        String stdout = b.getString(STDOUT, "");
        String stderr = b.getString(STDERR, "");
        int exit = b.getInt(EXIT, -1);
        long now = System.currentTimeMillis();
        String command = intent.getStringExtra("mcp_control_command");

        android.content.SharedPreferences.Editor e = getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("stdout", stdout)
                .putString("stderr", stderr)
                .putInt("exit", exit)
                .putString("last_command", command == null ? "" : command)
                .putLong("received_at", now);

        if (command != null && !command.isEmpty()) {
            e.putString("stdout_" + command, stdout)
             .putString("stderr_" + command, stderr)
             .putInt("exit_" + command, exit)
             .putLong("received_at_" + command, now);
        }
        e.apply();
    }
}
