package com.re3ae6.mcpcontrol;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private LinearLayout content, indicatorRow, tabBar;
    private ScrollView scrollView;
    private TextView status, statusAge;
    private Button masterLockButton;
    private LinearLayout logHost;
    private JSONObject policy;
    private JSONArray pendingApprovals = new JSONArray();
    private String group = "overview";
    private boolean busy = false;
    private boolean connectionOk = false;
    private static final int STORAGE_FOLDER_REQUEST = 5101;
    private android.widget.EditText storagePathField;
    private String storageSelectedPath = "";
    private TextView storageResult;
    private String storageResultMessage = "";
    private int storageResultColor = MUTED;
    private boolean connectionFresh = false;
    private JSONObject lastConnectionStatus;
    private long connectionSnapshotAt = 0L;
    private final Runnable uiMonitorTicker = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && !isDestroyed()) {
                applyMonitorSnapshot();
                handler.postDelayed(this, 1000L);
            }
        }
    };

    private static final int BG = 0xfff7f6f2;
    private static final int CARD = 0xffffffff;
    private static final int CARD_SOFT = 0xfff1f0ec;
    private static final int BORDER = 0xffe5e2db;
    private static final int TEXT = 0xff171918;
    private static final int MUTED = 0xff777a76;
    private static final int GREEN = 0xff16835b;
    private static final int RED = 0xffc84b4b;
    private static final int YELLOW = 0xffa87308;

    private final String[] groups = {"overview","files","git","terminal","network","mcp","device","dangerous"};
    private final String[] labels = {"Overview","Files","Git","Terminal","Network","MCP","Device","Dangerous"};
    private static final int RUN_COMMAND_PERMISSION_REQUEST = 4101;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 4102;
    private static final long CONNECTION_FRESH_MS = 20000L;

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(), 10, MUTED);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLetterSpacing(.08f);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(2), dp(12), dp(2));
        b.setIncludeFontPadding(false);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable bg(int color, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radius));
        return g;
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        if (isCloseFromNotification(getIntent())) {
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("monitor_enabled", false)
                    .putBoolean("activity_visible", false).apply();
            try { stopService(new Intent(this, ConnectionMonitorService.class)); } catch (RuntimeException ignored) {}
            finishAndRemoveTask();
            return;
        }

        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("monitor_enabled", true)
                .putBoolean("activity_visible", true).apply();

        storageSelectedPath = getSharedPreferences("bridge", MODE_PRIVATE).getString("storage_selected_path", "");
        loadCachedPolicy();
        buildUi();
        render();
        ensureNotificationPermission();
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED) {
            startConnectionMonitor();
        } else {
            ensureTermuxRunCommandPermission();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isCloseFromNotification(intent)) {
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("monitor_enabled", false)
                    .putBoolean("activity_visible", false).apply();
            try { stopService(new Intent(this, ConnectionMonitorService.class)); } catch (RuntimeException ignored) {}
            finishAndRemoveTask();
        }
    }

    private boolean isCloseFromNotification(Intent intent) {
        return intent != null && intent.getBooleanExtra("close_from_notification", false);
    }



    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override protected void onResume() {
        super.onResume();
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("activity_visible", true).apply();
        applyMonitorSnapshot();
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED) {
            startConnectionMonitor();
        }
        loadCachedPolicy();
        render();
        handler.removeCallbacks(uiMonitorTicker);
        handler.post(uiMonitorTicker);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(uiMonitorTicker);
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("activity_visible", false).apply();
        super.onPause();
    }

    private void startConnectionMonitor() {
        android.content.SharedPreferences bridge = getSharedPreferences("bridge", MODE_PRIVATE);
        if (bridge.getBoolean("emergency_killed", false)) return;
        if (!bridge.getBoolean("monitor_enabled", true)) return;
        try {
            Intent i = new Intent(this, ConnectionMonitorService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException ignored) {}
    }

    private void applyMonitorSnapshot() {
        android.content.SharedPreferences prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        long receivedAt = prefs.getLong("monitor_received_at", 0L);
        String json = prefs.getString("monitor_status_json", "");
        if (receivedAt <= 0L || json == null || json.isEmpty()) {
            connectionFresh = false;
            showConnectionHeader();
            return;
        }
        try {
            applyConnectionSnapshot(new JSONObject(json), receivedAt, "monitor");
        } catch (Exception ignored) {
            connectionFresh = false;
            showConnectionHeader();
        }
    }

    private boolean applyConnectionSnapshot(JSONObject o, long receivedAt, String source) {
        if (o == null || !(o.has("mcp") || o.has("proxy") || o.has("tunnel") || o.has("connected"))) {
            return connectionOk;
        }
        if (receivedAt <= 0L) receivedAt = System.currentTimeMillis();
        if (connectionSnapshotAt > receivedAt) return connectionOk;

        boolean m = isMcpReady(o);
        boolean p = isProxyReady(o);
        boolean t = isTunnelReady(o);
        connectionOk = m && p && t;
        connectionFresh = System.currentTimeMillis() - receivedAt <= CONNECTION_FRESH_MS;
        lastConnectionStatus = o;
        connectionSnapshotAt = receivedAt;

        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("monitor_connected", connectionOk)
                .putBoolean("monitor_mcp", m)
                .putBoolean("monitor_proxy", p)
                .putBoolean("monitor_tunnel", t)
                .putBoolean("monitor_fresh", connectionFresh)
                .putLong("monitor_received_at", receivedAt)
                .putString("monitor_status_json", o.toString())
                .putString("monitor_state", "connection_snapshot_" + source)
                .putString("monitor_last_event",
                        "Connection state: MCP " + (m ? "OK" : "DOWN")
                                + " / Proxy " + (p ? "OK" : "DOWN")
                                + " / Tunnel " + (t ? "LIVE" : "DOWN"))
                .putLong("monitor_last_event_at", System.currentTimeMillis())
                .apply();

        showConnectionHeader();
        return connectionOk;
    }

    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }



    private void ensureTermuxRunCommandPermission() {
        // RUN_COMMAND is a Termux-managed Additional Permission. Android cannot
        // grant it silently; on first launch we explicitly guide the user to it.
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED) return;
        if (getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("termux_permission_prompted", false)) return;
        handler.postDelayed(this::promptTermuxPermission, 900L);
    }

    private void promptTermuxPermission() {
        if (isFinishing() || checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Termux permission required")
                .setMessage("MCP Control needs “Run commands in Termux environment” for the bridge. Open App Info → Permissions → Additional permissions and allow it.")
                .setCancelable(false)
                .setNegativeButton("Later", (d, w) ->
                        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                                .putBoolean("termux_permission_prompted", true).apply())
                .setPositiveButton("Open permissions", (d, w) -> {
                    getSharedPreferences("bridge", MODE_PRIVATE).edit()
                            .putBoolean("termux_permission_prompted", true).apply();
                    openTermuxPermissionSettings();
                })
                .show();
    }

    private void openTermuxPermissionSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (RuntimeException ignored) {}
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RUN_COMMAND_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startConnectionMonitor();
                refresh();
            } else {
                addLogBox("Termux permission required. Open MCP Control → Permissions → Additional permissions → Run commands in Termux environment.");
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            handler.postDelayed(this::ensureTermuxRunCommandPermission, 300L);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(8), dp(18), dp(7));
        header.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            v.setPadding(dp(18), dp(6) + top, dp(18), dp(7));
            return insets;
        });

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("MCP Control", 21, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(title, new LinearLayout.LayoutParams(0, dp(31), 1));

        TextView signature = text("re3a • v" + BuildConfig.VERSION_NAME + " / #" + BuildConfig.VERSION_CODE, 8, MUTED);
        signature.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        brand.addView(signature, new LinearLayout.LayoutParams(dp(92), dp(31)));
        header.addView(brand);

        LinearLayout state = new LinearLayout(this);
        state.setGravity(Gravity.CENTER_VERTICAL);
        status = text("●  Checking", 12, YELLOW);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state.addView(status, new LinearLayout.LayoutParams(0, dp(23), 1));
        statusAge = text("no snapshot", 9, MUTED);
        statusAge.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        state.addView(statusAge, new LinearLayout.LayoutParams(dp(88), dp(23)));
        header.addView(state);

        indicatorRow = new LinearLayout(this);
        indicatorRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(indicatorRow, new LinearLayout.LayoutParams(-1, dp(22)));
        setInitialIndicators();

        LinearLayout actions = new LinearLayout(this);
        Button connect = button("Connect / Refresh", v -> refresh());
        connect.setBackground(bg(TEXT, TEXT, 18));
        connect.setTextColor(Color.WHITE);
        actions.addView(connect, new LinearLayout.LayoutParams(0, dp(38), 1));

        masterLockButton = button("Lock", v -> toggleMasterLock());
        masterLockButton.setSingleLine(true);
        masterLockButton.setHorizontallyScrolling(true);
        masterLockButton.setEllipsize(null);
        masterLockButton.setPadding(dp(8), dp(2), dp(8), dp(2));
        masterLockButton.setBackground(bg(CARD, BORDER, 18));
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(88), dp(38));
        lockLp.setMargins(dp(7), 0, 0, 0);
        actions.addView(masterLockButton, lockLp);
        header.addView(actions);
        root.addView(header);

        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        tabs.setBackgroundColor(CARD);
        tabBar = new LinearLayout(this);
        tabBar.setPadding(dp(12), dp(4), dp(12), dp(4));
        for (int i = 0; i < groups.length; i++) {
            final String g = groups[i];
            Button b = button(labels[i], v -> { group = g; render(); });
            b.setTextSize(10);
            b.setSingleLine(true);
            b.setPadding(dp(7), 0, dp(7), 0);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(78), dp(32));
            if (i > 0) tp.setMargins(dp(3), 0, 0, 0);
            tabBar.addView(b, tp);
        }
        tabs.addView(tabBar);
        root.addView(tabs);

        scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(8), dp(14), dp(20));
        scrollView.addView(content);
        scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            content.setPadding(dp(14), dp(8), dp(14), dp(20) + bottom);
            return insets;
        });
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        highlightTab();
    }

    private void setInitialIndicators() {
        indicatorRow.removeAllViews();
        addIndicator("MCP", false, false);
        addIndicator("Proxy", false, false);
        addIndicator("Tunnel", false, false);
    }

    private void addIndicator(String name, boolean ok) {
        addIndicator(name, ok, true);
    }

    private void addIndicator(String name, boolean ok, boolean fresh) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        int color = fresh ? (ok ? GREEN : RED) : YELLOW;
        String state = fresh ? (ok ? "Ready" : "Offline") : "Checking";
        TextView dot = text("●", 12, color);
        TextView n = text(" " + name + "  " + state, 10, MUTED);
        item.addView(dot, new LinearLayout.LayoutParams(dp(15), dp(22)));
        item.addView(n, new LinearLayout.LayoutParams(0, dp(22), 1));
        indicatorRow.addView(item, new LinearLayout.LayoutParams(0, dp(22), 1));
    }

    private void highlightTab() {
        if (tabBar == null) return;
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View v = tabBar.getChildAt(i);
            boolean selected = groups[i].equals(group);
            v.setBackground(bg(selected ? CARD_SOFT : Color.TRANSPARENT, selected ? BORDER : 0, 20));
            if (v instanceof Button) {
                ((Button)v).setTextColor(selected ? TEXT : MUTED);
                ((Button)v).setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }

    private void addCard(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(9));
        box.setBackground(bg(CARD, BORDER, 12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(3), 0, dp(3));
        content.addView(box, p);
        TextView a = text(title, 14, TEXT);
        a.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(a);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 9, MUTED);
            sub.setPadding(0, dp(2), 0, 0);
            box.addView(sub);
        }
    }

    // Section headings are labels, not controls; keeping them out of cards reduces vertical scrolling.
    private void addSectionHeader(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(3));
        TextView h = text(title, 14, TEXT);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(h, new LinearLayout.LayoutParams(0, dp(24), 1));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 9, MUTED);
            sub.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(sub, new LinearLayout.LayoutParams(dp(160), dp(24)));
        }
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addMasterBanner(boolean locked) {
        // Master Lock is intentionally represented only by the single header control.
    }

    private View metric(String name, String value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(8), dp(6), dp(8), dp(6));
        box.setBackground(bg(CARD, BORDER, 11));
        TextView n = text(name, 9, MUTED);
        n.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(n, new LinearLayout.LayoutParams(0, dp(28), 1));
        TextView v = text(value, 12, color);
        v.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(v, new LinearLayout.LayoutParams(dp(62), dp(28)));
        return box;
    }

    private void addBridgeConnectionCard(boolean m, boolean p, boolean t) {
        addConnectionSummary();
    }

    private void renderOffline() {
        render();
    }

    private void render() {
        if (storagePathField != null) { String currentPath = storagePathField.getText().toString().trim(); if (!currentPath.isEmpty()) storageSelectedPath = currentPath; }
        showConnectionHeader();
        content.removeAllViews();
        logHost = null;
        highlightTab();

        boolean locked = policy != null && policy.optBoolean("master_lock", true);

        if ("overview".equals(group)) {
            addConnectionSummary();

            LinearLayout policyBox = new LinearLayout(this);
            policyBox.setOrientation(LinearLayout.VERTICAL);
            policyBox.setPadding(dp(10), dp(8), dp(10), dp(9));
            policyBox.setBackground(bg(CARD, BORDER, 13));
            LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(-1, -2);
            pb.setMargins(0, dp(5), 0, 0);
            content.addView(policyBox, pb);

            LinearLayout policyTitle = new LinearLayout(this);
            policyTitle.setGravity(Gravity.CENTER_VERTICAL);
            TextView ph = text("Policy", 13, TEXT);
            ph.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            policyTitle.addView(ph, new LinearLayout.LayoutParams(0, dp(23), 1));
            TextView ps = text(locked ? "MASTER LOCK • DENY" : "individual states", 9,
                    locked ? RED : MUTED);
            ps.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            policyTitle.addView(ps, new LinearLayout.LayoutParams(dp(130), dp(23)));
            policyBox.addView(policyTitle);

            LinearLayout counts = new LinearLayout(this);
            int[] c = countStates();
            counts.addView(metric("DENY", String.valueOf(c[0]), RED),
                    new LinearLayout.LayoutParams(0, dp(44), 1));
            LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(44), 1);
            q.setMargins(dp(5), 0, 0, 0);
            counts.addView(metric("ASK", String.valueOf(c[1]), YELLOW), q);
            q = new LinearLayout.LayoutParams(0, dp(44), 1);
            q.setMargins(dp(5), 0, 0, 0);
            counts.addView(metric("ALLOW", String.valueOf(c[2]), GREEN), q);
            policyBox.addView(counts);

            LinearLayout controlBox = new LinearLayout(this);
            controlBox.setGravity(Gravity.CENTER_VERTICAL);
            controlBox.setPadding(dp(10), dp(7), dp(10), dp(7));
            controlBox.setBackground(bg(CARD, BORDER, 13));
            LinearLayout.LayoutParams cb = new LinearLayout.LayoutParams(-1, dp(52));
            cb.setMargins(0, dp(5), 0, 0);

            Button restart = button("Restart", v -> runAction("restart", 1900));
            restart.setBackground(bg(CARD_SOFT, BORDER, 17));
            controlBox.addView(restart, new LinearLayout.LayoutParams(0, dp(38), 1));

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(38), 1);
            cp.setMargins(dp(5), 0, 0, 0);
            Button approvals = button("Approvals", v -> loadApprovals());
            approvals.setBackground(bg(CARD_SOFT, BORDER, 17));
            controlBox.addView(approvals, cp);

            cp = new LinearLayout.LayoutParams(0, dp(38), 1);
            cp.setMargins(dp(5), 0, 0, 0);
            Button audit = button("Audit", v -> loadAudit());
            audit.setBackground(bg(CARD_SOFT, BORDER, 17));
            controlBox.addView(audit, cp);
            content.addView(controlBox, cb);

            if (pendingApprovals.length() > 0) addPendingApprovalsCard();
            addMonitorDiagnosticsCard();
            return;
        }

        if ("files".equals(group)) {
            addSelectedFolderCard(locked);
            addPathScopesCard(locked);
            return;
        }

        JSONObject caps = policy == null ? null : policy.optJSONObject(group);
        JSONArray a = caps == null ? null : caps.optJSONArray(group);
        int n = a == null ? 0 : a.length();

        addSectionHeader(labels[indexOf(group)],
                locked ? (n + " permissions • MASTER LOCK") : (n + " permissions"));
        if (n == 0) {
            content.addView(text("No capabilities in this group.", 12, MUTED));
            return;
        }

        for (int i = 0; i < n; i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            addCapabilityRow(x, locked);
        }
    }

    private int[] countStates() {
        int[] c = {0,0,0};
        if (policy == null) return c;
        JSONObject caps = policy.optJSONObject("capabilities");
        if (caps == null) return c;
        for (String g : groups) {
            if ("overview".equals(g)) continue;
            JSONArray a = caps.optJSONArray(g);
            if (a == null) continue;
            for (int i=0;i<a.length();i++) {
                JSONObject x=a.optJSONObject(i);
                if(x==null) continue;
                String s=x.optString("state","deny");
                if("ask".equals(s)) c[1]++; else if("allow".equals(s)) c[2]++; else c[0]++;
            }
        }
        if(policy.optBoolean("master_lock",true)) {
            c[0]+=c[1]+c[2]; c[1]=0; c[2]=0;
        }
        return c;
    }

    private void addCapabilityRow(JSONObject x, boolean locked) {
        String id=x.optString("id");
        String name=x.optString("label","");
        String description=x.optString("description","");
        String state=x.optString("state","deny");
        if(name.isEmpty()) name=description.isEmpty()?id:description;

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10),dp(8),dp(10),dp(8));
        box.setBackground(bg(CARD,BORDER,12));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);
        bp.setMargins(0,dp(3),0,dp(3));
        content.addView(box,bp);

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot=text("●",10,locked?RED:stateColor(state));
        top.addView(dot,new LinearLayout.LayoutParams(dp(15),dp(21)));
        TextView title=text(name,12,TEXT);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        top.addView(title,new LinearLayout.LayoutParams(0,dp(21),1));
        TextView st=text(locked?"DENY":state.toUpperCase(),8,locked?RED:stateColor(state));
        st.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        st.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        top.addView(st,new LinearLayout.LayoutParams(dp(50),dp(21)));
        box.addView(top);

        if(!description.isEmpty()&&!description.equals(name)){
            TextView d=text(description,9,MUTED);
            d.setPadding(dp(15),0,0,dp(1));
            box.addView(d);
        }

        TextView idText=text(id,8,0xff999b98);
        idText.setPadding(dp(15),0,0,dp(4));
        box.addView(idText);

        LinearLayout choices=new LinearLayout(this);
        addStateButton(choices,"Deny","deny",id,state,locked);
        addStateButton(choices,"Ask","ask",id,state,locked);
        addStateButton(choices,"Allow","allow",id,state,locked);
        box.addView(choices);
    }

    private int stateColor(String s) {
        if ("allow".equals(s)) return GREEN;
        if ("ask".equals(s)) return YELLOW;
        return RED;
    }
    private boolean isFileOperationCapability(String id) {
        return "files.list".equals(id) || "files.read".equals(id) || "files.search".equals(id)
                || "files.context".equals(id) || "files.history".equals(id)
                || "files.changes".equals(id) || "files.write".equals(id)
                || "files.mkdir".equals(id);
    }

    private JSONObject findCapability(String id) {
        if (policy == null) return null;
        JSONObject caps = policy.optJSONObject("capabilities");
        if (caps == null) return null;
        for (String g : groups) {
            JSONArray a = caps.optJSONArray(g);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x != null && id.equals(x.optString("id", ""))) return x;
            }
        }
        return null;
    }

    private String fileOperationLabel(String id) {
        if ("files.read".equals(id)) return "Read files";
        if ("files.write".equals(id)) return "Write files";
        if ("files.list".equals(id)) return "List files";
        if ("files.search".equals(id)) return "Search files";
        if ("files.mkdir".equals(id)) return "Create folders";
        if ("files.context".equals(id)) return "Read context";
        if ("files.history".equals(id)) return "File history";
        if ("files.changes".equals(id)) return "List changes";
        return id;
    }

    private void addScopedFileCapabilityRow(LinearLayout host, String id, String name,
                                             String scopeText, boolean locked) {
        JSONObject x = findCapability(id);
        if (x == null) return;
        String state = x.optString("state", "deny");

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(name, 11, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        nameBox.addView(title, new LinearLayout.LayoutParams(-1, dp(20)));
        TextView scope = text(scopeText, 8, MUTED);
        scope.setSingleLine(false);
        scope.setMaxLines(6);
        nameBox.addView(scope, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.setMargins(0, 0, dp(6), 0);
        row.addView(nameBox, np);

        LinearLayout choices = new LinearLayout(this);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        addStateButton(choices, "Deny", "deny", id, state, locked);
        addStateButton(choices, "Ask", "ask", id, state, locked);
        addStateButton(choices, "Allow", "allow", id, state, locked);
        row.addView(choices, new LinearLayout.LayoutParams(dp(174), dp(38)));
        host.addView(row);
    }

    private void addSelectedFolderCard(boolean locked) {
        addSectionHeader("Selected Folder", locked
                ? "MASTER LOCK"
                : "Only these folders are available to file operations");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(9));
        box.setBackground(bg(CARD, BORDER, 12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(3), 0, dp(6));
        content.addView(box, bp);

        TextView hint = text(
                "Add a specific phone folder. Adding a folder enables Read / Write / List / Search only inside that folder.",
                9, MUTED);
        hint.setPadding(dp(2), 0, dp(2), dp(6));
        box.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.EditText path = new android.widget.EditText(this);
        if (!storageSelectedPath.isEmpty()) path.setText(storageSelectedPath);
        storagePathField = path;
        path.setSingleLine(true);
        path.setTextSize(12);
        path.setHint("/storage/emulated/0/Chatgpt");
        path.setPadding(dp(10), 0, dp(10), 0);
        path.setBackground(bg(CARD_SOFT, BORDER, 10));
        row.addView(path, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button browse = button("📂", v -> openStorageFolderPicker());
        browse.setEnabled(!locked && !busy);
        browse.setBackground(bg(CARD_SOFT, BORDER, 17));
        browse.setTextColor(TEXT);
        LinearLayout.LayoutParams br = new LinearLayout.LayoutParams(dp(64), dp(42));
        br.setMargins(dp(6), 0, 0, 0);
        row.addView(browse, br);

        Button add = button("Add", v -> {
            String p = path.getText().toString().trim();
            if (p.isEmpty()) {
                setStorageResult("Choose a folder or enter its absolute phone path.", RED);
                return;
            }
            if (!(p.startsWith("/storage/emulated/0/") || p.startsWith("/sdcard/"))) {
                setStorageResult("Storage path must be inside /storage/emulated/0 or /sdcard.", RED);
                return;
            }
            runCustomPath("add_path", p);
        });
        add.setEnabled(!locked && !busy);
        add.setBackground(bg(TEXT, TEXT, 17));
        add.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(64), dp(42));
        ap.setMargins(dp(6), 0, 0, 0);
        row.addView(add, ap);
        box.addView(row);

        storageResult = text(storageResultMessage, 10, storageResultColor);
        storageResult.setPadding(dp(2), dp(6), dp(2), dp(2));
        storageResult.setVisibility(storageResultMessage.isEmpty() ? View.GONE : View.VISIBLE);
        box.addView(storageResult);

        JSONArray paths = policy == null ? null : policy.optJSONArray("custom_paths");
        if (paths == null || paths.length() == 0) {
            TextView empty = text("No folder selected.", 10, MUTED);
            empty.setPadding(dp(2), dp(7), dp(2), dp(6));
            box.addView(empty);
        } else {
            TextView selected = text("Selected folders", 9, MUTED);
            selected.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            selected.setPadding(dp(2), dp(7), dp(2), dp(2));
            box.addView(selected);
            for (int i = 0; i < paths.length(); i++) {
                final String p = paths.optString(i, "");
                if (p.isEmpty()) continue;
                LinearLayout pr = new LinearLayout(this);
                pr.setGravity(Gravity.CENTER_VERTICAL);
                TextView pt = text("🟢  " + p, 10, TEXT);
                pt.setSingleLine(false);
                pr.addView(pt, new LinearLayout.LayoutParams(0, -2, 1));
                Button rm = button("Delete", v -> runCustomPath("remove_path", p));
                rm.setEnabled(!locked && !busy);
                rm.setTextSize(10);
                rm.setTextColor(RED);
                rm.setBackgroundColor(Color.TRANSPARENT);
                rm.setGravity(Gravity.CENTER);
                rm.setPadding(0, 0, 0, 0);
                rm.setMinWidth(0);
                rm.setMinimumWidth(0);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(48), dp(30));
                rp.setMargins(dp(6), 0, 0, 0);
                pr.addView(rm, rp);
                box.addView(pr);
            }
        }

        TextView operationsHeader = text("File Operations", 12, TEXT);
        operationsHeader.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        operationsHeader.setPadding(dp(2), dp(10), dp(2), dp(1));
        box.addView(operationsHeader);
        TextView operationsScope = text(
                paths != null && paths.length() > 0
                        ? "Each row shows its exact selected-folder scope."
                        : "No selected-folder scope.",
                8, MUTED);
        operationsScope.setPadding(dp(2), 0, dp(2), dp(5));
        box.addView(operationsScope);

        String[] ids = {
                "files.read", "files.write", "files.list", "files.search",
                "files.mkdir", "files.context", "files.history", "files.changes"
        };
        for (String id : ids) {
            String scope = selectedFolderScopeText(paths);
            addScopedFileCapabilityRow(box, id, fileOperationLabel(id), scope, locked);
        }
    }

    private String selectedFolderScopeText(JSONArray paths) {
        if (paths == null || paths.length() == 0) return "Scope: no selected folder";
        StringBuilder b = new StringBuilder("Scope: ");
        for (int i = 0; i < paths.length(); i++) {
            String p = paths.optString(i, "");
            if (p.isEmpty()) continue;
            if (b.length() > 8) b.append("\n");
            b.append(p);
        }
        return b.toString();
    }

    private void addPathScopesCard(boolean locked) {
        addSectionHeader("Path Scopes", locked ? "MASTER LOCK" : "Explicit path boundaries");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(7), dp(10), dp(8));
        box.setBackground(bg(CARD, BORDER, 12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(3), 0, dp(5));
        content.addView(box, bp);

        JSONObject caps = policy == null ? null : policy.optJSONObject("capabilities");
        JSONArray a = caps == null ? null : caps.optJSONArray("files");
        boolean any = false;
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                String id = x.optString("id", "");
                if (isFileOperationCapability(id) || "files.custom".equals(id)) continue;
                any = true;
                String name = x.optString("label", "");
                if (name.isEmpty()) name = id;
                addScopedFileCapabilityRow(box, id, name, "Path scope: " + name, locked);
            }
        }
        if (!any) box.addView(text("No additional path scopes.", 10, MUTED));
    }

    private void addStateButton(LinearLayout row,String label,String target,String id,String current,boolean locked) {
        boolean selected=target.equals(current);
        Button b=button((selected?"✓  ":"")+label,v->runSet(id,target));
        b.setEnabled(!locked&&!busy);
        b.setTextSize(10);
        b.setTextColor(selected?TEXT:MUTED);
        b.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);
        b.setBackground(bg(selected?CARD_SOFT:0xfffaf9f6,BORDER,17));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(33),1);
        if(row.getChildCount()>0)p.setMargins(dp(4),0,0,0);
        row.addView(b,p);
    }

    private void addCustomStorageCard(boolean locked) {
        addSectionHeader("Storage Access", locked ? "MASTER LOCK" : "User-approved paths");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(9));
        box.setBackground(bg(CARD, BORDER, 12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(3), 0, dp(5));
        content.addView(box, bp);

        TextView hint = text("Add a specific phone folder. Access stays denied unless explicitly allowed.", 9, MUTED);
        box.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.EditText path = new android.widget.EditText(this);
        if (!storageSelectedPath.isEmpty()) path.setText(storageSelectedPath);
        storagePathField = path;
        path.setSingleLine(true);
        path.setTextSize(12);
        path.setHint("/storage/emulated/0/Chatgpt");
        path.setPadding(dp(10), 0, dp(10), 0);
        path.setBackground(bg(CARD_SOFT, BORDER, 10));
        row.addView(path, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button browse = button("📂", v -> openStorageFolderPicker());
        browse.setEnabled(!locked && !busy);
        browse.setBackground(bg(CARD_SOFT, BORDER, 17));
        browse.setTextColor(TEXT);
        LinearLayout.LayoutParams br = new LinearLayout.LayoutParams(dp(72), dp(42));
        br.setMargins(dp(6), 0, 0, 0);
        row.addView(browse, br);

        Button add = button("Add", v -> {
            String p = path.getText().toString().trim();
            if (p.isEmpty()) {
                setStorageResult("Choose a folder or enter its absolute phone path.", RED);
                return;
            }
            if (!(p.startsWith("/storage/emulated/0/") || p.equals("/storage/emulated/0")
                    || p.startsWith("/sdcard/") || p.equals("/sdcard"))) {
                setStorageResult("Storage path must be under /storage/emulated/0 or /sdcard.", RED);
                return;
            }
            runCustomPath("add_path", p);
        });
        add.setEnabled(!locked && !busy);
        add.setBackground(bg(TEXT, TEXT, 17));
        add.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(64), dp(42));
        ap.setMargins(dp(6), 0, 0, 0);
        row.addView(add, ap);
        box.addView(row);

        storageResult = text(storageResultMessage, 10, storageResultColor);
        storageResult.setPadding(dp(2), dp(6), dp(2), dp(2));
        storageResult.setVisibility(storageResultMessage.isEmpty() ? View.GONE : View.VISIBLE);
        box.addView(storageResult);

        JSONArray paths = policy == null ? null : policy.optJSONArray("custom_paths");
        if (paths != null) for (int i=0; i<paths.length(); i++) {
            final String p = paths.optString(i, "");
            if (p.isEmpty()) continue;
            LinearLayout pr = new LinearLayout(this);
            pr.setGravity(Gravity.CENTER_VERTICAL);
            TextView pt = text("🟢  " + p, 10, TEXT);
            pr.addView(pt, new LinearLayout.LayoutParams(0, dp(30), 1));
            Button rm = button("Delete", v -> runCustomPath("remove_path", p));
            rm.setEnabled(!locked && !busy);
            rm.setTextSize(10);
            rm.setTextColor(RED);
            rm.setBackgroundColor(Color.TRANSPARENT);
            rm.setGravity(Gravity.CENTER);
            rm.setPadding(0, 0, 0, 0);
            rm.setMinWidth(0);
            rm.setMinimumWidth(0);
            pr.addView(rm, new LinearLayout.LayoutParams(dp(48), dp(30)));
            box.addView(pr);
        }

        JSONObject customScope = findCapability("files.custom");
        if (customScope != null) {
            addScopedFileCapabilityRow(box, "files.custom", "Selected Folder Access",
                    (paths != null && paths.length() > 0)
                            ? "Applies only to the folders listed above"
                            : "Add a folder above to create the scope",
                    locked);
        }
    }

    private void openStorageFolderPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, STORAGE_FOLDER_REQUEST);
        } catch (RuntimeException e) {
            addLogBox("Browse: " + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != STORAGE_FOLDER_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
            int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (takeFlags != 0) getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (RuntimeException ignored) {}
        String selectedPath = uriToStoragePath(uri);
        if (selectedPath != null) {
            storageSelectedPath = selectedPath;
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("storage_selected_path", selectedPath).apply();
            if (storagePathField != null) storagePathField.setText(selectedPath);
        } else {
            setStorageResult("Selected folder could not be mapped to an absolute phone path.", RED);
        }

    }
    private String uriToStoragePath(android.net.Uri uri) {
        if (uri == null) return null;
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(uri);
            if (docId != null) {
                int colon = docId.indexOf(':');
                if (colon > 0) {
                    String volume = docId.substring(0, colon);
                    String relative = docId.substring(colon + 1);
                    if ("primary".equalsIgnoreCase(volume)) {
                        return relative.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + relative;
                    }
                    return relative.isEmpty() ? "/storage/" + volume : "/storage/" + volume + "/" + relative;
                }
            }
        }
        return null;
    }

    private void setStorageResult(String message, int color) {
        storageResultMessage = message == null ? "" : message;
        storageResultColor = color;
        if (storageResult != null) {
            storageResult.setText(storageResultMessage);
            storageResult.setTextColor(storageResultColor);
            storageResult.setVisibility(storageResultMessage.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private String storageFailureDetail(String command, int exit) {
        android.content.SharedPreferences bridge = getSharedPreferences("bridge", MODE_PRIVATE);
        String error = commandError(command);
        if (!error.isEmpty()) return error;
        String stderr = bridge.getString("stderr_" + command, "");
        if (stderr != null && !stderr.isEmpty()) return stderr.trim();
        String stdout = bridge.getString("stdout_" + command, "");
        if (stdout != null && !stdout.isEmpty()) return stdout.trim();
        return "Termux command failed (exit=" + exit + ").";
    }

    private void runCustomPath(String command, String path) {
        if (busy) return;
        busy = true;
        setStorageResult(("add_path".equals(command) ? "Adding " : "Removing ") + path + "…", YELLOW);
        status.setText("●  Saving…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult(command);
        try {
            if (!McpBridge.run(this, command, path)) {
                busy = false;
                setStorageResult("Could not start " + command + ". Check Termux bridge status.", RED);
                render();
                return;
            }
            final long sentAt = getSharedPreferences("bridge", MODE_PRIVATE)
                    .getLong("sent_at_" + command, System.currentTimeMillis());
            waitForCustomPathResult(command, path, sentAt, System.currentTimeMillis() + 8000L);
        } catch (RuntimeException e) {
            busy = false;
            setStorageResult("Storage path: " + e.getMessage(), RED);
            render();
        }
    }

    private void waitForCustomPathResult(String command, String path, long sentAt, long deadline) {
        handler.postDelayed(() -> {
            android.content.SharedPreferences bridge =
                    getSharedPreferences("bridge", MODE_PRIVATE);
            long receivedAt = bridge.getLong("received_at_" + command, 0L);
            String state = bridge.getString("callback_state_" + command, "unknown");

            if (receivedAt >= sentAt && "received".equals(state)) {
                int exit = bridge.getInt("exit_" + command, -1);
                if (exit != 0 || !commandError(command).isEmpty()) {
                    busy = false;
                    setStorageResult(storageFailureDetail(command, exit), RED);
                    render();
                    return;
                }
                loadPolicyAndFinish(() -> {
                    busy = false;
                    setStorageResult(("add_path".equals(command) ? "Added: " : "Removed: ") + path, GREEN);
                    render();
                });
                return;
            }

            String error = commandError(command);
            if (!error.isEmpty() && receivedAt >= sentAt) {
                busy = false;
                setStorageResult(error, RED);
                render();
                return;
            }

            if (System.currentTimeMillis() < deadline) {
                waitForCustomPathResult(command, path, sentAt, deadline);
                return;
            }

            busy = false;
            String stage = bridge.getString("callback_stage_" + command, "");
            String detail = bridge.getString("stderr_" + command, "");
            StringBuilder b = new StringBuilder("Storage path callback timeout.")
                    .append(" command=").append(command)
                    .append(" callback=").append(state)
                    .append(" stage=").append(stage.isEmpty() ? "none" : stage)
                    .append(" sent_at=").append(sentAt)
                    .append(" received_at=").append(receivedAt);
            if (detail != null && !detail.isEmpty()) b.append(" stderr=").append(detail);
            setStorageResult(b.toString(), RED);
            render();
        }, 150L);
    }

    private int indexOf(String g){for(int i=0;i<groups.length;i++)if(groups[i].equals(g))return i;return 0;}

    private boolean isMcpReady(JSONObject o) {
        return "OK".equalsIgnoreCase(o.optString("mcp")) || o.optBoolean("mcp_ok", false);
    }

    private boolean isProxyReady(JSONObject o) {
        return "OK".equalsIgnoreCase(o.optString("proxy")) || o.optBoolean("proxy_ok", false);
    }

    private boolean isTunnelReady(JSONObject o) {
        String ts = o.optString("tunnel", "").toLowerCase();
        return ts.contains("live") || ts.contains("ready") || o.optBoolean("tunnel_ok", false);
    }

    private void updateIndicators(JSONObject o) {
        indicatorRow.removeAllViews();
        addIndicator("MCP", isMcpReady(o), connectionFresh);
        addIndicator("Proxy", isProxyReady(o), connectionFresh);
        addIndicator("Tunnel", isTunnelReady(o), connectionFresh);
    }

    private void addMonitorDiagnosticsCard() {
        android.content.SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
        String event = p.getString("monitor_last_event", "No monitor event yet.");
        long at = p.getLong("monitor_last_event_at", 0L);
        String callback = p.getString("callback_state_status", "unknown");
        String stage = p.getString("callback_stage_status", "");
        long sent = p.getLong("sent_at_status", 0L);
        long received = p.getLong("received_at_status", 0L);
        String err = commandError("status");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(7), dp(10), dp(8));
        box.setBackground(bg(CARD, BORDER, 13));

        LinearLayout title = new LinearLayout(this);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = text("Diagnostics", 13, TEXT);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.addView(h, new LinearLayout.LayoutParams(0, dp(23), 1));
        TextView tap = text("details", 9, MUTED);
        tap.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.addView(tap, new LinearLayout.LayoutParams(dp(55), dp(23)));
        box.addView(title);

        String summary = event + (at > 0 ? " • " + formatAge(System.currentTimeMillis() - at) : "");
        TextView v = text(summary, 9, TEXT);
        v.setPadding(dp(10), dp(7), dp(10), dp(7));
        v.setBackground(bg(CARD_SOFT, BORDER, 11));
        v.setOnClickListener(view -> showDiagnosticsDetail(event, callback, stage, sent, received, err));
        box.addView(v);

        boolean termux = false;
        try {
            getPackageManager().getPackageInfo("com.termux", 0);
            termux = true;
        } catch (PackageManager.NameNotFoundException ignored) {}
        boolean runGranted = checkSelfPermission("com.termux.permission.RUN_COMMAND")
                == PackageManager.PERMISSION_GRANTED;
        boolean bridgeReady = termux && runGranted;
        String commit = BuildConfig.GIT_COMMIT == null || BuildConfig.GIT_COMMIT.isEmpty()
                ? "unknown" : BuildConfig.GIT_COMMIT;
        if (commit.length() > 8) commit = commit.substring(0, 8);
        String buildLine = "Build " + BuildConfig.VERSION_NAME + " / #" + BuildConfig.VERSION_CODE
                + "  •  " + commit
                + "  •  SDK " + android.os.Build.VERSION.SDK_INT
                + "  •  Termux " + (termux ? "✓" : "✕")
                + "  •  RUN_COMMAND " + (runGranted ? "✓" : "✕")
                + "  •  Bridge " + (bridgeReady ? "READY" : "BLOCKED");
        TextView build = text(buildLine, 8, MUTED);
        build.setPadding(dp(2), dp(6), dp(2), 0);
        box.addView(build);

        content.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    private void loadCachedPolicy() {
        try {
            String cached = getSharedPreferences("bridge", MODE_PRIVATE).getString("policy_json", "");
            if (cached != null && !cached.isEmpty()) {
                JSONObject o = new JSONObject(cached);
                if (o.has("master_lock") && o.has("capabilities")) {
                    policy = o;
                    return;
                }
            }
        } catch (Exception ignored) {}
        if (policy == null) {
            try {
                policy = new JSONObject()
                        .put("master_lock", getSharedPreferences("bridge", MODE_PRIVATE)
                                .getBoolean("master_lock", true))
                        .put("capabilities", new JSONObject());
            } catch (Exception ignored) {}
        }
    }

    private void showConnectionHeader() {
        if (status == null) return;

        if (!connectionFresh) {
            status.setText("●  Checking");
            status.setTextColor(YELLOW);
        } else {
            status.setText("●  " + (connectionOk ? "Connected" : "Disconnected"));
            status.setTextColor(connectionOk ? GREEN : RED);
        }

        if (statusAge != null) {
            long at = connectionSnapshotAt;
            statusAge.setText(at <= 0 ? "no snapshot" :
                    formatAge(System.currentTimeMillis() - at));
            statusAge.setTextColor(connectionFresh ? MUTED : YELLOW);
        }

        if (masterLockButton != null) {
            boolean locked = policy != null && policy.optBoolean("master_lock", true);
            masterLockButton.setText(locked ? "Unlock" : "Lock");
        }

        if (indicatorRow != null && lastConnectionStatus != null) {
            updateIndicators(lastConnectionStatus);
        }
    }

    private String formatAge(long ms) {
        if (ms < 0) ms = 0;
        long sec = ms / 1000L;
        if (sec < 60) return sec + "s ago";
        long min = sec / 60L;
        return min + "m " + (sec % 60L) + "s ago";
    }

    private void addConnectionSummary() {
        boolean have = lastConnectionStatus != null;
        boolean m = have && isMcpReady(lastConnectionStatus);
        boolean p = have && isProxyReady(lastConnectionStatus);
        boolean t = have && isTunnelReady(lastConnectionStatus);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(9));
        box.setBackground(bg(CARD, BORDER, 13));

        LinearLayout title = new LinearLayout(this);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = text("Connection", 13, TEXT);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.addView(h, new LinearLayout.LayoutParams(0, dp(23), 1));

        String freshness = connectionFresh ? "fresh" :
                (connectionSnapshotAt <= 0 ? "waiting" :
                        "last known • " + formatAge(System.currentTimeMillis() - connectionSnapshotAt));
        TextView f = text(freshness, 9, connectionFresh ? MUTED : YELLOW);
        f.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.addView(f, new LinearLayout.LayoutParams(dp(145), dp(23)));
        box.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.addView(metric("MCP", m ? "Ready" : "Offline", m ? GREEN : RED),
                new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(42), 1);
        q.setMargins(dp(5), 0, 0, 0);
        row.addView(metric("Proxy", p ? "Ready" : "Offline", p ? GREEN : RED), q);
        q = new LinearLayout.LayoutParams(0, dp(42), 1);
        q.setMargins(dp(5), 0, 0, 0);
        row.addView(metric("Tunnel", t ? "Live" : "Offline", t ? GREEN : RED), q);
        box.addView(row);

        TextView note = text("Background monitor is the single connection source of truth.", 9, MUTED);
        note.setPadding(dp(2), dp(5), dp(2), 0);
        box.addView(note);

        logHost = new LinearLayout(this);
        logHost.setOrientation(LinearLayout.VERTICAL);
        box.addView(logHost);

        content.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showDiagnosticsDetail(String event, String callback, String stage,
                                       long sent, long received, String err) {
        StringBuilder b = new StringBuilder(event)
                .append("\ncallback: ").append(callback)
                .append(stage.isEmpty() ? "" : " / " + stage)
                .append("\nsent: ").append(sent)
                .append("  received: ").append(received);
        if (connectionSnapshotAt > 0) {
            b.append("\nmonitor snapshot: ")
                    .append(formatAge(System.currentTimeMillis() - connectionSnapshotAt));
        }
        if (!err.isEmpty()) b.append("\nerror: ").append(err);
        addLogBox(b.toString());
    }

    private void toggleMasterLock() {
        boolean locked = policy != null && policy.optBoolean("master_lock", true);
        if (locked) unlockAll(); else lockAll();
    }

    private void clearOutput() {
        getSharedPreferences("bridge",MODE_PRIVATE).edit()
                .putString("stdout","")
                .putString("stderr","")
                .putInt("exit",-1)
                .apply();
    }

    private void clearCommandResult(String command) {
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("stdout_" + command, "")
                .putString("stderr_" + command, "")
                .putInt("exit_" + command, -1)
                .putInt("error_code_" + command, -1)
                .putString("error_message_" + command, "")
                .putLong("received_at_" + command, 0L)
                .apply();
    }

    private String commandError(String command) {
        android.content.SharedPreferences bridge = getSharedPreferences("bridge", MODE_PRIVATE);
        String message = bridge.getString("error_message_" + command, "");
        if (message != null && !message.isEmpty()) return message;
        int code = bridge.getInt("error_code_" + command, -1);
        return code > 0 ? "Termux error code " + code : "";
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        status.setText("●  Connecting…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult("connect");
        clearCommandResult("status");
        clearCommandResult("policy");

        if (!McpBridge.run(this, "connect")) {
            bridgeFailure("Could not start the Termux bridge. Check Termux permission: Run commands in Termux.");
            return;
        }
        pollConnection(0);
    }

    private void pollConnection(int attempt) {
        long delay = attempt == 0 ? 1200L : 2000L;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!McpBridge.run(MainActivity.this, "status")) {
                    bridgeFailure("Could not query Termux. Check Termux permission: Run commands in Termux.");
                    return;
                }
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        String statusError = commandError("status");
                        if (!statusError.isEmpty()) {
                            bridgeFailure(statusError);
                            return;
                        }
                        boolean connected = applyStatusResult();
                        if (connected) {
                            loadPolicyAndFinish();
                            return;
                        }
                        android.content.SharedPreferences bridge = getSharedPreferences("bridge", MODE_PRIVATE);
                        int connectExit = bridge.getInt("exit_connect", -1);
                        int connectErrorCode = bridge.getInt("error_code_connect", -1);
                        long connectAt = bridge.getLong("received_at_connect", 0L);
                        boolean connectFailed = (connectExit > 0 || connectErrorCode > 0) && connectAt > 0 &&
                                System.currentTimeMillis() - connectAt < 5000L;
                        if (connectFailed || attempt >= 3) {
                            String err = commandError("connect");
                            if (err.isEmpty()) err = bridge.getString("stderr_connect", "");
                            if (err.isEmpty()) err = bridge.getString("stdout_connect", "");
                            busy = false;
                            render();
                            if (err != null && !err.isEmpty()) {
                                addLogBox("Connect: " + err);
                            } else {
                                long connectReceivedAt = bridge.getLong("received_at_connect", 0L);
                                long statusAt = bridge.getLong("received_at_status", 0L);
                                long connectSentAt = bridge.getLong("sent_at_connect", 0L);
                                long statusSentAt = bridge.getLong("sent_at_status", 0L);
                                int statusExit = bridge.getInt("exit_status", -1);
                                int statusErrorDetail = bridge.getInt("error_code_status", -1);
                                String statusErr = bridge.getString("error_message_status", "");
                                String connectState = bridge.getString("callback_state_connect", "unknown");
                                String statusState = bridge.getString("callback_state_status", "unknown");
                                String connectStage = bridge.getString("callback_stage_connect", "");
                                String statusStage = bridge.getString("callback_stage_status", "");
                                String connectErr = bridge.getString("error_message_connect", "");
                                String connectStderr = bridge.getString("stderr_connect", "");
                                String statusStderr = bridge.getString("stderr_status", "");
                                String detail = statusErr == null || statusErr.isEmpty() ? "No result detail returned." : statusErr;
                                StringBuilder diag = new StringBuilder("Connect did not become ready.\\n")
                                        .append("DISPATCH connect: ").append(connectSentAt > 0 ? "sent" : "not sent")
                                        .append(" | callback: ").append(connectState)
                                        .append(" | stage: ").append(connectStage.isEmpty() ? "none" : connectStage).append("\\n")
                                        .append("DISPATCH status: ").append(statusSentAt > 0 ? "sent" : "not sent")
                                        .append(" | callback: ").append(statusState)
                                        .append(" | stage: ").append(statusStage.isEmpty() ? "none" : statusStage).append("\\n")
                                        .append("RESULT status: exit=").append(statusExit)
                                        .append(" error=").append(statusErrorDetail).append("\\n");
                                if (connectErr != null && !connectErr.isEmpty()) diag.append("CONNECT ERROR: ").append(connectErr).append("\\n");
                                if (connectStderr != null && !connectStderr.isEmpty()) diag.append("CONNECT STDERR: ").append(connectStderr).append("\\n");
                                if (statusStderr != null && !statusStderr.isEmpty()) diag.append("STATUS STDERR: ").append(statusStderr).append("\\n");
                                diag.append(detail);
                                addLogBox(diag.toString());
                            }
                            return;
                        }
                        pollConnection(attempt + 1);
                    }
                }, 550L);
            }
        }, delay);
    }

    private boolean applyStatusResult() {
        android.content.SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
        String out = p.getString("stdout_status", "");
        String err = p.getString("stderr_status", "");
        int exit = p.getInt("exit_status", -1);
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("mcp") || o.has("proxy") || o.has("tunnel") || o.has("connected")) {
                long receivedAt = p.getLong("received_at_status", System.currentTimeMillis());
                boolean ok = applyConnectionSnapshot(o, receivedAt, "activity_status");
                requestNotificationStatusSync(o);
                return ok;
            }
        } catch (Exception ignored) {}

        // A missing/late callback is not a disconnect. Preserve the last known good state.
        if (exit == -1 && (err == null || err.isEmpty())) return connectionOk;
        return connectionOk;
    }

    private void requestNotificationStatusSync(JSONObject o) {
        try {
            Intent i = new Intent(this, ConnectionMonitorService.class)
                    .setAction(ConnectionMonitorService.ACTION_STATUS_UPDATE)
                    .putExtra("status_json", o.toString());
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException ignored) {}
    }

    private void loadPolicyAndFinish() {
        loadPolicyAndFinish(0, null);
    }

    private void loadPolicyAndFinish(Runnable afterLoaded) {
        loadPolicyAndFinish(0, afterLoaded);
    }

    private void loadPolicyAndFinish(int attempt, Runnable afterLoaded) {
        // Do not redispatch policy while an earlier callback may still be in flight.
        // A late callback from a previous attempt is rejected by the token guard, so
        // repeated dispatches can accidentally discard the valid result we are waiting for.
        clearOutput();
        clearCommandResult("policy");

        if (!McpBridge.run(this, "policy")) {
            if (attempt < 2) {
                handler.postDelayed(() -> loadPolicyAndFinish(attempt + 1, afterLoaded), 1000L);
            } else {
                operationFailure("Connected, but the policy query could not be started.");
            }
            return;
        }

        final long sentAt = getSharedPreferences("bridge", MODE_PRIVATE)
                .getLong("sent_at_policy", System.currentTimeMillis());
        waitForPolicyResult(sentAt, System.currentTimeMillis() + 8000L, afterLoaded);
    }

    private void waitForPolicyResult(long sentAt, long deadline, Runnable afterLoaded) {
        handler.postDelayed(() -> {
            android.content.SharedPreferences bridge =
                    getSharedPreferences("bridge", MODE_PRIVATE);

            String state = bridge.getString("callback_state_policy", "unknown");
            long receivedAt = bridge.getLong("received_at_policy", 0L);
            String policyError = commandError("policy");
            String out = bridge.getString("stdout_policy", "");
            boolean loaded = false;

            if (receivedAt >= sentAt && "received".equals(state)) {
                try {
                    JSONObject o = new JSONObject(out);
                    if (o.has("master_lock")) {
                        policy = o;
                        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                                .putBoolean("master_lock", o.optBoolean("master_lock", true))
                                .putString("policy_json", o.toString())
                                .apply();
                        loaded = true;
                    }
                } catch (Exception ignored) {}
            }

            if (loaded) {
                if (afterLoaded != null) {
                    afterLoaded.run();
                } else {
                    busy = false;
                    render();
                }
                return;
            }

            if (!policyError.isEmpty() && receivedAt >= sentAt) {
                busy = false;
                render();
                addLogBox(policyError);
                return;
            }

            if (System.currentTimeMillis() < deadline) {
                waitForPolicyResult(sentAt, deadline, afterLoaded);
                return;
            }

            busy = false;
            render();

            String stateDetail = bridge.getString("callback_state_policy", "unknown");
            String stage = bridge.getString("callback_stage_policy", "");
            int exit = bridge.getInt("exit_policy", -1);
            int errorCode = bridge.getInt("error_code_policy", -1);
            String stderr = bridge.getString("stderr_policy", "");
            String detail = stderr == null ? "" : stderr;

            StringBuilder b = new StringBuilder("Policy callback timeout.\\n")
                    .append("callback: ").append(stateDetail)
                    .append(" | stage: ").append(stage.isEmpty() ? "none" : stage)
                    .append("\\nreceived_at: ").append(receivedAt)
                    .append(" | sent_at: ").append(sentAt)
                    .append("\\nexit: ").append(exit)
                    .append(" | error: ").append(errorCode);
            if (!detail.isEmpty()) b.append("\\nSTDERR: ").append(detail);
            addLogBox(b.toString());
        }, 200L);
    }

    private void operationFailure(String message) {
        busy = false;
        render();
        addLogBox(message);
    }

    private void bridgeFailure(String message) {
        busy = false;
        connectionOk = false;
        lastConnectionStatus = null;
        status.setText("●  Disconnected");
        status.setTextColor(RED);
        render();
        String detail = getSharedPreferences("bridge", MODE_PRIVATE).getString("last_error", "");
        String log = detail == null || detail.isEmpty() ? message : detail;
        addLogBox(log);
    }

    private void addLogBox(String message) {
        String finalMessage = message == null || message.isEmpty() ? "No details." : message;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MCP Control diagnostic", finalMessage));
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(bg(CARD_SOFT, BORDER, 16));

        TextView heading = text("Log", 12, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(heading, new LinearLayout.LayoutParams(-1, dp(22)));

        ScrollView logScroll = new ScrollView(this);
        logScroll.setFillViewport(false);
        TextView log = text(finalMessage, 11, TEXT);
        log.setTextIsSelectable(true);
        log.setGravity(Gravity.TOP | Gravity.START);
        log.setPadding(0, dp(4), 0, dp(2));
        logScroll.addView(log);
        box.addView(logScroll, new LinearLayout.LayoutParams(-1, dp(86)));

        if (finalMessage.contains("com.termux.permission.RUN_COMMAND") || finalMessage.contains("Run commands in Termux")) {
            Button settings = button("Open MCP Control permissions", v -> openTermuxPermissionSettings());
            settings.setBackground(bg(CARD, BORDER, 20));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(38));
            sp.setMargins(0, dp(7), 0, 0);
            box.addView(settings, sp);
        }

        if (logHost != null) {
            logHost.removeAllViews();
            logHost.addView(box, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(6), 0, dp(6));
            content.addView(box, 0, lp);
        }
    }

    private void syncPolicyAndStatus() {
        // Use the same callback-token-aware policy waiter as initial load. The old
        // fixed 650 ms read could race a slow Termux callback, leaving the UI stale
        // until the user pressed Connect / Refresh.
        clearOutput();
        clearCommandResult("policy");
        if (!McpBridge.run(this, "policy")) {
            operationFailure("Policy query could not be started.");
            return;
        }

        final long sentAt = getSharedPreferences("bridge", MODE_PRIVATE)
                .getLong("sent_at_policy", System.currentTimeMillis());

        waitForPolicyResult(sentAt, System.currentTimeMillis() + 8000L, () -> {
            clearOutput();
            clearCommandResult("status");
            if (!McpBridge.run(this, "status")) {
                bridgeFailure("Status query could not be started.");
                return;
            }
            handler.postDelayed(() -> {
                String statusError = commandError("status");
                if (!statusError.isEmpty()) {
                    bridgeFailure(statusError);
                    return;
                }
                applyStatusResult();
                busy = false;
                render();
            }, 700L);
        });
    }

    private void loadApprovals() {
        if (busy) return;
        busy = true;
        status.setText("●  Checking approvals…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult("approvals");
        if (!McpBridge.run(this, "approvals")) {
            operationFailure("Could not query pending approvals.");
            return;
        }
        handler.postDelayed(() -> {
            String error = commandError("approvals");
            if (!error.isEmpty()) {
                operationFailure(error);
                return;
            }
            String out = getSharedPreferences("bridge", MODE_PRIVATE).getString("stdout_approvals", "");
            try {
                JSONObject o = new JSONObject(out);
                JSONArray a = o.optJSONArray("approvals");
                pendingApprovals = a == null ? new JSONArray() : a;
            } catch (Exception ignored) {
                pendingApprovals = new JSONArray();
            }
            busy = false;
            render();
            if ("overview".equals(group)) addPendingApprovalsCard();
        }, 650L);
    }

    private void addPendingApprovalsCard() {
        int count = pendingApprovals.length();
        addCard("Approval Queue", count == 0 ? "No pending requests" : count + " request" + (count == 1 ? "" : "s") + " awaiting approval");
        if (count == 0) {
            content.addView(text("Nothing needs approval right now.", 13, MUTED));
            return;
        }
        for (int i = 0; i < count; i++) {
            JSONObject item = pendingApprovals.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("approval_id", "");
            String capability = item.optString("capability", "unknown");
            String tool = item.optString("tool", "unknown");
            String expires = item.optString("expires_at", "");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(bg(CARD, BORDER, 16));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.setMargins(0, dp(5), 0, dp(5));
            content.addView(row, rp);
            TextView title = text(capability + "  •  " + tool, 14, TEXT);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(title);
            row.addView(text("Expires: " + expires, 10, MUTED));
            Button approve = button("Approve", v -> runApproval(id));
            approve.setBackground(bg(CARD_SOFT, BORDER, 22));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(40));
            bp.setMargins(0, dp(8), 0, 0);
            row.addView(approve, bp);
        }
    }

    private void loadAudit() {
        if (busy) return;
        busy = true;
        status.setText("●  Loading log…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult("audit");
        if (!McpBridge.run(this, "audit")) {
            operationFailure("Could not query the audit log.");
            return;
        }
        handler.postDelayed(() -> {
            String error = commandError("audit");
            String out = getSharedPreferences("bridge", MODE_PRIVATE).getString("stdout_audit", "");
            busy = false;
            render();
            if (!error.isEmpty()) {
                addLogBox(error);
                return;
            }
            try {
                JSONObject o = new JSONObject(out);
                JSONArray lines = o.optJSONArray("lines");
                if (lines == null || lines.length() == 0) {
                    addLogBox("No audit entries found.");
                    return;
                }
                StringBuilder b = new StringBuilder("Recent audit entries:\n");
                for (int i = 0; i < lines.length(); i++) {
                    b.append(lines.optString(i)).append('\n');
                }
                addLogBox(b.toString().trim());
            } catch (Exception e) {
                addLogBox(out.isEmpty() ? "No audit entries found." : out);
            }
        }, 650L);
    }

    private void runApproval(String id) {
        if (busy || id.isEmpty()) return;
        busy = true;
        status.setText("●  Approving…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult("approve");
        if (!McpBridge.run(this, "approve", id)) {
            operationFailure("Could not approve the request.");
            return;
        }
        handler.postDelayed(() -> {
            String error = commandError("approve");
            if (!error.isEmpty()) {
                operationFailure(error);
                return;
            }
            busy = false;
            loadApprovals();
        }, 650L);
    }

    private void runSet(String id,String n) {
        if(busy)return;
        busy=true;
        status.setText("●  Saving…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult("set");
        if (!McpBridge.run(this,"set",id,n)) {
            bridgeFailure("Could not start the policy change in Termux.");
            return;
        }
        handler.postDelayed(()->{syncPolicyAndStatus();},1100);
    }

    private void runAction(String a,int d) {
        if(busy)return;
        busy=true;
        status.setText("●  "+a.substring(0,1).toUpperCase()+a.substring(1)+"…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult(a);
        if (!McpBridge.run(this,a)) {
            bridgeFailure("Could not start the " + a + " action in Termux.");
            return;
        }
        handler.postDelayed(()->{syncPolicyAndStatus();},d);
    }

    private void lockAll() {
        if(busy)return;
        busy=true;
        status.setText("●  Locking…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult("lock");
        if (!McpBridge.run(this,"lock")) {
            bridgeFailure("Could not lock the control plane in Termux.");
            return;
        }
        handler.postDelayed(()->{syncPolicyAndStatus();},900);
    }

    private void unlockAll() {
        if(busy)return;
        busy=true;
        status.setText("●  Unlocking…");
        status.setTextColor(YELLOW);
        clearOutput();
        clearCommandResult("unlock");
        if (!McpBridge.run(this,"unlock","UNLOCK")) {
            bridgeFailure("Could not unlock the control plane in Termux.");
            return;
        }
        handler.postDelayed(()->{
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("emergency_locked", false)
                    .putBoolean("emergency_killed", false)
                    .apply();
            startConnectionMonitor();
            syncPolicyAndStatus();
        },900);
    }
}

// Command callbacks are emitted by the executable mobile_control.sh dispatcher.
// CI trigger: connection diagnostic branch is syntactically closed.
