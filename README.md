# BMS Monitor Native

**Independent Android BLE companion for Haitai / Hinergi battery packs.** The provided app sucks, so this project is an independent alternative. It is not the official app, is not made or endorsed by Haitai/Hinergi, and is not a universal BMS client.

Compatibility depends on the battery BMS exposing the BLE service and packet/command behavior implemented in this repository. The code is based on a reference implementation of the `0x8900` BLE protocol; actual compatibility must be confirmed with the specific battery model and firmware. The project currently has no verified hardware test matrix.

## What the app does

- Scans for BLE devices whose name starts with `BTD` or which advertise the expected BMS service, then presents actual matching scan results with device address and signal strength for selection.
- Connects to the vendor service `00008900-0000-1000-8000-00805f9b34fb`. If the authentication characteristic is present, it subscribes to its notifications where possible and sends a 17-byte all-`FF` handshake.
- Reads status, cell-voltage blocks, and two alarm/fault blocks through a serialized GATT queue. The app refreshes automatically about every 1.5 seconds while connected.
- Displays state of charge (SOC), pack voltage and current, state of health (SOH), cell count and voltages, three temperatures, decoded alarms, plus raw speed and BMS-status bytes whose units/meanings are not verified.
- Shows SOC in an animated horizontal wave battery bar and plots measured pack voltage. Drag across the graph for a smoothly following inspection marker and nearest real-sample readout; 24-hour, 7-day, and 30-day range changes animate between views while filtering only samples collected during the current app session.
- Shows the highest and lowest cell readings and their difference, labeled `Voltage (V)`.
- Offers charge-MOS, discharge-MOS, buzzer, sleep, and battery on/off controls using only the direct command payloads recovered from the original app. Every command is user-initiated; a confirmation shows the exact bytes before sending.
- Shows discovered GATT services and characteristic properties, with a separate in-memory command log that records payloads and BLE write outcomes.

The English dashboard follows the mockup's dark green and charcoal visual system, with Overview, Cells, Alarms and Controls tabs. The disconnected screen uses placeholders instead of invented battery readings. The app uses Android's native UI widgets and custom Canvas views for the animated battery bar and graph; it does not use a web view or a third-party UI framework.

## Important safety and accuracy notes

This is an independent monitoring and control utility, **not a battery protection system, charger controller, or substitute for the BMS manufacturer's safety guidance**. Do not use it as the sole basis for a safety-critical decision. Test commands only when the battery and attached equipment can safely tolerate the result.

- Battery capacity is deliberately shown as unavailable. The decoder validates status bytes `0–15` only; trailing bytes previously guessed to represent capacity are ignored until their meaning and units are verified.
- Cell-voltage blocks shorter than 20 bytes are ignored instead of being parsed as partial readings.
- Voltage history is session-only, capped at 2,000 samples, cleared when the BLE connection ends, and never uploaded or saved as a persistent archive. A selected time range does not create missing historical readings.
- Switch labels describe the last command accepted by the BLE write operation. The implemented protocol does not provide a verified MOS-state readback here. A successful GATT write does not prove that a MOSFET, output, or battery state physically changed.
- Turning off discharge or switching the battery off can stop connected motors, lights, GPS devices, and other loads. Some devices wired directly to battery cells may remain powered.
- The fixed commands are `11 01 0B/A` (charge MOS on/off), `12 01 0B/A` (discharge MOS and `SetPowerSwitch` on/off), `13 01 0B/A` (buzzer on/off), and `10 01 0A` (sleep). Their physical effects are not verified; a successful GATT write does not prove that the battery changed state.
- Native power on/off fallback chains and cut-output sequences are not used. Automatic broad opcode probing and arbitrary raw-byte transmission are intentionally omitted.
- No target Haitai/Hinergi battery is attached to the build environment. BLE scanning, packet readings, and controls still need real-device testing for each supported model/firmware combination.

Read [the protocol reference](docs/PROTOCOL_REFERENCE.md) before changing command bytes or using power controls. See [the code walkthrough](docs/CODE_WALKTHROUGH.md) for the implementation and project map.

## First-use outline

1. Install the debug APK on a BLE-capable Android device and enable Bluetooth.
2. Tap **Connect**. Grant the Bluetooth permissions requested by Android.
3. Keep the target battery powered and within BLE range. The scan lasts about eight seconds and offers matching devices for selection.
4. Once connected, verify that readings are plausible for the battery. A missing value or unavailable capacity is preferable to treating an unknown field as a measurement.
5. Treat every control as a real battery command. Read the confirmation text, check attached loads, and proceed only when the outcome is safe.
6. Open **Controls** and use **Scan GATT** to inspect service/characteristic availability if the app cannot connect or read data.

## Repository layout

```text
.
├── README.md
├── docs/
│   ├── CODE_WALKTHROUGH.md
│   └── PROTOCOL_REFERENCE.md
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/app/bmsmonitor/
│       │   │   ├── MainActivity.kt
│       │   │   └── BmsProtocol.kt
│       │   └── res/
│       └── test/java/app/bmsmonitor/BmsProtocolTest.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/
```

- `BmsProtocol.kt` contains protocol constants, decoding, alarm mapping, and direct command-byte builders recovered from the original app.
- `MainActivity.kt` builds the screen and owns permissions, scanning, GATT lifecycle, the operation queue, refresh loop, controls, diagnostics, and rendering.
- `BmsProtocolTest.kt` checks status decoding and scaling, short-packet rejection, complete cell-block parsing, alarm mapping, and direct command bytes.
- `app/build.gradle.kts` configures the Android app and its JVM unit-test dependency. The app currently has no runtime library dependencies beyond the Android platform.

## Data handling

The app communicates directly with the nearby BMS over Bluetooth LE. It has no account, cloud backend, or internet permission. Although the original APK declares battery-life and fault-list server routes, backend access and behavior are unverified, so no such network integration is added here. The activity and command logs are held in memory, limited to 30 entries each, and are not uploaded or persisted. BLE captures and uploaded diagnostic logs must remain outside this repository; `.gitignore` excludes common capture/log paths and file extensions.

## Project status and known gaps

- The packet decoder and command builders have JVM unit tests; those tests cannot validate real radio behavior or the battery's response to commands.
- Capacity fields, alarm definitions, direct command effects, and the original APK's server routes need verification against vendor documentation and/or controlled hardware tests before being treated as authoritative.
- Compatibility has not been established across all Haitai/Hinergi models or firmware revisions.
- The project currently has no license file. Do not assume that reusing or redistributing the code is permitted under an open-source license until the owner chooses and adds one.

## Documentation

- [Code walkthrough](docs/CODE_WALKTHROUGH.md): app architecture, main code paths, lifecycle, and extension guidance.
- [BLE protocol reference](docs/PROTOCOL_REFERENCE.md): UUIDs, mapped packet bytes, alarms, command payloads, and validation caveats.

## Android permissions and network access

Android 12+ requires Bluetooth Scan and Connect permissions. Android 6–11 requires fine-location permission for BLE scanning. No account, cloud service, or internet access is used by the native app.
