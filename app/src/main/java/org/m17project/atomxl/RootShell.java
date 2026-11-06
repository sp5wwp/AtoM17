/*
 * AtoM17 - Copyright (C) 2026 IIMacGyverII (SignalDeck); modified for AtoM17, 2026.
 * Based on SignalDeck (https://github.com/IIMacGyverII/SignalDeck); see COPYRIGHT.SignalDeck.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One long-lived root shell for small commands that run often (the W6's speaker and receive lines at every reception).
 * Magisk shows its "granted Superuser rights" toast for every su session, so {@link Root#run} (one su per
 * command) toasted at each reception; this opens su once and feeds it commands, each followed by an end marker.
 * Same su flags as {@link Root#run}; if the shell cannot be started or dies twice, the command falls back to it.
 */
public final class RootShell {
    private Process proc;
    private OutputStream in;
    private BufferedReader out;
    private int seq;

    /** Run {@code cmd} as root; returns its output (stdout and stderr), trimmed. */
    public synchronized String run(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (proc == null) start();
                String end = "__atom17_end_" + (++seq) + "__";
                in.write((cmd + "\necho " + end + "\n").getBytes(StandardCharsets.UTF_8)); in.flush();
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = out.readLine()) != null) {
                    if (line.equals(end)) return sb.toString().trim();
                    sb.append(line).append('\n');
                }
                close();                                                   // end of stream: the shell is gone, start a new one
            } catch (IOException e) { close(); }
        }
        return Root.run(cmd);
    }

    private void start() throws IOException {
        proc = new ProcessBuilder("su", "--mount-master", "--context", "u:r:magisk:s0").redirectErrorStream(true).start();
        in = proc.getOutputStream();
        out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
    }

    public synchronized void close() {
        if (proc != null) { try { in.write("exit\n".getBytes(StandardCharsets.UTF_8)); in.flush(); } catch (IOException ignored) { } proc.destroy(); }
        proc = null; in = null; out = null;
    }
}
