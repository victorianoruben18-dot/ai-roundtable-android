package com.roundtable.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class UiLabelsTest {
    @Test public void independentTextAndDescriptionAvoidConcatenationFailure() {
        assertTrue(UiLabels.isSend("发送","发送消息"));
        assertTrue(UiLabels.isSend("Send", "Send"));
        assertTrue(UiLabels.isSend(null,"  SEND   MESSAGE  "));
    }
    @Test public void voiceAndUnrelatedButtonsAreNeverSend() {
        for(String s:new String[]{"语音模式","microphone","start voice mode","send later","请发送消息给我","发送验证码"})assertFalse(s,UiLabels.isSend(s,s));
    }
    @Test public void verificationMarkersStillBlock() {
        assertTrue(UiLabels.blocked(null,"Verify you are human"));
        assertTrue(UiLabels.blocked("验证码",null));
        assertTrue(UiLabels.blocked("Sign in",null));
    }
    @Test public void diagnosticsHideUnknownUiText() {
        assertFalse(UiLabels.safeRole("私人聊天标题","用户账户内容").contains("私人"));
        assertEquals("其他（名称未记录）",UiLabels.safeRole("私人聊天标题","用户账户内容"));
    }
}
