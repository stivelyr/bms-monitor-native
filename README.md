# BMS Monitor Native

A standalone native Android BLE companion for compatible battery-management systems (BMS) that expose the BLE protocol implemented here. This project is separate from `/workspace/motor-key-app`; it does not modify or depend on that motorcycle companion.

## What it does

- Scans for nearby `BTD*` devices or a device advertising the BMS service and lets the user choose one.
- Connects to vendor BLE service `00008900-0000-1000-8000-00805f9b34fb`, performs the `0x8901` handshake when available, and polls battery status, cell voltage blocks, and fault flags every 1.5 seconds.
- Shows SOC, pack voltage/current, health, capacity, cell voltages, temperatures, and decoded alarms.
- Exposes the PWA's MOS charge/discharge, buzzer, sleep, power on/off, and cut-output controls. Battery/load-interrupting controls require a confirmation dialog.
- Provides a GATT service/characteristic inspection view and a bounded operation log.

The switch labels report the last command sent by this app; the referenced BLE protocol does not expose a confirmed MOS readback in the PWA. A successful GATT write is not proof that the battery physically changed state. The native app therefore calls that limitation out in the UI.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## BLE / compatibility notes

The implementation mirrors the PWA's 0x8900 service, 0x8901 all-`FF` handshake, 0x8902 status packet, 0x8903/04/07 cell blocks, 0x8905/06 alarm bytes, and 0x8910 command characteristic. The decoder and command bytes have JVM unit tests. This workspace does not have the target battery hardware attached, so real-device BLE behavior remains unverified.

Controls that can interrupt charging or loads use explicit confirmation. The PWA's broad automatic opcode test and arbitrary raw-byte writer are deliberately omitted; the power on/off fallback order is retained from the PWA and only advances when a GATT write reports failure.

Android 12+ requires Bluetooth Scan and Connect permissions. Android 6–11 requires fine-location permission for BLE scanning. No account, cloud service, or internet access is used by the native app.
