package dev.proxyboot.beacon;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int COLOR_BG = Color.rgb(246, 247, 242);
    private static final int COLOR_TEXT = Color.rgb(23, 32, 38);
    private static final int COLOR_MUTED = Color.rgb(93, 103, 109);
    private static final int COLOR_PANEL = Color.WHITE;
    private static final int COLOR_ACCENT = Color.rgb(17, 109, 110);
    private static final int COLOR_ACCENT_DARK = Color.rgb(13, 77, 83);
    private static final int COLOR_AMBER = Color.rgb(242, 184, 75);
    private static final int COLOR_BORDER = Color.rgb(220, 224, 221);

    private EditText uuidEdit;
    private LinearLayout knownList;
    private TextView statusText;
    private TextView supportText;
    private TextView statsText;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(BeaconStats.PREFS, MODE_PRIVATE);
        requestNeededPermissions();
        buildUi();
        refreshStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStats();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(22));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("ProxyBeacon");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Stable BLE signal for ProxyBoot");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        root.addView(subtitle);

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusText.setBackground(roundRect(COLOR_ACCENT, 8, 0));
        root.addView(statusText, matchWrap());

        root.addView(sectionTitle("Signal UUID"));
        LinearLayout signalPanel = panel();
        uuidEdit = new EditText(this);
        uuidEdit.setSingleLine(false);
        uuidEdit.setMinLines(1);
        uuidEdit.setMaxLines(2);
        uuidEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        uuidEdit.setTextColor(COLOR_TEXT);
        uuidEdit.setTextSize(16);
        uuidEdit.setTypeface(Typeface.MONOSPACE);
        uuidEdit.setText(prefs.getString(BeaconStats.KEY_SAVED_UUID, BeaconStats.DEFAULT_UUID));
        uuidEdit.setSelectAllOnFocus(false);
        signalPanel.addView(uuidEdit, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(primaryButton("Start", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAdvertising();
            }
        }), weightedButton());
        actions.addView(gap(dp(8), 1));
        actions.addView(secondaryButton("Stop", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopAdvertising();
            }
        }), weightedButton());
        signalPanel.addView(actions);

        LinearLayout editActions = new LinearLayout(this);
        editActions.setOrientation(LinearLayout.HORIZONTAL);
        editActions.setPadding(0, dp(8), 0, 0);
        editActions.addView(secondaryButton("Random", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uuidEdit.setText(UUID.randomUUID().toString());
            }
        }), weightedButton());
        editActions.addView(gap(dp(8), 1));
        editActions.addView(secondaryButton("Remember", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCurrentUuid();
            }
        }), weightedButton());
        signalPanel.addView(editActions);

        Button copyButton = secondaryButton("Copy ESP value", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyEspValue();
            }
        });
        LinearLayout.LayoutParams copyParams = matchWrap();
        copyParams.topMargin = dp(8);
        signalPanel.addView(copyButton, copyParams);
        root.addView(signalPanel);

        root.addView(sectionTitle("Known signals"));
        LinearLayout knownPanel = panel();
        knownList = new LinearLayout(this);
        knownList.setOrientation(LinearLayout.VERTICAL);
        knownPanel.addView(knownList, matchWrap());
        root.addView(knownPanel);

        root.addView(sectionTitle("Device"));
        LinearLayout devicePanel = panel();
        supportText = bodyText();
        devicePanel.addView(supportText, matchWrap());
        root.addView(devicePanel);

        root.addView(sectionTitle("Stats"));
        LinearLayout statsPanel = panel();
        statsText = bodyText();
        statsText.setTypeface(Typeface.MONOSPACE);
        statsPanel.addView(statsText, matchWrap());
        root.addView(statsPanel);

        setContentView(scroll);
        renderKnownSignals();
    }

    private void startAdvertising() {
        String uuid = normalizedUuid();
        if (uuid == null) {
            Toast.makeText(this, "Use a valid UUID", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasNeededPermissions()) {
            requestNeededPermissions();
            Toast.makeText(this, "Grant Nearby Devices first", Toast.LENGTH_SHORT).show();
            return;
        }

        rememberUuid(uuid);
        Intent intent = new Intent(this, BeaconService.class)
                .setAction(BeaconService.ACTION_START)
                .putExtra(BeaconService.EXTRA_UUID, uuid);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refreshStats();
    }

    private void stopAdvertising() {
        Intent intent = new Intent(this, BeaconService.class).setAction(BeaconService.ACTION_STOP);
        startService(intent);
        refreshStats();
    }

    private void saveCurrentUuid() {
        String uuid = normalizedUuid();
        if (uuid == null) {
            Toast.makeText(this, "Use a valid UUID", Toast.LENGTH_SHORT).show();
            return;
        }
        rememberUuid(uuid);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private void copyEspValue() {
        String uuid = normalizedUuid();
        if (uuid == null) {
            Toast.makeText(this, "Use a valid UUID", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        String value = "const char *TRUSTED_SERVICE_UUID = \"" + uuid + "\";";
        clipboard.setPrimaryClip(ClipData.newPlainText("ProxyBoot UUID", value));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void rememberUuid(String uuid) {
        List<String> known = knownSignals();
        known.remove(uuid);
        known.add(0, uuid);
        while (known.size() > 5) {
            known.remove(known.size() - 1);
        }
        prefs.edit()
                .putString(BeaconStats.KEY_SAVED_UUID, uuid)
                .putString(BeaconStats.KEY_KNOWN_SIGNALS, joinSignals(known))
                .apply();
        renderKnownSignals();
    }

    private void renderKnownSignals() {
        if (knownList == null) {
            return;
        }
        knownList.removeAllViews();
        List<String> known = knownSignals();
        if (known.isEmpty()) {
            TextView empty = bodyText();
            empty.setText("No saved signals");
            knownList.addView(empty, matchWrap());
            return;
        }
        for (final String uuid : known) {
            Button button = signalButton(uuid, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    uuidEdit.setText(uuid);
                    prefs.edit().putString(BeaconStats.KEY_SAVED_UUID, uuid).apply();
                    refreshStats();
                }
            });
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(8);
            knownList.addView(button, params);
        }
    }

    private List<String> knownSignals() {
        String raw = prefs.getString(BeaconStats.KEY_KNOWN_SIGNALS, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            out.add(BeaconStats.DEFAULT_UUID);
            return out;
        }
        String[] parts = raw.split("\\n");
        for (String part : parts) {
            String value = part.trim().toLowerCase(Locale.US);
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
        if (!out.contains(BeaconStats.DEFAULT_UUID)) {
            out.add(BeaconStats.DEFAULT_UUID);
        }
        return out;
    }

    private String joinSignals(List<String> signals) {
        StringBuilder builder = new StringBuilder();
        for (String signal : signals) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(signal);
        }
        return builder.toString();
    }

    private String normalizedUuid() {
        String uuid = uuidEdit.getText().toString().trim().toLowerCase(Locale.US);
        try {
            return UUID.fromString(uuid).toString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void refreshStats() {
        if (prefs == null || statusText == null) {
            return;
        }
        boolean advertising = prefs.getBoolean(BeaconStats.KEY_ADVERTISING, false);
        String status = prefs.getString(BeaconStats.KEY_STATUS, advertising ? "Advertising" : "Ready");
        String activeUuid = prefs.getString(BeaconStats.KEY_ACTIVE_UUID, uuidEdit.getText().toString());
        String lastError = prefs.getString(BeaconStats.KEY_LAST_ERROR, "");
        long startedAt = prefs.getLong(BeaconStats.KEY_STARTED_AT_MS, 0);

        statusText.setText(advertising ? "Advertising - " + shortUuid(activeUuid) : status);
        statusText.setBackground(roundRect(advertising ? COLOR_ACCENT : COLOR_AMBER, 8, 0));
        supportText.setText(deviceSupportText());

        int starts = prefs.getInt(BeaconStats.KEY_STARTS, 0);
        int failures = prefs.getInt(BeaconStats.KEY_FAILURES, 0);
        long lastStartMs = prefs.getLong(BeaconStats.KEY_LAST_START_MS, 0);

        StringBuilder stats = new StringBuilder();
        stats.append("active: ").append(activeUuid == null ? "-" : activeUuid).append('\n');
        stats.append("starts: ").append(starts).append('\n');
        stats.append("failures: ").append(failures).append('\n');
        stats.append("last start: ").append(formatTime(lastStartMs)).append('\n');
        stats.append("uptime: ").append(startedAt == 0 ? "0s" : elapsed(startedAt)).append('\n');
        stats.append("last error: ").append(lastError == null || lastError.isEmpty() ? "-" : lastError);
        statsText.setText(stats.toString());
    }

    private String deviceSupportText() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasNeededPermissions()) {
            return "permissions: needed";
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null) {
            return "bluetooth: unavailable";
        }
        try {
            if (!adapter.isEnabled()) {
                return "bluetooth: off";
            }
            if (!adapter.isMultipleAdvertisementSupported()) {
                return "ble advertising: unsupported";
            }
            if (adapter.getBluetoothLeAdvertiser() == null) {
                return "ble advertiser: unavailable";
            }
        } catch (SecurityException ex) {
            return "permissions: needed";
        }
        return "ble advertising: supported";
    }

    private boolean hasNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), 7);
        }
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private TextView bodyText() {
        TextView view = new TextView(this);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(14);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(roundRect(COLOR_PANEL, 8, COLOR_BORDER));
        return panel;
    }

    private Button primaryButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(Color.WHITE);
        button.setBackground(roundRect(COLOR_ACCENT_DARK, 8, 0));
        return button;
    }

    private Button secondaryButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(COLOR_ACCENT_DARK);
        button.setBackground(roundRect(Color.WHITE, 8, COLOR_BORDER));
        return button;
    }

    private Button signalButton(String uuid, View.OnClickListener listener) {
        Button button = secondaryButton(uuid, listener);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setTypeface(Typeface.MONOSPACE);
        button.setTextSize(13);
        return button;
    }

    private Button baseButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setMinHeight(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setOnClickListener(listener);
        return button;
    }

    private View gap(int width, int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(2);
        return params;
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private String shortUuid(String uuid) {
        if (uuid == null || uuid.length() < 8) {
            return "--------";
        }
        return uuid.substring(0, 8);
    }

    private String formatTime(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(millis);
    }

    private String elapsed(long startMs) {
        long seconds = Math.max(0, (System.currentTimeMillis() - startMs) / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
