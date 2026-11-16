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
 * command) toasted at each reception. This opens su once and feeds it commands, each followed by an end marker.
 *
 * A command is sent at most once: if the shell cannot be started or written to, the command has not run and falls
 * back to {@link Root#run}. Once it is written, an ended shell or the deadline gives an error text instead of a
 * second run, because the power sequences and the firmware download must not run twice. The deadline kills the
 * shell, so one hung command cannot block the callers behind it for good.
 */
public final class RootShell {
    private final long timeoutMs;
    private Process proc;
    private OutputStream in;
    private BufferedReader out;
    private int seq;

    RootShell(long timeoutMs) { this.timeoutMs = timeoutMs; }

    /** Run {@code cmd} as root, returns its output (stdout and stderr), trimmed. */
    public synchronized String run(String cmd) {
        String end = "__atom17_end_" + (++seq) + "__";
        try {
            if (proc == null) start();
            in.write((cmd + "\nprintf '\\n%s\\n' " + end + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException e) {
            close();
            return Root.run(cmd);                                         // not sent: one su of its own instead
        }
        Process p = proc;
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(timeoutMs); p.destroy(); } catch (InterruptedException ignored) { }
        }, "root-shell-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.equals(end)) return sb.toString().trim();
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        } finally { watchdog.interrupt(); }
        boolean timedOut = !p.isAlive();
        close();
        return (sb.toString().trim() + (timedOut ? " (root shell: no answer in " + timeoutMs / 1000 + " s)" : " (root shell ended)")).trim();
    }

    private void start() throws IOException {
        proc = Root.process(null, true);
        in = proc.getOutputStream();
        out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
    }

    public synchronized void close() {
        if (proc != null) { try { in.write("exit\n".getBytes(StandardCharsets.UTF_8)); in.flush(); } catch (IOException ignored) { } proc.destroy(); }
        proc = null; in = null; out = null;
    }
}
