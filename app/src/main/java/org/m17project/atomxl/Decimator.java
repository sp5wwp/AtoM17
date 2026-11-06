/*
 * AtoM17 - 32 kHz stereo capture to 8 kHz mono (left channel), exactly as SignalDeck's RateConverter.decimate does it:
 * the Atom XL's M17 equaliser table was fitted on audio made this way.
 * Copyright (C) 2026 IIMacGyverII (SignalDeck, RateConverter); modified for AtoM17, 2026. See COPYRIGHT.SignalDeck.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

final class Decimator {
    private final int factor;
    private final float[] h, hist;
    private int pos, phase;

    Decimator(int factor, int highRate) {
        this.factor = factor;
        int n = 16 * factor + 1;
        h = new float[n];
        double fc = 3400.0 / highRate, sum = 0;
        for (int i = 0; i < n; i++) {
            double m = i - (n - 1) / 2.0;
            double sinc = m == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (n - 1));
            h[i] = (float) (sinc * w); sum += h[i];
        }
        for (int i = 0; i < n; i++) h[i] /= (float) sum;
        hist = new float[n];
    }

    /** Interleaved stereo frames in, the left channel at the low rate out; returns the samples written. */
    int run(short[] stereo, int frames, short[] out) {
        int o = 0;
        for (int i = 0; i < frames; i++) {
            hist[pos] = stereo[2 * i]; pos = (pos + 1) % h.length;
            if (++phase < factor) continue;
            phase = 0;
            float acc = 0; int k = pos;
            for (int t = h.length - 1; t >= 0; t--) { acc += h[t] * hist[k]; k = (k + 1) % h.length; }
            out[o++] = (short) Math.max(-32768, Math.min(32767, Math.round(acc)));
        }
        return o;
    }
}
