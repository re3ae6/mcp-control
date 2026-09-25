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
    private TextView status, lockBanner;
    private LinearLayout logHost;
    private JSONObject policy;
    private JSONArray pendingApprovals = new JSONArray();
    private String group = "overview";
    private boolean busy = false;
    private boolean connectionOk = false;
    private JSONObject lastConnectionStatus;

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
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (isCloseFromNotification(getIntent())) {
            finishAndRemoveTask();
            return;
        }
        buildUi();
        renderOffline();
        ensureNotificationPermission();
        ensureTermuxRunCommandPermission();
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED) {
            startConnectionMonitor();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isCloseFromNotification(intent)) finishAndRemoveTask();
    }

    private boolean isCloseFromNotification(Intent intent) {
        return intent != null && intent.getBooleanExtra("close_from_notification", false);
    }

    @Override protected void onResume() {
        super.onResume();
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("activity_visible", true)
                .apply();
        startConnectionMonitor();
        applyMonitorSnapshot();
        refreshStatusSnapshot();
    }

    @Override protected void onPause() {
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("activity_visible", false)
                .apply();
        super.onPause();
    }

    private void startConnectionMonitor() {
        android.content.SharedPreferences bridge =
                getSharedPreferences("bridge", MODE_PRIVATE);
        if (bridge.getBoolean("emergency_killed", false)) return;
        try {
            Intent i = new Intent(this, ConnectionMonitorService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (RuntimeException ignored) {
            // Manual Connect / Refresh remains available if the monitor cannot start.
        }
    }

    private void applyMonitorSnapshot() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("bridge", MODE_PRIVATE);
        long receivedAt = prefs.getLong("monitor_received_at", 0L);
        if (receivedAt <= 0L || System.currentTimeMillis() - receivedAt > 30000L) return;

        String json = prefs.getString("monitor_status_json", "");
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("connected")) {
                connectionOk = o.optBoolean("connected");
                lastConnectionStatus = o;
                status.setText("●  " + (connectionOk ? "Connected" : "Disconnected"));
                status.setTextColor(connectionOk ? GREEN : RED);
                updateIndicators(o);
            }
        } catch (Exception ignored) {}
    }

    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void refreshStatusSnapshot() {
        if (busy || getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("emergency_killed", false)) return;
        clearCommandResult("status");
        if (!McpBridge.run(this, "status")) return;
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            String error = commandError("status");
            if (!error.isEmpty()) return;
            applyStatusResult();
        }, 700L);
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
        header.setPadding(dp(20), dp(20), dp(20), dp(14));
        header.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            v.setPadding(dp(20), dp(20) + top, dp(20), dp(14));
            return insets;
        });
        header.setBackgroundColor(BG);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("MCP Control", 25, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));

        TextView signature = text("re3a  •  v0.2.1", 11, MUTED);
        signature.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        signature.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(signature, new LinearLayout.LayoutParams(dp(112), dp(36)));
        header.addView(brand);

        status = text("●  Offline", 13, RED);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(0, dp(6), 0, dp(10));
        header.addView(status);

        indicatorRow = new LinearLayout(this);
        indicatorRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(indicatorRow, new LinearLayout.LayoutParams(-1, dp(30)));
        setInitialIndicators();

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(8), 0, 0);

        Button connect = button("Connect / Refresh", v -> refresh());
        connect.setBackground(bg(TEXT, TEXT, 24));
        connect.setTextColor(Color.WHITE);
        actions.addView(connect, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button lock = button("Lock", v -> lockAll());
        lock.setBackground(bg(CARD, BORDER, 24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(78), dp(46));
        lp.setMargins(dp(8), 0, 0, 0);
        actions.addView(lock, lp);
        header.addView(actions);

        root.addView(header);

        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        tabs.setBackgroundColor(CARD);
        tabBar = new LinearLayout(this);
        tabBar.setPadding(dp(16), dp(8), dp(16), dp(8));
        for (int i = 0; i < groups.length; i++) {
            final String g = groups[i];
            Button b = button(labels[i], v -> { group = g; render(); });
            b.setTag(g);
            b.setTextSize(11);
            b.setSingleLine(true);
            b.setMaxLines(1);
            b.setIncludeFontPadding(false);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(84), dp(40));
            if (i > 0) tp.setMargins(dp(4), 0, 0, 0);
            tabBar.addView(b, tp);
        }
        tabs.addView(tabBar);
        root.addView(tabs);

        scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(32));
        scrollView.addView(content);
        scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            content.setPadding(dp(16), dp(18), dp(16), dp(32) + bottom);
            return insets;
        });
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        highlightTab();
    }

    private void setInitialIndicators() {
        indicatorRow.removeAllViews();
        addIndicator("MCP", false);
        addIndicator("Proxy", false);
        addIndicator("Tunnel", false);
    }

    private void addIndicator(String name, boolean ok) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = text("●", 18, ok ? GREEN : RED);
        TextView nameText = text("  " + name + "  " + (ok ? "Ready" : "Offline"), 11, MUTED);
        item.addView(dot, new LinearLayout.LayoutParams(dp(20), dp(30)));
        item.addView(nameText, new LinearLayout.LayoutParams(0, dp(30), 1));
        indicatorRow.addView(item, new LinearLayout.LayoutParams(0, dp(30), 1));
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
        box.setPadding(dp(16), dp(15), dp(16), dp(16));
        box.setBackground(bg(CARD, BORDER, 18));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(4), 0, dp(4));
        content.addView(box, p);

        TextView a = text(title, 19, TEXT);
        a.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(a);
        if (subtitle != null) {
            TextView sub = text(subtitle, 11, MUTED);
            sub.setPadding(0, dp(4), 0, 0);
            box.addView(sub);
        }
    }

    // Section headings are labels, not controls; keeping them out of cards reduces vertical scrolling.
    private void addSectionHeader(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(4));
        TextView h = text(title, 16, TEXT);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(h, new LinearLayout.LayoutParams(-1, dp(24)));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 11, MUTED);
            row.addView(sub, new LinearLayout.LayoutParams(-1, dp(20)));
        }
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addMasterBanner(boolean locked) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(bg(locked ? 0xfffff1f1 : 0xffeef8f3, locked ? 0xfff0caca : 0xffcfe9dc, 18));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(4), 0, dp(4));
        content.addView(box, p);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView h = text("Master Lock", 16, locked ? RED : GREEN);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(h);
        copy.addView(text(locked ? "All capabilities are effectively denied" : "Policy is active", 11, MUTED));
        box.addView(copy, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button action = button(locked ? "Unlock" : "Lock", v -> { if (locked) unlockAll(); else lockAll(); });
        action.setBackground(bg(CARD, locked ? 0xffe7bcbc : 0xffc7dfd1, 22));
        box.addView(action, new LinearLayout.LayoutParams(dp(92), dp(42)));
        lockBanner = h;
    }

    private View metric(String name, String value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackground(bg(CARD, BORDER, 16));
        TextView n = text(name, 10, MUTED);
        n.setGravity(Gravity.CENTER);
        n.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(n, new LinearLayout.LayoutParams(-1, dp(18)));
        TextView v = text(value, 18, color);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(v, new LinearLayout.LayoutParams(-1, dp(28)));
        return box;
    }

    private void addBridgeConnectionCard(boolean m, boolean p, boolean t) {
        addSectionHeader("Bridge & Connection", "Local services");
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(3), 0, 0);
        row.addView(metric("MCP", m ? "Ready" : "Offline", m ? GREEN : RED),
                new LinearLayout.LayoutParams(0, dp(70), 1));
        LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(70), 1);
        q.setMargins(dp(6), 0, 0, 0);
        row.addView(metric("Proxy", p ? "Ready" : "Offline", p ? GREEN : RED), q);
        q = new LinearLayout.LayoutParams(0, dp(70), 1);
        q.setMargins(dp(6), 0, 0, 0);
        row.addView(metric("Tunnel", t ? "Live" : "Offline", t ? GREEN : RED), q);
        content.addView(row);

        logHost = new LinearLayout(this);
        logHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, -2);
        logLp.setMargins(0, dp(6), 0, 0);
        content.addView(logHost, logLp);

        Button connect = button("Connect / Refresh", v -> refresh());
        connect.setBackground(bg(TEXT, TEXT, 22));
        connect.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(42));
        cp.setMargins(0, dp(7), 0, 0);
        content.addView(connect, cp);
    }

    private void renderOffline() {
        status.setText("●  " + (connectionOk ? "Connected" : "Offline"));
        status.setTextColor(connectionOk ? GREEN : RED);
        if (connectionOk && lastConnectionStatus != null) updateIndicators(lastConnectionStatus);
        content.removeAllViews();

        addSectionHeader("System Overview", "Secure local controller");
        addMasterBanner(true);
        addBridgeConnectionCard(false, false, false);

        highlightTab();
    }

    private void render() {
        content.removeAllViews();
        if (policy == null) { renderOffline(); return; }

        boolean locked = policy.optBoolean("master_lock", true);
        highlightTab();

        if ("overview".equals(group)) {
            addSectionHeader("System Overview", "Secure local controller");
            addMasterBanner(locked);
            if (connectionOk && lastConnectionStatus != null) {
                addBridgeConnectionCard(
                        "OK".equalsIgnoreCase(lastConnectionStatus.optString("mcp")),
                        "OK".equalsIgnoreCase(lastConnectionStatus.optString("proxy")),
                        lastConnectionStatus.optString("tunnel", "").toLowerCase().contains("live")
                                || lastConnectionStatus.optString("tunnel", "").toLowerCase().contains("ready"));
            }

            addSectionHeader("Policy", "Effective capability states");
            LinearLayout counts = new LinearLayout(this);
            int[] c = countStates();
            counts.addView(metric("DENY", String.valueOf(c[0]), RED), new LinearLayout.LayoutParams(0, dp(76), 1));
            LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(76), 1);
            q.setMargins(dp(7), 0, 0, 0);
            counts.addView(metric("ASK", String.valueOf(c[1]), YELLOW), q);
            q = new LinearLayout.LayoutParams(0, dp(76), 1);
            q.setMargins(dp(7), 0, 0, 0);
            counts.addView(metric("ALLOW", String.valueOf(c[2]), GREEN), q);
            content.addView(counts);

            addSectionHeader("Actions", "Service control");
            Button start = button("Start MCP", v -> { if (!busy) refresh(); });
            start.setBackground(bg(CARD, BORDER, 24));
            content.addView(start, new LinearLayout.LayoutParams(-1, dp(46)));
            Button restart = button("Restart MCP", v -> runAction("restart", 1900));
            restart.setBackground(bg(CARD, BORDER, 24));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46));
            p.setMargins(0, dp(7), 0, 0);
            content.addView(restart, p);

            addSectionHeader("Approvals", "Pending one-shot requests");
            Button approvals = button("Check pending approvals", v -> loadApprovals());
            approvals.setBackground(bg(CARD, BORDER, 24));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(46));
            ap.setMargins(0, dp(7), 0, 0);
            content.addView(approvals, ap);

            Button log = button("View recent audit log", v -> loadAudit());
            log.setBackground(bg(CARD, BORDER, 24));
            LinearLayout.LayoutParams lg = new LinearLayout.LayoutParams(-1, dp(46));
            lg.setMargins(0, dp(7), 0, 0);
            content.addView(log, lg);
            return;
        }

        addSectionHeader(labels[indexOf(group)], "Capability policy  •  default deny");
        if (locked) addMasterBanner(true);

        JSONObject caps = policy.optJSONObject("capabilities");
        JSONArray a = caps == null ? null : caps.optJSONArray(group);
        if (a == null || a.length() == 0) {
            content.addView(text("No capabilities in this group.", 13, MUTED));
            return;
        }

        content.addView(label("Permissions"), new LinearLayout.LayoutParams(-1, dp(28)));
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null) addCapabilityRow(x, locked);
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
        String name=x.optString("label",id);
        String description=x.optString("description","");
        String state=x.optString("state","deny");

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15),dp(14),dp(15),dp(17));
        box.setBackground(bg(CARD,BORDER,18));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);
        bp.setMargins(0,dp(4),0,dp(4));
        content.addView(box,bp);

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot=text("●",13,locked?RED:stateColor(state));
        top.addView(dot,new LinearLayout.LayoutParams(dp(20),dp(28)));
        TextView title=text(name,15,TEXT);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        top.addView(title,new LinearLayout.LayoutParams(0,dp(28),1));
        TextView st=text(locked?"DENY":state.toUpperCase(),10,locked?RED:stateColor(state));
        st.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        st.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        top.addView(st,new LinearLayout.LayoutParams(dp(62),dp(28)));
        box.addView(top);

        if(!description.isEmpty()) {
            TextView d=text(description,11,MUTED);
            d.setPadding(dp(20),dp(2),0,dp(5));
            box.addView(d);
        }

        TextView idText=text(id,9,0xff999b98);
        idText.setPadding(dp(20),0,0,dp(8));
        box.addView(idText);

        LinearLayout choices=new LinearLayout(this);
        choices.setPadding(0, 0, dp(2), 0);
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

    private void addStateButton(LinearLayout row,String label,String target,String id,String current,boolean locked) {
        Button b=button((target.equals(current) ? "✓  " : "") + label,v->runSet(id,target));
        boolean selected=target.equals(current);
        b.setEnabled(!locked && !busy);
        b.setTextColor(selected?TEXT:MUTED);
        b.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);
        b.setBackground(bg(selected?CARD_SOFT:0xfffaf9f6,selected?BORDER:BORDER,22));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);
        if(row.getChildCount()>0)p.setMargins(dp(5),0,0,0);
        else p.setMargins(0,0,dp(1),0);
        row.addView(b,p);
    }

    private int indexOf(String g){for(int i=0;i<groups.length;i++)if(groups[i].equals(g))return i;return 0;}

    private void updateIndicators(JSONObject o) {
        indicatorRow.removeAllViews();
        boolean m="OK".equalsIgnoreCase(o.optString("mcp")) || o.optBoolean("mcp_ok",false);
        boolean p="OK".equalsIgnoreCase(o.optString("proxy")) || o.optBoolean("proxy_ok",false);
        String ts=o.optString("tunnel","").toLowerCase();
        boolean t=ts.contains("live") || ts.contains("ready") || o.optBoolean("tunnel_ok",false);
        addIndicator("MCP",m); addIndicator("Proxy",p); addIndicator("Tunnel",t);
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
        String out = getSharedPreferences("bridge", MODE_PRIVATE).getString("stdout_status", "");
        String err = getSharedPreferences("bridge", MODE_PRIVATE).getString("stderr_status", "");
        int exit = getSharedPreferences("bridge", MODE_PRIVATE).getInt("exit_status", -1);
        try {
            JSONObject o = new JSONObject(out);
            if (o.has("connected")) {
                boolean ok = o.optBoolean("connected");
                connectionOk = ok;
                lastConnectionStatus = o;
                status.setText("●  " + (ok ? "Connected" : "Disconnected"));
                status.setTextColor(ok ? GREEN : RED);
                updateIndicators(o);
                return ok;
            }
        } catch (Exception ignored) {}
        if (err != null && !err.isEmpty()) status.setText("●  Bridge Error");
        else if (exit != 0) status.setText("●  Disconnected");
        status.setTextColor(RED);
        connectionOk = false;
        lastConnectionStatus = null;
        return false;
    }

    private void loadPolicyAndFinish() {
        loadPolicyAndFinish(0);
    }

    private void loadPolicyAndFinish(int attempt) {
        // Do not redispatch policy while an earlier callback may still be in flight.
        // A late callback from a previous attempt is rejected by the token guard, so
        // repeated dispatches can accidentally discard the valid result we are waiting for.
        clearOutput();
        clearCommandResult("policy");

        if (!McpBridge.run(this, "policy")) {
            if (attempt < 2) {
                handler.postDelayed(() -> loadPolicyAndFinish(attempt + 1), 1000L);
            } else {
                operationFailure("Connected, but the policy query could not be started.");
            }
            return;
        }

        final long sentAt = getSharedPreferences("bridge", MODE_PRIVATE)
                .getLong("sent_at_policy", System.currentTimeMillis());
        waitForPolicyResult(sentAt, System.currentTimeMillis() + 8000L);
    }

    private void waitForPolicyResult(long sentAt, long deadline) {
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
                        loaded = true;
                    }
                } catch (Exception ignored) {}
            }

            if (loaded) {
                busy = false;
                render();
                return;
            }

            if (!policyError.isEmpty() && receivedAt >= sentAt) {
                busy = false;
                render();
                addLogBox(policyError);
                return;
            }

            if (System.currentTimeMillis() < deadline) {
                waitForPolicyResult(sentAt, deadline);
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
        clearOutput();
        clearCommandResult("policy");
        if (!McpBridge.run(this, "policy")) {
            operationFailure("Policy query could not be started.");
            return;
        }
        handler.postDelayed(() -> {
            String policyError = commandError("policy");
            if (!policyError.isEmpty()) {
                operationFailure(policyError);
                return;
            }
            String out = getSharedPreferences("bridge", MODE_PRIVATE).getString("stdout_policy", "");
            try {
                JSONObject o = new JSONObject(out);
                if (o.has("master_lock")) policy = o;
            } catch (Exception ignored) {}
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
            }, 550L);
        }, 650L);
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
