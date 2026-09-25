package com.re3ae6.mcpcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.RemoteViews;

import org.json.JSONObject;

public class ConnectionMonitorService extends Service {
    private static final String CHANNEL_ID = "mcp_connection_monitor";
    private static final String ACTION_EXIT = "com.re3ae6.mcpcontrol.EXIT";
    private static final String ACTION_NOOP = "com.re3ae6.mcpcontrol.NOOP";
    static final String ACTION_STATUS_UPDATE = "com.re3ae6.mcpcontrol.STATUS_UPDATE";
    private static final int NOTIFICATION_ID = 4201;
    private static final long INTERVAL_MS = 10000L;
    private static final long RESULT_WAIT_MS = 1600L;

    private final Handler handler = new Handler();
    private PowerManager.WakeLock wakeLock;
    private boolean checking = false;
    private boolean mcpReady = false;
    private boolean proxyReady = false;
    private boolean tunnelReady = false;
    private long lastAcceptedAt = 0L;

    private static final int GREEN = 0xFF4CAF50;
    private static final int RED = 0xFFF44336;
    private static final int YELLOW = 0xFFFFB300;
    private static final long FRESH_MS = 20000L;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!checking) checkConnection();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        restoreLastStatus();
        recordMonitorEvent("Monitor started");
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "McpControl:ConnectionMonitor");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }

        handler.post(loop);
    }

    private void restoreLastStatus() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("bridge", MODE_PRIVATE);
            String out = prefs.getString("monitor_status_json", "");
            lastAcceptedAt = prefs.getLong("monitor_received_at", 0L);
            if (out == null || out.isEmpty()) return;
            applyStatusJson(new JSONObject(out), false, lastAcceptedAt);
        } catch (Exception ignored) {}
    }

    private boolean isMcpReady(JSONObject o) {
        return "OK".equalsIgnoreCase(o.optString("mcp")) || o.optBoolean("mcp_ok", false);
    }

    private boolean isProxyReady(JSONObject o) {
        return "OK".equalsIgnoreCase(o.optString("proxy")) || o.optBoolean("proxy_ok", false);
    }

    private boolean isTunnelReady(JSONObject o) {
        String tunnel = o.optString("tunnel", "").toLowerCase();
        return tunnel.contains("live") || tunnel.contains("ready") || o.optBoolean("tunnel_ok", false);
    }

    private void applyStatusJson(JSONObject result, boolean notify, long receivedAt) {
        if (result == null) return;
        if (receivedAt <= 0L) receivedAt = System.currentTimeMillis();
        if (lastAcceptedAt > receivedAt) {
            if (notify) updateNotification();
            return;
        }

        mcpReady = isMcpReady(result);
        proxyReady = isProxyReady(result);
        tunnelReady = isTunnelReady(result);
        lastAcceptedAt = receivedAt;

        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("monitor_connected", mcpReady && proxyReady && tunnelReady)
                .putBoolean("monitor_mcp", mcpReady)
                .putBoolean("monitor_proxy", proxyReady)
                .putBoolean("monitor_tunnel", tunnelReady)
                .putBoolean("monitor_fresh", true)
                .putLong("monitor_received_at", receivedAt)
                .putString("monitor_status_json", result.toString())
                .putString("monitor_state", "connection_snapshot")
                .putString("monitor_last_event",
                        "Connection state: MCP " + (mcpReady ? "OK" : "DOWN")
                                + " / Proxy " + (proxyReady ? "OK" : "DOWN")
                                + " / Tunnel " + (tunnelReady ? "LIVE" : "DOWN"))
                .putLong("monitor_last_event_at", System.currentTimeMillis())
                .apply();
        if (notify) updateNotification();
    }

    private void restoreNotificationState(android.content.SharedPreferences prefs) {
        mcpReady = prefs.getBoolean("monitor_mcp", mcpReady);
        proxyReady = prefs.getBoolean("monitor_proxy", proxyReady);
        tunnelReady = prefs.getBoolean("monitor_tunnel", tunnelReady);
        String json = prefs.getString("monitor_status_json", "");
        try {
            if (!json.isEmpty()) {
                JSONObject o = new JSONObject(json);
                mcpReady = isMcpReady(o);
                proxyReady = isProxyReady(o);
                tunnelReady = isTunnelReady(o);
            }
        } catch (Exception ignored) {}
    }

    private void recordMonitorEvent(String event) {
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("monitor_last_event", event == null ? "" : event)
                .putLong("monitor_last_event_at", System.currentTimeMillis())
                .apply();
    }

    private void checkConnection() {
        android.content.SharedPreferences prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        if (!prefs.getBoolean("monitor_enabled", true) ||
                prefs.getBoolean("emergency_killed", false)) return;
        String state = prefs.getString("callback_state_status", "");
        long sentAt = prefs.getLong("sent_at_status", 0L);
        if ("pending".equals(state) && System.currentTimeMillis() - sentAt < 8000L) return;

        checking = true;
        if (!McpBridge.run(this, "status")) {
            checking = false;
            return;
        }

        final long expectedSentAt = prefs.getLong("sent_at_status", System.currentTimeMillis());
        handler.postDelayed(() -> finishCheck(expectedSentAt), RESULT_WAIT_MS);
    }

    private void finishCheck(long sentAt) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("bridge", MODE_PRIVATE);
            long receivedAt = prefs.getLong("received_at_status", 0L);
            String state = prefs.getString("callback_state_status", "");
            String out = prefs.getString("stdout_status", "");

            if (receivedAt >= sentAt && "received".equals(state)) {
                try {
                    JSONObject result = new JSONObject(out);
                    if (result.has("mcp") || result.has("proxy") ||
                            result.has("tunnel") || result.has("connected")) {
                        boolean oldMcp = mcpReady, oldProxy = proxyReady, oldTunnel = tunnelReady;
                        applyStatusJson(result, false, receivedAt);
                        if (oldMcp != mcpReady || oldProxy != proxyReady || oldTunnel != tunnelReady) {
                            updateNotification();
                        }
                    } else {
                        getSharedPreferences("bridge", MODE_PRIVATE)
                                .edit().putBoolean("monitor_fresh", false).apply();
                    }
                } catch (Exception ignored) {}
            } else if (System.currentTimeMillis() - sentAt >= RESULT_WAIT_MS) {
                getSharedPreferences("bridge", MODE_PRIVATE)
                        .edit().putBoolean("monitor_fresh", false).apply();
            }
        } finally {
            checking = false;
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) createChannel();

        PendingIntent exitIntent = activityExitIntent();
        String mcpLight = "●";
        String proxyLight = "●";
        String tunnelLight = "●";

        RemoteViews small = new RemoteViews(getPackageName(), R.layout.notification_monitor_small);
        setMonitorViews(small, mcpLight, proxyLight, tunnelLight);
        setMonitorLightColors(small);
        small.setOnClickPendingIntent(R.id.notification_exit, exitIntent);

        RemoteViews large = new RemoteViews(getPackageName(), R.layout.notification_monitor_large);
        setMonitorViews(large, mcpLight, proxyLight, tunnelLight);
        setMonitorLightColors(large);
        large.setOnClickPendingIntent(R.id.notification_exit, exitIntent);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MCP Control")
                .setSmallIcon(R.drawable.ic_notification)
                .setCustomContentView(small)
                .setCustomBigContentView(large)
                .setCustomHeadsUpContentView(large)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private PendingIntent activityExitIntent() {
        Intent i = new Intent(this, ConnectionMonitorService.class).setAction(ACTION_EXIT);
        return PendingIntent.getService(this, 4205, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent i = new Intent(this, ConnectionMonitorService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void setMonitorLightColors(RemoteViews views) {
        boolean fresh = lastAcceptedAt > 0L &&
                System.currentTimeMillis() - lastAcceptedAt <= FRESH_MS;
        int mcpColor = fresh ? (mcpReady ? GREEN : RED) : YELLOW;
        int proxyColor = fresh ? (proxyReady ? GREEN : RED) : YELLOW;
        int tunnelColor = fresh ? (tunnelReady ? GREEN : RED) : YELLOW;
        views.setTextColor(R.id.notification_mcp_light, mcpColor);
        views.setTextColor(R.id.notification_proxy_light, proxyColor);
        views.setTextColor(R.id.notification_tunnel_light, tunnelColor);
    }

    private void setMonitorViews(RemoteViews views, String mcpLight, String proxyLight,
                                 String tunnelLight) {
        views.setTextViewText(R.id.notification_mcp, "MCP");
        views.setTextViewText(R.id.notification_proxy, "Proxy");
        views.setTextViewText(R.id.notification_tunnel, "Tunnel");
        views.setTextViewText(R.id.notification_mcp_light, mcpLight);
        views.setTextViewText(R.id.notification_proxy_light, proxyLight);
        views.setTextViewText(R.id.notification_tunnel_light, tunnelLight);
        views.setTextViewText(R.id.notification_exit, "⛔");
        views.setTextColor(R.id.notification_exit, 0xFFFFFFFF);
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MCP connection monitor",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the MCP connection monitor alive while MCP Control is in the background.");
        nm.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (ACTION_STATUS_UPDATE.equals(action)) {
                String out = intent.getStringExtra("status_json");
                try {
                    if (out != null && !out.isEmpty()) {
                        android.content.SharedPreferences prefs =
                                getSharedPreferences("bridge", MODE_PRIVATE);
                        long at = prefs.getLong("received_at_status", System.currentTimeMillis());
                        applyStatusJson(new JSONObject(out), true, at);
                    }
                } catch (Exception ignored) {}
                return START_STICKY;
            }

            if (ACTION_EXIT.equals(action)) {
                getSharedPreferences("bridge", MODE_PRIVATE).edit()
                        .putBoolean("monitor_enabled", false)
                        .putBoolean("activity_visible", false)
                        .apply();

                checking = false;
                handler.removeCallbacksAndMessages(null);

                try {
                    ActivityManager am =
                            (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    if (am != null) {
                        for (ActivityManager.AppTask task : am.getAppTasks()) {
                            try { task.finishAndRemoveTask(); } catch (RuntimeException ignored) {}
                        }
                    }
                } catch (RuntimeException ignored) {}

                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        android.content.SharedPreferences prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        if (prefs.getBoolean("monitor_enabled", true) &&
                !prefs.getBoolean("emergency_killed", false)) {
            try {
                Intent restart = new Intent(this, ConnectionMonitorService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart);
                else startService(restart);
            } catch (RuntimeException ignored) {}
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

}
