/*
  ProxyBoot

  Turns on a fully shut-down PC when a trusted phone is nearby.
  USB HID only. No relay.

  Hardware/BIOS requirements:
  - ESP32-S3 (full support) or ESP32-S2 (USB-only, no BLE: ESP32-S2 has no
    Bluetooth radio, so the sketch disables BLE on that chip).
  - Arduino board menu: Tools -> USB Mode -> "USB-OTG (TinyUSB)".
  - Motherboard keeps USB powered while the PC is off (5V standby USB).
  - BIOS/UEFI allows power-on by USB keyboard / USB device.
    Disable ErP. Enable "Power on by USB keyboard" or "Wake on USB".

  iPhone note:
  - iPhones randomize BLE identifiers. Address matching is often unreliable.
  - Service UUID matching is better when a known app advertises one.
  - Wi-Fi detection by IP is also unreliable because phones sleep their radio.

  Spoofing warning:
  - BLE name and address matching is trivially spoofable in range.
  - Use a private random service UUID known only to you for higher trust.

  Targets Arduino-ESP32 core 3.x (current).
*/

#include <Arduino.h>
#include <WiFi.h>
#include <ctype.h>
#include "soc/soc_caps.h"
#include "sdkconfig.h"

#ifndef __has_include
#define __has_include(x) 0
#endif

// S2 has no BLE. Keep BLE code out on boards without a BLE stack.
#if __has_include(<BLEDevice.h>) && \
    (defined(SOC_BLE_SUPPORTED) || defined(CONFIG_ESP_HOSTED_ENABLE_BT_NIMBLE)) && \
    (defined(CONFIG_BLUEDROID_ENABLED) || defined(CONFIG_NIMBLE_ENABLED))
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#define PROXYBOOT_HAS_BLE 1
#else
#define PROXYBOOT_HAS_BLE 0
#endif

// Optional. Install ESP32Ping in Arduino IDE and set this to 1 to use ICMP ping.
#define USE_ESP32_PING_LIBRARY 0
#if USE_ESP32_PING_LIBRARY
#include <ESP32Ping.h>
#endif

#if (defined(CONFIG_IDF_TARGET_ESP32S2) || defined(CONFIG_IDF_TARGET_ESP32S3)) && \
    defined(ARDUINO_USB_MODE) && ARDUINO_USB_MODE == 0
#include <USB.h>
#include <USBHIDKeyboard.h>
USBHIDKeyboard Keyboard;
#else
#error "ProxyBoot needs ESP32-S2 or ESP32-S3 with Tools -> USB Mode = 'USB-OTG (TinyUSB)'. Pick the right board/USB mode in the Arduino IDE."
#endif

// =========================
// User configuration
// =========================

// Wi-Fi is only needed for IP presence detection.
const bool ENABLE_WIFI_DETECTION = false;
const char *WIFI_SSID = "CHANGE_ME";
const char *WIFI_PASSWORD = "CHANGE_ME";

// BLE detection. Disabled at compile time on boards with no BLE radio.
const bool ENABLE_BLE_DETECTION = true;

// Leave unused fields empty. Matching uses any configured BLE identity.
const char *TRUSTED_BLE_NAME = "";          // Example: "John's iPhone"
const bool BLE_NAME_CONTAINS = true;        // true = substring match, false = exact
const char *TRUSTED_BLE_ADDRESS = "";       // Example: "aa:bb:cc:dd:ee:ff"
const char *TRUSTED_SERVICE_UUID = "c5e6bcaa-108a-47e9-a50b-d9d02827d345";

// Wi-Fi IP detection.
const char *TRUSTED_WIFI_IP = "";           // Example: "192.168.1.50"
const char *TRUSTED_WIFI_MAC = "";          // Informational only, not used.
const uint16_t WIFI_TCP_PROBE_PORT = 62078; // iOS lockdown port if reachable
const uint32_t WIFI_TCP_TIMEOUT_MS = 1200;  // Bounds connect() itself, in ms.
const uint32_t WIFI_RECONNECT_BACKOFF_MS = 60UL * 1000UL;

// Proximity and scan behavior.
const int BLE_RSSI_THRESHOLD_DBM = -75;
const uint8_t REQUIRED_CONSECUTIVE_DETECTIONS = 3;
const uint8_t REQUIRED_CONSECUTIVE_ABSENCES = 3;   // Re-arm only after phone leaves
const uint32_t SCAN_INTERVAL_MS = 5000;
const uint32_t BLE_SCAN_SECONDS = 3;
const uint32_t WAKE_COOLDOWN_MS = 10UL * 60UL * 1000UL;
const uint32_t STARTUP_GRACE_MS = 10000;           // Wait for USB events to settle

// =========================
// Runtime state
// =========================

#if PROXYBOOT_HAS_BLE
BLEScan *bleScan = nullptr;
bool trustedServiceUuidValid = false;
String trustedServiceUuidText = "";
BLEUUID trustedServiceUuid;
#endif
uint8_t consecutiveDetections = 0;
uint8_t consecutiveAbsences = 0;
uint32_t lastScanMs = 0;
uint32_t lastWakeMs = 0;
uint32_t lastWifiReconnectAttemptMs = 0;
uint32_t bootTimeMs = 0;
bool wakeHasEverTriggered = false;

enum ArmState {
  ARMED,
  FIRED_WAITING_ABSENCE
};
ArmState armState = ARMED;

// Set true once the USB host has mounted us (PC is on).
// Keep this true during USB suspend so we do not type into a running PC.
volatile bool usbHostMounted = false;
volatile bool usbBusSuspended = false;

static void onUsbEvent(void *arg, esp_event_base_t eventBase, int32_t eventId, void *eventData) {
  if (eventBase != ARDUINO_USB_EVENTS) {
    return;
  }
  switch (eventId) {
    case ARDUINO_USB_STARTED_EVENT:
    case ARDUINO_USB_RESUME_EVENT:
      usbHostMounted = true;
      usbBusSuspended = false;
      break;
    case ARDUINO_USB_STOPPED_EVENT:
      usbHostMounted = false;
      usbBusSuspended = false;
      break;
    case ARDUINO_USB_SUSPEND_EVENT:
      usbBusSuspended = true;
      break;
    default:
      break;
  }
}

String lowerCopy(const String &value) {
  String out = value;
  out.toLowerCase();
  return out;
}

bool isConfigured(const char *value) {
  return value != nullptr && value[0] != '\0';
}

bool isConfiguredNonBlank(const char *value) {
  if (!isConfigured(value)) return false;
  for (const char *p = value; *p; p++) {
    if (!isspace((unsigned char)*p)) return true;
  }
  return false;
}

#if PROXYBOOT_HAS_BLE
bool isHexChar(char c) {
  return isxdigit((unsigned char)c) != 0;
}

bool isBleUuidTextValid(const String &value) {
  if (value.length() == 4 || value.length() == 8) {
    for (uint16_t i = 0; i < value.length(); i++) {
      if (!isHexChar(value.charAt(i))) return false;
    }
    return true;
  }

  if (value.length() != 36) {
    return false;
  }

  for (uint16_t i = 0; i < value.length(); i++) {
    const bool shouldBeDash = (i == 8 || i == 13 || i == 18 || i == 23);
    if (shouldBeDash) {
      if (value.charAt(i) != '-') return false;
    } else if (!isHexChar(value.charAt(i))) {
      return false;
    }
  }
  return true;
}
#endif

void printBootConfig() {
  Serial.println();
  Serial.println("ProxyBoot boot");
  Serial.println("Wake method: USB HID keyboard");
  Serial.print("BLE detection: ");
#if PROXYBOOT_HAS_BLE
  Serial.println(ENABLE_BLE_DETECTION ? "on" : "off");
#else
  Serial.println(ENABLE_BLE_DETECTION ? "REQUESTED but board has no Bluetooth radio - disabled" : "off");
#endif
  Serial.print("Wi-Fi detection: ");
  Serial.println(ENABLE_WIFI_DETECTION ? "on" : "off");
  Serial.print("BLE RSSI threshold dBm: ");
  Serial.println(BLE_RSSI_THRESHOLD_DBM);
  Serial.print("Required consecutive detections: ");
  Serial.println(REQUIRED_CONSECUTIVE_DETECTIONS);
  Serial.print("Required consecutive absences to re-arm: ");
  Serial.println(REQUIRED_CONSECUTIVE_ABSENCES);
  Serial.print("Scan interval ms: ");
  Serial.println(SCAN_INTERVAL_MS);
  Serial.print("Cooldown ms: ");
  Serial.println(WAKE_COOLDOWN_MS);

  if (isConfigured(TRUSTED_WIFI_MAC)) {
    Serial.print("Trusted Wi-Fi MAC configured but not used by this sketch: ");
    Serial.println(TRUSTED_WIFI_MAC);
  }
}

void setupUsbHid() {
  Keyboard.begin();
  USB.onEvent(onUsbEvent);
  USB.begin();
  Serial.println("USB HID keyboard initialized");
}

void connectWiFi() {
  // Always update the timestamp on exit so the reconnect backoff applies to
  // every code path, including the "misconfigured" early returns.
  if (!ENABLE_WIFI_DETECTION) {
    Serial.println("Wi-Fi detection disabled");
    lastWifiReconnectAttemptMs = millis();
    return;
  }

  if (!isConfigured(WIFI_SSID)) {
    Serial.println("Wi-Fi detection enabled but WIFI_SSID is empty");
    lastWifiReconnectAttemptMs = millis();
    return;
  }

  Serial.print("Connecting Wi-Fi SSID: ");
  Serial.println(WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  const uint32_t startMs = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startMs < 20000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Wi-Fi connected, ESP32 IP: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("Wi-Fi connection failed");
  }
  lastWifiReconnectAttemptMs = millis();
}

#if PROXYBOOT_HAS_BLE
void setupBle() {
  if (!ENABLE_BLE_DETECTION) {
    Serial.println("BLE detection disabled");
    return;
  }

  if (isConfiguredNonBlank(TRUSTED_SERVICE_UUID)) {
    trustedServiceUuidText = String(TRUSTED_SERVICE_UUID);
    trustedServiceUuidText.trim();

    if (!isBleUuidTextValid(trustedServiceUuidText)) {
      trustedServiceUuidValid = false;
      Serial.print("TRUSTED_SERVICE_UUID is not valid hex UUID text, ignoring: ");
      Serial.println(TRUSTED_SERVICE_UUID);
    } else {
      BLEUUID probe = BLEUUID(trustedServiceUuidText.c_str());
      trustedServiceUuidValid = (probe.bitSize() != 0);
      if (trustedServiceUuidValid) {
        trustedServiceUuid = probe;
      } else {
        Serial.print("TRUSTED_SERVICE_UUID does not parse as a valid BLE UUID, ignoring: ");
        Serial.println(TRUSTED_SERVICE_UUID);
      }
    }
  }

  Serial.println("Initializing BLE scanner");
  BLEDevice::init("ProxyBoot");
  bleScan = BLEDevice::getScan();
  bleScan->setActiveScan(true);
  bleScan->setInterval(100);
  bleScan->setWindow(80);
}

bool bleNameMatches(const String &deviceName) {
  if (!isConfiguredNonBlank(TRUSTED_BLE_NAME)) {
    return false;
  }

  String configuredName = String(TRUSTED_BLE_NAME);
  configuredName.trim();
  if (BLE_NAME_CONTAINS) {
    return lowerCopy(deviceName).indexOf(lowerCopy(configuredName)) >= 0;
  }

  return deviceName == configuredName;
}

bool bleAddressMatches(const String &deviceAddress) {
  if (!isConfigured(TRUSTED_BLE_ADDRESS)) {
    return false;
  }

  return lowerCopy(deviceAddress) == lowerCopy(String(TRUSTED_BLE_ADDRESS));
}

bool bleServiceMatches(BLEAdvertisedDevice &device) {
  if (!trustedServiceUuidValid) {
    return false;
  }

  return device.isAdvertisingService(trustedServiceUuid);
}

bool trustedBleIdentityMatches(BLEAdvertisedDevice &device, const String &name, const String &address) {
  return bleNameMatches(name) || bleAddressMatches(address) || bleServiceMatches(device);
}

bool scanBleForTrustedPhone() {
  if (!ENABLE_BLE_DETECTION || bleScan == nullptr) {
    return false;
  }

  Serial.println("BLE scan start");
  BLEScanResults *results = bleScan->start(BLE_SCAN_SECONDS, false);
  if (results == nullptr) {
    Serial.println("BLE scan returned no results object");
    return false;
  }

  const int count = results->getCount();
  Serial.print("BLE devices found: ");
  Serial.println(count);

  bool trustedDetected = false;

  for (int i = 0; i < count; i++) {
    BLEAdvertisedDevice device = results->getDevice(i);
    const String name = device.haveName() ? String(device.getName().c_str()) : String("");
    const String address = String(device.getAddress().toString().c_str());
    const int rssi = device.getRSSI();

    const bool identityMatched = trustedBleIdentityMatches(device, name, address);
    const bool rssiOk = rssi >= BLE_RSSI_THRESHOLD_DBM;
    const bool matched = identityMatched && rssiOk;

    Serial.print("BLE ");
    Serial.print(i);
    Serial.print(" addr=");
    Serial.print(address);
    Serial.print(" name=\"");
    Serial.print(name);
    Serial.print("\" rssi=");
    Serial.print(rssi);
    Serial.print(" service=");
    if (device.haveServiceUUID()) {
      Serial.print(device.getServiceUUID().toString().c_str());
    } else {
      Serial.print("none");
    }
    Serial.print(" identityMatch=");
    Serial.print(identityMatched ? "yes" : "no");
    Serial.print(" rssiOk=");
    Serial.print(rssiOk ? "yes" : "no");
    Serial.print(" trusted=");
    Serial.println(matched ? "yes" : "no");

    if (matched) {
      trustedDetected = true;
    }
  }

  bleScan->clearResults();
  return trustedDetected;
}
#else  // !PROXYBOOT_HAS_BLE
void setupBle() {
  if (ENABLE_BLE_DETECTION) {
    Serial.println("BLE detection requested but this board has no Bluetooth radio. Skipping.");
  } else {
    Serial.println("BLE detection disabled");
  }
}

bool scanBleForTrustedPhone() {
  return false;
}
#endif

bool probeTrustedPhoneByIp() {
  if (!ENABLE_WIFI_DETECTION) {
    return false;
  }

  if (!isConfigured(TRUSTED_WIFI_IP)) {
    Serial.println("Wi-Fi probe skipped, TRUSTED_WIFI_IP is empty");
    return false;
  }

  IPAddress ip;
  if (!ip.fromString(TRUSTED_WIFI_IP)) {
    Serial.print("Bad TRUSTED_WIFI_IP: ");
    Serial.println(TRUSTED_WIFI_IP);
    return false;
  }
  if ((uint32_t)ip == 0) {
    Serial.println("TRUSTED_WIFI_IP is 0.0.0.0, refusing to probe");
    return false;
  }

  if (WiFi.status() != WL_CONNECTED) {
    if (lastWifiReconnectAttemptMs != 0 &&
        millis() - lastWifiReconnectAttemptMs < WIFI_RECONNECT_BACKOFF_MS) {
      Serial.println("Wi-Fi disconnected, backing off reconnect");
      return false;
    }
    Serial.println("Wi-Fi disconnected, reconnecting");
    connectWiFi();
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("Wi-Fi probe failed, ESP32 is offline");
      return false;
    }
  }

#if USE_ESP32_PING_LIBRARY
  Serial.print("Pinging trusted phone IP: ");
  Serial.println(TRUSTED_WIFI_IP);
  const bool pingOk = Ping.ping(ip, 1);
  Serial.print("Ping result: ");
  Serial.println(pingOk ? "seen" : "not seen");
  return pingOk;
#else
  Serial.print("TCP probing trusted phone IP: ");
  Serial.print(TRUSTED_WIFI_IP);
  Serial.print(":");
  Serial.println(WIFI_TCP_PROBE_PORT);

  // Use the 3-arg connect overload so the timeout actually bounds connect()
  // itself. NetworkClient::setTimeout() in core 3.x takes seconds and only
  // affects subsequent reads, not connect().
  WiFiClient client;
  const bool connected = client.connect(ip, WIFI_TCP_PROBE_PORT, WIFI_TCP_TIMEOUT_MS);
  if (connected) {
    client.stop();
  }

  Serial.print("TCP probe result: ");
  Serial.println(connected ? "seen" : "not seen");
  Serial.println("If this always fails, the phone may be present but not accepting connections");
  return connected;
#endif
}

bool cooldownActive() {
  if (!wakeHasEverTriggered) {
    return false;
  }
  return millis() - lastWakeMs < WAKE_COOLDOWN_MS;
}

void printCooldown() {
  if (!cooldownActive()) {
    return;
  }
  const uint32_t remainingMs = WAKE_COOLDOWN_MS - (millis() - lastWakeMs);
  Serial.print("Cooldown remaining ms: ");
  Serial.println(remainingMs);
}

bool bootGraceActive() {
  return millis() - bootTimeMs < STARTUP_GRACE_MS;
}

bool pcAppearsOff() {
  return !usbHostMounted;
}

void sendWakeKeystrokes() {
  Keyboard.press(KEY_RETURN);
  delay(50);
  Keyboard.release(KEY_RETURN);
  delay(150);
  Keyboard.press(' ');
  delay(50);
  Keyboard.release(' ');
  Keyboard.releaseAll();
}

void waitForAbsenceBeforeNextWake(const char *reason) {
  Serial.println(reason);
  consecutiveDetections = 0;
  consecutiveAbsences = 0;
  armState = FIRED_WAITING_ABSENCE;
}

void triggerWake() {
  Serial.println("Wake trigger: USB HID keyboard");

  if (bootGraceActive()) {
    Serial.println("Boot grace active, waiting for USB state to settle. Skipping wake.");
    return;
  }

  if (!pcAppearsOff()) {
    waitForAbsenceBeforeNextWake("USB host is mounted. PC seems on. Skipping keypress and waiting for phone to leave.");
    return;
  }

  if (!usbHostMounted) {
    Serial.println("USB host is not mounted. HID reports may be dropped before BIOS/USB wake sees them.");
  }
  if (usbBusSuspended) {
    Serial.println("USB bus is suspended. A normal keypress may not wake this host without remote-wakeup support.");
  }

  sendWakeKeystrokes();
  Serial.println("USB HID keypress attempt complete");

  lastWakeMs = millis();
  wakeHasEverTriggered = true;
  consecutiveDetections = 0;
  consecutiveAbsences = 0;
  armState = FIRED_WAITING_ABSENCE;
}

void setup() {
  Serial.begin(115200);
  delay(1500);

  bootTimeMs = millis();

  printBootConfig();
  setupUsbHid();
  connectWiFi();
  setupBle();

  const bool bleEffective = PROXYBOOT_HAS_BLE && ENABLE_BLE_DETECTION;
  if (!bleEffective && !ENABLE_WIFI_DETECTION) {
    Serial.println();
    Serial.println("WARNING: no presence detection is active. ProxyBoot will never trigger a wake.");
  }
#if PROXYBOOT_HAS_BLE
  if (ENABLE_BLE_DETECTION &&
      !isConfiguredNonBlank(TRUSTED_BLE_NAME) &&
      !isConfigured(TRUSTED_BLE_ADDRESS) &&
      !trustedServiceUuidValid) {
    Serial.println();
    Serial.println("WARNING: BLE detection is on but no trusted identity is configured.");
    Serial.println("Set TRUSTED_BLE_NAME, TRUSTED_BLE_ADDRESS, or TRUSTED_SERVICE_UUID before a wake can fire.");
  }
#endif

  Serial.println("Setup complete");
}

void loop() {
  const uint32_t now = millis();
  if (now - lastScanMs < SCAN_INTERVAL_MS) {
    delay(50);
    return;
  }
  lastScanMs = now;

  Serial.println();
  Serial.println("Presence check");
  Serial.print("USB host mounted: ");
  Serial.println(usbHostMounted ? "yes (PC likely on)" : "no (PC likely off)");
  Serial.print("USB bus suspended: ");
  Serial.println(usbBusSuspended ? "yes" : "no");
  Serial.print("Arm state: ");
  Serial.println(armState == ARMED ? "ARMED" : "FIRED_WAITING_ABSENCE");
  printCooldown();

  const bool bleDetected = scanBleForTrustedPhone();
  const bool wifiDetected = probeTrustedPhoneByIp();
  const bool detected = bleDetected || wifiDetected;

  Serial.print("BLE detected: ");
  Serial.println(bleDetected ? "yes" : "no");
  Serial.print("Wi-Fi detected: ");
  Serial.println(wifiDetected ? "yes" : "no");

  if (detected) {
    // Hold the counter at 0 during boot grace so the first post-grace wake
    // does not fire instantly into a host that is mid-enumeration.
    if (bootGraceActive()) {
      Serial.println("Boot grace active, holding detection counter at 0");
      consecutiveDetections = 0;
    } else if (consecutiveDetections < 255) {
      consecutiveDetections++;
    }
    consecutiveAbsences = 0;
  } else {
    if (consecutiveAbsences < 255) consecutiveAbsences++;
    consecutiveDetections = 0;
  }

  Serial.print("Consecutive detections: ");
  Serial.print(consecutiveDetections);
  Serial.print("/");
  Serial.println(REQUIRED_CONSECUTIVE_DETECTIONS);
  Serial.print("Consecutive absences: ");
  Serial.print(consecutiveAbsences);
  Serial.print("/");
  Serial.println(REQUIRED_CONSECUTIVE_ABSENCES);

  if (armState == FIRED_WAITING_ABSENCE) {
    if (consecutiveAbsences >= REQUIRED_CONSECUTIVE_ABSENCES) {
      Serial.println("Phone has left. Re-arming.");
      armState = ARMED;
    } else {
      Serial.println("Waiting for phone to leave before re-arming");
    }
    return;
  }

  if (consecutiveDetections >= REQUIRED_CONSECUTIVE_DETECTIONS) {
    if (cooldownActive()) {
      Serial.println("Trusted phone detected, but cooldown blocks wake");
    } else {
      Serial.println("Trusted phone confirmed, triggering wake");
      triggerWake();
    }
  } else {
    Serial.println("Trusted phone not confirmed yet");
  }
}
