/*
 * AtoM17 - Copyright (C) 2026 IIMacGyverII (SignalDeck, ham/M17.java); modified for AtoM17, 2026:
 * package, no Android string resources. See COPYRIGHT.SignalDeck. SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl.dfe;



import java.util.Locale;

/**
 * The M17 protocol (M17 Project specification): 4FSK at 4800 symbols/s, 40 ms frames of an 8-symbol sync word and 184
 * payload symbols; the Link Setup Frame (LSF: destination, source, type, META, CRC) and stream frames (a Golay(24,12)
 * coded LICH carrying a sixth of the LSF, then the frame number and 16 bytes of Codec 2 3200 voice), coded with the
 * K=5 convolutional code (punctured), the quadratic-permutation interleaver and the randomizer. Written from the
 * specification's definitions; encode (for tests and the receiver's adaptation) and soft-decision decode.
 */
public final class M17 {
    private M17() { }

    public static final int SYM_RATE = 4800, SYNC_LSF = 0x55F7, SYNC_STR = 0xFF5D, SYNC_PKT = 0x75FF, SYNC_BERT = 0xDF55, EOT = 0x555D;
    /** Dibit -> symbol (the specification's mapping): 00 +1, 01 +3, 10 -1, 11 -3. */
    static final int[] SYMBOL_MAP = { +1, +3, -1, -3 };
    static final int[] EOT_SYMBOLS = { +3, +3, +3, +3, +3, +3, -3, +3 };
    static final byte[] P1 = { 1,1,0,1,1,1,0,1,1,1,0,1,1,1,0,1,1, 1,0,1,1,1,0,1,1,1,0,1,1,1,0,1,1, 1,0,1,1,1,0,1,1,1,0,1,1,1,0,1,1, 1,0,1,1,1,0,1,1,1,0,1,1 };
    static final byte[] P2 = { 1,1,1,1,1,1,1,1,1,1,1,0 };
    /** Packet frames (spec appendix E): keep seven, drop the eighth. */
    static final byte[] P3 = { 1,1,1,1,1,1,1,0 };
    private static final int[] RAND = { 0xD6, 0xB5, 0xE2, 0x30, 0x82, 0xFF, 0x84, 0x62, 0xBA, 0x4E, 0x96, 0x90, 0xD8, 0x98, 0xDD, 0x5D, 0x0C, 0xC8, 0x52, 0x43, 0x91, 0x1D, 0xF8,
        0x6E, 0x68, 0x2F, 0x35, 0xDA, 0x14, 0xEA, 0xCD, 0x76, 0x19, 0x8D, 0xD5, 0x80, 0xD1, 0x33, 0x87, 0x13, 0x57, 0x18, 0x2D, 0x29, 0x78, 0xC3 };
    private static final int[] GOLAY = { 0x8eb, 0x93e, 0xa97, 0xdc6, 0x367, 0x6cd, 0xd99, 0x3da, 0x7b4, 0xf68, 0x63b, 0xc75 };
    static final String CHARS = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-/.";
    static final int[] INTRL = new int[368];
    private static final int[] GOLAY_TABLE = new int[4096];
    static {
        for (int i = 0; i < 368; i++) INTRL[i] = (int) ((45L * i + 92L * i * i) % 368);
        for (int d = 0; d < 4096; d++) GOLAY_TABLE[d] = golay(d);
    }
    static int randBit(int i) { return (RAND[i >> 3] >> (7 - (i & 7))) & 1; }

    public static int crc16(byte[] d, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (d[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) { crc <<= 1; if ((crc & 0x10000) != 0) crc = (crc ^ 0x5935) & 0xFFFF; }
        }
        return crc & 0xFFFF;
    }

    /** A callsign as the 48-bit base-40 address (6 bytes, big-endian); "@ALL" is the broadcast address. */
    public static byte[] callsign(String s) {
        long v;
        if (s.equals("@ALL")) v = 0xFFFFFFFFFFFFL;
        else { v = 0; String u = s.toUpperCase(Locale.US); for (int i = Math.min(9, u.length()) - 1; i >= 0; i--) { int c = CHARS.indexOf(u.charAt(i)); v = v * 40 + Math.max(0, c); } }
        byte[] b = new byte[6]; for (int i = 5; i >= 0; i--) { b[i] = (byte) v; v >>>= 8; } return b;
    }
    public static String callsign(byte[] b, int off) {
        long v = 0; for (int i = 0; i < 6; i++) v = (v << 8) | (b[off + i] & 0xFF);
        if (v == 0xFFFFFFFFFFFFL) return "@ALL";
        if (v == 0) return "";
        if (v >= 262144000000000L) return v < 268697600000000L ? "#" + base40(v - 262144000000000L) : "?";   // # (hash) address range, reserved
        return base40(v);
    }
    private static String base40(long v) { StringBuilder sb = new StringBuilder(); while (v > 0) { sb.append(CHARS.charAt((int) (v % 40))); v /= 40; } return sb.toString().trim(); }

    static int golay(int d) { int c = 0; for (int i = 0; i < 12; i++) if ((d & (1 << i)) != 0) c ^= GOLAY[i]; return (d << 12) | c; }
    /** Nearest Golay(24,12) codeword: {data, bit errors}. */
    static int[] golayDecode(int cw) { int best = 0, bd = 99; for (int d = 0; d < 4096; d++) { int e = Integer.bitCount(GOLAY_TABLE[d] ^ cw); if (e < bd) { bd = e; best = d; } } return new int[] { best, bd }; }

    // ---- encoding ------------------------------------------------------------------------------------

    static byte[] bits(byte[] b, int n) { byte[] o = new byte[n]; for (int i = 0; i < n; i++) o[i] = (byte) ((b[i >> 3] >> (7 - (i & 7))) & 1); return o; }
    static byte[] convEncode(byte[] in, byte[] punct, int outLen) {
        byte[] out = new byte[outLen]; int sr = 0, p = 0, o = 0;
        for (int i = 0; i < in.length + 4; i++) {
            int b = i < in.length ? in[i] : 0;
            int g1 = b ^ ((sr >> 2) & 1) ^ ((sr >> 3) & 1), g2 = b ^ (sr & 1) ^ ((sr >> 1) & 1) ^ ((sr >> 3) & 1);
            sr = ((sr << 1) | b) & 0xF;
            if (punct[p] != 0 && o < outLen) out[o++] = (byte) g1; p = (p + 1) % punct.length;
            if (punct[p] != 0 && o < outLen) out[o++] = (byte) g2; p = (p + 1) % punct.length;
        }
        return out;
    }
    static int[] frameSymbols(int sync, byte[] type2) {
        int[] s = new int[192];
        for (int i = 0; i < 8; i++) s[i] = SYMBOL_MAP[(sync >> (14 - 2 * i)) & 3];
        for (int i = 0; i < 184; i++) {
            int b1 = type2[INTRL[2 * i]] ^ randBit(2 * i), b0 = type2[INTRL[2 * i + 1]] ^ randBit(2 * i + 1);
            s[8 + i] = SYMBOL_MAP[b1 * 2 + b0];
        }
        return s;
    }
    /** Stream + Codec 2 voice, no encryption. The channel access number (0–15) is TYPE bits 10:7. CAN 0 is 0x0005. */
    public static int streamVoice(int can) {
        int n = can < 0 ? 0 : Math.min(15, can);
        return 0x0005 | (n << 7);
    }

    /** The 30-byte LSF: destination, source, type, META (14 bytes), CRC. Type 0x0005 = stream, voice, no encryption, CAN 0. */
    public static byte[] lsf(String src, String dst, int type, byte[] meta) {
        byte[] d = new byte[30];
        System.arraycopy(callsign(dst), 0, d, 0, 6); System.arraycopy(callsign(src), 0, d, 6, 6);
        d[12] = (byte) (type >> 8); d[13] = (byte) type;
        if (meta != null) System.arraycopy(meta, 0, d, 14, Math.min(14, meta.length));
        int c = crc16(d, 28); d[28] = (byte) (c >> 8); d[29] = (byte) c;
        return d;
    }
    /** A callsign the air will accept: the charset, at most 9 characters, SSID removed. Empty becomes N0CALL. */
    public static String voiceCall(String raw) {
        if (raw == null) return "N0CALL";
        String u = raw.trim().toUpperCase(Locale.US);
        int dash = u.indexOf('-');
        if (dash >= 0) u = u.substring(0, dash);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < u.length() && sb.length() < 9; i++) if (CHARS.indexOf(u.charAt(i)) >= 0) sb.append(u.charAt(i));
        return sb.length() == 0 ? "N0CALL" : sb.toString();
    }

    /** Four symbols per byte, first symbol in bits 7:6. 01 = +3, 00 = +1, 10 = -1, 11 = -3. */
    public static byte[] packSymbols(int[] syms) {
        if ((syms.length & 3) != 0) throw new IllegalArgumentException("symbol count " + syms.length);
        byte[] o = new byte[syms.length / 4];
        for (int i = 0, n = 0; i < syms.length; i += 4, n++) {
            int b = 0;
            for (int k = 0; k < 4; k++) {
                int s = syms[i + k];
                int d = s == 3 ? 1 : s == 1 ? 0 : s == -1 ? 2 : 3;
                b = (b << 2) | d;
            }
            o[n] = (byte) b;
        }
        return o;
    }

    /** 40 ms of the +3/−3 preamble the specification puts in front of a voice transmission. */
    public static int[] preamble() {
        int[] s = new int[192];
        for (int i = 0; i < s.length; i += 2) { s[i] = 3; s[i + 1] = -3; }
        return s;
    }

    /** The end-of-transmission marker: 40 ms (192 symbols) of the EOT pattern after the end-bit frame, as the
     *  specification has it. The two-marker pad that covered the module's early carrier drop came out with FW20,
     *  which plays the ring out before dropping. */
    public static int[] eotTail() {
        int[] s = new int[192];
        for (int i = 0; i < s.length; i++) s[i] = EOT_SYMBOLS[i % EOT_SYMBOLS.length];
        return s;
    }

    public static int[] lsfSymbols(byte[] lsf) { return frameSymbols(SYNC_LSF, convEncode(bits(lsf, 240), P1, 368)); }
    public static int[] streamSymbols(byte[] lsf, int fn, byte[] payload16, int lichCnt) {
        byte[] lich = new byte[6]; System.arraycopy(lsf, 5 * lichCnt, lich, 0, 5); lich[5] = (byte) (lichCnt << 5);
        long lb = 0; for (byte b : lich) lb = (lb << 8) | (b & 0xFF);
        byte[] t2 = new byte[368];
        for (int k = 0; k < 4; k++) { int cw = golay((int) ((lb >> (36 - 12 * k)) & 0xFFF)); for (int j = 0; j < 24; j++) t2[24 * k + j] = (byte) ((cw >> (23 - j)) & 1); }
        byte[] d = new byte[18]; d[0] = (byte) (fn >> 8); d[1] = (byte) fn; System.arraycopy(payload16, 0, d, 2, 16);
        System.arraycopy(convEncode(bits(d, 144), P2, 272), 0, t2, 96, 272);
        return frameSymbols(SYNC_STR, t2);
    }

    /** A stream frame from its decoded LICH chunk (6 bytes) rather than the whole LSF: what the receiver re-encodes. */
    public static int[] streamSymbolsFromLich(byte[] lich6, int fn, byte[] payload16) {
        long lb = 0; for (int i = 0; i < 6; i++) lb = (lb << 8) | (lich6[i] & 0xFF);
        byte[] t2 = new byte[368];
        for (int k = 0; k < 4; k++) { int cw = golay((int) ((lb >> (36 - 12 * k)) & 0xFFF)); for (int j = 0; j < 24; j++) t2[24 * k + j] = (byte) ((cw >> (23 - j)) & 1); }
        byte[] d = new byte[18]; d[0] = (byte) (fn >> 8); d[1] = (byte) fn; System.arraycopy(payload16, 0, d, 2, 16);
        System.arraycopy(convEncode(bits(d, 144), P2, 272), 0, t2, 96, 272);
        return frameSymbols(SYNC_STR, t2);
    }

    // ---- decoding ------------------------------------------------------------------------------------

    /** 184 payload symbols (outer levels near +-3) -> 368 soft bits (+1 = bit 1), de-randomized and de-interleaved. */
    static float[] softBits(float[] sym, int off) {
        float[] soft = new float[368], out = new float[368];
        for (int i = 0; i < 184; i++) {
            float s = Math.max(-1.5f, Math.min(1.5f, sym[off + i] / 2f));
            float b1 = Math.max(-1, Math.min(1, -s)), b0 = Math.max(-1, Math.min(1, Math.abs(s) * 2 - 2));
            soft[2 * i] = randBit(2 * i) != 0 ? -b1 : b1; soft[2 * i + 1] = randBit(2 * i + 1) != 0 ? -b0 : b0;
        }
        for (int i = 0; i < 368; i++) out[INTRL[i]] = soft[i];
        return out;
    }

    /** Soft Viterbi for the K=5 code; punctured positions count as 0. Returns {bits} and the path metric in metric[0]. */
    static byte[] viterbi(float[] soft, int off, int len, byte[] punct, int nIn, float[] metricOut) {
        int steps = nIn + 4; float[] r = new float[steps * 2]; int p = 0, it = off, end = off + len;
        for (int k = 0; k < steps * 2; k++) { r[k] = punct[p] != 0 && it < end ? soft[it++] : 0f; p = (p + 1) % punct.length; }
        float[] m = new float[16], nm = new float[16]; java.util.Arrays.fill(m, 1e9f); m[0] = 0;
        byte[][] prev = new byte[steps][16]; byte[][] bit = new byte[steps][16];
        for (int t = 0; t < steps; t++) {
            java.util.Arrays.fill(nm, 1e9f);
            float r1 = r[2 * t], r2 = r[2 * t + 1];
            for (int s = 0; s < 16; s++) {
                if (m[s] >= 1e8f) continue;
                for (int b = 0; b < 2; b++) {
                    int g1 = b ^ ((s >> 2) & 1) ^ ((s >> 3) & 1), g2 = b ^ (s & 1) ^ ((s >> 1) & 1) ^ ((s >> 3) & 1);
                    int ns = ((s << 1) | b) & 0xF;
                    float v = m[s] - ((2 * g1 - 1) * r1 + (2 * g2 - 1) * r2);
                    if (v < nm[ns]) { nm[ns] = v; prev[t][ns] = (byte) s; bit[t][ns] = (byte) b; }
                }
            }
            float[] x = m; m = nm; nm = x;
        }
        byte[] out = new byte[nIn]; int s = 0;
        for (int t = steps - 1; t >= 0; t--) { if (t < nIn) out[t] = bit[t][s]; s = prev[t][s]; }
        if (metricOut != null) metricOut[0] = m[0];
        return out;
    }
    static byte[] pack(byte[] bits, int nBytes) { byte[] o = new byte[nBytes]; for (int i = 0; i < nBytes * 8 && i < bits.length; i++) if (bits[i] != 0) o[i >> 3] |= (byte) (0x80 >> (i & 7)); return o; }

    /** A decoded LSF. */
    public static final class Lsf {
        public final byte[] raw; public final boolean crcOk;
        Lsf(byte[] raw, boolean ok) { this.raw = raw; crcOk = ok; }
        public String dst() { return callsign(raw, 0); }
        public String src() { return callsign(raw, 6); }
        public int type() { return ((raw[12] & 0xFF) << 8) | (raw[13] & 0xFF); }
        public boolean stream() { return (type() & 1) != 0; }
        public boolean voice() { return ((type() >> 1) & 3) == 2 || ((type() >> 1) & 3) == 3; }
        public int encryption() { return (type() >> 3) & 3; }
        public int can() { return (type() >> 7) & 0xF; }
        /** The META field as text when it is printable (META text type, no encryption), else null. */
        public String metaText() {
            if (encryption() != 0 || ((type() >> 5) & 3) != 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 14; i < 28; i++) { int c = raw[i] & 0xFF; if (c == 0) break; if (c < 32 || c > 126) return null; sb.append((char) c); }
            String t = sb.toString().trim(); return t.isEmpty() ? null : t;
        }
    }
    public static Lsf decodeLsf(float[] sym, int off) {
        float[] soft = softBits(sym, off);
        byte[] b = pack(viterbi(soft, 0, 368, P1, 240, null), 30);
        return new Lsf(b, crc16(b, 28) == (((b[28] & 0xFF) << 8) | (b[29] & 0xFF)));
    }

    /** A decoded stream frame: frame number (bit 15 = last frame), 16 payload bytes, the LICH chunk (or null). */
    public static final class Stream {
        public final int fn; public final byte[] payload, lich; public final int lichErrors; public final float metric;
        Stream(int fn, byte[] p, byte[] lich, int le, float m) { this.fn = fn; payload = p; this.lich = lich; lichErrors = le; metric = m; }
        public boolean last() { return (fn & 0x8000) != 0; }
        public int lichCount() { return lich == null ? -1 : (lich[5] & 0xFF) >> 5; }
    }
    public static Stream decodeStream(float[] sym, int off) {
        float[] soft = softBits(sym, off);
        long lich = 0; int errs = 0;
        for (int k = 0; k < 4; k++) {
            int cw = 0; for (int j = 0; j < 24; j++) cw = (cw << 1) | (soft[24 * k + j] > 0 ? 1 : 0);
            int[] d = golayDecode(cw); lich = (lich << 12) | d[0]; errs += d[1];
        }
        byte[] lb = new byte[6]; for (int i = 5; i >= 0; i--) { lb[i] = (byte) lich; lich >>>= 8; }
        float[] met = new float[1];
        byte[] b = pack(viterbi(soft, 96, 272, P2, 144, met), 18);
        byte[] pay = java.util.Arrays.copyOfRange(b, 2, 18);
        return new Stream(((b[0] & 0xFF) << 8) | (b[1] & 0xFF), pay, errs <= 8 ? lb : null, errs, met[0]);
    }

    // ---- packet mode (spec sections 2.9 and 3.3; no LICH) ---------------------------------------------

    /** Packet LSF type: packet/stream bit 0 is 0, CAN in bits 10:7, every other bit reserved. */
    public static int packetType(int can) {
        int n = can < 0 ? 0 : Math.min(15, can);
        return n << 7;
    }
    public static byte[] encodePacketLsf(String src, String dst, int can) { return lsf(src, dst, packetType(can), null); }

    public static String protocolName(int id) {
        switch (id) {
            case 0x00: return "raw";
            case 0x01: return "AX.25";   // i18n: protocol name
            case 0x02: return "APRS";
            case 0x03: return "6LoWPAN";
            case 0x04: return "IPv4";
            case 0x05: return "SMS";
            case 0x06: return "Winlink";   // i18n: protocol name
            default: return ("proto " + id);
        }
    }

    /** SMS packet data: type 0x05, the text, a terminating null, then the CRC over those bytes. */
    public static byte[] smsPayload(String text) {
        byte[] t = (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (t.length > 818) t = java.util.Arrays.copyOf(t, 818);
        byte[] d = new byte[1 + t.length + 1 + 2];
        d[0] = 0x05;
        System.arraycopy(t, 0, d, 1, t.length);
        int c = crc16(d, d.length - 2);
        d[d.length - 2] = (byte) (c >> 8); d[d.length - 1] = (byte) c;
        return d;
    }

    /** One packet frame after it has been decoded. `chunk` is 25 bytes; `count` of them are valid. */
    public static final class Packet {
        public final boolean eof; public final int counter; public final byte[] chunk;
        Packet(boolean eof, int counter, byte[] chunk) { this.eof = eof; this.counter = counter; this.chunk = chunk; }
        public int count() { return eof ? counter : 25; }
    }

    /** 200 payload bits, then the 6-bit field: end-of-frame, then the 5-bit counter or byte count. */
    static byte[] packetBits(byte[] chunk, boolean eof, int counter) {
        byte[] bits = new byte[206];
        for (int i = 0; i < 200; i++) bits[i] = (byte) ((chunk[i >> 3] >> (7 - (i & 7))) & 1);
        int meta = ((eof ? 1 : 0) << 5) | (counter & 31);
        for (int i = 0; i < 6; i++) bits[200 + i] = (byte) ((meta >> (5 - i)) & 1);
        return bits;
    }
    public static int[] packetSymbols(byte[] chunk25, boolean eof, int counter) {
        byte[] chunk = new byte[25];
        if (chunk25 != null) System.arraycopy(chunk25, 0, chunk, 0, Math.min(25, chunk25.length));
        return frameSymbols(SYNC_PKT, convEncode(packetBits(chunk, eof, counter), P3, 368));
    }

    /** The packet frames for one buffer, in order. The last frame's counter is its valid byte count. */
    public static java.util.List<int[]> encodePacket(byte[] data) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (data == null || data.length == 0) return out;
        int frame = 0, off = 0;
        while (off < data.length && frame < 33) {
            int n = Math.min(25, data.length - off);
            boolean last = off + n >= data.length;
            byte[] chunk = new byte[25];
            System.arraycopy(data, off, chunk, 0, n);
            out.add(packetSymbols(chunk, last, last ? n : frame));
            off += n;
            if (!last) frame++;
        }
        return out;
    }

    /** Preamble, the packet LSF, the packet frames, and the end marker, as one symbol list. */
    public static int[] packetTransmission(String src, String dst, int can, byte[] data) {
        java.util.List<int[]> frames = encodePacket(data);
        int n = 192 + 192 + frames.size() * 192 + 192;
        int[] s = new int[n]; int at = 0;
        System.arraycopy(preamble(), 0, s, at, 192); at += 192;
        System.arraycopy(lsfSymbols(encodePacketLsf(src, dst, can)), 0, s, at, 192); at += 192;
        for (int[] f : frames) { System.arraycopy(f, 0, s, at, 192); at += 192; }
        System.arraycopy(eotTail(), 0, s, at, 192);
        return s;
    }

    public static Packet decodePacket(float[] sym, int off) {
        float[] soft = softBits(sym, off);
        byte[] bits = viterbi(soft, 0, 368, P3, 206, null);
        byte[] chunk = pack(bits, 25);
        int meta = 0;
        for (int i = 0; i < 6; i++) meta = (meta << 1) | (bits[200 + i] & 1);
        return new Packet((meta & 32) != 0, meta & 31, chunk);
    }

    /**
     * Join packet frames and check the CRC. Returns the type byte and payload, without the CRC, or null when the
     * CRC fails or a frame is missing. The list must start at frame 0 and end on the last-frame bit.
     */
    public static byte[] assemble(java.util.List<Packet> frames) {
        if (frames == null || frames.isEmpty()) return null;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int expect = 0;
        for (int i = 0; i < frames.size(); i++) {
            Packet f = frames.get(i);
            boolean last = i == frames.size() - 1;
            if (f.eof != last) return null;
            if (!f.eof && f.counter != expect) return null;
            int n = f.count();
            if (n < 1 || n > 25) return null;
            o.write(f.chunk, 0, n);
            if (!f.eof) expect++;
        }
        byte[] all = o.toByteArray();
        if (all.length < 3) return null;
        int got = ((all[all.length - 2] & 0xFF) << 8) | (all[all.length - 1] & 0xFF);
        if (crc16(all, all.length - 2) != got) return null;
        return java.util.Arrays.copyOf(all, all.length - 2);
    }

    /** The SMS text inside a checked packet body (type byte plus payload, no CRC). Null when it is not SMS. */
    public static String smsText(byte[] body) {
        if (body == null || body.length < 2 || (body[0] & 0xFF) != 0x05) return null;
        int end = 1;
        while (end < body.length && body[end] != 0) end++;
        return new String(body, 1, end - 1, java.nio.charset.StandardCharsets.UTF_8);
    }
}
