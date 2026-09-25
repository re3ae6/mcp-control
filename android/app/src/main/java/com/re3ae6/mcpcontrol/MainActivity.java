package com.re3ae6.mcpcontrol;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private LinearLayout content, indicatorRow, tabBar;
    private TextView status, lockBanner;
    private JSONObject policy;
    private String group = "overview";
    private boolean busy = false;

    private static final int BG = 0xfff7f6f2;
    private static final int CARD = 0xffffffff;
    private static final int CARD_SOFT = 0xfff1f0ec;
    private static final int BORDER = 0xffe5e2db;
    private static final int TEXT = 0xff171918;
    private static final int MUTED = 0xff777a76;
    private static final int GREEN = 0xff16835b;
    private static final int RED = 0xffc84b4b;
    private static final int YELLOW = 0xffa87308;
    private static final int BLUE = 0xff3e628e;

    private final String[] groups = {"overview","files","git","terminal","network","mcp","device","dangerous"};
    private final String[] labels = {"Overview","Files","Git","Terminal","Network","MCP","Device","Dangerous"};

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
        b.setPadding(dp(12), 0, dp(12), 0);
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
        buildUi();
        renderOffline();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(26), dp(20), dp(14));
        header.setBackgroundColor(BG);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("MCP Control", 25, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));

        TextView version = text("re3a  •  0.2", 11, MUTED);
        version.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        brand.addView(version, new LinearLayout.LayoutParams(dp(90), dp(36)));
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
            tabBar.addView(b, new LinearLayout.LayoutParams(dp(92), dp(40)));
        }
        tabs.addView(tabBar);
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(32));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

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
        TextView dot = text("●", 12, ok ? GREEN : RED);
        TextView nameText = text("  " + name + "  " + (ok ? "Ready" : "Offline"), 11, MUTED);
        item.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(28)));
        item.addView(nameText, new LinearLayout.LayoutParams(0, dp(28), 1));
        indicatorRow.addView(item, new LinearLayout.LayoutParams(0, dp(28), 1));
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
        p.setMargins(0, dp(7), 0, dp(7));
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

    private void addMasterBanner(boolean locked) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(bg(locked ? 0xfffff1f1 : 0xffeef8f3, locked ? 0xfff0caca : 0xffcfe9dc, 18));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(7), 0, dp(7));
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
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(13), dp(11), dp(10), dp(11));
        box.setBackground(bg(CARD, BORDER, 16));
        TextView n = text(name, 10, MUTED);
        n.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(n);
        TextView v = text(value, 22, color);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(v);
        return box;
    }

    private void addStatusCard(boolean m, boolean p, boolean t) {
        addCard("Connection", "Local services");
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(4), 0, 0);
        row.addView(metric("MCP", m ? "Ready" : "Offline", m ? GREEN : RED), new LinearLayout.LayoutParams(0, dp(76), 1));
        LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(76), 1);
        q.setMargins(dp(7), 0, 0, 0);
        row.addView(metric("Proxy", p ? "Ready" : "Offline", p ? GREEN : RED), q);
        q = new LinearLayout.LayoutParams(0, dp(76), 1);
        q.setMargins(dp(7), 0, 0, 0);
        row.addView(metric("Tunnel", t ? "Live" : "Offline", t ? GREEN : RED), q);
        content.addView(row);
    }

    private void renderOffline() {
        status.setText("●  Offline");
        status.setTextColor(RED);
        content.removeAllViews();

        addCard("System Overview", "Secure local controller");
        addMasterBanner(true);
        addStatusCard(false, false, false);

        addCard("Ready when you are", "The bridge is offline. Controls remain safe and visible.");
        Button b = button("Connect / Refresh", v -> refresh());
        b.setBackground(bg(TEXT, TEXT, 24));
        b.setTextColor(Color.WHITE);
        content.addView(b, new LinearLayout.LayoutParams(-1, dp(46)));

        highlightTab();
    }

    private void render() {
        content.removeAllViews();
        if (policy == null) { renderOffline(); return; }

        boolean locked = policy.optBoolean("master_lock", true);
        highlightTab();

        if ("overview".equals(group)) {
            addCard("System Overview", "Secure local controller");
            addMasterBanner(locked);

            addCard("Policy", "Effective capability states");
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

            addCard("Actions", "Service control");
            Button start = button("Start MCP", v -> { if (!busy) refresh(); });
            start.setBackground(bg(CARD, BORDER, 24));
            content.addView(start, new LinearLayout.LayoutParams(-1, dp(46)));
            Button restart = button("Restart MCP", v -> runAction("restart", 1900));
            restart.setBackground(bg(CARD, BORDER, 24));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46));
            p.setMargins(0, dp(7), 0, 0);
            content.addView(restart, p);
            return;
        }

        addCard(labels[indexOf(group)], "Capability policy  •  default deny");
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
        box.setPadding(dp(15),dp(14),dp(15),dp(14));
        box.setBackground(bg(CARD,BORDER,18));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);
        bp.setMargins(0,dp(5),0,dp(5));
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
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);
        if(row.getChildCount()>0)p.setMargins(dp(6),0,0,0);
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
        handler.postDelayed(() -> {
            clearOutput();
            clearCommandResult("status");
            if (!McpBridge.run(this, "status")) {
                bridgeFailure("Could not query Termux. Check Termux permission: Run commands in Termux.");
                return;
            }
            handler.postDelayed(() -> {
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
                if (connectFailed || attempt >= 34) {
                    String err = commandError("connect");
                    if (err.isEmpty()) err = bridge.getString("stderr_connect", "");
                    busy = false;
                    render();
                    if (err != null && !err.isEmpty()) {
                        content.addView(text("Connect: " + err, 11, RED));
                    } else {
                        content.addView(text("Connection did not become ready. Open Termux and verify Run commands in Termux plus allow-external-apps.", 11, RED));
                    }
                    return;
                }
                pollConnection(attempt + 1);
            }, 550L);
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
                status.setText("●  " + (ok ? "Connected" : "Disconnected"));
                status.setTextColor(ok ? GREEN : RED);
                updateIndicators(o);
                return ok;
            }
        } catch (Exception ignored) {}
        if (err != null && !err.isEmpty()) status.setText("●  Bridge Error");
        else if (exit != 0) status.setText("●  Disconnected");
        status.setTextColor(RED);
        return false;
    }

    private void loadPolicyAndFinish() {
        clearOutput();
        clearCommandResult("policy");
        if (!McpBridge.run(this, "policy")) {
            bridgeFailure("Connected, but the policy query could not be started.");
            return;
        }
        handler.postDelayed(() -> {
            String policyError = commandError("policy");
            if (!policyError.isEmpty()) {
                bridgeFailure(policyError);
                return;
            }
            String out = getSharedPreferences("bridge", MODE_PRIVATE).getString("stdout_policy", "");
            try {
                JSONObject o = new JSONObject(out);
                if (o.has("master_lock")) policy = o;
            } catch (Exception ignored) {}
            busy = false;
            render();
        }, 650L);
    }

    private void bridgeFailure(String message) {
        busy = false;
        status.setText("●  Disconnected");
        status.setTextColor(RED);
        render();
        String detail = getSharedPreferences("bridge", MODE_PRIVATE).getString("last_error", "");
        content.addView(text(detail == null || detail.isEmpty() ? message : detail, 11, RED));
    }

    private void syncPolicyAndStatus() {
        clearOutput();
        clearCommandResult("policy");
        if (!McpBridge.run(this, "policy")) {
            bridgeFailure("Policy query could not be started.");
            return;
        }
        handler.postDelayed(() -> {
            String policyError = commandError("policy");
            if (!policyError.isEmpty()) {
                bridgeFailure(policyError);
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
        handler.postDelayed(()->{syncPolicyAndStatus();},900);
    }
}
