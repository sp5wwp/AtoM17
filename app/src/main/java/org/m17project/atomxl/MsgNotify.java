/*
 * AtoM17 - notifications for received M17 SMS: one per sender, tap to open the Inbox, Reply from the notification.
 * The channel is silent: the ringtone and the vibration chosen in AtoM17's Settings › Messages are played here, so
 * they can be changed at any time (a channel's own sound is fixed once Android has created it). The ringer mode is
 * respected: silent plays nothing, vibrate only vibrates.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;

final class MsgNotify {
    static final String CHANNEL = "msg";
    static final String KEY_REPLY = "reply", EXTRA_TO = "to", EXTRA_ID = "id";
    /** Which received SMS notify: 0 none, 1 to my callsign, 2 to me and @ALL (default), 3 every one heard. */
    static final String[] WHICH = { "Off", "Messages to my callsign", "Messages to me and to @ALL", "Every message heard" };
    static final String[] VIBRATIONS = { "No vibration", "Short", "Long", "Double", "Triple", "Morse M17" };
    private static final long[][] PATTERNS = {
            null,
            { 0, 200 },
            { 0, 700 },
            { 0, 200, 150, 200 },
            { 0, 150, 120, 150, 120, 150 },
            // -- (M), .---- (1), --... (7) at about 15 wpm
            { 0, 240, 80, 240, 400, 80, 80, 240, 80, 240, 80, 240, 80, 240, 400, 240, 80, 240, 80, 80, 80, 80, 80, 80 } };

    /** The chosen ringtone: null = default notification sound, "" = silent. */
    static android.net.Uri sound(Config cfg) {
        String s = cfg.p.getString("msgSound", null);
        if (s == null) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return s.isEmpty() ? null : android.net.Uri.parse(s);
    }

    static String soundName(Context c, Config cfg) {
        android.net.Uri u = sound(cfg);
        if (u == null) return "Silent";
        try { android.media.Ringtone r = RingtoneManager.getRingtone(c, u); return r == null ? "Default" : r.getTitle(c); }
        catch (Throwable t) { return "Default"; }
    }

    static int vibration(Config cfg) { return Math.max(0, Math.min(VIBRATIONS.length - 1, cfg.p.getInt("msgVibrate", 3))); }

    /** Play the chosen sound and vibration, as the ringer mode allows. */
    static void alert(Context c, Config cfg) {
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        int mode = am == null ? android.media.AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        boolean dnd = Build.VERSION.SDK_INT >= 23 && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        if (dnd || mode == android.media.AudioManager.RINGER_MODE_SILENT) return;
        long[] pat = PATTERNS[vibration(cfg)];
        if (pat != null) {
            android.os.Vibrator v = c.getSystemService(android.os.Vibrator.class);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(pat, -1),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
                else v.vibrate(pat, -1);
            }
        }
        android.net.Uri u = sound(cfg);
        if (u != null && mode == android.media.AudioManager.RINGER_MODE_NORMAL) {
            try {
                android.media.Ringtone r = RingtoneManager.getRingtone(c, u);
                if (r != null) {
                    r.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                    r.play();
                }
            } catch (Throwable ignored) { }
        }
    }

    private MsgNotify() { }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) != null) return;          // the user's sound and vibration choices stay
        NotificationChannel ch = new NotificationChannel(CHANNEL, "M17 messages", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Received M17 SMS. Sound and vibration are chosen in AtoM17's Settings › Messages.");
        ch.setSound(null, null);                                          // AtoM17 plays them (alert())
        ch.enableVibration(false);
        ch.enableLights(true);
        ch.setLightColor(0xFFF59A23);
        nm.createNotificationChannel(ch);
    }

    static boolean wanted(Config cfg, String dst) {
        int which = cfg.p.getInt("msgNotify", 2);
        if (which <= 0) return false;
        String me = cfg.src().trim().toUpperCase(java.util.Locale.US), to = dst == null ? "" : dst.trim().toUpperCase(java.util.Locale.US);
        if (which >= 3) return true;
        if (!me.isEmpty() && to.equals(me)) return true;
        return which == 2 && (to.equals("@ALL") || to.equals("ALL") || to.isEmpty());
    }

    private static int id(String from) { return 1000 + ((from == null ? "" : from).hashCode() & 0x7FFF); }

    static void post(Context c, Config cfg, String from, String to, String text) {
        if (!wanted(cfg, to)) return;
        channel(c);
        String who = from == null || from.isEmpty() ? "?" : from;
        int id = id(from);
        PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class).putExtra("tab", 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(who + (to != null && !to.isEmpty() ? " → " + to : ""))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setColor(0xFFF59A23)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis()).setShowWhen(true)
                .setContentIntent(open);
        if (from != null && !from.isEmpty()) {
            RemoteInput in = new RemoteInput.Builder(KEY_REPLY).setLabel("Reply to " + who).build();
            PendingIntent reply = PendingIntent.getBroadcast(c, id, new Intent(c, ReplyReceiver.class).putExtra(EXTRA_TO, from).putExtra(EXTRA_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Reply", reply).addRemoteInput(in).build());
        }
        if (Build.VERSION.SDK_INT < 26) { b.setDefaults(Notification.DEFAULT_LIGHTS); b.setPriority(Notification.PRIORITY_HIGH); }
        c.getSystemService(NotificationManager.class).notify(id, b.build());
        alert(c, cfg);
    }

    /** The Inbox was looked at: the message notifications go. */
    static void clear(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 23) {
            for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications())
                if (n.getId() >= 1000) nm.cancel(n.getId());
        }
    }

    /** Reply from the notification: sends the SMS and says how it went in the notification. */
    public static final class ReplyReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            Bundle r = RemoteInput.getResultsFromIntent(i);
            CharSequence text = r == null ? null : r.getCharSequence(KEY_REPLY);
            String to = i.getStringExtra(EXTRA_TO);
            int id = i.getIntExtra(EXTRA_ID, 1000);
            if (text == null || text.length() == 0 || to == null) return;
            Station st = Station.get(c);
            String why = st.sendSms(to, text.toString());
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_notify).setColor(0xFFF59A23).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setContentTitle(why == null ? "Sending to " + to : "Not sent to " + to)
                    .setContentText(why == null ? text : why);
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(why == null ? 8000 : 30000);
            c.getSystemService(NotificationManager.class).notify(id, b.build());
        }
    }
}
