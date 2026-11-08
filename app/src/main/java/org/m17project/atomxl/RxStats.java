/*
 * AtoM17 - receive measurements taken on the equaliser (dfe.M17Receiver.Stats), not on libm17rx: the decoder is fed
 * a re-synthesised baseband (re-encoded symbols for every confirmed frame), so its EVM, deviation and offset describe
 * that, not the radio's signal. Everything here comes from the radio's audio and the equaliser's own decisions.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import org.m17project.atomxl.dfe.M17Receiver;

final class RxStats {
    static final int HIST_BINS = 64, HISTORY = 64, EYE_KEEP = 240;
    /** Eye traces: two symbols, 10 points a symbol (the symbol instants at 0, 10 and 20). */
    static final int EYE_POINTS = 21, EYE_SPS = 10, EYE_PER_FRAME = 24;
    /**
     * Raised cosine, roll-off 0.5 (M17's RRC at both ends), at 10 samples a symbol over +-4 symbols: the equalised
     * symbols are drawn as the matched-filter output an ideal receiver would see, the way SDR#'s plugin draws its eye.
     * Its spread at the symbol instants is the equaliser's real scatter; the curves between them are this pulse.
     */
    private static final int RC_SPAN = 4;
    private static final float[] RC = new float[2 * RC_SPAN * EYE_SPS + 1];
    static {
        double a = 0.5;
        for (int i = 0; i < RC.length; i++) {
            double t = (i - RC_SPAN * EYE_SPS) / (double) EYE_SPS;
            double sinc = t == 0 ? 1 : Math.sin(Math.PI * t) / (Math.PI * t);
            double d = 1 - 4 * a * a * t * t;
            double c = Math.abs(d) < 1e-9 ? Math.PI / 4 : Math.cos(Math.PI * a * t) / d;
            RC[i] = (float) (sinc * c);
        }
    }
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
        final float[] eye = new float[EYE_KEEP * EYE_POINTS];
        int eyeN;
        boolean free;                                  // the eye shows the free-running equaliser (no M17 signal)
    }

    private final Snap s = new Snap();
    private int eyeHead, confirmed;
    private double innerSum, outerSum; private int innerN, outerN;

    synchronized void reset() {
        Snap n = new Snap();
        System.arraycopy(n.hist, 0, s.hist, 0, HIST_BINS);
        s.frames = 0; s.lastAt = 0; s.ser = s.serNow = s.evm = s.metric = s.levelDb = s.innerOuter = Float.NaN;
        s.lichErrors = s.syncHits = s.confirmedPct = -1; s.historyN = 0; s.eyeN = 0; eyeHead = 0; confirmed = 0; s.free = false;
        innerSum = outerSum = 0; innerN = outerN = 0;
    }

    synchronized void add(M17Receiver.Stats f) {
        if (f.free) { eye(f.soft); s.free = true; return; }             // no signal: only the eye moves
        s.free = false;
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
        eye(f.soft);
    }

    /** Traces centred on symbols spread over the frame, from one symbol before to one after. */
    private void eye(float[] y) {
        int first = 8 + RC_SPAN + 1, last = y.length - RC_SPAN - 2;
        for (int t = 0; t < EYE_PER_FRAME; t++) {
            int k = first + (int) ((long) t * (last - first) / (EYE_PER_FRAME - 1));
            int base = eyeHead * EYE_POINTS;
            for (int j = 0; j < EYE_POINTS; j++) {
                float pos = k - 1 + j / (float) EYE_SPS, v = 0;     // time in symbols
                for (int i = k - 1 - RC_SPAN; i <= k + 1 + RC_SPAN; i++) {
                    int r = Math.round((pos - i) * EYE_SPS) + RC_SPAN * EYE_SPS;
                    if (r >= 0 && r < RC.length && i >= 0 && i < y.length) v += y[i] * RC[r];
                }
                s.eye[base + j] = v;
            }
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
        System.arraycopy(s.eye, 0, c.eye, 0, s.eye.length); c.eyeN = s.eyeN; c.free = s.free;
        return c;
    }
}
