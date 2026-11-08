/*
 * AtoM17 - binds the M17 tab to the settings. Keys and seeds are edited in a dialog (no keyboard suggestions, which
 * mangle long hex strings), checked for length, and shown masked.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

final class M17Tab {
    interface IntSetter { void set(int v); }
    interface StrSetter { void set(String v); }

    private final Activity a;
    private final Station st;
    private final Config cfg;

    M17Tab(Activity a, Station st) { this.a = a; this.st = st; this.cfg = st.cfg; }

    static void spinner(Activity a, int id, String[] items, int selected, IntSetter save) {
        Spinner sp = a.findViewById(id);
        ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, Math.min(items.length - 1, selected)), false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { save.set(pos); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    private void text(int id, String value, StrSetter save) {
        EditText e = a.findViewById(id);
        e.setText(value);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            public void onTextChanged(CharSequence s, int x, int y, int z) { }
            public void afterTextChanged(Editable s) { save.set(s.toString()); }
        });
    }

    private void check(int id, boolean value, java.util.function.Consumer<Boolean> save) {
        CheckBox c = a.findViewById(id);
        c.setChecked(value);
        c.setOnCheckedChangeListener((b, on) -> save.accept(on));
    }

    private void putS(String k, String v) { cfg.edit().putString(k, v).apply(); }
    private void putI(String k, int v) { cfg.edit().putInt(k, v).apply(); }

    // ---- keys ----

    static String clean(String hex) { return hex == null ? "" : hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US); }

    /** "not set", or "128-bit, …1A2B" */
    static String masked(String hex) {
        String h = clean(hex);
        if (h.isEmpty()) return "not set";
        return (h.length() * 4) + "-bit, …" + h.substring(Math.max(0, h.length() - 4));
    }

    /** A hex value of one of the allowed digit counts, edited in a dialog. */
    private void hexDialog(String title, String pref, int[] digits, Runnable after) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setTypeface(Typeface.MONOSPACE);
        e.setText(clean(cfg.p.getString(pref, "")));
        StringBuilder hint = new StringBuilder();
        for (int d : digits) hint.append(hint.length() > 0 ? " or " : "").append(d);
        e.setHint(hint + " hex digits");
        AlertDialog d = new AlertDialog.Builder(a).setTitle(title).setView(e)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String h = clean(e.getText().toString());
            boolean ok = false;
            for (int n : digits) if (h.length() == n) ok = true;
            if (!ok) { e.setError("needs " + hint + " hex digits (has " + h.length() + ")"); return; }
            if (h.matches("0+")) { e.setError("all zero"); return; }
            putS(pref, h);
            after.run();
            d.dismiss();
        }));
        d.show();
    }

    private void confirmClear(String what, String pref, Runnable after) {
        if (clean(cfg.p.getString(pref, "")).isEmpty()) return;
        new AlertDialog.Builder(a).setMessage("Clear the " + what + "?")
                .setPositiveButton("Clear", (dd, w) -> { putS(pref, ""); after.run(); }).setNegativeButton("Cancel", null).show();
    }

    private void showKeys() {
        ((TextView) a.findViewById(R.id.aesKey)).setText(masked(cfg.aesKey()));
        ((TextView) a.findViewById(R.id.scrSeed)).setText(masked(cfg.scramSeed()));
        ((TextView) a.findViewById(R.id.rxAes)).setText(masked(cfg.rxAesKey()));
        ((TextView) a.findViewById(R.id.rxScr)).setText("seed " + masked(cfg.rxScramSeed()));
        int n = 0;
        for (String l : cfg.pubKeys().split("\n")) if (!l.trim().isEmpty()) n++;
        ((TextView) a.findViewById(R.id.pubKeys)).setText(n == 0 ? "no public keys" : n + " public key" + (n == 1 ? "" : "s"));
        String pub = publicKey();
        ((TextView) a.findViewById(R.id.pubKey)).setText(pub.isEmpty() ? "No signing key" : "My public key (X||Y): " + pub);
    }

    private String publicKey() {
        byte[] p = M17Native.hex(cfg.privKey());
        if (p == null || p.length != 32) return "";
        byte[] pub = M17Native.publicKey(p);
        return pub == null ? "" : M17Native.hex(pub);
    }

    private void rxChanged() { showKeys(); st.configureRx(); }

    void bind() {
        text(R.id.src, cfg.src(), v -> putS("src", v.trim().toUpperCase(Locale.US)));
        text(R.id.dst, cfg.dst(), v -> putS("dst", v.trim().toUpperCase(Locale.US)));
        text(R.id.can, String.valueOf(cfg.can()), v -> { try { int c = Integer.parseInt(v.trim()); if (c >= 0 && c <= 15) putI("can", c); } catch (NumberFormatException ignored) { } });

        spinner(a, R.id.metaKind, new String[] { "META: text", "META: position (phone GNSS)", "META: extended callsign" }, cfg.metaKind(), v -> {
            putI("metaKind", v); visibility();
            if (v == M17Native.META_GNSS) ensureGnss();
            st.updateGnss();
        });
        a.findViewById(R.id.metaTextEdit).setOnClickListener(v -> metaTextDialog());
        text(R.id.ecd1, cfg.ecd1(), v -> putS("ecd1", v.trim().toUpperCase(Locale.US)));
        text(R.id.ecd2, cfg.ecd2(), v -> putS("ecd2", v.trim().toUpperCase(Locale.US)));
        spinner(a, R.id.station, new String[] { "Station: fixed", "Station: mobile", "Station: handheld" }, cfg.station(), v -> putI("station", v));

        spinner(a, R.id.enc, new String[] { "No encryption", "Scrambler", "AES" }, cfg.enc(), v -> { putI("enc", v); visibility(); });
        final int[] aes = { 128, 192, 256 }, scr = { 8, 16, 24 };
        spinner(a, R.id.aesBits, new String[] { "AES-128", "AES-192", "AES-256" }, idx(aes, cfg.aesBits()), v -> putI("aesBits", aes[v]));
        a.findViewById(R.id.aesEdit).setOnClickListener(v -> hexDialog("Transmit AES key (" + cfg.aesBits() + "-bit)", "aesKey", new int[] { cfg.aesBits() / 4 }, this::showKeys));
        a.findViewById(R.id.aesNew).setOnClickListener(v -> {
            byte[] k = new byte[cfg.aesBits() / 8];
            new java.security.SecureRandom().nextBytes(k);
            putS("aesKey", M17Native.hex(k));
            showKeys();
            Toast.makeText(a, "New random key. Edit shows it, to share with the other stations.", Toast.LENGTH_LONG).show();
        });
        a.findViewById(R.id.aesClear).setOnClickListener(v -> confirmClear("transmit AES key", "aesKey", this::showKeys));
        spinner(a, R.id.scrBits, new String[] { "8-bit", "16-bit", "24-bit" }, idx(scr, cfg.scramBits()), v -> putI("scramBits", scr[v]));
        a.findViewById(R.id.scrEdit).setOnClickListener(v -> hexDialog("Transmit scrambler seed (" + cfg.scramBits() + "-bit)", "scramSeed", new int[] { cfg.scramBits() / 4 }, this::showKeys));
        a.findViewById(R.id.scrClear).setOnClickListener(v -> confirmClear("transmit scrambler seed", "scramSeed", this::showKeys));

        check(R.id.sign, cfg.sign(), on -> cfg.edit().putBoolean("sign", on).apply());
        a.findViewById(R.id.keyGen).setOnClickListener(v -> {
            if (!cfg.privKey().isEmpty())
                new AlertDialog.Builder(a).setTitle("Replace the signing key?")
                        .setMessage("Stations that stored your current public key will no longer verify your streams.")
                        .setPositiveButton("Replace", (d, w) -> newKey()).setNegativeButton("Cancel", null).show();
            else newKey();
        });
        a.findViewById(R.id.keyImport).setOnClickListener(v -> hexDialog("Signing private key (P-256)", "privKey", new int[] { 64 }, () -> {
            if (publicKey().isEmpty()) { putS("privKey", ""); Toast.makeText(a, "Not a valid P-256 private key", Toast.LENGTH_LONG).show(); }
            showKeys();
        }));
        a.findViewById(R.id.keyCopy).setOnClickListener(v -> {
            String pub = publicKey();
            if (pub.isEmpty()) { Toast.makeText(a, "No signing key yet", Toast.LENGTH_SHORT).show(); return; }
            ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("M17 public key", (cfg.src().isEmpty() ? "CALLSIGN" : cfg.src()) + " " + pub));
            Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
        });
        a.findViewById(R.id.keyClear).setOnClickListener(v -> confirmClear("signing key", "privKey", this::showKeys));

        a.findViewById(R.id.rxAesEdit).setOnClickListener(v -> hexDialog("Receive AES key", "rxAesKey", new int[] { 32, 48, 64 }, this::rxChanged));
        a.findViewById(R.id.rxAesClear).setOnClickListener(v -> confirmClear("receive AES key", "rxAesKey", this::rxChanged));
        spinner(a, R.id.rxScrBits, new String[] { "RX scrambler 8-bit", "RX scrambler 16-bit", "RX scrambler 24-bit" }, idx(scr, cfg.rxScramBits()), v -> { putI("rxScramBits", scr[v]); rxChanged(); });
        a.findViewById(R.id.rxScrEdit).setOnClickListener(v -> hexDialog("Receive scrambler seed (" + cfg.rxScramBits() + "-bit)", "rxScramSeed", new int[] { cfg.rxScramBits() / 4 }, this::rxChanged));
        a.findViewById(R.id.rxScrClear).setOnClickListener(v -> confirmClear("receive scrambler seed", "rxScramSeed", this::rxChanged));
        a.findViewById(R.id.pubKeysEdit).setOnClickListener(v -> pubKeysDialog());
        check(R.id.agc, cfg.agc(), on -> cfg.edit().putBoolean("agc", on).apply());
        check(R.id.neural, cfg.neural(), on -> { cfg.edit().putBoolean("neural", on).apply(); st.configureRx(); });
        check(R.id.invert, cfg.invert(), on -> { cfg.edit().putBoolean("invert", on).apply(); st.configureRx(); });

        int[] leads = { 0, 120, 200, 320, 480, 800 };
        String[] leadNames = new String[leads.length];
        int leadIdx = 2;
        for (int i = 0; i < leads.length; i++) { leadNames[i] = "Lead-in preamble: " + (leads[i] == 0 ? "none" : leads[i] + " ms"); if (leads[i] == cfg.leadInMs()) leadIdx = i; }
        spinner(a, R.id.leadIn, leadNames, leadIdx, v -> putI("leadIn", leads[v]));
        showKeys();
        showMeta();
        visibility();
        if (cfg.metaKind() == M17Native.META_GNSS) ensureGnss();
    }

    /** Compose and send an M17 SMS (packet mode, protocol 0x05). {@code to}: a preset destination (a reply), or null. */
    void smsDialog(String to) { smsDialog(to, null); }

    void smsDialog(String to, String text) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText dst = new EditText(a);
        dst.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        dst.setHint("To (callsign or @ALL)");
        dst.setText(to != null ? to : cfg.dst());
        EditText msg = new EditText(a);
        msg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        msg.setMinLines(3);
        msg.setHint("Message");
        if (text != null) msg.setText(text);
        TextView count = new TextView(a);
        count.setTextColor(0xFF8A949E);
        count.setText("0 / 821 bytes");
        msg.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            public void onTextChanged(CharSequence s, int x, int y, int z) { }
            public void afterTextChanged(Editable s) {
                int n = s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                count.setText(n + " / 821 bytes, " + ((n + 4 + 24) / 25) + " frame" + ((n + 4 + 24) / 25 == 1 ? "" : "s"));
            }
        });
        box.addView(dst); box.addView(msg); box.addView(count);
        AlertDialog d = new AlertDialog.Builder(a).setTitle("M17 SMS").setView(box)
                .setPositiveButton("Send", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = msg.getText().toString();
            if (t.isEmpty()) { msg.setError("empty"); return; }
            String why = st.sendSms(dst.getText().toString().trim().toUpperCase(Locale.US), t);
            if (why != null) { Toast.makeText(a, why, Toast.LENGTH_LONG).show(); return; }
            d.dismiss();
        }));
        d.show();
        msg.requestFocus();
    }

    private void metaTextDialog() {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(cfg.metaText());
        e.setSelection(e.getText().length());
        e.setHint("Up to 52 bytes (4 blocks of 13)");
        AlertDialog d = new AlertDialog.Builder(a).setTitle("META text").setView(e)
                .setPositiveButton("Save", null).setNeutralButton("Clear", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String t = e.getText().toString();
                int n = t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (n > 52) { e.setError(n + " bytes: at most 52"); return; }
                putS("metaText", t); showMeta(); d.dismiss();
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> e.setText(""));
        });
        d.show();
    }

    private void showMeta() {
        String t = cfg.metaText();
        ((TextView) a.findViewById(R.id.metaText)).setText(t.isEmpty() ? "no text" : "“" + t + "”");
    }

    /** META position needs the location permission and location switched on; ask for what is missing. */
    void ensureGnss() {
        if (a.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[] { android.Manifest.permission.ACCESS_FINE_LOCATION }, 2);
            return;
        }
        android.location.LocationManager lm = (android.location.LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null && !lm.isLocationEnabled())
            new AlertDialog.Builder(a).setTitle("Location is off")
                    .setMessage("META position sends the phone's GNSS fix. Switch location on?")
                    .setPositiveButton("Settings", (d, w) -> a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Cancel", null).show();
        st.updateGnss();
    }

    void showGnss() {
        TextView t = a.findViewById(R.id.gnssInfo);
        if (t == null) return;                       // not on this screen
        if (cfg.metaKind() != M17Native.META_GNSS || cfg.enc() != M17Native.ENC_NONE) { t.setVisibility(View.GONE); return; }
        t.setVisibility(View.VISIBLE);
        t.setText(st.gnssText());
    }

    private void pubKeysDialog() {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setTypeface(Typeface.MONOSPACE);
        e.setMinLines(4);
        e.setText(cfg.pubKeys());
        e.setHint("CALLSIGN HEX, one per line");
        new AlertDialog.Builder(a).setTitle("Public keys of other stations").setView(e)
                .setPositiveButton("Save", (d, w) -> { putS("pubKeys", e.getText().toString()); rxChanged(); })
                .setNegativeButton("Cancel", null).show();
    }

    private static int idx(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return a.length - 1; }

    private void newKey() {
        byte[] k = M17Native.makeKey();
        if (k == null) { Toast.makeText(a, "Key generation failed", Toast.LENGTH_SHORT).show(); return; }
        putS("privKey", M17Native.hex(java.util.Arrays.copyOfRange(k, 0, 32)));
        java.util.Arrays.fill(k, (byte) 0);
        showKeys();
    }

    private void visibility() {
        int mk = cfg.metaKind(), enc = cfg.enc();
        boolean clear = enc == M17Native.ENC_NONE;
        a.findViewById(R.id.metaKind).setVisibility(clear ? View.VISIBLE : View.GONE);
        a.findViewById(R.id.metaTextRow).setVisibility(clear && mk == M17Native.META_TEXT ? View.VISIBLE : View.GONE);
        showGnss();
        a.findViewById(R.id.ecdRow).setVisibility(clear && mk == M17Native.META_ECD ? View.VISIBLE : View.GONE);
        a.findViewById(R.id.station).setVisibility(clear && mk == M17Native.META_GNSS ? View.VISIBLE : View.GONE);
        TextView note = a.findViewById(R.id.metaEncNote);
        if (note != null) {
            Keys.Key k = new Keys(cfg).byId(cfg.p.getString("keyId", ""));
            note.setVisibility(clear ? View.GONE : View.VISIBLE);
            note.setText("Encryption is on" + (k != null ? " (" + k.name + ")" : "") + ", so META carries the "
                    + (enc == M17Native.ENC_AES ? "AES nonce" : "scrambler state") + " instead. Choose \u201cNo encryption\u201d in Encryption and signing to send text or position.");
        }
        a.findViewById(R.id.aesRow).setVisibility(enc == M17Native.ENC_AES ? View.VISIBLE : View.GONE);
        a.findViewById(R.id.scrRow).setVisibility(enc == M17Native.ENC_SCRAM ? View.VISIBLE : View.GONE);
    }
}
