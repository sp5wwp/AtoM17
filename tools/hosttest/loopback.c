/*
 * Host loopback test: m17tx symbols -> RRC (alpha 0.5, 10 sps at 48 kHz) -> FM deviation in Hz -> resample to
 * 32 kHz (the Atom XL's USB capture rate) -> m17rx_process_fm. Checks voice, SMS, AES, scrambler and signatures.
 * Test 9 takes the app's own receive path instead: the equaliser's symbols re-synthesised at 48 kHz (m17resynth).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "m17tx.h"
#include "m17rx.h"
#include "m17.h"
#include "m17agc.h"
#include "m17resynth.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL: "); printf(__VA_ARGS__); printf("\n"); } else { printf("ok:   "); printf(__VA_ARGS__); printf("\n"); } } while (0)

/* symbols -> FM at 32 kHz; noise_hz adds gaussian-ish discriminator noise, invert flips polarity */
static int lead_noise_sym; static float lead_noise_hz;
static float* modulate(const int8_t* sym, int n, int* out_n, float dev_outer, float noise_hz, float scale)
{
    int sps = 10, L = lead_noise_sym, n48 = (n + 20 + L) * sps;
    float* up = calloc((size_t)n48, sizeof(float));
    for (int i = 0; i < n; i++) up[(i + 10 + L) * sps] = sym[i] * sps;   /* lead-in */
    float* sh = calloc((size_t)n48, sizeof(float));
    for (int i = 0; i < n48; i++)
    {
        float acc = 0;
        for (int k = 0; k < 81; k++) { int j = i - k; if (j >= 0) acc += up[j] * rrc_taps_10[k]; }
        sh[i] = acc;
    }
    /* normalise: ideal +3 peak ~ dev_outer */
    float gain = dev_outer / 3.0f / (float)sqrt(10.0);   /* rrc_taps_10 sum of squares ~ 1/sps scaling */
    /* measure the actual gain from the shaped signal at symbol instants */
    double num = 0, den = 0;
    for (int i = 0; i < n; i++) { int at = (i + 10 + L) * sps + 40; if (at < n48) { num += sh[at] * sym[i]; den += sym[i] * sym[i]; } }
    gain = (float)(dev_outer / 3.0 / (num / den));
    int n32 = n48 * 2 / 3;
    float* o = malloc(sizeof(float) * (size_t)n32);
    unsigned s = 12345;
    for (int i = 0; i < n32; i++)
    {
        double t = i * 1.5; int a = (int)t; double f = t - a;
        float v = (a + 1 < n48) ? (float)(sh[a] * (1 - f) + sh[a + 1] * f) : 0;
        float nz = 0;
        if (noise_hz > 0) { for (int k = 0; k < 4; k++) { s = s * 1103515245 + 12345; nz += ((s >> 8) & 0xFFFF) / 65535.0f - 0.5f; } nz *= noise_hz; }
        if (i < L * sps * 2 / 3) { float w = 0; for (int k = 0; k < 4; k++) { s = s * 1103515245 + 12345; w += ((s >> 8) & 0xFFFF) / 65535.0f - 0.5f; } nz += w * lead_noise_hz; }
        o[i] = (v * gain + nz) * scale;
    }
    free(up); free(sh);
    *out_n = n32;
    return o;
}

typedef struct { int starts, ends, sig_state, packets; char src[12], dst[12], text[64], sms[900]; int dec; int frames; int audio; float dev; int enc; int pkt_meta, pkt_pos; double lat, lon; char sig_signer[12], sig_dst[12]; unsigned sig_time; int sig_not_orig; } result_t;

static int use_agc;
static result_t run(m17rx_t* rx, const int8_t* sym, int n, float noise, float scale)
{
    m17agc_t agc; m17agc_init(&agc);
    result_t r; memset(&r, 0, sizeof(r));
    int fn = 0;
    float* fm = modulate(sym, n, &fn, 2400.0f, noise, scale);
    /* feed with trailing silence so the end is detected */
    m17rx_set_input_rate(rx, 32000.0);
    for (int off = 0; off < fn; off += 1280)
    {
        int k = fn - off < 1280 ? fn - off : 1280;
        if (use_agc) m17agc_run(&agc, fm + off, k);
        m17rx_process_fm(rx, fm + off, k);
        int16_t pcm[4096]; int a; while ((a = m17rx_read_audio(rx, pcm, 4096)) > 0) r.audio += a;
        m17rx_event_t ev;
        while (m17rx_next_event(rx, &ev))
        {
            if (ev.kind == M17RX_EV_STREAM_START) { r.starts++; strcpy(r.src, ev.link.src); strcpy(r.dst, ev.link.dst); r.enc = ev.link.enc_type; r.dec = ev.link.decrypt; }
            if (ev.kind == M17RX_EV_LINK_UPDATE && ev.link.meta_text_complete) strcpy(r.text, ev.link.meta_text);
            if (ev.kind == M17RX_EV_STREAM_END) { r.ends++; r.sig_state = ev.sig_state; r.frames = ev.frames; if (ev.link.meta_text[0]) strcpy(r.text, ev.link.meta_text);
                                              strcpy(r.sig_signer, ev.sig_signer); strcpy(r.sig_dst, ev.sig_dst); r.sig_time = ev.sig_time; r.sig_not_orig = ev.sig_not_originator; }
            if (ev.kind == M17RX_EV_PACKET) { r.packets++; strcpy(r.src, ev.link.src); strcpy(r.dst, ev.link.dst); strcpy(r.sms, ev.sms);
                                              r.pkt_meta = ev.link.meta_kind; r.pkt_pos = ev.link.gnss_valid_pos; r.lat = ev.link.gnss_lat; r.lon = ev.link.gnss_lon; }
        }
        m17rx_status_t st; m17rx_get_status(rx, &st); if (st.meas_valid) r.dev = st.deviation_hz;
    }
    float zero[3200] = {0};
    for (int i = 0; i < 20; i++)
    {
        m17rx_process_fm(rx, zero, 3200);
        int16_t pcm[4096]; int a; while ((a = m17rx_read_audio(rx, pcm, 4096)) > 0) r.audio += a;
        m17rx_event_t ev;
        while (m17rx_next_event(rx, &ev))
        {
            if (ev.kind == M17RX_EV_STREAM_END) { r.ends++; r.sig_state = ev.sig_state; r.frames = ev.frames; if (ev.link.meta_text[0]) strcpy(r.text, ev.link.meta_text);
                                              strcpy(r.sig_signer, ev.sig_signer); strcpy(r.sig_dst, ev.sig_dst); r.sig_time = ev.sig_time; r.sig_not_orig = ev.sig_not_originator; }
            if (ev.kind == M17RX_EV_PACKET) { r.packets++; strcpy(r.sms, ev.sms); }
        }
    }
    free(fm);
    return r;
}

static int8_t symbuf[200 * 192];

static int voice_tx(m17tx_t* tx, int frames)
{
    int n = m17tx_stream_begin(tx, symbuf);
    int16_t pcm[320];
    for (int f = 0; f < frames; f++)
    {
        for (int i = 0; i < 320; i++) pcm[i] = (int16_t)(8000 * sin(2 * M_PI * 440 * (f * 320 + i) / 8000.0));
        n += m17tx_stream_voice(tx, pcm, symbuf + n);
    }
    n += m17tx_stream_end(tx, NULL, symbuf + n);
    return n;
}

int main(void)
{
    m17tx_t* tx = m17tx_create();
    m17rx_t* rx = m17rx_create();
    m17tx_config_t c; memset(&c, 0, sizeof(c));

    /* 1. plain voice with multi-block META text */
    strcpy(c.src, "SP5WWP"); strcpy(c.dst, "@ALL"); c.can = 0; c.meta_kind = M17TX_META_TEXT;
    strcpy(c.meta_text, "AtoM17 loopback test, 3 blocks!");
    CHECK(m17tx_configure(tx, &c) == 0, "configure plain");
    int n = voice_tx(tx, 50);
    result_t r = run(rx, symbuf, n, 0, 1.0f);
    CHECK(r.starts == 1 && r.ends == 1, "plain: start/end (%d/%d)", r.starts, r.ends);
    CHECK(!strcmp(r.src, "SP5WWP") && !strcmp(r.dst, "@ALL"), "plain: src=%s dst=%s", r.src, r.dst);
    CHECK(r.frames >= 50, "plain: %d frames", r.frames);
    CHECK(r.audio >= 50 * 320 - 640, "plain: %d audio samples", r.audio);
    CHECK(!strcmp(r.text, "AtoM17 loopback test, 3 blocks!"), "plain: META text '%s'", r.text);

    /* 1b. the same through Codec2-mod-neural: twice the samples (16 kHz), then back to Codec2 */
    CHECK(m17rx_set_neural(rx, 1) == 0 && m17rx_audio_rate(rx) == 16000, "neural: on");
    r = run(rx, symbuf, n, 0, 1.0f);
    CHECK(r.frames >= 50 && r.audio >= 2 * (50 * 320 - 640), "neural: %d frames, %d audio samples", r.frames, r.audio);
    CHECK(m17rx_set_neural(rx, 0) == 0 && m17rx_audio_rate(rx) == 8000, "neural: off");
    printf("      plain: deviation reported %.0f Hz (test modulator, single RRC)\n", r.dev);

    /* 2. scale and polarity sweep: what does the core tolerate? */
    {
        float scales[] = { -1.0f, 0.1f, 0.3f, 2.0f, 4.0f, 9.3f };
        for (int i = 0; i < 6; i++)
        {
            m17rx_config_t rc; m17rx_default_config(&rc); rc.invert = scales[i] < 0; m17rx_set_config(rx, &rc); m17rx_reset(rx);
            r = run(rx, symbuf, n, 0, scales[i]);
            printf("      scale %5.1f invert=%d: frames=%d dev=%.0f\n", scales[i], rc.invert, r.frames, r.dev);
        }
        m17rx_config_t rc; m17rx_default_config(&rc); rc.invert = 1; m17rx_set_config(rx, &rc); m17rx_reset(rx);
        r = run(rx, symbuf, n, 0, -1.0f);
        CHECK(r.frames >= 50, "inverted polarity with invert=1: %d frames", r.frames);
        m17rx_default_config(&rc); m17rx_set_config(rx, &rc); m17rx_reset(rx);
    }

    /* 2b. the AGC in front: any input scale, as from the USB card */
    use_agc = 1;
    {
        float scales[] = { 0.02f, 0.1f, 1.0f, 9.3f, 40.0f };
        for (int i = 0; i < 5; i++)
        {
            m17rx_reset(rx);
            r = run(rx, symbuf, n, 0, scales[i]);
            CHECK(r.frames >= 50, "AGC, input scale %.2f: %d frames, dev %.0f", scales[i], r.frames, r.dev);
        }
        m17rx_reset(rx);
        r = run(rx, symbuf, n, 900, 9.3f);
        CHECK(r.frames >= 48, "AGC, scale 9.3 with noise: %d frames", r.frames);
        /* 2 s of open-squelch noise at 3x the signal's RMS, then the carrier: the first frames must decode */
        lead_noise_sym = 9600; lead_noise_hz = 6000;
        m17rx_reset(rx);
        r = run(rx, symbuf, n, 0, 9.3f);
        CHECK(r.starts == 1 && r.frames >= 49, "AGC, 2 s noise then signal: %d frames", r.frames);
        lead_noise_sym = 0;
    }
    use_agc = 0;

    /* 3. noise */
    r = run(rx, symbuf, n, 900, 1.0f);
    CHECK(r.starts == 1 && r.frames >= 48, "noisy: %d frames", r.frames);

    /* 4. SMS */
    m17rx_reset(rx);
    int k = m17tx_sms(tx, "Hello from AtoM17 on the Atom XL", 32, symbuf);
    r = run(rx, symbuf, k, 0, 1.0f);
    CHECK(r.packets == 1 && !strcmp(r.sms, "Hello from AtoM17 on the Atom XL"), "sms: '%s'", r.sms);
    CHECK(r.pkt_meta == M17RX_META_NONE, "sms: no META without a position (meta kind %d)", r.pkt_meta);

    /* 4b. SMS with a position in the packet's LSF META (out of spec, common) */
    {
        m17tx_config_t g = c;
        g.meta_kind = M17TX_META_GNSS; g.gnss_source = 0; g.gnss_station = 1;
        g.lat = 52.22970f; g.lon = 21.01220f; g.alt_m = 112; g.gnss_validity = 1 << 3 | 1 << 2;
        CHECK(m17tx_configure(tx, &g) == 0, "configure gnss");
        m17rx_reset(rx);
        k = m17tx_sms(tx, "pos", 3, symbuf);
        r = run(rx, symbuf, k, 0, 1.0f);
        CHECK(r.packets == 1 && !strcmp(r.sms, "pos") && r.pkt_meta == M17RX_META_GNSS && r.pkt_pos
              && fabs(r.lat - 52.2297) < 1e-3 && fabs(r.lon - 21.0122) < 1e-3, "sms+pos: '%s' meta %d valid %d %.5f %.5f", r.sms, r.pkt_meta, r.pkt_pos, r.lat, r.lon);
        CHECK(m17tx_configure(tx, &c) == 0, "configure back");
    }

    /* 5. AES-256 */
    m17rx_reset(rx);
    c.enc = M17TX_ENC_AES; c.aes_bits = 256; for (int i = 0; i < 32; i++) c.aes_key[i] = (uint8_t)(i * 7 + 1);
    CHECK(m17tx_configure(tx, &c) == 0, "configure aes");
    m17rx_set_aes_key(rx, c.aes_key, 32);
    n = voice_tx(tx, 30);
    r = run(rx, symbuf, n, 0, 1.0f);
    CHECK(r.starts == 1 && r.enc == M17RX_ENC_AES && r.dec == M17RX_DEC_OK && r.audio > 0, "aes256: enc=%d dec=%d audio=%d", r.enc, r.dec, r.audio);
    m17rx_set_aes_key(rx, NULL, 0); m17rx_reset(rx);
    r = run(rx, symbuf, n, 0, 1.0f);
    CHECK(r.dec == M17RX_DEC_NO_KEY, "aes256 without key: dec=%d (2 = no key, audio muted to silence)", r.dec);

    /* 6. 24-bit scrambler */
    m17rx_reset(rx);
    c.enc = M17TX_ENC_SCRAM; c.scram_bits = 24; c.scram_seed = 0xA5C3F1;
    CHECK(m17tx_configure(tx, &c) == 0, "configure scrambler");
    m17rx_set_scrambler_seed(rx, 0xA5C3F1, 24);
    n = voice_tx(tx, 30);
    r = run(rx, symbuf, n, 0, 1.0f);
    CHECK(r.enc == M17RX_ENC_SCRAMBLER && r.dec == M17RX_DEC_OK && r.audio > 0, "scrambler24: dec=%d audio=%d", r.dec, r.audio);
    m17rx_set_scrambler_seed(rx, 0, 0);

    /* 7. signed stream */
    m17rx_reset(rx);
    c.enc = M17TX_ENC_NONE; c.sign = 1;
    uint8_t pub[64];
    CHECK(m17tx_make_key(c.priv_key, pub) == 1, "make key");
    uint8_t pub2[64]; m17tx_public_key(c.priv_key, pub2);
    CHECK(!memcmp(pub, pub2, 64), "public key from private");
    CHECK(m17tx_configure(tx, &c) == 0, "configure signed");
    m17rx_add_public_key(rx, "SP5WWP", pub, 64);
    n = voice_tx(tx, 25);
    r = run(rx, symbuf, n, 0, 1.0f);
    CHECK(r.sig_state == M17RX_SIG_VALID, "signed: sig_state=%d (1 = valid)", r.sig_state);
    {
        long age = (long)time(NULL) - 1577836800L - (long)r.sig_time;
        CHECK(!strcmp(r.sig_signer, "SP5WWP") && !strcmp(r.sig_dst, "@ALL") && age >= 0 && age < 60 && r.sig_not_orig == 0,
              "signed: Authentication Frame signer=%s dst=%s, signed %ld s ago, not_originator=%d", r.sig_signer, r.sig_dst, age, r.sig_not_orig);
    }
    m17rx_clear_public_keys(rx); uint8_t other[32], opub[64]; m17tx_make_key(other, opub); m17rx_add_public_key(rx, "SP5WWP", opub, 64); m17rx_reset(rx);
    r = run(rx, symbuf, n, 0, 1.0f);
    CHECK(r.sig_state == M17RX_SIG_INVALID, "signed, wrong key: sig_state=%d (2 = invalid)", r.sig_state);

    /* 9. the app's receive path (rxPushSymbols): symbols -> m17resynth -> the core at 48 kHz. The META text has a
       two-byte UTF-8 character across the boundary of blocks 0 and 1, which must arrive whole. */
    {
        m17rx_t* rx48 = m17rx_create();
        m17rx_set_input_rate(rx48, 48000);
        m17tx_config_t c9; memset(&c9, 0, sizeof(c9));
        strcpy(c9.src, "SP5WWP"); strcpy(c9.dst, "@ALL"); c9.meta_kind = M17TX_META_TEXT;
        const char* text9 = "AAAAAAAAAAAA\xc4\x85" "BBBBBBBBBBBBBBB";
        strcpy(c9.meta_text, text9);
        CHECK(m17tx_configure(tx, &c9) == 0, "resynth: configure");
        n = voice_tx(tx, 40);
        m17resynth_t rs; m17resynth_init(&rs);
        float s9[192], o9[1920];
        int starts = 0, ends = 0, frames = 0, audio = 0;
        char got[64] = "";
        for (int k = 0; k < n / 192 + 6; k++)
        {
            for (int i = 0; i < 192; i++) s9[i] = k * 192 + i < n ? symbuf[k * 192 + i] : 0;
            m17resynth_run(&rs, s9, 192, o9);
            m17rx_process_fm(rx48, o9, 1920);
            int16_t pcm[4096]; int a; while ((a = m17rx_read_audio(rx48, pcm, 4096)) > 0) audio += a;
            m17rx_event_t ev;
            while (m17rx_next_event(rx48, &ev))
            {
                if (ev.kind == M17RX_EV_STREAM_START) starts++;
                if (ev.kind == M17RX_EV_LINK_UPDATE && ev.link.meta_text_complete) strcpy(got, ev.link.meta_text);
                if (ev.kind == M17RX_EV_STREAM_END) { ends++; frames = ev.frames; }
            }
        }
        CHECK(starts == 1 && ends == 1 && frames >= 40, "resynth: start/end %d/%d, %d frames", starts, ends, frames);
        CHECK(audio >= 40 * 320 - 640, "resynth: %d audio samples", audio);
        CHECK(!strcmp(got, text9), "resynth: META text with a character across blocks arrives whole");
        m17rx_destroy(rx48);

        /* the encoder refuses settings out of range */
        c9.enc = 3;
        CHECK(m17tx_configure(tx, &c9) == -5, "configure: encryption type 3 refused");
        c9.enc = M17TX_ENC_AES; c9.aes_bits = 100;
        CHECK(m17tx_configure(tx, &c9) == -6, "configure: AES key size 100 refused");
    }

    /* 8. pack */
    int8_t s4[8] = {3, 1, -1, -3, -3, -1, 1, 3}; uint8_t pk[2];
    m17tx_pack(s4, 8, pk);
    CHECK(pk[0] == 0x4B && pk[1] == 0xE1, "pack: %02X %02X (4B E1)", pk[0], pk[1]);

    printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    m17tx_destroy(tx); m17rx_destroy(rx);
    return fails != 0;
}
