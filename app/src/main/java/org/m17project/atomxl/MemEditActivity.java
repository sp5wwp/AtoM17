/*
 * AtoM17 - edit one memory, full window: name, frequency, mode, CAN; the FM settings (channel width, CTCSS) apply to
 * an FM channel only and are greyed out for M17. Move up / down, Delete, Save.
 * Extras: index (-1 = a new one), current (start from the radio's channel).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public final class MemEditActivity extends Activity {
    private Station st;
    private Memories mem;
    private int index;
    private Memories.Ch ch;
    private EditText name, mhz, can, offset;
    private TextView rxTone, txTone;
    private LinearLayout fmCard, m17Card;
    private TextView keyPick, txNote;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = Station.get(this);
        mem = new Memories(st.cfg);
        index = getIntent().getIntExtra("index", -1);
        boolean current = getIntent().getBooleanExtra("current", false);
        List<Memories.Ch> l = mem.all();
        if (index >= l.size()) index = -1;
        if (index >= 0) ch = l.get(index);
        else { ch = Memories.Ch.current(st.cfg); if (!current) ch.name = ""; }

        LinearLayout root = Ui.vbox(this);
        root.addView(Ui.header(this, index >= 0 ? "Memory " + (index + 1) : current ? "Store current channel" : "New memory"));
        LinearLayout body = Ui.vbox(this);
        int p = Ui.dp(this, 12);
        body.setPadding(p, Ui.dp(this, 4), p, p);

        name = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, ch.name, "e.g. SR5WAW Warszawa", false);
        body.addView(labelled(label("Name", null), name, 0));

        LinearLayout fr = Ui.hbox(this);
        fr.setGravity(android.view.Gravity.BOTTOM);
        mhz = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, String.format(Locale.US, "%.6f", ch.hz / 1e6), "438.825000", true);
        can = field(InputType.TYPE_CLASS_NUMBER, String.valueOf(ch.can), "0", true);
        fr.addView(labelled(label("Frequency,", "MHz"), mhz, 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(this, 76), ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = Ui.dp(this, 10);
        fr.addView(labelled(label("CAN", null), can, 0), cp);
        body.addView(fr, Ui.matchWrap(this, 13));

        LinearLayout mp = Ui.hbox(this);
        mp.setGravity(android.view.Gravity.BOTTOM);
        mp.addView(labelled(label("Mode", null), Ui.seg(this, new String[] { "FM", "M17" }, ch.m17 ? 1 : 0, k -> { ch.m17 = k == 1; fmEnabled(); }), 0),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        pp.leftMargin = Ui.dp(this, 10);
        mp.addView(labelled(label("RF power", null), Ui.seg(this, new String[] { "High", "Low" }, ch.high ? 0 : 1, k -> ch.high = k == 0), 0), pp);
        body.addView(mp, Ui.matchWrap(this, 13));

        LinearLayout rp = Ui.hbox(this);
        rp.setGravity(android.view.Gravity.BOTTOM);
        rp.addView(labelled(label("Repeater shift", null), Ui.seg(this, new String[] { "Simplex", "−", "+" }, ch.shift == 0 ? 0 : ch.shift < 0 ? 1 : 2,
                k -> { ch.shift = k == 0 ? 0 : k == 1 ? -1 : 1; offsetEnabled(); txNote(); }), 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        offset = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, Config.shiftText(1, ch.offset).substring(1), "7.6", true);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(Ui.dp(this, 100), ViewGroup.LayoutParams.WRAP_CONTENT);
        op.leftMargin = Ui.dp(this, 10);
        rp.addView(labelled(label("Offset,", "MHz"), offset, 0), op);
        body.addView(rp, Ui.matchWrap(this, 13));
        txNote = Ui.text(this, "", 12.5f, Ui.TEXT3, false);
        body.addView(txNote, Ui.matchWrap(this, 6));
        offsetEnabled();
        android.text.TextWatcher txWatch = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable e) { txNote(); }
        };
        mhz.addTextChangedListener(txWatch); offset.addTextChangedListener(txWatch);
        txNote();

        body.addView(label("M17 only", null), Ui.matchWrap(this, 16));
        m17Card = Ui.card(this, 10);
        keyPick = Ui.text(this, "", 15, Ui.WHITE, true);
        keyPick.setBackgroundResource(R.drawable.field);
        keyPick.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_chevron, 0);
        keyPick.setOnClickListener(v -> pickKey());
        showKey();
        m17Card.addView(labelled(label("Encryption", null), keyPick, 0));
        android.widget.CheckBox sign = new android.widget.CheckBox(this);
        sign.setText("Sign transmissions (ECDSA, with the station key)");
        sign.setTextColor(Ui.WHITE); sign.setTextSize(15); sign.setChecked(ch.sign);
        sign.setOnCheckedChangeListener((c, on) -> ch.sign = on);
        m17Card.addView(sign, Ui.matchWrap(this, 6));
        m17Card.addView(Ui.text(this, "Encrypted transmissions are not allowed on amateur bands in most countries.", 12.5f, Ui.WARN, false), Ui.matchWrap(this, 4));
        body.addView(m17Card, Ui.matchWrap(this, 6));

        TextView fmHead = label("FM only", null);
        body.addView(fmHead, Ui.matchWrap(this, 16));
        fmCard = Ui.card(this, 10);
        fmCard.addView(labelled(label("Channel width", null), Ui.seg(this, new String[] { "12.5 kHz", "25 kHz" }, ch.wide ? 1 : 0, k -> ch.wide = k == 1), 0));
        LinearLayout tones = Ui.hbox(this);
        rxTone = picker(ch.rxTone, v -> ch.rxTone = v);
        txTone = picker(ch.txTone, v -> ch.txTone = v);
        tones.addView(labelled(label("RX CTCSS", null), rxTone, 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = Ui.dp(this, 10);
        tones.addView(labelled(label("TX CTCSS", null), txTone, 0), tp);
        fmCard.addView(tones, Ui.matchWrap(this, 10));
        body.addView(fmCard, Ui.matchWrap(this, 6));
        fmEnabled();

        if (index >= 0) {
            LinearLayout mv = Ui.hbox(this);
            Button up = small("Move up"), down = small("Move down");
            up.setEnabled(index > 0); down.setEnabled(index < l.size() - 1);
            up.setOnClickListener(v -> move(-1));
            down.setOnClickListener(v -> move(1));
            mv.addView(up, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
            dp.leftMargin = Ui.dp(this, 8);
            mv.addView(down, dp);
            body.addView(mv, Ui.matchWrap(this, 16));
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = Ui.hbox(this);
        bottom.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        if (index >= 0) {
            Button del = small("Delete");
            del.setBackgroundResource(R.drawable.btn_danger);
            del.setTextColor(0xFFFFB3B5);
            del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Delete memory " + (index + 1) + ", " + ch.name + "?")
                    .setPositiveButton("Delete", (d, w) -> {
                        List<Memories.Ch> l2 = mem.all();
                        if (index < l2.size()) l2.remove(index);
                        mem.save(l2);
                        int cur = mem.index();
                        if (cur > index || cur >= l2.size()) st.cfg.edit().putInt("memIdx", Math.max(0, cur - 1)).apply();
                        if (l2.isEmpty()) st.cfg.edit().putBoolean("memMode", false).apply();
                        finish();
                    }).setNegativeButton("Cancel", null).show());
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
            dl.rightMargin = Ui.dp(this, 8);
            del.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
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

    // ---- helpers ----

    /** An upper-case field label; {@code unit} (MHz) keeps its own case. */
    private TextView label(String text, String unit) {
        TextView t = Ui.label(this, "");
        t.setAllCaps(false);
        String s = text.toUpperCase(Locale.ROOT) + (unit == null ? "" : " " + unit);
        t.setText(s);
        return t;
    }

    private View labelled(TextView label, View v, int topDp) {
        LinearLayout l = Ui.vbox(this);
        l.addView(label);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, 5);
        l.addView(v, p);
        if (topDp > 0) l.setPadding(0, Ui.dp(this, topDp), 0, 0);
        return l;
    }

    private EditText field(int type, String text, String hint, boolean mono) {
        EditText e = new EditText(this);
        e.setInputType(type); e.setText(text); e.setHint(hint); e.setSingleLine(true);
        e.setTextColor(Ui.WHITE); e.setHintTextColor(Ui.IDLE); e.setTextSize(16);
        e.setTypeface(mono ? Ui.MONO_B : android.graphics.Typeface.DEFAULT_BOLD);
        e.setBackgroundResource(R.drawable.field);
        return e;
    }

    private Button small(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextColor(Ui.WHITE); b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); b.setTextSize(14);
        b.setBackgroundResource(R.drawable.btn); b.setMinHeight(0); b.setMinimumHeight(0);
        return b;
    }

    private interface IntSet { void set(int v); }

    /** A CTCSS selector: a field-like button opening the list. */
    private TextView picker(int sel, IntSet set) {
        TextView t = Ui.text(this, SettingsActivity.tone(sel), 15, Ui.WHITE, true);
        t.setBackgroundResource(R.drawable.field);
        t.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_chevron, 0);
        t.setOnClickListener(v -> {
            String[] tones = new String[Config.CTCSS.length + 1];
            tones[0] = "none"; for (int i = 0; i < Config.CTCSS.length; i++) tones[i + 1] = String.format(Locale.US, "%.1f Hz", Config.CTCSS[i]);
            int cur = 0; for (int i = 0; i < tones.length; i++) if (SettingsActivity.tone(i).equals(t.getText().toString())) cur = i;
            new AlertDialog.Builder(this).setSingleChoiceItems(tones, cur, (d, w) -> { set.set(w); t.setText(SettingsActivity.tone(w)); d.dismiss(); }).show();
        });
        return t;
    }

    /** The live "Transmits on …" line under the shift: hidden for simplex, red outside the region's 70 cm band. */
    private void txNote() {
        if (txNote == null) return;
        double rx, off;
        try { rx = Double.parseDouble(mhz.getText().toString().trim().replace(',', '.')); } catch (NumberFormatException e) { rx = -1; }
        try { off = Double.parseDouble(offset.getText().toString().trim().replace(',', '.')); } catch (NumberFormatException e) { off = -1; }
        if (ch.shift == 0 || rx <= 0 || off <= 0) {
            // simplex: still warn when the frequency itself is out of band
            int hz = (int) Math.round(rx * 1e6);
            if (rx > 0 && !Config.inBand(hz, st.cfg.region())) {
                txNote.setText(Config.txCaption(hz, st.cfg.region())); txNote.setTextColor(Ui.BAD); txNote.setVisibility(View.VISIBLE);
            } else txNote.setVisibility(View.GONE);
            return;
        }
        int tx = (int) Math.round((rx + ch.shift * off) * 1e6);
        boolean in = Config.inBand(tx, st.cfg.region());
        txNote.setText(Config.txCaption(tx, st.cfg.region()));
        txNote.setTextColor(in ? Ui.TEXT3 : Ui.BAD);
        txNote.setVisibility(View.VISIBLE);
    }

    private void offsetEnabled() { offset.setEnabled(ch.shift != 0); offset.setAlpha(ch.shift != 0 ? 1f : 0.55f); }

    private void showKey() {
        Keys.Key k = new Keys(st.cfg).byId(ch.keyId);
        if (k == null) ch.keyId = "";
        keyPick.setText(k == null ? "None" : k.name + " · " + k.type());
    }

    private void pickKey() {
        java.util.List<Keys.Key> l = new Keys(st.cfg).all();
        String[] names = new String[l.size() + 2];
        names[0] = "None";
        int cur = 0;
        for (int i = 0; i < l.size(); i++) { names[i + 1] = l.get(i).name + " · " + l.get(i).type(); if (l.get(i).id.equals(ch.keyId)) cur = i + 1; }
        names[l.size() + 1] = "Manage keys…";
        new AlertDialog.Builder(this).setTitle("Encryption").setSingleChoiceItems(names, cur, (d, w) -> {
            d.dismiss();
            if (w == names.length - 1) { startActivity(new android.content.Intent(this, KeysActivity.class)); return; }
            ch.keyId = w == 0 ? "" : l.get(w - 1).id;
            showKey();
        }).show();
    }

    @Override protected void onResume() { super.onResume(); if (keyPick != null) showKey(); }

    private void fmEnabled() {
        boolean fm = !ch.m17;
        m17Card.setAlpha(fm ? 0.55f : 1f);
        setEnabled(m17Card, !fm);
        fmCard.setAlpha(fm ? 1f : 0.55f);
        setEnabled(fmCard, fm);
        can.setEnabled(ch.m17); can.setAlpha(ch.m17 ? 1f : 0.55f);
    }
    private static void setEnabled(View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) setEnabled(((ViewGroup) v).getChildAt(i), on);
    }

    /** Read the fields into ch; false (with the field marked) when one is wrong. */
    private boolean read() {
        int hz;
        try { hz = (int) Math.round(Double.parseDouble(mhz.getText().toString().trim().replace(',', '.')) * 1e6); }
        catch (NumberFormatException e) { mhz.setError("MHz, e.g. 438.825"); return false; }
        if (hz < 400_000_000 || hz > 480_000_000) { mhz.setError("400–480 MHz"); return false; }
        int cn;
        try { cn = Integer.parseInt(can.getText().toString().trim()); } catch (NumberFormatException e) { cn = -1; }
        if (ch.m17 && (cn < 0 || cn > 15)) { can.setError("0–15"); return false; }
        int off = ch.offset;
        if (ch.shift != 0) {
            try { off = (int) Math.round(Double.parseDouble(offset.getText().toString().trim().replace(',', '.')) * 1e6); }
            catch (NumberFormatException e) { offset.setError("MHz, e.g. 7.6"); return false; }
            if (off <= 0 || off > 40_000_000) { offset.setError("0–40 MHz"); return false; }
            int tx = hz + ch.shift * off;
            if (tx < 400_000_000 || tx > 480_000_000) { offset.setError("TX " + Config.mhz(tx) + " MHz is outside 400–480"); return false; }
        }
        ch.offset = off;
        ch.hz = hz;
        ch.can = Math.max(0, Math.min(15, cn));
        String n = name.getText().toString().trim();
        ch.name = n.isEmpty() ? Config.mhz(hz) : n;
        return true;
    }

    private void save() {
        if (!read()) return;
        List<Memories.Ch> l = mem.all();
        if (index >= 0 && index < l.size()) l.set(index, ch); else { l.add(ch); index = l.size() - 1; }
        mem.save(l);
        if (mem.memMode() && index == mem.index()) mem.apply(st, index);       // the channel in use was edited: retune
        finish();
    }

    private void move(int d) {
        if (!read()) return;
        List<Memories.Ch> l = mem.all();
        int to = index + d;
        if (index < 0 || to < 0 || to >= l.size()) return;
        l.set(index, ch);
        Memories.Ch x = l.remove(index);
        l.add(to, x);
        mem.save(l);
        int cur = mem.index();                                                  // the channel in use keeps being the same channel
        if (cur == index) st.cfg.edit().putInt("memIdx", to).apply();
        else if (cur == to) st.cfg.edit().putInt("memIdx", index).apply();
        finish();
    }
}
