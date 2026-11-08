/*
 * AtoM17 - the named keys, full window: tap one to edit it, + adds one.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class KeysActivity extends Activity {
    private Keys keys;
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        keys = new Keys(Station.get(this).cfg);
        LinearLayout root = Ui.vbox(this);
        LinearLayout head = Ui.header(this, "Keys");
        ImageButton add = new ImageButton(this);
        add.setImageResource(R.drawable.ic_plus);
        add.setBackgroundResource(R.drawable.row_bg);
        add.setContentDescription("New key");
        add.setOnClickListener(v -> startActivity(new Intent(this, KeyEditActivity.class)));
        head.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(head);
        LinearLayout body = Ui.vbox(this);
        int p = Ui.dp(this, 10);
        body.setPadding(p, 0, p, p);
        TextView hint = Ui.text(this, "AES keys and scrambler seeds, by name. A memory or VFO picks one; it is used to transmit and to receive on that channel.", 12.5f, Ui.TEXT3, false);
        hint.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 8));
        body.addView(hint);
        list = Ui.card(this, 0);
        body.addView(list);
        TextView law = Ui.text(this, "Encrypted transmissions are not allowed on amateur bands in most countries.", 12.5f, Ui.WARN, false);
        law.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), 0);
        body.addView(law);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); build(); }

    private void build() {
        list.removeAllViews();
        List<Keys.Key> l = keys.all();
        if (l.isEmpty()) {
            TextView t = Ui.text(this, "No keys yet. + adds one.", 14, Ui.TEXT3, false);
            int p = Ui.dp(this, 14); t.setPadding(p, p, p, p);
            list.addView(t);
        }
        for (int i = 0; i < l.size(); i++) {
            Keys.Key k = l.get(i);
            if (i > 0) { View sep = new View(this); sep.setBackgroundColor(0x1AFFFFFF); list.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
            LinearLayout r = Ui.hbox(this);
            r.setMinimumHeight(Ui.dp(this, 58));
            r.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            r.setBackgroundResource(R.drawable.row_bg);
            ImageView lock = new ImageView(this);
            lock.setImageResource(R.drawable.ic_key); lock.setColorFilter(k.valid() ? Ui.OK : Ui.WARN);
            r.addView(lock, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
            LinearLayout t = Ui.vbox(this);
            t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
            t.addView(Ui.text(this, k.name.isEmpty() ? "(no name)" : k.name, 15, Ui.WHITE, true));
            LinearLayout line = Ui.hbox(this);
            line.addView(Ui.chip(this, k.type()));
            TextView m = Ui.mono(this, k.masked(), 12, Ui.TEXT3);
            m.setPadding(Ui.dp(this, 8), 0, 0, 0);
            line.addView(m);
            t.addView(line, Ui.matchWrap(this, 3));
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            String used = keys.usedBy(k.id);
            TextView u = Ui.text(this, used.isEmpty() ? "not used" : "used by\n" + used, 11, Ui.LABEL, false);
            u.setGravity(Gravity.END);
            r.addView(u);
            r.setOnClickListener(v -> startActivity(new Intent(this, KeyEditActivity.class).putExtra("id", k.id)));
            list.addView(r);
        }
    }
}
