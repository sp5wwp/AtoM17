/*
 * AtoM17 - named encryption keys (AES keys, scrambler seeds). A memory, or VFO, picks one; picking it makes it the
 * active key for both directions on that channel: M17's AES carries no key ID, so a receiver cannot try several keys
 * and tell which one fits. The active key lives in the settings the encoder and the receiver already read
 * (enc, aesBits, aesKey, scramBits, scramSeed, rxAesKey, rxScramSeed, rxScramBits).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Keys {
    static final class Key {
        String id = "", name = "";
        boolean aes = true;
        int bits = 256;                    // AES 128/192/256, scrambler 8/16/24
        String hex = "";
        String type() { return aes ? "AES-" + bits : "Scrambler " + bits + "-bit"; }
        String shortType() { return aes ? "AES" : "SCR"; }
        /** The last digits only, as the screens show a key. */
        String masked() { String h = M17Tab.clean(hex); return h.isEmpty() ? "not set" : "•••• " + h.substring(Math.max(0, h.length() - 4)); }
        boolean valid() { String h = M17Tab.clean(hex); return h.length() == bits / 4 && (aes || !h.matches("0+")); }
        JSONObject json() {
            try { return new JSONObject().put("id", id).put("name", name).put("aes", aes).put("bits", bits).put("hex", hex); }
            catch (Exception e) { return new JSONObject(); }
        }
        static Key of(JSONObject o) {
            Key k = new Key();
            k.id = o.optString("id"); k.name = o.optString("name"); k.aes = o.optBoolean("aes", true);
            k.bits = o.optInt("bits", 256); k.hex = o.optString("hex");
            return k;
        }
    }

    private final Config cfg;
    Keys(Config cfg) { this.cfg = cfg; migrate(); }

    synchronized List<Key> all() {
        List<Key> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(cfg.p.getString("keys", "[]")); for (int i = 0; i < a.length(); i++) l.add(Key.of(a.getJSONObject(i))); }
        catch (Exception ignored) { }
        return l;
    }

    synchronized void save(List<Key> l) {
        JSONArray a = new JSONArray();
        for (Key k : l) a.put(k.json());
        cfg.edit().putString("keys", a.toString()).apply();
    }

    Key byId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Key k : all()) if (k.id.equals(id)) return k;
        return null;
    }

    static String newId() { return Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36); }

    /** Which channels use the key: "VFO", "memory 3", "memories 3, 5"… or "" when none. */
    String usedBy(String id) {
        List<String> m = new ArrayList<>();
        List<Memories.Ch> l = new Memories(cfg).all();
        for (int i = 0; i < l.size(); i++) if (id.equals(l.get(i).keyId)) m.add(String.valueOf(i + 1));
        boolean vfo = id.equals(cfg.p.getString("vfoKeyId", ""));
        StringBuilder b = new StringBuilder(vfo ? "VFO" : "");
        if (!m.isEmpty()) {
            if (b.length() > 0) b.append(", ");
            b.append(m.size() == 1 ? "memory " : "memories ").append(String.join(", ", m));
        }
        return b.toString();
    }

    /** Make key {@code id} (or none) the active one for transmit and receive. The caller reconfigures the receiver. */
    void activate(String id) {
        Key k = byId(id);
        android.content.SharedPreferences.Editor e = cfg.edit().putString("keyId", k == null ? "" : k.id);
        if (k == null || !k.valid()) e.putInt("enc", M17Native.ENC_NONE).putString("rxAesKey", "").putString("rxScramSeed", "");
        else if (k.aes) e.putInt("enc", M17Native.ENC_AES).putInt("aesBits", k.bits).putString("aesKey", k.hex)
                .putString("rxAesKey", k.hex).putString("rxScramSeed", "");
        else e.putInt("enc", M17Native.ENC_SCRAM).putInt("scramBits", k.bits).putString("scramSeed", k.hex)
                .putString("rxScramSeed", k.hex).putInt("rxScramBits", k.bits).putString("rxAesKey", "");
        e.apply();
    }

    /** First use: the keys entered before named keys existed become named keys, and VFO keeps using what it used. */
    private void migrate() {
        if (cfg.p.contains("keys")) return;
        List<Key> l = new ArrayList<>();
        String vfo = "";
        String tx = M17Tab.clean(cfg.aesKey()), rx = M17Tab.clean(cfg.rxAesKey());
        if (!tx.isEmpty()) { Key k = new Key(); k.id = newId(); k.name = "AES key"; k.aes = true; k.bits = tx.length() * 4; k.hex = tx; l.add(k); if (cfg.enc() == M17Native.ENC_AES) vfo = k.id; }
        if (!rx.isEmpty() && !rx.equals(tx)) { Key k = new Key(); k.id = newId() + "r"; k.name = "AES key (receive)"; k.aes = true; k.bits = rx.length() * 4; k.hex = rx; l.add(k); }
        String st = M17Tab.clean(cfg.scramSeed()), sr = M17Tab.clean(cfg.rxScramSeed());
        if (!st.isEmpty()) { Key k = new Key(); k.id = newId() + "s"; k.name = "Scrambler seed"; k.aes = false; k.bits = cfg.scramBits(); k.hex = st; l.add(k); if (cfg.enc() == M17Native.ENC_SCRAM) vfo = k.id; }
        if (!sr.isEmpty() && !sr.equals(st)) { Key k = new Key(); k.id = newId() + "t"; k.name = "Scrambler seed (receive)"; k.aes = false; k.bits = cfg.rxScramBits(); k.hex = sr; l.add(k); }
        save(l);
        cfg.edit().putString("vfoKeyId", vfo).putBoolean("vfoSign", cfg.sign()).apply();
    }
}
