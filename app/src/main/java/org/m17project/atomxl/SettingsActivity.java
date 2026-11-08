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
        Keys.Key vk = new Keys(cfg).byId(cfg.p.getString("vfoKeyId", ""));
        String enc = "VFO: " + (vk == null ? "no encryption" : vk.name);
        int nk = new Keys(cfg).all().size();
        group("M17 transmit",
                new Row("Station and META", (cfg.src().isEmpty() ? "no callsign" : cfg.src()) + " → " + (cfg.dst().isEmpty() ? "@ALL" : cfg.dst()) + " · CAN " + cfg.can()
                        + "\n" + meta + " · lead-in " + (cfg.leadInMs() == 0 ? "none" : cfg.leadInMs() + " ms"), page("station", "Station and META")),
                new Row("Encryption and signing", enc + ", " + (cfg.p.getBoolean("vfoSign", cfg.sign()) ? "signed" : "not signed") + " · " + nk + (nk == 1 ? " key" : " keys"), page("crypto", "Encryption and signing")));

        int pk = 0;
        for (String l : cfg.pubKeys().split("\n")) if (!l.trim().isEmpty()) pk++;
        group("M17 receive",
                new Row("Voice decoder and polarity", (cfg.neural() ? "Neural (FARGAN), 16 kHz" : "Codec2, 8 kHz") + (cfg.agc() ? " · AGC" : "") + " · " + (cfg.invert() ? "inverted" : "normal polarity"), page("rx", "Voice decoder and polarity")),
                new Row("Signature checking", pk + " public key" + (pk == 1 ? "" : "s") + " · decryption uses the channel's key", page("rxkeys", "Signature checking")));

        group("Radio",
                new Row("IARU Region", Config.regionText(cfg.region()), this::pickRegion),
                new Row("Tuning, FM and power", "Step " + step(cfg.step()) + String.format(Locale.US, " · %.1f ppm", cfg.ppm()) + " · squelch " + (cfg.squelch() == 0 ? "open" : String.valueOf(cfg.squelch()))
                        + "\nCTCSS " + tone(cfg.rxTone()) + " / " + tone(cfg.txTone()) + " · " + (cfg.wide() ? "25" : "12.5") + " kHz · " + (cfg.high() ? "high" : "low") + " power", page("radio", "Tuning, FM and power")),
                new Row("Memories", memCount(), () -> startActivity(new Intent(this, MemoriesActivity.class))));

        group("Messages",
                new Row("Message notifications", MsgNotify.WHICH[Math.max(0, Math.min(3, cfg.p.getInt("msgNotify", 2)))]
                        + (cfg.p.getInt("msgNotify", 2) == 0 ? "" : "\n" + MsgNotify.soundName(this, cfg) + " · " + MsgNotify.VIBRATIONS[MsgNotify.vibration(cfg)].toLowerCase(Locale.US)),
                        page("msgs", "Message notifications")));

        String tag = st.moduleTag();
        group("Device",
                new Row("PTT key", (st.pttKey.learned() ? "Learned: " + st.pttKey.code() : "Not learned") + (cfg.p.getBoolean("softPtt", false) ? " · on-screen PTT on" : ""), page("ptt", "PTT key")),
                new Row("Module firmware", (tag.isEmpty() ? "start the radio to read it" : tag)
                        + (tag.endsWith("V076X") ? String.format(Locale.US, " · deviation ×%.3f", cfg.devGainLoaded()) : ""), () -> startActivity(new Intent(this, FirmwareActivity.class))),
                new Row("Bench", "Receive signal · voice check · test tone · recording", page("bench", "Bench")),
                new Row("Screen and power", new String[] { "Screen: phone timeout", "Screen: on while active", "Screen: always on" }[Math.max(0, Math.min(2, cfg.p.getInt("screenOn", 1)))]
                        + " · " + new String[] { "never woken", "woken for messages", "woken for calls and messages" }[Math.max(0, Math.min(2, cfg.p.getInt("wakeFor", 1)))], page("screen", "Screen and power")),
                new Row("Backup and restore", "Settings, keys, memories and messages in one file", this::backup),
                new Row("Log", "What the radio and the decoder did", this::showLog),
                new Row("About", "AtoM17 " + BuildConfig.VERSION_NAME + " · SP5WWP, M17 Foundation", page("about", "About")));
    }

    private void pickRegion() {
        String[] items = { Config.regionText(1) + "\nEurope, Africa, Middle East, northern Asia", Config.regionText(2) + "\nThe Americas", Config.regionText(3) + "\nAsia-Pacific" };
        new AlertDialog.Builder(this).setTitle("IARU Region")
                .setSingleChoiceItems(items, cfg.region() - 1, (d, w) -> { cfg.edit().putInt("iaruRegion", w + 1).apply(); d.dismiss(); build(); })
                .setNegativeButton("Cancel", null).show();
    }

    private String memCount() {
        int n = new Memories(cfg).all().size();
        return n == 0 ? "None stored · MEM on the main screen" : n + " channel" + (n == 1 ? "" : "s");
    }

    // ---- backup ----

    private static final int EXPORT = 11, IMPORT = 12;

    private void backup() {
        new AlertDialog.Builder(this).setTitle("Backup and restore")
                .setMessage("The file holds every setting, the AES keys, the scrambler seed, your signing key, the memories and the messages, in clear. Keep it somewhere safe.")
                .setPositiveButton("Export…", (d, w) -> {
                    String name = "atom17-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date()) + ".json";
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name), EXPORT);
                })
                .setNeutralButton("Import…", (d, w) -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT))
                .setNegativeButton("Cancel", null).show();
    }

    private static final String[] FILES = { "atom17", "inbox" };

    private static org.json.JSONObject dump(android.content.SharedPreferences p) throws org.json.JSONException {
        org.json.JSONObject o = new org.json.JSONObject();
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            Object v = e.getValue();
            String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f" : v instanceof String ? "s" : null;
            if (t != null) o.put(e.getKey(), new org.json.JSONObject().put("t", t).put("v", v));
        }
        return o;
    }

    private static void load(android.content.SharedPreferences p, org.json.JSONObject o) throws org.json.JSONException {
        android.content.SharedPreferences.Editor ed = p.edit().clear();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next(); org.json.JSONObject e = o.getJSONObject(k);
            switch (e.getString("t")) {
                case "b": ed.putBoolean(k, e.getBoolean("v")); break;
                case "i": ed.putInt(k, e.getInt("v")); break;
                case "l": ed.putLong(k, e.getLong("v")); break;
                case "f": ed.putFloat(k, (float) e.getDouble("v")); break;
                default: ed.putString(k, e.getString("v"));
            }
        }
        ed.commit();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri u = data.getData();
        try {
            if (req == EXPORT) {
                org.json.JSONObject all = new org.json.JSONObject().put("app", "AtoM17").put("version", BuildConfig.VERSION_NAME).put("format", 1);
                for (String f : FILES) all.put(f, dump(getSharedPreferences(f, MODE_PRIVATE)));
                try (java.io.OutputStream out = getContentResolver().openOutputStream(u, "wt")) {
                    out.write(all.toString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                android.widget.Toast.makeText(this, "Exported", android.widget.Toast.LENGTH_SHORT).show();
            } else if (req == IMPORT) {
                byte[] b;
                try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    b = bo.toByteArray();
                }
                org.json.JSONObject all = new org.json.JSONObject(new String(b, java.nio.charset.StandardCharsets.UTF_8));
                if (!"AtoM17".equals(all.optString("app"))) throw new IllegalArgumentException("not an AtoM17 backup");
                new AlertDialog.Builder(this).setTitle("Restore this backup?")
                        .setMessage("From AtoM17 " + all.optString("version", "?") + ". It replaces every current setting, key, memory and message.")
                        .setPositiveButton("Restore", (d, w) -> {
                            try {
                                for (String f : FILES) if (all.has(f)) load(getSharedPreferences(f, MODE_PRIVATE), all.getJSONObject(f));
                                st.restored();
                                android.widget.Toast.makeText(this, "Restored", android.widget.Toast.LENGTH_SHORT).show();
                                build();
                            } catch (Exception e) { android.widget.Toast.makeText(this, "Restore failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
                        }).setNegativeButton("Cancel", null).show();
            }
        } catch (Exception e) { android.widget.Toast.makeText(this, (req == EXPORT ? "Export" : "Import") + " failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
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
