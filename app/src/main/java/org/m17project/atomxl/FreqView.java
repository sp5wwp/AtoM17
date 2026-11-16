/*
 * AtoM17 - frequency display and tuner, SDR#-style: tap the upper half of a digit to step it up, the lower half to
 * step it down, drag up or down over a digit to tune with it, long press to type a frequency. A digit finer than the
 * tuning step (Settings) moves to the next frequency on the step's grid instead, so 1 kHz taps with a 12.5 kHz step
 * go 433.475, 433.4875, 433.500. TalkBack reads the frequency, its scroll actions tune by one step.
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
    private int stepHz = 12_500;
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
        digit.setTypeface(mono); digit.setColor(Ui.WHITE); digit.setTextAlign(Paint.Align.CENTER);
        dim.setTypeface(mono); dim.setColor(Ui.DIM); dim.setTextAlign(Paint.Align.CENTER);
        mark.setColor((Ui.ORANGE & 0x00FFFFFF) | 0x55000000);
        setLongClickable(true);
        setFocusable(true);
        describe();
    }

    void setOnChange(OnChange l) { listener = l; }
    void setHz(int v) { hz = clamp(v); describe(); invalidate(); }
    /** The tuning step in Hz (Settings, Tuning). */
    void setStep(int v) { stepHz = Math.max(1, v); }

    private void describe() { setContentDescription("Frequency " + Config.mhz(hz) + " megahertz"); }
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
        int w = pow10(digitIndex);
        set(w >= stepHz ? hz + dir * w : onGrid(dir));
    }

    /** The next frequency on the step's grid above ({@code dir} 1) or below (-1) the current one. */
    private int onGrid(int dir) {
        long k = Math.floorDiv((long) hz, stepHz);
        long v = dir > 0 ? (k + 1) * stepHz : (hz % stepHz == 0 ? k - 1 : k) * stepHz;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    private void set(int v) {
        v = clamp(v);
        if (v != hz) { hz = v; describe(); invalidate(); if (listener != null) listener.changed(hz); }
    }

    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(true);
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { set(onGrid(1)); announceForAccessibility(getContentDescription()); return true; }
        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { set(onGrid(-1)); announceForAccessibility(getContentDescription()); return true; }
        return super.performAccessibilityAction(action, args);
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
