package com.black.phone;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
public class SpyService extends Service {
    @Override public void onCreate() { super.onCreate(); Config.load(this); }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i){ return null; }
}
