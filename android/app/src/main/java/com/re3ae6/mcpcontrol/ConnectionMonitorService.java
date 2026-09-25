package com.re3ae6.mcpcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

public class ConnectionMonitorService extends Service {
    private static final String CHANNEL_ID = "mcp_connection_monitor";
    private static final int NOTIFICATION_ID = 4201;
    private static final long INTERVAL_MS = 10000L;
    private static final long RESULT_WAIT_MS = 1600L;

    private final Handler handler = new Handler();
    private PowerManager.WakeLock wakeLock;
    private boolean checking = false;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!checking) checkConnection();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = buildNotification("Monitoring MCP connection");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
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
                    if (result.has("connected")) {
                        boolean connected = result.optBoolean("connected");
                        prefs.edit()
                                .putBoolean("monitor_connected", connected)
                                .putLong("monitor_received_at", receivedAt)
                                .putString("monitor_status_json", result.toString())
                                .putString("monitor_state", "ok")
                                .apply();
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            checking = false;
        }
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            createChannel();
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MCP Control")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
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

    @Override public void onTimeout(int startId) {
        if (Build.VERSION.SDK_INT >= 35) {
            stopSelf();
        }
    }
}
