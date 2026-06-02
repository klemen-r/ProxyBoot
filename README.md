# ProxyBoot

ProxyBoot turns on a fully shut-down PC when a trusted phone is detected nearby via Bluetooth Low Energy (and optionally Wi-Fi).

It uses USB HID keyboard wake. No relay, no wiring inside the PC.

## Hard Limits (read first)

USB power-on from full shutdown only works if **all** of the following are true:

- The ESP32 board is an **ESP32-S2 or ESP32-S3** with native USB.
  - **ESP32-S2 has no Bluetooth radio.** The sketch detects this at compile time and disables BLE detection with a boot log. On S2 you can still wake the PC, but presence has to come from Wi-Fi IP probing.
- Arduino IDE has **Tools -> USB Mode = "USB-OTG (TinyUSB)"**.
- The motherboard keeps USB powered while the PC is off (5V standby USB).
- BIOS/UEFI allows power-on by USB keyboard or USB device.
  - Disable **ErP / EuP** (or set to "Disabled" / "S4+S5"). ErP commonly cuts standby USB power.
  - Enable **Power on by USB keyboard** or **Wake on USB device**.

If any of those is false, the PC will not power on from S5. There is no software fix - it is a motherboard limitation.

Some BIOSes only honor USB power-on from sleep (S3/S4), not full shutdown (S5). Confirm with a known wired USB keyboard before trusting the ESP32.

## Hardware Notes

- **Standby current.** With BLE scanning + Wi-Fi STA active, the ESP32 draws roughly 150-300 mA from +5VSB. Most ATX supplies with ErP disabled provide enough headroom. ErP-enforced standby (sub-100 mA) will brown the board out.
- **CDC + HID re-enumeration.** With `USB CDC On Boot = Enabled`, the host already enumerated the ESP32 as a USB CDC serial port. Adding HID at runtime forces a re-enumeration on flash/reset, so the first boot's Serial output may be partially lost. This is cosmetic; subsequent boots are fine. If it's a problem, set `USB CDC On Boot = Disabled` and use a UART adapter for Serial.

## Files

- `ProxyBoot.ino`: Arduino IDE sketch.

## Arduino IDE Setup

1. Install Arduino IDE.
2. Install the ESP32 board package.
3. Pick the real board (e.g., "ESP32S3 Dev Module").
4. Tools -> USB Mode -> **USB-OTG (TinyUSB)**.
5. Tools -> USB CDC On Boot -> **Enabled** (keeps Serial logs working).
6. Open `ProxyBoot.ino`.
7. Open Serial Monitor at `115200`.
8. Flash the board.

The sketch will fail to compile with a clear `#error` if the board or USB mode is wrong.

Optional: install `ESP32Ping` and set `USE_ESP32_PING_LIBRARY` to `1` for ICMP ping presence.

## Configuration

Edit the constants at the top of `ProxyBoot.ino`.

Important fields:

- `WIFI_SSID`, `WIFI_PASSWORD` (only if `ENABLE_WIFI_DETECTION = true`)
- `TRUSTED_BLE_NAME`
- `TRUSTED_BLE_ADDRESS`
- `TRUSTED_SERVICE_UUID`
- `TRUSTED_WIFI_IP`
- `BLE_RSSI_THRESHOLD_DBM`
- `REQUIRED_CONSECUTIVE_DETECTIONS`
- `REQUIRED_CONSECUTIVE_ABSENCES`
- `SCAN_INTERVAL_MS`
- `WAKE_COOLDOWN_MS`

## Spoofing Warning

BLE name and address matching is trivially spoofable. Anyone within ~10 m advertising the configured name or address can power your PC on.

The strongest option is to advertise a **private random service UUID** from an app you control, and match against that.

## How The Wake Logic Works

- The ESP32 enumerates as a USB HID keyboard to the PC.
- Every `SCAN_INTERVAL_MS`, it does a BLE scan (and optionally a Wi-Fi probe).
- When the trusted phone is detected for `REQUIRED_CONSECUTIVE_DETECTIONS` scans in a row, it attempts `Enter` + `Space`.
- "PC appears off" is decided by USB mount state: if the host has not mounted the ESP32, the PC is off, asleep, unplugged, or not acting as a USB host. If the host has mounted us, the PC is treated as running and the keystroke is **skipped** so it does not type into a focused window.
- If the phone is already present while the PC is running, that arrival is consumed. The phone must leave before another wake can fire.
- After a wake, the sketch enters `FIRED_WAITING_ABSENCE` and refuses to wake again until it has seen `REQUIRED_CONSECUTIVE_ABSENCES` "not seen" scans. This prevents the PC from being re-powered every cooldown while the phone sits at the desk.
- `WAKE_COOLDOWN_MS` is an additional minimum gap between wakes.
- A short `STARTUP_GRACE_MS` after boot also skips wakes, to let USB enumeration events settle.
- If Serial says `USB host is not mounted`, the HID report may be dropped. That is the hard USB-only limitation.

## BLE Detection

ProxyBoot scans nearby BLE advertisements and logs:

- address
- advertised name
- RSSI
- advertised service UUID (if any)
- whether it matched the configured trusted device

RSSI is a rough room proximity check:

- `-90 dBm`: far or weak
- `-75 dBm`: nearby
- `-60 dBm`: close

Tune `BLE_RSSI_THRESHOLD_DBM` after watching Serial logs.

### iPhone BLE Reality

iPhones randomize BLE addresses and may not advertise a useful name. Address matching often breaks across reboots.

Better iPhone options:

- Match a stable service UUID from an app that advertises BLE.
- Use Wi-Fi detection by static DHCP IP.

### Android BLE Reality

Android is usually easier for BLE testing. Some apps can advertise a custom BLE name or service UUID for a stable test signal.

## Finding BLE Devices From Serial Logs

1. Flash with `ENABLE_BLE_DETECTION = true` and all trusted BLE fields empty.
2. Open Serial Monitor at `115200`.
3. Walk into the room with the phone.
4. Look for lines like:

```text
BLE 4 addr=aa:bb:cc:dd:ee:ff name="PhoneName" rssi=-63 service=...
```

5. Copy a stable value into one of:

```cpp
const char *TRUSTED_BLE_NAME = "PhoneName";
const char *TRUSTED_BLE_ADDRESS = "aa:bb:cc:dd:ee:ff";
const char *TRUSTED_SERVICE_UUID = "service-uuid-here";
```

For iPhone, do not trust the address unless you have tested across multiple reboots and reconnects.

## Wi-Fi Detection (Optional)

Set:

```cpp
const bool ENABLE_WIFI_DETECTION = true;
const char *TRUSTED_WIFI_IP = "192.168.1.50";
```

Reserve a static DHCP lease for the phone in the router.

Without the optional ping library, the sketch tries a TCP connection to `WIFI_TCP_PROBE_PORT`. This may fail even when the phone is nearby because phones often reject incoming connections or sleep their Wi-Fi radio.

`TRUSTED_WIFI_MAC` exists in the config, but the sketch does not use it. A normal ESP32 station does not reliably know which phone MACs are on the network. Phones also randomize Wi-Fi MACs unless private address is disabled for that network.

## BIOS/UEFI Settings To Check

Names vary by motherboard. Look for:

- USB power in soft off / S5 / shutdown.
- ErP / EuP disabled.
- Power on by keyboard.
- Power on by USB device.
- Wake from USB.
- Resume by USB device.

After changing BIOS settings, shut the PC down fully and confirm the ESP32 still has power from USB.

## First Safe Test Values

```cpp
const bool ENABLE_BLE_DETECTION = true;
const bool ENABLE_WIFI_DETECTION = false;
const int BLE_RSSI_THRESHOLD_DBM = -75;
const uint8_t REQUIRED_CONSECUTIVE_DETECTIONS = 3;
const uint8_t REQUIRED_CONSECUTIVE_ABSENCES = 3;
const uint32_t SCAN_INTERVAL_MS = 5000;
```

## Troubleshooting

**Sketch will not compile, hits `#error`:**

- Confirm the board is ESP32-S2 or ESP32-S3.
- Confirm Tools -> USB Mode is set to "USB-OTG (TinyUSB)".

**No BLE devices logged:**

- Confirm Serial Monitor is `115200`.
- Confirm `ENABLE_BLE_DETECTION = true`.
- Move phone closer.
- On Android, use a BLE advertiser app for testing.

**Trusted phone logged but not matched:**

- Check spelling and case for name matching.
- Use `BLE_NAME_CONTAINS = true`.
- Check RSSI is above the threshold.
- For iPhone, do not rely on BLE address.

**Detected but no wake fires:**

- Check the Serial line `USB host mounted:`. If it says `yes (PC likely on)`, the wake was intentionally skipped.
- Check the Serial line `USB bus suspended:`. A suspended bus can still mean the ESP32 was mounted earlier, so the sketch avoids typing into the PC.
- Check the Serial line `Arm state:`. If it says `FIRED_WAITING_ABSENCE`, the phone needs to leave for `REQUIRED_CONSECUTIVE_ABSENCES` scans before re-arming.
- Lower `REQUIRED_CONSECUTIVE_DETECTIONS`.
- Lower the RSSI threshold, e.g. `-65` -> `-75`.

**Wi-Fi detection never works:**

- Reserve a static IP for the phone in the router.
- Disable private Wi-Fi address for that network on the phone.
- Install `ESP32Ping` and set `USE_ESP32_PING_LIBRARY` to `1`.
- Accept that phone Wi-Fi presence is not guaranteed.

**USB HID does nothing while PC is off:**

- Confirm the ESP32 still has power from USB while the PC is off. If the LED is dark, the board is unpowered - check BIOS ErP setting.
- Confirm BIOS "power-on by USB keyboard / USB device" is enabled.
- Try a wired USB keyboard plugged into the same port. If the wired keyboard cannot power the PC on, no ESP32 sketch will either - it is a motherboard limitation.
- Some BIOSes only honor USB power-on from S3/S4, not from S5.

**PC turns on repeatedly:**

- Increase `REQUIRED_CONSECUTIVE_ABSENCES`.
- Increase `WAKE_COOLDOWN_MS`.
- Raise the RSSI threshold, e.g. `-80` -> `-70`.

**PC shuts down and then powers back on:**

- The phone is still in range when you shut down.
- Confirm the sketch logged `USB host mounted: yes` while the PC was on. If the ESP32 never mounted, it cannot know the PC was already running.
- Walk out of range before shutdown, or raise `REQUIRED_CONSECUTIVE_ABSENCES`.
