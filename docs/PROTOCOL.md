# Unihertz Atom XL radio: host protocol as AtoM17 uses it

Taken from SignalDeck `1.0.0-beta.32` (commit `2b2736e4`, GPL-3.0-or-later, © 2026 IIMacGyverII) and the
public write-up `androidradio_firmware_project/research/atom-xl-v076-m17.md`. Every item below is something
SignalDeck does on the bench unit and reports as measured. Nothing here was measured by AtoM17 itself. Items marked
**(SD bench)** come from SignalDeck's code comments and have not been checked independently.

## 1. Hardware chain

```
app ──/dev/ttyACM0 (USB CDC-ACM, 115200 raw)──► STM32F411 bridge ──USART1 57600 8N1──► HR-C7000 (C-SKY V2) + AT1846S
    ──USB Audio Class card 1 dev 0, S16_LE 2ch 32 kHz (capture = RX audio, playback = FM TX audio)──►
```

* The bridge sits on the phone's **only USB controller**. While the radio is powered, the controller is in host
  mode, so adb over the cable and MTP drop out. Use Wi-Fi adb for any bench work.
* The vendor HAL `vendor.mediatek.hardware.aguiextmodule@1.0-service` (init service `aguiextmodule`) holds the
  port. AtoM17 stops it with `stop aguiextmodule` and starts it again on exit.

### sysfs / device nodes (all under `/sys/bus/platform/drivers/mcu/`)

| node | meaning |
|---|---|
| `mcu_power` | bridge power, `1`/`0`; reading it gives `power on = N` |
| `usb_path` | `1` = USB controller to the bridge (host) |
| `rstmcu` | bridge BOOT0/reset strap. `0` after power-up = normal boot. `1` before power-up = STM32 ROM DFU |
| `handle_ptt` | PTT line, **active low** (`0` = keyed, `1` = idle). With the P1/B4 bridge, a `0` held at power-up puts the module into its ROM loader |
| `/dev/stmcu` | reception edges, one byte per edge: `0x00` = reception started, `0x01` = reception ended. The first ~2 s after power-up are toggles and are ignored |

`setprop vendor.agui.extmodule.open 1|0` and `setprop vendor.agui.extmodule.start 1|0` (PTT) mirror what the HAL sets.

### SELinux (magiskpolicy --live)
```
allow untrusted_app ttyACM_device chr_file { open read write ioctl getattr lock }
typeattribute ttyACM_device mlstrustedobject
allow untrusted_app agui_device chr_file { open read getattr }
typeattribute agui_device mlstrustedobject
```
The audio helper runs as root through `su --mount-master` (a plain app su cannot open the USB card **(SD bench)**).

### Bring-up (from the HAL's own sequence)
```
stop aguiextmodule; sleep 0.5
[if mcu_power says "= 1": full RESET first, see below]
setprop vendor.agui.extmodule.open 1; echo 1 > usb_path; echo 1 > mcu_power; sleep 0.75; echo 0 > rstmcu
wait ≤3 s for /dev/ttyACM0  (miss → RESET, power up again, wait ≤10 s)
sleep 2; chmod 0666 /dev/ttyACM0; stty -F /dev/ttyACM0 115200 raw -echo; echo 1 > handle_ptt
settings put secure usb_audio_automatic_routing_disabled 1   (otherwise Android grabs the card as a headset)
```
RESET = `mcu_power 0`, wait for `/sys/class/tty/ttyACM0` to go, then the HAL's pulse (`usb_path 1; mcu_power 1;
rstmcu 0; 0.2 s; mcu_power 0`), `usb_path 0`, then toggle `<udc controller>/cmode` 0→1 so the USB socket comes back.
Shutdown = RESET + `setprop …open 0` + `start aguiextmodule`.

Don't toggle `cmode` while the bridge is coming up after a DFU write. On the SD bench that wedged the controller
until a phone restart.

## 2. Control envelope (`/dev/ttyACM0`)

```
7E 96 69 | route | cmd | rw | len_hi len_lo | body… | ck_hi ck_lo | 81
```
* `rw` = 01 for a request, 00 for a reply or push. `len` is big-endian.
* `ck` is the 16-bit one's-complement sum of big-endian words over route…body (an odd last byte goes in the high
  half), folded, then `^ 0xFFFF`.
* Route `00` is the bridge itself, `01` is the module (the bridge re-frames to `68 cmd rw sr ck len body 10`), and
  `02` is the bridge's audio reports (`67 01` when the capture opens, `66 01` when it closes).
* Zero bytes between frames are USB padding and are skipped.
* For module replies the result byte is in the body: for set commands (`22 23 2E 30 31`) the body is one byte,
  `00` = OK. For data commands (`24 25 2B 2D 32 34 36 40`) the body is the data.

### Bridge commands (route 00)
| cmd | body | use |
|---|---|---|
| `01` | `01` | build date (ASCII). Stock `20201016161541`, B4 `20261102000001`, P1 `20261102000002` |
| `08` | `01` / `00` | update mode on/off (with it on, module bytes come back in cmd `09`) |
| `09` | ≤256 bytes | raw bytes to the module UART. In update mode each module byte returns in its own cmd-09 reply |

### Module commands AtoM17 uses (route 01)
| cmd | body | notes |
|---|---|---|
| `34` | `01` | version → ASCII `DMR006.U4A.V0xx[letter]` |
| `23` | 19 bytes LE, see below | analog channel write |
| `30` | `sq` 0…9 | squelch |
| `2E` | `vol` | volume |
| `26` | `01` key / `02` unkey | **M17 symbol key only.** FM keys with the `handle_ptt` line, not 0x26 |
| `3B` | seconds | module TX timeout (0 = off; the start-up default is 120 s) |
| `32` | `01` | RSSI: dBm = −120 + raw − 17. **Never while the USB capture is open** (see §4) |
| `40` | opcode… | V076J symbol engine (§3) |

`0x23` body: `rxHz u32 · txHz u32 · band (0 = 12.5 kHz, 1 = 25 kHz) · power (0 low, 1 high) · sq · rxType · rxSub ·
txType · txSub · pwrSave=2 · volume · monitor (1 on / 2 off) · relay=2`. Tone type 0 = none and 1 = CTCSS (the sub
code indexes the 51-tone table that starts at 62.5 Hz). The module refuses a `0` in the enum bytes. Its parser
accepts only 400.000–490.000 MHz, and the phone's band is 400–480 MHz.

## 3. M17 transmit on V076J (command 0x40)

| op | body | reply body |
|---|---|---|
| `7F` | — | capability `[1, lo, hi]`: bit0 = flash read, bit1 = M17 TX (V076J = `[1,03,00]`) |
| `03` | — | reset the symbol ring |
| `01` | 48 bytes = 192 dibits | `fill_lo fill_hi` (symbols in the 2048-symbol ring) |
| `07` | last chunk | as op 1, and the module drops the carrier once the ring has played out |
| `02` | — | status: `fill, underrun, err` (u16 LE each) |
| `0C` | offset/length | flash read-back (1…240 bytes) |

Dibits are packed MSB first, four per byte: `+3→01, +1→00, −1→10, −3→11`.

Sequence (SD bench, m17-cxx-demod decoded 75/75 frames):
1. op 3 (retry up to 3× with 300 ms gaps; the first reply after an unkey can be lost).
2. Stream 48-byte chunks with op 1. Retry a chunk up to 8× with 40 ms gaps if no reply.
3. Send **0x26 01** once 8 chunks (1536 symbols, 320 ms) are in the ring. The ring drains only while keyed.
4. After the key: if the fill is over 1500, poll op 2 every 20 ms until it drops below.
5. The last chunk goes as **op 7**. Wait 800 ms (the module sends no status-4 for a symbol key), then **0x26 02**.
6. Write the channel (`0x23`) once more, or the receiver stays muted after a symbol key.

The shaping (9-tap, 5-phase RRC) and deviation (`0x11000504 = 0x000F1010` re-asserted on every refill) are in the
firmware. The write-up gives no absolute deviation figure yet.

## 4. Receive audio

* The capture runs as root: `sdalsa cap -c 1 -d 0 -r 32000 -n 2 -p 1280 -k 4 -q 1` (interleaved S16_LE stereo on
  stdout).
* FM audio is FM-demodulated. With `sq` = 0 the module plays continuously, which is what M17 RX needs: the
  stream goes to `m17rx_process_fm()` at 32 kHz.
* **(SD bench)** Rules that keep the bridge's audio alive:
  * No `0x32` while the capture is open. One query stops the bridge's audio until it is power-cycled.
  * The bridge cuts module replies short while the capture streams, so stop the capture before any command
    whose reply you read, and restart it afterwards.
  * A capture left open while the module plays nothing hits the kernel's 2 s I/O error, and after that the bridge
    streams nothing until it is power-cycled. With squelch > 0, open the capture on a `/dev/stmcu` `00` edge and
    close it on `01`.
* FM TX audio: `sdalsa play -c 1 -d 0 -r 32000 -n 2 -k 4 -s 60` with the microphone upsampled 8→32 kHz.

## 5. Firmware loading

### Bridge (STM32F411) through its ROM DFU, `/vendor/bin/dfu-util`
* **Enter:** `mcu_power 0`, wait for the tty to go, `usb_path 1; rstmcu 1; mcu_power 1`, wait ≤10 s for
  `0483:df11`.
* **Back up:** `dfu-util -d 0483:df11 -a 0 -s 0x08000000:0x80000 -U file` (512 KiB).
* **Write:** `dfu-util -d 0483:df11 -a 0 -s 0x08000000 -D file`, then read back. Pass only if the read-back is
  the image followed by `0xFF` to the end.
* **Leave:** `rstmcu 0; mcu_power 0`. First boot: `usb_path 1; mcu_power 1; 0.75 s; rstmcu 0`, wait ≤45 s
  for the port to stay up, then power off. **No cmode toggle.**
* **Images:** the stock bridge is the phone's own `/vendor/data/mcu/20201016161541.bin` (55,011 B,
  sha256 `76063054…0abb`). P1 = stock + 9 edits (`bridge-p1.patch.json`, 55,562 B, sha256 `4b744510…2854`).
  P1 copies the PTT line to the module's loader strap PA7 at power-up and passes module bytes losslessly in
  update mode.

### Module (HR-C7000) through P1
1. Hold PTT (`handle_ptt 0`), `stop aguiextmodule`, `mcu_power 0`, wait for the node to go, `usb_path 0`, then
   power up as usual. Wait ≤15 s until `stty -g` works.
2. `00 01 01` must return the P1/B4 date, then `00 08 01` (update mode on).
3. Send `'6'` in cmd 09 and wait for `HardVersion 1.1`. (The hardware check also sends `'7'`, which must start
   with `002`, then `'3'` to run the application without writing anything.)
4. Send `'1'` and wait for `'C'`, then release PTT.
5. YModem **stop-and-wait, 128-byte SOH blocks**, CRC-16/XMODEM. Block 0 = `name\0size`, the last data block zero-padded (as SignalDeck sends it), then EOT
   and an empty block 0. One block per cmd-09 envelope. The loader erases only after block 0 is accepted, so a CAN
   before block 0 leaves the running image intact.
6. `00 08 00`, normal power cycle, then `0x34` must return the new tag.
7. Write the stock bridge back.

There is no read-back on the stock loader, so whatever image is installed now (V082 on your unit) cannot be saved
first. V076J adds opcode 12 for read-back afterwards.

### Images AtoM17 builds on the phone (no vendor bytes in the APK)
| image | built from | sha256 |
|---|---|---|
| V076 (restore) | `/vendor/data/dmr/DMR006.U4A.V076.bin` as is | `4f5f287b…4d40fe` |
| V076J | V076 + `v076j.patch.json` (9 runs, 1,811 B) | `e8e44635…98bfea` |
