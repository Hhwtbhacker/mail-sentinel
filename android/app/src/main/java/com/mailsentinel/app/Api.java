package com.mailsentinel.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Api {
    static final String DEFAULT_URL = "https://1.95.120.199";
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("account", Context.MODE_PRIVATE);
    }
    static String base(Context context) {
        return prefs(context).getString("base", DEFAULT_URL).replaceAll("/+$", "");
    }
    static JSONObject request(Context context, String path, String method, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base(context) + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(path.equals("/api/chat") || path.equals("/api/mail/draft") ? 120000 : path.equals("/api/mail/send") ? 40000 : 20000);
        connection.setRequestProperty("Accept", "application/json");
        String token = prefs(context).getString("token", "");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (payload != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        String result = new String((status < 400 ? connection.getInputStream() : connection.getErrorStream()).readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        if (status >= 400) throw new Exception(new JSONObject(result).optString("detail", "请求失败"));
        return new JSONObject(result);
    }
    private static JSONArray getArray(Context context, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base(context) + path).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Authorization", "Bearer " + prefs(context).getString("token", ""));
        int status = connection.getResponseCode();
        String result = new String((status < 400 ? connection.getInputStream() : connection.getErrorStream()).readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        if (status >= 400) throw new Exception("获取数据失败：" + status);
        return new JSONArray(result);
    }
    static JSONArray reminders(Context context) throws Exception { return getArray(context, "/api/reminders"); }
    static JSONArray mailboxes(Context context) throws Exception { return getArray(context, "/api/mailboxes"); }
    static JSONArray chatHistory(Context context) throws Exception { return getArray(context, "/api/chat"); }
}
