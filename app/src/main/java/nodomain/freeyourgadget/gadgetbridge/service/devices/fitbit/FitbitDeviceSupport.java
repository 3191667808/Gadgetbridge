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
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.HeartRrIntervalSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.fitbit.FitbitConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartRrIntervalSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEQueue;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattDescriptor;
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
    private static final byte[] ZERO_RESPONSE = new byte[]{0x00};
    private static final byte[] PHONE_GATTLINK_STATUS_1_RESPONSE = new byte[18];
    private static final byte[] CCCD_DISABLED_RESPONSE = new byte[]{0x00, 0x00};
    private static final String MOBILE_DATA_PSK_IDENTITY_PREFIX = "MD-";
    private static final int MOBILE_DATA_PSK_IDENTITY_LENGTH = 20;
    private static final int MOBILE_DATA_PSK_LENGTH = 16;
    private static final long LIVE_DATA_POLL_INTERVAL_MS = 1000;
    private static final int HEART_RATE_TEST_REQUESTS = 10;
    private static final String COAP_PATH_DEVICE_INFO = "md/606";
    private static final String COAP_PATH_LIVE_ACTIVITY = "liveactivity";

    private final Handler serverStatusHandler = new Handler(Looper.getMainLooper());
    private final FitbitGattlink.Session gattlinkSession = new FitbitGattlink.Session();
    private final FitbitDtls fitbitDtls = new FitbitDtls(this::resolvePsk, this::handleFitbitCoapResponse);
    private final DeviceInfoProfile<FitbitDeviceSupport> deviceInfoProfile;
    private final BatteryInfoProfile<FitbitDeviceSupport> batteryInfoProfile;
    private final HeartRateProfile<FitbitDeviceSupport> heartRateProfile;
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();

    private BluetoothDevice serverDevice;
    private BluetoothGattCharacteristic phoneGattlinkStatus2Characteristic;
    private boolean phoneGattlinkStatus2Subscribed;
    private boolean newHeartRateSamples;
    private boolean realtimeStepsEnabled;
    private boolean realtimeHeartRateEnabled;
    private boolean initialFitbitDeviceInfoRequested;
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

    private final Runnable initialDeviceInfoRunnable = new Runnable() {
        @Override
        public void run() {
            requestFitbitDeviceInfo();
        }
    };

    public FitbitDeviceSupport() {
        super(LOG);

        addSupportedService(FitbitConstants.GATTLINK_SERVICE);
        addSupportedService(DeviceInfoProfile.SERVICE_UUID);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
        addSupportedService(GattService.UUID_SERVICE_HEART_RATE);
        addSupportedServerService(createPhoneLocationService());
        addSupportedServerService(createPhoneGattlinkService());

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
        if (isMobileDataPskIdentity(identity)) {
            final byte[] mobileDataPsk = loadMobileDataPsk(identity);
            if (mobileDataPsk != null) {
                LOG.info("Resolved Fitbit mobile-data PSK for identity {}", identity);
                return mobileDataPsk;
            }

            LOG.warn("Missing Fitbit mobile-data PSK for identity {}. Enter the mobile-data key in the Fitbit pairing screen. Matching uses the first MD key-id field.",
                    identity);
        }

        return null;
    }

    private byte[] loadMobileDataPsk(final String identity) {
        final String normalizedIdentity = normalizeMobileDataPskIdentity(identity);
        if (normalizedIdentity == null) {
            return null;
        }

        return loadMobileDataPskFromPreferences(normalizedIdentity);
    }

    private byte[] loadMobileDataPskFromPreferences(final String normalizedIdentity) {
        final String requestedKeyId = mobileDataPskIdentityKeyId(normalizedIdentity);
        final SharedPreferences preferences = getDevicePrefs().getPreferences();
        final String storedKeys = preferences.getString(FitbitConstants.PREF_MOBILE_DATA_KEYS, "");
        for (final String line : storedKeys.split("\\R")) {
            final String normalizedEntry = normalizeMobileDataKeyEntry(line);
            if (normalizedEntry == null) {
                continue;
            }

            if (requestedKeyId.equals(mobileDataKeyEntryKeyId(normalizedEntry))) {
                return parseMobileDataKey(normalizedEntry);
            }
        }

        return null;
    }

    private static String normalizeMobileDataKeyEntry(final String rawEntry) {
        if (rawEntry == null) {
            return null;
        }

        String entry = rawEntry.trim();
        if (entry.isEmpty() || entry.startsWith("#")) {
            return null;
        }

        final int delimiterIndex = findMobileDataKeyDelimiter(entry);
        if (delimiterIndex <= 0 || delimiterIndex >= entry.length() - 1) {
            return null;
        }

        final String identity = normalizeMobileDataKeyReference(entry.substring(0, delimiterIndex).trim());
        if (identity == null) {
            return null;
        }

        final String keyHex = cleanHexString(entry.substring(delimiterIndex + 1));
        if (keyHex.length() != MOBILE_DATA_PSK_LENGTH * 2 || !isHexString(keyHex)) {
            return null;
        }

        return identity + ":" + keyHex.toLowerCase(Locale.ROOT);
    }

    private static int findMobileDataKeyDelimiter(final String entry) {
        final int colonIndex = entry.indexOf(':');
        final int equalsIndex = entry.indexOf('=');
        final int spaceIndex = firstWhitespaceIndex(entry);

        int delimiterIndex = -1;
        if (colonIndex >= 0) {
            delimiterIndex = colonIndex;
        }
        if (equalsIndex >= 0 && (delimiterIndex < 0 || equalsIndex < delimiterIndex)) {
            delimiterIndex = equalsIndex;
        }
        if (spaceIndex >= 0 && (delimiterIndex < 0 || spaceIndex < delimiterIndex)) {
            delimiterIndex = spaceIndex;
        }
        return delimiterIndex;
    }

    private static int firstWhitespaceIndex(final String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeMobileDataPskIdentity(final String identity) {
        if (identity == null) {
            return null;
        }

        final String normalized = identity.trim().toUpperCase(Locale.ROOT);
        if (!isMobileDataPskIdentity(normalized)) {
            return null;
        }

        final String keyId = normalized.substring(3, 11);
        final String expiration = normalized.substring(12, 20);
        if (!isHexString(keyId) || !isHexString(expiration)) {
            return null;
        }

        return normalized;
    }

    private static String normalizeMobileDataKeyReference(final String reference) {
        if (reference == null) {
            return null;
        }

        final String normalizedIdentity = normalizeMobileDataPskIdentity(reference);
        if (normalizedIdentity != null) {
            return normalizedIdentity;
        }

        final String keyId = cleanHexString(reference).toUpperCase(Locale.ROOT);
        if (keyId.length() == 8 && isHexString(keyId)) {
            return MOBILE_DATA_PSK_IDENTITY_PREFIX + keyId + "-00000000";
        }

        return null;
    }

    private static boolean isMobileDataPskIdentity(final String identity) {
        return identity != null
                && identity.length() == MOBILE_DATA_PSK_IDENTITY_LENGTH
                && identity.startsWith(MOBILE_DATA_PSK_IDENTITY_PREFIX)
                && identity.charAt(11) == '-';
    }

    private static String mobileDataKeyEntryIdentity(final String normalizedEntry) {
        return normalizedEntry.substring(0, normalizedEntry.indexOf(':'));
    }

    private static String mobileDataKeyEntryKeyId(final String normalizedEntry) {
        return mobileDataPskIdentityKeyId(mobileDataKeyEntryIdentity(normalizedEntry));
    }

    private static String mobileDataPskIdentityKeyId(final String normalizedIdentity) {
        return normalizedIdentity.substring(3, 11);
    }

    private static byte[] parseMobileDataKey(final String normalizedEntry) {
        final String keyHex = normalizedEntry.substring(normalizedEntry.indexOf(':') + 1);
        final byte[] key = new byte[keyHex.length() / 2];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) Integer.parseInt(keyHex.substring(i * 2, i * 2 + 2), 16);
        }
        return key;
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

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        LOG.info("Initializing Fitbit BLE connection");

        gattlinkSession.reset();
        fitbitDtls.reset();
        initialFitbitDeviceInfoRequested = false;
        pendingHeartRateTestRequests = 0;

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
        realtimeStepsEnabled = enable;
        updateFitbitLiveDataPolling();
    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(final boolean enable) {
        realtimeHeartRateEnabled = enable;
        updateFitbitLiveDataPolling();
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
        final byte[] value = readLocalServerCharacteristic(uuid);

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
        queueServerResponse("Fitbit local GATT descriptor read", device, requestId, BluetoothGatt.GATT_SUCCESS, offset, CCCD_DISABLED_RESPONSE);
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

        if (isPhoneGattlinkDescriptor(characteristicUuid)) {
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

    private BluetoothGattService createPhoneLocationService() {
        final BluetoothGattService service = new BluetoothGattService(
                FitbitConstants.PHONE_LOCATION_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_LOCATION_STATUS_1_CHARACTERISTIC));
        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_LOCATION_STATUS_2_CHARACTERISTIC));
        service.addCharacteristic(new BluetoothGattCharacteristic(
                FitbitConstants.PHONE_LOCATION_WRITE_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        ));
        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_LOCATION_STATUS_4_CHARACTERISTIC));

        return service;
    }

    private BluetoothGattService createPhoneGattlinkService() {
        final BluetoothGattService service = new BluetoothGattService(
                FitbitConstants.PHONE_GATTLINK_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_GATTLINK_STATUS_1_CHARACTERISTIC));
        phoneGattlinkStatus2Characteristic = notifiableReadCharacteristic(FitbitConstants.PHONE_GATTLINK_STATUS_2_CHARACTERISTIC);
        service.addCharacteristic(phoneGattlinkStatus2Characteristic);
        service.addCharacteristic(notifiableReadCharacteristic(FitbitConstants.PHONE_GATTLINK_STATUS_3_CHARACTERISTIC));

        return service;
    }

    private BluetoothGattCharacteristic notifiableReadCharacteristic(final UUID uuid) {
        final BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        characteristic.addDescriptor(new BluetoothGattDescriptor(
                GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        ));
        return characteristic;
    }

    private byte[] readLocalServerCharacteristic(final UUID uuid) {
        if (FitbitConstants.PHONE_GATTLINK_STATUS_1_CHARACTERISTIC.equals(uuid)) {
            return PHONE_GATTLINK_STATUS_1_RESPONSE;
        }
        if (FitbitConstants.PHONE_GATTLINK_STATUS_2_CHARACTERISTIC.equals(uuid)) {
            return getPhoneGattlinkStatus2Value();
        }
        if (FitbitConstants.PHONE_GATTLINK_STATUS_3_CHARACTERISTIC.equals(uuid)
                || FitbitConstants.PHONE_LOCATION_STATUS_1_CHARACTERISTIC.equals(uuid)
                || FitbitConstants.PHONE_LOCATION_STATUS_2_CHARACTERISTIC.equals(uuid)
                || FitbitConstants.PHONE_LOCATION_STATUS_4_CHARACTERISTIC.equals(uuid)) {
            return ZERO_RESPONSE;
        }
        return null;
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
        serverStatusHandler.removeCallbacks(initialDeviceInfoRunnable);
        pendingHeartRateTestRequests = 0;
    }

    private void requestInitialFitbitDeviceInfoIfNeeded() {
        if (initialFitbitDeviceInfoRequested || !fitbitDtls.isSessionEstablished()) {
            return;
        }

        initialFitbitDeviceInfoRequested = true;
        serverStatusHandler.postDelayed(initialDeviceInfoRunnable, 1000);
    }

    private void requestFitbitDeviceInfo() {
        final byte[] ipv4Packet = fitbitDtls.buildCoapGetRequest(COAP_PATH_DEVICE_INFO);
        if (ipv4Packet == null) {
            return;
        }

        writeGattlinkIpPacket("Fitbit device-info request", ipv4Packet);
    }

    private void requestFitbitLiveActivity() {
        final byte[] ipv4Packet = fitbitDtls.buildCoapGetRequest(COAP_PATH_LIVE_ACTIVITY);
        if (ipv4Packet == null) {
            return;
        }

        writeGattlinkIpPacket("Fitbit liveactivity request", ipv4Packet);
    }

    private void handleFitbitCoapResponse(final String requestPath, final int code, final byte[] payload) {
        if ((code >> 5) != 2) {
            LOG.warn("Fitbit CoAP response for {} was not successful: code={}.{}, payloadLen={}",
                    requestPath,
                    (code >> 5) & 0x07,
                    code & 0x1f,
                    payload.length);
            return;
        }

        if (COAP_PATH_LIVE_ACTIVITY.equals(requestPath)) {
            handleFitbitLiveActivity(payload);
        } else if (COAP_PATH_DEVICE_INFO.equals(requestPath)) {
            handleFitbitDeviceInfo(payload);
        }
    }

    private void handleFitbitDeviceInfo(final byte[] payload) {
        final FitbitDeviceInfo deviceInfo;
        try {
            deviceInfo = FitbitDeviceInfo.parse(payload);
        } catch (final IllegalArgumentException e) {
            LOG.warn("Unable to parse Fitbit device-info payload: {}", GB.hexdump(payload), e);
            return;
        }

        LOG.info("Fitbit device-info: {}", deviceInfo);
        if (!deviceInfo.hasBatteryLevel) {
            LOG.warn("Fitbit device-info did not contain a battery level: {}", GB.hexdump(payload));
            return;
        }

        publishFitbitBatteryInfo(deviceInfo);
    }

    private void publishFitbitBatteryInfo(final FitbitDeviceInfo deviceInfo) {
        if (deviceInfo.batteryLevel < 0 || deviceInfo.batteryLevel > 100) {
            LOG.warn("Ignoring Fitbit battery level outside 0-100 range: {}", deviceInfo.batteryLevel);
            return;
        }

        batteryCmd.batteryIndex = 0;
        batteryCmd.level = deviceInfo.batteryLevel;
        batteryCmd.voltage = deviceInfo.hasVoltage && deviceInfo.voltage > 0 ? deviceInfo.voltage / 1000f : -1f;
        batteryCmd.state = BatteryState.BATTERY_NORMAL;
        if (deviceInfo.hasOnCharger && deviceInfo.onCharger != 0) {
            batteryCmd.state = deviceInfo.batteryLevel >= 100
                    ? BatteryState.BATTERY_CHARGING_FULL
                    : BatteryState.BATTERY_CHARGING;
        }
        handleGBDeviceEvent(batteryCmd);
    }

    private void handleFitbitLiveActivity(final byte[] payload) {
        final FitbitLiveActivity liveActivity;
        try {
            liveActivity = FitbitLiveActivity.parse(payload);
        } catch (final IllegalArgumentException e) {
            LOG.warn("Unable to parse Fitbit liveactivity payload: {}", GB.hexdump(payload), e);
            return;
        }

        LOG.info("Fitbit liveactivity: {}", liveActivity);
        publishFitbitLiveActivity(liveActivity);
        persistFitbitLiveHeartRate(liveActivity);
        updateFitbitHeartRateTestPolling(liveActivity);
    }

    private void updateFitbitHeartRateTestPolling(final FitbitLiveActivity liveActivity) {
        if (pendingHeartRateTestRequests <= 0) {
            return;
        }

        if (isValidHeartRate(liveActivity.heartRate)) {
            pendingHeartRateTestRequests = 0;
            return;
        }

        pendingHeartRateTestRequests--;
        if (pendingHeartRateTestRequests > 0 && !realtimeHeartRateEnabled) {
            serverStatusHandler.postDelayed(heartRateTestRunnable, LIVE_DATA_POLL_INTERVAL_MS);
        }
    }

    private void publishFitbitLiveActivity(final FitbitLiveActivity liveActivity) {
        final FitbitLiveActivitySample sample = new FitbitLiveActivitySample();
        sample.setTimestamp(liveActivity.timestamp > 0 ? liveActivity.timestamp : (int) (System.currentTimeMillis() / 1000));
        sample.setRawKind(ActivityKind.UNKNOWN.getCode());
        sample.setRawIntensity(ActivitySample.NOT_MEASURED);
        sample.setActiveCalories(liveActivity.calories);
        sample.setDistanceCm(liveActivity.distance);

        final boolean shouldPublishHeartRate = realtimeHeartRateEnabled || pendingHeartRateTestRequests > 0;
        if (shouldPublishHeartRate && isValidHeartRate(liveActivity.heartRate)) {
            sample.setHeartRate(liveActivity.heartRate);
        } else {
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
        }

        if (realtimeStepsEnabled && liveActivity.steps >= 0) {
            if (lastLiveActivitySteps == ActivitySample.NOT_MEASURED || liveActivity.steps < lastLiveActivitySteps) {
                sample.setSteps(0);
            } else {
                sample.setSteps(liveActivity.steps - lastLiveActivitySteps);
            }
            lastLiveActivitySteps = liveActivity.steps;
        } else {
            sample.setSteps(ActivitySample.NOT_MEASURED);
        }

        final Intent intent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                .putExtra(GBDevice.EXTRA_DEVICE, getDevice())
                .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, sample);
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
    }

    private void persistFitbitLiveHeartRate(final FitbitLiveActivity liveActivity) {
        if (!isValidHeartRate(liveActivity.heartRate)) {
            return;
        }

        final long timestamp = liveActivity.timestamp > 0
                ? liveActivity.timestamp * 1000L
                : System.currentTimeMillis();
        try (DBHandler db = GBApplication.acquireDB()) {
            final long userId = DBHelper.getUser(db.getDaoSession()).getId();
            final long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            final GenericHeartRateSampleProvider sampleProvider = new GenericHeartRateSampleProvider(getDevice(), db.getDaoSession());
            sampleProvider.addSample(new GenericHeartRateSample(
                    timestamp,
                    deviceId,
                    userId,
                    liveActivity.heartRate
            ));
            newHeartRateSamples = true;
        } catch (final Exception e) {
            LOG.warn("Unable to persist Fitbit liveactivity heart-rate sample", e);
        }
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
        if (!phoneGattlinkStatus2Subscribed || serverDevice == null || phoneGattlinkStatus2Characteristic == null) {
            return;
        }

        final BtLEQueue queue = getQueue();
        if (queue == null) {
            LOG.warn("Unable to notify Fitbit local GATT status, queue is missing");
            return;
        }

        final ServerTransactionBuilder builder = createServerTransactionBuilder("Fitbit local GATT status notify");
        builder.notifyCharacteristicChanged(serverDevice, phoneGattlinkStatus2Characteristic, getPhoneGattlinkStatus2Value());
        builder.queue(queue);
    }

    private byte[] getPhoneGattlinkStatus2Value() {
        return ZERO_RESPONSE;
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

    private boolean isPhoneGattlinkDescriptor(final UUID characteristicUuid) {
        return FitbitConstants.PHONE_GATTLINK_STATUS_1_CHARACTERISTIC.equals(characteristicUuid)
                || FitbitConstants.PHONE_GATTLINK_STATUS_2_CHARACTERISTIC.equals(characteristicUuid)
                || FitbitConstants.PHONE_GATTLINK_STATUS_3_CHARACTERISTIC.equals(characteristicUuid);
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

    private static int skipProtobufField(final byte[] payload, final int offset, final int wireType) {
        switch (wireType) {
            case 1:
                return checkedOffset(payload, offset, 8);
            case 2:
                final Varint length = readVarint(payload, offset);
                return checkedOffset(payload, length.nextOffset, (int) length.value);
            case 5:
                return checkedOffset(payload, offset, 4);
            default:
                throw new IllegalArgumentException("Unsupported protobuf wire type " + wireType);
        }
    }

    private static int checkedOffset(final byte[] payload, final int offset, final int length) {
        final int nextOffset = offset + length;
        if (length < 0 || nextOffset < offset || nextOffset > payload.length) {
            throw new IllegalArgumentException("Malformed protobuf field length");
        }
        return nextOffset;
    }

    private static Varint readVarint(final byte[] payload, final int offset) {
        long value = 0;
        int shift = 0;
        int pos = offset;
        while (pos < payload.length && shift < 64) {
            final int b = payload[pos++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new Varint(value, pos);
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Malformed protobuf varint");
    }

    private static final class FitbitDeviceInfo {
        private boolean hasOnCharger;
        private boolean hasVoltage;
        private boolean hasBatteryLevel;
        private boolean hasProductId;
        private int onCharger = ActivitySample.NOT_MEASURED;
        private int voltage = ActivitySample.NOT_MEASURED;
        private int batteryLevel = ActivitySample.NOT_MEASURED;
        private int productId = ActivitySample.NOT_MEASURED;
        private String gitDescribe = "";

        private static FitbitDeviceInfo parse(final byte[] payload) {
            final FitbitDeviceInfo info = new FitbitDeviceInfo();
            int pos = 0;
            while (pos < payload.length) {
                final Varint tag = readVarint(payload, pos);
                pos = tag.nextOffset;

                final int fieldNumber = (int) (tag.value >> 3);
                final int wireType = (int) (tag.value & 0x07);
                if (wireType == 0) {
                    final Varint value = readVarint(payload, pos);
                    pos = value.nextOffset;
                    info.setVarintField(fieldNumber, value.value);
                    continue;
                }

                if (wireType == 2) {
                    final Varint length = readVarint(payload, pos);
                    final int valueOffset = length.nextOffset;
                    final int nextOffset = checkedOffset(payload, valueOffset, (int) length.value);
                    if (fieldNumber == 15) {
                        info.gitDescribe = new String(payload, valueOffset, (int) length.value, StandardCharsets.UTF_8);
                    }
                    pos = nextOffset;
                    continue;
                }

                pos = skipProtobufField(payload, pos, wireType);
            }
            return info;
        }

        private void setVarintField(final int fieldNumber, final long value) {
            final int intValue = (int) value;
            switch (fieldNumber) {
                case 10:
                    hasOnCharger = true;
                    onCharger = intValue;
                    break;
                case 12:
                    hasVoltage = true;
                    voltage = intValue;
                    break;
                case 13:
                    hasBatteryLevel = true;
                    batteryLevel = intValue;
                    break;
                case 17:
                    hasProductId = true;
                    productId = intValue;
                    break;
                default:
                    break;
            }
        }

        @Override
        public String toString() {
            return "onCharger=" + fieldValue(hasOnCharger, onCharger)
                    + ", voltage=" + fieldValue(hasVoltage, voltage)
                    + ", batteryLevel=" + fieldValue(hasBatteryLevel, batteryLevel)
                    + ", productId=" + fieldValue(hasProductId, productId)
                    + ", gitDescribe=" + gitDescribe;
        }

        private static String fieldValue(final boolean hasValue, final int value) {
            return hasValue ? Integer.toString(value) : "(missing)";
        }
    }

    private static final class FitbitLiveActivity {
        private int timestamp = ActivitySample.NOT_MEASURED;
        private int steps = ActivitySample.NOT_MEASURED;
        private int distance = ActivitySample.NOT_MEASURED;
        private int calories = ActivitySample.NOT_MEASURED;
        private int elevation = ActivitySample.NOT_MEASURED;
        private int vaMinutes = ActivitySample.NOT_MEASURED;
        private int heartRate = ActivitySample.NOT_MEASURED;
        private int heartRateConfidence = ActivitySample.NOT_MEASURED;
        private int dailyZoneMinutes = ActivitySample.NOT_MEASURED;
        private int weeklyZoneMinutes = ActivitySample.NOT_MEASURED;

        private static FitbitLiveActivity parse(final byte[] payload) {
            final FitbitLiveActivity activity = new FitbitLiveActivity();
            int pos = 0;
            while (pos < payload.length) {
                final Varint tag = readVarint(payload, pos);
                pos = tag.nextOffset;

                final int fieldNumber = (int) (tag.value >> 3);
                final int wireType = (int) (tag.value & 0x07);
                if (wireType == 0) {
                    final Varint value = readVarint(payload, pos);
                    pos = value.nextOffset;
                    activity.setVarintField(fieldNumber, value.value);
                    continue;
                }

                pos = skipProtobufField(payload, pos, wireType);
            }
            return activity;
        }

        private void setVarintField(final int fieldNumber, final long value) {
            final int intValue = (int) value;
            switch (fieldNumber) {
                case 1:
                    timestamp = intValue;
                    break;
                case 2:
                    steps = intValue;
                    break;
                case 3:
                    distance = intValue;
                    break;
                case 4:
                    calories = intValue;
                    break;
                case 5:
                    elevation = intValue;
                    break;
                case 6:
                    vaMinutes = intValue;
                    break;
                case 7:
                    heartRate = intValue;
                    break;
                case 8:
                    heartRateConfidence = intValue;
                    break;
                case 9:
                    dailyZoneMinutes = intValue;
                    break;
                case 10:
                    weeklyZoneMinutes = intValue;
                    break;
                default:
                    break;
            }
        }

        @Override
        public String toString() {
            return "timestamp=" + timestamp
                    + ", steps=" + steps
                    + ", distance=" + distance
                    + ", calories=" + calories
                    + ", elevation=" + elevation
                    + ", vaMinutes=" + vaMinutes
                    + ", heartRate=" + heartRate
                    + ", heartRateConfidence=" + heartRateConfidence
                    + ", dailyZoneMinutes=" + dailyZoneMinutes
                    + ", weeklyZoneMinutes=" + weeklyZoneMinutes;
        }
    }

    private static final class Varint {
        private final long value;
        private final int nextOffset;

        private Varint(final long value, final int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }

    private static final class FitbitLiveActivitySample extends GenericActivitySample implements Serializable {
    }
}
