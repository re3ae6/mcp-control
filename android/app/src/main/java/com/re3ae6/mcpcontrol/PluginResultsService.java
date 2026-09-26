package com.re3ae6.mcpcontrol;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;

public class PluginResultsService extends IntentService {
    static final String BUNDLE = "result";
    static final String STDOUT = "stdout";
    static final String STDERR = "stderr";
    static final String EXIT = "exitCode";
    static final String ERR = "err";
    static final String ERRMSG = "errmsg";

    public PluginResultsService() { super("McpControlResults"); }

    @Override protected void onHandleIntent(Intent intent) {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences.Editor diagnostic =
                getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putLong("callback_received_at", now)
                .putString("callback_transport", "service");

        if (intent == null) {
            diagnostic.putString("callback_state", "intent_missing").apply();
            return;
        }

        Bundle b = intent.getBundleExtra(BUNDLE);
        if (b == null) {
            diagnostic.putString("callback_state", "bundle_missing")
                    .putString("callback_stage", "finished")
                    .apply();
            return;
        }

        String stdout = b.getString(STDOUT, "");
        String stderr = b.getString(STDERR, "");
        int exit = b.getInt(EXIT, -1);
        int errorCode = b.getInt(ERR, -1);
        String errorMessage = b.getString(ERRMSG, "");
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
                .putString("callback_stage", "finished")
                .putString("callback_transport", "service");

        if (command != null && !command.isEmpty()) {
            e.putString("callback_state_" + command, "received")
             .putLong("callback_received_at_" + command, now)
             .putString("stdout_" + command, stdout)
             .putString("stderr_" + command, stderr)
             .putInt("exit_" + command, exit)
             .putInt("error_code_" + command, errorCode)
             .putString("error_message_" + command, errorMessage)
             .putString("callback_stage_" + command, "finished")
             .putLong("received_at_" + command, now);
        }
        e.apply();

        // Push status results to the live monitor immediately instead of making
        // the UI wait for its polling timeout.
        if ("status".equals(command) && stdout != null && !stdout.isEmpty()) {
            try {
                Intent update = new Intent(this, ConnectionMonitorService.class)
                        .setAction(ConnectionMonitorService.ACTION_STATUS_UPDATE)
                        .putExtra("status_json", stdout);
                startService(update);
            } catch (RuntimeException ignored) {}
        }
    }
}
