package com.roundtable.app;

import java.util.Locale;

final class UiLabels {
    static String normalize(CharSequence s) {return s==null?"":s.toString().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
    static boolean isSend(CharSequence text,CharSequence description) {return send(normalize(text)) || send(normalize(description));}
    private static boolean send(String s) {
        return s.equals("send") || s.equals("send message") || s.equals("send prompt") || s.equals("submit message") || s.equals("发送") || s.equals("发送消息") || s.equals("发送提示");
    }
    static boolean blocked(CharSequence text,CharSequence description) {return blockedLabel(normalize(text)) || blockedLabel(normalize(description));}
    private static boolean blockedLabel(String s) {
        return s.equals("登录") || s.equals("立即登录") || s.equals("log in") || s.equals("sign in") || s.equals("验证码") || s.equals("输入验证码") || s.equals("人机验证") || s.contains("captcha") || s.contains("verify you are human");
    }
    // Only known generic controls may appear in diagnostics; arbitrary UI text is never exported.
    static String safeRole(CharSequence text,CharSequence description) {
        if(isSend(text,description))return "发送";
        String s=normalize(description);
        if(s.equals("voice mode") || s.equals("start voice mode") || s.equals("语音模式"))return "语音模式";
        return "其他（名称未记录）";
    }
}
