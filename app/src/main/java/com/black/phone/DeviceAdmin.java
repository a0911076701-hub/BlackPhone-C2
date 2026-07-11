package com.black.phone;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class DeviceAdmin extends DeviceAdminReceiver {
    @Override public void onEnabled(Context c, Intent i) {
        Toast.makeText(c, "Admin activated", Toast.LENGTH_SHORT).show();
    }
    @Override public CharSequence onDisableRequested(Context c, Intent i) {
        return "لا يمكن تعطيل هذا الإجراء";
    }
}
