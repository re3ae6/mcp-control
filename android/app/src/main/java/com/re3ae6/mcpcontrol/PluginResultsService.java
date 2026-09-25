package com.re3ae6.mcpcontrol;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;

public class PluginResultsService extends IntentService {
    static final String BUNDLE = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE";
    static final String STDOUT = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT";
    static final String STDERR = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR";
    static final String EXIT = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE";
    static final String ERR = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_ERR";
    static final String ERRMSG = "com.termux.service.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG";

    public PluginResultsService() { super("McpControlResults"); }

    @Override protected void onHandleIntent(Intent intent) {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences.Editor diagnostic = getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putLong("callback_received_at", now);
        if (intent == null) {
            diagnostic.putString("callback_state", "intent_missing").apply();
            return;
        }
        Bundle b = intent.getBundleExtra(BUNDLE);
        if (b == null) {
            diagnostic.putString("callback_state", "bundle_missing").apply();
            return;
        }

        String stdout = b.getString(STDOUT, "");
        String stderr = b.getString(STDERR, "");
        int exit = b.getInt(EXIT, -1);
        int errorCode = b.getInt(ERR, -1);
        String errorMessage = b.getString(ERRMSG, "");
        String command = intent.getStringExtra("mcp_control_command");

        android.content.SharedPreferences.Editor e = getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("stdout", stdout)
                .putString("stderr", stderr)
                .putInt("exit", exit)
                .putInt("error_code", errorCode)
                .putString("error_message", errorMessage)
                .putString("last_command", command == null ? "" : command)
                .putLong("received_at", now);

        if (command != null && !command.isEmpty()) {
            e.putString("callback_state_" + command, "received")
             .putLong("callback_received_at_" + command, now)
             .putString("stdout_" + command, stdout)
             .putString("stderr_" + command, stderr)
             .putInt("exit_" + command, exit)
             .putInt("error_code_" + command, errorCode)
             .putString("error_message_" + command, errorMessage)
             .putLong("received_at_" + command, now);
        }
        e.apply();
    }
}
