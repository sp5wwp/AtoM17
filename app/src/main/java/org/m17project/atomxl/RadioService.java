/*
 * AtoM17 - foreground service: keeps the radio running with the screen off, and takes the Atom XL's side PTT key.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public final class RadioService extends Service {
    /** The Atom XL's side key (SignalDeck's profile: the phone broadcasts these). */
    static final String PTT_DOWN = "android.intent.action.PTT.down", PTT_UP = "android.intent.action.PTT.up";

    private PowerManager.WakeLock lock;
    private final BroadcastReceiver ptt = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Station s = Station.peek();
            if (s == null) return;
            if (PTT_DOWN.equals(i.getAction())) s.ptt(true);
            else if (PTT_UP.equals(i.getAction())) s.ptt(false);
        }
    };

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
        IntentFilter f = new IntentFilter(); f.addAction(PTT_DOWN); f.addAction(PTT_UP);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ptt, f, Context.RECEIVER_EXPORTED); else registerReceiver(ptt, f);
        lock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atom17:radio");
        lock.acquire();
        Station.get(this).start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }

    @Override public void onDestroy() {
        try { unregisterReceiver(ptt); } catch (Throwable ignored) { }
        Station s = Station.peek();
        if (s != null) s.stop();
        if (lock != null && lock.isHeld()) lock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
