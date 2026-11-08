/*
 * AtoM17 - main screen: mode switch above the frequency, a status line, and three tabs: Live (the stream's LSF/LSD,
 * the FM channel, or what is being transmitted), Heard and Inbox. Options are in SettingsActivity.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements Station.Listener {
    private static final int[] TABS = { R.id.tabLive, R.id.tabHeard, R.id.tabInbox };
    private static final int[] PANELS = { R.id.panelLive, R.id.panelHeard, R.id.panelInbox };

    private Station st;
    private Config cfg;
    private FreqView freq;
    private TextView powerBtn, shiftChip, shiftTx;
    private LinearLayout shiftLine;
    private TextView status, sigText, txBar, modeFm, modeM17, modeVfo, modeMem, badge, memCh, memName, memFreq, memTag;
    private LinearLayout memCard;
    private Memories mem;
    private View sigBar;
    private Button power;
    private LinearLayout heardList, inboxList;
    private M17Tab m17tab;
    private int tab;
    private JSONObject lastStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { @Override public void run() { if (st.isTransmitting()) updateLive(); ui.postDelayed(this, 200); } };

    // the Live card
    private LinearLayout liveCard, metaButtons, meterRow, cells;
    private TextView topLabel, timer, tagView, call, sub, metaLabel, metaValue, metaValue2, foot;
    private TextView[] cellLabel = new TextView[4], cellValue = new TextView[4];
    private ImageView lockIcon, sealIcon;
    private View meterFill, meterRest;
    private double posLat, posLon;
    private String posName = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = Station.get(this);
        cfg = st.cfg;
        mem = new Memories(cfg);
        setContentView(R.layout.main);

        status = findViewById(R.id.status); sigText = findViewById(R.id.sigText); txBar = findViewById(R.id.txBar);
        power = findViewById(R.id.power); modeFm = findViewById(R.id.modeFm); modeM17 = findViewById(R.id.modeM17);
        modeVfo = findViewById(R.id.modeVfo); modeMem = findViewById(R.id.modeMem); badge = findViewById(R.id.inboxBadge);
        heardList = findViewById(R.id.heardList); inboxList = findViewById(R.id.inboxList);
        sigBar = findViewById(R.id.sigBar);
        android.graphics.drawable.GradientDrawable bd = new android.graphics.drawable.GradientDrawable();
        bd.setCornerRadius(Ui.dp(this, 1)); bd.setColor(Ui.IDLE); sigBar.setBackground(bd);

        freq = new FreqView(this);
        FrameLayout freqBox = findViewById(R.id.freqHolder);
        // VFO: the digits, and under them the repeater shift with the TX frequency (hidden for simplex)
        LinearLayout freqCol = Ui.vbox(this);
        freqCol.addView(freq);
        shiftLine = Ui.hbox(this);
        shiftLine.setGravity(android.view.Gravity.CENTER);
        shiftLine.setPadding(0, 0, 0, Ui.dp(this, 8));
        shiftChip = Ui.chip(this, ""); shiftChip.setTextSize(12); shiftChip.setSingleLine(true);
        shiftTx = Ui.text(this, "", 12.5f, Ui.TEXT3, false); shiftTx.setSingleLine(true);
        shiftTx.setPadding(Ui.dp(this, 7), 0, 0, 0);
        shiftLine.addView(shiftChip); shiftLine.addView(shiftTx);
        shiftLine.setVisibility(View.GONE);
        shiftLine.setContentDescription("Channel settings; tap for tuning, shift and power");
        shiftLine.setOnClickListener(v -> startActivity(new Intent(this, SettingsPageActivity.class).putExtra("page", "radio").putExtra("title", "Tuning, FM and power")));
        freqCol.addView(shiftLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        freqBox.addView(freqCol);
        // MEM mode: a compact card in place of the digits: ‹ [CH n name / frequency MHz tag] ›
        memCard = Ui.hbox(this);
        memCard.setVisibility(View.GONE);
        android.widget.ImageButton prev = new android.widget.ImageButton(this), next = new android.widget.ImageButton(this);
        prev.setImageResource(R.drawable.ic_prev); next.setImageResource(R.drawable.ic_next);
        prev.setBackgroundResource(R.drawable.row_bg); next.setBackgroundResource(R.drawable.row_bg);
        prev.setContentDescription("Previous memory"); next.setContentDescription("Next memory");
        prev.setOnClickListener(v -> memStep(-1)); next.setOnClickListener(v -> memStep(1));
        LinearLayout mid = Ui.vbox(this);
        mid.setBackgroundResource(R.drawable.row_bg);
        mid.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        mid.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        LinearLayout l1 = Ui.hbox(this);
        l1.setGravity(android.view.Gravity.BOTTOM);
        memCh = Ui.mono(this, "", 18, Ui.ORANGE); memCh.setTypeface(Ui.MONO_B);
        memName = Ui.text(this, "", 18, Ui.WHITE, true);
        memName.setSingleLine(true); memName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        memName.setPadding(Ui.dp(this, 8), 0, 0, 0);
        l1.addView(memCh); l1.addView(memName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout l2 = Ui.hbox(this);
        memFreq = Ui.mono(this, "", 16, Ui.WHITE); memFreq.setTypeface(Ui.MONO_B); memFreq.setSingleLine(true);
        TextView mhz = Ui.text(this, "MHz", 12, Ui.TEXT3, false);
        mhz.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        memTag = Ui.chip(this, ""); memTag.setSingleLine(true);
        l2.addView(memFreq); l2.addView(mhz);
        memTag.setTextSize(12);
        mid.addView(l1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mid.addView(l2, Ui.matchWrap(this, 4));
        mid.addView(memTag, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ topMargin = Ui.dp(MainActivity.this, 5); }});
        mid.setOnClickListener(v -> startActivity(new Intent(this, MemoriesActivity.class)));
        memCard.addView(prev, new LinearLayout.LayoutParams(Ui.dp(this, 36), ViewGroup.LayoutParams.MATCH_PARENT));
        memCard.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        memCard.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 36), ViewGroup.LayoutParams.MATCH_PARENT));
        freqBox.addView(memCard, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        freq.setHz(cfg.freq());
        freq.setOnChange(hz -> { cfg.edit().putInt("freq", hz).apply(); st.applyChannel(); });
        freq.setOnLongClickListener(v -> { typeFrequency(); return true; });

        // the frequency selector's top edge at 25 % of the screen height
        findViewById(R.id.header).post(() -> {
            View space = findViewById(R.id.topSpace), holder = findViewById(R.id.freqHolder);
            int[] at = new int[2]; holder.getLocationInWindow(at);
            int target = Math.round(getWindow().getDecorView().getHeight() * 0.21f);
            space.getLayoutParams().height = Math.max(0, target - at[1]);
            space.requestLayout();
        });

        power.setOnClickListener(v -> { if (st.isOpen() || st.isStarting()) RadioService.stop(this); else RadioService.start(this); });
        findViewById(R.id.gear).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        modeFm.setOnClickListener(v -> setMode(false));
        modeM17.setOnClickListener(v -> setMode(true));
        modeVfo.setOnClickListener(v -> setMem(false));
        modeMem.setOnClickListener(v -> setMem(true));
        powerBtn = findViewById(R.id.powerBtn);
        powerBtn.setOnClickListener(v -> { SettingsPageActivity.setHigh(cfg, !cfg.high()); st.applyChannel(); onState(); });
        modeMem.setOnLongClickListener(v -> { storeMemory(null); return true; });
        findViewById(R.id.heardClear).setOnClickListener(v -> {
            synchronized (st.heard) { if (st.heard.isEmpty()) return; }
            new AlertDialog.Builder(this).setMessage("Clear the list of stations heard?")
                    .setPositiveButton("Clear", (d, w) -> { synchronized (st.heard) { st.heard.clear(); } rebuildHeard(); updateLive(); })
                    .setNegativeButton("Cancel", null).show();
        });
        for (int i = 0; i < TABS.length; i++) { final int k = i; findViewById(TABS[i]).setOnClickListener(v -> showTab(k)); }
        findViewById(R.id.smsNew).setOnClickListener(v -> m17tab.smsDialog(null));
        findViewById(R.id.smsClear).setOnClickListener(v -> {
            if (st.inbox.all().isEmpty()) return;
            new AlertDialog.Builder(this).setMessage("Delete all " + st.inbox.all().size() + " messages?")
                    .setPositiveButton("Delete", (d, w) -> { st.inbox.clear(); rebuildInbox(); }).setNegativeButton("Cancel", null).show();
        });

        m17tab = new M17Tab(this, st);
        buildLive();
        showTab(getIntent().getIntExtra("tab", cfg.p.getInt("tab", 0)));
        MsgNotify.channel(this);

        txBar.setOnClickListener(v -> {
            if (!cfg.p.getBoolean("softPtt", false)) return;
            st.ptt(!st.isTransmitting());                         // a toggle: no touch tracking to lose
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION }, 1);
    }

    /** A message notification was tapped while the screen was already open. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.hasExtra("tab")) showTab(i.getIntExtra("tab", 0));
    }

    @Override protected void onResume() {
        super.onResume();
        st.addListener(this);
        freq.setHz(cfg.freq());
        onState(); rebuildHeard(); rebuildInbox(); st.updateGnss();
        ui.post(tick);
    }
    @Override protected void onPause() {
        st.removeListener(this); ui.removeCallbacks(tick);
        if (busy != null) { busy.dismiss(); busy = null; }
        super.onPause();
    }

    // ---- tabs, mode, step ----

    private void showTab(int k) {
        tab = Math.max(0, Math.min(TABS.length - 1, k));
        for (int i = 0; i < TABS.length; i++) {
            findViewById(PANELS[i]).setVisibility(i == tab ? View.VISIBLE : View.GONE);
            View t = findViewById(TABS[i]);
            t.setBackgroundResource(i == tab ? R.drawable.tab_on : 0);
            TextView label = i == 2 ? findViewById(R.id.tabInboxText) : (TextView) t;
            label.setTextColor(i == tab ? Ui.WHITE : Ui.TEXT3);
        }
        cfg.edit().putInt("tab", tab).apply();
        if (tab == 2) { rebuildInbox(); st.inbox.markRead(); showBadge(); MsgNotify.clear(this); }
    }

    private void setMode(boolean m17) { cfg.edit().putBoolean("m17", m17).apply(); st.applyChannel(); onState(); }

    // ---- VFO / MEM ----

    private void setMem(boolean on) {
        if (on && mem.all().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No memories yet")
                    .setMessage("Store this channel as memory 1? Long-press MEM stores the current channel at any time; Settings › Memories edits them.")
                    .setPositiveButton("Store", (d, w) -> storeMemory(() -> setMem(true))).setNegativeButton("Cancel", null).show();
            return;
        }
        if (on) mem.enter(st, mem.index());
        else if (mem.memMode()) mem.vfo(st);
        freq.setHz(cfg.freq());
        onState();
    }

    private void memStep(int d) { mem.apply(st, mem.index() + d); freq.setHz(cfg.freq()); onState(); }

    private void pickMemory() { startActivity(new Intent(this, MemoriesActivity.class)); }

    /** Store the current channel as a new memory, asking for its name. */
    private void storeMemory(Runnable then) {
        Memories.Ch c = Memories.Ch.current(cfg);
        long tx = (long) c.hz + (long) c.shift * c.offset;
        if (!Config.tunable(c.hz) || !Config.tunable(tx)) {
            new AlertDialog.Builder(this).setTitle("Can't store this channel")
                    .setMessage("With the " + Config.shiftText(c.shift, c.offset) + " MHz repeater shift it would transmit on " + Config.mhz((int) tx)
                            + " MHz, outside the radio's " + Config.rangeText() + " range. Change the shift or offset in Settings › Tuning, FM and power.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        EditText e = new EditText(this);
        e.setText(c.name); e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Store " + Config.mhz(c.hz) + " MHz").setMessage(c.summary()).setView(e)
                .setPositiveButton("Store", (d, w) -> {
                    c.name = e.getText().toString().trim().isEmpty() ? Config.mhz(c.hz) : e.getText().toString().trim();
                    List<Memories.Ch> l = mem.all(); l.add(c); mem.save(l);
                    cfg.edit().putInt("memIdx", l.size() - 1).apply();
                    Toast.makeText(this, "Stored as memory " + l.size(), Toast.LENGTH_SHORT).show();
                    onState();
                    if (then != null) then.run();
                }).setNegativeButton("Cancel", null).show();
    }

    private void typeFrequency() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(Config.mhz(freq.hz()));
        new AlertDialog.Builder(this).setTitle("Frequency, MHz (400–480)").setView(e)
                .setPositiveButton("Tune", (d, w) -> {
                    try {
                        int hz = (int) Math.round(Double.parseDouble(e.getText().toString().trim()) * 1e6);
                        freq.setHz(hz); cfg.edit().putInt("freq", freq.hz()).apply(); st.applyChannel();
                    } catch (NumberFormatException ignored) { }
                }).setNegativeButton("Cancel", null).show();
    }

    // ---- the Live card ----

    private void buildLive() {
        LinearLayout box = findViewById(R.id.liveBox);
        liveCard = Ui.card(this, 12);
        LinearLayout top = Ui.hbox(this);
        topLabel = Ui.label(this, "");
        top.addView(topLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timer = Ui.mono(this, "", 13, Ui.WHITE);
        top.addView(timer);
        lockIcon = Ui.icon(this, R.drawable.ic_lock, Ui.WHITE);
        sealIcon = Ui.icon(this, R.drawable.ic_seal, Ui.WHITE);
        ((LinearLayout.LayoutParams) lockIcon.getLayoutParams()).leftMargin = Ui.dp(this, 8);
        ((LinearLayout.LayoutParams) sealIcon.getLayoutParams()).leftMargin = Ui.dp(this, 6);
        top.addView(lockIcon); top.addView(sealIcon);
        liveCard.addView(top);

        LinearLayout callRow = Ui.hbox(this);
        call = Ui.text(this, "", 30, Ui.WHITE, true);
        call.setSingleLine(true);
        callRow.addView(call, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tagView = Ui.tag(this, "", Ui.OK);
        callRow.addView(tagView);
        liveCard.addView(callRow, Ui.matchWrap(this, 2));
        sub = Ui.text(this, "", 14, Ui.TEXT2, false);
        liveCard.addView(sub);

        View[] cv = new View[4];
        for (int i = 0; i < 4; i++) { cellValue[i] = Ui.value(this); cv[i] = Ui.cell(this, "", cellValue[i]); cellLabel[i] = (TextView) ((LinearLayout) cv[i]).getChildAt(0); }
        cells = Ui.grid(this, cv);
        liveCard.addView(cells, Ui.matchWrap(this, 8));

        LinearLayout meta = Ui.vbox(this);
        meta.setBackgroundResource(R.drawable.cell);
        meta.setPadding(Ui.dp(this, 9), Ui.dp(this, 6), Ui.dp(this, 9), Ui.dp(this, 8));
        metaLabel = Ui.label(this, "");
        metaValue = Ui.text(this, "", 14, Ui.WHITE, true);
        metaValue2 = Ui.text(this, "", 12.5f, Ui.TEXT2, false);
        meta.addView(metaLabel); meta.addView(metaValue); meta.addView(metaValue2);
        metaButtons = Ui.hbox(this);
        Button map = smallButton("Open in map"), copy = smallButton("Copy position");
        map.setOnClickListener(v -> {
            Uri u = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", posLat, posLon, posLat, posLon, Uri.encode(posName)));
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { Toast.makeText(this, "No map app", Toast.LENGTH_SHORT).show(); }
        });
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("position", String.format(Locale.US, "%.6f, %.6f", posLat, posLon)));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        metaButtons.addView(map, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1); cp.leftMargin = Ui.dp(this, 8);
        metaButtons.addView(copy, cp);
        meta.addView(metaButtons, Ui.matchWrap(this, 6));
        liveCard.addView(meta, Ui.matchWrap(this, 6));

        meterRow = Ui.vbox(this);
        LinearLayout ml = Ui.hbox(this);
        TextView mlab = Ui.label(this, "Microphone");
        ml.addView(mlab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        meterRow.addView(ml);
        LinearLayout track = Ui.hbox(this);
        android.graphics.drawable.GradientDrawable td = new android.graphics.drawable.GradientDrawable(); td.setCornerRadius(Ui.dp(this, 2)); td.setColor(0x40000000);
        track.setBackground(td);
        meterFill = new View(this);
        android.graphics.drawable.GradientDrawable fd = new android.graphics.drawable.GradientDrawable(); fd.setCornerRadius(Ui.dp(this, 2)); fd.setColor(Ui.OK);
        meterFill.setBackground(fd);
        meterRest = new View(this);
        track.addView(meterFill, new LinearLayout.LayoutParams(0, Ui.dp(this, 6), 0));
        track.addView(meterRest, new LinearLayout.LayoutParams(0, Ui.dp(this, 6), 1));
        meterRow.addView(track, Ui.matchWrap(this, 4));
        liveCard.addView(meterRow, Ui.matchWrap(this, 10));

        foot = Ui.mono(this, "", 11.5f, Ui.LABEL);
        liveCard.addView(foot, Ui.matchWrap(this, 8));
        box.addView(liveCard);
    }

    private Button smallButton(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextColor(Ui.WHITE); b.setTextSize(13); b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setBackgroundResource(R.drawable.btn); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void cell(int i, String label, String value, int color) {
        cellLabel[i].setText(label); cellValue[i].setText(value); cellValue[i].setTextColor(color);
        ((View) cellLabel[i].getParent()).setVisibility(label == null ? View.INVISIBLE : View.VISIBLE);
    }

    private static String clock(double s) { int t = (int) Math.max(0, s); return String.format(Locale.US, "%d:%04.1f", t / 60, s - 60 * (t / 60)); }

    private static final String[] STATIONS = { "fixed", "mobile", "handheld" };

    private void updateLive() {
        boolean m17 = cfg.m17();
        lockIcon.setVisibility(View.GONE); sealIcon.setVisibility(View.GONE); tagView.setVisibility(View.GONE);
        metaButtons.setVisibility(View.GONE); meterRow.setVisibility(View.GONE); foot.setVisibility(View.GONE);
        ((View) metaLabel.getParent()).setVisibility(View.VISIBLE); cells.setVisibility(View.VISIBLE);
        timer.setText("");
        liveCard.setBackgroundResource(R.drawable.card);

        if (st.isTransmitting()) { showTx(m17); return; }
        if (!m17) { showFm(); return; }
        int state = 0;
        Station.RxEntry live = null;
        try {
            if (lastStatus != null) state = lastStatus.getInt("state");
            if (state != 0) { live = new Station.RxEntry(); Station.fillLink(live, lastStatus.getJSONObject("link")); }
        } catch (Exception ignored) { }
        if (live != null) { showStream(live, true, state); return; }
        Station.RxEntry last = null;
        synchronized (st.heard) { for (Station.RxEntry e : st.heard) if (!"bad".equals(e.kind)) { last = e; break; } }
        if (last != null) { showStream(last, false, "stream".equals(last.kind) ? 1 : 2); return; }
        topLabel.setText(st.isOpen() ? "M17 · listening" : "M17 · radio off");
        call.setText(st.isOpen() ? "Nothing heard yet" : "Radio off");
        call.setTextSize(24);
        sub.setText(st.isOpen() ? "Streams and SMS show here as they arrive." : "Press Start to switch the radio on.");
        cells.setVisibility(View.GONE); ((View) metaLabel.getParent()).setVisibility(View.GONE);
    }

    private void showStream(Station.RxEntry r, boolean live, int state) {
        boolean packet = state == 2 || !"stream".equals(r.kind);
        call.setTextSize(30);
        call.setText(r.src.isEmpty() ? "?" : r.src);
        sub.setText("to " + (r.dst.isEmpty() ? "?" : r.dst) + " · CAN " + r.can + (r.fromLich ? " · joined late" : ""));
        double dur = live ? lastStatus.optDouble("duration", 0) : r.duration;
        String kind = packet ? ("sms".equals(r.kind) ? "M17 SMS" : "M17 packet") : r.dataType == 3 ? "M17 voice + data" : r.dataType == 1 ? "M17 data" : "M17 voice";
        topLabel.setText(live ? kind : "Last heard · " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(r.at)));
        timer.setText(packet && !live ? "" : clock(dur));
        boolean muted = r.enc != 0 && r.decrypt != 1;
        if (live) { tagView.setVisibility(View.VISIBLE); Ui.tag(tagView, muted ? "MUTED" : packet ? "PACKET" : "VOICE", muted ? Ui.WARN : Ui.OK); }

        String type = packet ? ("sms".equals(r.kind) ? "SMS" : "Packet") : r.dataType == 2 ? "Voice 3200" : r.dataType == 3 ? "Voice + data 1600" : r.dataType == 1 ? "Data" : "—";
        cell(0, "Type", type, Ui.WHITE);
        String enc; int encColor;
        if (r.enc == 0) { enc = "None"; encColor = Ui.WHITE; }
        else {
            String what = r.enc == 2 ? "AES-" + (128 + 64 * r.encSub) : "Scrambler " + (8 * (r.encSub + 1));
            enc = what + " · " + (r.decrypt == 1 ? (r.enc == 2 ? "decrypted" : "descrambled") : r.decrypt == 3 ? "unsupported" : "no key");
            encColor = r.decrypt == 1 ? Ui.OK : r.decrypt == 3 ? Ui.BAD : Ui.WARN;
            lockIcon.setVisibility(View.VISIBLE); lockIcon.setColorFilter(encColor);
        }
        cell(1, "Encryption", enc, encColor);
        String sig; int sigColor = Ui.WHITE;
        if (!r.signed) { sig = "Not signed"; sigColor = Ui.TEXT3; }
        else {
            sealIcon.setVisibility(View.VISIBLE);
            if (live || r.sig < 0) sig = "Checked at end";
            else switch (r.sig) {
                case 1: sig = "Valid" + (r.sigKey.isEmpty() ? "" : " · " + r.sigKey); sigColor = Ui.OK; break;
                case 2: sig = "INVALID"; sigColor = Ui.BAD; break;
                case 3: sig = "No public key"; sigColor = Ui.WARN; break;
                case 4: sig = "Not checkable (frames lost)"; sigColor = Ui.WARN; break;
                default: sig = "Signature missing"; sigColor = Ui.WARN;
            }
            sealIcon.setColorFilter(sigColor == Ui.TEXT3 ? Ui.WHITE : sigColor == Ui.WHITE ? Ui.WHITE : sigColor);
        }
        cell(2, "Signature", sig, sigColor);
        if (live && !packet) cell(3, "Frames", "FN " + lastStatus.optInt("fn") + " · LICH " + lastStatus.optInt("lich") + "/6", Ui.WHITE);
        else if (!packet) cell(3, "Frames", r.frames + (r.lost > 0 ? " · " + r.lost + " lost" : " · none lost"), r.lost > 0 ? Ui.WARN : Ui.WHITE);
        else cell(3, "Length", r.sms.isEmpty() ? "—" : r.sms.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " bytes", Ui.WHITE);

        metaValue.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        metaValue2.setVisibility(View.GONE);
        if (packet && "sms".equals(r.kind)) {
            metaLabel.setText(r.posValid ? "Message · with position" : "Message"); metaValue.setText(r.sms);
            if (r.posValid) {                                          // position in the packet's LSF (out of spec, common)
                metaValue2.setText(String.format(Locale.US, "%.5f° %s  %.5f° %s", Math.abs(r.lat), r.lat >= 0 ? "N" : "S", Math.abs(r.lon), r.lon >= 0 ? "E" : "W")
                        + (r.altValid ? String.format(Locale.US, " · %.0f m", r.alt) : ""));
                metaValue2.setVisibility(View.VISIBLE);
                posLat = r.lat; posLon = r.lon; posName = r.src;
                metaButtons.setVisibility(View.VISIBLE);
            }
        }
        else if (packet) { metaLabel.setText("Payload"); metaValue.setText(r.sms.isEmpty() ? "receiving…" : r.sms); }
        else if (r.enc == 2) { metaLabel.setText("META · nonce"); metaValue.setText("Carries the AES nonce, no text"); metaValue.setTypeface(android.graphics.Typeface.DEFAULT); }
        else if (r.metaKind == 1) { metaLabel.setText("META · text"); metaValue.setText(r.text.isEmpty() ? "receiving…" : r.text); }
        else if (r.metaKind == 2 && r.posValid) {
            metaLabel.setText("META · position" + (r.station >= 0 && r.station < 3 ? " (" + STATIONS[r.station] + ")" : ""));
            metaValue.setTypeface(Ui.MONO_B);
            metaValue.setText(String.format(Locale.US, "%.5f° %s  %.5f° %s", Math.abs(r.lat), r.lat >= 0 ? "N" : "S", Math.abs(r.lon), r.lon >= 0 ? "E" : "W"));
            StringBuilder b = new StringBuilder();
            if (r.altValid) b.append(String.format(Locale.US, "%.0f m", r.alt));
            if (r.spdValid) b.append(b.length() > 0 ? " · " : "").append(String.format(Locale.US, "%.0f km/h · heading %d°", r.speed, r.bearing));
            if (b.length() > 0) { metaValue2.setText(b); metaValue2.setVisibility(View.VISIBLE); }
            posLat = r.lat; posLon = r.lon; posName = r.src;
            metaButtons.setVisibility(View.VISIBLE);
        }
        else if (r.metaKind == 2) { metaLabel.setText("META · position"); metaValue.setText("no valid fix in the stream"); }
        else if (r.metaKind == 3) {
            metaLabel.setText("META · extended callsign");
            metaValue.setText("Originator " + (r.ecd1.isEmpty() ? "?" : r.ecd1) + (r.ecd2.isEmpty() ? "" : "  ·  via " + r.ecd2));
        }
        else if (r.metaKind == 4) { metaLabel.setText("META · raw"); metaValue.setTypeface(Ui.MONO); metaValue.setText(r.meta); }
        else { metaLabel.setText("META"); metaValue.setText("none"); metaValue.setTypeface(android.graphics.Typeface.DEFAULT); }
    }

    private void showFm() {
        boolean open = st.isReceiving();
        topLabel.setText("Analog FM · " + (cfg.wide() ? "25" : "12.5") + " kHz");
        call.setTextSize(28);
        call.setText(!st.isOpen() ? "Radio off" : open ? "Squelch open" : "Squelch closed");
        sub.setText(cfg.rxTone() > 0 ? "RX tone " + SettingsActivity.tone(cfg.rxTone()) + " Hz" : "no RX tone");
        cell(0, "RX CTCSS", SettingsActivity.tone(cfg.rxTone()), Ui.WHITE);
        cell(1, "TX CTCSS", SettingsActivity.tone(cfg.txTone()), Ui.WHITE);
        cell(2, "Squelch", cfg.squelch() == 0 ? "Open" : "Level " + cfg.squelch(), Ui.WHITE);
        cell(3, "Power", cfg.high() ? "High" : "Low", Ui.WHITE);
        ((View) metaLabel.getParent()).setVisibility(View.GONE);
    }

    private void showTx(boolean m17) {
        liveCard.setBackground(txCard());
        topLabel.setText("Transmitting · " + st.txKind);
        timer.setText(clock((System.currentTimeMillis() - st.txSince) / 1000.0));
        call.setTextSize(28);
        if (!m17) {
            call.setText("FM");
            sub.setText((cfg.txTone() > 0 ? "TX tone " + SettingsActivity.tone(cfg.txTone()) + " Hz · " : "") + (cfg.high() ? "high" : "low") + " power");
            cells.setVisibility(View.GONE); ((View) metaLabel.getParent()).setVisibility(View.GONE);
        } else {
            call.setText(cfg.src() + "  → " + (cfg.dst().isEmpty() ? "@ALL" : cfg.dst()));
            String enc = cfg.enc() == M17Native.ENC_AES ? "AES-" + cfg.aesBits() : cfg.enc() == M17Native.ENC_SCRAM ? "scrambler " + cfg.scramBits() + "-bit" : "no encryption";
            sub.setText("CAN " + cfg.can() + " · " + enc + " · " + (cfg.sign() ? "signed" : "not signed"));
            cells.setVisibility(View.GONE);
            if (cfg.enc() != M17Native.ENC_NONE) { metaLabel.setText("META"); metaValue.setText("carries the " + (cfg.enc() == M17Native.ENC_AES ? "nonce" : "scrambler state")); }
            else if (cfg.metaKind() == M17Native.META_TEXT) { metaLabel.setText("META · text"); metaValue.setText(cfg.metaText().isEmpty() ? "none" : cfg.metaText()); }
            else if (cfg.metaKind() == M17Native.META_GNSS) { metaLabel.setText("META · position"); metaValue.setText(st.gnssText()); }
            else { metaLabel.setText("META · extended callsign"); metaValue.setText(cfg.ecd1() + (cfg.ecd2().isEmpty() ? "" : " · via " + cfg.ecd2())); }
            metaValue2.setVisibility(View.GONE);
            String tag = st.moduleTag();
            foot.setText("lead-in " + (cfg.leadInMs() == 0 ? "none" : cfg.leadInMs() + " ms")
                    + (tag.endsWith("V076X") ? String.format(Locale.US, " · V076X deviation ×%.3f", cfg.devGainLoaded()) : tag.isEmpty() ? "" : " · " + tag.substring(tag.lastIndexOf('.') + 1)));
            foot.setVisibility(View.VISIBLE);
        }
        if (!"M17 SMS".equals(st.txKind) && !"M17 test tone".equals(st.txKind)) {
            meterRow.setVisibility(View.VISIBLE);
            float frac = Math.max(0, Math.min(1, (st.txLevelDb + 60) / 60f));
            ((LinearLayout.LayoutParams) meterFill.getLayoutParams()).weight = frac;
            ((LinearLayout.LayoutParams) meterRest.getLayoutParams()).weight = 1 - frac;
            meterFill.requestLayout();
        }
    }

    private android.graphics.drawable.Drawable txCard() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(Ui.dp(this, 16)); d.setColor(0x1CFFFFFF); d.setStroke(Ui.dp(this, 1), 0x8CFF785A);
        return d;
    }

    // ---- status line, bottom bar ----

    private void updateSignal() {
        int color; String s;
        if (!st.isOpen()) { color = Ui.IDLE; s = st.isStarting() ? "Starting the radio…" : "Radio off"; }
        else if (st.isTransmitting()) { color = 0xFFFF8A6B; s = "Transmitting · " + Config.mhz(cfg.txFreq()) + " MHz" + (cfg.shift() != 0 ? " (" + Config.shiftText(cfg.shift(), cfg.offset()) + ")" : "") + " · " + (cfg.high() ? "high" : "low") + " power"; }
        else if (!cfg.m17()) { boolean o = st.isReceiving(); color = o ? Ui.OK : Ui.IDLE; s = o ? "Carrier · squelch open" : "Squelch closed"; }
        else {
            int state = lastStatus == null ? 0 : lastStatus.optInt("state");
            if (state == 0) { color = Ui.IDLE; s = "Listening · no carrier"; }
            else {
                JSONObject l = lastStatus.optJSONObject("link");
                boolean lsf = l != null && l.optInt("valid") != 0;
                StringBuilder b = new StringBuilder("Sync · ").append(!lsf ? "no LSF yet" : l.optInt("fromLich") != 0 ? "LSF from LICH" : "LSF OK");
                // measured on the equaliser (RxStats): libm17rx sees a re-synthesised signal
                RxStats.Snap q = st.rxStats.snap();
                boolean fresh = q.lastAt > 0 && System.currentTimeMillis() - q.lastAt < 2000;
                if (fresh && !Float.isNaN(q.ser)) b.append(String.format(Locale.US, " · SER %.1f %%", 100 * q.ser));
                if (fresh && !Float.isNaN(q.evm)) b.append(String.format(Locale.US, " · EQ EVM %.1f %%", q.evm));
                b.append(" · lost ").append(lastStatus.optInt("lost"));
                color = fresh && !Float.isNaN(q.ser) ? (q.ser < 0.02f ? Ui.OK : q.ser < 0.06f ? Ui.WARN : Ui.BAD) : Ui.OK;
                s = b.toString();
            }
        }
        Ui.barColor(sigBar, color);
        sigText.setText(s);
    }

    // ---- Station.Listener ----

    private boolean appBlockAsked;
    private AlertDialog busy;
    private TextView busyText;

    /** "Starting / stopping the RF module" while the radio thread works on it. */
    private void busyPopup() {
        String what = st.isStopping() ? "Stopping the RF module…" : st.isStarting() ? "Starting the RF module…" : null;
        if (what == null) { if (busy != null) { busy.dismiss(); busy = null; } return; }
        if (busy == null) {
            LinearLayout l = Ui.hbox(this);
            int p = Ui.dp(this, 22); l.setPadding(p, p, p, p);
            android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
            pb.setIndeterminate(true);
            l.addView(pb, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
            busyText = Ui.text(this, what, 16, Ui.WHITE, false);
            busyText.setPadding(Ui.dp(this, 16), 0, 0, 0);
            l.addView(busyText);
            busy = new AlertDialog.Builder(this).setView(l).setCancelable(false).create();
            busy.show();
        }
        busyText.setText(what);
    }

    @Override public void onState() {
        if (st.appBlock != null && !appBlockAsked) {
            appBlockAsked = true;
            new AlertDialog.Builder(this).setTitle("App block")
                    .setMessage("The phone's App block will stop AtoM17 a few minutes after the screen goes off (" + st.appBlock + "). Turn those off for AtoM17.")
                    .setPositiveButton("Open App block", (d, w) -> new Thread(() -> Bridge.openAppBlock(getPackageName())).start())
                    .setNegativeButton("Later", null).show();
        }
        busyPopup();
        boolean m17 = cfg.m17();
        modeFm.setBackgroundResource(m17 ? 0 : R.drawable.seg_on); modeFm.setTextColor(m17 ? Ui.TEXT3 : 0xFF1B1206);
        modeM17.setBackgroundResource(m17 ? R.drawable.seg_on : 0); modeM17.setTextColor(m17 ? 0xFF1B1206 : Ui.TEXT3);
        boolean memOn = mem.memMode() && !mem.all().isEmpty();
        modeVfo.setBackgroundResource(memOn ? 0 : R.drawable.seg_on); modeVfo.setTextColor(memOn ? Ui.TEXT3 : 0xFF1B1206);
        modeMem.setBackgroundResource(memOn ? R.drawable.seg_on : 0); modeMem.setTextColor(memOn ? 0xFF1B1206 : Ui.TEXT3);
        // MEM: the compact card takes the digits' place (they stay laid out, invisible, so the card keeps their height)
        memCard.setVisibility(memOn ? View.VISIBLE : View.GONE);
        // the memory decides FM or M17, so the switch is hidden (kept in the layout, VFO/MEM stays put)
        ((View) modeFm.getParent()).setVisibility(memOn ? View.INVISIBLE : View.VISIBLE);
        powerBtn.setVisibility(memOn ? View.INVISIBLE : View.VISIBLE);
        boolean vfoOn = !memOn;
        if (vfoOn) {
            shiftChip.setText(Memories.Ch.current(cfg).tag(true));
            if (cfg.shift() != 0) {
                int tx = cfg.txFreq(), r = cfg.region();
                boolean ok = Config.tunable(tx) && Config.inBand(tx, r);
                android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("TX ");
                int a = b.length();
                b.append(Config.dotted(tx));
                b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, b.length(), 0);
                b.setSpan(new android.text.style.TypefaceSpan("monospace"), a, b.length(), 0);
                b.setSpan(new android.text.style.ForegroundColorSpan(ok ? Ui.WHITE : Ui.BAD), a, b.length(), 0);
                if (!ok) b.append(Config.tunable(tx) ? "  out of band" : "  out of range");
                shiftTx.setText(b);
                shiftTx.setTextColor(ok ? Ui.TEXT3 : Ui.BAD);
                shiftTx.setVisibility(View.VISIBLE);
            } else shiftTx.setVisibility(View.GONE);
        }
        boolean shifted = vfoOn;
        if ((shiftLine.getVisibility() == View.VISIBLE) != shifted) shiftLine.setVisibility(shifted ? View.VISIBLE : View.GONE);
        powerBtn.setText(cfg.high() ? "HI" : "LO");
        powerBtn.setContentDescription(cfg.high() ? "RF power: high, tap for low" : "RF power: low, tap for high");
        // MEM: the card is a little taller than the digits, for its three lines
        View fb = findViewById(R.id.freqHolder);
        int minH = memOn ? freq.getHeight() + Ui.dp(this, 24) : 0;
        if (fb.getMinimumHeight() != minH) fb.setMinimumHeight(minH);
        if (memOn && freq.getHeight() == 0) fb.post(this::onState);   // not laid out yet: size it once it is
        freq.setVisibility(memOn ? View.INVISIBLE : View.VISIBLE);
        if (memOn) {
            List<Memories.Ch> l = mem.all();
            int i = Math.min(mem.index(), l.size() - 1);
            Memories.Ch c = l.get(i);
            memCh.setText("CH" + (i + 1));
            memName.setText(c.name);
            memFreq.setText(c.dotted());
            memTag.setText(c.tag());
            if (freq.hz() != cfg.freq()) freq.setHz(cfg.freq());
        }
        power.setText(st.isOpen() || st.isStarting() ? "Stop" : "Start");
        String s;
        if (st.isFlashing()) s = "Loading firmware…";
        else if (st.isStarting()) s = "Starting the radio…";
        else if (st.isOpen()) s = (m17 ? "M17" : "FM") + " · " + st.moduleTag().replace("DMR006.U4A.", "") + "\nbridge " + st.bridgeDate()
                + (m17 && !st.m17TxCapable() ? " · receive only" : "");
        else s = st.error().isEmpty() ? "Radio off" : "Radio off: " + st.error();
        status.setText(s);

        boolean soft = cfg.p.getBoolean("softPtt", false);
        android.graphics.drawable.GradientDrawable bar = new android.graphics.drawable.GradientDrawable();
        bar.setCornerRadius(Ui.dp(this, 14));
        if (st.isTransmitting()) {
            bar.setColor(Ui.TX); bar.setStroke(Ui.dp(this, 1), 0xFFE2654C);
            txBar.setText(soft ? "TX · tap to stop" : "TX · release PTT to stop"); txBar.setTextColor(Ui.WHITE);
            txBar.setVisibility(View.VISIBLE);
        } else {
            bar.setColor(0x1CFFFFFF); bar.setStroke(Ui.dp(this, 1), 0x29FFFFFF);
            txBar.setTextColor(Ui.TEXT2);
            if (!st.isOpen()) { txBar.setText("Radio off"); txBar.setVisibility(soft ? View.VISIBLE : View.GONE); }
            else if (soft) { txBar.setText("Tap here to transmit"); txBar.setVisibility(View.VISIBLE); }
            else if (!st.pttKey.learned()) { txBar.setText("Learn the PTT key: Settings › PTT key"); txBar.setVisibility(View.VISIBLE); }
            else txBar.setVisibility(View.GONE);
        }
        txBar.setBackground(bar);
        screenOn();
        updateSignal();
        updateLive();
        if (tab == 2 && !st.isTransmitting()) rebuildInbox();      // a sent SMS
    }

    @Override public void onStatus(JSONObject s) { lastStatus = s; updateSignal(); updateLive(); screenOn(); }

    private long activeUntil;
    private boolean keptOn;

    /** Keep the screen on as Settings › Screen and power says: always, never, or while something is on the air (+10 s). */
    private void screenOn() {
        int how = cfg.p.getInt("screenOn", 1);                       // 0 never, 1 while active, 2 always
        boolean active = st.isTransmitting() || st.isStarting() || st.isFlashing()
                || (cfg.m17() ? lastStatus != null && lastStatus.optInt("state") != 0 : st.isReceiving());
        long now = System.currentTimeMillis();
        if (active) activeUntil = now + 10_000;
        boolean want = how == 2 || (how == 1 && now < activeUntil);
        if (want != keptOn) {
            keptOn = want;
            if (want) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override public void onRx(Station.RxEntry e) {
        rebuildHeard();
        if (tab == 2 && hasWindowFocus()) { rebuildInbox(); st.inbox.markRead(); MsgNotify.clear(this); }
        showBadge();
        updateLive();
        if ("sms".equals(e.kind) && e.at > shownSmsAt && tab != 2 && hasWindowFocus()) {
            shownSmsAt = e.at;
            new AlertDialog.Builder(this).setTitle("SMS from " + (e.src.isEmpty() ? "?" : e.src))
                    .setMessage(e.sms + "\n\nto " + e.dst + " · " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(e.at)))
                    .setPositiveButton("Reply", (d, w) -> m17tab.smsDialog(e.src))
                    .setNeutralButton("Inbox", (d, w) -> showTab(2))
                    .setNegativeButton("Close", null).show();
        }
    }
    private long shownSmsAt;

    // ---- Heard ----

    private void rebuildHeard() {
        heardList.removeAllViews();
        int n = 0;
        synchronized (st.heard) {
            for (Station.RxEntry e : st.heard) {
                if (n > 0) { View sep = new View(this); sep.setBackgroundColor(0x1AFFFFFF); heardList.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
                heardList.addView(heardRow(e));
                if (++n >= 100) break;
            }
        }
        if (n == 0) {
            TextView t = Ui.text(this, "Nothing heard yet.", 14, Ui.TEXT3, false);
            int p = Ui.dp(this, 14); t.setPadding(p, p, p, p);
            heardList.addView(t);
        }
    }

    private static String summary(Station.RxEntry e) {
        if ("sms".equals(e.kind)) return "SMS “" + e.sms + "”";
        if ("bad".equals(e.kind)) return "packet with a bad CRC";
        if ("packet".equals(e.kind)) return "Packet · " + e.sms;
        StringBuilder b = new StringBuilder(e.dataType == 3 ? "Voice+data" : "Voice");
        if (e.enc == 2) b.append(" · AES").append(e.decrypt == 1 ? "" : " (no key)");
        if (e.enc == 1) b.append(" · scrambler").append(e.decrypt == 1 ? "" : " (no key)");
        if (!e.text.isEmpty()) b.append(" · “").append(e.text).append("”");
        if (e.posValid) b.append(" · position");
        if (!e.ecd2.isEmpty()) b.append(" · via ").append(e.ecd2);
        if (e.signed && e.ended) b.append(e.sig == 1 ? " · signed ✓" : e.sig == 2 ? " · signature ✗" : " · signed ?");
        if (e.ended) b.append(String.format(Locale.US, " · %.1f s", e.duration)); else b.append(" · on air");
        return b.toString();
    }

    private View heardRow(Station.RxEntry e) {
        LinearLayout l = Ui.hbox(this);
        l.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        l.setBackgroundResource(R.drawable.row_bg);
        TextView time = Ui.mono(this, new SimpleDateFormat("HH:mm", Locale.US).format(new Date(e.at)), 12, Ui.LABEL);
        l.addView(time, new LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout mid = Ui.vbox(this);
        LinearLayout who = Ui.hbox(this);
        TextView src = Ui.text(this, e.src.isEmpty() ? "?" : e.src, 15, Ui.WHITE, true);
        src.setTypeface(Ui.MONO_B);
        who.addView(src);
        who.addView(Ui.text(this, "  → " + (e.dst.isEmpty() ? "?" : e.dst), 13, Ui.TEXT3, false));
        mid.addView(who);
        TextView sm = Ui.text(this, summary(e), 12, Ui.TEXT3, false);
        sm.setMaxLines(2); sm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(sm);
        l.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (e.ended && !Float.isNaN(e.ser)) l.addView(Ui.mono(this, String.format(Locale.US, "SER %.1f %%", 100 * e.ser), 11.5f, Ui.TEXT3));
        l.setOnClickListener(v -> details(e));
        return l;
    }

    private static final String[] SIG = { "not signed", "signature VALID", "signature INVALID", "no public key for the sender", "signature not checkable (late join / lost frames)", "signature missing" };

    private void details(Station.RxEntry e) {
        StringBuilder b = new StringBuilder();
        b.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(e.at))).append("\n");
        b.append(e.src.isEmpty() ? "?" : e.src).append(" → ").append(e.dst.isEmpty() ? "?" : e.dst);
        if ("stream".equals(e.kind)) {
            b.append(" · CAN ").append(e.can);
            if (e.ended) b.append(String.format(Locale.US, "\n%.1f s, %d frames%s", e.duration, e.frames, e.lost > 0 ? " (" + e.lost + " lost)" : ""))
                    .append(Float.isNaN(e.ser) ? "" : String.format(Locale.US, "\nsymbol errors %.1f %%, equaliser EVM %.1f %%", 100 * e.ser, e.evm));
            else b.append("\non air");
            if (e.enc == 1) b.append("\nscrambler ").append(8 * (e.encSub + 1)).append("-bit, ").append(e.decrypt == 1 ? "descrambled" : "no seed: muted");
            if (e.enc == 2) b.append("\nAES-").append(128 + 64 * e.encSub).append(", ").append(e.decrypt == 1 ? "decrypted" : "no key: muted");
            if (!e.text.isEmpty()) b.append("\nMETA text “").append(e.text).append("”");
            if (!e.gnss.isEmpty()) b.append("\nposition ").append(e.gnss);
            if (!e.ecd.isEmpty()) b.append("\nextended callsign ").append(e.ecd);
            if (!e.meta.isEmpty()) b.append("\nMETA ").append(e.meta);
            if (e.signed && e.ended && e.sig >= 0 && e.sig < SIG.length) b.append("\n").append(SIG[e.sig]).append(e.sig == 1 && !e.sigKey.isEmpty() ? " (" + e.sigKey + ")" : "");
            if (e.fromLich) b.append("\nlink setup rebuilt from LICH (joined late)");
        } else b.append("\n").append(e.sms);
        TextView t = Ui.text(this, b.toString(), 14, Ui.WHITE, false);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 20); t.setPadding(p, p / 2, p, 0);
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle(e.src.isEmpty() ? "?" : e.src).setView(t).setNegativeButton("Close", null);
        if (!e.src.isEmpty()) d.setPositiveButton("Send SMS", (x, w) -> m17tab.smsDialog(e.src));
        d.show();
    }

    // ---- Inbox ----

    private void showBadge() {
        int n = st.inbox.unread();
        badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        badge.setText(String.valueOf(n));
    }

    private void rebuildInbox() {
        inboxList.removeAllViews();
        List<Inbox.Msg> all = st.inbox.all();
        SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm", Locale.US);
        if (all.isEmpty()) {
            LinearLayout c = Ui.card(this, 14);
            c.addView(Ui.text(this, "No messages yet.", 14, Ui.TEXT3, false));
            inboxList.addView(c);
        }
        for (Inbox.Msg m : all) {
            LinearLayout row = Ui.hbox(this);
            row.setGravity(Gravity.TOP);
            View mark = Ui.bar(this, m.in && !m.read ? Ui.ORANGE : 0x00000000);
            LinearLayout.LayoutParams mp = (LinearLayout.LayoutParams) mark.getLayoutParams();
            mp.topMargin = Ui.dp(this, 12); mp.rightMargin = Ui.dp(this, 6);
            row.addView(mark, mp);
            LinearLayout bub = Ui.vbox(this);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 12)); bg.setColor(m.in ? 0x1CFFFFFF : 0x3378BEFF);
            bub.setBackground(bg);
            bub.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            LinearLayout head = Ui.hbox(this);
            TextView who = Ui.text(this, m.in ? (m.from.isEmpty() ? "?" : m.from) + "  → " + m.to : "Sent to " + m.to, 13, Ui.WHITE, true);
            head.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            head.addView(Ui.text(this, f.format(new Date(m.at)), 11.5f, Ui.LABEL, false));
            bub.addView(head);
            TextView body = Ui.text(this, m.text, 15, Ui.WHITE, m.in && !m.read);
            bub.addView(body, Ui.matchWrap(this, 2));
            bub.setOnClickListener(v -> { if (m.in) m17tab.smsDialog(m.from.isEmpty() ? null : m.from); else m17tab.smsDialog(m.to, m.text); });
            row.addView(bub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            inboxList.addView(row, Ui.matchWrap(this, inboxList.getChildCount() == 0 ? 0 : 8));
        }
        showBadge();
    }
}
