/*
 * AtoM17 - M17 transmit encoder: microphone PCM in, M17 symbols (+3/+1/-1/-3) out.
 * The framing, META handling, encryption and signing follow gr-m17's m17_coder
 * (Copyright 2023 jmfriedt, 2024-2026 Wojciech Kaczmarski, GPL-3.0-or-later).
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef M17TX_H
#define M17TX_H

#include <stdint.h>

#define M17TX_SYM_PER_FRAME 192

/* encryption */
#define M17TX_ENC_NONE  0
#define M17TX_ENC_SCRAM 1
#define M17TX_ENC_AES   2

/* META kind when not encrypted */
#define M17TX_META_TEXT 0
#define M17TX_META_GNSS 1
#define M17TX_META_ECD  2

typedef struct
{
    char     src[10];        /* callsigns, up to 9 characters */
    char     dst[10];        /* "@ALL" for broadcast */
    int      can;            /* 0..15 */
    int      meta_kind;      /* M17TX_META_*, ignored with AES (META = nonce) */
    char     meta_text[53];  /* text, up to 52 bytes UTF-8, up to 4 blocks */
    /* GNSS */
    int      gnss_source;    /* 0 = M17 client */
    int      gnss_station;   /* 0 fixed, 1 mobile, 2 handheld */
    float    lat, lon, alt_m, speed_kmh;
    int      bearing;
    int      gnss_validity;  /* M17_GNSS_* bits */
    /* ECD */
    char     ecd_cf1[10], ecd_cf2[10];
    /* encryption */
    int      enc;            /* M17TX_ENC_* */
    int      aes_bits;       /* 128, 192 or 256 */
    uint8_t  aes_key[32];
    int      scram_bits;     /* 8, 16 or 24 */
    uint32_t scram_seed;
    /* signature */
    int      sign;           /* 1 = signed stream (ECDSA secp256r1) */
    uint8_t  priv_key[32];
} m17tx_config_t;

typedef struct m17tx m17tx_t;

m17tx_t* m17tx_create(void);
void     m17tx_destroy(m17tx_t* tx);
/* returns 0, or a negative number when the configuration cannot be used (bad callsign, key size, private key) */
int      m17tx_configure(m17tx_t* tx, const m17tx_config_t* cfg);

/* Stream: preamble + LSF (384 symbols). Returns the symbol count. */
int      m17tx_stream_begin(m17tx_t* tx, int8_t* out);
/* One 40 ms stream frame from 320 samples of 8 kHz mono PCM (two Codec2 3200 frames). Returns 192. */
int      m17tx_stream_voice(m17tx_t* tx, const int16_t pcm[320], int8_t* out);
/* The end of the stream: last frame (EOS), the 4 signature frames when signed, and the EoT marker. `pcm` may be
   NULL (silence). Returns the symbol count (at most 6 * 192). */
int      m17tx_stream_end(m17tx_t* tx, const int16_t* pcm, int8_t* out);

/* Packet mode: one SMS (protocol 0x05). Writes preamble, LSF, packet frames and EoT; returns the symbol count or a
   negative number if `len` is too long (max 821 bytes). `out` must hold (3 + 33) * 192 symbols. */
int      m17tx_sms(m17tx_t* tx, const char* text, int len, int8_t* out);
/* Packet mode with raw packet data (first byte = protocol). */
int      m17tx_packet(m17tx_t* tx, const uint8_t* data, int len, int8_t* out);

/* Key helpers (secp256r1, big-endian X||Y public key). */
int      m17tx_make_key(uint8_t priv[32], uint8_t pub[64]);
int      m17tx_public_key(const uint8_t priv[32], uint8_t pub[64]);

/* Pack symbols for the V076J symbol engine: 4 symbols per byte, MSB first, +3->01 +1->00 -1->10 -3->11. */
int      m17tx_pack(const int8_t* sym, int n, uint8_t* out);

#endif
