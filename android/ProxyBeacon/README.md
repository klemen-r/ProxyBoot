# ProxyBeacon

ProxyBeacon is the Android side of ProxyBoot. It advertises a stable private
BLE service UUID so the ESP32-S3 can match the UUID instead of the phone's
rotating BLE address.

Default UUID:

```text
c5e6bcaa-108a-47e9-a50b-d9d02827d345
```

The ESP sketch already uses this value as `TRUSTED_SERVICE_UUID`.

## Build

Android Studio's SDK is enough; Gradle is not required.

```sh
cd android/ProxyBeacon
./build-apk.sh
```

The APK is written to:

```text
android/ProxyBeacon/build/proxybeacon-debug.apk
```

## Install On Your Phone

1. On the phone, enable Developer options.
2. Enable USB debugging.
3. Plug the phone into this Mac and accept the debugging prompt.
4. Build and install:

```sh
cd android/ProxyBeacon
./install-apk.sh
```

You can also open Android Studio, choose **Profile or debug APK**, and pick
`build/proxybeacon-debug.apk`.

## Use

1. Open ProxyBeacon.
2. Grant Nearby Devices.
3. Tap **Start**.
4. Keep the app notification alive while ProxyBoot is watching for your phone.

The app can save several known UUID signals. Tap a saved signal to use it, or
tap **Random** to generate a new private UUID and **Copy ESP value** to copy the
matching C++ line for `ProxyBoot.ino`.
