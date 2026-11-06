/*
 * AtoM17 - power, PTT and USB hand-over of the Atom XL's radio bridge.
 * Copyright (C) 2026 IIMacGyverII (SignalDeck: AgoldUsb, measured on the bench Atom XL); modified for AtoM17, 2026.
 * See COPYRIGHT.SignalDeck. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The shell sequences are SignalDeck's, byte for byte where they touch the hardware: they encode bench findings
 * (stale tty nodes, a ghost ttyACM0 that only a reboot clears, the HAL's reset pulse, no cmode toggle after DFU).
 */
package org.m17project.atomxl;

import java.io.IOException;

final class Bridge {
    private Bridge() { }

    static final String PORT = "/dev/ttyACM0";
    static final String D = "/sys/bus/platform/drivers/mcu/";
    static final String MCU_POWER = D + "mcu_power", USB_PATH = D + "usb_path", RSTMCU = D + "rstmcu", HANDLE_PTT = D + "handle_ptt";
    /** Reception edges: one byte per edge, 0 = reception started, 1 = ended. */
    static final String STMCU = "/dev/stmcu";
    static final String HAL = "aguiextmodule";
    static final String STOCK_APP = "com.agold.intercom";
    static final String USB_ROUTING_OFF = "usb_audio_automatic_routing_disabled";
    /** The stock app flashes any .bin here into the module at its start. AtoM17 never writes there. */
    static final String STOCK_FIRMWARE_DIR = "/sdcard/intercom/dmr";

    private static final String STTY = "S=stty; [ -x /data/adb/magisk/busybox ] && S='/data/adb/magisk/busybox stty'; ";
    private static final String STALE_PORT_GONE = "k=0; while [ -e " + PORT + " ] && [ $k -lt 25 ]; do sleep 0.2; k=$((k+1)); done; "
            + "[ -e " + PORT + " ] && echo 'stale port still there'; ";
    private static final String POWER_OFF = "echo 0 > " + MCU_POWER + "; "
            + "k=0; while [ -e /sys/class/tty/ttyACM0 ] && [ $k -lt 30 ]; do sleep 0.2; k=$((k+1)); done; "
            + "[ -e /sys/class/tty/ttyACM0 ] && echo 'bridge still registered after power-off'; sleep 0.3; ";
    private static final String HAL_PULSE = "sleep 0.2; echo 1 > " + USB_PATH + "; echo 1 > " + MCU_POWER + "; echo 0 > " + RSTMCU + "; sleep 0.2; echo 0 > " + MCU_POWER + "; "
            + "k=0; while [ -e /sys/class/tty/ttyACM0 ] && [ $k -lt 15 ]; do sleep 0.2; k=$((k+1)); done; ";
    /** Radio off with the HAL's pulse, and the USB controller back in device mode (cable mode 0, then 1). */
    static final String RESET = POWER_OFF + HAL_PULSE + "echo 0 > " + USB_PATH + "; sleep 0.5; "
            + "for u in /sys/class/udc/*; do d=$(readlink -f $u); d=${d%/udc/*}; [ -f $d/cmode ] && { echo 0 > $d/cmode; sleep 1; echo 1 > $d/cmode; }; done; sleep 0.5; ";
    private static final String ONE_POWER_UP = "setprop vendor.agui.extmodule.open 1 2>/dev/null; echo 1 > " + USB_PATH + "; echo 1 > " + MCU_POWER + "; sleep 0.75; echo 0 > " + RSTMCU + "; ";
    private static final String USB_SEEN = "ls /sys/bus/usb/devices 2>/dev/null | grep -qE '^[0-9]+-[0-9.]+$' && seen=1; ";
    static final String NOTHING_ATTACHED = "usb: nothing attached";
    static final String POWER_UP = STALE_PORT_GONE + "seen=0; " + ONE_POWER_UP
            + "i=0; while [ ! -e " + PORT + " ] && [ $i -lt 6 ]; do sleep 0.5; i=$((i+1)); " + USB_SEEN + "done; "
            + "if [ ! -e " + PORT + " ]; then echo 'retry: through none'; " + RESET + ONE_POWER_UP
            + "i=0; while [ ! -e " + PORT + " ] && [ $i -lt 20 ]; do sleep 0.5; i=$((i+1)); " + USB_SEEN + "done; "
            + "[ ! -e " + PORT + " ] && [ $seen = 0 ] && echo '" + NOTHING_ATTACHED + "'; fi; ";
    static final String POWER_DOWN = RESET + "setprop vendor.agui.extmodule.open 0 2>/dev/null; start " + HAL + "; ";

    /** Take the radio: HAL stopped, a still-powered bridge reset first, the HAL's own power-up, the port raw at 115200, PTT idle. */
    static String up() throws IOException {
        String out = Root.power(STTY + "stop " + HAL + "; sleep 0.5; "
                + "case \"$(cat " + MCU_POWER + ")\" in *'= 1'*) echo 'was powered: off first'; " + RESET + ";; esac; "
                + POWER_UP
                + "if [ -e " + PORT + " ]; then sleep 2; chmod 0666 " + PORT + "; $S -F " + PORT + " 115200 raw -echo 2>&1; echo 1 > " + HANDLE_PTT + "; "
                + "echo \"port: after $((i*5)) tenths of a second\"; else echo 'port: none'; " + POWER_DOWN + "fi");
        if (!out.contains("port: after")) throw new IOException("the radio's USB bridge did not appear (" + out.replace('\n', ' ').trim() + "); the radio is off again"
                + (out.contains(NOTHING_ATTACHED) ? ". Nothing attached to USB at all: restart the phone." : ""));
        return "bridge up: " + out.replace('\n', ' ').trim();
    }

    /** The bridge powered with PTT held: with the P1/B4 bridge the module starts in its ROM loader. */
    static String loaderUp() throws IOException {
        String out = Root.power(STTY + "echo 0 > " + HANDLE_PTT + "; stop " + HAL + "; sleep 0.5; "
                + "echo 0 > " + MCU_POWER + "; " + STALE_PORT_GONE + "sleep 0.3; echo 0 > " + USB_PATH + "; sleep 0.5; "
                + ONE_POWER_UP
                + "i=0; while ! $S -F " + PORT + " -g >/dev/null 2>&1 && [ $i -lt 30 ]; do sleep 0.5; i=$((i+1)); done; "
                + "if $S -F " + PORT + " -g >/dev/null 2>&1; then chmod 0666 " + PORT + "; $S -F " + PORT + " 115200 raw -echo 2>&1; "
                + "echo \"tty: after $((i*5)) tenths of a second, PTT held\"; else echo 'tty: none'; echo 1 > " + HANDLE_PTT + "; " + POWER_DOWN + "fi");
        if (!out.contains("tty: after")) throw new IOException("the bridge did not come up as a tty (" + out.replace('\n', ' ').trim() + "); PTT released, the radio is off again");
        return "loader bridge up: " + out.replace('\n', ' ').trim();
    }

    /** Radio off, the USB socket back, the HAL running again. */
    static String down() {
        return "bridge down: " + Root.power("echo 1 > " + HANDLE_PTT + " 2>/dev/null; " + POWER_DOWN + "sleep 0.5; "
                + "echo \"radio $(cat " + MCU_POWER + "), HAL $(pidof vendor.mediatek.hardware." + HAL + "@1.0-service >/dev/null && echo running || echo NOT running)\"")
                .replace('\n', ' ').trim();
    }

    /** FM key: the PTT line, active low, and the HAL's property, as its startPtt / stopPtt do. */
    static void ptt(boolean down) throws IOException {
        String r = Root.quick("echo " + (down ? 0 : 1) + " > " + HANDLE_PTT + " && setprop vendor.agui.extmodule.start " + (down ? 1 : 0) + " && echo ok");
        if (!r.endsWith("ok")) throw new IOException(HANDLE_PTT + ": " + r);
    }

    /** The PTT line back to idle: a power-up with it held would start the module in its loader. */
    static void pttIdle() { Root.power("echo 1 > " + HANDLE_PTT); }

    /** Android treats the bridge as a USB headset and grabs the card; switch that off while AtoM17 owns the radio. */
    static String usbRoutingOff() {
        String prev = Root.run("settings get secure " + USB_ROUTING_OFF).trim();
        if (!"1".equals(prev)) Root.run("settings put secure " + USB_ROUTING_OFF + " 1");
        return prev.isEmpty() ? "null" : prev;
    }

    static void usbRoutingRestore(String prev) {
        if (prev == null || "1".equals(prev)) return;
        Root.run("null".equals(prev) ? "settings delete secure " + USB_ROUTING_OFF : "settings put secure " + USB_ROUTING_OFF + " " + prev);
    }

    /** Stop and disable the stock intercom app (it would fight for the radio, and reflash the bridge if it gets no answer). */
    static String stockAppOff() { return Root.run("am force-stop " + STOCK_APP + "; pm disable-user --user 0 " + STOCK_APP + " 2>&1"); }
    static String stockAppOn() { return Root.run("pm enable " + STOCK_APP + " 2>&1"); }

    /** .bin files the stock app would load into the module at its next start, or null. */
    static String pendingStockFirmware() {
        StringBuilder b = new StringBuilder();
        for (String l : Root.run("ls " + STOCK_FIRMWARE_DIR + " 2>/dev/null").split("\\s+"))
            if (l.toLowerCase(java.util.Locale.US).endsWith(".bin")) b.append(b.length() > 0 ? ", " : "").append(l);
        return b.length() == 0 ? null : b.toString();
    }

    /**
     * Agold's App block (on when ro.agui.appblock=on) force-stops an app without its own entry a few minutes after the
     * screen goes off (SignalDeck, 2026-10-28). Returns what is still on for {@code pkg}, or null.
     */
    static String appBlockProblem(String pkg) {
        String[] v = Root.run("echo \"$(getprop ro.agui.appblock)|$(settings get system appblock_enabled)|$(settings get system appblock_list)\"").split("\\|", 3);
        if (v.length < 3 || !"on".equals(v[0].trim()) || "0".equals(v[1].trim())) return null;
        boolean clean = true, boot = true, start = true;
        try {
            String j = v[2].trim();
            org.json.JSONObject o = new org.json.JSONObject(j.isEmpty() || j.equals("null") ? "{}" : j).optJSONObject(pkg);
            if (o != null) { clean = o.optBoolean("clean", true); boot = o.optBoolean("blockboot", true); start = o.optBoolean("blockstart", true); }
        } catch (org.json.JSONException ignored) { }
        StringBuilder b = new StringBuilder();
        if (clean) b.append("background cleanup");
        if (boot) b.append(b.length() > 0 ? ", " : "").append("boot blocker");
        if (start) b.append(b.length() > 0 ? ", " : "").append("start blocker");
        return b.length() == 0 ? null : b.toString();
    }

    static String openAppBlock(String pkg) { return Root.run("am start -n com.agui.appblock/.AppBlockActivity --es package_name " + pkg + " 2>&1 | tail -1"); }
}
