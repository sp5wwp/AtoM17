/*
 * AtoM17 - channel memories: name, frequency, mode, CAN and the FM settings, kept as JSON in the settings.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Memories {
    static final class Ch {
        String name; int hz; boolean m17; int can, rxTone, txTone; boolean wide;
        int shift; int offset = 7_600_000;                 // repeater: -1 / 0 / +1, Hz
        String keyId = ""; boolean sign;                   // M17: the named key (Keys), "" none; sign transmissions
        boolean high = true;                               // RF power
        JSONObject json() {
            try {
                return new JSONObject().put("name", name).put("hz", hz).put("m17", m17).put("can", can)
                        .put("rxTone", rxTone).put("txTone", txTone).put("wide", wide).put("shift", shift).put("offset", offset).put("keyId", keyId).put("sign", sign).put("high", high);
            } catch (Exception e) { return new JSONObject(); }
        }
        static Ch of(JSONObject o) {
            Ch c = new Ch();
            c.name = o.optString("name", ""); c.hz = o.optInt("hz"); c.m17 = o.optBoolean("m17", true); c.can = o.optInt("can");
            c.rxTone = o.optInt("rxTone"); c.txTone = o.optInt("txTone"); c.wide = o.optBoolean("wide");
            c.shift = Math.max(-1, Math.min(1, o.optInt("shift", 0))); c.offset = o.optInt("offset", 7_600_000);
            c.keyId = o.optString("keyId", ""); c.sign = o.optBoolean("sign", false);
            c.high = o.optBoolean("high", true);
            return c;
        }
        /** The radio's current channel. */
        static Ch current(Config cfg) {
            Ch c = new Ch();
            c.hz = cfg.freq(); c.m17 = cfg.m17(); c.can = cfg.can(); c.rxTone = cfg.rxTone(); c.txTone = cfg.txTone(); c.wide = cfg.wide();
            c.shift = cfg.shift(); c.offset = cfg.offset();
            c.keyId = cfg.p.getString("keyId", ""); c.sign = cfg.sign(); c.high = cfg.high();
            c.name = Config.mhz(c.hz);
            return c;
        }
        /** The short tag after the frequency: "M17 CAN 0 HI", "FM 12.5k T88.5 −7.6M LO". */
        String tag() { return tag(false); }
        /** simplexWord: "Simplex" where there is no shift (the VFO line says it; memories leave it out). */
        String tag(boolean simplexWord) {
            String t;
            if (m17) t = "M17 CAN " + can;
            else {
                t = "FM " + (wide ? "25k" : "12.5k");
                if (rxTone > 0 || txTone > 0) t += " T" + SettingsActivity.tone(rxTone > 0 ? rxTone : txTone);
            }
            if (shift != 0) t += " " + Config.shiftText(shift, offset) + "M";
            else if (simplexWord) t += " Simplex";
            return t + (high ? " HI" : " LO");
        }
        /** 438.825.000, as the frequency display groups it. */
        String dotted() {
            String d = String.format(java.util.Locale.US, "%09d", hz);
            return d.substring(0, 3) + "." + d.substring(3, 6) + "." + d.substring(6);
        }
        String summary() {
            return Config.mhz(hz) + " MHz" + (shift != 0 ? " (" + Config.shiftText(shift, offset) + ")" : "") + " · " + (m17 ? "M17 · CAN " + can
                    : "FM " + (wide ? "25" : "12.5") + " kHz" + (rxTone > 0 || txTone > 0 ? " · CTCSS " + SettingsActivity.tone(rxTone) + "/" + SettingsActivity.tone(txTone) : ""));
        }
    }

    private final Config cfg;
    Memories(Config cfg) { this.cfg = cfg; }

    synchronized List<Ch> all() {
        List<Ch> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(cfg.p.getString("memories", "[]")); for (int i = 0; i < a.length(); i++) l.add(Ch.of(a.getJSONObject(i))); }
        catch (Exception ignored) { }
        return l;
    }

    synchronized void save(List<Ch> l) {
        JSONArray a = new JSONArray();
        for (Ch c : l) a.put(c.json());
        cfg.edit().putString("memories", a.toString()).apply();
    }

    /** Tune to memory {@code i}: its frequency, mode, CAN and FM settings become the radio's. */
    void apply(Station st, int i) {
        List<Ch> l = all();
        if (l.isEmpty()) return;
        i = ((i % l.size()) + l.size()) % l.size();
        Ch c = l.get(i);
        if (!cfg.p.contains("vfoHigh")) cfg.edit().putBoolean("vfoHigh", cfg.high()).apply();
        cfg.edit().putInt("memIdx", i).putInt("freq", c.hz).putBoolean("m17", c.m17).putInt("can", c.can)
                .putInt("rxTone", c.rxTone).putInt("txTone", c.txTone).putBoolean("wide", c.wide)
                .putInt("shift", c.shift).putInt("offset", c.offset).putBoolean("sign", c.sign).putBoolean("high", c.high).apply();
        new Keys(cfg).activate(c.m17 ? c.keyId : "");
        st.configureRx();
        st.applyChannel();
    }

    /** VFO → MEM: keep VFO's channel (frequency, mode, CAN, FM settings, shift) so leaving MEM brings it back. */
    void enter(Station st, int i) {
        if (!memMode()) {
            Ch v = Ch.current(cfg);
            cfg.edit().putString("vfoState", v.json().toString()).putBoolean("vfoHigh", cfg.p.getBoolean("vfoHigh", cfg.high())).apply();
        }
        cfg.edit().putBoolean("memMode", true).apply();
        apply(st, i);
    }

    /** Back to VFO: its own channel as it was before MEM, its key, signing switch and RF power. */
    void vfo(Station st) {
        SharedPreferences.Editor e = cfg.edit().putBoolean("memMode", false).putBoolean("sign", cfg.p.getBoolean("vfoSign", cfg.sign()))
                .putBoolean("high", cfg.p.getBoolean("vfoHigh", cfg.high()));
        try {
            String js = cfg.p.getString("vfoState", "");
            if (!js.isEmpty()) {
                Ch v = Ch.of(new JSONObject(js));
                if (Config.tunable(v.hz))
                    e.putInt("freq", v.hz).putBoolean("m17", v.m17).putInt("can", v.can).putInt("rxTone", v.rxTone).putInt("txTone", v.txTone)
                            .putBoolean("wide", v.wide).putInt("shift", v.shift).putInt("offset", v.offset);
            }
        } catch (Exception ignored) { }
        e.apply();
        new Keys(cfg).activate(cfg.p.getString("vfoKeyId", ""));
        st.configureRx();
        st.applyChannel();
    }

    int index() { return cfg.p.getInt("memIdx", 0); }
    boolean memMode() { return cfg.p.getBoolean("memMode", false); }
}
