/*
 * AtoM17 - the Bench's receive plots, from RxStats: the eye of the radio's audio before the equaliser, the histogram of
 * the equaliser's outputs, and the symbol error rate per frame.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import org.m17project.atomxl.dfe.M17Receiver;

final class ScopeView extends View {
    enum Mode { EYE, HIST, HISTORY }
    private final Mode mode;
    private RxStats.Snap snap;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), bar = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);

    ScopeView(Context c, Mode m) {
        super(c);
        mode = m;
        line.setColor(0x59FFC37A); line.setStrokeWidth(Ui.dp(c, 1.2f));
        grid.setColor(0x33FFFFFF); grid.setStrokeWidth(1);
        bar.setColor(0xFFF59A23);
        text.setColor(0xFFC9D8E5); text.setTextSize(Ui.dp(c, 10)); text.setTypeface(Ui.MONO);
    }

    void set(RxStats.Snap s) { snap = s; invalidate(); }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (width * (mode == Mode.HISTORY ? 0.30f : 0.50f)));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        android.graphics.drawable.Drawable bg = getContext().getDrawable(R.drawable.cell);
        bg.setBounds(0, 0, (int) w, (int) h); bg.draw(c);
        if (mode == Mode.EYE) eye(c, w, h); else if (mode == Mode.HIST) hist(c, w, h); else history(c, w, h);
    }

    /** y for a value in symbol units, +-4.5 over the height */
    private static float y(float v, float h) { return h / 2 - v * h / 9f; }

    private void eye(Canvas c, float w, float h) {
        for (int lv = -3; lv <= 3; lv += 2) c.drawLine(0, y(lv, h), w, y(lv, h), grid);
        c.drawLine(w / 2, 0, w / 2, h, grid);
        if (snap == null || snap.eyeN == 0) { empty(c, w, h); return; }
        int P = M17Receiver.EYE_POINTS;
        for (int t = 0; t < snap.eyeN; t++)
            for (int i = 1; i < P; i++) {
                float x0 = (i - 1) * w / (P - 1), x1 = i * w / (P - 1);
                c.drawLine(x0, y(snap.eye[t * P + i - 1], h), x1, y(snap.eye[t * P + i], h), line);
            }
    }

    private void hist(Canvas c, float w, float h) {
        for (int lv = -3; lv <= 3; lv += 2) { float x = (lv + RxStats.HIST_SPAN) / (2 * RxStats.HIST_SPAN) * w; c.drawLine(x, 0, x, h, grid); }
        float max = 0;
        if (snap != null) for (float v : snap.hist) max = Math.max(max, v);
        if (max <= 0) { empty(c, w, h); return; }
        float bw = w / RxStats.HIST_BINS;
        for (int i = 0; i < RxStats.HIST_BINS; i++) {
            float bh = snap.hist[i] / max * (h - Ui.dp(getContext(), 6));
            c.drawRect(i * bw + 1, h - bh, (i + 1) * bw - 1, h, bar);
        }
    }

    private void history(Canvas c, float w, float h) {
        float top = 0.10f;                                     // full scale: 10 % symbol errors
        for (float g = 0.02f; g < top; g += 0.02f) c.drawLine(0, h - g / top * h, w, h - g / top * h, grid);
        if (snap == null || snap.historyN == 0) { empty(c, w, h); return; }
        float bw = w / RxStats.HISTORY;
        int off = RxStats.HISTORY - snap.historyN;
        for (int i = 0; i < snap.historyN; i++) {
            float v = Math.min(top, snap.history[i]);
            bar.setColor(v < 0.02f ? 0xFF7DF0B4 : v < 0.06f ? 0xFFFFCB6B : 0xFFFF9EA1);
            float bh = Math.max(Ui.dp(getContext(), 1.5f), v / top * h);
            c.drawRect((off + i) * bw + 1, h - bh, (off + i + 1) * bw - 1, h, bar);
        }
        bar.setColor(0xFFF59A23);
        c.drawText("10 %", Ui.dp(getContext(), 4), text.getTextSize() + 2, text);
    }

    private void empty(Canvas c, float w, float h) {
        String s = "no M17 frames yet";
        c.drawText(s, (w - text.measureText(s)) / 2, h / 2 + text.getTextSize() / 3, text);
    }
}
