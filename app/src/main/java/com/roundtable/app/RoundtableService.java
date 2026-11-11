package com.roundtable.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

public class RoundtableService extends AccessibilityService {
    static RoundtableService instance;
    private final Handler handler=new Handler(android.os.Looper.getMainLooper());
    private Attempt attempt;
    private final Runnable poll=this::tickSafely;
    private static final long STABLE_MS=500;

    @Override protected void onServiceConnected() { instance=this; begin(); }
    @Override public void onDestroy() { interruptAttempt(); instance=null; super.onDestroy(); }
    @Override public void onInterrupt() { interruptAttempt(); }
    private void interruptAttempt() {
        handler.removeCallbacks(poll); attempt=null;
        JSONObject s=Store.load(this);
        if("ARMED".equals(s.optString("status")) || "SENDING".equals(s.optString("status"))) fail(s,"无障碍操作被中断，请人工检查发送结果。");
        Store.put(s,"diagRequested",false); Store.save(this,s);
    }
    /** Starts a bounded inspection loop. Partial writes from a previous process are never resumed. */
    void begin() {
        JSONObject s=Store.load(this);
        boolean auto="ARMED".equals(s.optString("status"));
        boolean diag=s.optBoolean("diagRequested") && !"SENDING".equals(s.optString("status")) && !auto;
        if(!auto && !diag)return;
        if(auto && (s.optString("pending").trim().isEmpty() || s.optString("pending").length()>12000)) {fail(s,"待发送内容为空或超过上限，已取消授权。");return;}
        if(attempt!=null && attempt.matches(s) && attempt.diagnostic==diag)return;
        handler.removeCallbacks(poll);
        attempt=new Attempt(s,diag); handler.postDelayed(poll,250);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            JSONObject s=Store.load(this);
            if(s.optBoolean("capture") && ("WAITING".equals(s.optString("status")) || "UNCERTAIN".equals(s.optString("status")))) {
                if(System.currentTimeMillis()>s.optLong("captureDeadline")) { Store.put(s,"capture",false);Store.save(this,s);return; }
                AccessibilityNodeInfo page=getRootInActiveWindow();
                if(page!=null && Store.pkg(Store.target(s)).contentEquals(page.getPackageName())) {
                    List<String> candidates=new ArrayList<>();collectReplies(page,candidates,0);
                    Store.put(s,"capture",false);Store.put(s,"candidate",candidates.isEmpty()?"":candidates.get(candidates.size()-1));
                    Store.put(s,"notice",candidates.isEmpty()?"未识别到带明确助手标签的回复，请手动复制。":"已读取候选；可能不完整或属于旧消息，请核对后再保存。");Store.save(this,s);
                }
                return;
            }
            begin();
        } catch(RuntimeException error) { handleError(); }
    }
    private void tickSafely() {
        try { tick(); } catch(RuntimeException error) { handleError(); }
    }
    private void handleError() {
        handler.removeCallbacks(poll);attempt=null;
        JSONObject s=Store.load(this);
        Store.put(s,"diagRequested",false);
        if("ARMED".equals(s.optString("status")) || "SENDING".equals(s.optString("status"))) fail(s,"界面操作发生错误，已暂停；请检查目标 App，避免重复发送。");
        else Store.save(this,s);
    }
    private void tick() {
        Attempt a=attempt; if(a==null)return;
        JSONObject s=Store.load(this);
        boolean active=a.diagnostic?s.optBoolean("diagRequested"):("ARMED".equals(s.optString("status")) || "SENDING".equals(s.optString("status")));
        if(!a.matches(s) || !active) { attempt=null;return; }
        long now=System.currentTimeMillis();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        boolean target=root!=null && a.pkg.contentEquals(root.getPackageName());
        Snapshot snap=new Snapshot();
        if(target)inspect(root,snap,a.pkg,0);
        String key=target?snap.fingerprint():"outside";
        if(!key.equals(a.lastKey)) {a.lastKey=key;a.stableSince=now;}
        boolean stable=target && now-a.stableSince>=STABLE_MS;
        boolean draft=snap.inputs.size()==1 && !inputText(snap.inputs.get(0)).isEmpty();
        boolean matches=snap.inputs.size()==1 && a.pending.equals(inputText(snap.inputs.get(0)));
        boolean enabled=snap.sends.size()==1 && snap.sends.get(0).isEnabled();
        SendFlow.Action action=a.diagnostic?SendFlow.Action.WAIT:a.flow.next(now,target,snap.blocked,snap.truncated,snap.inputs.size(),draft,matches,snap.sends.size(),enabled,stable);
        String phase=a.diagnostic?"只读诊断":s.optString("sendPhase","等待页面");
        String reason=a.diagnostic?"仅采集控件结构，不输入、不发送":a.flow.reason;
        Store.put(s,"diagnostics",diagnostics(a,phase,reason,target,snap,matches));
        if(a.diagnostic) {
            if((target && stable) || now>=a.deadline) {
                Store.put(s,"diagRequested",false); Store.put(s,"notice",target?"只读诊断完成，请返回查看或复制诊断信息。":"诊断超时，未读取其他应用。");
                Store.save(this,s);attempt=null;return;
            }
        } else if(action==SendFlow.Action.ABORT) { fail(s,a.flow.reason);attempt=null;return; }
        else if(action==SendFlow.Action.FILL) {
            Store.put(s,"status","SENDING");Store.put(s,"sendPhase","等待输入和按钮更新");Store.put(s,"notice",a.flow.reason);
            // Durable boundary before the only text write. A failed write is never retried.
            if(!Store.save(this,s)) {attempt=null;return;}
            AccessibilityNodeInfo input=snap.inputs.get(0);
            if(!input.refresh() || !input.isEditable() || !input.isVisibleToUser() || !input.isEnabled() || !a.pkg.contentEquals(input.getPackageName()) || !inputText(input).isEmpty()) {
                fail(s,"填入前页面或草稿发生变化，已暂停。");attempt=null;return;
            }
            Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,a.pending);
            if(!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) {fail(s,"输入接口未确认成功，请检查是否留下草稿；不会再次填入。");attempt=null;return;}
            a.lastKey="";a.stableSince=now;
        } else if(action==SendFlow.Action.CLICK) {
            AccessibilityNodeInfo input=snap.inputs.get(0), send=snap.sends.get(0);
            if(!input.refresh() || !send.refresh() || !a.pending.equals(inputText(input)) || !input.isEditable() || !input.isVisibleToUser() || !input.isEnabled() || !a.pkg.contentEquals(input.getPackageName()) || !a.pkg.contentEquals(send.getPackageName()) || !send.isVisibleToUser() || !send.isEnabled() || send.isEditable() || !send.isClickable() || !UiLabels.isSend(send.getText(),send.getContentDescription())) {
                fail(s,"点击前控件或输入内容发生变化，未点击发送。");attempt=null;return;
            }
            Store.put(s,"sendPhase","已领取单次点击授权");Store.put(s,"notice",a.flow.reason);
            // Claim before clicking. No callbacks or subsequent events can claim another click.
            if(!Store.save(this,s)) {attempt=null;return;}
            boolean clicked=send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if(!clicked)fail(s,"发送接口未确认成功，请检查目标 App；不会重试点击。");
            else {Store.put(s,"status","WAITING");Store.put(s,"sendPhase","已尝试点击一次");Store.put(s,"notice","已尝试点击发送；请核对是否发出，完整回复仍需你确认。");Store.save(this,s);}
            attempt=null;return;
        } else {Store.put(s,"notice",reason); if(!Store.save(this,s)){attempt=null;return;}}
        handler.postDelayed(poll,250);
    }
    private static String inputText(AccessibilityNodeInfo n) {return n.isShowingHintText() || n.getText()==null?"":n.getText().toString();}
    private void fail(JSONObject s,String why) {Store.put(s,"status","UNCERTAIN");Store.put(s,"notice",why);Store.save(this,s);}
    private void inspect(AccessibilityNodeInfo n,Snapshot out,String pkg,int depth) {
        if(n==null)return;
        if(depth>50 || ++out.nodes>2000) {out.truncated=true;return;}
        if(n.isVisibleToUser() && pkg.contentEquals(n.getPackageName())) {
            if(!n.isEditable() && UiLabels.blocked(n.getText(),n.getContentDescription()))out.blocked=true;
            if(n.isEditable() && n.isEnabled())out.inputs.add(n);
            if(n.isClickable()) {
                if(!n.isEditable() && UiLabels.isSend(n.getText(),n.getContentDescription()))out.sends.add(n);
                if(out.controls.size()<10)out.controls.add("类型="+safeClass(n)+"；角色="+UiLabels.safeRole(n.getText(),n.getContentDescription())+"；可用="+n.isEnabled());
            }
        }
        for(int i=0;i<n.getChildCount() && !out.truncated;i++)inspect(n.getChild(i),out,pkg,depth+1);
    }
    private static String safeClass(AccessibilityNodeInfo n) {
        String c=String.valueOf(n.getClassName());return c.matches("[A-Za-z0-9_.$]{1,120}")?c:"未知";
    }
    private String diagnostics(Attempt a,String phase,String reason,boolean target,Snapshot snap,boolean matches) {
        StringBuilder b=new StringBuilder("AI 圆桌 0.2.0；Android ").append(android.os.Build.VERSION.RELEASE)
            .append("\n目标：").append(a.pkg).append("\n步骤：").append(phase).append("\n判断：").append(reason)
            .append("\n目标在前台：").append(target).append("；控件树节点：").append(snap.nodes)
            .append("\n输入框：").append(snap.inputs.size()).append("；发送按钮：").append(snap.sends.size())
            .append("；输入核对：").append(matches).append("\n登录/验证标记：").append(snap.blocked).append("；遍历截断：").append(snap.truncated);
        for(String c:snap.controls)b.append("\n").append(c);
        return b.append("\n未记录输入正文、聊天内容、账户或未知控件名称。").toString();
    }
    private static class Snapshot {
        final List<AccessibilityNodeInfo> inputs=new ArrayList<>(), sends=new ArrayList<>();
        final List<String> controls=new ArrayList<>(); int nodes;boolean blocked,truncated;
        String fingerprint() {
            StringBuilder b=new StringBuilder().append(nodes).append('/').append(inputs.size()).append('/').append(sends.size());
            for(AccessibilityNodeInfo n:inputs)b.append('/').append(n.getWindowId()).append('/').append(inputText(n).hashCode()).append('/').append(n.isShowingHintText());
            for(AccessibilityNodeInfo n:sends)b.append('/').append(n.isEnabled()).append('/').append(n.getWindowId());
            return b.toString();
        }
    }
    private static class Attempt {
        final long session,deadline;final int turn;final String pkg,pending;final boolean diagnostic;final SendFlow flow;
        String lastKey="";long stableSince;
        Attempt(JSONObject s,boolean diag) {
            session=s.optLong("created");turn=s.optInt("turn");pkg=Store.pkg(Store.target(s));pending=s.optString("pending");diagnostic=diag;
            deadline=s.optLong(diag?"diagDeadline":"deadline");flow=new SendFlow(deadline);
        }
        boolean matches(JSONObject s) {return session==s.optLong("created") && turn==s.optInt("turn");}
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
}
