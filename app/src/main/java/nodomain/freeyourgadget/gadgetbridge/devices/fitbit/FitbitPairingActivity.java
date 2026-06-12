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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputFilter;
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.ControlCenterv2;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class FitbitPairingActivity extends AbstractGBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(FitbitPairingActivity.class);

    private GBDeviceCandidate deviceCandidate;
    private GBDevice gbDevice;
    private EditText codeEditText;
    private TextView statusTextView;
    private Button sendCodeButton;
    private boolean pairingCodeSent;
    private boolean pairingCodeAccepted;

    private final BroadcastReceiver pairingCodeAcceptedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (!FitbitConstants.ACTION_PAIRING_CODE_ACCEPTED.equals(intent.getAction()) || pairingCodeAccepted) {
                return;
            }

            pairingCodeAccepted = true;
            statusTextView.setText(R.string.fitbit_pairing_code_sent);
            FitbitPairingActivity.this.setResult(RESULT_OK);
            returnToMainScreen();
        }
    };

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

        clearDebugPairingCodeFile();
        connectToFitbit();
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                pairingCodeAcceptedReceiver,
                new IntentFilter(FitbitConstants.ACTION_PAIRING_CODE_ACCEPTED)
        );
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(pairingCodeAcceptedReceiver);
        super.onStop();
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
        statusTextView.setText(R.string.fitbit_pairing_waiting);
        final LinearLayout.LayoutParams statusParams = matchWrapParams();
        statusParams.topMargin = gap;
        root.addView(statusTextView, statusParams);

        codeEditText = new EditText(this);
        codeEditText.setHint(R.string.fitbit_pairing_code_hint);
        codeEditText.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeEditText.setSingleLine(true);
        codeEditText.setSelectAllOnFocus(true);
        codeEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        final LinearLayout.LayoutParams editParams = matchWrapParams();
        editParams.topMargin = gap;
        root.addView(codeEditText, editParams);

        sendCodeButton = new Button(this);
        sendCodeButton.setText(R.string.fitbit_pairing_send_code);
        sendCodeButton.setOnClickListener(v -> sendPairingCode());
        final LinearLayout.LayoutParams buttonParams = matchWrapParams();
        buttonParams.topMargin = gap;
        root.addView(sendCodeButton, buttonParams);

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

        gbDevice = DeviceHelper.getInstance().toSupportedDevice(deviceCandidate.getDevice());
        LOG.warn("Fitbit onboarding script: starting GB Fitbit pairing connection for {} {}",
                deviceCandidate.getName(),
                deviceCandidate.getMacAddress());
        statusTextView.setText(R.string.fitbit_pairing_connecting);
        GBApplication.deviceService().disconnect();
        GBApplication.deviceService(gbDevice).connect(true);
    }

    private void sendPairingCode() {
        if (pairingCodeSent) {
            return;
        }

        if (gbDevice == null) {
            GB.toast(this, "Fitbit connection is not ready", Toast.LENGTH_LONG, GB.ERROR);
            return;
        }

        final String pairingCode = codeEditText.getText() == null ? "" : codeEditText.getText().toString().trim();
        if (!pairingCode.matches("\\d{4}")) {
            codeEditText.setError(getString(R.string.fitbit_pairing_code_invalid));
            return;
        }

        codeEditText.setError(null);
        pairingCodeSent = true;
        codeEditText.setEnabled(false);
        sendCodeButton.setEnabled(false);
        persistDebugPairingCode(pairingCode);
        LOG.warn("Fitbit onboarding script: DEBUG RAW pairing code submitted from Fitbit pairing UI={}", pairingCode);
        GBApplication.deviceService(gbDevice).onSendConfiguration(FitbitConstants.CONFIG_PAIRING_CODE_PREFIX + pairingCode);
        statusTextView.setText(R.string.fitbit_pairing_code_submitted);
    }

    private void returnToMainScreen() {
        final Intent intent = new Intent(this, ControlCenterv2.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void clearDebugPairingCodeFile() {
        if (!BuildConfig.DEBUG) {
            return;
        }

        try {
            final File codeFile = getDebugPairingCodeFile();
            if (codeFile.isFile() && !codeFile.delete()) {
                LOG.warn("Fitbit onboarding script: unable to delete stale Fitbit pairing code file {}",
                        codeFile.getAbsolutePath());
            }
        } catch (final IOException e) {
            LOG.warn("Fitbit onboarding script: unable to clear stale Fitbit pairing code file", e);
        }
    }

    private void persistDebugPairingCode(final String pairingCode) {
        if (!BuildConfig.DEBUG) {
            return;
        }

        try {
            final File codeFile = getDebugPairingCodeFile();
            final File parent = codeFile.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                LOG.warn("Fitbit onboarding script: unable to create Fitbit fixture directory for pairing code");
                return;
            }

            try (FileOutputStream output = new FileOutputStream(codeFile)) {
                output.write(pairingCode.getBytes(StandardCharsets.US_ASCII));
            }
            LOG.warn("Fitbit onboarding script: wrote live pairing code mirror to {}",
                    codeFile.getAbsolutePath());
        } catch (final IOException e) {
            LOG.warn("Fitbit onboarding script: unable to write debug Fitbit pairing code file", e);
        }
    }

    private File getDebugPairingCodeFile() throws IOException {
        return new File(
                new File(FileUtils.getExternalFilesDir(), FitbitConstants.SYNC_RESPONSE_FIXTURE_DIRECTORY),
                FitbitConstants.PAIRING_CODE_FILE);
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
