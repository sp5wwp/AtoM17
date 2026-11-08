/*
 * AtoM17 - JNI declarations for libatom17 (libm17, Codec2-mod, micro-ecc, tinier-aes, libm17rx, m17tx).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

final class M17Native {
    static { System.loadLibrary("atom17"); }
    private M17Native() { }

    // receiver
    static native long rxCreate(double rate);
    static native void rxDestroy(long h);
    static native void rxReset(long h);
    static native void rxConfig(long h, boolean invert, boolean agc, float fixedGain);
    /** Codec2-mod-neural (FARGAN) voice synthesis on or off; 0 = ok. The audio queue is emptied. */
    static native int rxSetNeural(long h, boolean on);
    /** Sample rate of what rxReadAudio returns: 8000, or 16000 with the neural decoder. */
    static native int rxAudioRate(long h);
    /** Voice check: 8 kHz speech through Codec2 3200 and back (FARGAN at 16 kHz with {@code neural}). */
    static native short[] c2Loopback(short[] pcm8k, boolean neural);
    static native int rxProcessPcm(long h, short[] pcm, int frames, int channels, int ch);
    static native int rxPushSymbols(long h, float[] sym192);
    static native int rxReadAudio(long h, short[] out);
    static native String rxNextEvent(long h);
    static native String rxStatus(long h);
    static native int rxEye(long h, float[] out, int maxTraces);
    static native int rxSetAes(long h, byte[] key);
    static native int rxSetScrambler(long h, int seed, int bits);
    static native void rxClearKeys(long h);
    static native int rxAddKey(long h, String name, byte[] key);

    // transmitter
    static native long txCreate();
    static native void txDestroy(long h);
    static native int txConfigure(long h, String src, String dst, int can, int metaKind, String metaText,
                                  double lat, double lon, float alt, float speed, int bearing, int station, int validity,
                                  String ecd1, String ecd2, int enc, int aesBits, byte[] aesKey, int scramBits, int seed,
                                  boolean sign, byte[] priv);
    static native int txBegin(long h, byte[] out);
    static native int txVoice(long h, short[] pcm320, byte[] out);
    static native int txEnd(long h, short[] pcm320, byte[] out);
    static native int txSms(long h, byte[] utf8, byte[] out);

    static native byte[] makeKey();
    static native byte[] publicKey(byte[] priv);
    static native String version();

    static final int EV_STREAM_START = 1, EV_LINK_UPDATE = 2, EV_STREAM_END = 3, EV_PACKET = 4, EV_PACKET_BAD = 5;
    static final int META_TEXT = 0, META_GNSS = 1, META_ECD = 2;
    static final int ENC_NONE = 0, ENC_SCRAM = 1, ENC_AES = 2;

    static byte[] hex(String s) {
        if (s == null) return null;
        s = s.replaceAll("[^0-9A-Fa-f]", "");
        if (s.length() % 2 != 0) return null;
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(java.util.Locale.US, "%02X", x & 0xFF));
        return s.toString();
    }
}
