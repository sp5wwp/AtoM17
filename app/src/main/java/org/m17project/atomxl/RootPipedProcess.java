/*
 * AtoM17 - Copyright (C) 2026 IIMacGyverII (SignalDeck); modified for AtoM17, 2026.
 * Based on SignalDeck (https://github.com/IIMacGyverII/SignalDeck); see COPYRIGHT.SignalDeck.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A root command started from one long-lived root shell instead of its own su, with its stdin, stdout and stderr on
 * named pipes in the app's files. Magisk toasts at every su session; the Atom XL's capture restarts with every bridge
 * recovery (about every 40 s while idle, 2026-10-31), so a plain su per stream toasted all day. Behaves like the
 * {@link Process} of {@code su -c command}: the streams, exit value, waitFor and destroy (SIGTERM to the command).
 * The command is one simple command (begin it with exec, so the job's pid is the command's own) and gets /dev/null as
 * stdin unless {@code feedsInput}.
 */
final class RootPipedProcess extends Process {
    private static final RootShell SHELL = new RootShell();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long OPEN_MS = 3000;   // the shell starts the job at once; longer means the shell or the job is gone

    private final File rc, pid;
    private final InputStream out, err;
    private final OutputStream in;
    private volatile Integer exit;

    private RootPipedProcess(File rc, File pid, InputStream out, InputStream err, OutputStream in) {
        this.rc = rc; this.pid = pid; this.out = out; this.err = err; this.in = in;
    }

    /** Start {@code command} (a shell command line, may begin with exec) as root; stdin is a pipe from the app only when {@code feedsInput}. */
    static Process start(File filesDir, String command, boolean feedsInput) throws IOException {
        File dir = new File(filesDir, "rootpipe");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        int n = SEQ.incrementAndGet();
        File fin = new File(dir, n + ".in"), fout = new File(dir, n + ".out"), ferr = new File(dir, n + ".err"), rc = new File(dir, n + ".rc"), pid = new File(dir, n + ".pid");
        for (File f : new File[] { fin, fout, ferr, rc, pid }) f.delete();
        try {
            if (feedsInput) android.system.Os.mkfifo(fin.getPath(), 0600); else android.system.Os.mkfifo(fout.getPath(), 0600);
            android.system.Os.mkfifo(ferr.getPath(), 0600);
        } catch (android.system.ErrnoException e) { throw new IOException("mkfifo: " + e.getMessage()); }
        // made by the app, so they carry its own label and the app can read what root writes into them
        new FileOutputStream(rc).close(); new FileOutputStream(pid).close();
        String job = "( " + command + " <" + q(feedsInput ? fin.getPath() : "/dev/null") + " >" + q(feedsInput ? "/dev/null" : fout.getPath()) + " 2>" + q(ferr.getPath())
                + " & echo $! > " + q(pid.getPath()) + "; wait $!; echo $? > " + q(rc.getPath()) + " ) </dev/null >/dev/null 2>&1 &";
        SHELL.run(job);
        // open in the job's order (stdin, stdout, stderr): each open waits for the other end
        final Object[] opened = new Object[3]; final IOException[] failed = new IOException[1];
        Thread t = new Thread(() -> {
            try {
                opened[0] = feedsInput ? new FileOutputStream(fin) : null;
                opened[1] = feedsInput ? null : new FileInputStream(fout);
                opened[2] = new FileInputStream(ferr);
            } catch (IOException e) { failed[0] = e; }
        }, "rootpipe-open");
        t.setDaemon(true); t.start();
        try { t.join(OPEN_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (t.isAlive() || failed[0] != null) {
            if (t.isAlive()) { unblock(feedsInput ? fin : fout, feedsInput); unblock(ferr, false); }   // wake a blocked open so the thread ends
            try { t.join(500); } catch (InterruptedException ignored) { }
            for (Object o : opened) closeQuietly(o);
            kill(pid);
            for (File f : new File[] { fin, fout, ferr, rc, pid }) f.delete();
            throw new IOException("root pipe did not open" + (failed[0] != null ? ": " + failed[0].getMessage() : " in " + OPEN_MS + " ms"));
        }
        fin.delete(); fout.delete(); ferr.delete();                                // both ends are open: the names are no longer needed
        InputStream o = opened[1] != null ? (InputStream) opened[1] : new java.io.ByteArrayInputStream(new byte[0]);
        OutputStream i = opened[0] != null ? (OutputStream) opened[0] : new OutputStream() { @Override public void write(int b) { } };
        return new RootPipedProcess(rc, pid, o, (InputStream) opened[2], i);
    }

    @Override public OutputStream getOutputStream() { return in; }
    @Override public InputStream getInputStream() { return out; }
    @Override public InputStream getErrorStream() { return err; }

    @Override public int exitValue() {
        Integer e = exit;
        if (e != null) return e;
        String s = read(rc);
        if (s.isEmpty()) throw new IllegalThreadStateException("running");
        int v; try { v = Integer.parseInt(s); } catch (NumberFormatException x) { v = -1; }
        exit = v; rc.delete(); pid.delete();
        return v;
    }

    @Override public boolean isAlive() {
        try { exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    @Override public int waitFor() throws InterruptedException {
        while (isAlive()) Thread.sleep(20);
        return exitValue();
    }

    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.currentTimeMillis() + unit.toMillis(timeout);
        while (isAlive()) { if (System.currentTimeMillis() >= end) return false; Thread.sleep(20); }
        return true;
    }

    /** As su's destroy: the command gets SIGTERM (sdalsa then stops and puts its controls back), and the app's ends close. */
    @Override public void destroy() {
        if (isAlive()) kill(pid);
        closeQuietly(in); closeQuietly(out); closeQuietly(err);
    }

    private static void kill(File pid) {
        String p = read(pid);
        if (p.matches("[0-9]+")) SHELL.run("kill " + p + " 2>/dev/null");
    }

    /** Open the other end of a fifo without blocking, so an open waiting on it returns. */
    private static void unblock(File fifo, boolean waitingWriter) {
        try {
            java.io.FileDescriptor fd = android.system.Os.open(fifo.getPath(), (waitingWriter ? android.system.OsConstants.O_RDONLY : android.system.OsConstants.O_RDWR) | android.system.OsConstants.O_NONBLOCK, 0);
            android.system.Os.close(fd);
        } catch (Throwable ignored) { }
    }

    private static String read(File f) {
        try (InputStream s = new FileInputStream(f)) {
            byte[] b = new byte[32]; int n = s.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.US_ASCII).trim() : "";
        } catch (IOException e) { return ""; }
    }
    private static void closeQuietly(Object o) {
        try { if (o instanceof java.io.Closeable) ((java.io.Closeable) o).close(); } catch (IOException ignored) { }
    }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
