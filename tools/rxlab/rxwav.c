/* rxlab: run a 16-bit mono WAV through AGC (+ optional pre-filter) into libm17rx. Usage: rxwav file.wav [invert] */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "m17rx.h"
#include "m17agc.h"
int main(int argc, char** argv) {
    FILE* f = fopen(argv[1], "rb"); if (!f) return 1;
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 44, SEEK_SET);
    int n = (int)((sz - 44) / 2); short* s = malloc(n * 2); fread(s, 2, n, f); fclose(f);
    int rate = argc > 3 ? atoi(argv[3]) : 8000;
    m17rx_t* rx = m17rx_create(); m17rx_set_input_rate(rx, rate);
    m17rx_config_t c; m17rx_default_config(&c); c.invert = argc > 2 && atoi(argv[2]); m17rx_set_config(rx, &c);
    m17agc_t agc; m17agc_init(&agc);
    int blk = rate / 25, frames = 0, starts = 0, lsfok = 0; float x[8000];
    for (int o = 0; o + blk <= n; o += blk) {
        for (int i = 0; i < blk; i++) x[i] = s[o + i];
        m17agc_run(&agc, x, blk);
        m17rx_process_fm(rx, x, blk);
        short pcm[4000]; while (m17rx_read_audio(rx, pcm, 4000) > 0) { }
        m17rx_event_t ev; while (m17rx_next_event(rx, &ev)) {
            if (ev.kind == M17RX_EV_STREAM_START) { starts++; if (!ev.link.from_lich) lsfok++; printf("start %s > %s lich=%d\n", ev.link.src, ev.link.dst, ev.link.from_lich); }
            if (ev.kind == M17RX_EV_STREAM_END) { frames += ev.frames; printf("end %d frames lost %d evm %.1f\n", ev.frames, ev.frames_lost, ev.evm_rms_pct); }
        }
    }
    m17rx_status_t st; m17rx_get_status(rx, &st);
    printf("TOTAL starts=%d frames=%d polarity_hint=%d sync=%.2f\n", starts, frames, st.polarity_hint, st.sync_quality);
    return 0;
}
