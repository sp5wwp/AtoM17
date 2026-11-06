#!/usr/bin/env python3
"""Fit a linear FIR equaliser (8 kHz) that maps the Atom XL's receive audio to the ideal M17 FM baseband, from a
recording with known symbols (SignalDeck's test clips). Writes the taps and equalised WAVs for testing with rxwav."""
import json, sys, wave, numpy as np
R = '/tmp/sd_src/signaldeck/app/src/test/resources/m17/'

def wav(p):
    w = wave.open(p); return np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float64)

def rrc(alpha=0.5, span=8, sps=10):
    t = np.arange(-span * sps // 2, span * sps // 2 + 1) / sps
    h = np.zeros_like(t)
    for i, x in enumerate(t):
        if abs(x) < 1e-9: h[i] = 1 - alpha + 4 * alpha / np.pi
        elif abs(abs(x) - 1 / (4 * alpha)) < 1e-9: h[i] = alpha / np.sqrt(2) * ((1 + 2 / np.pi) * np.sin(np.pi / (4 * alpha)) + (1 - 2 / np.pi) * np.cos(np.pi / (4 * alpha)))
        else: h[i] = (np.sin(np.pi * x * (1 - alpha)) + 4 * alpha * x * np.cos(np.pi * x * (1 + alpha))) / (np.pi * x * (1 - (4 * alpha * x) ** 2))
    return h / np.sqrt(np.sum(h ** 2))

def ideal(sym, n8, start8k, ppm):
    """ideal FM baseband (Hz, +-2400 outer) at 8 kHz, symbol k at 8 kHz position start8k + k*(8000/4800)*(1+ppm)"""
    sps = 10; up = np.zeros(len(sym) * sps); up[::sps] = sym
    y48 = np.convolve(up, rrc(), mode='full')[40:40 + len(up)]   # RRC delay removed: symbol k at index k*10
    g = np.dot(y48[::sps], sym) / np.dot(sym, sym); y48 *= 800.0 / g  # +-3 -> +-2400 Hz at symbol instants
    t8 = np.arange(n8)
    pos = (t8 - start8k) / ((8000 / 4800) * (1 + ppm * 1e-6)) * sps     # index into y48
    d = np.interp(pos, np.arange(len(y48)), y48, left=0, right=0)
    mask = (pos >= 0) & (pos < len(y48) - 1)
    return d, mask

def design(x, d, mask, ntaps, lam=1e-3):
    """least squares, taps centred (non-causal); returns h"""
    half = ntaps // 2
    idx = np.where(mask)[0]; idx = idx[(idx >= half) & (idx < len(x) - half)]
    A = np.stack([x[idx + k] for k in range(-half, half + 1)], axis=1)
    AtA = A.T @ A; AtA += lam * np.trace(AtA) / len(AtA) * np.eye(len(AtA))
    h = np.linalg.solve(AtA, A.T @ d[idx])
    err = d[idx] - A @ h
    return h, 10 * np.log10(np.sum(d[idx] ** 2) / np.sum(err ** 2))

def apply(x, h): return np.convolve(x, h[::-1], mode='same')

def save(p, y):
    y = np.clip(y / np.max(np.abs(y)) * 20000, -32767, 32767).astype('<i2')
    w = wave.open(p, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(y.tobytes()); w.close()

if __name__ == '__main__':
    ntaps = int(sys.argv[1]) if len(sys.argv) > 1 else 65
    j = json.load(open(R + 'm17_rx_atomxl_s7.json')); part = j['parts'][0]
    sym = np.array([2 * (ord(c) - 48) - 3 for c in part['symbols']], float) * part['polarity']
    x = wav(R + 'm17_rx_atomxl_s7.wav'); x -= np.mean(x)
    best = None
    for ppm in range(0, 251, 25):
        for off in np.arange(-3, 3.01, 0.5):
            d, m = ideal(sym, len(x), part['start8k'] + off, ppm)
            h, snr = design(x, d, m, 33)
            if best is None or snr > best[0]: best = (snr, ppm, off)
    print('alignment: SNR %.1f dB at %d ppm, offset %.1f samples' % best)
    _, ppm, off = best
    d, m = ideal(sym, len(x), part['start8k'] + off, ppm)
    for nt in (17, 33, 65, 129):
        h, snr = design(x, d, m, nt); print('  %3d taps: fit SNR %.1f dB' % (nt, snr))
    h, snr = design(x, d, m, ntaps)
    np.savetxt('/home/claude/atom17/build-host/eq_atomxl_8k.txt', h)
    for name in ('m17_rx_atomxl_s7', 'm17_rx_atomxl_s9', 'm17_rx_armor_on_atomxl'):
        y = wav(R + name + '.wav'); y -= np.mean(y)
        save('/home/claude/atom17/build-host/%s_eq.wav' % name, apply(y, h))
    # frequency response of the fitted equaliser (what the radio removed)
    H = np.fft.rfft(h, 1024); f = np.fft.rfftfreq(1024, 1 / 8000)
    for fr in (50, 100, 200, 300, 500, 1000, 2000, 3000):
        print('  gain at %4d Hz: %+.1f dB' % (fr, 20 * np.log10(abs(H[np.argmin(abs(f - fr))]) + 1e-12)))
