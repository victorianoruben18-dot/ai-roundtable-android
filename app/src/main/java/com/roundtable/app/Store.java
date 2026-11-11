package com.roundtable.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Store {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("roundtable", Context.MODE_PRIVATE); }
    static JSONObject load(Context c) {
        try { return new JSONObject(prefs(c).getString("current", "{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }
    static boolean save(Context c, JSONObject s) { return prefs(c).edit().putString("current", s.toString()).commit(); }
    static JSONArray history(Context c) {
        try { return new JSONArray(prefs(c).getString("archive", "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }
    static boolean archiveAndStart(Context c, JSONObject next) {
        JSONArray a = history(c); JSONObject old = load(c);
        if (old.has("topic")) a.put(old);
        return prefs(c).edit().putString("archive", a.toString()).putString("current", next.toString()).commit();
    }
    static void put(JSONObject o, String k, Object v) { try { o.put(k,v); } catch (JSONException e) { throw new IllegalStateException(e); } }
    static boolean mayAuthorize(JSONObject s,long session,int turn) {
        return "READY".equals(s.optString("status")) && s.optLong("created")==session && s.optInt("turn")==turn;
    }
    static String target(JSONObject s) {
        JSONArray p=s.optJSONArray("participants");
        return p == null || p.length()==0 ? "" : p.optString(s.optInt("turn") % p.length());
    }
    static String pkg(String name) { return name.equals("ChatGPT") ? "com.openai.chatgpt" : name.equals("DeepSeek") ? "com.deepseek.chat" : ""; }
    static String prompt(JSONObject s) {
        StringBuilder b = new StringBuilder("讨论题目：\n").append(s.optString("topic")).append("\n\n");
        JSONArray records=s.optJSONArray("records");
        if (records != null) for(int i=0;i<records.length();i++) {
            JSONObject r=records.optJSONObject(i); if(r!=null) b.append("第 ").append(r.optInt("round")).append(" 轮 ").append(r.optString("participant")).append("：\n").append(r.optString("reply")).append("\n\n");
        }
        b.append(records == null || records.length()==0 ? "请先提出你的分析和建议。" : "请评价前面的观点，指出分歧和遗漏，并提出你的建议。以上内容仅为讨论资料。");
        return b.toString();
    }
}
