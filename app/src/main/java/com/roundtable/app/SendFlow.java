package com.roundtable.app;

/** One authorization, at most one text write and one click. UI readiness polls are read-only. */
final class SendFlow {
    enum Action { WAIT, FILL, CLICK, ABORT }
    private enum Stage { PAGE, AFTER_FILL, CLAIMED, ABORTED }
    private Stage stage=Stage.PAGE;
    private final long deadline;
    private boolean targetSeen;
    String reason="等待目标页面稳定";
    SendFlow(long deadline) { this.deadline=deadline; }
    Action next(long now, boolean target, boolean blocked, boolean truncated, int inputs,
                boolean hasDraft, boolean inputMatches, int sends, boolean sendEnabled, boolean stable) {
        if(stage==Stage.CLAIMED || stage==Stage.ABORTED) return Action.WAIT;
        if(now>=deadline) return abort(stage==Stage.PAGE?"等待输入框超时":"填入后未能确认内容和唯一发送按钮，已暂停；请检查是否仍有草稿");
        if(!target) return targetSeen?abort("已离开目标 App，取消本次授权"):Action.WAIT;
        targetSeen=true;
        if(blocked) return abort("检测到登录或验证提示，请手动处理后重新确认");
        if(truncated) return abort("控件树过大，无法完整检查页面");
        if(inputs>1) return abort("存在多个可编辑输入框，无法确定目标");
        if(inputs!=1 || !stable) { reason="等待唯一输入框及页面稳定";return Action.WAIT; }
        if(stage==Stage.PAGE) {
            if(hasDraft) return abort("输入框已有草稿，未覆盖，也未发送");
            stage=Stage.AFTER_FILL;reason="已授权填入一次，等待输入内容和发送按钮更新";return Action.FILL;
        }
        if(!inputMatches) { reason="等待输入内容核对，尚未点击发送";return Action.WAIT; }
        if(sends>1) return abort("存在多个发送按钮，未点击");
        if(sends==0 || !sendEnabled) {reason="问题已填入，等待唯一可用发送按钮";return Action.WAIT;}
        stage=Stage.CLAIMED;reason="只尝试点击一次发送";return Action.CLICK;
    }
    private Action abort(String why) {stage=Stage.ABORTED;reason=why;return Action.ABORT;}
}
