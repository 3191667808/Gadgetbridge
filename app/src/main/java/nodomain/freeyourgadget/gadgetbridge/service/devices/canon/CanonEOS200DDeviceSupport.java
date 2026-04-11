package nodomain.freeyourgadget.gadgetbridge.service.devices.canon;

import static nodomain.freeyourgadget.gadgetbridge.service.btle.actions.WriteAction.writeCharacteristic;
import static nodomain.freeyourgadget.gadgetbridge.util.GB.hexStringToByteArray;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;

public class CanonEOS200DDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(CanonEOS200DDeviceSupport.class);

    // Standard pause for all Bluetooth operations
    private static final int STANDARD_PAUSE_MS = 100;

    // UUIDs for Services and Characteristics
    private static final UUID UUID_SERVICE_CANON_0 = UUID.fromString("00010000-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_SERVICE_CANON_1 = UUID.fromString("00020000-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_SERVICE_GPS = UUID.fromString("00040000-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_SERVICE_DEVICE_INFORMATION = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CHARACTERISTIC_0 = UUID.fromString("00010006-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_1 = UUID.fromString("0001000b-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_2 = UUID.fromString("00010005-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_3 = UUID.fromString("00020001-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_4 = UUID.fromString("00020003-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_5 = UUID.fromString("00020002-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_6 = UUID.fromString("00040001-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_GPS_REQUEST = UUID.fromString("00040003-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_8 = UUID.fromString("0001000a-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_9 = UUID.fromString("00020002-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_10 = UUID.fromString("00020004-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_11 = UUID.fromString("00020006-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_12 = UUID.fromString("00020005-0000-1000-0000-d8492fffa821");
    private static final UUID UUID_CHARACTERISTIC_GPS_RESPONSE = UUID.fromString("00040002-0000-1000-0000-d8492fffa821");

    // Constants for hex values used in write operations
    private static final String HEX_VALUE_INITIALIZE = "0336ebb49c6ff118996540308ddaacbaa4";
    private static final String HEX_VALUE_DEVICE_NAME = "04476164676574627269646765";
    private static final String HEX_VALUE_CONFIRMATION = "0502";
    private static final String HEX_VALUE_ENABLE_READS = "0a";
    private static final String HEX_VALUE_FINAL_CONFIRMATION = "01";

    // Default values for write operations
    private static final byte[] SMARTPHONE_NAME_VALUE = {
            0x01, 0x47, 0x61, 0x64, 0x67, 0x65, 0x74, 0x62, 0x72, 0x69, 0x64, 0x67, 0x65
    };

    private final DeviceInfoProfile deviceInfoProfile;

    public CanonEOS200DDeviceSupport() {
        super(LOG);
        addSupportedService(UUID_SERVICE_CANON_0);
        addSupportedService(UUID_SERVICE_DEVICE_INFORMATION);
        addSupportedService(UUID_SERVICE_CANON_1);
        addSupportedService(UUID_SERVICE_GPS);

        deviceInfoProfile = new DeviceInfoProfile(this);

        // Device Info Intent Listener
        IntentListener deviceInfoListener = intent -> {
            String action = intent.getAction();
            if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(action)) {
                DeviceInfo info = intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO);
                if (info == null) return;
                GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
                versionCmd.hwVersion = info.getHardwareRevision();
                versionCmd.fwVersion = info.getSoftwareRevision(); // Returns 1.0.0, but the camera settings show 1.1.0 – likely a firmware reporting issue
                LOG.info("Device Info: Hardware Revision: {}, Software Revision: {}", versionCmd.hwVersion, versionCmd.fwVersion);
                handleGBDeviceEvent(versionCmd);
            }
        };

        deviceInfoProfile.addListener(deviceInfoListener);
        addSupportedProfile(deviceInfoProfile);
    }

    @Override
    public boolean useAutoConnect() {
        return false; // When true the first connection is not possible
    }

    // Helper method for write operations
    private void sendWriteRequest(TransactionBuilder builder, UUID characteristicUuid, byte[] value) {
        BluetoothGattCharacteristic characteristic = getCharacteristic(characteristicUuid);
        if (characteristic == null) {
            LOG.warn("Write Characteristic not found: {}", characteristicUuid);
            return;
        }
        builder.write(characteristic, value);
        LOG.info("Write Request sent to characteristic: {}", characteristicUuid);
        builder.wait(STANDARD_PAUSE_MS);
    }

    // Helper method for read operations
    private void sendReadRequest(TransactionBuilder builder, UUID characteristicUuid) {
        BluetoothGattCharacteristic characteristic = getCharacteristic(characteristicUuid);
        if (characteristic == null) {
            LOG.warn("Read Characteristic not found: {}", characteristicUuid);
            return;
        }
        builder.read(characteristic);
        LOG.info("Read Request sent to characteristic: {}", characteristicUuid);
        builder.wait(STANDARD_PAUSE_MS);
    }

    // Helper method for notify operations
    private void sendNotifyRequest(TransactionBuilder builder, UUID characteristicUuid, boolean enable) {
        BluetoothGattCharacteristic characteristic = getCharacteristic(characteristicUuid);
        if (characteristic == null) {
            LOG.warn("Notify Characteristic not found: {}", characteristicUuid);
            return;
        }
        builder.notify(characteristic, enable);
        LOG.info("Notify Request sent to characteristic: {}", characteristicUuid);
        builder.wait(STANDARD_PAUSE_MS);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");
        deviceInfoProfile.requestDeviceInfo(builder);

        // Initialization sequence using helper methods
        sendWriteRequest(builder, UUID_CHARACTERISTIC_0, SMARTPHONE_NAME_VALUE); // Starts the connection process and sends a name (in this case "Gadgetbridge"), but this is for the connection.
        sendNotifyRequest(builder, UUID_CHARACTERISTIC_0, true);
        sendReadRequest(builder, UUID_CHARACTERISTIC_1);
        sendReadRequest(builder, UUID_CHARACTERISTIC_2);
        sendNotifyRequest(builder, UUID_CHARACTERISTIC_2, true);
        sendReadRequest(builder, UUID_CHARACTERISTIC_3);
        sendNotifyRequest(builder, UUID_CHARACTERISTIC_4, true);
        sendNotifyRequest(builder, UUID_CHARACTERISTIC_5, true);
        sendReadRequest(builder, UUID_CHARACTERISTIC_6);
        sendReadRequest(builder, UUID_CHARACTERISTIC_GPS_REQUEST); // Meaning unknown but, has probably to do with gps
        sendNotifyRequest(builder, UUID_CHARACTERISTIC_GPS_REQUEST, true); // Meaning unknown but, has probably to do with gps
        sendWriteRequest(builder, UUID_CHARACTERISTIC_8, hexStringToByteArray(HEX_VALUE_INITIALIZE)); // Required for successful connection, meaning unknown
        sendWriteRequest(builder, UUID_CHARACTERISTIC_8, hexStringToByteArray(HEX_VALUE_DEVICE_NAME)); // Sends the name again (Gadgetbridge), which is stored on the camera
        sendWriteRequest(builder, UUID_CHARACTERISTIC_8, hexStringToByteArray(HEX_VALUE_CONFIRMATION)); // Required for successful connection, meaning unknown
        sendWriteRequest(builder, UUID_CHARACTERISTIC_9, hexStringToByteArray(HEX_VALUE_ENABLE_READS)); // Required for the next three reads to return meaningful values instead of zeros
        sendReadRequest(builder, UUID_CHARACTERISTIC_10);
        sendReadRequest(builder, UUID_CHARACTERISTIC_11); // Returns "EOS200D-851_Canon0A.", meaning model EOS200D and 851_Canon0A, meaning unknown
        sendReadRequest(builder, UUID_CHARACTERISTIC_12);
        sendWriteRequest(builder, UUID_CHARACTERISTIC_8, hexStringToByteArray(HEX_VALUE_FINAL_CONFIRMATION)); // Likely a confirmation that everything is okay

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    private boolean isGpsAvailable() {
        // Implement logic to check if GPS data is available
        // Query GPS provider and check a setting
        return true; // Placeholder
    }

    private byte[] getGpsData() {
        // Example values for latitude, longitude, altitude, and timestamp
        // These values should be replaced with actual GPS data
        float latitude = 15; // Example: 15.000000°
        float longitude = 10; // Example: 10.000000°
        float altitude = 100; // Example: 100 meters (positive)
        int timestamp = (int) (System.currentTimeMillis() / 1000L); // Current Unix timestamp

        // Buffer for the GPS data
        ByteBuffer buffer = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN);

        // Start byte
        buffer.put((byte) 0x04);

        // Latitude (W)
        buffer.put((byte) 0x4E);
        buffer.putFloat(latitude);

        // Longitude (E)
        buffer.put((byte) 0x45);
        buffer.putFloat(longitude);

        // Altitude (positive)
        buffer.put((byte) 0x2B); //2D for negative
        buffer.putFloat(altitude);

        // Unix timestamp
        buffer.putInt(timestamp);

        return buffer.array();
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        boolean handled = super.onCharacteristicChanged(gatt, characteristic, value);

        if (characteristic.getUuid().equals(UUID_CHARACTERISTIC_GPS_REQUEST)) {
            if (value != null && value.length > 0) {
                BluetoothGattCharacteristic responseChar = getCharacteristic(UUID_CHARACTERISTIC_GPS_RESPONSE);

                if (value[0] == 0x03) {
                    if (isGpsAvailable()) {
                        writeCharacteristic(gatt, responseChar, new byte[]{0x01});
                    } else {
                        writeCharacteristic(gatt, responseChar, new byte[]{0x02});
                    }
                    return true;
                } else if (value[0] == 0x02) {
                    byte[] gpsData = getGpsData();
                    writeCharacteristic(gatt, responseChar, gpsData);
                    return true;
                }
            }
        }

        return handled;
    }
}

