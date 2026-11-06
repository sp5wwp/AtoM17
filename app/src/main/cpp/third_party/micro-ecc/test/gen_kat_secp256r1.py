#!/usr/bin/env python3
"""Generate the known-answer vectors used by test/test_kat_secp256r1.c.

Every vector is computed with plain integer arithmetic (no micro-ecc involved)
and then independently checked with OpenSSL through the `cryptography` package.
All values are in the standard big-endian byte order (SEC 1 / X9.62), which is
what OpenSSL, `openssl ecparam` key files and M17 tooling expect.

Usage: python3 test/gen_kat_secp256r1.py
"""
import hashlib

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils

P = 2**256 - 2**224 + 2**192 + 2**96 - 1
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
     0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)


def add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0:
        return None
    if a == b:
        lam = (3 * a[0] * a[0] - 3) * pow(2 * a[1], -1, P) % P
    else:
        lam = (b[1] - a[1]) * pow(b[0] - a[0], -1, P) % P
    x = (lam * lam - a[0] - b[0]) % P
    return x, (lam * (a[0] - x) - a[1]) % P


def mul(k, pt):
    acc = None
    while k:
        if k & 1:
            acc = add(acc, pt)
        pt = add(pt, pt)
        k >>= 1
    return acc


def sign(d, e, k):
    r = mul(k, G)[0] % N
    s = pow(k, -1, N) * (e + r * d) % N
    return r, s


def h(x, n=32):
    return x.to_bytes(n, "big").hex()


def openssl_verify(pub, digest, r, s):
    key = ec.EllipticCurvePublicNumbers(pub[0], pub[1], ec.SECP256R1()).public_key()
    # A digest shorter than 32 bytes is used as-is by ECDSA (bits2int); a
    # left-zero-padded 32-byte prehash yields the same integer.
    padded = bytes(32 - len(digest)) + digest
    key.verify(utils.encode_dss_signature(r, s), padded,
               ec.ECDSA(utils.Prehashed(hashes.SHA256())))


vectors = []

# 1) RFC 6979, appendix A.2.5: P-256, SHA-256, message "sample".
d = 0xC9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721
k = 0xA6E3C57DD01ABE90086538398355DD4C3B17AA873382B0F24D6129493D8AAD60
digest = hashlib.sha256(b"sample").digest()
r, s = sign(d, int.from_bytes(digest, "big"), k)
assert (r, s) == (0xEFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716,
                  0xF7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8)
vectors.append(("RFC 6979 A.2.5, SHA-256(\"sample\")", d, k, digest, r, s))

# 2) M17-style: the 16-byte stream digest is signed directly, without hashing.
d = int.from_bytes(hashlib.sha256(b"M17 KAT private key").digest(), "big") % N
k = int.from_bytes(hashlib.sha256(b"M17 KAT nonce").digest(), "big") % N
digest = hashlib.sha256(b"M17 KAT stream digest").digest()[:16]
r, s = sign(d, int.from_bytes(digest, "big"), k)
vectors.append(("M17 16-byte stream digest", d, k, digest, r, s))

for name, d, k, digest, r, s in vectors:
    pub = mul(d, G)
    openssl_verify(pub, digest, r, s)
    print("    { /* %s */" % name)
    print('        "%s",' % h(d))
    print('        "%s",' % (h(pub[0]) + h(pub[1])))
    print('        "%s",' % h(k))
    print('        "%s",' % digest.hex())
    print('        "%s",' % h(r))
    print('        "%s"' % h(s))
    print("    },")
