package com.black.phone;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class DeviceAdmin extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "مدير الجهاز مفعل", Toast.LENGTH_SHORT).show();
    }
    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "لا يمكن تعطيل المدير", Toast.LENGTH_SHORT).show();
    }
}
