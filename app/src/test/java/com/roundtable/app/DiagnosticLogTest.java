package com.roundtable.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticLogTest {
    @Test public void readProbeCannotEraseAutoFailure() throws Exception {
        JSONObject s=new JSONObject().put("lastAutoNotice","按钮未识别");
        DiagnosticLog.record(s,false,"自动采样，输入框1");
        DiagnosticLog.record(s,true,"只读采样，输入框0");
        assertEquals("自动采样，输入框1",s.getString("autoDiagnostics"));
        assertEquals("只读采样，输入框0",s.getString("readDiagnostics"));
        assertTrue(DiagnosticLog.autoReport(s).contains("按钮未识别"));
    }
    @Test public void earlyStableEmptyPageDoesNotFinishProbe() {
        assertFalse(DiagnosticLog.finishProbe(1500,15000,1000,true,0));
        assertFalse(DiagnosticLog.finishProbe(8000,15000,1000,true,0));
        assertFalse(DiagnosticLog.finishProbe(1500,15000,1000,true,1));
        assertTrue(DiagnosticLog.finishProbe(4500,15000,1000,true,1));
        assertTrue(DiagnosticLog.finishProbe(15000,15000,1000,true,0));
    }
    @Test public void reportDoesNotConfuseClaimWithAction() throws Exception {
        JSONObject s=new JSONObject().put("autoFillClaimed",true).put("autoClickClaimed",true);
        String report=DiagnosticLog.autoReport(s);
        assertTrue(report.contains("已尝试填入：false"));assertTrue(report.contains("已尝试点击：false"));
    }
}
