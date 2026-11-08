/*
 * AtoM17 - settings (SharedPreferences, private to the app).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.Context;
import android.content.SharedPreferences;

final class Config {
    final SharedPreferences p;
    Config(Context c) { p = c.getSharedPreferences("atom17", Context.MODE_PRIVATE); }

    static final int MIN_HZ = 400_000_000, MAX_HZ = 480_000_000;
    static final double[] CTCSS = { 62.5, 67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 100.0, 103.5, 107.2,
            110.9, 114.8, 118.8, 123.0, 127.3, 131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9, 171.3, 173.8,
            177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5, 203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1 };

    int freq() { return p.getInt("freq", 433_475_000); }
    boolean m17() { return p.getBoolean("m17", true); }
    boolean wide() { return p.getBoolean("wide", false); }
    boolean high() { return p.getBoolean("high", false); }
    int squelch() { return p.getInt("sq", 3); }
    /** 0 = none, else 1 + index into {@link #CTCSS}. */
    int rxTone() { return p.getInt("rxTone", 0); }
    int txTone() { return p.getInt("txTone", 0); }
    int volume() { return p.getInt("vol", 6); }
    int step() { return p.getInt("step", 12_500); }

    String src() { return p.getString("src", ""); }
    String dst() { return p.getString("dst", "@ALL"); }
    int can() { return p.getInt("can", 0); }
    int metaKind() { return p.getInt("metaKind", M17Native.META_TEXT); }
    String metaText() { return p.getString("metaText", ""); }
    String ecd1() { return p.getString("ecd1", ""); }
    String ecd2() { return p.getString("ecd2", ""); }
    boolean gnssFromPhone() { return p.getBoolean("gnssPhone", true); }
    int station() { return p.getInt("station", 2); }
    int enc() { return p.getInt("enc", M17Native.ENC_NONE); }
    int aesBits() { return p.getInt("aesBits", 256); }
    String aesKey() { return p.getString("aesKey", ""); }
    int scramBits() { return p.getInt("scramBits", 24); }
    String scramSeed() { return p.getString("scramSeed", ""); }
    boolean sign() { return p.getBoolean("sign", false); }
    String privKey() { return p.getString("privKey", ""); }

    String rxAesKey() { return p.getString("rxAesKey", ""); }
    String rxScramSeed() { return p.getString("rxScramSeed", ""); }
    int rxScramBits() { return p.getInt("rxScramBits", 24); }
    /** One per line: NAME HEX (64-byte X||Y, 65-byte 04||X||Y, or 33-byte compressed). */
    String pubKeys() { return p.getString("pubKeys", ""); }
    /** Decode M17 voice with Codec2-mod-neural (FARGAN, 16 kHz) instead of the classic Codec2 synthesis. */
    /** DroidStar-style AGC on decoded M17 voice. */
    boolean agc() { return p.getBoolean("agc", false); }
    boolean neural() { return p.getBoolean("neural", false); }
    boolean invert() { return p.getBoolean("invert", false); }
    /** Extra preamble before every M17 transmission, for the transmitter's ramp-up (0 = only the standard 40 ms). */
    int leadInMs() { return p.getInt("leadIn", 200); }

    /** Deviation trim for the next module load (1 = V076J as is), and the trim of the image last loaded. */
    float devGain() { return p.getFloat("devGain", 1f); }
    float devGainLoaded() { return p.getFloat("devGainLoaded", 1f); }

    /** Frequency correction: the radio's reference error in ppm, one decimal (+ = it transmits and receives high). */
    float ppm() { return p.getFloat("ppm", 0f); }

    /** Repeater shift: -1 (transmit below), 0 simplex, +1 (above); the offset in Hz (70 cm default 7.6 MHz). */
    int shift() { return Math.max(-1, Math.min(1, p.getInt("shift", 0))); }
    int offset() { return p.getInt("offset", 7_600_000); }
    /** The transmit frequency: the receive frequency moved by the repeater shift. */
    int txFreq() { return freq() + shift() * offset(); }
    static String shiftText(int shift, int offset) {
        return shift == 0 ? "simplex" : (shift < 0 ? "−" : "+") + String.format(java.util.Locale.US, "%.3f", offset / 1e6).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** IARU Region (1–3), for the 70 cm band edges the transmit frequency is checked against. */
    int region() { return Math.max(1, Math.min(3, p.getInt("iaruRegion", 1))); }
    static int bandLo(int region) { return region == 2 ? 420_000_000 : 430_000_000; }
    static int bandHi(int region) { return region == 2 ? 450_000_000 : 440_000_000; }
    static String regionText(int region) { return "Region " + region + " · 70 cm band " + bandLo(region) / 1_000_000 + "–" + bandHi(region) / 1_000_000 + " MHz"; }
    static String dotted(int hz) {
        String d = String.format(java.util.Locale.US, "%09d", hz);
        return d.substring(0, 3) + "." + d.substring(3, 6) + "." + d.substring(6);
    }
    /** "Transmits on 431.225.000 MHz", with the band warning when it falls outside the region's 70 cm band. */
    static CharSequence txCaption(int txHz, int region) {
        boolean in = txHz >= bandLo(region) && txHz <= bandHi(region);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("Transmits on ");
        int a = b.length();
        b.append(dotted(txHz));
        b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, b.length(), 0);
        b.setSpan(new android.text.style.TypefaceSpan("monospace"), a, b.length(), 0);
        b.append(" MHz");
        if (!tunable(txHz)) b.append(" · outside the radio's " + rangeText() + " range, can't be stored");
        else if (!in) b.append(" · outside " + bandLo(region) / 1_000_000 + "–" + bandHi(region) / 1_000_000 + " MHz (IARU Region " + region + ")");
        else b.setSpan(new android.text.style.ForegroundColorSpan(Ui.WHITE), a, a + 11, 0);
        return b;
    }
    /** Within the module's tuning range (400–480 MHz). */
    static boolean tunable(long hz) { return hz >= MIN_HZ && hz <= MAX_HZ; }
    static String rangeText() { return MIN_HZ / 1_000_000 + "–" + MAX_HZ / 1_000_000 + " MHz"; }
    static boolean inBand(int txHz, int region) { return txHz >= bandLo(region) && txHz <= bandHi(region); }

    SharedPreferences.Editor edit() { return p.edit(); }

    static String mhz(int hz) { return String.format(java.util.Locale.US, "%d.%06d", hz / 1_000_000, hz % 1_000_000).replaceAll("0{1,3}$", ""); }
}
