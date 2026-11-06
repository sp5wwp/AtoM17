/*
 * AtoM17 - the bridge's control tty. Reader thread, envelope framing, a divert mode for the firmware loader, and
 * writes with a deadline (SignalDeck's TimedWriter: a write the bridge never takes must not freeze the radio).
 * Copyright (C) 2026 IIMacGyverII (SignalDeck: UartPort, TimedWriter); modified for AtoM17, 2026.
 * See COPYRIGHT.SignalDeck. SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Port {
    interface Listener {
        void onFrame(Agold.Frame f);
        void onLog(String s);
    }

    static final long WRITE_DEADLINE_MS = 2000;

    private final Listener listener;
    private InputStream in;
    private OutputStream out;
    private ExecutorService writer;
    private Future<?> pending;
    private volatile boolean stalled;
    private Thread reader;
    private volatile boolean running;
    private volatile boolean divert;
    private final LinkedBlockingQueue<byte[]> diverted = new LinkedBlockingQueue<>();
    private byte[] divertCur;
    private int divertPos;

    Port(Listener l) { listener = l; }

    synchronized void open() throws IOException {
        if (in != null) return;
        in = new FileInputStream(Bridge.PORT);
        out = new FileOutputStream(Bridge.PORT);
        writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "port-writer"); t.setDaemon(true); return t; });
        running = true;
        reader = new Thread(this::readLoop, "port-reader");
        reader.setDaemon(true);
        reader.start();
        listener.onLog("port " + Bridge.PORT + " open");
    }

    synchronized void close() {
        running = false;
        if (writer != null) { writer.shutdownNow(); writer = null; }
        try { if (in != null) in.close(); } catch (IOException ignored) { }
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        in = null; out = null;
    }

    boolean isOpen() { return in != null; }
    boolean stalled() { return stalled; }

    synchronized void write(byte[] b) throws IOException {
        if (out == null || writer == null) throw new IOException("port closed");
        if (pending != null && !pending.isDone()) throw new IOException("the previous write has not finished after " + WRITE_DEADLINE_MS + " ms");
        final OutputStream o = out;
        pending = writer.submit(() -> { o.write(b); o.flush(); return null; });
        try {
            pending.get(WRITE_DEADLINE_MS, TimeUnit.MILLISECONDS);
            stalled = false;
        } catch (TimeoutException e) {
            stalled = true;
            throw new IOException("write did not finish in " + WRITE_DEADLINE_MS + " ms");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof IOException ? (IOException) c : new IOException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** Received bytes go to {@link #readRaw} instead of being framed, until {@link #stopDivert}. */
    synchronized void startDivert() { divert = true; diverted.clear(); divertCur = null; divertPos = 0; }
    synchronized void stopDivert() { divert = false; diverted.clear(); divertCur = null; }

    /** One diverted byte, or -1 when the wait expires. */
    int readRaw(int timeoutMs) throws IOException {
        try {
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                if (divertCur != null && divertPos < divertCur.length) return divertCur[divertPos++] & 0xFF;
                long left = end - System.currentTimeMillis();
                if (left <= 0) return -1;
                divertCur = diverted.poll(left, TimeUnit.MILLISECONDS);
                divertPos = 0;
                if (divertCur == null) return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private void readLoop() {
        byte[] buf = new byte[8192];
        int n = 0;
        byte[] chunk = new byte[2048];
        Agold.Frame[] one = new Agold.Frame[1];
        InputStream src = in;
        while (running) {
            int r;
            try { r = src.read(chunk); } catch (IOException e) { if (running) listener.onLog("port read: " + e.getMessage()); break; }
            if (r < 0) { if (running) listener.onLog("port: end of stream"); break; }
            if (r == 0) continue;
            if (divert) {
                byte[] c = new byte[r];
                System.arraycopy(chunk, 0, c, 0, r);
                diverted.offer(c);
                n = 0;
                continue;
            }
            if (n + r > buf.length) n = 0;                       // garbage overflow: start over
            System.arraycopy(chunk, 0, buf, n, r);
            n += r;
            int off = 0;
            while (off < n) {
                int k = Agold.tryDecode(buf, off, n - off, one);
                if (k == 0) break;
                if (k < 0) { off++; continue; }
                off += k;
                if (one[0] != null) {
                    try { listener.onFrame(one[0]); } catch (Throwable t) { listener.onLog("frame handler: " + t); }
                }
            }
            if (off > 0) { System.arraycopy(buf, off, buf, 0, n - off); n -= off; }
        }
    }
}
