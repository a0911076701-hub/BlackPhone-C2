package com.black.phone;
import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
public class DeviceAdmin extends DeviceAdminReceiver {
    @Override public CharSequence onDisableRequested(Context c, Intent i){ return "لا يمكن التعطيل"; }
}
