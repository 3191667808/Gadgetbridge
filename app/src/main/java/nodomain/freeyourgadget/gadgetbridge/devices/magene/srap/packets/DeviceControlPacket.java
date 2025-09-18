package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the DEVICE_CONTROL (0xF2) page.
 * This is used for various device-specific operations like setting names.
 */
public final class DeviceControlPacket {

    private DeviceControlPacket() {
        // This class is a namespace and should not be instantiated.
    }

    /**
     * Namespace for commands to set the Bluetooth and Device names (f2:f2, functionId 4).
     */
    public static final class SetBtAndDeviceName {

        public static final int FUNCTION_ID_SET_NAMES = 4;

        private SetBtAndDeviceName() {
            // This class is a namespace and should not be instantiated.
        }

        /**
         * Represents a WRITE command to set the Bluetooth and Device names.
         */
        public static class Write extends AbstractSRAPMessage {
            private final byte nodeAddress;

            private final byte[] payload;

            public Write(byte nodeAddress, String bluetoothName, String deviceName) {
                this.nodeAddress = nodeAddress;

                this.payload = buildPayload(bluetoothName, deviceName);
            }

            private byte[] buildPayload(String bluetoothName, String deviceName) {
                byte[] btNameBytes = bluetoothName.getBytes(StandardCharsets.UTF_8);
                byte[] devNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);

                try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                    // 1 byte: Function Identifier
                    stream.write(FUNCTION_ID_SET_NAMES);

                    // Bluetooth Name (Length + Data)
                    stream.write(btNameBytes.length);
                    stream.write(btNameBytes);

                    // Device Name (Length + Data)
                    stream.write(devNameBytes.length);
                    stream.write(devNameBytes);

                    return stream.toByteArray();
                } catch (IOException e) {
                    // This should not happen with ByteArrayOutputStream
                    throw new RuntimeException("Failed to build SetBtAndDeviceName payload", e);
                }
            }

            @Override
            public byte getNodeAddress() { return nodeAddress; }
            @Override
            public FunctionCode getFunctionCode() { return FunctionCode.WRITE; }
            @Override
            public ResourceType getResourceType() { return ResourceType.MAIN; }
            @Override
            public PageNumber getPageNumber() { return PageNumber.DEVICE_CONTROL; }
            @Override
            public byte[] getPayload() { return payload; }
        }

        /**
         * Represents a RESPONSE for the Set BT/Device Name command (f5:f2).
         * The device echoes back the same payload as the write command.
         */
        public static class Response {
            private final int functionId;
            private final String bluetoothName;
            private final String deviceName;

            public Response(byte[] payload) {
                if (payload == null || payload.length < 3) { // Min: funcId + len + len
                    throw new IllegalArgumentException("Payload for SetBtAndDeviceName Response is too short.");
                }

                ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

                this.functionId = buffer.get() & 0xFF;

                int btNameLen = buffer.get() & 0xFF;
                byte[] btNameBytes = new byte[btNameLen];
                buffer.get(btNameBytes);
                this.bluetoothName = new String(btNameBytes, StandardCharsets.UTF_8);

                int devNameLen = buffer.get() & 0xFF;
                byte[] devNameBytes = new byte[devNameLen];
                if (buffer.remaining() >= devNameLen) {
                    buffer.get(devNameBytes);
                    this.deviceName = new String(devNameBytes, StandardCharsets.UTF_8);
                } else {
                    this.deviceName = "";
                }
            }

            public int getFunctionId() { return functionId; }
            public String getBluetoothName() { return bluetoothName; }
            public String getDeviceName() { return deviceName; }

            @Override
            public String toString() {
                return "SetBtAndDeviceName.Response{functionId=" + functionId + ", bluetoothName='" + bluetoothName + "', deviceName='" + deviceName + "'}";
            }
        }
    }
}
