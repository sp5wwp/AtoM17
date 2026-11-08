/*
 * AtoM17 - one full-window settings page. The M17, radio and device layouts are inflated whole (so their binders find
 * every view) and only the groups of this page are shown.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class SettingsPageActivity extends Activity implements Station.Listener {
    static final int[] STEPS = { 100, 1_000, 5_000, 6_250, 10_000, 12_500, 25_000, 100_000, 1_000_000 };
    private static final int[] M17_GROUPS = { R.id.grpStation, R.id.grpMeta, R.id.grpEnc, R.id.grpSign, R.id.grpRxKeys, R.id.grpRx };
    private static final int[] DEVICE_GROUPS = { R.id.grpPtt, R.id.grpBench, R.id.grpMsgs, R.id.grpAbout };

    private Station st;
    private Config cfg;
    private M17Tab m17tab;
    private String page;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = Station.get(this);
        cfg = st.cfg;
        page = getIntent().getStringExtra("page");
        if (page == null) page = "station";
        String title = getIntent().getStringExtra("title");

        LinearLayout root = Ui.vbox(this);
        root.addView(Ui.header(this, title == null ? "Settings" : title));
        LinearLayout body = Ui.vbox(this);
        int p = Ui.dp(this, 10);
        body.setPadding(p, 0, p, p * 3);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        switch (page) {
            case "station": case "crypto": case "rxkeys": case "rx": {
                getLayoutInflater().inflate(R.layout.tab_m17, body, true);
                int[] show = page.equals("station") ? new int[] { R.id.grpStation, R.id.grpMeta }
                        : page.equals("crypto") ? new int[] { R.id.grpEnc, R.id.grpSign }
                        : page.equals("rxkeys") ? new int[] { R.id.grpRxKeys } : new int[] { R.id.grpRx };
                only(M17_GROUPS, show);
                m17tab = new M17Tab(this, st);
                m17tab.bind();
                break;
            }
            case "radio":
                getLayoutInflater().inflate(R.layout.tab_radio, body, true);
                bindRadio();
                break;
            default: {
                getLayoutInflater().inflate(R.layout.tab_device, body, true);
                only(DEVICE_GROUPS, new int[] { page.equals("ptt") ? R.id.grpPtt : page.equals("bench") ? R.id.grpBench : page.equals("msgs") ? R.id.grpMsgs : R.id.grpAbout });
                bindDevice();
                if (page.equals("bench")) buildSignal();
            }
        }
    }

    private void only(int[] all, int[] show) {
        for (int id : all) {
            boolean on = false;
            for (int s : show) if (s == id) on = true;
            findViewById(id).setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    @Override protected void onResume() { super.onResume(); st.addListener(this); onState(); }
    @Override protected void onPause() { st.removeListener(this); super.onPause(); }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 2 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && m17tab != null) m17tab.ensureGnss();
    }

    @Override public void onState() {
        if (m17tab != null) m17tab.showGnss();
        Button rec = findViewById(R.id.record);
        if (rec != null) rec.setText(st.isRecording() ? "Recording…" : "Record M17 receive audio (30 s)");
    }
    @Override public void onStatus(org.json.JSONObject s) { showSignal(); }

    // ---- Bench › Receive signal ----

    private TextView[] sigVal;
    private ScopeView eye, hist, history;
    private TextView sigAge;

    private void buildSignal() {
        LinearLayout box = findViewById(R.id.signalBox);
        if (box == null) return;
        String[] labels = { "Symbol errors (pre-FEC)", "Equaliser EVM", "Sync symbols", "LICH corrections", "Viterbi metric", "Frames · confirmed",
                "Level (preamble RMS)", "Inner / outer levels" };
        sigVal = new TextView[labels.length];
        android.view.View[] cells = new android.view.View[labels.length];
        for (int i = 0; i < labels.length; i++) { sigVal[i] = Ui.value(this); sigVal[i].setTypeface(Ui.MONO_B); cells[i] = Ui.cell(this, labels[i], sigVal[i]); }
        sigAge = Ui.text(this, "", 12, Ui.TEXT3, false);
        box.addView(sigAge);
        box.addView(Ui.grid(this, cells), Ui.matchWrap(this, 6));
        eye = new ScopeView(this, ScopeView.Mode.EYE);
        hist = new ScopeView(this, ScopeView.Mode.HIST);
        history = new ScopeView(this, ScopeView.Mode.HISTORY);
        box.addView(Ui.label(this, "Eye · equalised symbols, raised-cosine shaped"), Ui.matchWrap(this, 12));
        box.addView(eye, Ui.matchWrap(this, 4));
        box.addView(Ui.label(this, "Equalised symbol levels (−3 −1 +1 +3)"), Ui.matchWrap(this, 12));
        box.addView(hist, Ui.matchWrap(this, 4));
        box.addView(Ui.label(this, "Symbol errors per frame (last 64)"), Ui.matchWrap(this, 12));
        box.addView(history, Ui.matchWrap(this, 4));
        showSignal();
    }

    private void showSignal() {
        if (sigVal == null) return;
        RxStats.Snap s = st.rxStats.snap();
        long age = s.lastAt == 0 ? -1 : (System.currentTimeMillis() - s.lastAt) / 1000;
        sigAge.setText(!cfg.m17() ? "Switch to M17 to measure." : age < 0 ? "Waiting for an M17 signal…" : age < 2 ? "Receiving" : "Last frame " + age + " s ago");
        float ser = s.ser;
        set(0, Float.isNaN(ser) ? "—" : String.format(Locale.US, "%.1f %%", 100 * ser) + (Float.isNaN(s.serNow) ? "" : String.format(Locale.US, " (%d/184)", Math.round(s.serNow * 184))),
                Float.isNaN(ser) ? Ui.WHITE : ser < 0.02f ? Ui.OK : ser < 0.06f ? Ui.WARN : Ui.BAD);
        set(1, Float.isNaN(s.evm) ? "—" : String.format(Locale.US, "%.1f %%", s.evm), Ui.WHITE);
        set(2, s.syncHits < 0 ? "—" : s.syncHits + "/8", s.syncHits == 8 ? Ui.OK : s.syncHits >= 6 ? Ui.WHITE : Ui.WARN);
        set(3, s.lichErrors < 0 ? "—" : String.valueOf(s.lichErrors), Ui.WHITE);
        set(4, Float.isNaN(s.metric) ? "—" : String.format(Locale.US, "%.0f", s.metric), Ui.WHITE);
        set(5, s.frames == 0 ? "—" : s.frames + " · " + s.confirmedPct + " %", Ui.WHITE);
        set(6, Float.isNaN(s.levelDb) ? "—" : String.format(Locale.US, "%.1f dBFS", s.levelDb), Ui.WHITE);
        set(7, Float.isNaN(s.innerOuter) ? "—" : String.format(Locale.US, "%.3f (1/3 nominal)", s.innerOuter), Ui.WHITE);
        eye.set(s); hist.set(s); history.set(s);
    }

    private void set(int i, String v, int color) { sigVal[i].setText(v); sigVal[i].setTextColor(color); }
    @Override public void onRx(Station.RxEntry e) { }

    private void bindRadio() {
        String[] steps = new String[STEPS.length];
        for (int i = 0; i < STEPS.length; i++) steps[i] = SettingsActivity.step(STEPS[i]);
        int stepIdx = 5; for (int i = 0; i < STEPS.length; i++) if (STEPS[i] == cfg.step()) stepIdx = i;
        M17Tab.spinner(this, R.id.step, steps, stepIdx, v -> cfg.edit().putInt("step", STEPS[v]).apply());
        String[] sq = new String[10]; for (int i = 0; i < 10; i++) sq[i] = i == 0 ? "open" : String.valueOf(i);
        M17Tab.spinner(this, R.id.sq, sq, cfg.squelch(), v -> { cfg.edit().putInt("sq", v).apply(); st.applyChannel(); });
        String[] tones = new String[Config.CTCSS.length + 1];
        tones[0] = "none"; for (int i = 0; i < Config.CTCSS.length; i++) tones[i + 1] = String.format(Locale.US, "%.1f Hz", Config.CTCSS[i]);
        M17Tab.spinner(this, R.id.rxTone, tones, cfg.rxTone(), v -> { cfg.edit().putInt("rxTone", v).apply(); st.applyChannel(); });
        M17Tab.spinner(this, R.id.txTone, tones, cfg.txTone(), v -> { cfg.edit().putInt("txTone", v).apply(); st.applyChannel(); });
        showPpm();
        findViewById(R.id.ppmEdit).setOnClickListener(v -> {
            android.widget.EditText e = new android.widget.EditText(this);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            e.setText(String.format(Locale.US, "%.1f", cfg.ppm()));
            e.setSelectAllOnFocus(true);
            AlertDialog d = new AlertDialog.Builder(this).setTitle("Frequency correction, ppm").setView(e)
                    .setPositiveButton("Set", null).setNeutralButton("0.0", (x, w) -> { cfg.edit().putFloat("ppm", 0f).apply(); showPpm(); st.applyChannel(); })
                    .setNegativeButton("Cancel", null).create();
            d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                try {
                    float v2 = Math.round(Float.parseFloat(e.getText().toString().trim().replace(',', '.')) * 10f) / 10f;
                    if (Math.abs(v2) > 50) { e.setError("at most ±50 ppm"); return; }
                    cfg.edit().putFloat("ppm", v2).apply(); showPpm(); st.applyChannel(); d.dismiss();
                } catch (NumberFormatException ex) { e.setError("a number, e.g. -1.5"); }
            }));
            d.show();
        });
        CheckBox wide = findViewById(R.id.wide), high = findViewById(R.id.high);
        wide.setChecked(cfg.wide()); high.setChecked(cfg.high());
        wide.setOnCheckedChangeListener((c, on) -> { cfg.edit().putBoolean("wide", on).apply(); st.applyChannel(); });
        high.setOnCheckedChangeListener((c, on) -> { cfg.edit().putBoolean("high", on).apply(); st.applyChannel(); });
    }

    private void showPpm() {
        float ppm = cfg.ppm();
        ((TextView) findViewById(R.id.ppmValue)).setText(String.format(Locale.US, "%+.1f ppm", ppm).replace("+0.0", "0.0")
                + String.format(Locale.US, "   (correction %+.0f Hz at %s MHz)", -cfg.freq() * ppm * 1e-6, Config.mhz(cfg.freq())).replace("correction -0 Hz", "no correction").replace("correction +0 Hz", "no correction"));
    }

    private void bindDevice() {
        showPttInfo();
        findViewById(R.id.pttLearn).setOnClickListener(v -> {
            AlertDialog d = new AlertDialog.Builder(this).setTitle("Learn the PTT key").setMessage("Press the PTT key now (10 s)…").setCancelable(false).show();
            new Thread(() -> {
                String got = st.pttKey.learn(10);
                runOnUiThread(() -> {
                    d.dismiss();
                    Toast.makeText(this, got == null ? "No key pressed" : "PTT key: " + got, Toast.LENGTH_LONG).show();
                    showPttInfo();
                    st.startPttKey();
                });
            }).start();
        });
        findViewById(R.id.pttForget).setOnClickListener(v -> { st.pttKey.forget(); showPttInfo(); });
        CheckBox soft = findViewById(R.id.softPtt);
        soft.setChecked(cfg.p.getBoolean("softPtt", false));
        soft.setOnCheckedChangeListener((c, on) -> cfg.edit().putBoolean("softPtt", on).apply());
        findViewById(R.id.testTx).setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("M17 test transmission")
                .setMessage("Transmits 10 s of M17 voice (a 1 kHz tone) on " + Config.mhz(cfg.freq()) + " MHz as " + (cfg.src().isEmpty() ? "?" : cfg.src()) + ".")
                .setPositiveButton("Transmit", (d, w) -> { String why = st.testTransmission(); if (why != null) Toast.makeText(this, why, Toast.LENGTH_LONG).show(); else finish(); })
                .setNegativeButton("Cancel", null).show());
        findViewById(R.id.record).setOnClickListener(v -> {
            String why = st.record(30);
            Toast.makeText(this, why != null ? why : "Recording 30 s: transmit to the Atom now", Toast.LENGTH_LONG).show();
            onState();
        });
        M17Tab.spinner(this, R.id.msgNotify, MsgNotify.WHICH, Math.max(0, Math.min(3, cfg.p.getInt("msgNotify", 2))), v -> cfg.edit().putInt("msgNotify", v).apply());
        showSoundName();
        findViewById(R.id.msgSound).setOnClickListener(v -> {
            android.net.Uri cur = MsgNotify.sound(cfg);
            Intent i = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "M17 message sound")
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur);
            try { startActivityForResult(i, 7); } catch (Exception e) { Toast.makeText(this, "No ringtone picker", Toast.LENGTH_SHORT).show(); }
        });
        M17Tab.spinner(this, R.id.msgVibrate, MsgNotify.VIBRATIONS, MsgNotify.vibration(cfg), v -> cfg.edit().putInt("msgVibrate", v).apply());
        findViewById(R.id.msgTest).setOnClickListener(v -> {
            int keep = cfg.p.getInt("msgNotify", 2);
            if (keep == 0) { Toast.makeText(this, "Notifications are off", Toast.LENGTH_SHORT).show(); return; }
            MsgNotify.post(this, cfg, "TEST", cfg.src().isEmpty() ? "@ALL" : cfg.src(), "This is how a received M17 SMS is announced.");
        });
        ((TextView) findViewById(R.id.about)).setText("AtoM17 " + BuildConfig.VERSION_NAME + " – FM and M17 for the Unihertz Atom XL. GPL-3.0-or-later.\n\n"
                + "Wojciech Kaczmarski, SP5WWP, M17 Foundation.\n\n"
                + "Based on SignalDeck © 2026 IIMacGyverII (GPL-3.0-or-later): bridge protocol, power sequences, the V076J symbol engine, "
                + "the firmware loader and the M17 receive equaliser.\n\nM17: libm17, Codec2-mod, Codec2-mod-neural (LGPL-2.1, with the FARGAN runtime from Opus, BSD-3-Clause), "
                + "libm17rx (M17 Project), micro-ecc, tinier-aes. Voice AGC from DroidStar (Doug McLain, GPL-3.0-or-later).\n\n" + M17Native.version());
    }

    private void showSoundName() {
        TextView t = findViewById(R.id.msgSoundName);
        if (t != null) t.setText(MsgNotify.soundName(this, cfg));
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 7 || res != RESULT_OK || data == null) return;
        android.net.Uri u = data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        cfg.edit().putString("msgSound", u == null ? "" : u.toString()).apply();      // null from the picker = Silent
        showSoundName();
    }

    private void showPttInfo() {
        ((TextView) findViewById(R.id.pttInfo)).setText(st.pttKey.learned() ? "PTT key: " + st.pttKey.code() + " on \"" + st.pttKey.device() + "\"" : "No PTT key learned yet: press Learn, then the side key.");
    }
}
