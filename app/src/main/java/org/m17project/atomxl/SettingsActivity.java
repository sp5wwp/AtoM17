/*
 * AtoM17 - Settings: one full-window list. Every row shows its current value and opens a full-window page.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public final class SettingsActivity extends Activity {
    private Station st;
    private Config cfg;
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = Station.get(this);
        cfg = st.cfg;
        LinearLayout root = Ui.vbox(this);
        root.addView(Ui.header(this, "Settings"));
        list = Ui.vbox(this);
        int p = Ui.dp(this, 10);
        list.setPadding(p, 0, p, p * 2);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); build(); }

    private interface Go { void go(); }
    private static final class Row { final String title, value; final Go go; Row(String t, String v, Go g) { title = t; value = v; go = g; } }

    private Go page(String id, String title) {
        return () -> startActivity(new Intent(this, SettingsPageActivity.class).putExtra("page", id).putExtra("title", title));
    }

    private void build() {
        list.removeAllViews();
        String[] metas = { "text", "position", "extended callsign" };
        String meta = cfg.enc() != M17Native.ENC_NONE ? "META: encryption" : "META " + metas[Math.max(0, Math.min(2, cfg.metaKind()))]
                + (cfg.metaKind() == M17Native.META_TEXT && !cfg.metaText().isEmpty() ? " “" + cfg.metaText() + "”" : "");
        String enc = cfg.enc() == M17Native.ENC_AES ? "AES-" + cfg.aesBits() : cfg.enc() == M17Native.ENC_SCRAM ? "Scrambler " + cfg.scramBits() + "-bit" : "No encryption";
        group("M17 transmit",
                new Row("Station and META", (cfg.src().isEmpty() ? "no callsign" : cfg.src()) + " → " + (cfg.dst().isEmpty() ? "@ALL" : cfg.dst()) + " · CAN " + cfg.can()
                        + "\n" + meta + " · lead-in " + (cfg.leadInMs() == 0 ? "none" : cfg.leadInMs() + " ms"), page("station", "Station and META")),
                new Row("Encryption and signing", enc + " · " + (cfg.sign() ? "signing on" : "not signed"), page("crypto", "Encryption and signing")));

        int pk = 0;
        for (String l : cfg.pubKeys().split("\n")) if (!l.trim().isEmpty()) pk++;
        group("M17 receive",
                new Row("Voice decoder and polarity", (cfg.neural() ? "Neural (FARGAN), 16 kHz" : "Codec2, 8 kHz") + (cfg.agc() ? " · AGC" : "") + " · " + (cfg.invert() ? "inverted" : "normal polarity"), page("rx", "Voice decoder and polarity")),
                new Row("Receive keys", (M17Tab.clean(cfg.rxAesKey()).isEmpty() ? "no AES key" : "AES key set") + " · " + (M17Tab.clean(cfg.rxScramSeed()).isEmpty() ? "no scrambler seed" : "scrambler seed set")
                        + " · " + pk + " public key" + (pk == 1 ? "" : "s"), page("rxkeys", "Receive keys")));

        group("Radio",
                new Row("Tuning, FM and power", "Step " + step(cfg.step()) + String.format(Locale.US, " · %.1f ppm", cfg.ppm()) + " · squelch " + (cfg.squelch() == 0 ? "open" : String.valueOf(cfg.squelch()))
                        + "\nCTCSS " + tone(cfg.rxTone()) + " / " + tone(cfg.txTone()) + " · " + (cfg.wide() ? "25" : "12.5") + " kHz · " + (cfg.high() ? "high" : "low") + " power", page("radio", "Tuning, FM and power")));

        String tag = st.moduleTag();
        group("Device",
                new Row("PTT key", (st.pttKey.learned() ? "Learned: " + st.pttKey.code() : "Not learned") + (cfg.p.getBoolean("softPtt", false) ? " · on-screen PTT on" : ""), page("ptt", "PTT key")),
                new Row("Module firmware", (tag.isEmpty() ? "start the radio to read it" : tag)
                        + (tag.endsWith("V076X") ? String.format(Locale.US, " · deviation ×%.3f", cfg.devGainLoaded()) : ""), () -> startActivity(new Intent(this, FirmwareActivity.class))),
                new Row("Bench", "Transmit test tone · record receive audio", page("bench", "Bench")),
                new Row("Log", "What the radio and the decoder did", this::showLog),
                new Row("About", "AtoM17 " + BuildConfig.VERSION_NAME + " · SP5WWP, M17 Foundation", page("about", "About")));
    }

    static String step(int hz) { return hz >= 1000 ? (hz % 1000 == 0 ? hz / 1000 + " kHz" : hz / 1000.0 + " kHz") : hz + " Hz"; }
    static String tone(int i) { return i <= 0 || i > Config.CTCSS.length ? "none" : String.format(Locale.US, "%.1f", Config.CTCSS[i - 1]); }

    private void group(String title, Row... rows) {
        TextView h = Ui.label(this, title);
        h.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        list.addView(h);
        LinearLayout card = Ui.card(this, 0);
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) { View sep = new View(this); sep.setBackgroundColor(0x1AFFFFFF); card.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
            card.addView(row(rows[i]));
        }
        list.addView(card);
    }

    private View row(Row r) {
        LinearLayout l = Ui.hbox(this);
        l.setMinimumHeight(Ui.dp(this, 56));
        l.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        l.setBackgroundResource(R.drawable.row_bg);
        LinearLayout t = Ui.vbox(this);
        t.addView(Ui.text(this, r.title, 16, Ui.WHITE, true));
        t.addView(Ui.text(this, r.value, 12.5f, Ui.TEXT3, false));
        l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView ch = new ImageView(this);
        ch.setImageResource(R.drawable.ic_chevron);
        l.addView(ch);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setOnClickListener(v -> r.go.go());
        return l;
    }

    private void showLog() {
        TextView t = new TextView(this);
        t.setText(st.logText()); t.setTextIsSelectable(true); t.setTextSize(11); t.setTypeface(Ui.MONO); t.setPadding(16, 16, 16, 16);
        ScrollView s = new ScrollView(this); s.addView(t);
        s.post(() -> s.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this).setTitle("Log").setView(s)
                .setPositiveButton("Share", (d, w) -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, st.logText()), "AtoM17 log")))
                .setNegativeButton("Close", null).show();
    }
}
