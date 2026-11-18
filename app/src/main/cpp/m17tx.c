/*
 * AtoM17 - M17 transmit encoder. See m17tx.h.
 * Framing, META latching, AES-CTR nonce, scrambler, stream hash and signature follow gr-m17's m17_coder_impl.cc
 * (Copyright 2023 jmfriedt, 2024-2026 Wojciech Kaczmarski, GPL-3.0-or-later), rewritten for a pull-style C API.
 * AES and the scrambler are the receiver's (m17rx/src/crypto.c), the stream hash is m17rx/src/m17sig.c (spec 2.1.0):
 * the code that decrypts and checks them.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "m17tx.h"

#include <ctype.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <time.h>
#include <unistd.h>

#include "m17.h"
#include "codec2_mod.h"
#include "crypto.h"
#include "m17sig.h"
#include "uECC.h"

#define PAYLOAD_BYTES 16
#define PKT_CHUNK 25
#define PKT_MAX_FRAMES 33
#define C2_FRAME_BYTES 8
static const uint8_t C2_SILENCE[C2_FRAME_BYTES] = {0x01, 0x00, 0x09, 0x43, 0x9C, 0xE4, 0x21, 0x08};

struct m17tx
{
    m17tx_config_t cfg;
    lsf_t lsf;          /* configured LSF */
    lsf_t lsf_tx;       /* the transmission's working copy */
    uint8_t meta_block[4][14];
    int meta_blocks, meta_idx;
    uint16_t fn;
    uint8_t lich_cnt;
    m17sig_ctx_t sig_ctx; /* stream hash; holds pointers into itself, the struct is never copied */
    uint32_t scram_state;
    int scram_subtype;
    int aes_subtype;
    m17_aes_t aes;      /* the expanded key, once per configuration */
    codec2_encoder_t c2;
};

/* random bytes for the AES nonce and the signing keys: getrandom(), else /dev/urandom. 1 on success, 0 if neither works */
static int urandom(uint8_t* dest, unsigned size)
{
    unsigned have = 0;
    while (have < size)
    {
        ssize_t r = getrandom(dest + have, size - have, 0);
        if (r <= 0) break;
        have += (unsigned)r;
    }
    if (have == size) return 1;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned got = 0;
    while (got < size)
    {
        ssize_t r = read(fd, dest + got, size - got);
        if (r <= 0) { close(fd); return 0; }
        got += (unsigned)r;
    }
    close(fd);
    return 1;
}

m17tx_t* m17tx_create(void)
{
    m17tx_t* tx = calloc(1, sizeof(*tx));
    if (!tx) return NULL;
    uECC_set_rng(urandom);
    codec2_encoder_init(&tx->c2);
    return tx;
}

void m17tx_destroy(m17tx_t* tx)
{
    if (!tx) return;
    memset(tx, 0, sizeof(*tx));                        /* the keys */
    free(tx);
}

static void upper9(char* dst, const char* src)
{
    int i = 0;
    for (; src[i] && i < 9; i++) dst[i] = (char)toupper((unsigned char)src[i]);
    dst[i] = 0;
}

static uint16_t type_word(const m17tx_t* tx)
{
    const m17tx_config_t* c = &tx->cfg;
    int encr_subtype;
    if (c->enc == M17TX_ENC_AES) encr_subtype = tx->aes_subtype;
    else if (c->enc == M17TX_ENC_SCRAM) encr_subtype = tx->scram_subtype;
    else encr_subtype = c->meta_kind;
    return (uint16_t)(M17_TYPE_STREAM | M17_TYPE_VOICE | (c->enc << 3) | (encr_subtype << 5) | M17_TYPE_CAN(c->can) |
                      (c->sign ? M17_TYPE_SIGNED : 0));
}

int m17tx_configure(m17tx_t* tx, const m17tx_config_t* cfg)
{
    tx->cfg = *cfg;
    m17tx_config_t* c = &tx->cfg;
    char src[10], dst[10];
    upper9(src, c->src);
    upper9(dst, c->dst);
    if (!src[0]) return -1;
    if (!dst[0]) strcpy(dst, "@ALL");
    if (c->can < 0 || c->can > 15) return -2;
    if (c->enc < M17TX_ENC_NONE || c->enc > M17TX_ENC_AES) return -5;
    if (c->meta_kind < M17TX_META_TEXT || c->meta_kind > M17TX_META_ECD) return -5;

    tx->aes_subtype = c->aes_bits == 192 ? 1 : c->aes_bits == 256 ? 2 : 0;
    tx->scram_subtype = c->scram_bits == 16 ? 1 : c->scram_bits == 24 ? 2 : 0;
    if (c->enc == M17TX_ENC_AES)
    {
        if (c->aes_bits != 128 && c->aes_bits != 192 && c->aes_bits != 256) return -6;
        if (m17_aes_init(&tx->aes, c->aes_key, c->aes_bits / 8) != 0) return -6;
    }
    if (c->enc == M17TX_ENC_SCRAM)
    {
        if (c->scram_bits != 8 && c->scram_bits != 16 && c->scram_bits != 24) return -6;
        uint32_t mask = c->scram_bits == 24 ? 0xFFFFFF : c->scram_bits == 16 ? 0xFFFF : 0xFF;
        c->scram_seed &= mask;
        if (c->scram_seed == 0) return -3;          /* an all-zero seed does not scramble */
    }
    if (c->sign)
    {
        uint8_t pub[64];
        if (!uECC_compute_public_key(c->priv_key, pub, uECC_secp256r1())) return -4;
    }

    memset(&tx->lsf, 0, sizeof(tx->lsf));
    encode_callsign_bytes(tx->lsf.src, src);
    encode_callsign_bytes(tx->lsf.dst, dst);
    uint16_t t = type_word(tx);
    tx->lsf.type[0] = t >> 8;
    tx->lsf.type[1] = t & 0xFF;

    tx->meta_blocks = 0;
    if (c->enc == M17TX_ENC_NONE)
    {
        if (c->meta_kind == M17TX_META_TEXT && c->meta_text[0])
        {
            size_t n = strnlen(c->meta_text, 52);
            tx->meta_blocks = (int)((n + 12) / 13);
            uint8_t used = (uint8_t)((1 << tx->meta_blocks) - 1);
            for (int b = 0; b < tx->meta_blocks; b++)
            {
                tx->meta_block[b][0] = (uint8_t)((used << 4) | (1 << b));
                memset(&tx->meta_block[b][1], ' ', 13);
                size_t k = n - 13 * (size_t)b < 13 ? n - 13 * (size_t)b : 13;
                memcpy(&tx->meta_block[b][1], c->meta_text + 13 * b, k);
            }
            memcpy(tx->lsf.meta, tx->meta_block[0], 14);
        }
        else if (c->meta_kind == M17TX_META_GNSS)
        {
            set_LSF_meta_position(&tx->lsf, (uint8_t)c->gnss_source, (uint8_t)c->gnss_station, c->lat, c->lon,
                                  (uint8_t)c->gnss_validity, c->alt_m, (uint16_t)c->bearing, c->speed_kmh, 0.0f);
        }
        else if (c->meta_kind == M17TX_META_ECD)
        {
            char cf1[10], cf2[10];
            upper9(cf1, c->ecd_cf1);
            upper9(cf2, c->ecd_cf2);
            set_LSF_meta_ecd(&tx->lsf, cf1, cf2);
        }
    }
    /* set_LSF_meta_* rewrite META only; TYPE stays ours */
    tx->lsf.type[0] = t >> 8;
    tx->lsf.type[1] = t & 0xFF;
    update_LSF_CRC(&tx->lsf);
    return 0;
}

/* the transmission's LSF copy. AES gets a fresh nonce (timestamp since 2020 + 80 random bits), -1 when there are no
   random bits: a fixed pattern would repeat the AES-CTR keystream of another transmission in the same second */
static int latch_lsf(m17tx_t* tx, int start)
{
    int aes = tx->cfg.enc == M17TX_ENC_AES;
    if (start)
    {
        tx->lsf_tx = tx->lsf;
        if (aes)
        {
            uint8_t rnd[10];
            if (!urandom(rnd, sizeof(rnd))) return -1;
            set_LSF_meta_nonce(&tx->lsf_tx, time(NULL), rnd);
        }
    }
    else if (aes)
        return 0;                                      /* the nonce stays for the whole transmission */
    if (!aes && tx->meta_blocks > 1)
        memcpy(tx->lsf_tx.meta, tx->meta_block[tx->meta_idx], 14);
    update_LSF_CRC(&tx->lsf_tx);
    return 0;
}

static void encrypt_payload(m17tx_t* tx, uint8_t* data)
{
    if (tx->cfg.enc == M17TX_ENC_AES)
    {
        m17_aes_ctr_frame(&tx->aes, tx->lsf_tx.meta, tx->fn, data);
    }
    else if (tx->cfg.enc == M17TX_ENC_SCRAM)
    {
        uint8_t k[PAYLOAD_BYTES];
        tx->scram_state = m17_lfsr_frame(tx->scram_state, tx->scram_subtype, k);
        for (int i = 0; i < PAYLOAD_BYTES; i++) data[i] ^= k[i];
    }
}

int m17tx_stream_begin(m17tx_t* tx, int8_t* out)
{
    uint32_t n = 0;
    tx->fn = 0;
    tx->lich_cnt = 0;
    tx->meta_idx = 0;
    m17sig_init(&tx->sig_ctx);
    tx->scram_state = tx->cfg.scram_seed;
    codec2_encoder_init(&tx->c2);
    if (latch_lsf(tx, 1) < 0) return -1;
    gen_preamble_i8(out, &n, PREAM_LSF);
    gen_frame_i8(out + n, NULL, FRAME_LSF, &tx->lsf_tx, 0, 0);
    n += SYM_PER_FRA;
    if (tx->meta_blocks > 1 && tx->cfg.enc != M17TX_ENC_AES) { tx->meta_idx = 1; latch_lsf(tx, 0); }
    return (int)n;
}

static void encode_voice(m17tx_t* tx, const int16_t* pcm, uint8_t data[PAYLOAD_BYTES])
{
    if (pcm)
    {
        codec2_encode(&tx->c2, &data[0], &pcm[0]);
        codec2_encode(&tx->c2, &data[8], &pcm[160]);
    }
    else
    {
        memcpy(&data[0], C2_SILENCE, 8);
        memcpy(&data[8], C2_SILENCE, 8);
    }
}

int m17tx_stream_voice(m17tx_t* tx, const int16_t pcm[320], int8_t* out)
{
    uint8_t data[PAYLOAD_BYTES];
    encode_voice(tx, pcm, data);
    encrypt_payload(tx, data);
    gen_frame_i8(out, data, FRAME_STR, &tx->lsf_tx, tx->lich_cnt, tx->fn);
    if (tx->cfg.sign) m17sig_frame(&tx->sig_ctx, tx->fn, data); /* FN and payload as transmitted */
    tx->fn = (tx->fn + 1) % 0x8000;
    tx->lich_cnt = (tx->lich_cnt + 1) % 6;
    if (tx->lich_cnt == 0)
    {
        if (tx->meta_blocks > 1) tx->meta_idx = (tx->meta_idx + 1) % tx->meta_blocks;
        latch_lsf(tx, 0);
    }
    return SYM_PER_FRA;
}

int m17tx_stream_end(m17tx_t* tx, const int16_t* pcm, int8_t* out)
{
    uint8_t data[PAYLOAD_BYTES];
    uint32_t n = 0;
    encode_voice(tx, pcm, data);
    encrypt_payload(tx, data);
    uint16_t fn = tx->fn;
    if (!tx->cfg.sign) fn |= 0x8000;
    gen_frame_i8(out, data, FRAME_STR, &tx->lsf_tx, tx->lich_cnt, fn);
    n += SYM_PER_FRA;
    tx->lich_cnt = (tx->lich_cnt + 1) % 6;
    if (tx->cfg.sign)
    {
        uint8_t sig[64], hash[M17SIG_HASH_LEN], auth[PAYLOAD_BYTES];
        m17sig_frame(&tx->sig_ctx, fn, data);

        /* Authentication Frame (FN 7FFB): signer, destination, time of signing; not encrypted */
        m17sig_auth_build(auth, tx->lsf_tx.src, tx->lsf_tx.dst, (uint32_t)(time(NULL) - M17SIG_EPOCH));
        gen_frame_i8(out + n, auth, FRAME_STR, &tx->lsf_tx, tx->lich_cnt, M17SIG_FN_AUTH);
        n += SYM_PER_FRA;
        tx->lich_cnt = (tx->lich_cnt + 1) % 6;
        m17sig_frame(&tx->sig_ctx, M17SIG_FN_AUTH, auth);

        m17sig_final(&tx->sig_ctx, hash);
        if (!uECC_sign(tx->cfg.priv_key, hash, sizeof(hash), sig, uECC_secp256r1()))
            memset(sig, 0, sizeof(sig)); /* no random numbers: a signature that won't verify, never a wrong one */
        fn = M17SIG_FN_SIG;
        for (int i = 0; i < 4; i++)
        {
            gen_frame_i8(out + n, &sig[i * PAYLOAD_BYTES], FRAME_STR, &tx->lsf_tx, tx->lich_cnt, fn);
            n += SYM_PER_FRA;
            fn = (fn < 0x7FFE) ? fn + 1 : (0x7FFF | 0x8000);
            tx->lich_cnt = (tx->lich_cnt + 1) % 6;
        }
    }
    uint32_t e = 0;
    gen_eot_i8(out + n, &e);
    n += e;
    return (int)n;
}

int m17tx_packet(m17tx_t* tx, const uint8_t* data, int len, int8_t* out)
{
    uint8_t pkt[PKT_MAX_FRAMES * PKT_CHUNK];
    if (len < 1 || len + 2 > (int)sizeof(pkt)) return -1;
    memcpy(pkt, data, (size_t)len);
    uint16_t crc = CRC_M17(pkt, (uint16_t)len);
    pkt[len] = crc >> 8;
    pkt[len + 1] = crc & 0xFF;
    int total = len + 2, frames = (total + PKT_CHUNK - 1) / PKT_CHUNK;

    lsf_t lsf = tx->lsf;                               /* packet mode: only Packet/Stream = 0 and CAN in TYPE */
    uint16_t t = (uint16_t)((tx->cfg.can & 0xF) << 7);
    /* out of spec (for now): a position in META, TYPE marked as a stream marks it (META subtype 1); several
       implementations do this and the spec is expected to follow. The META was set by m17tx_configure. */
    int pos = tx->cfg.meta_kind == M17TX_META_GNSS && tx->cfg.enc == M17TX_ENC_NONE;
    if (pos)
        t |= M17_TYPE_META_POSITION;
    else
        memset(lsf.meta, 0, sizeof(lsf.meta));
    lsf.type[0] = t >> 8;
    lsf.type[1] = t & 0xFF;
    update_LSF_CRC(&lsf);

    uint32_t n = 0;
    gen_preamble_i8(out, &n, PREAM_LSF);
    gen_frame_i8(out + n, NULL, FRAME_LSF, &lsf, 0, 0);
    n += SYM_PER_FRA;
    for (int k = 0; k < frames; k++)
    {
        int rem = total - k * PKT_CHUNK, take = rem < PKT_CHUNK ? rem : PKT_CHUNK;
        uint8_t chunk[PKT_CHUNK + 1] = {0};
        memcpy(chunk, &pkt[k * PKT_CHUNK], (size_t)take);
        chunk[PKT_CHUNK] = rem > PKT_CHUNK ? (uint8_t)((k & 0x1F) << 2) : (uint8_t)(0x80 | ((take & 0x1F) << 2));
        gen_frame_i8(out + n, chunk, FRAME_PKT, &lsf, 0, 0);
        n += SYM_PER_FRA;
    }
    uint32_t e = 0;
    gen_eot_i8(out + n, &e);
    n += e;
    return (int)n;
}

int m17tx_sms(m17tx_t* tx, const char* text, int len, int8_t* out)
{
    uint8_t d[PKT_MAX_FRAMES * PKT_CHUNK];
    if (len < 0 || len > PKT_MAX_FRAMES * PKT_CHUNK - 4) return -1;
    d[0] = 0x05;
    memcpy(&d[1], text, (size_t)len);
    d[1 + len] = 0;
    return m17tx_packet(tx, d, len + 2, out);
}

int m17tx_make_key(uint8_t priv[32], uint8_t pub[64])
{
    uECC_set_rng(urandom);
    return uECC_make_key(pub, priv, uECC_secp256r1());
}

int m17tx_public_key(const uint8_t priv[32], uint8_t pub[64])
{
    return uECC_compute_public_key(priv, pub, uECC_secp256r1());
}

int m17tx_pack(const int8_t* sym, int n, uint8_t* out)
{
    int bytes = n / 4;
    for (int i = 0; i < bytes; i++)
    {
        uint8_t b = 0;
        for (int k = 0; k < 4; k++)
        {
            int s = sym[4 * i + k];
            uint8_t d = s == 3 ? 1 : s == 1 ? 0 : s == -1 ? 2 : 3;
            b = (uint8_t)((b << 2) | d);
        }
        out[i] = b;
    }
    return bytes;
}
