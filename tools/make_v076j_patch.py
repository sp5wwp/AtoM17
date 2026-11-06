#!/usr/bin/env python3
"""Make app/src/main/assets/v076j.patch.json: the byte runs where V076J differs from the vendor's V076.
The APK then carries only the M17 symbol-engine patch (SignalDeck's work), never the vendor's image; the phone
builds V076J from its own /vendor/data/dmr/DMR006.U4A.V076.bin and both hashes are checked."""
import hashlib, json, sys
base, patched, out = sys.argv[1:4]
a, b = open(base, 'rb').read(), open(patched, 'rb').read()
assert len(a) == len(b)
edits, i = [], 0
while i < len(b):
    if a[i] != b[i]:
        j = i
        while j < len(b) and a[j] != b[j]: j += 1
        edits.append({"at": i, "bytes": b[i:j].hex()}); i = j
    else: i += 1
doc = {"name": "DMR006.U4A.V076J (SignalDeck M17 symbol engine on command 0x40)",
       "source": "AndroidRadio_Firmware_Project firmware/unihertz-atom-xl/DMR006.U4A.V076J (GPL-3.0-or-later, (C) 2026 IIMacGyverII)",
       "tag": "DMR006.U4A.V076J", "baseSha256": hashlib.sha256(a).hexdigest(), "baseSize": len(a),
       "size": len(b), "sha256": hashlib.sha256(b).hexdigest(), "edits": edits}
json.dump(doc, open(out, 'w'), indent=1)
print(len(edits), "runs,", sum(len(e["bytes"]) // 2 for e in edits), "bytes")
