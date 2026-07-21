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
import android.bluetooth.BluetoothGattService;
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
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BleNamesResolver;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattDescriptor;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.NotifyAction;

public class YcbtDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(YcbtDeviceSupport.class);
    private YcbtInboundRouter inboundRouter = new YcbtInboundRouter();
    private boolean batteryQueryRequested;
    private boolean capabilityQueryRequested;

    public YcbtDeviceSupport() {
        super(LOG);
        addSupportedService(YcbtConstants.SERVICE_UUID);
    }

    @Override
    public void onServicesDiscovered(final BluetoothGatt gatt) {
        final List<BluetoothGattService> services = gatt.getServices();
        final List<YcbtGattInventory.Service> serviceMetadata = new ArrayList<>();
        final int shownServiceCount = Math.min(services.size(), YcbtGattInventory.MAX_SERVICES);
        for (int serviceIndex = 0; serviceIndex < shownServiceCount; serviceIndex++) {
            final BluetoothGattService service = services.get(serviceIndex);
            final List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            final List<YcbtGattInventory.Characteristic> characteristicMetadata = new ArrayList<>();
            final int shownCharacteristicCount = Math.min(
                    characteristics.size(),
                    YcbtGattInventory.MAX_CHARACTERISTICS_PER_SERVICE
            );
            for (int characteristicIndex = 0; characteristicIndex < shownCharacteristicCount; characteristicIndex++) {
                final BluetoothGattCharacteristic characteristic = characteristics.get(characteristicIndex);
                final List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
                final List<UUID> descriptorUuids = new ArrayList<>();
                final int shownDescriptorCount = Math.min(
                        descriptors.size(),
                        YcbtGattInventory.MAX_DESCRIPTORS_PER_CHARACTERISTIC
                );
                for (int descriptorIndex = 0; descriptorIndex < shownDescriptorCount; descriptorIndex++) {
                    descriptorUuids.add(descriptors.get(descriptorIndex).getUuid());
                }
                characteristicMetadata.add(new YcbtGattInventory.Characteristic(
                        characteristic.getUuid(),
                        characteristic.getProperties(),
                        BleNamesResolver.getCharacteristicPropertyString(characteristic.getProperties()),
                        descriptors.size(),
                        descriptorUuids
                ));
            }
            serviceMetadata.add(new YcbtGattInventory.Service(
                    service.getUuid(),
                    serviceType(service.getType()),
                    characteristics.size(),
                    characteristicMetadata
            ));
        }

        final BluetoothGattService configuredService = gatt.getService(YcbtConstants.SERVICE_UUID);
        final Integer configuredServiceCharacteristicCount = configuredService == null
                ? null
                : configuredService.getCharacteristics().size();
        final YcbtGattInventory.Inventory inventory = new YcbtGattInventory.Inventory(
                services.size(),
                serviceMetadata,
                configuredServiceCharacteristicCount
        );
        for (final String event : YcbtGattInventory.format(inventory, YcbtConstants.SERVICE_UUID)) {
            diagnostic(YcbtDiagnostics.TYPE_STAGE, event);
        }

        super.onServicesDiscovered(gatt);
    }

    @Override
    protected TransactionBuilder initializeDevice(@NonNull final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        inboundRouter = new YcbtInboundRouter();
        batteryQueryRequested = false;
        capabilityQueryRequested = false;
        diagnostic(YcbtDiagnostics.TYPE_INITIALIZE_ENTERED, "initialize entered");

        final List<BluetoothGattCharacteristic> indicationCharacteristics = new ArrayList<>(2);
        boolean allCharacteristicsUsable = true;
        for (final UUID characteristicUuid : YcbtInboundRouter.getInboundCharacteristicUuids()) {
            final BluetoothGattCharacteristic characteristic = getCharacteristic(characteristicUuid);
            if (!isUsableInboundCharacteristic(characteristic, characteristicUuid)) {
                allCharacteristicsUsable = false;
            } else {
                indicationCharacteristics.add(characteristic);
            }
        }
        if (!allCharacteristicsUsable) {
            LOG.error("YCBT initialization failed: service {} or required transport characteristics are missing or invalid",
                    YcbtConstants.SERVICE_UUID);
            builder.setDeviceState(GBDevice.State.NOT_CONNECTED);
            builder.run(this::disconnect);
            return builder;
        }

        for (final BluetoothGattCharacteristic characteristic : indicationCharacteristics) {
            builder.add(new YcbtIndicateAction(characteristic));
        }
        final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                YcbtConstants.WRITE_CHARACTERISTIC_UUID
        );
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        builder.run(() -> diagnostic(
                YcbtDiagnostics.TYPE_STAGE,
                "model probe write request=020308004750ef20"
        ));
        builder.write(commandCharacteristic, YcbtProtocol.buildModelRequest());
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        builder.run(() -> diagnostic(
                YcbtDiagnostics.TYPE_INITIALIZED,
                "INITIALIZED after indications and model probe write"
        ));
        return builder;
    }

    private boolean isUsableInboundCharacteristic(final BluetoothGattCharacteristic characteristic,
                                                   final UUID expectedUuid) {
        final String characteristicName = characteristicName(expectedUuid);
        if (characteristic == null) {
            LOG.error("YCBT characteristic {} was not discovered under service {}", expectedUuid, YcbtConstants.SERVICE_UUID);
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, characteristicName + " missing");
            return false;
        }
        diagnostic(YcbtDiagnostics.TYPE_STAGE, characteristicName + " found");

        final int properties = characteristic.getProperties();
        diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                Locale.ROOT,
                "%s properties=0x%08x",
                characteristicName,
                properties
        ));
        if (!supportsIndications(properties)) {
            LOG.error("YCBT characteristic {} does not advertise indication support; properties=0x{}",
                    expectedUuid, Integer.toHexString(properties));
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, characteristicName + " indication property missing");
            return false;
        }
        if (YcbtConstants.WRITE_CHARACTERISTIC_UUID.equals(expectedUuid)
                && !supportsWriteWithoutResponse(properties)) {
            LOG.error("YCBT command characteristic {} does not advertise write-without-response support; properties=0x{}",
                    expectedUuid, Integer.toHexString(properties));
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, characteristicName + " writeNoResponse property missing");
            return false;
        }

        final BluetoothGattDescriptor cccd = characteristic.getDescriptor(
                GattDescriptor.UUID_DESCRIPTOR_GATT_CLIENT_CHARACTERISTIC_CONFIGURATION
        );
        if (cccd == null) {
            LOG.error("YCBT characteristic {} has no CCCD", expectedUuid);
            diagnostic(YcbtDiagnostics.TYPE_FAILURE, characteristicName + " CCCD missing");
            return false;
        }
        diagnostic(YcbtDiagnostics.TYPE_STAGE, characteristicName + " CCCD present");
        return true;
    }

    static boolean supportsIndications(final int properties) {
        return (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }

    static boolean supportsWriteWithoutResponse(final int properties) {
        return (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
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
            final String characteristicName = characteristicName(getCharacteristic().getUuid());
            diagnostic(YcbtDiagnostics.TYPE_STAGE, characteristicName + " forced indication start");
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
            diagnostic(
                    descriptorWriteStarted ? YcbtDiagnostics.TYPE_STAGE : YcbtDiagnostics.TYPE_FAILURE,
                    characteristicName + " forced indication synchronous result=" + descriptorWriteStarted
            );

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
        diagnostic(
                status == BluetoothGatt.GATT_SUCCESS ? YcbtDiagnostics.TYPE_STAGE : YcbtDiagnostics.TYPE_FAILURE,
                characteristicName(characteristicUuid) + " descriptor callback status=" + status
        );
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
    public boolean onCharacteristicWrite(final BluetoothGatt gatt,
                                         final BluetoothGattCharacteristic characteristic,
                                         final int status) {
        if (YcbtConstants.WRITE_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
            diagnostic(
                    status == BluetoothGatt.GATT_SUCCESS ? YcbtDiagnostics.TYPE_STAGE : YcbtDiagnostics.TYPE_FAILURE,
                    "command write callback status=" + status
            );
        }
        final boolean handledByParent = super.onCharacteristicWrite(gatt, characteristic, status);
        return handledByParent || YcbtConstants.WRITE_CHARACTERISTIC_UUID.equals(characteristic.getUuid());
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
            diagnostic(YcbtDiagnostics.TYPE_STAGE,
                    characteristicName(characteristicUuid) + " malformed inbound reason=unexpected reassembly failure");
            return inboundRouter.accepts(characteristicUuid);
        }
        if (!result.isAccepted()) {
            return false;
        }

        if (result.getMalformedReason() != null) {
            LOG.warn("Malformed YCBT chunk from {} (length={}): {}",
                    characteristicUuid, value == null ? 0 : value.length, result.getMalformedReason());
            diagnostic(YcbtDiagnostics.TYPE_STAGE,
                    characteristicName(characteristicUuid) + " malformed inbound reason=" + result.getMalformedReason());
        }
        for (final YcbtFrameCodec.Frame frame : result.getFrames()) {
            final String model = YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(characteristicUuid)
                    ? YcbtProtocol.parseModelResponse(frame)
                    : null;
            if (model != null) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "model probe response=" + model);
                requestBatteryQuery();
            }
            final Integer batteryLevel = YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(characteristicUuid)
                    ? YcbtProtocol.parseBatteryLevel(frame)
                    : null;
            if (batteryLevel != null) {
                final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
                batteryInfo.level = batteryLevel;
                handleGBDeviceEvent(batteryInfo);
                diagnostic(YcbtDiagnostics.TYPE_STAGE, "battery query response=" + batteryLevel + "%");
                requestCapabilityQuery();
            }
            final YcbtProtocol.Capabilities capabilities = YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(
                    characteristicUuid
            ) ? YcbtProtocol.parseCapabilities(frame) : null;
            if (capabilities != null) {
                diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                        Locale.ROOT,
                        "capability query response bloodPressure=%s temperature=%s findDevice=%s bloodSugar=%s",
                        capabilities.hasBloodPressure(),
                        capabilities.hasTemperature(),
                        capabilities.hasFindDevice(),
                        capabilities.hasBloodSugar()
                ));
            }
            LOG.info("Decoded YCBT frame from {}: group=0x{}, command=0x{}, payloadLength={}",
                    characteristicUuid,
                    String.format(Locale.ROOT, "%02x", frame.getGroup()),
                    String.format(Locale.ROOT, "%02x", frame.getCommand()),
                    frame.getPayload().length);
            diagnostic(YcbtDiagnostics.TYPE_STAGE, String.format(
                    Locale.ROOT,
                    "%s decoded group=0x%02x command=0x%02x payloadLength=%d",
                    characteristicName(characteristicUuid),
                    frame.getGroup(),
                    frame.getCommand(),
                    frame.getPayload().length
            ));
        }
        return true;
    }

    private void requestBatteryQuery() {
        if (batteryQueryRequested) {
            return;
        }
        batteryQueryRequested = true;

        final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                YcbtConstants.WRITE_CHARACTERISTIC_UUID
        );
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        final TransactionBuilder builder = createTransactionBuilder("YCBT battery query");
        builder.run(() -> diagnostic(
                YcbtDiagnostics.TYPE_STAGE,
                "battery query write request=0200080047436fec"
        ));
        builder.write(commandCharacteristic, YcbtProtocol.buildBatteryRequest());
        builder.queue();
    }

    private void requestCapabilityQuery() {
        synchronized (ConnectionMonitor) {
            if (capabilityQueryRequested || !isConnected()) {
                return;
            }

            final BluetoothGattCharacteristic commandCharacteristic = getCharacteristic(
                    YcbtConstants.WRITE_CHARACTERISTIC_UUID
            );
            if (commandCharacteristic == null) {
                diagnostic(YcbtDiagnostics.TYPE_FAILURE, "capability query command/reply missing");
                return;
            }
            commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            final TransactionBuilder builder = createTransactionBuilder("YCBT capability query");
            builder.run(() -> diagnostic(
                    YcbtDiagnostics.TYPE_STAGE,
                    "capability query write request=0201080047469b16"
            ));
            builder.write(commandCharacteristic, YcbtProtocol.buildCapabilityRequest());
            capabilityQueryRequested = true;
            builder.queue();
        }
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            LOG.warn("YCBT GATT disconnected with status {}", status);
            final String message = "disconnect status=" + status;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                diagnostic(YcbtDiagnostics.TYPE_FAILURE, message);
            }
            diagnostic(YcbtDiagnostics.TYPE_DISCONNECTED, message);
        }
    }

    private void diagnostic(final String eventType, final String message) {
        YcbtDiagnostics.emit(getContext(), getDevice().getAddress(), eventType, message);
    }

    private static String characteristicName(final UUID characteristicUuid) {
        if (YcbtConstants.COMMAND_REPLY_CHARACTERISTIC_UUID.equals(characteristicUuid)) {
            return "command/reply";
        }
        if (YcbtConstants.STREAM_HISTORY_CHARACTERISTIC_UUID.equals(characteristicUuid)) {
            return "stream/history";
        }
        return characteristicUuid.toString();
    }

    private static String serviceType(final int type) {
        if (type == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
            return "primary(" + type + ")";
        }
        if (type == BluetoothGattService.SERVICE_TYPE_SECONDARY) {
            return "secondary(" + type + ")";
        }
        return "unknown(" + type + ")";
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }
}
