/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt;

import static nodomain.freeyourgadget.gadgetbridge.util.BondingUtil.STATE_DEVICE_CANDIDATE;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.ControlCenterv2;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt.YcbtDiagnostics;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.BondingInterface;
import nodomain.freeyourgadget.gadgetbridge.util.BondingUtil;
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class YcbtPairingActivity extends AbstractGBActivity implements BondingInterface {
    private static final String STATE_TRACE_LINES = "ycbt_trace_lines";
    private static final String STATE_ATTEMPT_NUMBER = "ycbt_attempt_number";
    private static final String STATE_EVENT_NUMBER = "ycbt_event_number";
    private static final String STATE_ATTEMPT_IN_PROGRESS = "ycbt_attempt_in_progress";
    private static final String STATE_DISCONNECT_REQUESTED = "ycbt_disconnect_requested";
    private static final String STATE_SUCCESS = "ycbt_success";
    private static final String STATE_FAILURE = "ycbt_failure";

    private final BroadcastReceiver pairingReceiver = BondingUtil.getPairingReceiver(this);
    private final BroadcastReceiver diagnosticReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (!YcbtDiagnostics.ACTION_EVENT.equals(intent.getAction()) || deviceCandidate == null) {
                return;
            }
            final String address = intent.getStringExtra(YcbtDiagnostics.EXTRA_DEVICE_ADDRESS);
            if (!deviceCandidate.getMacAddress().equals(address)) {
                return;
            }

            final String type = intent.getStringExtra(YcbtDiagnostics.EXTRA_EVENT_TYPE);
            final String message = intent.getStringExtra(YcbtDiagnostics.EXTRA_MESSAGE);
            if (type == null || message == null) {
                return;
            }
            trace.append(message);
            final YcbtPairingLifecycle.Outcome outcome = YcbtPairingLifecycle.classify(
                    type,
                    attemptInProgress,
                    success,
                    failure != null
            );
            if (outcome == YcbtPairingLifecycle.Outcome.SUCCESS) {
                showSuccess();
            } else if (outcome == YcbtPairingLifecycle.Outcome.FAILURE) {
                showFailure(message, YcbtDiagnostics.TYPE_DISCONNECTED.equals(type));
            }
            renderTrace();
        }
    };

    private GBDeviceCandidate deviceCandidate;
    private YcbtPairingTrace trace;
    private TextView deviceView;
    private TextView statusView;
    private TextView traceView;
    private ProgressBar progressBar;
    private Button retryButton;
    private Button continueButton;
    private boolean receiversRegistered;
    private boolean attemptInProgress;
    private boolean disconnectRequested;
    private boolean success;
    private String failure;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ycbt_pairing);

        deviceView = findViewById(R.id.ycbt_pair_device);
        statusView = findViewById(R.id.ycbt_pair_status);
        traceView = findViewById(R.id.ycbt_pair_trace);
        progressBar = findViewById(R.id.ycbt_pair_progress);
        retryButton = findViewById(R.id.ycbt_pair_retry);
        final Button copyButton = findViewById(R.id.ycbt_pair_copy);
        continueButton = findViewById(R.id.ycbt_pair_continue);

        deviceCandidate = getIntent().getParcelableExtra(DeviceCoordinator.EXTRA_DEVICE_CANDIDATE);
        if (deviceCandidate == null && savedInstanceState != null) {
            deviceCandidate = savedInstanceState.getParcelable(STATE_DEVICE_CANDIDATE);
        }

        if (savedInstanceState == null) {
            trace = new YcbtPairingTrace();
        } else {
            final ArrayList<String> lines = savedInstanceState.getStringArrayList(STATE_TRACE_LINES);
            trace = new YcbtPairingTrace(
                    savedInstanceState.getInt(STATE_ATTEMPT_NUMBER),
                    savedInstanceState.getInt(STATE_EVENT_NUMBER),
                    lines == null ? new ArrayList<>() : lines
            );
            attemptInProgress = savedInstanceState.getBoolean(STATE_ATTEMPT_IN_PROGRESS);
            disconnectRequested = savedInstanceState.getBoolean(STATE_DISCONNECT_REQUESTED);
            success = savedInstanceState.getBoolean(STATE_SUCCESS);
            failure = savedInstanceState.getString(STATE_FAILURE);
        }

        retryButton.setOnClickListener(view -> startAttempt());
        copyButton.setOnClickListener(view -> copyDiagnostics());
        continueButton.setOnClickListener(view -> continueToControlCenter());

        registerBroadcastReceivers();
        if (deviceCandidate == null) {
            deviceView.setText(getString(R.string.ycbt_pairing_selected_device,
                    getString(R.string._unknown_), getString(R.string._unknown_)));
            trace.append(getString(R.string.ycbt_pairing_missing_candidate));
            showFailure(getString(R.string.ycbt_pairing_missing_candidate), true);
            renderTrace();
            return;
        }
        renderDevice();

        if (savedInstanceState == null) {
            startAttempt();
        } else {
            renderState();
            renderTrace();
        }
    }

    private void startAttempt() {
        if (deviceCandidate == null || attemptInProgress || success) {
            return;
        }
        attemptInProgress = true;
        disconnectRequested = false;
        failure = null;
        trace.beginAttempt();
        trace.append(getString(R.string.ycbt_pairing_connect_requested, getDeviceName()));
        renderState();
        renderTrace();
        BondingUtil.connectThenComplete(this, deviceCandidate);
    }

    @Override
    public void onBondingComplete(final boolean callbackSuccess) {
        if (!callbackSuccess) {
            trace.append(getString(R.string.ycbt_pairing_bonding_callback_failed));
            showFailure(getString(R.string.ycbt_pairing_bonding_callback_failed), true);
            renderTrace();
            return;
        }

        trace.append(getString(R.string.ycbt_pairing_success_callback));
        renderTrace();
    }

    private void showSuccess() {
        if (success) {
            return;
        }
        success = true;
        attemptInProgress = false;
        disconnectRequested = false;
        failure = null;
        renderState();
    }

    private void showFailure(final String reason, final boolean disconnected) {
        if (success) {
            return;
        }
        if (failure == null) {
            failure = reason;
        }
        if (disconnected || deviceCandidate == null) {
            attemptInProgress = false;
            disconnectRequested = false;
        } else if (attemptInProgress && !disconnectRequested) {
            disconnectRequested = true;
            final GBDevice device = DeviceHelper.getInstance().toSupportedDevice(deviceCandidate);
            GBApplication.deviceService(device).disconnect();
            trace.append(getString(R.string.ycbt_pairing_disconnect_requested));
        }
        renderState();
    }

    private void renderDevice() {
        deviceView.setText(getString(
                R.string.ycbt_pairing_selected_device,
                getDeviceName(),
                deviceCandidate.getMacAddress()
        ));
    }

    private String getDeviceName() {
        final String name = deviceCandidate.getName();
        return name == null || name.trim().isEmpty() ? getString(R.string._unknown_) : name;
    }

    private void renderState() {
        if (success) {
            statusView.setText(R.string.ycbt_pairing_success);
        } else if (failure != null) {
            statusView.setText(getString(R.string.ycbt_pairing_failure, failure));
        } else if (attemptInProgress) {
            statusView.setText(R.string.ycbt_pairing_connecting);
        } else {
            statusView.setText(R.string.ycbt_pairing_ready);
        }
        progressBar.setVisibility(attemptInProgress && failure == null ? View.VISIBLE : View.GONE);
        retryButton.setEnabled(deviceCandidate != null && !attemptInProgress && !success);
        continueButton.setVisibility(success ? View.VISIBLE : View.GONE);
    }

    private void renderTrace() {
        traceView.setText(trace.format());
    }

    private void copyDiagnostics() {
        trace.append(getString(R.string.ycbt_pairing_diagnostics_copied));
        renderTrace();
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.ycbt_pairing_diagnostics),
                trace.format()
        ));
        GB.toast(getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT, GB.INFO);
    }

    private void continueToControlCenter() {
        setResult(RESULT_OK);
        startActivity(new Intent(this, ControlCenterv2.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_DEVICE_CANDIDATE, deviceCandidate);
        outState.putStringArrayList(STATE_TRACE_LINES, trace.getLines());
        outState.putInt(STATE_ATTEMPT_NUMBER, trace.getAttemptNumber());
        outState.putInt(STATE_EVENT_NUMBER, trace.getEventNumber());
        outState.putBoolean(STATE_ATTEMPT_IN_PROGRESS, attemptInProgress);
        outState.putBoolean(STATE_DISCONNECT_REQUESTED, disconnectRequested);
        outState.putBoolean(STATE_SUCCESS, success);
        outState.putString(STATE_FAILURE, failure);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        BondingUtil.handleActivityResult(this, requestCode, resultCode, data);
    }

    @Override
    public GBDeviceCandidate getCurrentTarget() {
        return deviceCandidate;
    }

    @Override
    public boolean getAttemptToConnect() {
        return true;
    }

    @Override
    public void registerBroadcastReceivers() {
        if (receiversRegistered) {
            return;
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
                pairingReceiver,
                new IntentFilter(GBDevice.ACTION_DEVICE_CHANGED)
        );
        ContextCompat.registerReceiver(
                this,
                diagnosticReceiver,
                new IntentFilter(YcbtDiagnostics.ACTION_EVENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        receiversRegistered = true;
    }

    @Override
    public void unregisterBroadcastReceivers() {
        if (!receiversRegistered) {
            return;
        }
        AndroidUtils.safeUnregisterBroadcastReceiver(LocalBroadcastManager.getInstance(this), pairingReceiver);
        AndroidUtils.safeUnregisterBroadcastReceiver(this, diagnosticReceiver);
        receiversRegistered = false;
    }

    @Override
    public Context getContext() {
        return this;
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerBroadcastReceivers();
    }

    @Override
    protected void onStop() {
        unregisterBroadcastReceivers();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterBroadcastReceivers();
        super.onDestroy();
    }
}
