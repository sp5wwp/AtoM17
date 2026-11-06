/*
 * AtoM17 - the station: owns the radio, the audio and the M17 coder; runs FM and M17 receive and transmit.
 *
 * The transmit sequence on the V076J symbol engine and the receive-audio rules are SignalDeck's bench findings
 * (RadioService.m17VoiceRun / sendSymChunk / paceSym / unkeyAfterStop / rxAfterSym / quietForReply).
 * Copyright (C) 2026 IIMacGyverII (SignalDeck); modified for AtoM17, 2026. See COPYRIGHT.SignalDeck.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Station implements Radio.Events, Audio.Sink {
    interface Listener {
        default void onState() { }
        default void onLog(String line) { }
        default void onRx(RxEntry e) { }
        default void onStatus(JSONObject st) { }
    }

    /** One transmission or packet heard over RF. */
    static final class RxEntry {
        final long at = System.currentTimeMillis();
        String kind = "stream";              // stream | sms | packet | bad
        String src = "", dst = "", text = "", sms = "", meta = "", gnss = "", ecd = "", sigKey = "";
        int can, enc, encSub, decrypt, sig = -1, frames, lost, metaKind, protocol;
        boolean signed, fromLich, ended;
        float duration, evm, ser = Float.NaN;   // evm, ser: the equaliser's (RxStats) at the end
        /** for the main screen's stream card */
        int dataType, station = -1, bearing;
        boolean posValid, altValid, spdValid;
        double lat, lon, alt, speed;
        String ecd1 = "", ecd2 = "";
    }

    private static Station instance;
    static synchronized Station get(Context c) { if (instance == null) instance = new Station(c.getApplicationContext()); return instance; }
    static synchronized Station peek() { return instance; }

    final Context ctx;
    final Config cfg;
    final Inbox inbox;
    private final Radio radio;
    private Audio audio;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "radio"));
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    final List<RxEntry> heard = new ArrayList<>();
    private final StringBuilder logBuf = new StringBuilder();

    private volatile boolean open, starting, flashing, transmitting, receiving;
    private volatile String moduleTag = "", bridgeDate = "", error = "";
    private volatile int caps;
    volatile String appBlock;
    private String usbRoutingPrev;
    private long rxh, txh;
    private AudioTrack fmTrack, m17Track;
    private int m17TrackRate;
    /** DroidStar's voice AGC on the decoded M17 audio (Settings › Voice decoder). */
    private final AudioAgc agc = new AudioAgc();
    /** The decoder's audio rate: 8000 (Codec2) or 16000 (Codec2-mod-neural); set under rxLock with the decoder. */
    private volatile int audioRate = 8000;
    private boolean neuralOn;
    private volatile long quietUntil, lastBlockAt, lastRecoveryAt;

    private Station(Context c) {
        ctx = c;
        cfg = new Config(c);
        inbox = new Inbox(c);
        radio = new Radio(c.getFilesDir(), this);
        pttKey = new PttKey(cfg);
        radio.setGuard(new Radio.Guard() {
            @Override public void before() { quietForReply(); }
            @Override public void after() { ui.postDelayed(() -> worker.execute(Station.this::evalCapture), REPLY_QUIET_MS + 100); }
        });
        rxh = M17Native.rxCreate(48000);                 // fed with the DFE's re-synthesised symbols (48 kHz)
        initDfe();
        txh = M17Native.txCreate();
    }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    boolean isOpen() { return open; }
    boolean isStarting() { return starting; }
    boolean isFlashing() { return flashing; }
    boolean isTransmitting() { return transmitting; }
    boolean isReceiving() { return receiving; }
    private volatile boolean stopping;
    boolean isStopping() { return stopping; }
    /** What is on the air (for the main screen): since when, what kind, and the microphone level in dBFS. */
    volatile long txSince;
    volatile String txKind = "";
    volatile float txLevelDb = -90;
    static float levelDb(short[] p, int n) {
        if (n <= 0) return -90;
        double e = 0; for (int i = 0; i < n; i++) e += (double) p[i] * p[i];
        return (float) Math.max(-90, 10 * Math.log10(e / n / (32768.0 * 32768.0) + 1e-12));
    }
    String moduleTag() { return moduleTag; }
    String bridgeDate() { return bridgeDate; }
    String error() { return error; }
    boolean m17TxCapable() { return (caps & Radio.CAP_M17_TX) != 0; }
    String logText() { synchronized (logBuf) { return logBuf.toString(); } }

    private void state() { ui.post(() -> { for (Listener l : listeners) l.onState(); }); }

    @Override public void log(String s) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()) + " " + s;
        android.util.Log.i("AtoM17", s);
        synchronized (logBuf) { logBuf.append(line).append('\n'); if (logBuf.length() > 200_000) logBuf.delete(0, 50_000); }
        ui.post(() -> { for (Listener l : listeners) l.onLog(line); });
    }

    // ---- start / stop ----

    void start() {
        if (open || starting) return;
        starting = true; error = ""; state();
        worker.execute(() -> {
            try {
                if (!Root.available()) throw new IOException("no root: grant AtoM17 superuser rights in Magisk");
                if (audio == null) { audio = new Audio(ctx, this); audio.setSink(this); }
                String pending = Bridge.pendingStockFirmware();
                if (pending != null) log("WARNING: the stock app would load these into the module at its next start: " + pending + " (" + Bridge.STOCK_FIRMWARE_DIR + ")");
                log("stock app: " + Bridge.stockAppOff());
                appBlock = Bridge.appBlockProblem(ctx.getPackageName());
                if (appBlock != null) log("WARNING: Agold App block will stop AtoM17 with the screen off (" + appBlock + "): turn those off for AtoM17");
                usbRoutingPrev = Bridge.usbRoutingOff();
                radio.open();
                open = true;
                identify();
                applyChannelNow();
                pttKey.start(keyListener);
                updateGnss();
                log("ready: module " + moduleTag + ", bridge " + bridgeDate + (m17TxCapable() ? ", M17 transmit available" : ", no M17 transmit (stock firmware)"));
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
                log("start: " + t);
                try { radio.shutdown(); } catch (Throwable ignored) { }
                open = false;
            } finally {
                starting = false; state();
                evalCapture();
            }
        });
    }

    void stop() {
        if (open || starting) { stopping = true; state(); }
        worker.execute(() -> {
            pttHeld = false;
            pttKey.stop();
            updateGnss();
            if (audio != null) { audio.stopCapture(); audio.stopPlayback(); }
            if (open) radio.shutdown();
            open = false; receiving = false;
            updateGnss();
            Bridge.usbRoutingRestore(usbRoutingPrev);
            // the stock app reflashes the bridge when its version query goes unanswered: hand it back only when the bridge
            // was last seen answering with the stock build (SignalDeck's hand-back rule)
            if (Flasher.STOCK_BRIDGE_DATE.equals(bridgeDate) && !moduleTag.isEmpty()) log("stock app: " + Bridge.stockAppOn());
            else log("stock app stays disabled (bridge " + bridgeDate + ", module " + moduleTag + "): restart the phone, start and stop AtoM17 once, or enable it by hand (pm enable " + Bridge.STOCK_APP + ")");
            String pending = Bridge.pendingStockFirmware();
            if (pending != null) log("WARNING: the stock app would load into the module at its start: " + pending);
            stopping = false;
            state();
        });
    }

    private void identify() throws IOException {
        bridgeDate = String.valueOf(radio.bridgeDate());
        String v = radio.version();
        moduleTag = v == null ? "" : v;
        caps = moduleTag.startsWith("DMR006.U4A.V076") && moduleTag.length() > "DMR006.U4A.V076".length() ? radio.capabilities() : 0;
        log("bridge " + bridgeDate + ", module " + moduleTag + ", capabilities 0x" + Integer.toHexString(caps));
    }

    // ---- channel ----

    /** Re-write the channel from the settings (on the radio thread). */
    void applyChannel() { worker.execute(() -> { try { applyChannelNow(); } catch (Throwable t) { log("channel: " + t); } state(); }); }

    private void applyChannelNow() throws IOException {
        if (!open) return;
        int hz = cfg.freq();
        // the radio's reference error: positive ppm = it lands high, so ask for a little less
        int cmd = (int) Math.round(hz / (1 + cfg.ppm() * 1e-6));
        boolean m17 = cfg.m17();
        // M17: wide FM, squelch open and no tones, so the demodulator gets the audio continuously (SignalDeck does the same)
        boolean ok = radio.setAnalog(cmd, cmd, m17 || cfg.wide(), cfg.high(), m17 ? 0 : cfg.squelch(), m17 ? 0 : cfg.rxTone(), m17 ? 0 : cfg.txTone(), cfg.volume());
        log("channel " + Config.mhz(hz) + " MHz " + (m17 ? "M17" : "FM") + (cmd != hz ? String.format(java.util.Locale.US, " (%.1f ppm: %d Hz sent)", cfg.ppm(), cmd) : "") + (ok ? "" : " (refused)"));
        if (m17) { synchronized (rxLock) { M17Native.rxReset(rxh); } configureRx(); symQueue.clear(); rxStats.reset(); initDfe(); }
        evalCapture();
    }

    // ---- capture rules ----

    static final long REPLY_QUIET_MS = 500, NO_DATA_MS = 1200, RECOVERY_GAP_MS = 20_000;

    /** Before a command whose reply is read: the bridge cuts replies short while the capture streams. */
    private void quietForReply() {
        quietUntil = System.currentTimeMillis() + REPLY_QUIET_MS;
        if (audio != null && audio.capturing()) {
            audio.stopCapture();
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private boolean captureWanted() {
        return open && !flashing && !transmitting && (cfg.m17() || cfg.squelch() == 0 || receiving);
    }

    /** Open or close the capture as the rules say. Runs on the radio thread (or the thread that just used the port). */
    private void evalCapture() {
        if (audio == null) return;
        boolean want = captureWanted();
        if (want && !audio.capturing()) {
            if (System.currentTimeMillis() < quietUntil) { ui.postDelayed(() -> worker.execute(this::evalCapture), quietUntil - System.currentTimeMillis() + 50); return; }
            lastBlockAt = System.currentTimeMillis();
            log(audio.startCapture());
            ui.removeCallbacks(watchdog);
            ui.postDelayed(watchdog, NO_DATA_MS);
        } else if (!want && audio.capturing()) log(audio.stopCapture());
    }

    /**
     * A capture with no data: the bridge streams only while the module plays; a capture left open then hits the
     * kernel's I/O error and the bridge streams nothing until it is power-cycled (SD bench 2026-10-29). Close it cleanly
     * first; if it died anyway, power-cycle the bridge (at most once every 20 s).
     */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (audio == null || !open) return;
            long idle = System.currentTimeMillis() - lastBlockAt;
            if (audio.capturing() && idle > NO_DATA_MS) {
                worker.execute(() -> { log("capture: no audio for " + idle + " ms, closed"); audio.stopCapture(); });
                if (!cfg.m17() && cfg.squelch() > 0) return;            // squelched FM: reopened by the next reception edge
                if (System.currentTimeMillis() - lastRecoveryAt > RECOVERY_GAP_MS) {
                    lastRecoveryAt = System.currentTimeMillis();
                    worker.execute(() -> {
                        try { log("bridge: power-cycle to bring the receive audio back"); radio.powerCycle(); applyChannelNow(); }
                        catch (Throwable t) { log("recovery: " + t); }
                    });
                }
                return;
            }
            if (audio.capturing()) ui.postDelayed(this, NO_DATA_MS / 2);
        }
    };

    @Override public void reception(boolean on) {
        receiving = on;
        log("reception " + (on ? "started" : "ended"));
        state();
        worker.execute(this::evalCapture);
    }

    // ---- receive ----

    private AudioTrack track(int rate) {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                Math.max(min * 4, rate / 2), AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
        t.play();
        return t;
    }

    private final short[] mono = new short[Audio.CAPTURE_PERIOD];
    private final Decimator decimator = new Decimator(4, Audio.RATE);
    private final short[] pcm8 = new short[Audio.CAPTURE_PERIOD];
    private final java.util.concurrent.ConcurrentLinkedQueue<float[]> symQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final float[] SILENT = new float[192];
    private volatile org.m17project.atomxl.dfe.M17Receiver dfe;

    /** The equaliser with the Atom XL table SignalDeck ships (fitted on the bench Atom XL); its frames go to symQueue. */
    private void initDfe() {
        try (java.io.InputStream in = ctx.getAssets().open("m17/eq_unihertz-atomxl.txt")) {
            String[] lines = new String(Audio.readAll(in), StandardCharsets.US_ASCII).trim().split("\\s+");
            float[] coef = new float[lines.length];
            for (int i = 0; i < coef.length; i++) coef[i] = Float.parseFloat(lines[i]);
            org.m17project.atomxl.dfe.M17Receiver r = new org.m17project.atomxl.dfe.M17Receiver(coef, new org.m17project.atomxl.dfe.M17Receiver.Listener() {
                @Override public void started(org.m17project.atomxl.dfe.M17.Lsf lsf) { }
                @Override public void frame(org.m17project.atomxl.dfe.M17.Stream st, float q) { dfeQuality = q; }
                @Override public void lsfFromLich(org.m17project.atomxl.dfe.M17.Lsf lsf) { }
                @Override public void ended(int frames, int good, String why) { log("equaliser: " + frames + " frames, " + good + " confirmed (" + why + ")"); }
            });
            r.tap = symQueue::add;
            r.stats = rxStats::add;
            dfe = r;
        } catch (Throwable t) { log("equaliser: " + t); }
    }
    volatile float dfeQuality;
    /** Receive measurements on the equaliser (Bench › Receive signal, the main status line). */
    final RxStats rxStats = new RxStats();
    private final short[] dec = new short[4096];
    private long statusAt;

    @Override public void onCapture(short[] stereo, int frames) {
        lastBlockAt = System.currentTimeMillis();
        if (transmitting) return;
        if (!cfg.m17()) {
            if (fmTrack == null) fmTrack = track(Audio.RATE);
            for (int i = 0; i < frames; i++) mono[i] = stereo[2 * i];
            fmTrack.write(mono, 0, frames);
            return;
        }
        // the radio's audio path removes the low end of the 4FSK: SignalDeck's decision-feedback equaliser recovers the
        // symbols (8 kHz, left channel), and libm17rx decodes them re-synthesised. One 40 ms frame goes in per 40 ms block.
        int n8 = decimator.run(stereo, frames, pcm8);
        recordBlock(pcm8, n8);
        if (cfg.invert()) for (int i = 0; i < n8; i++) pcm8[i] = (short) Math.max(-32767, -pcm8[i]);   // polarity: before the equaliser
        if (dfe != null) dfe.feed(pcm8, n8);
        float[] fr = symQueue.poll();
        while (symQueue.size() > 4) fr = symQueue.poll();          // never lag more than a few frames
        int evs, n;
        synchronized (rxLock) {
            evs = M17Native.rxPushSymbols(rxh, fr != null ? fr : SILENT);
            n = M17Native.rxReadAudio(rxh, dec);
        }
        if (n > 0) {
            if (cfg.agc()) agc.run(dec, n, audioRate / 50);
            if (m17Track == null || m17TrackRate != audioRate) {
                if (m17Track != null) { try { m17Track.stop(); } catch (Exception ignored) { } m17Track.release(); }
                m17Track = track(audioRate); m17TrackRate = audioRate;
            }
            m17Track.write(dec, 0, n);
        }
        if (evs > 0) { String e; while (true) { synchronized (rxLock) { e = M17Native.rxNextEvent(rxh); } if (e == null) break; onRxEvent(e); } }
        long now = System.currentTimeMillis();
        if (now - statusAt > 250) {
            statusAt = now;
            try { String js; synchronized (rxLock) { js = M17Native.rxStatus(rxh); } JSONObject st = new JSONObject(js); ui.post(() -> { for (Listener l : listeners) l.onStatus(st); }); } catch (Exception ignored) { }
        }
    }

    private RxEntry current;

    private void onRxEvent(String json) {
        try {
            JSONObject e = new JSONObject(json), l = e.getJSONObject("link");
            int kind = e.getInt("kind");
            RxEntry r;
            if (kind == M17Native.EV_STREAM_START || (kind == M17Native.EV_LINK_UPDATE && current == null)) { r = new RxEntry(); current = r; synchronized (heard) { heard.add(0, r); if (heard.size() > 300) heard.remove(heard.size() - 1); } }
            else if (kind == M17Native.EV_PACKET || kind == M17Native.EV_PACKET_BAD) { r = new RxEntry(); synchronized (heard) { heard.add(0, r); } }
            else r = current;
            if (r == null) return;
            fillLink(r, l);
            if (kind == M17Native.EV_STREAM_END) {
                r.ended = true; r.frames = e.getInt("frames"); r.lost = e.getInt("lost"); r.duration = (float) e.getDouble("duration");
                RxStats.Snap q = rxStats.snap();                                 // the equaliser's, not libm17rx's (re-synthesised input)
                r.evm = Float.isNaN(q.evm) ? 0 : q.evm; r.ser = q.ser; r.sig = r.signed ? e.getInt("sig") : -1; r.sigKey = e.getString("sigKey");
                current = null;
                log("M17 " + r.src + " > " + r.dst + ": " + r.frames + " frames, " + String.format(java.util.Locale.US, "%.1f s", r.duration));
            } else if (kind == M17Native.EV_PACKET) {
                r.protocol = e.getInt("protocol"); r.ended = true;
                r.kind = r.protocol == 5 ? "sms" : "packet";
                r.sms = r.protocol == 5 ? e.getString("sms") : "protocol 0x" + Integer.toHexString(r.protocol) + ", " + e.getInt("length") + " bytes: " + e.getString("data");
                if (r.protocol == 5) inbox.add(true, r.src, r.dst, r.sms);
                log("M17 packet " + r.src + " > " + r.dst + ": " + r.sms);
            } else if (kind == M17Native.EV_PACKET_BAD) { r.kind = "bad"; r.ended = true; r.sms = "packet with a bad CRC"; }
            final RxEntry fr = r;
            ui.post(() -> { for (Listener x : listeners) x.onRx(fr); });
        } catch (Exception ex) { log("rx event: " + ex); }
    }

    static void fillLink(RxEntry r, JSONObject l) throws org.json.JSONException {
        if (l.getInt("valid") == 0) return;
        r.src = l.getString("src"); r.dst = l.getString("dst"); r.can = l.getInt("can"); r.enc = l.getInt("enc"); r.encSub = l.getInt("encSub");
        r.signed = l.getInt("signed") != 0; r.decrypt = l.getInt("decrypt"); r.fromLich = l.getInt("fromLich") != 0; r.metaKind = l.getInt("metaKind");
        if (r.metaKind == 1 && l.getString("text").length() > 0) r.text = l.getString("text");
        if (r.metaKind == 2 && l.getInt("posValid") != 0)
            r.gnss = String.format(java.util.Locale.US, "%.5f, %.5f", l.getDouble("lat"), l.getDouble("lon"))
                    + (l.getInt("altValid") != 0 ? String.format(java.util.Locale.US, ", %.0f m", l.getDouble("alt")) : "")
                    + (l.getInt("spdValid") != 0 ? String.format(java.util.Locale.US, ", %.0f km/h %d°", l.getDouble("speed"), l.getInt("bearing")) : "");
        r.dataType = l.getInt("dataType");
        r.posValid = r.metaKind == 2 && l.getInt("posValid") != 0;
        if (r.posValid) {
            r.lat = l.getDouble("lat"); r.lon = l.getDouble("lon"); r.station = l.getInt("station");
            r.altValid = l.getInt("altValid") != 0; r.alt = l.getDouble("alt");
            r.spdValid = l.getInt("spdValid") != 0; r.speed = l.getDouble("speed"); r.bearing = l.getInt("bearing");
        }
        if (r.metaKind == 3) { r.ecd1 = l.getString("ecd1"); r.ecd2 = l.getString("ecd2"); }
        if (r.metaKind == 3) r.ecd = l.getString("ecd1") + (l.getString("ecd2").isEmpty() ? "" : " / " + l.getString("ecd2"));
        if (r.metaKind == 4) r.meta = l.getString("meta");
    }

    /** RX keys, polarity and AGC from the settings. */
    private final Object rxLock = new Object();

    void configureRx() { synchronized (rxLock) { configureRx0(); } }
    private void configureRx0() {
        if (cfg.neural() != neuralOn) {
            if (M17Native.rxSetNeural(rxh, cfg.neural()) == 0) { neuralOn = cfg.neural(); log("M17 voice: " + (neuralOn ? "neural decoder (FARGAN, 16 kHz)" : "Codec2 decoder (8 kHz)")); }
            else log("M17 voice: the neural decoder could not be created (memory)");
            audioRate = M17Native.rxAudioRate(rxh);
        }
        M17Native.rxConfig(rxh, false, true, 0.1f);          // the core sees re-synthesised symbols: polarity is set before the equaliser
        byte[] k = M17Native.hex(cfg.rxAesKey());
        M17Native.rxSetAes(rxh, k != null && (k.length == 16 || k.length == 24 || k.length == 32) ? k : null);
        byte[] s = M17Native.hex(cfg.rxScramSeed());
        int seed = 0; if (s != null) for (byte b : s) seed = (seed << 8) | (b & 0xFF);
        M17Native.rxSetScrambler(rxh, seed, seed == 0 ? 0 : cfg.rxScramBits());
        M17Native.rxClearKeys(rxh);
        for (String line : cfg.pubKeys().split("\n")) {
            String[] f = line.trim().split("[\\s=:,]+", 2);
            if (f.length < 2) continue;
            byte[] pk = M17Native.hex(f[1]);
            if (pk != null && M17Native.rxAddKey(rxh, f[0].toUpperCase(java.util.Locale.US), pk) != 0) log("public key for " + f[0] + " is not a valid P-256 point");
        }
    }

    int eye(float[] out, int max) { synchronized (rxLock) { return M17Native.rxEye(rxh, out, max); } }

    // ---- receive recorder: the equaliser's own input (8 kHz mono, left channel), for offline analysis ----

    private volatile java.io.ByteArrayOutputStream recording;
    private volatile long recordingEnd;

    /** Record {@code seconds} of the M17 receive audio; saved to Downloads as a WAV when done. */
    String record(int seconds) {
        if (!open) return "start the radio first";
        if (!cfg.m17()) return "switch to M17 first (the recorder takes the M17 receive audio)";
        if (recording != null) return "already recording";
        recording = new java.io.ByteArrayOutputStream(seconds * 16000 + 64);
        recordingEnd = System.currentTimeMillis() + seconds * 1000L;
        log("recording " + seconds + " s of receive audio");
        state();
        return null;
    }
    boolean isRecording() { return recording != null; }

    private void recordBlock(short[] p, int n) {
        java.io.ByteArrayOutputStream r = recording;
        if (r == null) return;
        for (int i = 0; i < n; i++) { r.write(p[i]); r.write(p[i] >> 8); }
        if (System.currentTimeMillis() >= recordingEnd) { recording = null; final byte[] pcm = r.toByteArray(); worker.execute(() -> saveWav(pcm)); }
    }

    private void saveWav(byte[] pcm) {
        String name = "atom17-rx-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".wav";
        try {
            java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                    .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(pcm.length);
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "audio/wav");
            v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/AtoM17");
            android.net.Uri u = ctx.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            try (java.io.OutputStream o = ctx.getContentResolver().openOutputStream(u)) { o.write(h.array()); o.write(pcm); }
            log("recording saved: Download/AtoM17/" + name + " (" + pcm.length / 16000 + " s)");
        } catch (Throwable t) { log("recording: " + t); }
        state();
    }

    // ---- transmit ----

    private volatile boolean pttHeld;
    final PttKey pttKey;
    private final PttKey.Listener keyListener = new PttKey.Listener() {
        @Override public void key(boolean down) { log("PTT key " + (down ? "down" : "up")); ptt(down); }
        @Override public void log(String s) { Station.this.log(s); }
    };
    void startPttKey() { if (open) pttKey.start(keyListener); }
    private volatile Thread txThread;
    static final long TX_LIMIT_MS = 115_000;   // under the module's own 120 s timer

    /** Push to talk: FM through the PTT line and the USB playback; M17 through the V076J symbol engine. */
    void ptt(boolean down) {
        if (!down) { pttHeld = false; return; }
        if (!open || flashing || transmitting) return;
        if (cfg.m17() && !m17TxCapable()) { log("M17 transmit needs V076J (Settings › Module firmware); the module answers " + moduleTag); return; }
        if (cfg.m17() && cfg.src().trim().isEmpty()) { log("set your callsign first (Settings › Station and META)"); return; }
        pttHeld = true;
        txSince = System.currentTimeMillis(); txKind = cfg.m17() ? "M17 voice" : "FM"; txLevelDb = -90;
        transmitting = true; state();
        Thread t = new Thread(cfg.m17() ? this::m17VoiceRun : this::fmRun, "tx");
        t.setPriority(Thread.MAX_PRIORITY);
        txThread = t;
        t.start();
    }

    @SuppressLint("MissingPermission")
    private AudioRecord mic(int frames) {
        int min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, frames * 8));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return null; }
        r.startRecording();
        return r;
    }

    private void fmRun() {
        AudioRecord rec = null;
        boolean keyed = false;
        try {
            if (audio.capturing()) { audio.stopCapture(); Thread.sleep(150); }
            rec = mic(256);
            if (rec == null) { log("no microphone (permission?)"); return; }
            log(audio.startPlayback());
            radio.fmPtt(true); keyed = true;
            log("FM key");
            short[] in = new short[256], out = new short[256 * 4 * 2];
            short prev = 0;
            long t0 = System.currentTimeMillis();
            while (pttHeld && System.currentTimeMillis() - t0 < TX_LIMIT_MS) {
                int n = rec.read(in, 0, in.length);
                if (n <= 0) continue;
                txLevelDb = levelDb(in, n);
                for (int i = 0; i < n; i++) {                       // 8 -> 32 kHz, linear, both channels
                    for (int k = 0; k < 4; k++) {
                        short v = (short) (prev + (in[i] - prev) * (k + 1) / 4);
                        out[2 * (4 * i + k)] = v; out[2 * (4 * i + k) + 1] = v;
                    }
                    prev = in[i];
                }
                audio.play(out, n * 4);
            }
        } catch (Throwable t) { log("FM tx: " + t); }
        finally {
            if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } rec.release(); }
            if (audio != null) log(audio.stopPlayback());
            if (keyed) try { radio.fmPtt(false); log("FM unkey"); } catch (Throwable t) { log("unkey: " + t); }
            transmitting = false; state();
            worker.execute(this::evalCapture);
        }
    }

    /** Configure the encoder from the settings; null when it is ready, else why not. */
    String configureTx() { return configureTx(cfg.dst()); }

    String configureTx(String dst) {
        double lat = 0, lon = 0; float alt = 0, spd = 0; int brg = 0, validity = 0;
        if (cfg.metaKind() == M17Native.META_GNSS) {
            android.location.Location loc = lastLocation();
            if (loc != null) {
                lat = loc.getLatitude(); lon = loc.getLongitude(); validity = 1 << 3;
                if (loc.hasAltitude()) { alt = (float) loc.getAltitude(); validity |= 1 << 2; }
                if (loc.hasSpeed() && loc.hasBearing()) { spd = loc.getSpeed() * 3.6f; brg = Math.round(loc.getBearing()); validity |= 1 << 1; }
            } else return "no position known yet (location permission, GNSS fix)";
        }
        byte[] aes = M17Native.hex(cfg.aesKey()), seed = M17Native.hex(cfg.scramSeed()), priv = M17Native.hex(cfg.privKey());
        int enc = cfg.enc();
        if (enc == M17Native.ENC_AES && (aes == null || aes.length != cfg.aesBits() / 8)) return "the AES key must be " + cfg.aesBits() / 4 + " hex digits";
        int sd = 0; if (seed != null) for (byte b : seed) sd = (sd << 8) | (b & 0xFF);
        if (enc == M17Native.ENC_SCRAM && (seed == null || seed.length != cfg.scramBits() / 8 || sd == 0)) return "the scrambler seed must be " + cfg.scramBits() / 4 + " hex digits, not all zero";
        if (cfg.sign() && (priv == null || priv.length != 32)) return "signing needs a private key (M17 tab: Generate)";
        int r = M17Native.txConfigure(txh, cfg.src().trim(), dst.trim(), cfg.can(), cfg.metaKind(), cfg.metaText(),
                lat, lon, alt, spd, brg, cfg.station(), validity, cfg.ecd1(), cfg.ecd2(), enc, cfg.aesBits(), aes, cfg.scramBits(), sd, cfg.sign(), priv);
        return r == 0 ? null : "the encoder refused the settings (" + r + ")";
    }

    // ---- GNSS for META position: live updates while that META is selected and the radio is on ----
    private volatile android.location.Location gnssFix;
    private android.location.LocationListener gnssListener;

    /** Start or stop the location updates as the settings say (any thread). */
    void updateGnss() { ui.post(this::updateGnss0); }

    @SuppressLint("MissingPermission")
    private void updateGnss0() {
        boolean want = open && cfg.metaKind() == M17Native.META_GNSS && cfg.enc() == M17Native.ENC_NONE
                && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        if (want && gnssListener == null) {
            gnssListener = loc -> { gnssFix = loc; state(); };
            try {
                lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 2000, 0, gnssListener, Looper.getMainLooper());
                log("GNSS on (META position)");
            } catch (Throwable t) { log("GNSS: " + t.getMessage()); gnssListener = null; }
        } else if (!want && gnssListener != null) {
            lm.removeUpdates(gnssListener); gnssListener = null; log("GNSS off");
        }
    }

    /** One line about the position META will carry. */
    String gnssText() {
        android.location.Location l = gnssFix != null ? gnssFix : lastLocation();
        if (l == null) return gnssListener != null ? "Waiting for a GNSS fix…" : "No position yet (location permission and GNSS on?)";
        long age = (System.currentTimeMillis() - l.getTime()) / 1000;
        return String.format(java.util.Locale.US, "Position %.5f, %.5f%s, %s", l.getLatitude(), l.getLongitude(),
                l.hasAltitude() ? String.format(java.util.Locale.US, ", %.0f m", l.getAltitude()) : "", age < 10 ? "live" : age + " s old");
    }

    @SuppressLint("MissingPermission")
    private android.location.Location lastLocation() {
        android.location.Location f = gnssFix;
        if (f != null && System.currentTimeMillis() - f.getTime() < 60_000) return f;
        try {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String p : lm.getProviders(true)) { android.location.Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; }
            return best;
        } catch (Throwable t) { return null; }
    }

    // --- the V076J symbol engine (command 0x40) ---

    private Agold.Frame sym(byte[] body, int waitMs) { try { return radio.sym(body, waitMs); } catch (IOException e) { log("sym: " + e.getMessage()); return null; } }

    /** Reset the ring; the first reply after an unkey can be lost (SD bench 2026-11-03: 2 of 10 presses). */
    private boolean symReset() throws InterruptedException {
        for (int t = 0; t < 3; t++) { Agold.Frame f = sym(new byte[] { 3 }, 500); if (f != null) return true; Thread.sleep(300); }
        return false;
    }

    /** One 48-byte chunk (192 symbols) as op 1, the last as op 7. Returns the ring fill, or -1. */
    private int chunk(byte[] data, int off, int n, boolean last) throws InterruptedException {
        byte[] body = new byte[1 + n];
        body[0] = (byte) (last ? 7 : 1);
        System.arraycopy(data, off, body, 1, n);
        Agold.Frame f = sym(body, 500);
        for (int w = 0; f == null && w < 8; w++) { Thread.sleep(40); f = sym(body, 500); }
        if (f == null) { log("symbol engine: no answer at byte " + off); return -1; }
        return f.body.length > 1 ? ((f.body[0] & 0xFF) | ((f.body[1] & 0xFF) << 8)) : 0;
    }

    /** Before the key a short pause; after it, wait while the ring holds more than 1500 symbols. */
    private void pace(int fill, boolean keyed) throws InterruptedException {
        if (!keyed) { Thread.sleep(5); return; }
        for (int spins = 0; fill > 1500 && spins < 400; spins++) {
            Thread.sleep(20);
            Agold.Frame s = sym(new byte[] { 2 }, 500);
            if (s != null && s.body.length > 1) fill = (s.body[0] & 0xFF) | ((s.body[1] & 0xFF) << 8);
        }
        Thread.sleep(15);
    }

    /** The key after eight chunks (1536 symbols) are in the ring: it only drains while keyed. */
    private static final int KEY_AFTER_CHUNKS = 8;
    /** The Atom XL reports no end of a symbol key; its ring (~0.3 s after op 7) has played out after this. */
    static final long SYM_DRAIN_MS = 300;   // the ring is drained before op 7 now (SymStream.flush): only the padding is left

    private final class SymStream {
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        int sent; boolean keyed;
        /** The ring fill the op 7 reply reported: what is left to play before the unkey. */
        int endFill = -1;
        /**
         * Extra preamble frames (+3 -3 alternating) before the transmission's own 40 ms preamble. V076J starts draining the
         * ring at the key while the transmitter is still coming up, so the first symbols never reach the air (SDR,
         * 2026-11-06: no preamble on a voice key, and an SMS, all of whose frames are short, did not decode at all).
         */
        void leadIn(int ms) {
            int frames = Math.max(0, (ms + 20) / 40);
            for (int f = 0; f < frames; f++) pending.write(PREAMBLE_CHUNK, 0, 48);
            if (frames > 0) log("lead-in: " + frames * 40 + " ms of preamble");
        }
        void add(byte[] b, int n) { pending.write(b, 0, n); }
        /**
         * Send every whole chunk as op 1. With {@code last}: the rest too, then the end: key if not yet keyed, wait until
         * the ring has nearly played out, and only then one padding chunk (the EoT pattern) as op 7. V076J drains only a
         * few more refills after op 7 and drops the modulator (atom-xl-v076-m17.md), so anything still queued then would be
         * cut: an SMS lost most of its frames that way, and a voice call its last frame and EoT.
         */
        boolean flush(boolean last) throws InterruptedException, IOException {
            byte[] data = pending.toByteArray();
            while (sent + 48 <= data.length || (last && sent < data.length)) {
                int n = Math.min(48, data.length - sent);
                byte[] c = n == 48 ? java.util.Arrays.copyOfRange(data, sent, sent + 48) : padTo48(data, sent, n);
                int fill = chunk(c, 0, 48, false);
                if (fill < 0) return false;
                sent += n;
                if (!keyed && sent / 48 >= KEY_AFTER_CHUNKS) { radio.symbolKey(true); keyed = true; }
                pace(fill, keyed);
            }
            if (last) {
                if (!keyed) { radio.symbolKey(true); keyed = true; }       // a short transmission (an SMS): all of it is queued, now play it
                drainTo(DRAIN_TO_SYMBOLS);
                endFill = chunk(EOT_CHUNK, 0, 48, true);
                if (endFill < 0) return false;
            }
            if (sent >= 1 << 16) { byte[] rest = java.util.Arrays.copyOfRange(data, sent, data.length); pending.reset(); pending.write(rest); sent = 0; }
            return true;
        }

        /** Poll the ring until at most {@code target} symbols are left (or it should have played out). */
        private void drainTo(int target) throws InterruptedException {
            long until = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < until) {
                Agold.Frame st = sym(new byte[] { 2 }, 300);
                if (st != null && st.body.length > 1) {
                    int fill = (st.body[0] & 0xFF) | ((st.body[1] & 0xFF) << 8);
                    if (fill <= target) { log("symbol engine: " + fill + " symbols left at the end"); return; }
                    Thread.sleep(Math.max(5, Math.min(100, (fill - target) * 1000L / 4800 - 10)));
                } else Thread.sleep(10);
            }
            log("symbol engine: the ring did not drain in 3 s");
        }
    }

    /** At most this many symbols may still be queued when op 7 goes out (about 50 ms of air time). */
    static final int DRAIN_TO_SYMBOLS = 240;
    /** One 48-byte chunk (192 symbols) of the EoT pattern +3 +3 +3 +3 +3 +3 -3 +3: the padding the end may cut. */
    static final byte[] EOT_CHUNK = new byte[48];
    static { for (int i = 0; i < 48; i += 2) { EOT_CHUNK[i] = 0x55; EOT_CHUNK[i + 1] = 0x5D; } }

    /** One 48-byte chunk of the preamble: +3 -3 alternating (dibits 01 11 01 11 = 0x77). */
    static final byte[] PREAMBLE_CHUNK = new byte[48];
    static { java.util.Arrays.fill(PREAMBLE_CHUNK, (byte) 0x77); }

    private static byte[] padTo48(byte[] d, int off, int n) {
        byte[] c = java.util.Arrays.copyOf(EOT_CHUNK, 48);
        System.arraycopy(d, off, c, 0, n);
        return c;
    }

    private void m17VoiceRun() {
        AudioRecord rec = null;
        SymStream s = new SymStream();
        try {
            if (audio.capturing()) { audio.stopCapture(); Thread.sleep(150); }
            String why = configureTx();
            if (why != null) { log("M17: " + why); return; }
            if (!symReset()) { log("M17: the symbol engine did not answer the reset"); return; }
            rec = testTone != null ? null : mic(320);
            if (rec == null && testTone == null) { log("no microphone (permission?)"); return; }
            byte[] b = new byte[6 * 48];
            s.leadIn(cfg.leadInMs());
            s.add(b, M17Native.txBegin(txh, b));
            if (!s.flush(false)) return;
            short[] pcm = new short[320];
            long t0 = System.currentTimeMillis(), lastStat = t0;
            int frames = 0, shortReads = 0;
            short[] tone = testTone;
            String end = "PTT released";
            while (true) {
                if (tone == null && !pttHeld) break;
                if (tone != null && frames * 320 >= tone.length) { end = "test done"; break; }
                if (System.currentTimeMillis() - t0 >= TX_LIMIT_MS) { end = "time limit"; break; }
                int got = 0;
                if (tone != null) { System.arraycopy(tone, frames * 320, pcm, 0, 320); got = 320; }   // no sleep: the ring-fill pacing in flush() sets the rate
                else while (got < 320) { int r = rec.read(pcm, got, 320 - got); if (r <= 0) { shortReads++; break; } got += r; }
                if (got < 320) java.util.Arrays.fill(pcm, Math.max(0, got), 320, (short) 0);
                txLevelDb = levelDb(pcm, 320);
                if (s.keyed && System.currentTimeMillis() - lastStat >= 1000) { lastStat = System.currentTimeMillis(); symStatus("on air"); }
                s.add(b, M17Native.txVoice(txh, pcm, b));
                frames++;
                if (!s.flush(false)) { log("M17 voice: the symbol engine stopped answering after " + frames + " frames"); symStatus("at the failure"); return; }
            }
            log("M17 voice: end (" + end + ") after " + frames + " frames, " + (System.currentTimeMillis() - t0) + " ms" + (shortReads > 0 ? ", " + shortReads + " short microphone reads" : ""));
            if (s.keyed) symStatus("before the last frame");
            s.add(b, M17Native.txEnd(txh, null, b));
            if (!s.flush(true)) return;
            log("M17 voice: " + (frames + 1) + " frames" + (cfg.sign() ? ", signed" : ""));
        } catch (Throwable t) { log("M17 tx: " + t); }
        finally { testTone = null; m17Finish(rec, s.keyed, s.endFill); }
    }

    private void m17Finish(AudioRecord rec, boolean keyed, int endFill) {
        if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } rec.release(); }
        if (keyed) {
            // unkey once what op 7 left in the ring (the EoT padding and a little) has played: a fixed hang left an unmodulated
            // carrier at the end (SDR, 2026-11-06)
            long hang = endFill >= 0 ? Math.max(40, endFill * 1000L / 4800 + 20) : SYM_DRAIN_MS;
            try { Thread.sleep(hang); radio.symbolKey(false); log("unkey after " + hang + " ms"); } catch (Throwable t) { log("unkey: " + t); }
            // after a symbol key the module leaves its receive audio muted until a channel write (SD bench 2026-10-27)
            try { applyChannelNow(); } catch (Throwable t) { log("rx after tx: " + t); }
        }
        transmitting = false; state();
        worker.execute(this::evalCapture);
    }

    /** The engine's own counters: ring fill, underruns, errors (op 2). */
    private void symStatus(String when) {
        Agold.Frame f = sym(new byte[] { 2 }, 500);
        if (f == null || f.body.length < 6) { log("symbol engine " + when + ": no status"); return; }
        byte[] b = f.body;
        log("symbol engine " + when + ": fill " + ((b[0] & 0xFF) | (b[1] & 0xFF) << 8) + ", underruns " + ((b[2] & 0xFF) | (b[3] & 0xFF) << 8) + ", errors " + ((b[4] & 0xFF) | (b[5] & 0xFF) << 8));
    }

    private volatile short[] testTone;

    /** A 10 s M17 voice transmission of a 1 kHz tone, without the microphone or any PTT: for bench checks. */
    String testTransmission() {
        if (!open || flashing || transmitting) return "the radio is busy or off";
        if (!cfg.m17()) return "switch to M17 first";
        if (!m17TxCapable()) return "M17 transmit needs V076J (Settings › Module firmware)";
        if (cfg.src().trim().isEmpty()) return "set your callsign first";
        short[] t = new short[8000 * 10];
        for (int i = 0; i < t.length; i++) t[i] = (short) (8000 * Math.sin(2 * Math.PI * 1000 * i / 8000.0));
        testTone = t;
        txSince = System.currentTimeMillis(); txKind = "M17 test tone"; txLevelDb = -90;
        transmitting = true; state();
        Thread th = new Thread(this::m17VoiceRun, "tx-test");
        th.setPriority(Thread.MAX_PRIORITY);
        th.start();
        return null;
    }

    /** Send an M17 SMS (packet mode, protocol 0x05). Returns null when it has started. */
    String sendSms(String dst, String text) {
        if (!open || flashing || transmitting) return "the radio is busy or off";
        if (!m17TxCapable()) return "M17 transmit needs V076J (Settings › Module firmware)";
        if (cfg.src().trim().isEmpty()) return "set your callsign first";
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        if (t.length > 821) return "at most 821 bytes";
        txSince = System.currentTimeMillis(); txKind = "M17 SMS"; txLevelDb = -90;
        transmitting = true; state();
        Thread th = new Thread(() -> {
            SymStream s = new SymStream();
            try {
                if (audio.capturing()) { audio.stopCapture(); Thread.sleep(150); }
                String why = configureTx(dst == null || dst.isEmpty() ? cfg.dst() : dst);
                if (why != null) { log("SMS: " + why); return; }
                if (!symReset()) { log("SMS: the symbol engine did not answer the reset"); return; }
                byte[] b = new byte[(3 + 33) * 48];
                int n = M17Native.txSms(txh, t, b);
                if (n <= 0) { log("SMS: encoder " + n); return; }
                s.leadIn(cfg.leadInMs());
                s.add(b, n);
                if (s.flush(true)) { String to = dst == null || dst.isEmpty() ? cfg.dst() : dst; log("SMS sent to " + to + ": " + text); inbox.add(false, cfg.src(), to, text); }
            } catch (Throwable e) { log("SMS: " + e); }
            finally { m17Finish(null, s.keyed, s.endFill); }
        }, "sms");
        th.start();
        return null;
    }

    // ---- firmware ----

    void flash(boolean restore, Flasher.Ui fui) {
        if (flashing || transmitting || starting) { fui.log("busy"); return; }
        flashing = true; state();
        worker.execute(() -> {
            android.os.PowerManager.WakeLock lock = ((android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "atom17:firmware");
            lock.acquire(30 * 60 * 1000L);
            try {
                if (audio != null) audio.stopCapture();
                if (!open) {
                    if (audio == null) { audio = new Audio(ctx, this); audio.setSink(this); }
                    log("stock app: " + Bridge.stockAppOff());
                    usbRoutingPrev = Bridge.usbRoutingOff();
                }
                Flasher f = new Flasher(ctx, radio, new Flasher.Ui() {
                    @Override public void phase(String p) { log("firmware: " + p); fui.phase(p); }
                    @Override public void log(String s) { Station.this.log("firmware: " + s); fui.log(s); }
                    @Override public void progress(int d, int t) { fui.progress(d, t); }
                    @Override public boolean confirmReplace(String installed) { return fui.confirmReplace(installed); }
                });
                f.gain = cfg.devGain();
                String result = f.run(restore, moduleTag);
                if (result.startsWith("LOADED ")) cfg.edit().putFloat("devGainLoaded", result.endsWith("V076X") ? cfg.devGain() : 1f).apply();
                else if (result.startsWith("RESTORED")) cfg.edit().putFloat("devGainLoaded", 1f).apply();
                log("firmware: " + result);
                fui.log(result);
                open = radio.isOpen();
                if (open) { identify(); applyChannelNow(); }
            } catch (Throwable t) { log("firmware: " + t); fui.log("error: " + t); }
            finally {
                Bridge.pttIdle();
                if (lock.isHeld()) lock.release();
                flashing = false; state();
                fui.phase("done");
                evalCapture();
            }
        });
    }

    // ---- reporting status pushes ----
    @Override public void status(int code) { log("module status " + code); }
}
