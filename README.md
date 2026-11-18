# AtoM17

FM and M17 receiver for the Unihertz Atom XL's built-in radio, and nothing else. Root (Magisk) required.

AtoM17 is **based on SignalDeck** (© 2026 IIMacGyverII, GPL-3.0-or-later, see `COPYRIGHT.SignalDeck`). It reuses
SignalDeck's bridge protocol, power sequences, V076J symbol-engine transmit path and firmware loader. Only the parts
FM and M17 need were taken, there is no DMR, no other mode and no network service. It is a modified work and not
SignalDeck.

## Screenshots

<p>
<img src="pics/main.png" width="250" alt="Main screen, receiving an M17 call">
<img src="pics/heard.png" width="250" alt="Heard list">
<img src="pics/inbox.png" width="250" alt="SMS inbox">
</p>
<p>
<img src="pics/main-fm.png" width="250" alt="Main screen, FM, high power">
<img src="pics/diagnostics.png" width="250" alt="Diagnostics, receiving">
<img src="pics/settings.png" width="250" alt="Settings">
</p>

M17 reception is benchmarked against a USRP B210 reference.

## What it does

* **FM:** 400-480 MHz, squelch 0-9, CTCSS receive and transmit (51 tones), 12.5/25 kHz, low/high power.
* **PTT:** the side key, learned once (Settings -> PTT key -> Learn PTT key) and read from the kernel input device as root
  (`getevent`), so it works with the screen off. An on-screen toggle can be turned on as a fallback.
* **M17 receive:**
  * voice, link info, and SMS
  * META: multi-block text, GNSS position, extended callsign data (ECD)
  * AES-128/192/256 and 8/16/24-bit scrambler decryption
  * ECDSA signature checks against stored public keys (M17 Specification 2.1.0: SHA-256 stream hash and the
    Authentication Frame, with the signer, destination and time of signing shown)
* **Receive measurements** (Settings -> Diagnostics):
  * symbol error rate before FEC
  * equaliser EVM
  * sync symbols
  * LICH corrections
  * Viterbi metric
  * preamble level
  * inner/outer level ratio
  * the eye of the equalised symbols
  * the equalised symbol-level histogram
  * the symbol errors per frame

  They are taken on the equaliser: libm17rx decodes a re-synthesised signal, so its own EVM, deviation and offset
  would describe that instead. The Atom's audio path removes DC, so the carrier offset cannot be measured.
* **M17 transmit** (needs a modified firmware in the module): Codec2 3200 voice, CAN, the same three META kinds (position from the
  phone's GNSS), AES or scrambler, signed streams (spec 2.1.0, key generated on the phone), and SMS (packet mode). All of it goes
  through the V076J symbol engine.
* **Firmware:** loads V076J or restores the vendor V076 with SignalDeck's loader sequence. Both images are built on the
  phone from its own `/vendor/data/dmr/DMR006.U4A.V076.bin`. The APK carries only patches (`assets/*.patch.json`). The
  P1 loader bridge is built the same way from `/vendor/data/mcu/20201016161541.bin`.

Libraries: libm17 1.4.3 (`49c3010`), Codec2-mod `split-no-doubles` (`1e5d642`), micro-ecc (M17 fork), libm17rx
from `m17_sdrsharp` (`10be6e8`). The TX encoder (`app/src/main/cpp/m17tx.c`) follows gr-m17's `m17_coder`.

## Build

```
export ANDROID_HOME=~/android-sdk     # platform 34, build-tools 34, NDK 26.3.11579264, CMake 3.22.1
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
```
Host test of the M17 core:
```
C=app/src/main/cpp; T=$C/third_party; N=$T/codec2-mod-neural
gcc -O2 -std=gnu11 -w -DSUPPRESS_PERF_WARNINGS -o loopback tools/hosttest/loopback.c $C/m17tx.c $C/m17rx/src/*.c \
  $T/libm17/m17.c $T/libm17/{decode,encode,math,payload,phy}/*.c $T/codec2-mod/src/*.c $T/micro-ecc/uECC.c $T/sha-2/sha-256.c \
  $N/src/c2fargan.c $N/src/c2f_features.c $N/model/fargan_data.c $N/third_party/opus/dnn/{fargan,nnet,nnet_default,parse_lpcnet_weights}.c \
  -I$C -I$C/m17rx/include -I$C/m17rx/src -I$T/libm17 -I$T/codec2-mod/inc -I$T/micro-ecc -I$T/sha-2 \
  -I$N/include -I$N/src -I$N/model -I$N/third_party/opus -I$N/third_party/opus/dnn -I$N/third_party/opus/celt -I$N/third_party/opus/include \
  -Itools/hosttest -lm && ./loopback
```

CI: `.github/workflows/build.yml` builds the APK and runs the host test on every push, and attaches the APK to a
release for a `v*` tag. Give it a fixed signing key through four repository secrets (see the workflow file), or every
CI build is signed with a different throw-away key and needs an uninstall first.

## Install and first run

1. `adb install app-debug.apk`. Use **Wi-Fi adb**: while the radio is on, the phone's USB socket belongs to the radio
   bridge and a cable connection drops.
2. Open AtoM17, allow the microphone (and location, for GNSS META), then tap the power icon at the top and grant root in Magisk.
3. AtoM17 then:
   * stops and disables the stock intercom app (`com.agold.intercom`);
   * stops the radio HAL;
   * turns off Android's USB audio routing;
   * powers the bridge.

   **Stop** reverses all of this. The stock app is re-enabled only if the bridge last answered with its stock build.
4. If the App block dialog appears, turn off "background cleanup" for AtoM17, or the phone stops it a few minutes
   after the screen goes off.

## Licences

The app is GPL-3.0-or-later (`LICENSE`), with SignalDeck's additional terms (`COPYRIGHT.SignalDeck`) for the files
derived from it. Third-party licences are in each directory under `app/src/main/cpp/third_party/` and
`app/src/main/cpp/m17rx/` (GPL-2.0-or-later with the SDR# exception). See `docs/PROTOCOL.md` for the hardware
protocol.
