package com.roundtable.app;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class DiscussionTest {
    private JSONObject session() throws Exception {
        return new JSONObject("{\"topic\":\"测试题目\",\"participants\":[\"ChatGPT\",\"DeepSeek\"],\"turn\":0,\"records\":[]}");
    }
    @Test public void participantsAlternateAcrossRounds() throws Exception {
        JSONObject s=session(); assertEquals("ChatGPT",Store.target(s));
        s.put("turn",1); assertEquals("DeepSeek",Store.target(s));
        s.put("turn",2); assertEquals("ChatGPT",Store.target(s));
    }
    @Test public void promptPreservesSourceAndCompleteReply() throws Exception {
        JSONObject s=session(); s.getJSONArray("records").put(new JSONObject().put("round",1).put("participant","ChatGPT").put("reply","第一段\n最后一段"));
        String p=Store.prompt(s); assertTrue(p.contains("测试题目")); assertTrue(p.contains("ChatGPT")); assertTrue(p.contains("第一段\n最后一段")); assertTrue(p.contains("仅为讨论资料"));
    }
    @Test public void newDiscussionHasNoInventedResponses() throws Exception {assertFalse(Store.prompt(session()).contains("ChatGPT："));}
    @Test public void unknownAppCannotBecomeAutomationTarget() { assertEquals("",Store.pkg("元宝")); assertEquals("",Store.pkg("unknown")); }
    @Test public void authorizationIsRejectedAfterFirstAttemptOrPause() throws Exception {
        JSONObject s=session().put("created",123).put("status","READY");
        assertTrue(Store.mayAuthorize(s,123,0));
        for(String status:new String[]{"ARMED","SENDING","WAITING","UNCERTAIN","PAUSED","STOPPED","COMPLETE"}) {
            s.put("status",status); assertFalse(status,Store.mayAuthorize(s,123,0));
        }
    }
    @Test public void oldConfirmationCannotSendNewSessionOrTurn() throws Exception {
        JSONObject s=session().put("created",123).put("status","READY");
        assertFalse(Store.mayAuthorize(s,122,0)); assertFalse(Store.mayAuthorize(s,123,1));
    }
}
