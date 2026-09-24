package com.re3ae6.mcpcontrol;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
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
    private LinearLayout content;
    private TextView status;
    private JSONObject policy;
    private String group = "overview";
    private final String[] groups={"overview","files","git","terminal","network","mcp","device","dangerous"};
    private final String[] labels={"Overview","Files","Git","Terminal","Network","MCP","Device","Dangerous"};

    @Override public void onCreate(Bundle b){ super.onCreate(b); buildUi(); refresh(); }

    private TextView tv(String s,int size){
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setPadding(18,12,18,12); return t;
    }
    private Button btn(String s,View.OnClickListener l){
        Button b=new Button(this); b.setText(s); b.setOnClickListener(l); return b;
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        status=tv("MCP CONTROL",18); root.addView(status);
        HorizontalScrollView hs=new HorizontalScrollView(this);
        LinearLayout tabbar=new LinearLayout(this);
        for(int i=0;i<groups.length;i++){ final String g=groups[i]; Button b=btn(labels[i],v->{group=g;render();}); tabbar.addView(b); }
        hs.addView(tabbar); root.addView(hs);
        ScrollView sv=new ScrollView(this); content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); sv.addView(content);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }

    private void refresh(){
        McpBridge.run(this,"status"); McpBridge.run(this,"policy");
        handler.postDelayed(this::read,700);
    }

    private void read(){
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        if(out.isEmpty()){ render(); return; }
        try{
            JSONObject o=new JSONObject(out);
            if(o.has("master_lock")) policy=o;
            if(o.has("connected")) status.setText("MCP CONTROL  •  "+
                (o.optBoolean("connected")?"CONNECTED":"DISCONNECTED")+
                "   MCP="+o.optString("mcp")+"   Proxy="+o.optString("proxy")+
                "   Tunnel="+o.optString("tunnel"));
        }catch(Exception ignored){}
        render();
    }

    private void render(){
        content.removeAllViews();
        if(policy==null){content.addView(tv("Waiting for Termux bridge…",16));return;}
        boolean locked=policy.optBoolean("master_lock",true);
        if("overview".equals(group)){
            content.addView(tv(locked?"MASTER LOCK: ON  •  effective DENY":"MASTER LOCK: OFF",20));
            content.addView(btn("Refresh status",v->refresh()));
            content.addView(btn("Start MCP",v->{McpBridge.run(this,"start");refresh();}));
            content.addView(btn("Restart MCP",v->{McpBridge.run(this,"restart");refresh();}));
            content.addView(btn("Lock everything",v->{McpBridge.run(this,"lock");refresh();}));
            content.addView(btn("Audit",v->{McpBridge.run(this,"audit");refresh();}));
            content.addView(tv("Default DENY. No Android device permissions are requested.",14));
            return;
        }
        JSONObject caps=policy.optJSONObject("capabilities");
        JSONArray a=caps==null?null:caps.optJSONArray(group);
        if(a==null){content.addView(tv("No capabilities.",16));return;}
        for(int i=0;i<a.length();i++){
            JSONObject item=a.optJSONObject(i); if(item==null)continue;
            String id=item.optString("id"), label=item.optString("label",id), state=item.optString("state","deny");
            Button b=btn(label+"   ["+state.toUpperCase()+"]",v->cycle(id,state));
            b.setEnabled(!locked); content.addView(b);
        }
    }

    private void cycle(String id,String state){
        String next="deny".equals(state)?"ask":("ask".equals(state)?"allow":"deny");
        McpBridge.run(this,"set",id,next); handler.postDelayed(this::refresh,500);
    }
}
