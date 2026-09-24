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
    private TextView status;
    private JSONObject policy;
    private String group="overview";
    private boolean busy=false;
    private final String[] groups={"overview","files","git","terminal","network","mcp","device","dangerous"};
    private final String[] labels={"Overview","Files","Git","Terminal","Network","MCP","Device","Dangerous"};

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int size,int color){
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(dp(14),dp(7),dp(14),dp(7)); return t;
    }
    private TextView title(String s){
        TextView t=text(s,21,Color.rgb(15,23,42)); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(dp(14),dp(18),dp(14),dp(5)); return t;
    }
    private Button button(String s,View.OnClickListener l){
        Button b=new Button(this); b.setText(s); b.setTextSize(14); b.setAllCaps(false);
        b.setMinHeight(dp(48)); b.setOnClickListener(l); return b;
    }
    private TextView dot(String label,int color){
        TextView d=text("●  "+label,13,color); d.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return d;
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b); buildUi(); renderOffline();
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246,248,251));

        LinearLayout header=new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(10),dp(10),dp(10),dp(8)); header.setBackgroundColor(Color.rgb(15,23,42));

        TextView brand=text("MCP  CONTROL",22,Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD); brand.setPadding(dp(10),dp(4),dp(10),0);
        header.addView(brand);

        status=text("●  READY  •  DISCONNECTED  •  re3a",13,Color.rgb(250,204,21));
        status.setTypeface(Typeface.DEFAULT,Typeface.BOLD); status.setPadding(dp(10),0,dp(10),dp(8));
        header.addView(status);

        indicatorRow=new LinearLayout(this); indicatorRow.setOrientation(LinearLayout.HORIZONTAL);
        setInitialIndicators();
        header.addView(indicatorRow);

        LinearLayout actions=new LinearLayout(this);
        Button connect=button("↻  Connect / Refresh",v->refresh());
        Button lock=button("🔒  Lock",v->lockAll());
        actions.addView(connect,new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(lock,new LinearLayout.LayoutParams(0,dp(52),1));
        header.addView(actions);
        root.addView(header);

        HorizontalScrollView tabs=new HorizontalScrollView(this); tabs.setHorizontalScrollBarEnabled(false);
        tabBar=new LinearLayout(this);
        for(int i=0;i<groups.length;i++){
            final String g=groups[i];
            Button b=button(labels[i],v->{group=g;render();});
            b.setTag(g);
            tabBar.addView(b,new LinearLayout.LayoutParams(dp(116),dp(50)));
        }
        tabs.addView(tabBar); root.addView(tabs);

        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8),0,dp(8),dp(24)); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        highlightTab();
    }

    private void setInitialIndicators(){
        indicatorRow.removeAllViews();
        indicatorRow.addView(dot("MCP",Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        indicatorRow.addView(dot("PROXY",Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        indicatorRow.addView(dot("TUNNEL",Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
    }

    private void highlightTab(){
        if(tabBar==null)return;
        for(int i=0;i<tabBar.getChildCount();i++){
            View v=tabBar.getChildAt(i);
            boolean selected=groups[i].equals(group);
            v.setAlpha(selected?1.0f:0.65f);
        }
    }

    private void renderOffline(){
        status.setText("●  READY  •  DISCONNECTED  •  re3a");
        status.setTextColor(Color.rgb(250,204,21));
        content.removeAllViews();
        content.addView(title("System Overview"));
        content.addView(text("Secure local controller",15,Color.rgb(71,85,105)));
        content.addView(card("MASTER LOCK","ON  •  EFFECTIVE DENY",Color.rgb(239,68,68)));
        content.addView(text("Controls stay visible while the bridge is offline. Connect to load live policy and status.",14,Color.rgb(71,85,105)));
        content.addView(button("↻  Connect / Refresh",v->refresh()));
        highlightTab();
    }

    private TextView card(String name,String value,int color){
        TextView t=text(name+"\n"+value,15,Color.rgb(15,23,42)); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1),color);
        t.setBackground(bg); t.setPadding(dp(16),dp(14),dp(16),dp(14));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(74)); p.setMargins(0,dp(7),0,dp(7)); t.setLayoutParams(p); return t;
    }

    private void clearOutput(){
        getSharedPreferences("bridge",MODE_PRIVATE).edit().putString("stdout","").putString("stderr","").putInt("exit",-1).apply();
    }

    private void refresh(){
        if(busy)return;
        busy=true;
        status.setText("●  CONNECTING  •  PLEASE WAIT  •  re3a");
        status.setTextColor(Color.rgb(250,204,21));
        clearOutput();
        McpBridge.run(this,"policy");
        handler.postDelayed(this::readPolicy,900);
    }

    private void readPolicy(){
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        try{
            JSONObject o=new JSONObject(out);
            if(o.has("master_lock"))policy=o;
        }catch(Exception ignored){}
        clearOutput();
        McpBridge.run(this,"status");
        handler.postDelayed(this::readStatus,900);
    }

    private void readStatus(){
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        String err=getSharedPreferences("bridge",MODE_PRIVATE).getString("stderr","");
        int exit=getSharedPreferences("bridge",MODE_PRIVATE).getInt("exit",-1);
        boolean parsed=false;
        try{
            JSONObject o=new JSONObject(out);
            if(o.has("connected")){
                parsed=true;
                boolean ok=o.optBoolean("connected");
                status.setText("●  "+(ok?"CONNECTED":"DISCONNECTED")+"  •  MCP CONTROL  •  re3a");
                status.setTextColor(ok?Color.rgb(34,197,94):Color.rgb(239,68,68));
                updateIndicators(o);
            }
        }catch(Exception ignored){}
        busy=false;
        render();
        if(!parsed && err.length()>0){
            content.addView(text("Bridge: "+err,13,Color.rgb(185,28,28)));
        }else if(!parsed && exit!=0){
            content.addView(text("Bridge did not return a valid status.",13,Color.rgb(185,28,28)));
        }
    }

    private void updateIndicators(JSONObject o){
        indicatorRow.removeAllViews();
        boolean m="OK".equalsIgnoreCase(o.optString("mcp")) || o.optBoolean("mcp_ok",false);
        boolean p="OK".equalsIgnoreCase(o.optString("proxy")) || o.optBoolean("proxy_ok",false);
        String ts=o.optString("tunnel","").toLowerCase();
        boolean t=ts.contains("live") || ts.contains("ready") || o.optBoolean("tunnel_ok",false);
        indicatorRow.addView(dot("MCP  "+(m?"OK":"DOWN"),m?Color.rgb(34,197,94):Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        indicatorRow.addView(dot("PROXY  "+(p?"OK":"DOWN"),p?Color.rgb(34,197,94):Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        indicatorRow.addView(dot("TUNNEL  "+(t?"OK":"DOWN"),t?Color.rgb(34,197,94):Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
    }

    private void render(){
        content.removeAllViews();
        if(policy==null){renderOffline();return;}
        boolean locked=policy.optBoolean("master_lock",true);
        highlightTab();
        if("overview".equals(group)){
            content.addView(title("System Overview"));
            content.addView(card("MASTER LOCK",locked?"ON  •  EFFECTIVE DENY":"OFF  •  POLICY ACTIVE",locked?Color.rgb(239,68,68):Color.rgb(34,197,94)));
            content.addView(button("↻  Refresh status",v->refresh()));
            content.addView(button("▶  Start MCP",v->runAction("start",1600)));
            content.addView(button("↻  Restart MCP",v->runAction("restart",1900)));
            content.addView(button(locked?"🔓  Unlock Controls":"🔒  Lock Everything",v->{if(locked)unlockAll();else lockAll();}));
            content.addView(text("Default policy: DENY. Each capability below is independently controlled.",13,Color.rgb(71,85,105)));
            return;
        }

        JSONObject caps=policy.optJSONObject("capabilities");
        JSONArray a=caps==null?null:caps.optJSONArray(group);
        content.addView(title(labels[indexOf(group)]));
        content.addView(text("Each permission is independent. Select exactly one: DENY, ASK, or ALLOW.",13,Color.rgb(71,85,105)));
        if(locked)content.addView(card("MASTER LOCK","ON  •  ALL CAPABILITIES EFFECTIVELY DENIED",Color.rgb(239,68,68)));
        if(a==null){content.addView(text("No capabilities in this group.",15,Color.rgb(100,116,139)));return;}

        for(int i=0;i<a.length();i++){
            JSONObject x=a.optJSONObject(i);
            if(x==null)continue;
            addCapabilityRow(x,locked);
        }
    }

    private void addCapabilityRow(JSONObject x,boolean locked){
        String id=x.optString("id");
        String label=x.optString("label",id);
        String description=x.optString("description","");
        String state=x.optString("state","deny");

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12),dp(10),dp(12),dp(10));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1),Color.rgb(203,213,225));
        box.setBackground(bg);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);
        bp.setMargins(0,dp(6),0,dp(6)); box.setLayoutParams(bp);

        TextView name=text(label,15,Color.rgb(15,23,42));
        name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        box.addView(name);
        if(!description.isEmpty())box.addView(text(description,12,Color.rgb(100,116,139)));

        TextView idText=text(id,11,Color.rgb(100,116,139));
        box.addView(idText);

        LinearLayout choices=new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        addStateButton(choices,"DENY","deny",id,state,locked);
        addStateButton(choices,"ASK","ask",id,state,locked);
        addStateButton(choices,"ALLOW","allow",id,state,locked);
        box.addView(choices);
        content.addView(box);
    }

    private void addStateButton(LinearLayout row,String label,String target,String id,String current,boolean locked){
        Button b=button((target.equals(current)?"● ":"")+label,v->runSet(id,target));
        b.setEnabled(!locked && !busy);
        b.setTextColor(target.equals(current)?Color.rgb(15,23,42):Color.rgb(71,85,105));
        row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));
    }

    private int indexOf(String g){for(int i=0;i<groups.length;i++)if(groups[i].equals(g))return i;return 0;}

    private void runSet(String id,String n){
        if(busy)return;
        busy=true;
        status.setText("●  SAVING  •  "+id+" = "+n.toUpperCase()+"  •  re3a");
        status.setTextColor(Color.rgb(250,204,21));
        clearOutput();
        McpBridge.run(this,"set",id,n);
        handler.postDelayed(()->{busy=false;refresh();},1100);
    }

    private void runAction(String a,int d){
        if(busy)return;
        busy=true;
        status.setText("●  "+a.toUpperCase()+"  •  WORKING  •  re3a");
        status.setTextColor(Color.rgb(250,204,21));
        clearOutput();
        McpBridge.run(this,a);
        handler.postDelayed(()->{busy=false;refresh();},d);
    }

    private void lockAll(){
        if(busy)return;
        busy=true;
        status.setText("●  LOCKING  •  DENY ALL  •  re3a");
        status.setTextColor(Color.rgb(250,204,21));
        clearOutput();
        McpBridge.run(this,"lock");
        handler.postDelayed(()->{busy=false;refresh();},900);
    }

    private void unlockAll(){
        if(busy)return;
        busy=true;
        status.setText("●  UNLOCKING  •  LOCAL CONTROL  •  re3a");
        status.setTextColor(Color.rgb(250,204,21));
        clearOutput();
        McpBridge.run(this,"unlock","UNLOCK");
        handler.postDelayed(()->{busy=false;refresh();},900);
    }
}
