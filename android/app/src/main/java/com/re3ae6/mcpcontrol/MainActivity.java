package com.re3ae6.mcpcontrol;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
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
    private boolean busy = false;
    private final String[] groups={"overview","files","git","terminal","network","mcp","device","dangerous"};
    private final String[] labels={"Overview","Files","Git","Terminal","Network","MCP","Device","Dangerous"};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        renderOffline();
    }

    private TextView tv(String s,int size){
        TextView t=new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(Color.rgb(30,41,59));
        t.setPadding(18,10,18,10);
        return t;
    }

    private TextView title(String s){
        TextView t=tv(s,20);
        t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(18,16,18,8);
        return t;
    }

    private Button btn(String s,View.OnClickListener l){
        Button b=new Button(this);
        b.setText(s); b.setTextSize(14); b.setAllCaps(false);
        b.setMinHeight(52); b.setPadding(16,4,16,4);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245,247,250));

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(10,10,10,6);
        header.setBackgroundColor(Color.rgb(17,24,39));

        TextView app=tv("MCP CONTROL",20);
        app.setTextColor(Color.WHITE);
        app.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        app.setPadding(12,8,12,2);
        header.addView(app);

        status=tv("READY  •  offline until connected",13);
        status.setTextColor(Color.rgb(187,247,208));
        status.setPadding(12,2,12,8);
        header.addView(status);

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button connect=btn("Connect / Refresh",v->refresh());
        Button lock=btn("Lock",v->lockAll());
        actions.addView(connect,new LinearLayout.LayoutParams(0,52,1));
        actions.addView(lock,new LinearLayout.LayoutParams(0,52,1));
        header.addView(actions);
        root.addView(header);

        HorizontalScrollView hs=new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabbar=new LinearLayout(this);
        for(int i=0;i<groups.length;i++){
            final String g=groups[i];
            Button b=btn(labels[i],v->{group=g;render();});
            tabbar.addView(b,new LinearLayout.LayoutParams(132,52));
        }
        hs.addView(tabbar);
        root.addView(hs);

        ScrollView sv=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(8,8,8,24);
        sv.addView(content);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void renderOffline(){
        status.setText("READY  •  offline until connected");
        content.removeAllViews();
        content.addView(title("MCP Control"));
        content.addView(tv("Secure local controller • default DENY",16));
        content.addView(tv("Connect is always available in the header.",14));
        content.addView(tv("No Termux command is sent automatically.",14));
        content.addView(tv("Use Connect / Refresh to load the current policy and status.",14));
    }

    private void clearOutput(){
        getSharedPreferences("bridge",MODE_PRIVATE).edit()
            .putString("stdout","").putString("stderr","").putInt("exit",-1).apply();
    }

    private void refresh(){
        if(busy) return;
        busy=true;
        status.setText("CONNECTING  •  reading policy…");
        clearOutput();
        McpBridge.run(this,"policy");
        handler.postDelayed(this::readPolicyThenStatus,900);
    }

    private void readPolicyThenStatus(){
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        try {
            JSONObject o=new JSONObject(out);
            if(o.has("master_lock")) policy=o;
        } catch(Exception ignored){}
        clearOutput();
        McpBridge.run(this,"status");
        handler.postDelayed(this::readStatus,900);
    }

    private void readStatus(){
        String out=getSharedPreferences("bridge",MODE_PRIVATE).getString("stdout","");
        String err=getSharedPreferences("bridge",MODE_PRIVATE).getString("stderr","");
        int exit=getSharedPreferences("bridge",MODE_PRIVATE).getInt("exit",-1);

        if(exit!=0 && out.length()==0){
            status.setText("OFFLINE  •  Termux bridge unavailable");
            content.removeAllViews();
            content.addView(title("Connection problem"));
            content.addView(tv(err.length()>0?err:"No response from Termux.",14));
        } else {
            try{
                JSONObject o=new JSONObject(out);
                if(o.has("connected")) status.setText(
                    (o.optBoolean("connected")?"CONNECTED":"DISCONNECTED")+
                    "  •  MCP="+o.optString("mcp")+
                    "  •  Proxy="+o.optString("proxy")+
                    "  •  Tunnel="+o.optString("tunnel"));
            }catch(Exception ignored){
                status.setText(policy!=null?"POLICY LOADED  •  status unavailable":"OFFLINE  •  no policy received");
            }
        }
        busy=false;
        render();
    }

    private void render(){
        content.removeAllViews();
        if(policy==null){
            content.addView(title("Not connected"));
            content.addView(tv("Tap Connect / Refresh in the header.",16));
            return;
        }

        boolean locked=policy.optBoolean("master_lock",true);
        if("overview".equals(group)){
            content.addView(title(locked?"MASTER LOCK  •  ON":"MASTER LOCK  •  OFF"));
            content.addView(tv(locked?"All capabilities are effectively DENY.":"Per-capability policy is active.",15));
            content.addView(btn("Refresh status",v->refresh()));
            content.addView(btn("Start MCP",v->runAction("start",1500)));
            content.addView(btn("Restart MCP",v->runAction("restart",1800)));
            content.addView(btn(locked?"Unlock controls":"Lock everything",v->{ if(locked) unlockAll(); else lockAll(); }));
            content.addView(tv("Emergency Lock is always available. Unlock is a local app action and is not exposed through MCP.",13));
            return;
        }

        JSONObject caps=policy.optJSONObject("capabilities");
        JSONArray a=caps==null?null:caps.optJSONArray(group);
        if(a==null){content.addView(title("No capabilities"));return;}
        content.addView(title(labels[indexOf(group)]));
        if(locked) content.addView(tv("Master Lock is ON. Unlock controls from Overview.",14));

        for(int i=0;i<a.length();i++){
            JSONObject item=a.optJSONObject(i);
            if(item==null) continue;
            String id=item.optString("id");
            String label=item.optString("label",id);
            String state=item.optString("state","deny");
            Button b=btn(label+"    ["+state.toUpperCase()+"]",v->cycle(id,state));
            b.setEnabled(!locked);
            content.addView(b);
        }
    }

    private int indexOf(String g){
        for(int i=0;i<groups.length;i++) if(groups[i].equals(g)) return i;
        return 0;
    }

    private void cycle(String id,String state){
        String next="deny".equals(state)?"ask":("ask".equals(state)?"allow":"deny");
        runSet(id,next);
    }

    private void runSet(String id,String next){
        busy=true;
        status.setText("SAVING  •  "+id+" → "+next);
        clearOutput();
        McpBridge.run(this,"set",id,next);
        handler.postDelayed(this::refresh,700);
    }

    private void runAction(String action,int delay){
        busy=true;
        status.setText(action.toUpperCase()+"  •  working…");
        clearOutput();
        McpBridge.run(this,action);
        handler.postDelayed(this::refresh,delay);
    }

    private void lockAll(){
        busy=true;
        status.setText("LOCKING  •  all capabilities → DENY");
        clearOutput();
        McpBridge.run(this,"lock");
        handler.postDelayed(this::refresh,700);
    }

    private void unlockAll(){
        busy=true;
        status.setText("UNLOCKING  •  local controller");
        clearOutput();
        McpBridge.run(this,"unlock","UNLOCK");
        handler.postDelayed(this::refresh,700);
    }
}
