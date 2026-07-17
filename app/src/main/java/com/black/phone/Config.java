package com.black.phone;

public class Config {
    // 🔥 Firebase Configuration (Replace with your actual values)
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
}
