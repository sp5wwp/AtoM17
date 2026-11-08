/*
 * AtoM17 - edit one named key: name, type (AES / scrambler), length, the key (hidden but for its last digits; Show,
 * Enter…, Generate), Delete, Save. Extra: id (none = a new key).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

public final class KeyEditActivity extends Activity {
    private Station st;
    private Keys keys;
    private Keys.Key key;
    private boolean isNew, shown;
    private EditText name;
    private TextView value, valueLabel;
    private LinearLayout lenBox;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = Station.get(this);
        keys = new Keys(st.cfg);
        key = keys.byId(getIntent().getStringExtra("id"));
        isNew = key == null;
        if (isNew) { key = new Keys.Key(); key.id = Keys.newId(); }

        LinearLayout root = Ui.vbox(this);
        root.addView(Ui.header(this, isNew ? "New key" : key.name));
        LinearLayout body = Ui.vbox(this);
        int p = Ui.dp(this, 12);
        body.setPadding(p, Ui.dp(this, 4), p, p);

        name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(key.name); name.setHint("e.g. Club AES"); name.setSingleLine(true);
        name.setTextColor(Ui.WHITE); name.setHintTextColor(Ui.IDLE); name.setTextSize(16); name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setBackgroundResource(R.drawable.field);
        body.addView(labelled("NAME", name));

        body.addView(labelled("TYPE", Ui.seg(this, new String[] { "AES", "Scrambler" }, key.aes ? 0 : 1, k -> {
            boolean aes = k == 0;
            if (aes != key.aes) { key.aes = aes; key.bits = aes ? 256 : 24; key.hex = ""; lengths(); showValue(); }
        })), Ui.matchWrap(this, 13));
        lenBox = Ui.vbox(this);
        body.addView(lenBox, Ui.matchWrap(this, 13));
        lengths();

        valueLabel = Ui.label(this, "");
        value = Ui.mono(this, "", 14, Ui.WHITE);
        value.setTypeface(Ui.MONO_B);
        value.setBackgroundResource(R.drawable.field);
        LinearLayout vb = Ui.vbox(this);
        vb.addView(valueLabel);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = Ui.dp(this, 5);
        vb.addView(value, vp);
        body.addView(vb, Ui.matchWrap(this, 13));

        LinearLayout acts = Ui.hbox(this);
        Button show = small("Show"), enter = small("Enter…"), gen = small("Generate");
        show.setOnClickListener(v -> { shown = !shown; show.setText(shown ? "Hide" : "Show"); showValue(); });
        enter.setOnClickListener(v -> enter());
        gen.setOnClickListener(v -> {
            byte[] r = new byte[key.bits / 8];
            SecureRandom sr = new SecureRandom();
            do sr.nextBytes(r); while (!key.aes && allZero(r));
            StringBuilder h = new StringBuilder();
            for (byte x : r) h.append(String.format("%02X", x & 0xFF));
            key.hex = h.toString(); showValue();
        });
        acts.addView(show, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1); ap.leftMargin = Ui.dp(this, 8);
        acts.addView(enter, ap);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1); gp.leftMargin = Ui.dp(this, 8);
        acts.addView(gen, gp);
        body.addView(acts, Ui.matchWrap(this, 10));

        String used = keys.usedBy(key.id);
        if (!used.isEmpty()) body.addView(Ui.text(this, "Used by " + used + ". Changing the key changes it there too.", 12.5f, Ui.TEXT3, false), Ui.matchWrap(this, 12));
        showValue();

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = Ui.hbox(this);
        bottom.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        if (!isNew) {
            Button del = small("Delete");
            del.setBackgroundResource(R.drawable.btn_danger); del.setTextColor(0xFFFFB3B5);
            del.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("Delete the key \"" + key.name + "\"?" + (used.isEmpty() ? "" : " " + capital(used) + " will transmit and receive without encryption."))
                    .setPositiveButton("Delete", (d, w) -> { delete(); finish(); }).setNegativeButton("Cancel", null).show());
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
            dl.rightMargin = Ui.dp(this, 8);
            bottom.addView(del, dl);
        }
        Button save = new Button(this);
        save.setText("Save"); save.setAllCaps(false); save.setTextColor(0xFF1B1206); save.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); save.setTextSize(15);
        save.setBackgroundResource(R.drawable.btn_primary);
        save.setOnClickListener(v -> save());
        bottom.addView(save, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        root.addView(bottom);
        setContentView(root);
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static boolean allZero(byte[] b) { for (byte x : b) if (x != 0) return false; return true; }

    private void lengths() {
        lenBox.removeAllViews();
        int[] opts = key.aes ? new int[] { 128, 192, 256 } : new int[] { 8, 16, 24 };
        String[] names = new String[3]; int sel = 2;
        for (int i = 0; i < 3; i++) { names[i] = String.valueOf(opts[i]); if (opts[i] == key.bits) sel = i; }
        lenBox.addView(labelled("LENGTH, BITS", Ui.seg(this, names, sel, k -> { if (key.bits != opts[k]) { key.bits = opts[k]; key.hex = ""; showValue(); } })));
    }

    private void showValue() {
        String h = M17Tab.clean(key.hex);
        valueLabel.setText((key.aes ? "KEY" : "SEED") + " · " + key.bits / 4 + " HEX DIGITS");
        if (h.isEmpty()) { value.setText("not set"); value.setTextColor(Ui.WARN); return; }
        value.setTextColor(Ui.WHITE);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.length(); i += 4) {
            if (b.length() > 0) b.append(' ');
            String g = h.substring(i, Math.min(h.length(), i + 4));
            b.append(shown || i + 4 >= h.length() ? g : "••••");
        }
        value.setText(b);
    }

    private void enter() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        e.setHint(key.bits / 4 + " hex digits"); e.setTypeface(Ui.MONO);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(key.aes ? "AES key" : "Scrambler seed").setView(e)
                .setPositiveButton("Set", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String h = M17Tab.clean(e.getText().toString());
            if (h.length() != key.bits / 4) { e.setError(key.bits / 4 + " hex digits, " + h.length() + " given"); return; }
            if (!key.aes && h.matches("0+")) { e.setError("not all zero"); return; }
            key.hex = h; showValue(); d.dismiss();
        }));
        d.show();
    }

    private LinearLayout labelled(String label, android.view.View v) {
        LinearLayout l = Ui.vbox(this);
        TextView t = Ui.label(this, ""); t.setAllCaps(false); t.setText(label);
        l.addView(t);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, 5);
        l.addView(v, p);
        return l;
    }

    private Button small(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextColor(Ui.WHITE); b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); b.setTextSize(14);
        b.setBackgroundResource(R.drawable.btn); b.setMinHeight(0); b.setMinimumHeight(0);
        return b;
    }

    private void save() {
        String n = name.getText().toString().trim();
        if (n.isEmpty()) { name.setError("a name"); return; }
        if (!key.valid()) { new AlertDialog.Builder(this).setMessage("Enter or generate the " + (key.aes ? "key" : "seed") + " first.").setPositiveButton("OK", null).show(); return; }
        key.name = n;
        List<Keys.Key> l = keys.all();
        boolean found = false;
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(key.id)) { l.set(i, key); found = true; }
        if (!found) l.add(key);
        keys.save(l);
        if (key.id.equals(st.cfg.p.getString("keyId", ""))) { keys.activate(key.id); st.configureRx(); }   // in use now: the new key applies
        finish();
    }

    private void delete() {
        List<Keys.Key> l = keys.all();
        l.removeIf(k -> k.id.equals(key.id));
        keys.save(l);
        List<Memories.Ch> m = new Memories(st.cfg).all();                  // channels that used it: no encryption
        for (Memories.Ch c : m) if (key.id.equals(c.keyId)) c.keyId = "";
        new Memories(st.cfg).save(m);
        if (key.id.equals(st.cfg.p.getString("vfoKeyId", ""))) st.cfg.edit().putString("vfoKeyId", "").apply();
        if (key.id.equals(st.cfg.p.getString("keyId", ""))) { keys.activate(""); st.configureRx(); }
    }
}
