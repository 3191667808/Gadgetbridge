/*  Copyright (C) 2026 Marc

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.fitbit;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.core.app.ActivityCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.ControlCenterv2;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.util.BondingInterface;
import nodomain.freeyourgadget.gadgetbridge.util.BondingUtil;
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class FitbitPairingActivity extends AbstractGBActivity implements BondingInterface {
    private static final Logger LOG = LoggerFactory.getLogger(FitbitPairingActivity.class);
    private static final String EXTRACTED_MOBILE_DATA_KEY_PREFIX = "fitbit_mobile_data_key:";

    private GBDeviceCandidate deviceCandidate;
    private GBDevice gbDevice;
    private EditText mobileDataKeyEditText;
    private TextView statusTextView;
    private Button saveMobileDataKeyButton;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        deviceCandidate = getIntent().getParcelableExtra(DeviceCoordinator.EXTRA_DEVICE_CANDIDATE);
        if (deviceCandidate == null) {
            GB.toast(this, "Device candidate missing", Toast.LENGTH_LONG, GB.ERROR);
            finish();
            return;
        }

        setContentView(createContentView());

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(deviceCandidate.getName());
        }
    }

    private ScrollView createContentView() {
        final int padding = dp(20);
        final int gap = dp(12);

        final ScrollView scrollView = new ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView infoTextView = new TextView(this);
        infoTextView.setText(getString(
                R.string.fitbit_pairing_info,
                deviceCandidate.getName(),
                deviceCandidate.getMacAddress()));
        root.addView(infoTextView, matchWrapParams());

        statusTextView = new TextView(this);
        statusTextView.setText(R.string.fitbit_pairing_enter_mobile_data_key);
        final LinearLayout.LayoutParams statusParams = matchWrapParams();
        statusParams.topMargin = gap;
        root.addView(statusTextView, statusParams);

        final TextView mobileDataKeyInfoTextView = new TextView(this);
        mobileDataKeyInfoTextView.setText(R.string.fitbit_pairing_mobile_data_key_info);
        final LinearLayout.LayoutParams keyInfoParams = matchWrapParams();
        keyInfoParams.topMargin = gap;
        root.addView(mobileDataKeyInfoTextView, keyInfoParams);

        mobileDataKeyEditText = new EditText(this);
        mobileDataKeyEditText.setHint(R.string.fitbit_pairing_mobile_data_key_hint);
        mobileDataKeyEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mobileDataKeyEditText.setSingleLine(false);
        mobileDataKeyEditText.setSelectAllOnFocus(true);
        final LinearLayout.LayoutParams keyEditParams = matchWrapParams();
        keyEditParams.topMargin = gap;
        root.addView(mobileDataKeyEditText, keyEditParams);

        saveMobileDataKeyButton = new Button(this);
        saveMobileDataKeyButton.setText(R.string.fitbit_pairing_save_key_and_connect);
        saveMobileDataKeyButton.setOnClickListener(v -> saveMobileDataKeyAndConnect());
        final LinearLayout.LayoutParams saveKeyButtonParams = matchWrapParams();
        saveKeyButtonParams.topMargin = gap;
        root.addView(saveMobileDataKeyButton, saveKeyButtonParams);

        return scrollView;
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void connectToFitbit() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            GB.toast(this, "No bluetooth permissions", Toast.LENGTH_LONG, GB.ERROR);
            finish();
            return;
        }

        ensureGbDevice();
        LOG.info("Starting Fitbit mobile-data key connection for {} {}",
                deviceCandidate.getName(),
                deviceCandidate.getMacAddress());
        statusTextView.setText(R.string.fitbit_pairing_connecting);
        BondingUtil.connectThenComplete(this, gbDevice);
    }

    private void ensureGbDevice() {
        if (gbDevice == null) {
            gbDevice = DeviceHelper.getInstance().toSupportedDevice(deviceCandidate.getDevice());
        }
    }

    private void saveMobileDataKeyAndConnect() {
        ensureGbDevice();

        final String rawKey = mobileDataKeyEditText.getText() == null ? "" : mobileDataKeyEditText.getText().toString();
        final StringBuilder normalizedEntries = new StringBuilder();
        for (final String line : rawKey.split("\\R")) {
            final String normalizedEntry = normalizeMobileDataKeyEntry(line);
            if (normalizedEntry == null) {
                continue;
            }

            appendMobileDataKeyEntry(normalizedEntries, normalizedEntry);
        }

        if (normalizedEntries.length() == 0) {
            mobileDataKeyEditText.setError(getString(R.string.fitbit_pairing_mobile_data_key_invalid));
            return;
        }

        mobileDataKeyEditText.setError(null);
        for (final String normalizedEntry : normalizedEntries.toString().split("\\R")) {
            storeMobileDataKey(normalizedEntry);
        }
        statusTextView.setText(R.string.fitbit_pairing_mobile_data_key_saved);
        saveMobileDataKeyButton.setEnabled(false);
        connectToFitbit();
    }

    private void storeMobileDataKey(final String normalizedEntry) {
        final String keyId = mobileDataKeyEntryKeyId(normalizedEntry);
        final SharedPreferences preferences = GBApplication.getDevicePrefs(gbDevice).getPreferences();
        final String existing = preferences.getString(FitbitConstants.PREF_MOBILE_DATA_KEYS, "");
        final StringBuilder updated = new StringBuilder();
        boolean replaced = false;

        for (final String line : existing.split("\\R")) {
            final String normalizedExisting = normalizeMobileDataKeyEntry(line);
            if (normalizedExisting == null) {
                continue;
            }

            if (keyId.equals(mobileDataKeyEntryKeyId(normalizedExisting))) {
                if (!replaced) {
                    appendMobileDataKeyEntry(updated, normalizedEntry);
                    replaced = true;
                }
                continue;
            }

            appendMobileDataKeyEntry(updated, normalizedExisting);
        }

        if (!replaced) {
            appendMobileDataKeyEntry(updated, normalizedEntry);
        }

        preferences.edit()
                .putString(FitbitConstants.PREF_MOBILE_DATA_KEYS, updated.toString())
                .apply();

        LOG.info("Stored Fitbit mobile-data key from pairing UI for keyId {}", keyId);
    }

    private static void appendMobileDataKeyEntry(final StringBuilder builder, final String entry) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(entry);
    }

    private static String normalizeMobileDataKeyEntry(final String rawEntry) {
        if (rawEntry == null) {
            return null;
        }

        String entry = rawEntry.trim();
        if (entry.isEmpty() || entry.startsWith("#")) {
            return null;
        }

        if (entry.startsWith(EXTRACTED_MOBILE_DATA_KEY_PREFIX)) {
            entry = entry.substring(EXTRACTED_MOBILE_DATA_KEY_PREFIX.length()).trim();
        }

        entry = entry.replace("=", ":");
        final int delimiterIndex = entry.indexOf(':');
        if (delimiterIndex <= 0 || delimiterIndex >= entry.length() - 1) {
            return null;
        }

        final String identity = normalizeMobileDataIdentity(entry.substring(0, delimiterIndex));
        if (identity == null) {
            return null;
        }

        final String keyHex = cleanHexString(entry.substring(delimiterIndex + 1));
        if (keyHex.length() != 32 || !isHexString(keyHex)) {
            return null;
        }

        return identity + ":" + keyHex.toLowerCase(Locale.ROOT);
    }

    private static String normalizeMobileDataIdentity(final String identity) {
        final String normalizedIdentity = identity.trim().toUpperCase(Locale.ROOT);
        if (normalizedIdentity.matches("MD-[0-9A-F]{8}-[0-9A-F]{8}")) {
            return normalizedIdentity;
        }

        final String keyId = cleanHexString(normalizedIdentity).toUpperCase(Locale.ROOT);
        if (keyId.matches("[0-9A-F]{8}")) {
            return "MD-" + keyId + "-00000000";
        }

        return null;
    }

    private static String mobileDataKeyEntryIdentity(final String normalizedEntry) {
        return normalizedEntry.substring(0, normalizedEntry.indexOf(':'));
    }

    private static String mobileDataKeyEntryKeyId(final String normalizedEntry) {
        return mobileDataKeyEntryIdentity(normalizedEntry).substring(3, 11);
    }

    private static String cleanHexString(final String value) {
        return value
                .replace("0x", "")
                .replace("0X", "")
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .replace("\t", "")
                .trim();
    }

    private static boolean isHexString(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!((c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBondingComplete(final boolean success) {
        startActivity(new Intent(this, ControlCenterv2.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    @Override
    public GBDeviceCandidate getCurrentTarget() {
        return deviceCandidate;
    }

    @Override
    public void unregisterBroadcastReceivers() {
    }

    @Override
    public boolean getAttemptToConnect() {
        return true;
    }

    @Override
    public void registerBroadcastReceivers() {
    }

    @Override
    public Context getContext() {
        return this;
    }
}
