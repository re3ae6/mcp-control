package com.re3ae6.mcpcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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

    private static final int GREEN = 0xFF4CAF50;
    private static final int RED = 0xFFF44336;

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
            String out = getSharedPreferences("bridge", MODE_PRIVATE)
                    .getString("monitor_status_json", "");
            if (out == null || out.isEmpty()) return;
            applyStatusJson(new JSONObject(out), false);
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

    private void applyStatusJson(JSONObject result, boolean notify) {
        mcpReady = isMcpReady(result);
        proxyReady = isProxyReady(result);
        tunnelReady = isTunnelReady(result);
        boolean connected = mcpReady && proxyReady && tunnelReady;
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("monitor_connected", connected)
                .putLong("monitor_received_at", System.currentTimeMillis())
                .putString("monitor_status_json", result.toString())
                .putString("monitor_state", "ok")
                .putString("monitor_last_event",
                        "MCP " + (mcpReady ? "OK" : "DOWN")
                                + " / Proxy " + (proxyReady ? "OK" : "DOWN")
                                + " / Tunnel " + (tunnelReady ? "LIVE" : "DOWN"))
                .putLong("monitor_last_event_at", System.currentTimeMillis())
                .apply();
        if (notify) updateNotification();
    }

    private void recordMonitorEvent(String event) {
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("monitor_last_event", event == null ? "" : event)
                .putLong("monitor_last_event_at", System.currentTimeMillis())
                .apply();
    }

    private void checkConnection() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("bridge", MODE_PRIVATE);

        if (prefs.getBoolean("activity_visible", false)) return;

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
            android.content.SharedPreferences prefs =
                    getSharedPreferences("bridge", MODE_PRIVATE);
            long receivedAt = prefs.getLong("received_at_status", 0L);
            String state = prefs.getString("callback_state_status", "");
            String out = prefs.getString("stdout_status", "");

            if (receivedAt >= sentAt && "received".equals(state)) {
                try {
                    JSONObject result = new JSONObject(out);
                    if (result.has("mcp") || result.has("proxy") || result.has("tunnel") || result.has("connected")) {
                        boolean oldMcpReady = mcpReady;
                        boolean oldProxyReady = proxyReady;
                        boolean oldTunnelReady = tunnelReady;
                        applyStatusJson(result, false);
                        boolean indicatorChanged = oldMcpReady != mcpReady
                                || oldProxyReady != proxyReady
                                || oldTunnelReady != tunnelReady;
                        if (indicatorChanged) updateNotification();
                    } else {
                        recordMonitorEvent("Invalid status response");
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            checking = false;
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) createChannel();

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 4202, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
                .setContentIntent(contentIntent)
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
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("close_from_notification", true);
        return PendingIntent.getActivity(this, 4205, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent i = new Intent(this, ConnectionMonitorService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void setMonitorLightColors(RemoteViews views) {
        views.setTextColor(R.id.notification_mcp_light, mcpReady ? GREEN : RED);
        views.setTextColor(R.id.notification_proxy_light, proxyReady ? GREEN : RED);
        views.setTextColor(R.id.notification_tunnel_light, tunnelReady ? GREEN : RED);
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
                        applyStatusJson(new JSONObject(out), true);
                        recordMonitorEvent("Notification synced from app");
                    }
                } catch (Exception ignored) {
                    recordMonitorEvent("Notification sync received invalid status");
                }
                return START_STICKY;
            }

            if (ACTION_NOOP.equals(action)) {
                return START_STICKY;
            }

            if (ACTION_EXIT.equals(action)) {
                checking = false;
                handler.removeCallbacks(loop);
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
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
