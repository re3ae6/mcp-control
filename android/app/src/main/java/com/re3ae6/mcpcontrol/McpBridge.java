package com.re3ae6.mcpcontrol;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.UUID;

public final class McpBridge {
    private McpBridge() {}

    public static boolean run(Context context, String... args) {
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"));
        i.putExtra("com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/home/mcp-control/tools/mobile_control_bridge.sh");
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home/mcp-control");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");

        String command = args.length == 0 ? "" : args[0];
        String token = UUID.randomUUID().toString();
        String[] bridgeArgs = new String[args.length + 1];
        System.arraycopy(args, 0, bridgeArgs, 0, args.length);
        bridgeArgs[args.length] = "__MCP_CONTROL_TOKEN__=" + token;
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", bridgeArgs);

        Intent result = new Intent(context, PluginResultsReceiver.class);
        result.putExtra("mcp_control_command", command);
        result.putExtra("mcp_control_pending", true);
        int code = (int)(System.currentTimeMillis() & 0x7fffffff);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",
                PendingIntent.getBroadcast(context, code, result, flags));

        long sentAt = System.currentTimeMillis();
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                .putString("sent_command", command)
                .putInt("sent_execution_id", code)
                .putLong("sent_at_" + command, sentAt)
                .putString("callback_state_" + command, "pending")
                .putString("callback_token_" + command, token)
                .apply();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                    .putString("last_error", "")
                    .putLong("last_error_at", 0L)
                    .apply();
            return true;
        } catch (RuntimeException e) {
            String detail = e.getClass().getSimpleName();
            if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                detail += ": " + e.getMessage();
            }
            context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                    .putString("last_error", "Termux bridge: " + detail)
                    .putLong("last_error_at", System.currentTimeMillis())
                    .apply();
            return false;
        }
    }
}
