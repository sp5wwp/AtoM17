/* Two-step signing: uECC_sign_precompute() + uECC_sign_finish().
   Licensed under the BSD 2-clause license. */

#include "uECC.h"

#include <stdio.h>
#include <string.h>

int main() {
    int i, c;
    uint8_t private[32] = {0};
    uint8_t public[64] = {0};
    uint8_t hash[32] = {0};
    uint8_t sig[64] = {0};
    uint8_t presig[uECC_PRESIGNATURE_BYTES];

    const struct uECC_Curve_t * curves[5];
    int num_curves = 0;
#if uECC_SUPPORTS_secp160r1
    curves[num_curves++] = uECC_secp160r1();
#endif
#if uECC_SUPPORTS_secp192r1
    curves[num_curves++] = uECC_secp192r1();
#endif
#if uECC_SUPPORTS_secp224r1
    curves[num_curves++] = uECC_secp224r1();
#endif
#if uECC_SUPPORTS_secp256r1
    curves[num_curves++] = uECC_secp256r1();
#endif
#if uECC_SUPPORTS_secp256k1
    curves[num_curves++] = uECC_secp256k1();
#endif

    printf("Testing 256 two-step signatures\n");
    for (c = 0; c < num_curves; ++c) {
        for (i = 0; i < 256; ++i) {
            if (!uECC_make_key(public, private, curves[c])) {
                printf("uECC_make_key() failed\n");
                return 1;
            }
            memcpy(hash, public, sizeof(hash));

            if (!uECC_sign_precompute(presig, curves[c])) {
                printf("uECC_sign_precompute() failed\n");
                return 1;
            }
            if (!uECC_sign_finish(private, hash, sizeof(hash), presig, sig, curves[c])) {
                printf("uECC_sign_finish() failed\n");
                return 1;
            }
            if (!uECC_verify(public, hash, sizeof(hash), sig, curves[c])) {
                printf("uECC_verify() failed\n");
                return 1;
            }

            /* a precomputation serves one signature only */
            if (uECC_sign_finish(private, hash, sizeof(hash), presig, sig, curves[c])) {
                printf("uECC_sign_finish() accepted a used precomputation\n");
                return 1;
            }

            /* short hashes, as M17 signs a 16-byte digest */
            if (!uECC_sign_precompute(presig, curves[c]) ||
                !uECC_sign_finish(private, hash, 16, presig, sig, curves[c]) ||
                !uECC_verify(public, hash, 16, sig, curves[c])) {
                printf("16-byte hash failed\n");
                return 1;
            }
            hash[0] ^= 1;
            if (uECC_verify(public, hash, 16, sig, curves[c])) {
                printf("uECC_verify() accepted a changed hash\n");
                return 1;
            }
        }
    }
    printf("passed\n");
    return 0;
}
