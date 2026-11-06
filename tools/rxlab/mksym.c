/* writes the symbols (int8, one per byte) of an M17 transmission: "sms TEXT" or "voice N" */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "m17tx.h"
static int8_t s[200 * 192];
int main(int argc, char** argv) {
    m17tx_t* tx = m17tx_create(); m17tx_config_t c; memset(&c, 0, sizeof c);
    strcpy(c.src, "SP5WWP"); strcpy(c.dst, "@ALL"); m17tx_configure(tx, &c);
    int n;
    if (!strcmp(argv[1], "sms")) n = m17tx_sms(tx, argv[2], (int)strlen(argv[2]), s);
    else { n = m17tx_stream_begin(tx, s); int16_t pcm[320] = {0}; for (int f = 0; f < atoi(argv[2]); f++) n += m17tx_stream_voice(tx, pcm, s + n); n += m17tx_stream_end(tx, NULL, s + n); }
    fwrite(s, 1, n, stdout); fprintf(stderr, "%d symbols\n", n); return 0;
}
