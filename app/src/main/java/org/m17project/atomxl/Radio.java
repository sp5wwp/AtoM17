/*
 * AtoM17 - the Atom XL's module, through the bridge: power, the command set AtoM17 needs, the V076J symbol engine and
 * the reception line. Command layouts and every timing rule are SignalDeck's bench findings (RadioEngine, AgoldUsb,
 * RadioService). Copyright (C) 2026 IIMacGyverII (SignalDeck); modified for AtoM17, 2026.
 * See COPYRIGHT.SignalDeck. SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class Radio implements Port.Listener {
    interface Events {
        void log(String s);
        /** /dev/stmcu: true = a reception started, false = it ended. */
        void reception(boolean on);
        /** A module status push (0x36). */
        default void status(int code) { }
    }

    /** Before and after a command whose reply is read: the bridge cuts replies short while its USB capture streams. */
    interface Guard { void before(); void after(); }

    static final int CMD_ANALOG = 0x23, CMD_LAUNCH = 0x26, CMD_VOLUME = 0x2E, CMD_SQUELCH = 0x30, CMD_RSSI = 0x32,
            CMD_VERSION = 0x34, CMD_STATUS = 0x36, CMD_TOT = 0x3B, CMD_SYM = 0x40;

    private final Events ev;
    private final Port port = new Port(this);
    private final java.io.File filesDir;
    private volatile Guard guard;
    private final Object reqLock = new Object();
    private final Object replyGate = new Object();
    private int wantRoute = -1, wantCmd = -1;
    private Agold.Frame reply;
    private volatile String bridgeDate;

    Radio(java.io.File filesDir, Events ev) { this.filesDir = filesDir; this.ev = ev; }

    void setGuard(Guard g) { guard = g; }
    boolean isOpen() { return port.isOpen(); }

    // ---- power ----

    /** Take the radio and open its port. */
    void open() throws IOException {
        ev.log(Root.applyPolicy(filesDir));
        ev.log(Bridge.up());
        port.open();
        startReceptionWatch();
    }

    /** Close the port, power the radio off, give the USB socket back and start the HAL. */
    void shutdown() {
        stopReceptionWatch();
        port.close();
        ev.log(Bridge.down());
    }

    /** A full bridge power cycle (the port goes with the bridge's power). */
    void powerCycle() throws IOException {
        stopReceptionWatch();
        port.close();
        ev.log(Bridge.up());
        port.open();
        startReceptionWatch();
    }

    /** For the firmware loader: power the bridge up with PTT held, so a loader bridge starts the module in its ROM loader. */
    void loaderOpen() throws IOException {
        stopReceptionWatch();
        port.close();
        ev.log(Bridge.loaderUp());
        port.open();
    }

    void closePort() { stopReceptionWatch(); port.close(); }
    void openPortOnly() throws IOException { port.open(); }

    // ---- raw access for the loader ----
    void startDivert() { port.startDivert(); }
    void stopDivert() { port.stopDivert(); }
    void writeRaw(byte[] b) throws IOException { port.write(b); }
    int readRaw(int ms) throws IOException { return port.readRaw(ms); }

    // ---- requests ----

    /** Send a request and wait for its reply (route + cmd, rw 00), or null. One request at a time. */
    Agold.Frame request(int route, int cmd, byte[] body, int waitMs) throws IOException {
        synchronized (reqLock) {
            Guard g = guard;
            if (g != null) g.before();
            try {
                synchronized (replyGate) { wantRoute = route; wantCmd = cmd; reply = null; }
                port.write(Agold.request(route, cmd, body));
                long end = System.currentTimeMillis() + Math.max(1, waitMs);
                synchronized (replyGate) {
                    while (reply == null && System.currentTimeMillis() < end) {
                        try { replyGate.wait(Math.max(1, end - System.currentTimeMillis())); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                    Agold.Frame r = reply;
                    wantRoute = wantCmd = -1;
                    return r;
                }
            } finally { if (g != null) g.after(); }
        }
    }

    Agold.Frame module(int cmd, byte[] body, int waitMs) throws IOException { return request(Agold.ROUTE_MODULE, cmd, body, waitMs); }

    /** A set command: true when the module answered 00. */
    boolean set(int cmd, byte... body) throws IOException {
        Agold.Frame f = module(cmd, body, 1000);
        boolean ok = f != null && f.result() == 0;
        if (!ok) ev.log(String.format(java.util.Locale.US, "cmd 0x%02X: %s", cmd, f == null ? "no answer" : "refused " + f));
        return ok;
    }

    @Override public void onFrame(Agold.Frame f) {
        if (f.route == Agold.ROUTE_BRIDGE && f.cmd == 0x01) bridgeDate = new String(f.body, StandardCharsets.US_ASCII).trim();
        if (f.route == Agold.ROUTE_MODULE && f.cmd == CMD_STATUS && f.body.length > 0) ev.status(f.body[0] & 0xFF);
        if (f.rw != 0 && f.route != Agold.ROUTE_BRIDGE) return;   // bridge reports: matched whatever their rw byte (as SignalDeck's loader does)
        synchronized (replyGate) {
            if (f.route == wantRoute && f.cmd == wantCmd && reply == null) { reply = f; replyGate.notifyAll(); }
        }
    }

    @Override public void onLog(String s) { ev.log(s); }

    /** The bridge's build date: stock 20201016161541, B4 20261102000001, P1 20261102000002. */
    String bridgeDate() throws IOException {
        bridgeDate = null;
        Agold.Frame f = request(Agold.ROUTE_BRIDGE, 0x01, new byte[] { 1 }, 3000);
        return f != null ? new String(f.body, StandardCharsets.US_ASCII).trim() : bridgeDate;
    }

    /** "DMR006.U4A.V0xx[letter]", or null. */
    String version() throws IOException {
        Agold.Frame f = module(CMD_VERSION, new byte[] { 1 }, 1500);
        if (f == null) return null;
        StringBuilder s = new StringBuilder();
        for (byte b : f.body) { if (b >= 0x20 && b < 0x7F) s.append((char) b); else if (s.length() > 0) break; }
        return s.toString().trim();
    }

    /** V076J's capability bits (command 0x40 opcode 0x7F answers [1, lo, hi]); 0 for stock or no answer. */
    int capabilities() throws IOException {
        Agold.Frame f = sym(new byte[] { 0x7F }, 1500);
        if (f == null || f.body.length != 3 || f.body[0] != 1) return 0;
        return (f.body[1] & 0xFF) | ((f.body[2] & 0xFF) << 8);
    }
    static final int CAP_FLASH_READ = 1, CAP_M17_TX = 2;

    /**
     * Analog channel: rxHz, txHz (LE), band (0 = 12.5 kHz, 1 = 25 kHz), power (0 low, 1 high), sq (0 = open … 9),
     * rx tone type/sub, tx tone type/sub (type 1 = CTCSS, sub = index into the 51-tone table), pwrSave 2, volume,
     * monitor 2 (off), relay 2. Enum bytes are 1 = on / 2 = off; the module refuses a 0 there.
     */
    boolean setAnalog(int rxHz, int txHz, boolean wide, boolean high, int sq, int rxTone, int txTone, int volume) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(rxHz).putInt(txHz).put((byte) (wide ? 1 : 0)).put((byte) (high ? 1 : 0)).put((byte) Math.max(0, Math.min(9, sq)))
                .put((byte) (rxTone > 0 ? 1 : 0)).put((byte) Math.max(0, rxTone - 1))
                .put((byte) (txTone > 0 ? 1 : 0)).put((byte) Math.max(0, txTone - 1))
                .put((byte) 2).put((byte) volume).put((byte) 2).put((byte) 2);
        return set(CMD_ANALOG, b.array());
    }

    boolean setSquelch(int level) throws IOException { return set(CMD_SQUELCH, (byte) Math.max(0, Math.min(9, level))); }
    boolean setVolume(int v) throws IOException { return set(CMD_VOLUME, (byte) v); }
    /** 0 = the module's own timer off. Its start-up value is 120 s. */
    boolean setTot(int seconds) throws IOException { set(CMD_TOT, (byte) Math.max(0, Math.min(255, seconds))); return true; }   // V076 answers status 1 in both branches

    /** FM key: the bridge's PTT line (the HAL's startPtt / stopPtt), never 0x26. */
    void fmPtt(boolean down) throws IOException { Bridge.ptt(down); }

    /** M17 symbol key: 0x26 alone (SD bench 2026-11-02: the PTT line does not start the symbol engine). */
    void symbolKey(boolean down) throws IOException {
        port.write(Agold.request(Agold.ROUTE_MODULE, CMD_LAUNCH, new byte[] { (byte) (down ? 1 : 2) }));
        ev.log("symbol key " + (down ? "on" : "off"));
    }

    /** One 0x40 request and its reply. */
    Agold.Frame sym(byte[] body, int waitMs) throws IOException { return module(CMD_SYM, body, waitMs); }

    // ---- the reception line ----

    private volatile Thread rxWatch;
    private volatile java.io.Closeable rxSource;
    static final long POWER_UP_EDGES_MS = 2000;

    private void startReceptionWatch() {
        stopReceptionWatch();
        Thread t = new Thread(() -> {
            java.io.InputStream in = null; Process p = null;
            try {
                try { in = new java.io.FileInputStream(Bridge.STMCU); }
                catch (IOException e) {
                    p = new ProcessBuilder("su", "--mount-master", "--context", "u:r:magisk:s0", "-c", "exec cat " + Bridge.STMCU).start();
                    in = p.getInputStream();
                    ev.log("reception line through root (" + e.getMessage() + ")");
                }
                final java.io.InputStream src = in; final Process proc = p;
                rxSource = () -> { try { src.close(); } finally { if (proc != null) proc.destroy(); } };
                long started = System.currentTimeMillis();
                while (rxWatch == Thread.currentThread()) {
                    int b = in.read();
                    if (b < 0) {
                        if (p != null) break;
                        try { in.close(); } catch (IOException ignored) { }
                        Thread.sleep(50);
                        in = new java.io.FileInputStream(Bridge.STMCU);
                        final java.io.InputStream again = in;
                        rxSource = again::close;
                        continue;
                    }
                    if (System.currentTimeMillis() - started < POWER_UP_EDGES_MS) continue;   // the bridge toggles the line at power-up
                    if (b == 0) ev.reception(true); else if (b == 1) ev.reception(false);
                }
            } catch (Exception e) {
                if (rxWatch == Thread.currentThread()) ev.log("reception line: " + e.getMessage());
            } finally {
                try { if (in != null) in.close(); } catch (IOException ignored) { }
                if (p != null) p.destroy();
            }
        }, "rx-line");
        t.setDaemon(true);
        rxWatch = t;
        t.start();
    }

    private void stopReceptionWatch() {
        rxWatch = null;
        java.io.Closeable c = rxSource; rxSource = null;
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
}
