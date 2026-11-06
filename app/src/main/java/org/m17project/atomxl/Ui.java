/*
 * AtoM17 - small view builders for the steel-blue theme: cards, labels, rows, chips. System fonts only.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int WHITE = 0xFFFFFFFF, TEXT2 = 0xFFE4EDF5, TEXT3 = 0xFFD3E0EB, LABEL = 0xFFC9D8E5,
            ORANGE = 0xFFF59A23, OK = 0xFF7DF0B4, WARN = 0xFFFFCB6B, BAD = 0xFFFF9EA1, TX = 0xFFC2412B, IDLE = 0xFFA9BED0;
    static final Typeface MONO = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL), MONO_B = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    private Ui() { }

    static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView mono(Context c, String s, float sp, int color) { TextView t = text(c, s, sp, color, false); t.setTypeface(MONO); return t; }

    /** Small upper-case section label. */
    static TextView label(Context c, String s) {
        TextView t = text(c, s, 10.5f, LABEL, true);
        t.setAllCaps(true); t.setLetterSpacing(0.08f);
        return t;
    }

    static LinearLayout vbox(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout hbox(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }

    static LinearLayout card(Context c, int padDp) {
        LinearLayout l = vbox(c);
        l.setBackgroundResource(R.drawable.card);
        int p = dp(c, padDp); l.setPadding(p, p, p, p);
        return l;
    }

    /** One of the dark key/value cells inside a card. */
    static LinearLayout cell(Context c, String label, TextView value) {
        LinearLayout l = vbox(c);
        l.setBackgroundResource(R.drawable.cell);
        l.setPadding(dp(c, 9), dp(c, 6), dp(c, 9), dp(c, 6));
        l.addView(label(c, label));
        l.addView(value);
        return l;
    }

    static TextView value(Context c) { TextView t = text(c, "", 13.5f, WHITE, true); t.setMaxLines(2); t.setEllipsize(TextUtils.TruncateAt.END); return t; }

    /** A two-column grid of cells, row by row. */
    static LinearLayout grid(Context c, View... cells) {
        LinearLayout g = vbox(c);
        for (int i = 0; i < cells.length; i += 2) {
            LinearLayout r = hbox(c);
            r.setGravity(Gravity.TOP);
            for (int k = 0; k < 2; k++) {
                View v = i + k < cells.length ? cells[i + k] : new View(c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (k == 0) lp.rightMargin = dp(c, 3); else lp.leftMargin = dp(c, 3);
                r.addView(v, lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rp.topMargin = dp(c, 6);
            g.addView(r, rp);
        }
        return g;
    }

    /** The thin status bar shown left of a status line (instead of a dot). */
    static View bar(Context c, int color) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(c, 1)); d.setColor(color);
        v.setBackground(d);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 3), dp(c, 14)));
        return v;
    }
    static void barColor(View v, int color) { ((GradientDrawable) v.getBackground()).setColor(color); }

    /** A rounded rectangle tag ("MUTED", "VOICE"). */
    static TextView tag(Context c, String s, int color) {
        TextView t = text(c, s, 11, color, true);
        GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(c, 6)); d.setColor((color & 0x00FFFFFF) | 0x33000000);
        t.setBackground(d);
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }
    static void tag(TextView t, String s, int color) {
        t.setText(s); t.setTextColor(color);
        ((GradientDrawable) t.getBackground()).setColor((color & 0x00FFFFFF) | 0x33000000);
    }

    static ImageView icon(Context c, int res, int tint) {
        ImageView i = new ImageView(c);
        i.setImageResource(res); i.setColorFilter(tint);
        GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(c, 8)); d.setColor(0x24FFFFFF);
        i.setBackground(d);
        int p = dp(c, 6); i.setPadding(p, p, p, p);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        return i;
    }

    /** Full-window page header: back arrow and title. */
    static LinearLayout header(android.app.Activity a, String title) {
        LinearLayout h = hbox(a);
        h.setPadding(dp(a, 4), dp(a, 4), dp(a, 8), dp(a, 4));
        ImageButton back = new ImageButton(a);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.row_bg);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> a.finish());
        h.addView(back, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        TextView t = text(a, title, 20, WHITE, true);
        t.setPadding(dp(a, 4), 0, 0, 0);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return h;
    }

    static LinearLayout.LayoutParams matchWrap(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }
}
