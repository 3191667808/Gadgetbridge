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
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.fitbit.FitbitConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEQueue;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.btle.ServerTransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class FitbitDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(FitbitDeviceSupport.class);

    private static final int REQUESTED_MTU = 185;
    private static final long SERVER_STATUS_NOTIFY_INTERVAL_MS = 5000;
    private static final byte[] EMPTY_RESPONSE = new byte[0];
    private static final byte[] ZERO_RESPONSE = new byte[]{0x00};
    private static final byte[] PHONE_GATTLINK_STATUS_1_RESPONSE = new byte[18];
    private static final byte[] CCCD_DISABLED_RESPONSE = new byte[]{0x00, 0x00};
    private static final String BOOTSTRAP_PSK_IDENTITY = "BOOTSTRAP";
    private static final byte[] BOOTSTRAP_PSK = new byte[]{
            (byte) 0x81, 0x06, 0x54, (byte) 0xe3,
            0x36, (byte) 0xad, (byte) 0xca, (byte) 0xb0,
            (byte) 0xa0, 0x3c, 0x60, (byte) 0xf7,
            0x4a, (byte) 0xa0, (byte) 0xb6, (byte) 0xfb,
    };

    private final Handler serverStatusHandler = new Handler(Looper.getMainLooper());
    private final FitbitGattlink.Session gattlinkSession = new FitbitGattlink.Session();
    private final FitbitDtls fitbitDtls = new FitbitDtls(this::resolvePsk);

    private BluetoothDevice serverDevice;
    private BluetoothGattCharacteristic phoneGattlinkStatus2Characteristic;
    private boolean phoneGattlinkStatus2Subscribed;

    private final Runnable serverStatusRunnable = new Runnable() {
        @Override
        public void run() {
            sendPhoneGattlinkStatus();
            if (phoneGattlinkStatus2Subscribed && serverDevice != null) {
                serverStatusHandler.postDelayed(this, SERVER_STATUS_NOTIFY_INTERVAL_MS);
            }
        }
    };

    public FitbitDeviceSupport() {
        super(LOG);

        addSupportedService(FitbitConstants.BOOTSTRAP_SERVICE);
        addSupportedService(FitbitConstants.GATTLINK_SERVICE);
        addSupportedServerService(createPhoneLocationService());
        addSupportedServerService(createPhoneGattlinkService());
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }

    @Override
    public void onSendConfiguration(final String config) {
        if (config != null && config.startsWith(FitbitConstants.CONFIG_PAIRING_CODE_PREFIX)) {
            final String pairingCode = config.substring(FitbitConstants.CONFIG_PAIRING_CODE_PREFIX.length()).trim();
            LOG.warn("Fitbit onboarding script: received live pairing code from GB UI");
            if (fitbitDtls.setPairingCode(pairingCode)) {
                drainFitbitDtlsOutboundPackets();
                LocalBroadcastManager.getInstance(getContext()).sendBroadcast(
                        new Intent(FitbitConstants.ACTION_PAIRING_CODE_ACCEPTED)
                );
            }
            return;
        }

        super.onSendConfiguration(config);
    }

    private byte[] resolvePsk(final String identity) {
        if (BOOTSTRAP_PSK_IDENTITY.equals(identity)) {
            return Arrays.copyOf(BOOTSTRAP_PSK, BOOTSTRAP_PSK.length);
        }

        // MD-* identities require Fitbit mobile-data AES keys. Those are cloud/app-private
        // credentials and are not derivable from the DTLS identity itself.
        return null;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        LOG.info("Initializing Fitbit BLE connection scaffold, onboardingMode={}",
                FitbitConstants.EXPERIMENTAL_ONBOARDING_MODE);

        gattlinkSession.reset();
        fitbitDtls.reset();

        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.requestMtu(REQUESTED_MTU);
        builder.read(FitbitConstants.BOOTSTRAP_READ_CHARACTERISTIC);
        builder.write(FitbitConstants.BOOTSTRAP_WRITE_CHARACTERISTIC, FitbitConstants.BOOTSTRAP_WRITE_OPEN);
        builder.notify(FitbitConstants.GATTLINK_NOTIFY_CHARACTERISTIC, true);

        return builder;
    }

    @Override
    public void dispose() {
        stopPhoneGattlinkStatusNotifications();
        gattlinkSession.reset();
        fitbitDtls.reset();
        super.dispose();
    }

    @Override
    public boolean onCharacteristicRead(final BluetoothGatt gatt,
                                        final BluetoothGattCharacteristic characteristic,
                                        final byte[] value,
                                        final int status) {
        final UUID uuid = characteristic.getUuid();
        if (FitbitConstants.BOOTSTRAP_READ_CHARACTERISTIC.equals(uuid)) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                LOG.warn("Fitbit bootstrap read failed with status {}", status);
                return true;
            }

            LOG.info("Fitbit bootstrap read: {}", GB.hexdump(value));
            if (!Arrays.equals(value, FitbitConstants.BOOTSTRAP_READ_EXPECTED_VALUE)) {
                LOG.warn("Fitbit bootstrap read did not match expected write characteristic UUID bytes");
            }
            return true;
        }

        return super.onCharacteristicRead(gatt, characteristic, value, status);
    }

    @Override
    public boolean onCharacteristicWrite(final BluetoothGatt gatt,
                                         final BluetoothGattCharacteristic characteristic,
                                         final int status) {
        final UUID uuid = characteristic.getUuid();
        if (FitbitConstants.BOOTSTRAP_WRITE_CHARACTERISTIC.equals(uuid)
                || FitbitConstants.GATTLINK_WRITE_CHARACTERISTIC.equals(uuid)) {
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
            }
            drainFitbitDtlsOutboundPackets();
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
            stopPhoneGattlinkStatusNotifications();
            gattlinkSession.reset();
            fitbitDtls.reset();
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

    private void drainFitbitDtlsOutboundPackets() {
        byte[] outboundPacket = fitbitDtls.pollOutboundPacket();
        while (outboundPacket != null) {
            writeGattlinkIpPacket("Fitbit DTLS outbound", outboundPacket);
            outboundPacket = fitbitDtls.pollOutboundPacket();
        }
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
        if (FitbitConstants.EXPERIMENTAL_ONBOARDING_MODE) {
            return new byte[]{0x01};
        }

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
}
