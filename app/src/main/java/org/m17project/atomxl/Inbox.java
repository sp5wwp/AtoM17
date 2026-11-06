/*
 * AtoM17 - the M17 SMS inbox: received and sent messages, newest first, kept across restarts (at most 200).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Inbox {
    static final class Msg {
        final long at; final boolean in; final String from, to, text; boolean read;
        Msg(long at, boolean in, String from, String to, String text, boolean read) { this.at = at; this.in = in; this.from = from; this.to = to; this.text = text; this.read = read; }
    }

    private final SharedPreferences p;
    private final List<Msg> msgs = new ArrayList<>();

    Inbox(Context c) {
        p = c.getSharedPreferences("inbox", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString("msgs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                msgs.add(new Msg(o.getLong("at"), o.getBoolean("in"), o.getString("from"), o.getString("to"), o.getString("text"), o.optBoolean("read", true)));
            }
        } catch (Exception ignored) { }
    }

    synchronized void add(boolean in, String from, String to, String text) {
        msgs.add(0, new Msg(System.currentTimeMillis(), in, from == null ? "" : from, to == null ? "" : to, text == null ? "" : text, !in));
        while (msgs.size() > 200) msgs.remove(msgs.size() - 1);
        save();
    }

    synchronized List<Msg> all() { return new ArrayList<>(msgs); }
    synchronized int unread() { int n = 0; for (Msg m : msgs) if (m.in && !m.read) n++; return n; }
    synchronized void markRead() { for (Msg m : msgs) m.read = true; save(); }
    synchronized void clear() { msgs.clear(); save(); }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Msg m : msgs) a.put(new JSONObject().put("at", m.at).put("in", m.in).put("from", m.from).put("to", m.to).put("text", m.text).put("read", m.read));
        } catch (Exception ignored) { }
        p.edit().putString("msgs", a.toString()).apply();
    }
}
