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
    private LinearLayout content, indicatorRow;
    private TextView status, masterState;
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

        status=text("●  READY  •  NOT CONNECTED",13,Color.rgb(250,204,21));
        status.setTypeface(Typeface.DEFAULT,Typeface.BOLD); status.setPadding(dp(10),0,dp(10),dp(8));
        header.addView(status);

        indicatorRow=new LinearLayout(this); indicatorRow.setOrientation(LinearLayout.HORIZONTAL);
        indicatorRow.addView(dot("MCP",Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        indicatorRow.addView(dot("PROXY",Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        indicatorRow.addView(dot("TUNNEL",Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        header.addView(indicatorRow);

        LinearLayout actions=new LinearLayout(this);
        Button connect=button("↻  Connect / Refresh",v->refresh());
        Button lock=button("🔒  Lock",v->lockAll());
        actions.addView(connect,new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(lock,new LinearLayout.LayoutParams(0,dp(52),1));
        header.addView(actions);
        root.addView(header);

        HorizontalScrollView tabs=new HorizontalScrollView(this); tabs.setHorizontalScrollBarEnabled(false);
        LinearLayout bar=new LinearLayout(this);
        for(int i=0;i<groups.length;i++){
            final String g=groups[i]; Button b=button(labels[i],v->{group=g;render();});
            bar.addView(b,new LinearLayout.LayoutParams(dp(116),dp(50)));
        }
        tabs.addView(bar); root.addView(tabs);

        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8),0,dp(8),dp(24)); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void renderOffline(){
        status.setText("●  READY  •  NOT CONNECTED"); status.setTextColor(Color.rgb(250,204,21));
        content.removeAllViews();
        content.addView(title("System Overview"));
        content.addView(text("Secure local controller",15,Color.rgb(71,85,105)));
        content.addView(card("MASTER LOCK","ON  •  EFFECTIVE DENY",Color.rgb(239,68,68)));
        content.addView(text("MCP, Proxy and Tunnel status will appear here after Connect / Refresh.",14,Color.rgb(71,85,105)));
        content.addView(button("Connect / Refresh",v->refresh()));
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
        if(busy)return; busy=true; status.setText("●  CONNECTING  •  PLEASE WAIT"); status.setTextColor(Color.rgb(250,204,21));
        clearOutput(); McpBridge.run(this,"policy"); handler.postDelayed(this::readPolicy,900);
    }

    private void readPolicy(){
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        try{JSONObject o=new JSONObject(out); if(o.has("master_lock"))policy=o;}catch(Exception ignored){}
        clearOutput(); McpBridge.run(this,"status"); handler.postDelayed(this::readStatus,900);
    }

    private void readStatus(){
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        String err=getSharedPreferences("bridge",MODE_PRIVATE).getString("stderr","");
        int exit=getSharedPreferences("bridge",MODE_PRIVATE).getInt("exit",-1);
        boolean ok=false;
        try{
            JSONObject o=new JSONObject(out);
            if(o.has("connected")){
                ok=o.optBoolean("connected");
                status.setText("●  "+(ok?"CONNECTED":"DISCONNECTED")+"  •  MCP CONTROL");
                status.setTextColor(ok?Color.rgb(34,197,94):Color.rgb(239,68,68));
                updateIndicators(o);
            }
        }catch(Exception ignored){}
        if(!ok && policy==null && err.length()>0) content.addView(text(err,13,Color.rgb(185,28,28)));
        busy=false; render();
    }

    private void updateIndicators(JSONObject o){
        indicatorRow.removeAllViews();
        boolean m=o.optBoolean("mcp_ok",o.optBoolean("connected",false));
        boolean p=o.optBoolean("proxy_ok",false);
        boolean t=o.optBoolean("tunnel_ok",false);
        indicatorRow.addView(dot("MCP  "+(m?"OK":"DOWN"),m?Color.rgb(34,197,94):Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        indicatorRow.addView(dot("PROXY  "+(p?"OK":"DOWN"),p?Color.rgb(34,197,94):Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
        indicatorRow.addView(dot("TUNNEL  "+(t?"OK":"DOWN"),t?Color.rgb(34,197,94):Color.rgb(239,68,68)),new LinearLayout.LayoutParams(0,dp(38),1));
    }

    private void render(){
        content.removeAllViews();
        if(policy==null){renderOffline();return;}
        boolean locked=policy.optBoolean("master_lock",true);
        if("overview".equals(group)){
            content.addView(title("System Overview"));
            content.addView(card("MASTER LOCK",locked?"ON  •  EFFECTIVE DENY":"OFF  •  POLICY ACTIVE",locked?Color.rgb(239,68,68):Color.rgb(34,197,94)));
            content.addView(button("↻  Refresh status",v->refresh()));
            content.addView(button("▶  Start MCP",v->runAction("start",1600)));
            content.addView(button("↻  Restart MCP",v->runAction("restart",1900)));
            content.addView(button(locked?"🔓  Unlock Controls":"🔒  Lock Everything",v->{if(locked)unlockAll();else lockAll();}));
            content.addView(text("Default policy: DENY. Changes are controlled locally through this app.",13,Color.rgb(71,85,105)));
            return;
        }
        JSONObject caps=policy.optJSONObject("capabilities"); JSONArray a=caps==null?null:caps.optJSONArray(group);
        content.addView(title(labels[indexOf(group)]));
        if(a==null){content.addView(text("No capabilities in this group.",15,Color.rgb(100,116,139)));return;}
        if(locked)content.addView(card("MASTER LOCK","ON  •  CONTROLS DISABLED",Color.rgb(239,68,68)));
        for(int i=0;i<a.length();i++){
            JSONObject x=a.optJSONObject(i); if(x==null)continue;
            String id=x.optString("id"), label=x.optString("label",id), state=x.optString("state","deny");
            Button b=button(label+"     ["+state.toUpperCase()+"]",v->cycle(id,state)); b.setEnabled(!locked); content.addView(b);
        }
    }

    private int indexOf(String g){for(int i=0;i<groups.length;i++)if(groups[i].equals(g))return i;return 0;}
    private void cycle(String id,String state){String n="deny".equals(state)?"ask":("ask".equals(state)?"allow":"deny");runSet(id,n);}
    private void runSet(String id,String n){busy=true;status.setText("●  SAVING  •  "+id);clearOutput();McpBridge.run(this,"set",id,n);handler.postDelayed(this::refresh,800);}
    private void runAction(String a,int d){busy=true;status.setText("●  "+a.toUpperCase()+"  •  WORKING");clearOutput();McpBridge.run(this,a);handler.postDelayed(this::refresh,d);}
    private void lockAll(){busy=true;status.setText("●  LOCKING  •  DENY ALL");clearOutput();McpBridge.run(this,"lock");handler.postDelayed(this::refresh,800);}
    private void unlockAll(){busy=true;status.setText("●  UNLOCKING  •  LOCAL CONTROL");clearOutput();McpBridge.run(this,"unlock","UNLOCK");handler.postDelayed(this::refresh,800);}
}
