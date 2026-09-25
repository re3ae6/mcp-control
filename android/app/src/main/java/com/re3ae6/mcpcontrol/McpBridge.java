package com.re3ae6.mcpcontrol;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class McpBridge {
    private McpBridge() {}

    public static boolean run(Context context, String... args) {
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"));
        i.putExtra("com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/home/mcp-control/tools/mobile_control.sh");
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home/mcp-control");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        Intent result = new Intent(context, PluginResultsService.class);
        result.putExtra("mcp_control_command", args.length == 0 ? "" : args[0]);
        int code = (int)(System.currentTimeMillis() & 0x7fffffff);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_MUTABLE;
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",
                PendingIntent.getService(context, code, result, flags));
        try {
            context.startService(i);
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
