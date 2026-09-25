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

    private static final int BG = 0xff070b12;
    private static final int PANEL = 0xff0d131d;
    private static final int PANEL2 = 0xff111a26;
    private static final int BORDER = 0xff243244;
    private static final int TEXT = 0xffe5edf7;
    private static final int MUTED = 0xff8291a5;
    private static final int GREEN = 0xff39d98a;
    private static final int RED = 0xffff5d6c;
    private static final int YELLOW = 0xffffc857;
    private static final int BLUE = 0xff58a6ff;

    private final String[] groups = {"overview","files","git","terminal","network","mcp","device","dangerous"};
    private final String[] labels = {"Overview","Files","Git","Terminal","Network","MCP","Device","Dangerous"};

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 11, MUTED);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(.08f);
        t.setPadding(dp(2), dp(16), dp(2), dp(7));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setMinHeight(dp(42));
        b.setPadding(dp(10), 0, dp(10), 0);
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

    private TextView mono(String s, int size, int color) {
        TextView t = text(s, size, color);
        t.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        return t;
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
        header.setPadding(dp(14), dp(13), dp(14), dp(11));
        header.setBackgroundColor(PANEL);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = mono("MCP CONTROL", 21, TEXT);
        brand.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, dp(34), 1));

        TextView build = mono("re3a  •  v2", 10, MUTED);
        build.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        brandRow.addView(build, new LinearLayout.LayoutParams(dp(100), dp(34)));
        header.addView(brandRow);

        status = mono("●  OFFLINE  /  DISCONNECTED", 11, RED);
        status.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        status.setPadding(0, dp(3), 0, dp(8));
        header.addView(status);

        indicatorRow = new LinearLayout(this);
        indicatorRow.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(indicatorRow, new LinearLayout.LayoutParams(-1, dp(30)));
        setInitialIndicators();

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(5), 0, 0);
        Button connect = button("↻  CONNECT / REFRESH", v -> refresh());
        Button lock = button("LOCK", v -> lockAll());
        connect.setBackground(bg(PANEL2, BORDER, 8));
        lock.setBackground(bg(0xff35131a, 0xff6f2933, 8));
        actions.addView(connect, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(78), dp(44));
        lp.setMargins(dp(7), 0, 0, 0);
        actions.addView(lock, lp);
        header.addView(actions);

        root.addView(header);

        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        tabs.setBackgroundColor(0xff090f17);
        tabBar = new LinearLayout(this);
        tabBar.setPadding(dp(7), dp(5), dp(7), dp(5));
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
        content.setPadding(dp(12), 0, dp(12), dp(26));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        highlightTab();
    }

    private void setInitialIndicators() {
        indicatorRow.removeAllViews();
        addIndicator("MCP", false);
        addIndicator("PROXY", false);
        addIndicator("TUNNEL", false);
    }

    private void addIndicator(String name, boolean ok) {
        TextView d = mono((ok ? "● " : "○ ") + name + "  " + (ok ? "OK" : "DOWN"), 10, ok ? GREEN : RED);
        d.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        indicatorRow.addView(d, new LinearLayout.LayoutParams(0, dp(30), 1));
    }

    private void highlightTab() {
        if (tabBar == null) return;
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View v = tabBar.getChildAt(i);
            boolean selected = groups[i].equals(group);
            v.setBackground(bg(selected ? 0xff172538 : 0xff090f17, selected ? BLUE : 0xff090f17, 7));
            if (v instanceof Button) {
                ((Button)v).setTextColor(selected ? TEXT : MUTED);
                ((Button)v).setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }

    private TextView panelTitle(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(13), dp(14), dp(13));
        box.setBackground(bg(PANEL, BORDER, 10));
        TextView a = mono(title, 17, TEXT);
        a.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        box.addView(a);
        if (sub != null) box.addView(mono(sub, 10, MUTED));
        TextView out = text("", 1, Color.TRANSPARENT);
        box.addView(out, new LinearLayout.LayoutParams(1,1));
        return wrapBox(box);
    }

    private TextView wrapBox(LinearLayout box) {
        TextView anchor = text("", 1, Color.TRANSPARENT);
        anchor.setTag(box);
        return anchor;
    }

    private void addPanel(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(13), dp(14), dp(13));
        box.setBackground(bg(PANEL, BORDER, 10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(7), 0, 0);
        content.addView(box, p);
        TextView a = mono(title, 17, TEXT);
        a.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        box.addView(a);
        if (sub != null) box.addView(mono(sub, 10, MUTED));
    }

    private TextView metric(String name, String value, int color) {
        TextView t = mono(name + "\n" + value, 11, color);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setPadding(dp(12), dp(10), dp(8), dp(10));
        t.setBackground(bg(PANEL2, BORDER, 8));
        return t;
    }

    private void renderOffline() {
        status.setText("●  OFFLINE  /  DISCONNECTED  /  re3a");
        status.setTextColor(RED);
        content.removeAllViews();
        addPanel("SYSTEM OVERVIEW", "LOCAL POLICY CONTROLLER");
        addMasterBanner(true);
        addStatusPanel(false, false, false);
        content.addView(section("Controller"));
        content.addView(button("↻  CONNECT / REFRESH", v -> refresh()));
        content.addView(mono("Default security posture: DENY\nBridge is offline; controls remain visible.", 11, MUTED));
        highlightTab();
    }

    private void addMasterBanner(boolean locked) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(13), dp(11), dp(13), dp(11));
        box.setBackground(bg(locked ? 0xff211116 : 0xff10231b, locked ? 0xff6f2933 : 0xff245b42, 10));
        TextView left = mono(locked ? "MASTER LOCK" : "MASTER LOCK", 12, locked ? RED : GREEN);
        left.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        box.addView(left, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView right = mono(locked ? "ON  /  EFFECTIVE DENY" : "OFF  /  POLICY ACTIVE", 10, locked ? RED : GREEN);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        box.addView(right, new LinearLayout.LayoutParams(-2, dp(46)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(7), 0, 0);
        content.addView(box, p);
        lockBanner = right;
    }

    private void addStatusPanel(boolean m, boolean pxy, boolean tun) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(10), dp(13), dp(10));
        box.setBackground(bg(PANEL, BORDER, 10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(7), 0, 0);
        content.addView(box, p);

        TextView h = section("Connection");
        h.setPadding(0, 0, 0, dp(6));
        box.addView(h);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(metric("MCP", m ? "OK" : "DOWN", m ? GREEN : RED), new LinearLayout.LayoutParams(0, dp(58), 1));
        LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(58), 1);
        q.setMargins(dp(6), 0, 0, 0);
        row.addView(metric("PROXY", pxy ? "OK" : "DOWN", pxy ? GREEN : RED), q);
        q = new LinearLayout.LayoutParams(0, dp(58), 1);
        q.setMargins(dp(6), 0, 0, 0);
        row.addView(metric("TUNNEL", tun ? "OK" : "DOWN", tun ? GREEN : RED), q);
        box.addView(row);
    }

    private void render() {
        content.removeAllViews();
        if (policy == null) { renderOffline(); return; }

        boolean locked = policy.optBoolean("master_lock", true);
        highlightTab();

        if ("overview".equals(group)) {
            addPanel("SYSTEM OVERVIEW", "SECURE LOCAL CONTROLLER");
            addMasterBanner(locked);

            boolean m = false, p = false, t = false;
            // Indicators are already driven by the last real status response.
            content.addView(section("Policy"));
            LinearLayout counts = new LinearLayout(this);
            counts.setOrientation(LinearLayout.HORIZONTAL);
            int[] c = countStates();
            counts.addView(metric("DENY", String.valueOf(c[0]), RED), new LinearLayout.LayoutParams(0, dp(68), 1));
            LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(68), 1);
            q.setMargins(dp(6), 0, 0, 0);
            counts.addView(metric("ASK", String.valueOf(c[1]), YELLOW), q);
            q = new LinearLayout.LayoutParams(0, dp(68), 1);
            q.setMargins(dp(6), 0, 0, 0);
            counts.addView(metric("ALLOW", String.valueOf(c[2]), GREEN), q);
            content.addView(counts);

            content.addView(section("Actions"));
            content.addView(button("▶  START MCP", v -> runAction("start", 1600)));
            content.addView(button("↻  RESTART MCP", v -> runAction("restart", 1900)));
            content.addView(button(locked ? "UNLOCK CONTROLS" : "LOCK EVERYTHING", v -> { if (locked) unlockAll(); else lockAll(); }));
            content.addView(mono("Every capability is independently governed by DENY / ASK / ALLOW.\nMaster Lock overrides all capabilities to effective DENY.", 11, MUTED));
            return;
        }

        addPanel(labels[indexOf(group)], "CAPABILITY POLICY  /  DEFAULT DENY");
        if (locked) addMasterBanner(true);

        JSONObject caps = policy.optJSONObject("capabilities");
        JSONArray a = caps == null ? null : caps.optJSONArray(group);
        if (a == null || a.length() == 0) {
            content.addView(mono("No capabilities in this group.", 12, MUTED));
            return;
        }

        content.addView(section("Permissions"));
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
                if (x==null) continue;
                String s=x.optString("state","deny");
                if ("ask".equals(s)) c[1]++; else if ("allow".equals(s)) c[2]++; else c[0]++;
            }
        }
        if (policy.optBoolean("master_lock", true)) {
            c[0] += c[1] + c[2];
            c[1] = 0; c[2] = 0;
        }
        return c;
    }

    private void addCapabilityRow(JSONObject x, boolean locked) {
        String id=x.optString("id");
        String label=x.optString("label",id);
        String description=x.optString("description","");
        String state=x.optString("state","deny");

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12),dp(10),dp(12),dp(10));
        box.setBackground(bg(PANEL, BORDER, 9));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);
        bp.setMargins(0,dp(5),0,dp(5));
        content.addView(box,bp);

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView marker=mono(markerFor(state, locked),16,locked?RED:stateColor(state));
        marker.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        top.addView(marker,new LinearLayout.LayoutParams(dp(27),dp(28)));
        TextView name=mono(label,13,TEXT);
        name.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        top.addView(name,new LinearLayout.LayoutParams(0,dp(28),1));
        TextView stateText=mono(locked?"DENY":state.toUpperCase(),10,locked?RED:stateColor(state));
        stateText.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        stateText.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        top.addView(stateText,new LinearLayout.LayoutParams(dp(62),dp(28)));
        box.addView(top);

        if(!description.isEmpty()) {
            TextView d=mono(description,10,MUTED);
            d.setPadding(dp(27),0,0,dp(5));
            box.addView(d);
        }
        TextView idText=mono(id,9,0xff64748b);
        idText.setPadding(dp(27),0,0,dp(5));
        box.addView(idText);

        LinearLayout choices=new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        addStateButton(choices,"DENY","deny",id,state,locked);
        addStateButton(choices,"ASK","ask",id,state,locked);
        addStateButton(choices,"ALLOW","allow",id,state,locked);
        box.addView(choices);
    }

    private String markerFor(String s, boolean locked) {
        if (locked) return "■";
        if ("allow".equals(s)) return "●";
        if ("ask".equals(s)) return "◐";
        return "○";
    }

    private int stateColor(String s) {
        if ("allow".equals(s)) return GREEN;
        if ("ask".equals(s)) return YELLOW;
        return RED;
    }

    private void addStateButton(LinearLayout row,String label,String target,String id,String current,boolean locked) {
        Button b=button((target.equals(current) ? "● " : "○ ") + label,v->runSet(id,target));
        b.setEnabled(!locked && !busy);
        b.setTextColor(target.equals(current)?TEXT:MUTED);
        b.setTypeface(Typeface.MONOSPACE,target.equals(current)?Typeface.BOLD:Typeface.NORMAL);
        b.setBackground(bg(target.equals(current)?0xff172538:PANEL2,target.equals(current)?BLUE:BORDER,7));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),1);
        if(row.getChildCount()>0)p.setMargins(dp(5),0,0,0);
        row.addView(b,p);
    }

    private int indexOf(String g){for(int i=0;i<groups.length;i++)if(groups[i].equals(g))return i;return 0;}

    private void updateIndicators(JSONObject o) {
        indicatorRow.removeAllViews();
        boolean m="OK".equalsIgnoreCase(o.optString("mcp")) || o.optBoolean("mcp_ok",false);
        boolean p="OK".equalsIgnoreCase(o.optString("proxy")) || o.optBoolean("proxy_ok",false);
        String ts=o.optString("tunnel","").toLowerCase();
        boolean t=ts.contains("live") || ts.contains("ready") || o.optBoolean("tunnel_ok",false);
        addIndicator("MCP",m); addIndicator("PROXY",p); addIndicator("TUNNEL",t);
    }

    private void clearOutput() {
        getSharedPreferences("bridge",MODE_PRIVATE).edit().putString("stdout","").putString("stderr","").putInt("exit",-1).apply();
    }

    private void refresh() {
        if(busy)return;
        busy=true;
        status.setText("●  CONNECTING  /  PLEASE WAIT  /  re3a");
        status.setTextColor(YELLOW);
        clearOutput();
        McpBridge.run(this,"policy");
        handler.postDelayed(this::readPolicy,900);
    }

    private void readPolicy() {
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        try { JSONObject o=new JSONObject(out); if(o.has("master_lock")) policy=o; } catch(Exception ignored) {}
        clearOutput();
        McpBridge.run(this,"status");
        handler.postDelayed(this::readStatus,900);
    }

    private void readStatus() {
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        String err=getSharedPreferences("bridge",MODE_PRIVATE).getString("stderr","");
        int exit=getSharedPreferences("bridge",MODE_PRIVATE).getInt("exit",-1);
        boolean parsed=false;
        try {
            JSONObject o=new JSONObject(out);
            if(o.has("connected")) {
                parsed=true;
                boolean ok=o.optBoolean("connected");
                status.setText("●  "+(ok?"CONNECTED":"DISCONNECTED")+"  /  MCP CONTROL  /  re3a");
                status.setTextColor(ok?GREEN:RED);
                updateIndicators(o);
            }
        } catch(Exception ignored) {}
        busy=false;
        render();
        if(!parsed && err.length()>0) content.addView(mono("Bridge: "+err,10,RED));
        else if(!parsed && exit!=0) content.addView(mono("Bridge did not return a valid status.",10,RED));
    }

    private void runSet(String id,String n) {
        if(busy)return;
        busy=true;
        status.setText("●  SAVING  /  "+id+" = "+n.toUpperCase()+"  /  re3a");
        status.setTextColor(YELLOW);
        clearOutput();
        McpBridge.run(this,"set",id,n);
        handler.postDelayed(()->{busy=false;refresh();},1100);
    }

    private void runAction(String a,int d) {
        if(busy)return;
        busy=true;
        status.setText("●  "+a.toUpperCase()+"  /  WORKING  /  re3a");
        status.setTextColor(YELLOW);
        clearOutput();
        McpBridge.run(this,a);
        handler.postDelayed(()->{busy=false;refresh();},d);
    }

    private void lockAll() {
        if(busy)return;
        busy=true;
        status.setText("●  LOCKING  /  DENY ALL  /  re3a");
        status.setTextColor(YELLOW);
        clearOutput();
        McpBridge.run(this,"lock");
        handler.postDelayed(()->{busy=false;refresh();},900);
    }

    private void unlockAll() {
        if(busy)return;
        busy=true;
        status.setText("●  UNLOCKING  /  LOCAL CONTROL  /  re3a");
        status.setTextColor(YELLOW);
        clearOutput();
        McpBridge.run(this,"unlock","UNLOCK");
        handler.postDelayed(()->{busy=false;refresh();},900);
    }
}
