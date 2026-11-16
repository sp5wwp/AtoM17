/*
 * AtoM17 - JNI bridge to the M17 receiver core (libm17rx) and the transmit encoder (m17tx).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "m17rx.h"
#include "m17tx.h"
#include "m17.h"
#include "m17resynth.h"
#include "codec2_mod.h"
#include "c2fargan.h"

#define JF(name) Java_org_m17project_atomxl_M17Native_##name

/* ------------------------------------------------------------------ receiver */

typedef struct
{
    m17rx_t* rx;
    m17resynth_t rs;                                   /* the DFE's symbols back to 48 kHz for the core */
    float syn[1920];
} rxh_t;

JNIEXPORT jlong JNICALL JF(rxCreate)(JNIEnv* env, jclass c, jdouble rate)
{
    rxh_t* h = calloc(1, sizeof(*h));
    if (!h) return 0;
    h->rx = m17rx_create();
    if (!h->rx) { free(h); return 0; }
    m17rx_set_input_rate(h->rx, rate);
    m17resynth_init(&h->rs);
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL JF(rxDestroy)(JNIEnv* env, jclass c, jlong p)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h) return;
    m17rx_destroy(h->rx);
    free(h);
}

JNIEXPORT void JNICALL JF(rxReset)(JNIEnv* env, jclass c, jlong p)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (h) m17rx_reset(h->rx);
}

JNIEXPORT void JNICALL JF(rxConfig)(JNIEnv* env, jclass c, jlong p, jboolean invert)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h) return;
    m17rx_config_t cfg;
    m17rx_default_config(&cfg);
    cfg.invert = invert ? 1 : 0;
    m17rx_set_config(h->rx, &cfg);
}

/* One frame (192) of equalised symbols from the DFE: re-synthesised at 48 kHz into the core (created at 48000). */
JNIEXPORT jint JNICALL JF(rxPushSymbols)(JNIEnv* env, jclass c, jlong p, jfloatArray sym)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h) return 0;
    float s[192];
    jsize n = (*env)->GetArrayLength(env, sym);
    if (n > 192) n = 192;
    (*env)->GetFloatArrayRegion(env, sym, 0, n, s);
    for (int i = n; i < 192; i++) s[i] = 0;
    m17resynth_run(&h->rs, s, 192, h->syn);
    return m17rx_process_fm(h->rx, h->syn, 1920);
}

JNIEXPORT jint JNICALL JF(rxReadAudio)(JNIEnv* env, jclass c, jlong p, jshortArray out)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h) return 0;
    jsize n = (*env)->GetArrayLength(env, out);
    jshort* s = (*env)->GetShortArrayElements(env, out, NULL);
    if (!s) return 0;
    int got = m17rx_read_audio(h->rx, (int16_t*)s, n);
    (*env)->ReleaseShortArrayElements(env, out, s, 0);
    return got;
}

JNIEXPORT jint JNICALL JF(rxSetNeural)(JNIEnv* env, jclass c, jlong p, jboolean on)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h) return -1;
    return m17rx_set_neural(h->rx, on ? 1 : 0);
}

JNIEXPORT jint JNICALL JF(rxAudioRate)(JNIEnv* env, jclass c, jlong p)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    return h ? m17rx_audio_rate(h->rx) : 8000;
}

/* Voice check: 8 kHz speech through Codec2 3200 and back, the way another station hears it. With {@code neural}
   the frames are synthesised by FARGAN (16 kHz out, twice the samples). Returns the decoded audio. */
JNIEXPORT jshortArray JNICALL JF(c2Loopback)(JNIEnv* env, jclass c, jshortArray in, jboolean neural)
{
    jsize n = (*env)->GetArrayLength(env, in), frames = n / 160;
    int up = neural ? 2 : 1;
    jshortArray outA = (*env)->NewShortArray(env, frames * 160 * up);
    if (!outA || frames == 0) return outA;
    int16_t* pcm = (int16_t*)malloc((size_t)n * sizeof(int16_t));
    int16_t* out = (int16_t*)malloc((size_t)frames * 160 * up * sizeof(int16_t));
    codec2_encoder_t* enc = (codec2_encoder_t*)calloc(1, sizeof(codec2_encoder_t));
    codec2_decoder_t* dec = (codec2_decoder_t*)calloc(1, sizeof(codec2_decoder_t));
    c2f_decoder* nn = neural ? c2f_create() : NULL;
    if (pcm && out && enc && dec && (!neural || nn)) {
        (*env)->GetShortArrayRegion(env, in, 0, n, pcm);
        codec2_encoder_init(enc);
        codec2_decoder_init(dec);
        for (int f = 0; f < frames; f++) {
            uint8_t bits[8];
            codec2_encode(enc, bits, &pcm[f * 160]);
            if (nn) c2f_decode(nn, &out[f * 320], bits);
            else codec2_decode(dec, &out[f * 160], bits);
        }
        (*env)->SetShortArrayRegion(env, outA, 0, frames * 160 * up, out);
    }
    if (nn) c2f_destroy(nn);
    free(pcm); free(out); free(enc); free(dec);
    return outA;
}

/*
 * `in` (UTF-8 from the air, maybe invalid or cut) as the inside of a JSON string, in plain ASCII: quotes, backslashes
 * and control characters escaped, every other non-ASCII character as \uXXXX (a surrogate pair above U+FFFF), bytes
 * that are not valid UTF-8 as U+FFFD. NewStringUTF() takes only valid modified UTF-8 and aborts on anything else under
 * CheckJNI (debuggable builds), so received text never reaches it raw.
 */
static void jesc(char* out, size_t cap, const char* in)
{
    const unsigned char* s = (const unsigned char*)in;
    size_t o = 0;
    while (*s && o + 13 < cap)
    {
        uint32_t cp = *s;
        int len = 1;
        if (cp >= 0x80)
        {
            len = cp >= 0xF0 && cp <= 0xF4 ? 4 : cp >= 0xE0 ? 3 : cp >= 0xC2 && cp < 0xE0 ? 2 : 0;
            if (len == 4) cp &= 0x07; else if (len == 3) cp &= 0x0F; else if (len == 2) cp &= 0x1F;
            for (int i = 1; i < len; i++)
            {
                if ((s[i] & 0xC0) != 0x80) { len = 0; break; }
                cp = (cp << 6) | (s[i] & 0x3F);
            }
            /* overlong forms, surrogates and values past U+10FFFF are not valid either */
            if (len == 3 && (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF))) len = 0;
            if (len == 4 && (cp < 0x10000 || cp > 0x10FFFF)) len = 0;
            if (len == 0) { cp = 0xFFFD; len = 1; }
        }
        s += len;
        if (cp == '"' || cp == '\\') { out[o++] = '\\'; out[o++] = (char)cp; }
        else if (cp >= 0x20 && cp < 0x7F) out[o++] = (char)cp;
        else if (cp < 0x10000) o += (size_t)snprintf(out + o, cap - o, "\\u%04x", (unsigned)cp);
        else
        {
            cp -= 0x10000;
            o += (size_t)snprintf(out + o, cap - o, "\\u%04x\\u%04x", 0xD800u + (cp >> 10), 0xDC00u + (cp & 0x3FF));
        }
    }
    out[o] = 0;
}

static int link_json(char* b, size_t cap, const m17rx_link_t* l)
{
    char src[32], dst[32], text[400], e1[32], e2[32];
    jesc(src, sizeof src, l->src); jesc(dst, sizeof dst, l->dst); jesc(text, sizeof text, l->meta_text);
    jesc(e1, sizeof e1, l->ecd_cf1); jesc(e2, sizeof e2, l->ecd_cf2);
    char meta[29] = {0};
    for (int i = 0; i < 14; i++) snprintf(meta + 2 * i, 3, "%02X", l->meta[i]);
    return snprintf(b, cap,
        "{\"valid\":%d,\"fromLich\":%d,\"src\":\"%s\",\"dst\":\"%s\",\"type\":%u,\"stream\":%d,\"dataType\":%d,"
        "\"enc\":%d,\"encSub\":%d,\"can\":%d,\"signed\":%d,\"meta\":\"%s\",\"metaKind\":%d,\"text\":\"%s\",\"textComplete\":%d,"
        "\"ecd1\":\"%s\",\"ecd2\":\"%s\",\"posValid\":%d,\"altValid\":%d,\"spdValid\":%d,\"station\":%d,\"source\":%d,"
        "\"lat\":%.6f,\"lon\":%.6f,\"alt\":%.1f,\"speed\":%.1f,\"bearing\":%d,\"decrypt\":%d}",
        l->valid, l->from_lich, src, dst, l->type, l->stream, l->data_type, l->enc_type, l->enc_subtype, l->can,
        l->signed_stream, meta, l->meta_kind, text, l->meta_text_complete, e1, e2, l->gnss_valid_pos, l->gnss_valid_alt,
        l->gnss_valid_speed, l->gnss_station, l->gnss_source, l->gnss_lat, l->gnss_lon, l->gnss_alt_m, l->gnss_speed_kmh,
        l->gnss_bearing_deg, l->decrypt);
}

/* The next event as JSON, or null. */
JNIEXPORT jstring JNICALL JF(rxNextEvent)(JNIEnv* env, jclass c, jlong p)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h) return NULL;
    m17rx_event_t* ev = malloc(sizeof(*ev));
    if (!ev) return NULL;
    if (!m17rx_next_event(h->rx, ev)) { free(ev); return NULL; }
    size_t cap = 8192;
    char* b = malloc(cap);
    if (!b) { free(ev); return NULL; }
    char key[64], sms[M17RX_PKT_MAX * 6 + 16];         /* every byte of an SMS escaped as at most \uXXXX */
    jesc(key, sizeof key, ev->sig_key);
    jesc(sms, sizeof sms, ev->sms);
    int o = snprintf(b, cap, "{\"kind\":%d,\"end\":%d,\"frames\":%d,\"lost\":%d,\"duration\":%.2f,\"evm\":%.2f,\"sig\":%d,\"sigKey\":\"%s\",\"protocol\":%d,\"length\":%d,\"sms\":\"%s\",\"data\":\"",
                     ev->kind, ev->end_reason, ev->frames, ev->frames_lost, ev->duration_s, ev->evm_rms_pct, ev->sig_state, key,
                     ev->protocol, ev->length, sms);
    for (int i = 0; i < ev->length && i < 64 && o + 4 < (int)cap; i++) o += snprintf(b + o, cap - (size_t)o, "%02X", ev->data[i]);
    o += snprintf(b + o, cap - (size_t)o, "\",\"link\":");
    o += link_json(b + o, cap - (size_t)o, &ev->link);
    snprintf(b + o, cap - (size_t)o, "}");
    jstring r = (*env)->NewStringUTF(env, b);
    free(b);
    free(ev);
    return r;
}

JNIEXPORT jstring JNICALL JF(rxStatus)(JNIEnv* env, jclass c, jlong p)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h) return NULL;
    m17rx_status_t st;
    m17rx_get_status(h->rx, &st);
    char b[4096];
    int o = snprintf(b, sizeof b,
        "{\"state\":%d,\"locked\":%d,\"fn\":%d,\"frames\":%d,\"lost\":%d,\"duration\":%.2f,\"lich\":%d,\"measValid\":%d,"
        "\"evm\":%.2f,\"evmPeak\":%.2f,\"mer\":%.2f,\"ber\":%.5f,\"vber\":%.5f,\"eye\":%.1f,\"dev\":%.0f,\"ratio\":%.3f,"
        "\"offset\":%.0f,\"ppm\":%.1f,\"sync\":%.3f,\"polarity\":%d,\"link\":",
        st.state, st.locked, st.last_fn, st.frames, st.frames_lost, st.duration_s, st.lich_chunks, st.meas_valid,
        st.evm_rms_pct, st.evm_peak_pct, st.mer_db, st.ber_pre_fec, st.viterbi_ber, st.eye_opening_pct, st.deviation_hz,
        st.inner_outer_ratio, st.freq_offset_hz, st.clock_ppm, st.sync_quality, st.polarity_hint);
    o += link_json(b + o, sizeof b - (size_t)o, &st.link);
    snprintf(b + o, sizeof b - (size_t)o, "}");
    return (*env)->NewStringUTF(env, b);
}

JNIEXPORT jint JNICALL JF(rxSetAes)(JNIEnv* env, jclass c, jlong p, jbyteArray key)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h) return -1;
    if (!key) return m17rx_set_aes_key(h->rx, NULL, 0);
    jsize n = (*env)->GetArrayLength(env, key);
    jbyte* k = (*env)->GetByteArrayElements(env, key, NULL);
    int r = m17rx_set_aes_key(h->rx, (const uint8_t*)k, n);
    (*env)->ReleaseByteArrayElements(env, key, k, JNI_ABORT);
    return r;
}

JNIEXPORT jint JNICALL JF(rxSetScrambler)(JNIEnv* env, jclass c, jlong p, jint seed, jint bits)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    return h ? m17rx_set_scrambler_seed(h->rx, (uint32_t)seed, bits) : -1;
}

JNIEXPORT void JNICALL JF(rxClearKeys)(JNIEnv* env, jclass c, jlong p)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (h) m17rx_clear_public_keys(h->rx);
}

JNIEXPORT jint JNICALL JF(rxAddKey)(JNIEnv* env, jclass c, jlong p, jstring name, jbyteArray key)
{
    rxh_t* h = (rxh_t*)(intptr_t)p;
    if (!h || !name || !key) return -1;
    const char* nm = (*env)->GetStringUTFChars(env, name, NULL);
    jsize n = (*env)->GetArrayLength(env, key);
    jbyte* k = (*env)->GetByteArrayElements(env, key, NULL);
    int r = m17rx_add_public_key(h->rx, nm, (const uint8_t*)k, n);
    (*env)->ReleaseByteArrayElements(env, key, k, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, nm);
    return r;
}

/* ------------------------------------------------------------------ transmitter */

JNIEXPORT jlong JNICALL JF(txCreate)(JNIEnv* env, jclass c) { return (jlong)(intptr_t)m17tx_create(); }
JNIEXPORT void JNICALL JF(txDestroy)(JNIEnv* env, jclass c, jlong p) { m17tx_destroy((m17tx_t*)(intptr_t)p); }

/*
 * A Java string as standard UTF-8 in `out` (cap bytes with the NUL), cut only between whole characters.
 * GetStringUTFChars() gives modified UTF-8 (six bytes for an emoji, C0 80 for NUL), which other receivers show as
 * garbage, so the UTF-16 units are encoded here. A lone surrogate becomes U+FFFD.
 */
static void jstr(JNIEnv* env, jstring s, char* out, size_t cap)
{
    out[0] = 0;
    if (!s) return;
    jsize n = (*env)->GetStringLength(env, s);
    if (n > (jsize)cap) n = (jsize)cap;               /* every unit takes at least one byte */
    jchar u[256];
    if (n > 256) n = 256;
    (*env)->GetStringRegion(env, s, 0, n, u);
    size_t o = 0;
    for (jsize i = 0; i < n; i++)
    {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF)
            cp = 0x10000 + ((cp - 0xD800) << 10) + (u[++i] - 0xDC00);
        else if (cp >= 0xD800 && cp <= 0xDFFF)
            cp = 0xFFFD;
        uint8_t e[4];
        size_t len;
        if (cp < 0x80) { e[0] = (uint8_t)cp; len = 1; }
        else if (cp < 0x800) { e[0] = (uint8_t)(0xC0 | cp >> 6); e[1] = (uint8_t)(0x80 | (cp & 0x3F)); len = 2; }
        else if (cp < 0x10000)
        {
            e[0] = (uint8_t)(0xE0 | cp >> 12); e[1] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F)); e[2] = (uint8_t)(0x80 | (cp & 0x3F));
            len = 3;
        }
        else
        {
            e[0] = (uint8_t)(0xF0 | cp >> 18); e[1] = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
            e[2] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F)); e[3] = (uint8_t)(0x80 | (cp & 0x3F));
            len = 4;
        }
        if (o + len > cap - 1) break;
        memcpy(out + o, e, len);
        o += len;
    }
    out[o] = 0;
}

static void jbytes(JNIEnv* env, jbyteArray a, uint8_t* out, int cap)
{
    memset(out, 0, (size_t)cap);
    if (!a) return;
    jsize n = (*env)->GetArrayLength(env, a);
    if (n > cap) n = cap;
    (*env)->GetByteArrayRegion(env, a, 0, n, (jbyte*)out);
}

JNIEXPORT jint JNICALL JF(txConfigure)(JNIEnv* env, jclass c, jlong p, jstring src, jstring dst, jint can,
                                       jint metaKind, jstring metaText,
                                       jdouble lat, jdouble lon, jfloat alt, jfloat speed, jint bearing, jint station, jint validity,
                                       jstring ecd1, jstring ecd2,
                                       jint enc, jint aesBits, jbyteArray aesKey, jint scramBits, jint seed,
                                       jboolean sign, jbyteArray priv)
{
    m17tx_t* tx = (m17tx_t*)(intptr_t)p;
    if (!tx) return -100;
    m17tx_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    jstr(env, src, cfg.src, sizeof cfg.src);
    jstr(env, dst, cfg.dst, sizeof cfg.dst);
    cfg.can = can;
    cfg.meta_kind = metaKind;
    jstr(env, metaText, cfg.meta_text, sizeof cfg.meta_text);
    cfg.lat = (float)lat; cfg.lon = (float)lon; cfg.alt_m = alt; cfg.speed_kmh = speed; cfg.bearing = bearing;
    cfg.gnss_station = station; cfg.gnss_validity = validity; cfg.gnss_source = 0;
    jstr(env, ecd1, cfg.ecd_cf1, sizeof cfg.ecd_cf1);
    jstr(env, ecd2, cfg.ecd_cf2, sizeof cfg.ecd_cf2);
    cfg.enc = enc;
    cfg.aes_bits = aesBits;
    jbytes(env, aesKey, cfg.aes_key, 32);
    cfg.scram_bits = scramBits;
    cfg.scram_seed = (uint32_t)seed;
    cfg.sign = sign ? 1 : 0;
    jbytes(env, priv, cfg.priv_key, 32);
    int r = m17tx_configure(tx, &cfg);
    memset(&cfg, 0, sizeof(cfg));
    return r;
}


/* The most symbols one call writes: an SMS, preamble + LSF + 33 packet frames + EoT. */
#define TX_MAX_SYM ((3 + 33) * SYM_PER_FRA)

/* `nsym` symbols packed into `out`: the byte count, or a negative error. Each call has its own symbol buffer. */
static jint packed(JNIEnv* env, jbyteArray out, const int8_t* sym, int nsym)
{
    if (nsym <= 0) return nsym;
    if (!out) return -52;
    uint8_t b[TX_MAX_SYM / 4];
    int n = m17tx_pack(sym, nsym, b);
    if ((*env)->GetArrayLength(env, out) < n) return -50;
    (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte*)b);
    return n;
}
/* Preamble + LSF, packed: 96 bytes. */
JNIEXPORT jint JNICALL JF(txBegin)(JNIEnv* env, jclass c, jlong p, jbyteArray out)
{
    int8_t sym[TX_MAX_SYM];
    return packed(env, out, sym, m17tx_stream_begin((m17tx_t*)(intptr_t)p, sym));
}
/* One stream frame from 320 samples: 48 bytes. */
JNIEXPORT jint JNICALL JF(txVoice)(JNIEnv* env, jclass c, jlong p, jshortArray pcm, jbyteArray out)
{
    int16_t s[320];
    int8_t sym[TX_MAX_SYM];
    if (!pcm || (*env)->GetArrayLength(env, pcm) < 320) return -51;
    (*env)->GetShortArrayRegion(env, pcm, 0, 320, s);
    return packed(env, out, sym, m17tx_stream_voice((m17tx_t*)(intptr_t)p, s, sym));
}
/* Last frame (+ signature) + EoT: up to 6 * 48 bytes. pcm may be null. */
JNIEXPORT jint JNICALL JF(txEnd)(JNIEnv* env, jclass c, jlong p, jshortArray pcm, jbyteArray out)
{
    int16_t s[320];
    int8_t sym[TX_MAX_SYM];
    if (pcm && (*env)->GetArrayLength(env, pcm) < 320) return -51;
    if (pcm) (*env)->GetShortArrayRegion(env, pcm, 0, 320, s);
    return packed(env, out, sym, m17tx_stream_end((m17tx_t*)(intptr_t)p, pcm ? s : NULL, sym));
}
/* A whole SMS transmission (UTF-8 bytes), packed. */
JNIEXPORT jint JNICALL JF(txSms)(JNIEnv* env, jclass c, jlong p, jbyteArray text, jbyteArray out)
{
    if (!text) return -1;
    jsize n = (*env)->GetArrayLength(env, text);
    char t[840];
    int8_t sym[TX_MAX_SYM];
    if (n > 821) return -1;
    (*env)->GetByteArrayRegion(env, text, 0, n, (jbyte*)t);
    return packed(env, out, sym, m17tx_sms((m17tx_t*)(intptr_t)p, t, n, sym));
}
/* priv(32) || pub(64), or null */
JNIEXPORT jbyteArray JNICALL JF(makeKey)(JNIEnv* env, jclass c)
{
    uint8_t priv[32], pub[64];
    if (!m17tx_make_key(priv, pub)) return NULL;
    jbyteArray r = (*env)->NewByteArray(env, 96);
    if (!r) { memset(priv, 0, sizeof priv); return NULL; }
    (*env)->SetByteArrayRegion(env, r, 0, 32, (const jbyte*)priv);
    (*env)->SetByteArrayRegion(env, r, 32, 64, (const jbyte*)pub);
    memset(priv, 0, sizeof priv);
    return r;
}

JNIEXPORT jbyteArray JNICALL JF(publicKey)(JNIEnv* env, jclass c, jbyteArray priv)
{
    uint8_t k[32], pub[64];
    jbytes(env, priv, k, 32);
    int ok = m17tx_public_key(k, pub);
    memset(k, 0, sizeof k);
    if (!ok) return NULL;
    jbyteArray r = (*env)->NewByteArray(env, 64);
    if (!r) return NULL;
    (*env)->SetByteArrayRegion(env, r, 0, 64, (const jbyte*)pub);
    return r;
}

JNIEXPORT jstring JNICALL JF(version)(JNIEnv* env, jclass c)
{
    char b[128];
    snprintf(b, sizeof b, "libm17 %s, m17rx %s", LIBM17_VERSION, m17rx_version());
    return (*env)->NewStringUTF(env, b);
}
