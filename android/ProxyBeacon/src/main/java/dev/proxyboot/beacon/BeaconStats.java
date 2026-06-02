package dev.proxyboot.beacon;

final class BeaconStats {
    static final String PREFS = "proxybeacon";
    static final String DEFAULT_UUID = "c5e6bcaa-108a-47e9-a50b-d9d02827d345";

    static final String KEY_ACTIVE_UUID = "activeUuid";
    static final String KEY_ADVERTISING = "advertising";
    static final String KEY_FAILURES = "failures";
    static final String KEY_KNOWN_SIGNALS = "knownSignals";
    static final String KEY_LAST_ERROR = "lastError";
    static final String KEY_LAST_START_MS = "lastStartMs";
    static final String KEY_SAVED_UUID = "savedUuid";
    static final String KEY_STARTED_AT_MS = "startedAtMs";
    static final String KEY_STARTS = "starts";
    static final String KEY_STATUS = "status";

    private BeaconStats() {
    }
}
