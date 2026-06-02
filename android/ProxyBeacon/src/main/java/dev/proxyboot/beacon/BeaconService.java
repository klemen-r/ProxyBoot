package dev.proxyboot.beacon;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;

import java.util.UUID;

public class BeaconService extends Service {
    static final String ACTION_START = "dev.proxyboot.beacon.START";
    static final String ACTION_STOP = "dev.proxyboot.beacon.STOP";
    static final String EXTRA_UUID = "uuid";

    private static final String CHANNEL_ID = "proxybeacon_status";
    private static final int NOTIFICATION_ID = 42;

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback callback;
    private SharedPreferences prefs;
    private String activeUuid = BeaconStats.DEFAULT_UUID;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(BeaconStats.PREFS, MODE_PRIVATE);
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAdvertising("Stopped");
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        activeUuid = intent != null ? intent.getStringExtra(EXTRA_UUID) : null;
        if (activeUuid == null || activeUuid.trim().isEmpty()) {
            activeUuid = BeaconStats.DEFAULT_UUID;
        }
        activeUuid = activeUuid.trim().toLowerCase();

        startAsForeground();
        startAdvertising(activeUuid);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAdvertising("Service closed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAdvertising(String uuidText) {
        if (!hasBluetoothPermissions()) {
            markFailure("Missing Bluetooth permission", 0);
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException ex) {
            markFailure("Bad UUID", 0);
            return;
        }

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null) {
            markFailure("Bluetooth unavailable", 0);
            return;
        }
        if (!adapter.isEnabled()) {
            markFailure("Bluetooth is off", 0);
            return;
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            markFailure("BLE advertising unsupported", 0);
            return;
        }

        BluetoothLeAdvertiser nextAdvertiser = adapter.getBluetoothLeAdvertiser();
        if (nextAdvertiser == null) {
            markFailure("BLE advertiser unavailable", 0);
            return;
        }

        stopAdvertising("Restarting");
        advertiser = nextAdvertiser;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(uuid))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build();

        callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                prefs.edit()
                        .putBoolean(BeaconStats.KEY_ADVERTISING, true)
                        .putString(BeaconStats.KEY_ACTIVE_UUID, activeUuid)
                        .putString(BeaconStats.KEY_STATUS, "Advertising")
                        .putLong(BeaconStats.KEY_LAST_START_MS, System.currentTimeMillis())
                        .putLong(BeaconStats.KEY_STARTED_AT_MS, System.currentTimeMillis())
                        .putInt(BeaconStats.KEY_STARTS, prefs.getInt(BeaconStats.KEY_STARTS, 0) + 1)
                        .putString(BeaconStats.KEY_LAST_ERROR, "")
                        .apply();
                startAsForeground();
            }

            @Override
            public void onStartFailure(int errorCode) {
                markFailure(errorMessage(errorCode), errorCode);
            }
        };

        try {
            prefs.edit()
                    .putString(BeaconStats.KEY_STATUS, "Starting")
                    .putString(BeaconStats.KEY_ACTIVE_UUID, activeUuid)
                    .apply();
            advertiser.startAdvertising(settings, data, callback);
        } catch (SecurityException ex) {
            markFailure("Bluetooth permission denied", 0);
        }
    }

    private void stopAdvertising(String status) {
        if (advertiser != null && callback != null) {
            try {
                advertiser.stopAdvertising(callback);
            } catch (SecurityException ignored) {
            }
        }
        advertiser = null;
        callback = null;
        prefs.edit()
                .putBoolean(BeaconStats.KEY_ADVERTISING, false)
                .putString(BeaconStats.KEY_STATUS, status)
                .putLong(BeaconStats.KEY_STARTED_AT_MS, 0)
                .apply();
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void markFailure(String message, int errorCode) {
        String detail = errorCode == 0 ? message : message + " (" + errorCode + ")";
        prefs.edit()
                .putBoolean(BeaconStats.KEY_ADVERTISING, false)
                .putString(BeaconStats.KEY_STATUS, "Failed")
                .putString(BeaconStats.KEY_LAST_ERROR, detail)
                .putLong(BeaconStats.KEY_STARTED_AT_MS, 0)
                .putInt(BeaconStats.KEY_FAILURES, prefs.getInt(BeaconStats.KEY_FAILURES, 0) + 1)
                .apply();
        startAsForeground();
    }

    private String errorMessage(int errorCode) {
        switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "Advertisement data too large";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "Too many advertisers active";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "Already advertising";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "Bluetooth internal error";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "BLE advertising unsupported";
            default:
                return "Advertising failed";
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, BeaconService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                2,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        boolean advertising = prefs.getBoolean(BeaconStats.KEY_ADVERTISING, false);
        String status = prefs.getString(BeaconStats.KEY_STATUS, advertising ? "Advertising" : "Ready");
        String title = advertising ? "ProxyBeacon advertising" : "ProxyBeacon";

        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop",
                stopPendingIntent)
                .build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(status + " - " + shortUuid(activeUuid))
                .setContentIntent(openPendingIntent)
                .setOngoing(advertising)
                .addAction(stopAction)
                .build();
    }

    private int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private String shortUuid(String uuid) {
        if (uuid == null || uuid.length() < 8) {
            return BeaconStats.DEFAULT_UUID.substring(0, 8);
        }
        return uuid.substring(0, 8);
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ProxyBeacon",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
