/*
 * AtoM17 - the radio's USB sound card (card 1, device 0: S16_LE, 2 channels, 32 kHz both ways) through SignalDeck's
 * root helper sdalsa. Capture = receive audio (FM-demodulated, used for FM listening and M17 decoding); playback =
 * FM transmit audio. Stream parameters are the stock HAL's (SignalDeck profile, measured 2026-10-28/31).
 * Copyright (C) 2026 IIMacGyverII (SignalDeck: AlsaDirectAudio); modified for AtoM17, 2026.
 * See COPYRIGHT.SignalDeck. SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

final class Audio {
    interface Sink { void onCapture(short[] stereo, int frames); }

    static final int RATE = 32000, CH = 2;
    static final int CAPTURE_PERIOD = 1280, CAPTURE_PERIODS = 4, PLAY_PERIODS = 4, PLAY_KEEP_MS = 60;

    private final String helper;
    private final File filesDir;
    private final Radio.Events log;
    private volatile Sink sink;

    private Process cap; private Thread capThread; private volatile boolean capRunning;
    private Process play; private OutputStream playOut;

    Audio(Context c, Radio.Events log) throws IOException {
        this.helper = install(c, "sdalsa");
        this.filesDir = c.getFilesDir();
        this.log = log;
    }

    void setSink(Sink s) { sink = s; }
    boolean capturing() { return capRunning; }

    static String install(Context c, String name) throws IOException {
        String abi = null;
        for (String a : Build.SUPPORTED_ABIS) if (a.equals("arm64-v8a") || a.equals("armeabi-v7a")) { abi = a; break; }
        if (abi == null) throw new IOException("no " + name + " for " + Arrays.toString(Build.SUPPORTED_ABIS));
        byte[] want;
        try (InputStream in = c.getAssets().open(name + "/" + abi + "/" + name)) { want = readAll(in); }
        File dir = new File(c.getFilesDir(), "bin");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File f = new File(dir, name);
        boolean same = false;
        if (f.length() == want.length) try (InputStream in = new java.io.FileInputStream(f)) { same = Arrays.equals(readAll(in), want); }
        if (!same) {
            File tmp = new File(dir, name + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(want); }
            if (!tmp.renameTo(f)) throw new IOException("cannot install " + f);
        }
        if (!f.setExecutable(true, true)) throw new IOException("cannot make " + f + " executable");
        return f.getAbsolutePath();
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private Process start(String cmd, boolean feedsInput) throws IOException {
        Process p;
        try { p = RootPipedProcess.start(filesDir, cmd, feedsInput); }
        catch (IOException e) {
            log.log("root pipe: " + e.getMessage() + "; own su");
            p = new ProcessBuilder("su", "--mount-master", "--context", "u:r:magisk:s0", "-c", cmd).start();
        }
        final InputStream es = p.getErrorStream();
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(es))) {
                String line; while ((line = r.readLine()) != null) log.log("sdalsa: " + line);
            } catch (Throwable ignored) { }
        }, "sdalsa-err");
        t.setDaemon(true); t.start();
        return p;
    }

    /** Start the receive capture; blocks of 1280 frames go to the sink. */
    synchronized String startCapture() {
        if (capRunning) return "capture already running";
        String cmd = "exec '" + helper + "' cap -c 1 -d 0 -r " + RATE + " -n " + CH + " -p " + CAPTURE_PERIOD + " -k " + CAPTURE_PERIODS + " -q 1 --";
        try { cap = start(cmd, false); } catch (IOException e) { return "capture: " + e.getMessage(); }
        capRunning = true;
        final Process p = cap;
        capThread = new Thread(() -> {
            byte[] b = new byte[CAPTURE_PERIOD * CH * 2];
            short[] s = new short[CAPTURE_PERIOD * CH];
            try (InputStream in = p.getInputStream()) {
                while (capRunning && cap == p) {
                    int got = 0;
                    while (got < b.length) { int r = in.read(b, got, b.length - got); if (r < 0) { got = -1; break; } got += r; }
                    if (got < 0) break;
                    for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
                    Sink k = sink;
                    if (k != null) k.onCapture(s, CAPTURE_PERIOD);
                }
            } catch (IOException e) { if (capRunning) log.log("capture read: " + e.getMessage()); }
            if (cap == p) capRunning = false;
        }, "capture");
        capThread.setPriority(Thread.MAX_PRIORITY);
        capThread.start();
        return "capture on";
    }

    /** A clean close (SIGTERM to sdalsa) keeps the bridge streaming next time; a dead capture does not. */
    synchronized String stopCapture() {
        if (!capRunning && cap == null) return "capture off";
        capRunning = false;
        Process p = cap; cap = null;
        if (p != null) { p.destroy(); try { p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { } }
        return "capture off";
    }

    /** Start the transmit playback (FM). {@link #play(short[], int)} then takes 32 kHz stereo frames. */
    synchronized String startPlayback() {
        if (play != null) return "playback already running";
        String cmd = "exec '" + helper + "' play -c 1 -d 0 -r " + RATE + " -n " + CH + " -p " + (256 * RATE / 8000) + " -k " + PLAY_PERIODS + " -s " + PLAY_KEEP_MS + " --";
        try { play = start(cmd, true); playOut = play.getOutputStream(); } catch (IOException e) { play = null; return "playback: " + e.getMessage(); }
        return "playback on";
    }

    void play(short[] stereo, int frames) {
        OutputStream o = playOut;
        if (o == null) return;
        byte[] b = new byte[frames * CH * 2];
        for (int i = 0; i < frames * CH; i++) { b[2 * i] = (byte) stereo[i]; b[2 * i + 1] = (byte) (stereo[i] >> 8); }
        try { o.write(b); o.flush(); } catch (IOException e) { log.log("playback write: " + e.getMessage()); }
    }

    synchronized String stopPlayback() {
        Process p = play; OutputStream o = playOut;
        play = null; playOut = null;
        if (o != null) try { o.close(); } catch (IOException ignored) { }       // end of input: sdalsa drains and exits
        if (p != null) { try { if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroy(); } catch (InterruptedException e) { p.destroy(); } }
        return "playback off";
    }
}
