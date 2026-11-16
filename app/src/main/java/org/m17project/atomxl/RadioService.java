/*
 * AtoM17 - foreground service: keeps the radio running with the screen off. The side PTT key is read by PttKey.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public final class RadioService extends Service {
    private PowerManager.WakeLock lock;

    static void start(Context c) {
        Intent i = new Intent(c, RadioService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, RadioService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("radio", "Radio", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "radio") : new Notification.Builder(this))
                .setContentTitle("AtoM17").setContentText("The radio is on").setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentIntent(pi).setOngoing(true).build();
        startForeground(17, n);
        lock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atom17:radio");
        lock.acquire();
    }

    /** Every start request (each Start press) tries again: start() does nothing while the radio is on or starting. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Station.get(this).start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        Station s = Station.peek();
        PowerManager.WakeLock l = lock;
        Runnable release = () -> { if (l != null && l.isHeld()) l.release(); };
        if (s != null) s.stop(release);                                   // the lock holds until the radio is really off
        else release.run();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
