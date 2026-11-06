/*
 * AtoM17 - Copyright (C) 2026 IIMacGyverII (SignalDeck, ham/M17Receiver.java); modified for AtoM17, 2026:
 * package, no Android string resources, and a symbol tap (SymbolTap) feeding the equalised frames to libm17rx. See COPYRIGHT.SignalDeck. SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.m17project.atomxl.dfe;

/**
 * M17 receiver for this radio's 8 kHz receive audio. The radio's audio path removes everything below about 250 Hz, where
 * M17 4FSK carries much of its signal, so a plain demodulator decodes nothing. This one equalizes: a decision-feedback
 * equalizer (feed-forward taps at half-symbol spacing with cubic terms for the receiver's compression, plus the last 24
 * symbols fed back, which rebuild the missing low frequencies), calibrated per phone, with the symbol timing re-chosen
 * every 40 ms frame and the coefficients slowly adapted on frames the error correction confirms. The fed-back history
 * is corrected wherever the symbols are known: a stream frame's 8 sync symbols, and a whole frame once its error
 * correction confirms it (re-encoded). Without that, one burst of wrong decisions fed back spreads into the next frames
 * (ANYSECU W6+, 2026-10-27: a table fitted on one transmission lost the stream after 11-14 frames).
 * A transmission is found by its preamble (a 40 ms 2400 Hz tone, which the radio passes), then the LSF sync word.
 * <p>i18n: stays English (the end and reset reasons are diagnostic labels; no screen shows them).
 */
public final class M17Receiver {
    public interface Listener {
        void started(M17.Lsf lsf);                    // after the LSF (crcOk tells whether it is reliable)
        void frame(M17.Stream s, float quality);       // every stream frame (payload = two Codec 2 3200 frames)
        void lsfFromLich(M17.Lsf lsf);                 // the LSF rebuilt from six LICH chunks (late or damaged LSF)
        void ended(int frames, int good, String why);
        /** A packet whose CRC checked. The body is the type byte and the payload, without the CRC. */
        default void packet(M17.Lsf lsf, byte[] body) { }
        /** The last frame arrived and the packet CRC failed, or a frame in the run was missing. */
        default void framesLost() { }
    }

    public static final int L = 8, FB = 24, GROUPS = 4, NCOEF = GROUPS * (2 * L + 1) + FB + 1;
    static final int UP = 6, SPS48 = 10, HALF = 16;              // 8 -> 48 kHz; 10 samples a symbol; interpolator half length (input samples)
    private static final float[] INTERP = new float[UP * 2 * HALF + 1];
    static {
        int n = INTERP.length, c = n / 2;
        for (int i = 0; i < n; i++) {
            double t = (i - c) / (double) UP, s = t == 0 ? 1 : Math.sin(Math.PI * t) / (Math.PI * t);
            double w = 0.42 + 0.5 * Math.cos(Math.PI * (i - c) / (c + 1.0)) + 0.08 * Math.cos(2 * Math.PI * (i - c) / (c + 1.0));
            INTERP[i] = (float) (s * w);
        }
    }

    private final float[] coef;
    /** AtoM17: every frame the receiver tracks, as 192 equalised symbols (the exact re-encoded symbols where the frame is
     *  confirmed), in air order, for re-synthesis into libm17rx. */
    public interface SymbolTap { void symbols(float[] s192); }

    /**
     * AtoM17: per-frame measurements on the equaliser itself, before any frame is re-encoded for libm17rx (whose own
     * EVM, deviation and offset then describe the re-synthesised signal, not the radio's). Symbols are in M17 units
     * (+-1, +-3); the eye is the equaliser's INPUT (the radio's audio) around 16 symbol instants of the frame, scaled so
     * the preamble's RMS is 3.
     */
    public static final class Stats {
        public float[] soft;                 // 192 equaliser outputs
        public int symErrors = -1;           // of the 184 after the sync: decisions that differ from the frame as decoded and re-encoded; -1 = unknown
        public int syncHits;                 // of 8 sync symbols
        public float mse;                    // mean squared decision error, symbol units
        public float metric = Float.NaN;     // Viterbi path metric (more negative = better)
        public int lichErrors = -1;          // Golay corrections in the LICH
        public boolean confirmed, packet;
        public float gain;                   // preamble RMS of the input, audio counts
        public float[] eye; public int eyeTraces;
    }
    public interface StatsTap { void frame(Stats s); }
    public volatile StatsTap stats;
    public static final int EYE_POINTS = 21, EYE_TRACES = 16;

    private void stats(float[] sym, float mse, int syncHits, int[] exact, M17.Stream st, boolean confirmed, boolean packet) {
        StatsTap t = stats; if (t == null) return;
        Stats s = new Stats();
        s.soft = sym.clone(); s.mse = mse; s.syncHits = syncHits; s.confirmed = confirmed; s.packet = packet; s.gain = gain;
        if (exact != null && exact.length == 192) { int m = 0; for (int i = 8; i < 192; i++) if ((int) slice(sym[i]) != exact[i]) m++; s.symErrors = m; }
        if (st != null) { s.metric = st.metric; s.lichErrors = st.lich != null ? st.lichErrors : -1; }
        s.eye = new float[EYE_TRACES * EYE_POINTS];
        float k = gain > 0 ? 3f / gain : 0f;
        for (int tr = 0; tr < EYE_TRACES; tr++) {
            long c = chosen + (long) (8 + tr * 11) * SPS48;
            for (int j = 0; j < EYE_POINTS; j++) s.eye[tr * EYE_POINTS + j] = at(c + j - EYE_POINTS / 2) * k;
        }
        s.eyeTraces = EYE_TRACES;
        t.frame(s);
    }
    public volatile SymbolTap tap;
    private void emit(float[] s) { SymbolTap t = tap; if (t != null) t.symbols(s.clone()); }
    private void emit(int[] s) { SymbolTap t = tap; if (t == null) return; float[] f = new float[192]; for (int i = 0; i < 192 && i < s.length; i++) f[i] = s[i]; t.symbols(f); }
    private static final float[] PREAMBLE_SYMS = new float[192], EOT_SYMS = new float[192];
    static { for (int i = 0; i < 192; i++) PREAMBLE_SYMS[i] = (i % 2 == 0) ? 3 : -3; }
    private final Listener listener;
    public volatile float adaptRate = 0f;                        // NLMS on confirmed frames: off (on the bench recordings it pulled the timing off; 299/300 frames exact without it)

    // the audio at 48 kHz in a ring; absolute index = 48 kHz samples since start
    private static final int RING = 48000 * 8;
    private final float[] x48 = new float[RING];
    private final float[] x8 = new float[2 * HALF + 1];          // the last input samples, for the interpolator
    private long n8, n48;                                        // samples received (8 kHz) / produced (48 kHz)

    // preamble detector (Goertzel at 2400 Hz on the 8 kHz audio, 20 ms windows every 10 ms)
    private final float[] win = new float[160]; private int winFill;
    private int strong; private long preStart48 = -1, preEnd48 = -1;

    private enum State { IDLE, ACQUIRE, STREAM, PACKET }
    /** Frames in a row (40 ms each) without the stream sync before the call is given up: the symbol clock holds over that long (W6: 1/10 of a symbol in 4 s). */
    static final int MAX_LOST = 6;
    /** Feedback taps a fit on a phone's own reception keeps (the rest held at 0): fewer spread wrong decisions less (W6: 4 decoded another transmission best, 95 and 92 of 100 frames). */
    public static final int LEARN_FEEDBACK = 4;
    static final int SEARCH = 60;                                // symbols either side of the estimated preamble end
    /**
     * More symbols searched after it: the end is taken from the last 160-sample window (hop 80) that still reads as the
     * preamble, and a 40 ms preamble spans three or only two such windows depending on where the grid falls; with two the
     * estimate is a hop (48 symbols) early, and the sync sat ~82 symbols after it, outside +-60 (W6+ 2026-10-30: a recording
     * identical to one that decoded 98/100 found no sync at all). The true end is never before the last strong window.
     */
    static final int SEARCH_AFTER = SEARCH + 64;
    /**
     * Late entry (2026-10-31): a real M17 radio sends its 40 ms preamble right after key-up, and a phone's receive audio opens
     * tens to hundreds of ms after the carrier (the module's squelch, the Atom XL's USB capture), so on the air the preamble
     * and the link setup are gone and acquisition from the preamble never starts (the Armor's FW57 stream: 0 of 1125 frames on
     * the W6+, the W6Plus DMR and the Atom XL; the HackRF references decoded only because they led with a long carrier). While
     * idle the receiver therefore looks for the stream sync word every LATE_EVERY frames in one frame's worth of positions and
     * takes a position only when the next frame's sync follows exactly a frame later. The gain cannot come from a preamble:
     * the stream's RMS times a factor tried from LATE_K (the preamble/stream ratio differs per phone: W6+ 0.3, W6Plus DMR 0.5,
     * Atom XL 0.6, Armor 0.86), kept once found. The call is announced at its first confirmed frame (a false lock ends silently)
     * and the link setup is rebuilt from six LICH chunks.
     * <p>Acquisition at the start of a call (2026-11-03, eight Atom XL recordings of a real transmitter: first frame decoded 8-56,
     * the link setup never kept): the search runs every frame (every second one left half the frame starts unsearched), a sync
     * is scored also from a history primed on the symbols before it (the equalizer's feedback rebuilds the low frequencies the
     * sync needs), a frame with 3-6 LICH errors is taken when the frame before it decodes to the number one less, and once a
     * position is taken the receiver walks back through the ring, frame by frame while each earlier one decodes to the number
     * one less, to the first frame heard and to a link setup whose CRC checks (squelch 0: the preamble lost in the noise). The
     * frames before the first confirmed one are held and delivered with the announcement. Since: first frame 8 on the four
     * recordings whose audio opened mid-call (the first frame wholly heard; fn 0-7 are before the audio opened), frame 0 and
     * the link setup with its CRC on the four with squelch 0, payloads as exact as before.
     */
    static final int LATE_EVERY = 1;
    static final float[] LATE_K = { 0.25f, 0.32f, 0.4f, 0.5f, 0.63f, 0.8f, 1.0f };
    private float lateK = -1;                                    // the factor found on this receiver's last late entry
    private long lastLate48;
    private boolean latePending;                                 // entered late, not yet announced
    private boolean lateCall;                                    // this call was entered late: its gain is refined on confirmed frames
    private int pendingLimit;                                    // frames a late entry may take to confirm itself
    private final java.util.List<M17.Stream> heldFrames = new java.util.ArrayList<>(); private final java.util.List<Float> heldQuality = new java.util.ArrayList<>();
    private long lateFloor48;                                    // never search before this: where the last call ended (else its own frames lock again)
    private State state = State.IDLE;
    private java.util.List<M17.Packet> packets;
    private long next48;                                         // where the next frame's first sync symbol is expected
    private float gain = 1;
    private final float[] past = new float[FB];
    private int frames, good, lost;
    private M17.Lsf lsf;
    private final byte[][] lichChunks = new byte[6][]; private boolean lsfFromLichSent;

    public M17Receiver(float[] coef, Listener l) { if (coef.length != NCOEF) throw new IllegalArgumentException("coefficients: " + coef.length); this.coef = coef.clone(); listener = l; }
    public float[] coefficients() { return coef.clone(); }
    public boolean receiving() { return state != State.IDLE; }

    /** 16-bit little-endian mono audio at 8 kHz (as the radio delivers it). */
    public void feed(byte[] mono, int n) { for (int i = 0; i + 1 < n; i += 2) push((short) ((mono[i] & 0xFF) | (mono[i + 1] << 8))); }
    public void feed(short[] pcm, int n) { for (int i = 0; i < n; i++) push(pcm[i]); }

    /** 8 kHz sample time t (since start) -> its index in the 48 kHz stream (the interpolator lags HALF input samples). */
    static long to48(long t8) { return t8 * UP + (long) HALF * UP; }
    private void interpolate(float v) {
        System.arraycopy(x8, 1, x8, 0, x8.length - 1); x8[x8.length - 1] = v; n8++;
        for (int ph = 0; ph < UP; ph++) {                            // the 6 outputs between input times n-HALF and n-HALF+5/6
            float s = 0;
            for (int j = 0; j < x8.length; j++) { int ti = UP * j + ph; if (ti < INTERP.length) s += x8[x8.length - 1 - j] * INTERP[INTERP.length - 1 - ti]; }
            x48[(int) (n48 % RING)] = s; n48++;
        }
    }
    private void push(float v) {
        interpolate(v);
        preamble(v);
        if (state == State.ACQUIRE && n48 > preEnd48 + (SEARCH_AFTER + 192 + 4) * SPS48 + 5 * L + 60) acquire();
        if (state == State.IDLE && n48 - lastLate48 >= (long) LATE_EVERY * 192 * SPS48) { lastLate48 = n48; lateEntry(); }
        while (state == State.STREAM && n48 > next48 + 192 * SPS48 + 5 * L + 60) frame();
        while (state == State.PACKET && n48 > next48 + 192 * SPS48 + 5 * L + 60) packetFrame();
    }
    private float at(long i) { return i < 0 || i >= n48 || n48 - i >= RING ? 0f : x48[(int) (i % RING)]; }

    private void preamble(float v) {
        win[winFill++] = v;
        if (winFill < win.length) return;
        double c = 2 * Math.cos(2 * Math.PI * 2400 / 8000.0), s1 = 0, s2 = 0, e = 0;
        for (float x : win) { double s = x + c * s1 - s2; s2 = s1; s1 = s; e += x * x; }
        double p = s1 * s1 + s2 * s2 - c * s1 * s2, r = e > 1e-9 ? 2 * p / (win.length * e) : 0;
        System.arraycopy(win, 80, win, 0, 80); winFill = 80;
        long centre48 = to48(n8 - 80);                               // the window's centre in the 48 kHz stream
        if (r > 0.6) { if (strong == 0) preStart48 = centre48; strong++; preEnd48 = to48(n8 - 40); }
        else if (strong > 0) {
            if (strong >= 2 && state == State.IDLE) state = State.ACQUIRE;
            strong = 0;
        }
    }

    // ---- equalizer ---------------------------------------------------------------------------------------

    private final float[] f = new float[NCOEF];
    /** Features of the symbol centred at 48 kHz index p, with the decision history `hist` (most recent first). */
    private void features(long p, float[] hist) {
        int j = 0, n = 2 * L + 1;
        for (int m = -L; m <= L; m++) {
            float v = at(p + 5L * m) / gain;
            f[j] = v; f[j + n] = v * v * v; f[j + 2 * n] = Math.abs(v) * v; f[j + 3 * n] = v * v * v * v * v; j++;
        }
        System.arraycopy(hist, 0, f, GROUPS * n, FB); f[NCOEF - 1] = 1f;
    }
    private float out() { float y = 0; for (int i = 0; i < NCOEF; i++) y += coef[i] * f[i]; return y; }
    static float slice(float y) { return y >= 2 ? 3 : y >= 0 ? 1 : y >= -2 ? -1 : -3; }

    /** Equalize `n` symbols from p0 with the history `hist` (updated); returns the summed decision error, outputs in ys. */
    private float run(long p0, int n, float[] hist, float[] ys, int off) { return run(p0, n, hist, ys, off, null); }
    /** As above; where {@code known[k]} is non-zero that symbol, not the decision, goes into the history (the error still counts the decision). */
    private float run(long p0, int n, float[] hist, float[] ys, int off, int[] known) {
        float err = 0;
        for (int k = 0; k < n; k++) {
            features(p0 + (long) k * SPS48, hist);
            float y = out(), d = slice(y); if (ys != null) ys[off + k] = y; err += (y - d) * (y - d);
            System.arraycopy(hist, 0, hist, 1, FB - 1); hist[0] = known != null && k < known.length && known[k] != 0 ? known[k] : d;
        }
        return err;
    }
    /** A stream frame's sync symbols, for the history (the rest unknown). */
    private static final int[] STREAM_SYNC = new int[8];
    static { for (int i = 0; i < 8; i++) STREAM_SYNC[i] = M17.SYMBOL_MAP[(M17.SYNC_STR >> (14 - 2 * i)) & 3]; }

    // ---- acquisition: the LSF sync word right after the preamble -------------------------------------------

    /** AtoM17: the preamble's level is taken over at most its last PREAMBLE_GAIN_8K samples (40 ms): a long preamble
     *  (the Nokia 3310 M17 mod sends ~1 s) is still settling in the receiver's audio while it plays, and its average set
     *  the gain ~30 % low, so no link setup decoded (recording 2026-11-06). A 40 ms preamble is measured whole, as before. */
    static final int PREAMBLE_GAIN_8K = 320;
    /** AtoM17: gain factors tried around the measured one while the link setup's CRC fails (the CRC decides). */
    static final float[] ACQUIRE_K = { 1.0f, 0.89f, 1.12f, 0.79f, 1.26f, 0.71f, 1.41f };

    private void acquire() {
        double e = 0; int cnt = 0;
        long from = Math.max(preStart48 + 20 * UP, preEnd48 - 40 * UP - (long) PREAMBLE_GAIN_8K * UP);
        for (long i = from; i < preEnd48 - 40 * UP; i++) { float v = at(i); e += v * v; cnt++; }
        float base = (float) Math.sqrt(e / Math.max(1, cnt)); if (base < 1e-3f) { reset("no level"); return; }
        long bestP = -1; float[] sym = null; M17.Lsf got = null; float[] pastGot = null; float gainGot = base;
        for (float k : ACQUIRE_K) {
            gain = base * k;
            long p = lsfSync();
            if (p < 0) continue;
            for (int i = 0; i < FB; i++) past[i] = (i % 2 == 0) ? -3 : 3;
            float[] y = new float[192]; trackFrame(p, y);
            M17.Lsf l = M17.decodeLsf(y, 8);
            if (bestP < 0 || (l != null && l.crcOk)) { bestP = chosen; sym = y; got = l; pastGot = past.clone(); gainGot = gain; }
            if (l != null && l.crcOk) break;
        }
        if (bestP < 0) { reset("no LSF sync after the preamble"); return; }
        gain = gainGot; System.arraycopy(pastGot, 0, past, 0, FB); chosen = bestP; next48 = bestP + 192 * SPS48;
        lsf = got;
        lastFn = -1;
        frames = 0; good = 0; lost = 0; java.util.Arrays.fill(lichChunks, null); lsfFromLichSent = false;
        learnA = new double[NCOEF][NCOEF]; learnB = new double[NCOEF]; learnFrames = 0; learnSymbols = 0;
        emit(PREAMBLE_SYMS);
        if (lsf != null && lsf.crcOk && lsf.raw != null && lsf.raw.length >= 30) emit(M17.lsfSymbols(lsf.raw)); else emit(sym);   // re-encoded when its CRC checks
        listener.started(lsf);
        state = lsf != null && lsf.crcOk && !lsf.stream() ? State.PACKET : State.STREAM;
        if (state == State.PACKET) packets = new java.util.ArrayList<>();
    }

    /** The LSF sync word's position after the preamble at the current gain, or -1. */
    private long lsfSync() {
        int[] sync = new int[8]; for (int i = 0; i < 8; i++) sync[i] = M17.SYMBOL_MAP[(M17.SYNC_LSF >> (14 - 2 * i)) & 3];
        long bestP = -1; float bestScore = Float.MAX_VALUE; float[] h = new float[FB]; float[] ys = new float[8];
        for (long p = preEnd48 - SEARCH * SPS48; p <= preEnd48 + SEARCH_AFTER * SPS48; p++) {
            for (int i = 0; i < FB; i++) h[i] = (i % 2 == 0) ? -3 : 3;      // the preamble: ... +3 -3, the last one -3
            float err = run(p, 8, h, ys, 0); int miss = 0;
            for (int i = 0; i < 8; i++) if (slice(ys[i]) != sync[i]) miss++;
            float score = miss * 100 + err;
            if (score < bestScore) { bestScore = score; bestP = p; }
        }
        return bestScore < 200 ? bestP : -1;
    }

    /** One frame at p0 with timing re-chosen among p0-2..p0+2 (48 kHz samples); returns the chosen start, symbols in sym. */
    private long chosen;
    private float[] frameFeat;                                   // the chosen frame's features, for adaptation
    private float trackFrame(long p0, float[] sym) { return trackFrame(p0, sym, null); }
    private float trackFrame(long p0, float[] sym, int[] known) {
        float best = Float.MAX_VALUE; long bp = p0; float[] bh = null, bys = null;
        for (long p = p0 - 2; p <= p0 + 2; p++) {
            float[] h = past.clone(), ys = new float[192]; float err = run(p, 192, h, ys, 0, known);
            if (err < best) { best = err; bp = p; bh = h; bys = ys; }
        }
        System.arraycopy(bys, 0, sym, 0, 192); System.arraycopy(bh, 0, past, 0, FB); chosen = bp;
        next48 = bp + 192 * SPS48;
        return best / 192;
    }

    private void frame() {
        long p0 = next48; float[] before = past.clone();
        float[] sym = new float[192]; float mse = trackFrame(p0, sym, STREAM_SYNC);
        int sm = 0, em = 0;
        for (int i = 0; i < 8; i++) { int s = (int) slice(sym[i]); if (s == M17.SYMBOL_MAP[(M17.SYNC_STR >> (14 - 2 * i)) & 3]) sm++; if (s == M17.EOT_SYMBOLS[i]) em++; }
        if (em >= 7 && em > sm) { emitEot(); end("end of transmission"); return; }
        if (em >= 7 && em > sm && latePending) { reset("late entry: end"); return; }
        M17.Stream s = M17.decodeStream(sym, 8);
        int expect = lastFn >= 0 ? (lastFn + 1) & 0x7FFF : -1;
        if (sm < 6) {
            // a sync with three or more misses: the frame is still taken when it decodes to the number that follows the last one
            // (15 bits) with a good Viterbi metric; one bad sync no longer throws away a frame the listener can hear
            boolean inStep = !latePending && expect >= 0 && (s.fn & 0x7FFF) == expect && s.metric < -180;
            if (!inStep) {
                emit(sym);
                if (expect >= 0) lastFn = expect;
                if (++lost >= (latePending ? 2 : MAX_LOST)) { if (latePending) reset("late entry: no frames"); else end("signal lost"); } return;
            }
        }
        lost = 0; frames++;
        float quality = Math.max(0, Math.min(1, 1 - mse / 0.5f));
        boolean confirmed = s.lich != null && s.lichErrors <= 2 && s.metric < -150;
        int[] exact = s.lich != null ? M17.streamSymbolsFromLich(s.lich, s.fn, s.payload) : null;
        // a frame its LICH does not confirm is equalized again with the frame as decoded (re-encoded) in the feedback instead of
        // the symbol decisions: a burst of wrong decisions inside the frame then no longer feeds itself (the Atom XL's frames
        // with long runs of one symbol, e.g. LICH chunk 0 of a call to @ALL, lost payloads that way); kept when the
        // Viterbi metric improves, twice at most
        for (int it = 0; it < 2 && !confirmed && exact != null; it++) {
            float[] h = before.clone(), sym2 = new float[192];
            run(chosen, 192, h, sym2, 0, exact);
            M17.Stream s2 = M17.decodeStream(sym2, 8);
            if (s2.metric >= s.metric) break;
            s = s2; sym = sym2; confirmed = s.lich != null && s.lichErrors <= 2 && s.metric < -150; exact = s.lich != null ? M17.streamSymbolsFromLich(s.lich, s.fn, s.payload) : null;
        }
        int mism = 999; if (exact != null) { mism = 0; for (int i = 8; i < 192; i++) if ((int) slice(sym[i]) != exact[i]) mism++; }
        stats(sym, mse, sm, exact, s, confirmed, false);
        // consistent: the frame re-encoded from its decoded LICH and payload matches the received symbols but for a few (a
        // random or wrongly decoded frame differs in about 140 of 184; confirmed frames 0-16, unconfirmed ones mostly 5-28): its
        // re-encoding is then the history for the next frame even when the LICH's Golay words needed more than two corrections
        boolean consistent = confirmed || mism <= MAX_REENCODED_MISSES;
        if (consistent && exact != null && exact.length == 192) emit(exact); else emit(sym);
        if (latePending) {
            if (!confirmed) {                                                  // held until a frame confirms the entry (a false lock ends silently)
                java.util.Arrays.fill(past, 0f); if (frames >= pendingLimit) { reset("late entry: not confirmed"); return; }
                heldFrames.add(s); heldQuality.add(quality); return;
            }
            latePending = false; listener.started(new M17.Lsf(new byte[30], false));   // no link setup yet: it comes from the LICH
            for (int i = 0; i < heldFrames.size(); i++) listener.frame(heldFrames.get(i), heldQuality.get(i));
            heldFrames.clear(); heldQuality.clear();
        }
        if (confirmed) { good++; adapt(chosen, before, s); collectLich(s); }
        if (consistent && exact != null && exact.length == 192) {
            if (confirmed) learn(chosen, before, exact);
            for (int i = 0; i < FB; i++) past[i] = exact[191 - i];       // the frame as sent: the history the next frame needs
            if (lateCall) {                                                // the gain came from one frame: scale it toward what makes the output match the frame as sent
                double yx = 0, xx = 0; for (int i = 8; i < 192; i++) { yx += sym[i] * exact[i]; xx += (double) exact[i] * exact[i]; }
                double a = xx > 0 ? yx / xx : 1; if (a > 0.5 && a < 2) gain *= (float) Math.sqrt(a);
            }
        } else java.util.Arrays.fill(past, 0f);                              // unconfirmed: feeding its decisions back spreads its errors; 0 = unknown
        lastFn = consistent || expect < 0 ? s.fn & 0x7FFF : expect;

        listener.frame(s, quality);
        if (s.last() && consistent) end("last frame");                    // an unconfirmed frame's end bit may be a bit error
    }
    /** Symbols of 184 a frame may differ from its own re-encoding by and still give the next frame its history (see frame()). */
    static final int MAX_REENCODED_MISSES = 24;
    private int lastFn = -1;

    /** A packet frame. Voice frames never come here: this state is entered only from a packet-mode LSF. */
    private void packetFrame() {
        long p0 = next48; float[] sym = new float[192]; float pmse = trackFrame(p0, sym);
        int pm = 0, em = 0;
        for (int i = 0; i < 8; i++) {
            int s = (int) slice(sym[i]);
            if (s == M17.SYMBOL_MAP[(M17.SYNC_PKT >> (14 - 2 * i)) & 3]) pm++;
            if (s == M17.EOT_SYMBOLS[i]) em++;
        }
        if (em >= 7 && em > pm) { emitEot(); end("end of transmission"); return; }
        M17.Packet dec = M17.decodePacket(sym, 8);
        int[] pex = toInts(M17.packetSymbols(dec.chunk, dec.eof, dec.counter));
        if (pm >= 6) stats(sym, pmse, pm, pex, null, false, true);
        emit(M17.packetSymbols(dec.chunk, dec.eof, dec.counter));       // re-encoded from this receiver's decode: clean for libm17rx
        if (pm < 6) { if (++lost >= 2) { end("signal lost"); return; } return; }
        lost = 0; frames++;
        M17.Packet p = M17.decodePacket(sym, 8);
        if (packets != null) packets.add(p);
        if (p.eof) {
            byte[] body = M17.assemble(packets);
            if (body != null) listener.packet(lsf, body); else listener.framesLost();
            end("last frame");
        }
    }

    /** NLMS on a confirmed frame: re-encode it, then nudge the coefficients toward the exact symbols. */
    private void adapt(long p0, float[] hist0, M17.Stream s) {
        if (adaptRate <= 0) return;
        int[] exact = M17.streamSymbolsFromLich(s.lich, s.fn, s.payload);
        float[] h = hist0.clone();
        for (int k = 0; k < 192; k++) {
            features(p0 + (long) k * SPS48, h);
            float y = out(), e = exact[k] - y, nrm = 1e-3f;
            for (float v : f) nrm += v * v;
            float mu = adaptRate * e / nrm;
            for (int i = 0; i < NCOEF; i++) coef[i] += mu * f[i];
            System.arraycopy(h, 0, h, 1, FB - 1); h[0] = exact[k];
        }
    }

    private void collectLich(M17.Stream s) {
        int c = s.lichCount(); if (c < 0 || c > 5) return;
        lichChunks[c] = java.util.Arrays.copyOf(s.lich, 5);
        if (lsfFromLichSent || (lsf != null && lsf.crcOk)) return;
        for (byte[] ch : lichChunks) if (ch == null) return;
        byte[] raw = new byte[30]; for (int i = 0; i < 6; i++) System.arraycopy(lichChunks[i], 0, raw, 5 * i, 5);
        M17.Lsf l = new M17.Lsf(raw, M17.crc16(raw, 28) == (((raw[28] & 0xFF) << 8) | (raw[29] & 0xFF)));
        if (l.crcOk) { lsfFromLichSent = true; lsf = l; listener.lsfFromLich(l); }
    }

    private static int[] toInts(Object a) {
        if (a instanceof int[]) return (int[]) a;
        if (a instanceof float[]) { float[] f = (float[]) a; int[] r = new int[f.length]; for (int i = 0; i < f.length; i++) r[i] = Math.round(f[i]); return r; }
        return null;
    }
    private void emitEot() { float[] e = new float[192]; for (int i = 0; i < 192; i++) e[i] = M17.EOT_SYMBOLS[i % M17.EOT_SYMBOLS.length]; emit(e); }
    private void end(String why) { listener.ended(frames, good, why); reset(null); }
    private void reset(String why) { heldFrames.clear(); heldQuality.clear(); state = State.IDLE; strong = 0; preStart48 = preEnd48 = -1; latePending = false; lateCall = false; lateFloor48 = Math.max(lateFloor48, next48); }

    /** Normalized correlation of the equalizer's linear taps' output at the 8 sync symbols from p with the sync pattern (gain-free). */
    private float linCorr(long p, int[] sync) {
        double ys = 0, yy = 0;
        for (int k = 0; k < 8; k++) {
            long c = p + (long) k * SPS48; double y = 0;
            for (int m = -L; m <= L; m++) y += coef[m + L] * at(c + 5L * m);
            ys += y * sync[k]; yy += y * y;
        }
        return yy > 0 ? (float) (ys / Math.sqrt(yy * 8 * 5)) : 0;          // 8 symbols, mean square of the sync pattern 5
    }
    /** Sync misses (x100) plus decision error of the 8 sync symbols at p with the known sync fed back, from an unknown (zero) history or one primed on the symbols before p, the better. */
    private float syncScore(long p, int[] sync) {
        float s0 = syncScore(p, sync, new float[FB]);
        return s0 < 100 ? s0 : Math.min(s0, syncScore(p, sync, primed(p)));
    }
    private float syncScore(long p, int[] sync, float[] h) {
        float[] ys = new float[8]; float err = run(p, 8, h, ys, 0, sync); int miss = 0;   // the known sync goes into the history, as in frame()
        for (int i = 0; i < 8; i++) if (slice(ys[i]) != sync[i]) miss++;
        return miss * 100 + err;
    }
    private void lateEntry() {
        long frame48 = 192L * SPS48, newest = n48 - (frame48 + 8 * SPS48 + 5 * L + 60), oldest = Math.max(newest - frame48, lateFloor48);
        if (newest <= oldest) return;
        if (oldest - 5 * L < 0 || n48 - oldest + 5 * L >= RING) return;
        double e = 0; int cnt = 0;
        for (long i = oldest; i < newest + frame48; i += 3) { float v = at(i); e += v * v; cnt++; }
        float rms = (float) Math.sqrt(e / Math.max(1, cnt)); if (rms < 1f) return;
        int[] sync = STREAM_SYNC;
        // 0: a cheap pass that does not depend on the gain: the equalizer's linear taps alone, correlated with the sync pattern
        // (normalized, so the scale drops out); only the PRE best positions go on to the gain-dependent checks
        final int PRE = 16; long[] pp = new long[PRE]; float[] pc = new float[PRE]; java.util.Arrays.fill(pc, -1f);
        for (long p = oldest; p < newest; p += 4) {
            float c = linCorr(p, sync); int w = 0; for (int i = 1; i < PRE; i++) if (pc[i] < pc[w]) w = i;
            if (c > pc[w]) { pc[w] = c; pp[w] = p; }
        }
        // 1: a shortlist of (position, factor) whose sync comes within two misses from an unknown history; one entry per
        // position (the same position at two factors, or two grid points a sample apart, would crowd the true one out: a
        // repeated payload, such as Codec 2 silence, puts a sync-like pattern at the same place in every frame)
        float[] ks = lateK > 0 ? new float[] { lateK / 1.26f, lateK, lateK * 1.26f } : LATE_K;   // once found, only its neighbours
        final int SHORT = 8; long[] cp = new long[SHORT]; float[] ck = new float[SHORT], cs = new float[SHORT]; java.util.Arrays.fill(cs, Float.MAX_VALUE);
        for (float k : ks) {
            gain = rms * k;
            for (int j = 0; j < PRE; j++) {
                if (pc[j] < 0) continue; long p = pp[j];
                float sc = syncScore(p, sync); if (sc >= 300) continue;
                shortlist(cp, ck, cs, p, k, sc);
            }
        }
        // the grid steps 4: each shortlisted position to its best within +-2 (the decode below needs the timing within a sample or two)
        for (int i = 0; i < SHORT; i++) {
            if (cs[i] == Float.MAX_VALUE) continue;
            gain = rms * ck[i]; long bp = cp[i]; float bsc = cs[i];
            for (long q = cp[i] - 2; q <= cp[i] + 2; q++) { if (q == cp[i]) continue; float sc = syncScore(q, sync); if (sc < bsc) { bsc = sc; bp = q; } }
            cp[i] = bp; cs[i] = bsc;
        }
        // 2: decode the frame at each, as the receiver would (sync fed back), at every factor; the next frame's sync must follow
        // within one miss, and the frame must prove itself: its LICH passes as frame() requires it, or (the Atom XL's frames
        // often carry 3-6 LICH bit errors from an unknown history while the payload decodes) its LICH decodes and the frame
        // before it decodes to the frame number one less (15 bits: noise does not match). The best Viterbi metric picks the
        // position and the factor.
        long bestP = -1; float best = Float.MAX_VALUE, bk = 0, rms2 = rms; int bestFn = 0;
        float[] ys = new float[192];
        Integer[] order = new Integer[SHORT]; for (int i = 0; i < SHORT; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Float.compare(cs[x], cs[y]));
        for (int oi = 0; oi < SHORT; oi++) {                                 // each distinct position, at its own factor and the two beside it
            int i = order[oi]; if (cs[i] == Float.MAX_VALUE) break;
            if (cs[i] >= 200) break;                                        // more than one sync miss: not worth a decode
            boolean seen = false; for (int oj = 0; oj < oi; oj++) if (Math.abs(cp[order[oj]] - cp[i]) <= 2) seen = true;
            if (seen) continue;
            double e2 = 0; int c2 = 0;                                       // the level from these two frames (the search window can hold the quiet before the stream)
            for (long q = cp[i]; q < cp[i] + 2 * frame48; q += 3) { float v = at(q); e2 += v * v; c2++; }
            float r2 = (float) Math.sqrt(e2 / Math.max(1, c2));
            for (float k : new float[] { ck[i] / 1.26f, ck[i], ck[i] * 1.26f }) {
                gain = r2 * k; float[] h = new float[FB];
                run(cp[i], 192, h, ys, 0, sync);
                M17.Stream st = M17.decodeStream(ys, 8);
                if (st.lich == null || st.lichErrors > 6 || st.metric >= -150 || st.metric >= best) continue;
                run(cp[i] + frame48, 8, h, ys, 0, null); int miss = 0;
                for (int j = 0; j < 8; j++) if (slice(ys[j]) != sync[j]) miss++;
                if (miss > 1) continue;
                int fn = st.fn & 0x7FFF;
                if (st.lichErrors > 2 && (fn == 0 || frameAt(cp[i] - frame48, fn - 1, ys) < 0)) continue;
                best = st.metric; bestP = cp[i]; bk = k; rms2 = r2; bestFn = fn;
            }
        }
        if (bestP < 0) return;
        lateK = bk; gain = rms2 * bk;
        // 3: the frames before it are still in the ring: walk back while each earlier frame decodes to the number one less, to
        // the first one after the receive audio opened, or to the link setup when it is there (a squelch open before the call),
        // so the call starts at its first frame heard rather than where the search first caught it
        long first = bestP; int back = 0; M17.Lsf found = null;
        for (int want = bestFn - 1; back < MAX_BACK; want--) {
            long c = first - frame48;
            if (c - (PRIME + 2) * SPS48 - 5 * L < Math.max(0, lateFloor48) || n48 - c + (PRIME + 2) * SPS48 + 5 * L >= RING) break;
            if (want < 0) { found = lsfAt(c); break; }                     // before frame 0: the link setup
            long bp = frameAt(c, want, ys);
            if (bp < 0) break;
            first = bp; back++;
        }
        java.util.Arrays.fill(past, 0f);
        lastFn = -1;
        frames = 0; good = 0; lost = 0; java.util.Arrays.fill(lichChunks, null); lsfFromLichSent = false; lsf = null;
        learnA = new double[NCOEF][NCOEF]; learnB = new double[NCOEF]; learnFrames = 0; learnSymbols = 0;
        heldFrames.clear(); heldQuality.clear(); pendingLimit = back + 4;
        next48 = first; latePending = true; lateCall = true; state = State.STREAM;
        if (found != null) {                                                 // the link setup checked: it confirms the call
            lsf = found; latePending = false;
            System.arraycopy(lsfTail, 0, past, 0, FB);
            listener.started(found);
        }
    }
    /**
     * The stream frame within +-2 samples of {@code c} that decodes to frame number {@code want} (from an unknown history or
     * one primed on the symbols before it, the better Viterbi metric), or -1. The search window must hold it: callers check.
     */
    private long frameAt(long c, int want, float[] ys) {
        if (c - (PRIME + 2) * SPS48 - 5 * L < Math.max(0, lateFloor48) || n48 - c + (PRIME + 2) * SPS48 + 5 * L >= RING) return -1;
        long bp = -1; float bm = Float.MAX_VALUE;
        for (long q = c - 2; q <= c + 2; q++)
            for (int hi = 0; hi < 2; hi++) {
                float[] h = hi == 0 ? new float[FB] : primed(q);
                run(q, 192, h, ys, 0, STREAM_SYNC); M17.Stream t = M17.decodeStream(ys, 8);
                if ((t.fn & 0x7FFF) == want && t.metric < bm) { bm = t.metric; bp = q; }
            }
        return bp;
    }
    /** Symbols before a position the equalizer runs over (from an unknown history) to give that position a history of its decisions. */
    static final int PRIME = 32;
    private float[] primed(long p) {
        float[] h = new float[FB]; long p0 = p - (long) PRIME * SPS48;
        double e = 0; int cnt = 0; for (long i = p0; i < p; i += 3) { float v = at(i); e += v * v; cnt++; }
        if (Math.sqrt(e / Math.max(1, cnt)) < 0.3 * gain) return h;          // quiet before it (the receive audio just opened): nothing to decide on
        run(p0, PRIME, h, null, 0); return h;
    }
    /** Frames the late entry walks back from where it caught the call: 0.8 s (the voice output buffers about a second). */
    static final int MAX_BACK = 20;
    private final float[] lsfTail = new float[FB];
    /** A stream link setup checked by its CRC within +-2 samples of {@code c} (decoded after the preamble), else null; its last symbols go to lsfTail (the history for the first frame). */
    private M17.Lsf lsfAt(long c) {
        int[] sync = new int[8]; for (int i = 0; i < 8; i++) sync[i] = M17.SYMBOL_MAP[(M17.SYNC_LSF >> (14 - 2 * i)) & 3];
        float[] ys = new float[192], h = new float[FB];
        for (long q = c - 2; q <= c + 2; q++) {
            for (int i = 0; i < FB; i++) h[i] = (i % 2 == 0) ? -3 : 3;          // the preamble before it
            run(q, 192, h, ys, 0, sync);
            M17.Lsf l = M17.decodeLsf(ys, 8);
            if (!l.crcOk || !l.stream()) continue;
            int[] exact = M17.lsfSymbols(l.raw); for (int i = 0; i < FB; i++) lsfTail[i] = exact[191 - i];
            return l;
        }
        return null;
    }
    /** Adds (p, k, sc) to the shortlist: replaces an entry within 4 samples of p when better, else the worst entry. */
    private static void shortlist(long[] cp, float[] ck, float[] cs, long p, float k, float sc) {
        int w = -1;
        for (int i = 0; i < cs.length; i++) if (cs[i] != Float.MAX_VALUE && Math.abs(cp[i] - p) <= 4) { w = i; break; }
        if (w < 0) { w = 0; for (int i = 1; i < cs.length; i++) if (cs[i] > cs[w]) w = i; }
        if (sc < cs[w]) { cs[w] = sc; cp[w] = p; ck[w] = k; }
    }

    // ---- learning from the frames the error correction confirmed (self-calibration) ---------------------------

    private double[][] learnA; private double[] learnB; private int learnFrames, learnSymbols;
    /** Adds a confirmed frame to this call's normal equations: at the timing the receiver chose, with the history it had, and the frame as sent. */
    private void learn(long p0, float[] hist0, int[] exact) {
        if (learnA == null) return;
        float[] h = hist0.clone();
        for (int k = 0; k < 192; k++) {
            features(p0 + (long) k * SPS48, h);
            for (int i = 0; i < NCOEF; i++) { learnB[i] += f[i] * exact[k]; for (int j = i; j < NCOEF; j++) learnA[i][j] += f[i] * f[j]; }
            System.arraycopy(h, 0, h, 1, FB - 1); h[0] = exact[k];
        }
        learnFrames++; learnSymbols += 192;
    }
    /** Confirmed frames the current (or the call just ended) has added. */
    public int learnedFrames() { return learnFrames; }
    /** A table fitted on this call's confirmed frames, the feedback taps past {@code feedback} held at 0; null with fewer than {@code minFrames}. */
    public float[] learnedTable(int minFrames, int feedback) {
        if (learnA == null || learnFrames < minFrames) return null;
        double[][] A = new double[NCOEF][]; for (int i = 0; i < NCOEF; i++) A[i] = learnA[i].clone();
        double[] c = solveNormal(A, learnB.clone(), learnSymbols, feedback); if (c == null) return null;
        float[] out = new float[NCOEF]; for (int i = 0; i < NCOEF; i++) out[i] = (float) c[i]; return out;
    }
    /** Solves upper-triangle normal equations (in place): mirrored, lightly regularised, feedback taps past {@code feedback} held at 0. */
    static double[] solveNormal(double[][] A, double[] b, int used, int feedback) {
        for (int i = 0; i < NCOEF; i++) { A[i][i] += 1e-6 * Math.max(1, used); for (int j = 0; j < i; j++) A[i][j] = A[j][i]; }
        int fb0 = GROUPS * (2 * L + 1);
        for (int i = fb0 + Math.max(0, feedback); i < fb0 + FB; i++) { java.util.Arrays.fill(A[i], 0); for (int r = 0; r < NCOEF; r++) A[r][i] = 0; A[i][i] = 1; b[i] = 0; }
        return solve(A, b);
    }

    /** How a table does on a recording: {confirmed frames, frames, link setups with a good CRC}. */
    public static int[] score(short[] audio, float[] coef) {
        final int[] r = new int[3];
        M17Receiver rx = new M17Receiver(coef, new Listener() {
            public void started(M17.Lsf l) { if (l.crcOk) r[2]++; }
            public void frame(M17.Stream s, float q) { }
            public void lsfFromLich(M17.Lsf l) { }
            public void ended(int f, int g, String w) { r[0] += g; r[1] += f; }
        });
        rx.feed(audio, audio.length); rx.feed(new short[8000], 8000);      // a second of silence ends a call still open
        return r;
    }

    // ---- calibration (bench: a known transmission recorded through this radio) ---------------------------------

    /**
     * Least-squares fit of the coefficients on a recorded transmission whose symbols are known: {@code start8k} is roughly
     * where symbol 0 (the first preamble symbol) is in the recording; the exact delay is searched, and so is the recording's
     * clock offset against the transmitter (the Atom XL's bridge records about 127 ppm fast: 2.4 symbols over a 4 s
     * transmission, which a fit at the nominal rate cannot follow). Returns {coefficients..., mse, clock offset in ppm}.
     */
    public static float[] calibrate(short[] audio, int start8k, int[] symbols) { return calibrate(audio, start8k, symbols, FB); }
    /** As above with only the first {@code feedback} feedback taps fitted; a symbol of 0 is unknown (not fitted, 0 in the history). */
    public static float[] calibrate(short[] audio, int start8k, int[] symbols, int feedback) {
        M17Receiver r = new M17Receiver(new float[NCOEF], new Listener() {
            public void started(M17.Lsf l) { } public void frame(M17.Stream s, float q) { } public void lsfFromLich(M17.Lsf l) { } public void ended(int a, int b, String w) { } });
        int from = Math.max(0, start8k - 4000), to = Math.min(audio.length, start8k + symbols.length * 8000 / 4800 + 4000);
        r.feedRaw(java.util.Arrays.copyOfRange(audio, from, to));      // just this transmission: the ring holds 8 s
        long base = to48(start8k - from);
        int[] lead = java.util.Arrays.copyOf(symbols, symbols.length);  // the delay from the first half second, where a clock offset has not moved anything yet
        for (int k = CLOCK_LEAD; k < lead.length; k++) lead[k] = 0;
        double[] best = null; double bestE = Double.MAX_VALUE; int bestD = 0;
        for (int d = -120; d <= 120; d += 2) {
            double[] c = r.fit(base + d, lead, feedback, 0); if (c == null) continue;
            if (c[NCOEF] < bestE) { bestE = c[NCOEF]; bestD = d; }
        }
        bestE = Double.MAX_VALUE; double bestPpm = 0;
        for (int step : new int[] { 25, 5 }) {                          // then the clock offset on the whole transmission, coarse and fine
            double centre = bestPpm; int dc = bestD;
            for (double ppm = centre - (step == 25 ? 400 : 20); ppm <= centre + (step == 25 ? 400 : 20); ppm += step)
                for (int d = dc - (step == 25 ? 0 : 4); d <= dc + (step == 25 ? 0 : 4); d += 2) {
                    double[] c = r.fit(base + d, symbols, feedback, ppm); if (c == null) continue;
                    if (c[NCOEF] < bestE) { bestE = c[NCOEF]; best = c; bestPpm = ppm; bestD = d; }
                }
        }
        float[] out = new float[NCOEF + 2]; for (int i = 0; i <= NCOEF; i++) out[i] = (float) best[i]; out[NCOEF + 1] = (float) bestPpm; return out;
    }
    static final int CLOCK_LEAD = 2400;                                 // symbols (0.5 s) used to find the delay before the clock offset
    private void feedRaw(short[] a) { state = State.IDLE; for (short v : a) interpolate(v); }
    private double[] fit(long p0, int[] sym, int feedback, double ppm) {
        final double sps = SPS48 * (1 + ppm * 1e-6);
        if (p0 + Math.round(sym.length * sps) + 5 * L >= n48 || n48 - p0 > RING) return null;
        double e = 0; int cnt = 0;
        for (long i = p0 + 20 * SPS48; i < p0 + 172 * SPS48; i++) { float v = at(i); e += v * v; cnt++; }
        gain = (float) Math.sqrt(e / cnt);
        double[][] A = new double[NCOEF][NCOEF]; double[] b = new double[NCOEF]; float[] h = new float[FB];
        int used = 0;                                                    // a symbol of 0 is unknown: not fitted, 0 in the history
        for (int k = 0; k < sym.length; k++) {
            features(p0 + Math.round(k * sps), h);
            if (sym[k] != 0) { used++; for (int i = 0; i < NCOEF; i++) { b[i] += f[i] * sym[k]; for (int j = i; j < NCOEF; j++) A[i][j] += f[i] * f[j]; } }
            System.arraycopy(h, 0, h, 1, FB - 1); h[0] = sym[k];
        }
        double[] c = solveNormal(A, b, used, feedback); if (c == null) return null;
        double err = 0; java.util.Arrays.fill(h, 0);
        for (int k = 0; k < sym.length; k++) { features(p0 + Math.round(k * sps), h); double y = 0; for (int i = 0; i < NCOEF; i++) y += c[i] * f[i]; if (sym[k] != 0) err += (y - sym[k]) * (y - sym[k]); System.arraycopy(h, 0, h, 1, FB - 1); h[0] = sym[k]; }
        double[] out = java.util.Arrays.copyOf(c, NCOEF + 1); out[NCOEF] = err / Math.max(1, used); return out;
    }
    private static double[] solve(double[][] A, double[] b) {
        int n = b.length; double[][] M = new double[n][]; for (int i = 0; i < n; i++) { M[i] = java.util.Arrays.copyOf(A[i], n + 1); M[i][n] = b[i]; }
        for (int c = 0; c < n; c++) {
            int p = c; for (int r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r;
            if (Math.abs(M[p][c]) < 1e-12) return null;
            double[] t = M[c]; M[c] = M[p]; M[p] = t;
            for (int r = c + 1; r < n; r++) { double k = M[r][c] / M[c][c]; if (k == 0) continue; for (int j = c; j <= n; j++) M[r][j] -= k * M[c][j]; }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) { double s = M[r][n]; for (int j = r + 1; j < n; j++) s -= M[r][j] * x[j]; x[r] = s / M[r][r]; }
        return x;
    }
}
