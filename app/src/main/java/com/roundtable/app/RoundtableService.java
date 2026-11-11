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
        if(target) {
            if(a.targetSince==0)a.targetSince=now;
            inspect(root,snap,a.pkg,0);
        }
        String key=target?snap.fingerprint():"outside";
        if(!key.equals(a.lastKey)) {a.lastKey=key;a.stableSince=now;}
        boolean stable=target && now-a.stableSince>=STABLE_MS;
        boolean draft=snap.inputs.size()==1 && !inputText(snap.inputs.get(0)).isEmpty();
        boolean matches=snap.inputs.size()==1 && a.pending.equals(inputText(snap.inputs.get(0)));
        boolean enabled=snap.sends.size()==1 && snap.sends.get(0).isEnabled();
        SendFlow.Action action=a.diagnostic?SendFlow.Action.WAIT:a.flow.next(now,target,snap.blocked,snap.truncated,snap.inputs.size(),draft,matches,snap.sends.size(),enabled,stable);
        String phase=a.diagnostic?"只读诊断":s.optString("sendPhase","等待页面");
        String reason=a.diagnostic?"仅采集控件结构，不输入、不发送":a.flow.reason;
        String summary=phase+"；前台="+target+"；输入="+snap.inputs.size()+"；设置文字动作="+snap.setTextNodes+"；发送标签="+snap.sendLabels+"；可点击发送="+snap.sends.size()+"；输入核对="+matches;
        a.trace(summary,now);
        String data=diagnostics(a,phase,reason,target,snap,matches)+"\n采样变化：\n"+a.trace;
        if(a.diagnostic) {
            int score=target?10+snap.inputs.size()*100+snap.setTextNodes*20+snap.sendLabels*30:0;
            if(score>=a.bestScore) {a.bestScore=score;a.bestData=data;}
            data="只读探测代表样本（优先保留包含输入区的样本）：\n"+a.bestData+"\n最新采样变化：\n"+a.trace;
        }
        DiagnosticLog.record(s,a.diagnostic,data);
        if(a.diagnostic) {
            if(DiagnosticLog.finishProbe(now,a.deadline,a.targetSince,target && stable,snap.inputs.size())) {
                Store.put(s,"diagRequested",false); Store.put(s,"notice",target?"只读诊断完成，请复制只读诊断；自动发送记录另行保留。":"只读探测已结束，保留目标 App 代表样本，未读取其他应用。");
                Store.save(this,s);attempt=null;return;
            }
        } else if(action==SendFlow.Action.ABORT) { fail(s,a.flow.reason);attempt=null;return; }
        else if(action==SendFlow.Action.FILL) {
            Store.put(s,"status","SENDING");Store.put(s,"autoFillClaimed",true);Store.put(s,"sendPhase","等待输入和按钮更新");Store.put(s,"notice",a.flow.reason);
            // Durable boundary before the only text write. A failed write is never retried.
            if(!Store.save(this,s)) {attempt=null;return;}
            AccessibilityNodeInfo input=snap.inputs.get(0);
            if(!input.refresh() || !isInput(input) || !a.pkg.contentEquals(input.getPackageName()) || !inputText(input).isEmpty()) {
                fail(s,"填入前页面或草稿发生变化，已暂停。");attempt=null;return;
            }
            Store.put(s,"autoFillAttempted",true);if(!Store.save(this,s)){attempt=null;return;}
            Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,a.pending);
            if(!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) {fail(s,"输入接口未确认成功，请检查是否留下草稿；不会再次填入。");attempt=null;return;}
            a.lastKey="";a.stableSince=now;
        } else if(action==SendFlow.Action.CLICK) {
            AccessibilityNodeInfo input=snap.inputs.get(0), send=snap.sends.get(0);
            if(!input.refresh() || !send.refresh() || !a.pending.equals(inputText(input)) || !isInput(input) || !a.pkg.contentEquals(input.getPackageName()) || !a.pkg.contentEquals(send.getPackageName()) || !send.isVisibleToUser() || !send.isEnabled() || isInput(send) || !send.isClickable() || !UiLabels.isSend(send.getText(),send.getContentDescription())) {
                fail(s,"点击前控件或输入内容发生变化，未点击发送。");attempt=null;return;
            }
            Store.put(s,"autoClickClaimed",true);Store.put(s,"sendPhase","已领取单次点击授权");Store.put(s,"notice",a.flow.reason);
            // Claim before clicking. No callbacks or subsequent events can claim another click.
            if(!Store.save(this,s)) {attempt=null;return;}
            Store.put(s,"autoClickAttempted",true);if(!Store.save(this,s)){attempt=null;return;}
            boolean clicked=send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if(!clicked)fail(s,"发送接口未确认成功，请检查目标 App；不会重试点击。");
            else {Store.put(s,"status","WAITING");Store.put(s,"sendPhase","已尝试点击一次");Store.put(s,"notice","已尝试点击发送；请核对是否发出，完整回复仍需你确认。");Store.put(s,"lastAutoNotice","已尝试点击一次，请核对是否发出。");Store.save(this,s);}
            attempt=null;return;
        } else {Store.put(s,"notice",reason); if(!Store.save(this,s)){attempt=null;return;}}
        handler.postDelayed(poll,250);
    }
    private static String inputText(AccessibilityNodeInfo n) {return n.isShowingHintText() || n.getText()==null?"":n.getText().toString();}
    private void fail(JSONObject s,String why) {Store.put(s,"status","UNCERTAIN");Store.put(s,"notice",why);Store.put(s,"lastAutoNotice",why);Store.save(this,s);}
    private static boolean hasAction(AccessibilityNodeInfo n, int id) {
        for(AccessibilityNodeInfo.AccessibilityAction a:n.getActionList())if(a.getId()==id)return true;
        return false;
    }
    private static boolean isInput(AccessibilityNodeInfo n) {
        return NodeRules.input(n.isVisibleToUser(),n.isEnabled(),n.isPassword(),n.isEditable(),hasAction(n,AccessibilityNodeInfo.ACTION_SET_TEXT));
    }
    private void inspect(AccessibilityNodeInfo n,Snapshot out,String pkg,int depth) {
        if(n==null)return;
        if(depth>50 || ++out.nodes>2000) {out.truncated=true;return;}
        if(pkg.contentEquals(n.getPackageName())) {
            boolean visible=n.isVisibleToUser(), setText=hasAction(n,AccessibilityNodeInfo.ACTION_SET_TEXT);
            if(setText && visible)out.setTextNodes++;
            if(n.isEditable() && visible)out.editableNodes++;
            boolean input=isInput(n);
            boolean label=!input && !n.isPassword() && UiLabels.isSend(n.getText(),n.getContentDescription());
            if(visible) {
                if(!input && UiLabels.blocked(n.getText(),n.getContentDescription()))out.blocked=true;
                if(input)out.inputs.add(n);
                if(label)out.sendLabels++;
                if(label && n.isClickable())out.sends.add(n);
            }
            if(visible && (n.isClickable() || input || label || setText)) {
                android.graphics.Rect rect=new android.graphics.Rect();n.getBoundsInScreen(rect);
                String id=n.getViewIdResourceName();
                if(id==null || !id.matches(java.util.regex.Pattern.quote(pkg)+":id/[A-Za-z_][A-Za-z0-9_]{0,80}"))id="无/隐藏";
                String meta="类型="+safeClass(n)+"；id="+id+"；角色="+(input?"输入框":UiLabels.safeRole(n.getText(),n.getContentDescription()))
                    +"；editable="+n.isEditable()+"；SET_TEXT="+setText+"；clickable="+n.isClickable()+"；CLICK="+hasAction(n,AccessibilityNodeInfo.ACTION_CLICK)
                    +"；可用="+n.isEnabled()+"；hint="+n.isShowingHintText()+"；区域="+rect.toShortString();
                if(input || label || setText) {if(out.priorityControls.size()<12)out.priorityControls.add(meta);}
                else {
                    if(out.firstControls.size()<4)out.firstControls.add(meta);
                    out.lastControls.add(meta);if(out.lastControls.size()>8)out.lastControls.remove(0);
                }
            }
        }
        for(int i=0;i<n.getChildCount() && !out.truncated;i++)inspect(n.getChild(i),out,pkg,depth+1);
    }
    private static String safeClass(AccessibilityNodeInfo n) {
        String c=String.valueOf(n.getClassName());return c.matches("[A-Za-z0-9_.$]{1,120}")?c:"未知";
    }
    private String diagnostics(Attempt a,String phase,String reason,boolean target,Snapshot snap,boolean matches) {
        StringBuilder b=new StringBuilder("AI 圆桌 0.2.1；Android ").append(android.os.Build.VERSION.RELEASE)
            .append("\n目标：").append(a.pkg).append("\n步骤：").append(phase).append("\n判断：").append(reason)
            .append("\n目标在前台：").append(target).append("；控件树节点：").append(snap.nodes)
            .append("\n输入框：").append(snap.inputs.size()).append("；发送按钮：").append(snap.sends.size())
            .append("；输入核对：").append(matches).append("\n可见 editable：").append(snap.editableNodes).append("；可见 SET_TEXT：").append(snap.setTextNodes).append("；发送标签（含不可点击）：").append(snap.sendLabels).append("\n登录/验证标记：").append(snap.blocked).append("；遍历截断：").append(snap.truncated);
        for(String c:snap.priorityControls)b.append("\n关键控件：").append(c);
        for(String c:snap.firstControls)b.append("\n前部控件：").append(c);
        for(String c:snap.lastControls)b.append("\n尾部控件：").append(c);
        return b.append("\n未记录输入正文、聊天内容、账户或未知控件名称。").toString();
    }
    private static class Snapshot {
        final List<AccessibilityNodeInfo> inputs=new ArrayList<>(), sends=new ArrayList<>();
        final List<String> priorityControls=new ArrayList<>(),firstControls=new ArrayList<>(),lastControls=new ArrayList<>(); int nodes,editableNodes,setTextNodes,sendLabels;boolean blocked,truncated;
        String fingerprint() {
            StringBuilder b=new StringBuilder().append(nodes).append('/').append(inputs.size()).append('/').append(sends.size());
            for(AccessibilityNodeInfo n:inputs)b.append('/').append(n.getWindowId()).append('/').append(inputText(n).hashCode()).append('/').append(n.isShowingHintText());
            for(AccessibilityNodeInfo n:sends)b.append('/').append(n.isEnabled()).append('/').append(n.getWindowId());
            return b.toString();
        }
    }
    private static class Attempt {
        final long session,deadline;final int turn;final String pkg,pending;final boolean diagnostic;final SendFlow flow;
        String lastKey="",lastSummary="",bestData="";long stableSince,targetSince;int bestScore=-1;
        final StringBuilder trace=new StringBuilder();
        void trace(String summary,long now) {
            if(summary.equals(lastSummary))return;
            lastSummary=summary;
            trace.append(Math.max(0,15000-(deadline-now))).append("ms ").append(summary).append("\n");
            if(trace.length()>3500)trace.delete(0,trace.indexOf("\n",trace.length()-3000)+1);
        }
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
