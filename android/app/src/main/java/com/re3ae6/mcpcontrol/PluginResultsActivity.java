package com.re3ae6.mcpcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class PluginResultsActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
        finish();
    }

    private void handle(Intent intent) {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences.Editor diagnostic =
                getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putLong("callback_received_at", now)
                .putString("callback_transport", "activity");

        if (intent == null) {
            diagnostic.putString("callback_state", "intent_missing").apply();
            return;
        }

        Bundle b = intent.getBundleExtra(PluginResultsService.BUNDLE);
        if (b == null) {
            diagnostic.putString("callback_state", "bundle_missing").apply();
            return;
        }

        String stdout = b.getString(PluginResultsService.STDOUT, "");
        String stderr = b.getString(PluginResultsService.STDERR, "");
        int exit = b.getInt(PluginResultsService.EXIT, -1);
        int errorCode = b.getInt(PluginResultsService.ERR, -1);
        String errorMessage = b.getString(PluginResultsService.ERRMSG, "");
        String command = intent.getStringExtra("mcp_control_command");

        android.content.SharedPreferences.Editor e =
                getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("stdout", stdout)
                .putString("stderr", stderr)
                .putInt("exit", exit)
                .putInt("error_code", errorCode)
                .putString("error_message", errorMessage)
                .putString("last_command", command == null ? "" : command)
                .putLong("received_at", now)
                .putString("callback_state", "received")
                .putString("callback_transport", "activity");

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
