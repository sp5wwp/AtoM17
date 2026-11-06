/*
 * AtoM17 - input AGC for libm17rx's FM input. The Atom XL's receive audio arrives as USB-card counts with an
 * unknown gain; m17rx_process_fm() wants Hz and decodes only within about 0.3x..2x of true Hz (host test,
 * tools/hosttest/loopback.c). The AGC scales the block's RMS to that of an M17 signal at 2.4 kHz outer deviation.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef M17AGC_H
#define M17AGC_H
#include <math.h>

#ifndef M17AGC_TARGET_HZ
#define M17AGC_TARGET_HZ 1700.0f
#endif

typedef struct { double ms; float gain; float target; } m17agc_t;

static inline void m17agc_init(m17agc_t* a) { a->ms = 0; a->gain = 0.1f; a->target = M17AGC_TARGET_HZ; }

/* scales x[0..n) in place */
static inline void m17agc_run(m17agc_t* a, float* x, int n)
{
    double s = 0;
    for (int i = 0; i < n; i++) s += (double)x[i] * x[i];
    double ms = s / (n > 0 ? n : 1);
    double k = ms > a->ms ? 0.5 : 0.15;             /* fast attack; release within ~0.3 s, so a carrier after open-squelch noise is followed */
    a->ms = a->ms <= 0 ? ms : a->ms + (ms - a->ms) * k;
    if (a->ms > 1e-6) a->gain = (float)(a->target / sqrt(a->ms));
    for (int i = 0; i < n; i++) x[i] *= a->gain;
}
#endif
