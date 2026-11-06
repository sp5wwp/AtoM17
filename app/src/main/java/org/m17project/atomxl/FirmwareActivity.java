/*
 * AtoM17 - module firmware page: load V076J (M17 transmit) or restore the vendor V076. Images are built on the phone
 * from its own vendor files; nothing vendor-made is in the APK.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FirmwareActivity extends Activity {
    private Station st;
    private TextView info, phase, log;
    private ProgressBar bar;
    private Button load, restore, trimBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        st = Station.get(this);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setPadding(pad / 2, 0, pad, pad);
        root.addView(Ui.header(this, "Module firmware"));
        info = new TextView(this); info.setTextColor(Ui.TEXT3); info.setTextSize(13);
        root.addView(info);

        load = new Button(this);
        restore = new Button(this); restore.setText("Restore vendor V076");
        trimBtn = new Button(this); trimBtn.setText("Trim M17 deviation…");
        for (Button x : new Button[] { load, trimBtn, restore }) {
            x.setBackgroundResource(R.drawable.btn); x.setTextColor(Ui.WHITE); x.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
            lp.topMargin = Ui.dp(this, 8);
            root.addView(x, lp);
        }
        labelLoad();
        trimBtn.setOnClickListener(v -> trimDialog());

        phase = new TextView(this); phase.setTextColor(Ui.ORANGE);
        root.addView(phase);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar);

        log = new TextView(this); log.setTextColor(Ui.WHITE); log.setTextSize(11); log.setTypeface(android.graphics.Typeface.MONOSPACE); log.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this); sv.addView(log);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        info.setText("Writes the module's flash through the bridge's loader route (P1 bridge, then the stock bridge back). "
                + "About 5 minutes. Keep the phone on the charger, screen on, and do not touch the radio.\n\n"
                + "The loader cannot read the installed image back: whatever is installed now cannot be saved first. "
                + "Restore puts back the vendor V076 from this phone's own /vendor partition.\n\nChecking the phone's files…");
        new Thread(this::check, "fw-check").start();

        load.setOnClickListener(v -> confirm(false));
        restore.setOnClickListener(v -> confirm(true));
    }

    private void check() {
        Flasher f = new Flasher(this, null, null);
        boolean root = Root.available();
        byte[] v076 = root ? f.vendorV076() : null;
        f.gain = st.cfg.devGain();
        byte[] j = root ? f.m17Image() : null;
        boolean bridge = root && Flasher.STOCK_BRIDGE_SHA.equals(Flasher.sha256(f.stockBridge() == null ? new byte[0] : f.stockBridge())) && f.p1Bridge() != null;
        boolean dfu = root && Root.run("[ -x " + Flasher.DFU_UTIL + " ] && echo ok").endsWith("ok");
        String s = "\n\nroot: " + (root ? "yes" : "NO")
                + "\nvendor V076 (" + Flasher.VENDOR_V076_FILE + "): " + (v076 != null ? "found, sha256 ok" : "MISSING or different")
                + "\n" + (f.gain == 1f ? "V076J" : "V076X (deviation ×" + fmt(f.gain) + ")") + " built from it: " + (j != null ? "yes, sha256 " + Flasher.sha256(j).substring(0, 16) + "… ok" : "no")
                + "\nstock bridge file and P1 loader bridge: " + (bridge ? "ok" : "NOT OK")
                + "\nflasher " + Flasher.DFU_UTIL + ": " + (dfu ? "ok" : "MISSING")
                + "\nmodule now: " + (st.moduleTag().isEmpty() ? "unknown (start the radio first)" : st.moduleTag());
        boolean ok = root && v076 != null && j != null && bridge && dfu;
        ui.post(() -> {
            info.append(s);
            load.setEnabled(ok && !st.moduleTag().isEmpty());
            restore.setEnabled(root && v076 != null && bridge && dfu);
            if (st.moduleTag().isEmpty()) info.append("\n\nStart the radio on the main screen first, so the installed version is known.");
        });
    }

    private void confirm(boolean isRestore) {
        if (running) return;
        String installed = st.moduleTag();
        new AlertDialog.Builder(this)
                .setTitle(isRestore ? "Restore V076?" : st.cfg.devGain() == 1f ? "Load V076J?" : "Load V076X (deviation ×" + fmt(st.cfg.devGain()) + ")?")
                .setMessage("The module's firmware " + (installed.isEmpty() ? "" : "(" + installed + ") ") + "will be erased and replaced. "
                        + "If the load is interrupted the module stays in its loader until Restore is run.\n\nNo warranty: this writes the radio's firmware.")
                .setPositiveButton("Write", (d, w) -> go(isRestore))
                .setNegativeButton("Cancel", null).show();
    }

    private static String fmt(double g) { return String.format(java.util.Locale.US, "%.3f", g); }

    private void labelLoad() {
        float g = st.cfg.devGain();
        load.setText(g == 1f ? "Load V076J (M17 transmit)" : "Load V076X (M17 transmit, deviation ×" + fmt(g) + ")");
    }

    /** Gain from a measurement: the installed image's trim times target / measured. */
    private void trimDialog() {
        if (running) return;
        String t = st.moduleTag();
        float installed = t.endsWith("V076X") ? st.cfg.devGainLoaded() : 1f;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density); box.setPadding(pad, pad / 2, pad, 0);
        android.widget.EditText meas = new android.widget.EditText(this), want = new android.widget.EditText(this);
        int num = android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;
        meas.setInputType(num); want.setInputType(num);
        meas.setHint("measured peak deviation, kHz (e.g. 1.79)"); want.setText("2.4");
        TextView note = new TextView(this);
        note.setText("Installed: " + (t.isEmpty() ? "unknown" : t) + (installed != 1f ? " (×" + fmt(installed) + ")" : "")
                + "\nMeasure an M17 transmission with the installed image, enter the peak deviation and the target. "
                + "The multiplier and the clamp of V076J's shaper are scaled together; the result is loaded as V076X with the Load button.");
        box.addView(note); box.addView(meas); box.addView(want);
        new AlertDialog.Builder(this).setTitle("M17 deviation trim").setView(box)
                .setPositiveButton("Set", (d, w) -> {
                    try {
                        double m = Double.parseDouble(meas.getText().toString().trim().replace(',', '.'));
                        double g = Double.parseDouble(want.getText().toString().trim().replace(',', '.'));
                        double gain = installed * g / m;
                        if (gain < Flasher.GAIN_MIN || gain > Flasher.GAIN_MAX) { info.append("\n\nTrim ×" + fmt(gain) + " is outside " + Flasher.GAIN_MIN + "…" + Flasher.GAIN_MAX + "; not set."); return; }
                        if (Math.abs(gain - 1) < 0.005) gain = 1;
                        st.cfg.edit().putFloat("devGain", (float) gain).apply();
                        labelLoad();
                        info.append("\n\nNext load: " + (gain == 1 ? "V076J" : "V076X, deviation ×" + fmt(gain)) + ".");
                    } catch (NumberFormatException e) { info.append("\n\nTrim: enter both values in kHz."); }
                })
                .setNeutralButton("Reset to V076J", (d, w) -> { st.cfg.edit().putFloat("devGain", 1f).apply(); labelLoad(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void go(boolean isRestore) {
        running = true;
        load.setEnabled(false); restore.setEnabled(false);
        log.setText("");
        st.flash(isRestore, new Flasher.Ui() {
            @Override public void phase(String p) {
                ui.post(() -> {
                    phase.setText(p);
                    if ("done".equals(p)) { running = false; load.setEnabled(true); restore.setEnabled(true); }
                });
            }
            @Override public void log(String s) { ui.post(() -> log.append(s + "\n")); }
            @Override public void progress(int d, int t) { ui.post(() -> { bar.setProgress(t == 0 ? 0 : d * 1000 / t); phase.setText("transfer " + d + " / " + t); }); }
            @Override public boolean confirmReplace(String tag) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicBoolean yes = new AtomicBoolean();
                ui.post(() -> new AlertDialog.Builder(FirmwareActivity.this).setCancelable(false)
                        .setTitle("Replace " + tag + " for good?")
                        .setMessage(tag + " is a newer vendor build than V076. Nobody has a copy of it, and the loader cannot read it back: "
                                + "once replaced, it cannot be put back (Restore gives V076). Replace it?")
                        .setPositiveButton("Replace for good", (d, w) -> { yes.set(true); done.countDown(); })
                        .setNegativeButton("Keep " + tag, (d, w) -> done.countDown()).show());
                try { done.await(); } catch (InterruptedException e) { return false; }
                return yes.get();
            }
        });
    }

    @Override public void onBackPressed() {
        if (running) { new AlertDialog.Builder(this).setMessage("A firmware load is running; leaving this page does not stop it.").setPositiveButton("OK", null).show(); return; }
        super.onBackPressed();
    }
}
