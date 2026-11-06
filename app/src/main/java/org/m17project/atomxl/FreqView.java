/*
 * AtoM17 - frequency display and tuner, SDR#-style: tap the upper half of a digit to step it up, the lower half to
 * step it down; drag up or down over a digit to tune with it; long press to type a frequency.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

final class FreqView extends View {
    interface OnChange { void changed(int hz); }

    private int hz = 433_475_000;
    private OnChange listener;
    private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG), dim = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 9 digits: 100 MHz … 1 Hz, drawn as 433.475.000 */
    private static final int DIGITS = 9;
    private final float[] digitX = new float[DIGITS];
    private float digitW;
    private int touchDigit = -1, highlight = -1;
    private float downY, accY;
    private boolean dragged;

    FreqView(Context c) {
        super(c);
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        digit.setTypeface(mono); digit.setColor(0xFFFFFFFF); digit.setTextAlign(Paint.Align.CENTER);
        dim.setTypeface(mono); dim.setColor(0xFF8FA9BF); dim.setTextAlign(Paint.Align.CENTER);
        mark.setColor(0x55F59A23);
        setLongClickable(true);
        setContentDescription("Frequency");
    }

    void setOnChange(OnChange l) { listener = l; }
    void setHz(int v) { hz = clamp(v); invalidate(); }
    int hz() { return hz; }

    private static int clamp(int v) { return Math.max(Config.MIN_HZ, Math.min(Config.MAX_HZ, v)); }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (width * 0.20f));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float slots = DIGITS + 2 * 0.5f + 0.6f;                      // nine digits, two separators, a little margin
        digitW = w / slots;
        float size = Math.min(h * 0.9f, digitW * 1.65f);
        digit.setTextSize(size); dim.setTextSize(size);
        float base = h / 2 - (digit.descent() + digit.ascent()) / 2;
        String s = String.format(java.util.Locale.US, "%09d", hz);
        float x = digitW * 0.8f;
        boolean leading = true;
        for (int i = 0; i < DIGITS; i++) {
            if (i == 3 || i == 6) { c.drawText(".", x - digitW * 0.25f, base, dim); x += digitW * 0.5f; }
            digitX[i] = x;
            char ch = s.charAt(i);
            if (ch != '0' || i >= 2) leading = false;
            if (i == highlight) c.drawRect(x - digitW / 2, 0, x + digitW / 2, h, mark);
            c.drawText(String.valueOf(ch), x, base, leading ? dim : (i >= 6 ? dim : digit));
            x += digitW;
        }
    }

    private int digitAt(float x) {
        int best = -1; float bd = Float.MAX_VALUE;
        for (int i = 0; i < DIGITS; i++) { float d = Math.abs(digitX[i] - x); if (d < bd) { bd = d; best = i; } }
        return bd < digitW ? best : -1;
    }

    private static int pow10(int digitIndex) { int p = 1; for (int k = 0; k < DIGITS - 1 - digitIndex; k++) p *= 10; return p; }

    private void step(int digitIndex, int dir) {
        int v = clamp(hz + dir * pow10(digitIndex));
        if (v != hz) { hz = v; invalidate(); if (listener != null) listener.changed(hz); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDigit = digitAt(e.getX()); highlight = touchDigit; downY = e.getY(); accY = 0; dragged = false; invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchDigit >= 0) {
                    float dy = e.getY() - downY;
                    if (Math.abs(dy) > getHeight() * 0.25f) {
                        dragged = true; cancelLongPress();
                        step(touchDigit, dy < 0 ? 1 : -1);
                        downY = e.getY();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (touchDigit >= 0 && !dragged && e.getEventTime() - e.getDownTime() < 400) step(touchDigit, e.getY() < getHeight() / 2f ? 1 : -1);
                highlight = -1; invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                highlight = -1; invalidate();
                break;
        }
        super.onTouchEvent(e);
        return true;
    }
}
