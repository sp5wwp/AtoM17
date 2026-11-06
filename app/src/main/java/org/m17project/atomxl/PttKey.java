/*
 * AtoM17 - the hardware PTT key, read from the kernel's input devices as root (getevent), so it works with the screen
 * off and AtoM17 in the background. The key is learned once (press it within 10 s) and stored as the input device's
 * name plus the key's kernel name; device paths change between boots, names do not.
 * Method after SignalDeck's CustomPttKey (C) 2026 IIMacGyverII; written for AtoM17. See COPYRIGHT.SignalDeck.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PttKey {
    interface Listener { void key(boolean down); void log(String s); }

    private static final Pattern ADD = Pattern.compile("^add device \\d+: (\\S+)");
    private static final Pattern NAME = Pattern.compile("^\\s*name:\\s*\"(.*)\"");
    /** "/dev/input/event2: EV_KEY  KEY_F1  DOWN" (with a path when getevent watches all devices) */
    private static final Pattern KEY = Pattern.compile("^(?:(/dev/input/\\S+?):\\s+)?EV_KEY\\s+(\\S+)\\s+(DOWN|UP|REPEAT)\\s*$");
    /** Keys that must never become PTT. */
    private static final java.util.Set<String> REFUSED = new java.util.HashSet<>(java.util.Arrays.asList("KEY_POWER", "BTN_TOUCH", "BTN_TOOL_FINGER", "KEY_WAKEUP"));

    private final Config cfg;
    private volatile Process proc;
    private volatile Thread reader;

    PttKey(Config cfg) { this.cfg = cfg; }

    String device() { return cfg.p.getString("pttDev", ""); }
    String code() { return cfg.p.getString("pttKey", ""); }
    boolean learned() { return !device().isEmpty() && !code().isEmpty(); }

    private static Process root(String cmd) throws java.io.IOException {
        return new ProcessBuilder("su", "--mount-master", "--context", "u:r:magisk:s0", "-c", cmd).redirectErrorStream(true).start();
    }

    /** Device path -> name, from getevent -pl. */
    static java.util.Map<String, String> devices() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        String path = null;
        for (String l : Root.run("getevent -pl 2>/dev/null").split("\n")) {
            Matcher a = ADD.matcher(l); if (a.find()) { path = a.group(1); continue; }
            Matcher n = NAME.matcher(l); if (n.find() && path != null) m.put(path, n.group(1));
        }
        return m;
    }

    /** Wait up to {@code seconds} for one key press on any device; store it. Returns a description, or null. */
    String learn(int seconds) {
        stop();
        java.util.Map<String, String> devs = devices();
        Process p = null;
        try {
            p = root("exec timeout " + seconds + " getevent -ql");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) {
                Matcher m = KEY.matcher(l.trim());
                if (!m.find() || !"DOWN".equals(m.group(3)) || m.group(1) == null) continue;
                if (REFUSED.contains(m.group(2))) continue;
                String name = devs.getOrDefault(m.group(1), m.group(1));
                cfg.edit().putString("pttDev", name).putString("pttKey", m.group(2)).apply();
                return m.group(2) + " on \"" + name + "\"";
            }
        } catch (Exception ignored) {
        } finally { if (p != null) p.destroy(); }
        return null;
    }

    void forget() { stop(); cfg.edit().remove("pttDev").remove("pttKey").apply(); }

    /** Read the learned key until {@link #stop}. */
    void start(Listener l) {
        stop();
        if (!learned()) { l.log("PTT key: none learned yet (Device tab)"); return; }
        final String dev = device(), code = code();
        Thread t = new Thread(() -> {
            String path = null;
            for (java.util.Map.Entry<String, String> e : devices().entrySet()) if (e.getValue().equals(dev) || e.getKey().equals(dev)) path = e.getKey();
            if (path == null) { l.log("PTT key: input device \"" + dev + "\" not found"); return; }
            Root.run("pkill -f 'getevent -ql " + path + "' 2>/dev/null; true");        // a reader left from before
            try {
                Process p = root("exec getevent -ql " + path);
                proc = p;
                l.log("PTT key: " + code + " on " + path);
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (reader == Thread.currentThread() && (line = r.readLine()) != null) {
                    Matcher m = KEY.matcher(line.trim());
                    if (!m.find() || !code.equals(m.group(2))) continue;
                    if ("DOWN".equals(m.group(3))) l.key(true);
                    else if ("UP".equals(m.group(3))) l.key(false);
                }
            } catch (Exception e) { if (reader == Thread.currentThread()) l.log("PTT key: " + e.getMessage()); }
        }, "ptt-key");
        t.setDaemon(true);
        reader = t;
        t.start();
    }

    void stop() {
        boolean was = reader != null;
        reader = null;
        Process p = proc; proc = null;
        if (p != null) p.destroy();
        if (was) new Thread(() -> Root.run("pkill -f 'getevent -ql /dev/input' 2>/dev/null; true")).start();   // its su child outlives destroy()
    }
}
