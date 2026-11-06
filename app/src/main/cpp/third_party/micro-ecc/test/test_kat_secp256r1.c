/* Known-answer tests for secp256r1 (NIST P-256).
 *
 * Checks that keys, digests and signatures go in and out of the public API in
 * the standard big-endian byte order used by OpenSSL and the M17 protocol, and
 * that signing with a fixed nonce gives exactly the expected (r, s).
 * The vectors come from test/gen_kat_secp256r1.py and were verified with OpenSSL.
 *
 * Exits with 0 when every check passes, 1 otherwise, so it can run from CTest.
 * Licensed under the BSD 2-clause license, like the rest of micro-ecc.
 */
#include "uECC.h"
#include "types.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Mirrors the default in uECC.c, for the summary line only. */
#ifndef uECC_COMB_MULT
    #if uECC_WORD_SIZE == 4
        #define uECC_COMB_MULT 1
    #else
        #define uECC_COMB_MULT 0
    #endif
#endif

extern int uECC_sign_with_k(const uint8_t *private_key,
                            const uint8_t *message_hash,
                            unsigned hash_size,
                            const uint8_t *k,
                            uint8_t *signature,
                            uECC_Curve curve);

typedef struct {
    const char *name;
    const char *private_key; /* 32 bytes */
    const char *public_key;  /* 64 bytes, X || Y */
    const char *k;           /* 32 bytes, fixed nonce */
    const char *digest;      /* signed as-is, no hashing */
    const char *r;           /* 32 bytes */
    const char *s;           /* 32 bytes */
} kat_t;

static const kat_t kats[] = {
    {
        "RFC 6979 A.2.5, SHA-256(\"sample\")",
        "c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721",
        "60fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6"
        "7903fe1008b8bc99a41ae9e95628bc64f2f1b20c2d7e9f5177a3c294d4462299",
        "a6e3c57dd01abe90086538398355dd4c3b17aa873382b0f24d6129493d8aad60",
        "af2bdbe1aa9b6ec1e2ade1d694f41fc71a831d0268e9891562113d8a62add1bf",
        "efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716",
        "f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8"
    },
    {
        "M17 16-byte stream digest",
        "f166f4643cd902f9234b9fcf20db9c823f4d1a0c7fb19d78cb68f01bcfb6a6d9",
        "6bc9730fe2d5281ce8f793282df1a763bd92dc9947745c351cdaa62fcb065eec"
        "10df38a627b5199d2a06597152119fd9a5b82b714c8c9a485dff493c46d35c72",
        "7260d20662e37d7f05161504b8ab96a9b3caa4c1f652e532309e3930fbc816a8",
        "bed38d52506d14234eae0d44b5593bac",
        "3075e2a5ede2bad7cefc58a697b115826452111420384d9703d1726f50b1cce3",
        "ac893eed27054ab4485aaaf66e1ac8b481210e302f4246a939c56502a20f1b17"
    },
};

static int failures = 0;

static void check(int ok, const char *name, const char *what)
{
    if (!ok) {
        printf("  FAIL  %s: %s\n", name, what);
        failures++;
    }
}

static unsigned from_hex(const char *hex, uint8_t *out, unsigned max)
{
    unsigned n = (unsigned)strlen(hex) / 2;
    unsigned i;
    if (n > max) {
        fprintf(stderr, "test vector too long\n");
        exit(1);
    }
    for (i = 0; i < n; i++) {
        unsigned v;
        if (sscanf(hex + 2 * i, "%2x", &v) != 1) {
            fprintf(stderr, "bad hex in test vector\n");
            exit(1);
        }
        out[i] = (uint8_t)v;
    }
    return n;
}

static void print_hex(const char *label, const uint8_t *p, unsigned n)
{
    unsigned i;
    printf("        %-9s", label);
    for (i = 0; i < n; i++)
        printf("%02x", p[i]);
    printf("\n");
}

/* Test-only RNG (blinding and random-nonce signing). Not for real keys. */
static uint64_t rng_state = 0x9E3779B97F4A7C15ull;

static int test_rng(uint8_t *dest, unsigned size)
{
    while (size--) {
        rng_state ^= rng_state << 13;
        rng_state ^= rng_state >> 7;
        rng_state ^= rng_state << 17;
        *dest++ = (uint8_t)rng_state;
    }
    return 1;
}

static void reverse(uint8_t *p, unsigned n)
{
    unsigned i;
    for (i = 0; i < n / 2; i++) {
        uint8_t t = p[i];
        p[i] = p[n - 1 - i];
        p[n - 1 - i] = t;
    }
}

int main(void)
{
    const uECC_Curve curve = uECC_secp256r1();
    unsigned i, j;

    uECC_set_rng(test_rng);

    printf("micro-ecc secp256r1 known-answer tests "
           "(word size %d, comb %d, native little-endian %d)\n",
           (int)uECC_WORD_SIZE, (int)uECC_COMB_MULT,
           (int)uECC_VLI_NATIVE_LITTLE_ENDIAN);
#if uECC_VLI_NATIVE_LITTLE_ENDIAN
    printf("NOTE: uECC_VLI_NATIVE_LITTLE_ENDIAN=1 reverses the byte order of every key,\n"
           "      digest and signature, so OpenSSL and other M17 implementations can't\n"
           "      read them. Build with uECC_VLI_NATIVE_LITTLE_ENDIAN=0.\n");
#endif

    for (i = 0; i < sizeof(kats) / sizeof(kats[0]); i++) {
        const kat_t *t = &kats[i];
        uint8_t priv[32], pub[64], k[32], digest[32], expect_sig[64];
        uint8_t got_pub[64], sig[64], tmp[64];
        unsigned dlen;
        int pub_ok, sig_ok;

        printf("- %s\n", t->name);

        from_hex(t->private_key, priv, 32);
        from_hex(t->public_key, pub, 64);
        from_hex(t->k, k, 32);
        dlen = from_hex(t->digest, digest, 32);
        from_hex(t->r, expect_sig, 32);
        from_hex(t->s, expect_sig + 32, 32);

        /* 1. Public key derivation, in big-endian X || Y. */
        pub_ok = uECC_compute_public_key(priv, got_pub, curve)
                 && memcmp(got_pub, pub, 64) == 0;
        check(pub_ok, t->name, "public key differs from the expected value");
        if (!pub_ok) {
            print_hex("expected", pub, 64);
            print_hex("got", got_pub, 64);
        }

        check(uECC_valid_public_key(pub, curve), t->name,
              "expected public key rejected as invalid");

        /* 2. Signing with a fixed nonce gives exactly (r, s). */
        sig_ok = uECC_sign_with_k(priv, digest, dlen, k, sig, curve)
                 && memcmp(sig, expect_sig, 64) == 0;
        check(sig_ok, t->name, "signature with fixed k differs from the expected (r, s)");
        if (!sig_ok) {
            print_hex("expected", expect_sig, 64);
            print_hex("got", sig, 64);
        }

        /* 3. Verification of the known-good signature. */
        check(uECC_verify(pub, digest, dlen, expect_sig, curve) == 1, t->name,
              "known-good signature rejected");

        /* 4. Negative checks. */
        memcpy(tmp, digest, dlen);
        tmp[dlen - 1] ^= 0x01;
        check(uECC_verify(pub, tmp, dlen, expect_sig, curve) == 0, t->name,
              "signature accepted for a modified digest");

        memcpy(tmp, expect_sig, 64);
        reverse(tmp, 32);
        reverse(tmp + 32, 32);
        check(uECC_verify(pub, digest, dlen, tmp, curve) == 0, t->name,
              "byte-reversed signature accepted");

        /* 5. Random-nonce signatures verify against the expected key. */
        for (j = 0; j < 16; j++) {
            if (!uECC_sign(priv, digest, dlen, sig, curve)
                || uECC_verify(pub, digest, dlen, sig, curve) != 1) {
                check(0, t->name, "random-nonce sign/verify round trip failed");
                break;
            }
        }
    }

    if (failures)
        printf("%d check(s) FAILED\n", failures);
    else
        printf("all checks passed\n");

    return failures ? 1 : 0;
}
