/*
 * AtoM17 - module firmware loading on the Atom XL: the bridge's ROM DFU (backup, P1 loader bridge, stock bridge back),
 * the module's ROM loader through P1 (hardware check, stop-and-wait YModem with 128-byte blocks), and the images built
 * on the phone from its own vendor files (the APK carries only patches, never vendor bytes).
 *
 * The sequence, every refusal and every timing are SignalDeck's (AgoldFirmwareSession, AgoldBridgeDfu, AgoldLoaderWire,
 * YModemSender, BridgePatch, AgoldFirmware), certified there on the bench Atom XL 2026-11-02.
 * Copyright (C) 2026 IIMacGyverII (SignalDeck); modified for AtoM17, 2026. See COPYRIGHT.SignalDeck.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Flasher {
    interface Ui {
        void phase(String p);
        void log(String s);
        void progress(int done, int total);
        /** The second confirmation for replacing a newer vendor build that cannot be put back. */
        boolean confirmReplace(String installed);
    }

    // ---- known files ----
    static final String DFU_UTIL = "/vendor/bin/dfu-util";
    static final String DFU_ID = "0483:df11";
    static final String STOCK_BRIDGE_FILE = "/vendor/data/mcu/20201016161541.bin";
    static final String STOCK_BRIDGE_SHA = "7606305434c269f9ca7446e099714540b6dda3e37390396a6e3d07efdbd60abb";
    static final String STOCK_BRIDGE_DATE = "20201016161541", B4_DATE = "20261102000001", P1_DATE = "20261102000002";
    static final String VENDOR_V076_FILE = "/vendor/data/dmr/DMR006.U4A.V076.bin";
    static final String V076_SHA = "4f5f287bef875fad4a5c8ca9a94c9e841a21f5b507f2cc2a0645d5947e4d40fe";
    static final String V076_TAG = "DMR006.U4A.V076", V076J_TAG = "DMR006.U4A.V076J";
    static final int FLASH_BYTES = 0x80000, TAG_AT = 0x33410;
    static final String HARD_VERSION = "HardVersion 1.1";

    private final Context ctx;
    private final Radio radio;
    private final Ui ui;
    private final java.util.concurrent.atomic.AtomicBoolean halt = new java.util.concurrent.atomic.AtomicBoolean();

    Flasher(Context ctx, Radio radio, Ui ui) { this.ctx = ctx; this.radio = radio; this.ui = ui; }

    void halt() { halt.set(true); }

    static boolean loaderBridge(String date) { return B4_DATE.equals(date) || P1_DATE.equals(date); }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format(java.util.Locale.US, "%02x", x & 0xFF));
            return s.toString();
        } catch (Exception e) { return ""; }
    }

    /** A root-readable file, read as root (the vendor partition is not readable by the app). */
    byte[] rootRead(String path) {
        File tmp = new File(ctx.getCacheDir(), "rr-" + System.nanoTime());
        String r = Root.run("cat '" + path + "' > '" + tmp.getAbsolutePath() + "' && chown " + android.os.Process.myUid() + ":" + android.os.Process.myUid() + " '" + tmp.getAbsolutePath() + "' && echo ok");
        try { return r.endsWith("ok") ? Files.readAllBytes(tmp.toPath()) : null; } catch (IOException e) { return null; }
        finally { tmp.delete(); }
    }

    static byte[] applyPatch(byte[] json, byte[] base) {
        try {
            JSONObject o = new JSONObject(new String(json, StandardCharsets.UTF_8));
            if (base == null || base.length != o.getInt("baseSize") || !o.getString("baseSha256").equals(sha256(base))) return null;
            int size = o.getInt("size");
            if (size < base.length) return null;
            byte[] out = new byte[size];
            System.arraycopy(base, 0, out, 0, base.length);
            JSONArray edits = o.getJSONArray("edits");
            for (int i = 0; i < edits.length(); i++) {
                JSONObject e = edits.getJSONObject(i);
                int at = e.getInt("at"); String hex = e.getString("bytes");
                if (at < 0 || at + hex.length() / 2 > size) return null;
                for (int k = 0; k < hex.length() / 2; k++) out[at + k] = (byte) Integer.parseInt(hex.substring(2 * k, 2 * k + 2), 16);
            }
            return o.getString("sha256").equals(sha256(out)) ? out : null;
        } catch (Exception e) { return null; }
    }

    byte[] asset(String name) throws IOException { try (java.io.InputStream in = ctx.getAssets().open(name)) { return Audio.readAll(in); } }

    byte[] stockBridge;
    byte[] stockBridge() { if (stockBridge == null) stockBridge = rootRead(STOCK_BRIDGE_FILE); return stockBridge; }
    byte[] p1Bridge() { try { return applyPatch(asset("bridge-p1.patch.json"), stockBridge()); } catch (IOException e) { return null; } }
    byte[] vendorV076() { byte[] b = rootRead(VENDOR_V076_FILE); return b != null && V076_SHA.equals(sha256(b)) ? b : null; }
    byte[] v076j() { try { return applyPatch(asset("v076j.patch.json"), vendorV076()); } catch (IOException e) { return null; } }

    /*
     * Transmit deviation trim. V076J's shaper multiplies each RRC output sample by 1692 (movi r1 at 0x5635C), shifts
     * right by 15 and clamps to +-6451 (movi r1 at 0x56364, literal -6451 at 0x564FC). Scaling the multiplier and both
     * clamp bounds by the same factor scales the deviation linearly. A trimmed image carries the tag V076X.
     */
    static final int SCALE_AT = 0x5635C, CLAMP_HI_AT = 0x56364, CLAMP_LO_AT = 0x564FC, SCALE = 1692, CLAMP = 6451;
    static final double GAIN_MIN = 0.5, GAIN_MAX = 1.8;

    static byte[] trim(byte[] j, double gain) {
        if (j == null) return null;
        if (Math.abs(gain - 1.0) < 0.005) return j;
        if (gain < GAIN_MIN || gain > GAIN_MAX) return null;
        if (!movi(j, SCALE_AT, 1, SCALE) || !movi(j, CLAMP_HI_AT, 1, CLAMP) || le32(j, CLAMP_LO_AT) != -CLAMP) return null;
        byte[] x = j.clone();
        int sc = (int) Math.round(SCALE * gain), cl = (int) Math.round(CLAMP * gain);
        x[SCALE_AT + 2] = (byte) sc; x[SCALE_AT + 3] = (byte) (sc >> 8);
        x[CLAMP_HI_AT + 2] = (byte) cl; x[CLAMP_HI_AT + 3] = (byte) (cl >> 8);
        int lo = -cl;
        for (int i = 0; i < 4; i++) x[CLAMP_LO_AT + i] = (byte) (lo >> (8 * i));
        x[TAG_AT + V076_TAG.length()] = 'X';
        return x;
    }
    private static boolean movi(byte[] b, int at, int rz, int imm) {
        return (b[at] & 0xFF) == rz && (b[at + 1] & 0xFF) == 0xEA && (b[at + 2] & 0xFF) == (imm & 0xFF) && (b[at + 3] & 0xFF) == (imm >> 8);
    }
    private static int le32(byte[] b, int at) { return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24; }

    /** The image Load writes: V076J, or V076X with the deviation trim {@code gain}. */
    double gain = 1.0;
    byte[] m17Image() { return trim(v076j(), gain); }

    static String tagOf(byte[] image) {
        if (image == null || image.length < TAG_AT + 16) return "";
        int n = 0;
        while (n < 20 && TAG_AT + n < image.length && image[TAG_AT + n] != 0) n++;
        return new String(image, TAG_AT, n, StandardCharsets.US_ASCII).trim();
    }

    enum Variant { KNOWN, NEWER, UNKNOWN }
    private static final Pattern TAG = Pattern.compile("DMR006\\.U4A\\.V(\\d{3})([A-Z]?)");
    static Variant variant(String tag) {
        if (tag == null) return Variant.UNKNOWN;
        Matcher m = TAG.matcher(tag.trim());
        if (!m.matches()) return Variant.UNKNOWN;
        int v = Integer.parseInt(m.group(1)); boolean ours = !m.group(2).isEmpty();
        if (v == 67 && !ours) return Variant.KNOWN;
        if (v == 76) return Variant.KNOWN;
        if (v > 76 && !ours) return Variant.NEWER;
        return Variant.UNKNOWN;
    }

    // ---- preflight ----

    /** Why nothing can start, or null. Decided before the port is touched. */
    String refusal(String moduleTag, String bridgeDate, byte[] image) {
        if (!Root.run("[ -x " + DFU_UTIL + " ] && echo ok").endsWith("ok")) return "the phone's flasher " + DFU_UTIL + " is missing";
        if (!STOCK_BRIDGE_SHA.equals(sha256(stockBridge() == null ? new byte[0] : stockBridge()))) return "the stock bridge file " + STOCK_BRIDGE_FILE + " is missing or not the known one";
        if (p1Bridge() == null) return "the loader bridge (P1) could not be built from the stock bridge file";
        if (!(STOCK_BRIDGE_DATE.equals(bridgeDate) || loaderBridge(bridgeDate))) return "the bridge answers \"" + bridgeDate + "\", not a bridge build the loader knows";
        if (image == null) return "the image could not be built (" + VENDOR_V076_FILE + " missing or not V076, sha256 " + V076_SHA.substring(0, 8) + "…)";
        if (variant(tagOf(image)) != Variant.KNOWN) return "the image tag " + tagOf(image) + " is not a V076 build";
        if (variant(moduleTag) == Variant.UNKNOWN) return "the module answers \"" + moduleTag + "\", which the loader does not know";
        return null;
    }

    // ---- the bridge's ROM DFU ----

    static String enterScript() {
        return "echo 0 > " + Bridge.MCU_POWER + "; k=0; while [ -e /sys/class/tty/ttyACM0 ] && [ $k -lt 30 ]; do sleep 0.2; k=$((k+1)); done; sleep 0.3; "
                + "echo 1 > " + Bridge.USB_PATH + "; echo 1 > " + Bridge.RSTMCU + "; echo 1 > " + Bridge.MCU_POWER + "; "
                + "i=0; while ! " + DFU_UTIL + " -l 2>/dev/null | grep -q '" + DFU_ID + "' && [ $i -lt 20 ]; do sleep 0.5; i=$((i+1)); done; "
                + "if " + DFU_UTIL + " -l 2>/dev/null | grep -q '" + DFU_ID + "'; then echo \"dfu: up after $((i*5)) tenths\"; else echo 'dfu: none'; fi";
    }
    static String leaveScript() { return "echo 0 > " + Bridge.RSTMCU + "; echo 0 > " + Bridge.MCU_POWER + "; sleep 1; echo 'dfu: left'"; }
    static String firstBootScript() {
        String p = Bridge.PORT;
        return "echo 0 > " + Bridge.RSTMCU + "; echo 1 > " + Bridge.USB_PATH + "; echo 1 > " + Bridge.MCU_POWER + "; sleep 0.75; echo 0 > " + Bridge.RSTMCU + "; "
                + "i=0; up=0; while [ $i -lt 90 ]; do if [ -e " + p + " ]; then up=$((up+1)); [ $up -ge 2 ] && break; else up=0; fi; sleep 0.5; i=$((i+1)); done; "
                + "[ $up -lt 2 ] && echo 'first boot: no port in 45 s'; "
                + "if [ -e " + p + " ]; then echo \"first boot: port after $((i*5)) tenths\"; else echo 'first boot: no port'; fi; "
                + "echo 0 > " + Bridge.MCU_POWER + "; k=0; while [ -e /sys/class/tty/ttyACM0 ] && [ $k -lt 30 ]; do sleep 0.2; k=$((k+1)); done; sleep 0.3";
    }
    static String uploadScript(String path) {
        return "rm -f '" + path + "'; timeout 120 " + DFU_UTIL + " -d " + DFU_ID + " -a 0 -s 0x08000000:0x" + Integer.toHexString(FLASH_BYTES) + " -U '" + path + "' 2>&1 | tail -3; "
                + "echo \"upload size $(stat -c %s '" + path + "' 2>/dev/null)\"";
    }
    static String downloadScript(String path) {
        return "timeout 120 " + DFU_UTIL + " -d " + DFU_ID + " -a 0 -s 0x08000000 -D '" + path + "' 2>&1 | tail -3; echo 'download done'";
    }

    static boolean readbackMatches(byte[] rb, byte[] image) {
        if (rb == null || image == null || rb.length < image.length) return false;
        for (int i = 0; i < image.length; i++) if (rb[i] != image[i]) return false;
        for (int i = image.length; i < rb.length; i++) if (rb[i] != (byte) 0xFF) return false;
        return true;
    }

    private boolean dfuEnter() { String o = Root.power(enterScript()); ui.log("dfu enter: " + o.replace('\n', ' ')); return o.contains("dfu: up"); }
    private void dfuLeave() { ui.log("dfu leave: " + Root.power(leaveScript()).replace('\n', ' ')); }

    /** The whole bridge flash, read through the ROM DFU; null on failure. */
    byte[] bridgeReadback(File keep) {
        String tmp = "/data/local/tmp/atom17_bridge_readback.bin";
        try {
            if (!dfuEnter()) { ui.log("the bridge's ROM DFU did not appear; nothing written"); return null; }
            ui.log("dfu upload: " + Root.power(uploadScript(tmp)).replace('\n', ' '));
        } finally { dfuLeave(); }
        String cp = Root.run("cp '" + tmp + "' '" + keep.getAbsolutePath() + "' && chown " + android.os.Process.myUid() + ":" + android.os.Process.myUid() + " '" + keep.getAbsolutePath() + "' && rm -f '" + tmp + "' && echo ok");
        if (!cp.endsWith("ok")) { ui.log("read-back not copied: " + cp); return null; }
        try { byte[] rb = Files.readAllBytes(keep.toPath()); return rb.length == FLASH_BYTES ? rb : null; } catch (IOException e) { return null; }
    }

    /** Write a bridge image and verify the read-back (image + 0xFF). Two DFU entries with a full power-down between. */
    boolean bridgeWrite(byte[] image, String name) {
        File f = new File(ctx.getFilesDir(), name);
        try { Files.write(f.toPath(), image); } catch (IOException e) { ui.log("copy: " + e.getMessage()); return false; }
        try {
            if (!dfuEnter()) { ui.log("the bridge's ROM DFU did not appear; nothing written"); return false; }
            ui.log("dfu download: " + Root.power(downloadScript(f.getAbsolutePath())).replace('\n', ' '));
        } finally { dfuLeave(); }
        byte[] rb = bridgeReadback(new File(ctx.getFilesDir(), "bridge-after.bin"));
        boolean ok = readbackMatches(rb, image);
        ui.log("bridge " + name + (ok ? ": written and verified" : ": read-back does NOT match"));
        return ok;
    }

    /** The first boot after DFU: no cable-mode toggle; true when the port came back. Then the radio opens normally. */
    boolean firstBoot() {
        String o = Root.power(firstBootScript());
        ui.log("first boot: " + o.replace('\n', ' '));
        if (!o.contains("first boot: port after")) return false;
        try { radio.open(); return true; } catch (IOException e) { ui.log("reopen: " + e.getMessage()); return false; }
    }

    // ---- the module's loader through P1 / B4 ----

    /** Module bytes come back one per bridge cmd-09 reply; bytes to the module go in cmd 09, at most 256 per envelope. */
    final class LoaderWire {
        private final ArrayDeque<Integer> module = new ArrayDeque<>();
        private final byte[] buf = new byte[2048];
        private int n;
        private String bridge;
        private boolean updating;

        void write(byte[] b) throws IOException {
            for (int off = 0; off < b.length; off += 256) {
                int len = Math.min(256, b.length - off);
                byte[] body = new byte[len]; System.arraycopy(b, off, body, 0, len);
                radio.writeRaw(Agold.request(0x00, 0x09, body));
            }
        }

        int read(int timeoutMs) throws IOException {
            long end = System.currentTimeMillis() + Math.max(0, timeoutMs);
            while (module.isEmpty()) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return -1;
                int b = radio.readRaw((int) left);
                if (b < 0) return -1;
                feed(b);
            }
            return module.poll();
        }

        void leave() {
            if (!updating) return;
            updating = false;
            try { radio.writeRaw(Agold.request(0x00, 0x08, 0x00)); } catch (IOException e) { ui.log("update flag off: " + e.getMessage()); }
        }

        private void feed(int b) {
            if (n == buf.length) { System.arraycopy(buf, 1, buf, 0, n - 1); n--; }
            buf[n++] = (byte) b;
            Agold.Frame[] one = new Agold.Frame[1];
            while (n > 0) {
                int k = Agold.tryDecode(buf, 0, n, one);
                if (k == 0) return;
                if (k < 0) { shift(1); continue; }
                Agold.Frame f = one[0];
                if (f != null && f.route == 0 && f.cmd == 0x09 && f.rw == 0) for (byte x : f.body) module.add(x & 0xFF);
                else if (f != null && f.route == 0 && f.cmd == 0x01) bridge = new String(f.body, StandardCharsets.US_ASCII).trim();
                shift(k);
            }
        }
        private void shift(int k) { System.arraycopy(buf, k, buf, 0, n - k); n -= k; }

        /** Bridge version, then update mode on. False when the bridge is not a loader bridge (nothing sent to the module). */
        boolean enter() throws IOException {
            bridge = null;
            radio.writeRaw(Agold.request(0x00, 0x01, 0x01));
            long end = System.currentTimeMillis() + 2000;
            while (bridge == null && System.currentTimeMillis() < end) { int b = radio.readRaw((int) Math.max(1, end - System.currentTimeMillis())); if (b < 0) break; feed(b); }
            if (!loaderBridge(bridge)) { ui.log("loader: the bridge is " + bridge + ", not a loader bridge; nothing sent to the module"); return false; }
            radio.writeRaw(Agold.request(0x00, 0x08, 0x01));
            updating = true;
            module.clear();
            return true;
        }

        String menu(char key, String until, int ms) throws IOException {
            module.clear();
            write(new byte[] { (byte) key });
            StringBuilder sb = new StringBuilder();
            long end = System.currentTimeMillis() + ms;
            while (sb.indexOf(until) < 0) { int b = read((int) Math.max(0, end - System.currentTimeMillis())); if (b < 0) break; sb.append((char) b); }
            return sb.toString();
        }
    }

    /** Read-only hardware check: '6' must be HardVersion 1.1, '7' must start "002"; then '3' runs the installed firmware. */
    private boolean probe() {
        boolean ok = false;
        LoaderWire w = new LoaderWire();
        try {
            radio.loaderOpen();
            radio.startDivert();
            if (!w.enter()) return false;
            String six = w.menu('6', HARD_VERSION, 10_000);
            if (!six.contains(HARD_VERSION)) { ui.log("hardware: '6' answered " + six.trim()); return false; }
            w.module.clear();
            w.write(new byte[] { '7' });
            StringBuilder hex = new StringBuilder();
            long e7 = System.currentTimeMillis() + 2500;
            while (true) { int b = w.read((int) Math.max(0, e7 - System.currentTimeMillis())); if (b < 0) break; hex.append(String.format(java.util.Locale.US, hex.length() > 0 ? " %02X" : "%02X", b)); }
            ok = hex.toString().startsWith("30 30 32");
            ui.log("hardware: " + HARD_VERSION + ", '7' = [" + hex + "]" + (ok ? " (the tested unit's hardware)" : " (DIFFERENT from the tested unit)"));
            w.module.clear();
            w.write(new byte[] { '3' });                              // run the installed application; nothing written
            long e3 = System.currentTimeMillis() + 3000;
            while (w.read((int) Math.max(0, e3 - System.currentTimeMillis())) >= 0) { }
        } catch (IOException e) { ui.log("probe: " + e.getMessage()); ok = false; }
        finally {
            w.leave();
            Bridge.pttIdle();
            radio.stopDivert();
            try { radio.powerCycle(); } catch (IOException e) { ui.log("probe power-up: " + e.getMessage()); }
        }
        return ok;
    }

    // ---- YModem, stop-and-wait, SOH 128 (SignalDeck's YModemSender as the Atom XL uses it: zero-padded last block) ----
    static final int SOH = 0x01, EOT = 0x04, ACK = 0x06, NAK = 0x15, CAN = 0x18, CRC_REQ = 0x43;
    static final int BLOCK_MS = 15_000, HEADER_MS = 60_000, RETRIES = 6;

    static int crc16(byte[] d, int off, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (d[off + i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xFFFF;
        }
        return crc;
    }
    static byte[] block(int seq, byte[] data) {
        byte[] out = new byte[3 + data.length + 2];
        out[0] = (byte) SOH; out[1] = (byte) seq; out[2] = (byte) (seq ^ 0xFF);
        System.arraycopy(data, 0, out, 3, data.length);
        int crc = crc16(data, 0, data.length);
        out[out.length - 2] = (byte) (crc >> 8); out[out.length - 1] = (byte) crc;
        return out;
    }

    private String fail = "timeout";
    private boolean ack(LoaderWire w, byte[] packet, boolean thenC) throws IOException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            w.write(packet);
            long end = System.currentTimeMillis() + (thenC ? HEADER_MS : BLOCK_MS);
            boolean sawAck = false;
            while (System.currentTimeMillis() < end) {
                int b = w.read((int) Math.max(1, end - System.currentTimeMillis()));
                if (b < 0) { fail = "timeout"; break; }
                if (b == CAN) { fail = "cancelled"; return false; }
                if (b == NAK) { fail = "nak"; break; }
                if (b == ACK) { if (!thenC) return true; sawAck = true; continue; }
                if (b == CRC_REQ && sawAck) return true;
            }
            if (attempt == RETRIES) return false;
            ui.log("retry " + (attempt + 1));
        }
        return false;
    }

    private void drain(LoaderWire w) throws IOException { while (w.read(20) >= 0) { } }

    /** Load one image. Returns the module's version after it, or null when the transfer did not finish. */
    private String transfer(byte[] image, String name) {
        LoaderWire w = new LoaderWire();
        boolean diverted = false;
        String tag = null;
        try {
            radio.loaderOpen();
            radio.startDivert();
            diverted = true;
            ui.phase("loader");
            if (!w.enter()) return null;
            String six = w.menu('6', HARD_VERSION, 10_000);
            if (!six.contains(HARD_VERSION)) { ui.log("loader: '6' not answered with " + HARD_VERSION + "; nothing sent"); return null; }
            w.module.clear();
            w.write(new byte[] { '1' });
            long t0 = System.currentTimeMillis(), end = t0 + 30_000;
            while (true) {
                int b = w.read((int) Math.max(0, end - System.currentTimeMillis()));
                if (b < 0) { ui.log("loader: no C within 30 s of '1'"); return null; }
                if (b == CRC_REQ) break;
            }
            ui.log("loader: C " + (System.currentTimeMillis() - t0) + " ms after '1'");
            Bridge.pttIdle();

            ui.phase("erasing");
            byte[] hdr = new byte[128];
            byte[] text = (name + "\0" + image.length).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(text, 0, hdr, 0, Math.min(text.length, 128));
            if (!ack(w, block(0, hdr), true)) { ui.log("header: " + fail); return null; }

            ui.phase("transfer");
            int blocks = (image.length + 127) / 128, seq = 1;
            for (int i = 0; i < blocks; i++) {
                if (halt.get()) { ui.log("stopped after block " + i + " of " + blocks); return null; }
                drain(w);
                byte[] data = new byte[128];
                int k = Math.min(128, image.length - i * 128);
                System.arraycopy(image, i * 128, data, 0, k);
                if (!ack(w, block(seq & 0xFF, data), false)) { ui.log("block " + (i + 1) + ": " + fail); return null; }
                seq++;
                if ((i & 15) == 0 || i + 1 == blocks) ui.progress(i + 1, blocks);
            }
            ui.phase("finish");
            boolean eotOk = false;
            for (int a = 0; a <= RETRIES && !eotOk; a++) {
                w.write(new byte[] { (byte) EOT });
                long e = System.currentTimeMillis() + BLOCK_MS;
                while (System.currentTimeMillis() < e) { int b = w.read((int) Math.max(1, e - System.currentTimeMillis())); if (b < 0 || b == NAK) break; if (b == CAN) return null; if (b == ACK) { eotOk = true; break; } }
            }
            if (!eotOk) { ui.log("EOT not acknowledged"); return null; }
            if (!ack(w, block(0, new byte[128]), false)) { ui.log("end block: " + fail); return null; }

            ui.phase("restart");
            w.leave();
            radio.stopDivert();
            diverted = false;
            radio.powerCycle();                                  // PTT idle: a normal power-up runs the new image
            Thread.sleep(3000);
            for (int pass = 0; pass < 3 && tag == null; pass++) {
                String v = radio.version();
                if (v != null && v.contains("DMR")) tag = v;
                else { Thread.sleep(1000); }
            }
            if (tag == null) tag = "";
            ui.log("module answers " + tag);
        } catch (Exception e) {
            ui.log("transfer: " + e);
        } finally {
            w.leave();
            Bridge.pttIdle();
            if (diverted) radio.stopDivert();
            if (tag == null) {
                radio.shutdown();
                ui.log("not finished: the module stays in its loader, the radio is off (PTT released). Restore loads V076.");
            }
        }
        return tag;
    }

    private void restorePowerUp() { try { if (!radio.isOpen()) radio.open(); } catch (IOException e) { ui.log("power-up: " + e.getMessage()); } }

    // ---- the sequences ----

    /** Load V076J (or, with {@code restore}, the vendor V076). Returns a one-line outcome. */
    String run(boolean restore, String moduleTag) {
        halt.set(false);
        ui.phase("preparing");
        byte[] image = restore ? vendorV076() : m17Image();
        String want = restore ? V076_TAG : tagOf(image);
        String bridgeDate;
        try { if (!radio.isOpen()) radio.open(); bridgeDate = radio.bridgeDate(); } catch (IOException e) { return "REFUSED: the radio did not come up: " + e.getMessage(); }
        ui.log("bridge " + bridgeDate + ", module " + moduleTag + ", image " + tagOf(image) + (image != null ? " sha256 " + sha256(image).substring(0, 16) + "…" : ""));
        if (!restore) {
            String why = refusal(moduleTag, bridgeDate, image);
            if (why != null) return "REFUSED: " + why;
            if (variant(moduleTag) == Variant.NEWER && !ui.confirmReplace(moduleTag)) return "NOT CONFIRMED: " + moduleTag + " stays installed";
        } else {
            if (image == null) return "REFUSED: " + VENDOR_V076_FILE + " is missing or not the vendor V076";
            if (!STOCK_BRIDGE_SHA.equals(sha256(stockBridge() == null ? new byte[0] : stockBridge())) || p1Bridge() == null) return "REFUSED: the stock bridge file is missing or not the known one";
        }

        ui.phase("bridge backup");
        radio.closePort();
        byte[] rb = bridgeReadback(new File(ctx.getFilesDir(), "bridge-backup.bin"));
        boolean isStock = readbackMatches(rb, stockBridge()), isP1 = readbackMatches(rb, p1Bridge()), isB4 = false;
        if (!isStock && !isP1) try { isB4 = readbackMatches(rb, applyPatch(asset("bridge-b4.patch.json"), stockBridge())); } catch (IOException ignored) { }
        if (!isStock && !isP1 && !isB4) { restorePowerUp(); return "BRIDGE UNKNOWN: the read-back is neither the stock nor a loader bridge; nothing written"; }
        ui.log("bridge read back: " + (isStock ? "stock" : isP1 ? "P1" : "B4") + " (kept in the app's files as bridge-backup.bin)");

        if (isStock) {
            ui.phase("loader bridge");
            if (!bridgeWrite(p1Bridge(), "bridge-p1.bin")) return bridgeFailure("the loader bridge did not verify");
            if (!firstBoot()) return "NO FIRST BOOT after the loader bridge: restart the phone, then Restore";
        } else if (!firstBoot()) return "NO FIRST BOOT: restart the phone, then Restore";

        if (!restore) {
            ui.phase("hardware check");
            if (!probe()) return stockBridgeBack("HARDWARE DIFFERS from the tested unit: nothing was written to the module");
        }

        ui.phase("transfer");
        String got = transfer(image, want + ".bin");
        if (got == null) return "TRANSFER FAILED: the module is in its loader with the loader bridge kept; use Restore";
        String result = "LOADED " + got;
        if (!want.equals(got)) {
            ui.log("tag " + got + " is not " + want + "; loading the restore image");
            byte[] v076 = vendorV076();
            String back = v076 == null ? null : transfer(v076, V076_TAG + ".bin");
            if (back == null) return "TRANSFER FAILED during the restore: use Restore";
            result = V076_TAG.equals(back) ? "RESTORED V076 after the wrong tag " + got : "RESTORE FAILED: module answers " + back;
        }
        return stockBridgeBack(result);
    }

    private String stockBridgeBack(String before) {
        ui.phase("stock bridge");
        radio.closePort();
        if (!bridgeWrite(stockBridge(), "bridge-stock.bin")) return "STOCK BRIDGE FAILED (" + before + "): the loader bridge stays; Restore or retry";
        if (!firstBoot()) return "NO FIRST BOOT after the stock bridge (" + before + "): restart the phone";
        try {
            String d = radio.bridgeDate();
            if (!STOCK_BRIDGE_DATE.equals(d)) return "STOCK BRIDGE FAILED: bridge answers " + d + " (" + before + ")";
        } catch (IOException e) { return "STOCK BRIDGE: no answer (" + before + ")"; }
        return before;
    }

    private String bridgeFailure(String what) {
        boolean back = bridgeWrite(stockBridge(), "bridge-stock.bin") && firstBoot();
        return "BRIDGE WRITE FAILED: " + what + (back ? "; the stock bridge is back" : "; the stock bridge did not verify either: restart the phone, then Restore");
    }
}
