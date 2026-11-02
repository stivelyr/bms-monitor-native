# BLE protocol reference

This document records what the current source code implements for the Haitai/Hinergi battery companion. It is a code-level reference, **not a vendor-issued protocol specification**. Offsets, units, alarm labels, and command effects must be verified against the exact BMS model and firmware before being treated as authoritative.

The implementation is based on a reference `0x8900` BLE protocol. Compatibility is not established for every Haitai/Hinergi pack. There is no target battery attached to the current build environment, and a successful BLE operation does not confirm a physical battery action.

## Service and characteristics

All UUIDs below use the standard Bluetooth base UUID suffix `-0000-1000-8000-00805f9b34fb`, except the separate advertised-service constant noted below.

| UUID short form | Full UUID | Role in this app |
|---|---|---|
| `0x8900` | `00008900-0000-1000-8000-00805f9b34fb` | Required vendor service. The app refuses normal operation if this service is absent. |
| `0x8901` | `00008901-0000-1000-8000-00805f9b34fb` | Optional auth/handshake characteristic. The app attempts notification setup and writes a fixed all-`FF` payload when available. |
| `0x8902` | `00008902-0000-1000-8000-00805f9b34fb` | Status packet read on each refresh cycle. |
| `0x8903` | `00008903-0000-1000-8000-00805f9b34fb` | First cell-voltage block. |
| `0x8904` | `00008904-0000-1000-8000-00805f9b34fb` | Second cell-voltage block. |
| `0x8905` | `00008905-0000-1000-8000-00805f9b34fb` | Fault/alarm block A. |
| `0x8906` | `00008906-0000-1000-8000-00805f9b34fb` | Fault/alarm block B. |
| `0x8907` | `00008907-0000-1000-8000-00805f9b34fb` | Third cell-voltage block. |
| `0x8908` | `00008908-0000-1000-8000-00805f9b34fb` | Info UUID constant exists in code; the app does not currently read it. |
| `0x8910` | `00008910-0000-1000-8000-00805f9b34fb` | Command characteristic used by the implemented control buttons. |
| `0x2902` | `00002902-0000-1000-8000-00805f9b34fb` | Standard Client Characteristic Configuration Descriptor, used to request auth notifications when present. |

`BmsProtocol.kt` also declares `6e400001-b5a3-f393-e0a9-e50e24dcca9e` as an advertised-service UUID. The current scan filter accepts a device name beginning with `BTD` or an advertisement containing the vendor `0x8900` service; it does not use that separate constant as an acceptance condition.

## Handshake

When `0x8901` exists, the app attempts to enable local notifications and write the notification descriptor if available. It then sends exactly 17 bytes, each `0xFF`:

```text
FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF
```

If the auth characteristic or its descriptor is unavailable, or the handshake write reports failure, the app logs the condition and continues trying to read battery data. The fixed payload is an implementation behavior, not evidence of cryptographic authentication or a security guarantee.

### Original APK handshake comparison

Static inspection of the uploaded original APK shows a stricter app-level handshake flow on the same `0x8901` characteristic: it enables notifications, waits about 500 ms, writes the same 17-byte all-`FF` payload, then waits for a notification. The APK treats a nonempty notification whose first byte is `0x00` as handshake success. The native companion currently does not make that response check before proceeding with reads. This is an observed app behavior, not a vendor specification or proof that later control commands changed the battery state.

## Status packet `0x8902`

`parseStatus()` requires at least 16 bytes. Multi-byte values are big-endian. Extra bytes after offset 15 are ignored; they are not parsed as capacity or any other field.

| Byte offset | Current interpretation in code | Conversion / notes |
|---|---|---|
| `0` | State index | `0` Bergerak (moving), `1` Diam (idle), `2` Penyimpanan (storage), `3` Tidur (sleep), `4` Tidur dalam (deep sleep); other values become “unknown.” These labels are code mappings, not independently verified meanings. |
| `1–2` | Pack voltage | Unsigned 16-bit big-endian, divided by `100.0` to produce volts. |
| `3–4` | Pack current | Signed 16-bit big-endian two's-complement, divided by `100.0` to produce amps. |
| `5` | SOC | Unsigned byte, displayed as percent. The gauge clamps its visual value to 0–100; the raw field is not itself evidence that the BMS value is valid. |
| `6` | SOH | Unsigned byte, displayed as percent. |
| `7` | Speed | Unsigned byte displayed as a raw value; units and scaling are unknown. |
| `8` | Unmapped | Ignored. |
| `9` | Pack temperature | Signed Java/Kotlin byte converted directly to an integer and displayed with °C. No scale or offset correction is applied. |
| `10` | Minimum cell temperature | Signed byte converted directly to an integer and displayed with °C. |
| `11` | Maximum cell temperature | Signed byte converted directly to an integer and displayed with °C. |
| `12` | Unmapped | Ignored. |
| `13` | Cell count | Unsigned byte. When positive, the combined parsed cell list is trimmed to this many entries. |
| `14` | BMS status | Unsigned byte displayed as a raw hexadecimal value; bit meanings are unknown. |
| `15` | Has-fault flag | True only when this unsigned byte is exactly `1`. If no detailed fault bit was decoded, the app shows a generic BMS fault. |

### Capacity is intentionally unavailable

The recovered field map in the source establishes bytes `0–15` only. The app does not estimate remaining/full capacity from later bytes. Capacity cards display unavailable placeholders until a field definition, unit, scaling, and hardware behavior have been verified. Do not reintroduce a capacity estimate based only on plausible-looking values in trailing bytes.

## Cell-voltage blocks `0x8903`, `0x8904`, `0x8907`

Each block shorter than 20 bytes is ignored. Complete blocks are read as a sequence of two-byte, unsigned big-endian values; the parser reads at most the first 20 bytes in each block (up to ten readings per block) and converts each raw value using:

```text
volts = raw_value / 1000.0
```

Values equal to `0` or `0xFFFF` are omitted. The three lists are concatenated in the order `0x8903`, `0x8904`, `0x8907`. If status reports a positive cell count, the combined list is trimmed to that count; otherwise all parsed readings are kept. There is no additional range validation or gap filling.

The screen marks the highest and lowest parsed cells. It also displays an exclamation mark when a cell is more than 100 mV from the average of the parsed cells. That 100 mV is a presentation heuristic, not a protocol-defined or safety-certified alarm threshold.

## Fault blocks `0x8905` and `0x8906`

The current decoder only maps the listed bits. Unlisted bits are ignored. The label wording below is translated to English for readability; the UI labels are Indonesian.

| Block | Byte offset | Bit | Current label |
|---|---:|---:|---|
| `0x8905` | 1 | 2 | Excessive charge current |
| `0x8905` | 1 | 3 | Excessive discharge current |
| `0x8905` | 1 | 4 | Short circuit |
| `0x8905` | 1 | 5 | High charging temperature |
| `0x8905` | 1 | 6 | High discharging temperature |
| `0x8905` | 1 | 7 | Low charging temperature |
| `0x8905` | 2 | 0 | Low discharging temperature |
| `0x8905` | 2 | 1 | Charge MOS fault |
| `0x8905` | 2 | 2 | Discharge MOS fault |
| `0x8905` | 2 | 3 | Internal communication error |
| `0x8906` | 5 | 0 | Large cell-voltage difference |
| `0x8906` | 5 | 1 | Total voltage too high |
| `0x8906` | 5 | 2 | Total voltage too low |
| `0x8906` | 5 | 3 | Very high temperature |
| `0x8906` | 5 | 4 | MOS temperature high |
| `0x8906` | 5 | 5 | Ambient temperature low |
| `0x8906` | 5 | 6 | Ambient temperature high |
| `0x8906` | 5 | 7 | Cell failure |
| `0x8906` | 6 | 3 | Battery disconnected |

Fault offsets and names are carried over from the current reference mapping and still require independent validation. If status byte 15 equals `1` and none of these mapped bits is set, the app displays “BMS reports a fault; details unavailable.” A missing fault block is not treated as a zero-filled block.

## Command characteristic `0x8910`

The native app provides only the direct three-byte mappings recovered from the original APK. Every command is user-initiated and requires confirmation; the dialog displays the exact bytes. Values below are hexadecimal. The original APK's source-level mappings are not vendor-confirmed and have not been validated on battery hardware.

| Action | Payload | Guard / notes |
|---|---|---|
| Charge MOS on / off | `11 01 0B` / `11 01 0A` | Confirmation dialog. |
| Discharge MOS on / off | `12 01 0B` / `12 01 0A` | Confirmation dialog; off may interrupt loads. |
| `SetPowerSwitch` on / off | `12 01 0B` / `12 01 0A` | Confirmation dialog; direct single write, no fallback sequence. |
| Buzzer on / off | `13 01 0B` / `13 01 0A` | Confirmation dialog. |
| Sleep | `10 01 0A` | Confirmation dialog; the recovered handler has no `10 01 0B` branch. |

The native app does not probe alternate opcodes, retry with different payloads, or provide a cut-all-outputs sequence. A successful GATT write callback reports only that the BLE operation succeeded; the app has no verified physical-state readback. These command names and effects must be checked against the exact BMS model and firmware before use.

## Operation sequence and failure handling

Only one GATT operation is active at a time. Read, write, and descriptor operations are queued and each active operation has a five-second timeout. A failed read becomes missing data; a failed non-read operation is logged. The read cycle waits for its queued reads, updates the screen, then waits about 1.5 seconds before scheduling the next cycle.

The app excludes unrestricted raw-byte writing and automatic broad opcode probing. The original APK declares battery-life and fault-list server routes, but backend access and behavior are unverified; the native app does not call them and has no internet permission.

## Validation checklist for a new battery model

Before describing a model/firmware as supported, at minimum:

1. Verify the vendor service and each characteristic UUID on the exact BMS hardware.
2. Capture known-safe examples of status/cell/alarm packets and verify byte order, units, signed values, and sentinel handling against vendor documentation or independent measurement.
3. Verify temperature encoding and each alarm bit without creating unsafe electrical or thermal conditions.
4. Validate every control payload and response behavior with a safe test setup. Confirm physical state through a trustworthy independent observation; a successful GATT write is insufficient.
5. Record the tested battery model and firmware, Android device/version, expected behavior, and remaining limitations in a version-controlled compatibility note—never commit private BLE captures or uploaded diagnostic logs.
6. Keep capacity unavailable until its exact packet offsets, scaling, units, and meaning are confirmed.
