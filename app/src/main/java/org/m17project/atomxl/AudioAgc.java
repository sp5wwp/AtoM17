/*
 * AtoM17 - decoded-voice AGC, ported from DroidStar's AudioEngine::process_audio() (Doug McLain, GPL-3.0-or-later),
 * itself based on DSD (https://github.com/szechyjs/dsd). Per 20 ms block: the peak over the last 25 blocks sets a
 * target gain of 30000 / peak (at most 50); the gain falls at once and rises by at most 5 % per block, ramped across it.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

final class AudioAgc {
    private final float[] maxBuf = new float[25];
    private int idx;
    private float gain = 100;

    void reset() { java.util.Arrays.fill(maxBuf, 0); idx = 0; gain = 100; }

    /** Process {@code n} samples in place, in blocks of {@code block} samples (20 ms at the audio rate). */
    void run(short[] pcm, int n, int block) {
        for (int off = 0; off < n; off += block) process(pcm, off, Math.min(block, n - off));
    }

    private void process(short[] pcm, int off, int s) {
        float max = 0;
        for (int i = 0; i < s; i++) { float a = Math.abs((float) pcm[off + i]); if (a > max) max = a; }
        maxBuf[idx] = max;
        if (++idx > 24) idx = 0;
        for (float m : maxBuf) if (m > max) max = m;

        float factor = max > 0 ? 30000f / max : 50f, delta;
        if (factor < gain) { gain = factor; delta = 0; }
        else {
            if (factor > 50f) factor = 50f;
            delta = factor - gain;
            if (delta > 0.05f * gain) delta = 0.05f * gain;
        }
        delta /= s;
        for (int i = 0; i < s; i++) {
            float v = (gain + i * delta) * pcm[off + i];
            pcm[off + i] = (short) Math.max(-32760f, Math.min(32760f, v));
        }
        gain += s * delta;
    }
}
