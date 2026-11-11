package com.roundtable.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

public class RoundtableService extends AccessibilityService {
    static RoundtableService instance;
    @Override protected void onServiceConnected() { instance=this; }
    @Override public void onDestroy() { instance=null; super.onDestroy(); }
    @Override public void onInterrupt() {
        JSONObject s=Store.load(this);
        if("ARMED".equals(s.optString("status")) || "SENDING".equals(s.optString("status"))) fail(s,"无障碍操作被中断，请人工检查发送结果。");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        try { handleEvent(event); }
        catch(RuntimeException error) {
            JSONObject s=Store.load(this);
            if("ARMED".equals(s.optString("status")) || "SENDING".equals(s.optString("status"))) fail(s,"界面读取发生错误，已暂停，请人工检查。");
        }
    }
    private void handleEvent(AccessibilityEvent event) {
        JSONObject s=Store.load(this);
        if (s.optBoolean("capture") && ("WAITING".equals(s.optString("status")) || "UNCERTAIN".equals(s.optString("status")))) {
            if (System.currentTimeMillis()>s.optLong("captureDeadline")) {Store.put(s,"capture",false);Store.save(this,s);return;}
            String expected=Store.pkg(Store.target(s));
            AccessibilityNodeInfo page=getRootInActiveWindow();
            if(page!=null && expected.contentEquals(page.getPackageName())) {
                List<String> candidates=new ArrayList<>(); collectReplies(page,candidates,0);
                Store.put(s,"capture",false);
                Store.put(s,"candidate",candidates.isEmpty()?"":candidates.get(candidates.size()-1));
                Store.put(s,"notice",candidates.isEmpty()?"未识别到带明确助手标签的回复，请手动复制；自动流程已暂停。":"已读取候选；可能不完整或属于旧消息，请人工核对后再保存。");
                Store.save(this,s);
            }
            return;
        }
        if (!"ARMED".equals(s.optString("status"))) return;
        if (System.currentTimeMillis() > s.optLong("deadline")) { fail(s,"授权已超时，请回到圆桌重新确认。"); return; }
        String pkg=Store.pkg(Store.target(s));
        if (event.getPackageName()==null || !pkg.contentEquals(event.getPackageName())) return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null || root.getPackageName()==null || !pkg.contentEquals(root.getPackageName())) { fail(s,"无法确认当前页面。"); return; }
        List<AccessibilityNodeInfo> inputs=new ArrayList<>(), sends=new ArrayList<>();
        boolean[] blocked={false}; inspect(root, inputs, sends, blocked, 0);
        if(blocked[0] || inputs.size()!=1 || sends.size()!=1) { fail(s,"页面无法安全识别（可能需要登录、验证或处理弹窗），请检查并使用手动流程。"); return; }
        AccessibilityNodeInfo input=inputs.get(0), send=sends.get(0);
        if ((input.getText()!=null && input.getText().length()>0) || !pkg.contentEquals(input.getPackageName()) || !pkg.contentEquals(send.getPackageName())) {
            fail(s,"输入框已有草稿或控件来源不明，请人工检查，避免覆盖或重复发送。");return;
        }
        // Persist before any interaction: an interrupted attempt is never retried automatically.
        Store.put(s,"status","SENDING");
        if(!Store.save(this,s)) return;
        Bundle args=new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,s.optString("pending"));
        boolean filled=input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
        input.refresh();
        CharSequence value=input.getText();
        if(!filled || value==null || !s.optString("pending").contentEquals(value)) { fail(s,"无法确认输入内容；请检查目标 App，避免重复发送。"); return; }
        // Let the target update its send button once; never retry the click.
        final long session=s.optLong("created"); final int turn=s.optInt("turn");
        new android.os.Handler(getMainLooper()).postDelayed(()->{
            JSONObject latest=Store.load(this);
            if(!"SENDING".equals(latest.optString("status")) || latest.optLong("created")!=session || latest.optInt("turn")!=turn) return;
            try {
                AccessibilityNodeInfo active=getRootInActiveWindow();
                if(active==null || !pkg.contentEquals(active.getPackageName()) || !send.refresh() || !send.isEnabled() || !send.isVisibleToUser() || !send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    fail(latest,"发送结果不确定，请检查目标 App 后使用手动流程，切勿重复发送。"); return;
                }
                Store.put(latest,"status","WAITING"); Store.put(latest,"notice","已尝试点击发送；请在目标 App 检查是否发出，等待完整回答后手动复制并确认。"); Store.save(this,latest);
            } catch(RuntimeException error) { fail(latest,"操作中断，发送结果不确定，请人工检查。"); }
        },400);
    }
    private void collectReplies(AccessibilityNodeInfo n,List<String> out,int depth) {
        if(n==null || depth>40 || !n.isVisibleToUser())return;
        String label=n.getContentDescription()==null?"":n.getContentDescription().toString().trim().toLowerCase(Locale.ROOT);
        if(label.equals("assistant") || label.equals("assistant response") || label.equals("chatgpt said:") || label.equals("助手回复")) {
            StringBuilder b=new StringBuilder(); collectText(n,b,0);
            if(b.length()>0)out.add(b.toString());
            return;
        }
        for(int i=0;i<n.getChildCount();i++)collectReplies(n.getChild(i),out,depth+1);
    }
    private void collectText(AccessibilityNodeInfo n,StringBuilder b,int depth) {
        if(n==null || depth>40 || n.isEditable() || !n.isVisibleToUser() || b.length()>50000)return;
        if(n.getText()!=null)b.append(n.getText()).append("\n");
        for(int i=0;i<n.getChildCount();i++)collectText(n.getChild(i),b,depth+1);
    }
    private void fail(JSONObject s,String why) { Store.put(s,"status","UNCERTAIN"); Store.put(s,"notice",why); Store.save(this,s); }
    private void inspect(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> inputs,List<AccessibilityNodeInfo> sends,boolean[] blocked,int depth) {
        if(n==null || depth>40) return;
        if(n.isVisibleToUser()) {
            String label=((n.getText()==null?"":n.getText().toString())+" "+(n.getContentDescription()==null?"":n.getContentDescription().toString())).trim().toLowerCase(Locale.ROOT);
            if(label.equals("log in") || label.equals("sign in") || label.equals("登录") || label.contains("验证码") || label.contains("captcha") || label.contains("verify you are human")) blocked[0]=true;
            if(n.isEditable() && n.isEnabled()) inputs.add(n);
            if(n.isClickable() && (label.equals("send") || label.equals("send message") || label.equals("发送") || label.equals("发送消息"))) sends.add(n);
        }
        for(int i=0;i<n.getChildCount();i++) inspect(n.getChild(i),inputs,sends,blocked,depth+1);
    }
}
