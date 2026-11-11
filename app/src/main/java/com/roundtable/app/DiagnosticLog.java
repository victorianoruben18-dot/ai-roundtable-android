package com.roundtable.app;

import org.json.JSONObject;

/** Keep a later read-only probe from erasing the evidence of an authorized send. */
final class DiagnosticLog {
    static void record(JSONObject state, boolean readOnly, String data) {
        Store.put(state,readOnly?"readDiagnostics":"autoDiagnostics",data);
    }
    static String autoReport(JSONObject s) {
        return s.optString("autoDiagnostics")+"\n自动操作结果："+s.optString("lastAutoNotice","未确认")
            +"\n已尝试填入："+s.optBoolean("autoFillAttempted")+"；已尝试点击："+s.optBoolean("autoClickAttempted");
    }
    static boolean finishProbe(long now,long deadline,long targetSince,boolean stable,int inputCount) {
        return now>=deadline || (targetSince>0 && now-targetSince>=3000 && stable && inputCount>0);
    }
}
