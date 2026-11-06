/*
 * AtoM17 - eye diagram after the receiver's RRC filter (traces from libm17rx, normalised to +-3).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public final class EyeView extends View {
    static final int POINTS = 21, MAX = 64;
    private final float[] traces = new float[POINTS * MAX];
    private int n;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();

    public EyeView(Context c, AttributeSet a) {
        super(c, a);
        line.setColor(0x6039A0FF); line.setStrokeWidth(1.5f);
        grid.setColor(0xFF2A323B);
    }

    void update(Station s) { n = s.eye(traces, MAX); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(0xFF1A2027);
        for (int lv = -3; lv <= 3; lv += 2) { float y = h / 2 - lv * h / 9; c.drawLine(0, y, w, y, grid); }
        for (int t = 0; t < n; t++)
            for (int i = 1; i < POINTS; i++) {
                float x0 = (i - 1) * w / (POINTS - 1), x1 = i * w / (POINTS - 1);
                float y0 = h / 2 - traces[t * POINTS + i - 1] * h / 9, y1 = h / 2 - traces[t * POINTS + i] * h / 9;
                c.drawLine(x0, y0, x1, y1, line);
            }
    }
}
