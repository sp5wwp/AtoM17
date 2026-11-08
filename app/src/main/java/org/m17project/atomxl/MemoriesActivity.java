/*
 * AtoM17 - the memories, full window: tap a channel to use it, the pencil edits it, + adds one, and "Store current
 * channel" saves what the radio is on now.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class MemoriesActivity extends Activity {
    private Station st;
    private Memories mem;
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = Station.get(this);
        mem = new Memories(st.cfg);
        LinearLayout root = Ui.vbox(this);
        LinearLayout head = Ui.header(this, "Memories");
        ImageButton add = new ImageButton(this);
        add.setImageResource(R.drawable.ic_plus);
        add.setBackgroundResource(R.drawable.row_bg);
        add.setContentDescription("New memory");
        add.setOnClickListener(v -> startActivity(new Intent(this, MemEditActivity.class).putExtra("index", -1)));
        head.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(head);

        LinearLayout body = Ui.vbox(this);
        int p = Ui.dp(this, 10);
        body.setPadding(p, 0, p, p);
        TextView hint = Ui.text(this, "Tap a channel to use it · the pencil edits it", 12.5f, Ui.TEXT3, false);
        hint.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, Ui.dp(this, 8));
        body.addView(hint);
        list = Ui.card(this, 0);
        body.addView(list);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button store = new Button(this);
        store.setText("Store current channel");
        store.setAllCaps(false); store.setTextColor(0xFF1B1206); store.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); store.setTextSize(15);
        store.setBackgroundResource(R.drawable.btn_primary);
        store.setOnClickListener(v -> startActivity(new Intent(this, MemEditActivity.class).putExtra("index", -1).putExtra("current", true)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        sp.setMargins(p, p, p, p);
        root.addView(store, sp);
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); build(); }

    private void build() {
        list.removeAllViews();
        List<Memories.Ch> l = mem.all();
        int inUse = mem.memMode() ? Math.min(mem.index(), l.size() - 1) : -1;
        if (l.isEmpty()) {
            TextView t = Ui.text(this, "No memories yet. + adds one; Store current channel saves what the radio is on now.", 14, Ui.TEXT3, false);
            int p = Ui.dp(this, 14); t.setPadding(p, p, p, p);
            list.addView(t);
        }
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) { View sep = new View(this); sep.setBackgroundColor(0x1AFFFFFF); list.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
            list.addView(row(i, l.get(i), i == inUse));
        }
    }

    private View row(int i, Memories.Ch c, boolean sel) {
        LinearLayout r = Ui.hbox(this);
        r.setMinimumHeight(Ui.dp(this, 58));
        if (sel) r.setBackgroundColor(0x1AF59A23);
        View bar = new View(this);
        bar.setBackgroundColor(sel ? Ui.ORANGE : 0);
        r.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 3), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout tap = Ui.hbox(this);
        tap.setBackgroundResource(R.drawable.row_bg);
        tap.setPadding(Ui.dp(this, 11), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        TextView num = Ui.mono(this, String.valueOf(i + 1), 13, sel ? Ui.ORANGE : Ui.LABEL);
        num.setTypeface(Ui.MONO_B); num.setGravity(Gravity.END);
        tap.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 22), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout t = Ui.vbox(this);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView name = Ui.text(this, c.name, 15, Ui.WHITE, true);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(name);
        LinearLayout line = Ui.hbox(this);
        TextView f = Ui.mono(this, c.dotted(), 13, Ui.TEXT2);
        f.setTypeface(Ui.MONO_B);
        line.addView(f);
        TextView tag = Ui.chip(this, c.tag());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(this, 8);
        line.addView(tag, tp);
        t.addView(line, Ui.matchWrap(this, 3));
        tap.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tap.setOnClickListener(v -> {
            mem.enter(st, i);
            finish();
        });
        r.addView(tap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ImageButton edit = new ImageButton(this);
        edit.setImageResource(R.drawable.ic_pen);
        edit.setBackgroundResource(R.drawable.row_bg);
        edit.setContentDescription("Edit " + c.name);
        edit.setOnClickListener(v -> startActivity(new Intent(this, MemEditActivity.class).putExtra("index", i)));
        r.addView(edit, new LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.MATCH_PARENT));
        return r;
    }
}
