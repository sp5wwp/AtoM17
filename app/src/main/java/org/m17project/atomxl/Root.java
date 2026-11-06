/*
 * AtoM17 - root commands. Copyright (C) 2026 IIMacGyverII (SignalDeck, RootSetup.run); modified for AtoM17, 2026.
 * See COPYRIGHT.SignalDeck. SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** One-shot su commands, plus the two long-lived shells the radio uses (so Magisk does not toast at every PTT). */
public final class Root {
    private Root() { }

    /** Bridge power-ups take seconds, so they get their own shell and a PTT release never waits behind one. */
    static final RootShell POWER = new RootShell(), QUICK = new RootShell();

    static String power(String cmd) { return POWER.run("( " + cmd + " ) </dev/null"); }
    static String quick(String cmd) { return QUICK.run("( " + cmd + " ) </dev/null"); }

    /** {@code su --mount-master --context u:r:magisk:s0 -c cmd}: the magisk domain and the global mount namespace. */
    public static String run(String cmd) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("su", "--mount-master", "--context", "u:r:magisk:s0", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            int rc = p.waitFor();
            if (rc != 0) sb.append("(rc=").append(rc).append(")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroy();
            sb.append("su interrupted");
        } catch (Exception e) { sb.append("su failed: ").append(e.getMessage()); }
        return sb.toString().trim();
    }

    public static boolean available() { return run("id").contains("uid=0"); }

    /** The Atom XL's policy lines (SignalDeck's profile): the app may open the bridge's tty and the reception line. */
    static final String[] POLICY = {
            "allow untrusted_app ttyACM_device chr_file { open read write ioctl getattr lock }",
            "typeattribute ttyACM_device mlstrustedobject",
            "allow untrusted_app agui_device chr_file { open read getattr }",
            "typeattribute agui_device mlstrustedobject",
    };

    static String applyPolicy(java.io.File dir) {
        java.io.File f = new java.io.File(dir, "atom17.rule");
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { for (String l : POLICY) w.write(l + "\n"); }
        catch (java.io.IOException e) { return "policy: " + e.getMessage(); }
        return run("magiskpolicy --live --apply " + f.getAbsolutePath() + " 2>&1");
    }
}
