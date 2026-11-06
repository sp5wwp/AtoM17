/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
/*
 * sdalsa: SignalDeck's root helper for phones whose radio audio is an ordinary ALSA stream but whose ROM lacks the
 * vendor audio service (a custom ROM or GSI on the Ulefone Armor 26 Ultra). Written against the kernel's ALSA ioctl
 * interface (<sound/asound.h>); SignalDeck runs it through Magisk su.
 *
 *   sdalsa list [card]                                    every mixer control and its value (read-only)
 *   sdalsa ctl  [-c card] NAME=VALUE ...                  set mixer controls
 *   sdalsa cap  [-c card] -d dev [-r rate] [-n ch] [-p period] [-k periods] [-q 1] [NAME=VALUE ...] [-- NAME=VALUE ...]
 *                (-q 1: drop whole periods while the pipe is full, rather than stall the capture)
 *   sdalsa play [-c card] -d dev [-r rate] [-n ch] [-p period] [-k periods] [-q 1] [-s keep_ms] [NAME=VALUE ...] [-- NAME=VALUE ...]
 *
 * cap writes interleaved S16_LE frames to stdout until stdout closes or the process is told to stop; play reads them
 * from stdin until end of input, then drains. Controls before "--" are set before the stream opens, the ones after it
 * when the stream ends. A control value is a number (several numbers separated by commas set several channels) or an
 * enumerated item's name. Exit status 0 = ok, 2 = usage, 3 = a control, 4 = the stream.
 */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <sound/asound.h>

static volatile sig_atomic_t stop;
static void on_signal(int s) { (void) s; stop = 1; }
static long now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000L + t.tv_nsec / 1000000L; }

static int ctl_open(int card, int flags) {
    char p[64]; snprintf(p, sizeof p, "/dev/snd/controlC%d", card);
    int fd = open(p, flags); if (fd < 0) fprintf(stderr, "sdalsa: %s: %s\n", p, strerror(errno));
    return fd;
}

/* the control's info by exact name; 0 = found */
static int ctl_find(int fd, const char *name, struct snd_ctl_elem_info *out) {
    struct snd_ctl_elem_list list; memset(&list, 0, sizeof list);
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) return -1;
    unsigned n = list.count; struct snd_ctl_elem_id *ids = calloc(n ? n : 1, sizeof *ids); if (!ids) return -1;
    list.space = n; list.pids = ids;
    int rc = -1;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) == 0)
        for (unsigned i = 0; i < list.used; i++)
            if (strcmp((const char *) ids[i].name, name) == 0) {
                memset(out, 0, sizeof *out); out->id = ids[i];
                rc = ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, out) < 0 ? -1 : 0; break;
            }
    free(ids); return rc;
}

static int enum_index(int fd, const struct snd_ctl_elem_info *info, const char *item) {
    for (unsigned k = 0; k < info->value.enumerated.items; k++) {
        struct snd_ctl_elem_info ei = *info; ei.value.enumerated.item = k;
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &ei) == 0 && strcmp(ei.value.enumerated.name, item) == 0) return (int) k;
    }
    return -1;
}

/* NAME=VALUE; 0 = set */
static int ctl_set(int fd, const char *arg) {
    const char *eq = strrchr(arg, '='); if (!eq || eq == arg) { fprintf(stderr, "sdalsa: not NAME=VALUE: %s\n", arg); return -1; }
    char name[SNDRV_CTL_ELEM_ID_NAME_MAXLEN]; size_t len = (size_t) (eq - arg);
    if (len >= sizeof name) return -1;
    memcpy(name, arg, len); name[len] = 0;
    const char *val = eq + 1;
    struct snd_ctl_elem_info info;
    if (ctl_find(fd, name, &info) != 0) { fprintf(stderr, "sdalsa: no control \"%s\"\n", name); return -1; }
    struct snd_ctl_elem_value v; memset(&v, 0, sizeof v); v.id = info.id;
    unsigned count = info.count > 128 ? 128 : info.count;
    if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
        int k = enum_index(fd, &info, val);
        if (k < 0) { fprintf(stderr, "sdalsa: \"%s\" has no item \"%s\"\n", name, val); return -1; }
        for (unsigned c = 0; c < count; c++) v.value.enumerated.item[c] = (unsigned) k;
    } else if (info.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN || info.type == SNDRV_CTL_ELEM_TYPE_INTEGER) {
        const char *p = val; long last = 0;
        for (unsigned c = 0; c < count; c++) {                 /* "1" sets every channel; "1,0" the first two, the rest like the last */
            if (*p) { char *end; last = strtol(p, &end, 10); if (end == p) { fprintf(stderr, "sdalsa: bad number \"%s\"\n", val); return -1; } p = *end == ',' ? end + 1 : end; }
            v.value.integer.value[c] = last;
        }
    } else { fprintf(stderr, "sdalsa: \"%s\": unsupported type %d\n", name, info.type); return -1; }
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &v) < 0) { fprintf(stderr, "sdalsa: write \"%s\": %s\n", name, strerror(errno)); return -1; }
    return 0;
}

static int ctl_set_all(int card, char **args, int n) {
    if (n <= 0) return 0;
    int fd = ctl_open(card, O_RDWR); if (fd < 0) return -1;
    int bad = 0; for (int i = 0; i < n; i++) if (ctl_set(fd, args[i]) != 0) bad++;
    close(fd); return bad ? -1 : 0;
}

static int list(int card) {
    int fd = ctl_open(card, O_RDONLY); if (fd < 0) return 3;
    struct snd_ctl_card_info ci; memset(&ci, 0, sizeof ci);
    if (ioctl(fd, SNDRV_CTL_IOCTL_CARD_INFO, &ci) == 0) printf("card %d: %s (%s)\n", card, (char *) ci.id, (char *) ci.name);
    struct snd_ctl_elem_list l; memset(&l, 0, sizeof l);
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &l) < 0) { close(fd); return 3; }
    unsigned n = l.count; struct snd_ctl_elem_id *ids = calloc(n ? n : 1, sizeof *ids); l.space = n; l.pids = ids;
    if (!ids || ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &l) < 0) { close(fd); return 3; }
    for (unsigned i = 0; i < l.used; i++) {
        struct snd_ctl_elem_info info; memset(&info, 0, sizeof info); info.id = ids[i];
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) continue;
        printf("%s\t", (char *) info.id.name);
        struct snd_ctl_elem_value v; memset(&v, 0, sizeof v); v.id = info.id;
        if (!(info.access & SNDRV_CTL_ELEM_ACCESS_READ) || ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &v) < 0) { printf("?\n"); continue; }
        unsigned c = info.count > 8 ? 8 : info.count;
        for (unsigned k = 0; k < c; k++) {
            if (k) putchar(',');
            if (info.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN || info.type == SNDRV_CTL_ELEM_TYPE_INTEGER) printf("%ld", v.value.integer.value[k]);
            else if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
                struct snd_ctl_elem_info ei = info; ei.value.enumerated.item = v.value.enumerated.item[k];
                printf("%s", ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &ei) == 0 ? ei.value.enumerated.name : "?");
            } else { printf("(type %d)", info.type); break; }
        }
        if (info.count > 8) printf(",... (%u values)", info.count);
        putchar('\n');
    }
    free(ids); close(fd); return 0;
}

/* ---- PCM ---- */
static void mask_set(struct snd_pcm_hw_params *p, int param, unsigned bit) {
    struct snd_mask *m = &p->masks[param - SNDRV_PCM_HW_PARAM_FIRST_MASK];
    memset(m, 0, sizeof *m); m->bits[bit >> 5] = 1u << (bit & 31);
}
static void interval_set(struct snd_pcm_hw_params *p, int param, unsigned v) {
    struct snd_interval *i = &p->intervals[param - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
    memset(i, 0, sizeof *i); i->min = i->max = v; i->integer = 1;
}

static int pcm_open(int card, int dev, int capture, unsigned rate, unsigned ch, unsigned period, unsigned periods) {
    char path[64]; snprintf(path, sizeof path, "/dev/snd/pcmC%dD%d%c", card, dev, capture ? 'c' : 'p');
    int fd = open(path, O_RDWR | O_NONBLOCK);                  /* a device someone else holds fails at once (EBUSY) instead of waiting */
    if (fd < 0) { fprintf(stderr, "sdalsa: %s: %s\n", path, strerror(errno)); return -1; }
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) & ~O_NONBLOCK);
    struct snd_pcm_hw_params hw; memset(&hw, 0, sizeof hw);
    for (int m = 0; m <= SNDRV_PCM_HW_PARAM_LAST_MASK - SNDRV_PCM_HW_PARAM_FIRST_MASK; m++) memset(&hw.masks[m], 0xFF, sizeof hw.masks[m]);
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; i++) { hw.intervals[i].min = 0; hw.intervals[i].max = UINT_MAX; }
    hw.rmask = ~0u; hw.info = ~0u;
    mask_set(&hw, SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    mask_set(&hw, SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
    mask_set(&hw, SNDRV_PCM_HW_PARAM_SUBFORMAT, SNDRV_PCM_SUBFORMAT_STD);
    interval_set(&hw, SNDRV_PCM_HW_PARAM_SAMPLE_BITS, 16);
    interval_set(&hw, SNDRV_PCM_HW_PARAM_FRAME_BITS, 16 * ch);
    interval_set(&hw, SNDRV_PCM_HW_PARAM_CHANNELS, ch);
    interval_set(&hw, SNDRV_PCM_HW_PARAM_RATE, rate);
    interval_set(&hw, SNDRV_PCM_HW_PARAM_PERIOD_SIZE, period);
    interval_set(&hw, SNDRV_PCM_HW_PARAM_PERIODS, periods);
    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &hw) < 0) { fprintf(stderr, "sdalsa: hw_params %u Hz %u ch %u x %u: %s\n", rate, ch, period, periods, strerror(errno)); close(fd); return -1; }
    snd_pcm_uframes_t buffer = (snd_pcm_uframes_t) period * periods;
    struct snd_pcm_sw_params sw; memset(&sw, 0, sizeof sw);
    sw.tstamp_mode = SNDRV_PCM_TSTAMP_NONE; sw.period_step = 1; sw.avail_min = period;
    sw.start_threshold = capture ? 1 : buffer;            /* playback starts once the buffer is full: no underrun right after key-up */
    sw.stop_threshold = buffer;
    snd_pcm_uframes_t boundary = buffer; while (boundary * 2 <= (snd_pcm_uframes_t) LONG_MAX - buffer) boundary *= 2;
    sw.boundary = boundary;
    if (ioctl(fd, SNDRV_PCM_IOCTL_SW_PARAMS, &sw) < 0) { fprintf(stderr, "sdalsa: sw_params: %s\n", strerror(errno)); close(fd); return -1; }
    if (ioctl(fd, SNDRV_PCM_IOCTL_PREPARE) < 0) { fprintf(stderr, "sdalsa: prepare: %s\n", strerror(errno)); close(fd); return -1; }
    if (capture && ioctl(fd, SNDRV_PCM_IOCTL_START) < 0) { fprintf(stderr, "sdalsa: start: %s\n", strerror(errno)); close(fd); return -1; }
    return fd;
}

static int write_all(int fd, const char *b, size_t n) {
    while (n > 0) { ssize_t w = write(fd, b, n); if (w < 0) { if (errno == EINTR && !stop) continue; return -1; } b += w; n -= (size_t) w; }
    return 0;
}

/* Free bytes in the pipe on fd, or -1 when fd is not a pipe (then nothing is dropped). */
static long pipe_free(int fd) {
    int cap = fcntl(fd, F_GETPIPE_SZ), queued = 0;
    if (cap <= 0 || ioctl(fd, FIONREAD, &queued) < 0) return -1;
    return (long) cap - queued;
}

/* drop: when the reader falls behind, drop whole periods instead of waiting on the pipe, so the card is always read in time
   (the Atom XL's USB bridge stops streaming for good after one overrun, 2026-10-28).
   EIO means no period came within the kernel's wait (2 s on the Atom XL). Its bridge then streams nothing until it is
   power-cycled (2026-10-29: re-preparing failed, and reopening the device three times gave no period either), so the
   capture ends at once and the caller's recovery power-cycles the bridge; the message says how long the gap was. */
static int capture_loop(int pcm, unsigned ch, unsigned period, int drop) {
    size_t fb = 2u * ch; char *buf = malloc(period * fb); if (!buf) return 4;
    unsigned long overruns = 0, dropped = 0;
    long last = now_ms();
    while (!stop) {
        struct snd_xferi x; memset(&x, 0, sizeof x); x.buf = buf; x.frames = period;
        if (ioctl(pcm, SNDRV_PCM_IOCTL_READI_FRAMES, &x) < 0) {
            if (errno == EINTR) continue;
            if (errno == EPIPE) { overruns++; if (ioctl(pcm, SNDRV_PCM_IOCTL_PREPARE) < 0 || ioctl(pcm, SNDRV_PCM_IOCTL_START) < 0) break; continue; }
            int e = errno;
            fprintf(stderr, "sdalsa: read: %s after %ld ms without a period (after %lu overruns, %lu periods dropped)\n", strerror(e), now_ms() - last, overruns, dropped); free(buf); return 4;
        }
        if (x.result <= 0) continue;
        last = now_ms();
        size_t n = (size_t) x.result * fb;
        if (drop) { long f = pipe_free(1); if (f >= 0 && (size_t) f < n) { dropped++; continue; } }
        if (write_all(1, buf, n) != 0) break;   /* SignalDeck closed the pipe: stop */
    }
    if (overruns || dropped) fprintf(stderr, "sdalsa: %lu capture overruns, %lu periods dropped\n", overruns, dropped);
    free(buf); return 0;
}

/* keep_ms > 0: audio that queued in the pipe while this helper started (SignalDeck's microphone runs from the key) is
   dropped down to keep_ms, so it does not delay the whole transmission (the Atom XL, 2026-10-31). */
static int play_loop(int pcm, unsigned ch, unsigned period, unsigned rate, unsigned keep_ms) {
    size_t fb = 2u * ch, want = period * fb; char *buf = malloc(want); if (!buf) return 4;
    unsigned long underruns = 0; int rc = 0;
    if (keep_ms > 0) {
        int queued = 0; size_t keep = (size_t) rate * keep_ms / 1000 * fb;
        if (ioctl(0, FIONREAD, &queued) == 0 && (size_t) queued > keep) {
            size_t skip = ((size_t) queued - keep) / fb * fb, n = 0;
            while (n < skip) { ssize_t r = read(0, buf, skip - n < want ? skip - n : want); if (r < 0 && errno == EINTR) continue; if (r <= 0) break; n += (size_t) r; }
            if (n % fb) { size_t pad = fb - n % fb; while (pad > 0) { ssize_t r = read(0, buf, pad); if (r <= 0) break; pad -= (size_t) r; } }   /* stay on a frame boundary */
            fprintf(stderr, "sdalsa: dropped %zu ms queued at start\n", n / fb * 1000 / rate);
        }
    }
    while (!stop) {
        size_t got = 0;
        while (got < want) { ssize_t r = read(0, buf + got, want - got); if (r < 0 && errno == EINTR && !stop) continue; if (r <= 0) break; got += (size_t) r; }
        if (got < fb) break;                                  /* end of input */
        struct snd_xferi x; memset(&x, 0, sizeof x); x.buf = buf; x.frames = got / fb;
        while (ioctl(pcm, SNDRV_PCM_IOCTL_WRITEI_FRAMES, &x) < 0) {
            if (errno == EINTR && !stop) continue;
            if (errno == EPIPE) { underruns++; if (ioctl(pcm, SNDRV_PCM_IOCTL_PREPARE) == 0) continue; }
            fprintf(stderr, "sdalsa: write: %s\n", strerror(errno)); rc = 4; goto out;
        }
        if (got < want) break;
    }
    /* a stream shorter than the buffer never reached the start threshold: start it, then let it play out */
    { snd_pcm_sframes_t delay = 0; if (ioctl(pcm, SNDRV_PCM_IOCTL_DELAY, &delay) == 0 && delay > 0) ioctl(pcm, SNDRV_PCM_IOCTL_START); }
    ioctl(pcm, SNDRV_PCM_IOCTL_DRAIN);
out:
    if (underruns) fprintf(stderr, "sdalsa: %lu playback underruns\n", underruns);
    free(buf); return rc;
}

static int usage(void) { fprintf(stderr, "usage: sdalsa list [card] | ctl [-c card] NAME=VALUE... | cap|play [-c card] -d dev [-r rate] [-n ch] [-p period] [-k periods] [NAME=VALUE...] [-- NAME=VALUE...]\n"); return 2; }

int main(int argc, char **argv) {
    if (argc < 2) return usage();
    signal(SIGPIPE, SIG_IGN);                                 /* a closed pipe is an error return, so the after-controls still run */
    struct sigaction sa; memset(&sa, 0, sizeof sa); sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL); sigaction(SIGHUP, &sa, NULL); sigaction(SIGINT, &sa, NULL);
    const char *mode = argv[1];
    if (strcmp(mode, "list") == 0) return list(argc > 2 ? atoi(argv[2]) : 0);
    int card = 0, dev = -1, drop = 0; unsigned rate = 8000, ch = 2, period = 256, periods = 4, keep_ms = 0;
    char **pre = calloc((size_t) argc, sizeof *pre), **post = calloc((size_t) argc, sizeof *post); int npre = 0, npost = 0, after = 0;
    if (!pre || !post) return 4;
    for (int i = 2; i < argc; i++) {
        const char *a = argv[i];
        if (strcmp(a, "--") == 0) { after = 1; continue; }
        if (!after && a[0] == '-' && a[1] && !a[2] && i + 1 < argc) {
            long v = strtol(argv[++i], NULL, 10);
            switch (a[1]) { case 'c': card = (int) v; break; case 'd': dev = (int) v; break; case 'r': rate = (unsigned) v; break;
                            case 'n': ch = (unsigned) v; break; case 'p': period = (unsigned) v; break; case 'k': periods = (unsigned) v; break; case 'q': drop = v != 0; break; case 's': keep_ms = (unsigned) v; break; default: return usage(); }
            continue;
        }
        if (after) post[npost++] = argv[i]; else pre[npre++] = argv[i];
    }
    if (strcmp(mode, "ctl") == 0) return ctl_set_all(card, pre, npre) == 0 ? 0 : 3;
    int capture = strcmp(mode, "cap") == 0;
    if ((!capture && strcmp(mode, "play") != 0) || dev < 0 || ch < 1 || ch > 8 || period < 16 || periods < 2) return usage();
    if (ctl_set_all(card, pre, npre) != 0) { ctl_set_all(card, post, npost); return 3; }
    int pcm = pcm_open(card, dev, capture, rate, ch, period, periods);
    int rc = pcm < 0 ? 4 : capture ? capture_loop(pcm, ch, period, drop) : play_loop(pcm, ch, period, rate, keep_ms);
    if (pcm >= 0) close(pcm);
    if (ctl_set_all(card, post, npost) != 0 && rc == 0) rc = 3;
    return rc;
}
