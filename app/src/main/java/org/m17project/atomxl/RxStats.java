/*
 * AtoM17 - receive measurements taken on the equaliser (dfe.M17Receiver.Stats), not on libm17rx: the decoder is fed
 * a re-synthesised baseband (re-encoded symbols for every confirmed frame), so its EVM, deviation and offset describe
 * that, not the radio's signal. Everything here comes from the radio's audio and the equaliser's own decisions.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import org.m17project.atomxl.dfe.M17Receiver;

final class RxStats {
    static final int HIST_BINS = 64, HISTORY = 64, EYE_KEEP = 64;
    static final float HIST_SPAN = 4.5f;               // the histogram covers -4.5 .. +4.5

    /** One consistent copy for the UI. */
    static final class Snap {
        long frames, lastAt;
        float ser = Float.NaN, serNow = Float.NaN;     // symbol error rate (smoothed, last frame), 0..1
        float evm = Float.NaN;                         // decision-error EVM, % of the RMS symbol level (sqrt 5)
        float metric = Float.NaN;
        int lichErrors = -1, syncHits = -1, confirmedPct = -1;
        float levelDb = Float.NaN;                     // preamble RMS, dBFS
        float innerOuter = Float.NaN;                  // mean |inner| / mean |outer| decision, nominal 1/3
        final float[] hist = new float[HIST_BINS];
        final float[] history = new float[HISTORY];    // per-frame SER, oldest first
        int historyN;
        final float[] eye = new float[EYE_KEEP * M17Receiver.EYE_POINTS];
        int eyeN;
    }

    private final Snap s = new Snap();
    private int eyeHead, confirmed;
    private double innerSum, outerSum; private int innerN, outerN;

    synchronized void reset() {
        Snap n = new Snap();
        System.arraycopy(n.hist, 0, s.hist, 0, HIST_BINS);
        s.frames = 0; s.lastAt = 0; s.ser = s.serNow = s.evm = s.metric = s.levelDb = s.innerOuter = Float.NaN;
        s.lichErrors = s.syncHits = s.confirmedPct = -1; s.historyN = 0; s.eyeN = 0; eyeHead = 0; confirmed = 0;
        innerSum = outerSum = 0; innerN = outerN = 0;
    }

    synchronized void add(M17Receiver.Stats f) {
        s.frames++; s.lastAt = System.currentTimeMillis();
        if (f.confirmed) confirmed++;
        s.confirmedPct = (int) (100 * confirmed / s.frames);
        if (f.symErrors >= 0) {
            s.serNow = f.symErrors / 184f;
            s.ser = Float.isNaN(s.ser) ? s.serNow : 0.8f * s.ser + 0.2f * s.serNow;
            if (s.historyN == HISTORY) { System.arraycopy(s.history, 1, s.history, 0, HISTORY - 1); s.historyN--; }
            s.history[s.historyN++] = s.serNow;
        }
        float evm = (float) (100 * Math.sqrt(f.mse / 5.0));
        s.evm = Float.isNaN(s.evm) ? evm : 0.8f * s.evm + 0.2f * evm;
        s.metric = f.metric; s.lichErrors = f.lichErrors; s.syncHits = f.syncHits;
        if (f.gain > 0) s.levelDb = (float) (20 * Math.log10(f.gain / 32768.0));
        for (int i = 8; i < f.soft.length; i++) {
            float y = f.soft[i];
            int b = (int) ((y + HIST_SPAN) / (2 * HIST_SPAN) * HIST_BINS);
            if (b >= 0 && b < HIST_BINS) s.hist[b] += 1;
            float a = Math.abs(y);
            if (a < 2) { innerSum += a; innerN++; } else { outerSum += a; outerN++; }
        }
        for (int i = 0; i < HIST_BINS; i++) s.hist[i] *= 0.92f;          // about the last dozen frames
        if (innerN > 0 && outerN > 0) s.innerOuter = (float) ((innerSum / innerN) / (outerSum / outerN));
        if (innerN + outerN > 20000) { innerSum *= 0.5; outerSum *= 0.5; innerN /= 2; outerN /= 2; }
        int P = M17Receiver.EYE_POINTS;
        for (int t = 0; t < f.eyeTraces; t++) {
            System.arraycopy(f.eye, t * P, s.eye, eyeHead * P, P);
            eyeHead = (eyeHead + 1) % EYE_KEEP;
            if (s.eyeN < EYE_KEEP) s.eyeN++;
        }
    }

    synchronized Snap snap() {
        Snap c = new Snap();
        c.frames = s.frames; c.lastAt = s.lastAt; c.ser = s.ser; c.serNow = s.serNow; c.evm = s.evm; c.metric = s.metric;
        c.lichErrors = s.lichErrors; c.syncHits = s.syncHits; c.confirmedPct = s.confirmedPct; c.levelDb = s.levelDb; c.innerOuter = s.innerOuter;
        System.arraycopy(s.hist, 0, c.hist, 0, HIST_BINS);
        System.arraycopy(s.history, 0, c.history, 0, s.historyN); c.historyN = s.historyN;
        System.arraycopy(s.eye, 0, c.eye, 0, s.eye.length); c.eyeN = s.eyeN;
        return c;
    }
}
