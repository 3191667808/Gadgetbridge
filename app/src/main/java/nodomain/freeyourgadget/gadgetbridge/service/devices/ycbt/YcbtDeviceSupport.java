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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.ycbt.YcbtConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.NotifyAction;

public class YcbtDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(YcbtDeviceSupport.class);
    private YcbtInboundRouter inboundRouter = new YcbtInboundRouter();

    public YcbtDeviceSupport() {
        super(LOG);
        addSupportedService(YcbtConstants.SERVICE_UUID);
    }

    @Override
    protected TransactionBuilder initializeDevice(@NonNull final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        inboundRouter = new YcbtInboundRouter();

        final List<BluetoothGattCharacteristic> indicationCharacteristics = new ArrayList<>(2);
        for (final UUID characteristicUuid : YcbtInboundRouter.getInboundCharacteristicUuids()) {
            final BluetoothGattCharacteristic characteristic = getCharacteristic(characteristicUuid);
            if (!isUsableIndicationCharacteristic(characteristic, characteristicUuid)) {
                LOG.error("YCBT initialization failed: service {} or required indication characteristics are missing or invalid",
                        YcbtConstants.SERVICE_UUID);
                builder.setDeviceState(GBDevice.State.NOT_CONNECTED);
                builder.run(this::disconnect);
                return builder;
            }
            indicationCharacteristics.add(characteristic);
        }

        for (final BluetoothGattCharacteristic characteristic : indicationCharacteristics) {
            builder.add(new YcbtIndicateAction(characteristic));
        }
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    private boolean isUsableIndicationCharacteristic(final BluetoothGattCharacteristic characteristic,
                                                      final UUID expectedUuid) {
        if (characteristic == null) {
            LOG.error("YCBT characteristic {} was not discovered under service {}", expectedUuid, YcbtConstants.SERVICE_UUID);
            return false;
        }

        final int properties = characteristic.getProperties();
        if (!supportsIndications(properties)) {
            LOG.error("YCBT characteristic {} does not advertise indication support; properties=0x{}",
                    expectedUuid, Integer.toHexString(properties));
            return false;
        }

        final BluetoothGattDescriptor cccd = characteristic.getDescriptor(
                GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION
        );
        if (cccd == null) {
            LOG.error("YCBT characteristic {} has no CCCD", expectedUuid);
            return false;
        }
        return true;
    }

    static boolean supportsIndications(final int properties) {
        return (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }

    private final class YcbtIndicateAction extends BtLEAction {
        private boolean descriptorWriteStarted;

        private YcbtIndicateAction(final BluetoothGattCharacteristic characteristic) {
            super(characteristic);
        }

        @Override
        @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
        public boolean run(@NonNull final BluetoothGatt gatt) {
            descriptorWriteStarted = false;
            if (gatt.setCharacteristicNotification(getCharacteristic(), true)) {
                final BluetoothGattDescriptor cccd = getCharacteristic().getDescriptor(
                        GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION
                );
                descriptorWriteStarted = NotifyAction.writeDescriptor(
                        gatt,
                        cccd,
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                );
            }

            if (!descriptorWriteStarted) {
                final UUID characteristicUuid = getCharacteristic().getUuid();
                LOG.error("Failed to start YCBT indication setup on {}", characteristicUuid);
                getDevice().setUpdateState(GBDevice.State.NOT_CONNECTED, getContext());
                disconnect();
            }
            return descriptorWriteStarted;
        }

        @Override
        public boolean expectsResult() {
            return descriptorWriteStarted;
        }
    }

    @Override
    public boolean onDescriptorWrite(final BluetoothGatt gatt,
                                     final BluetoothGattDescriptor descriptor,
                                     final int status) {
        final boolean handledByParent = super.onDescriptorWrite(gatt, descriptor, status);
        if (!GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION.equals(descriptor.getUuid())
                || !inboundRouter.accepts(descriptor.getCharacteristic().getUuid())) {
            return handledByParent;
        }

        final UUID characteristicUuid = descriptor.getCharacteristic().getUuid();
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.error("Failed to enable YCBT indications on {}: GATT status {}", characteristicUuid, status);
            getDevice().setUpdateState(GBDevice.State.NOT_CONNECTED, getContext());
            disconnect();
        } else {
            LOG.info("Enabled YCBT indications on {}", characteristicUuid);
        }
        return true;
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }

        final UUID characteristicUuid = characteristic.getUuid();
        final YcbtInboundRouter.RouteResult result;
        try {
            result = inboundRouter.accept(characteristicUuid, value);
        } catch (final RuntimeException e) {
            LOG.error("Unexpected YCBT frame reassembly failure from {}", characteristicUuid, e);
            return inboundRouter.accepts(characteristicUuid);
        }
        if (!result.isAccepted()) {
            return false;
        }

        if (result.getMalformedReason() != null) {
            LOG.warn("Malformed YCBT chunk from {} (length={}): {}",
                    characteristicUuid, value == null ? 0 : value.length, result.getMalformedReason());
        }
        for (final YcbtFrameCodec.Frame frame : result.getFrames()) {
            LOG.info("Decoded YCBT frame from {}: group=0x{}, command=0x{}, payloadLength={}",
                    characteristicUuid,
                    String.format(Locale.ROOT, "%02x", frame.getGroup()),
                    String.format(Locale.ROOT, "%02x", frame.getCommand()),
                    frame.getPayload().length);
        }
        return true;
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            LOG.warn("YCBT GATT disconnected with status {}", status);
        }
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }
}
