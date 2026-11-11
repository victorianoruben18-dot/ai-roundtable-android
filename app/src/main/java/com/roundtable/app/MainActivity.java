package com.roundtable.app;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;

public class MainActivity extends Activity {
    private LinearLayout body;
    private EditText topic, rounds, prompt, reply;
    private CheckBox chat, deep;
    private JSONObject state;
    private static final int LIMIT=12000;
    @Override public void onCreate(Bundle b) { super.onCreate(b); }
    @Override public void onResume() {
        super.onResume();
        state=Store.load(this);
        if("SENDING".equals(state.optString("status")) || "ARMED".equals(state.optString("status"))) {
            Store.put(state,"status","UNCERTAIN"); Store.put(state,"notice","已返回圆桌或操作中断，本次自动授权已取消；请检查是否已发送。");Store.put(state,"lastAutoNotice","返回圆桌，自动授权已取消，请检查是否留下草稿或已发送。"); Store.save(this,state);
        }
        render();
    }
    private void text(String s) { TextView t=new TextView(this); t.setText(s); t.setTextSize(16); t.setPadding(0,12,0,8); t.setTextIsSelectable(true); body.addView(t); }
    private EditText edit(String hint,String value) { EditText e=new EditText(this); e.setHint(hint); e.setText(value); e.setMinLines(2); e.setGravity(android.view.Gravity.TOP); body.addView(e); return e; }
    private void button(String label,boolean enabled,Runnable action) { Button b=new Button(this); b.setText(label); b.setEnabled(enabled); b.setOnClickListener(v->action.run()); body.addView(b); }
    private void message(String s) { new AlertDialog.Builder(this).setMessage(s).setPositiveButton("知道了",null).show(); }
    private boolean persist() { if(Store.save(this,state)) return true; message("保存失败，已取消后续操作。"); return false; }
    private void render() {
        prompt=null; reply=null;
        ScrollView scroll=new ScrollView(this); body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24,32,24,32); scroll.setFillViewport(true); scroll.addView(body); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        text("AI 圆桌 · 0.2.2 诊断版");
        text("每次发送前确认。自动操作未经真机验证。回复必须由你检查完整性。讨论保存在本机，转发会把内容交给所选 AI 服务。复制会覆盖剪贴板。");
        button("无障碍设置（可选；手动流程无需开启）",true,()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        String status=state.optString("status", "NONE");
        boolean terminal=status.equals("NONE") || status.equals("STOPPED") || status.equals("COMPLETE");
        if(terminal) {
            topic=edit("讨论题目","");
            chat=new CheckBox(this); chat.setText("ChatGPT"); chat.setChecked(true); body.addView(chat);
            deep=new CheckBox(this); deep.setText("DeepSeek"); deep.setChecked(true); body.addView(deep);
            text("后续计划：Gemini、Grok、千问、豆包；元宝为可选参与者。当前尚未适配。");
            rounds=edit("轮数（1—5，每轮各参与者发言一次）","2"); rounds.setInputType(InputType.TYPE_CLASS_NUMBER);
            button("开始新讨论",true,this::startSession);
        }
        if(!state.has("topic")) { history(); return; }
        text("题目："+state.optString("topic")+"\n状态："+statusLabel(status)+"\n"+state.optString("notice"));
        if(!state.optString("autoDiagnostics").isEmpty()) {
            text("自动发送诊断（保留自动操作时的记录）\n"+DiagnosticLog.autoReport(state));
            button("复制自动发送诊断",true,()->copyReport("自动发送诊断",DiagnosticLog.autoReport(state)));
        }
        if(!state.optString("readDiagnostics").isEmpty()) {
            text("只读页面诊断（单独采样）\n"+state.optString("readDiagnostics"));
            button("复制只读诊断",true,()->copyReport("只读诊断",state.optString("readDiagnostics")));
        }
        if(state.optString("autoDiagnostics").isEmpty() && state.optString("readDiagnostics").isEmpty() && !state.optString("diagnostics").isEmpty()) {
            text("旧版诊断（请用新版重新采样）\n"+state.optString("diagnostics"));
        }
        if(!terminal) {
            text("当前：第 "+(state.optInt("turn")/state.optJSONArray("participants").length()+1)+" 轮 · "+Store.target(state));
            boolean ready=status.equals("READY");
            prompt=edit("本次要发送的内容（可修改）",state.optString("pending"));
            prompt.setEnabled(ready);
            button("确认并尝试自动发送",ready,()->confirmSend(false));
            button("确认复制问题，转为手动发送",ready,()->confirmSend(true));
            button("打开当前参与者 App",true,()->openTarget());
            button("只读页面诊断（不输入、不发送）",!status.equals("ARMED") && !status.equals("SENDING"),()->{
                if(RoundtableService.instance==null) {message("请先开启无障碍服务。");return;}
                Store.put(state,"readDiagnostics","");Store.put(state,"diagRequested",true);Store.put(state,"capture",false);Store.put(state,"diagDeadline",System.currentTimeMillis()+15000);
                if(persist()) {openTarget();RoundtableService.instance.begin();}
            });
            text("在目标 App 等待回答完成。自动读取仅尝试带明确助手标签的文本，可能失败或读到旧消息。请核对来源、轮次和完整性。");
            boolean waiting=status.equals("WAITING") || status.equals("UNCERTAIN");
            button("尝试读取：打开目标 App 后返回查看候选",waiting,()->{
                Store.put(state,"capture",true); Store.put(state,"captureDeadline",System.currentTimeMillis()+20000); Store.put(state,"candidate","");
                if(persist()) openTarget();
            });
            text("读取候选（未经确认）：\n"+state.optString("candidate","尚无；可直接手动复制回复。"));
            reply=edit("粘贴或填写完整回复",state.optString("replyDraft"));
            reply.setEnabled(waiting);
            button("把候选填入回复框",waiting && !state.optString("candidate").isEmpty(),()->reply.setText(state.optString("candidate")));
            button("我已核对完整回复，保存并准备下一位",waiting,()->{
                String answer=reply.getText().toString().trim(); if(answer.isEmpty()) {message("请先填写回复。");return;}
                new AlertDialog.Builder(this).setMessage("确认这确实是当前参与者对本次问题的完整回答？保存后仍需另行确认下一次发送。").setNegativeButton("返回检查",null).setPositiveButton("确认保存",(d,w)->acceptReply(answer)).show();
            });
            button("暂停",!status.equals("PAUSED"),()->{
                Store.put(state,"resume",ready?"READY":"UNCERTAIN"); Store.put(state,"status","PAUSED"); Store.put(state,"capture",false);Store.put(state,"diagRequested",false);
                saveDrafts(); if(persist()) render();
            });
            button("继续（不会自动发送）",status.equals("PAUSED"),()->{ Store.put(state,"status",state.optString("resume","UNCERTAIN")); if(persist()) render(); });
            button("停止讨论",true,()->new AlertDialog.Builder(this).setMessage("停止后保留记录，不再继续本次讨论。").setNegativeButton("取消",null).setPositiveButton("停止",(d,w)->{Store.put(state,"status","STOPPED"); Store.put(state,"capture",false); Store.put(state,"diagRequested",false); saveDrafts(); if(persist())render();}).show());
        }
        text("本次记录"); records(state); history();
    }
    private void saveDrafts() {
        if(prompt!=null && prompt.isEnabled()) Store.put(state,"pending",prompt.getText().toString());
        if(reply!=null && reply.isEnabled()) Store.put(state,"replyDraft",reply.getText().toString());
    }
    @Override protected void onPause() {
        super.onPause();
        if(state==null || !state.has("topic"))return;
        // Merge only editor drafts; never overwrite newer service status or diagnostics.
        JSONObject latest=Store.load(this);
        if(latest.optLong("created")!=state.optLong("created") || latest.optInt("turn")!=state.optInt("turn"))return;
        if(prompt!=null && prompt.isEnabled() && "READY".equals(latest.optString("status")))Store.put(latest,"pending",prompt.getText().toString());
        if(reply!=null && reply.isEnabled())Store.put(latest,"replyDraft",reply.getText().toString());
        Store.save(this,latest);
    }
    private void startSession() {
        String t=topic.getText().toString().trim(); int n;
        try { n=Integer.parseInt(rounds.getText().toString()); } catch(NumberFormatException e) { n=0; }
        if(t.isEmpty() || n<1 || n>5 || (!chat.isChecked() && !deep.isChecked())) {message("请填写题目，选择参与者，并输入 1—5 轮。");return;}
        JSONObject s=new JSONObject(); JSONArray p=new JSONArray(); if(chat.isChecked())p.put("ChatGPT"); if(deep.isChecked())p.put("DeepSeek");
        Store.put(s,"topic",t);Store.put(s,"participants",p);Store.put(s,"rounds",n);Store.put(s,"turn",0);Store.put(s,"records",new JSONArray());Store.put(s,"status","READY");Store.put(s,"created",System.currentTimeMillis());Store.put(s,"pending",Store.prompt(s));
        if(Store.archiveAndStart(this,s)) { state=s; render(); } else message("保存失败。");
    }
    private void confirmSend(boolean manual) {
        String p=prompt.getText().toString().trim();
        if(p.isEmpty() || p.length()>LIMIT) {message("请输入不超过 "+LIMIT+" 个字符的内容；过长时请手动整理，系统不会静默截断。");return;}
        final long expectedSession=state.optLong("created"); final int expectedTurn=state.optInt("turn");
        new AlertDialog.Builder(this).setTitle("发送给 "+Store.target(state)).setMessage("请先确认目标 App 已登录，并已打开正确聊天。ChatGPT 请先选择“聊天”页，保持输入框为空。\n\n"+p).setNegativeButton("取消",null).setPositiveButton(manual?"确认复制":"确认尝试发送",(d,w)->{
            JSONObject current=Store.load(this);
            if(!Store.mayAuthorize(current,expectedSession,expectedTurn)) {message("状态已改变，已取消重复或过期授权。");state=current;render();return;}
            state=current;
            if(!manual && RoundtableService.instance==null) { message("请先开启无障碍服务，或使用手动流程。");return; }
            Intent launch=getPackageManager().getLaunchIntentForPackage(Store.pkg(Store.target(state)));
            if(!manual && launch==null) {message("未找到目标 App，请使用手动流程。");return;}
            Store.put(state,"autoDiagnostics","");Store.put(state,"lastAutoNotice","");Store.put(state,"autoFillAttempted",false);Store.put(state,"autoClickAttempted",false);Store.put(state,"diagRequested",false);Store.put(state,"diagnostics","");Store.put(state,"sendPhase","等待页面稳定");Store.put(state,"pending",p);Store.put(state,"status",manual?"WAITING":"ARMED");Store.put(state,"deadline",System.currentTimeMillis()+15000);Store.put(state,"candidate","");Store.put(state,"capture",false);
            Store.put(state,"notice",manual?"请手动粘贴并发送一次，再将完整回复粘贴回来。":"已授权一次发送，15 秒内有效；识别失败时不会重试。");
            if(!persist())return;
            if(manual) { ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("圆桌问题",p)); render(); }
            else { try {startActivity(launch);if(RoundtableService.instance!=null)RoundtableService.instance.begin();}catch(RuntimeException e){Store.put(state,"status","UNCERTAIN");persist();render();} }
        }).show();
    }
    private void copyReport(String label,String value) {
        ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label,value));
    }
    private void openTarget() {
        Intent i=getPackageManager().getLaunchIntentForPackage(Store.pkg(Store.target(state)));
        if(i==null) message("未找到目标 App，请手动打开安装的应用。"); else try {startActivity(i);}catch(RuntimeException e){message("无法打开，请手动切换。");}
    }
    private void acceptReply(String answer) {
        JSONArray r=state.optJSONArray("records"); JSONObject item=new JSONObject();
        Store.put(item,"participant",Store.target(state));Store.put(item,"round",state.optInt("turn")/state.optJSONArray("participants").length()+1);Store.put(item,"prompt",state.optString("pending"));Store.put(item,"reply",answer);Store.put(item,"time",System.currentTimeMillis());r.put(item);
        int turn=state.optInt("turn")+1;Store.put(state,"turn",turn);Store.put(state,"status",turn>=state.optInt("rounds")*state.optJSONArray("participants").length()?"COMPLETE":"READY");Store.put(state,"pending",Store.prompt(state));Store.put(state,"replyDraft","");Store.put(state,"candidate","");Store.put(state,"capture",false);Store.put(state,"diagRequested",false);Store.put(state,"diagnostics","");Store.put(state,"autoDiagnostics","");Store.put(state,"readDiagnostics","");Store.put(state,"notice","回复已保存。");
        if(persist())render();
    }
    private void records(JSONObject s) {JSONArray a=s.optJSONArray("records"); if(a==null)return;for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);text("第 "+r.optInt("round")+" 轮 · "+r.optString("participant")+"\n"+r.optString("reply"));}}
    private void history() { JSONArray a=Store.history(this); text("历史讨论："+a.length()+" 次"); for(int i=a.length()-1;i>=0;i--){JSONObject s=a.optJSONObject(i);text(s.optString("topic"));records(s);} }
    private String statusLabel(String s) {switch(s){case "READY":return "等待你确认下一次发送";case "ARMED":return "已授权一次发送";case "SENDING":return "尝试发送中";case "WAITING":return "等待完整回复";case "UNCERTAIN":return "已暂停自动操作，请人工检查";case "PAUSED":return "已暂停";case "STOPPED":return "已停止";case "COMPLETE":return "讨论完成";default:return s;}}
}
