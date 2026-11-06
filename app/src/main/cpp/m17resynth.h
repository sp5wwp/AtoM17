/*
 * AtoM17 - re-synthesis of an ideal M17 baseband from equalised symbols, for libm17rx's FM input.
 * The Atom XL's receive audio is high-passed (~250 Hz) and compressed by the module's FM path; a decision-feedback
 * equaliser (SignalDeck's M17Receiver) recovers the symbols, and this turns them back into what an ideal discriminator
 * would give: RRC (alpha 0.5) pulses at 10 samples a symbol, 48 kHz, +-2400 Hz outer deviation.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef M17RESYNTH_H
#define M17RESYNTH_H
#include <string.h>

extern const float rrc_taps_10[8 * 10 + 1];   /* libm17 */

typedef struct { float up[81]; int pos; float scale; } m17resynth_t;

static inline void m17resynth_init(m17resynth_t* r)
{
    memset(r, 0, sizeof(*r));
    /* a lone symbol's peak through one RRC is rrc_taps_10[40]; one symbol unit (1/3 of the outer level) -> 800 Hz */
    r->scale = 800.0f / rrc_taps_10[40];
}

/* n symbols in, n * 10 samples (48 kHz, Hz) out */
static inline int m17resynth_run(m17resynth_t* r, const float* sym, int n, float* out)
{
    int o = 0;
    for (int i = 0; i < n; i++)
    {
        float s = sym[i];
        if (s > 4.0f) s = 4.0f; else if (s < -4.0f) s = -4.0f;
        for (int k = 0; k < 10; k++)
        {
            memmove(r->up, r->up + 1, sizeof(float) * 80);
            r->up[80] = k == 0 ? s : 0.0f;
            float acc = 0;
            for (int t = 0; t < 81; t++) acc += r->up[80 - t] * rrc_taps_10[t];
            out[o++] = acc * r->scale;
        }
    }
    return o;
}
#endif
