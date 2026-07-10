package com.black.phone;
import android.content.Context;
import com.google.gson.Gson;
import java.io.InputStreamReader;
public class Config {
    public String bot_token, admin_chat_id, webview_url;
    public int poll_interval_sec;
    private static Config instance;
    public static Config get(){ return instance; }
    public static void load(Context ctx){
        try {
            instance = new Gson().fromJson(new InputStreamReader(ctx.getAssets().open("config.json")), Config.class);
        } catch(Exception e){ instance = new Config(); }
    }
}
