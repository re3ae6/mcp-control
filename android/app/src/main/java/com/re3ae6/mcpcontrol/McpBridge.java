package com.re3ae6.mcpcontrol;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class McpBridge {
    private McpBridge() {}

    public static void run(Context context, String... args) {
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"));
        i.putExtra("com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/home/mcp-control/tools/mobile_control.sh");
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home/mcp-control");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        Intent result = new Intent(context, PluginResultsService.class);
        int code = (int)(System.currentTimeMillis() & 0x7fffffff);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_MUTABLE;
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",
                PendingIntent.getService(context, code, result, flags));
        context.startService(i);
    }
}
