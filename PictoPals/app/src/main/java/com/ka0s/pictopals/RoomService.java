package com.ka0s.pictopals;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service active while the user is in a room. It owns no sockets —
 * its whole job is to keep the process alive and the network usable when the
 * app is backgrounded or the screen is locked: the ongoing notification
 * exempts the process from Doze/app-sleep process kills, the partial wake
 * lock keeps the CPU servicing the relay threads, and the WiFi lock keeps the
 * radio from powering down mid-room.
 */
public class RoomService extends Service {

    private static final String CHANNEL_ID = "room";
    private static final int NOTIF_ID = 1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context ctx, String label) {
        Intent i = new Intent(ctx, RoomService.class);
        i.putExtra("label", label);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, RoomService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pictopals:room");
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pictopals:room");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String label = intent != null ? intent.getStringExtra("label") : null;
        if (label == null) label = "In a room";

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Active room", NotificationManager.IMPORTANCE_LOW));

        // Tapping the notification brings the existing task back to the front.
        PendingIntent tap = PendingIntent.getActivity(this, 0,
                getPackageManager().getLaunchIntentForPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("PictoPals")
                .setContentText(label + " — chat stays connected")
                .setOngoing(true)
                .setContentIntent(tap)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, notif);
        }

        if (!wakeLock.isHeld()) wakeLock.acquire();
        if (!wifiLock.isHeld()) wifiLock.acquire();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
