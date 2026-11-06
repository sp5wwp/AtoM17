/* rxlab: float32 symbol stream (192 per 40 ms) -> m17resynth -> libm17rx at 48 kHz. */
#include <stdio.h>
#include <stdlib.h>
#include "m17rx.h"
#include "m17resynth.h"
int main(int argc, char** argv) {
    FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f) / 4; fseek(f, 0, SEEK_SET);
    float* s = malloc(n * 4); fread(s, 4, n, f); fclose(f);
    m17rx_t* rx = m17rx_create(); m17rx_set_input_rate(rx, 48000);
    m17resynth_t rs; m17resynth_init(&rs);
    int frames = 0, starts = 0, audio = 0; float out[1920];
    for (long o = 0; o + 192 <= n; o += 192) {
        m17resynth_run(&rs, s + o, 192, out);
        m17rx_process_fm(rx, out, 1920);
        short pcm[4000]; int a; while ((a = m17rx_read_audio(rx, pcm, 4000)) > 0) audio += a;
        m17rx_event_t ev; while (m17rx_next_event(rx, &ev)) {
            if (ev.kind == M17RX_EV_STREAM_START) { starts++; printf("start %s > %s lich=%d\n", ev.link.src, ev.link.dst, ev.link.from_lich); }
            if (ev.kind == M17RX_EV_PACKET) printf("PACKET proto %d, %d bytes: %s\n", ev.protocol, ev.length, ev.sms);
            if (ev.kind == M17RX_EV_PACKET_BAD) printf("PACKET_BAD\n");
            if (ev.kind == M17RX_EV_STREAM_END) { frames += ev.frames; printf("end %d frames, lost %d, evm %.1f%%\n", ev.frames, ev.frames_lost, ev.evm_rms_pct); }
        }
    }
    m17rx_status_t st; m17rx_get_status(rx, &st);
    printf("TOTAL starts=%d frames=%d audio=%d dev=%.0f\n", starts, frames, audio, st.deviation_hz);
    return 0;
}
