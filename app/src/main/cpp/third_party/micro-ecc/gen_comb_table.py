#!/usr/bin/env python3
"""
Generates the precomputed comb table + correction point for constant-time
fixed-base (G) scalar multiplication on secp256r1, as used by
comb_g_mult.inc's EccPoint_mult_G_comb().

Parameters (must match COMB_W / COMB_T_COLS in comb_g_mult.inc):
    W       = 4   comb window width (bits per digit)
    T_COLS  = 67  number of comb columns -> 66 doublings, 67 table adds
                  capacity = W*T_COLS = 268 bits = n's 256 bits + 12 bits
                  headroom for Coron-style per-call scalar blinding (r < 256).

Design (see accompanying writeup for full derivation):
  - h_i = 2^(i*T_COLS) * G,  i = 0..W-1
  - T'[idx] = B + sum_{i: bit i of idx set} h_i,  for a fixed public point B
    (this "additive blinding" guarantees no table entry is ever the point at
    infinity, which is what makes the multiply constant-time -- a naive comb
    table without this trick needs a data-dependent branch on digit==0,
    which leaks the secret scalar's bit pattern through timing).
  - C = (2^T_COLS - 1) * B  is subtracted once at the end to cancel B's
    accumulated contribution.

Re-run this script and paste its output into comb_g_mult.inc if you ever
change B, W, or T_COLS. Do not hand-edit the generated tables.
"""

p  = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
a  = -3 % p
b  = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
Gx = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
Gy = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5
n  = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G  = (Gx, Gy)

W = 4
T_COLS = 67
B_SCALAR = 0xB1   # arbitrary fixed public scalar defining the blinding point B

def inv(x, m):
    return pow(x, m - 2, m)

def padd(P, Q):
    if P is None: return Q
    if Q is None: return P
    (x1, y1), (x2, y2) = P, Q
    if x1 == x2:
        if (y1 + y2) % p == 0:
            return None
        lam = (3 * x1 * x1 + a) * inv(2 * y1, p) % p
    else:
        lam = (y2 - y1) * inv((x2 - x1) % p, p) % p
    x3 = (lam * lam - x1 - x2) % p
    y3 = (lam * (x1 - x3) - y1) % p
    return (x3, y3)

def pdouble(P):
    return padd(P, P)

def pmul(P, k):
    R = None
    for bit in bin(k)[2:]:
        R = pdouble(R)
        if bit == '1':
            R = padd(R, P)
    return R

def words_le(val, nwords=8):
    return [(val >> (32 * i)) & 0xFFFFFFFF for i in range(nwords)]

def fmt_point_row(pt):
    xw = words_le(pt[0]); yw = words_le(pt[1])
    xs = ", ".join(f"0x{w:08X}" for w in xw)
    ys = ", ".join(f"0x{w:08X}" for w in yw)
    return f"{{ {xs},\n      {ys} }}"

def main():
    hs = [pmul(G, pow(2, i * T_COLS, n)) for i in range(W)]
    B = pmul(G, B_SCALAR)

    TABLE = []
    for idx in range(1 << W):
        pt = B
        for i in range(W):
            if idx & (1 << i):
                pt = padd(pt, hs[i])
        TABLE.append(pt)
    assert all(t is not None for t in TABLE), "table must never contain infinity"

    C = pmul(B, (pow(2, T_COLS, n) - 1) % n)

    print(f"static const uECC_word_t comb_table_secp256r1[{1<<W}][16] = {{")
    for idx, pt in enumerate(TABLE):
        comma = "," if idx < (1 << W) - 1 else ""
        print(f"    {fmt_point_row(pt)}{comma}  /* T'[{idx}] */")
    print("};")
    print()
    print("static const uECC_word_t comb_correction_secp256r1[16] =")
    print("    " + fmt_point_row(C) + ";")

    # self-check against naive double-and-add
    import random
    random.seed(0)
    def digit_at(kk, col):
        d = 0
        for i in range(W):
            d |= ((kk >> (i * T_COLS + col)) & 1) << i
        return d
    def neg(P):
        return None if P is None else (P[0], (-P[1]) % p)
    def comb_mult_ct(kk):
        R = TABLE[digit_at(kk, T_COLS - 1)]
        for j in range(T_COLS - 2, -1, -1):
            R = pdouble(R)
            R = padd(R, TABLE[digit_at(kk, j)])
        return padd(R, neg(C))
    ok = True
    for _ in range(500):
        k = random.randrange(1, n)
        r = random.randrange(0, 256)
        kb = k + r * n
        if comb_mult_ct(kb) != pmul(G, k):
            ok = False
            break
    import sys
    print(f"\n/* self-check (500 random trials incl. Coron blinding): "
          f"{'PASS' if ok else 'FAIL'} */", file=sys.stderr)

if __name__ == "__main__":
    main()
