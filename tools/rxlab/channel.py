#!/usr/bin/env python3
"""Fit a model of the Atom XL receive path (ideal M17 baseband at 8 kHz -> the radio's 8 kHz audio) on SignalDeck's
s7 recording, then pass synthetic transmissions through it: linear FIR + a cubic compression term, plus noise."""
import sys, numpy as np
sys.argv = [sys.argv[0]] + sys.argv[1:]
exec(open('/home/claude/atom17/tools/rxlab/fit_eq.py').read().split("if __name__")[0])
j = json.load(open(R + 'm17_rx_atomxl_s7.json')); part = j['parts'][0]
sym = np.array([2 * (ord(c) - 48) - 3 for c in part['symbols']], float)
x = wav(R + 'm17_rx_atomxl_s7.wav'); xm = x - np.mean(x)
d, m = ideal(sym, len(x), part['start8k'] + 12, 125)
# forward model: x ~ h * d + c * (h*d)^3
half = 64
idx = np.where(m)[0]; idx = idx[(idx >= half) & (idx < len(x) - half)]
A = np.stack([d[idx + k] for k in range(-half, half + 1)], axis=1)
h = np.linalg.lstsq(A, xm[idx], rcond=None)[0]
lin = A @ h
c = np.dot(lin ** 3, xm[idx] - lin) / np.dot(lin ** 3, lin ** 3)
res = xm[idx] - (lin + c * lin ** 3)
print('model SNR %.1f dB, cubic %.3g, residual rms %.0f' % (10 * np.log10(np.var(xm[idx]) / np.var(res)), c, np.std(res)))
np.save('/home/claude/atom17/build-host/chan.npy', np.concatenate([[c, np.std(res)], h]))

def through(symfile, out):
    s = np.frombuffer(open(symfile, 'rb').read(), dtype=np.int8).astype(float)
    lead = 4000
    dd, mm = ideal(s, lead + int(len(s) * 8000 / 4800) + 8000, lead, 0)
    y = np.convolve(dd, h[::-1], mode='same'); y = y + c * y ** 3
    y += np.random.default_rng(1).normal(0, np.std(res), len(y))
    save(out, y)
for a in sys.argv[1:]: through(a, a.replace('.sym', '.wav'))
