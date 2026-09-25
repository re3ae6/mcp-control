package com.re3ae6.mcpcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class PluginResultsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences.Editor diagnostic =
                context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                .putLong("callback_received_at", now)
                .putString("callback_transport", "broadcast");

        if (intent == null) {
            diagnostic.putString("callback_state", "intent_missing").apply();
            return;
        }

        String command = intent.getStringExtra("mcp_control_command");
        if (command == null || command.isEmpty()) {
            command = intent.getStringExtra("com.re3ae6.mcpcontrol.COMMAND");
        }
        String token = intent.getStringExtra("com.re3ae6.mcpcontrol.TOKEN");
        if (command == null || command.isEmpty() || token == null || token.isEmpty()) {
            diagnostic.putString("callback_state", "callback_missing_identity").apply();
            return;
        }
        String expected = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .getString("callback_token_" + command, "");
        if (expected.isEmpty() || !expected.equals(token)) {
            diagnostic.putString("callback_state", "callback_token_mismatch").apply();
            return;
        }

        Bundle b = intent.getBundleExtra(PluginResultsService.BUNDLE);
        String stdout = b == null ? intent.getStringExtra("com.re3ae6.mcpcontrol.STDOUT") : b.getString(PluginResultsService.STDOUT, "");
        String stderr = b == null ? intent.getStringExtra("com.re3ae6.mcpcontrol.STDERR") : b.getString(PluginResultsService.STDERR, "");
        int exit = b == null ? intent.getIntExtra("com.re3ae6.mcpcontrol.EXIT", -1) : b.getInt(PluginResultsService.EXIT, -1);
        int errorCode = b == null ? intent.getIntExtra("com.re3ae6.mcpcontrol.ERROR_CODE", -1) : b.getInt(PluginResultsService.ERR, -1);
        String errorMessage = b == null ? intent.getStringExtra("com.re3ae6.mcpcontrol.ERROR_MESSAGE") : b.getString(PluginResultsService.ERRMSG, "");

        android.content.SharedPreferences.Editor e =
                context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                .putString("stdout", stdout)
                .putString("stderr", stderr)
                .putInt("exit", exit)
                .putInt("error_code", errorCode)
                .putString("error_message", errorMessage)
                .putString("last_command", command == null ? "" : command)
                .putLong("received_at", now)
                .putString("callback_state", "received")
                .putString("callback_transport", "broadcast");

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
