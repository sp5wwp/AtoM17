/*
 * AtoM17 - the Agold bridge envelope. Copyright (C) 2026 IIMacGyverII (SignalDeck: AgoldFrames, AgoldEnvelope);
 * modified for AtoM17, 2026. See COPYRIGHT.SignalDeck. SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import java.util.Arrays;

/**
 * {@code 7E 96 69 | route | cmd | rw | len (BE) | body | checksum (BE) | 81}. Route 00 = the bridge, 01 = the module,
 * 02 = the bridge's audio reports. rw 01 = request, 00 = reply or push. The checksum is the stock app's
 * IComUtils.checkSum: one's-complement sum of big-endian words over route … body, folded and inverted.
 */
public final class Agold {
    private Agold() { }

    public static final int ROUTE_BRIDGE = 0, ROUTE_MODULE = 1, ROUTE_AUDIO = 2;

    /** One decoded envelope. */
    public static final class Frame {
        public final int route, cmd, rw;
        public final byte[] body;
        Frame(int route, int cmd, int rw, byte[] body) { this.route = route; this.cmd = cmd; this.rw = rw; this.body = body; }
        /** The one-byte result of a set command (0 = ok), or -1. */
        public int result() { return body.length == 1 ? body[0] & 0xFF : -1; }
        @Override public String toString() { return String.format(java.util.Locale.US, "r%02X c%02X rw%d [%s]", route, cmd, rw, hex(body, Math.min(32, body.length))); }
    }

    static int checksum(int[] b, int from, int to) {
        int s = 0, i = from;
        for (; i + 1 < to; i += 2) s += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        if (i < to) s += (b[i] & 0xFF) << 8;
        while ((s >> 16) > 0) s = (s & 0xFFFF) + (s >> 16);
        return s ^ 0xFFFF;
    }

    /** A request (rw 01). */
    public static byte[] request(int route, int cmd, byte[] data) {
        int n = data == null ? 0 : data.length;
        int[] f = new int[11 + n];
        f[0] = 0x7E; f[1] = 0x96; f[2] = 0x69; f[3] = route; f[4] = cmd; f[5] = 1; f[6] = n >> 8; f[7] = n & 0xFF;
        for (int i = 0; i < n; i++) f[8 + i] = data[i] & 0xFF;
        int c = checksum(f, 3, 8 + n);
        f[8 + n] = c >> 8; f[9 + n] = c & 0xFF; f[10 + n] = 0x81;
        byte[] out = new byte[f.length];
        for (int i = 0; i < f.length; i++) out[i] = (byte) f[i];
        return out;
    }

    public static byte[] request(int route, int cmd, int... data) {
        byte[] b = new byte[data.length];
        for (int i = 0; i < data.length; i++) b[i] = (byte) data[i];
        return request(route, cmd, b);
    }

    /**
     * Decode at {@code off}: the length consumed (out[0] set for a valid envelope), 0 when more bytes are needed, -1 when
     * the byte at {@code off} does not start an envelope (skip it). Zero bytes are USB padding and are consumed.
     */
    public static int tryDecode(byte[] b, int off, int avail, Frame[] out) {
        out[0] = null;
        if (avail < 1) return 0;
        if (b[off] == 0) { int z = 1; while (z < avail && b[off + z] == 0) z++; return z; }
        if ((b[off] & 0xFF) != 0x7E) return -1;
        if (avail < 3) return 0;
        if ((b[off + 1] & 0xFF) != 0x96 || (b[off + 2] & 0xFF) != 0x69) return -1;
        if (avail < 8) return 0;
        int len = ((b[off + 6] & 0xFF) << 8) | (b[off + 7] & 0xFF);
        if (len > 1024) return -1;
        int total = 11 + len;
        if (avail < total) return 0;
        if ((b[off + total - 1] & 0xFF) != 0x81) return -1;
        int[] u = new int[total];
        for (int i = 0; i < total; i++) u[i] = b[off + i] & 0xFF;
        int end = 8 + len;
        if (checksum(u, 3, end) != ((u[end] << 8) | u[end + 1])) return -1;
        out[0] = new Frame(u[3], u[4], u[5], Arrays.copyOfRange(b, off + 8, off + end));
        return total;
    }

    static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format(java.util.Locale.US, "%02X ", b[i] & 0xFF));
        return sb.toString().trim();
    }
}
