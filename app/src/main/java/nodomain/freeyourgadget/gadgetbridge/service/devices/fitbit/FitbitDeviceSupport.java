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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.protobuf.InvalidProtocolBufferException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.HeartRrIntervalSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.fitbit.FitbitActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.fitbit.FitbitConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.FitbitActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartRrIntervalSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.proto.fitbit.FitbitMobileDataProto;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEQueue;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.ServerTransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.heartrate.HeartRate;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.heartrate.HeartRateProfile;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class FitbitDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(FitbitDeviceSupport.class);

    private static final int REQUESTED_MTU = 185;
    private static final long SERVER_STATUS_NOTIFY_INTERVAL_MS = 5000;
    private static final byte[] EMPTY_RESPONSE = new byte[0];
    private static final long LIVE_DATA_POLL_INTERVAL_MS = 1000;
    private static final long RECORDED_DATA_FETCH_TIMEOUT_MS = 10000;
    private static final int HEART_RATE_TEST_REQUESTS = 10;

    private final Handler serverStatusHandler = new Handler(Looper.getMainLooper());
    private final FitbitGattlink.Session gattlinkSession = new FitbitGattlink.Session();
    private final FitbitDtls fitbitDtls = new FitbitDtls(this::resolvePsk, this::handleFitbitCoapResponse);
    private final FitbitResourceClient fitbitResources;
    private final FitbitPhoneLocalGatt phoneLocalGatt = new FitbitPhoneLocalGatt();
    private final DeviceInfoProfile<FitbitDeviceSupport> deviceInfoProfile;
    private final BatteryInfoProfile<FitbitDeviceSupport> batteryInfoProfile;
    private final HeartRateProfile<FitbitDeviceSupport> heartRateProfile;
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();

    private BluetoothDevice serverDevice;
    private boolean phoneGattlinkStatus2Subscribed;
    private boolean newHeartRateSamples;
    private boolean realtimeStepsEnabled;
    private boolean realtimeHeartRateEnabled;
    private boolean initialFitbitDeviceInfoRequested;
    private boolean recordedDataFetchInProgress;
    private boolean recordedDataFetchMarkedBusy;
    private boolean pendingRecordedDataSyncStatus;
    private int pendingLiveActivitySnapshotRequests;
    private int pendingHeartRateTestRequests;
    private int lastLiveActivitySteps = ActivitySample.NOT_MEASURED;

    private final Runnable serverStatusRunnable = new Runnable() {
        @Override
        public void run() {
            sendPhoneGattlinkStatus();
            if (phoneGattlinkStatus2Subscribed && serverDevice != null) {
                serverStatusHandler.postDelayed(this, SERVER_STATUS_NOTIFY_INTERVAL_MS);
            }
        }
    };

    private final Runnable liveDataRunnable = new Runnable() {
        @Override
        public void run() {
            requestFitbitLiveActivity();
            if (isFitbitLiveDataPollingEnabled()) {
                serverStatusHandler.postDelayed(this, LIVE_DATA_POLL_INTERVAL_MS);
            }
        }
    };

    private final Runnable heartRateTestRunnable = new Runnable() {
        @Override
        public void run() {
            requestFitbitLiveActivity();
        }
    };

    private final Runnable recordedDataFetchTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!recordedDataFetchInProgress) {
                return;
            }

            LOG.warn("Fitbit recorded data fetch timed out: pendingLiveActivitySnapshots={}, pendingSyncStatus={}",
                    pendingLiveActivitySnapshotRequests,
                    pendingRecordedDataSyncStatus);
            pendingLiveActivitySnapshotRequests = 0;
            pendingRecordedDataSyncStatus = false;
            finishRecordedDataFetchIfIdle();
        }
    };

    private final Runnable initialResourceDiscoveryRunnable = new Runnable() {
        @Override
        public void run() {
            requestFitbitDeviceInfo();
            requestFitbitLiveActivitySnapshot();
            requestFitbitSyncStatus();
            requestFitbitSyncConfig();
            requestFitbitInboxStatus();
        }
    };

    public FitbitDeviceSupport() {
        super(LOG);

        fitbitResources = new FitbitResourceClient(fitbitDtls, this::writeGattlinkIpPacket);

        addSupportedService(FitbitConstants.GATTLINK_SERVICE);
        addSupportedService(DeviceInfoProfile.SERVICE_UUID);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
        addSupportedService(GattService.UUID_SERVICE_HEART_RATE);
        addSupportedServerService(phoneLocalGatt.createPhoneLocationService());
        addSupportedServerService(phoneLocalGatt.createPhoneGattlinkService());

        final IntentListener listener = intent -> {
            if (intent == null || intent.getAction() == null) {
                return;
            }

            if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(intent.getAction())) {
                final DeviceInfo deviceInfo = intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO);
                if (deviceInfo != null) {
                    handleDeviceInfo(deviceInfo);
                }
                return;
            }

            if (BatteryInfoProfile.ACTION_BATTERY_INFO.equals(intent.getAction())) {
                final BatteryInfo batteryInfo = intent.getParcelableExtra(BatteryInfoProfile.EXTRA_BATTERY_INFO);
                if (batteryInfo != null) {
                    handleBatteryInfo(batteryInfo);
                }
                return;
            }

            if (HeartRateProfile.ACTION_HEART_RATE.equals(intent.getAction())) {
                final HeartRate heartRate = intent.getParcelableExtra(HeartRateProfile.EXTRA_HEART_RATE);
                if (heartRate != null) {
                    handleHeartRate(heartRate);
                }
            }
        };

        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(listener);
        addSupportedProfile(deviceInfoProfile);

        batteryInfoProfile = new BatteryInfoProfile<>(this);
        batteryInfoProfile.addListener(listener);
        addSupportedProfile(batteryInfoProfile);

        heartRateProfile = new HeartRateProfile<>(this);
        heartRateProfile.addListener(listener);
        addSupportedProfile(heartRateProfile);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }

    private byte[] resolvePsk(final String identity) {
        if (FitbitMobileDataKeys.isMobileDataPskIdentity(identity)) {
            final byte[] mobileDataPsk = FitbitMobileDataKeys.loadPsk(
                    getDevicePrefs().getPreferences(),
                    identity
            );
            if (mobileDataPsk != null) {
                LOG.info("Resolved Fitbit mobile-data PSK for identity {}", identity);
                return mobileDataPsk;
            }

            LOG.warn("Missing Fitbit mobile-data PSK for identity {}. Enter the mobile-data key in the Fitbit pairing screen. Matching uses the first MD key-id field.",
                    identity);
        }

        return null;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        LOG.info("Initializing Fitbit BLE connection");

        gattlinkSession.reset();
        fitbitDtls.reset();
        initialFitbitDeviceInfoRequested = false;
        recordedDataFetchInProgress = false;
        recordedDataFetchMarkedBusy = false;
        pendingRecordedDataSyncStatus = false;
        pendingLiveActivitySnapshotRequests = 0;
        pendingHeartRateTestRequests = 0;
        lastLiveActivitySteps = ActivitySample.NOT_MEASURED;

        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.requestMtu(REQUESTED_MTU);
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("Gattlink");
        deviceInfoProfile.requestDeviceInfo(builder);
        if (getCharacteristic(BatteryInfoProfile.UUID_CHARACTERISTIC_BATTERY_LEVEL) != null) {
            batteryInfoProfile.requestBatteryInfo(builder);
            batteryInfoProfile.enableNotify(builder, true);
        } else {
            LOG.info("Fitbit BLE battery characteristic is not present, battery will be read through mobile-data");
        }
        heartRateProfile.enableNotify(builder, true);
        builder.notify(FitbitConstants.GATTLINK_NOTIFY_CHARACTERISTIC, true);

        return builder;
    }

    @Override
    public void disconnect() {
        stopFitbitLiveDataPolling();
        signalHeartRateDataFinishIfNeeded();
        super.disconnect();
    }

    @Override
    public void dispose() {
        stopFitbitLiveDataPolling();
        signalHeartRateDataFinishIfNeeded();
        stopPhoneGattlinkStatusNotifications();
        gattlinkSession.reset();
        fitbitDtls.reset();
        super.dispose();
    }

    @Override
    public void onHeartRateTest() {
        pendingHeartRateTestRequests = HEART_RATE_TEST_REQUESTS;
        requestFitbitLiveActivity();
    }

    @Override
    public void onEnableRealtimeSteps(final boolean enable) {
        if (realtimeStepsEnabled != enable) {
            lastLiveActivitySteps = ActivitySample.NOT_MEASURED;
        }
        realtimeStepsEnabled = enable;
        updateFitbitLiveDataPolling();
    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(final boolean enable) {
        realtimeHeartRateEnabled = enable;
        updateFitbitLiveDataPolling();
    }

    @Override
    public void onFetchRecordedData(final int dataTypes) {
        if (!fitbitDtls.isSessionEstablished()) {
            LOG.info("Fitbit recorded data fetch requested before DTLS session is established: dataTypes={}", dataTypes);
            GB.signalActivityDataFinish(getDevice());
            getDevice().sendDeviceUpdateIntent(getContext());
            return;
        }

        LOG.info("Fitbit recorded data fetch requested: dataTypes={}", dataTypes);
        startRecordedDataFetchBusyState();
        recordedDataFetchInProgress = true;
        pendingRecordedDataSyncStatus = true;
        serverStatusHandler.removeCallbacks(recordedDataFetchTimeoutRunnable);
        serverStatusHandler.postDelayed(recordedDataFetchTimeoutRunnable, RECORDED_DATA_FETCH_TIMEOUT_MS);
        requestFitbitLiveActivitySnapshot();
        requestFitbitSyncStatus();
        if ((dataTypes & RecordedDataTypes.TYPE_DEBUGLOGS) != 0) {
            requestFitbitSyncConfig();
            requestFitbitInboxStatus();
            requestFitbitWifiOperationStatus();
            requestFitbitAppDownloadStatus();
        }
    }

    @Override
    public boolean onCharacteristicWrite(final BluetoothGatt gatt,
                                         final BluetoothGattCharacteristic characteristic,
                                         final int status) {
        final UUID uuid = characteristic.getUuid();
        if (FitbitConstants.GATTLINK_WRITE_CHARACTERISTIC.equals(uuid)) {
            LOG.debug("Fitbit write {} completed with status {}", uuid, status);
            return true;
        }

        return super.onCharacteristicWrite(gatt, characteristic, status);
    }

    @Override
    public boolean onDescriptorWrite(final BluetoothGatt gatt,
                                     final BluetoothGattDescriptor descriptor,
                                     final int status) {
        final BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic != null && FitbitConstants.GATTLINK_NOTIFY_CHARACTERISTIC.equals(characteristic.getUuid())) {
            LOG.info("Fitbit Gattlink notification descriptor write completed with status {}", status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startGattlinkSession();
            }
            return true;
        }

        return super.onDescriptorWrite(gatt, descriptor, status);
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (!FitbitConstants.GATTLINK_NOTIFY_CHARACTERISTIC.equals(characteristic.getUuid())) {
            return super.onCharacteristicChanged(gatt, characteristic, value);
        }

        final FitbitGattlink.IncomingResult result = gattlinkSession.handleIncoming(value);
        handleGattlinkStateChange(result);
        writeGattlinkResponses("Fitbit Gattlink response", result.getResponses(), result.isSessionReady());

        if (result.isDataOnClosedSession()) {
            LOG.warn("Fitbit Gattlink data received while session is not ready: {}",
                    GB.hexdump(result.getRawDataOnClosedSession()));
            return true;
        }

        if (result.getUnexpectedPsn() >= 0) {
            LOG.warn("Fitbit Gattlink unexpected PSN {}, expected {}",
                    result.getUnexpectedPsn(),
                    result.getExpectedPsn());
            return true;
        }

        final byte[] ipv4Packet = result.getIpv4Packet();
        if (ipv4Packet != null) {
            LOG.info("Fitbit Gattlink IPv4 packet: {}", FitbitGattlink.describeIpv4Packet(ipv4Packet));
            final byte[] dtlsResponse = fitbitDtls.maybeBuildResponse(ipv4Packet);
            if (dtlsResponse != null) {
                writeGattlinkIpPacket("Fitbit DTLS response", dtlsResponse);
                requestInitialFitbitDeviceInfoIfNeeded();
            }
            return true;
        }

        if (gattlinkSession.hasPartialIpv4Packet()) {
            LOG.debug("Fitbit Gattlink IPv4 fragment: {}", GB.hexdump(value));
            return true;
        }

        LOG.info("Fitbit Gattlink notification: {}", GB.hexdump(value));

        return true;
    }

    @Override
    public void onMtuChanged(final BluetoothGatt gatt, final int mtu, final int status) {
        super.onMtuChanged(gatt, mtu, status);
        LOG.info("Fitbit MTU changed to {} with status {}", mtu, status);
    }

    @Override
    public void onConnectionStateChange(final BluetoothDevice device, final int status, final int newState) {
        LOG.info("Fitbit local GATT server connection state changed: device={}, status={}, state={}",
                device != null ? device.getAddress() : "(null)", status, newState);

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gattlinkSession.reset();
            fitbitDtls.reset();
            serverDevice = device;
            phoneGattlinkStatus2Subscribed = true;
            startPhoneGattlinkStatusNotifications();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            stopFitbitLiveDataPolling();
            stopPhoneGattlinkStatusNotifications();
            gattlinkSession.reset();
            fitbitDtls.reset();
            initialFitbitDeviceInfoRequested = false;
            recordedDataFetchInProgress = false;
            pendingLiveActivitySnapshotRequests = 0;
            pendingRecordedDataSyncStatus = false;
            serverStatusHandler.removeCallbacks(recordedDataFetchTimeoutRunnable);
            clearRecordedDataFetchBusyState();
            if (device != null && device.equals(serverDevice)) {
                serverDevice = null;
            }
        }
    }

    @Override
    public boolean onCharacteristicReadRequest(final BluetoothDevice device,
                                               final int requestId,
                                               final int offset,
                                               final BluetoothGattCharacteristic characteristic) {
        final UUID uuid = characteristic.getUuid();
        final byte[] value = phoneLocalGatt.readCharacteristic(uuid);

        if (value == null) {
            LOG.warn("Fitbit unexpected local GATT read: {}", uuid);
            queueServerResponse("Fitbit unexpected local GATT read", device, requestId, BluetoothGatt.GATT_FAILURE, offset, EMPTY_RESPONSE);
            return true;
        }

        LOG.info("Fitbit local GATT read {} -> {}", uuid, GB.hexdump(value));
        queueServerResponse("Fitbit local GATT read", device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
        return true;
    }

    @Override
    public boolean onCharacteristicWriteRequest(final BluetoothDevice device,
                                                final int requestId,
                                                final BluetoothGattCharacteristic characteristic,
                                                final boolean preparedWrite,
                                                final boolean responseNeeded,
                                                final int offset,
                                                final byte[] value) {
        LOG.info("Fitbit local GATT write {} <- {}", characteristic.getUuid(), GB.hexdump(value));

        if (responseNeeded) {
            queueServerResponse("Fitbit local GATT write response", device, requestId, BluetoothGatt.GATT_SUCCESS, offset, EMPTY_RESPONSE);
        }
        return true;
    }

    @Override
    public boolean onDescriptorReadRequest(final BluetoothDevice device,
                                           final int requestId,
                                           final int offset,
                                           final BluetoothGattDescriptor descriptor) {
        LOG.info("Fitbit local GATT descriptor read {}", descriptor.getUuid());
        queueServerResponse("Fitbit local GATT descriptor read", device, requestId, BluetoothGatt.GATT_SUCCESS, offset, FitbitPhoneLocalGatt.CCCD_DISABLED_RESPONSE);
        return true;
    }

    @Override
    public boolean onDescriptorWriteRequest(final BluetoothDevice device,
                                            final int requestId,
                                            final BluetoothGattDescriptor descriptor,
                                            final boolean preparedWrite,
                                            final boolean responseNeeded,
                                            final int offset,
                                            final byte[] value) {
        final BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        final UUID characteristicUuid = characteristic != null ? characteristic.getUuid() : null;

        LOG.info("Fitbit local GATT descriptor write {} for {} <- {}",
                descriptor.getUuid(), characteristicUuid, GB.hexdump(value));

        if (phoneLocalGatt.isPhoneGattlinkDescriptor(characteristicUuid)) {
            if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                phoneGattlinkStatus2Subscribed = true;
                serverDevice = device;
                startPhoneGattlinkStatusNotifications();
            } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                stopPhoneGattlinkStatusNotifications();
            }
        }

        if (responseNeeded) {
            queueServerResponse("Fitbit local GATT descriptor write response", device, requestId, BluetoothGatt.GATT_SUCCESS, offset, EMPTY_RESPONSE);
        }
        return true;
    }

    private void startGattlinkSession() {
        final FitbitGattlink.IncomingResult result = gattlinkSession.start();
        writeGattlinkResponses("Fitbit Gattlink reset request", result.getResponses(), false);
    }

    private void handleGattlinkStateChange(final FitbitGattlink.IncomingResult result) {
        if (result.isSessionReset()) {
            LOG.info("Fitbit Gattlink session reset");
            fitbitDtls.reset();
        }

        if (result.isSessionReady()) {
            LOG.info("Fitbit Gattlink session ready");
            fitbitDtls.reset();
        }
    }

    private void writeGattlinkResponses(final String taskName,
                                        final List<byte[]> values,
                                        final boolean markInitialized) {
        if (values.isEmpty()) {
            if (markInitialized) {
                markDeviceInitialized(taskName);
            }
            return;
        }

        final BluetoothGattCharacteristic characteristic = getCharacteristic(FitbitConstants.GATTLINK_WRITE_CHARACTERISTIC);
        if (characteristic == null) {
            LOG.warn("Unable to write Fitbit Gattlink control, characteristic is missing");
            return;
        }

        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        try {
            final TransactionBuilder builder = createTransactionBuilder(taskName);
            for (final byte[] value : values) {
                LOG.debug("{}: {}", taskName, GB.hexdump(value));
                builder.write(characteristic, value);
            }
            if (markInitialized) {
                builder.setDeviceState(GBDevice.State.INITIALIZED);
            }
            builder.queueConnected();
        } catch (final IOException e) {
            LOG.warn("Unable to write Fitbit Gattlink response", e);
        }
    }

    private void writeGattlinkIpPacket(final String taskName, final byte[] ipv4Packet) {
        final BluetoothGattCharacteristic characteristic = getCharacteristic(FitbitConstants.GATTLINK_WRITE_CHARACTERISTIC);
        if (characteristic == null) {
            LOG.warn("Unable to write Fitbit Gattlink packet, characteristic is missing");
            return;
        }

        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        try {
            final TransactionBuilder builder = createTransactionBuilder(taskName);
            final int maxFrameLength = builder.getMaxWriteChunk();
            for (byte[] frame : gattlinkSession.encodeIpv4Packet(ipv4Packet, maxFrameLength)) {
                LOG.debug("{}: {}", taskName, GB.hexdump(frame));
                builder.write(characteristic, frame);
            }
            builder.queueConnected();
        } catch (final IOException e) {
            LOG.warn("Unable to write Fitbit Gattlink packet", e);
        }
    }

    private boolean isFitbitLiveDataPollingEnabled() {
        return realtimeStepsEnabled || realtimeHeartRateEnabled;
    }

    private void updateFitbitLiveDataPolling() {
        serverStatusHandler.removeCallbacks(liveDataRunnable);
        if (isFitbitLiveDataPollingEnabled()) {
            serverStatusHandler.post(liveDataRunnable);
        }
    }

    private void stopFitbitLiveDataPolling() {
        serverStatusHandler.removeCallbacks(liveDataRunnable);
        serverStatusHandler.removeCallbacks(heartRateTestRunnable);
        serverStatusHandler.removeCallbacks(initialResourceDiscoveryRunnable);
        serverStatusHandler.removeCallbacks(recordedDataFetchTimeoutRunnable);
        pendingLiveActivitySnapshotRequests = 0;
        pendingHeartRateTestRequests = 0;
        lastLiveActivitySteps = ActivitySample.NOT_MEASURED;
    }

    private void requestInitialFitbitDeviceInfoIfNeeded() {
        if (initialFitbitDeviceInfoRequested || !fitbitDtls.isSessionEstablished()) {
            return;
        }

        initialFitbitDeviceInfoRequested = true;
        serverStatusHandler.postDelayed(initialResourceDiscoveryRunnable, 1000);
    }

    private void requestFitbitDeviceInfo() {
        fitbitResources.requestDeviceInfo();
    }

    private void requestFitbitLiveActivity() {
        fitbitResources.requestLiveActivity();
    }

    private void requestFitbitLiveActivitySnapshot() {
        pendingLiveActivitySnapshotRequests++;
        requestFitbitLiveActivity();
    }

    private void requestFitbitSyncStatus() {
        fitbitResources.requestSyncStatus();
    }

    private void requestFitbitSyncConfig() {
        fitbitResources.requestSyncConfig();
    }

    private void requestFitbitInboxStatus() {
        fitbitResources.requestInboxStatus();
    }

    private void requestFitbitWifiOperationStatus() {
        fitbitResources.requestWifiOperationStatus();
    }

    private void requestFitbitAppDownloadStatus() {
        fitbitResources.requestAppDownloadStatus();
    }

    private void handleFitbitCoapResponse(final String requestPath,
                                          final int requestCode,
                                          final int responseCode,
                                          final byte[] payload) {
        if ((responseCode >> 5) != 2) {
            LOG.warn("Fitbit CoAP response for {} was not successful: code={}.{}, payloadLen={}",
                    requestPath,
                    (responseCode >> 5) & 0x07,
                    responseCode & 0x1f,
                    payload.length);
            if (FitbitResourceClient.PATH_SYNC_STATUS.equals(requestPath)) {
                pendingRecordedDataSyncStatus = false;
                finishRecordedDataFetchIfIdle();
            } else if (FitbitResourceClient.PATH_LIVE_ACTIVITY.equals(requestPath)) {
                finishFitbitLiveActivitySnapshotRequest();
                finishRecordedDataFetchIfIdle();
            }
            return;
        }

        if (FitbitResourceClient.PATH_LIVE_ACTIVITY.equals(requestPath)) {
            handleFitbitLiveActivity(payload);
        } else if (FitbitResourceClient.PATH_DEVICE_INFO.equals(requestPath)) {
            handleFitbitDeviceInfo(payload);
        } else if (FitbitResourceClient.PATH_SYNC_STATUS.equals(requestPath)) {
            handleFitbitSyncStatus(payload);
        } else if (FitbitResourceClient.PATH_SYNC_CONFIG.equals(requestPath)
                || FitbitResourceClient.PATH_INBOX_STATUS.equals(requestPath)
                || FitbitResourceClient.PATH_WIFI_OPERATION_STATUS.equals(requestPath)
                || FitbitResourceClient.PATH_APP_DOWNLOAD_STATUS.equals(requestPath)) {
            handleFitbitRawResource(requestPath, payload);
        }
    }

    private void handleFitbitDeviceInfo(final byte[] payload) {
        final FitbitMobileDataProto.DeviceInfo deviceInfo;
        try {
            deviceInfo = FitbitMobileDataProto.DeviceInfo.parseFrom(payload);
        } catch (final InvalidProtocolBufferException e) {
            LOG.warn("Unable to parse Fitbit device-info payload: {}", GB.hexdump(payload), e);
            return;
        }

        LOG.info("Fitbit device-info: {}", describeFitbitDeviceInfo(deviceInfo));
        updateFitbitDeviceMetadata(deviceInfo);
        if (!deviceInfo.hasBatteryLevel()) {
            LOG.warn("Fitbit device-info did not contain a battery level: {}", GB.hexdump(payload));
            return;
        }

        publishFitbitBatteryInfo(deviceInfo);
    }

    private void publishFitbitBatteryInfo(final FitbitMobileDataProto.DeviceInfo deviceInfo) {
        final int batteryLevel = deviceInfo.getBatteryLevel();
        if (batteryLevel < 0 || batteryLevel > 100) {
            LOG.warn("Ignoring Fitbit battery level outside 0-100 range: {}", batteryLevel);
            return;
        }

        batteryCmd.batteryIndex = 0;
        batteryCmd.level = batteryLevel;
        batteryCmd.voltage = deviceInfo.hasVoltage() && deviceInfo.getVoltage() > 0 ? deviceInfo.getVoltage() / 1000f : -1f;
        batteryCmd.state = BatteryState.BATTERY_NORMAL;
        if (deviceInfo.hasOnCharger() && deviceInfo.getOnCharger() != 0) {
            batteryCmd.state = batteryLevel >= 100
                    ? BatteryState.BATTERY_CHARGING_FULL
                    : BatteryState.BATTERY_CHARGING;
        }
        handleGBDeviceEvent(batteryCmd);
    }

    private void updateFitbitDeviceMetadata(final FitbitMobileDataProto.DeviceInfo deviceInfo) {
        final String firmwareVersion = firmwareVersion(deviceInfo);
        if (!firmwareVersion.isEmpty()) {
            getDevice().setFirmwareVersion(firmwareVersion);
        }

        final String bootloaderVersion = bootloaderVersion(deviceInfo);
        if (!bootloaderVersion.isEmpty()) {
            getDevice().setFirmwareVersion2("BL " + bootloaderVersion);
        }

        if (deviceInfo.hasProductId()) {
            getDevice().setModel("Fitbit product " + deviceInfo.getProductId());
        }
    }

    private void handleFitbitLiveActivity(final byte[] payload) {
        final FitbitMobileDataProto.LiveActivity liveActivity;
        try {
            liveActivity = FitbitMobileDataProto.LiveActivity.parseFrom(payload);
        } catch (final InvalidProtocolBufferException e) {
            finishFitbitLiveActivitySnapshotRequest();
            finishRecordedDataFetchIfIdle();
            LOG.warn("Unable to parse Fitbit liveactivity payload: {}", GB.hexdump(payload), e);
            return;
        }

        LOG.info("Fitbit liveactivity: {}", FitbitLiveActivity.describe(liveActivity));
        publishFitbitLiveActivity(liveActivity);
        persistFitbitLiveActivity(liveActivity);
        persistFitbitLiveHeartRate(liveActivity);
        updateFitbitHeartRateTestPolling(liveActivity);
        finishFitbitLiveActivitySnapshotRequest();
        finishRecordedDataFetchIfIdle();
    }

    private void handleFitbitSyncStatus(final byte[] payload) {
        final FitbitMobileDataProto.SyncStatus syncStatus;
        try {
            syncStatus = FitbitMobileDataProto.SyncStatus.parseFrom(payload);
        } catch (final InvalidProtocolBufferException e) {
            pendingRecordedDataSyncStatus = false;
            finishRecordedDataFetchIfIdle();
            LOG.warn("Unable to parse Fitbit sync-status payload: {}", GB.hexdump(payload), e);
            return;
        }

        LOG.info("Fitbit sync-status: {}, outstanding={}", describeFitbitSyncStatus(syncStatus), hasOutstandingSync(syncStatus));
        pendingRecordedDataSyncStatus = false;
        finishRecordedDataFetchIfIdle();
    }

    private void handleFitbitRawResource(final String requestPath, final byte[] payload) {
        final int previewLength = Math.min(payload.length, 64);
        LOG.info("Fitbit raw resource {}: payloadLen={}", requestPath, payload.length);
        if (previewLength > 0) {
            LOG.info("Fitbit raw resource {} first {} bytes: {}",
                    requestPath,
                    previewLength,
                    GB.hexdump(payload, 0, previewLength));
        }
    }

    private void finishRecordedDataFetchIfIdle() {
        if (!recordedDataFetchInProgress) {
            return;
        }

        if (pendingLiveActivitySnapshotRequests > 0 || pendingRecordedDataSyncStatus) {
            return;
        }

        recordedDataFetchInProgress = false;
        serverStatusHandler.removeCallbacks(recordedDataFetchTimeoutRunnable);
        clearRecordedDataFetchBusyState();
        GB.signalActivityDataFinish(getDevice());
    }

    private void startRecordedDataFetchBusyState() {
        if (getDevice().isBusy()) {
            recordedDataFetchMarkedBusy = false;
            return;
        }

        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        recordedDataFetchMarkedBusy = true;
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    private void clearRecordedDataFetchBusyState() {
        if (!recordedDataFetchMarkedBusy) {
            return;
        }

        if (getDevice().isBusy()) {
            getDevice().unsetBusyTask();
        }
        recordedDataFetchMarkedBusy = false;
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    private static String describeFitbitDeviceInfo(final FitbitMobileDataProto.DeviceInfo deviceInfo) {
        return "blVer=" + bootloaderVersion(deviceInfo)
                + ", sysVer=" + firmwareVersion(deviceInfo)
                + ", sysDebugNum=" + fieldValue(deviceInfo.hasSysDebugNum(), deviceInfo.getSysDebugNum())
                + ", fwLang=" + fieldValue(deviceInfo.hasFwLang(), deviceInfo.getFwLang())
                + ", hwRevision=" + fieldValue(deviceInfo.hasHwRevision(), deviceInfo.getHwRevision())
                + ", color=" + fieldValue(deviceInfo.hasColor(), deviceInfo.getColor())
                + ", edition=" + fieldValue(deviceInfo.hasEdition(), deviceInfo.getEdition())
                + ", onCharger=" + fieldValue(deviceInfo.hasOnCharger(), deviceInfo.getOnCharger())
                + ", btAddress=" + fieldValue(deviceInfo.hasBtAddress(), FitbitCoap.toHex(deviceInfo.getBtAddress().toByteArray()))
                + ", voltage=" + fieldValue(deviceInfo.hasVoltage(), deviceInfo.getVoltage())
                + ", batteryLevel=" + fieldValue(deviceInfo.hasBatteryLevel(), deviceInfo.getBatteryLevel())
                + ", deviceTime=" + fieldValue(deviceInfo.hasDeviceTime(), deviceInfo.getDeviceTime())
                + ", productId=" + fieldValue(deviceInfo.hasProductId(), deviceInfo.getProductId())
                + ", fwBuildType=" + fieldValue(deviceInfo.hasFwBuildType(), buildTypeName(deviceInfo.getFwBuildType()))
                + ", gitDescribe=" + (deviceInfo.hasGitDescribe() ? deviceInfo.getGitDescribe() : "")
                + ", peripherals=" + describeFitbitPeripherals(deviceInfo);
    }

    private static String describeFitbitPeripherals(final FitbitMobileDataProto.DeviceInfo deviceInfo) {
        final StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < deviceInfo.getPeripheralCount(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            final FitbitMobileDataProto.PeripheralDevice peripheral = deviceInfo.getPeripheral(i);
            builder.append("type=").append(fieldValue(peripheral.hasDeviceType(), peripheral.getDeviceType()))
                    .append(", vendorId=").append(fieldValue(peripheral.hasVendorId(), peripheral.getVendorId()))
                    .append(", version=").append(fieldValue(peripheral.hasMajor(), peripheral.getMajor()))
                    .append(".").append(fieldValue(peripheral.hasMinor(), peripheral.getMinor()))
                    .append(".").append(fieldValue(peripheral.hasPatch(), peripheral.getPatch()));
        }
        return builder.append("]").toString();
    }

    private static String firmwareVersion(final FitbitMobileDataProto.DeviceInfo deviceInfo) {
        if (deviceInfo.hasGitDescribe() && !deviceInfo.getGitDescribe().isEmpty()) {
            return deviceInfo.getGitDescribe();
        }

        if (deviceInfo.hasSysVerMajor() && deviceInfo.hasSysVerMinor() && deviceInfo.hasSysDebugNum()) {
            return deviceInfo.getSysVerMajor() + "." + deviceInfo.getSysVerMinor() + "." + deviceInfo.getSysDebugNum();
        }

        if (deviceInfo.hasSysVerMajor() && deviceInfo.hasSysVerMinor()) {
            return deviceInfo.getSysVerMajor() + "." + deviceInfo.getSysVerMinor();
        }

        return "";
    }

    private static String bootloaderVersion(final FitbitMobileDataProto.DeviceInfo deviceInfo) {
        if (deviceInfo.hasBlVerMajor() && deviceInfo.hasBlVerMinor()) {
            return deviceInfo.getBlVerMajor() + "." + deviceInfo.getBlVerMinor();
        }

        return "";
    }

    private static String buildTypeName(final int fwBuildType) {
        switch (fwBuildType) {
            case 1:
                return "MFG";
            case 2:
                return "DEMO";
            case 3:
                return "OOB";
            case 4:
                return "FSI";
            case 5:
                return "CU";
            case 0:
                return "UNKNOWN";
            default:
                return Integer.toString(fwBuildType);
        }
    }

    private static String describeFitbitSyncStatus(final FitbitMobileDataProto.SyncStatus syncStatus) {
        final boolean hasFull = syncStatus.hasBackground() && syncStatus.getBackground().hasFull();
        final boolean hasTrickle = syncStatus.hasBackground() && syncStatus.getBackground().hasTrickle();
        final boolean fullOutstanding = hasFull
                && syncStatus.getBackground().getFull().hasOutstanding()
                && syncStatus.getBackground().getFull().getOutstanding();
        final boolean trickleOutstanding = hasTrickle
                && syncStatus.getBackground().getTrickle().hasOutstanding()
                && syncStatus.getBackground().getTrickle().getOutstanding();
        return "background=" + syncStatus.hasBackground()
                + ", full=" + fieldValue(hasFull, fullOutstanding)
                + ", trickle=" + fieldValue(hasTrickle, trickleOutstanding);
    }

    private static boolean hasOutstandingSync(final FitbitMobileDataProto.SyncStatus syncStatus) {
        if (!syncStatus.hasBackground()) {
            return false;
        }

        final FitbitMobileDataProto.BackgroundSyncStatus background = syncStatus.getBackground();
        return background.hasFull()
                && background.getFull().hasOutstanding()
                && background.getFull().getOutstanding()
                || background.hasTrickle()
                && background.getTrickle().hasOutstanding()
                && background.getTrickle().getOutstanding();
    }

    private static String fieldValue(final boolean hasValue, final int value) {
        return hasValue ? Integer.toString(value) : "(missing)";
    }

    private static String fieldValue(final boolean hasValue, final boolean value) {
        return hasValue ? Boolean.toString(value) : "(missing)";
    }

    private static String fieldValue(final boolean hasValue, final String value) {
        return hasValue ? value : "(missing)";
    }

    private void updateFitbitHeartRateTestPolling(final FitbitMobileDataProto.LiveActivity liveActivity) {
        if (pendingHeartRateTestRequests <= 0) {
            return;
        }

        if (isValidHeartRate(FitbitLiveActivity.heartRate(liveActivity))) {
            pendingHeartRateTestRequests = 0;
            return;
        }

        pendingHeartRateTestRequests--;
        if (pendingHeartRateTestRequests > 0 && !realtimeHeartRateEnabled) {
            serverStatusHandler.postDelayed(heartRateTestRunnable, LIVE_DATA_POLL_INTERVAL_MS);
        }
    }

    private void finishFitbitLiveActivitySnapshotRequest() {
        if (pendingLiveActivitySnapshotRequests > 0) {
            pendingLiveActivitySnapshotRequests--;
        }
    }

    private void publishFitbitLiveActivity(final FitbitMobileDataProto.LiveActivity liveActivity) {
        if (!isFitbitLiveDataPollingEnabled()
                && pendingHeartRateTestRequests <= 0
                && pendingLiveActivitySnapshotRequests <= 0) {
            return;
        }

        final FitbitLiveActivitySample sample = new FitbitLiveActivitySample();
        final int timestamp = FitbitLiveActivity.timestampSeconds(liveActivity);
        final int steps = FitbitLiveActivity.steps(liveActivity);
        final int heartRate = FitbitLiveActivity.heartRate(liveActivity);

        sample.setTimestamp(timestamp > 0 ? timestamp : (int) (System.currentTimeMillis() / 1000));
        sample.setRawKind(ActivityKind.UNKNOWN.getCode());
        sample.setRawIntensity(ActivitySample.NOT_MEASURED);
        sample.setActiveCalories(FitbitLiveActivity.calories(liveActivity));
        sample.setDistanceCm(FitbitLiveActivity.distanceCentimeters(liveActivity));

        final boolean shouldPublishHeartRate = realtimeHeartRateEnabled
                || pendingHeartRateTestRequests > 0
                || pendingLiveActivitySnapshotRequests > 0;
        if (shouldPublishHeartRate && isValidHeartRate(heartRate)) {
            sample.setHeartRate(heartRate);
        } else {
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
        }

        if (realtimeStepsEnabled && steps >= 0) {
            if (lastLiveActivitySteps == ActivitySample.NOT_MEASURED || steps < lastLiveActivitySteps) {
                sample.setSteps(0);
            } else {
                sample.setSteps(steps - lastLiveActivitySteps);
            }
            lastLiveActivitySteps = steps;
        } else {
            sample.setSteps(ActivitySample.NOT_MEASURED);
        }

        final Intent intent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                .putExtra(GBDevice.EXTRA_DEVICE, getDevice())
                .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, sample);
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
    }

    private void persistFitbitLiveHeartRate(final FitbitMobileDataProto.LiveActivity liveActivity) {
        if (!realtimeHeartRateEnabled
                && pendingHeartRateTestRequests <= 0
                && pendingLiveActivitySnapshotRequests <= 0) {
            return;
        }

        final int heartRate = FitbitLiveActivity.heartRate(liveActivity);
        if (!isValidHeartRate(heartRate)) {
            return;
        }

        final int liveActivityTimestamp = FitbitLiveActivity.timestampSeconds(liveActivity);
        final long timestamp = liveActivityTimestamp > 0
                ? liveActivityTimestamp * 1000L
                : System.currentTimeMillis();
        try (DBHandler db = GBApplication.acquireDB()) {
            final long userId = DBHelper.getUser(db.getDaoSession()).getId();
            final long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            final GenericHeartRateSampleProvider sampleProvider = new GenericHeartRateSampleProvider(getDevice(), db.getDaoSession());
            sampleProvider.addSample(new GenericHeartRateSample(
                    timestamp,
                    deviceId,
                    userId,
                    heartRate
            ));
            newHeartRateSamples = true;
        } catch (final Exception e) {
            LOG.warn("Unable to persist Fitbit liveactivity heart-rate sample", e);
        }
    }

    private void persistFitbitLiveActivity(final FitbitMobileDataProto.LiveActivity liveActivity) {
        final int timestamp = FitbitLiveActivity.timestampSeconds(liveActivity);
        final int steps = FitbitLiveActivity.steps(liveActivity);
        final int distanceCm = FitbitLiveActivity.distanceCentimeters(liveActivity);
        final int calories = FitbitLiveActivity.calories(liveActivity);
        final int heartRate = FitbitLiveActivity.heartRate(liveActivity);
        final int elevation = FitbitLiveActivity.elevation(liveActivity);
        final int vaMinutes = FitbitLiveActivity.vaMinutes(liveActivity);
        final int dailyZoneMinutes = FitbitLiveActivity.dailyZoneMinutes(liveActivity);
        final int weeklyZoneMinutes = FitbitLiveActivity.weeklyZoneMinutes(liveActivity);

        if (!hasMeasuredValue(steps)
                && !hasMeasuredValue(distanceCm)
                && !hasMeasuredValue(calories)
                && !isValidHeartRate(heartRate)
                && !hasMeasuredValue(elevation)
                && !hasMeasuredValue(vaMinutes)
                && !hasMeasuredValue(dailyZoneMinutes)
                && !hasMeasuredValue(weeklyZoneMinutes)) {
            return;
        }

        final int sampleTimestamp = timestamp > 0
                ? (timestamp / 60) * 60
                : (int) ((System.currentTimeMillis() / 1000L / 60L) * 60L);

        try (DBHandler db = GBApplication.acquireDB()) {
            final long userId = DBHelper.getUser(db.getDaoSession()).getId();
            final long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            final FitbitActivitySampleProvider sampleProvider =
                    new FitbitActivitySampleProvider(getDevice(), db.getDaoSession());

            final FitbitActivitySample sample = sampleProvider.createActivitySample();
            sample.setTimestamp(sampleTimestamp);
            sample.setDeviceId(deviceId);
            sample.setUserId(userId);
            sample.setRawKind(ActivityKind.ACTIVITY.getCode());
            sample.setRawIntensity(ActivitySample.NOT_MEASURED);
            sample.setSteps(steps);
            sample.setDistanceCm(distanceCm);
            sample.setActiveCalories(calories);
            sample.setHeartRate(isValidHeartRate(heartRate) ? heartRate : ActivitySample.NOT_MEASURED);
            sample.setHeartRateConfidence(measuredOrNull(FitbitLiveActivity.heartRateConfidence(liveActivity)));
            sample.setElevation(measuredOrNull(elevation));
            sample.setVaMinutes(measuredOrNull(vaMinutes));
            sample.setDailyZoneMinutes(measuredOrNull(dailyZoneMinutes));
            sample.setWeeklyZoneMinutes(measuredOrNull(weeklyZoneMinutes));

            sampleProvider.addGBActivitySample(sample);
            newHeartRateSamples = true;
        } catch (final Exception e) {
            LOG.warn("Unable to persist Fitbit liveactivity sample", e);
        }
    }

    private static boolean hasMeasuredValue(final int value) {
        return value != ActivitySample.NOT_MEASURED;
    }

    private static Integer measuredOrNull(final int value) {
        return hasMeasuredValue(value) ? value : null;
    }

    private static boolean isValidHeartRate(final int heartRate) {
        return HeartRateUtils.getInstance().isValidHeartRateValue(heartRate);
    }

    private void markDeviceInitialized(final String taskName) {
        try {
            final TransactionBuilder builder = createTransactionBuilder(taskName);
            builder.setDeviceState(GBDevice.State.INITIALIZED);
            builder.queueConnected();
        } catch (final IOException e) {
            LOG.warn("Unable to mark Fitbit initialized", e);
        }
    }

    private void startPhoneGattlinkStatusNotifications() {
        serverStatusHandler.removeCallbacks(serverStatusRunnable);
        serverStatusHandler.postDelayed(serverStatusRunnable, 1000);
    }

    private void stopPhoneGattlinkStatusNotifications() {
        phoneGattlinkStatus2Subscribed = false;
        serverStatusHandler.removeCallbacks(serverStatusRunnable);
    }

    private void sendPhoneGattlinkStatus() {
        final BluetoothGattCharacteristic phoneGattlinkStatus2Characteristic =
                phoneLocalGatt.getPhoneGattlinkStatus2Characteristic();
        if (!phoneGattlinkStatus2Subscribed || serverDevice == null || phoneGattlinkStatus2Characteristic == null) {
            return;
        }

        final BtLEQueue queue = getQueue();
        if (queue == null) {
            LOG.warn("Unable to notify Fitbit local GATT status, queue is missing");
            return;
        }

        final ServerTransactionBuilder builder = createServerTransactionBuilder("Fitbit local GATT status notify");
        builder.notifyCharacteristicChanged(serverDevice, phoneGattlinkStatus2Characteristic, phoneLocalGatt.getPhoneGattlinkStatus2Value());
        builder.queue(queue);
    }

    private void queueServerResponse(final String taskName,
                                     final BluetoothDevice device,
                                     final int requestId,
                                     final int status,
                                     final int offset,
                                     final byte[] value) {
        final BtLEQueue queue = getQueue();
        if (queue == null) {
            LOG.warn("Unable to queue Fitbit local GATT response, queue is missing");
            return;
        }

        final ServerTransactionBuilder builder = createServerTransactionBuilder(taskName);
        builder.writeServerResponse(device, requestId, status, offset, value);
        builder.queue(queue);
    }

    private void handleDeviceInfo(final DeviceInfo deviceInfo) {
        LOG.info("Fitbit BLE device info: {}", deviceInfo);
        for (final GBDeviceEvent event : DeviceInfoProfile.toDeviceEvents(deviceInfo)) {
            handleGBDeviceEvent(event);
        }
    }

    private void handleBatteryInfo(final BatteryInfo batteryInfo) {
        LOG.info("Fitbit BLE battery: {}%", batteryInfo.getPercentCharged());
        batteryCmd.state = BatteryState.BATTERY_NORMAL;
        batteryCmd.level = batteryInfo.getPercentCharged();
        handleGBDeviceEvent(batteryCmd);
    }

    private void handleHeartRate(final HeartRate heartRate) {
        LOG.info("Fitbit BLE Heart Rate: {}", heartRate);

        if (!heartRate.isValid()) {
            return;
        }

        try (DBHandler db = GBApplication.acquireDB()) {
            final long userId = DBHelper.getUser(db.getDaoSession()).getId();
            final long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            final GenericHeartRateSampleProvider sampleProvider = new GenericHeartRateSampleProvider(getDevice(), db.getDaoSession());
            sampleProvider.addSample(new GenericHeartRateSample(
                    heartRate.getTimestamp(),
                    deviceId,
                    userId,
                    heartRate.getHeartRate()
            ));

            final ArrayList<Integer> rrIntervals = heartRate.getRrIntervals();
            if (!rrIntervals.isEmpty()) {
                final List<HeartRrIntervalSample> rrIntervalSamples = new ArrayList<>();
                for (int i = 0; i < rrIntervals.size(); i++) {
                    final HeartRrIntervalSample rrSample = new HeartRrIntervalSample();
                    rrSample.setTimestamp(heartRate.getTimestamp());
                    rrSample.setDeviceId(deviceId);
                    rrSample.setUserId(userId);
                    rrSample.setSeq(i);
                    rrSample.setRrMillis(rrIntervals.get(i));
                    rrIntervalSamples.add(rrSample);
                }

                final HeartRrIntervalSampleProvider rrIntervalSampleProvider =
                        new HeartRrIntervalSampleProvider(getDevice(), db.getDaoSession());
                rrIntervalSampleProvider.persistSamples(rrIntervalSamples, getContext());
            }

            newHeartRateSamples = true;
        } catch (final Exception e) {
            LOG.warn("Unable to persist Fitbit BLE heart-rate sample", e);
        }
    }

    private void signalHeartRateDataFinishIfNeeded() {
        if (!newHeartRateSamples) {
            return;
        }

        GB.signalActivityDataFinish(getDevice());
        newHeartRateSamples = false;
    }

}
